import Foundation
import Testing
@testable import Example

// Results below are from a live mcp.mapbox.com search_and_geocode_tool call for "Coit Tower" with the Ferry
// Building as proximity, recorded 2026-11-13.

private let ferryBuilding = Coordinate(latitude: 37.7955, longitude: -122.3937)

private func place(_ id: String, _ latitude: Double, _ longitude: Double) -> Place {
    Place(id: id, name: id, address: "", coordinate: Coordinate(latitude: latitude, longitude: longitude), category: nil)
}

@Test func farResultsAreDroppedWhenOthersAreNearby() {
    let coitTower = place("coit", 37.80259358, -122.40585873)
    let akron = place("akron", 41.09438583, -81.47501309)

    #expect(PlaceFilter.nearby([coitTower, akron], to: ferryBuilding) == [coitTower])
}

@Test func farResultsAreKeptWhenNoneAreNearby() {
    let paris = place("paris", 48.8584, 2.2945)
    let lasVegas = place("vegas", 36.1125, -115.1728)

    #expect(PlaceFilter.nearby([paris, lasVegas], to: ferryBuilding) == [paris, lasVegas])
}

@Test func nearbyResultsAreAllKept() {
    let oakland = place("oakland", 37.8044, -122.2712)
    let coitTower = place("coit", 37.80259358, -122.40585873)

    #expect(PlaceFilter.nearby([oakland, coitTower], to: ferryBuilding) == [oakland, coitTower])
}
