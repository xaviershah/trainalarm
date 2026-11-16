import Foundation
import Observation

enum ThemeChoice: String, CaseIterable { case system, dark, light }

/// User settings, kept in UserDefaults. See spec sections 4 and 5.
@Observable
final class SettingsStore {
    /// Every whole minute from 1 to 30.
    static let leadChoices = Array(1...30)

    private(set) var theme: ThemeChoice
    /// nil until the user has chosen a lead time (first run).
    private(set) var defaultLeadMinutes: Int?

    @ObservationIgnored private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        theme = defaults.string(forKey: "theme").flatMap(ThemeChoice.init(rawValue:)) ?? .system
        let stored = defaults.object(forKey: "defaultLeadMinutes") as? Int
        defaultLeadMinutes = stored.flatMap { Self.leadChoices.contains($0) ? $0 : nil }
    }

    func setTheme(_ choice: ThemeChoice) {
        theme = choice
        defaults.set(choice.rawValue, forKey: "theme")
    }

    /// Ignores a value that is not one of `leadChoices`.
    func setDefaultLeadMinutes(_ minutes: Int) {
        guard Self.leadChoices.contains(minutes) else { return }
        defaultLeadMinutes = minutes
        defaults.set(minutes, forKey: "defaultLeadMinutes")
    }
}
