import Foundation

/// Time text for the UI. Times are always shown in London time, whatever the device zone,
/// and countdowns come from absolute instants. See spec section 4.
enum TimeText {
    static let london = TimeZone(identifier: "Europe/London")!

    struct HM: Equatable {
        let hours: String
        let minutes: String
        var plain: String { "\(hours):\(minutes)" }
    }

    private static var calendar: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = london
        return c
    }

    static func clock(_ date: Date) -> HM {
        let c = calendar.dateComponents([.hour, .minute], from: date)
        return HM(hours: String(format: "%02d", c.hour ?? 0), minutes: String(format: "%02d", c.minute ?? 0))
    }

    /// Time left as hours and minutes, rounded down. Negative values show 0:00.
    static func countdown(_ seconds: TimeInterval) -> HM {
        // Int(Double) traps on NaN, infinity and out-of-range values, so guard first.
        // Non-finite or non-positive counts as 0; huge values clamp to 1e12 seconds.
        let safeSeconds = seconds.isFinite ? Int(min(max(seconds, 0), 1e12)) : 0
        let totalMinutes = safeSeconds / 60
        return HM(hours: String(totalMinutes / 60), minutes: String(format: "%02d", totalMinutes % 60))
    }

    /// nil on the same London day as `now`, otherwise a label such as "Thu 2 Jul".
    /// The fixed en_US_POSIX locale keeps month names identical on both platforms.
    static func dateLabel(for date: Date, now: Date) -> String? {
        guard !calendar.isDate(date, inSameDayAs: now) else { return nil }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = london
        formatter.dateFormat = "EEE d MMM"
        return formatter.string(from: date)
    }
}
