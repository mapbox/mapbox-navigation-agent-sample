import Foundation

enum TravelMode: String, CaseIterable, Identifiable, Sendable {
    case walking = "Walk"
    case driving = "Drive"
    case cycling = "Bike"

    var id: String { rawValue }
}
