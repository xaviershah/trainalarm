#if DEBUG
import Foundation

/// Scripted trains for demo mode and screenshots. Debug builds only. All times are relative to the clock
/// at the moment the provider was made, and stay fixed after that: a later poll must see the same timetable,
/// or the ETA would drift and every poll would look like a delay.
struct DemoProvider: TrainDataProvider {
    private let epoch: Date
    /// How long ago the first train left, when the scenario wants a train that is already under way.
    private let firstTrainDeparture: Int

    /// `underway` makes the first train (demo-1) depart 100 minutes before the clock's epoch, so the route strip
    /// shows it part-way along (about 40 percent) instead of still at the first stop.
    init(clock: TrackerClock, underway: Bool = false) {
        epoch = clock.now()
        firstTrainDeparture = underway ? -100 : 20
    }

    private struct Call { let crs: String; let name: String; let minutes: Int }
    private struct Train { let id: String; let departsInMinutes: Int; let delay: Int; let cancelled: Bool }

    private static let route = [
        Call(crs: "KGX", name: "London Kings Cross", minutes: 0), Call(crs: "PBO", name: "Peterborough", minutes: 45),
        Call(crs: "NCL", name: "Newcastle", minutes: 190), Call(crs: "BWK", name: "Berwick-upon-Tweed", minutes: 220),
        Call(crs: "EDB", name: "Edinburgh", minutes: 250),
    ]
    private static let trains = [
        Train(id: "demo-1", departsInMinutes: 20, delay: 0, cancelled: false),
        Train(id: "demo-2", departsInMinutes: 50, delay: 4, cancelled: false),
        Train(id: "demo-3", departsInMinutes: 80, delay: 0, cancelled: true),
    ]

    func searchStations(query: String) async throws -> [Station] { [] }

    func departureBoard(station: Station, from: Date, callingAt: Station?) async throws -> [Service] {
        guard station.id == "KGX" else { return [] }
        if let callingAt, !Self.route.dropFirst().contains(where: { $0.crs == callingAt.id }) { return [] }
        return try Self.trains.map { try service($0, summaryOnly: true) }
    }

    func serviceDetails(serviceId: String, date: Date) async throws -> Service {
        guard let train = Self.trains.first(where: { $0.id == serviceId }) else { throw ProviderError.http(404) }
        return try service(train, summaryOnly: false)
    }

    private func service(_ train: Train, summaryOnly: Bool) throws -> Service {
        let now = epoch.timeIntervalSince1970
        let departsIn = train.id == "demo-1" ? firstTrainDeparture : train.departsInMinutes
        let base = Date(timeIntervalSince1970: (now / 60).rounded(.down) * 60 + Double(departsIn) * 60)
        let stops: [Stop] = Self.route.enumerated().map { index, call in
            let scheduled = base.addingTimeInterval(Double(call.minutes) * 60)
            let estimated = train.cancelled ? nil : scheduled.addingTimeInterval(Double(train.delay) * 60)
            let isOrigin = index == 0
            return Stop(
                station: Station(id: call.crs, name: call.name, latitude: 0, longitude: 0),
                scheduledArrival: isOrigin ? nil : scheduled, scheduledDeparture: scheduled,
                estimatedArrival: isOrigin ? nil : estimated, estimatedDeparture: estimated,
                isArrivalCancelled: train.cancelled && !isOrigin, isDepartureCancelled: train.cancelled,
                displayAs: train.cancelled ? .cancelled : .call, hasArrived: false)
        }
        let shown = summaryOnly ? [stops[0]] : stops
        let journey = try Journey(origin: stops[0].station, destination: stops[stops.count - 1].station, stops: shown)
        return Service(id: train.id, operatorName: "LNER", journey: journey)
    }
}

/// Demo mode never arms a real alarm.
struct NoopAlarmScheduler: AlarmScheduler {
    func schedule(fireAt: Date) async throws {}
    func cancel() async {}
}

/// A clock that runs `speed` times faster than the wall clock, so a demo can reach the alarm and the arrival
/// without waiting hours (`-demo-speed 60`). Speed 1 is real time.
struct DemoClock: TrackerClock {
    private let start = Date()
    let speed: Double

    init(speed: Double) { self.speed = max(speed, 1) }

    func now() -> Date { start.addingTimeInterval(Date().timeIntervalSince(start) * speed) }

    func sleep(for seconds: TimeInterval) async throws {
        try await Task.sleep(nanoseconds: UInt64(max(seconds, 0) / speed * 1_000_000_000))
    }
}

/// Demo mode's alarm: nothing is armed with the system, but when the demo clock reaches the fire time the
/// app is told the alarm rang, so a demo run can reach the ringing screen. Replaces any earlier alarm.
final class DemoAlarmScheduler: AlarmScheduler, @unchecked Sendable {
    private let clock: TrackerClock
    private let onRing: @MainActor () -> Void
    private let lock = NSLock()
    private var task: Task<Void, Never>?

    init(clock: TrackerClock, onRing: @escaping @MainActor () -> Void) {
        self.clock = clock
        self.onRing = onRing
    }

    func schedule(fireAt: Date) async throws {
        let delay = max(fireAt.timeIntervalSince(clock.now()), 0)
        let next = Task { [clock, onRing] in
            do { try await clock.sleep(for: delay) } catch { return }
            await onRing()
        }
        lock.lock(); let previous = task; task = next; lock.unlock()
        previous?.cancel()
    }

    func cancel() async {
        lock.lock(); let previous = task; task = nil; lock.unlock()
        previous?.cancel()
    }
}

/// Lets the demo scheduler reach the model, which is made after it.
final class RingBox {
    var handler: (@MainActor () -> Void)?
}

/// Demo mode asks for nothing and everything is granted.
struct DemoPermissions: PermissionsGateway {
    var requiredKinds: [PermissionKind] { [.alarm] }   // listed (granted) so Settings shows a row; location stays unlisted so the demo never starts GPS
    var optionalKinds: [PermissionKind] { [] }
    func status(_ kind: PermissionKind) -> PermissionStatus { .granted }
    func request(_ kind: PermissionKind) async -> PermissionStatus { .granted }
}
#endif
