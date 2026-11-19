/// Which player the app should start, pause, or drop for one floor call.
nonisolated struct VoiceFloorDecision: Equatable {
    var pauseAssistant: Bool
    var resumeAssistant: Bool
    var speakGuidance: Bool
    var speakAssistant: Bool
    var discardAssistant: Bool

    init(
        pauseAssistant: Bool = false,
        resumeAssistant: Bool = false,
        speakGuidance: Bool = false,
        speakAssistant: Bool = false,
        discardAssistant: Bool = false
    ) {
        self.pauseAssistant = pauseAssistant
        self.resumeAssistant = resumeAssistant
        self.speakGuidance = speakGuidance
        self.speakAssistant = speakAssistant
        self.discardAssistant = discardAssistant
    }
}

/// One maneuver and the assistant reply are never granted together. Guidance wins.
/// Not thread-safe. Callers confine every call to one queue.
nonisolated final class VoiceFloor {
    private let arbiter = VoiceFloorArbiter()

    init() {}

    func requestGuidance(utteranceId: String) -> VoiceFloorDecision {
        decide(arbiter.request(.guidance, utteranceId: utteranceId))
    }

    func releaseGuidance(utteranceId: String) -> VoiceFloorDecision {
        decide(arbiter.release(.guidance, utteranceId: utteranceId))
    }

    func requestAssistant(utteranceId: String) -> VoiceFloorDecision {
        decide(arbiter.request(.assistant, utteranceId: utteranceId))
    }

    func releaseAssistant(utteranceId: String) -> VoiceFloorDecision {
        decide(arbiter.release(.assistant, utteranceId: utteranceId))
    }

    /// True while a maneuver owns the floor, including the moment after a stale release.
    var guidanceIsSpeaking: Bool {
        arbiter.holder == .guidance
    }

    private func decide(_ effects: [FloorEffect]) -> VoiceFloorDecision {
        var decision = VoiceFloorDecision()
        for effect in effects {
            switch effect {
            case .pause(.assistant):
                decision.pauseAssistant = true
            case .resume(.assistant, _):
                decision.resumeAssistant = true
            case .speak(.guidance, _):
                decision.speakGuidance = true
            case .speak(.assistant, _):
                decision.speakAssistant = true
            case .discard(.assistant, _):
                decision.discardAssistant = true
            case .pause, .resume, .speak, .discard:
                break
            }
        }
        return decision
    }
}

nonisolated private enum VoiceStream: Equatable {
    case guidance
    case assistant
}

nonisolated private enum FloorEffect: Equatable {
    case speak(VoiceStream, String)
    case pause(VoiceStream)
    case resume(VoiceStream, String)
    case discard(VoiceStream, String)
}

/// Serialises guidance and the assistant so only one speaks at a time.
nonisolated private final class VoiceFloorArbiter {
    private struct Utterance: Equatable {
        var stream: VoiceStream
        var id: String
    }

    private var current: Utterance?
    private var parked: Utterance?
    private var queued: Utterance?

    var holder: VoiceStream? { current?.stream }

    func request(_ stream: VoiceStream, utteranceId: String) -> [FloorEffect] {
        let incoming = Utterance(stream: stream, id: utteranceId)
        guard let holderNow = current, holderNow.stream != stream else {
            return takeFloor(incoming)
        }
        if stream == .guidance {
            return preempt(incoming)
        }
        return enqueue(incoming)
    }

    func release(_ stream: VoiceStream, utteranceId: String) -> [FloorEffect] {
        if let holderNow = current, holderNow.stream == stream, holderNow.id == utteranceId {
            current = nil
            return resumeParkedOrServeQueue()
        }
        return withdraw(stream, utteranceId: utteranceId)
    }

    private func takeFloor(_ incoming: Utterance) -> [FloorEffect] {
        let displaced = current.flatMap { $0.id == incoming.id ? nil : $0 }
        current = incoming
        var effects: [FloorEffect] = []
        if let displaced {
            effects.append(.discard(displaced.stream, displaced.id))
        }
        effects.append(.speak(incoming.stream, incoming.id))
        return effects
    }

    private func enqueue(_ incoming: Utterance) -> [FloorEffect] {
        let superseded = queued.flatMap { $0.stream == incoming.stream ? $0 : nil }
        queued = incoming
        if let superseded {
            return [.discard(superseded.stream, superseded.id)]
        }
        return []
    }

    private func withdraw(_ stream: VoiceStream, utteranceId: String) -> [FloorEffect] {
        if let park = parked, park.stream == stream, park.id == utteranceId {
            parked = nil
            return [.discard(park.stream, park.id)]
        }
        if let waiting = queued, waiting.stream == stream, waiting.id == utteranceId {
            queued = nil
        }
        return []
    }

    private func preempt(_ incoming: Utterance) -> [FloorEffect] {
        guard let victim = current else { return [] }
        let evicted = queued
        current = incoming
        queued = nil
        parked = victim
        var effects: [FloorEffect] = []
        if let evicted {
            effects.append(.discard(evicted.stream, evicted.id))
        }
        effects.append(.pause(victim.stream))
        effects.append(.speak(incoming.stream, incoming.id))
        return effects
    }

    private func resumeParkedOrServeQueue() -> [FloorEffect] {
        guard let park = parked else { return serveQueue() }
        parked = nil
        current = park
        return [.resume(park.stream, park.id)]
    }

    private func serveQueue() -> [FloorEffect] {
        guard let next = queued else { return [] }
        queued = nil
        current = next
        return [.speak(next.stream, next.id)]
    }
}
