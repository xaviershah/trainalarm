import XCTest
@testable import TrainAlarm

final class TrackingCardsTests: XCTestCase {
    private func titles(_ d: TrackingDisplay) -> [String] { TrackingCards.make(display: d, destination: "Edinburgh").map(\.title) }

    func testNothingFlaggedShowsNoCards() {
        XCTAssertEqual(titles(TrackingDisplay()), [])
    }

    func testCardsAreOrderedMostUrgentFirst() {
        var d = TrackingDisplay()
        d.isEstimated = true
        d.feedLost = true
        d.destinationDropped = true
        d.cancelled = true
        d.alarmNotSet = true
        XCTAssertEqual(titles(d), ["Your alarm is NOT set", "Train cancelled", "Edinburgh is no longer on this route",
                                   "No live data", "Estimated arrival"])
    }

    func testRateLimitedReplacesTheNoLiveDataCard() {
        var d = TrackingDisplay()
        d.feedLost = true
        d.rateLimited = true
        XCTAssertEqual(titles(d), ["Train data is busy"])
    }

    func testNoLiveDataSaysWhenTheLastGoodUpdateWas() {
        var d = TrackingDisplay()
        d.feedLost = true
        d.lastGoodAt = ISO8601DateFormatter().date(from: "2026-09-13T08:00:00Z")
        let card = TrackingCards.make(display: d, destination: "Edinburgh")[0]
        XCTAssertEqual(card.detail, "Using the timetable and your GPS. Last good update 09:00.")
        XCTAssertEqual(card.tone, .plain)
    }

    func testEstimatedArrivalExplainsItsSource() {
        var d = TrackingDisplay()
        d.isEstimated = true
        d.estimateSource = .gpsPace
        XCTAssertEqual(TrackingCards.make(display: d, destination: "X")[0].detail, "Worked out from your speed and position.")
        d.estimateSource = .lastKnownTrajectory
        XCTAssertEqual(TrackingCards.make(display: d, destination: "X")[0].detail, "Worked out from the last live update.")
    }

    func testTonesMatchTheMockup() {
        var d = TrackingDisplay()
        d.cancelled = true
        XCTAssertEqual(TrackingCards.make(display: d, destination: "X")[0].tone, .warn)
        d = TrackingDisplay()
        d.alarmNotSet = true
        XCTAssertEqual(TrackingCards.make(display: d, destination: "X")[0].tone, .error)
        d = TrackingDisplay()
        d.isEstimated = true
        XCTAssertEqual(TrackingCards.make(display: d, destination: "X")[0].tone, .info)
    }
}
