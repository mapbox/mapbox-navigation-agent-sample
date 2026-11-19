import Foundation

public struct McpToolDescriptor: Sendable {
    public var name: String
    public var description: String
    public var inputSchemaJSON: String
}

public struct McpCallResult: Sendable {
    public var isError: Bool
    public var text: String
    public var structuredJSON: String?
}

public struct McpSessionClosed: Error, CustomStringConvertible, LocalizedError {
    public init() {}
    public var description: String { "MCP session is closed" }
    public var errorDescription: String? { description }
}

/// Streamable-HTTP client for one Mapbox MCP server. The Example wraps this for the voice session.
public final class McpClientSession: @unchecked Sendable {
    private let serverURL: URL
    private let accessToken: String
    private let urlSession: URLSession
    private let state = State()

    private final class State: @unchecked Sendable {
        var closed = false
        var sessionID: String?
        var nextID = 0
        private let lock = NSLock()

        func withLock<T>(_ body: (State) -> T) -> T {
            lock.lock()
            defer { lock.unlock() }
            return body(self)
        }
    }

    public init(serverUrl: String, accessToken: String, urlSession: URLSession = .shared) {
        self.serverURL = URL(string: serverUrl) ?? URL(string: "https://example.invalid")!
        self.accessToken = accessToken
        self.urlSession = urlSession
    }

    public func listTools() async throws -> [McpToolDescriptor] {
        try ensureOpen()
        try await initializeIfNeeded()
        let result = try await rpc(method: "tools/list", params: [:])
        let tools = (result["tools"] as? [[String: Any]]) ?? []
        return tools.compactMap { tool in
            guard let name = tool["name"] as? String else { return nil }
            let schema = tool["inputSchema"].map { value in
                (try? JSONSerialization.data(withJSONObject: value)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
            } ?? "{}"
            return McpToolDescriptor(
                name: name,
                description: tool["description"] as? String ?? "",
                inputSchemaJSON: schema
            )
        }
    }

    public func callTool(name: String, argumentsJson: String) async throws -> McpCallResult {
        try ensureOpen()
        try await initializeIfNeeded()
        let arguments = (try? JSONSerialization.jsonObject(with: Data(argumentsJson.utf8))) as? [String: Any] ?? [:]
        let result = try await rpc(method: "tools/call", params: ["name": name, "arguments": arguments])
        let content = result["content"] as? [[String: Any]] ?? []
        let text = content.compactMap { $0["text"] as? String }.joined(separator: "\n")
        let structured = result["structuredContent"].flatMap { value -> String? in
            guard JSONSerialization.isValidJSONObject(value),
                  let data = try? JSONSerialization.data(withJSONObject: value) else { return nil }
            return String(data: data, encoding: .utf8)
        }
        return McpCallResult(isError: result["isError"] as? Bool ?? false, text: text, structuredJSON: structured)
    }

    public func close() {
        state.withLock { $0.closed = true }
    }

    private func ensureOpen() throws {
        let isClosed = state.withLock { $0.closed }
        if isClosed { throw McpSessionClosed() }
    }

    private func initializeIfNeeded() async throws {
        let existing = state.withLock { $0.sessionID }
        if existing != nil { return }
        _ = try await rpc(
            method: "initialize",
            params: [
                "protocolVersion": "2025-03-26",
                "capabilities": [String: Any](),
                "clientInfo": ["name": "MapboxNavigationAgent", "version": "0.1.0-alpha.3"],
            ],
            notify: false
        )
        try await rpc(method: "notifications/initialized", params: [:], notify: true)
    }

    private func rpc(method: String, params: [String: Any], notify: Bool = false) async throws -> [String: Any] {
        try ensureOpen()
        let id = state.withLock { state -> Int in
            state.nextID += 1
            return state.nextID
        }
        let token = accessToken
        let session = state.withLock { $0.sessionID }

        var payload: [String: Any] = ["jsonrpc": "2.0", "method": method]
        if !notify { payload["id"] = id }
        if !params.isEmpty { payload["params"] = params }
        var request = URLRequest(url: serverURL)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json, text/event-stream", forHTTPHeaderField: "Accept")
        if !token.isEmpty { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let session { request.setValue(session, forHTTPHeaderField: "Mcp-Session-Id") }
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)

        let (data, response) = try await urlSession.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw LiveVoiceException(message: "MCP request failed")
        }
        if !(200..<300).contains(http.statusCode) {
            throw LiveVoiceException(message: httpErrorMessage(data, status: http.statusCode))
        }
        if let sessionID = http.value(forHTTPHeaderField: "Mcp-Session-Id") {
            state.withLock { $0.sessionID = sessionID }
        }
        if notify { return [:] }
        return try responseObject(data, matching: id)
    }

    /// The JSON-RPC `result` whose `id` matches [id]. An HTTP error, a body with no `result`, or an
    /// event-stream message for a different request is a failure rather than an empty tool list.
    private func responseObject(_ data: Data, matching id: Int) throws -> [String: Any] {
        if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            return try resultObject(object, matching: id)
        }
        for payload in ssePayloads(String(decoding: data, as: UTF8.self)) {
            guard let object = jsonObject(payload), jsonRPCID(object["id"]) == id else { continue }
            return try resultObject(object, matching: id)
        }
        throw LiveVoiceException(message: "MCP request failed")
    }

    private func resultObject(_ object: [String: Any], matching id: Int) throws -> [String: Any] {
        if let eventID = jsonRPCID(object["id"]), eventID != id {
            throw LiveVoiceException(message: "MCP request failed")
        }
        if let error = object["error"] as? [String: Any] {
            throw LiveVoiceException(message: error["message"] as? String ?? "MCP request failed")
        }
        guard let result = object["result"] as? [String: Any] else {
            throw LiveVoiceException(message: "MCP request failed")
        }
        return result
    }

    /// Joins every `data:` line of one event with a newline, per the server-sent events format.
    private func ssePayloads(_ text: String) -> [String] {
        var payloads: [String] = []
        var dataLines: [String] = []
        func flush() {
            if dataLines.isEmpty { return }
            payloads.append(dataLines.joined(separator: "\n"))
            dataLines = []
        }
        for raw in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = raw.hasSuffix("\r") ? raw.dropLast() : raw
            if line.isEmpty {
                flush()
                continue
            }
            guard line.hasPrefix("data:") else { continue }
            var value = line.dropFirst(5)
            if value.first == " " { value = value.dropFirst() }
            dataLines.append(String(value))
        }
        flush()
        return payloads
    }

    private func jsonObject(_ text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private func jsonRPCID(_ value: Any?) -> Int? {
        if let number = value as? Int { return number }
        if let number = value as? NSNumber { return number.intValue }
        return nil
    }

    private func httpErrorMessage(_ data: Data, status: Int) -> String {
        let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        if let message = object?["message"] as? String, !message.isEmpty { return message }
        return "MCP request failed (\(status))"
    }
}
