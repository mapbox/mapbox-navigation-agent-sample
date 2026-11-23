import XCTest
@testable import Example

nonisolated final class VoiceToolRegistryTests: XCTestCase {
    func testUnknownToolFailsWithoutThrowing() async {
        let registry = VoiceToolRegistry(tools: [])
        let outcome = await registry.run(name: "nav_sdk_zoom_in", argumentsJSON: "{}")
        XCTAssertEqual(outcome, .failure("Tool 'nav_sdk_zoom_in' is not available"))
    }

    func testConcurrentBatchRequiresEveryToolToOptIn() {
        let optedIn = VoiceTool(name: "a", description: "one", parameters: [:], supportsConcurrentExecution: true) { _ in
            VoiceToolOutcome(output: "ok", error: nil)
        }
        let optedOut = VoiceTool(name: "b", description: "two", parameters: [:], supportsConcurrentExecution: false) { _ in
            VoiceToolOutcome(output: "ok", error: nil)
        }
        let registry = VoiceToolRegistry(tools: [optedIn, optedOut])
        XCTAssertFalse(registry.supportsConcurrentBatch(["a"]))
        XCTAssertTrue(registry.supportsConcurrentBatch(["a", "a"]))
        XCTAssertFalse(registry.supportsConcurrentBatch(["a", "b"]))
    }
}

nonisolated final class LiveDirectorTests: XCTestCase {
    func testDeviceTimeRegistryAdvertisesLivePromptsAndDelegation() throws {
        let tool = VoiceTool(
            name: "device_time",
            description: "Reads the device clock.",
            parameters: [
                "type": "object",
                "properties": [
                    "zoom": [
                        "type": "number",
                        "minimum": 0.5,
                        "maximum": 22,
                        "enum": [1, 1.5, "near", true],
                    ] as [String: Any],
                ] as [String: Any],
                "required": ["zoom"],
            ],
            supportsConcurrentExecution: false
        ) { _ in
            VoiceToolOutcome(output: "12:00", error: nil)
        }
        let director = LiveDirector(registry: VoiceToolRegistry(tools: [tool]))

        XCTAssertTrue(director.liveInstructions.contains("device_time, end_conversation"))
        XCTAssertTrue(director.liveInstructions.contains("You are Mapbox Agent"))
        XCTAssertTrue(director.liveInstructions.contains("All backend actions: device_time, end_conversation."))
        XCTAssertFalse(director.liveInstructions.contains("{{ACTIONS}}"))
        XCTAssertEqual(director.delegation.model, "gpt-5.6-terra")
        XCTAssertTrue(director.delegation.instructions.hasPrefix("## Voice conversation context"))
        XCTAssertTrue(director.delegation.instructions.contains("don't retry automatically."))
        XCTAssertEqual(director.delegation.tools.map(\.name), ["device_time", "end_conversation"])

        let device = try XCTUnwrap(director.delegation.tools.first)
        let properties = try XCTUnwrap(device.parameters["properties"] as? [String: Any])
        let zoom = try XCTUnwrap(properties["zoom"] as? [String: Any])
        XCTAssertNil(zoom["minimum"])
        XCTAssertEqual(zoom["type"] as? String, "number")
        XCTAssertEqual(zoom["maximum"] as? Int, 22)
        let choices = try XCTUnwrap(zoom["enum"] as? [Any])
        XCTAssertEqual(choices.count, 3)
        XCTAssertEqual(choices[0] as? Int, 1)
        XCTAssertEqual(choices[1] as? String, "near")
        XCTAssertEqual(choices[2] as? Bool, true)

        let end = try XCTUnwrap(director.delegation.tools.dropFirst().first)
        XCTAssertEqual(
            end.description,
            "Ends the conversation immediately, exactly as if the user pressed the app's stop button. " +
                "Call this as soon as the user signals they are done talking to you — for example \"that's all\", " +
                "\"goodbye\", or \"end the call\" — rather than replying and waiting for confirmation. Don't call " +
                "it when the user only asks you to stop talking, or to stop navigation. " +
                "The conversation closes after the goodbye finishes playing."
        )
        XCTAssertEqual(end.parameters["type"] as? String, "object")
        XCTAssertEqual(end.parameters["additionalProperties"] as? Bool, false)
        let endProperties = try XCTUnwrap(end.parameters["properties"] as? [String: Any])
        XCTAssertTrue(endProperties.isEmpty)
    }

    func testCompletedDeviceTimeCallSendsOutputThenResponseCreate() async throws {
        let sent = FrameLog()
        let toolCalls = NameLog()
        let runs = Counter()
        let ids = EventIDs()
        let session = LiveDirector(registry: deviceTimeRegistry(runs: runs), nextEventID: { ids.next() }).session(
            send: { sent.append($0) },
            onEndConversation: {},
            onToolCall: { toolCalls.append($0) }
        )

        session.onServerEvent(responseEvent(outputItemDone(name: "device_time", callID: "call_1")))
        session.onServerEvent(responseEvent(completedResponse()))
        try await sent.waitForCount(2)

        XCTAssertEqual(toolCalls.snapshot(), ["device_time"])
        XCTAssertEqual(runs.value, 1)
        let frames = sent.snapshot()
        XCTAssertEqual(frames.count, 2)

        let itemFrame = try jsonObject(frames[0])
        XCTAssertEqual(itemFrame["type"] as? String, "response.item.create")
        XCTAssertEqual(itemFrame["event_id"] as? String, "evt_1")
        let item = try XCTUnwrap(itemFrame["item"] as? [String: Any])
        XCTAssertEqual(item["type"] as? String, "function_call_output")
        XCTAssertEqual(item["call_id"] as? String, "call_1")
        XCTAssertEqual(item["output"] as? String, "12:00")
        XCTAssertEqual(item["status"] as? String, "completed")

        let create = try jsonObject(frames[1])
        XCTAssertEqual(create["type"] as? String, "response.create")
        XCTAssertEqual(create["event_id"] as? String, "evt_2")
    }

    func testCompletedResponseWithNoCallsSendsNothing() async throws {
        let sent = FrameLog()
        let session = LiveDirector(registry: deviceTimeRegistry(runs: Counter())).session(
            send: { sent.append($0) },
            onEndConversation: {},
            onToolCall: { _ in }
        )

        session.onServerEvent(responseEvent(completedResponse()))
        try await Task.sleep(for: .milliseconds(50))

        XCTAssertTrue(sent.snapshot().isEmpty)
    }

    func testSeventhRoundOmitsResponseCreateAndEighthRestoresIt() async throws {
        let sent = FrameLog()
        let session = LiveDirector(registry: deviceTimeRegistry(runs: Counter())).session(
            send: { sent.append($0) },
            onEndConversation: {},
            onToolCall: { _ in }
        )

        var seen = 0
        for round in 1...8 {
            session.onServerEvent(responseEvent(outputItemDone(name: "device_time", callID: "call_\(round)")))
            session.onServerEvent(responseEvent(completedResponse()))
            let sendsCreate = round != 7
            try await sent.waitForCount(seen + (sendsCreate ? 2 : 1))
            let added = Array(sent.snapshot().dropFirst(seen))
            XCTAssertEqual(added.count, sendsCreate ? 2 : 1, "round \(round)")
            let output = try jsonObject(added[0])
            XCTAssertEqual(output["type"] as? String, "response.item.create")
            let item = try XCTUnwrap(output["item"] as? [String: Any])
            XCTAssertEqual(item["output"] as? String, "12:00")
            XCTAssertEqual(item["status"] as? String, "completed")
            if sendsCreate {
                XCTAssertEqual(try jsonObject(added[1])["type"] as? String, "response.create")
            }
            seen += added.count
        }
    }

    func testEndConversationWaitsForQuietWithoutRunningTheRegistry() async throws {
        let sent = FrameLog()
        let runs = Counter()
        let ended = Gate()
        let clock = ManualClock()
        let session = LiveDirector(registry: deviceTimeRegistry(runs: runs), clock: clock).session(
            send: { sent.append($0) },
            onEndConversation: { ended.signal() },
            onToolCall: { _ in }
        )

        session.onServerEvent(responseEvent(outputItemDone(name: "end_conversation", callID: "call_end")))
        session.onServerEvent(responseEvent(completedResponse()))

        try await sent.waitForCount(2)
        try await clock.waitUntilSleeping()
        XCTAssertEqual(ended.value, 0)
        XCTAssertEqual(runs.value, 0)

        let frames = sent.snapshot()
        let item = try XCTUnwrap(try jsonObject(frames[0])["item"] as? [String: Any])
        XCTAssertEqual(item["call_id"] as? String, "call_end")
        XCTAssertEqual(item["output"] as? String, "Ending the conversation now.")
        XCTAssertEqual(item["status"] as? String, "completed")
        XCTAssertNotEqual(item["output"] as? String, "Tool 'end_conversation' is not available")
        XCTAssertEqual(try jsonObject(frames[1])["type"] as? String, "response.create")

        try await clock.advance(by: 1.5)
        await ended.waitUntil(atLeast: 1)
        XCTAssertEqual(ended.value, 1)
        XCTAssertEqual(runs.value, 0)
    }

    func testLocationOnlyUpdateSendsDeveloperMessageWithoutThinkingAppend() throws {
        let sent = FrameLog()
        let ids = EventIDs()
        let session = makeSession(sent: sent, ids: ids)
        let summary = "Navigation: active. Destination: Work. Mode: driving."
        let initial = VoiceContext(latitude: 37.7, longitude: -122.4, navigationActive: true, summary: summary)

        session.updateContext(initial)
        XCTAssertTrue(sent.snapshot().isEmpty)
        session.onServerEvent(#"{"type":"session.started"}"#)
        XCTAssertTrue(sent.snapshot().isEmpty)

        session.updateContext(initial)
        let published = sent.snapshot()
        XCTAssertEqual(published.count, 2)
        let thinking = try jsonObject(published[0])
        XCTAssertEqual(thinking["type"] as? String, "session.thinking.append")
        XCTAssertEqual(thinking["content"] as? String, summary)
        XCTAssertEqual(thinking["event_id"] as? String, "evt_1")
        XCTAssertTrue(thinking["delegation_id"] is NSNull)
        try assertDeveloperMessage(published[1], eventID: "evt_2", text: withLocation(37.7, -122.4, summary))

        let moved = VoiceContext(latitude: 37.8, longitude: -122.5, navigationActive: true, summary: summary)
        session.updateContext(moved)
        let added = Array(sent.snapshot().dropFirst(published.count))
        XCTAssertEqual(added.count, 1)
        XCTAssertFalse(added[0].contains("session.thinking.append"))
        try assertDeveloperMessage(added[0], eventID: "evt_3", text: withLocation(37.8, -122.5, summary))

        session.updateContext(moved)
        XCTAssertEqual(sent.snapshot().count, published.count + 1)
    }

    func testNavigationEndedTransitionAppendsSentenceAndOneThinkingAppend() throws {
        let sent = FrameLog()
        let ids = EventIDs()
        let session = makeSession(sent: sent, ids: ids)
        session.onServerEvent(#"{"type":"session.started"}"#)

        let summary = "Navigation: active. Destination: Work. Mode: driving."
        session.updateContext(VoiceContext(latitude: 1, longitude: 2, navigationActive: true, summary: summary))
        let afterActive = sent.snapshot().count

        session.updateContext(VoiceContext(latitude: 1, longitude: 2, navigationActive: false, summary: summary))
        let ended = Array(sent.snapshot().dropFirst(afterActive))
        XCTAssertEqual(ended.count, 2)
        let thinking = try jsonObject(ended[0])
        XCTAssertEqual(thinking["type"] as? String, "session.thinking.append")
        XCTAssertEqual(thinking["content"] as? String, navigationEndedNote)
        XCTAssertEqual(thinking["event_id"] as? String, "evt_3")
        try assertDeveloperMessage(
            ended[1],
            eventID: "evt_4",
            text: withLocation(1, 2, summary + "\n" + navigationEndedNote)
        )

        session.updateContext(VoiceContext(latitude: 3, longitude: 4, navigationActive: false, summary: summary))
        let moved = Array(sent.snapshot().dropFirst(afterActive + ended.count))
        XCTAssertEqual(moved.count, 1)
        XCTAssertFalse(moved[0].contains("session.thinking.append"))
        try assertDeveloperMessage(
            moved[0],
            eventID: "evt_5",
            text: withLocation(3, 4, summary + "\n" + navigationEndedNote)
        )

        let idle = "Navigation: idle. Destination: none. Mode: none."
        session.updateContext(VoiceContext(latitude: 3, longitude: 4, navigationActive: false, summary: idle))
        let idleFrames = Array(sent.snapshot().dropFirst(afterActive + ended.count + moved.count))
        XCTAssertEqual(idleFrames.count, 2)
        XCTAssertEqual(try jsonObject(idleFrames[0])["type"] as? String, "session.thinking.append")
        XCTAssertEqual(try jsonObject(idleFrames[0])["content"] as? String, idle)
        try assertDeveloperMessage(
            idleFrames[1],
            eventID: "evt_7",
            text: withLocation(3, 4, idle + "\n" + navigationEndedNote)
        )

        let resumed = "Navigation: active. Destination: Home. Mode: walking."
        session.updateContext(VoiceContext(latitude: 3, longitude: 4, navigationActive: true, summary: resumed))
        let resumedFrames = Array(sent.snapshot().dropFirst(afterActive + ended.count + moved.count + idleFrames.count))
        XCTAssertEqual(resumedFrames.count, 2)
        XCTAssertEqual(try jsonObject(resumedFrames[0])["content"] as? String, resumed)
        try assertDeveloperMessage(resumedFrames[1], eventID: "evt_9", text: withLocation(3, 4, resumed))
        XCTAssertFalse(resumedFrames.contains { $0.contains(navigationEndedNote) })
    }

    func testChangedSummarySplitsThinkingAppendsOnCharacterBoundaries() throws {
        let sent = FrameLog()
        let ids = EventIDs()
        let session = makeSession(sent: sent, ids: ids)
        session.onServerEvent(#"{"type":"session.started"}"#)

        let summary = String(repeating: "a", count: 599) + "🙂" + "b"
        XCTAssertEqual(summary.count, 601)
        session.updateContext(VoiceContext(latitude: nil, longitude: nil, navigationActive: false, summary: summary))

        let frames = sent.snapshot()
        XCTAssertEqual(frames.count, 3)
        let first = try jsonObject(frames[0])
        let second = try jsonObject(frames[1])
        XCTAssertEqual(first["type"] as? String, "session.thinking.append")
        XCTAssertEqual(second["type"] as? String, "session.thinking.append")
        let head = try XCTUnwrap(first["content"] as? String)
        let tail = try XCTUnwrap(second["content"] as? String)
        XCTAssertEqual(head.count, 600)
        XCTAssertEqual(tail.count, 1)
        XCTAssertTrue(head.hasSuffix("🙂"))
        XCTAssertEqual(tail, "b")
        XCTAssertEqual(head + tail, summary)
        try assertDeveloperMessage(frames[2], eventID: "evt_3", text: summary)
    }

    func testDeveloperMessageBeginsWithTheSimulatedLocation() throws {
        let sent = FrameLog()
        let ids = EventIDs()
        let session = makeSession(sent: sent, ids: ids)
        session.onServerEvent(#"{"type":"session.started"}"#)

        let summary = "Navigation: idle. Destination: none. Mode: none."
        session.updateContext(VoiceContext(
            latitude: 37.7955,
            longitude: -122.3937,
            navigationActive: false,
            summary: summary
        ))

        let frames = sent.snapshot()
        XCTAssertEqual(frames.count, 2)
        XCTAssertEqual(try jsonObject(frames[0])["type"] as? String, "session.thinking.append")
        XCTAssertEqual(try jsonObject(frames[0])["content"] as? String, summary)
        let text = try developerMessageText(frames[1])
        XCTAssertTrue(text.hasPrefix("User location: latitude 37.7955, longitude -122.3937."))
        XCTAssertEqual(text, "User location: latitude 37.7955, longitude -122.3937.\n\n\(summary)")
    }

    func testFailedCompletedResponseUsesTheNextCallId() async throws {
        let sent = FrameLog()
        let toolCalls = NameLog()
        let session = LiveDirector(registry: deviceTimeRegistry(runs: Counter())).session(
            send: { sent.append($0) },
            onEndConversation: {},
            onToolCall: { toolCalls.append($0) }
        )

        session.onServerEvent(responseEvent(outputItemDone(name: "device_time", callID: "call_1")))
        session.onServerEvent(responseEvent(
            #"{"type":"response.completed","response":{"status":"failed"}}"#
        ))
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(toolCalls.snapshot(), [])
        XCTAssertTrue(sent.snapshot().isEmpty)

        session.onServerEvent(responseEvent(outputItemDone(name: "device_time", callID: "call_2")))
        session.onServerEvent(responseEvent(completedResponse()))
        try await sent.waitForCount(2)

        XCTAssertEqual(toolCalls.snapshot(), ["device_time"])
        let frames = sent.snapshot()
        let item = try XCTUnwrap(try jsonObject(frames[0])["item"] as? [String: Any])
        XCTAssertEqual(item["type"] as? String, "function_call_output")
        XCTAssertEqual(item["call_id"] as? String, "call_2")
        XCTAssertEqual(item["output"] as? String, "12:00")
    }

    private let navigationEndedNote =
        "Navigation ended: there is no active route or destination now. Any trip mentioned earlier in this conversation is over; start a new one only if the user asks."

    private func makeSession(sent: FrameLog, ids: EventIDs) -> LiveDirectorSession {
        LiveDirector(registry: deviceTimeRegistry(runs: Counter()), nextEventID: { ids.next() }).session(
            send: { sent.append($0) },
            onEndConversation: {},
            onToolCall: { _ in }
        )
    }

    private func withLocation(_ latitude: Double, _ longitude: Double, _ body: String) -> String {
        let line = String(
            format: "User location: latitude %.4f, longitude %.4f.",
            locale: Locale(identifier: "en_US_POSIX"),
            latitude,
            longitude
        )
        return line + "\n\n" + body
    }

    private func developerMessageText(_ frame: String) throws -> String {
        let object = try jsonObject(frame)
        let item = try XCTUnwrap(object["item"] as? [String: Any])
        let content = try XCTUnwrap(item["content"] as? [[String: Any]])
        return try XCTUnwrap(content[0]["text"] as? String)
    }

    private func assertDeveloperMessage(_ frame: String, eventID: String, text: String) throws {
        let object = try jsonObject(frame)
        XCTAssertEqual(object["type"] as? String, "response.item.create")
        XCTAssertEqual(object["event_id"] as? String, eventID)
        let item = try XCTUnwrap(object["item"] as? [String: Any])
        XCTAssertEqual(item["type"] as? String, "message")
        XCTAssertEqual(item["role"] as? String, "developer")
        let content = try XCTUnwrap(item["content"] as? [[String: Any]])
        XCTAssertEqual(content.count, 1)
        XCTAssertEqual(content[0]["type"] as? String, "input_text")
        XCTAssertEqual(content[0]["text"] as? String, text)
    }

    private func deviceTimeRegistry(runs: Counter) -> VoiceToolRegistry {
        let tool = VoiceTool(
            name: "device_time",
            description: "Reads the device clock.",
            parameters: [:],
            supportsConcurrentExecution: false
        ) { _ in
            runs.increment()
            return VoiceToolOutcome(output: "12:00", error: nil)
        }
        return VoiceToolRegistry(tools: [tool])
    }

    private func responseEvent(_ event: String) -> String {
        #"{"type":"response.event","event":\#(event)}"#
    }

    private func outputItemDone(name: String, callID: String) -> String {
        #"{"type":"response.output_item.done","item":{"type":"function_call","name":"\#(name)","call_id":"\#(callID)"}}"#
    }

    private func completedResponse() -> String {
        #"{"type":"response.completed","response":{"status":"completed"}}"#
    }

    private func jsonObject(_ json: String) throws -> [String: Any] {
        let data = Data(json.utf8)
        let object = try JSONSerialization.jsonObject(with: data)
        return try XCTUnwrap(object as? [String: Any])
    }
}

nonisolated final class FrameLog: @unchecked Sendable {
    private let lock = NSLock()
    private var frames: [String] = []
    private var waiters: [(Int, CheckedContinuation<Void, Never>)] = []

    func append(_ frame: String) {
        lock.lock()
        frames.append(frame)
        let count = frames.count
        let ready = waiters.filter { count >= $0.0 }.map(\.1)
        waiters.removeAll { count >= $0.0 }
        lock.unlock()
        ready.forEach { $0.resume() }
    }

    func snapshot() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return frames
    }

    func waitForCount(_ count: Int) async throws {
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                    self.lock.lock()
                    if self.frames.count >= count {
                        self.lock.unlock()
                        continuation.resume()
                        return
                    }
                    self.waiters.append((count, continuation))
                    self.lock.unlock()
                }
            }
            group.addTask {
                try await Task.sleep(for: .seconds(2))
                throw FrameLogError.timedOut
            }
            try await group.next()
            group.cancelAll()
        }
    }
}

private enum FrameLogError: Error {
    case timedOut
}

nonisolated final class NameLog: @unchecked Sendable {
    private let lock = NSLock()
    private var names: [String] = []

    func append(_ name: String) {
        lock.lock()
        names.append(name)
        lock.unlock()
    }

    func snapshot() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return names
    }
}

nonisolated final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    func increment() {
        lock.lock()
        count += 1
        lock.unlock()
    }

    var value: Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }
}

nonisolated final class Gate: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    private var waiters: [(Int, CheckedContinuation<Void, Never>)] = []

    func signal() {
        lock.lock()
        count += 1
        let current = count
        let ready = waiters.filter { current >= $0.0 }.map(\.1)
        waiters.removeAll { current >= $0.0 }
        lock.unlock()
        ready.forEach { $0.resume() }
    }

    var value: Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }

    func waitUntil(atLeast target: Int) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            lock.lock()
            if count >= target {
                lock.unlock()
                continuation.resume()
                return
            }
            waiters.append((target, continuation))
            lock.unlock()
        }
    }
}

nonisolated final class EventIDs: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    func next() -> String {
        lock.lock()
        count += 1
        let current = count
        lock.unlock()
        return "evt_\(current)"
    }
}

nonisolated final class ManualClock: LiveClock, @unchecked Sendable {
    private struct Waiter {
        var remaining: TimeInterval
        var continuation: CheckedContinuation<Void, Error>
    }

    private let lock = NSLock()
    private var time: TimeInterval = 0
    private var waiters: [UUID: Waiter] = [:]
    private var order: [UUID] = []
    private var cancelled: Set<UUID> = []
    private var sleepWaiters: [CheckedContinuation<Void, Never>] = []

    func now() -> TimeInterval {
        lock.lock()
        defer { lock.unlock() }
        return time
    }

    func sleep(for seconds: TimeInterval) async throws {
        if seconds <= 0 { return }
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                self.lock.lock()
                if self.cancelled.remove(id) != nil {
                    self.lock.unlock()
                    continuation.resume(throwing: CancellationError())
                    return
                }
                self.waiters[id] = Waiter(remaining: seconds, continuation: continuation)
                self.order.append(id)
                let pending = self.sleepWaiters
                self.sleepWaiters.removeAll()
                self.lock.unlock()
                pending.forEach { $0.resume() }
            }
        } onCancel: {
            self.lock.lock()
            if let waiter = self.waiters.removeValue(forKey: id) {
                self.order.removeAll { $0 == id }
                self.lock.unlock()
                waiter.continuation.resume(throwing: CancellationError())
            } else {
                self.cancelled.insert(id)
                self.lock.unlock()
            }
        }
    }

    func waitUntilSleeping() async throws {
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                    self.lock.lock()
                    if !self.waiters.isEmpty {
                        self.lock.unlock()
                        continuation.resume()
                        return
                    }
                    self.sleepWaiters.append(continuation)
                    self.lock.unlock()
                }
            }
            group.addTask {
                try await Task.sleep(for: .seconds(2))
                throw FrameLogError.timedOut
            }
            try await group.next()
            group.cancelAll()
        }
    }

    func advance(by seconds: TimeInterval) async throws {
        let ready = try takeReadyWaiters(advancing: seconds)
        ready.forEach { $0.resume() }
    }

    private func takeReadyWaiters(advancing seconds: TimeInterval) throws -> [CheckedContinuation<Void, Error>] {
        lock.lock()
        defer { lock.unlock() }
        guard !waiters.isEmpty else { throw FrameLogError.timedOut }
        time += seconds
        var budget = seconds
        var ready: [CheckedContinuation<Void, Error>] = []
        var still: [UUID] = []
        for id in order {
            guard var waiter = waiters[id] else { continue }
            if budget >= waiter.remaining {
                budget -= waiter.remaining
                waiters.removeValue(forKey: id)
                ready.append(waiter.continuation)
            } else {
                waiter.remaining -= budget
                budget = 0
                waiters[id] = waiter
                still.append(id)
            }
        }
        order = still
        return ready
    }
}
