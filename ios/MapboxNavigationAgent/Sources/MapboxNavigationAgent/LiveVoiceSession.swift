import Foundation
import ViaKit

/// Receives a voice session's events. Calls arrive off the main thread.
public protocol LiveVoiceListener: AnyObject, Sendable {
    /// Base64 PCM of the assistant's voice, 16-bit mono at `LiveVoiceSession.sampleRate`.
    func onAudio(base64Pcm: String)
    func onToolCall(name: String, callId: String, arguments: String)
    /// `text` is what the voice model sees. `structuredJson` is for the app to draw.
    func onToolResult(name: String, callId: String, text: String, structuredJson: String?, isError: Bool)
    func onError(message: String)
    func onEnded()
}

public struct LiveVoiceException: Error, LocalizedError {
    public let message: String
    public var errorDescription: String? { message }
    public init(message: String) {
        self.message = message
    }
}

/// The Example-facing voice session. The socket, the tool loop, and the local tools live in
/// ViaKit's `ViaVoiceSession`. This type keeps the Example on one module and adapts its MCP client.
public final class LiveVoiceSession: @unchecked Sendable {
    public static let sampleRate = Int(ViaVoiceSession.companion.SAMPLE_RATE)
    public static let endConversationToolName = ViaVoiceSession.companion.END_CONVERSATION_TOOL_NAME
    public static let stopNavigationToolName = ViaVoiceSession.companion.STOP_NAVIGATION_TOOL_NAME

    public init(apiKey: String, mcp: McpClientSession, instructions: String, listener: LiveVoiceListener) {
        let tools = ToolBridge(mcp)
        let events = ListenerBridge(listener)
        toolBridge = tools
        listenerBridge = events
        session = ViaVoiceSession(apiKey: apiKey, tools: tools, instructions: instructions, listener: events)
    }

    public func appendAudio(base64Pcm: String) {
        session.appendAudio(base64Pcm: base64Pcm)
    }

    public func connect() async throws {
        try await session.connect()
    }

    public func stop() async throws {
        try await session.stop()
    }

    public func awaitEnd() async throws {
        try await session.awaitEnd()
    }

    private let session: ViaVoiceSession
    private let toolBridge: ToolBridge
    private let listenerBridge: ListenerBridge
}

private final class ToolBridge: NSObject, ViaVoiceToolClient {
    private let mcp: McpClientSession

    init(_ mcp: McpClientSession) {
        self.mcp = mcp
    }

    func listTools() async throws -> [ViaVoiceTool] {
        try await mcp.listTools().map { tool in
            ViaVoiceTool(name: tool.name, description: tool.description, inputSchemaJson: tool.inputSchemaJSON)
        }
    }

    func callTool(name: String, argumentsJson: String) async throws -> ViaVoiceToolResult {
        let result = try await mcp.callTool(name: name, argumentsJson: argumentsJson)
        return ViaVoiceToolResult(isError: result.isError, text: result.text, structuredJson: result.structuredJSON)
    }
}

private final class ListenerBridge: NSObject, ViaVoiceListener {
    private let listener: LiveVoiceListener

    init(_ listener: LiveVoiceListener) {
        self.listener = listener
    }

    func onAudio(base64Pcm: String) {
        listener.onAudio(base64Pcm: base64Pcm)
    }

    func onToolCall(name: String, callId: String, arguments: String) {
        listener.onToolCall(name: name, callId: callId, arguments: arguments)
    }

    func onToolResult(name: String, callId: String, text: String, structuredJson: String?, isError: Bool) {
        listener.onToolResult(
            name: name,
            callId: callId,
            text: text,
            structuredJson: structuredJson,
            isError: isError
        )
    }

    func onError(message: String) {
        listener.onError(message: message)
    }

    func onEnded() {
        listener.onEnded()
    }
}
