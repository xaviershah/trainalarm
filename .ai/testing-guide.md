# Testing guide

Rule: nothing is done without something that checks it — a test, a build or
(for UI) a screenshot.

## iOS

| | |
|---|---|
| Framework | XCTest |
| Location | `ios/Tests/TrainAlarmTests/<Layer>/` (e.g. `Model/`, `Provider/`) |
| File naming | `<TypeUnderTest>Tests.swift` (e.g. `StopTests.swift`) |
| Test naming | `test<Behaviour>` (e.g. `testBestArrivalPrefersLiveEstimateOverSchedule`) |
| Fixtures | `ios/Tests/TrainAlarmTests/Fixtures/*.json`, loaded via `Bundle(for:)` with subdirectory `Fixtures` |

Run it (needs full Xcode 26; CI runs the same tests on every push/PR that touches `ios/`):
```bash
cd ios
xcodegen generate
xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=<an installed iPhone>"
```
List installed simulators with `xcrun simctl list devices available iPhone` (the name varies by Xcode version; CI picks one automatically).

## Android

| | |
|---|---|
| Framework | JUnit 4 (JVM unit tests); `org.json` test dependency makes JSON parsing work off-device |
| Location | `android/app/src/test/java/com/trainalarm/app/<layer>/` |
| File naming | `<TypeUnderTest>Test.kt` (e.g. `StopTest.kt`) |
| Fixtures | `android/app/src/test/resources/fixtures/*.json` |

Run it:
```bash
cd android
./gradlew test          # unit tests
./gradlew lint
```

## Station tools (Python)

```bash
python3 -m unittest discover -s tools/stations -p "test_*.py" -v
```

## Shared rules

- Fixtures are the **same files on both platforms** (`gb-nr-*.json`), built
  from RTT's real schema. If you add one, add it to both.
- Test against the `TrainDataProvider` contract and the model types. Tracking
  and ETA logic (Stage 2) is tested with fixtures only, without UI or network.
- `stations.json` is bundled on both platforms; each platform's `StationDirectory`
  tests assert the bundled copy equals `data/stations.json`.
- CI runs both suites on every push/PR that touches that platform. Android CI also runs `gradle lint`; a lint error fails the check, warnings do not.
- **Alarm delivery needs a real device**, not a simulator or emulator.
  It passes when the alarm fires while the phone is locked and silenced.
- **Provider network tests** use no mocking library: iOS passes an ephemeral
  `URLSession` whose `protocolClasses` is a `URLProtocol` stub
  (`StubURLProtocol`) to `RttProvider`; Android passes a fake `HttpTransport`.
- **Tracker tests** use hand-written fakes only: `FakeClock`, `FakeProvider`,
  `FakeAlarmScheduler`, `FakeLocationSource`, with `ServiceBuilder` scripting poll
  sequences (from the shared fixtures or built to match them). The pure
  `JourneyMonitor` is tested through `MonitorHarness`; `JourneyTracker` through the fakes,
  synchronising on `FakeClock.waitForSleepCount` / `awaitSleepCount`. Android uses
  `runBlocking` and no `kotlinx-coroutines-test`; iOS uses an `AsyncStream` and a
  continuation-based fake clock. The iOS and Android test lists are the same, except the Android-only throwing-`cancel()` tests.
- **UI is checked by unit tests on the model plus screenshots.** `SessionModel` is tested with the same
  hand-written fakes as the tracker. `tools/screenshots/ios.sh` opens every demo scenario in both
  themes and saves the PNGs to `docs/ui-screenshots/ios/`; look at them against `docs/ui-mockup/`.
  `tools/screenshots/android.sh` does the same on a running emulator but has never been run, so the
  Android screens have not been seen. A screenshot is never a pass for alarm delivery.
- **Demo mode** (debug builds, no RTT token) runs the whole flow on scripted trains; scenario tests in
  `android/app/src/testDebug/.../demo/ScenarioTest.kt` and `ios/Tests/TrainAlarmTests/App/ScenarioTests.swift` pin each named scenario.
- In a fresh git worktree `android/local.properties` is absent (it is
  gitignored), so run Gradle with the SDK path set:
  `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew test --offline`.

## TBD

- Instrumented/UI tests (`androidTest`, XCUITest) — none: Stage 4 checks the UI with model unit tests and
  screenshots. Revisit if UI regressions start to bite.
- Code coverage target.
