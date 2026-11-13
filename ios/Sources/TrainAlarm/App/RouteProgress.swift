import Foundation

/// Where the train is between the boarding station and the destination, for the route strip. Stop times are
/// the ones captured when tracking started (the tracker does not publish updated stops), so only `eta` is live.
struct RouteProgress: Equatable {
    /// 0 at departure from the boarding station, 1 at the destination.
    let fraction: Double
    let nextStop: Stop?
    /// Stops still to come, including the destination.
    let stopsLeft: Int

    static func make(stops: [Stop], from fromId: String, to toId: String, eta: Date?, now: Date) -> RouteProgress? {
        guard let boardIndex = stops.firstIndex(where: { $0.station.id == fromId }),
              let destIndex = stops.indices.first(where: { $0 > boardIndex && stops[$0].station.id == toId }) else { return nil }
        let ahead = stops[(boardIndex + 1)...destIndex].filter { !$0.hasArrived && ($0.bestArrival ?? .distantFuture) > now }
        var fraction = 0.0
        let board = stops[boardIndex]
        if let departure = board.estimatedDeparture ?? board.scheduledDeparture, let arrival = eta ?? stops[destIndex].bestArrival,
           arrival > departure {
            fraction = max(0, min(1, now.timeIntervalSince(departure) / arrival.timeIntervalSince(departure)))
        }
        return RouteProgress(fraction: fraction, nextStop: ahead.first, stopsLeft: ahead.count)
    }
}
