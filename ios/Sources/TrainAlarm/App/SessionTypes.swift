import Foundation

enum EndReason: Equatable { case arrived, imOff }

enum SessionPhase: Equatable { case selecting, confirming, tracking, ringing, ended(EndReason) }

enum BoardState: Equatable {
    case idle, loading
    case loaded([Service])
    case empty
    case failed(String)
    case rateLimited
}

struct ConfirmState: Equatable {
    let service: Service
    /// Best arrival at the destination after the boarding stop; nil if the train does not call there.
    let arrival: Date?
    var leadMinutes: Int?
}

/// What the tracking screen shows, derived from tracker events. See the table in spec section 6.
struct TrackingDisplay: Equatable {
    var eta: Date?
    var scheduledArrival: Date?
    var alarmAt: Date?
    var isEstimated = false
    var estimateSource: EtaSource?
    var feedLost = false
    var lastGoodAt: Date?
    var rateLimited = false
    var destinationDropped = false
    var cancelled = false
    /// True when the tracker could not schedule the alarm: the screen must say the alarm is NOT set.
    var alarmNotSet = false

    /// Minutes late (positive) or early, rounded; nil without both times.
    var delayMinutes: Int? {
        guard let eta, let scheduledArrival else { return nil }
        return Int((eta.timeIntervalSince(scheduledArrival) / 60).rounded())
    }
}

enum NearestFailure: Equatable { case permissionDenied, noFix }
