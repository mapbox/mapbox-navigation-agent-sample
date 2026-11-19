import Foundation

nonisolated public struct Coordinate: Hashable, Sendable {
    public var latitude: Double
    public var longitude: Double

    public init(latitude: Double, longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
    }

    public var json: [String: Double] {
        ["longitude": longitude, "latitude": latitude]
    }

    public func distance(to other: Coordinate) -> Double {
        let radius = 6_371_000.0
        let lat1 = latitude * .pi / 180
        let lat2 = other.latitude * .pi / 180
        let dLat = (other.latitude - latitude) * .pi / 180
        let dLon = (other.longitude - longitude) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return radius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

nonisolated public enum PlaceFilter {
    /// How far from the user a result can be when other results are nearby.
    public static let nearbyMeters = 100_000.0

    /// Drops far-off results when at least one result is near the user.
    ///
    /// The search tools can return a stray match far away, even with `proximity` set. "Coit Tower" from the Ferry
    /// Building returns the tower and a cleaning company in Akron, Ohio. Pinning both makes the camera frame the
    /// continent. When every result is far away, such as "Eiffel Tower", all of them are kept.
    public static func nearby(_ places: [Place], to origin: Coordinate, within meters: Double = nearbyMeters) -> [Place] {
        let near = places.filter { $0.coordinate.distance(to: origin) <= meters }
        return near.isEmpty ? places : near
    }
}

nonisolated public enum RouteProfile: String, Equatable, Sendable {
    case walking
    case driving
    case cycling
}

/// Where a `directions_tool` result should go, and how.
nonisolated public struct RouteRequest: Equatable, Sendable {
    public var destination: Place
    public var profile: RouteProfile

    public init(destination: Place, profile: RouteProfile) {
        self.destination = destination
        self.profile = profile
    }
}

nonisolated public struct Place: Identifiable, Hashable, Sendable {
    public var id: String
    public var name: String
    public var address: String
    public var coordinate: Coordinate
    public var category: String?

    public init(id: String, name: String, address: String, coordinate: Coordinate, category: String? = nil) {
        self.id = id
        self.name = name
        self.address = address
        self.coordinate = coordinate
        self.category = category
    }
}

nonisolated public struct PlaceDetails: Sendable {
    public var phone: String?
    public var website: URL?
    public var hours: [OpeningHours]
    public var photo: URL?

    public init(phone: String? = nil, website: URL? = nil, hours: [OpeningHours] = [], photo: URL? = nil) {
        self.phone = phone
        self.website = website
        self.hours = hours
        self.photo = photo
    }
}

nonisolated public struct OpeningHours: Hashable, Sendable {
    public var day: String
    public var hours: String

    public init(day: String, hours: String) {
        self.day = day
        self.hours = hours
    }
}

/// Reads the text the Mapbox MCP tools return. Tools that render a map append a
/// "pass this ref to render_map_tool" hint after the payload, which is not part of it.
nonisolated public enum MapboxToolText {
    private static let mapHint = "\n\nIf the user wants"

    public static func payload(_ text: String) -> String {
        guard let range = text.range(of: mapHint) else { return text }
        return String(text[..<range.lowerBound])
    }

    /// Numbered results from `search_and_geocode_tool` and `reverse_geocode_tool`.
    public static func places(fromList text: String) -> [Place] {
        var places: [Place] = []
        var name: String?
        var fields: [String: String] = [:]

        func flush() {
            defer {
                name = nil
                fields = [:]
            }
            guard let name,
                  let id = fields["Mapbox ID"] ?? fields["Address"],
                  let coordinate = coordinate(fields["Coordinates"])
            else { return }
            places.append(Place(
                id: id,
                name: name,
                address: fields["Address"] ?? "",
                coordinate: coordinate,
                category: fields["Category"].flatMap(firstCategory)
            ))
        }

        for line in payload(text).components(separatedBy: .newlines) {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if let title = numberedTitle(trimmed) {
                flush()
                name = title
            } else if name != nil, let colon = trimmed.range(of: ": ") {
                fields[String(trimmed[..<colon.lowerBound])] = String(trimmed[colon.upperBound...])
            }
        }
        flush()
        return places
    }

    /// GeoJSON from `category_search_tool` with `format: json_string`.
    public static func places(fromFeatureCollection text: String) -> [Place] {
        guard let features = jsonObject(text)?["features"] as? [[String: Any]] else { return [] }
        return features.compactMap { feature in
            guard let properties = feature["properties"] as? [String: Any],
                  let id = properties["mapbox_id"] as? String,
                  let name = properties["name"] as? String,
                  let coordinates = properties["coordinates"] as? [String: Any],
                  let latitude = coordinates["latitude"] as? Double,
                  let longitude = coordinates["longitude"] as? Double
            else { return nil }
            let categories = properties["poi_category"] as? [String]
            return Place(
                id: id,
                name: name,
                address: (properties["full_address"] as? String) ?? (properties["address"] as? String) ?? "",
                coordinate: Coordinate(latitude: latitude, longitude: longitude),
                category: categories?.first.map { $0.capitalized }
            )
        }
    }

    /// `nearby_pois` in `ground_location_tool`'s structured content. Places without a Mapbox ID are left out,
    /// because place details need one.
    public static func places(nearbyIn structuredJSON: String?) -> [Place] {
        guard let structuredJSON, let pois = jsonObject(structuredJSON)?["nearby_pois"] as? [[String: Any]] else {
            return []
        }
        return pois.compactMap { poi in
            guard let id = poi["mapbox_id"] as? String,
                  let name = poi["name"] as? String,
                  let latitude = number(poi["latitude"]),
                  let longitude = number(poi["longitude"])
            else { return nil }
            return Place(
                id: id,
                name: name,
                address: (poi["address"] as? String) ?? "",
                coordinate: Coordinate(latitude: latitude, longitude: longitude),
                category: (poi["category"] as? String)?.capitalized
            )
        }
    }

    /// The `Image URL:` line in a `static_map_image_tool` result. Only an https URL on api.mapbox.com is accepted,
    /// since the app loads it. The URL carries the same public token the app already uses.
    public static func staticMapImageURL(_ text: String) -> URL? {
        let prefix = "Image URL:"
        guard let line = text.split(whereSeparator: \.isNewline).first(where: { $0.hasPrefix(prefix) }),
              let url = URL(string: line.dropFirst(prefix.count).trimmingCharacters(in: .whitespaces)),
              url.scheme == "https", url.host == "api.mapbox.com"
        else { return nil }
        return url
    }

    /// Places from either a numbered list or a GeoJSON feature collection.
    public static func places(in text: String) -> [Place] {
        let listed = places(fromList: text)
        return listed.isEmpty ? places(fromFeatureCollection: text) : listed
    }

    /// Destination and travel mode from a `directions_tool` result.
    ///
    /// The last waypoint is the destination. Its coordinate is `snap_location` (longitude, latitude),
    /// and the mode comes from the tool call's `routing_profile`. A summary with no waypoint JSON
    /// falls back to the last coordinate in the arguments.
    public static func directions(text: String, arguments: String) -> RouteRequest? {
        guard let destination = waypointDestination(text) ?? argumentDestination(arguments) else { return nil }
        return RouteRequest(destination: destination, profile: routeProfile(arguments))
    }

    /// Labeled lines from `place_details_tool`.
    public static func details(_ text: String) -> PlaceDetails {
        var details = PlaceDetails()
        var readingHours = false
        for line in payload(text).components(separatedBy: .newlines) {
            if readingHours, line.hasPrefix("  ") {
                let entry = line.trimmingCharacters(in: .whitespaces)
                if let space = entry.firstIndex(of: " ") {
                    details.hours.append(OpeningHours(
                        day: String(entry[..<space]),
                        hours: String(entry[entry.index(after: space)...])
                    ))
                }
                continue
            }
            readingHours = false
            guard let colon = line.range(of: ":") else { continue }
            let key = String(line[..<colon.lowerBound])
            let value = line[colon.upperBound...].trimmingCharacters(in: .whitespaces)
            switch key {
            case "Phone":
                details.phone = value
            case "Website":
                details.website = URL(string: value)
            case "Hours":
                readingHours = true
            case "Photos":
                let urls = value.components(separatedBy: ", ")
                let large = urls.first { $0.contains("/photo-l/") } ?? urls.first
                details.photo = large.flatMap(URL.init(string:))
            default:
                break
            }
        }
        return details
    }

    private static func jsonObject(_ text: String) -> [String: Any]? {
        let body = payload(text)
        guard let start = body.firstIndex(of: "{"), let end = body.lastIndex(of: "}") else { return nil }
        let data = Data(body[start...end].utf8)
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private static func numberedTitle(_ line: String) -> String? {
        guard let dot = line.range(of: ". "),
              !line[..<dot.lowerBound].isEmpty,
              line[..<dot.lowerBound].allSatisfy(\.isNumber)
        else { return nil }
        return String(line[dot.upperBound...])
    }

    private static func coordinate(_ value: String?) -> Coordinate? {
        guard let parts = value?.components(separatedBy: ","), parts.count == 2,
              let latitude = Double(parts[0].trimmingCharacters(in: .whitespaces)),
              let longitude = Double(parts[1].trimmingCharacters(in: .whitespaces))
        else { return nil }
        return Coordinate(latitude: latitude, longitude: longitude)
    }

    private static func firstCategory(_ value: String) -> String? {
        value.components(separatedBy: ", ").first.map { $0.capitalized }
    }

    private static func routeProfile(_ arguments: String) -> RouteProfile {
        switch jsonObject(arguments)?["routing_profile"] as? String {
        case "mapbox/walking": .walking
        case "mapbox/cycling": .cycling
        default: .driving
        }
    }

    private static func waypointDestination(_ text: String) -> Place? {
        guard let waypoints = jsonObject(text)?["waypoints"] as? [[String: Any]],
              let last = waypoints.last,
              let coordinate = coordinatePair(last["snap_location"]) ?? coordinatePair(last["location"])
        else { return nil }
        let trimmed = (last["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return routePlace(name: trimmed.isEmpty ? "Destination" : trimmed, coordinate: coordinate)
    }

    private static func argumentDestination(_ arguments: String) -> Place? {
        guard let coordinates = jsonObject(arguments)?["coordinates"] as? [[String: Any]],
              let last = coordinates.last,
              let latitude = number(last["latitude"]),
              let longitude = number(last["longitude"])
        else { return nil }
        guard let coordinate = Coordinate(latitude: latitude, longitude: longitude).validated else { return nil }
        return routePlace(name: "Destination", coordinate: coordinate)
    }

    private static func routePlace(name: String, coordinate: Coordinate) -> Place {
        Place(
            id: "route:\(coordinate.latitude),\(coordinate.longitude)",
            name: name,
            address: "",
            coordinate: coordinate
        )
    }

    /// Directions waypoints are `[longitude, latitude]`.
    private static func coordinatePair(_ value: Any?) -> Coordinate? {
        guard let pair = value as? [Any], pair.count >= 2,
              let longitude = number(pair[0]),
              let latitude = number(pair[1])
        else { return nil }
        return Coordinate(latitude: latitude, longitude: longitude).validated
    }

    private static func number(_ value: Any?) -> Double? {
        (value as? NSNumber)?.doubleValue
    }
}

nonisolated extension Coordinate {
    fileprivate var validated: Coordinate? {
        guard (-90...90).contains(latitude), (-180...180).contains(longitude) else { return nil }
        return self
    }
}
