import Foundation
import MapboxNavigationAgent

public actor MapboxMcpClient {
    private let serverURL: URL
    private let accessToken: String
    private var box: SessionBox?
    private var closed = false

    public init(serverURL: URL, accessToken: String) {
        self.serverURL = serverURL
        self.accessToken = accessToken
    }

    init(session: McpSession) {
        self.serverURL = URL(string: "https://example.invalid")!
        self.accessToken = ""
        self.box = SessionBox(session)
    }

    public func listTools() async throws -> [McpTool] {
        try await session().listTools()
    }

    public func callTool(name: String, argumentsJSON: String) async throws -> McpToolResult {
        try await session().callTool(name: name, argumentsJSON: argumentsJSON)
    }

    /// Encodes `arguments` as JSON, calls the tool, and throws when the tool reports an error.
    public func callTool(name: String, arguments: [String: Any]) async throws -> String {
        let data = try JSONSerialization.data(withJSONObject: arguments, options: [.sortedKeys])
        let json = String(decoding: data, as: UTF8.self)
        let result = try await callTool(name: name, argumentsJSON: json)
        if result.isError { throw McpClientError.failed(message: result.text) }
        return result.text
    }

    func frameworkSession() throws -> McpClientSession {
        try session().frameworkSession()
    }

    public func close() {
        closed = true
        box?.close()
        box = nil
    }

    private func session() throws -> SessionBox {
        if closed { throw McpClientError.closed }
        if let box { return box }
        let created = SessionBox(FrameworkMcpSession(serverURL: serverURL, accessToken: accessToken))
        box = created
        return created
    }
}
