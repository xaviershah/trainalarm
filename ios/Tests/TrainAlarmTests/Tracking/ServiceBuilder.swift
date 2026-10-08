import XCTest
@testable import TrainAlarm

/// Builds `Service` values for tracker tests. All times are on 2026-09-13 UTC, like the
/// fixtures. Provider stations carry latitude/longitude 0, exactly as `RttMapper` produces them.
enum ServiceBuilder {
    private final class BundleToken {}

    static let happyPathId = "gb-nr:L01525:2026-09-13"

    static func time(_ hhmmss: String) -> Date {
        ISO8601DateFormatter().date(from: "2026-09-13T\(hhmmss)Z")!
    }

    static func station(_ id: String) -> Station {
        Station(id: id, name: id, latitude: 0, longitude: 0)
    }

    static func stop(
        _ id: String,
        scheduledArrival: String? = nil,
        estimatedArrival: String? = nil,
        isArrivalCancelled: Bool = false,
        isDepartureCancelled: Bool = false,
        displayAs: StopDisplay? = nil,
        hasArrived: Bool = false
    ) -> Stop {
        Stop(
            station: station(id),
            scheduledArrival: scheduledArrival.map(time),
            scheduledDeparture: nil,
            estimatedArrival: estimatedArrival.map(time),
            estimatedDeparture: nil,
            isArrivalCancelled: isArrivalCancelled,
            isDepartureCancelled: isDepartureCancelled,
            displayAs: displayAs,
            hasArrived: hasArrived
        )
    }

    static func service(id: String = happyPathId, stops: [Stop]) -> Service {
        let journey = try! Journey(origin: stops.first!.station, destination: stops.last!.station, stops: stops)
        return Service(id: id, operatorName: "South Western Railway", journey: journey)
    }

    /// WAT -> WOK -> BSK, mirroring `gb-nr-service.json`: BSK is scheduled 08:47:00.
    static func happyPath(destinationEstimate: String? = "08:52:00", id: String = happyPathId) -> Service {
        service(id: id, stops: [
            stop("WAT"),
            stop("WOK", scheduledArrival: "08:23:00", estimatedArrival: "08:27:00"),
            stop("BSK", scheduledArrival: "08:47:00", estimatedArrival: destinationEstimate),
        ])
    }

    /// Loads a shared fixture (without the `.json` extension) through the real mapper.
    static func loadFixture(_ name: String) throws -> Service {
        guard let url = Bundle(for: BundleToken.self).url(forResource: name, withExtension: "json", subdirectory: "Fixtures") else {
            throw ProviderError.malformed("fixture \(name).json not found in test bundle")
        }
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        return try RttMapper.fullService(json["service"] as! [String: Any])
    }

    /// A copy of `service` with the stop at `index` replaced.
    static func replacing(_ service: Service, stopAt index: Int, with stop: Stop) -> Service {
        var stops = service.journey.stops
        stops[index] = stop
        let journey = try! Journey(origin: service.journey.origin, destination: service.journey.destination, stops: stops)
        return Service(id: service.id, operatorName: service.operatorName, journey: journey)
    }
}
