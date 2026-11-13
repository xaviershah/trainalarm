import AlarmKit
import SwiftUI

/// What the system alarm says and how it is tinted (spec section 5: orange is a fill, and the alarm is the
/// one place the system draws our colour for us).
enum AlarmWording {
    static let title = "Nearly there"
    static let stopText = "I'm off"
    static let tintHex: UInt32 = Palette.dark.accent
    static var tint: Color { Color(hex: tintHex) }
}

/// Reads AlarmKit's own state so the app can show the ringing screen when the alarm fires in the foreground.
/// Not yet verified on a device; `isRinging` is the unit-tested part.
enum AlarmRingSignal {
    static func isRinging(_ states: [Alarm.State]) -> Bool { states.contains(.alerting) }

    /// Yields once per update in which an alarm is alerting. Cancelling the consumer stops it.
    static func stream() -> AsyncStream<Void> {
        AsyncStream { continuation in
            let task = Task {
                for await alarms in AlarmManager.shared.alarmUpdates where isRinging(alarms.map(\.state)) {
                    continuation.yield()
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
