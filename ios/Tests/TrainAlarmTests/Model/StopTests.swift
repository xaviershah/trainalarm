import XCTest
@testable import TrainAlarm

final class StopTests: XCTestCase {
    let reading = Station(id: "READING", name: "Reading", latitude: 51.4585, longitude: -0.9781)

    func testBestArrivalPrefersLiveEstimateOverSchedule() {
        let scheduled = ISO8601DateFormatter().date(from: "2026-09-13T08:00:00Z")!
        let estimated = ISO8601DateFormatter().date(from: "2026-09-13T08:07:00Z")!
        let stop = Stop(station: reading, scheduledArrival: scheduled, scheduledDeparture: scheduled, estimatedArrival: estimated, estimatedDeparture: estimated)
        XCTAssertEqual(stop.bestArrival, estimated)
    }

    func testBestArrivalFallsBackToScheduleWhenNoLiveData() {
        let scheduled = ISO8601DateFormatter().date(from: "2026-09-13T08:00:00Z")!
        let stop = Stop(station: reading, scheduledArrival: scheduled, scheduledDeparture: scheduled, estimatedArrival: nil, estimatedDeparture: nil)
        XCTAssertEqual(stop.bestArrival, scheduled)
    }

    func testIsCancelledIsFalseByDefault() {
        let stop = Stop(station: reading, scheduledArrival: nil, scheduledDeparture: nil, estimatedArrival: nil, estimatedDeparture: nil)
        XCTAssertFalse(stop.isArrivalCancelled)
        XCTAssertFalse(stop.isDepartureCancelled)
        XCTAssertFalse(stop.isCancelled)
        XCTAssertNil(stop.displayAs)
        XCTAssertFalse(stop.hasArrived)
    }

    func testIsCancelledIsTrueWhenOnlyDepartureIsCancelled() {
        let stop = Stop(station: reading, scheduledArrival: nil, scheduledDeparture: nil, estimatedArrival: nil, estimatedDeparture: nil, isDepartureCancelled: true)
        XCTAssertFalse(stop.isArrivalCancelled)
        XCTAssertTrue(stop.isDepartureCancelled)
        XCTAssertTrue(stop.isCancelled)
    }
}
