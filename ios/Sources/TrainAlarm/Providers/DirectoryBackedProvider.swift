import Foundation

extension Station {
    /// RTT stations carry latitude and longitude 0 until resolved through `StationDirectory`.
    var hasCoordinate: Bool { latitude != 0 || longitude != 0 }
}

/// Wraps a provider so that stations get real coordinates and station search works. RTT has no
/// search endpoint and its stations have no coordinates (docs/spec.md section 3), so both come from
/// the bundled NaPTAN directory, keyed by CRS. Stations the directory does not know stay as RTT
/// gave them, with `hasCoordinate == false`.
struct DirectoryBackedProvider: TrainDataProvider {
    let base: TrainDataProvider
    let directory: StationDirectory

    func searchStations(query: String) async throws -> [Station] { directory.search(name: query) }

    func departureBoard(station: Station, from: Date, callingAt: Station?) async throws -> [Service] {
        try await base.departureBoard(station: station, from: from, callingAt: callingAt).map(resolved)
    }

    func serviceDetails(serviceId: String, date: Date) async throws -> Service {
        try resolved(await base.serviceDetails(serviceId: serviceId, date: date))
    }

    private func resolve(_ station: Station) -> Station { directory.station(crs: station.id) ?? station }

    private func resolved(_ service: Service) throws -> Service {
        let journey = service.journey
        let stops = journey.stops.map { stop in
            Stop(station: resolve(stop.station), scheduledArrival: stop.scheduledArrival,
                 scheduledDeparture: stop.scheduledDeparture, estimatedArrival: stop.estimatedArrival,
                 estimatedDeparture: stop.estimatedDeparture, isArrivalCancelled: stop.isArrivalCancelled,
                 isDepartureCancelled: stop.isDepartureCancelled, displayAs: stop.displayAs,
                 hasArrived: stop.hasArrived)
        }
        return Service(id: service.id, operatorName: service.operatorName,
                       journey: try Journey(origin: resolve(journey.origin),
                                            destination: resolve(journey.destination), stops: stops))
    }
}
