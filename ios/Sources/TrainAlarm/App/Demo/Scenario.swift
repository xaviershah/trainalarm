#if DEBUG
import Foundation

/// Opens the app in a named state with no live data, for screenshots and demos. Debug builds only.
/// iOS reads `-scenario <name>` from the launch arguments.
enum Scenario: String, CaseIterable {
    case home, board, confirm, tracking
    case trackingLate = "tracking-late"
    case cancelled, dropped
    case feedLost = "feed-lost"
    case alarmNotSet = "alarm-not-set"
    case ringing, ended, settings

    static func from(arguments: [String]) -> Scenario? {
        guard let index = arguments.firstIndex(of: "-scenario"), arguments.indices.contains(index + 1) else { return nil }
        return Scenario(rawValue: arguments[index + 1])
    }

    var opensSettings: Bool { self == .settings }

    /// The screens with a route strip show a train that has already left, so the marker is part-way along.
    var trainIsUnderway: Bool {
        switch self {
        case .tracking, .trackingLate, .cancelled, .dropped, .feedLost, .alarmNotSet: return true
        default: return false
        }
    }

    /// `-demo-speed N` runs the demo clock N times faster (default 1; anything below 1 or not a number is 1).
    static func speed(from arguments: [String]) -> Double {
        guard let index = arguments.firstIndex(of: "-demo-speed"), arguments.indices.contains(index + 1),
              let value = Double(arguments[index + 1]), value >= 1 else { return 1 }
        return value
    }

    /// Drives the model from its empty state to this scenario, using the demo trains from KGX to EDB.
    @MainActor
    func apply(to model: SessionModel, directory: StationDirectory, now: Date) async {
        guard self != .home, self != .settings,
              let from = directory.station(crs: "KGX"), let to = directory.station(crs: "EDB") else { return }
        model.selectFrom(from)
        model.selectTo(to)
        await model.loadBoard()
        if self == .board { return }
        guard case let .loaded(services) = model.board else { return }
        await model.choose(services[0])
        model.setLead(15)
        if self == .confirm { return }
        await model.start()
        // The tracker publishes its own start-up events. Let them arrive first, or they overwrite ours.
        try? await Task.sleep(nanoseconds: 500_000_000)
        let eta = model.display.eta ?? now
        let alarm = model.display.alarmAt ?? now
        switch self {
        case .trackingLate: await model.handle(.etaChanged(oldEta: eta, newEta: eta + 240, fireAt: alarm + 240))
        case .cancelled: await model.handle(.serviceCancelled(at: now))
        case .dropped: await model.handle(.destinationLost(at: now))
        case .feedLost: await model.handle(.feedLost(lastGoodAt: now.addingTimeInterval(-360), kind: .network))
        case .alarmNotSet: await model.handle(.alarmSchedulingFailed(fireAt: alarm, message: "demo"))
        case .ringing: model.alarmRang()
        case .ended: await model.handle(.arrived(at: now))
        default: break
        }
    }
}
#endif
