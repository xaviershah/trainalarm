import Foundation

/// Why a poll failed, derived from `ProviderError` by the driver.
enum FailureKind: Equatable {
    case network
    case http(Int)
    case malformed
    case other
}

enum EtaSource: Equatable {
    case lastKnownTrajectory
    case gpsPace
}

/// What the tracker tells the rest of the app. See the spec's Events table.
enum TrackerEvent: Equatable {
    case etaChanged(oldEta: Date?, newEta: Date, fireAt: Date)
    case destinationLost(at: Date)
    case serviceCancelled(at: Date)
    case destinationRestored(newEta: Date?)
    case feedLost(lastGoodAt: Date?, kind: FailureKind)
    case etaEstimated(eta: Date, source: EtaSource, staleness: TimeInterval, rescheduled: Bool)
    case feedRecovered(outage: TimeInterval, freshEta: Date?, deltaFromFallback: TimeInterval?)
    case alarmSchedulingFailed(fireAt: Date, message: String)
    case arrived(at: Date)
}
