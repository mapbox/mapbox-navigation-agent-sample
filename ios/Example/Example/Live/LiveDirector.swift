import Foundation

nonisolated protocol LiveClock: Sendable {
    func now() -> TimeInterval
    func sleep(for seconds: TimeInterval) async throws
}

private struct SystemLiveClock: LiveClock {
    func now() -> TimeInterval {
        ProcessInfo.processInfo.systemUptime
    }

    func sleep(for seconds: TimeInterval) async throws {
        try await Task.sleep(for: .seconds(seconds))
    }
}

nonisolated struct VoiceToolSpec: Equatable, Sendable {
    var name: String
    var description: String
    private let parametersJSON: String

    var parameters: [String: Any] {
        guard let data = parametersJSON.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        return object
    }

    init(name: String, description: String, parameters: [String: Any] = [:]) {
        self.name = name
        self.description = description
        let data = (try? JSONSerialization.data(withJSONObject: parameters)) ?? Data("{}".utf8)
        self.parametersJSON = String(decoding: data, as: UTF8.self)
    }
}

nonisolated struct LiveDelegation: Equatable, Sendable {
    var model: String
    var instructions: String
    var tools: [VoiceToolSpec]
    var toolChoice: String
    var parallelToolCalls: Bool
    var reasoningEffort: String
}

nonisolated final class LiveDirector: Sendable {
    let liveInstructions: String
    let delegation: LiveDelegation

    private let registry: VoiceToolRegistry
    private let clock: any LiveClock
    private let nextEventID: @Sendable () -> String

    init(
        registry: VoiceToolRegistry,
        clock: any LiveClock = SystemLiveClock(),
        nextEventID: @escaping @Sendable () -> String = { "evt_\(UUID().uuidString)" }
    ) {
        self.registry = registry
        self.clock = clock
        self.nextEventID = nextEventID
        self.liveInstructions = LivePrompts.liveInstructions(toolNames: registry.tools.map(\.name))
        self.delegation = LiveDelegation(
            model: "gpt-5.6-terra",
            instructions: LivePrompts.backendInstructions(),
            tools: LivePrompts.tools(for: registry),
            toolChoice: "auto",
            parallelToolCalls: true,
            reasoningEffort: "low"
        )
    }

    func session(
        send: @escaping @Sendable (String) -> Void,
        onEndConversation: @escaping @Sendable () -> Void,
        onToolCall: @escaping @Sendable (String) -> Void
    ) -> LiveDirectorSession {
        LiveDirectorSession(
            registry: registry,
            clock: clock,
            nextEventID: nextEventID,
            send: send,
            onEndConversation: onEndConversation,
            onToolCall: onToolCall
        )
    }
}

nonisolated final class LiveDirectorSession: @unchecked Sendable {
    private struct PendingCall: Sendable {
        var name: String
        var callID: String
        var arguments: String
    }

    private struct CallResult: Sendable {
        var callID: String
        var output: String
        var failed: Bool
    }

    private let registry: VoiceToolRegistry
    private let clock: any LiveClock
    private let nextEventID: @Sendable () -> String
    private let send: @Sendable (String) -> Void
    private let onEndConversation: @Sendable () -> Void
    private let onToolCall: @Sendable (String) -> Void

    private let lock = NSLock()
    private var pending: [PendingCall] = []
    private var rounds = 0
    private var lastAssistantSpeech: TimeInterval
    private var goodbyeTask: Task<Void, Never>?
    private var toolTasks: [Task<Void, Never>] = []
    private var closed = false
    private var acceptingContext = false
    private var lastContext: VoiceContext?
    private var navigationEnded = false

    private static let maxRounds = 6
    private static let goodbyeQuiet: TimeInterval = 1.5
    private static let goodbyeMaxWait: TimeInterval = 8
    private static let endConversationToolName = "end_conversation"
    private static let endConversationOutput = "Ending the conversation now."
    private static let thinkingAppendLimit = 600
    private static let navigationEndedNote =
        "Navigation ended: there is no active route or destination now. Any trip mentioned earlier in this conversation is over; start a new one only if the user asks."

    fileprivate init(
        registry: VoiceToolRegistry,
        clock: any LiveClock,
        nextEventID: @escaping @Sendable () -> String,
        send: @escaping @Sendable (String) -> Void,
        onEndConversation: @escaping @Sendable () -> Void,
        onToolCall: @escaping @Sendable (String) -> Void
    ) {
        self.registry = registry
        self.clock = clock
        self.nextEventID = nextEventID
        self.send = send
        self.onEndConversation = onEndConversation
        self.onToolCall = onToolCall
        self.lastAssistantSpeech = clock.now()
    }

    func onServerEvent(_ frame: String) {
        guard let object = jsonObject(frame), let type = object["type"] as? String else { return }
        if type == "session.output_transcript.delta" {
            noteAssistantSpeech()
            return
        }
        if type == "session.started" {
            noteContextAccepted()
            return
        }
        guard type == "response.event", let event = object["event"] as? [String: Any] else { return }
        switch event["type"] as? String {
        case "response.created":
            clearPending()
        case "response.output_item.done":
            if let call = functionCall(event) {
                appendPending(call)
            }
        case "response.completed", "response.failed", "response.incomplete":
            finishRound(event)
        default:
            break
        }
    }

    func updateContext(_ context: VoiceContext) {
        let frames = framesForContextUpdate(context)
        frames.forEach(send)
    }

    func close() {
        let goodbye: Task<Void, Never>?
        let tools: [Task<Void, Never>]
        lock.lock()
        closed = true
        goodbye = goodbyeTask
        tools = toolTasks
        goodbyeTask = nil
        toolTasks.removeAll()
        lock.unlock()
        goodbye?.cancel()
        tools.forEach { $0.cancel() }
    }

    private func finishRound(_ event: [String: Any]) {
        let response = event["response"] as? [String: Any] ?? [:]
        let calls = takePending()
        if failureMessage(response) != nil || calls.isEmpty {
            resetRounds()
            return
        }
        let continueBackend = recordRound()
        let task = Task {
            await self.runCalls(calls, continueBackend: continueBackend)
        }
        track(task)
    }

    private func runCalls(_ calls: [PendingCall], continueBackend: Bool) async {
        let results = if registry.supportsConcurrentBatch(calls.map(\.name)) {
            await concurrentResults(calls)
        } else {
            await sequentialResults(calls)
        }
        guard !isClosed, !Task.isCancelled else { return }
        for result in results {
            guard !Task.isCancelled else { return }
            send(itemFrame(result))
        }
        if continueBackend, !Task.isCancelled {
            send(createFrame())
        }
    }

    private func sequentialResults(_ calls: [PendingCall]) async -> [CallResult] {
        var results: [CallResult] = []
        for call in calls {
            if Task.isCancelled { break }
            results.append(await result(for: call))
        }
        return results
    }

    private func concurrentResults(_ calls: [PendingCall]) async -> [CallResult] {
        await withTaskGroup(of: (Int, CallResult).self) { group in
            for (index, call) in calls.enumerated() {
                group.addTask {
                    (index, await self.result(for: call))
                }
            }
            var ordered = Array<CallResult?>(repeating: nil, count: calls.count)
            for await (index, result) in group {
                ordered[index] = result
            }
            return ordered.compactMap { $0 }
        }
    }

    private func result(for call: PendingCall) async -> CallResult {
        onToolCall(call.name)
        if call.name == Self.endConversationToolName {
            return CallResult(callID: call.callID, output: scheduleGoodbye(), failed: false)
        }
        let outcome = await registry.run(name: call.name, argumentsJSON: call.arguments)
        return CallResult(callID: call.callID, output: outcome.output, failed: outcome.error != nil)
    }

    /// Waits out the spoken goodbye. A later `end_conversation` cancels this wait.
    private func scheduleGoodbye() -> String {
        let start = clock.now()
        let task = Task {
            await self.runGoodbye(startedAt: start)
        }
        let previous: Task<Void, Never>?
        lock.lock()
        if closed {
            lock.unlock()
            task.cancel()
            return Self.endConversationOutput
        }
        previous = goodbyeTask
        goodbyeTask = task
        lock.unlock()
        previous?.cancel()
        return Self.endConversationOutput
    }

    private func runGoodbye(startedAt start: TimeInterval) async {
        do {
            try await clock.sleep(for: Self.goodbyeQuiet)
            while true {
                let now = clock.now()
                if now - start >= Self.goodbyeMaxWait || now - assistantSpeechTime() >= Self.goodbyeQuiet {
                    break
                }
                try await clock.sleep(for: Self.goodbyeQuiet)
            }
        } catch {
            return
        }
        guard !Task.isCancelled else { return }
        onEndConversation()
    }

    private func itemFrame(_ result: CallResult) -> String {
        let item: [String: Any] = [
            "type": "function_call_output",
            "call_id": result.callID,
            "output": result.output,
            "status": result.failed ? "incomplete" : "completed",
        ]
        return encode([
            "type": "response.item.create",
            "event_id": nextEventID(),
            "item": item,
        ])
    }

    private func createFrame() -> String {
        encode([
            "type": "response.create",
            "event_id": nextEventID(),
        ])
    }

    private func framesForContextUpdate(_ context: VoiceContext) -> [String] {
        lock.lock()
        defer { lock.unlock() }
        guard acceptingContext, context != lastContext else { return [] }

        let summaryChanged = lastContext?.summary != context.summary
        let justEnded = lastContext?.navigationActive == true && !context.navigationActive
        if context.navigationActive {
            navigationEnded = false
        } else if justEnded {
            navigationEnded = true
        }
        lastContext = context

        var frames: [String] = []
        if summaryChanged {
            for chunk in Self.characterChunks(context.summary, limit: Self.thinkingAppendLimit) {
                frames.append(thinkingFrame(chunk))
            }
        }
        if justEnded {
            frames.append(thinkingFrame(Self.navigationEndedNote))
        }
        frames.append(developerFrame(Self.developerText(
            summary: context.summary,
            latitude: context.latitude,
            longitude: context.longitude,
            includeEndedNote: navigationEnded
        )))
        return frames
    }

    private func thinkingFrame(_ content: String) -> String {
        encode([
            "type": "session.thinking.append",
            "content": content,
            "event_id": nextEventID(),
            "delegation_id": NSNull(),
        ])
    }

    private func developerFrame(_ text: String) -> String {
        encode([
            "type": "response.item.create",
            "event_id": nextEventID(),
            "item": [
                "type": "message",
                "role": "developer",
                "content": [
                    [
                        "type": "input_text",
                        "text": text,
                    ],
                ],
            ],
        ])
    }

    private static func developerText(
        summary: String,
        latitude: Double?,
        longitude: Double?,
        includeEndedNote: Bool
    ) -> String {
        let body: String
        if includeEndedNote {
            body = summary.isEmpty ? navigationEndedNote : summary + "\n" + navigationEndedNote
        } else {
            body = summary
        }
        guard let latitude, let longitude else { return body }
        return "\(userLocationLine(latitude: latitude, longitude: longitude))\n\n\(body)"
    }

    /// Period separator and four decimal places, so the model sees the same coordinate text in every locale.
    private static func userLocationLine(latitude: Double, longitude: Double) -> String {
        let lat = String(format: "%.4f", locale: Locale(identifier: "en_US_POSIX"), latitude)
        let lon = String(format: "%.4f", locale: Locale(identifier: "en_US_POSIX"), longitude)
        return "User location: latitude \(lat), longitude \(lon)."
    }

    /// Splits on Swift character boundaries so a multi-scalar character is never cut in half.
    private static func characterChunks(_ text: String, limit: Int) -> [String] {
        guard limit > 0, !text.isEmpty else { return [] }
        var chunks: [String] = []
        var rest = text[...]
        while !rest.isEmpty {
            let end = rest.index(rest.startIndex, offsetBy: limit, limitedBy: rest.endIndex) ?? rest.endIndex
            chunks.append(String(rest[..<end]))
            rest = rest[end...]
        }
        return chunks
    }

    /// Nil when the response completed. Any other message resets the round counter and is not sent.
    private func failureMessage(_ response: [String: Any]) -> String? {
        guard let status = response["status"] as? String else { return nil }
        switch status {
        case "completed":
            return nil
        case "incomplete":
            var message = "OpenAI response incomplete"
            if let details = response["incomplete_details"] as? [String: Any],
               let reason = details["reason"] as? String {
                message += ": \(reason)"
            }
            return message
        default:
            if let error = response["error"] as? [String: Any] {
                let message = error["message"] as? String ?? "null"
                let code = error["code"] as? String ?? "null"
                return "\(message) (\(code))"
            }
            return "OpenAI response \(status)"
        }
    }

    private func functionCall(_ event: [String: Any]) -> PendingCall? {
        guard let item = event["item"] as? [String: Any],
              item["type"] as? String == "function_call",
              let name = item["name"] as? String,
              let callID = item["call_id"] as? String else {
            return nil
        }
        return PendingCall(name: name, callID: callID, arguments: item["arguments"] as? String ?? "")
    }

    private func jsonObject(_ frame: String) -> [String: Any]? {
        guard let data = frame.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return object
    }

    private func encode(_ object: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: object) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    private func noteContextAccepted() {
        lock.lock()
        acceptingContext = true
        lock.unlock()
    }

    private func noteAssistantSpeech() {
        let now = clock.now()
        lock.lock()
        lastAssistantSpeech = now
        lock.unlock()
    }

    private func assistantSpeechTime() -> TimeInterval {
        lock.lock()
        defer { lock.unlock() }
        return lastAssistantSpeech
    }

    private func clearPending() {
        lock.lock()
        pending.removeAll()
        lock.unlock()
    }

    private func appendPending(_ call: PendingCall) {
        lock.lock()
        pending.append(call)
        lock.unlock()
    }

    private func takePending() -> [PendingCall] {
        lock.lock()
        defer { lock.unlock() }
        let calls = pending
        pending.removeAll()
        return calls
    }

    private func resetRounds() {
        lock.lock()
        rounds = 0
        lock.unlock()
    }

    private func recordRound() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        rounds += 1
        if rounds <= Self.maxRounds {
            return true
        }
        rounds = 0
        return false
    }

    private var isClosed: Bool {
        lock.lock()
        defer { lock.unlock() }
        return closed
    }

    private func track(_ task: Task<Void, Never>) {
        lock.lock()
        if closed {
            lock.unlock()
            task.cancel()
            return
        }
        toolTasks.append(task)
        lock.unlock()
    }
}
