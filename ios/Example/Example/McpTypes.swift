import Foundation

nonisolated public struct McpTool: Sendable, Equatable {
    public let name: String
    public let description: String
    public let inputSchemaJSON: String

    public init(name: String, description: String, inputSchemaJSON: String) {
        self.name = name
        self.description = description
        self.inputSchemaJSON = inputSchemaJSON
    }
}

nonisolated public struct McpToolResult: Sendable, Equatable {
    public let isError: Bool
    public let text: String
    /// The result's `structuredContent` as JSON, such as GeoJSON from a geometry tool. Nil when the server sent none.
    public let structuredJSON: String?

    public init(isError: Bool, text: String, structuredJSON: String? = nil) {
        self.isError = isError
        self.text = text
        self.structuredJSON = structuredJSON
    }
}

nonisolated public enum McpClientError: LocalizedError, Equatable {
    case failed(message: String)
    case closed

    public var errorDescription: String? {
        switch self {
        case .failed(let message): message
        case .closed: "The MCP client is closed."
        }
    }
}

nonisolated public enum MapboxMcpServerURL {
    public static let mapbox = URL(string: "https://mcp.mapbox.com/mcp")!
    public static let devKit = URL(string: "https://mcp-devkit.mapbox.com/mcp")!
    public static let docs = URL(string: "https://mcp-docs.mapbox.com/mcp")!
}
