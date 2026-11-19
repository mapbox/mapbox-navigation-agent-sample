import Foundation
import os

/// One queue for the floor and the assistant player, so a maneuver and a reply cannot pass each other.
nonisolated final class VoiceFloorCoordinator: @unchecked Sendable {
    static let shared = VoiceFloorCoordinator()

    let queue = DispatchQueue(label: "MapboxNavigationAgent.VoiceFloor")
    private static let log = Logger(subsystem: "MapboxNavigationAgent", category: "VoiceFloor")
    private static let queueKey = DispatchSpecificKey<UInt8>()

    private let floor = VoiceFloor()
    private let muteLock = NSLock()
    private var guidanceMuted = false
    private weak var audio: VoiceAudio?

    private init() {
        queue.setSpecific(key: Self.queueKey, value: 1)
    }

    /// Runs on the floor queue. A caller that is already on it must not `sync` again.
    private func sync<T>(_ body: () throws -> T) rethrows -> T {
        if DispatchQueue.getSpecific(key: Self.queueKey) == 1 {
            return try body()
        }
        return try queue.sync(execute: body)
    }

    /// The capture tap reads this off the audio thread. Either source of mute is enough to zero the mic.
    var inputMuted: Bool {
        muteLock.lock()
        defer { muteLock.unlock() }
        return guidanceMuted
    }

    func attach(_ audio: VoiceAudio) {
        sync { self.audio = audio }
    }

    /// True when this maneuver may speak now. The assistant player is already paused.
    func requestGuidance(utteranceId: String) -> Bool {
        sync {
            let decision = floor.requestGuidance(utteranceId: utteranceId)
            apply(decision)
            setInputMuted(floor.guidanceIsSpeaking)
            if decision.speakGuidance {
                Self.log.info("Guidance took the floor \(utteranceId, privacy: .public)")
            }
            return decision.speakGuidance
        }
    }

    func releaseGuidance(utteranceId: String) {
        sync {
            let decision = floor.releaseGuidance(utteranceId: utteranceId)
            apply(decision)
            setInputMuted(floor.guidanceIsSpeaking)
            Self.log.info("Guidance released the floor \(utteranceId, privacy: .public)")
        }
    }

    func enqueueAssistantAudio(_ pcm: Data, on audio: VoiceAudio) {
        let work: @Sendable () -> Void = { self.enqueueOnQueue(pcm, on: audio) }
        if DispatchQueue.getSpecific(key: Self.queueKey) == 1 {
            work()
        } else {
            queue.async(execute: work)
        }
    }

    private func enqueueOnQueue(_ pcm: Data, on audio: VoiceAudio) {
        guard audio.acceptsPlayback else { return }
        if audio.isParked || audio.isWaitingForFloor {
            if !audio.isKnownToFloor {
                registerAssistant(pcm, on: audio)
            } else {
                audio.hold(pcm)
            }
            return
        }
        if !audio.isHoldingFloor {
            registerAssistant(pcm, on: audio)
            return
        }
        audio.schedule(pcm)
    }

    /// One registration per voice session. A second request for the same reply would discard the audio the floor is already holding.
    private func registerAssistant(_ pcm: Data, on audio: VoiceAudio) {
        guard let sessionId = audio.sessionId else { return }
        let decision = floor.requestAssistant(utteranceId: sessionId)
        apply(decision)
        setInputMuted(floor.guidanceIsSpeaking)
        if decision.speakAssistant {
            audio.markHolding()
            audio.schedule(pcm)
            return
        }
        audio.markWaiting()
        audio.hold(pcm)
    }

    func releaseAssistant(sessionId: String) {
        sync {
            let decision = floor.releaseAssistant(utteranceId: sessionId)
            apply(decision)
            setInputMuted(floor.guidanceIsSpeaking)
        }
    }

    private func apply(_ decision: VoiceFloorDecision) {
        if decision.discardAssistant {
            audio?.discardAssistantAudio()
        }
        // Park on every granted maneuver, including one that found the floor empty while a reply
        // was still audible. A reply that no longer holds the floor must still yield.
        if decision.pauseAssistant || decision.speakGuidance {
            audio?.parkAssistant()
        }
        if decision.resumeAssistant || decision.speakAssistant {
            audio?.resumeAssistant()
        }
    }

    private func setInputMuted(_ muted: Bool) {
        muteLock.lock()
        guidanceMuted = muted
        muteLock.unlock()
    }
}
