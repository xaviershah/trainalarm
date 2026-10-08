import Foundation

/// The tracker's only source of time. Production uses the system clock; tests drive a fake.
protocol TrackerClock {
    func now() -> Date
    /// Suspends for `seconds`. Throws `CancellationError` if the calling task is cancelled.
    func sleep(for seconds: TimeInterval) async throws
}

struct LocationFix: Equatable {
    let latitude: Double
    let longitude: Double
    let timestamp: Date
    /// Horizontal accuracy in metres.
    let accuracy: Double
}

protocol LocationSource {
    func latestFix() -> LocationFix?
}
