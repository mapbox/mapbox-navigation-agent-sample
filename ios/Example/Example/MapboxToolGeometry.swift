import Foundation

/// One shape a tool result draws on the map.
nonisolated public enum ToolShape: Hashable, Sendable {
    /// A label, such as a stop's place in an optimized trip, is drawn inside a larger marker.
    case point(Coordinate, label: String? = nil)
    case line([Coordinate])
    /// Rings, outer ring first.
    case polygon([[Coordinate]])
}

/// What a geometry, isochrone, or map matching result draws on the map, above the place pins.
nonisolated public struct ToolOverlay: Hashable, Sendable {
    public var shapes: [ToolShape]

    public init(shapes: [ToolShape]) {
        self.shapes = shapes
    }

    /// Every coordinate in the overlay, for framing the camera.
    public var coordinates: [Coordinate] {
        shapes.flatMap { shape -> [Coordinate] in
            switch shape {
            case .point(let coordinate, _): [coordinate]
            case .line(let coordinates): coordinates
            case .polygon(let rings): rings.flatMap { $0 }
            }
        }
    }
}

/// Reads the shapes in a Mapbox MCP tool's `structuredContent`.
///
/// The public server returns each tool's geometry under a different key, so this knows each one:
/// GeoJSON at the top level (`isochrone_tool`), a GeoJSON `geometry` (`union_tool`, `intersect_tool`,
/// `difference_tool`, `convex_tool`), raw rings in `bufferedPolygon` (`buffer_tool`), raw coordinates in
/// `simplified` (`simplify_tool`), `[west, south, east, north]` in `bbox` (`bbox_tool`), GeoJSON lines in
/// `matchings` (`map_matching_tool`), `{longitude, latitude}` objects in `points_within`
/// (`points_within_polygon_tool`), and one `{longitude, latitude}` object in `centroid`, `midpoint`,
/// `destination`, or `nearest` (the point tools), and `trips` plus numbered `waypoints` (`optimization_tool`).
nonisolated public enum MapboxToolGeometry {
    /// Tools whose results the app already shows as pins or a route.
    static let drawnElsewhere: Set<String> = [
        "search_and_geocode_tool",
        "reverse_geocode_tool",
        "category_search_tool",
        "directions_tool",
        "place_details_tool",
    ]

    public static func overlay(toolName: String, structuredJSON: String?) -> ToolOverlay? {
        guard !drawnElsewhere.contains(toolName),
              let data = structuredJSON?.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { return nil }
        let shapes = shapes(in: object)
        return shapes.isEmpty ? nil : ToolOverlay(shapes: shapes)
    }

    private static func shapes(in object: [String: Any]) -> [ToolShape] {
        let geoJSON = geoJSONShapes(object)
        if !geoJSON.isEmpty { return geoJSON }
        if let geometry = object["geometry"] as? [String: Any] {
            return geoJSONShapes(geometry)
        }
        if let rings = rings(object["bufferedPolygon"]) {
            return [.polygon(rings)]
        }
        if let simplified = object["simplified"] {
            if let line = line(simplified) { return [.line(line)] }
            if let rings = rings(simplified) { return [.polygon(rings)] }
        }
        if let bbox = object["bbox"] as? [Any], let box = rectangle(bbox) {
            return [.polygon([box])]
        }
        if let matchings = object["matchings"] as? [[String: Any]] {
            return matchings.compactMap { $0["geometry"] as? [String: Any] }.flatMap(geoJSONShapes)
        }
        if let points = object["points_within"] as? [[String: Any]] {
            return points.compactMap(namedCoordinate).map { .point($0) }
        }
        if let trips = object["trips"] as? [[String: Any]] {
            return optimizedTrip(trips, waypoints: object["waypoints"] as? [[String: Any]] ?? [])
        }
        for key in computedPointKeys {
            if let point = object[key] as? [String: Any], let coordinate = namedCoordinate(point) {
                return [.point(coordinate)]
            }
        }
        return []
    }

    /// `optimization_tool`: each trip's GeoJSON line, then each stop numbered by its `waypoint_index`, the order the
    /// trip visits it in.
    private static func optimizedTrip(_ trips: [[String: Any]], waypoints: [[String: Any]]) -> [ToolShape] {
        let lines = trips.compactMap { $0["geometry"] as? [String: Any] }.flatMap(geoJSONShapes)
        let stops = waypoints.compactMap { waypoint -> ToolShape? in
            guard let coordinate = position(waypoint["location"]),
                  let order = number(waypoint["waypoint_index"])
            else { return nil }
            return .point(coordinate, label: String(Int(order) + 1))
        }
        return lines + stops
    }

    /// `centroid_tool`, `midpoint_tool`, `destination_tool`, then `nearest_point_tool` and `nearest_point_on_line_tool`.
    private static let computedPointKeys = ["centroid", "midpoint", "destination", "nearest"]

    /// Shapes from a GeoJSON geometry, Feature, or FeatureCollection. Empty for anything else.
    private static func geoJSONShapes(_ object: [String: Any]) -> [ToolShape] {
        let coordinates = object["coordinates"]
        switch object["type"] as? String {
        case "FeatureCollection":
            return (object["features"] as? [[String: Any]] ?? []).flatMap(geoJSONShapes)
        case "Feature":
            return (object["geometry"] as? [String: Any]).map(geoJSONShapes) ?? []
        case "GeometryCollection":
            return (object["geometries"] as? [[String: Any]] ?? []).flatMap(geoJSONShapes)
        case "Point":
            return position(coordinates).map { [.point($0)] } ?? []
        case "MultiPoint":
            return line(coordinates)?.map { .point($0) } ?? []
        case "LineString":
            return line(coordinates).map { [.line($0)] } ?? []
        case "MultiLineString":
            return (coordinates as? [Any] ?? []).compactMap(line).map(ToolShape.line)
        case "Polygon":
            return rings(coordinates).map { [.polygon($0)] } ?? []
        case "MultiPolygon":
            return (coordinates as? [Any] ?? []).compactMap(rings).map(ToolShape.polygon)
        default:
            return []
        }
    }

    /// `[longitude, latitude]`.
    private static func position(_ value: Any?) -> Coordinate? {
        guard let pair = value as? [Any], pair.count >= 2,
              let longitude = number(pair[0]), let latitude = number(pair[1])
        else { return nil }
        return Coordinate(latitude: latitude, longitude: longitude)
    }

    private static func line(_ value: Any?) -> [Coordinate]? {
        guard let positions = value as? [Any], !positions.isEmpty else { return nil }
        let coordinates = positions.compactMap(position)
        return coordinates.count == positions.count ? coordinates : nil
    }

    private static func rings(_ value: Any?) -> [[Coordinate]]? {
        guard let rings = value as? [Any], !rings.isEmpty else { return nil }
        let lines = rings.compactMap(line)
        return lines.count == rings.count ? lines : nil
    }

    private static func rectangle(_ bbox: [Any]) -> [Coordinate]? {
        let values = bbox.compactMap(number)
        guard values.count == 4 else { return nil }
        let (west, south, east, north) = (values[0], values[1], values[2], values[3])
        return [
            Coordinate(latitude: south, longitude: west),
            Coordinate(latitude: south, longitude: east),
            Coordinate(latitude: north, longitude: east),
            Coordinate(latitude: north, longitude: west),
            Coordinate(latitude: south, longitude: west),
        ]
    }

    private static func namedCoordinate(_ object: [String: Any]) -> Coordinate? {
        guard let latitude = number(object["latitude"]), let longitude = number(object["longitude"]) else { return nil }
        return Coordinate(latitude: latitude, longitude: longitude)
    }

    private static func number(_ value: Any?) -> Double? {
        (value as? NSNumber)?.doubleValue
    }
}
