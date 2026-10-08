import Foundation

/// Where the destination stands for the train.
enum Standing: Equatable {
    case calling
    case lost
    case cancelled
}

/// What the driver must do to the alarm after a monitor step.
enum AlarmAction: Equatable {
    case none
    case schedule(Date)
    case cancel
}

enum PollResult: Equatable {
    case service(Service)
    case failure(FailureKind)
}

/// One tick's input to the monitor.
struct Observation: Equatable {
    let result: PollResult
    let now: Date
    /// The latest location fix sampled this tick, if any (sampled whether or not the feed is up).
    let fix: LocationFix?
}

/// The fixed inputs of a tracked journey.
struct TrackerPlan: Equatable {
    let boardingId: String
    let destinationId: String
    /// Resolved by the caller from the station directory; nil disables the GPS fallback.
    let destinationCoordinate: Coordinate?
    let config: TrackerConfig
}

/// Everything the monitor remembers between ticks.
struct MonitorState: Equatable {
    var finished = false
    var standing: Standing = .calling
    var startedAt: Date? = nil
    var lastEta: Date? = nil
    var lastLiveEta: Date? = nil
    var sawLive = false
    var lastGoodAt: Date? = nil
    /// The fire time the scheduler has confirmed.
    var scheduledFire: Date? = nil
    /// A `schedule` call failed; the next tick must issue it again without a new `etaChanged`.
    var retryPending = false
    var alarmDue = false
    var feedDown = false
    var outageStartedAt: Date? = nil
    var lastFallbackEta: Date? = nil
    var fixes: [LocationFix] = []
}

struct MonitorStep: Equatable {
    var state: MonitorState
    var events: [TrackerEvent]
    var action: AlarmAction
}
