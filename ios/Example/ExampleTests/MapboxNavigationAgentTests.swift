import Foundation
import Testing
@testable import Example

private struct FakeSession: McpSession {
    var tools: [McpTool]
    var result: McpToolResult
    var error: McpClientError?

    func listTools() async throws -> [McpTool] {
        if let error { throw error }
        return tools
    }

    func callTool(name: String, argumentsJSON: String) async throws -> McpToolResult {
        if let error { throw error }
        return result
    }

    func close() {}
}

@Test func listToolsCopiesFieldsFromTheSession() async throws {
    let client = MapboxMcpClient(
        session: FakeSession(
            tools: [McpTool(name: "search", description: "Search places", inputSchemaJSON: "{}")],
            result: McpToolResult(isError: false, text: ""),
            error: nil
        )
    )

    let tools = try await client.listTools()

    #expect(tools == [McpTool(name: "search", description: "Search places", inputSchemaJSON: "{}")])
}

@Test func callToolCopiesFieldsFromTheSession() async throws {
    let client = MapboxMcpClient(
        session: FakeSession(
            tools: [],
            result: McpToolResult(isError: false, text: "one\ntwo"),
            error: nil
        )
    )

    let result = try await client.callTool(name: "search", argumentsJSON: "{}")

    #expect(result == McpToolResult(isError: false, text: "one\ntwo"))
}

@Test func failedSessionErrorSurfaces() async {
    let client = MapboxMcpClient(
        session: FakeSession(
            tools: [],
            result: McpToolResult(isError: false, text: ""),
            error: .failed(message: "down")
        )
    )

    await #expect(throws: McpClientError.failed(message: "down")) {
        try await client.listTools()
    }
}

@Test func callsAfterCloseThrowClosed() async {
    let client = MapboxMcpClient(
        session: FakeSession(
            tools: [],
            result: McpToolResult(isError: false, text: ""),
            error: nil
        )
    )
    await client.close()

    await #expect(throws: McpClientError.closed) {
        try await client.listTools()
    }
    await #expect(throws: McpClientError.closed) {
        try await client.callTool(name: "search", argumentsJSON: "{}")
    }
}

@Test func serverURLsMatchThePublicMapboxHosts() {
    #expect(MapboxMcpServerURL.mapbox == URL(string: "https://mcp.mapbox.com/mcp"))
    #expect(MapboxMcpServerURL.devKit == URL(string: "https://mcp-devkit.mapbox.com/mcp"))
    #expect(MapboxMcpServerURL.docs == URL(string: "https://mcp-docs.mapbox.com/mcp"))
}

@Test func otherFrameworkErrorsUseTheExceptionMessage() {
    let error = NSError(domain: "test", code: 1, userInfo: [NSLocalizedDescriptionKey: "down"])
    #expect(FrameworkMcpSession.map(error) as? McpClientError == .failed(message: "down"))
}

@Test func closedSessionBecomesClosedError() async {
    let session = FrameworkMcpSession(
        serverURL: URL(string: "https://mcp.test/mcp")!,
        accessToken: "token"
    )
    session.close()

    await #expect(throws: McpClientError.closed) {
        try await session.listTools()
    }
}

@Test func voiceSessionWithoutAnOpenAIKeyFailsAndEnds() async {
    let client = MapboxMcpClient(serverURL: URL(string: "https://mcp.test/mcp")!, accessToken: "token")
    let session = MapboxVoiceSession(client: client, openAIAPIKey: "")

    await session.start()

    #expect(await firstEvents(session, count: 3) == [
        .connecting,
        .failed(message: "An OpenAI API key is required to start a voice session."),
        .ended,
    ])
}

@Test func voiceSessionNeedsAFrameworkClient() async {
    let client = MapboxMcpClient(
        session: FakeSession(tools: [], result: McpToolResult(isError: false, text: ""), error: nil)
    )
    let session = MapboxVoiceSession(client: client, openAIAPIKey: "sk-test")

    await session.start()

    #expect(await firstEvents(session, count: 3) == [
        .connecting,
        .failed(message: "This client can't run a voice session."),
        .ended,
    ])
}

private func firstEvents(_ session: MapboxVoiceSession, count: Int) async -> [MapboxVoiceEvent] {
    var events: [MapboxVoiceEvent] = []
    for await event in session.events {
        events.append(event)
        if events.count == count { break }
    }
    return events
}

private nonisolated let liveToken = ProcessInfo.processInfo.environment["MAPBOX_ACCESS_TOKEN"] ?? ""

@Test(.enabled(if: !liveToken.isEmpty))
func liveMapboxServerListsToolsWhenTokenIsSet() async throws {
    let client = MapboxMcpClient(serverURL: MapboxMcpServerURL.mapbox, accessToken: liveToken)

    let tools = try await client.listTools()

    #expect(!tools.isEmpty)
    await client.close()
}

private final class RecordingSession: McpSession, @unchecked Sendable {
    var argumentsJSON: String?
    var result: McpToolResult

    init(result: McpToolResult) {
        self.result = result
    }

    func listTools() async throws -> [McpTool] { [] }

    func callTool(name: String, argumentsJSON: String) async throws -> McpToolResult {
        self.argumentsJSON = argumentsJSON
        return result
    }

    func close() {}
}

@Test func callToolEncodesArgumentsAndReturnsTheText() async throws {
    let session = RecordingSession(result: McpToolResult(isError: false, text: "ok"))
    let client = MapboxMcpClient(session: session)

    let text = try await client.callTool(name: "place_details_tool", arguments: ["mapbox_id": "abc"])

    #expect(text == "ok")
    #expect(session.argumentsJSON == #"{"mapbox_id":"abc"}"#)
}

@Test func callToolThrowsWhenTheToolReportsAnError() async {
    let client = MapboxMcpClient(session: RecordingSession(result: McpToolResult(isError: true, text: "nope")))

    await #expect(throws: McpClientError.failed(message: "nope")) {
        try await client.callTool(name: "search", arguments: [:])
    }
}

@Test func toolTextReadsPlacesDetailsAndDropsTheMapHint() {
    let list = """
    1. Ferry Building
    Mapbox ID: abc
    Address: 1 Ferry
    Coordinates: 37.79, -122.39
    Category: cafe, coffee

    If the user wants a map, pass this ref
    """
    let places = MapboxToolText.places(fromList: list)
    #expect(places.count == 1)
    #expect(places[0].id == "abc")
    #expect(places[0].name == "Ferry Building")
    #expect(places[0].coordinate.latitude == 37.79)
    #expect(places[0].category == "Cafe")

    let collection = """
    {"features":[{"properties":{"mapbox_id":"xyz","name":"Park","full_address":"2 Park","coordinates":{"latitude":1.5,"longitude":2.5},"poi_category":["park"]}}]}
    """
    let features = MapboxToolText.places(fromFeatureCollection: collection)
    #expect(features == [Place(id: "xyz", name: "Park", address: "2 Park", coordinate: Coordinate(latitude: 1.5, longitude: 2.5), category: "Park")])

    let details = MapboxToolText.details("""
    Phone: +1
    Website: https://example.com
    Hours:
      Mo 9-5
    Photos: https://example.com/photo-s/a, https://example.com/photo-l/b
    """)
    #expect(details.phone == "+1")
    #expect(details.website == URL(string: "https://example.com"))
    #expect(details.hours == [OpeningHours(day: "Mo", hours: "9-5")])
    #expect(details.photo == URL(string: "https://example.com/photo-l/b"))
}

@Test func voiceResultsStayWithTheirCallAndBecomePlaces() {
    var log = MapboxToolLog()
    let text = """
    1. Ferry Building
    Mapbox ID: abc
    Address: 1 Ferry
    Coordinates: 37.79, -122.39
    """

    _ = log.record(.toolCall(name: "search", callId: "a", arguments: "{}"))
    _ = log.record(.toolCall(name: "search", callId: "b", arguments: "{}"))
    let places = log.record(.toolResult(name: "search", callId: "a", text: text, isError: false))

    #expect(log.calls.map(\.callId) == ["a", "b"])
    #expect(log.calls[0].result == text)
    #expect(log.calls[1].result == nil)
    #expect(places.map(\.id) == ["abc"])
}

@Test func structuredResultStaysWithItsCall() {
    var log = MapboxToolLog()
    let geoJSON = #"{"type":"FeatureCollection","features":[]}"#

    _ = log.record(.toolCall(name: "isochrone_tool", callId: "i", arguments: "{}"))
    _ = log.record(.toolResult(name: "isochrone_tool", callId: "i", text: "Found 1 contour", isError: false, structuredJSON: geoJSON))
    _ = log.record(.toolCall(name: "search", callId: "s", arguments: "{}"))
    _ = log.record(.toolResult(name: "search", callId: "s", text: "none", isError: false))

    #expect(log.calls[0].structuredResult == geoJSON)
    #expect(log.calls[1].structuredResult == nil)
}

@Test func callToolKeepsStructuredContent() async throws {
    let structured = McpToolResult(isError: false, text: "Distance: 3.2 km", structuredJSON: #"{"distance":3.2}"#)
    let client = MapboxMcpClient(session: FakeSession(tools: [], result: structured, error: nil))

    let result = try await client.callTool(name: "distance_tool", argumentsJSON: "{}")

    #expect(result.structuredJSON == #"{"distance":3.2}"#)
}

@Test func directionsResultBecomesARouteToTheLastWaypoint() {
    let text = """
    {
      "waypoints": [
        {"name": "Ferry Building", "snap_location": [-122.3937, 37.7955]},
        {"name": "Coit Tower", "snap_location": [-122.4058, 37.8024]}
      ]
    }

    If the user wants a map, pass this ref
    """
    let arguments = """
    {"coordinates":[{"longitude":-122.3937,"latitude":37.7955},{"longitude":-122.41,"latitude":37.81}],"routing_profile":"mapbox/walking"}
    """

    let route = MapboxToolText.directions(text: text, arguments: arguments)

    #expect(route?.destination.name == "Coit Tower")
    #expect(route?.destination.coordinate.latitude == 37.8024)
    #expect(route?.destination.coordinate.longitude == -122.4058)
    #expect(route?.profile == .walking)
}

@Test func directionsFallsBackToTheLastArgumentCoordinate() {
    let arguments = """
    {"coordinates":[{"longitude":-122.39,"latitude":37.79},{"longitude":-122.41,"latitude":37.81}],"routing_profile":"mapbox/cycling"}
    """

    let route = MapboxToolText.directions(text: "Route found: 1 mile", arguments: arguments)

    #expect(route?.destination.coordinate.latitude == 37.81)
    #expect(route?.destination.coordinate.longitude == -122.41)
    #expect(route?.profile == .cycling)
    #expect(MapboxToolText.directions(text: "nope", arguments: "{}") == nil)
}

@Test func recordedDirectionsResultIsARoute() {
    var log = MapboxToolLog()
    let arguments = #"{"routing_profile":"mapbox/driving-traffic","coordinates":[{"longitude":-122.39,"latitude":37.79},{"longitude":-122.41,"latitude":37.81}]}"#
    let text = #"{"waypoints":[{"name":"Start","snap_location":[-122.39,37.79]},{"name":"Park","snap_location":[-122.41,37.81]}]}"#

    _ = log.record(.toolCall(name: "directions_tool", callId: "d", arguments: arguments))
    let places = log.record(.toolResult(name: "directions_tool", callId: "d", text: text, isError: false))

    #expect(places.isEmpty)
    #expect(log.route(callId: "d")?.destination.name == "Park")
    #expect(log.route(callId: "d")?.profile == .driving)
    #expect(log.route(callId: "missing") == nil)
}
