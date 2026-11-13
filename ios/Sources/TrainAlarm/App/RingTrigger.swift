import Foundation

/// The foreground fallback for "the alarm is ringing": the system alarm may have fired while the app was
/// away, so when the app comes back after the alarm time, show the ringing screen.
enum RingTrigger {
    static func shouldRing(phase: SessionPhase, alarmAt: Date?, now: Date) -> Bool {
        guard phase == .tracking, let alarmAt else { return false }
        return now >= alarmAt
    }
}
