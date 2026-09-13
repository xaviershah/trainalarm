import Foundation

/// One station within a `Journey`. Scheduled times come from the timetable;
/// estimated times are refined by live running data where available (may be
/// nil if the provider has nothing live yet, or this stop is in the past).
/// See docs/spec.md §4 for how these get updated as a journey runs.
struct Stop: Equatable {
    let station: Station
    let scheduledArrival: Date?
    let scheduledDeparture: Date?
    let estimatedArrival: Date?
    let estimatedDeparture: Date?
    var isCancelled: Bool = false

    /// Best-known arrival time: live estimate if we have one, else the timetable.
    var bestArrival: Date? {
        estimatedArrival ?? scheduledArrival
    }
}
