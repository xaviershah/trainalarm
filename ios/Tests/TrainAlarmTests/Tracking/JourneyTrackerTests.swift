import XCTest
@testable import TrainAlarm

/// Collects a tracker's event stream so tests can wait for it deterministically.
final class EventCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var collected: [TrackerEvent] = []
    private var done = false
    private var task: Task<Void, Never>?

    init(_ stream: AsyncStream<TrackerEvent>) {
        task = Task { [self] in
            for await event in stream { append(event) }
            finish()
        }
    }

    private func append(_ event: TrackerEvent) { lock.lock(); collected.append(event); lock.unlock() }
    private func finish() { lock.lock(); done = true; lock.unlock() }

    var events: [TrackerEvent] { lock.lock(); defer { lock.unlock() }; return collected }
    var isFinished: Bool { lock.lock(); defer { lock.unlock() }; return done }

    func waitForCount(_ count: Int, file: StaticString = #filePath, line: UInt = #line) async {
        for _ in 0..<2000 {
            if events.count >= count { return }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("timed out waiting for \(count) events; have \(events)", file: file, line: line)
    }

    func waitUntilFinished(file: StaticString = #filePath, line: UInt = #line) async {
        for _ in 0..<2000 {
            if isFinished { return }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("timed out waiting for the stream to finish", file: file, line: line)
    }
}

final class JourneyTrackerTests: XCTestCase {
    private struct Boom: Error {}

    private func t(_ hhmmss: String) -> Date { ServiceBuilder.time(hhmmss) }
    private func happy(_ estimate: String? = "08:52:00") -> Service { ServiceBuilder.happyPath(destinationEstimate: estimate) }

    private struct Rig {
        let tracker: JourneyTracker
        let clock: FakeClock
        let provider: FakeProvider
        let scheduler: FakeAlarmScheduler
        let location: FakeLocationSource
        let events: EventCollector
    }

    private func makeRig(_ results: [Result<Service, Error>], coordinate: Coordinate? = nil) -> Rig {
        let clock = FakeClock(start: t("08:00:00"))
        let provider = FakeProvider()
        provider.results = results
        let scheduler = FakeAlarmScheduler()
        let location = FakeLocationSource()
        let plan = TrackerPlan(boardingId: "WAT", destinationId: "BSK", destinationCoordinate: coordinate,
                               config: TrackerConfig(leadTime: 600))
        let tracker = JourneyTracker(plan: plan, snapshot: happy(), provider: provider,
                                     locationSource: location, scheduler: scheduler, clock: clock)
        addTeardownBlock { await tracker.stop() }  // a failed wait must not leave the loop running
        return Rig(tracker: tracker, clock: clock, provider: provider, scheduler: scheduler,
                   location: location, events: EventCollector(tracker.events))
    }

    // MARK: Wiring through the real loop

    func testStartSchedulesTheTimetableAlarmBeforeAnyPoll() async {
        let rig = makeRig([.success(happy())])
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)
        await rig.tracker.stop()
        await rig.events.waitUntilFinished()  // every event has now been consumed

        XCTAssertEqual(rig.events.events, [.etaChanged(oldEta: nil, newEta: t("08:52:00"), fireAt: t("08:42:00"))])
        XCTAssertEqual(rig.scheduler.scheduled, [t("08:42:00")])
        XCTAssertEqual(rig.provider.calls, 0)
    }

    func testEachTickPollsTheProviderOnceAndAppliesTheChange() async {
        let rig = makeRig([.success(happy("08:57:00"))])
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)

        rig.clock.tick(advancing: 45)
        await rig.clock.waitForSleepCount(2)
        await rig.events.waitForCount(2)
        XCTAssertEqual(rig.provider.calls, 1)
        XCTAssertEqual(rig.provider.requestedIds, [ServiceBuilder.happyPathId])
        XCTAssertEqual(rig.events.events.last, .etaChanged(oldEta: t("08:52:00"), newEta: t("08:57:00"), fireAt: t("08:47:00")))
        XCTAssertEqual(rig.scheduler.scheduled, [t("08:42:00"), t("08:47:00")])

        rig.clock.tick(advancing: 45)
        await rig.clock.waitForSleepCount(3)
        XCTAssertEqual(rig.provider.calls, 2)
        await rig.tracker.stop()
        await rig.events.waitUntilFinished()
        XCTAssertEqual(rig.events.events.count, 2)  // same ETA again: nothing new
    }

    func testAProviderErrorBecomesAFailedPollWithItsKind() async {
        let rig = makeRig([.failure(ProviderError.http(503))])
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)

        rig.clock.tick(advancing: 45)
        await rig.clock.waitForSleepCount(2)
        await rig.events.waitForCount(3)

        XCTAssertEqual(Array(rig.events.events.dropFirst()), [
            .feedLost(lastGoodAt: nil, kind: .http(503)),
            .etaEstimated(eta: t("08:52:00"), source: .lastKnownTrajectory, staleness: 45, rescheduled: false),
        ])
        await rig.tracker.stop()
    }

    func testAnErrorThatIsNotAProviderErrorIsKindOtherAndPollingContinues() async {
        let rig = makeRig([.failure(Boom())])
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)

        rig.clock.tick(advancing: 45)
        await rig.clock.waitForSleepCount(2)
        rig.clock.tick(advancing: 45)
        await rig.clock.waitForSleepCount(3)
        await rig.events.waitForCount(3)

        XCTAssertEqual(rig.events.events.dropFirst().first, .feedLost(lastGoodAt: nil, kind: .other))
        XCTAssertEqual(rig.provider.calls, 2)
        await rig.tracker.stop()
    }

    func testLocationFixesAreSampledEachTickAndReachTheGpsFallback() async {
        let rig = makeRig([.success(happy()), .failure(ProviderError.network("down"))],
                          coordinate: Coordinate(latitude: 0, longitude: 1.0))
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)

        rig.location.fix = LocationFix(latitude: 0, longitude: 0.0, timestamp: t("08:00:45"), accuracy: 10)
        rig.clock.tick(advancing: 45)  // 08:00:45, good poll
        await rig.clock.waitForSleepCount(2)
        rig.location.fix = LocationFix(latitude: 0, longitude: 0.02, timestamp: t("08:01:45"), accuracy: 10)
        rig.clock.tick(advancing: 60)  // 08:01:45, failed poll: GPS ETA 08:50:45 is clamped by the guard to 08:51:00
        await rig.clock.waitForSleepCount(3)
        await rig.events.waitForCount(3)

        XCTAssertEqual(rig.events.events.last, .etaEstimated(eta: t("08:51:00"), source: .gpsPace, staleness: 60, rescheduled: true))
        XCTAssertEqual(rig.scheduler.scheduled, [t("08:42:00"), t("08:41:00")])
        await rig.tracker.stop()
    }

    func testASchedulerRefusalIsReportedThenRetriedOnTheNextTick() async {
        let rig = makeRig([.success(happy())])
        rig.scheduler.failuresRemaining = 1
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)
        await rig.events.waitForCount(2)

        guard rig.events.events.count == 2 else { return XCTFail("expected 2 events, got \(rig.events.events)") }
        XCTAssertEqual(rig.events.events[0], .etaChanged(oldEta: nil, newEta: t("08:52:00"), fireAt: t("08:42:00")))
        guard case .alarmSchedulingFailed(let fireAt, _) = rig.events.events[1] else {
            return XCTFail("expected alarmSchedulingFailed, got \(rig.events.events[1])")
        }
        XCTAssertEqual(fireAt, t("08:42:00"))
        XCTAssertEqual(rig.scheduler.scheduled, [])

        rig.clock.tick(advancing: 45)
        await rig.clock.waitForSleepCount(2)
        XCTAssertEqual(rig.scheduler.scheduled, [t("08:42:00")])
        XCTAssertEqual(rig.scheduler.attempts, [t("08:42:00"), t("08:42:00")])
        await rig.tracker.stop()
        await rig.events.waitUntilFinished()
        XCTAssertEqual(rig.events.events.count, 2)  // the retry announces nothing new
    }

    func testArrivalCancelsTheAlarmAndEndsTheStream() async {
        let arrived = ServiceBuilder.replacing(happy(), stopAt: 2, with: ServiceBuilder.stop(
            "BSK", scheduledArrival: "08:47:00", estimatedArrival: "08:50:00", hasArrived: true))
        let rig = makeRig([.success(arrived)])
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)

        rig.clock.tick(advancing: 45)
        await rig.events.waitUntilFinished()

        XCTAssertEqual(rig.events.events.last, .arrived(at: t("08:00:45")))
        XCTAssertEqual(rig.scheduler.cancelCount, 1)
        XCTAssertEqual(rig.clock.sleepCount, 1)  // never slept again
        await rig.tracker.stop()
    }

    // MARK: Scenarios 25-26: stop and cancellation

    func testStopCancelsTheAlarmAndEndsTheStreamWithoutPolling() async {
        let rig = makeRig([.success(happy())])
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)

        await rig.tracker.stop()
        await rig.events.waitUntilFinished()

        XCTAssertEqual(rig.scheduler.cancelCount, 1)
        XCTAssertEqual(rig.provider.calls, 0)
        rig.clock.tick(advancing: 45)  // nobody is sleeping any more
        XCTAssertEqual(rig.provider.calls, 0)
    }

    func testCancellationDuringTheSleepIsNotReportedAsAnOutage() async {
        let rig = makeRig([.failure(ProviderError.http(500))])  // would show up as feedLost if a poll happened
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)

        await rig.tracker.stop()
        await rig.events.waitUntilFinished()

        XCTAssertEqual(rig.events.events, [.etaChanged(oldEta: nil, newEta: t("08:52:00"), fireAt: t("08:42:00"))])
    }

    func testACancellationErrorFromTheProviderIsSkippedNotAnOutage() async {
        let rig = makeRig([.failure(CancellationError())])
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)

        rig.clock.tick(advancing: 45)
        await rig.clock.waitForSleepCount(2)  // the loop carried on and is sleeping again

        XCTAssertEqual(rig.provider.calls, 1)
        await rig.tracker.stop()
        await rig.events.waitUntilFinished()
        XCTAssertEqual(rig.events.events.count, 1)  // only the initial etaChanged; no feedLost
    }

    func testACancellationErrorFromTheSchedulerDoesNotEndTheLoop() async {
        let clock = FakeClock(start: t("08:00:00"))
        let provider = FakeProvider()
        provider.results = [.success(happy("08:57:00"))]
        let scheduler = CancellingScheduler(cancellingAttempt: 1)
        let plan = TrackerPlan(boardingId: "WAT", destinationId: "BSK", destinationCoordinate: nil,
                               config: TrackerConfig(leadTime: 600))
        let tracker = JourneyTracker(plan: plan, snapshot: happy(), provider: provider,
                                     locationSource: FakeLocationSource(), scheduler: scheduler, clock: clock)
        addTeardownBlock { await tracker.stop() }
        let events = EventCollector(tracker.events)
        await tracker.start()
        await clock.waitForSleepCount(1)  // the loop survived the spurious cancellation

        clock.tick(advancing: 45)
        await clock.waitForSleepCount(2)
        await events.waitForCount(2)

        XCTAssertEqual(provider.calls, 1)
        XCTAssertEqual(scheduler.attempts, [t("08:42:00"), t("08:47:00")])
        XCTAssertFalse(events.isFinished)
        await tracker.stop()
    }

    func testASpuriousSchedulerCancellationOnAClampedAlarmIsRetriedNotLost() async {
        let clock = FakeClock(start: t("08:00:00"))
        // ETA 08:08 with a 10 minute lead: the fire time is already past, so the alarm is clamped to now.
        let provider = FakeProvider()
        provider.results = [.success(happy("08:08:00"))]
        let scheduler = CancellingScheduler(cancellingAttempt: 2)  // the clamped call
        let plan = TrackerPlan(boardingId: "WAT", destinationId: "BSK", destinationCoordinate: nil,
                               config: TrackerConfig(leadTime: 600))
        let tracker = JourneyTracker(plan: plan, snapshot: happy(), provider: provider,
                                     locationSource: FakeLocationSource(), scheduler: scheduler, clock: clock)
        addTeardownBlock { await tracker.stop() }
        let events = EventCollector(tracker.events)
        await tracker.start()
        await clock.waitForSleepCount(1)

        clock.tick(advancing: 45)  // 08:00:45: clamped schedule(now) is cancelled spuriously
        await clock.waitForSleepCount(2)
        clock.tick(advancing: 45)  // 08:01:30: the retry must ask again
        await clock.waitForSleepCount(3)

        XCTAssertEqual(scheduler.attempts, [t("08:42:00"), t("08:00:45"), t("08:01:30")])
        XCTAssertEqual(scheduler.scheduled, [t("08:42:00"), t("08:01:30")])
        XCTAssertFalse(events.isFinished)
        await tracker.stop()
    }
}

/// Throws `CancellationError` on the given (1-based) `schedule` attempt while the calling task is not cancelled.
private final class CancellingScheduler: AlarmScheduler, @unchecked Sendable {
    private let lock = NSLock()
    private let cancellingAttempt: Int
    private var recorded: [Date] = []
    private var accepted: [Date] = []
    init(cancellingAttempt: Int) { self.cancellingAttempt = cancellingAttempt }
    var attempts: [Date] { lock.lock(); defer { lock.unlock() }; return recorded }
    var scheduled: [Date] { lock.lock(); defer { lock.unlock() }; return accepted }

    func schedule(fireAt: Date) async throws {
        lock.lock(); recorded.append(fireAt); let n = recorded.count
        if n != cancellingAttempt { accepted.append(fireAt) }
        lock.unlock()
        if n == cancellingAttempt { throw CancellationError() }
    }

    func cancel() async {}
}
