import AVFoundation
import Foundation
@preconcurrency import WebRTC

/// Minimal GPT-Live client over WebRTC.
///
/// Development only: the app exchanges the SDP offer with OpenAI directly using a standard API key.
nonisolated final class LiveSessionClient: NSObject, @unchecked Sendable {
    private let apiKey: String
    private let lock = NSLock()
    private var factory: RTCPeerConnectionFactory?
    private var peerConnection: RTCPeerConnection?
    private var audioTrack: RTCAudioTrack?
    private var audioSender: RTCRtpSender?
    private var dataChannel: RTCDataChannel?
    private var events: AsyncStream<String>.Continuation?
    private var pendingEvents: [String] = []
    private var eventsFinished = false
    private var iceContinuation: CheckedContinuation<Void, Never>?
    private var iceWatching = false
    private var iceQuietTask: Task<Void, Never>?
    private var iceTimeoutTask: Task<Void, Never>?
    private var didActivateAudioSession = false

    private static let sessionsURL = URL(string: "https://api.openai.com/v1/live/sessions")!
    private static let stunURL = "stun:stun.l.google.com:19302"
    private static let dataChannelLabel = "oai-events"
    private static let sessionModel = "gpt-live-1"
    private static let voice = "marin"
    private static let iceSettle: Duration = .milliseconds(500)
    private static let iceCap: Duration = .seconds(5)
    private static let sslReady: Bool = RTCInitializeSSL()

    init(apiKey: String) {
        self.apiKey = apiKey
        super.init()
        _ = Self.sslReady
    }

    deinit {
        disconnect()
    }

    func connect(instructions: String, delegation: LiveDelegation) async throws -> AsyncStream<String> {
        guard !apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw LiveSessionError.missingAPIKey
        }
        disconnect()
        prepareForConnect()
        do {
            return try await open(instructions: instructions, delegation: delegation)
        } catch {
            disconnect()
            throw error
        }
    }

    func send(_ event: String) async {
        guard let data = event.data(using: .utf8) else { return }
        let channel = currentDataChannel()
        _ = channel?.sendData(RTCDataBuffer(data: data, isBinary: false))
    }

    func requestClose() async {
        let eventID = "evt_\(UUID().uuidString)"
        await send("{\"type\":\"session.close\",\"event_id\":\"\(eventID)\"}")
    }

    func disconnect() {
        lock.lock()
        let channel = dataChannel
        let track = audioTrack
        let sender = audioSender
        let connection = peerConnection
        dataChannel = nil
        audioTrack = nil
        audioSender = nil
        peerConnection = nil
        let continuation = events
        events = nil
        eventsFinished = true
        pendingEvents.removeAll()
        iceWatching = false
        let iceContinuation = self.iceContinuation
        self.iceContinuation = nil
        iceQuietTask?.cancel()
        iceQuietTask = nil
        iceTimeoutTask?.cancel()
        iceTimeoutTask = nil
        lock.unlock()

        channel?.delegate = nil
        channel?.close()
        // M125's audio track has no close. Disable it and drop the sender, then close the connection.
        track?.isEnabled = false
        if let sender, let connection {
            _ = connection.removeTrack(sender)
        }
        connection?.delegate = nil
        connection?.close()
        continuation?.finish()
        iceContinuation?.resume()
        deactivateAudioSession()
    }

    private func prepareForConnect() {
        lock.lock()
        eventsFinished = false
        pendingEvents.removeAll()
        events = nil
        iceWatching = false
        iceContinuation = nil
        lock.unlock()
    }

    private func open(instructions: String, delegation: LiveDelegation) async throws -> AsyncStream<String> {
        try activateAudioSession()
        let connection = try makePeerConnection()
        let (track, sender) = try addAudioTrack(to: connection)
        let channel = try makeDataChannel(on: connection)
        channel.delegate = self
        retain(connection: connection, track: track, sender: sender, channel: channel)

        try await publishOffer(on: connection)
        await awaitIceGathering()
        let offer = try localOfferSDP()
        let answer = try await postSession(instructions: instructions, delegation: delegation, sdp: offer)
        let stream = makeEventStream()
        try await applyAnswer(answer, on: connection)
        return stream
    }

    private func retain(
        connection: RTCPeerConnection,
        track: RTCAudioTrack,
        sender: RTCRtpSender,
        channel: RTCDataChannel
    ) {
        lock.lock()
        peerConnection = connection
        audioTrack = track
        audioSender = sender
        dataChannel = channel
        lock.unlock()
    }

    /// `.defaultToSpeaker` has to live on the configuration WebRTC reapplies. `configureWebRTCSession`
    /// installs `webRTCConfiguration` when the audio unit starts, and that call replaces the category
    /// options, so a direct `AVAudioSession` change is gone before playback and voice chat routes to the receiver.
    private func activateAudioSession() throws {
        let configuration = RTCAudioSessionConfiguration.webRTC()
        configuration.categoryOptions = configuration.categoryOptions.union(.defaultToSpeaker)
        RTCAudioSessionConfiguration.setWebRTC(configuration)

        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        do {
            try session.setConfiguration(configuration, active: true)
        } catch {
            session.unlockForConfiguration()
            throw error
        }
        session.unlockForConfiguration()

        lock.lock()
        didActivateAudioSession = true
        lock.unlock()
    }

    private func deactivateAudioSession() {
        lock.lock()
        let shouldDeactivate = didActivateAudioSession
        didActivateAudioSession = false
        lock.unlock()
        guard shouldDeactivate else { return }

        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        defer { session.unlockForConfiguration() }
        try? session.setActive(false)
    }

    private func makePeerConnection() throws -> RTCPeerConnection {
        let configuration = RTCConfiguration()
        configuration.iceServers = [RTCIceServer(urlStrings: [Self.stunURL])]
        configuration.bundlePolicy = .maxBundle
        configuration.continualGatheringPolicy = .gatherOnce
        configuration.sdpSemantics = .unifiedPlan
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let connection = peerFactory().peerConnection(
            with: configuration,
            constraints: constraints,
            delegate: self
        ) else {
            throw LiveSessionError.peerConnectionFailed
        }
        return connection
    }

    private func peerFactory() -> RTCPeerConnectionFactory {
        lock.lock()
        if let factory {
            lock.unlock()
            return factory
        }
        lock.unlock()
        let created = RTCPeerConnectionFactory()
        lock.lock()
        if let factory {
            lock.unlock()
            return factory
        }
        factory = created
        lock.unlock()
        return created
    }

    private func addAudioTrack(to connection: RTCPeerConnection) throws -> (RTCAudioTrack, RTCRtpSender) {
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        let source = peerFactory().audioSource(with: constraints)
        let track = peerFactory().audioTrack(with: source, trackId: "audio0")
        guard let sender = connection.add(track, streamIds: ["audio"]) else {
            throw LiveSessionError.audioTrackFailed
        }
        return (track, sender)
    }

    private func makeDataChannel(on connection: RTCPeerConnection) throws -> RTCDataChannel {
        let configuration = RTCDataChannelConfiguration()
        configuration.isOrdered = true
        guard let channel = connection.dataChannel(forLabel: Self.dataChannelLabel, configuration: configuration) else {
            throw LiveSessionError.dataChannelFailed
        }
        return channel
    }

    private func publishOffer(on connection: RTCPeerConnection) async throws {
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.offer(for: constraints) { sdp, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                guard let sdp else {
                    continuation.resume(throwing: LiveSessionError.offerFailed)
                    return
                }
                connection.setLocalDescription(sdp) { error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else {
                        continuation.resume()
                    }
                }
            }
        }
    }

    /// Gathering complete, 500 ms with no new candidate, or the 5 second cap — whichever comes first.
    private func awaitIceGathering() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            lock.lock()
            iceContinuation = continuation
            iceWatching = true
            let connection = peerConnection
            iceTimeoutTask = Task { [weak self] in
                try? await Task.sleep(for: Self.iceCap)
                guard !Task.isCancelled else { return }
                self?.finishIceWait()
            }
            lock.unlock()
            // WebRTC answers this on its signaling thread, which can be waiting on `lock` in a delegate
            // callback. Asking while holding the lock would deadlock both threads, so ask outside it.
            if connection?.iceGatheringState == .complete {
                finishIceWait()
            }
        }
    }

    private func noteIceCandidate() {
        lock.lock()
        guard iceWatching, iceContinuation != nil else {
            lock.unlock()
            return
        }
        iceQuietTask?.cancel()
        iceQuietTask = Task { [weak self] in
            try? await Task.sleep(for: Self.iceSettle)
            guard !Task.isCancelled else { return }
            self?.finishIceWait()
        }
        lock.unlock()
    }

    private func noteIceGatheringComplete() {
        finishIceWait()
    }

    private func finishIceWait() {
        lock.lock()
        guard iceWatching || iceContinuation != nil else {
            lock.unlock()
            return
        }
        iceWatching = false
        let continuation = iceContinuation
        iceContinuation = nil
        iceQuietTask?.cancel()
        iceQuietTask = nil
        iceTimeoutTask?.cancel()
        iceTimeoutTask = nil
        lock.unlock()
        continuation?.resume()
    }

    private func localOfferSDP() throws -> String {
        lock.lock()
        let connection = peerConnection
        lock.unlock()
        // Read the description outside the lock for the same reason as `awaitIceGathering`.
        let sdp = connection?.localDescription?.sdp
        guard let sdp, !sdp.isEmpty else {
            throw LiveSessionError.missingLocalDescription
        }
        return sdp
    }

    private func postSession(instructions: String, delegation: LiveDelegation, sdp: String) async throws -> String {
        var request = URLRequest(url: Self.sessionsURL)
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try sessionBody(instructions: instructions, delegation: delegation, sdp: sdp)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw LiveSessionError.missingAnswerSDP
        }
        guard (200 ..< 300).contains(http.statusCode) else {
            throw LiveSessionError.requestFailed(status: http.statusCode)
        }
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let transport = json["transport"] as? [String: Any],
              let answer = transport["sdp"] as? String,
              !answer.isEmpty else {
            throw LiveSessionError.missingAnswerSDP
        }
        return answer
    }

    private func sessionBody(instructions: String, delegation: LiveDelegation, sdp: String) throws -> Data {
        let tools: [[String: Any]] = delegation.tools.map { tool in
            [
                "type": "function",
                "name": tool.name,
                "description": tool.description,
                "parameters": tool.parameters,
                "strict": false,
            ]
        }
        let body: [String: Any] = [
            "session": [
                "model": Self.sessionModel,
                "instructions": instructions,
                "audio": [
                    "output": [
                        "voice": Self.voice,
                    ],
                ],
                "delegation": [
                    "type": "responses",
                    "responses": [
                        "model": delegation.model,
                        "instructions": delegation.instructions,
                        "tools": tools,
                        "tool_choice": delegation.toolChoice,
                        "parallel_tool_calls": delegation.parallelToolCalls,
                        "reasoning": [
                            "effort": delegation.reasoningEffort,
                        ],
                    ],
                ],
            ],
            "transport": [
                "type": "webrtc",
                "sdp": sdp,
            ],
        ]
        return try JSONSerialization.data(withJSONObject: body)
    }

    private func applyAnswer(_ sdp: String, on connection: RTCPeerConnection) async throws {
        let answer = RTCSessionDescription(type: .answer, sdp: sdp)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.setRemoteDescription(answer) { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    private func makeEventStream() -> AsyncStream<String> {
        AsyncStream { continuation in
            self.lock.lock()
            if self.eventsFinished {
                self.lock.unlock()
                continuation.finish()
                return
            }
            let buffered = self.pendingEvents
            self.pendingEvents.removeAll()
            self.events = continuation
            self.lock.unlock()
            buffered.forEach { continuation.yield($0) }
        }
    }

    private func emit(_ text: String) {
        lock.lock()
        if eventsFinished {
            lock.unlock()
            return
        }
        if let events {
            lock.unlock()
            events.yield(text)
            return
        }
        pendingEvents.append(text)
        lock.unlock()
    }

    private func finishEvents() {
        lock.lock()
        eventsFinished = true
        let continuation = events
        events = nil
        lock.unlock()
        continuation?.finish()
    }

    private func currentDataChannel() -> RTCDataChannel? {
        lock.lock()
        let channel = dataChannel
        lock.unlock()
        return channel
    }
}

// WebRTC calls these on its signaling thread, so they must not inherit the default MainActor isolation.
nonisolated extension LiveSessionClient: RTCPeerConnectionDelegate {
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}

    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        guard peerConnection === currentPeerConnection() else { return }
        if newState == .complete {
            noteIceGatheringComplete()
        }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        guard peerConnection === currentPeerConnection() else { return }
        noteIceCandidate()
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}

    private func currentPeerConnection() -> RTCPeerConnection? {
        lock.lock()
        let connection = peerConnection
        lock.unlock()
        return connection
    }
}

nonisolated extension LiveSessionClient: RTCDataChannelDelegate {
    func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {
        guard dataChannel === currentDataChannel() else { return }
        if dataChannel.readyState == .closed {
            finishEvents()
        }
    }

    func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        guard dataChannel === currentDataChannel(), !buffer.isBinary else { return }
        guard let text = String(data: buffer.data, encoding: .utf8) else { return }
        emit(text)
    }
}

private enum LiveSessionError: Error {
    case missingAPIKey
    case peerConnectionFailed
    case audioTrackFailed
    case dataChannelFailed
    case offerFailed
    case missingLocalDescription
    case requestFailed(status: Int)
    case missingAnswerSDP
}
