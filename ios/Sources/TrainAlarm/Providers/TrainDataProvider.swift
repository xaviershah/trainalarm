import Foundation

/// Everything the rest of the app knows about talking to a country's rail
/// data. Tracking, ETA and alarm logic (Stages 2-3) depend ONLY on this
/// protocol and the model types - never on a provider's raw response shape.
/// See docs/spec.md §2 for the architecture rationale.
///
/// A concrete implementation (e.g. an RTT-backed provider) lives alongside
/// this file as a separate type conforming to this protocol.
protocol TrainDataProvider {
    /// Find stations matching a free-text search (e.g. a name the user typed).
    func searchStations(query: String) async throws -> [Station]

    /// Live/scheduled departures from `station` at or after `from`.
    func departureBoard(station: Station, from: Date) async throws -> [Service]

    /// The full stop-by-stop detail for one specific service, including live
    /// times where available. Called both to select a journey and,
    /// repeatedly, to poll for changes while a journey is active (§4).
    func serviceDetails(serviceId: String, date: Date) async throws -> Service
}
