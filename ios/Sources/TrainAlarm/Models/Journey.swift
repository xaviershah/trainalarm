import Foundation

enum JourneyError: Error {
    case noStops
}

/// A user's planned trip: an origin, a destination, and the ordered stops of
/// the specific `Service` they've picked between them (inclusive of origin
/// and destination). Stage 2's live-change handling re-derives this from a
/// fresh `Service` on every poll - see docs/spec.md §4.
struct Journey: Equatable {
    let origin: Station
    let destination: Station
    let stops: [Stop]

    init(origin: Station, destination: Station, stops: [Stop]) throws {
        guard !stops.isEmpty else { throw JourneyError.noStops }
        self.origin = origin
        self.destination = destination
        self.stops = stops
    }

    /// Nil if the destination has dropped out of the calling pattern (diversion/cancellation) - see docs/spec.md §4.
    var destinationStop: Stop? {
        stops.last { $0.station.id == destination.id }
    }
}
