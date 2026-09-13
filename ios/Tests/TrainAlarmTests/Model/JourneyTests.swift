import XCTest
@testable import TrainAlarm

final class JourneyTests: XCTestCase {
    let paddington = Station(id: "PAD", name: "London Paddington", latitude: 51.5154, longitude: -0.1755)
    let reading = Station(id: "RDG", name: "Reading", latitude: 51.4585, longitude: -0.9781)
    let bristol = Station(id: "BRI", name: "Bristol Temple Meads", latitude: 51.4491, longitude: -2.5813)
    let time = ISO8601DateFormatter().date(from: "2026-09-13T08:00:00Z")!

    private func stop(at station: Station) -> Stop {
        Stop(station: station, scheduledArrival: time, scheduledDeparture: time, estimatedArrival: time, estimatedDeparture: time)
    }

    func testDestinationStopFindsDestinationInCallingPattern() throws {
        let journey = try Journey(origin: paddington, destination: bristol, stops: [stop(at: paddington), stop(at: reading), stop(at: bristol)])
        XCTAssertEqual(journey.destinationStop?.station.id, bristol.id)
    }

    func testDestinationStopIsNilWhenDestinationDroppedFromPattern() throws {
        // Models the "diverted/terminated early" case from docs/spec.md §4 -
        // the destination station is no longer in the calling pattern.
        let journey = try Journey(origin: paddington, destination: bristol, stops: [stop(at: paddington), stop(at: reading)])
        XCTAssertNil(journey.destinationStop)
    }

    func testJourneyRejectsEmptyStopList() {
        XCTAssertThrowsError(try Journey(origin: paddington, destination: bristol, stops: []))
    }
}
