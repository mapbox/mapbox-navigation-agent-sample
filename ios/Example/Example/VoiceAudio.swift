import AVFoundation
import Foundation
import MapboxNavigationAgent
import os

/// Captures microphone audio as 24 kHz PCM and plays the model's PCM replies.
/// The engine is driven from its own queue, so this type stays off the main actor.
nonisolated final class VoiceAudio: @unchecked Sendable {
    private static let sampleRate = Double(LiveVoiceSession.sampleRate)
    private static let log = Logger(subsystem: "MapboxNavigationAgent", category: "voice")

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var queue: DispatchQueue { VoiceFloorCoordinator.shared.queue }
    private var started = false
    private var didLogPlayback = false
    private var tapInstalled = false
    private var pending: [Data] = []
    private var held: [Data] = []
    private var chunkHandler: (@Sendable (Data) -> Void)?
    private(set) var sessionId: String?
    private(set) var acceptsPlayback = false
    private(set) var isParked = false
    private(set) var isWaitingForFloor = false
    private(set) var isHoldingFloor = false
    private(set) var isKnownToFloor = false
    private var resumptionObserver: NotificationCenter.ObservationToken?
    private var configurationObserver: NSObjectProtocol?

    func start(_ onChunk: @escaping @Sendable (Data) -> Void) throws {
        try queue.sync { try startOnQueue(onChunk) }
    }

    /// `.voiceChat` keeps playback on the receiver, and `defaultToSpeaker` does not override it.
    /// `.videoChat` keeps echo cancellation and routes the reply to the loudspeaker.
    private func startOnQueue(_ onChunk: @escaping @Sendable (Data) -> Void) throws {
        chunkHandler = onChunk
        sessionId = UUID().uuidString
        acceptsPlayback = true
        isParked = false
        isWaitingForFloor = false
        isHoldingFloor = false
        isKnownToFloor = false
        VoiceFloorCoordinator.shared.attach(self)
        try configureSession()
        try startEngine(onChunk)
        observeAudioChanges()
        started = true
        let queued = pending
        pending.removeAll()
        for pcm in queued { VoiceFloorCoordinator.shared.enqueueAssistantAudio(pcm, on: self) }
    }

    private func configureSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            .playAndRecord,
            mode: .videoChat,
            options: [.defaultToSpeaker, .allowBluetoothHFP]
        )
        try session.setActive(true)
        try session.overrideOutputAudioPort(.speaker)
    }

    private func startEngine(_ onChunk: @escaping @Sendable (Data) -> Void) throws {
        let input = engine.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, let playbackFormat = Self.playbackFormat else {
            throw VoiceAudioError.unsupportedFormat
        }
        if tapInstalled {
            input.removeTap(onBus: 0)
            tapInstalled = false
        }
        if player.engine == nil {
            engine.attach(player)
        }
        try engine.connectNode(player, to: engine.mainMixerNode, format: playbackFormat)
        try input.installAudioTap(onBus: 0, bufferSize: 2_400, format: inputFormat) { buffer, _ in
            guard var pcm = Self.pcm16(from: AVAudioPCMBuffer(copying: buffer)) else { return }
            // A maneuver is speaking. Keep the capture cadence and send silence, so the model
            // does not hear the instruction and answer over it.
            if VoiceFloorCoordinator.shared.inputMuted {
                pcm.resetBytes(in: pcm.startIndex..<pcm.endIndex)
            }
            onChunk(pcm)
        }
        tapInstalled = true
        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            tapInstalled = false
            throw error
        }
    }

    /// Guidance and other clients can stop the engine without closing the voice socket.
    private func observeAudioChanges() {
        guard resumptionObserver == nil else { return }
        let center = NotificationCenter.default
        resumptionObserver = center.addObserver(of: AVAudioSession.self, for: .resumptionRecommendation) { [weak self] message in
            guard message.recommendation == .shouldResume, let audio = self else { return }
            audio.queue.async { [weak audio] in
                audio?.restoreEngine(reason: "an audio interruption")
            }
        }
        configurationObserver = center.addObserver(
            forName: .AVAudioEngineConfigurationChange,
            object: engine,
            queue: nil
        ) { [weak self] _ in
            guard let audio = self else { return }
            audio.queue.async { [weak audio] in
                audio?.restoreEngine(reason: "an audio configuration change")
            }
        }
    }

    private func restoreEngine(reason: String) {
        guard started, !engine.isRunning, let chunkHandler else { return }
        do {
            try configureSession()
            try startEngine(chunkHandler)
            Self.log.info("Restored the microphone after \(reason, privacy: .public)")
        } catch {
            Self.log.error("Could not restore the microphone after \(reason, privacy: .public): \(error.localizedDescription, privacy: .public)")
        }
    }

    private func removeAudioObservers() {
        let center = NotificationCenter.default
        if let resumptionObserver {
            center.removeObserver(resumptionObserver)
            self.resumptionObserver = nil
        }
        if let configurationObserver {
            center.removeObserver(configurationObserver)
            self.configurationObserver = nil
        }
    }

    func play(_ pcm: Data) {
        queue.async {
            guard self.started else {
                self.pending.append(pcm)
                return
            }
            VoiceFloorCoordinator.shared.enqueueAssistantAudio(pcm, on: self)
        }
    }

    func hold(_ pcm: Data) {
        held.append(pcm)
    }

    func markWaiting() {
        isWaitingForFloor = true
        isHoldingFloor = false
        isKnownToFloor = true
    }

    func markHolding() {
        isWaitingForFloor = false
        isHoldingFloor = true
        isKnownToFloor = true
    }

    /// The maneuver owns the speaker. Pause keeps the buffers for the resume.
    func parkAssistant() {
        isParked = true
        isHoldingFloor = false
        if player.isPlaying { player.pause() }
    }

    /// The maneuver has left the floor. Play what arrived while it was speaking, then continue.
    func resumeAssistant() {
        guard acceptsPlayback else {
            isParked = false
            isWaitingForFloor = false
            held.removeAll()
            return
        }
        isParked = false
        isWaitingForFloor = false
        isHoldingFloor = true
        isKnownToFloor = true
        let queued = held
        held.removeAll()
        for pcm in queued { schedule(pcm) }
        if started { playAssistant() }
    }

    func discardAssistantAudio() {
        isParked = false
        isWaitingForFloor = false
        isHoldingFloor = false
        isKnownToFloor = false
        held.removeAll()
        player.stop()
    }

    func schedule(_ pcm: Data) {
        guard let format = Self.playbackFormat,
              pcm.count >= 2,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(pcm.count / 2)),
              let channel = buffer.floatChannelData?[0]
        else { return }
        buffer.frameLength = AVAudioFrameCount(pcm.count / 2)
        pcm.withUnsafeBytes { raw in
            let samples = raw.bindMemory(to: Int16.self)
            for index in 0..<Int(buffer.frameLength) {
                channel[index] = Float(samples[index]) / 32_768
            }
        }
        if !didLogPlayback {
            didLogPlayback = true
            Self.log.info("Playing assistant audio, \(pcm.count) bytes")
        }
        player.scheduleBuffer(buffer)
        playAssistant()
    }

    private func playAssistant() {
        guard !player.isPlaying else { return }
        do {
            try player.playAudio()
        } catch {
            Self.log.error("Could not play assistant audio: \(error.localizedDescription, privacy: .public)")
        }
    }

    private static var playbackFormat: AVAudioFormat? {
        AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate, channels: 1, interleaved: false)
    }

    func stop() {
        queue.sync {
            let wasStarted = started
            let sessionId = self.sessionId
            started = false
            acceptsPlayback = false
            self.sessionId = nil
            pending.removeAll()
            held.removeAll()
            isParked = false
            isWaitingForFloor = false
            isHoldingFloor = false
            isKnownToFloor = false
            didLogPlayback = false
            chunkHandler = nil
            removeAudioObservers()
            if let sessionId {
                VoiceFloorCoordinator.shared.releaseAssistant(sessionId: sessionId)
            }
            guard wasStarted else { return }
            if tapInstalled {
                engine.inputNode.removeTap(onBus: 0)
                tapInstalled = false
            }
            player.stop()
            engine.stop()
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    private static func pcm16(from buffer: AVAudioPCMBuffer) -> Data? {
        guard let channel = buffer.floatChannelData?[0] else { return nil }
        let frames = Int(buffer.frameLength)
        guard frames > 0 else { return nil }
        let ratio = buffer.format.sampleRate / Self.sampleRate
        let count = max(1, Int(Double(frames) / ratio))
        var data = Data(count: count * 2)
        data.withUnsafeMutableBytes { raw in
            let samples = raw.bindMemory(to: Int16.self)
            for index in 0..<count {
                let source = min(frames - 1, Int(Double(index) * ratio))
                let sample = max(-1, min(1, channel[source]))
                samples[index] = Int16(sample * (sample < 0 ? 32_768 : 32_767))
            }
        }
        return data
    }
}

enum VoiceAudioError: LocalizedError {
    case unsupportedFormat

    var errorDescription: String? { "This device cannot capture voice audio." }
}
