import Foundation
import MapboxNavigationAgent

nonisolated final class FrameworkMcpSession: McpSession, @unchecked Sendable {
    let kotlin: McpClientSession

    init(serverURL: URL, accessToken: String, urlSession: URLSession = .shared) {
        kotlin = McpClientSession(serverUrl: serverURL.absoluteString, accessToken: accessToken, urlSession: urlSession)
    }

    func listTools() async throws -> [McpTool] {
        do {
            let tools = try await kotlin.listTools()
            return tools.map { tool in
                McpTool(
                    name: tool.name,
                    description: tool.description,
                    inputSchemaJSON: tool.inputSchemaJSON
                )
            }
        } catch {
            throw Self.map(error)
        }
    }

    func callTool(name: String, argumentsJSON: String) async throws -> McpToolResult {
        do {
            let result = try await kotlin.callTool(name: name, argumentsJson: argumentsJSON)
            return McpToolResult(isError: result.isError, text: result.text, structuredJSON: result.structuredJSON)
        } catch {
            throw Self.map(error)
        }
    }

    func close() {
        kotlin.close()
    }

    static func map(_ error: Error) -> Error {
        if error is CancellationError { return error }
        let nsError = error as NSError
        let description = String(describing: error)
        if error is McpSessionClosed
            || nsError.localizedDescription == "MCP session is closed"
            || description.contains("McpSessionClosed")
        {
            return McpClientError.closed
        }
        let message = nsError.localizedDescription.isEmpty ? description : nsError.localizedDescription
        return McpClientError.failed(message: message)
    }
}
