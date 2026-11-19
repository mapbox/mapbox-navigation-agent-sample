import Foundation
import Testing
@testable import Example

// Shapes below are trimmed from live mcp.mapbox.com responses (structuredContent), recorded 2026-11-12.

private let square: [[Coordinate]] = [[
    Coordinate(latitude: 37.79, longitude: -122.4),
    Coordinate(latitude: 37.79, longitude: -122.39),
    Coordinate(latitude: 37.8, longitude: -122.39),
    Coordinate(latitude: 37.79, longitude: -122.4),
]]

@Test func isochroneFeatureCollectionBecomesPolygons() {
    let json = """
    {"type":"FeatureCollection","features":[{"type":"Feature","properties":{"contour":10,"fill":"#bf4040"},
    "geometry":{"type":"Polygon","coordinates":[[[-122.4,37.79],[-122.39,37.79],[-122.39,37.8],[-122.4,37.79]]]}}]}
    """

    let overlay = MapboxToolGeometry.overlay(toolName: "isochrone_tool", structuredJSON: json)

    #expect(overlay?.shapes == [.polygon(square)])
}

@Test func geometryKeyBecomesItsShape() {
    let json = """
    {"geometry":{"type":"Polygon","coordinates":[[[-122.4,37.79],[-122.39,37.79],[-122.39,37.8],[-122.4,37.79]]]},
    "type":"Polygon","mapboxRender":{"ref":"mapbox://compute/union?data=abc"}}
    """

    #expect(MapboxToolGeometry.overlay(toolName: "union_tool", structuredJSON: json)?.shapes == [.polygon(square)])
}

@Test func bufferedPolygonBecomesAPolygon() {
    let json = """
    {"bufferedPolygon":[[[-122.4,37.79],[-122.39,37.79],[-122.39,37.8],[-122.4,37.79]]],"distance":1,"units":"kilometers"}
    """

    #expect(MapboxToolGeometry.overlay(toolName: "buffer_tool", structuredJSON: json)?.shapes == [.polygon(square)])
}

@Test func simplifiedCoordinatesBecomeALine() {
    let json = """
    {"simplified":[[-122.4,37.79],[-122.398,37.7899],[-122.39,37.8]],"originalVertexCount":4,"simplifiedVertexCount":3}
    """

    let overlay = MapboxToolGeometry.overlay(toolName: "simplify_tool", structuredJSON: json)

    #expect(overlay?.shapes == [.line([
        Coordinate(latitude: 37.79, longitude: -122.4),
        Coordinate(latitude: 37.7899, longitude: -122.398),
        Coordinate(latitude: 37.8, longitude: -122.39),
    ])])
}

@Test func bboxBecomesAClosedRectangle() {
    let overlay = MapboxToolGeometry.overlay(toolName: "bbox_tool", structuredJSON: #"{"bbox":[-122.4,37.79,-122.39,37.8]}"#)

    #expect(overlay?.shapes == [.polygon([[
        Coordinate(latitude: 37.79, longitude: -122.4),
        Coordinate(latitude: 37.79, longitude: -122.39),
        Coordinate(latitude: 37.8, longitude: -122.39),
        Coordinate(latitude: 37.8, longitude: -122.4),
        Coordinate(latitude: 37.79, longitude: -122.4),
    ]])])
}

@Test func mapMatchingsBecomeLines() {
    let json = """
    {"code":"Ok","matchings":[{"confidence":0.89,"distance":593.8,
    "geometry":{"type":"LineString","coordinates":[[-122.393986,37.795205],[-122.3895,37.7915]]}}],"tracepoints":[]}
    """

    let overlay = MapboxToolGeometry.overlay(toolName: "map_matching_tool", structuredJSON: json)

    #expect(overlay?.shapes == [.line([
        Coordinate(latitude: 37.795205, longitude: -122.393986),
        Coordinate(latitude: 37.7915, longitude: -122.3895),
    ])])
}

@Test func pointsWithinBecomePoints() {
    let json = #"{"points_within":[{"longitude":-122.395,"latitude":37.795}],"count":1,"total":2}"#

    let overlay = MapboxToolGeometry.overlay(toolName: "points_within_polygon_tool", structuredJSON: json)

    #expect(overlay?.shapes == [.point(Coordinate(latitude: 37.795, longitude: -122.395))])
}

@Test func resultsWithoutGeometryHaveNoOverlay() {
    let distance = #"{"distance":3.217,"units":"kilometers","from":{"longitude":-122.3937,"latitude":37.7955}}"#

    #expect(MapboxToolGeometry.overlay(toolName: "distance_tool", structuredJSON: distance) == nil)
    #expect(MapboxToolGeometry.overlay(toolName: "union_tool", structuredJSON: nil) == nil)
    #expect(MapboxToolGeometry.overlay(toolName: "union_tool", structuredJSON: "not json") == nil)
}

@Test func toolsThatAlreadyDrawThemselvesHaveNoOverlay() {
    let points = #"{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[-122.39,37.79]}}]}"#

    #expect(MapboxToolGeometry.overlay(toolName: "category_search_tool", structuredJSON: points) == nil)
    #expect(MapboxToolGeometry.overlay(toolName: "directions_tool", structuredJSON: points) == nil)
}

@Test func overlayCoordinatesCoverEveryShape() {
    let overlay = ToolOverlay(shapes: [
        .point(Coordinate(latitude: 1, longitude: 2)),
        .line([Coordinate(latitude: 3, longitude: 4)]),
        .polygon([[Coordinate(latitude: 5, longitude: 6)]]),
    ])

    #expect(overlay.coordinates.map(\.latitude) == [1, 3, 5])
}

@Test func computedPointsBecomePoints() {
    let results = [
        ("centroid_tool", #"{"centroid":{"longitude":-122.393333,"latitude":37.793333}}"#),
        ("midpoint_tool", #"{"midpoint":{"longitude":-122.393333,"latitude":37.793333},"from":{"longitude":-122.3937,"latitude":37.7955},"to":{"longitude":-122.4058,"latitude":37.8024}}"#),
        ("destination_tool", #"{"destination":{"longitude":-122.393333,"latitude":37.793333},"distance":1,"bearing":90,"units":"kilometers"}"#),
        ("nearest_point_tool", #"{"nearest":{"longitude":-122.393333,"latitude":37.793333},"distance":127.0,"units":"meters","index":1}"#),
        ("nearest_point_on_line_tool", #"{"nearest":{"longitude":-122.393333,"latitude":37.793333},"distance":0.05,"units":"kilometers","location":0.82}"#),
    ]

    for (tool, json) in results {
        let overlay = MapboxToolGeometry.overlay(toolName: tool, structuredJSON: json)
        #expect(overlay?.shapes == [.point(Coordinate(latitude: 37.793333, longitude: -122.393333))], "\(tool)")
    }
}

@Test func groundLocationHasNoOverlay() {
    let json = #"{"place":"Financial District","longitude":-122.3937,"latitude":37.7955,"isochrone":{"profile":"mapbox/walking","contours_minutes":[15,10,5]}}"#

    #expect(MapboxToolGeometry.overlay(toolName: "ground_location_tool", structuredJSON: json) == nil)
}

@Test func groundLocationNearbyPlacesBecomePins() {
    var log = MapboxToolLog()
    let json = """
    {"place":"Financial District","longitude":-122.3937,"latitude":37.7955,
    "nearby_pois":[{"name":"Blue Bottle Coffee","address":"Ferry Building #7","longitude":-122.3938434,"latitude":37.7960026,
    "category":"café","distance_meters":57,"mapbox_id":"bb1"},{"name":"No id","longitude":-122.39,"latitude":37.79}]}
    """

    _ = log.record(.toolCall(name: "ground_location_tool", callId: "g", arguments: "{}"))
    let places = log.record(.toolResult(
        name: "ground_location_tool",
        callId: "g",
        text: "**Financial District**\n\nNearby places:\n- Blue Bottle Coffee — Ferry Building #7 (57m)",
        isError: false,
        structuredJSON: json
    ))

    #expect(places == [Place(
        id: "bb1",
        name: "Blue Bottle Coffee",
        address: "Ferry Building #7",
        coordinate: Coordinate(latitude: 37.7960026, longitude: -122.3938434),
        category: "Café"
    )])
}

@Test func optimizedTripBecomesALineAndNumberedStops() {
    let json = """
    {"code":"Ok","waypoints":[
    {"name":"The Embarcadero","location":[-122.393985,37.795332],"waypoint_index":0,"trips_index":0},
    {"name":"Market Street","location":[-122.419449,37.774939],"waypoint_index":2,"trips_index":0},
    {"name":"Telegraph Hill Boulevard","location":[-122.405617,37.802488],"waypoint_index":1,"trips_index":0}],
    "trips":[{"distance":10080,"duration":2562,"geometry":{"type":"LineString","coordinates":[[-122.393985,37.795332],[-122.405617,37.802488]]}}]}
    """

    let overlay = MapboxToolGeometry.overlay(toolName: "optimization_tool", structuredJSON: json)

    #expect(overlay?.shapes == [
        .line([
            Coordinate(latitude: 37.795332, longitude: -122.393985),
            Coordinate(latitude: 37.802488, longitude: -122.405617),
        ]),
        .point(Coordinate(latitude: 37.795332, longitude: -122.393985), label: "1"),
        .point(Coordinate(latitude: 37.774939, longitude: -122.419449), label: "3"),
        .point(Coordinate(latitude: 37.802488, longitude: -122.405617), label: "2"),
    ])
}
