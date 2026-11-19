import Combine
import Foundation
import MapboxDirections
import MapboxNavigationCore
import os

/// Holds each spoken instruction until the voice floor grants it, so a maneuver and a reply
/// are never audible at once.
///
/// Decorates the SDK synthesizer. Mapbox voice, prefetch, and the offline fallback stay in place.
/// Only the moment `speak` reaches that synthesizer is ours.
@MainActor
final class GuidanceSpeechArbiter: SpeechSynthesizing {
    private static let log = Logger(subsystem: "MapboxNavigationAgent", category: "VoiceFloor")
    /// Mapbox emits DidSpeak before the utterance has fully cleared the speaker.
    private static let drainSeconds: TimeInterval = 1
    /// interruptSpeaking() finishes asynchronously. The next instruction waits this out.
    private static let interruptDrainSeconds: TimeInterval = 0.15
    /// A maneuver that never reports completion must not park the assistant for the rest of the trip.
    private static let grantTimeoutSeconds: TimeInterval = 30

    private let events = PassthroughSubject<VoiceInstructionEvent, Never>()
    private var delegateSynthesizer: (any SpeechSynthesizing)?
    private var delegateSubscription: AnyCancellable?
    private var pendingUtteranceId: String?
    private var holding: (id: String, instruction: SpokenInstruction)?
    /// The utterance that currently owns the floor, including the second after DidSpeak
    /// while the maneuver is still clearing the speaker. `holding` is cleared sooner.
    private var grantedUtteranceId: String?
    private var interruptDrainUntil: Date?
    private var grantTimeouts: [String: Task<Void, Never>] = [:]

    var voiceInstructions: AnyPublisher<VoiceInstructionEvent, Never> {
        events.eraseToAnyPublisher()
    }

    var muted: Bool = false {
        didSet {
            delegateSynthesizer?.muted = muted
            if muted { releaseEverythingHeld() }
        }
    }

    var volume: VolumeMode = .system {
        didSet { delegateSynthesizer?.volume = volume }
    }

    var isSpeaking: Bool { delegateSynthesizer?.isSpeaking ?? false }

    var locale: Locale? {
        didSet { delegateSynthesizer?.locale = locale }
    }

    /// The assistant owns the shared session. Letting the SDK switch it to playback stops the mic.
    var managesAudioSession: Bool = false {
        didSet { delegateSynthesizer?.managesAudioSession = managesAudioSession }
    }

    func attach(delegate: any SpeechSynthesizing) {
        delegateSynthesizer = delegate
        delegate.muted = muted
        delegate.volume = volume
        delegate.managesAudioSession = managesAudioSession
        if let locale { delegate.locale = locale }
        delegateSubscription = delegate.voiceInstructions.sink { [weak self] event in
            MainActor.assumeIsolated { self?.forward(event) }
        }
    }

    func prepareIncomingSpokenInstructions(_ instructions: [SpokenInstruction], locale: Locale?) {
        delegateSynthesizer?.prepareIncomingSpokenInstructions(instructions, locale: locale)
    }

    func speak(_ instruction: SpokenInstruction, during legProgress: RouteLegProgress, locale: Locale?) {
        guard !muted else {
            releaseEverythingHeld()
            return
        }
        guard let delegate = delegateSynthesizer else {
            Self.log.error("speak before attach(delegate:) — dropping instruction")
            return
        }
        if holding != nil {
            delegate.interruptSpeaking()
            holding = nil
            interruptDrainUntil = Date().addingTimeInterval(Self.interruptDrainSeconds)
        } else if pendingUtteranceId != nil {
            delegate.interruptSpeaking()
        }
        let utteranceId = UUID().uuidString
        pendingUtteranceId = utteranceId
        let begin = { [weak self] in
            MainActor.assumeIsolated {
                self?.beginSpeaking(
                    utteranceId: utteranceId,
                    instruction: instruction,
                    legProgress: legProgress,
                    locale: locale,
                    delegate: delegate
                )
            }
        }
        if let until = interruptDrainUntil, until > Date() {
            let remaining = until.timeIntervalSinceNow
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(max(remaining, 0) * 1_000_000_000))
                begin()
            }
        } else {
            begin()
        }
    }

    func stopSpeaking() {
        delegateSynthesizer?.stopSpeaking()
        releaseEverythingHeld()
    }

    func interruptSpeaking() {
        delegateSynthesizer?.interruptSpeaking()
        releaseEverythingHeld()
    }

    private func beginSpeaking(
        utteranceId: String,
        instruction: SpokenInstruction,
        legProgress: RouteLegProgress,
        locale: Locale?,
        delegate: any SpeechSynthesizing
    ) {
        guard pendingUtteranceId == utteranceId else { return }
        let granted = VoiceFloorCoordinator.shared.requestGuidance(utteranceId: utteranceId)
        guard granted, pendingUtteranceId == utteranceId else { return }
        pendingUtteranceId = nil
        grantedUtteranceId = utteranceId
        holding = (utteranceId, instruction)
        armGrantTimeout(utteranceId)
        delegate.speak(instruction, during: legProgress, locale: locale)
    }

    private func forward(_ event: VoiceInstructionEvent) {
        events.send(event)
        switch event {
        case let spoken as VoiceInstructionEvents.DidSpeak:
            guard holding?.instruction == spoken.instruction, let id = holding?.id else { return }
            releaseAfterDrain(id)
        case let interrupted as VoiceInstructionEvents.DidInterrupt:
            // DidInterrupt names the utterance that was cut off. The one now speaking holds the floor.
            guard holding?.instruction == interrupted.interruptedInstruction, let id = holding?.id else { return }
            releaseNow(id)
        case is VoiceInstructionEvents.EncounteredError:
            // A fallback synthesizer still speaks and later emits DidSpeak. Releasing here would
            // resume the assistant under the maneuver.
            guard !isSpeaking, let id = holding?.id else { return }
            releaseNow(id)
        default:
            break
        }
    }

    private func armGrantTimeout(_ utteranceId: String) {
        grantTimeouts[utteranceId]?.cancel()
        grantTimeouts[utteranceId] = Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(Self.grantTimeoutSeconds * 1_000_000_000))
            guard self.holding?.id == utteranceId else { return }
            self.releaseNow(utteranceId)
        }
    }

    private func releaseAfterDrain(_ utteranceId: String) {
        guard holding?.id == utteranceId else { return }
        holding = nil
        grantTimeouts[utteranceId]?.cancel()
        grantTimeouts[utteranceId] = nil
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(Self.drainSeconds * 1_000_000_000))
            self.releaseNow(utteranceId)
        }
    }

    private func releaseNow(_ utteranceId: String) {
        grantTimeouts[utteranceId]?.cancel()
        grantTimeouts[utteranceId] = nil
        if holding?.id == utteranceId { holding = nil }
        if pendingUtteranceId == utteranceId { pendingUtteranceId = nil }
        if grantedUtteranceId == utteranceId { grantedUtteranceId = nil }
        VoiceFloorCoordinator.shared.releaseGuidance(utteranceId: utteranceId)
    }

    private func releaseEverythingHeld() {
        pendingUtteranceId = nil
        holding = nil
        if let granted = grantedUtteranceId {
            releaseNow(granted)
        }
    }
}
