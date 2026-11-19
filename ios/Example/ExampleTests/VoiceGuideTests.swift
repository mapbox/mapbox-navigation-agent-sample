import Foundation
import Testing
@testable import Example

/// `tools/list` from mcp.mapbox.com, recorded 2026-11-12, plus the two tools the session runs itself.
private let offeredTools: Set<String> = [
    "area_tool", "bbox_tool", "bearing_tool", "buffer_tool", "category_list_tool", "category_search_tool",
    "centroid_tool", "convex_tool", "destination_tool", "difference_tool", "directions_tool", "distance_tool",
    "feedback_tool", "ground_location_tool", "intersect_tool", "isochrone_tool", "length_tool",
    "list_public_tokens_tool", "map_matching_tool", "matrix_tool", "midpoint_tool", "nearest_point_on_line_tool",
    "nearest_point_tool", "optimization_tool", "place_details_tool", "points_within_polygon_tool", "render_map_tool",
    "report_tool", "resource_reader_tool", "reverse_geocode_tool", "search_and_geocode_tool", "simplify_tool",
    "static_map_image_tool", "union_tool",
    MapboxVoiceSession.endConversationToolName,
    MapboxVoiceSession.stopNavigationToolName,
]

private func namedTools(_ text: String) -> Set<String> {
    let regex = /[a-z]+(?:_[a-z]+)*_(?:tool|navigation|conversation)\b/
    return Set(text.matches(of: regex).map { String($0.output) })
}

@Test func voiceGuideNamesOnlyOfferedTools() {
    let named = namedTools(VoiceGuide.instructions)

    #expect(!named.isEmpty)
    #expect(named.subtracting(offeredTools).isEmpty, "Not offered: \(named.subtracting(offeredTools).sorted())")
}

@Test func voiceGuideNamesEveryToolTheMapDraws() {
    let drawn: Set<String> = [
        "isochrone_tool", "buffer_tool", "union_tool", "intersect_tool", "difference_tool", "convex_tool",
        "simplify_tool", "bbox_tool", "points_within_polygon_tool", "map_matching_tool", "directions_tool",
        "search_and_geocode_tool", "category_search_tool", "centroid_tool", "midpoint_tool", "destination_tool",
        "nearest_point_tool", "nearest_point_on_line_tool", "ground_location_tool",
    ]

    let missing = drawn.subtracting(namedTools(VoiceGuide.instructions))

    #expect(missing.isEmpty, "Not named: \(missing.sorted())")
}

/// The server's isochrone_tool asks for generalize 1000-5000 when driving, which collapses a 10-minute area into a
/// triangle or nothing. The app draws the area, so the guide asks for 100 or less.
@Test func voiceGuideKeepsIsochronesDetailed() {
    #expect(VoiceGuide.instructions.contains("isochrone_tool with generalize set to 100 or less"))
}

@Test func voiceGuideGivesTheStartingCoordinate() {
    #expect(VoiceGuide.instructions.contains("longitude -122.3937, latitude 37.7955"))
}

@Test func voiceGuideSaysNotToReadImageURLs() {
    #expect(VoiceGuide.instructions.contains("static_map_image_tool"))
    #expect(VoiceGuide.instructions.contains("Never read a URL aloud"))
}
