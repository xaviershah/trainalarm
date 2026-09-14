import XCTest
@testable import TrainAlarm

/// Tests RttMapper against a fixture built from RTT's real, verified schema
/// (docs/spec.md §3) - not a live call. If this fixture ever stops matching
/// the real API, that's a signal to re-check the schema, not to loosen
/// these assertions.
final class RttMapperTests: XCTestCase {

    private func loadFixture(_ name: String) throws -> [String: Any] {
        guard let url = Bundle(for: Self.self).url(forResource: name, withExtension: "json", subdirectory: "Fixtures") else {
            throw RttError.malformedResponse("fixture \(name).json not found in test bundle")
        }
        let data = try Data(contentsOf: url)
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw RttError.malformedResponse("fixture was not a JSON object")
        }
        return json
    }

    func testFullServiceParsesCompleteCallingPattern() throws {
        let fixture = try loadFixture("gb-nr-service")
        let serviceJSON = fixture["service"] as! [String: Any]
        let service = try RttMapper.fullService(serviceJSON)

        XCTAssertEqual(service.id, "gb-nr:L01525:2026-09-13")
        XCTAssertEqual(service.operatorName, "South Western Railway")
        XCTAssertEqual(service.journey.origin.id, "WAT")
        XCTAssertEqual(service.journey.destination.id, "BSK")
        XCTAssertEqual(service.journey.stops.count, 3)
    }

    func testFullServicePrefersLiveEstimateOverSchedule() throws {
        let fixture = try loadFixture("gb-nr-service")
        let serviceJSON = fixture["service"] as! [String: Any]
        let service = try RttMapper.fullService(serviceJSON)

        let woking = service.journey.stops[1]
        let formatter = ISO8601DateFormatter()
        XCTAssertEqual(woking.scheduledArrival, formatter.date(from: "2026-09-13T08:23:00Z"))
        XCTAssertEqual(woking.estimatedArrival, formatter.date(from: "2026-09-13T08:27:00Z"))
    }

    func testFullServiceDestinationStopResolvesFromCallingPattern() throws {
        let fixture = try loadFixture("gb-nr-service")
        let serviceJSON = fixture["service"] as! [String: Any]
        let service = try RttMapper.fullService(serviceJSON)

        let destinationStop = service.journey.destinationStop
        XCTAssertEqual(destinationStop?.station.id, "BSK")
        // realtimeEstimate is the only live field present for this stop -
        // bestRealtime must fall through to it, not just realtimeActual/Forecast.
        let formatter = ISO8601DateFormatter()
        XCTAssertEqual(destinationStop?.estimatedArrival, formatter.date(from: "2026-09-13T08:52:00Z"))
    }

    func testFullServiceNoStopsAreCancelledInHappyPathFixture() throws {
        let fixture = try loadFixture("gb-nr-service")
        let serviceJSON = fixture["service"] as! [String: Any]
        let service = try RttMapper.fullService(serviceJSON)
        XCTAssertFalse(service.journey.stops.contains { $0.isCancelled })
    }

    func testFullServiceAllStopsCancelledWhenServiceIsCancelled() throws {
        let fixture = try loadFixture("gb-nr-service-cancelled")
        let serviceJSON = fixture["service"] as! [String: Any]
        let service = try RttMapper.fullService(serviceJSON)

        XCTAssertEqual(service.journey.stops.count, 3)
        XCTAssertTrue(service.journey.stops.allSatisfy { $0.isCancelled })
        // Cancelled stops still carry their scheduled times - a cancelled
        // service is not the same as "no data", the alarm/tracking layer
        // needs the schedule to know what was supposed to happen.
        let formatter = ISO8601DateFormatter()
        XCTAssertEqual(service.journey.destinationStop?.scheduledArrival, formatter.date(from: "2026-09-13T09:47:00Z"))
        // No realtime fields are present on a cancelled stop in this fixture.
        XCTAssertNil(service.journey.destinationStop?.estimatedArrival)
    }

    func testFullServiceThrowsOnMissingLocationsField() throws {
        // A malformed/incomplete response (missing "locations" entirely)
        // must fail loudly, not silently produce an empty or wrong journey.
        let fixture = try loadFixture("gb-nr-service-malformed")
        let serviceJSON = fixture["service"] as! [String: Any]
        XCTAssertThrowsError(try RttMapper.fullService(serviceJSON)) { error in
            guard case RttError.malformedResponse = error else {
                XCTFail("expected RttError.malformedResponse, got \(error)")
                return
            }
        }
    }

    func testFullServiceDestinationStopIsNilWhenServiceTerminatesEarly() throws {
        // The service's "destination" field still says Basingstoke, but the
        // calling pattern only reaches Woking (TERMINATES) - models a real
        // early-termination/diversion case from docs/spec.md §4.
        let fixture = try loadFixture("gb-nr-service-destination-dropped")
        let serviceJSON = fixture["service"] as! [String: Any]
        let service = try RttMapper.fullService(serviceJSON)

        XCTAssertEqual(service.journey.destination.id, "BSK")
        XCTAssertEqual(service.journey.stops.count, 2)
        XCTAssertNil(service.journey.destinationStop)
    }
}
