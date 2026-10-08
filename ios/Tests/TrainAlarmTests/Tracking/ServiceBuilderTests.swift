import XCTest
@testable import TrainAlarm

final class ServiceBuilderTests: XCTestCase {
    func testHappyPathMirrorsTheSharedFixture() throws {
        let built = ServiceBuilder.happyPath()
        let loaded = try ServiceBuilder.loadFixture("gb-nr-service")

        XCTAssertEqual(built.id, loaded.id)
        XCTAssertEqual(built.journey.stops.map(\.station.id), loaded.journey.stops.map(\.station.id))
        XCTAssertEqual(built.journey.stops.map(\.scheduledArrival), loaded.journey.stops.map(\.scheduledArrival))
        XCTAssertEqual(built.journey.stops.map(\.estimatedArrival), loaded.journey.stops.map(\.estimatedArrival))
    }

    func testReplacingSwapsOneStopOnly() {
        let original = ServiceBuilder.happyPath()
        let changed = ServiceBuilder.replacing(original, stopAt: 2, with: ServiceBuilder.stop("BSK", scheduledArrival: "08:47:00", estimatedArrival: "09:00:00"))

        XCTAssertEqual(changed.journey.stops[0], original.journey.stops[0])
        XCTAssertEqual(changed.journey.stops[2].estimatedArrival, ServiceBuilder.time("09:00:00"))
        XCTAssertEqual(changed.id, original.id)
    }
}
