import Foundation

/// Tuning for the journey tracker. Provider times are minute-granular, so a one-minute
/// shift is exactly 60s: the reschedule threshold is inclusive (>=).
struct TrackerConfig: Equatable {
    /// How long before the destination ETA the alarm should fire (the user's setting; no default).
    var leadTime: TimeInterval
    var rescheduleThreshold: TimeInterval = 60
    var pollInterval: TimeInterval = 45
    /// A GPS-based fallback ETA is never earlier than the last live ETA minus this.
    var guardMargin: TimeInterval = 60
    /// Minimum approach speed, in metres per second, for the GPS fallback to be trusted.
    var minPace: Double = 1.0
    var maxFixAgePolls: Int = 3
    /// Fixes less accurate than this many metres are ignored.
    var maxFixAccuracy: Double = 200
    /// How long after the ETA the tracker waits for an arrival before ending anyway.
    var arrivalGrace: TimeInterval = 600

    /// Fixes older than this are ignored.
    var maxFixAge: TimeInterval { Double(maxFixAgePolls) * pollInterval }
}
