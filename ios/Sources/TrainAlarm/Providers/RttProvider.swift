import Foundation

enum RttError: Error {
    case notImplemented(String)
    case malformedResponse(String)
    case httpError(Int)
}

/// Realtime Trains' next-generation API (data.rtt.io, gb-nr namespace).
/// Schema verified against the real OpenAPI spec on 2026-09-13 - see
/// docs/spec.md §3. NOT the legacy api.rtt.io, which RTT is shutting down
/// 30 Sep 2026.
///
/// `accessToken` is a bearer token from https://api-portal.rtt.io - per
/// RTT's own docs, this must not ship inside a distributable client app;
/// it should be proxied through a server you control before release. Fine
/// for local development/testing in the meantime.
final class RttProvider: TrainDataProvider {
    private let accessToken: String
    private let baseUrl = URL(string: "https://data.rtt.io")!
    private let session: URLSession

    init(accessToken: String, session: URLSession = .shared) {
        self.accessToken = accessToken
        self.session = session
    }

    func searchStations(query: String) async throws -> [Station] {
        // RTT's API is location-code based (CRS/TIPLOC), not a free-text
        // station search endpoint - see docs/spec.md §3. A real
        // implementation needs a separate static station name->code
        // dataset to resolve free text before calling /gb-nr/location.
        // Left unimplemented rather than guessed.
        throw RttError.notImplemented(
            "RTT has no free-text station search endpoint; resolve to a CRS/TIPLOC code via a static station list first."
        )
    }

    func departureBoard(station: Station, from: Date) async throws -> [Service] {
        let iso = ISO8601DateFormatter().string(from: from)
        let json = try await getJSON(path: "/gb-nr/location", query: ["code": station.id, "timeFrom": iso])
        let services = json["services"] as? [[String: Any]] ?? []
        return try services.map { try RttMapper.serviceSummary($0, atStation: station) }
    }

    func serviceDetails(serviceId: String, date: Date) async throws -> Service {
        let json = try await getJSON(path: "/gb-nr/service", query: ["uniqueIdentity": serviceId])
        guard let service = json["service"] as? [String: Any] else {
            throw RttError.malformedResponse("missing 'service' object")
        }
        return try RttMapper.fullService(service)
    }

    private func getJSON(path: String, query: [String: String]) async throws -> [String: Any] {
        var components = URLComponents(url: baseUrl.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        components.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }

        var request = URLRequest(url: components.url!)
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw RttError.httpError(http.statusCode)
        }
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw RttError.malformedResponse("top-level response was not a JSON object")
        }
        return json
    }
}

/// Pure JSON -> domain-model mapping, factored out so it's testable against
/// fixture JSON without a network call. Field names here must match the
/// verified schema in docs/spec.md §3 - do not add a field without checking
/// it against the real spec first.
enum RttMapper {

    static func station(from geographicLocation: [String: Any]) -> Station {
        let shortCodes = geographicLocation["shortCodes"] as? [String]
        let description = geographicLocation["description"] as? String ?? "UNKNOWN"
        let id = shortCodes?.first ?? description
        return Station(
            id: id,
            name: description,
            // RTT's GeographicLocation doesn't carry lat/lon - a separate
            // static station dataset supplies coordinates for GPS use.
            // See docs/spec.md §3.
            latitude: 0,
            longitude: 0
        )
    }

    private static func parseTime(_ iso: String?) -> Date? {
        guard let iso else { return nil }
        if let date = ISO8601DateFormatter().date(from: iso) { return date }
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractional.date(from: iso)
    }

    /// Best real-time instant for one activity (arrival/departure), per IndividualTemporalData.
    private static func bestRealtime(_ temporal: [String: Any]?) -> Date? {
        guard let temporal else { return nil }
        return parseTime(temporal["realtimeActual"] as? String)
            ?? parseTime(temporal["realtimeForecast"] as? String)
            ?? parseTime(temporal["realtimeEstimate"] as? String)
    }

    private static func scheduled(_ temporal: [String: Any]?) -> Date? {
        parseTime(temporal?["scheduleAdvertised"] as? String)
    }

    private static func isCancelled(_ locationTemporalData: [String: Any]) -> Bool {
        let arrival = locationTemporalData["arrival"] as? [String: Any]
        let departure = locationTemporalData["departure"] as? [String: Any]
        return (arrival?["isCancelled"] as? Bool ?? false) || (departure?["isCancelled"] as? Bool ?? false)
    }

    /// Maps one entry of NetworkRailServiceLocations into a `Stop`.
    static func stop(from serviceLocation: [String: Any]) throws -> Stop {
        guard let locationJSON = serviceLocation["location"] as? [String: Any],
              let temporal = serviceLocation["temporalData"] as? [String: Any] else {
            throw RttError.malformedResponse("service location missing 'location' or 'temporalData'")
        }
        let arrival = temporal["arrival"] as? [String: Any]
        let departure = temporal["departure"] as? [String: Any]
        return Stop(
            station: station(from: locationJSON),
            scheduledArrival: scheduled(arrival),
            scheduledDeparture: scheduled(departure),
            estimatedArrival: bestRealtime(arrival),
            estimatedDeparture: bestRealtime(departure),
            isCancelled: isCancelled(temporal)
        )
    }

    /// A /gb-nr/location entry only carries this one station's temporal data
    /// plus origin/destination - not the full calling pattern. This builds a
    /// single-stop Journey suitable for a departure-board picker screen;
    /// call `RttProvider.serviceDetails` afterward for the full stop list.
    static func serviceSummary(_ lineUp: [String: Any], atStation: Station) throws -> Service {
        guard let scheduleMetadata = lineUp["scheduleMetadata"] as? [String: Any],
              let uniqueIdentity = scheduleMetadata["uniqueIdentity"] as? String,
              let temporal = lineUp["temporalData"] as? [String: Any] else {
            throw RttError.malformedResponse("location line-up missing scheduleMetadata/temporalData")
        }
        let thisStop = Stop(
            station: atStation,
            scheduledArrival: scheduled(temporal["arrival"] as? [String: Any]),
            scheduledDeparture: scheduled(temporal["departure"] as? [String: Any]),
            estimatedArrival: bestRealtime(temporal["arrival"] as? [String: Any]),
            estimatedDeparture: bestRealtime(temporal["departure"] as? [String: Any]),
            isCancelled: isCancelled(temporal)
        )
        let originPairs = lineUp["origin"] as? [[String: Any]]
        let destinationPairs = lineUp["destination"] as? [[String: Any]]
        let origin = originPairs?.first.flatMap { $0["location"] as? [String: Any] }.map(station) ?? atStation
        let destination = destinationPairs?.first.flatMap { $0["location"] as? [String: Any] }.map(station) ?? atStation

        let operatorName = (scheduleMetadata["operator"] as? [String: Any])?["name"] as? String ?? "Unknown"
        return Service(
            id: uniqueIdentity,
            operatorName: operatorName,
            journey: try Journey(origin: origin, destination: destination, stops: [thisStop])
        )
    }

    /// Maps a full /gb-nr/service response body into a `Service` with its complete stop list.
    static func fullService(_ service: [String: Any]) throws -> Service {
        guard let scheduleMetadata = service["scheduleMetadata"] as? [String: Any],
              let uniqueIdentity = scheduleMetadata["uniqueIdentity"] as? String,
              let locations = service["locations"] as? [[String: Any]] else {
            throw RttError.malformedResponse("service missing scheduleMetadata/locations")
        }
        let stops = try locations.map { try stop(from: $0) }
        guard let firstStop = stops.first, let lastStop = stops.last else {
            throw RttError.malformedResponse("service had no locations")
        }

        let originPairs = service["origin"] as? [[String: Any]]
        let destinationPairs = service["destination"] as? [[String: Any]]
        let origin = originPairs?.first.flatMap { $0["location"] as? [String: Any] }.map(station) ?? firstStop.station
        let destination = destinationPairs?.first.flatMap { $0["location"] as? [String: Any] }.map(station) ?? lastStop.station

        let operatorName = (scheduleMetadata["operator"] as? [String: Any])?["name"] as? String ?? "Unknown"
        return Service(
            id: uniqueIdentity,
            operatorName: operatorName,
            journey: try Journey(origin: origin, destination: destination, stops: stops)
        )
    }
}
