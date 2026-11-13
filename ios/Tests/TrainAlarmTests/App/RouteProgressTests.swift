import XCTest
@testable import TrainAlarm

final class RouteProgressTests: XCTestCase {
    private func stop(_ id: String, dep: String? = nil, arr: String? = nil) -> Stop {
        Stop(station: ServiceBuilder.station(id), scheduledArrival: arr.map(ServiceBuilder.time),
             scheduledDeparture: dep.map(ServiceBuilder.time), estimatedArrival: nil, estimatedDeparture: nil)
    }

    private var stops: [Stop] { [stop("AAA", dep: "08:00:00"), stop("BBB", arr: "08:30:00"), stop("CCC", arr: "09:00:00")] }
    private func t(_ hhmmss: String) -> Date { ServiceBuilder.time(hhmmss) }

    func testBeforeDepartureTheTrainHasNotMoved() {
        let p = RouteProgress.make(stops: stops, from: "AAA", to: "CCC", eta: nil, now: t("07:50:00"))
        XCTAssertEqual(p?.fraction, 0)
        XCTAssertEqual(p?.nextStop?.station.id, "BBB")
        XCTAssertEqual(p?.stopsLeft, 2)
    }

    func testHalfwayThroughTheJourney() {
        let p = RouteProgress.make(stops: stops, from: "AAA", to: "CCC", eta: nil, now: t("08:30:00"))
        XCTAssertEqual(p?.fraction ?? 0, 0.5, accuracy: 0.001)
        XCTAssertEqual(p?.nextStop?.station.id, "CCC")
        XCTAssertEqual(p?.stopsLeft, 1)
    }

    func testAfterArrivalTheStripIsFull() {
        let p = RouteProgress.make(stops: stops, from: "AAA", to: "CCC", eta: nil, now: t("09:10:00"))
        XCTAssertEqual(p?.fraction, 1)
        XCTAssertNil(p?.nextStop)
        XCTAssertEqual(p?.stopsLeft, 0)
    }

    func testALiveEtaStretchesTheJourney() {
        let p = RouteProgress.make(stops: stops, from: "AAA", to: "CCC", eta: t("09:10:00"), now: t("08:35:00"))
        XCTAssertEqual(p?.fraction ?? 0, 0.5, accuracy: 0.001)
    }

    func testAStationThatIsNotOnTheRouteGivesNothing() {
        XCTAssertNil(RouteProgress.make(stops: stops, from: "ZZZ", to: "CCC", eta: nil, now: t("08:00:00")))
        XCTAssertNil(RouteProgress.make(stops: stops, from: "AAA", to: "ZZZ", eta: nil, now: t("08:00:00")))
    }

    func testADestinationBeforeTheBoardingStopGivesNothing() {
        XCTAssertNil(RouteProgress.make(stops: stops, from: "CCC", to: "AAA", eta: nil, now: t("08:00:00")))
    }

    func testMissingDepartureTimeKeepsTheMarkerAtTheStart() {
        let noTimes = [stop("AAA"), stop("BBB", arr: "08:30:00"), stop("CCC", arr: "09:00:00")]
        XCTAssertEqual(RouteProgress.make(stops: noTimes, from: "AAA", to: "CCC", eta: nil, now: t("08:45:00"))?.fraction, 0)
    }
}
