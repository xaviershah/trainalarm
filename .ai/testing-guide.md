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

Run it (needs full Xcode 26 — until it is installed locally, iOS tests only run in CI):
```bash
cd ios
xcodegen generate
xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 16"
```

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

## Shared rules

- Fixtures are the **same files on both platforms** (`gb-nr-service*.json`), built
  from RTT's real schema. If you add one, add it to both.
- Test against the `TrainDataProvider` contract and the model types. Tracking
  and ETA logic (Stage 2) is tested with fixtures only, without UI or network.
- CI runs both suites on every push/PR that touches that platform.
- **Alarm delivery (Stage 3) needs a real device**, not a simulator or emulator.
  It passes when the alarm fires while the phone is locked and silenced.

## TBD

- Instrumented/UI tests (`androidTest`, XCUITest) — none yet; decide at Stage 4.
- Code coverage target.
- Mocking approach for `TrainDataProvider` (hand-written fakes are the likely default).
