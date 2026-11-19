import Foundation

/// A one-line summary of a result: a measurement, a travel time matrix, a category list, a resource, or an
/// optimized trip, whose line and stops the map also draws. The app shows it in the notice banner.
nonisolated public enum MapboxToolSummary {
    /// The server's text already starts with a readable line, such as "Bearing: 305.82° (NW)".
    private static let firstLineTools: Set<String> = ["distance_tool", "length_tool", "area_tool", "bearing_tool"]
    private static let categoriesShown = 5

    public static func summary(toolName: String, text: String, structuredJSON: String?, arguments: String = "{}") -> String? {
        if firstLineTools.contains(toolName) {
            let line = text.split(whereSeparator: \.isNewline).first?.trimmingCharacters(in: .whitespaces) ?? ""
            return line.isEmpty ? nil : line
        }
        guard let structured = jsonObject(structuredJSON) else { return nil }
        switch toolName {
        case "matrix_tool":
            return matrix(structured, profile: jsonObject(arguments)?["profile"] as? String)
        case "category_list_tool":
            return categories(structured)
        case "resource_reader_tool":
            return (structured["uri"] as? String).map { "Read \($0)" }
        case "optimization_tool":
            return trip(structured)
        default:
            return nil
        }
    }

    /// Travel times from the first origin to each other destination.
    private static func matrix(_ object: [String: Any], profile: String?) -> String? {
        guard let durations = object["durations"] as? [[Any]], let row = durations.first else { return nil }
        let distances = (object["distances"] as? [[Any]])?.first
        let destinations = object["destinations"] as? [[String: Any]] ?? []
        var parts: [String] = []
        for (index, value) in row.enumerated() {
            guard let seconds = number(value), seconds > 0 else { continue }
            let name = (index < destinations.count ? destinations[index]["name"] as? String : nil) ?? ""
            var part = "\(name.isEmpty ? "stop \(index + 1)" : name) \(duration(seconds))"
            if let distances, index < distances.count, let meters = number(distances[index]) {
                part += " (\(distance(meters)))"
            }
            parts.append(part)
        }
        guard !parts.isEmpty else { return nil }
        let more = durations.count - 1
        if more > 0 {
            parts.append("from \(more) more origin\(more == 1 ? "" : "s")")
        }
        return "\(mode(profile)) times: " + parts.joined(separator: " · ")
    }

    /// The first optimized trip's stop count, duration, and distance. The map draws its line and stops.
    private static func trip(_ object: [String: Any]) -> String? {
        guard let trip = (object["trips"] as? [[String: Any]])?.first,
              let seconds = number(trip["duration"]),
              let meters = number(trip["distance"])
        else { return nil }
        let stops = (object["waypoints"] as? [Any])?.count ?? 0
        return "Trip: \(stops) stops, \(duration(seconds)), \(distance(meters))"
    }

    private static func categories(_ object: [String: Any]) -> String? {
        guard let items = object["listItems"] as? [String], !items.isEmpty else { return nil }
        var shown = items.prefix(categoriesShown).map { $0.replacingOccurrences(of: "_", with: " ") }
        if items.count > categoriesShown {
            shown.append("and \(items.count - categoriesShown) more")
        }
        return "Categories: " + shown.joined(separator: ", ")
    }

    private static func mode(_ profile: String?) -> String {
        switch profile?.replacingOccurrences(of: "mapbox/", with: "") {
        case "walking": "Walking"
        case "cycling": "Cycling"
        case "driving", "driving-traffic": "Driving"
        default: "Travel"
        }
    }

    private static func duration(_ seconds: Double) -> String {
        let minutes = max(1, Int((seconds / 60).rounded()))
        guard minutes >= 60 else { return "\(minutes) min" }
        let rest = minutes % 60
        return rest == 0 ? "\(minutes / 60) h" : "\(minutes / 60) h \(rest) min"
    }

    private static func distance(_ meters: Double) -> String {
        meters < 1000 ? "\(Int(meters.rounded())) m" : String(format: "%.1f km", meters / 1000)
    }

    private static func jsonObject(_ text: String?) -> [String: Any]? {
        guard let data = text?.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private static func number(_ value: Any?) -> Double? {
        (value as? NSNumber)?.doubleValue
    }
}
