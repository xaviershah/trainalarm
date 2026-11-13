import XCTest
@testable import TrainAlarm

final class SystemTrackerClockTests: XCTestCase {
    func testNowIsCloseToTheSystemTime() {
        XCTAssertEqual(SystemTrackerClock().now().timeIntervalSinceNow, 0, accuracy: 1)
    }

    func testSleepWaitsAtLeastTheRequestedTime() async throws {
        let start = Date()
        try await SystemTrackerClock().sleep(for: 0.05)
        XCTAssertGreaterThanOrEqual(Date().timeIntervalSince(start), 0.04)
    }

    func testSleepThrowsCancellationWhenTheTaskIsCancelled() async {
        let task = Task { try await SystemTrackerClock().sleep(for: 30) }
        task.cancel()
        do {
            try await task.value
            XCTFail("sleep should have thrown")
        } catch {
            XCTAssertTrue(error is CancellationError)
        }
    }

    func testNonFiniteAndNegativeWaitsAreNoWaitAndDoNotCrash() async throws {
        for value in [Double.nan, .infinity, -.infinity, -5, 0] {
            XCTAssertEqual(SystemTrackerClock.nanoseconds(for: value), 0)
            let start = Date()
            try await SystemTrackerClock().sleep(for: value)
            XCTAssertLessThan(Date().timeIntervalSince(start), 1)
        }
    }

    func testAHugeWaitIsCappedInsteadOfOverflowing() async {
        XCTAssertEqual(SystemTrackerClock.nanoseconds(for: 1e300), 86_400 * 1_000_000_000)
        XCTAssertEqual(SystemTrackerClock.nanoseconds(for: 2e10), 86_400 * 1_000_000_000)
        XCTAssertEqual(SystemTrackerClock.nanoseconds(for: 1.5), 1_500_000_000)
        let task = Task { try await SystemTrackerClock().sleep(for: 1e300) }
        task.cancel()
        do { try await task.value; XCTFail("sleep should have thrown") } catch { XCTAssertTrue(error is CancellationError) }
    }
}
