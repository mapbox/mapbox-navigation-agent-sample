import Foundation
import Testing
@testable import Example

private nonisolated let smokeOpenAIKey = ProcessInfo.processInfo.environment["OPENAI_API_KEY"] ?? ""
private nonisolated let smokeMapboxToken = ProcessInfo.processInfo.environment["MAPBOX_ACCESS_TOKEN"] ?? ""

/// Starts a real GPT-Live session and waits for it to get past connecting. Runs only when both keys are set.
@Test(.enabled(if: !smokeOpenAIKey.isEmpty && !smokeMapboxToken.isEmpty))
func voiceSessionGetsPastConnecting() async {
    let client = MapboxMcpClient(serverURL: MapboxMcpServerURL.mapbox, accessToken: smokeMapboxToken)
    let session = MapboxVoiceSession(client: client, openAIAPIKey: smokeOpenAIKey)

    let watcher = Task { () -> [MapboxVoiceEvent] in
        var seen: [MapboxVoiceEvent] = []
        for await event in session.events {
            seen.append(event)
            if event == .listening { break }
            if case .failed = event { break }
            if case .ended = event { break }
        }
        return seen
    }
    let start = Task { await session.start() }
    let timeout = Task {
        try? await Task.sleep(for: .seconds(45))
        watcher.cancel()
    }

    let seen = await watcher.value
    timeout.cancel()
    session.stop()
    await start.value

    #expect(seen.contains(.listening), "events: \(seen)")
}
