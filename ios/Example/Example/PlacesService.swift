import Foundation

/// Place details for a pin the voice session already found. The Ferry Building is where this sample starts.
final class PlacesService {
    /// The fixed location this sample app searches around: the Ferry Building, San Francisco.
    static let origin = Coordinate(latitude: 37.7955, longitude: -122.3937)

    private let client: MapboxMcpClient

    init() {
        let token = Bundle.main.object(forInfoDictionaryKey: "MBXAccessToken") as? String ?? ""
        client = MapboxMcpClient(serverURL: MapboxMcpServerURL.mapbox, accessToken: token)
    }

    func details(for place: Place) async throws -> PlaceDetails {
        let text = try await client.callTool(name: "place_details_tool", arguments: ["mapbox_id": place.id])
        return MapboxToolText.details(text)
    }
}
