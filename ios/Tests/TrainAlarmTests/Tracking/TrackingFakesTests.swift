import XCTest
@testable import TrainAlarm

final class TrackingFakesTests: XCTestCase {
    func testFakeClockSleepResumesOnTickAndTimeAdvances() async {
        let clock = FakeClock(start: ServiceBuilder.time("08:00:00"))
        let sleeper = Task { try await clock.sleep(for: 45) }

        await clock.waitForSleepCount(1)
        clock.tick(advancing: 45)
        let result = await sleeper.result

        XCTAssertNoThrow(try result.get())
        XCTAssertEqual(clock.now(), ServiceBuilder.time("08:00:45"))
    }

    func testFakeClockSleepThrowsCancellationErrorWhenCancelled() async {
        let clock = FakeClock(start: ServiceBuilder.time("08:00:00"))
        let sleeper = Task { try await clock.sleep(for: 45) }

        await clock.waitForSleepCount(1)
        sleeper.cancel()
        let result = await sleeper.result

        XCTAssertThrowsError(try result.get()) { XCTAssertTrue($0 is CancellationError) }
    }

    func testFakeAlarmSchedulerRecordsFailedAttemptsSeparately() async throws {
        let scheduler = FakeAlarmScheduler()
        scheduler.failuresRemaining = 1
        let fire = ServiceBuilder.time("08:37:00")

        do { try await scheduler.schedule(fireAt: fire); XCTFail("expected a refusal") } catch {}
        try await scheduler.schedule(fireAt: fire)

        XCTAssertEqual(scheduler.attempts, [fire, fire])
        XCTAssertEqual(scheduler.scheduled, [fire])
    }

    func testFakeProviderServesInOrderThenRepeatsTheLast() async throws {
        let provider = FakeProvider()
        let first = ServiceBuilder.happyPath(destinationEstimate: "08:52:00")
        let second = ServiceBuilder.happyPath(destinationEstimate: "08:55:00")
        provider.results = [.success(first), .success(second)]

        let a = try await provider.serviceDetails(serviceId: "x", date: Date())
        let b = try await provider.serviceDetails(serviceId: "x", date: Date())
        let c = try await provider.serviceDetails(serviceId: "x", date: Date())

        XCTAssertEqual([a, b, c], [first, second, second])
        XCTAssertEqual(provider.calls, 3)
    }
}
