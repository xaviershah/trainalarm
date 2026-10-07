# Stage 2 Tracking, ETA and Live-Change Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Stage 2 logic layer on iOS and Android: a pure `JourneyMonitor` (ETA, alarm fire time, 60s threshold, live-change and outage logic) and a thin `JourneyTracker` driver (poll loop, event stream, `stop()`), tested against the 26-row shared scenario table with hand-written fakes.

**Architecture:** `JourneyMonitor` is a pure decision core: `step(state, observation) -> (state, events, alarmAction)`. `JourneyTracker` owns the poll loop, the injected `TrackerClock`, `LocationSource` and `AlarmScheduler`, and applies the monitor's actions. Tracking depends only on `TrainDataProvider` and the model types (never on `RttProvider` or JSON). The `AlarmScheduler` protocol lives in the alarm layer (Stage 3 implements it).

**Tech Stack:** Swift 5 mode / XCTest / Swift concurrency (`actor`, `AsyncStream`) for iOS 26; Kotlin 2.0.20 / JUnit 4 / kotlinx-coroutines 1.9.0 (`Channel`-backed `Flow`) for Android. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-07-tracking-eta-live-change-design.md` (read it first; the plan argues from it). The 26-row scenario table in that spec is the acceptance test.

## Summary (plain English)

| Task | New files (per platform) | What it proves |
|---|---|---|
| 1. Foundations | config, event and state types; `TrackerClock`, `LocationSource`, `AlarmScheduler` protocols; Haversine helper; test fakes | The vocabulary exists and the distance maths gives identical numbers on both platforms |
| 2. Monitor: live changes | `JourneyMonitor` (success path) | Delay threshold, drift, clamp, cancelled vs dropped, restore, nil/vanished ETA: scenarios 1-13, 21, 22 |
| 3. Monitor: outage and arrival | `JourneyMonitor` (failure path, scheduler retry, arrival) | Outage fallback with and without GPS, recovery, flapping, retry, arrival: scenarios 14-20, 23, 24 |
| 4. Tracker driver | `JourneyTracker` | Poll cadence, event stream, `stop()`, cancellation is never an outage: scenarios 25, 26 |
| 5. Docs and verification | `docs/spec.md` §4/§8, `.ai/` docs, README | Docs match the code; both suites green |

## Global Constraints

- Build every change on **both** platforms in the matching layer: `ios/Sources/TrainAlarm/Tracking/` and `android/app/src/main/java/com/trainalarm/app/tracking/`; the `AlarmScheduler` protocol in `ios/.../Alarm/` and `.../alarm/`. Both scenario lists are identical.
- Tracking depends only on `TrainDataProvider` and the model types. It never imports `RttProvider`/`RttMapper`, JSON, or `StationDirectory`. The caller supplies `boardingId`, `destinationId`, an optional destination coordinate and the initial `Service` snapshot.
- The destination stop is the first stop with `destinationId` located after the boarding stop in `stops` (the whole list if the boarding station is not found). Never use `Journey.destination` or `Journey.destinationStop`.
- Names are fixed by the spec: events `etaChanged`, `destinationLost`, `serviceCancelled`, `destinationRestored`, `feedLost`, `etaEstimated`, `feedRecovered`, `alarmSchedulingFailed`, `arrived`; `TrackerConfig` defaults `rescheduleThreshold` 60s, `pollInterval` 45s, `guardMargin` 60s, `minPace` 1 m/s, `maxFixAge` 3 polls, `maxFixAccuracy` 200 m, `arrivalGrace` 10 min; `leadTime` has no default.
- Reschedule when `abs(desired - scheduled) >= rescheduleThreshold` (inclusive). The past-fire clamp is `fire <= now`. Failure kinds: `network`, `http(code)`, `malformed`, `other`.
- Cancellation (`CancellationError` / `CancellationException`) is never turned into a failed poll. No new dependencies (no `kotlinx-coroutines-test`); hand-written fakes only. Swift stays in Swift 5 language mode.
- No UI, no real alarm delivery (Stage 3), no real GPS plumbing, no background execution.
- Android commands: `cd android` then `ANDROID_HOME=/Users/Xavier/Library/Android/sdk ./gradlew test --offline -q` (literal path; the shell refuses `$HOME` in compound commands; `android/local.properties` is absent in worktrees). iOS: `cd ios && xcodegen generate`, then `xcodebuild test -project TrainAlarm.xcodeproj -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17 Pro"` (pass `-project` if a stray `TrainAlarm 2.xcodeproj` exists; run `xcodegen generate` whenever a Swift file is added). Run shell commands as plain separate calls.
- Before every commit run `git diff --cached --stat`, scan the staged diff per `.ai/git.md` (no tokens, `android/local.properties`, `*.xcodeproj`, signing files), and run `git diff --cached | grep -inE "bearer|secret|api[_-]?key"`, which must print nothing. Commit messages: `<Type>: <Description>` plus the `Co-Authored-By` trailer; say the platform when a commit touches only one.
- Use Sonnet for implementers and reviewers, never Opus unless the user says so.
- When `superpowers:subagent-driven-development` finishes and invokes `superpowers:finishing-a-development-branch`, let it run its verification steps but **do not present the following options menu**:
  ```
  1. Merge back to <base-branch> locally
  2. Push and create a Pull Request
  3. Keep the branch as-is (I'll handle it later)
  ```
  Instead, proceed to Step 12 of `gl-starting-a-feature`. Step 12 runs in full, then Step 13 handles the finishing sequence (delete spec+plan → squash implementation commits → push → exit worktree → open the PR).
- Do not push during implementation. Every task commits locally only, and every platform is verified locally (build and tests). The first push of implementation work happens in Step 13, after the squash, so CI runs once on the final commits. If a platform genuinely cannot be verified locally, stop and tell the user rather than pushing the PR branch to find out.

## Review Focus

Inputs the spec implies but a plain reading of the task list would not exercise; each has a pinning test in the task named.

1. Destination appears only **before** the boarding stop in the sequence: treated as absent, so `destinationLost` (Task 2).
2. `destinationId == boardingId`: no stop after boarding matches, so `destinationLost` at start, never a crash (Task 2).
3. A provider that throws something that is not a `ProviderError`: failure kind `other`, the loop keeps polling (Task 4).
4. GPS fixes that are stale or less accurate than `maxFixAccuracy`: ignored, so the fallback holds the last live ETA (Task 3).
5. Fire time exactly equal to `now`: counts as past (`<=`), schedules immediately, once (Task 2).
6. A poll where the destination stop has neither an estimate nor a schedule (`bestArrival` nil) on the very first observation: no crash and no schedule (Task 2).

## File map

| | iOS (`ios/`) | Android (`android/app/src/`) |
|---|---|---|
| Create (Tracking) | `Sources/TrainAlarm/Tracking/`: `TrackerConfig.swift`, `TrackerEvent.swift`, `TrackerInterfaces.swift` (`TrackerClock`, `LocationFix`, `LocationSource`), `Geo.swift`, `MonitorState.swift`, `JourneyMonitor.swift`, `JourneyTracker.swift` | `main/java/com/trainalarm/app/tracking/`: `TrackerConfig.kt`, `TrackerEvent.kt`, `TrackerInterfaces.kt`, `Geo.kt`, `MonitorState.kt`, `JourneyMonitor.kt`, `JourneyTracker.kt` |
| Create (Alarm) | `Sources/TrainAlarm/Alarm/AlarmScheduler.swift` | `main/java/com/trainalarm/app/alarm/AlarmScheduler.kt` |
| Create (tests) | `Tests/TrainAlarmTests/Tracking/`: `GeoTests.swift`, `TrackerConfigTests.swift`, `TrackingFakes.swift`, `TrackingFakesTests.swift`, `ServiceBuilder.swift`, `ServiceBuilderTests.swift`, `MonitorHarness.swift`, `JourneyMonitorTests.swift`, `JourneyMonitorOutageTests.swift`, `JourneyTrackerTests.swift` | `test/java/com/trainalarm/app/tracking/`: `GeoTest.kt`, `TrackerConfigTest.kt`, `TrackingFakes.kt`, `TrackingFakesTest.kt`, `ServiceBuilder.kt`, `ServiceBuilderTest.kt`, `MonitorHarness.kt`, `JourneyMonitorTest.kt`, `JourneyMonitorOutageTest.kt`, `JourneyTrackerTest.kt` |
| Modify (docs) | `docs/spec.md` §4 and §8, `.ai/architecture.md`, `.ai/testing-guide.md`, `README.md` | |

---

## Task 1: Foundations: types, protocols, distance maths, test fakes (both platforms)

**Spec:** Units, Events, Tracker inputs, Monitor rules (the GPS pace formula). Nothing here makes a decision yet; Tasks 2-4 build on it.

**Files:**
- Create iOS (production): `Sources/TrainAlarm/Tracking/TrackerConfig.swift`, `TrackerEvent.swift`, `TrackerInterfaces.swift`, `Geo.swift`, `MonitorState.swift`; `Sources/TrainAlarm/Alarm/AlarmScheduler.swift`
- Create iOS (tests): `Tests/TrainAlarmTests/Tracking/TrackingFakes.swift`, `ServiceBuilder.swift`, `GeoTests.swift`, `TrackerConfigTests.swift`, `ServiceBuilderTests.swift`, `TrackingFakesTests.swift`
- Create Android (production): `.../tracking/TrackerConfig.kt`, `TrackerEvent.kt`, `TrackerInterfaces.kt`, `Geo.kt`, `MonitorState.kt`; `.../alarm/AlarmScheduler.kt`
- Create Android (tests): `.../tracking/TrackingFakes.kt`, `ServiceBuilder.kt`, `GeoTest.kt`, `TrackerConfigTest.kt`, `ServiceBuilderTest.kt`, `TrackingFakesTest.kt` (under `android/app/src/test/java/com/trainalarm/app/tracking/`)

**Interfaces (Produces; Tasks 2-4 consume these exact names). Swift names; Kotlin uses the same names with Kotlin idioms (`Instant` for `Date`, `Double` seconds for `TimeInterval`, nested sealed classes / `data object`s, UPPER_SNAKE enum entries):**
- `TrackerConfig(leadTime:)` with defaults and `maxFixAge` (computed: `maxFixAgePolls * pollInterval`).
- `FailureKind` (`network`, `http(Int)`, `malformed`, `other`), `EtaSource` (`lastKnownTrajectory`, `gpsPace`), and `TrackerEvent` with the nine cases listed in the spec (payloads below).
- `protocol TrackerClock { func now() -> Date; func sleep(for seconds: TimeInterval) async throws }`; `struct LocationFix { latitude, longitude, timestamp, accuracy }`; `protocol LocationSource { func latestFix() -> LocationFix? }`; `protocol AlarmScheduler { func schedule(fireAt: Date) async throws; func cancel() async }`.
- `Coordinate`, `Geo.distanceMetres(fromLatitude:longitude:toLatitude:longitude:)` (Haversine, R = 6,371,000 m).
- `Standing` (`calling`, `lost`, `cancelled`), `AlarmAction` (`none`, `schedule(Date)`, `cancel`), `PollResult` (`service(Service)`, `failure(FailureKind)`), `Observation(result:now:fix:)`, `MonitorState` (all fields default), `MonitorStep(state:events:action:)`, `TrackerPlan(boardingId:destinationId:destinationCoordinate:config:)`.
- Test support: `FakeClock` (`now()`, `tick(advancing:)`, `sleepCount`, `waitForSleepCount(_:)`), `FakeLocationSource` (`fix`), `FakeAlarmScheduler` (`attempts`, `scheduled`, `cancelCount`, `failuresRemaining`), `FakeProvider` (`results`, `calls`, `requestedIds`), `ServiceBuilder` (`time`, `station`, `stop`, `service`, `happyPath`, `loadFixture`, `replacing`).

- [ ] **Step 1: Write the iOS test support and failing tests**

Create `ios/Tests/TrainAlarmTests/Tracking/ServiceBuilder.swift`:
```swift
import XCTest
@testable import TrainAlarm

/// Builds `Service` values for tracker tests. All times are on 2026-09-13 UTC, like the
/// fixtures. Provider stations carry latitude/longitude 0, exactly as `RttMapper` produces them.
enum ServiceBuilder {
    private final class BundleToken {}

    static let happyPathId = "gb-nr:L01525:2026-09-13"

    static func time(_ hhmmss: String) -> Date {
        ISO8601DateFormatter().date(from: "2026-09-13T\(hhmmss)Z")!
    }

    static func station(_ id: String) -> Station {
        Station(id: id, name: id, latitude: 0, longitude: 0)
    }

    static func stop(
        _ id: String,
        scheduledArrival: String? = nil,
        estimatedArrival: String? = nil,
        isArrivalCancelled: Bool = false,
        isDepartureCancelled: Bool = false,
        displayAs: StopDisplay? = nil,
        hasArrived: Bool = false
    ) -> Stop {
        Stop(
            station: station(id),
            scheduledArrival: scheduledArrival.map(time),
            scheduledDeparture: nil,
            estimatedArrival: estimatedArrival.map(time),
            estimatedDeparture: nil,
            isArrivalCancelled: isArrivalCancelled,
            isDepartureCancelled: isDepartureCancelled,
            displayAs: displayAs,
            hasArrived: hasArrived
        )
    }

    static func service(id: String = happyPathId, stops: [Stop]) -> Service {
        let journey = try! Journey(origin: stops.first!.station, destination: stops.last!.station, stops: stops)
        return Service(id: id, operatorName: "South Western Railway", journey: journey)
    }

    /// WAT -> WOK -> BSK, mirroring `gb-nr-service.json`: BSK is scheduled 08:47:00.
    static func happyPath(destinationEstimate: String? = "08:52:00", id: String = happyPathId) -> Service {
        service(id: id, stops: [
            stop("WAT"),
            stop("WOK", scheduledArrival: "08:23:00", estimatedArrival: "08:27:00"),
            stop("BSK", scheduledArrival: "08:47:00", estimatedArrival: destinationEstimate),
        ])
    }

    /// Loads a shared fixture (without the `.json` extension) through the real mapper.
    static func loadFixture(_ name: String) throws -> Service {
        guard let url = Bundle(for: BundleToken.self).url(forResource: name, withExtension: "json", subdirectory: "Fixtures") else {
            throw ProviderError.malformed("fixture \(name).json not found in test bundle")
        }
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        return try RttMapper.fullService(json["service"] as! [String: Any])
    }

    /// A copy of `service` with the stop at `index` replaced.
    static func replacing(_ service: Service, stopAt index: Int, with stop: Stop) -> Service {
        var stops = service.journey.stops
        stops[index] = stop
        let journey = try! Journey(origin: service.journey.origin, destination: service.journey.destination, stops: stops)
        return Service(id: service.id, operatorName: service.operatorName, journey: journey)
    }
}
```

Create `ios/Tests/TrainAlarmTests/Tracking/TrackingFakes.swift`:
```swift
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
```

Create `ios/Tests/TrainAlarmTests/Tracking/GeoTests.swift`. The vectors were computed independently with Python's `math` (R = 6,371,000 m) and are the **same numbers** the Android test uses:
```swift
import XCTest
@testable import TrainAlarm

final class GeoTests: XCTestCase {
    func testHaversineDistanceVectors() {
        // lat1, lon1, lat2, lon2, expected metres
        let vectors: [(Double, Double, Double, Double, Double)] = [
            (0, 0, 0, 1, 111194.927),
            (51.5031, -0.1132, 51.5031, -0.1132, 0.0),
            (51.5031, -0.1132, 51.2679, -1.0877, 72505.005),
            (90, 0, 0, 0, 10007543.398),
            (51.5, 0, 51.6, 0, 11119.493),
            (0, 0, 0, 180, 20015086.796),
        ]
        for (lat1, lon1, lat2, lon2, expected) in vectors {
            let actual = Geo.distanceMetres(fromLatitude: lat1, longitude: lon1, toLatitude: lat2, longitude: lon2)
            XCTAssertEqual(actual, expected, accuracy: 0.01, "(\(lat1),\(lon1)) to (\(lat2),\(lon2))")
        }
    }
}
```

Create `ios/Tests/TrainAlarmTests/Tracking/TrackerConfigTests.swift`:
```swift
import XCTest
@testable import TrainAlarm

final class TrackerConfigTests: XCTestCase {
    func testDefaultsMatchTheSpec() {
        let config = TrackerConfig(leadTime: 600)
        XCTAssertEqual(config.leadTime, 600)
        XCTAssertEqual(config.rescheduleThreshold, 60)
        XCTAssertEqual(config.pollInterval, 45)
        XCTAssertEqual(config.guardMargin, 60)
        XCTAssertEqual(config.minPace, 1.0)
        XCTAssertEqual(config.maxFixAgePolls, 3)
        XCTAssertEqual(config.maxFixAge, 135)
        XCTAssertEqual(config.maxFixAccuracy, 200)
        XCTAssertEqual(config.arrivalGrace, 600)
    }

    func testMaxFixAgeFollowsThePollInterval() {
        var config = TrackerConfig(leadTime: 600)
        config.pollInterval = 30
        XCTAssertEqual(config.maxFixAge, 90)
    }
}
```

Create `ios/Tests/TrainAlarmTests/Tracking/ServiceBuilderTests.swift`:
```swift
import XCTest
@testable import TrainAlarm

final class ServiceBuilderTests: XCTestCase {
    func testHappyPathMirrorsTheSharedFixture() throws {
        let built = ServiceBuilder.happyPath()
        let loaded = try ServiceBuilder.loadFixture("gb-nr-service")

        XCTAssertEqual(built.id, loaded.id)
        XCTAssertEqual(built.journey.stops.map(\.station.id), loaded.journey.stops.map(\.station.id))
        XCTAssertEqual(built.journey.stops.map(\.scheduledArrival), loaded.journey.stops.map(\.scheduledArrival))
        XCTAssertEqual(built.journey.stops.map(\.estimatedArrival), loaded.journey.stops.map(\.estimatedArrival))
    }

    func testReplacingSwapsOneStopOnly() {
        let original = ServiceBuilder.happyPath()
        let changed = ServiceBuilder.replacing(original, stopAt: 2, with: ServiceBuilder.stop("BSK", scheduledArrival: "08:47:00", estimatedArrival: "09:00:00"))

        XCTAssertEqual(changed.journey.stops[0], original.journey.stops[0])
        XCTAssertEqual(changed.journey.stops[2].estimatedArrival, ServiceBuilder.time("09:00:00"))
        XCTAssertEqual(changed.id, original.id)
    }
}
```

Create `ios/Tests/TrainAlarmTests/Tracking/TrackingFakesTests.swift` (the driver tests in Task 4 rely on these fakes, so they are tested first):
```swift
import XCTest
@testable import TrainAlarm

final class TrackingFakesTests: XCTestCase {
    func testFakeClockSleepResumesOnTickAndTimeAdvances() async {
        let clock = FakeClock(start: ServiceBuilder.time("08:00:00"))
        let sleeper = Task { try await clock.sleep(for: 45) }

        await clock.waitForSleepCount(1)
        clock.tick(advancing: 45)
        let result = await sleeper.result

        XCTAssertNoThrow(try result.get())
        XCTAssertEqual(clock.now(), ServiceBuilder.time("08:00:45"))
    }

    func testFakeClockSleepThrowsCancellationErrorWhenCancelled() async {
        let clock = FakeClock(start: ServiceBuilder.time("08:00:00"))
        let sleeper = Task { try await clock.sleep(for: 45) }

        await clock.waitForSleepCount(1)
        sleeper.cancel()
        let result = await sleeper.result

        XCTAssertThrowsError(try result.get()) { XCTAssertTrue($0 is CancellationError) }
    }

    func testFakeAlarmSchedulerRecordsFailedAttemptsSeparately() async throws {
        let scheduler = FakeAlarmScheduler()
        scheduler.failuresRemaining = 1
        let fire = ServiceBuilder.time("08:37:00")

        do { try await scheduler.schedule(fireAt: fire); XCTFail("expected a refusal") } catch {}
        try await scheduler.schedule(fireAt: fire)

        XCTAssertEqual(scheduler.attempts, [fire, fire])
        XCTAssertEqual(scheduler.scheduled, [fire])
    }

    func testFakeProviderServesInOrderThenRepeatsTheLast() async throws {
        let provider = FakeProvider()
        let first = ServiceBuilder.happyPath(destinationEstimate: "08:52:00")
        let second = ServiceBuilder.happyPath(destinationEstimate: "08:55:00")
        provider.results = [.success(first), .success(second)]

        let a = try await provider.serviceDetails(serviceId: "x", date: Date())
        let b = try await provider.serviceDetails(serviceId: "x", date: Date())
        let c = try await provider.serviceDetails(serviceId: "x", date: Date())

        XCTAssertEqual([a, b, c], [first, second, second])
        XCTAssertEqual(provider.calls, 3)
    }
}
```

- [ ] **Step 2: Write the Android test support and failing tests**

All files go in `android/app/src/test/java/com/trainalarm/app/tracking/` with `package com.trainalarm.app.tracking`.

`ServiceBuilder.kt`:
```kotlin
package com.trainalarm.app.tracking

import com.trainalarm.app.model.Journey
import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.model.Stop
import com.trainalarm.app.model.StopDisplay
import com.trainalarm.app.provider.RttMapper
import org.json.JSONObject
import java.time.Instant

/**
 * Builds [Service] values for tracker tests. All times are on 2026-09-13 UTC, like the
 * fixtures. Provider stations carry latitude/longitude 0, exactly as [RttMapper] produces them.
 */
object ServiceBuilder {
    const val HAPPY_PATH_ID = "gb-nr:L01525:2026-09-13"

    fun time(hhmmss: String): Instant = Instant.parse("2026-09-13T${hhmmss}Z")

    fun station(id: String) = Station(id, id, 0.0, 0.0)

    fun stop(
        id: String,
        scheduledArrival: String? = null,
        estimatedArrival: String? = null,
        isArrivalCancelled: Boolean = false,
        isDepartureCancelled: Boolean = false,
        displayAs: StopDisplay? = null,
        hasArrived: Boolean = false
    ) = Stop(
        station = station(id),
        scheduledArrival = scheduledArrival?.let(::time),
        scheduledDeparture = null,
        estimatedArrival = estimatedArrival?.let(::time),
        estimatedDeparture = null,
        isArrivalCancelled = isArrivalCancelled,
        isDepartureCancelled = isDepartureCancelled,
        displayAs = displayAs,
        hasArrived = hasArrived
    )

    fun service(stops: List<Stop>, id: String = HAPPY_PATH_ID) = Service(
        id = id,
        operatorName = "South Western Railway",
        journey = Journey(stops.first().station, stops.last().station, stops)
    )

    /** WAT -> WOK -> BSK, mirroring `gb-nr-service.json`: BSK is scheduled 08:47:00. */
    fun happyPath(destinationEstimate: String? = "08:52:00", id: String = HAPPY_PATH_ID) = service(
        listOf(
            stop("WAT"),
            stop("WOK", scheduledArrival = "08:23:00", estimatedArrival = "08:27:00"),
            stop("BSK", scheduledArrival = "08:47:00", estimatedArrival = destinationEstimate)
        ),
        id
    )

    /** Loads a shared fixture (without the `.json` extension) through the real mapper. */
    fun loadFixture(name: String): Service {
        val stream = ServiceBuilder::class.java.getResourceAsStream("/fixtures/$name.json")
            ?: error("Fixture $name.json not found on test classpath")
        return RttMapper.fullService(JSONObject(stream.bufferedReader().readText()).getJSONObject("service"))
    }

    /** A copy of [service] with the stop at [index] replaced. */
    fun replacing(service: Service, index: Int, stop: Stop): Service {
        val stops = service.journey.stops.toMutableList()
        stops[index] = stop
        return service.copy(journey = Journey(service.journey.origin, service.journey.destination, stops))
    }
}
```

`TrackingFakes.kt`:
```kotlin
package com.trainalarm.app.tracking

import com.trainalarm.app.alarm.AlarmScheduler
import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.provider.ProviderError
import com.trainalarm.app.provider.TrainDataProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.time.Instant
import java.time.LocalDate

/** A clock the test drives. `sleep` suspends until [tick]; cancelling the sleeper throws CancellationException. */
class FakeClock(start: Instant) : TrackerClock {
    private var current = start
    private var gate = CompletableDeferred<Unit>()
    var sleepCount = 0
        private set

    override fun now(): Instant = current

    override suspend fun sleep(seconds: Double) {
        sleepCount += 1
        gate.await()
    }

    /** Advances the clock and wakes whoever is sleeping. */
    fun tick(advanceSeconds: Double) {
        current = current.plusMillis((advanceSeconds * 1000).toLong())
        val released = gate
        gate = CompletableDeferred()
        released.complete(Unit)
    }

    /** Yields until at least [count] sleeps have started (fails after 2 seconds). */
    suspend fun awaitSleepCount(count: Int) {
        withTimeout(2_000) { while (sleepCount < count) yield() }
    }
}

class FakeLocationSource : LocationSource {
    var fix: LocationFix? = null
    override fun latestFix(): LocationFix? = fix
}

/** Records every call. [failuresRemaining] makes the next N `schedule` calls throw. */
class FakeAlarmScheduler : AlarmScheduler {
    class Refused : Exception("refused")

    val attempts = mutableListOf<Instant>()
    val scheduled = mutableListOf<Instant>()
    var cancelCount = 0
    var failuresRemaining = 0

    override suspend fun schedule(fireAt: Instant) {
        attempts += fireAt
        if (failuresRemaining > 0) {
            failuresRemaining -= 1
            throw Refused()
        }
        scheduled += fireAt
    }

    override suspend fun cancel() {
        cancelCount += 1
    }
}

/** Serves [results] in order, repeating the last one forever. */
class FakeProvider : TrainDataProvider {
    val results = mutableListOf<Result<Service>>()
    var calls = 0
    val requestedIds = mutableListOf<String>()

    override suspend fun searchStations(query: String): List<Station> =
        throw ProviderError.NotImplemented("fake")

    override suspend fun departureBoard(station: Station, from: Instant): List<Service> =
        throw ProviderError.NotImplemented("fake")

    override suspend fun serviceDetails(serviceId: String, date: LocalDate): Service {
        calls += 1
        requestedIds += serviceId
        val result = if (results.size > 1) results.removeAt(0) else results.first()
        return result.getOrThrow()
    }
}
```

`GeoTest.kt` (the same vectors as the iOS test):
```kotlin
package com.trainalarm.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {
    @Test
    fun haversineDistanceVectors() {
        // lat1, lon1, lat2, lon2, expected metres
        val vectors = listOf(
            listOf(0.0, 0.0, 0.0, 1.0, 111194.927),
            listOf(51.5031, -0.1132, 51.5031, -0.1132, 0.0),
            listOf(51.5031, -0.1132, 51.2679, -1.0877, 72505.005),
            listOf(90.0, 0.0, 0.0, 0.0, 10007543.398),
            listOf(51.5, 0.0, 51.6, 0.0, 11119.493),
            listOf(0.0, 0.0, 0.0, 180.0, 20015086.796)
        )
        for ((lat1, lon1, lat2, lon2, expected) in vectors) {
            assertEquals("($lat1,$lon1) to ($lat2,$lon2)", expected, Geo.distanceMetres(lat1, lon1, lat2, lon2), 0.01)
        }
    }
}
```

`TrackerConfigTest.kt`:
```kotlin
package com.trainalarm.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerConfigTest {
    @Test
    fun defaultsMatchTheSpec() {
        val config = TrackerConfig(leadTime = 600.0)
        assertEquals(600.0, config.leadTime, 0.0)
        assertEquals(60.0, config.rescheduleThreshold, 0.0)
        assertEquals(45.0, config.pollInterval, 0.0)
        assertEquals(60.0, config.guardMargin, 0.0)
        assertEquals(1.0, config.minPace, 0.0)
        assertEquals(3, config.maxFixAgePolls)
        assertEquals(135.0, config.maxFixAge, 0.0)
        assertEquals(200.0, config.maxFixAccuracy, 0.0)
        assertEquals(600.0, config.arrivalGrace, 0.0)
    }

    @Test
    fun maxFixAgeFollowsThePollInterval() {
        assertEquals(90.0, TrackerConfig(leadTime = 600.0, pollInterval = 30.0).maxFixAge, 0.0)
    }
}
```

`ServiceBuilderTest.kt`:
```kotlin
package com.trainalarm.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceBuilderTest {
    @Test
    fun happyPathMirrorsTheSharedFixture() {
        val built = ServiceBuilder.happyPath()
        val loaded = ServiceBuilder.loadFixture("gb-nr-service")

        assertEquals(loaded.id, built.id)
        assertEquals(loaded.journey.stops.map { it.station.id }, built.journey.stops.map { it.station.id })
        assertEquals(loaded.journey.stops.map { it.scheduledArrival }, built.journey.stops.map { it.scheduledArrival })
        assertEquals(loaded.journey.stops.map { it.estimatedArrival }, built.journey.stops.map { it.estimatedArrival })
    }

    @Test
    fun replacingSwapsOneStopOnly() {
        val original = ServiceBuilder.happyPath()
        val changed = ServiceBuilder.replacing(
            original, 2, ServiceBuilder.stop("BSK", scheduledArrival = "08:47:00", estimatedArrival = "09:00:00")
        )

        assertEquals(original.journey.stops[0], changed.journey.stops[0])
        assertEquals(ServiceBuilder.time("09:00:00"), changed.journey.stops[2].estimatedArrival)
        assertEquals(original.id, changed.id)
    }
}
```

`TrackingFakesTest.kt`:
```kotlin
package com.trainalarm.app.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingFakesTest {
    @Test
    fun fakeClockSleepResumesOnTickAndTimeAdvances() = runBlocking<Unit> {
        val clock = FakeClock(ServiceBuilder.time("08:00:00"))
        val sleeper = launch { clock.sleep(45.0) }

        clock.awaitSleepCount(1)
        clock.tick(45.0)
        sleeper.join()

        assertEquals(ServiceBuilder.time("08:00:45"), clock.now())
        assertTrue(sleeper.isCompleted && !sleeper.isCancelled)
    }

    @Test
    fun fakeClockSleepIsCancelledWhenTheSleeperIsCancelled() = runBlocking<Unit> {
        val clock = FakeClock(ServiceBuilder.time("08:00:00"))
        var seen: Throwable? = null
        val sleeper = launch {
            try {
                clock.sleep(45.0)
            } catch (e: CancellationException) {
                seen = e
                throw e
            }
        }

        clock.awaitSleepCount(1)
        sleeper.cancelAndJoin()

        assertTrue(seen is CancellationException)
    }

    @Test
    fun fakeAlarmSchedulerRecordsFailedAttemptsSeparately() = runBlocking<Unit> {
        val scheduler = FakeAlarmScheduler()
        scheduler.failuresRemaining = 1
        val fire = ServiceBuilder.time("08:37:00")

        try {
            scheduler.schedule(fire)
            throw AssertionError("expected a refusal")
        } catch (e: FakeAlarmScheduler.Refused) {
        }
        scheduler.schedule(fire)

        assertEquals(listOf(fire, fire), scheduler.attempts)
        assertEquals(listOf(fire), scheduler.scheduled)
    }

    @Test
    fun fakeProviderServesInOrderThenRepeatsTheLast() = runBlocking<Unit> {
        val provider = FakeProvider()
        val first = ServiceBuilder.happyPath(destinationEstimate = "08:52:00")
        val second = ServiceBuilder.happyPath(destinationEstimate = "08:55:00")
        provider.results += Result.success(first)
        provider.results += Result.success(second)

        val seen = List(3) { provider.serviceDetails("x", java.time.LocalDate.of(2026, 9, 13)) }

        assertEquals(listOf(first, second, second), seen)
        assertEquals(3, provider.calls)
    }
}
```

- [ ] **Step 3: Run both suites to verify they fail**

```bash
cd ios && xcodegen generate && xcodebuild test -project TrainAlarm.xcodeproj -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17 Pro" 2>&1 | grep -E "error:|TEST (FAILED|SUCCEEDED)" | head -20
cd ../android && ANDROID_HOME=/Users/Xavier/Library/Android/sdk ./gradlew test --offline -q 2>&1 | grep -E "^e: " | head -20
```
Expected: both FAIL to compile, e.g. iOS `cannot find type 'TrackerClock' in scope` / `cannot find 'Geo' in scope`; Android `Unresolved reference: TrackerClock` / `Unresolved reference: Geo`.

- [ ] **Step 4: Implement the iOS foundations**

Create these files under `ios/Sources/TrainAlarm/Tracking/`.

`TrackerConfig.swift`:
```swift
import Foundation

/// Tuning for the journey tracker. Provider times are minute-granular, so a one-minute
/// shift is exactly 60s: the reschedule threshold is inclusive (>=).
struct TrackerConfig: Equatable {
    /// How long before the destination ETA the alarm should fire (the user's setting; no default).
    var leadTime: TimeInterval
    var rescheduleThreshold: TimeInterval = 60
    var pollInterval: TimeInterval = 45
    /// A GPS-based fallback ETA is never earlier than the last live ETA minus this.
    var guardMargin: TimeInterval = 60
    /// Minimum approach speed, in metres per second, for the GPS fallback to be trusted.
    var minPace: Double = 1.0
    var maxFixAgePolls: Int = 3
    /// Fixes less accurate than this many metres are ignored.
    var maxFixAccuracy: Double = 200
    /// How long after the ETA the tracker waits for an arrival before ending anyway.
    var arrivalGrace: TimeInterval = 600

    /// Fixes older than this are ignored.
    var maxFixAge: TimeInterval { Double(maxFixAgePolls) * pollInterval }
}
```

`TrackerEvent.swift`:
```swift
import Foundation

/// Why a poll failed, derived from `ProviderError` by the driver.
enum FailureKind: Equatable {
    case network
    case http(Int)
    case malformed
    case other
}

enum EtaSource: Equatable {
    case lastKnownTrajectory
    case gpsPace
}

/// What the tracker tells the rest of the app. See the spec's Events table.
enum TrackerEvent: Equatable {
    case etaChanged(oldEta: Date?, newEta: Date, fireAt: Date)
    case destinationLost(at: Date)
    case serviceCancelled(at: Date)
    case destinationRestored(newEta: Date?)
    case feedLost(lastGoodAt: Date?, kind: FailureKind)
    case etaEstimated(eta: Date, source: EtaSource, staleness: TimeInterval, rescheduled: Bool)
    case feedRecovered(outage: TimeInterval, freshEta: Date?, deltaFromFallback: TimeInterval?)
    case alarmSchedulingFailed(fireAt: Date, message: String)
    case arrived(at: Date)
}
```

`TrackerInterfaces.swift`:
```swift
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
```

`Geo.swift`:
```swift
import Foundation

struct Coordinate: Equatable {
    let latitude: Double
    let longitude: Double
}

enum Geo {
    static let earthRadiusMetres = 6_371_000.0

    /// Great-circle (Haversine) distance in metres.
    static func distanceMetres(fromLatitude lat1: Double, longitude lon1: Double, toLatitude lat2: Double, longitude lon2: Double) -> Double {
        let phi1 = lat1 * .pi / 180
        let phi2 = lat2 * .pi / 180
        let deltaPhi = (lat2 - lat1) * .pi / 180
        let deltaLambda = (lon2 - lon1) * .pi / 180
        let a = sin(deltaPhi / 2) * sin(deltaPhi / 2) + cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)
        return 2 * earthRadiusMetres * asin(min(1, sqrt(a)))
    }
}
```

`MonitorState.swift`:
```swift
import Foundation

/// Where the destination stands for the train.
enum Standing: Equatable {
    case calling
    case lost
    case cancelled
}

/// What the driver must do to the alarm after a monitor step.
enum AlarmAction: Equatable {
    case none
    case schedule(Date)
    case cancel
}

enum PollResult: Equatable {
    case service(Service)
    case failure(FailureKind)
}

/// One tick's input to the monitor.
struct Observation: Equatable {
    let result: PollResult
    let now: Date
    /// The latest location fix sampled this tick, if any (sampled whether or not the feed is up).
    let fix: LocationFix?
}

/// The fixed inputs of a tracked journey.
struct TrackerPlan: Equatable {
    let boardingId: String
    let destinationId: String
    /// Resolved by the caller from the station directory; nil disables the GPS fallback.
    let destinationCoordinate: Coordinate?
    let config: TrackerConfig
}

/// Everything the monitor remembers between ticks.
struct MonitorState: Equatable {
    var finished = false
    var standing: Standing = .calling
    var startedAt: Date? = nil
    var lastEta: Date? = nil
    var lastLiveEta: Date? = nil
    var sawLive = false
    var lastGoodAt: Date? = nil
    /// The fire time the scheduler has confirmed.
    var scheduledFire: Date? = nil
    /// A `schedule` call failed; the next tick must issue it again without a new `etaChanged`.
    var retryPending = false
    var alarmDue = false
    var feedDown = false
    var outageStartedAt: Date? = nil
    var lastFallbackEta: Date? = nil
    var fixes: [LocationFix] = []
}

struct MonitorStep: Equatable {
    var state: MonitorState
    var events: [TrackerEvent]
    var action: AlarmAction
}
```

Create `ios/Sources/TrainAlarm/Alarm/AlarmScheduler.swift` (the protocol lives in the alarm layer; Stage 3 implements it):
```swift
import Foundation

/// Sets the one alarm for a journey. Stage 3 provides the real implementation (AlarmKit);
/// until then tests use a fake.
protocol AlarmScheduler {
    /// Replaces any scheduled alarm with one at `fireAt` (idempotent). Throws if the OS
    /// refuses, for example when authorization is denied.
    func schedule(fireAt: Date) async throws
    func cancel() async
}
```

- [ ] **Step 5: Implement the Android foundations**

Create these files under `android/app/src/main/java/com/trainalarm/app/tracking/` (`package com.trainalarm.app.tracking`), plus the alarm file.

`TrackerConfig.kt` (durations are `Double` seconds, matching Swift's `TimeInterval`):
```kotlin
package com.trainalarm.app.tracking

/**
 * Tuning for the journey tracker. Durations are seconds. Provider times are
 * minute-granular, so a one-minute shift is exactly 60s: the reschedule threshold is
 * inclusive (>=).
 */
data class TrackerConfig(
    /** How long before the destination ETA the alarm should fire (the user's setting; no default). */
    val leadTime: Double,
    val rescheduleThreshold: Double = 60.0,
    val pollInterval: Double = 45.0,
    /** A GPS-based fallback ETA is never earlier than the last live ETA minus this. */
    val guardMargin: Double = 60.0,
    /** Minimum approach speed, in metres per second, for the GPS fallback to be trusted. */
    val minPace: Double = 1.0,
    val maxFixAgePolls: Int = 3,
    /** Fixes less accurate than this many metres are ignored. */
    val maxFixAccuracy: Double = 200.0,
    /** How long after the ETA the tracker waits for an arrival before ending anyway. */
    val arrivalGrace: Double = 600.0
) {
    /** Fixes older than this are ignored. */
    val maxFixAge: Double get() = maxFixAgePolls * pollInterval
}
```

`TrackerEvent.kt`:
```kotlin
package com.trainalarm.app.tracking

import java.time.Instant

/** Why a poll failed, derived from `ProviderError` by the driver. */
sealed class FailureKind {
    data object Network : FailureKind()
    data class Http(val code: Int) : FailureKind()
    data object Malformed : FailureKind()
    data object Other : FailureKind()
}

enum class EtaSource { LAST_KNOWN_TRAJECTORY, GPS_PACE }

/** What the tracker tells the rest of the app. See the spec's Events table. */
sealed class TrackerEvent {
    data class EtaChanged(val oldEta: Instant?, val newEta: Instant, val fireAt: Instant) : TrackerEvent()
    data class DestinationLost(val at: Instant) : TrackerEvent()
    data class ServiceCancelled(val at: Instant) : TrackerEvent()
    data class DestinationRestored(val newEta: Instant?) : TrackerEvent()
    data class FeedLost(val lastGoodAt: Instant?, val kind: FailureKind) : TrackerEvent()
    data class EtaEstimated(
        val eta: Instant,
        val source: EtaSource,
        val staleness: Double,
        val rescheduled: Boolean
    ) : TrackerEvent()
    data class FeedRecovered(val outage: Double, val freshEta: Instant?, val deltaFromFallback: Double?) : TrackerEvent()
    data class AlarmSchedulingFailed(val fireAt: Instant, val message: String) : TrackerEvent()
    data class Arrived(val at: Instant) : TrackerEvent()
}
```

`TrackerInterfaces.kt`:
```kotlin
package com.trainalarm.app.tracking

import java.time.Instant

/** The tracker's only source of time. Production uses the system clock; tests drive a fake. */
interface TrackerClock {
    fun now(): Instant

    /** Suspends for [seconds]. Throws CancellationException if the caller is cancelled. */
    suspend fun sleep(seconds: Double)
}

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Instant,
    /** Horizontal accuracy in metres. */
    val accuracy: Double
)

interface LocationSource {
    fun latestFix(): LocationFix?
}
```

`Geo.kt`:
```kotlin
package com.trainalarm.app.tracking

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class Coordinate(val latitude: Double, val longitude: Double)

object Geo {
    const val EARTH_RADIUS_METRES = 6_371_000.0

    /** Great-circle (Haversine) distance in metres. */
    fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
            cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)
        return 2 * EARTH_RADIUS_METRES * asin(min(1.0, sqrt(a)))
    }
}
```

`MonitorState.kt`:
```kotlin
package com.trainalarm.app.tracking

import com.trainalarm.app.model.Service
import java.time.Instant

/** Where the destination stands for the train. */
enum class Standing { CALLING, LOST, CANCELLED }

/** What the driver must do to the alarm after a monitor step. */
sealed class AlarmAction {
    data object None : AlarmAction()
    data class Schedule(val fireAt: Instant) : AlarmAction()
    data object Cancel : AlarmAction()
}

sealed class PollResult {
    data class Success(val service: Service) : PollResult()
    data class Failure(val kind: FailureKind) : PollResult()
}

/** One tick's input to the monitor. */
data class Observation(
    val result: PollResult,
    val now: Instant,
    /** The latest location fix sampled this tick, if any (sampled whether or not the feed is up). */
    val fix: LocationFix?
)

/** The fixed inputs of a tracked journey. */
data class TrackerPlan(
    val boardingId: String,
    val destinationId: String,
    /** Resolved by the caller from the station directory; null disables the GPS fallback. */
    val destinationCoordinate: Coordinate?,
    val config: TrackerConfig
)

/** Everything the monitor remembers between ticks. */
data class MonitorState(
    val finished: Boolean = false,
    val standing: Standing = Standing.CALLING,
    val startedAt: Instant? = null,
    val lastEta: Instant? = null,
    val lastLiveEta: Instant? = null,
    val sawLive: Boolean = false,
    val lastGoodAt: Instant? = null,
    /** The fire time the scheduler has confirmed. */
    val scheduledFire: Instant? = null,
    /** A `schedule` call failed; the next tick must issue it again without a new `EtaChanged`. */
    val retryPending: Boolean = false,
    val alarmDue: Boolean = false,
    val feedDown: Boolean = false,
    val outageStartedAt: Instant? = null,
    val lastFallbackEta: Instant? = null,
    val fixes: List<LocationFix> = emptyList()
)

data class MonitorStep(
    val state: MonitorState,
    val events: List<TrackerEvent>,
    val action: AlarmAction
)
```

Create `android/app/src/main/java/com/trainalarm/app/alarm/AlarmScheduler.kt`:
```kotlin
package com.trainalarm.app.alarm

import java.time.Instant

/**
 * Sets the one alarm for a journey. Stage 3 provides the real implementation (foreground
 * service + exact alarm); until then tests use a fake.
 */
interface AlarmScheduler {
    /** Replaces any scheduled alarm with one at [fireAt] (idempotent). Throws if the OS refuses. */
    suspend fun schedule(fireAt: Instant)

    suspend fun cancel()
}
```

- [ ] **Step 6: Run both suites to verify they pass**

Commands as in Step 3 (without the `| head` filters if you want the full output). Expected: iOS `TEST SUCCEEDED` with 0 failures (the 56 earlier tests plus the new Geo, TrackerConfig, ServiceBuilder and fake tests); Android exit code 0 (`echo $?`), and no warnings in either output.

- [ ] **Step 7: Commit (local only, do not push)**

```bash
git add ios android
git diff --cached --stat   # secret check (.ai/git.md): no tokens, local.properties, *.xcodeproj, signing files
git commit -m "Feature: Add tracker foundations: config, events, interfaces, Haversine and test fakes

The vocabulary the Stage 2 journey monitor and tracker are built from, on
both platforms: TrackerConfig, TrackerEvent, TrackerClock, LocationSource,
AlarmScheduler, monitor state types and Haversine distance, plus hand-written
test fakes and a ServiceBuilder for scripting poll sequences.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Task 2: `JourneyMonitor`: the live-change logic (both platforms)

**Spec:** Monitor rules ("At start", "On a successful poll" rules 1-4, "Past-fire clamp"), Tracker inputs (destination stop), Scenario table rows 1-13, 21, 22. **Out of this task:** outage handling, GPS, arrival (Task 3); the poll loop (Task 4).

**Files:**
- Create iOS: `Sources/TrainAlarm/Tracking/JourneyMonitor.swift`; tests `Tests/TrainAlarmTests/Tracking/MonitorHarness.swift`, `JourneyMonitorTests.swift`
- Create Android: `.../tracking/JourneyMonitor.kt`; tests `android/app/src/test/java/com/trainalarm/app/tracking/MonitorHarness.kt`, `JourneyMonitorTest.kt`

**Interfaces:**
- Consumes (Task 1): `TrackerPlan`, `TrackerConfig`, `MonitorState`, `MonitorStep`, `Observation`, `PollResult`, `AlarmAction`, `Standing`, `TrackerEvent`, `ServiceBuilder`.
- Produces: `struct JourneyMonitor { init(plan:); func start(snapshot: Service, now: Date) -> MonitorStep; func step(_ state: MonitorState, _ observation: Observation) -> MonitorStep; func confirmScheduled(_ state: MonitorState, fireAt: Date) -> MonitorState; func schedulingFailed(_ state: MonitorState) -> MonitorState }` (Kotlin `class JourneyMonitor(plan)`, same method names with `Instant`). The driver (Task 4) calls `start` once, `step` per tick, then `confirmScheduled` or `schedulingFailed` after applying a `.schedule` action. Test support: `MonitorHarness(boarding:destination:coordinate:leadTime:start:)` with `state`, `now`, `scheduled`, `attempts`, `cancelCount`, `failNextSchedule`, `start(_:)`, `poll(_:advancing:fix:)`; Tasks 3 adds `fail(...)` to it.
- Decisions this task makes (the spec leaves them open; they follow its intent):
  - The decision "should we (re)schedule?" is one private function shared with Task 3's fallback path. It first honours a pending retry, then `alarmDue`, then treats a confirmed fire time that has already passed as "the alarm has fired" (so a later slip never re-arms it), then applies the inclusive 60s threshold against the **confirmed scheduled** fire time, and finally clamps a past fire time to `now`.
  - While the destination is lost or cancelled the monitor leaves the scheduled alarm alone and returns `AlarmAction.none`.
  - A failed poll is ignored for now (`observeFailure` is replaced in Task 3).

- [ ] **Step 1: Write the iOS harness and failing tests**

Create `ios/Tests/TrainAlarmTests/Tracking/MonitorHarness.swift`:
```swift
import XCTest
@testable import TrainAlarm

/// Drives the pure monitor the way the real driver does: it applies each action, confirming a
/// schedule unless `failNextSchedule` is set. Time only moves when a test says so.
final class MonitorHarness {
    let monitor: JourneyMonitor
    var state = MonitorState()
    var now: Date
    private(set) var scheduled: [Date] = []
    private(set) var attempts: [Date] = []
    private(set) var cancelCount = 0
    var failNextSchedule = false

    init(
        boarding: String = "WAT",
        destination: String = "BSK",
        coordinate: Coordinate? = nil,
        leadTime: TimeInterval = 600,
        start: Date = ServiceBuilder.time("08:00:00")
    ) {
        monitor = JourneyMonitor(plan: TrackerPlan(
            boardingId: boarding,
            destinationId: destination,
            destinationCoordinate: coordinate,
            config: TrackerConfig(leadTime: leadTime)
        ))
        now = start
    }

    @discardableResult
    func start(_ snapshot: Service) -> [TrackerEvent] {
        apply(monitor.start(snapshot: snapshot, now: now))
    }

    @discardableResult
    func poll(_ service: Service, advancing seconds: TimeInterval = 45, fix: LocationFix? = nil) -> [TrackerEvent] {
        now = now.addingTimeInterval(seconds)
        return apply(monitor.step(state, Observation(result: .service(service), now: now, fix: fix)))
    }

    private func apply(_ step: MonitorStep) -> [TrackerEvent] {
        state = step.state
        var events = step.events
        switch step.action {
        case .none:
            break
        case .schedule(let fireAt):
            attempts.append(fireAt)
            if failNextSchedule {
                failNextSchedule = false
                state = monitor.schedulingFailed(state)
                events.append(.alarmSchedulingFailed(fireAt: fireAt, message: "refused"))
            } else {
                scheduled.append(fireAt)
                state = monitor.confirmScheduled(state, fireAt: fireAt)
            }
        case .cancel:
            cancelCount += 1
        }
        return events
    }
}
```

Create `ios/Tests/TrainAlarmTests/Tracking/JourneyMonitorTests.swift`. The default harness starts at 08:00:00 with a 10-minute lead time, so the happy-path service (BSK estimate 08:52:00) gives a fire time of 08:42:00:
```swift
import XCTest
@testable import TrainAlarm

final class JourneyMonitorTests: XCTestCase {
    private func t(_ hhmmss: String) -> Date { ServiceBuilder.time(hhmmss) }
    private func happy(_ estimate: String? = "08:52:00") -> Service { ServiceBuilder.happyPath(destinationEstimate: estimate) }

    // MARK: Scenarios 1-5: ETA, threshold, drift

    func testSteadyLiveDataSchedulesOnceThenStaysQuiet() {
        let h = MonitorHarness()
        XCTAssertEqual(h.start(happy()), [.etaChanged(oldEta: nil, newEta: t("08:52:00"), fireAt: t("08:42:00"))])
        XCTAssertEqual(h.poll(happy()), [])
        XCTAssertEqual(h.poll(happy()), [])
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
    }

    func testThirtySecondShiftDoesNotReschedule() {
        let h = MonitorHarness()
        h.start(happy())
        XCTAssertEqual(h.poll(happy("08:52:30")), [])
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
    }

    func testExactlySixtySecondShiftReschedules() {
        let h = MonitorHarness()
        h.start(happy())
        XCTAssertEqual(h.poll(happy("08:53:00")),
                       [.etaChanged(oldEta: t("08:52:00"), newEta: t("08:53:00"), fireAt: t("08:43:00"))])
        XCTAssertEqual(h.scheduled, [t("08:42:00"), t("08:43:00")])
    }

    func testSlowDriftIsComparedAgainstTheScheduledTimeNotThePreviousPoll() {
        let h = MonitorHarness()
        h.start(happy())
        XCTAssertEqual(h.poll(happy("08:52:30")), [])
        XCTAssertEqual(h.poll(happy("08:53:00")),
                       [.etaChanged(oldEta: t("08:52:30"), newEta: t("08:53:00"), fireAt: t("08:43:00"))])
    }

    func testDelayThenBackToOnTimeReschedulesTwice() {
        let h = MonitorHarness()
        h.start(happy())
        XCTAssertEqual(h.poll(happy("08:57:00")),
                       [.etaChanged(oldEta: t("08:52:00"), newEta: t("08:57:00"), fireAt: t("08:47:00"))])
        XCTAssertEqual(h.poll(happy("08:52:00")),
                       [.etaChanged(oldEta: t("08:57:00"), newEta: t("08:52:00"), fireAt: t("08:42:00"))])
        XCTAssertEqual(h.scheduled, [t("08:42:00"), t("08:47:00"), t("08:42:00")])
    }

    // MARK: Scenarios 6-10: cancelled, dropped, displayAs

    func testWholeServiceCancelledEmitsServiceCancelledOnceAndLeavesTheAlarm() throws {
        let h = MonitorHarness()
        h.start(happy())
        let cancelled = try ServiceBuilder.loadFixture("gb-nr-service-cancelled")

        XCTAssertEqual(h.poll(cancelled), [.serviceCancelled(at: h.now)])
        XCTAssertEqual(h.poll(cancelled), [])
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
        XCTAssertEqual(h.cancelCount, 0)
    }

    func testDestinationAbsentEmitsDestinationLostOnce() throws {
        let h = MonitorHarness()
        h.start(happy())
        let dropped = try ServiceBuilder.loadFixture("gb-nr-service-destination-dropped")

        XCTAssertEqual(h.poll(dropped), [.destinationLost(at: h.now)])
        XCTAssertEqual(h.poll(dropped), [])
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
    }

    func testDivertedCancelledAndPassDestinationsAreLost() throws {
        let fixture = try ServiceBuilder.loadFixture("gb-nr-service-display-as")
        for destination in ["SUR", "WOK", "CLJ"] {  // DIVERTED, CANCELLED, PASS
            let h = MonitorHarness(destination: destination, start: t("12:00:00"))
            XCTAssertEqual(h.start(fixture), [.destinationLost(at: t("12:00:00"))], destination)
            XCTAssertEqual(h.scheduled, [], destination)
        }
    }

    func testUnknownDisplayAsAndTerminatesDoNotRaiseALoss() throws {
        let fixture = try ServiceBuilder.loadFixture("gb-nr-service-display-as")

        let unknown = MonitorHarness(destination: "WIN", start: t("12:00:00"))  // displayAs "FUTURE_VALUE"
        XCTAssertEqual(unknown.start(fixture), [.etaChanged(oldEta: nil, newEta: t("12:40:00"), fireAt: t("12:30:00"))])

        // TERMINATES means the train ends here: still a normal call. This stop is built, not taken
        // from the fixture: the fixture's BSK stop also has an actual arrival time, which ends
        // tracking once Task 3 lands.
        let terminatesStop = ServiceBuilder.stop(
            "BSK", scheduledArrival: "08:47:00", estimatedArrival: "08:52:00", displayAs: .terminates)
        let terminates = MonitorHarness()
        XCTAssertEqual(terminates.start(ServiceBuilder.replacing(happy(), stopAt: 2, with: terminatesStop)),
                       [.etaChanged(oldEta: nil, newEta: t("08:52:00"), fireAt: t("08:42:00"))])
    }

    func testOnlyTheDestinationsDepartureCancelledIsNotALoss() throws {
        let fixture = try ServiceBuilder.loadFixture("gb-nr-service-departure-cancelled")
        let h = MonitorHarness(destination: "WOK", start: t("11:00:00"))
        XCTAssertEqual(h.start(fixture), [.etaChanged(oldEta: nil, newEta: t("11:25:00"), fireAt: t("11:15:00"))])
    }

    func testDestinationArrivalCancelledWhileOtherStopsRunIsLost() {
        let h = MonitorHarness()
        h.start(happy())
        let arrivalCancelled = ServiceBuilder.replacing(
            happy(), stopAt: 2, with: ServiceBuilder.stop("BSK", scheduledArrival: "08:47:00", isArrivalCancelled: true))

        XCTAssertEqual(h.poll(arrivalCancelled), [.destinationLost(at: h.now)])
    }

    // MARK: Scenario 11: restored

    func testLostThenRestoredEmitsRestoredThenEtaChanged() throws {
        let h = MonitorHarness()
        h.start(happy())
        h.poll(try ServiceBuilder.loadFixture("gb-nr-service-destination-dropped"))

        XCTAssertEqual(h.poll(happy("08:58:00")), [
            .destinationRestored(newEta: t("08:58:00")),
            .etaChanged(oldEta: t("08:52:00"), newEta: t("08:58:00"), fireAt: t("08:48:00")),
        ])
        XCTAssertEqual(h.scheduled, [t("08:42:00"), t("08:48:00")])
    }

    // MARK: Scenarios 12-13: missing or vanished times

    func testNilBestArrivalHoldsTheLastEtaAndEmitsNothing() {
        let h = MonitorHarness()
        h.start(happy())
        let noTimes = ServiceBuilder.replacing(happy(), stopAt: 2, with: ServiceBuilder.stop("BSK"))

        XCTAssertEqual(h.poll(noTimes), [])
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
    }

    func testVanishedEstimateHoldsTheKnownDelayInsteadOfRevertingToTheTimetable() {
        let h = MonitorHarness()
        h.start(happy())  // live ETA 08:52:00
        let scheduledOnly = ServiceBuilder.replacing(
            happy(), stopAt: 2, with: ServiceBuilder.stop("BSK", scheduledArrival: "08:47:00"))

        XCTAssertEqual(h.poll(scheduledOnly), [])  // reverting to 08:47 would reschedule
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
    }

    func testFirstObservationWithNoTimesAtAllSchedulesNothing() {
        let h = MonitorHarness()
        let noTimes = ServiceBuilder.replacing(happy(), stopAt: 2, with: ServiceBuilder.stop("BSK"))

        XCTAssertEqual(h.start(noTimes), [])
        XCTAssertEqual(h.scheduled, [])
    }

    // MARK: Scenarios 21-22 and the clamp

    func testFireTimeAlreadyPastAtStartSchedulesNowOnceAndNeverAgain() {
        let h = MonitorHarness(start: t("08:45:00"))
        XCTAssertEqual(h.start(happy()), [.etaChanged(oldEta: nil, newEta: t("08:52:00"), fireAt: t("08:45:00"))])
        XCTAssertTrue(h.state.alarmDue)

        XCTAssertEqual(h.poll(happy()), [])
        XCTAssertEqual(h.poll(happy("09:30:00")), [])  // a big slip after the alarm is due is not re-armed
        XCTAssertEqual(h.scheduled, [t("08:45:00")])
    }

    func testEtaCloserThanTheLeadTimeAtStartSchedulesImmediately() {
        let h = MonitorHarness(start: t("08:50:00"))  // ETA 08:52 is only 2 minutes away, lead time is 10
        h.start(happy())
        XCTAssertEqual(h.scheduled, [t("08:50:00")])
    }

    func testFireTimeExactlyEqualToNowCountsAsPast() {
        let h = MonitorHarness(start: t("08:42:00"))  // desired fire time is exactly 08:42:00
        h.start(happy())
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
        XCTAssertTrue(h.state.alarmDue)
    }

    func testASlipAfterTheScheduledAlarmTimeHasPassedDoesNotRearmIt() {
        let h = MonitorHarness()
        h.start(happy())  // alarm confirmed for 08:42:00

        XCTAssertEqual(h.poll(happy("08:58:00"), advancing: 2580), [])  // now is 08:43:00
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
        XCTAssertTrue(h.state.alarmDue)
    }

    // MARK: Destination resolution

    func testDestinationOnlyBeforeTheBoardingStopIsLost() {
        let service = ServiceBuilder.service(stops: [
            ServiceBuilder.stop("BSK", scheduledArrival: "08:10:00"),
            ServiceBuilder.stop("WAT"),
            ServiceBuilder.stop("WOK", scheduledArrival: "08:23:00"),
        ])
        let h = MonitorHarness()
        XCTAssertEqual(h.start(service), [.destinationLost(at: h.now)])
    }

    func testDestinationEqualToBoardingIsLostNotACrash() {
        let h = MonitorHarness(boarding: "BSK", destination: "BSK")
        XCTAssertEqual(h.start(happy()), [.destinationLost(at: h.now)])
    }

    func testBoardingStationMissingFromTheCallingPatternSearchesTheWholeList() {
        let h = MonitorHarness(boarding: "XXX")
        XCTAssertEqual(h.start(happy()), [.etaChanged(oldEta: nil, newEta: t("08:52:00"), fireAt: t("08:42:00"))])
    }
}
```

- [ ] **Step 2: Write the Android harness and failing tests**

Create `android/app/src/test/java/com/trainalarm/app/tracking/MonitorHarness.kt`:
```kotlin
package com.trainalarm.app.tracking

import com.trainalarm.app.model.Service
import java.time.Instant

/**
 * Drives the pure monitor the way the real driver does: it applies each action, confirming a
 * schedule unless [failNextSchedule] is set. Time only moves when a test says so.
 */
class MonitorHarness(
    boarding: String = "WAT",
    destination: String = "BSK",
    coordinate: Coordinate? = null,
    leadTime: Double = 600.0,
    start: Instant = ServiceBuilder.time("08:00:00")
) {
    val monitor = JourneyMonitor(
        TrackerPlan(boarding, destination, coordinate, TrackerConfig(leadTime = leadTime))
    )
    var state = MonitorState()
    var now: Instant = start
    val scheduled = mutableListOf<Instant>()
    val attempts = mutableListOf<Instant>()
    var cancelCount = 0
    var failNextSchedule = false

    fun start(snapshot: Service): List<TrackerEvent> = apply(monitor.start(snapshot, now))

    fun poll(service: Service, advancing: Double = 45.0, fix: LocationFix? = null): List<TrackerEvent> {
        now = now.plusMillis((advancing * 1000).toLong())
        return apply(monitor.step(state, Observation(PollResult.Success(service), now, fix)))
    }

    private fun apply(step: MonitorStep): List<TrackerEvent> {
        state = step.state
        val events = step.events.toMutableList()
        when (val action = step.action) {
            AlarmAction.None -> {}
            is AlarmAction.Schedule -> {
                attempts += action.fireAt
                if (failNextSchedule) {
                    failNextSchedule = false
                    state = monitor.schedulingFailed(state)
                    events += TrackerEvent.AlarmSchedulingFailed(action.fireAt, "refused")
                } else {
                    scheduled += action.fireAt
                    state = monitor.confirmScheduled(state, action.fireAt)
                }
            }
            AlarmAction.Cancel -> cancelCount += 1
        }
        return events
    }
}
```

Create `android/app/src/test/java/com/trainalarm/app/tracking/JourneyMonitorTest.kt` (the same scenarios and numbers as the iOS file):
```kotlin
package com.trainalarm.app.tracking

import com.trainalarm.app.model.Service
import com.trainalarm.app.model.StopDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyMonitorTest {
    private fun t(hhmmss: String) = ServiceBuilder.time(hhmmss)
    private fun happy(estimate: String? = "08:52:00"): Service = ServiceBuilder.happyPath(estimate)
    private fun etaChanged(old: String?, new: String, fire: String) =
        TrackerEvent.EtaChanged(old?.let(::t), t(new), t(fire))

    // Scenarios 1-5: ETA, threshold, drift

    @Test
    fun steadyLiveDataSchedulesOnceThenStaysQuiet() {
        val h = MonitorHarness()
        assertEquals(listOf(etaChanged(null, "08:52:00", "08:42:00")), h.start(happy()))
        assertEquals(emptyList<TrackerEvent>(), h.poll(happy()))
        assertEquals(emptyList<TrackerEvent>(), h.poll(happy()))
        assertEquals(listOf(t("08:42:00")), h.scheduled)
    }

    @Test
    fun thirtySecondShiftDoesNotReschedule() {
        val h = MonitorHarness()
        h.start(happy())
        assertEquals(emptyList<TrackerEvent>(), h.poll(happy("08:52:30")))
        assertEquals(listOf(t("08:42:00")), h.scheduled)
    }

    @Test
    fun exactlySixtySecondShiftReschedules() {
        val h = MonitorHarness()
        h.start(happy())
        assertEquals(listOf(etaChanged("08:52:00", "08:53:00", "08:43:00")), h.poll(happy("08:53:00")))
        assertEquals(listOf(t("08:42:00"), t("08:43:00")), h.scheduled)
    }

    @Test
    fun slowDriftIsComparedAgainstTheScheduledTimeNotThePreviousPoll() {
        val h = MonitorHarness()
        h.start(happy())
        assertEquals(emptyList<TrackerEvent>(), h.poll(happy("08:52:30")))
        assertEquals(listOf(etaChanged("08:52:30", "08:53:00", "08:43:00")), h.poll(happy("08:53:00")))
    }

    @Test
    fun delayThenBackToOnTimeReschedulesTwice() {
        val h = MonitorHarness()
        h.start(happy())
        assertEquals(listOf(etaChanged("08:52:00", "08:57:00", "08:47:00")), h.poll(happy("08:57:00")))
        assertEquals(listOf(etaChanged("08:57:00", "08:52:00", "08:42:00")), h.poll(happy("08:52:00")))
        assertEquals(listOf(t("08:42:00"), t("08:47:00"), t("08:42:00")), h.scheduled)
    }

    // Scenarios 6-10: cancelled, dropped, displayAs

    @Test
    fun wholeServiceCancelledEmitsServiceCancelledOnceAndLeavesTheAlarm() {
        val h = MonitorHarness()
        h.start(happy())
        val cancelled = ServiceBuilder.loadFixture("gb-nr-service-cancelled")

        assertEquals(listOf<TrackerEvent>(TrackerEvent.ServiceCancelled(h.now.plusSeconds(45))), h.poll(cancelled))
        assertEquals(emptyList<TrackerEvent>(), h.poll(cancelled))
        assertEquals(listOf(t("08:42:00")), h.scheduled)
        assertEquals(0, h.cancelCount)
    }

    @Test
    fun destinationAbsentEmitsDestinationLostOnce() {
        val h = MonitorHarness()
        h.start(happy())
        val dropped = ServiceBuilder.loadFixture("gb-nr-service-destination-dropped")

        assertEquals(listOf<TrackerEvent>(TrackerEvent.DestinationLost(h.now.plusSeconds(45))), h.poll(dropped))
        assertEquals(emptyList<TrackerEvent>(), h.poll(dropped))
        assertEquals(listOf(t("08:42:00")), h.scheduled)
    }

    @Test
    fun divertedCancelledAndPassDestinationsAreLost() {
        val fixture = ServiceBuilder.loadFixture("gb-nr-service-display-as")
        for (destination in listOf("SUR", "WOK", "CLJ")) {  // DIVERTED, CANCELLED, PASS
            val h = MonitorHarness(destination = destination, start = t("12:00:00"))
            assertEquals(destination, listOf<TrackerEvent>(TrackerEvent.DestinationLost(t("12:00:00"))), h.start(fixture))
            assertEquals(destination, emptyList<java.time.Instant>(), h.scheduled)
        }
    }

    @Test
    fun unknownDisplayAsAndTerminatesDoNotRaiseALoss() {
        val fixture = ServiceBuilder.loadFixture("gb-nr-service-display-as")

        val unknown = MonitorHarness(destination = "WIN", start = t("12:00:00"))  // displayAs "FUTURE_VALUE"
        assertEquals(listOf(etaChanged(null, "12:40:00", "12:30:00")), unknown.start(fixture))

        // TERMINATES means the train ends here: still a normal call. This stop is built, not taken
        // from the fixture: the fixture's BSK stop also has an actual arrival time, which ends
        // tracking once Task 3 lands.
        val terminatesStop = ServiceBuilder.stop(
            "BSK", scheduledArrival = "08:47:00", estimatedArrival = "08:52:00", displayAs = StopDisplay.TERMINATES
        )
        val terminates = MonitorHarness()
        assertEquals(
            listOf(etaChanged(null, "08:52:00", "08:42:00")),
            terminates.start(ServiceBuilder.replacing(happy(), 2, terminatesStop))
        )
    }

    @Test
    fun onlyTheDestinationsDepartureCancelledIsNotALoss() {
        val fixture = ServiceBuilder.loadFixture("gb-nr-service-departure-cancelled")
        val h = MonitorHarness(destination = "WOK", start = t("11:00:00"))
        assertEquals(listOf(etaChanged(null, "11:25:00", "11:15:00")), h.start(fixture))
    }

    @Test
    fun destinationArrivalCancelledWhileOtherStopsRunIsLost() {
        val h = MonitorHarness()
        h.start(happy())
        val arrivalCancelled = ServiceBuilder.replacing(
            happy(), 2, ServiceBuilder.stop("BSK", scheduledArrival = "08:47:00", isArrivalCancelled = true)
        )

        assertEquals(listOf<TrackerEvent>(TrackerEvent.DestinationLost(h.now.plusSeconds(45))), h.poll(arrivalCancelled))
    }

    // Scenario 11: restored

    @Test
    fun lostThenRestoredEmitsRestoredThenEtaChanged() {
        val h = MonitorHarness()
        h.start(happy())
        h.poll(ServiceBuilder.loadFixture("gb-nr-service-destination-dropped"))

        assertEquals(
            listOf(
                TrackerEvent.DestinationRestored(t("08:58:00")),
                etaChanged("08:52:00", "08:58:00", "08:48:00")
            ),
            h.poll(happy("08:58:00"))
        )
        assertEquals(listOf(t("08:42:00"), t("08:48:00")), h.scheduled)
    }

    // Scenarios 12-13: missing or vanished times

    @Test
    fun nilBestArrivalHoldsTheLastEtaAndEmitsNothing() {
        val h = MonitorHarness()
        h.start(happy())
        val noTimes = ServiceBuilder.replacing(happy(), 2, ServiceBuilder.stop("BSK"))

        assertEquals(emptyList<TrackerEvent>(), h.poll(noTimes))
        assertEquals(listOf(t("08:42:00")), h.scheduled)
    }

    @Test
    fun vanishedEstimateHoldsTheKnownDelayInsteadOfRevertingToTheTimetable() {
        val h = MonitorHarness()
        h.start(happy())  // live ETA 08:52:00
        val scheduledOnly = ServiceBuilder.replacing(happy(), 2, ServiceBuilder.stop("BSK", scheduledArrival = "08:47:00"))

        assertEquals(emptyList<TrackerEvent>(), h.poll(scheduledOnly))  // reverting to 08:47 would reschedule
        assertEquals(listOf(t("08:42:00")), h.scheduled)
    }

    @Test
    fun firstObservationWithNoTimesAtAllSchedulesNothing() {
        val h = MonitorHarness()
        val noTimes = ServiceBuilder.replacing(happy(), 2, ServiceBuilder.stop("BSK"))

        assertEquals(emptyList<TrackerEvent>(), h.start(noTimes))
        assertEquals(emptyList<java.time.Instant>(), h.scheduled)
    }

    // Scenarios 21-22 and the clamp

    @Test
    fun fireTimeAlreadyPastAtStartSchedulesNowOnceAndNeverAgain() {
        val h = MonitorHarness(start = t("08:45:00"))
        assertEquals(listOf(etaChanged(null, "08:52:00", "08:45:00")), h.start(happy()))
        assertTrue(h.state.alarmDue)

        assertEquals(emptyList<TrackerEvent>(), h.poll(happy()))
        assertEquals(emptyList<TrackerEvent>(), h.poll(happy("09:30:00")))  // a big slip after the alarm is due is not re-armed
        assertEquals(listOf(t("08:45:00")), h.scheduled)
    }

    @Test
    fun etaCloserThanTheLeadTimeAtStartSchedulesImmediately() {
        val h = MonitorHarness(start = t("08:50:00"))  // ETA 08:52 is only 2 minutes away, lead time is 10
        h.start(happy())
        assertEquals(listOf(t("08:50:00")), h.scheduled)
    }

    @Test
    fun fireTimeExactlyEqualToNowCountsAsPast() {
        val h = MonitorHarness(start = t("08:42:00"))  // desired fire time is exactly 08:42:00
        h.start(happy())
        assertEquals(listOf(t("08:42:00")), h.scheduled)
        assertTrue(h.state.alarmDue)
    }

    @Test
    fun aSlipAfterTheScheduledAlarmTimeHasPassedDoesNotRearmIt() {
        val h = MonitorHarness()
        h.start(happy())  // alarm confirmed for 08:42:00

        assertEquals(emptyList<TrackerEvent>(), h.poll(happy("08:58:00"), advancing = 2580.0))  // now is 08:43:00
        assertEquals(listOf(t("08:42:00")), h.scheduled)
        assertTrue(h.state.alarmDue)
    }

    // Destination resolution

    @Test
    fun destinationOnlyBeforeTheBoardingStopIsLost() {
        val service = ServiceBuilder.service(
            listOf(
                ServiceBuilder.stop("BSK", scheduledArrival = "08:10:00"),
                ServiceBuilder.stop("WAT"),
                ServiceBuilder.stop("WOK", scheduledArrival = "08:23:00")
            )
        )
        val h = MonitorHarness()
        assertEquals(listOf<TrackerEvent>(TrackerEvent.DestinationLost(h.now)), h.start(service))
    }

    @Test
    fun destinationEqualToBoardingIsLostNotACrash() {
        val h = MonitorHarness(boarding = "BSK", destination = "BSK")
        assertEquals(listOf<TrackerEvent>(TrackerEvent.DestinationLost(h.now)), h.start(happy()))
    }

    @Test
    fun boardingStationMissingFromTheCallingPatternSearchesTheWholeList() {
        val h = MonitorHarness(boarding = "XXX")
        assertEquals(listOf(etaChanged(null, "08:52:00", "08:42:00")), h.start(happy()))
    }
}
```

- [ ] **Step 3: Run both suites to verify they fail**

Commands as in Task 1 Step 3 (iOS needs `xcodegen generate` first because of the new files). Expected: both FAIL to compile: iOS `cannot find 'JourneyMonitor' in scope`; Android `Unresolved reference: JourneyMonitor`.

- [ ] **Step 4: Implement the iOS monitor**

Create `ios/Sources/TrainAlarm/Tracking/JourneyMonitor.swift`:
```swift
import Foundation

/// The pure decision core of the tracker. It performs no I/O and never throws: the driver feeds
/// it one `Observation` per tick and applies the `AlarmAction` it returns, then reports back with
/// `confirmScheduled` or `schedulingFailed`. See the spec's "Monitor rules".
struct JourneyMonitor {
    let plan: TrackerPlan

    private var config: TrackerConfig { plan.config }

    /// Builds the first step from the selection-time snapshot, so the timetable-based fallback
    /// alarm is scheduled before any poll has happened.
    func start(snapshot: Service, now: Date) -> MonitorStep {
        var state = MonitorState()
        state.startedAt = now
        return observeService(snapshot, now: now, state: state)
    }

    func step(_ state: MonitorState, _ observation: Observation) -> MonitorStep {
        guard !state.finished else { return MonitorStep(state: state, events: [], action: .none) }
        switch observation.result {
        case .service(let service):
            return observeService(service, now: observation.now, state: state)
        case .failure(let kind):
            return observeFailure(kind, now: observation.now, state: state)
        }
    }

    /// Call after the scheduler accepted a `.schedule` action.
    func confirmScheduled(_ state: MonitorState, fireAt: Date) -> MonitorState {
        var state = state
        state.scheduledFire = fireAt
        state.retryPending = false
        return state
    }

    /// Call after the scheduler refused a `.schedule` action: the next tick asks again.
    func schedulingFailed(_ state: MonitorState) -> MonitorState {
        var state = state
        state.retryPending = true
        return state
    }

    // MARK: Successful poll

    private func observeService(_ service: Service, now: Date, state input: MonitorState) -> MonitorStep {
        var state = input
        state.lastGoodAt = now
        var events: [TrackerEvent] = []

        let destination = destinationStop(in: service)
        let newStanding = classify(destination, in: service)
        let previous = state.standing
        state.standing = newStanding
        if newStanding == .lost && previous != .lost { events.append(.destinationLost(at: now)) }
        if newStanding == .cancelled && previous != .cancelled { events.append(.serviceCancelled(at: now)) }
        guard newStanding == .calling, let stop = destination else {
            // Lost or cancelled: leave the scheduled alarm alone (cancelling it risks silence).
            return MonitorStep(state: state, events: events, action: .none)
        }

        let eta = resolveEta(for: stop, state: &state)
        if previous != .calling { events.append(.destinationRestored(newEta: eta)) }
        guard let eta else {
            return MonitorStep(state: state, events: events, action: .none)
        }

        let previousEta = state.lastEta
        state.lastEta = eta
        let decision = decideSchedule(desired: eta.addingTimeInterval(-config.leadTime), now: now, state: state)
        state = decision.state
        if decision.announces, case .schedule(let fireAt) = decision.action {
            events.append(.etaChanged(oldEta: previousEta, newEta: eta, fireAt: fireAt))
        }
        return MonitorStep(state: state, events: events, action: decision.action)
    }

    /// The first stop with `destinationId` after the boarding stop (the whole list if the boarding
    /// station is not found). Never `Journey.destination`: that is the service's terminus.
    private func destinationStop(in service: Service) -> Stop? {
        let stops = service.journey.stops
        let start = stops.firstIndex { $0.station.id == plan.boardingId }.map { $0 + 1 } ?? 0
        return stops[start...].first { $0.station.id == plan.destinationId }
    }

    private func classify(_ stop: Stop?, in service: Service) -> Standing {
        guard let stop else { return .lost }
        if service.journey.stops.allSatisfy({ $0.isCancelled }) { return .cancelled }
        if notCalledAt(stop) { return .lost }
        return .calling
    }

    /// The destination's arrival is cancelled, or the provider says the train no longer calls
    /// there. A nil or unknown `displayAs` is not enough on its own: the schema reads a missing
    /// value as PASS, but that has not been checked against real data.
    private func notCalledAt(_ stop: Stop) -> Bool {
        if stop.isArrivalCancelled { return true }
        switch stop.displayAs {
        case .cancelled?, .diverted?, .pass?: return true
        default: return false
        }
    }

    private func resolveEta(for stop: Stop, state: inout MonitorState) -> Date? {
        if let live = stop.estimatedArrival {
            state.sawLive = true
            state.lastLiveEta = live
            return live
        }
        // A live estimate that has vanished must not silently drop a known delay.
        if state.sawLive && !stop.hasArrived { return state.lastEta }
        return stop.scheduledArrival ?? state.lastEta
    }

    // MARK: Scheduling

    /// Decides whether to (re)schedule for `desired`. `announces` says whether a live-data
    /// `etaChanged` should accompany the action (false for retries and, in Task 3, fallback ETAs).
    private func decideSchedule(
        desired: Date, now: Date, state input: MonitorState
    ) -> (state: MonitorState, action: AlarmAction, announces: Bool) {
        var state = input
        let clamped = desired <= now
        if state.retryPending {
            // A previous schedule call failed: ask again, without announcing the same change twice.
            if clamped { state.alarmDue = true }
            return (state, .schedule(clamped ? now : desired), false)
        }
        if state.alarmDue { return (state, .none, false) }
        if let scheduled = state.scheduledFire {
            if scheduled <= now {
                // The confirmed alarm time has passed: it has fired, so a later slip must not re-arm it.
                state.alarmDue = true
                return (state, .none, false)
            }
            if abs(desired.timeIntervalSince(scheduled)) < config.rescheduleThreshold {
                return (state, .none, false)
            }
        }
        if clamped { state.alarmDue = true }
        return (state, .schedule(clamped ? now : desired), true)
    }

    // MARK: Failed poll

    /// Outage handling arrives in Task 3, which replaces this body; until then a failed poll changes nothing.
    private func observeFailure(_ kind: FailureKind, now: Date, state: MonitorState) -> MonitorStep {
        MonitorStep(state: state, events: [], action: .none)
    }
}
```

- [ ] **Step 5: Implement the Android monitor**

Create `android/app/src/main/java/com/trainalarm/app/tracking/JourneyMonitor.kt`:
```kotlin
package com.trainalarm.app.tracking

import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Stop
import com.trainalarm.app.model.StopDisplay
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

private fun Instant.shifted(bySeconds: Double): Instant = plusMillis((bySeconds * 1000).toLong())

private fun secondsBetween(from: Instant, to: Instant): Double = Duration.between(from, to).toMillis() / 1000.0

/**
 * The pure decision core of the tracker. It performs no I/O and never throws: the driver feeds
 * it one [Observation] per tick and applies the [AlarmAction] it returns, then reports back with
 * [confirmScheduled] or [schedulingFailed]. See the spec's "Monitor rules".
 */
class JourneyMonitor(private val plan: TrackerPlan) {
    private val config get() = plan.config

    private data class ScheduleDecision(val state: MonitorState, val action: AlarmAction, val announces: Boolean)

    /**
     * Builds the first step from the selection-time snapshot, so the timetable-based fallback
     * alarm is scheduled before any poll has happened.
     */
    fun start(snapshot: Service, now: Instant): MonitorStep =
        observeService(snapshot, now, MonitorState(startedAt = now))

    fun step(state: MonitorState, observation: Observation): MonitorStep {
        if (state.finished) return MonitorStep(state, emptyList(), AlarmAction.None)
        return when (val result = observation.result) {
            is PollResult.Success -> observeService(result.service, observation.now, state)
            is PollResult.Failure -> observeFailure(result.kind, observation.now, state)
        }
    }

    /** Call after the scheduler accepted a [AlarmAction.Schedule]. */
    fun confirmScheduled(state: MonitorState, fireAt: Instant): MonitorState =
        state.copy(scheduledFire = fireAt, retryPending = false)

    /** Call after the scheduler refused a [AlarmAction.Schedule]: the next tick asks again. */
    fun schedulingFailed(state: MonitorState): MonitorState = state.copy(retryPending = true)

    // Successful poll

    private fun observeService(service: Service, now: Instant, input: MonitorState): MonitorStep {
        var state = input.copy(lastGoodAt = now)
        val events = mutableListOf<TrackerEvent>()

        val stop = destinationStop(service)
        val newStanding = classify(stop, service)
        val previous = state.standing
        state = state.copy(standing = newStanding)
        if (newStanding == Standing.LOST && previous != Standing.LOST) events += TrackerEvent.DestinationLost(now)
        if (newStanding == Standing.CANCELLED && previous != Standing.CANCELLED) events += TrackerEvent.ServiceCancelled(now)
        if (newStanding != Standing.CALLING || stop == null) {
            // Lost or cancelled: leave the scheduled alarm alone (cancelling it risks silence).
            return MonitorStep(state, events, AlarmAction.None)
        }

        val live = stop.estimatedArrival
        val eta: Instant? = when {
            live != null -> {
                state = state.copy(sawLive = true, lastLiveEta = live)
                live
            }
            // A live estimate that has vanished must not silently drop a known delay.
            state.sawLive && !stop.hasArrived -> state.lastEta
            else -> stop.scheduledArrival ?: state.lastEta
        }
        if (previous != Standing.CALLING) events += TrackerEvent.DestinationRestored(eta)
        if (eta == null) return MonitorStep(state, events, AlarmAction.None)

        val previousEta = state.lastEta
        state = state.copy(lastEta = eta)
        val decision = decideSchedule(eta.shifted(-config.leadTime), now, state)
        state = decision.state
        val action = decision.action
        if (decision.announces && action is AlarmAction.Schedule) {
            events += TrackerEvent.EtaChanged(previousEta, eta, action.fireAt)
        }
        return MonitorStep(state, events, action)
    }

    /**
     * The first stop with `destinationId` after the boarding stop (the whole list if the boarding
     * station is not found). Never `Journey.destination`: that is the service's terminus.
     */
    private fun destinationStop(service: Service): Stop? {
        val stops = service.journey.stops
        val boardingIndex = stops.indexOfFirst { it.station.id == plan.boardingId }
        val start = if (boardingIndex >= 0) boardingIndex + 1 else 0
        return stops.drop(start).firstOrNull { it.station.id == plan.destinationId }
    }

    private fun classify(stop: Stop?, service: Service): Standing = when {
        stop == null -> Standing.LOST
        service.journey.stops.all { it.isCancelled } -> Standing.CANCELLED
        notCalledAt(stop) -> Standing.LOST
        else -> Standing.CALLING
    }

    /**
     * The destination's arrival is cancelled, or the provider says the train no longer calls
     * there. A null or unknown `displayAs` is not enough on its own: the schema reads a missing
     * value as PASS, but that has not been checked against real data.
     */
    private fun notCalledAt(stop: Stop): Boolean =
        stop.isArrivalCancelled ||
            stop.displayAs == StopDisplay.CANCELLED ||
            stop.displayAs == StopDisplay.DIVERTED ||
            stop.displayAs == StopDisplay.PASS

    // Scheduling

    /**
     * Decides whether to (re)schedule for [desired]. `announces` says whether a live-data
     * `EtaChanged` should accompany the action (false for retries and, in Task 3, fallback ETAs).
     */
    private fun decideSchedule(desired: Instant, now: Instant, input: MonitorState): ScheduleDecision {
        var state = input
        val clamped = !desired.isAfter(now)
        if (state.retryPending) {
            // A previous schedule call failed: ask again, without announcing the same change twice.
            if (clamped) state = state.copy(alarmDue = true)
            return ScheduleDecision(state, AlarmAction.Schedule(if (clamped) now else desired), false)
        }
        if (state.alarmDue) return ScheduleDecision(state, AlarmAction.None, false)
        val scheduled = state.scheduledFire
        if (scheduled != null) {
            if (!scheduled.isAfter(now)) {
                // The confirmed alarm time has passed: it has fired, so a later slip must not re-arm it.
                return ScheduleDecision(state.copy(alarmDue = true), AlarmAction.None, false)
            }
            if (abs(secondsBetween(scheduled, desired)) < config.rescheduleThreshold) {
                return ScheduleDecision(state, AlarmAction.None, false)
            }
        }
        if (clamped) state = state.copy(alarmDue = true)
        return ScheduleDecision(state, AlarmAction.Schedule(if (clamped) now else desired), true)
    }

    // Failed poll

    /** Outage handling arrives in Task 3, which replaces this body; until then a failed poll changes nothing. */
    @Suppress("UNUSED_PARAMETER")
    private fun observeFailure(kind: FailureKind, now: Instant, state: MonitorState): MonitorStep =
        MonitorStep(state, emptyList(), AlarmAction.None)
}
```

- [ ] **Step 6: Run both suites to verify they pass**

Commands as in Task 1 Step 3. Expected: iOS `TEST SUCCEEDED` with 0 failures (the 22 new `JourneyMonitorTests` plus everything before); Android exit code 0 with no warnings.

- [ ] **Step 7: Commit (local only, do not push)**

```bash
git add ios android
git diff --cached --stat   # secret check (.ai/git.md): no tokens, local.properties, *.xcodeproj, signing files
git commit -m "Feature: Add JourneyMonitor live-change logic: ETA, threshold, clamp, lost and cancelled

The pure decision core of the Stage 2 tracker on both platforms: resolves the
user's destination stop, computes the ETA and alarm fire time, reschedules
only when the confirmed fire time moves by 60s or more, clamps a past fire
time once, and tells a cancelled service from a destination the train no
longer calls at. Outage handling and arrival follow.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Task 3: `JourneyMonitor`: outage, recovery, scheduler retry and arrival (both platforms)

**Spec:** Monitor rules ("On a failed poll", rule 0 feedRecovered, rule 5 arrival, "Scheduler failure"), the GPS pace formula, Scenario table rows 14-20, 23, 24. Review Focus item 4.

**Files:**
- Modify iOS: `Sources/TrainAlarm/Tracking/JourneyMonitor.swift`; tests `Tests/TrainAlarmTests/Tracking/MonitorHarness.swift` (replaced)
- Create iOS tests: `Tests/TrainAlarmTests/Tracking/JourneyMonitorOutageTests.swift`
- Modify Android: `.../tracking/JourneyMonitor.kt`; test `.../tracking/MonitorHarness.kt` (replaced)
- Create Android tests: `.../tracking/JourneyMonitorOutageTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1-2.
- Produces: `JourneyMonitor.step` now handles `.failure` (feedLost once per outage; `etaEstimated` each tick; GPS or last-known fallback with the guard; the same inclusive threshold), records fixes, emits `feedRecovered` first on the next success, emits `arrived` (action `.cancel`, `state.finished = true`), and `step` after `finished` returns nothing. Test support gains `FixSpec(latitude:longitude:accuracy:ageSeconds:)` and `MonitorHarness.fail(_:advancing:fix:)`; `poll` now takes a `FixSpec?` instead of a `LocationFix?`.
- Decisions this task makes:
  - `lastGoodAt` is set only by polls (not by the selection-time snapshot), so a first poll that fails reports `lastGoodAt: nil` as the spec's scenario 14 requires.
  - The fallback never overwrites `lastEta`; it uses a separate `lastFallbackEta`. The guard anchor (`lastLiveEta ?? lastEta`) therefore stays fixed across a long outage instead of creeping earlier.
  - While the destination is lost or cancelled there is no fallback ETA, so an outage then emits only `feedLost`.
  - The arrival check runs before the scheduling decision, so a poll that confirms arrival never schedules anything.
  - On Android, `shifted` now rounds to the nearest millisecond (it truncated), so a computed fallback ETA is never a millisecond early.
  - Tracking never ends itself while the destination is lost or cancelled or the feed is down: only a confirmed arrival, or the arrival grace passing on a normal poll, ends it. The caller ends tracking with `stop()` (the "I'm off" action, spec §1). Ending automatically in those states would need a new event, because `arrived` would be wrong after a cancellation.
  - If the very first `schedule` call is refused and the destination is then lost, nothing retries it (retries only run on the calling path). No alarm exists in that case; accepted as unlikely.
  - After a GPS-driven reschedule, a recovery whose live ETA equals the pre-outage ETA reports `etaChanged` with equal old and new ETAs and a moved `fireAt`: the alarm time really did change. Accepted.

- [ ] **Step 1: Replace the iOS harness**

Replace `ios/Tests/TrainAlarmTests/Tracking/MonitorHarness.swift` with the version below (adds `FixSpec` and `fail`; `poll` takes a `FixSpec?` stamped at the tick's time):
```swift
import XCTest
@testable import TrainAlarm

/// A location fix described relative to the tick it arrives on.
struct FixSpec {
    var latitude: Double
    var longitude: Double
    var accuracy: Double = 10
    /// How old the fix already is when the tick happens.
    var ageSeconds: TimeInterval = 0
}

/// Drives the pure monitor the way the real driver does: it applies each action, confirming a
/// schedule unless `failNextSchedule` is set. Time only moves when a test says so.
final class MonitorHarness {
    let monitor: JourneyMonitor
    var state = MonitorState()
    var now: Date
    private(set) var scheduled: [Date] = []
    private(set) var attempts: [Date] = []
    private(set) var cancelCount = 0
    var failNextSchedule = false

    init(
        boarding: String = "WAT",
        destination: String = "BSK",
        coordinate: Coordinate? = nil,
        leadTime: TimeInterval = 600,
        start: Date = ServiceBuilder.time("08:00:00")
    ) {
        monitor = JourneyMonitor(plan: TrackerPlan(
            boardingId: boarding,
            destinationId: destination,
            destinationCoordinate: coordinate,
            config: TrackerConfig(leadTime: leadTime)
        ))
        now = start
    }

    @discardableResult
    func start(_ snapshot: Service) -> [TrackerEvent] {
        apply(monitor.start(snapshot: snapshot, now: now))
    }

    @discardableResult
    func poll(_ service: Service, advancing seconds: TimeInterval = 45, fix: FixSpec? = nil) -> [TrackerEvent] {
        now = now.addingTimeInterval(seconds)
        return apply(monitor.step(state, Observation(result: .service(service), now: now, fix: stamp(fix))))
    }

    @discardableResult
    func fail(_ kind: FailureKind = .network, advancing seconds: TimeInterval = 45, fix: FixSpec? = nil) -> [TrackerEvent] {
        now = now.addingTimeInterval(seconds)
        return apply(monitor.step(state, Observation(result: .failure(kind), now: now, fix: stamp(fix))))
    }

    private func stamp(_ spec: FixSpec?) -> LocationFix? {
        spec.map {
            LocationFix(latitude: $0.latitude, longitude: $0.longitude,
                        timestamp: now.addingTimeInterval(-$0.ageSeconds), accuracy: $0.accuracy)
        }
    }

    private func apply(_ step: MonitorStep) -> [TrackerEvent] {
        state = step.state
        var events = step.events
        switch step.action {
        case .none:
            break
        case .schedule(let fireAt):
            attempts.append(fireAt)
            if failNextSchedule {
                failNextSchedule = false
                state = monitor.schedulingFailed(state)
                events.append(.alarmSchedulingFailed(fireAt: fireAt, message: "refused"))
            } else {
                scheduled.append(fireAt)
                state = monitor.confirmScheduled(state, fireAt: fireAt)
            }
        case .cancel:
            cancelCount += 1
        }
        return events
    }
}
```

- [ ] **Step 2: Write the failing iOS tests**

Create `ios/Tests/TrainAlarmTests/Tracking/JourneyMonitorOutageTests.swift`. The GPS cases use a destination on the equator so distances are linear in longitude: with fixes at longitude 0.00 (08:09:00) and 0.02 (08:10:00) and the destination at longitude 1.0, the approach speed is 0.02° per 60s and the remaining 0.98° takes exactly 2940s, so the GPS ETA is 08:10:00 + 2940s = 08:59:00 (compared with a 1ms tolerance because of floating point):
```swift
import XCTest
@testable import TrainAlarm

final class JourneyMonitorOutageTests: XCTestCase {
    private func t(_ hhmmss: String) -> Date { ServiceBuilder.time(hhmmss) }
    private func happy(_ estimate: String? = "08:52:00") -> Service { ServiceBuilder.happyPath(destinationEstimate: estimate) }
    private let farDestination = Coordinate(latitude: 0, longitude: 1.0)
    private let nearDestination = Coordinate(latitude: 0, longitude: 0.1)

    /// start, one good poll at 08:09:00 with a fix at longitude 0, then a failed poll at 08:10:00 with a fix at 0.02.
    private func outageWithTwoFixes(_ h: MonitorHarness, secondFix: FixSpec = FixSpec(latitude: 0, longitude: 0.02),
                                    firstFix: FixSpec = FixSpec(latitude: 0, longitude: 0.0)) -> [TrackerEvent] {
        h.start(happy())
        h.poll(happy(), advancing: 540, fix: firstFix)  // now 08:09:00
        return h.fail(.network, advancing: 60, fix: secondFix)  // now 08:10:00
    }

    // MARK: Scenarios 14-17: the outage fallback

    func testFirstPollFailingReportsNoLastGoodTimeAndFallsBackToTheSnapshot() {
        let h = MonitorHarness()
        h.start(happy())
        XCTAssertEqual(h.fail(.network), [
            .feedLost(lastGoodAt: nil, kind: .network),
            .etaEstimated(eta: t("08:52:00"), source: .lastKnownTrajectory, staleness: 45, rescheduled: false),
        ])
    }

    func testOutageWithoutGpsHoldsTheLastLiveEtaAndFeedLostFiresOnce() {
        let h = MonitorHarness()
        h.start(happy())
        h.poll(happy())  // good at 08:00:45

        XCTAssertEqual(h.fail(.http(503)), [
            .feedLost(lastGoodAt: t("08:00:45"), kind: .http(503)),
            .etaEstimated(eta: t("08:52:00"), source: .lastKnownTrajectory, staleness: 45, rescheduled: false),
        ])
        XCTAssertEqual(h.fail(.http(503)), [
            .etaEstimated(eta: t("08:52:00"), source: .lastKnownTrajectory, staleness: 90, rescheduled: false),
        ])
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
    }

    func testOutageWithGpsApproachingUsesGpsPaceAndReschedules() {
        let h = MonitorHarness(coordinate: farDestination)
        let events = outageWithTwoFixes(h)

        guard events.count == 2 else { return XCTFail("expected 2 events, got \(events)") }
        XCTAssertEqual(events[0], .feedLost(lastGoodAt: t("08:09:00"), kind: .network))
        guard case .etaEstimated(let eta, let source, let staleness, let rescheduled) = events[1] else {
            return XCTFail("expected etaEstimated, got \(events[1])")
        }
        XCTAssertEqual(eta.timeIntervalSince(t("08:59:00")), 0, accuracy: 0.001)
        XCTAssertEqual(source, .gpsPace)
        XCTAssertEqual(staleness, 60)
        XCTAssertTrue(rescheduled)
        XCTAssertEqual(h.scheduled.count, 2)
        XCTAssertEqual(h.scheduled[1].timeIntervalSince(t("08:49:00")), 0, accuracy: 0.001)
    }

    func testGpsFallbackIsNeverEarlierThanTheLastLiveEtaMinusTheGuard() {
        let h = MonitorHarness(coordinate: nearDestination)  // pace would put the ETA at 08:14:00
        let events = outageWithTwoFixes(h)

        XCTAssertEqual(events, [
            .feedLost(lastGoodAt: t("08:09:00"), kind: .network),
            .etaEstimated(eta: t("08:51:00"), source: .gpsPace, staleness: 60, rescheduled: true),
        ])
        XCTAssertEqual(h.scheduled, [t("08:42:00"), t("08:41:00")])
    }

    func testWithoutADestinationCoordinateTheFallbackStaysOnTheTrajectory() {
        let h = MonitorHarness(coordinate: nil)
        let events = outageWithTwoFixes(h)
        XCTAssertEqual(events.last, .etaEstimated(eta: t("08:52:00"), source: .lastKnownTrajectory, staleness: 60, rescheduled: false))
    }

    func testInaccurateFixesAreIgnored() {
        let h = MonitorHarness(coordinate: farDestination)
        let events = outageWithTwoFixes(h, firstFix: FixSpec(latitude: 0, longitude: 0.0, accuracy: 500))
        XCTAssertEqual(events.last, .etaEstimated(eta: t("08:52:00"), source: .lastKnownTrajectory, staleness: 60, rescheduled: false))
    }

    func testStaleFixesAreIgnored() {
        let h = MonitorHarness(coordinate: farDestination)
        h.start(happy())
        h.poll(happy(), advancing: 45, fix: FixSpec(latitude: 0, longitude: 0.0))
        let events = h.fail(.network, advancing: 300, fix: FixSpec(latitude: 0, longitude: 0.02))  // the first fix is now 300s old

        XCTAssertEqual(events.last, .etaEstimated(eta: t("08:52:00"), source: .lastKnownTrajectory, staleness: 300, rescheduled: false))
    }

    func testAStationaryTrainBelowMinimumPaceHoldsTheLastEta() {
        let h = MonitorHarness(coordinate: farDestination)
        let events = outageWithTwoFixes(h, secondFix: FixSpec(latitude: 0, longitude: 0.0))
        XCTAssertEqual(events.last, .etaEstimated(eta: t("08:52:00"), source: .lastKnownTrajectory, staleness: 60, rescheduled: false))
    }

    func testAnOutageWhileTheDestinationIsLostEmitsOnlyFeedLost() throws {
        let h = MonitorHarness()
        h.start(happy())
        h.poll(try ServiceBuilder.loadFixture("gb-nr-service-destination-dropped"))

        XCTAssertEqual(h.fail(.network), [.feedLost(lastGoodAt: t("08:00:45"), kind: .network)])
    }

    // MARK: Scenarios 18-20: recovery and flapping

    func testRecoveryWithADelayReportsRecoveryThenTheChange() {
        let h = MonitorHarness()
        h.start(happy())
        h.fail()  // 08:00:45

        XCTAssertEqual(h.poll(happy("08:57:00")), [  // 08:01:30
            .feedRecovered(outage: 45, freshEta: t("08:57:00"), deltaFromFallback: 300),
            .etaChanged(oldEta: t("08:52:00"), newEta: t("08:57:00"), fireAt: t("08:47:00")),
        ])
        XCTAssertFalse(h.state.feedDown)
    }

    func testRecoveryThatRevealsADroppedDestinationReportsRecoveryFirst() throws {
        let h = MonitorHarness()
        h.start(happy())
        h.fail()

        XCTAssertEqual(h.poll(try ServiceBuilder.loadFixture("gb-nr-service-destination-dropped")), [
            .feedRecovered(outage: 45, freshEta: nil, deltaFromFallback: nil),
            .destinationLost(at: h.now),
        ])
    }

    func testFlappingYieldsTwoFeedLostAndOneFeedRecovered() {
        let h = MonitorHarness()
        h.start(happy())
        let all = h.fail() + h.poll(happy()) + h.fail()

        XCTAssertEqual(all.filter { if case .feedLost = $0 { return true } else { return false } }.count, 2)
        XCTAssertEqual(all.filter { if case .feedRecovered = $0 { return true } else { return false } }.count, 1)
    }

    // MARK: Scenario 23: scheduler refusal

    func testASchedulerRefusalIsReportedThenRetriedOnTheNextTickWithoutANewEtaChanged() {
        let h = MonitorHarness()
        h.start(happy())
        h.failNextSchedule = true

        XCTAssertEqual(h.poll(happy("08:58:00")), [
            .etaChanged(oldEta: t("08:52:00"), newEta: t("08:58:00"), fireAt: t("08:48:00")),
            .alarmSchedulingFailed(fireAt: t("08:48:00"), message: "refused"),
        ])
        XCTAssertEqual(h.scheduled, [t("08:42:00")])

        XCTAssertEqual(h.poll(happy("08:58:00")), [])
        XCTAssertEqual(h.scheduled, [t("08:42:00"), t("08:48:00")])
        XCTAssertEqual(h.attempts, [t("08:42:00"), t("08:48:00"), t("08:48:00")])
        XCTAssertFalse(h.state.retryPending)
    }

    // MARK: Scenario 24: arrival

    func testConfirmedArrivalEmitsArrivedCancelsTheAlarmAndEndsTracking() {
        let h = MonitorHarness()
        h.start(happy())
        let arrived = ServiceBuilder.replacing(happy(), stopAt: 2, with: ServiceBuilder.stop(
            "BSK", scheduledArrival: "08:47:00", estimatedArrival: "08:50:00", hasArrived: true))

        XCTAssertEqual(h.poll(arrived), [.arrived(at: h.now)])
        XCTAssertEqual(h.cancelCount, 1)
        XCTAssertTrue(h.state.finished)
        XCTAssertEqual(h.poll(happy()), [])  // finished: ignored
        XCTAssertEqual(h.cancelCount, 1)
        XCTAssertEqual(h.scheduled, [t("08:42:00")])
    }

    func testNoArrivalReportEndsTrackingOnceTheGracePeriodHasPassed() {
        let h = MonitorHarness()
        h.start(happy())  // ETA 08:52:00, grace 10 minutes
        XCTAssertEqual(h.poll(happy(), advancing: 3780), [.arrived(at: t("09:03:00"))])
        XCTAssertEqual(h.cancelCount, 1)
    }

    func testADestinationThatHasAlreadyArrivedEndsTrackingEvenWhenItIsMarkedTerminates() throws {
        // The fixture's BSK stop is TERMINATES and has an actual arrival time (12:48).
        let h = MonitorHarness(destination: "BSK", start: t("12:00:00"))
        XCTAssertEqual(h.start(try ServiceBuilder.loadFixture("gb-nr-service-display-as")), [.arrived(at: t("12:00:00"))])
        XCTAssertEqual(h.cancelCount, 1)
    }
}
```

- [ ] **Step 3: Replace the Android harness and write the failing Android tests**

Replace `android/app/src/test/java/com/trainalarm/app/tracking/MonitorHarness.kt` with (adds `FixSpec` and `fail`; `poll` takes a `FixSpec?`):
```kotlin
package com.trainalarm.app.tracking

import com.trainalarm.app.model.Service
import java.time.Instant

/** A location fix described relative to the tick it arrives on. */
data class FixSpec(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double = 10.0,
    /** How old the fix already is when the tick happens. */
    val ageSeconds: Double = 0.0
)

/**
 * Drives the pure monitor the way the real driver does: it applies each action, confirming a
 * schedule unless [failNextSchedule] is set. Time only moves when a test says so.
 */
class MonitorHarness(
    boarding: String = "WAT",
    destination: String = "BSK",
    coordinate: Coordinate? = null,
    leadTime: Double = 600.0,
    start: Instant = ServiceBuilder.time("08:00:00")
) {
    val monitor = JourneyMonitor(
        TrackerPlan(boarding, destination, coordinate, TrackerConfig(leadTime = leadTime))
    )
    var state = MonitorState()
    var now: Instant = start
    val scheduled = mutableListOf<Instant>()
    val attempts = mutableListOf<Instant>()
    var cancelCount = 0
    var failNextSchedule = false

    fun start(snapshot: Service): List<TrackerEvent> = apply(monitor.start(snapshot, now))

    fun poll(service: Service, advancing: Double = 45.0, fix: FixSpec? = null): List<TrackerEvent> {
        now = now.plusMillis((advancing * 1000).toLong())
        return apply(monitor.step(state, Observation(PollResult.Success(service), now, stamp(fix))))
    }

    fun fail(kind: FailureKind = FailureKind.Network, advancing: Double = 45.0, fix: FixSpec? = null): List<TrackerEvent> {
        now = now.plusMillis((advancing * 1000).toLong())
        return apply(monitor.step(state, Observation(PollResult.Failure(kind), now, stamp(fix))))
    }

    private fun stamp(spec: FixSpec?): LocationFix? = spec?.let {
        LocationFix(it.latitude, it.longitude, now.minusMillis((it.ageSeconds * 1000).toLong()), it.accuracy)
    }

    private fun apply(step: MonitorStep): List<TrackerEvent> {
        state = step.state
        val events = step.events.toMutableList()
        when (val action = step.action) {
            AlarmAction.None -> {}
            is AlarmAction.Schedule -> {
                attempts += action.fireAt
                if (failNextSchedule) {
                    failNextSchedule = false
                    state = monitor.schedulingFailed(state)
                    events += TrackerEvent.AlarmSchedulingFailed(action.fireAt, "refused")
                } else {
                    scheduled += action.fireAt
                    state = monitor.confirmScheduled(state, action.fireAt)
                }
            }
            AlarmAction.Cancel -> cancelCount += 1
        }
        return events
    }
}
```

Create `android/app/src/test/java/com/trainalarm/app/tracking/JourneyMonitorOutageTest.kt` (the same scenarios and numbers as the iOS file):
```kotlin
package com.trainalarm.app.tracking

import com.trainalarm.app.model.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class JourneyMonitorOutageTest {
    private fun t(hhmmss: String) = ServiceBuilder.time(hhmmss)
    private fun happy(estimate: String? = "08:52:00"): Service = ServiceBuilder.happyPath(estimate)
    private val farDestination = Coordinate(0.0, 1.0)
    private val nearDestination = Coordinate(0.0, 0.1)

    private fun estimated(eta: String, source: EtaSource, staleness: Double, rescheduled: Boolean) =
        TrackerEvent.EtaEstimated(t(eta), source, staleness, rescheduled)

    private fun assertClose(expected: Instant, actual: Instant) {
        assertTrue("expected $expected but was $actual", Duration.between(expected, actual).abs().toMillis() <= 1)
    }

    /** start, one good poll at 08:09:00 with a fix at longitude 0, then a failed poll at 08:10:00 with a fix at 0.02. */
    private fun outageWithTwoFixes(
        h: MonitorHarness,
        firstFix: FixSpec = FixSpec(0.0, 0.0),
        secondFix: FixSpec = FixSpec(0.0, 0.02)
    ): List<TrackerEvent> {
        h.start(happy())
        h.poll(happy(), advancing = 540.0, fix = firstFix)  // now 08:09:00
        return h.fail(FailureKind.Network, advancing = 60.0, fix = secondFix)  // now 08:10:00
    }

    // Scenarios 14-17: the outage fallback

    @Test
    fun firstPollFailingReportsNoLastGoodTimeAndFallsBackToTheSnapshot() {
        val h = MonitorHarness()
        h.start(happy())
        assertEquals(
            listOf(
                TrackerEvent.FeedLost(null, FailureKind.Network),
                estimated("08:52:00", EtaSource.LAST_KNOWN_TRAJECTORY, 45.0, false)
            ),
            h.fail(FailureKind.Network)
        )
    }

    @Test
    fun outageWithoutGpsHoldsTheLastLiveEtaAndFeedLostFiresOnce() {
        val h = MonitorHarness()
        h.start(happy())
        h.poll(happy())  // good at 08:00:45

        assertEquals(
            listOf(
                TrackerEvent.FeedLost(t("08:00:45"), FailureKind.Http(503)),
                estimated("08:52:00", EtaSource.LAST_KNOWN_TRAJECTORY, 45.0, false)
            ),
            h.fail(FailureKind.Http(503))
        )
        assertEquals(
            listOf<TrackerEvent>(estimated("08:52:00", EtaSource.LAST_KNOWN_TRAJECTORY, 90.0, false)),
            h.fail(FailureKind.Http(503))
        )
        assertEquals(listOf(t("08:42:00")), h.scheduled)
    }

    @Test
    fun outageWithGpsApproachingUsesGpsPaceAndReschedules() {
        val h = MonitorHarness(coordinate = farDestination)
        val events = outageWithTwoFixes(h)

        assertEquals(2, events.size)
        assertEquals(TrackerEvent.FeedLost(t("08:09:00"), FailureKind.Network), events[0])
        val estimate = events[1] as TrackerEvent.EtaEstimated
        assertClose(t("08:59:00"), estimate.eta)
        assertEquals(EtaSource.GPS_PACE, estimate.source)
        assertEquals(60.0, estimate.staleness, 0.0)
        assertTrue(estimate.rescheduled)
        assertEquals(2, h.scheduled.size)
        assertClose(t("08:49:00"), h.scheduled[1])
    }

    @Test
    fun gpsFallbackIsNeverEarlierThanTheLastLiveEtaMinusTheGuard() {
        val h = MonitorHarness(coordinate = nearDestination)  // pace would put the ETA at 08:14:00
        val events = outageWithTwoFixes(h)

        assertEquals(
            listOf(
                TrackerEvent.FeedLost(t("08:09:00"), FailureKind.Network),
                estimated("08:51:00", EtaSource.GPS_PACE, 60.0, true)
            ),
            events
        )
        assertEquals(listOf(t("08:42:00"), t("08:41:00")), h.scheduled)
    }

    @Test
    fun withoutADestinationCoordinateTheFallbackStaysOnTheTrajectory() {
        val h = MonitorHarness(coordinate = null)
        val events = outageWithTwoFixes(h)
        assertEquals(estimated("08:52:00", EtaSource.LAST_KNOWN_TRAJECTORY, 60.0, false), events.last())
    }

    @Test
    fun inaccurateFixesAreIgnored() {
        val h = MonitorHarness(coordinate = farDestination)
        val events = outageWithTwoFixes(h, firstFix = FixSpec(0.0, 0.0, accuracy = 500.0))
        assertEquals(estimated("08:52:00", EtaSource.LAST_KNOWN_TRAJECTORY, 60.0, false), events.last())
    }

    @Test
    fun staleFixesAreIgnored() {
        val h = MonitorHarness(coordinate = farDestination)
        h.start(happy())
        h.poll(happy(), advancing = 45.0, fix = FixSpec(0.0, 0.0))
        val events = h.fail(FailureKind.Network, advancing = 300.0, fix = FixSpec(0.0, 0.02))  // the first fix is now 300s old

        assertEquals(estimated("08:52:00", EtaSource.LAST_KNOWN_TRAJECTORY, 300.0, false), events.last())
    }

    @Test
    fun aStationaryTrainBelowMinimumPaceHoldsTheLastEta() {
        val h = MonitorHarness(coordinate = farDestination)
        val events = outageWithTwoFixes(h, secondFix = FixSpec(0.0, 0.0))
        assertEquals(estimated("08:52:00", EtaSource.LAST_KNOWN_TRAJECTORY, 60.0, false), events.last())
    }

    @Test
    fun anOutageWhileTheDestinationIsLostEmitsOnlyFeedLost() {
        val h = MonitorHarness()
        h.start(happy())
        h.poll(ServiceBuilder.loadFixture("gb-nr-service-destination-dropped"))

        assertEquals(listOf<TrackerEvent>(TrackerEvent.FeedLost(t("08:00:45"), FailureKind.Network)), h.fail())
    }

    // Scenarios 18-20: recovery and flapping

    @Test
    fun recoveryWithADelayReportsRecoveryThenTheChange() {
        val h = MonitorHarness()
        h.start(happy())
        h.fail()  // 08:00:45

        assertEquals(
            listOf(
                TrackerEvent.FeedRecovered(45.0, t("08:57:00"), 300.0),
                TrackerEvent.EtaChanged(t("08:52:00"), t("08:57:00"), t("08:47:00"))
            ),
            h.poll(happy("08:57:00"))  // 08:01:30
        )
        assertFalse(h.state.feedDown)
    }

    @Test
    fun recoveryThatRevealsADroppedDestinationReportsRecoveryFirst() {
        val h = MonitorHarness()
        h.start(happy())
        h.fail()

        val events = h.poll(ServiceBuilder.loadFixture("gb-nr-service-destination-dropped"))
        assertEquals(listOf(TrackerEvent.FeedRecovered(45.0, null, null), TrackerEvent.DestinationLost(h.now)), events)
    }

    @Test
    fun flappingYieldsTwoFeedLostAndOneFeedRecovered() {
        val h = MonitorHarness()
        h.start(happy())
        val all = h.fail() + h.poll(happy()) + h.fail()

        assertEquals(2, all.count { it is TrackerEvent.FeedLost })
        assertEquals(1, all.count { it is TrackerEvent.FeedRecovered })
    }

    // Scenario 23: scheduler refusal

    @Test
    fun aSchedulerRefusalIsReportedThenRetriedOnTheNextTickWithoutANewEtaChanged() {
        val h = MonitorHarness()
        h.start(happy())
        h.failNextSchedule = true

        assertEquals(
            listOf(
                TrackerEvent.EtaChanged(t("08:52:00"), t("08:58:00"), t("08:48:00")),
                TrackerEvent.AlarmSchedulingFailed(t("08:48:00"), "refused")
            ),
            h.poll(happy("08:58:00"))
        )
        assertEquals(listOf(t("08:42:00")), h.scheduled)

        assertEquals(emptyList<TrackerEvent>(), h.poll(happy("08:58:00")))
        assertEquals(listOf(t("08:42:00"), t("08:48:00")), h.scheduled)
        assertEquals(listOf(t("08:42:00"), t("08:48:00"), t("08:48:00")), h.attempts)
        assertFalse(h.state.retryPending)
    }

    // Scenario 24: arrival

    @Test
    fun confirmedArrivalEmitsArrivedCancelsTheAlarmAndEndsTracking() {
        val h = MonitorHarness()
        h.start(happy())
        val arrived = ServiceBuilder.replacing(
            happy(), 2,
            ServiceBuilder.stop("BSK", scheduledArrival = "08:47:00", estimatedArrival = "08:50:00", hasArrived = true)
        )

        assertEquals(listOf<TrackerEvent>(TrackerEvent.Arrived(h.now.plusSeconds(45))), h.poll(arrived))
        assertEquals(1, h.cancelCount)
        assertTrue(h.state.finished)
        assertEquals(emptyList<TrackerEvent>(), h.poll(happy()))  // finished: ignored
        assertEquals(1, h.cancelCount)
        assertEquals(listOf(t("08:42:00")), h.scheduled)
    }

    @Test
    fun noArrivalReportEndsTrackingOnceTheGracePeriodHasPassed() {
        val h = MonitorHarness()
        h.start(happy())  // ETA 08:52:00, grace 10 minutes
        assertEquals(listOf<TrackerEvent>(TrackerEvent.Arrived(t("09:03:00"))), h.poll(happy(), advancing = 3780.0))
        assertEquals(1, h.cancelCount)
    }

    @Test
    fun aDestinationThatHasAlreadyArrivedEndsTrackingEvenWhenItIsMarkedTerminates() {
        // The fixture's BSK stop is TERMINATES and has an actual arrival time (12:48).
        val h = MonitorHarness(destination = "BSK", start = t("12:00:00"))
        assertEquals(
            listOf<TrackerEvent>(TrackerEvent.Arrived(t("12:00:00"))),
            h.start(ServiceBuilder.loadFixture("gb-nr-service-display-as"))
        )
        assertEquals(1, h.cancelCount)
    }
}
```

- [ ] **Step 4: Run both suites to verify they fail**

Commands as in Task 1 Step 3. Expected: both compile (the harness only uses existing APIs) and the new outage tests FAIL, because `observeFailure` ignores failures and nothing emits `feedRecovered`/`arrived`: e.g. `testFirstPollFailingReportsNoLastGoodTimeAndFallsBackToTheSnapshot` (expected two events, got none), `testFlappingYieldsTwoFeedLostAndOneFeedRecovered`, `testConfirmedArrivalEmitsArrivedCancelsTheAlarmAndEndsTracking`, `testASchedulerRefusalIsReportedThenRetriedOnTheNextTick...`. The Task 2 tests still pass. (The scheduler-refusal test already gets its first event pair; it fails only at the retry assertions if the retry branch is wrong, so it may pass early: that is fine, it pins behaviour Task 2 already implemented.)

- [ ] **Step 5: Implement on iOS**

In `ios/Sources/TrainAlarm/Tracking/JourneyMonitor.swift`, make three replacements.

1. Replace `step(_:_:)` with a version that records the fix and stamps `lastGoodAt` (fixes are sampled every tick, up or down, so the window is not empty when an outage starts):
```swift
    func step(_ state: MonitorState, _ observation: Observation) -> MonitorStep {
        guard !state.finished else { return MonitorStep(state: state, events: [], action: .none) }
        var state = state
        if let fix = observation.fix {
            state.fixes = (state.fixes + [fix]).filter {
                observation.now.timeIntervalSince($0.timestamp) <= config.maxFixAge
            }
        }
        switch observation.result {
        case .service(let service):
            state.lastGoodAt = observation.now
            return observeService(service, now: observation.now, state: state)
        case .failure(let kind):
            return observeFailure(kind, now: observation.now, state: state)
        }
    }
```

2. Replace `observeService(_:now:state:)` with (it no longer sets `lastGoodAt`; it resolves the ETA first so `feedRecovered` can report it, and ends tracking on arrival before scheduling anything):
```swift
    private func observeService(_ service: Service, now: Date, state input: MonitorState) -> MonitorStep {
        var state = input
        var events: [TrackerEvent] = []

        let destination = destinationStop(in: service)
        let newStanding = classify(destination, in: service)
        let previous = state.standing

        // The ETA is resolved first because feedRecovered reports it ahead of every other event.
        var resolved: Date? = nil
        if newStanding == .calling, let stop = destination {
            resolved = resolveEta(for: stop, state: &state)
        }

        if state.feedDown {
            let outage = now.timeIntervalSince(state.outageStartedAt ?? now)
            let delta = resolved.flatMap { fresh in state.lastFallbackEta.map { fresh.timeIntervalSince($0) } }
            events.append(.feedRecovered(outage: outage, freshEta: resolved, deltaFromFallback: delta))
            state.feedDown = false
            state.outageStartedAt = nil
            state.lastFallbackEta = nil
        }

        state.standing = newStanding
        if newStanding == .lost && previous != .lost { events.append(.destinationLost(at: now)) }
        if newStanding == .cancelled && previous != .cancelled { events.append(.serviceCancelled(at: now)) }
        guard newStanding == .calling, let stop = destination else {
            // Lost or cancelled: leave the scheduled alarm alone (cancelling it risks silence).
            return MonitorStep(state: state, events: events, action: .none)
        }

        if previous != .calling { events.append(.destinationRestored(newEta: resolved)) }
        guard let eta = resolved else {
            return MonitorStep(state: state, events: events, action: .none)
        }

        // Arrival ends tracking, before anything is scheduled.
        if stop.hasArrived || now > eta.addingTimeInterval(config.arrivalGrace) {
            events.append(.arrived(at: now))
            state.finished = true
            return MonitorStep(state: state, events: events, action: .cancel)
        }

        let previousEta = state.lastEta
        state.lastEta = eta
        let decision = decideSchedule(desired: eta.addingTimeInterval(-config.leadTime), now: now, state: state)
        state = decision.state
        if decision.announces, case .schedule(let fireAt) = decision.action {
            events.append(.etaChanged(oldEta: previousEta, newEta: eta, fireAt: fireAt))
        }
        return MonitorStep(state: state, events: events, action: decision.action)
    }
```

3. Replace the Task 2 `observeFailure` stub (and its comment) with the real outage logic and the GPS helper:
```swift
    // MARK: Failed poll

    private func observeFailure(_ kind: FailureKind, now: Date, state input: MonitorState) -> MonitorStep {
        var state = input
        var events: [TrackerEvent] = []
        if !state.feedDown {
            state.feedDown = true
            state.outageStartedAt = now
            events.append(.feedLost(lastGoodAt: state.lastGoodAt, kind: kind))
        }
        // A lost or cancelled destination has no ETA to estimate. The anchor is the last live ETA
        // (or the snapshot's), which the fallback itself never overwrites, so the guard cannot creep.
        guard state.standing == .calling, let anchor = state.lastLiveEta ?? state.lastEta else {
            return MonitorStep(state: state, events: events, action: .none)
        }

        let gps = gpsEta(now: now, state: state)
        let eta = gps.map { max($0, anchor.addingTimeInterval(-config.guardMargin)) } ?? anchor
        state.lastFallbackEta = eta
        let decision = decideSchedule(desired: eta.addingTimeInterval(-config.leadTime), now: now, state: state)
        state = decision.state
        var rescheduled = false
        if case .schedule = decision.action { rescheduled = true }
        let staleness = now.timeIntervalSince(state.lastGoodAt ?? state.startedAt ?? now)
        events.append(.etaEstimated(
            eta: eta,
            source: gps == nil ? .lastKnownTrajectory : .gpsPace,
            staleness: staleness,
            rescheduled: rescheduled
        ))
        return MonitorStep(state: state, events: events, action: decision.action)
    }

    /// Approach speed over the usable fixes (fresh and accurate enough), extrapolated to the
    /// destination. Nil when there is no coordinate, fewer than two usable fixes, or the train is
    /// not closing in faster than `minPace`.
    private func gpsEta(now: Date, state: MonitorState) -> Date? {
        guard let destination = plan.destinationCoordinate else { return nil }
        let usable = state.fixes.filter {
            now.timeIntervalSince($0.timestamp) <= config.maxFixAge && $0.accuracy <= config.maxFixAccuracy
        }
        guard usable.count >= 2,
              let oldest = usable.min(by: { $0.timestamp < $1.timestamp }),
              let newest = usable.max(by: { $0.timestamp < $1.timestamp }) else { return nil }
        let elapsed = newest.timestamp.timeIntervalSince(oldest.timestamp)
        guard elapsed > 0 else { return nil }
        let before = Geo.distanceMetres(fromLatitude: oldest.latitude, longitude: oldest.longitude,
                                        toLatitude: destination.latitude, longitude: destination.longitude)
        let after = Geo.distanceMetres(fromLatitude: newest.latitude, longitude: newest.longitude,
                                       toLatitude: destination.latitude, longitude: destination.longitude)
        let pace = (before - after) / elapsed  // approach speed, metres per second
        guard pace >= config.minPace else { return nil }
        return now.addingTimeInterval(after / pace)
    }
```

- [ ] **Step 6: Implement on Android**

In `android/app/src/main/java/com/trainalarm/app/tracking/JourneyMonitor.kt`, make four replacements.

1. Make `shifted` round instead of truncate (so a computed fallback ETA is never a millisecond early):
```kotlin
private fun Instant.shifted(bySeconds: Double): Instant = plusMillis(Math.round(bySeconds * 1000))
```

2. Replace `step(...)`:
```kotlin
    fun step(state: MonitorState, observation: Observation): MonitorStep {
        if (state.finished) return MonitorStep(state, emptyList(), AlarmAction.None)
        var current = state
        val fix = observation.fix
        if (fix != null) {
            // Sampled every tick, up or down, so the window is not empty when an outage starts.
            current = current.copy(
                fixes = (current.fixes + fix).filter { secondsBetween(it.timestamp, observation.now) <= config.maxFixAge }
            )
        }
        return when (val result = observation.result) {
            is PollResult.Success ->
                observeService(result.service, observation.now, current.copy(lastGoodAt = observation.now))
            is PollResult.Failure -> observeFailure(result.kind, observation.now, current)
        }
    }
```

3. Replace `observeService(...)` (it no longer sets `lastGoodAt`; it resolves the ETA first so `FeedRecovered` can report it, and ends tracking on arrival before scheduling anything):
```kotlin
    private fun observeService(service: Service, now: Instant, input: MonitorState): MonitorStep {
        var state = input
        val events = mutableListOf<TrackerEvent>()

        val destination = destinationStop(service)
        val newStanding = classify(destination, service)
        val previous = state.standing

        // The ETA is resolved first because FeedRecovered reports it ahead of every other event.
        var resolved: Instant? = null
        if (newStanding == Standing.CALLING && destination != null) {
            val live = destination.estimatedArrival
            resolved = when {
                live != null -> {
                    state = state.copy(sawLive = true, lastLiveEta = live)
                    live
                }
                // A live estimate that has vanished must not silently drop a known delay.
                state.sawLive && !destination.hasArrived -> state.lastEta
                else -> destination.scheduledArrival ?: state.lastEta
            }
        }

        if (state.feedDown) {
            val outage = secondsBetween(state.outageStartedAt ?: now, now)
            val fallback = state.lastFallbackEta
            val delta = if (resolved != null && fallback != null) secondsBetween(fallback, resolved) else null
            events += TrackerEvent.FeedRecovered(outage, resolved, delta)
            state = state.copy(feedDown = false, outageStartedAt = null, lastFallbackEta = null)
        }

        state = state.copy(standing = newStanding)
        if (newStanding == Standing.LOST && previous != Standing.LOST) events += TrackerEvent.DestinationLost(now)
        if (newStanding == Standing.CANCELLED && previous != Standing.CANCELLED) events += TrackerEvent.ServiceCancelled(now)
        if (newStanding != Standing.CALLING || destination == null) {
            // Lost or cancelled: leave the scheduled alarm alone (cancelling it risks silence).
            return MonitorStep(state, events, AlarmAction.None)
        }

        if (previous != Standing.CALLING) events += TrackerEvent.DestinationRestored(resolved)
        val eta = resolved ?: return MonitorStep(state, events, AlarmAction.None)

        // Arrival ends tracking, before anything is scheduled.
        if (destination.hasArrived || now.isAfter(eta.shifted(config.arrivalGrace))) {
            events += TrackerEvent.Arrived(now)
            return MonitorStep(state.copy(finished = true), events, AlarmAction.Cancel)
        }

        val previousEta = state.lastEta
        state = state.copy(lastEta = eta)
        val decision = decideSchedule(eta.shifted(-config.leadTime), now, state)
        state = decision.state
        val action = decision.action
        if (decision.announces && action is AlarmAction.Schedule) {
            events += TrackerEvent.EtaChanged(previousEta, eta, action.fireAt)
        }
        return MonitorStep(state, events, action)
    }
```

4. Replace the Task 2 `observeFailure` stub (and its comment and `@Suppress`) with the real outage logic and the GPS helper:
```kotlin
    // Failed poll

    private fun observeFailure(kind: FailureKind, now: Instant, input: MonitorState): MonitorStep {
        var state = input
        val events = mutableListOf<TrackerEvent>()
        if (!state.feedDown) {
            state = state.copy(feedDown = true, outageStartedAt = now)
            events += TrackerEvent.FeedLost(state.lastGoodAt, kind)
        }
        // A lost or cancelled destination has no ETA to estimate. The anchor is the last live ETA
        // (or the snapshot's), which the fallback itself never overwrites, so the guard cannot creep.
        val anchor = state.lastLiveEta ?: state.lastEta
        if (state.standing != Standing.CALLING || anchor == null) {
            return MonitorStep(state, events, AlarmAction.None)
        }

        val gps = gpsEta(now, state)
        val eta = if (gps != null) maxOf(gps, anchor.shifted(-config.guardMargin)) else anchor
        state = state.copy(lastFallbackEta = eta)
        val decision = decideSchedule(eta.shifted(-config.leadTime), now, state)
        state = decision.state
        val staleness = secondsBetween(state.lastGoodAt ?: state.startedAt ?: now, now)
        events += TrackerEvent.EtaEstimated(
            eta,
            if (gps != null) EtaSource.GPS_PACE else EtaSource.LAST_KNOWN_TRAJECTORY,
            staleness,
            decision.action is AlarmAction.Schedule
        )
        return MonitorStep(state, events, decision.action)
    }

    /**
     * Approach speed over the usable fixes (fresh and accurate enough), extrapolated to the
     * destination. Null when there is no coordinate, fewer than two usable fixes, or the train is
     * not closing in faster than `minPace`.
     */
    private fun gpsEta(now: Instant, state: MonitorState): Instant? {
        val destination = plan.destinationCoordinate ?: return null
        val usable = state.fixes.filter {
            secondsBetween(it.timestamp, now) <= config.maxFixAge && it.accuracy <= config.maxFixAccuracy
        }
        if (usable.size < 2) return null
        val oldest = usable.minBy { it.timestamp }
        val newest = usable.maxBy { it.timestamp }
        val elapsed = secondsBetween(oldest.timestamp, newest.timestamp)
        if (elapsed <= 0) return null
        val before = Geo.distanceMetres(oldest.latitude, oldest.longitude, destination.latitude, destination.longitude)
        val after = Geo.distanceMetres(newest.latitude, newest.longitude, destination.latitude, destination.longitude)
        val pace = (before - after) / elapsed  // approach speed, metres per second
        if (pace < config.minPace) return null
        return now.shifted(after / pace)
    }
```

- [ ] **Step 7: Run both suites to verify they pass**

Commands as in Task 1 Step 3 (run `xcodegen generate` first for the new Swift test file). Expected: iOS `TEST SUCCEEDED` with 0 failures (Task 2's 22 monitor tests still pass, plus 16 new outage tests); Android exit code 0; no warnings in either output.

- [ ] **Step 8: Commit (local only, do not push)**

```bash
git add ios android
git diff --cached --stat   # secret check (.ai/git.md): no tokens, local.properties, *.xcodeproj, signing files
git commit -m "Feature: Add JourneyMonitor outage fallback, recovery, scheduler retry and arrival

A failed poll now reports feedLost once per outage and keeps estimating an
arrival time each tick from the last live ETA or, when GPS shows the train
closing in, from approach speed, never earlier than the last live ETA minus
a guard. Recovery is reported before any change it reveals, a refused alarm
is retried without repeating the change, and arrival ends tracking.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Task 4: `JourneyTracker`: the poll loop and event stream (both platforms)

**Spec:** Driver, Events (delivery), Scenario table rows 25-26, Review Focus item 3; plus a wiring test for scenarios 1, 14, 23, 24 through the real loop.

**Files:**
- Create iOS: `Sources/TrainAlarm/Tracking/JourneyTracker.swift`; tests `Tests/TrainAlarmTests/Tracking/JourneyTrackerTests.swift`
- Create Android: `.../tracking/JourneyTracker.kt`; tests `android/app/src/test/java/com/trainalarm/app/tracking/JourneyTrackerTest.kt`

**Interfaces:**
- Consumes (Tasks 1-3): `JourneyMonitor`, `TrackerPlan`, `MonitorState`, `TrackerClock`, `LocationSource`, `AlarmScheduler`, `TrainDataProvider`, `FailureKind`, `PollResult`, `Observation`, and the test fakes `FakeClock`, `FakeProvider`, `FakeAlarmScheduler`, `FakeLocationSource`.
- Produces:
  - iOS: `actor JourneyTracker { init(plan: TrackerPlan, snapshot: Service, provider: TrainDataProvider, locationSource: LocationSource, scheduler: AlarmScheduler, clock: TrackerClock); nonisolated let events: AsyncStream<TrackerEvent>; func start(); func stop() async }`.
  - Android: `class JourneyTracker(plan, snapshot, provider, locationSource, scheduler, clock) { val events: Flow<TrackerEvent>; fun start(scope: CoroutineScope): Job; suspend fun stop() }`.
- Behaviour:
  - `start` applies `monitor.start` immediately (scheduling the timetable alarm), then loops: sleep `pollInterval` on the `TrackerClock`, sample the location source, call `provider.serviceDetails(snapshot.id, today)` once, feed the monitor, apply the action (`schedule` then `confirmScheduled`/`schedulingFailed`; `cancel` calls `scheduler.cancel()`), yield the events.
  - A provider error becomes a failed poll: `ProviderError` maps to `network` / `http(code)` / `malformed` (`notImplemented` maps to `other`), anything else to `other`. **Cancellation is never a failed poll:** a real cancellation (iOS `Task` cancelled, Android job cancelled) ends the loop; a `CancellationError`/`CancellationException` thrown by the provider while the tracker is *not* cancelled is skipped (no observation), and polling continues.
  - A scheduler error becomes `alarmSchedulingFailed` appended after the monitor's events; a cancellation during `schedule` is not reported.
  - `stop()` cancels the loop, waits for it to end, calls `scheduler.cancel()` and finishes the stream. The stream also finishes when the monitor reports arrival.

- [ ] **Step 1: Write the failing iOS tests**

Create `ios/Tests/TrainAlarmTests/Tracking/JourneyTrackerTests.swift`. `FakeClock.waitForSleepCount(n)` is the synchronisation point: the loop only sleeps again after it has finished a tick (including yielding that tick's events), so waiting for sleep `n + 1` means tick `n` is fully processed.
```swift
import XCTest
@testable import TrainAlarm

/// Collects a tracker's event stream so tests can wait for it deterministically.
final class EventCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var collected: [TrackerEvent] = []
    private var done = false
    private var task: Task<Void, Never>?

    init(_ stream: AsyncStream<TrackerEvent>) {
        task = Task { [self] in
            for await event in stream { append(event) }
            finish()
        }
    }

    private func append(_ event: TrackerEvent) { lock.lock(); collected.append(event); lock.unlock() }
    private func finish() { lock.lock(); done = true; lock.unlock() }

    var events: [TrackerEvent] { lock.lock(); defer { lock.unlock() }; return collected }
    var isFinished: Bool { lock.lock(); defer { lock.unlock() }; return done }

    func waitForCount(_ count: Int, file: StaticString = #filePath, line: UInt = #line) async {
        for _ in 0..<2000 {
            if events.count >= count { return }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("timed out waiting for \(count) events; have \(events)", file: file, line: line)
    }

    func waitUntilFinished(file: StaticString = #filePath, line: UInt = #line) async {
        for _ in 0..<2000 {
            if isFinished { return }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("timed out waiting for the stream to finish", file: file, line: line)
    }
}

final class JourneyTrackerTests: XCTestCase {
    private struct Boom: Error {}

    private func t(_ hhmmss: String) -> Date { ServiceBuilder.time(hhmmss) }
    private func happy(_ estimate: String? = "08:52:00") -> Service { ServiceBuilder.happyPath(destinationEstimate: estimate) }

    private struct Rig {
        let tracker: JourneyTracker
        let clock: FakeClock
        let provider: FakeProvider
        let scheduler: FakeAlarmScheduler
        let location: FakeLocationSource
        let events: EventCollector
    }

    private func makeRig(_ results: [Result<Service, Error>], coordinate: Coordinate? = nil) -> Rig {
        let clock = FakeClock(start: t("08:00:00"))
        let provider = FakeProvider()
        provider.results = results
        let scheduler = FakeAlarmScheduler()
        let location = FakeLocationSource()
        let plan = TrackerPlan(boardingId: "WAT", destinationId: "BSK", destinationCoordinate: coordinate,
                               config: TrackerConfig(leadTime: 600))
        let tracker = JourneyTracker(plan: plan, snapshot: happy(), provider: provider,
                                     locationSource: location, scheduler: scheduler, clock: clock)
        addTeardownBlock { await tracker.stop() }  // a failed wait must not leave the loop running
        return Rig(tracker: tracker, clock: clock, provider: provider, scheduler: scheduler,
                   location: location, events: EventCollector(tracker.events))
    }

    // MARK: Wiring through the real loop

    func testStartSchedulesTheTimetableAlarmBeforeAnyPoll() async {
        let rig = makeRig([.success(happy())])
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)
        await rig.tracker.stop()
        await rig.events.waitUntilFinished()  // every event has now been consumed

        XCTAssertEqual(rig.events.events, [.etaChanged(oldEta: nil, newEta: t("08:52:00"), fireAt: t("08:42:00"))])
        XCTAssertEqual(rig.scheduler.scheduled, [t("08:42:00")])
        XCTAssertEqual(rig.provider.calls, 0)
    }

    func testEachTickPollsTheProviderOnceAndAppliesTheChange() async {
        let rig = makeRig([.success(happy("08:57:00"))])
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)

        rig.clock.tick(advancing: 45)
        await rig.clock.waitForSleepCount(2)
        await rig.events.waitForCount(2)
        XCTAssertEqual(rig.provider.calls, 1)
        XCTAssertEqual(rig.provider.requestedIds, [ServiceBuilder.happyPathId])
        XCTAssertEqual(rig.events.events.last, .etaChanged(oldEta: t("08:52:00"), newEta: t("08:57:00"), fireAt: t("08:47:00")))
        XCTAssertEqual(rig.scheduler.scheduled, [t("08:42:00"), t("08:47:00")])

        rig.clock.tick(advancing: 45)
        await rig.clock.waitForSleepCount(3)
        XCTAssertEqual(rig.provider.calls, 2)
        await rig.tracker.stop()
        await rig.events.waitUntilFinished()
        XCTAssertEqual(rig.events.events.count, 2)  // same ETA again: nothing new
    }

    func testAProviderErrorBecomesAFailedPollWithItsKind() async {
        let rig = makeRig([.failure(ProviderError.http(503))])
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)

        rig.clock.tick(advancing: 45)
        await rig.clock.waitForSleepCount(2)
        await rig.events.waitForCount(3)

        XCTAssertEqual(Array(rig.events.events.dropFirst()), [
            .feedLost(lastGoodAt: nil, kind: .http(503)),
            .etaEstimated(eta: t("08:52:00"), source: .lastKnownTrajectory, staleness: 45, rescheduled: false),
        ])
        await rig.tracker.stop()
    }

    func testAnErrorThatIsNotAProviderErrorIsKindOtherAndPollingContinues() async {
        let rig = makeRig([.failure(Boom())])
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)

        rig.clock.tick(advancing: 45)
        await rig.clock.waitForSleepCount(2)
        rig.clock.tick(advancing: 45)
        await rig.clock.waitForSleepCount(3)
        await rig.events.waitForCount(3)

        XCTAssertEqual(rig.events.events.dropFirst().first, .feedLost(lastGoodAt: nil, kind: .other))
        XCTAssertEqual(rig.provider.calls, 2)
        await rig.tracker.stop()
    }

    func testLocationFixesAreSampledEachTickAndReachTheGpsFallback() async {
        let rig = makeRig([.success(happy()), .failure(ProviderError.network("down"))],
                          coordinate: Coordinate(latitude: 0, longitude: 1.0))
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)

        rig.location.fix = LocationFix(latitude: 0, longitude: 0.0, timestamp: t("08:00:45"), accuracy: 10)
        rig.clock.tick(advancing: 45)  // 08:00:45, good poll
        await rig.clock.waitForSleepCount(2)
        rig.location.fix = LocationFix(latitude: 0, longitude: 0.02, timestamp: t("08:01:45"), accuracy: 10)
        rig.clock.tick(advancing: 60)  // 08:01:45, failed poll: GPS ETA 08:50:45 is clamped by the guard to 08:51:00
        await rig.clock.waitForSleepCount(3)
        await rig.events.waitForCount(3)

        XCTAssertEqual(rig.events.events.last, .etaEstimated(eta: t("08:51:00"), source: .gpsPace, staleness: 60, rescheduled: true))
        XCTAssertEqual(rig.scheduler.scheduled, [t("08:42:00"), t("08:41:00")])
        await rig.tracker.stop()
    }

    func testASchedulerRefusalIsReportedThenRetriedOnTheNextTick() async {
        let rig = makeRig([.success(happy())])
        rig.scheduler.failuresRemaining = 1
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)
        await rig.events.waitForCount(2)

        guard rig.events.events.count == 2 else { return XCTFail("expected 2 events, got \(rig.events.events)") }
        XCTAssertEqual(rig.events.events[0], .etaChanged(oldEta: nil, newEta: t("08:52:00"), fireAt: t("08:42:00")))
        guard case .alarmSchedulingFailed(let fireAt, _) = rig.events.events[1] else {
            return XCTFail("expected alarmSchedulingFailed, got \(rig.events.events[1])")
        }
        XCTAssertEqual(fireAt, t("08:42:00"))
        XCTAssertEqual(rig.scheduler.scheduled, [])

        rig.clock.tick(advancing: 45)
        await rig.clock.waitForSleepCount(2)
        XCTAssertEqual(rig.scheduler.scheduled, [t("08:42:00")])
        XCTAssertEqual(rig.scheduler.attempts, [t("08:42:00"), t("08:42:00")])
        await rig.tracker.stop()
        await rig.events.waitUntilFinished()
        XCTAssertEqual(rig.events.events.count, 2)  // the retry announces nothing new
    }

    func testArrivalCancelsTheAlarmAndEndsTheStream() async {
        let arrived = ServiceBuilder.replacing(happy(), stopAt: 2, with: ServiceBuilder.stop(
            "BSK", scheduledArrival: "08:47:00", estimatedArrival: "08:50:00", hasArrived: true))
        let rig = makeRig([.success(arrived)])
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)

        rig.clock.tick(advancing: 45)
        await rig.events.waitUntilFinished()

        XCTAssertEqual(rig.events.events.last, .arrived(at: t("08:00:45")))
        XCTAssertEqual(rig.scheduler.cancelCount, 1)
        XCTAssertEqual(rig.clock.sleepCount, 1)  // never slept again
        await rig.tracker.stop()
    }

    // MARK: Scenarios 25-26: stop and cancellation

    func testStopCancelsTheAlarmAndEndsTheStreamWithoutPolling() async {
        let rig = makeRig([.success(happy())])
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)

        await rig.tracker.stop()
        await rig.events.waitUntilFinished()

        XCTAssertEqual(rig.scheduler.cancelCount, 1)
        XCTAssertEqual(rig.provider.calls, 0)
        rig.clock.tick(advancing: 45)  // nobody is sleeping any more
        XCTAssertEqual(rig.provider.calls, 0)
    }

    func testCancellationDuringTheSleepIsNotReportedAsAnOutage() async {
        let rig = makeRig([.failure(ProviderError.http(500))])  // would show up as feedLost if a poll happened
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)

        await rig.tracker.stop()
        await rig.events.waitUntilFinished()

        XCTAssertEqual(rig.events.events, [.etaChanged(oldEta: nil, newEta: t("08:52:00"), fireAt: t("08:42:00"))])
    }

    func testACancellationErrorFromTheProviderIsSkippedNotAnOutage() async {
        let rig = makeRig([.failure(CancellationError())])
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)

        rig.clock.tick(advancing: 45)
        await rig.clock.waitForSleepCount(2)  // the loop carried on and is sleeping again

        XCTAssertEqual(rig.provider.calls, 1)
        await rig.tracker.stop()
        await rig.events.waitUntilFinished()
        XCTAssertEqual(rig.events.events.count, 1)  // only the initial etaChanged; no feedLost
    }
}
```

- [ ] **Step 2: Write the failing Android tests**

Create `android/app/src/test/java/com/trainalarm/app/tracking/JourneyTrackerTest.kt`. The tests run in one `runBlocking` event loop, so the fakes and the collector need no locks; `FakeClock.awaitSleepCount(n)` is the synchronisation point (the loop only sleeps again after finishing a tick), and any "nothing more was emitted" check stops the tracker and waits for the flow to finish first, so every event has been consumed. `tracking { }` always stops the tracker, so a failed assertion cannot leave `runBlocking` waiting forever:
```kotlin
package com.trainalarm.app.tracking

import com.trainalarm.app.model.Service
import com.trainalarm.app.provider.ProviderError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Collects a tracker's event flow so tests can wait for it deterministically. */
private class EventCollector(scope: CoroutineScope, flow: Flow<TrackerEvent>) {
    val events = mutableListOf<TrackerEvent>()
    var finished = false
        private set

    init {
        scope.launch {
            flow.collect { events += it }
            finished = true
        }
    }

    suspend fun awaitCount(count: Int) = withTimeout(2_000) { while (events.size < count) yield() }

    suspend fun awaitFinished() = withTimeout(2_000) { while (!finished) yield() }
}

private class Rig(scope: CoroutineScope, results: List<Result<Service>>, coordinate: Coordinate?) {
    val clock = FakeClock(ServiceBuilder.time("08:00:00"))
    val provider = FakeProvider().also { it.results += results }
    val scheduler = FakeAlarmScheduler()
    val location = FakeLocationSource()
    val tracker = JourneyTracker(
        TrackerPlan("WAT", "BSK", coordinate, TrackerConfig(leadTime = 600.0)),
        ServiceBuilder.happyPath(), provider, location, scheduler, clock
    )
    val events = EventCollector(scope, tracker.events)
}

class JourneyTrackerTest {
    private class Boom : Exception("boom")

    private fun t(hhmmss: String) = ServiceBuilder.time(hhmmss)
    private fun happy(estimate: String? = "08:52:00"): Service = ServiceBuilder.happyPath(estimate)
    private fun etaChanged(old: String?, new: String, fire: String) =
        TrackerEvent.EtaChanged(old?.let(::t), t(new), t(fire))

    /** Starts a tracker, runs [block], and always stops the tracker afterwards. */
    private fun tracking(
        results: List<Result<Service>>,
        coordinate: Coordinate? = null,
        setup: (Rig) -> Unit = {},
        block: suspend (Rig) -> Unit
    ) = runBlocking<Unit> {
        val rig = Rig(this, results, coordinate)
        setup(rig)
        rig.tracker.start(this)
        try {
            block(rig)
        } finally {
            rig.tracker.stop()
        }
    }

    // Wiring through the real loop

    @Test
    fun startSchedulesTheTimetableAlarmBeforeAnyPoll() = tracking(listOf(Result.success(happy()))) { rig ->
        rig.clock.awaitSleepCount(1)
        rig.events.awaitCount(1)

        assertEquals(listOf<TrackerEvent>(etaChanged(null, "08:52:00", "08:42:00")), rig.events.events)
        assertEquals(listOf(t("08:42:00")), rig.scheduler.scheduled)
        assertEquals(0, rig.provider.calls)
    }

    @Test
    fun eachTickPollsTheProviderOnceAndAppliesTheChange() = tracking(listOf(Result.success(happy("08:57:00")))) { rig ->
        rig.clock.awaitSleepCount(1)

        rig.clock.tick(45.0)
        rig.clock.awaitSleepCount(2)
        rig.events.awaitCount(2)
        assertEquals(1, rig.provider.calls)
        assertEquals(listOf(ServiceBuilder.HAPPY_PATH_ID), rig.provider.requestedIds)
        assertEquals(etaChanged("08:52:00", "08:57:00", "08:47:00"), rig.events.events.last())
        assertEquals(listOf(t("08:42:00"), t("08:47:00")), rig.scheduler.scheduled)

        rig.clock.tick(45.0)
        rig.clock.awaitSleepCount(3)
        assertEquals(2, rig.provider.calls)
        rig.tracker.stop()
        rig.events.awaitFinished()
        assertEquals(2, rig.events.events.size)  // same ETA again: nothing new
    }

    @Test
    fun aProviderErrorBecomesAFailedPollWithItsKind() = tracking(listOf(Result.failure(ProviderError.Http(503)))) { rig ->
        rig.clock.awaitSleepCount(1)

        rig.clock.tick(45.0)
        rig.clock.awaitSleepCount(2)
        rig.events.awaitCount(3)

        assertEquals(
            listOf(
                TrackerEvent.FeedLost(null, FailureKind.Http(503)),
                TrackerEvent.EtaEstimated(t("08:52:00"), EtaSource.LAST_KNOWN_TRAJECTORY, 45.0, false)
            ),
            rig.events.events.drop(1)
        )
    }

    @Test
    fun anErrorThatIsNotAProviderErrorIsKindOtherAndPollingContinues() = tracking(listOf(Result.failure(Boom()))) { rig ->
        rig.clock.awaitSleepCount(1)

        rig.clock.tick(45.0)
        rig.clock.awaitSleepCount(2)
        rig.clock.tick(45.0)
        rig.clock.awaitSleepCount(3)
        rig.events.awaitCount(3)

        assertEquals(TrackerEvent.FeedLost(null, FailureKind.Other), rig.events.events[1])
        assertEquals(2, rig.provider.calls)
    }

    @Test
    fun locationFixesAreSampledEachTickAndReachTheGpsFallback() = tracking(
        listOf(Result.success(happy()), Result.failure(ProviderError.Network("down"))),
        coordinate = Coordinate(0.0, 1.0)
    ) { rig ->
        rig.clock.awaitSleepCount(1)

        rig.location.fix = LocationFix(0.0, 0.0, t("08:00:45"), 10.0)
        rig.clock.tick(45.0)  // 08:00:45, good poll
        rig.clock.awaitSleepCount(2)
        rig.location.fix = LocationFix(0.0, 0.02, t("08:01:45"), 10.0)
        rig.clock.tick(60.0)  // 08:01:45, failed poll: GPS ETA 08:50:45 is clamped by the guard to 08:51:00
        rig.clock.awaitSleepCount(3)
        rig.events.awaitCount(3)

        assertEquals(TrackerEvent.EtaEstimated(t("08:51:00"), EtaSource.GPS_PACE, 60.0, true), rig.events.events.last())
        assertEquals(listOf(t("08:42:00"), t("08:41:00")), rig.scheduler.scheduled)
    }

    @Test
    fun aSchedulerRefusalIsReportedThenRetriedOnTheNextTick() = tracking(
        listOf(Result.success(happy())),
        setup = { it.scheduler.failuresRemaining = 1 }
    ) { rig ->
        rig.clock.awaitSleepCount(1)
        rig.events.awaitCount(2)

        assertEquals(2, rig.events.events.size)
        assertEquals(etaChanged(null, "08:52:00", "08:42:00"), rig.events.events[0])
        val refusal = rig.events.events[1] as TrackerEvent.AlarmSchedulingFailed
        assertEquals(t("08:42:00"), refusal.fireAt)
        assertEquals(emptyList<java.time.Instant>(), rig.scheduler.scheduled)

        rig.clock.tick(45.0)
        rig.clock.awaitSleepCount(2)
        assertEquals(listOf(t("08:42:00")), rig.scheduler.scheduled)
        assertEquals(listOf(t("08:42:00"), t("08:42:00")), rig.scheduler.attempts)
        rig.tracker.stop()
        rig.events.awaitFinished()
        assertEquals(2, rig.events.events.size)  // the retry announces nothing new
    }

    @Test
    fun arrivalCancelsTheAlarmAndEndsTheFlow() {
        val arrived = ServiceBuilder.replacing(
            happy(), 2,
            ServiceBuilder.stop("BSK", scheduledArrival = "08:47:00", estimatedArrival = "08:50:00", hasArrived = true)
        )
        tracking(listOf(Result.success(arrived))) { rig ->
            rig.clock.awaitSleepCount(1)

            rig.clock.tick(45.0)
            rig.events.awaitFinished()

            assertEquals(TrackerEvent.Arrived(t("08:00:45")), rig.events.events.last())
            assertEquals(1, rig.scheduler.cancelCount)
            assertEquals(1, rig.clock.sleepCount)  // never slept again
        }
    }

    // Scenarios 25-26: stop and cancellation

    @Test
    fun stopCancelsTheAlarmAndEndsTheFlowWithoutPolling() = tracking(listOf(Result.success(happy()))) { rig ->
        rig.clock.awaitSleepCount(1)

        rig.tracker.stop()
        rig.events.awaitFinished()

        assertEquals(1, rig.scheduler.cancelCount)
        assertEquals(0, rig.provider.calls)
        rig.clock.tick(45.0)  // nobody is sleeping any more
        yield()
        assertEquals(0, rig.provider.calls)
    }

    @Test
    fun cancellationDuringTheSleepIsNotReportedAsAnOutage() = tracking(listOf(Result.failure(ProviderError.Http(500)))) { rig ->
        rig.clock.awaitSleepCount(1)  // a poll would show up as feedLost

        rig.tracker.stop()
        rig.events.awaitFinished()

        assertEquals(listOf<TrackerEvent>(etaChanged(null, "08:52:00", "08:42:00")), rig.events.events)
    }

    @Test
    fun aCancellationExceptionFromTheProviderIsSkippedNotAnOutage() = tracking(
        listOf(Result.failure(CancellationException("timeout")))
    ) { rig ->
        rig.clock.awaitSleepCount(1)

        rig.clock.tick(45.0)
        rig.clock.awaitSleepCount(2)  // the loop carried on and is sleeping again

        assertEquals(1, rig.provider.calls)
        rig.tracker.stop()
        rig.events.awaitFinished()
        assertEquals(1, rig.events.events.size)  // only the initial EtaChanged; no FeedLost
    }
}
```

- [ ] **Step 3: Run both suites to verify they fail**

Commands as in Task 1 Step 3 (run `xcodegen generate` first for the new Swift file). Expected: both FAIL to compile: iOS `cannot find 'JourneyTracker' in scope`; Android `Unresolved reference: JourneyTracker`.

- [ ] **Step 4: Implement the iOS tracker**

Create `ios/Sources/TrainAlarm/Tracking/JourneyTracker.swift`:
```swift
import Foundation

/// The thin driver around the pure `JourneyMonitor`: it owns the poll loop, applies the
/// monitor's alarm actions through the injected scheduler, and publishes the monitor's events on
/// a stream. All decisions live in `JourneyMonitor`; this type only does I/O and timing.
actor JourneyTracker {
    /// Events in the order they happened. Finishes when the journey ends (arrival) or on `stop()`.
    nonisolated let events: AsyncStream<TrackerEvent>

    private let continuation: AsyncStream<TrackerEvent>.Continuation
    private let monitor: JourneyMonitor
    private let snapshot: Service
    private let provider: TrainDataProvider
    private let locationSource: LocationSource
    private let scheduler: AlarmScheduler
    private let clock: TrackerClock
    private var state = MonitorState()
    private var loop: Task<Void, Never>?

    init(
        plan: TrackerPlan,
        snapshot: Service,
        provider: TrainDataProvider,
        locationSource: LocationSource,
        scheduler: AlarmScheduler,
        clock: TrackerClock
    ) {
        let (stream, continuation) = AsyncStream.makeStream(of: TrackerEvent.self)
        self.events = stream
        self.continuation = continuation
        self.monitor = JourneyMonitor(plan: plan)
        self.snapshot = snapshot
        self.provider = provider
        self.locationSource = locationSource
        self.scheduler = scheduler
        self.clock = clock
    }

    /// Schedules the timetable-based alarm from the snapshot, then polls every `pollInterval`
    /// until arrival or `stop()`.
    func start() {
        guard loop == nil else { return }
        loop = Task { await self.run() }
    }

    /// Stops polling, cancels the alarm and finishes the event stream. Safe to call more than once.
    func stop() async {
        loop?.cancel()
        await loop?.value
        loop = nil
        await scheduler.cancel()
        continuation.finish()
    }

    private func run() async {
        await apply(monitor.start(snapshot: snapshot, now: clock.now()))
        while !state.finished && !Task.isCancelled {
            do {
                try await clock.sleep(for: monitor.plan.config.pollInterval)
            } catch {
                break  // cancelled while sleeping: not an outage
            }
            await tick()
        }
        continuation.finish()
    }

    private func tick() async {
        let fix = locationSource.latestFix()
        let result: PollResult
        do {
            result = .service(try await provider.serviceDetails(serviceId: snapshot.id, date: clock.now()))
        } catch is CancellationError {
            return  // stop() was called, or the request was cancelled: not an outage
        } catch let error as ProviderError {
            result = .failure(Self.failureKind(of: error))
        } catch {
            result = .failure(.other)
        }
        guard !Task.isCancelled else { return }
        await apply(monitor.step(state, Observation(result: result, now: clock.now(), fix: fix)))
    }

    private static func failureKind(of error: ProviderError) -> FailureKind {
        switch error {
        case .network: return .network
        case .http(let code): return .http(code)
        case .malformed: return .malformed
        case .notImplemented: return .other
        }
    }

    private func apply(_ step: MonitorStep) async {
        state = step.state
        var pending = step.events
        switch step.action {
        case .none:
            break
        case .schedule(let fireAt):
            do {
                try await scheduler.schedule(fireAt: fireAt)
                state = monitor.confirmScheduled(state, fireAt: fireAt)
            } catch is CancellationError {
                return
            } catch {
                state = monitor.schedulingFailed(state)
                pending.append(.alarmSchedulingFailed(fireAt: fireAt, message: String(describing: error)))
            }
        case .cancel:
            await scheduler.cancel()
        }
        for event in pending { continuation.yield(event) }
    }
}
```

- [ ] **Step 5: Implement the Android tracker**

Create `android/app/src/main/java/com/trainalarm/app/tracking/JourneyTracker.kt`:
```kotlin
package com.trainalarm.app.tracking

import com.trainalarm.app.alarm.AlarmScheduler
import com.trainalarm.app.model.Service
import com.trainalarm.app.provider.ProviderError
import com.trainalarm.app.provider.TrainDataProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.ZoneOffset

/**
 * The thin driver around the pure [JourneyMonitor]: it owns the poll loop, applies the monitor's
 * alarm actions through the injected scheduler, and publishes the monitor's events on a flow.
 * All decisions live in [JourneyMonitor]; this class only does I/O and timing.
 */
class JourneyTracker(
    plan: TrackerPlan,
    private val snapshot: Service,
    private val provider: TrainDataProvider,
    private val locationSource: LocationSource,
    private val scheduler: AlarmScheduler,
    private val clock: TrackerClock
) {
    private val monitor = JourneyMonitor(plan)
    private val pollInterval = plan.config.pollInterval
    private val channel = Channel<TrackerEvent>(Channel.UNLIMITED)

    /** Events in the order they happened. Completes on arrival or [stop]. Collect it once. */
    val events: Flow<TrackerEvent> = channel.receiveAsFlow()

    private var state = MonitorState()
    private var job: Job? = null

    /**
     * Schedules the timetable-based alarm from the snapshot, then polls every `pollInterval`
     * until arrival or [stop]. The loop runs in [scope].
     */
    fun start(scope: CoroutineScope): Job {
        job?.let { return it }
        return scope.launch { run() }.also { job = it }
    }

    /** Stops polling, cancels the alarm and completes the event flow. Safe to call more than once. */
    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        scheduler.cancel()
        channel.close()
    }

    private suspend fun run() {
        try {
            apply(monitor.start(snapshot, clock.now()))
            while (!state.finished) {
                clock.sleep(pollInterval)  // a cancellation here ends the loop: not an outage
                tick()
            }
        } finally {
            channel.close()
        }
    }

    private suspend fun tick() {
        val fix = locationSource.latestFix()
        val result: PollResult = try {
            PollResult.Success(provider.serviceDetails(snapshot.id, clock.now().atZone(ZoneOffset.UTC).toLocalDate()))
        } catch (e: CancellationException) {
            // A real cancellation (stop()) must propagate; anything else (e.g. a timeout) is not an outage.
            currentCoroutineContext().ensureActive()
            return
        } catch (e: ProviderError) {
            PollResult.Failure(failureKind(e))
        } catch (e: Exception) {
            PollResult.Failure(FailureKind.Other)
        }
        currentCoroutineContext().ensureActive()
        apply(monitor.step(state, Observation(result, clock.now(), fix)))
    }

    private fun failureKind(error: ProviderError): FailureKind = when (error) {
        is ProviderError.Network -> FailureKind.Network
        is ProviderError.Http -> FailureKind.Http(error.code)
        is ProviderError.Malformed -> FailureKind.Malformed
        is ProviderError.NotImplemented -> FailureKind.Other
    }

    private suspend fun apply(step: MonitorStep) {
        state = step.state
        val pending = step.events.toMutableList()
        when (val action = step.action) {
            AlarmAction.None -> {}
            is AlarmAction.Schedule -> try {
                scheduler.schedule(action.fireAt)
                state = monitor.confirmScheduled(state, action.fireAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = monitor.schedulingFailed(state)
                pending += TrackerEvent.AlarmSchedulingFailed(action.fireAt, e.message ?: e.toString())
            }
            AlarmAction.Cancel -> scheduler.cancel()
        }
        pending.forEach { channel.trySend(it) }
    }
}
```

- [ ] **Step 6: Run both suites to verify they pass**

Commands as in Task 1 Step 3 (run `xcodegen generate` first). Expected: iOS `TEST SUCCEEDED` with 0 failures (10 new `JourneyTrackerTests`); Android exit code 0; no warnings in either output; the new tests finish in well under a second each (a test that waits the full 2-second timeout means a real hang, so investigate it).

- [ ] **Step 7: Commit (local only, do not push)**

```bash
git add ios android
git diff --cached --stat   # secret check (.ai/git.md): no tokens, local.properties, *.xcodeproj, signing files
git commit -m "Feature: Add JourneyTracker poll loop and event stream

The thin driver around JourneyMonitor on both platforms: schedules the
timetable alarm at start, polls the provider every interval, samples the
location source, applies the monitor's alarm actions, and publishes its
events. Provider errors become failed polls with a failure kind;
cancellation never does; stop() cancels the alarm and ends the stream.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Task 5: Docs, parity check and final verification

**Spec:** "Docs to update in the plan's final task" and the acceptance criterion ("both platforms' scenario lists are identical, all unit-tested, CI green"). Docs describe what the code now does and claim no more.

**Files:**
- Modify: `docs/spec.md` (§4 line ~70, §8 Stage 2 line ~136), `.ai/architecture.md` (line 11 and lines 30-31), `.ai/testing-guide.md` (Shared rules and `## TBD`), `README.md` (Status), `ios/Sources/TrainAlarm/Tracking/README.md`, `android/app/src/main/java/com/trainalarm/app/tracking/README.md`, `ios/Sources/TrainAlarm/Alarm/README.md`, `android/app/src/main/java/com/trainalarm/app/alarm/README.md`
- No `CHANGELOG.md` exists in this repo, so none is created.

**Interfaces:** consumes everything from Tasks 1-4; produces docs only.

- [ ] **Step 1: Update the docs**

Before editing each file, view the lines named and confirm they match the "old text"; if a line number is slightly off, edit by the quoted text and say so in your report. If the real text differs materially, stop and report NEEDS_CONTEXT.

`docs/spec.md` §4: replace
```
  the notification, but only on a meaningful shift (~>60s), to avoid churn.
```
with
```
  the notification, but only on a meaningful shift (60s or more, measured
  against the currently scheduled time), to avoid churn.
```

`docs/spec.md` §8: replace the single line
```
2. Tracking, ETA & live-change logic — no UI, tested against fixtures.
```
with
```
2. Tracking, ETA & live-change logic — DONE. Ships when: on both platforms a
   `JourneyTracker` driven by fixture-based fakes (provider, clock, location
   source, alarm scheduler) produces the expected events and scheduler calls
   for delay, drop-out, cancellation, outage with and without GPS, recovery,
   flapping, the past-fire clamp, scheduler refusal and arrival, including the
   60s reschedule threshold; all unit-tested, no UI. The pure `JourneyMonitor`
   holds the logic; `JourneyTracker` is the poll loop and event stream.
   `AlarmScheduler` is only a protocol until Stage 3. Not done: the real clock
   and GPS plumbing and the app wiring (Stages 3-4), and background execution
   (Stage 5). On Android a blocking `HttpURLConnection` read cannot be
   interrupted, so `stop()` can lag by up to the 10s timeout. Once an alarm's
   scheduled time has passed, a later slip never re-arms it. Tracking ends
   itself only on arrival: while the destination is lost or cancelled or the
   feed is down, the caller ends it with `stop()`.
```

`.ai/architecture.md`: replace
```
| Tracking, ETA, live-change logic | `Tracking/` | `tracking/` | 2 |
| Alarm delivery | `Alarm/` | `alarm/` | 3 |
```
with
```
| Tracking, ETA, live-change logic | `Tracking/` | `tracking/` | 2 (done) |
| Alarm delivery (`AlarmScheduler` protocol exists; the real implementation is Stage 3) | `Alarm/` | `alarm/` | 3 |
```
In the same file, change the folder-tree line `│   └── Tests/TrainAlarmTests/{Model,Provider,Fixtures}/` to `│   └── Tests/TrainAlarmTests/{Model,Provider,Tracking,Fixtures}/`.

`.ai/testing-guide.md`: in "Shared rules", after the "Provider network tests" bullet, add
```
- **Tracker tests** use hand-written fakes only: `FakeClock`, `FakeProvider`,
  `FakeAlarmScheduler`, `FakeLocationSource`, with `ServiceBuilder` scripting poll
  sequences (from the shared fixtures or built to match them). The pure
  `JourneyMonitor` is tested through `MonitorHarness`; `JourneyTracker` through the fakes,
  synchronising on `FakeClock.waitForSleepCount` / `awaitSleepCount`. Android uses
  `runBlocking` and no `kotlinx-coroutines-test`; iOS uses an `AsyncStream` and a
  continuation-based fake clock. The iOS and Android test lists are the same.
```
and delete the `## TBD` bullet `- Mocking approach for \`TrainDataProvider\` itself (Stage 2 will use hand-written fakes).` (decided: hand-written fakes).

`README.md` Status: replace
```
Stage 1 done (journey model, UK provider, station data); Stage 2 (tracking,
ETA and live-change logic) is next. See `docs/spec.md` §8 for the full stage
list.
```
with
```
Stages 1-2 done (journey model, UK provider, station data; tracking, ETA and
live-change logic, with no UI yet); Stage 3 (alarm delivery spike, real device
only) is next. See `docs/spec.md` §8 for the full stage list.
```

Both `Tracking/README.md` and `tracking/README.md` currently read `Stage 2: polling loop, ETA recompute, delay/diversion/cancellation handling. See docs/spec.md §4.` Replace that line in each with:
```
Stage 2 (done): `JourneyMonitor` (pure ETA, fire-time, live-change and outage logic) and `JourneyTracker` (poll loop and event stream). Depends only on `TrainDataProvider` and the model types. Tracking ends itself only on arrival; otherwise (destination lost or cancelled, feed down) the caller ends it with `stop()`. See docs/spec.md §4.
```
In both `Alarm/README.md` and `alarm/README.md`, append a blank line and then this second paragraph:
```
`AlarmScheduler` (protocol) is defined here for Stage 2's tracker; the real implementation is Stage 3.
```

- [ ] **Step 2: Check the iOS and Android test lists are the same**

Run from the repo root (if the shell refuses the loop, run the two greps per pair separately):
```bash
for pair in GeoTests:GeoTest TrackerConfigTests:TrackerConfigTest ServiceBuilderTests:ServiceBuilderTest TrackingFakesTests:TrackingFakesTest JourneyMonitorTests:JourneyMonitorTest JourneyMonitorOutageTests:JourneyMonitorOutageTest JourneyTrackerTests:JourneyTrackerTest; do
  ios=${pair%%:*}; and=${pair##*:}
  echo "$ios: $(grep -c 'func test' ios/Tests/TrainAlarmTests/Tracking/$ios.swift) ios / $(grep -c '@Test' android/app/src/test/java/com/trainalarm/app/tracking/$and.kt) android"
done
```
Expected (both numbers equal on every line): `GeoTests: 1 / 1`, `TrackerConfigTests: 2 / 2`, `ServiceBuilderTests: 2 / 2`, `TrackingFakesTests: 4 / 4`, `JourneyMonitorTests: 22 / 22`, `JourneyMonitorOutageTests: 16 / 16`, `JourneyTrackerTests: 10 / 10`.

Then confirm every row of the spec's scenario table has a test. In your report, write this mapping with the actual test names you find (iOS name / Android name):

| # | Scenario | Test file |
|---|---|---|
| 1-5 | steady, 30s, exactly 60s, drift, delay-and-back | `JourneyMonitorTests` |
| 6-10 | whole-service cancelled, destination absent, diverted/cancelled/pass, departure-only cancelled, arrival cancelled | `JourneyMonitorTests` |
| 11-13 | lost then restored, nil `bestArrival`, vanished estimate | `JourneyMonitorTests` |
| 14-17 | first poll fails, outage without GPS, outage with GPS (and guard), no destination coordinate | `JourneyMonitorOutageTests` |
| 18-20 | recovery with delay, recovery reveals drop, flapping | `JourneyMonitorOutageTests` |
| 21-22 | past fire time at start, ETA closer than the lead time | `JourneyMonitorTests` |
| 23 | scheduler refusal and retry | `JourneyMonitorOutageTests` and `JourneyTrackerTests` |
| 24 | arrival | `JourneyMonitorOutageTests` and `JourneyTrackerTests` |
| 25-26 | `stop()`, cancellation during sleep | `JourneyTrackerTests` |

- [ ] **Step 3: Final verification (fresh evidence for every claim)**

```bash
# no leftover placeholders or debug output in the new code
grep -rnE "TODO|FIXME|print\(|println\(" ios/Sources/TrainAlarm/Tracking android/app/src/main/java/com/trainalarm/app/tracking ios/Sources/TrainAlarm/Alarm android/app/src/main/java/com/trainalarm/app/alarm --include='*.swift' --include='*.kt'
# tracking never reaches for RTT types
grep -rnE "RttProvider|RttMapper|StationDirectory" ios/Sources/TrainAlarm/Tracking android/app/src/main/java/com/trainalarm/app/tracking
# full suites, both platforms
cd ios && xcodegen generate && xcodebuild test -project TrainAlarm.xcodeproj -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17 Pro" 2>&1 | grep -E "error:|warning:|Executed|TEST (FAILED|SUCCEEDED)" | grep -v appintentsmetadataprocessor
cd ../android && ANDROID_HOME=/Users/Xavier/Library/Android/sdk ./gradlew test assembleDebug --offline -q; echo "android exit: $?"
# -q hides Kotlin warnings, so force a recompile of main and test sources and look for them
ANDROID_HOME=/Users/Xavier/Library/Android/sdk ./gradlew compileDebugKotlin compileDebugUnitTestKotlin --offline --rerun-tasks 2>&1 | grep -E "^(w|e): "
```
Then, as separate commands: `git status --short` (must be clean except the doc edits) and `git log --oneline main..HEAD`. Expected: the two source greps and the Android warnings grep print nothing; iOS `TEST SUCCEEDED` with 0 failures and no warnings or errors (the `appintentsmetadataprocessor ... No AppIntents.framework dependency found` notice appears on every build of this project and is excluded by the grep); `android exit: 0`; the log lists the spec commits, the merge of `main`, the plan commit, and the Task 1-4 commits.

- [ ] **Step 4: Commit (local only, do not push)**

```bash
git add docs/spec.md .ai README.md ios/Sources android/app/src/main
git diff --cached --stat   # secret check (.ai/git.md): only the eight doc files; no tokens, local.properties, *.xcodeproj, signing files
git commit -m "Docs: Mark Stage 2 done and describe the tracker's tests and layers

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```
