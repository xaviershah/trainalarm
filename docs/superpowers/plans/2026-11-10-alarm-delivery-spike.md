# Stage 3 — Alarm delivery spike Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Real `AlarmScheduler` implementations on iOS (AlarmKit) and Android (exact alarm + foreground service), debug harnesses, and two tracker fixes, so an alarm can be proven on a real device.

**Architecture:** Each platform's scheduler sits behind a small fakeable seam (`AlarmKitClient` / `ExactAlarmGateway`) so logic is unit-tested without a device; the OS-facing parts (AlarmKit, receiver, service, activity) are verified by build, lint and the device checklists. Debug-only harnesses exercise the real path.

**Tech Stack:** Swift 5 / XCTest / AlarmKit (iOS 26+); Kotlin 2.0.20 / JUnit 4 / AlarmManager / foreground service (minSdk 29, target 35).

**Spec:** `docs/superpowers/specs/2026-11-10-alarm-delivery-spike-design.md` (read it first; section numbers below refer to it).

## Global Constraints

- Build every feature on both platforms, in the matching layer (`ios/Sources/TrainAlarm/Alarm/`, `android/app/src/main/java/com/trainalarm/app/alarm/`).
- Native only; no shared cross-platform layer. Do not invent RTT shapes or station data (not touched here).
- Do not add a key to `TrainAlarm.entitlements` (`ios/CLAUDE.md`). `NSAlarmKitUsageDescription` goes in `ios/project.yml` Info.plist properties only.
- A simulator or emulator result is not a pass for alarm delivery; unit tests prove logic only.
- iOS: Swift 5 language mode, deployment target iOS 26.0; commands per `.ai/testing-guide.md` (`xcodegen generate`, then `xcodebuild test`; pick an installed iPhone simulator with `xcrun simctl list devices available iPhone`).
- Android: `minSdk 29`, `targetSdk/compileSdk 35`, foreground service type `mediaPlayback`; commands `./gradlew test assembleDebug lint` from `android/`. `lint` is a local-only gate (CI does not run it). In this worktree `android/local.properties` is absent, so prefix **every** Gradle call with `ANDROID_HOME=$HOME/Library/Android/sdk` (see `.ai/testing-guide.md`), and add `--offline` only if it works (drop it for `lint` if lint's artifacts are not cached).
- No new third-party dependencies. No Robolectric.
- Do not modify `.github/workflows/*`. Adding `lint` to Android CI is a separate change; the user asked to be reminded after Stage 3 (project memory `android-lint-not-in-ci`).
- Secret check before every commit (`.ai/git.md`): run `git diff --cached` and confirm no token, key or signing material, and that no `DEVELOPMENT_TEAM` or `android/local.properties` is staged. The device team id never goes in `ios/project.yml`.
- Commit messages: `<Type>: <Short description>` per `.ai/git.md`, ending with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- When `superpowers:subagent-driven-development` finishes and invokes `superpowers:finishing-a-development-branch`, let it run its verification steps but **do not present the following options menu**:
  ```
  1. Merge back to <base-branch> locally
  2. Push and create a Pull Request
  3. Keep the branch as-is (I'll handle it later)
  ```
  Instead, proceed to Step 12 of `gl-starting-a-feature`. Step 12 runs in full, then Step 13 handles the finishing sequence (delete spec+plan → squash implementation commits → push → exit worktree → open the PR).
- Do not push during implementation. Every task commits locally only, and every platform is verified locally (build and tests). The first push of implementation work happens in Step 13, after the squash, so CI runs once on the final commits. If a platform genuinely cannot be verified locally, stop and tell the user rather than pushing the PR branch to find out.
- Helper agents: Sonnet only. Never Opus without asking the user.

## Review Focus

Inputs the spec implies that a user will hit (each has a test in the owning task):

1. A fire time already past or exactly now (late boarding; tracker clamps to `now`) — Tasks 3, 5.
2. Scheduling twice in a row, and a second schedule that fails — the first alarm must survive — Tasks 3, 5.
3. Authorization / exact-alarm permission revoked between the check and the call — Task 3 (a client error propagates), Task 5 (`SecurityException` mapped to the typed error).
4. `cancel()` with nothing scheduled, and `cancel()` twice — Tasks 3, 5.
5. A stored alarm id that no longer exists (reinstall, data cleared) — cancel errors must not surface — Task 3.

## Task summary

| # | Task | Files touched | What it proves |
|---|------|---------------|----------------|
| 1 | iOS tracker: `start()` after `stop()` is a no-op | `JourneyTracker.swift`, `JourneyTrackerTests.swift` | No alarm re-armed into a closed stream |
| 2 | Android tracker: same guard + guarded `cancel()` | `JourneyTracker.kt`, `TrackingFakes.kt`, `JourneyTrackerTest.kt` | `cancel()` throwing can't crash the tracker or leave the flow open |
| 3 | iOS `AlarmKitScheduler` + seam + Info.plist key | `Alarm/*.swift`, `project.yml`, `AlarmKitSchedulerTests.swift` | Authorization flow, min lead, make-before-break, cancel safety |
| 4 | iOS debug harness | `App/DevAlarmView.swift`, `ContentView.swift` | App builds with the real client wired; used on the iPhone |
| 5 | Android `ExactAlarmScheduler` + gateway seam | `alarm/*.kt`, `ExactAlarmSchedulerTest.kt` | Permission gate, min lead, `SecurityException` mapping |
| 6 | Android delivery components: gateway, receiver, player, notification, service, activity, manifest | `alarm/*.kt`, `AndroidManifest.xml`, `res/` | Builds, lints clean, merged manifest correct; delivery itself unproven until a phone |
| 7 | Android permissions helper and debug harness | `alarm/AlarmPermissions.kt`, `alarm/DevAlarmScreen.kt`, `MainActivity.kt` | Debug-only screen builds; used on a phone later |
| 8 | iPhone device run (user-in-the-loop) | scratch notes only | The success criterion on a real device |
| 9 | Docs and stage status | `docs/spec.md`, `.ai/*`, READMEs, comments | Docs match the code and the device results; Stage 3 status rule applied |

## File map

- iOS create: `Alarm/AlarmKitClient.swift` (protocol + `SystemAlarmKitClient`), `Alarm/AlarmKitScheduler.swift`, `Alarm/AlarmSchedulerError.swift`, `App/DevAlarmView.swift`, `Tests/.../Alarm/AlarmKitSchedulerTests.swift`.
- iOS modify: `Tracking/JourneyTracker.swift`, `App/ContentView.swift`, `project.yml`, `Tracking` tests.
- Android create (all under `alarm/`): `AlarmSchedulerException.kt`, `ExactAlarmGateway.kt`, `ExactAlarmScheduler.kt`, `SystemExactAlarmGateway.kt`, `AlarmReceiver.kt`, `AlarmPlayer.kt`, `AlarmNotifications.kt`, `AlarmService.kt`, `AlarmActivity.kt`, `AlarmPermissions.kt`, `DevAlarmScreen.kt`; test `ExactAlarmSchedulerTest.kt`.
- Android modify: `tracking/JourneyTracker.kt`, `AndroidManifest.xml`, `MainActivity.kt`, `res/values/strings.xml`, test `TrackingFakes.kt`, `JourneyTrackerTest.kt`.

**Spec deviations (the agreed spec stays unchanged; they are recorded here and must go in the PR description at Step 13):** (1) The tracker is pure JVM-tested Kotlin and `android.util.Log` is a stub in unit tests, so the Android tracker reports a failed `cancel()` through an injectable `onAlarmError: (String, Throwable) -> Unit` constructor parameter (default no-op). Nothing constructs a tracker in the app until Stage 4, which will pass a `Log.w` lambda. (2) `ExactAlarmGateway.cancel()` is split into `cancelAlarm()` and `stopRinging()` so the JVM test can assert both halves of the scheduler's `cancel()`; the iOS client property is `authorization` (spec: `authorizationState`). (3) Android alarm logging uses `android.util.Log` only in the OS-facing classes (receiver, service, player, gateway), never in `ExactAlarmScheduler` or the tracker, because `Log` throws in JVM unit tests.

---

### Task 1: iOS tracker — `start()` after `stop()` does nothing

**Files:**
- Modify: `ios/Sources/TrainAlarm/Tracking/JourneyTracker.swift:41-53`
- Test: `ios/Tests/TrainAlarmTests/Tracking/JourneyTrackerTests.swift` (add after `testStopCancelsTheAlarmAndEndsTheStreamWithoutPolling`)

**Interfaces:** Produces: `JourneyTracker.start()` becomes a no-op once `stop()` has run. No signature change.

- [ ] **Step 1: Write the failing test**

```swift
    func testStartAfterStopDoesNothing() async {
        let rig = makeRig([.success(happy())])
        await rig.tracker.start()
        await rig.clock.waitForSleepCount(1)
        await rig.tracker.stop()
        await rig.events.waitUntilFinished()
        let attempts = rig.scheduler.attempts.count

        await rig.tracker.start()  // a stopped tracker is single-use
        try? await Task.sleep(nanoseconds: 50_000_000)

        XCTAssertEqual(rig.scheduler.attempts.count, attempts)
        XCTAssertEqual(rig.clock.sleepCount, 1)
    }
```

- [ ] **Step 2: Run it to see it fail**

Run (from `ios/`): `xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=<installed iPhone>" -only-testing:TrainAlarmTests/JourneyTrackerTests/testStartAfterStopDoesNothing`
Expected: FAIL (`attempts` grew: the second `start()` re-armed the alarm).

- [ ] **Step 3: Implement** — in `JourneyTracker.swift` add `private var stopped = false` below `private var loop: Task<Void, Never>?`, then change `start()` and `stop()`:

```swift
    func start() {
        guard loop == nil, !stopped else { return }
        loop = Task { await self.run() }
    }

    /// Stops polling, cancels the alarm and finishes the event stream. Safe to call more than once.
    /// A stopped tracker is single-use: `start()` does nothing afterwards.
    func stop() async {
        stopped = true
        loop?.cancel()
        await loop?.value
        loop = nil
        await scheduler.cancel()
        continuation.finish()
    }
```
Also update the `start()` doc comment to add: "Does nothing after `stop()`."

- [ ] **Step 4: Run the whole iOS test suite** — `xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=<installed iPhone>"`. Expected: all PASS.

- [ ] **Step 5: Commit** — `git add ios && git commit -m "BugFix: Make iOS tracker start() a no-op after stop()"` (with the Co-Authored-By trailer).

---

### Task 2: Android tracker — same guard, and a guarded `cancel()`

**Files:**
- Modify: `android/app/src/main/java/com/trainalarm/app/tracking/JourneyTracker.kt`
- Modify: `android/app/src/test/java/com/trainalarm/app/tracking/TrackingFakes.kt:48-68`
- Test: `android/app/src/test/java/com/trainalarm/app/tracking/JourneyTrackerTest.kt` (add after `stopCancelsTheAlarmAndEndsTheFlowWithoutPolling`)

**Interfaces:** Produces: `JourneyTracker(..., clock, onAlarmError: (String, Throwable) -> Unit = { _, _ -> })` (new trailing parameter with default); `FakeAlarmScheduler.cancelFailure: Exception?`.

- [ ] **Step 0: Baseline (once, before any Android change)** — from `android/`: `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew test assembleDebug lint` on the untouched tree. Record whether `lint` is already clean. If lint already reports errors, stop and tell the user instead of fixing unrelated findings (CI has never run lint).

- [ ] **Step 1: Extend the fake** — in `FakeAlarmScheduler` add `var cancelFailure: Exception? = null` and change `cancel()` to:

```kotlin
    override suspend fun cancel() {
        cancelCount += 1
        cancelFailure?.let { throw it }
    }
```

- [ ] **Step 2: Write the failing tests** (add imports `kotlinx.coroutines.Job`, `kotlinx.coroutines.cancel`, `kotlinx.coroutines.currentCoroutineContext`, `org.junit.Assert.assertTrue`):

```kotlin
    @Test
    fun startAfterStopDoesNothing() = tracking(listOf(Result.success(happy()))) { rig ->
        rig.clock.awaitSleepCount(1)
        rig.tracker.stop()
        rig.events.awaitFinished()
        val attempts = rig.scheduler.attempts.size

        val scope = CoroutineScope(currentCoroutineContext() + Job())
        val job = rig.tracker.start(scope)  // a stopped tracker is single-use
        yield()
        yield()

        assertEquals(attempts, rig.scheduler.attempts.size)
        assertTrue(job.isCompleted)
        scope.cancel()
    }

    @Test
    fun aThrowingCancelOnArrivalDoesNotEscapeTheLoop() {
        val arrived = ServiceBuilder.replacing(
            happy(), 2,
            ServiceBuilder.stop("BSK", scheduledArrival = "08:47:00", estimatedArrival = "08:50:00", hasArrived = true)
        )
        tracking(listOf(Result.success(arrived)), setup = { it.scheduler.cancelFailure = Boom() }) { rig ->
            rig.clock.awaitSleepCount(1)
            rig.clock.tick(45.0)
            rig.events.awaitFinished()

            assertEquals(TrackerEvent.Arrived(t("08:00:45")), rig.events.events.last())
            assertEquals(1, rig.scheduler.cancelCount)
        }
    }

    @Test
    fun stopStillClosesTheFlowWhenCancelThrows() = tracking(
        listOf(Result.success(happy())), setup = { it.scheduler.cancelFailure = Boom() }
    ) { rig ->
        rig.clock.awaitSleepCount(1)
        rig.tracker.stop()  // must not throw
        rig.events.awaitFinished()
        assertEquals(1, rig.scheduler.cancelCount)
    }
```

- [ ] **Step 3: Run to see them fail** — from `android/`: `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew --offline :app:testDebugUnitTest --tests "com.trainalarm.app.tracking.JourneyTrackerTest"` (the aggregate `test` task rejects `--tests`). Expected: `startAfterStopDoesNothing` FAILS (attempts grew), the arrival test FAILS (exception escapes `run()`), the stop test FAILS (`stop()` throws; flow never closes).

- [ ] **Step 4: Implement** in `JourneyTracker.kt`. Add the constructor parameter `private val onAlarmError: (String, Throwable) -> Unit = { _, _ -> }` after `clock`, add `private var stopped = false`, and replace `start`, `stop`, and the `AlarmAction.Cancel` branch:

```kotlin
    fun start(scope: CoroutineScope): Job {
        if (stopped) return Job().also { it.complete() }  // a stopped tracker is single-use
        job?.let { return it }
        return scope.launch { run() }.also { job = it }
    }

    /** Stops polling, cancels the alarm and completes the event flow. Safe to call more than once. */
    suspend fun stop() {
        stopped = true
        job?.cancelAndJoin()
        job = null
        try {
            cancelAlarm()
        } finally {
            channel.close()
        }
    }

    /** A failing `cancel()` is reported, never thrown; a real cancellation (stop()) still propagates. */
    private suspend fun cancelAlarm() {
        try {
            scheduler.cancel()
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            onAlarmError("cancel", e)
        }
    }
```
and in `apply`: `AlarmAction.Cancel -> cancelAlarm()`. Update the `start` KDoc: "Does nothing after [stop]."

- [ ] **Step 5: Run the full Android check** — `./gradlew test assembleDebug lint`. Expected: all PASS, lint clean.

- [ ] **Step 6: Commit** — `git add android && git commit -m "BugFix: Guard Android tracker cancel() and make start() a no-op after stop()"` (with the trailer).

---

### Task 3: iOS `AlarmKitScheduler`, its seam, and the Info.plist key

**Files:**
- Create: `ios/Sources/TrainAlarm/Alarm/AlarmSchedulerError.swift`, `AlarmKitClient.swift`, `AlarmKitScheduler.swift`
- Modify: `ios/project.yml` (Info.plist properties)
- Test: `ios/Tests/TrainAlarmTests/Alarm/AlarmKitSchedulerTests.swift`

**Interfaces:** Consumes `protocol AlarmScheduler { func schedule(fireAt: Date) async throws; func cancel() async }`. Produces:
`enum AlarmAuthorization { authorized, denied, notDetermined }`;
`protocol AlarmKitClient { var authorization: AlarmAuthorization { get }; func requestAuthorization() async throws -> AlarmAuthorization; func schedule(id: UUID, fireAt: Date, title: String) async throws; func cancel(id: UUID) throws }`;
`enum AlarmSchedulerError: Error, Equatable { case notAuthorized }`;
`final class AlarmKitScheduler: AlarmScheduler` with `init(client: AlarmKitClient, defaults: UserDefaults = .standard, now: @escaping () -> Date = Date.init)` and `static let minimumLead: TimeInterval = 2`;
`struct SystemAlarmKitClient: AlarmKitClient` (real AlarmKit; no unit tests, device-verified).

- [ ] **Step 1: Write the failing tests** — create `AlarmKitSchedulerTests.swift`:

```swift
import XCTest
@testable import TrainAlarm

private final class FakeAlarmKitClient: AlarmKitClient {
    struct Failure: Error {}
    var authorization: AlarmAuthorization = .authorized
    var requestResult: AlarmAuthorization = .authorized
    var scheduleFailure: Error?
    var cancelFailure: Error?
    private(set) var requested = 0
    private(set) var calls: [String] = []  // "schedule" / "cancel", in order
    private(set) var scheduled: [(id: UUID, fireAt: Date)] = []
    private(set) var cancelled: [UUID] = []

    func requestAuthorization() async throws -> AlarmAuthorization {
        requested += 1
        authorization = requestResult
        return requestResult
    }
    func schedule(id: UUID, fireAt: Date, title: String) async throws {
        calls.append("schedule")
        if let scheduleFailure { throw scheduleFailure }
        scheduled.append((id, fireAt))
    }
    func cancel(id: UUID) throws {
        calls.append("cancel")
        cancelled.append(id)
        if let cancelFailure { throw cancelFailure }
    }
}

final class AlarmKitSchedulerTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_000_000)
    private var defaults: UserDefaults!

    override func setUp() { defaults = UserDefaults(suiteName: UUID().uuidString)! }

    private func make(_ client: FakeAlarmKitClient) -> AlarmKitScheduler {
        AlarmKitScheduler(client: client, defaults: defaults, now: { self.now })
    }
    private func later(_ s: TimeInterval) -> Date { now.addingTimeInterval(s) }

    func testAuthorizedSchedulesAFixedAlarmWithoutRequesting() async throws {
        let client = FakeAlarmKitClient()
        try await make(client).schedule(fireAt: later(600))
        XCTAssertEqual(client.requested, 0)
        XCTAssertEqual(client.scheduled.map(\.fireAt), [later(600)])
    }

    func testNotDeterminedRequestsThenSchedules() async throws {
        let client = FakeAlarmKitClient()
        client.authorization = .notDetermined
        try await make(client).schedule(fireAt: later(600))
        XCTAssertEqual(client.requested, 1)
        XCTAssertEqual(client.scheduled.count, 1)
    }

    func testNotDeterminedThenDeniedThrowsAndSchedulesNothing() async {
        let client = FakeAlarmKitClient()
        client.authorization = .notDetermined
        client.requestResult = .denied
        await assertNotAuthorized(make(client), client)
    }

    func testDeniedThrowsWithoutRequesting() async {
        let client = FakeAlarmKitClient()
        client.authorization = .denied
        await assertNotAuthorized(make(client), client)
        XCTAssertEqual(client.requested, 0)
    }

    private func assertNotAuthorized(_ s: AlarmKitScheduler, _ c: FakeAlarmKitClient,
                                     file: StaticString = #filePath, line: UInt = #line) async {
        do {
            try await s.schedule(fireAt: later(600))
            XCTFail("expected notAuthorized", file: file, line: line)
        } catch {
            XCTAssertEqual(error as? AlarmSchedulerError, .notAuthorized, file: file, line: line)
        }
        XCTAssertTrue(c.scheduled.isEmpty, file: file, line: line)
    }

    func testAFireTimeAtOrBeforeNowIsRaisedToTheMinimumLead() async throws {
        let client = FakeAlarmKitClient()
        let scheduler = make(client)
        try await scheduler.schedule(fireAt: now)
        try await scheduler.schedule(fireAt: later(-300))
        let minimum = later(AlarmKitScheduler.minimumLead)
        XCTAssertEqual(client.scheduled.map(\.fireAt), [minimum, minimum])
    }

    func testASecondScheduleUsesAFreshIdThenCancelsThePreviousOne() async throws {
        let client = FakeAlarmKitClient()
        let scheduler = make(client)
        try await scheduler.schedule(fireAt: later(600))
        try await scheduler.schedule(fireAt: later(900))
        XCTAssertEqual(client.calls, ["schedule", "schedule", "cancel"])
        XCTAssertNotEqual(client.scheduled[0].id, client.scheduled[1].id)
        XCTAssertEqual(client.cancelled, [client.scheduled[0].id])
    }

    func testAFailingScheduleLeavesThePreviousAlarmInPlace() async throws {
        let client = FakeAlarmKitClient()
        let scheduler = make(client)
        try await scheduler.schedule(fireAt: later(600))
        client.scheduleFailure = FakeAlarmKitClient.Failure()
        do {
            try await scheduler.schedule(fireAt: later(900))
            XCTFail("expected the failure to propagate")
        } catch is FakeAlarmKitClient.Failure {}
        XCTAssertTrue(client.cancelled.isEmpty)  // the first alarm was not torn down
        await scheduler.cancel()
        XCTAssertEqual(client.cancelled, [client.scheduled[0].id])  // and is still the current one
    }

    func testTheCurrentIdSurvivesANewSchedulerInstance() async throws {
        let client = FakeAlarmKitClient()
        try await make(client).schedule(fireAt: later(600))
        await make(client).cancel()
        XCTAssertEqual(client.cancelled, [client.scheduled[0].id])
    }

    func testCancelCancelsOnceAndCancelWithNothingScheduledDoesNothing() async throws {
        let client = FakeAlarmKitClient()
        let scheduler = make(client)
        await scheduler.cancel()  // nothing scheduled
        XCTAssertTrue(client.calls.isEmpty)
        try await scheduler.schedule(fireAt: later(600))
        await scheduler.cancel()
        await scheduler.cancel()  // twice
        XCTAssertEqual(client.cancelled.count, 1)
    }

    func testCancelSwallowsClientErrorsForAStaleId() async throws {
        let client = FakeAlarmKitClient()
        let scheduler = make(client)
        try await scheduler.schedule(fireAt: later(600))
        client.cancelFailure = FakeAlarmKitClient.Failure()
        await scheduler.cancel()  // must not throw or crash
        await scheduler.cancel()  // the stale id was forgotten: no second attempt
        XCTAssertEqual(client.cancelled.count, 1)
    }
}
```

- [ ] **Step 2: Run to see it fail** — `xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=<installed iPhone>" -only-testing:TrainAlarmTests/AlarmKitSchedulerTests`. Expected: build FAILS (`AlarmKitClient`, `AlarmAuthorization`, `AlarmKitScheduler` undefined).

- [ ] **Step 3: Implement** — `AlarmSchedulerError.swift`:

```swift
/// Why the real scheduler refused. AlarmKit's own errors (e.g. `maximumLimitReached`) pass through unchanged.
enum AlarmSchedulerError: Error, Equatable {
    /// The user has not allowed alarms for this app (Settings → TrainAlarm).
    case notAuthorized
}
```
`AlarmKitClient.swift`:

```swift
import AlarmKit
import SwiftUI

enum AlarmAuthorization: Equatable { case authorized, denied, notDetermined }

/// The only thing `AlarmKitScheduler` knows about AlarmKit, so its logic is testable with a fake.
protocol AlarmKitClient {
    var authorization: AlarmAuthorization { get }
    func requestAuthorization() async throws -> AlarmAuthorization
    func schedule(id: UUID, fireAt: Date, title: String) async throws
    func cancel(id: UUID) throws
}

struct AlarmKitMetadata: AlarmMetadata {}

/// Real AlarmKit (iOS 26+). Verified on a device, not in unit tests.
struct SystemAlarmKitClient: AlarmKitClient {
    private var manager: AlarmManager { AlarmManager.shared }

    var authorization: AlarmAuthorization { Self.map(manager.authorizationState) }

    func requestAuthorization() async throws -> AlarmAuthorization {
        Self.map(try await manager.requestAuthorization())
    }

    func schedule(id: UUID, fireAt: Date, title: String) async throws {
        let stop = AlarmButton(text: "Stop", textColor: .white, systemImageName: "stop.circle")
        let alert = AlarmPresentation.Alert(title: LocalizedStringResource(stringLiteral: title), stopButton: stop)
        let attributes = AlarmAttributes<AlarmKitMetadata>(presentation: AlarmPresentation(alert: alert),
                                                           tintColor: .blue)
        let configuration = AlarmManager.AlarmConfiguration<AlarmKitMetadata>(
            countdownDuration: nil, schedule: .fixed(fireAt), attributes: attributes,
            stopIntent: nil, secondaryIntent: nil)
        _ = try await manager.schedule(id: id, configuration: configuration)
    }

    func cancel(id: UUID) throws { try manager.cancel(id: id) }

    private static func map(_ state: AlarmManager.AuthorizationState) -> AlarmAuthorization {
        switch state {
        case .authorized: return .authorized
        case .denied: return .denied
        case .notDetermined: return .notDetermined
        @unknown default: return .denied
        }
    }
}
```
If the compiler rejects an AlarmKit initialiser label (the spec lists these signatures as unverified), read the symbol page at `https://developer.apple.com/documentation/alarmkit/<symbol>.md` and adjust to match it; never guess, and report the change.

`AlarmKitScheduler.swift`:

```swift
import Foundation
import os

/// Real `AlarmScheduler` on AlarmKit. One alarm at a time: the current alarm's id is persisted so a
/// new instance (or a relaunch) can still cancel it.
final class AlarmKitScheduler: AlarmScheduler {
    /// AlarmKit's behaviour for a fire time of "now" or earlier is unverified, so never pass one.
    static let minimumLead: TimeInterval = 2

    private let client: AlarmKitClient
    private let defaults: UserDefaults
    private let now: () -> Date
    private let idKey = "TrainAlarm.currentAlarmId"
    private let log = Logger(subsystem: "com.trainalarm.app", category: "alarm")

    init(client: AlarmKitClient, defaults: UserDefaults = .standard, now: @escaping () -> Date = Date.init) {
        self.client = client
        self.defaults = defaults
        self.now = now
    }

    private var currentId: UUID? { defaults.string(forKey: idKey).flatMap(UUID.init(uuidString:)) }

    func schedule(fireAt: Date) async throws {
        try await ensureAuthorized()
        let fire = max(fireAt, now().addingTimeInterval(Self.minimumLead))
        let previous = currentId
        let id = UUID()
        // Make before break: if this throws, the previous alarm is still armed.
        try await client.schedule(id: id, fireAt: fire, title: "TrainAlarm")
        if let previous { try? client.cancel(id: previous) }
        defaults.set(id.uuidString, forKey: idKey)
        log.info("scheduled alarm \(id.uuidString) for \(fire.description)")
    }

    func cancel() async {
        guard let id = currentId else { return }
        do { try client.cancel(id: id) } catch { log.error("cancel failed: \(String(describing: error))") }
        defaults.removeObject(forKey: idKey)
    }

    private func ensureAuthorized() async throws {
        switch client.authorization {
        case .authorized: return
        case .denied: throw AlarmSchedulerError.notAuthorized
        case .notDetermined:
            guard try await client.requestAuthorization() == .authorized else {
                throw AlarmSchedulerError.notAuthorized
            }
        }
    }
}
```
In `ios/project.yml`, under `info.properties`, add `NSAlarmKitUsageDescription: "TrainAlarm sounds an alarm before your stop, even when your phone is silent or locked."`. Do not touch the entitlements.

- [ ] **Step 4: Run the whole iOS suite** — `xcodegen generate && xcodebuild test ...` (full). Expected: all PASS. Then, from `ios/`, confirm the key landed: `plutil -p Generated/Info.plist | grep AlarmKit`.

- [ ] **Step 5: Commit** — `git add ios && git commit -m "Feature: Add AlarmKit scheduler with authorization flow on iOS"` (with the trailer).

---

### Task 4: iOS debug harness

**Files:**
- Create: `ios/Sources/TrainAlarm/App/DevAlarmView.swift`
- Modify: `ios/Sources/TrainAlarm/App/ContentView.swift`

**Interfaces:** Consumes `AlarmKitScheduler(client: SystemAlarmKitClient())`, `SystemAlarmKitClient().authorization`. Produces nothing later tasks use.

- [ ] **Step 1: Create `DevAlarmView.swift`** (everything inside `#if DEBUG`, so release builds exclude it):

```swift
#if DEBUG
import SwiftUI

/// Throwaway dev harness for the Stage 3 device checklist. Not product UI (Stage 4).
struct DevAlarmView: View {
    @State private var seconds = "60"
    @State private var status = "Idle"
    @State private var scheduler = AlarmKitScheduler(client: SystemAlarmKitClient())
    private let client = SystemAlarmKitClient()

    var body: some View {
        Form {
            Section("Dev alarm (debug builds only)") {
                TextField("Seconds from now", text: $seconds).keyboardType(.numberPad)
                Button("Schedule alarm") { Task { await schedule(after: Double(seconds) ?? 60) } }
                Button("Schedule 30 s in the past") { Task { await schedule(after: -30) } }
                Button("Cancel alarm", role: .destructive) {
                    Task { await scheduler.cancel(); status = "Cancelled" }
                }
                Button("Check authorization") { status = "Authorization: \(client.authorization)" }
                Text(status).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private func schedule(after delay: TimeInterval) async {
        let fireAt = Date().addingTimeInterval(delay)
        do {
            try await scheduler.schedule(fireAt: fireAt)
            status = "Scheduled for \(fireAt.formatted(date: .omitted, time: .standard)) (asked for \(Int(delay)) s)"
        } catch {
            status = "Error: \(error)"
        }
    }
}
#endif
```

- [ ] **Step 2: Wire it in** — in `ContentView.swift`, add inside the `VStack`, after the footnote `Text`:

```swift
            #if DEBUG
            DevAlarmView()
            #endif
```
and leave the VStack's `.padding()` as it is.

- [ ] **Step 3: Build and run the full suite** — `xcodebuild test ...`. Expected: PASS (nothing tests the view; the build is the check).

- [ ] **Step 4: Screenshot** — boot an iPhone simulator (`xcrun simctl boot "<installed iPhone>" || true`, since an already-booted simulator is fine), then `xcodebuild -scheme TrainAlarm -destination "platform=iOS Simulator,name=<installed iPhone>" -derivedDataPath <scratchpad>/dd build`, `xcrun simctl install booted <scratchpad>/dd/Build/Products/Debug-iphonesimulator/TrainAlarm.app`, `xcrun simctl launch booted com.trainalarm.app`, `xcrun simctl io booted screenshot <scratchpad>/harness.png`. Confirm the screenshot shows the harness section. A simulator run is for the layout only and is not an alarm-delivery pass.

- [ ] **Step 5: Commit** — `git add ios && git commit -m "Feature: Add debug-only alarm harness on iOS"` (with the trailer).

---

### Task 5: Android `ExactAlarmScheduler` and its gateway seam

**Files:**
- Create (package `com.trainalarm.app.alarm`, dir `android/app/src/main/java/com/trainalarm/app/alarm/`): `AlarmSchedulerException.kt`, `ExactAlarmGateway.kt`, `ExactAlarmScheduler.kt`
- Test: `android/app/src/test/java/com/trainalarm/app/alarm/ExactAlarmSchedulerTest.kt`

**Interfaces:** Consumes `interface AlarmScheduler { suspend fun schedule(fireAt: Instant); suspend fun cancel() }`. Produces:
`sealed class AlarmSchedulerException : Exception` with `class ExactAlarmNotPermitted(cause: Throwable? = null)`;
`interface ExactAlarmGateway { fun canScheduleExactAlarms(): Boolean; fun setAlarmClock(fireAt: Instant) /* may throw SecurityException */; fun cancelAlarm(); fun stopRinging() }`;
`class ExactAlarmScheduler(gateway: ExactAlarmGateway, now: () -> Instant = Instant::now) : AlarmScheduler` with `companion val MINIMUM_LEAD: Duration` (2 s).

- [ ] **Step 1: Write the failing tests** — create `ExactAlarmSchedulerTest.kt`:

```kotlin
package com.trainalarm.app.alarm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.Instant

private class FakeGateway : ExactAlarmGateway {
    var permitted = true
    var setFailure: Exception? = null
    var current: Instant? = null
    var setCalls = 0
    var cancelCalls = 0
    var stopCalls = 0

    override fun canScheduleExactAlarms() = permitted
    override fun setAlarmClock(fireAt: Instant) {
        setCalls += 1
        setFailure?.let { throw it }
        current = fireAt  // like the real PendingIntent: a new alarm replaces the old one
    }
    override fun cancelAlarm() { cancelCalls += 1; current = null }
    override fun stopRinging() { stopCalls += 1 }
}

class ExactAlarmSchedulerTest {
    private val now = Instant.parse("2026-11-10T08:00:00Z")
    private val gateway = FakeGateway()
    private val scheduler = ExactAlarmScheduler(gateway) { now }

    private fun later(seconds: Long): Instant = now.plusSeconds(seconds)

    @Test
    fun permittedSchedulesTheAlarmClock() = runBlocking {
        scheduler.schedule(later(600))
        assertEquals(later(600), gateway.current)
    }

    @Test
    fun notPermittedThrowsAndSchedulesNothing() = runBlocking {
        gateway.permitted = false
        try {
            scheduler.schedule(later(600))
            fail("expected ExactAlarmNotPermitted")
        } catch (e: AlarmSchedulerException.ExactAlarmNotPermitted) {
        }
        assertEquals(0, gateway.setCalls)
        assertNull(gateway.current)
    }

    @Test
    fun aSecurityExceptionFromTheOsBecomesTheTypedException() = runBlocking {
        gateway.setFailure = SecurityException("revoked")  // revoked between the check and the call
        try {
            scheduler.schedule(later(600))
            fail("expected ExactAlarmNotPermitted")
        } catch (e: AlarmSchedulerException.ExactAlarmNotPermitted) {
            assertTrue(e.cause is SecurityException)
        }
    }

    @Test
    fun aFireTimeAtOrBeforeNowIsRaisedToTheMinimumLead() = runBlocking {
        val minimum = now.plus(ExactAlarmScheduler.MINIMUM_LEAD)
        scheduler.schedule(now)
        assertEquals(minimum, gateway.current)
        scheduler.schedule(later(-300))
        assertEquals(minimum, gateway.current)
        assertEquals(Duration.ofSeconds(2), ExactAlarmScheduler.MINIMUM_LEAD)
    }

    @Test
    fun aSecondScheduleReplacesTheFirst() = runBlocking {
        scheduler.schedule(later(600))
        scheduler.schedule(later(900))
        assertEquals(later(900), gateway.current)
        assertEquals(0, gateway.cancelCalls)  // replaced in place, never torn down first
    }

    @Test
    fun aFailingSecondScheduleLeavesTheFirstInPlace() = runBlocking {
        scheduler.schedule(later(600))
        gateway.permitted = false
        try {
            scheduler.schedule(later(900))
            fail("expected ExactAlarmNotPermitted")
        } catch (e: AlarmSchedulerException.ExactAlarmNotPermitted) {
        }
        assertEquals(later(600), gateway.current)
        assertEquals(0, gateway.cancelCalls)
    }

    @Test
    fun cancelCancelsTheAlarmAndStopsARingingService() = runBlocking {
        scheduler.schedule(later(600))
        scheduler.cancel()
        assertNull(gateway.current)
        assertEquals(1, gateway.stopCalls)
    }

    @Test
    fun cancelWithNothingScheduledAndCancelTwiceAreSafe() = runBlocking {
        scheduler.cancel()
        scheduler.cancel()
        assertEquals(2, gateway.cancelCalls)
        assertEquals(2, gateway.stopCalls)
    }
}
```

- [ ] **Step 2: Run to see it fail** — from `android/`: `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew --offline :app:testDebugUnitTest --tests "com.trainalarm.app.alarm.ExactAlarmSchedulerTest"` (the aggregate `test` task rejects `--tests`). Expected: compilation FAILS (types undefined).

- [ ] **Step 3: Implement.**

`AlarmSchedulerException.kt`:
```kotlin
package com.trainalarm.app.alarm

/** Why the real scheduler refused. Thrown from [AlarmScheduler.schedule]; the tracker reports it as a refusal. */
sealed class AlarmSchedulerException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The user has not granted "Alarms & reminders" (SCHEDULE_EXACT_ALARM), or revoked it. */
    class ExactAlarmNotPermitted(cause: Throwable? = null) :
        AlarmSchedulerException("Exact alarms are not allowed for this app", cause)
}
```
`ExactAlarmGateway.kt`:
```kotlin
package com.trainalarm.app.alarm

import java.time.Instant

/** The only thing [ExactAlarmScheduler] knows about Android, so its logic is testable on the JVM. */
interface ExactAlarmGateway {
    fun canScheduleExactAlarms(): Boolean

    /** Replaces any previous alarm. Throws [SecurityException] if the permission was revoked. */
    fun setAlarmClock(fireAt: Instant)

    fun cancelAlarm()

    /** Stops a ringing alarm (service, sound, notification), if any. */
    fun stopRinging()
}
```
`ExactAlarmScheduler.kt`:
```kotlin
package com.trainalarm.app.alarm

import java.time.Duration
import java.time.Instant

/** Real [AlarmScheduler]: an exact alarm-clock alarm that wakes [AlarmReceiver]. */
class ExactAlarmScheduler(
    private val gateway: ExactAlarmGateway,
    private val now: () -> Instant = Instant::now
) : AlarmScheduler {
    override suspend fun schedule(fireAt: Instant) {
        if (!gateway.canScheduleExactAlarms()) throw AlarmSchedulerException.ExactAlarmNotPermitted()
        val earliest = now().plus(MINIMUM_LEAD)
        val fire = if (fireAt.isBefore(earliest)) earliest else fireAt
        try {
            gateway.setAlarmClock(fire)
        } catch (e: SecurityException) {
            throw AlarmSchedulerException.ExactAlarmNotPermitted(e)
        }
    }

    override suspend fun cancel() {
        gateway.cancelAlarm()
        gateway.stopRinging()
    }

    companion object {
        /** Behaviour for a fire time of "now" or earlier is unverified on both platforms, so never pass one. */
        val MINIMUM_LEAD: Duration = Duration.ofSeconds(2)
    }
}
```

- [ ] **Step 4: Run the whole Android check** — `./gradlew test assembleDebug lint`. Expected: all PASS (the new tests plus all existing).

- [ ] **Step 5: Commit** — `git add android && git commit -m "Feature: Add exact-alarm scheduler and gateway seam on Android"` (with the trailer).

---

### Task 6: Android delivery components (gateway, receiver, player, notification, service, activity, manifest)

**Files:**
- Create (in `alarm/`): `SystemExactAlarmGateway.kt`, `AlarmReceiver.kt`, `AlarmPlayer.kt`, `AlarmNotifications.kt`, `AlarmService.kt`, `AlarmActivity.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/res/values/strings.xml`

**Interfaces:** Consumes `ExactAlarmGateway` (Task 5), `MainActivity`. Produces `SystemExactAlarmGateway(context: Context)`; `AlarmNotifications.NOTIFICATION_ID`, `AlarmNotifications.build(context)`; `AlarmPlayer.start(context, onTimeout)`/`stop()`; `AlarmService.stopIntent(context)`. No unit tests: these need the OS (device checklist in the spec §7); the check is `assembleDebug` + `lint` clean, plus the existing tests still passing.

- [ ] **Step 1: Strings** — add to `strings.xml` inside `<resources>`:
```xml
    <string name="alarm_channel_name">Alarm</string>
    <string name="alarm_title">TrainAlarm</string>
    <string name="alarm_stop">Stop</string>
```

- [ ] **Step 2: Manifest** — add `<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />` and `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />` next to the other permissions; replace the "declared ahead of use" comment with "Stage 3 uses SCHEDULE_EXACT_ALARM, POST_NOTIFICATIONS, USE_FULL_SCREEN_INTENT and FOREGROUND_SERVICE_MEDIA_PLAYBACK; the location permissions are for Stage 5."; add inside `<application>` after the activity:
```xml
        <receiver android:name=".alarm.AlarmReceiver" android:exported="false" />
        <service
            android:name=".alarm.AlarmService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />
        <activity
            android:name=".alarm.AlarmActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:launchMode="singleInstance"
            android:showWhenLocked="true"
            android:turnScreenOn="true" />
```

- [ ] **Step 3: `AlarmNotifications.kt` and `AlarmPlayer.kt`:**

```kotlin
package com.trainalarm.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.trainalarm.app.R

object AlarmNotifications {
    const val CHANNEL_ID = "alarm"
    const val NOTIFICATION_ID = 1001

    fun build(context: Context): Notification {
        ensureChannel(context)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(context, 0, Intent(context, AlarmActivity::class.java), flags)
        val stop = PendingIntent.getService(context, 1, AlarmService.stopIntent(context), flags)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.alarm_title))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, context.getString(R.string.alarm_stop), stop)
            .build()
    }

    private fun ensureChannel(context: Context) {
        // No channel sound: AlarmPlayer plays the audio itself, on the alarm stream.
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.alarm_channel_name),
            NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
```
```kotlin
package com.trainalarm.app.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Plays the default alarm sound on the alarm stream until [stop] or the safety timeout. The user's alarm volume is not touched. */
object AlarmPlayer {
    private const val TAG = "AlarmPlayer"
    const val TIMEOUT_MS = 5 * 60 * 1000L
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    fun start(context: Context, onTimeout: () -> Unit) {
        stop()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        if (uri == null) {
            Log.e(TAG, "no default alarm or ringtone sound on this device")
            return
        }
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setDataSource(context.applicationContext, uri)
            isLooping = true
            prepare()
            start()
        }
        handler.postDelayed(onTimeout, TIMEOUT_MS)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        player?.runCatching { stop(); release() }
        player = null
    }
}
```

- [ ] **Step 4: `AlarmService.kt`, `AlarmReceiver.kt`, `AlarmActivity.kt`:**

```kotlin
package com.trainalarm.app.alarm

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat

class AlarmService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRinging()
            return START_NOT_STICKY
        }
        Log.i(TAG, "alarm service started")
        ServiceCompat.startForeground(this, AlarmNotifications.NOTIFICATION_ID, AlarmNotifications.build(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        AlarmPlayer.start(this) { stopRinging() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AlarmPlayer.stop()
        super.onDestroy()
    }

    private fun stopRinging() {
        Log.i(TAG, "alarm stopped")
        AlarmPlayer.stop()
        getSystemService(NotificationManager::class.java).cancel(AlarmNotifications.NOTIFICATION_ID)
        stopSelf()
    }

    companion object {
        private const val TAG = "AlarmService"
        const val ACTION_STOP = "com.trainalarm.app.alarm.STOP"
        fun stopIntent(context: Context): Intent = Intent(context, AlarmService::class.java).setAction(ACTION_STOP)
    }
}
```
```kotlin
package com.trainalarm.app.alarm

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "alarm fired")
        try {
            ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java))
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException (API 31+): still notify and ring, without a service.
            Log.w(TAG, "could not start the alarm service; ringing without it", e)
            try {
                val app = context.applicationContext
                val manager = app.getSystemService(NotificationManager::class.java)
                manager.notify(AlarmNotifications.NOTIFICATION_ID, AlarmNotifications.build(app))
                AlarmPlayer.start(app) {
                    AlarmPlayer.stop()
                    manager.cancel(AlarmNotifications.NOTIFICATION_ID)
                }
            } catch (fallback: Exception) {
                Log.e(TAG, "the alarm fallback failed too", fallback)
            }
        }
    }

    private companion object {
        const val TAG = "AlarmReceiver"
    }
}
```
```kotlin
package com.trainalarm.app.alarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trainalarm.app.R

/** Shown over the lock screen while the alarm rings. Placeholder UI until Stage 4. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.alarm_title))
                        Button(onClick = {
                            startService(AlarmService.stopIntent(this@AlarmActivity))
                            finish()
                        }) { Text(stringResource(R.string.alarm_stop)) }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 5: `SystemExactAlarmGateway.kt`:**

```kotlin
package com.trainalarm.app.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.trainalarm.app.MainActivity
import java.time.Instant

/** Real [ExactAlarmGateway]. One fixed PendingIntent, so re-setting replaces the previous alarm. */
class SystemExactAlarmGateway(private val context: Context) : ExactAlarmGateway {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    override fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    override fun setAlarmClock(fireAt: Instant) {
        val show = PendingIntent.getActivity(context, SHOW_REQUEST, Intent(context, MainActivity::class.java), flags)
        Log.i(TAG, "setAlarmClock for $fireAt")
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(fireAt.toEpochMilli(), show), fire())
    }

    override fun cancelAlarm() {
        Log.i(TAG, "cancelAlarm")
        alarmManager.cancel(fire())
    }

    override fun stopRinging() {
        context.stopService(Intent(context, AlarmService::class.java))
        AlarmPlayer.stop()
        context.getSystemService(NotificationManager::class.java).cancel(AlarmNotifications.NOTIFICATION_ID)
    }

    private fun fire(): PendingIntent =
        PendingIntent.getBroadcast(context, FIRE_REQUEST, Intent(context, AlarmReceiver::class.java), flags)

    private companion object {
        const val TAG = "ExactAlarmGateway"
        const val FIRE_REQUEST = 100
        const val SHOW_REQUEST = 101
    }
}
```

- [ ] **Step 6: Verify** — `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew test assembleDebug lint` from `android/`. Expected: PASS and lint reports no new errors. Fix any `NewApi` finding with the API guards in the spec §5, not by suppressing. Then check the merged manifest: `grep -n 'foregroundServiceType\|exported\|USE_FULL_SCREEN_INTENT\|FOREGROUND_SERVICE_MEDIA_PLAYBACK' $(find android/app/build/intermediates/merged_manifests/debug -name AndroidManifest.xml)` (it also matches `exported="true"` on `MainActivity` and library components; that is expected) must show the service with `foregroundServiceType="mediaPlayback"` and `exported="false"`, the receiver and `AlarmActivity` with `exported="false"`, and both new permissions. Report this task as "builds and lints; device delivery unproven".

- [ ] **Step 7: Commit** — `git add android && git commit -m "Feature: Add alarm receiver, foreground service and lock-screen activity on Android"` (with the trailer).

---

### Task 7: Android permissions helper and debug harness

**Files:**
- Create: `alarm/AlarmPermissions.kt`, `alarm/DevAlarmScreen.kt`
- Modify: `android/app/src/main/java/com/trainalarm/app/MainActivity.kt`

**Interfaces:** Consumes `ExactAlarmScheduler`, `SystemExactAlarmGateway` (Tasks 5-6). Produces `AlarmPermissions.{canScheduleExactAlarms, exactAlarmIntent, notificationsGranted, canUseFullScreenIntent, fullScreenIntentSettings}(context)`; `DevAlarmScreen(refreshKey: Int)`.

- [ ] **Step 1: `AlarmPermissions.kt`** (API-level guards per spec §5; minSdk is 29):

```kotlin
package com.trainalarm.app.alarm

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Runtime state and settings screens for the three user-controlled switches the alarm depends on. */
object AlarmPermissions {
    fun canScheduleExactAlarms(context: Context): Boolean = SystemExactAlarmGateway(context).canScheduleExactAlarms()

    fun exactAlarmIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        } else null

    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun canUseFullScreenIntent(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun fullScreenIntentSettings(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
        } else null
}
```

- [ ] **Step 2: `DevAlarmScreen.kt`:**

```kotlin
package com.trainalarm.app.alarm

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Throwaway dev harness for the Stage 3 device checklist (debug builds only). Not product UI (Stage 4). */
@Composable
fun DevAlarmScreen(refreshKey: Int) {
    val context = LocalContext.current
    val scheduler = remember { ExactAlarmScheduler(SystemExactAlarmGateway(context)) }
    val scope = rememberCoroutineScope()
    var seconds by remember { mutableStateOf("60") }
    var status by remember { mutableStateOf("Idle") }
    var launcherResults by remember { mutableIntStateOf(0) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        launcherResults += 1
    }
    val clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    // Re-read when the activity resumes (refreshKey) or a permission dialog returns (launcherResults).
    val exactAllowed = remember(refreshKey, launcherResults) { AlarmPermissions.canScheduleExactAlarms(context) }
    val notificationsAllowed = remember(refreshKey, launcherResults) { AlarmPermissions.notificationsGranted(context) }
    val fullScreenAllowed = remember(refreshKey, launcherResults) { AlarmPermissions.canUseFullScreenIntent(context) }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Dev alarm (debug builds only)")
        OutlinedTextField(value = seconds, onValueChange = { seconds = it }, label = { Text("Seconds from now (0 allowed)") })
        Button(onClick = {
            scope.launch {
                val fireAt = Instant.now().plusSeconds(seconds.toLongOrNull() ?: 60)
                status = try {
                    scheduler.schedule(fireAt)
                    "Scheduled for ${clock.format(fireAt)}"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Error: ${e::class.simpleName}: ${e.message}"
                }
            }
        }) { Text("Schedule alarm") }
        Button(onClick = { scope.launch { scheduler.cancel(); status = "Cancelled" } }) { Text("Cancel alarm") }

        Text("Exact alarms: ${if (exactAllowed) "allowed" else "DENIED"}")
        AlarmPermissions.exactAlarmIntent(context)?.let { intent ->
            Button(onClick = { context.startActivity(intent) }) { Text("Open exact-alarm settings") }
        }
        Text("Notifications: ${if (notificationsAllowed) "allowed" else "DENIED"}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Button(onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text("Ask for notifications")
            }
        }
        Text("Full-screen alarm: ${if (fullScreenAllowed) "allowed" else "DENIED"}")
        AlarmPermissions.fullScreenIntentSettings(context)?.let { intent ->
            Button(onClick = { context.startActivity(intent) }) { Text("Open full-screen settings") }
        }
        Text(status)
    }
}
```

- [ ] **Step 3: `MainActivity.kt`** — add `import android.content.pm.ApplicationInfo`, `import androidx.compose.runtime.getValue`, `import androidx.compose.runtime.mutableIntStateOf`, `import androidx.compose.runtime.setValue`, `import com.trainalarm.app.alarm.DevAlarmScreen`. In the class add `private var resumeCount by mutableIntStateOf(0)` and:

```kotlin
    override fun onResume() {
        super.onResume()
        resumeCount += 1  // re-reads permission state after the user returns from system settings
    }

    private val isDebuggable: Boolean get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
```
and replace `PlaceholderScreen()` inside `setContent` with `if (isDebuggable) DevAlarmScreen(resumeCount) else PlaceholderScreen()`.

- [ ] **Step 4: Verify** — `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew test assembleDebug lint` from `android/`. Expected: PASS, lint clean. Confirm by reading `MainActivity.kt` that `DevAlarmScreen` is reachable only under `FLAG_DEBUGGABLE`. Report this task as "builds and lints; device behaviour unproven". There is no emulator or Android phone here, so the screen's appearance is **not** verified now; the Android checklist (spec §7) covers it once a phone exists. State this in the final report.

- [ ] **Step 5: Commit** — `git add android && git commit -m "Feature: Add alarm permissions helper and debug harness on Android"` (with the trailer).

---

### Task 8: iPhone device run (controller + user; no subagent)

**Files:** none changed. Results go in a scratch note `<scratchpad>/device-run-ios.md` and are pasted into the PR description and Task 9's docs.

**Interfaces:** Consumes the Task 3-4 build. Produces the recorded pass/fail for spec §7's iPhone checklist.

- [ ] **Step 1: Prerequisites with the user** — in Xcode → Settings → Accounts, sign in with the Apple ID; on the iPhone 12 Pro, enable Developer Mode (Settings → Privacy & Security), connect by cable and trust the Mac. Confirm the phone is listed and note its id: `xcrun devicectl list devices` (and `xcrun xctrace list devices`, where it must appear under Devices, not only the Mac and simulators). Find the team id in Xcode's Signing screen.

- [ ] **Step 2: Build and install** — from `ios/`: `xcodegen generate && xcodebuild -scheme TrainAlarm -destination "id=<device id>" -derivedDataPath <scratch>/dd-device -allowProvisioningUpdates DEVELOPMENT_TEAM=<team id> build`, then `xcrun devicectl device install app --device <device id> <scratch>/dd-device/Build/Products/Debug-iphoneos/TrainAlarm.app` and `xcrun devicectl device process launch --device <device id> com.trainalarm.app`. (`xcodebuild install` writes to DSTROOT and does not put the app on the phone.) Or press Run in Xcode after setting the team in the Signing UI. **Gate:** if signing or capabilities are rejected, stop and report the exact error to the user; do not add an entitlement key or change signing settings in the repo.

- [ ] **Step 3: Run spec §7's iPhone checklist, items 1-7**, one at a time with the user (60 s alarm, phone locked, silent switch on silent, a Focus mode on; cancel; denied authorization; force-quit; 0 s and past; twice in a row; cancel while alerting). Write each result (pass/fail, what was seen/heard, iOS version) into the scratch note.

- [ ] **Step 4: Decide** — if item 1 or 4 fails, stop and report to the user (this is the spike's answer: do not paper over it). Otherwise continue to Task 9.

---

### Task 9: Docs and stage status

**Files:**
- Modify: `docs/spec.md` (§5; §8 Stage 3, which is two lines, and the Stage 2 sentence "`AlarmScheduler` is only a protocol until Stage 3"), `README.md`, `ios/README.md`, `android/README.md`, `android/CLAUDE.md`, `.ai/tech-stack.md`, `.ai/architecture.md`, `.ai/testing-guide.md` (the "iOS and Android test lists are the same" bullet gains "except the Android-only throwing-`cancel()` tests"), `ios/Sources/TrainAlarm/Alarm/README.md`, `android/app/src/main/java/com/trainalarm/app/alarm/README.md`, `ios/Sources/TrainAlarm/Alarm/AlarmScheduler.swift` (doc comment), `android/app/src/main/java/com/trainalarm/app/alarm/AlarmScheduler.kt` (KDoc), `ios/Sources/TrainAlarm/TrainAlarm.entitlements` (comment).

**Interfaces:** Consumes the Task 8 scratch note. Produces docs that match the code.

- [ ] **Step 1: `docs/spec.md` §8, Stage 3 entry** — replace the line with: "3. Alarm delivery spike — `AlarmKitScheduler` (iOS) and `ExactAlarmScheduler` plus foreground service (Android) built and unit-tested on both platforms, with debug-only harnesses. iOS verified on a real iPhone 12 Pro, iOS `<version from the note>`, on `<date>`: `<one-line result from the note>`. Android: device test pending: Stage 3 is not DONE until the Android checklist in the Stage 3 spec has run on a physical Android 14+ phone. Also fixed: Android tracker `cancel()` is guarded; `start()` after `stop()` is a no-op on both platforms." Fill the three `<...>` from the note.
- [ ] **Step 2: `docs/spec.md` §5** — under iOS add: "Needs `NSAlarmKitUsageDescription` in Info.plist; `<no entitlement was required for the device build, per the note>`." Under Android add: "Implementation: `AlarmManager.setAlarmClock` (needs `SCHEDULE_EXACT_ALARM`, checked with `canScheduleExactAlarms()`), a `mediaPlayback` foreground service started from the alarm receiver, and a full-screen-intent notification (`USE_FULL_SCREEN_INTENT`; `systemExempted` is the recorded fallback type)."
- [ ] **Step 3: `.ai/tech-stack.md`** — iOS "Alarms" row: "AlarmKit; `NSAlarmKitUsageDescription` in Info.plist, no entitlement key added". Android "Alarms" row: "`setAlarmClock` + `SCHEDULE_EXACT_ALARM`, `mediaPlayback` foreground service, `USE_FULL_SCREEN_INTENT`". Replace the "Logging: TBD" section with: "`os.Logger` (iOS, category `alarm`) and `android.util.Log` (Android alarm code). The Android tracker reports failures through an injectable `onAlarmError` callback; Stage 4 wires it to `Log`."
- [ ] **Step 4: `.ai/architecture.md`** — the Alarm layer row: "Alarm delivery (`AlarmKitScheduler` / `ExactAlarmScheduler` behind `AlarmScheduler`; Android device test pending)".
- [ ] **Step 5: Comments and READMEs** — replace the "Stage 3 provides the real implementation… until then tests use a fake" wording in both `AlarmScheduler` files with "Real implementations: `AlarmKitScheduler` (iOS), `ExactAlarmScheduler` (Android). Tests use a fake." Replace `ios/Sources/TrainAlarm/Alarm/README.md` with a short description of `AlarmKitClient`/`AlarmKitScheduler`. Replace the entitlements TODO comment with "AlarmKit needs `NSAlarmKitUsageDescription` (Info.plist), read from Apple's *Scheduling an alarm with AlarmKit*; `<no entitlement key was needed>`. Do not add a key without reading Apple's docs." Run `xcodegen generate` and confirm `git diff ios/Sources/TrainAlarm/TrainAlarm.entitlements` shows only this comment change. If XcodeGen replaced the comment, restore the file's pre-edit content with `git checkout`, put the explanation in `.ai/tech-stack.md` and `ios/CLAUDE.md` instead, and tell the user; never add a key. Then `grep -rn "Stage 3" README.md ios android .ai docs/spec.md` (excluding `docs/superpowers`) and fix **every** now-stale hit, including the `ios/README.md` entitlements TODO, `android/README.md`, `android/CLAUDE.md`, and both `Alarm/README.md` files. The new Stage 3 entry in `docs/spec.md` §8 must also carry the "Ships when" wording from the Stage 3 spec §1.
- [ ] **Step 6: Verify** — `xcodegen generate && xcodebuild test ...` (iOS) and `./gradlew test assembleDebug lint` (Android). Expected: all PASS.
- [ ] **Step 7: Commit** — run the secret check (Global Constraints), then `git add -A docs .ai ios android README.md && git commit -m "Docs: Record Stage 3 alarm delivery results and status"` (with the trailer).
- [ ] **Step 8: Hand-off notes for the PR (Step 13)** — list the three spec deviations from the plan's "Spec deviations" paragraph in the PR description, state "Android delivery unproven until a physical phone runs the spec's Android checklist", and remind the user about adding `gradle lint` to `android.yml` as a separate change.
