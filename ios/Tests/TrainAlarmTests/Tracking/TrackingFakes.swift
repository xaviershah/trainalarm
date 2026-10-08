import XCTest
@testable import TrainAlarm

/// A clock the test drives. `sleep` suspends until `tick`, and throws `CancellationError` if
/// the sleeping task is cancelled.
final class FakeClock: TrackerClock, @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date
    private var waiters: [(id: UUID, continuation: CheckedContinuation<Void, Error>)] = []
    private var sleeps = 0

    init(start: Date) { current = start }

    func now() -> Date {
        lock.lock(); defer { lock.unlock() }
        return current
    }

    var sleepCount: Int {
        lock.lock(); defer { lock.unlock() }
        return sleeps
    }

    func sleep(for seconds: TimeInterval) async throws {
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                lock.lock()
                if Task.isCancelled {
                    lock.unlock()
                    continuation.resume(throwing: CancellationError())
                    return
                }
                sleeps += 1
                waiters.append((id, continuation))
                lock.unlock()
            }
        } onCancel: {
            lock.lock()
            let waiter = waiters.first { $0.id == id }
            waiters.removeAll { $0.id == id }
            lock.unlock()
            waiter?.continuation.resume(throwing: CancellationError())
        }
    }

    /// Advances the clock and wakes whoever is sleeping.
    func tick(advancing seconds: TimeInterval) {
        lock.lock()
        current = current.addingTimeInterval(seconds)
        let ready = waiters
        waiters = []
        lock.unlock()
        for waiter in ready { waiter.continuation.resume() }
    }

    /// Waits until at least `count` sleeps have started (fails the test after 2 seconds).
    func waitForSleepCount(_ count: Int, file: StaticString = #filePath, line: UInt = #line) async {
        for _ in 0..<2000 {
            if sleepCount >= count { return }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("timed out waiting for sleep #\(count)", file: file, line: line)
    }
}

final class FakeLocationSource: LocationSource {
    var fix: LocationFix?
    func latestFix() -> LocationFix? { fix }
}

/// Records every call. `failuresRemaining` makes the next N `schedule` calls throw.
final class FakeAlarmScheduler: AlarmScheduler {
    struct Refused: Error {}
    private(set) var attempts: [Date] = []
    private(set) var scheduled: [Date] = []
    private(set) var cancelCount = 0
    var failuresRemaining = 0

    func schedule(fireAt: Date) async throws {
        attempts.append(fireAt)
        if failuresRemaining > 0 {
            failuresRemaining -= 1
            throw Refused()
        }
        scheduled.append(fireAt)
    }

    func cancel() async { cancelCount += 1 }
}

/// Serves `results` in order, repeating the last one forever.
final class FakeProvider: TrainDataProvider {
    var results: [Result<Service, Error>] = []
    private(set) var calls = 0
    private(set) var requestedIds: [String] = []

    func searchStations(query: String) async throws -> [Station] { throw ProviderError.notImplemented("fake") }
    func departureBoard(station: Station, from: Date) async throws -> [Service] { throw ProviderError.notImplemented("fake") }

    func serviceDetails(serviceId: String, date: Date) async throws -> Service {
        calls += 1
        requestedIds.append(serviceId)
        let result = results.count > 1 ? results.removeFirst() : results[0]
        return try result.get()
    }
}
