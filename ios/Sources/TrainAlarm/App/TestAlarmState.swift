import Foundation

/// The result of "Test alarm in 10 seconds" on the Settings screen.
enum TestAlarmState: Equatable {
    case idle
    case scheduled(Date)
    case failed
}
