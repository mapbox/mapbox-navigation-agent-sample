import Foundation
import MapboxNavigationAgent

nonisolated public enum MapboxVoiceEvent: Sendable, Equatable {
    case connecting
    case listening
    case toolCall(name: String, callId: String, arguments: String)
    /// `text` is what the voice model saw. `structuredJSON` is the MCP `structuredContent`, for the app to draw.
    case toolResult(name: String, callId: String, text: String, isError: Bool, structuredJSON: String? = nil)
    case ended
    case failed(message: String)
    /// A server error while the session is still open. The mic and socket stay up.
    case serverError(message: String)
}

/// A voice session that speaks with the user and issues each tool call through a Mapbox MCP client.
///
/// WebRTC carries the microphone and the reply. `LiveSessionClient` owns that connection, and
/// `LiveDirector` runs the tool loop against the server's tool list.
nonisolated public final class MapboxVoiceSession: @unchecked Sendable {
    /// The tool the director runs itself to end the conversation.
    public static let endConversationToolName = LiveVoiceSession.endConversationToolName

    /// The tool a server-listed result uses to leave turn-by-turn guidance.
    public static let stopNavigationToolName = LiveVoiceSession.stopNavigationToolName

    public let events: AsyncStream<MapboxVoiceEvent>

    private let client: MapboxMcpClient
    private let openAIAPIKey: String
    private let registryLoader: (@Sendable (MapboxVoiceSession) async throws -> VoiceToolRegistry)?
    private let continuation: AsyncStream<MapboxVoiceEvent>.Continuation
    private let lock = NSLock()
    private var liveClient: LiveSessionClient?
    private var directorSession: LiveDirectorSession?
    private var outbound: AsyncStream<String>.Continuation?
    private var outboundPump: Task<Void, Never>?
    private var calls = VoicePendingCalls()
    private var stopped = false
    private var running = false
    private var closing = false
    private var closeObserved = false

    public convenience init(client: MapboxMcpClient, openAIAPIKey: String) {
        self.init(client: client, openAIAPIKey: openAIAPIKey, registryLoader: nil)
    }

    init(
        client: MapboxMcpClient,
        openAIAPIKey: String,
        registryLoader: (@Sendable (MapboxVoiceSession) async throws -> VoiceToolRegistry)?
    ) {
        self.client = client
        self.openAIAPIKey = openAIAPIKey
        self.registryLoader = registryLoader
        var continuation: AsyncStream<MapboxVoiceEvent>.Continuation!
        events = AsyncStream { continuation = $0 }
        self.continuation = continuation
    }

    public func start() async {
        guard begin() else { return }
        defer { endStart() }

        emit(.connecting)
        guard !openAIAPIKey.isEmpty else {
            emit(.failed(message: "An OpenAI API key is required to start a voice session."))
            return
        }

        let liveClient = LiveSessionClient(apiKey: openAIAPIKey)
        let outbound = AsyncStream<String>.makeStream()
        let pump = Task {
            for await event in outbound.stream {
                await liveClient.send(event)
            }
        }
        store(liveClient, outbound: outbound.continuation, pump: pump)

        do {
            let registry = try await loadRegistry()
            // Tool listing can outlast stop's 5 second close. connect() would open a new
            // WebRTC session on this same client, and closing is already true so a later stop cannot tear it down.
            guard !isStopped else { return }
            let director = LiveDirector(registry: registry)
            let directorSession = director.session(
                send: { outbound.continuation.yield($0) },
                onEndConversation: { [self] in
                    self.stop()
                },
                onToolCall: { [self] name in
                    self.beginToolCall(name)
                }
            )
            store(directorSession)
            guard !isStopped else { return }
            let frames = try await liveClient.connect(
                instructions: director.liveInstructions,
                delegation: director.delegation
            )
            guard !isStopped else { return }
            for await frame in frames {
                if consume(frame, director: directorSession) {
                    break
                }
            }
        } catch is CancellationError {
        } catch {
            if !isStopped {
                emit(.failed(message: error.localizedDescription))
            }
        }
    }

    /// Asks the server to close, then tears the connection down once it does or after 5 seconds.
    public func stop() {
        lock.lock()
        stopped = true
        lock.unlock()
        Task { await closeLive() }
    }

    func updateContext(_ context: VoiceContext) {
        lock.lock()
        let session = directorSession
        lock.unlock()
        session?.updateContext(context)
    }

    private func loadRegistry() async throws -> VoiceToolRegistry {
        if let registryLoader {
            return try await registryLoader(self)
        }
        return try await makeRegistry()
    }

    private func makeRegistry() async throws -> VoiceToolRegistry {
        // The sample's voice path still requires the app's Mapbox MCP client. A stand-in session cannot open one.
        _ = try await client.frameworkSession()
        let tools = try await client.listTools()
        let voiceTools = tools.compactMap { tool -> VoiceTool? in
            guard tool.name != Self.endConversationToolName else { return nil }
            let name = tool.name
            return VoiceTool(
                name: name,
                description: tool.description,
                parameters: Self.schema(tool.inputSchemaJSON),
                supportsConcurrentExecution: false
            ) { [self] argumentsJSON in
                await self.perform(name: name, argumentsJSON: argumentsJSON)
            }
        }
        return VoiceToolRegistry(tools: voiceTools)
    }

    /// The director calls this before it runs the tool. Emitting here is the tool-call event; the run closure emits the result.
    private func beginToolCall(_ name: String) {
        let call = lock.withLock { calls.begin(name) }
        emit(.toolCall(name: call.name, callId: call.callId, arguments: call.arguments))
    }

    private func perform(name: String, argumentsJSON: String) async -> VoiceToolOutcome {
        let call = takeActiveCall(name: name, argumentsJSON: argumentsJSON)
        do {
            let result = try await client.callTool(name: name, argumentsJSON: argumentsJSON)
            emit(.toolResult(
                name: name,
                callId: call.callId,
                text: result.text,
                isError: result.isError,
                structuredJSON: result.structuredJSON
            ))
            if result.isError {
                return .failure(result.text)
            }
            return VoiceToolOutcome(output: result.text, error: nil)
        } catch {
            let message = error.localizedDescription
            emit(.toolResult(name: name, callId: call.callId, text: message, isError: true, structuredJSON: nil))
            return .failure(message)
        }
    }

    private func consume(_ frame: String, director: LiveDirectorSession) -> Bool {
        lock.lock()
        calls.observe(frame)
        lock.unlock()
        director.onServerEvent(frame)
        guard let type = eventType(frame) else { return false }
        switch type {
        case "session.started":
            emit(.listening)
            return false
        case "error":
            emit(.serverError(message: errorMessage(frame)))
            return false
        case "session.closed":
            markCloseObserved()
            return true
        default:
            return false
        }
    }

    private func closeLive() async {
        guard let client = claimClose() else { return }
        await client.requestClose()
        await waitForServerClose()
        client.disconnect()
    }

    private func waitForServerClose() async {
        let deadline = ContinuousClock().now.advanced(by: .seconds(5))
        while ContinuousClock().now < deadline {
            if isCloseObserved { return }
            try? await Task.sleep(for: .milliseconds(50))
        }
    }

    private func endStart() {
        let pump: Task<Void, Never>?
        let outbound: AsyncStream<String>.Continuation?
        let director: LiveDirectorSession?
        let client: LiveSessionClient?
        lock.lock()
        pump = outboundPump
        outbound = self.outbound
        director = directorSession
        client = liveClient
        outboundPump = nil
        self.outbound = nil
        lock.unlock()

        outbound?.finish()
        pump?.cancel()
        director?.close()
        client?.disconnect()
        markCloseObserved()
        finish()
        emit(.ended)
        continuation.finish()
    }

    private func takeActiveCall(name: String, argumentsJSON: String) -> TrackedCall {
        lock.lock()
        defer { lock.unlock() }
        return calls.takeActive(name: name, argumentsJSON: argumentsJSON)
    }

    private func eventType(_ frame: String) -> String? {
        jsonObject(frame)?["type"] as? String
    }

    private func errorMessage(_ frame: String) -> String {
        guard let object = jsonObject(frame) else { return frame }
        if let error = object["error"] as? [String: Any],
           let message = error["message"] as? String,
           !message.isEmpty {
            return message
        }
        if let message = object["message"] as? String, !message.isEmpty {
            return message
        }
        return frame
    }

    private func jsonObject(_ frame: String) -> [String: Any]? {
        guard let data = frame.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return object
    }

    private static func schema(_ json: String) -> [String: Any] {
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        return object
    }

    private func store(
        _ client: LiveSessionClient,
        outbound: AsyncStream<String>.Continuation,
        pump: Task<Void, Never>
    ) {
        lock.lock()
        liveClient = client
        self.outbound = outbound
        outboundPump = pump
        lock.unlock()
    }

    private func store(_ session: LiveDirectorSession) {
        lock.lock()
        directorSession = session
        lock.unlock()
    }

    private func claimClose() -> LiveSessionClient? {
        lock.lock()
        defer { lock.unlock() }
        guard let client = liveClient, !closing else { return nil }
        closing = true
        return client
    }

    private func markCloseObserved() {
        lock.lock()
        closeObserved = true
        lock.unlock()
    }

    private func emit(_ event: MapboxVoiceEvent) {
        continuation.yield(event)
    }

    private func begin() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if running { return false }
        running = true
        stopped = false
        closing = false
        closeObserved = false
        calls = VoicePendingCalls()
        return true
    }

    private func finish() {
        lock.lock()
        running = false
        liveClient = nil
        directorSession = nil
        lock.unlock()
    }

    private var isStopped: Bool {
        lock.lock()
        defer { lock.unlock() }
        return stopped
    }

    private var isCloseObserved: Bool {
        lock.lock()
        defer { lock.unlock() }
        return closeObserved
    }
}

nonisolated struct TrackedCall: Sendable, Equatable {
    var name: String
    var callId: String
    var arguments: String
}

/// Call ids the map pairs with tool results. The director drops a round without `onToolCall` on
/// `response.created`, `response.failed`, `response.incomplete`, and `response.completed` when the
/// response status is present and not `completed`, so those ids are forgotten here too.
nonisolated struct VoicePendingCalls {
    private var pending: [TrackedCall] = []
    private var active: TrackedCall?

    mutating func observe(_ frame: String) {
        if let call = Self.functionCall(in: frame) {
            pending.append(call)
        }
        if Self.directorDropsPending(frame) {
            pending.removeAll()
        }
    }

    mutating func begin(_ name: String) -> TrackedCall {
        let index = pending.firstIndex { $0.name == name }
        let call = index.map { pending.remove(at: $0) }
            ?? TrackedCall(name: name, callId: "call_\(UUID().uuidString)", arguments: "{}")
        active = call
        return call
    }

    mutating func takeActive(name: String, argumentsJSON: String) -> TrackedCall {
        if let active, active.name == name {
            self.active = nil
            return TrackedCall(name: name, callId: active.callId, arguments: argumentsJSON)
        }
        return TrackedCall(name: name, callId: "call_\(UUID().uuidString)", arguments: argumentsJSON)
    }

    private static func directorDropsPending(_ frame: String) -> Bool {
        guard let event = responseEvent(frame), let type = event["type"] as? String else { return false }
        if type == "response.created" || type == "response.failed" || type == "response.incomplete" {
            return true
        }
        guard type == "response.completed",
              let response = event["response"] as? [String: Any],
              let status = response["status"] as? String else {
            return false
        }
        return status != "completed"
    }

    private static func functionCall(in frame: String) -> TrackedCall? {
        guard let event = responseEvent(frame),
              event["type"] as? String == "response.output_item.done",
              let item = event["item"] as? [String: Any],
              item["type"] as? String == "function_call",
              let name = item["name"] as? String,
              let callID = item["call_id"] as? String else {
            return nil
        }
        return TrackedCall(name: name, callId: callID, arguments: item["arguments"] as? String ?? "")
    }

    private static func responseEvent(_ frame: String) -> [String: Any]? {
        guard let object = jsonObject(frame),
              object["type"] as? String == "response.event",
              let event = object["event"] as? [String: Any] else {
            return nil
        }
        return event
    }

    private static func jsonObject(_ frame: String) -> [String: Any]? {
        guard let data = frame.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return object
    }
}
