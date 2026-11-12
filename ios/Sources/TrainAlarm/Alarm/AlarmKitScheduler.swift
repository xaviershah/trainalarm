import Foundation
import os

/// Real `AlarmScheduler` on AlarmKit. One alarm at a time: the current alarm's id is persisted so a
/// new instance (or a relaunch) can still cancel it.
final class AlarmKitScheduler: AlarmScheduler {
    /// AlarmKit's behaviour for a fire time of "now" or earlier is unverified, so never pass one.
    static let minimumLead: TimeInterval = 2

    private let client: AlarmKitClient
    private let defaults: UserDefaults
    private let now: () -> Date
    private let idKey = "TrainAlarm.currentAlarmId"
    private let log = Logger(subsystem: "com.trainalarm.app", category: "alarm")

    init(client: AlarmKitClient, defaults: UserDefaults = .standard, now: @escaping () -> Date = Date.init) {
        self.client = client
        self.defaults = defaults
        self.now = now
    }

    private var currentId: UUID? { defaults.string(forKey: idKey).flatMap(UUID.init(uuidString:)) }

    func schedule(fireAt: Date) async throws {
        try await ensureAuthorized()
        let fire = max(fireAt, now().addingTimeInterval(Self.minimumLead))
        let previous = currentId
        let id = UUID()
        // Make before break: if this throws, the previous alarm is still armed.
        try await client.schedule(id: id, fireAt: fire, title: "TrainAlarm")
        if let previous { try? client.cancel(id: previous) }
        defaults.set(id.uuidString, forKey: idKey)
        log.info("scheduled alarm \(id.uuidString) for \(fire.description)")
    }

    func cancel() async {
        guard let id = currentId else { return }
        do { try client.cancel(id: id) } catch { log.error("cancel failed: \(String(describing: error))") }
        defaults.removeObject(forKey: idKey)
    }

    private func ensureAuthorized() async throws {
        switch client.authorization {
        case .authorized: return
        case .denied: throw AlarmSchedulerError.notAuthorized
        case .notDetermined:
            guard try await client.requestAuthorization() == .authorized else {
                throw AlarmSchedulerError.notAuthorized
            }
        }
    }
}
