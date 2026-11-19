import Foundation
import Testing
@testable import Example

// Shapes below are trimmed from live mcp.mapbox.com responses, recorded 2026-11-12.

@Test func measurementsUseTheServersFirstLine() {
    let results = [
        ("distance_tool", "Distance: 3.217 kilometers\nFrom: [-122.3937, 37.7955]\nTo: [-122.4194, 37.7749]"),
        ("length_tool", "Length: 1.311 kilometers\nCoordinates: 2 points"),
        ("area_tool", "Area: 0.489 square kilometers"),
        ("bearing_tool", "\nBearing: 305.82° (NW)\nFrom: [-122.3937, 37.7955]"),
    ]

    let summaries = results.map { MapboxToolSummary.summary(toolName: $0.0, text: $0.1, structuredJSON: nil) }

    #expect(summaries == [
        "Distance: 3.217 kilometers",
        "Length: 1.311 kilometers",
        "Area: 0.489 square kilometers",
        "Bearing: 305.82° (NW)",
    ])
}

@Test func measurementWithABlankFirstLineHasNoSummary() {
    let summary = MapboxToolSummary.summary(toolName: "bearing_tool", text: "   \nBearing: 305.82° (NW)", structuredJSON: nil)

    #expect(summary == nil)
}

@Test func matrixListsTravelTimesFromTheFirstOrigin() {
    let json = """
    {"code":"Ok","durations":[[0,1571.8,2674.1]],"distances":[[0,2182.2,3715]],
    "sources":[{"name":"","location":[-122.39,37.79]}],
    "destinations":[{"name":""},{"name":"Columbus Avenue"},{"name":""}]}
    """
    let arguments = #"{"profile":"mapbox/walking"}"#

    let summary = MapboxToolSummary.summary(toolName: "matrix_tool", text: "{}", structuredJSON: json, arguments: arguments)

    #expect(summary == "Walking times: Columbus Avenue 26 min (2.2 km) · stop 3 45 min (3.7 km)")
}

@Test func matrixWithSeveralOriginsSaysHowManyMore() {
    let json = #"{"durations":[[0,600],[620,0]],"destinations":[{"name":"A"},{"name":"B"}]}"#

    let summary = MapboxToolSummary.summary(toolName: "matrix_tool", text: "{}", structuredJSON: json)

    #expect(summary == "Travel times: B 10 min · from 1 more origin")
}

@Test func longDurationsUseHours() {
    let json = #"{"durations":[[0,4500]],"destinations":[{"name":""},{"name":"Oakland"}]}"#

    #expect(MapboxToolSummary.summary(toolName: "matrix_tool", text: "{}", structuredJSON: json) == "Travel times: Oakland 1 h 15 min")
}

@Test func categoryListShowsTheFirstFewReadably() {
    let json = #"{"listItems":["shopping","services","food_and_drink","food","office","restaurant","grocery"]}"#

    let summary = MapboxToolSummary.summary(toolName: "category_list_tool", text: "{}", structuredJSON: json)

    #expect(summary == "Categories: shopping, services, food and drink, food, office, and 2 more")
}

@Test func resourceReaderNamesTheResource() {
    let json = #"{"uri":"mapbox://version","mimeType":"application/json","text":"{}"}"#

    #expect(MapboxToolSummary.summary(toolName: "resource_reader_tool", text: "{}", structuredJSON: json) == "Read mapbox://version")
}

@Test func otherToolsHaveNoSummary() {
    #expect(MapboxToolSummary.summary(toolName: "isochrone_tool", text: "Found 1 contour", structuredJSON: nil) == nil)
    #expect(MapboxToolSummary.summary(toolName: "search_and_geocode_tool", text: "1. Ferry", structuredJSON: nil) == nil)
    #expect(MapboxToolSummary.summary(toolName: "matrix_tool", text: "{}", structuredJSON: nil) == nil)
}

@Test func optimizedTripSaysStopsTimeAndDistance() {
    let json = """
    {"waypoints":[{"waypoint_index":0},{"waypoint_index":1},{"waypoint_index":2},{"waypoint_index":3}],
    "trips":[{"distance":10080,"duration":2562}]}
    """

    #expect(MapboxToolSummary.summary(toolName: "optimization_tool", text: "", structuredJSON: json) == "Trip: 4 stops, 43 min, 10.1 km")
}

@Test func staticMapImageURLComesFromTheResultText() {
    // Recorded from mcp.mapbox.com; the token is replaced.
    let text = """
    Static map image (600x400, mapbox/streets-v12) is attached to this result, which is where the host shows it to the user. \
    Give it as a link instead; it carries the account's public token, so it opens in a browser.
    Image URL: https://api.mapbox.com/styles/v1/mapbox/streets-v12/static/-122.3937,37.7955,13/600x400?access_token=pk.test
    """

    let url = MapboxToolText.staticMapImageURL(text)

    #expect(url?.absoluteString == "https://api.mapbox.com/styles/v1/mapbox/streets-v12/static/-122.3937,37.7955,13/600x400?access_token=pk.test")
}

@Test func staticMapImageURLMustBeMapboxHTTPS() {
    #expect(MapboxToolText.staticMapImageURL("Image URL: http://api.mapbox.com/styles/v1/x.png") == nil)
    #expect(MapboxToolText.staticMapImageURL("Image URL: https://example.com/x.png") == nil)
    #expect(MapboxToolText.staticMapImageURL("No image here") == nil)
}
