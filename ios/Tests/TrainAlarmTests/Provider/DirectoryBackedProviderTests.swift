import XCTest
@testable import TrainAlarm

final class DirectoryBackedProviderTests: XCTestCase {
    private final class RecordingProvider: TrainDataProvider {
        let service: Service
        var lastCallingAt: Station?
        init(service: Service) { self.service = service }
        func searchStations(query: String) async throws -> [Station] { throw ProviderError.notImplemented("fake") }
        func departureBoard(station: Station, from: Date, callingAt: Station?) async throws -> [Service] {
            lastCallingAt = callingAt
            return [service]
        }
        func serviceDetails(serviceId: String, date: Date) async throws -> Service { service }
    }

    private let json = #"[{"crs":"EDB","name":"Edinburgh","lat":55.9521,"lon":-3.1894},{"crs":"KGX","name":"London Kings Cross","lat":51.5309,"lon":-0.1233}]"#

    private func rtt(_ id: String, _ name: String) -> Station { Station(id: id, name: name, latitude: 0, longitude: 0) }

    private func makeService() throws -> Service {
        func stop(_ s: Station) -> Stop {
            Stop(station: s, scheduledArrival: nil, scheduledDeparture: nil, estimatedArrival: nil, estimatedDeparture: nil)
        }
        let kgx = rtt("KGX", "London Kings Cross"), xyz = rtt("XYZ", "Nowhere"), edb = rtt("EDB", "Edinburgh Waverley")
        return Service(id: "s1", operatorName: "LNER",
                       journey: try Journey(origin: kgx, destination: edb, stops: [stop(kgx), stop(xyz), stop(edb)]))
    }

    private func makeProvider() throws -> (DirectoryBackedProvider, RecordingProvider) {
        let base = RecordingProvider(service: try makeService())
        return (DirectoryBackedProvider(base: base, directory: try StationDirectory.parse(Data(json.utf8))), base)
    }

    func testServiceDetailsGetsRealCoordinatesForKnownStations() async throws {
        let (provider, _) = try makeProvider()
        let service = try await provider.serviceDetails(serviceId: "s1", date: Date())
        XCTAssertEqual(service.journey.destination.latitude, 55.9521, accuracy: 0.0001)
        XCTAssertEqual(service.journey.destination.name, "Edinburgh")
        XCTAssertEqual(service.journey.stops[0].station.longitude, -0.1233, accuracy: 0.0001)
    }

    func testUnknownStationKeepsItsNameAndHasNoCoordinate() async throws {
        let (provider, _) = try makeProvider()
        let unknown = try await provider.serviceDetails(serviceId: "s1", date: Date()).journey.stops[1].station
        XCTAssertEqual(unknown.name, "Nowhere")
        XCTAssertFalse(unknown.hasCoordinate)
    }

    func testDepartureBoardIsResolvedAndPassesCallingAtOn() async throws {
        let (provider, base) = try makeProvider()
        let edb = rtt("EDB", "Edinburgh")
        let board = try await provider.departureBoard(station: rtt("KGX", "London Kings Cross"), from: Date(), callingAt: edb)
        XCTAssertEqual(base.lastCallingAt, edb)
        XCTAssertTrue(board[0].journey.destination.hasCoordinate)
    }

    func testSearchStationsAnswersFromTheDirectory() async throws {
        let (provider, _) = try makeProvider()
        let names = try await provider.searchStations(query: "edin").map(\.name)
        XCTAssertEqual(names, ["Edinburgh"])
    }
}
