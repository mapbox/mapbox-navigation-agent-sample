import XCTest
@testable import Example

nonisolated final class VoiceSessionWiringTests: XCTestCase, @unchecked Sendable {
    func testStopDuringToolListingEndsTheSession() async throws {
        let entered = Gate()
        let release = Gate()
        let client = MapboxMcpClient(serverURL: URL(string: "https://mcp.test/mcp")!, accessToken: "token")
        let session = MapboxVoiceSession(client: client, openAIAPIKey: "sk-test", registryLoader: { _ in
            entered.signal()
            await release.waitUntil(atLeast: 1)
            return VoiceToolRegistry(tools: [])
        })
        let collected = Task { () -> [MapboxVoiceEvent] in
            var seen: [MapboxVoiceEvent] = []
            for await event in session.events {
                seen.append(event)
                if event == .ended { break }
            }
            return seen
        }

        let starter = Task { await session.start() }
        await entered.waitUntil(atLeast: 1)
        session.stop()
        release.signal()

        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask { await starter.value }
            group.addTask {
                try await Task.sleep(for: .seconds(3))
                throw VoiceSessionWiringError.timedOut
            }
            try await group.next()
            group.cancelAll()
        }

        let seen = await collected.value
        XCTAssertEqual(seen, [.connecting, .ended])
    }

    func testDroppedRoundDoesNotReuseDirectionsArguments() {
        let driving = #"{"routing_profile":"driving"}"#
        let walking = #"{"routing_profile":"walking"}"#
        for drop in ["response.failed", "response.incomplete", "response.created"] {
            var calls = VoicePendingCalls()
            calls.observe(functionCall(name: "directions_tool", callID: "call_old", arguments: driving))
            calls.observe(responseEvent(drop))
            let discarded = calls.begin("directions_tool")
            XCTAssertNotEqual(discarded.callId, "call_old", drop)
            XCTAssertNotEqual(discarded.arguments, driving, drop)

            calls.observe(functionCall(name: "directions_tool", callID: "call_new", arguments: walking))
            let next = calls.begin("directions_tool")
            XCTAssertEqual(next.callId, "call_new", drop)
            XCTAssertEqual(next.arguments, walking, drop)
        }
    }

    func testCompletedResponseWithFailedStatusUsesTheNextCallId() {
        let driving = #"{"routing_profile":"driving"}"#
        let walking = #"{"routing_profile":"walking"}"#
        var calls = VoicePendingCalls()
        calls.observe(functionCall(name: "directions_tool", callID: "call_old", arguments: driving))
        calls.observe(responseEvent("response.completed", response: ["status": "failed"]))

        calls.observe(functionCall(name: "directions_tool", callID: "call_new", arguments: walking))
        let next = calls.begin("directions_tool")
        XCTAssertEqual(next.callId, "call_new")
        XCTAssertEqual(next.arguments, walking)
        XCTAssertNotEqual(next.callId, "call_old")
    }

    func testCompletedRoundKeepsTheCallUntilItRuns() {
        let driving = #"{"routing_profile":"driving"}"#
        var calls = VoicePendingCalls()
        calls.observe(functionCall(name: "directions_tool", callID: "call_kept", arguments: driving))
        calls.observe(responseEvent("response.completed"))
        XCTAssertEqual(
            calls.begin("directions_tool"),
            TrackedCall(name: "directions_tool", callId: "call_kept", arguments: driving)
        )
    }

    private func functionCall(name: String, callID: String, arguments: String) -> String {
        responseEvent(
            "response.output_item.done",
            item: [
                "type": "function_call",
                "name": name,
                "call_id": callID,
                "arguments": arguments,
            ]
        )
    }

    private func responseEvent(
        _ type: String,
        item: [String: Any]? = nil,
        response: [String: Any]? = nil
    ) -> String {
        var event: [String: Any] = ["type": type]
        if let item { event["item"] = item }
        if let response { event["response"] = response }
        let data = try! JSONSerialization.data(withJSONObject: ["type": "response.event", "event": event])
        return String(decoding: data, as: UTF8.self)
    }
}

private enum VoiceSessionWiringError: Error {
    case timedOut
}
