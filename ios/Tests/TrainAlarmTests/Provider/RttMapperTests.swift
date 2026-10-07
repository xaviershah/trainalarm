import XCTest
@testable import TrainAlarm

/// Tests RttMapper against a fixture built from RTT's real, verified schema
/// (docs/spec.md §3) - not a live call. If this fixture ever stops matching
/// the real API, that's a signal to re-check the schema, not to loosen
/// these assertions.
final class RttMapperTests: XCTestCase {

    private func loadFixture(_ name: String) throws -> [String: Any] {
        guard let url = Bundle(for: Self.self).url(forResource: name, withExtension: "json", subdirectory: "Fixtures") else {
            throw ProviderError.malformed("fixture \(name).json not found in test bundle")
        }
        let data = try Data(contentsOf: url)
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ProviderError.malformed("fixture was not a JSON object")
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
            guard case ProviderError.malformed = error else {
                XCTFail("expected ProviderError.malformed, got \(error)")
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

    private func fullService(_ name: String) throws -> Service {
        let fixture = try loadFixture(name)
        return try RttMapper.fullService(fixture["service"] as! [String: Any])
    }

    func testDepartureCancelledOnIntermediateStopIsNotArrivalCancelled() throws {
        let woking = try fullService("gb-nr-service-departure-cancelled").journey.stops[1]
        XCTAssertFalse(woking.isArrivalCancelled)
        XCTAssertTrue(woking.isDepartureCancelled)
        XCTAssertTrue(woking.isCancelled)
    }

    func testDisplayAsIsMappedPerStop() throws {
        let stops = try fullService("gb-nr-service-display-as").journey.stops
        let expected: [StopDisplay?] = [.call, .pass, .diverted, .cancelled, .unknown, .terminates]
        XCTAssertEqual(stops.map(\.displayAs), expected)
    }

    func testPassStopHasNoArrivalOrDepartureBecausePassActivityIsNotMapped() throws {
        let pass = try fullService("gb-nr-service-display-as").journey.stops[1]
        XCTAssertNil(pass.scheduledArrival)
        XCTAssertNil(pass.scheduledDeparture)
        XCTAssertNil(pass.bestArrival)
    }

    func testHasArrivedOnlyWhenArrivalHasRealtimeActual() throws {
        let stops = try fullService("gb-nr-service-display-as").journey.stops
        XCTAssertEqual(stops.map(\.hasArrived), [false, false, false, false, false, true])
    }

    func testExplicitJsonNullsMapToNil() throws {
        let stops = try fullService("gb-nr-service-nulls").journey.stops
        XCTAssertNil(stops[0].displayAs)
        XCTAssertNil(stops[0].estimatedDeparture)
        XCTAssertNil(stops[1].displayAs)
        XCTAssertNil(stops[1].estimatedArrival)
        XCTAssertFalse(stops[1].hasArrived)
    }

    func testWrongCaseDisplayAsIsUnknownAndUnparseableActualIsNotArrived() throws {
        let location: [String: Any] = [
            "location": ["description": "Reading", "shortCodes": ["RDG"]],
            "temporalData": [
                "arrival": ["scheduleAdvertised": "2026-09-13T08:00:00Z", "realtimeActual": "garbage"],
                "displayAs": "call",
            ],
        ]
        let stop = try RttMapper.stop(from: location)
        XCTAssertEqual(stop.displayAs, .unknown)
        XCTAssertFalse(stop.hasArrived)
        XCTAssertNil(stop.estimatedArrival)
    }

    func testNullOperatorNameFallsBackToUnknown() throws {
        let lineUp: [String: Any] = [
            "scheduleMetadata": ["uniqueIdentity": "gb-nr:X:2026-09-13", "operator": ["name": NSNull()]],
            "temporalData": [String: Any](),
        ]
        let atStation = Station(id: "WAT", name: "London Waterloo", latitude: 51.5031, longitude: -0.1132)
        XCTAssertEqual(try RttMapper.serviceSummary(lineUp, atStation: atStation).operatorName, "Unknown")
    }

    func testFullServiceThrowsMalformedForEmptyLocations() {
        let service: [String: Any] = [
            "scheduleMetadata": ["uniqueIdentity": "gb-nr:X:2026-09-13"],
            "locations": [[String: Any]](),
        ]
        XCTAssertThrowsError(try RttMapper.fullService(service)) { error in
            XCTAssertEqual(error as? ProviderError, .malformed("service had no locations"))
        }
    }

    func testStopThrowsMalformedWhenTemporalDataIsMissing() {
        let location: [String: Any] = ["location": ["description": "Reading"]]
        XCTAssertThrowsError(try RttMapper.stop(from: location)) { error in
            guard case ProviderError.malformed = error else {
                return XCTFail("expected malformed, got \(error)")
            }
        }
    }

    private let waterloo = Station(id: "WAT", name: "London Waterloo", latitude: 51.5031, longitude: -0.1132)

    private func lineUps() throws -> [[String: Any]] {
        try loadFixture("gb-nr-location")["services"] as! [[String: Any]]
    }

    func testServiceSummaryMapsALineUpEntry() throws {
        let service = try RttMapper.serviceSummary(try lineUps()[0], atStation: waterloo)

        XCTAssertEqual(service.id, "gb-nr:L02001:2026-09-13")
        XCTAssertEqual(service.operatorName, "South Western Railway")
        XCTAssertEqual(service.journey.origin.id, "WAT")
        XCTAssertEqual(service.journey.destination.id, "BSK")
        XCTAssertEqual(service.journey.stops.count, 1)
        let stop = service.journey.stops[0]
        let formatter = ISO8601DateFormatter()
        XCTAssertEqual(stop.scheduledDeparture, formatter.date(from: "2026-09-13T14:00:00Z"))
        XCTAssertEqual(stop.estimatedDeparture, formatter.date(from: "2026-09-13T14:02:00Z"))
        XCTAssertEqual(stop.displayAs, .call)
        XCTAssertFalse(stop.isCancelled)
    }

    func testServiceSummaryMapsACancelledLineUp() throws {
        let service = try RttMapper.serviceSummary(try lineUps()[1], atStation: waterloo)

        XCTAssertEqual(service.journey.destination.id, "SOU")
        let stop = service.journey.stops[0]
        XCTAssertFalse(stop.isArrivalCancelled)
        XCTAssertTrue(stop.isDepartureCancelled)
        XCTAssertEqual(stop.displayAs, .cancelled)
    }

    func testServiceSummaryFallsBackToTheBoardStationWithoutOriginOrDestination() throws {
        let lineUp: [String: Any] = [
            "scheduleMetadata": ["uniqueIdentity": "gb-nr:X:2026-09-13"],
            "temporalData": ["departure": ["scheduleAdvertised": "2026-09-13T14:00:00Z"]],
        ]
        let service = try RttMapper.serviceSummary(lineUp, atStation: waterloo)
        XCTAssertEqual(service.journey.origin.id, "WAT")
        XCTAssertEqual(service.journey.destination.id, "WAT")
    }

    func testServiceSummaryThrowsMalformedWithoutScheduleMetadata() {
        let lineUp: [String: Any] = ["temporalData": [String: Any]()]
        XCTAssertThrowsError(try RttMapper.serviceSummary(lineUp, atStation: waterloo)) { error in
            guard case ProviderError.malformed = error else {
                return XCTFail("expected malformed, got \(error)")
            }
        }
    }
}
