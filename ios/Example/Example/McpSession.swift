import Foundation
import MapboxNavigationAgent

nonisolated protocol McpSession: Sendable {
    func listTools() async throws -> [McpTool]
    func callTool(name: String, argumentsJSON: String) async throws -> McpToolResult
    func close()
}

nonisolated final class SessionBox: @unchecked Sendable {
    private let lock = NSLock()
    private var session: McpSession?

    init(_ session: McpSession) {
        self.session = session
    }

    func listTools() async throws -> [McpTool] {
        try await openSession().listTools()
    }

    func callTool(name: String, argumentsJSON: String) async throws -> McpToolResult {
        try await openSession().callTool(name: name, argumentsJSON: argumentsJSON)
    }

    /// The Kotlin session behind this box, which a voice session calls tools through.
    func frameworkSession() throws -> McpClientSession {
        guard let framework = try openSession() as? FrameworkMcpSession else {
            throw McpClientError.failed(message: "This client can't run a voice session.")
        }
        return framework.kotlin
    }

    func close() {
        var current: McpSession?
        lock.lock()
        current = session
        session = nil
        lock.unlock()
        current?.close()
    }

    deinit {
        close()
    }

    private func openSession() throws -> McpSession {
        lock.lock()
        defer { lock.unlock() }
        guard let session else { throw McpClientError.closed }
        return session
    }
}
