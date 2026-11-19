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
/// The GPT-Live socket, protocol, and tool loop run in ViaKit, behind `LiveVoiceSession`. This type
/// captures microphone audio, plays the assistant's replies, and reports what the session does.
nonisolated public final class MapboxVoiceSession: @unchecked Sendable {
    /// The tool the session runs itself to end the conversation.
    public static let endConversationToolName = LiveVoiceSession.endConversationToolName

    /// The tool the session runs itself to leave turn-by-turn guidance.
    public static let stopNavigationToolName = LiveVoiceSession.stopNavigationToolName

    public static let defaultInstructions = """
        You are a voice guide for a map. Use the Mapbox tools to search for places, look up details, and geocode. \
        When the user asks for directions, call directions_tool. The app starts turn-by-turn guidance from that result. \
        When the user asks to stop guidance, stop navigation, or stop directions, call \(stopNavigationToolName). \
        The app ends the trip and the conversation stays open. \
        Speak in one or two sentences. When the user says \"stop listening to me\", or that they are done, call \(endConversationToolName). \
        The app closes the listening session.
        """

    public let events: AsyncStream<MapboxVoiceEvent>

    private let client: MapboxMcpClient
    private let openAIAPIKey: String
    private let instructions: String
    private let continuation: AsyncStream<MapboxVoiceEvent>.Continuation
    private let audio = VoiceAudio()
    private let lock = NSLock()
    private var live: LiveVoiceSession?
    private var stopped = false
    private var running = false
    private var closing = false

    public init(client: MapboxMcpClient, openAIAPIKey: String, instructions: String = MapboxVoiceSession.defaultInstructions) {
        self.client = client
        self.openAIAPIKey = openAIAPIKey
        self.instructions = instructions
        var continuation: AsyncStream<MapboxVoiceEvent>.Continuation!
        events = AsyncStream { continuation = $0 }
        self.continuation = continuation
    }

    public func start() async {
        guard begin() else { return }

        defer {
            audio.stop()
            finish()
            emit(.ended)
            continuation.finish()
        }

        emit(.connecting)
        guard !openAIAPIKey.isEmpty else {
            emit(.failed(message: "An OpenAI API key is required to start a voice session."))
            return
        }

        var reportedFailure = false
        do {
            let live = LiveVoiceSession(
                apiKey: openAIAPIKey,
                mcp: try await client.frameworkSession(),
                instructions: instructions,
                listener: VoiceListener(self)
            )
            setLive(live)
            // The engine has to be playing before connect. The greeting arrives while connect is in flight,
            // and play() drops any buffer that shows up before the engine has started.
            try audio.start { chunk in
                live.appendAudio(base64Pcm: chunk.base64EncodedString())
            }
            try await live.connect()
            if !isStopped {
                emit(.listening)
                try await live.awaitEnd()
            }
        } catch is CancellationError {
        } catch {
            if !isStopped {
                reportedFailure = true
                emit(.failed(message: error.localizedDescription))
            }
        }
        await closeLive(reportFailure: !reportedFailure)
    }

    public func stop() {
        lock.lock()
        stopped = true
        lock.unlock()
        audio.stop()
        Task { await closeLive() }
    }

    /// One closer. `stop()` and `start()`'s cleanup can both ask, and a second `socket.close()` races the first.
    private func closeLive(reportFailure: Bool = false) async {
        guard let session = claimClose() else { return }
        do {
            try await session.stop()
        } catch is CancellationError {
        } catch {
            if reportFailure, !isStopped {
                emit(.failed(message: error.localizedDescription))
            }
        }
    }

    private func claimClose() -> LiveVoiceSession? {
        lock.lock()
        defer { lock.unlock() }
        guard let session = live, !closing else { return nil }
        closing = true
        return session
    }

    fileprivate func play(_ base64Pcm: String) {
        guard let pcm = Data(base64Encoded: base64Pcm) else { return }
        audio.play(pcm)
    }

    fileprivate func emit(_ event: MapboxVoiceEvent) {
        continuation.yield(event)
    }

    private func setLive(_ session: LiveVoiceSession) {
        lock.lock()
        live = session
        lock.unlock()
    }

    private func begin() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if running { return false }
        running = true
        stopped = false
        return true
    }

    private func finish() {
        lock.lock()
        running = false
        live = nil
        lock.unlock()
    }

    private var isStopped: Bool {
        lock.lock()
        defer { lock.unlock() }
        return stopped
    }
}

nonisolated private final class VoiceListener: NSObject, LiveVoiceListener, @unchecked Sendable {
    private weak var session: MapboxVoiceSession?

    init(_ session: MapboxVoiceSession) {
        self.session = session
    }

    func onAudio(base64Pcm: String) {
        session?.play(base64Pcm)
    }

    func onToolCall(name: String, callId: String, arguments: String) {
        session?.emit(.toolCall(name: name, callId: callId, arguments: arguments))
    }

    func onToolResult(name: String, callId: String, text: String, structuredJson: String?, isError: Bool) {
        session?.emit(.toolResult(name: name, callId: callId, text: text, isError: isError, structuredJSON: structuredJson))
    }

    func onError(message: String) {
        session?.emit(.serverError(message: message))
    }

    func onEnded() {}
}
