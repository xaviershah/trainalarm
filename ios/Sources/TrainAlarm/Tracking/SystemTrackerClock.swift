import Foundation

/// The real clock for the tracker. `sleep` throws `CancellationError` when the task is cancelled.
struct SystemTrackerClock: TrackerClock {
    func now() -> Date { Date() }

    /// Non-finite or negative is no wait; anything above a day is capped, so the conversion cannot trap.
    static func nanoseconds(for seconds: TimeInterval) -> UInt64 {
        guard seconds.isFinite else { return 0 }
        return UInt64(min(max(0, seconds), 86_400) * 1_000_000_000)
    }

    func sleep(for seconds: TimeInterval) async throws {
        try await Task.sleep(nanoseconds: Self.nanoseconds(for: seconds))
    }
}
