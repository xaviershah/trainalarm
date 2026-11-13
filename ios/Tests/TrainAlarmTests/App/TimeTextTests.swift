import XCTest
@testable import TrainAlarm

final class TimeTextTests: XCTestCase {
    private func date(_ s: String) -> Date { ISO8601DateFormatter().date(from: s)! }

    func testClockUsesLondonTimeInSummer() {
        XCTAssertEqual(TimeText.clock(date("2026-07-01T17:50:00Z")), TimeText.HM(hours: "18", minutes: "50"))
    }

    func testClockAcrossTheMarchChangeover() {
        XCTAssertEqual(TimeText.clock(date("2026-03-29T00:59:00Z")), TimeText.HM(hours: "00", minutes: "59"))
        XCTAssertEqual(TimeText.clock(date("2026-03-29T01:01:00Z")), TimeText.HM(hours: "02", minutes: "01"))
    }

    func testClockAcrossMidnight() {
        XCTAssertEqual(TimeText.clock(date("2026-07-01T22:59:00Z")), TimeText.HM(hours: "23", minutes: "59"))
        XCTAssertEqual(TimeText.clock(date("2026-07-01T23:01:00Z")), TimeText.HM(hours: "00", minutes: "01"))
    }

    func testClockIgnoresTheDeviceZone() {
        let saved = NSTimeZone.default
        NSTimeZone.default = TimeZone(identifier: "Asia/Tokyo")!
        defer { NSTimeZone.default = saved }
        XCTAssertEqual(TimeText.clock(date("2026-07-01T17:50:00Z")).plain, "18:50")
    }

    func testCountdownRoundsDownToTheMinute() {
        XCTAssertEqual(TimeText.countdown(158 * 60 + 59), TimeText.HM(hours: "2", minutes: "38"))
        XCTAssertEqual(TimeText.countdown(12 * 60), TimeText.HM(hours: "0", minutes: "12"))
    }

    func testCountdownNeverGoesNegative() {
        XCTAssertEqual(TimeText.countdown(-30), TimeText.HM(hours: "0", minutes: "00"))
    }

    func testCountdownSurvivesNonFiniteAndHugeValues() {
        let zero = TimeText.HM(hours: "0", minutes: "00")
        XCTAssertEqual(TimeText.countdown(0), zero)
        XCTAssertEqual(TimeText.countdown(.infinity), zero)
        XCTAssertEqual(TimeText.countdown(-.infinity), zero)
        XCTAssertEqual(TimeText.countdown(.nan), zero)
        // Clamped rather than trapping; the exact ceiling is an implementation detail.
        XCTAssertFalse(TimeText.countdown(1e30).hours.isEmpty)
    }

    func testDateLabelIsNilOnTheSameLondonDay() {
        XCTAssertNil(TimeText.dateLabel(for: date("2026-07-01T22:59:00Z"), now: date("2026-07-01T22:50:00Z")))
    }

    func testDateLabelAppearsAfterLondonMidnight() {
        // 23:10Z on 1 July is 00:10 BST on 2 July.
        XCTAssertEqual(TimeText.dateLabel(for: date("2026-07-01T23:10:00Z"), now: date("2026-07-01T22:50:00Z")), "Thu 2 Jul")
    }
}
