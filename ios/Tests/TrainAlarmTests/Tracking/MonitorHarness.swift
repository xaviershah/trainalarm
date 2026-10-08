import XCTest
@testable import TrainAlarm

/// A location fix described relative to the tick it arrives on.
struct FixSpec {
    var latitude: Double
    var longitude: Double
    var accuracy: Double = 10
    /// How old the fix already is when the tick happens.
    var ageSeconds: TimeInterval = 0
}

/// Drives the pure monitor the way the real driver does: it applies each action, confirming a
/// schedule unless `failNextSchedule` is set. Time only moves when a test says so.
final class MonitorHarness {
    let monitor: JourneyMonitor
    var state = MonitorState()
    var now: Date
    private(set) var scheduled: [Date] = []
    private(set) var attempts: [Date] = []
    private(set) var cancelCount = 0
    var failNextSchedule = false

    init(
        boarding: String = "WAT",
        destination: String = "BSK",
        coordinate: Coordinate? = nil,
        leadTime: TimeInterval = 600,
        start: Date = ServiceBuilder.time("08:00:00")
    ) {
        monitor = JourneyMonitor(plan: TrackerPlan(
            boardingId: boarding,
            destinationId: destination,
            destinationCoordinate: coordinate,
            config: TrackerConfig(leadTime: leadTime)
        ))
        now = start
    }

    @discardableResult
    func start(_ snapshot: Service) -> [TrackerEvent] {
        apply(monitor.start(snapshot: snapshot, now: now))
    }

    @discardableResult
    func poll(_ service: Service, advancing seconds: TimeInterval = 45, fix: FixSpec? = nil) -> [TrackerEvent] {
        now = now.addingTimeInterval(seconds)
        return apply(monitor.step(state, Observation(result: .service(service), now: now, fix: stamp(fix))))
    }

    @discardableResult
    func fail(_ kind: FailureKind = .network, advancing seconds: TimeInterval = 45, fix: FixSpec? = nil) -> [TrackerEvent] {
        now = now.addingTimeInterval(seconds)
        return apply(monitor.step(state, Observation(result: .failure(kind), now: now, fix: stamp(fix))))
    }

    private func stamp(_ spec: FixSpec?) -> LocationFix? {
        spec.map {
            LocationFix(latitude: $0.latitude, longitude: $0.longitude,
                        timestamp: now.addingTimeInterval(-$0.ageSeconds), accuracy: $0.accuracy)
        }
    }

    private func apply(_ step: MonitorStep) -> [TrackerEvent] {
        state = step.state
        var events = step.events
        switch step.action {
        case .none:
            break
        case .schedule(let fireAt):
            attempts.append(fireAt)
            if failNextSchedule {
                failNextSchedule = false
                state = monitor.schedulingFailed(state)
                events.append(.alarmSchedulingFailed(fireAt: fireAt, message: "refused"))
            } else {
                scheduled.append(fireAt)
                state = monitor.confirmScheduled(state, fireAt: fireAt)
            }
        case .cancel:
            cancelCount += 1
        }
        return events
    }
}
