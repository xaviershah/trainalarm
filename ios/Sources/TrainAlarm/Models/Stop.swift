import Foundation

/// What the provider says this location is for the train (RTT `displayAs`, a
/// per-location field). `Stop.displayAs` is nil when the provider didn't say.
enum StopDisplay: Equatable {
    case call
    case cancelled
    case diverted
    case starts
    case terminates
    case pass
    /// A value this app doesn't recognise (the schema may grow).
    case unknown
}

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
    var isArrivalCancelled: Bool = false
    var isDepartureCancelled: Bool = false
    var displayAs: StopDisplay? = nil
    /// True once the train has arrived here (the arrival has a `realtimeActual`).
    var hasArrived: Bool = false

    /// Either activity cancelled. Use the per-activity flags when the difference matters.
    var isCancelled: Bool { isArrivalCancelled || isDepartureCancelled }

    /// Best-known arrival time: live estimate if we have one, else the timetable.
    var bestArrival: Date? {
        estimatedArrival ?? scheduledArrival
    }
}
