# Stage 1 Audit Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Stage 1's provider layer ready for Stage 2: typed `ProviderError`, per-activity cancellation / `displayAs` / `hasArrived` on `Stop`, Android timeouts, and a tested departure-board path, identically on iOS and Android.

**Architecture:** `ProviderError` lives next to the `TrainDataProvider` contract. `Stop` gains fields with defaults so existing call sites compile. Both mappers share one `makeStop` helper and throw `ProviderError.malformed` for bad data. Android gets an injectable `HttpTransport` (default: `HttpURLConnection` with 10s timeouts); iOS keeps its injectable `URLSession` and is tested with a `URLProtocol` stub.

**Tech Stack:** Swift 5 mode / XCTest / XcodeGen (iOS 26); Kotlin 2.0.20 / JUnit 4 / `org.json` test dependency / kotlinx-coroutines 1.9.0 (Android).

**Spec:** `docs/superpowers/specs/2026-10-07-stage-1-audit-fixes-design.md` (read it first; the plan argues from it).

## Summary (plain English)

| Task | Files touched (per platform) | What it proves |
|---|---|---|
| 1. `Stop` fields and mapping | `Stop`, `RttProvider` mapper, 3 new fixtures, Android null helper | A destination with only its departure cancelled is not "cancelled"; `displayAs` and `hasArrived` are read from the real schema; JSON nulls become nil |
| 2. `ProviderError` + mapper failures | new `ProviderError`, `RttProvider` mapper, existing malformed tests | Both mappers fail identically on bad data; `RttError` is gone |
| 3. Provider errors, 204, cancellation, timeouts | `RttProvider` network layer, Android `HttpTransport`, new provider tests | Every failure kind (network / http / malformed) maps correctly; a cancelled poll is never an error |
| 4. Departure board | new `/gb-nr/location` fixture, mapper and provider tests | The departure-board path is finally tested (spec §8's claim becomes true) |
| 5. Docs and final verification | `docs/spec.md` §8, `.ai/tech-stack.md`, `README.md`, `date` comments | Docs match the code; both suites green; no `RttError` / `NotImplementedError` left |

## Global Constraints

- Build every change on **both** platforms in the matching layer (`.ai/architecture.md`); the same fixtures, byte-identical, in both fixture folders.
- Do not invent RTT response shapes. Fixtures are built from `downloads/RTT.GH.API-spec` (read the cited lines in each task) and are labelled as schema-built, not captured. Never use the legacy `api.rtt.io`.
- Names are fixed by the spec and the Stage 2 spec: `ProviderError` cases `network(message)`, `http(code)`, `malformed(reason)`, `notImplemented(message)` (Kotlin: `Network`, `Http`, `Malformed`, `NotImplemented`); `Stop.isArrivalCancelled`, `isDepartureCancelled`, `isCancelled` (their OR), `displayAs: StopDisplay?` with `call, cancelled, diverted, starts, terminates, pass, unknown` (Kotlin `CALL` … `UNKNOWN`), `hasArrived`.
- Absent or JSON-null `displayAs` is nil (never `pass`); an unrecognised string is `unknown`. The `pass` activity is not mapped.
- Cancellation is never wrapped: iOS `URLError(.cancelled)` becomes `CancellationError()`; Android catches only `IOException` and `JSONException`, never `Exception`.
- HTTP 204: `departureBoard` returns `[]`; `serviceDetails` throws `malformed`; a 200 with an empty body is `malformed`.
- Swift stays in Swift 5 language mode. No new dependencies on either platform. No Stage 2 work, no `searchStations` wiring, no UI.
- Android commands need `ANDROID_HOME` because `android/local.properties` is gitignored and absent in this worktree: run `cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew test --offline -q`. iOS: `cd ios && xcodegen generate`, then `xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=<name>"`, with `<name>` from `xcrun simctl list devices available iPhone`.
- Before every commit, run `git diff --cached --stat` and scan the staged diff per the secret check in `.ai/git.md` (no tokens, `android/local.properties`, `*.xcodeproj`, signing files).
- Commit messages follow `.ai/git.md`: `<Type>: <Description>` plus the `Co-Authored-By` trailer; say which platform when a commit touches only one.
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

Inputs the spec implies but a plain reading of the tasks would not exercise; each has a pinning test in the task named.

1. `realtimeActual` present but not a parseable date (e.g. `"garbage"`): `hasArrived` is false, nothing crashes (Task 1).
2. `displayAs` in the wrong case (`"call"`) or an unknown value: `unknown`, not a crash and not `call` (Task 1).
3. Other non-2xx statuses a real token will hit, 401 (bad token) and 429 (rate limit): `http(code)` carrying the code (Task 3).
4. A 200 whose top-level JSON is an array, not an object: `malformed` (Task 3).
5. A socket timeout on Android (`SocketTimeoutException`, an `IOException`): `Network`, so the poll loop can retry (Task 3).
6. A `/gb-nr/location` 200 with no `services` key: `departureBoard` returns `[]` (Task 4).

## File map

| | iOS (`ios/`) | Android (`android/app/src/`) |
|---|---|---|
| Create | `Sources/TrainAlarm/Providers/ProviderError.swift`; `Tests/TrainAlarmTests/Provider/RttProviderTests.swift`; 4 fixtures | `main/java/com/trainalarm/app/provider/ProviderError.kt`, `HttpTransport.kt`; `test/java/.../provider/RttProviderTest.kt`, `HttpUrlConnectionTransportTest.kt`, `JsonNullTest.kt`; 4 fixtures |
| Modify | `Models/Stop.swift`; `Providers/RttProvider.swift`; `Providers/TrainDataProvider.swift`; `Model/StopTests.swift`; `Provider/RttMapperTests.swift` | `model/Stop.kt`; `provider/RttProvider.kt`, `TrainDataProvider.kt`; `model/StopTest.kt`; `provider/RttMapperTest.kt` |
| Fixtures (both folders) | `Tests/TrainAlarmTests/Fixtures/` | `test/resources/fixtures/`: `gb-nr-service-departure-cancelled.json`, `gb-nr-service-display-as.json`, `gb-nr-service-nulls.json`, `gb-nr-location.json` |
| Docs | `docs/spec.md` §8, `.ai/tech-stack.md:38-48`, `README.md:26-27` | |

---

## Task 1: `Stop` per-activity cancellation, `displayAs`, `hasArrived` (both platforms)

**Spec:** §2 and §4. **Schema to read first:** `downloads/RTT.GH.API-spec:197-218` (`LocationDisplayAs`), `:243-306` (`IndividualTemporalData`, `LocationTemporalData`).

**Files:**
- Create (both fixture folders, byte-identical): `gb-nr-service-departure-cancelled.json`, `gb-nr-service-display-as.json`, `gb-nr-service-nulls.json`
- Create (Android): `android/app/src/test/java/com/trainalarm/app/provider/JsonNullTest.kt`
- Modify iOS: `ios/Sources/TrainAlarm/Models/Stop.swift`, `ios/Sources/TrainAlarm/Providers/RttProvider.swift` (mapper), `ios/Tests/TrainAlarmTests/Model/StopTests.swift`, `ios/Tests/TrainAlarmTests/Provider/RttMapperTests.swift`
- Modify Android: `.../model/Stop.kt`, `.../provider/RttProvider.kt` (mapper), `.../model/StopTest.kt`, `.../provider/RttMapperTest.kt`

**Interfaces:**
- Produces: `StopDisplay` (Swift: `.call .cancelled .diverted .starts .terminates .pass .unknown`; Kotlin `CALL … UNKNOWN`). `Stop` gains `isArrivalCancelled`, `isDepartureCancelled`, `displayAs: StopDisplay?` (nil when absent/JSON null), `hasArrived`; `isCancelled` becomes their OR. `RttMapper` keeps its current public signatures. Android gains `internal fun JSONObject.optStringOrNull(key: String): String?`.
- Consumes: nothing from earlier tasks. (Tasks 2-4 rely on these `Stop` fields.)

- [ ] **Step 1: Create the three fixtures (schema-built, not captured)**

Write each file under `ios/Tests/TrainAlarmTests/Fixtures/`, then copy all three to `android/app/src/test/resources/fixtures/`.

`gb-nr-service-departure-cancelled.json`:
```json
{
  "service": {
    "scheduleMetadata": {
      "uniqueIdentity": "gb-nr:L01529:2026-09-13",
      "namespace": "gb-nr",
      "identity": "L01529",
      "departureDate": "2026-09-13",
      "operator": { "code": "SW", "name": "South Western Railway" },
      "modeType": "TRAIN",
      "inPassengerService": true
    },
    "origin": [
      { "location": { "namespace": "gb-nr", "description": "London Waterloo", "shortCodes": ["WAT"], "longCodes": ["WATRLMN"] } }
    ],
    "destination": [
      { "location": { "namespace": "gb-nr", "description": "Basingstoke", "shortCodes": ["BSK"], "longCodes": ["BASINGS"] } }
    ],
    "locations": [
      {
        "location": { "namespace": "gb-nr", "description": "London Waterloo", "shortCodes": ["WAT"], "longCodes": ["WATRLMN"] },
        "temporalData": {
          "departure": { "scheduleAdvertised": "2026-09-13T11:00:00Z", "realtimeActual": "2026-09-13T11:00:00Z", "isCancelled": false },
          "displayAs": "CALL"
        }
      },
      {
        "location": { "namespace": "gb-nr", "description": "Woking", "shortCodes": ["WOK"], "longCodes": ["WOKING"] },
        "temporalData": {
          "arrival": { "scheduleAdvertised": "2026-09-13T11:23:00Z", "realtimeForecast": "2026-09-13T11:25:00Z", "isCancelled": false },
          "departure": { "scheduleAdvertised": "2026-09-13T11:24:00Z", "isCancelled": true, "cancellationReasonCode": "TB" },
          "displayAs": "CALL"
        }
      },
      {
        "location": { "namespace": "gb-nr", "description": "Basingstoke", "shortCodes": ["BSK"], "longCodes": ["BASINGS"] },
        "temporalData": {
          "arrival": { "scheduleAdvertised": "2026-09-13T11:47:00Z", "realtimeForecast": "2026-09-13T11:49:00Z", "isCancelled": false },
          "displayAs": "CALL"
        }
      }
    ]
  }
}
```

`gb-nr-service-display-as.json` (six stops: CALL, PASS with times under `pass`, DIVERTED, CANCELLED, an unrecognised value, TERMINATES with `realtimeActual` on arrival):
```json
{
  "service": {
    "scheduleMetadata": {
      "uniqueIdentity": "gb-nr:L01530:2026-09-13",
      "namespace": "gb-nr",
      "identity": "L01530",
      "departureDate": "2026-09-13",
      "operator": { "code": "SW", "name": "South Western Railway" },
      "modeType": "TRAIN",
      "inPassengerService": true
    },
    "origin": [
      { "location": { "namespace": "gb-nr", "description": "London Waterloo", "shortCodes": ["WAT"], "longCodes": ["WATRLMN"] } }
    ],
    "destination": [
      { "location": { "namespace": "gb-nr", "description": "Basingstoke", "shortCodes": ["BSK"], "longCodes": ["BASINGS"] } }
    ],
    "locations": [
      {
        "location": { "namespace": "gb-nr", "description": "London Waterloo", "shortCodes": ["WAT"], "longCodes": ["WATRLMN"] },
        "temporalData": {
          "departure": { "scheduleAdvertised": "2026-09-13T12:00:00Z", "realtimeActual": "2026-09-13T12:00:00Z", "isCancelled": false },
          "displayAs": "CALL"
        }
      },
      {
        "location": { "namespace": "gb-nr", "description": "Clapham Junction", "shortCodes": ["CLJ"], "longCodes": ["CLPHMJN"] },
        "temporalData": {
          "pass": { "scheduleAdvertised": "2026-09-13T12:08:00Z", "realtimeForecast": "2026-09-13T12:09:00Z", "isCancelled": false },
          "displayAs": "PASS"
        }
      },
      {
        "location": { "namespace": "gb-nr", "description": "Surbiton", "shortCodes": ["SUR"], "longCodes": ["SURBITN"] },
        "temporalData": {
          "arrival": { "scheduleAdvertised": "2026-09-13T12:17:00Z", "realtimeForecast": "2026-09-13T12:18:00Z", "isCancelled": false },
          "displayAs": "DIVERTED"
        }
      },
      {
        "location": { "namespace": "gb-nr", "description": "Woking", "shortCodes": ["WOK"], "longCodes": ["WOKING"] },
        "temporalData": {
          "arrival": { "scheduleAdvertised": "2026-09-13T12:23:00Z", "isCancelled": true, "cancellationReasonCode": "TB" },
          "departure": { "scheduleAdvertised": "2026-09-13T12:24:00Z", "isCancelled": true, "cancellationReasonCode": "TB" },
          "displayAs": "CANCELLED"
        }
      },
      {
        "location": { "namespace": "gb-nr", "description": "Winchester", "shortCodes": ["WIN"], "longCodes": ["WNCHSTR"] },
        "temporalData": {
          "arrival": { "scheduleAdvertised": "2026-09-13T12:40:00Z", "isCancelled": false },
          "displayAs": "FUTURE_VALUE"
        }
      },
      {
        "location": { "namespace": "gb-nr", "description": "Basingstoke", "shortCodes": ["BSK"], "longCodes": ["BASINGS"] },
        "temporalData": {
          "arrival": { "scheduleAdvertised": "2026-09-13T12:47:00Z", "realtimeActual": "2026-09-13T12:48:00Z", "isCancelled": false },
          "displayAs": "TERMINATES"
        }
      }
    ]
  }
}
```

`gb-nr-service-nulls.json` (explicit JSON nulls, which the schema allows):
```json
{
  "service": {
    "scheduleMetadata": {
      "uniqueIdentity": "gb-nr:L01531:2026-09-13",
      "namespace": "gb-nr",
      "identity": "L01531",
      "departureDate": "2026-09-13",
      "operator": { "code": "SW", "name": "South Western Railway" },
      "modeType": "TRAIN",
      "inPassengerService": true
    },
    "origin": [
      { "location": { "namespace": "gb-nr", "description": "London Waterloo", "shortCodes": ["WAT"], "longCodes": ["WATRLMN"] } }
    ],
    "destination": [
      { "location": { "namespace": "gb-nr", "description": "Basingstoke", "shortCodes": ["BSK"], "longCodes": ["BASINGS"] } }
    ],
    "locations": [
      {
        "location": { "namespace": "gb-nr", "description": "London Waterloo", "shortCodes": ["WAT"], "longCodes": ["WATRLMN"] },
        "temporalData": {
          "departure": { "scheduleAdvertised": "2026-09-13T13:00:00Z", "realtimeActual": null, "realtimeForecast": null, "isCancelled": false },
          "displayAs": null
        }
      },
      {
        "location": { "namespace": "gb-nr", "description": "Basingstoke", "shortCodes": ["BSK"], "longCodes": ["BASINGS"] },
        "temporalData": {
          "arrival": { "scheduleAdvertised": "2026-09-13T13:47:00Z", "realtimeActual": null, "realtimeForecast": null, "realtimeEstimate": null, "isCancelled": false },
          "displayAs": null
        }
      }
    ]
  }
}
```

Then:
```bash
cp ios/Tests/TrainAlarmTests/Fixtures/gb-nr-service-{departure-cancelled,display-as,nulls}.json android/app/src/test/resources/fixtures/
cmp ios/Tests/TrainAlarmTests/Fixtures/gb-nr-service-nulls.json android/app/src/test/resources/fixtures/gb-nr-service-nulls.json && echo identical
```
Expected: `identical`.

- [ ] **Step 2: Write the failing iOS tests**

Append to `ios/Tests/TrainAlarmTests/Model/StopTests.swift`, inside `StopTests` (after the last test):
```swift
    func testIsCancelledIsFalseByDefault() {
        let stop = Stop(station: reading, scheduledArrival: nil, scheduledDeparture: nil, estimatedArrival: nil, estimatedDeparture: nil)
        XCTAssertFalse(stop.isArrivalCancelled)
        XCTAssertFalse(stop.isDepartureCancelled)
        XCTAssertFalse(stop.isCancelled)
        XCTAssertNil(stop.displayAs)
        XCTAssertFalse(stop.hasArrived)
    }

    func testIsCancelledIsTrueWhenOnlyDepartureIsCancelled() {
        let stop = Stop(station: reading, scheduledArrival: nil, scheduledDeparture: nil, estimatedArrival: nil, estimatedDeparture: nil, isDepartureCancelled: true)
        XCTAssertFalse(stop.isArrivalCancelled)
        XCTAssertTrue(stop.isDepartureCancelled)
        XCTAssertTrue(stop.isCancelled)
    }
```

Append to `ios/Tests/TrainAlarmTests/Provider/RttMapperTests.swift`, inside `RttMapperTests` (before the final `}`):
```swift
    private func fullService(_ name: String) throws -> Service {
        let fixture = try loadFixture(name)
        return try RttMapper.fullService(fixture["service"] as! [String: Any])
    }

    func testDepartureCancelledOnIntermediateStopIsNotArrivalCancelled() throws {
        let woking = try fullService("gb-nr-service-departure-cancelled").journey.stops[1]
        XCTAssertFalse(woking.isArrivalCancelled)
        XCTAssertTrue(woking.isDepartureCancelled)
        XCTAssertTrue(woking.isCancelled)
    }

    func testDisplayAsIsMappedPerStop() throws {
        let stops = try fullService("gb-nr-service-display-as").journey.stops
        let expected: [StopDisplay?] = [.call, .pass, .diverted, .cancelled, .unknown, .terminates]
        XCTAssertEqual(stops.map(\.displayAs), expected)
    }

    func testPassStopHasNoArrivalOrDepartureBecausePassActivityIsNotMapped() throws {
        let pass = try fullService("gb-nr-service-display-as").journey.stops[1]
        XCTAssertNil(pass.scheduledArrival)
        XCTAssertNil(pass.scheduledDeparture)
        XCTAssertNil(pass.bestArrival)
    }

    func testHasArrivedOnlyWhenArrivalHasRealtimeActual() throws {
        let stops = try fullService("gb-nr-service-display-as").journey.stops
        XCTAssertEqual(stops.map(\.hasArrived), [false, false, false, false, false, true])
    }

    func testExplicitJsonNullsMapToNil() throws {
        let stops = try fullService("gb-nr-service-nulls").journey.stops
        XCTAssertNil(stops[0].displayAs)
        XCTAssertNil(stops[0].estimatedDeparture)
        XCTAssertNil(stops[1].displayAs)
        XCTAssertNil(stops[1].estimatedArrival)
        XCTAssertFalse(stops[1].hasArrived)
    }

    func testWrongCaseDisplayAsIsUnknownAndUnparseableActualIsNotArrived() throws {
        let location: [String: Any] = [
            "location": ["description": "Reading", "shortCodes": ["RDG"]],
            "temporalData": [
                "arrival": ["scheduleAdvertised": "2026-09-13T08:00:00Z", "realtimeActual": "garbage"],
                "displayAs": "call",
            ],
        ]
        let stop = try RttMapper.stop(from: location)
        XCTAssertEqual(stop.displayAs, .unknown)
        XCTAssertFalse(stop.hasArrived)
        XCTAssertNil(stop.estimatedArrival)
    }

    func testNullOperatorNameFallsBackToUnknown() throws {
        let lineUp: [String: Any] = [
            "scheduleMetadata": ["uniqueIdentity": "gb-nr:X:2026-09-13", "operator": ["name": NSNull()]],
            "temporalData": [String: Any](),
        ]
        let atStation = Station(id: "WAT", name: "London Waterloo", latitude: 51.5031, longitude: -0.1132)
        XCTAssertEqual(try RttMapper.serviceSummary(lineUp, atStation: atStation).operatorName, "Unknown")
    }
```

- [ ] **Step 3: Write the failing Android tests**

Append to `android/app/src/test/java/com/trainalarm/app/model/StopTest.kt`, inside `StopTest` (add `import org.junit.Assert.assertFalse`, `assertNull`, `assertTrue` to the imports):
```kotlin
    @Test
    fun isCancelled_isFalseByDefault() {
        val stop = Stop(reading, null, null, null, null)
        assertFalse(stop.isArrivalCancelled)
        assertFalse(stop.isDepartureCancelled)
        assertFalse(stop.isCancelled)
        assertNull(stop.displayAs)
        assertFalse(stop.hasArrived)
    }

    @Test
    fun isCancelled_isTrueWhenOnlyDepartureIsCancelled() {
        val stop = Stop(reading, null, null, null, null, isDepartureCancelled = true)
        assertFalse(stop.isArrivalCancelled)
        assertTrue(stop.isDepartureCancelled)
        assertTrue(stop.isCancelled)
    }
```

Append to `android/app/src/test/java/com/trainalarm/app/provider/RttMapperTest.kt`, inside `RttMapperTest` (add `import com.trainalarm.app.model.Service`, `import com.trainalarm.app.model.Station`, `import com.trainalarm.app.model.StopDisplay`, `import org.junit.Assert.assertNull`):
```kotlin
    private fun fullService(name: String): Service =
        RttMapper.fullService(loadFixture("$name.json").getJSONObject("service"))

    @Test
    fun departureCancelledOnIntermediateStop_isNotArrivalCancelled() {
        val woking = fullService("gb-nr-service-departure-cancelled").journey.stops[1]
        assertFalse(woking.isArrivalCancelled)
        assertTrue(woking.isDepartureCancelled)
        assertTrue(woking.isCancelled)
    }

    @Test
    fun displayAs_isMappedPerStop() {
        val stops = fullService("gb-nr-service-display-as").journey.stops
        assertEquals(
            listOf(
                StopDisplay.CALL, StopDisplay.PASS, StopDisplay.DIVERTED,
                StopDisplay.CANCELLED, StopDisplay.UNKNOWN, StopDisplay.TERMINATES
            ),
            stops.map { it.displayAs }
        )
    }

    @Test
    fun passStop_hasNoArrivalOrDepartureBecausePassActivityIsNotMapped() {
        val pass = fullService("gb-nr-service-display-as").journey.stops[1]
        assertNull(pass.scheduledArrival)
        assertNull(pass.scheduledDeparture)
        assertNull(pass.bestArrival)
    }

    @Test
    fun hasArrived_onlyWhenArrivalHasRealtimeActual() {
        val stops = fullService("gb-nr-service-display-as").journey.stops
        assertEquals(listOf(false, false, false, false, false, true), stops.map { it.hasArrived })
    }

    @Test
    fun explicitJsonNulls_mapToNull() {
        val stops = fullService("gb-nr-service-nulls").journey.stops
        assertNull(stops[0].displayAs)
        assertNull(stops[0].estimatedDeparture)
        assertNull(stops[1].displayAs)
        assertNull(stops[1].estimatedArrival)
        assertFalse(stops[1].hasArrived)
    }

    @Test
    fun wrongCaseDisplayAs_isUnknown_andUnparseableActualIsNotArrived() {
        val location = JSONObject(
            """{"location": {"description": "Reading", "shortCodes": ["RDG"]},
                "temporalData": {
                  "arrival": {"scheduleAdvertised": "2026-09-13T08:00:00Z", "realtimeActual": "garbage"},
                  "displayAs": "call"}}"""
        )
        val stop = RttMapper.stop(location)
        assertEquals(StopDisplay.UNKNOWN, stop.displayAs)
        assertFalse(stop.hasArrived)
        assertNull(stop.estimatedArrival)
    }

    @Test
    fun nullOperatorName_fallsBackToUnknown() {
        // The JVM library's optString(key) returns "" for a JSON null, so this fails
        // without optStringOrNull (on a device it would be the string "null").
        val lineUp = JSONObject(
            """{"scheduleMetadata": {"uniqueIdentity": "gb-nr:X:2026-09-13", "operator": {"name": null}},
                "temporalData": {}}"""
        )
        val atStation = Station("WAT", "London Waterloo", 51.5031, -0.1132)
        assertEquals("Unknown", RttMapper.serviceSummary(lineUp, atStation).operatorName)
    }
```

Create `android/app/src/test/java/com/trainalarm/app/provider/JsonNullTest.kt`. Real Android `optString(key, fallback)` returns the string `"null"` for a JSON null, but the JVM test library returns the fallback, so this test simulates Android's behaviour to prove the helper still yields null:
```kotlin
package com.trainalarm.app.provider

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonNullTest {

    /** Simulates real Android org.json, whose optString(key, fallback) returns "null" for a JSON null. */
    private fun androidStyle(json: String): JSONObject = object : JSONObject(json) {
        override fun optString(key: String?, defaultValue: String?): String? =
            if (isNull(key)) "null" else super.optString(key, defaultValue)
    }

    @Test
    fun simulation_returnsTheStringNullForAJsonNull() {
        assertEquals("null", androidStyle("""{"displayAs": null}""").optString("displayAs", null))
    }

    @Test
    fun optStringOrNull_returnsNullForJsonNull_evenWithAndroidSemantics() {
        assertNull(androidStyle("""{"displayAs": null}""").optStringOrNull("displayAs"))
    }

    @Test
    fun optStringOrNull_returnsNullForMissingKey() {
        assertNull(androidStyle("""{}""").optStringOrNull("displayAs"))
    }

    @Test
    fun optStringOrNull_returnsTheValueWhenPresent() {
        assertEquals("CALL", androidStyle("""{"displayAs": "CALL"}""").optStringOrNull("displayAs"))
    }
}
```

- [ ] **Step 4: Run both suites to verify they fail**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=<name>" 2>&1 | grep -E "error:|Executed|TEST (FAILED|SUCCEEDED)" | head -20
cd ../android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew test --offline -q 2>&1 | grep -E "^e: |FAILED|error" | head -20
```
Expected: both FAIL to compile, e.g. iOS `value of type 'Stop' has no member 'isArrivalCancelled'` / `extra argument 'isDepartureCancelled'`; Android `Unresolved reference: isArrivalCancelled`, `Unresolved reference: StopDisplay`, `Unresolved reference: optStringOrNull`.

- [ ] **Step 5: Implement on iOS**

Replace `ios/Sources/TrainAlarm/Models/Stop.swift` with:
```swift
import Foundation

/// What the provider says this location is for the train (RTT `displayAs`, a
/// per-location field). `Stop.displayAs` is nil when the provider didn't say.
enum StopDisplay: Equatable {
    case call
    case cancelled
    case diverted
    case starts
    case terminates
    case pass
    /// A value this app doesn't recognise (the schema may grow).
    case unknown
}

/// One station within a `Journey`. Scheduled times come from the timetable;
/// estimated times are refined by live running data where available (may be
/// nil if the provider has nothing live yet, or this stop is in the past).
/// See docs/spec.md §4 for how these get updated as a journey runs.
struct Stop: Equatable {
    let station: Station
    let scheduledArrival: Date?
    let scheduledDeparture: Date?
    let estimatedArrival: Date?
    let estimatedDeparture: Date?
    var isArrivalCancelled: Bool = false
    var isDepartureCancelled: Bool = false
    var displayAs: StopDisplay? = nil
    /// True once the train has arrived here (the arrival has a `realtimeActual`).
    var hasArrived: Bool = false

    /// Either activity cancelled. Use the per-activity flags when the difference matters.
    var isCancelled: Bool { isArrivalCancelled || isDepartureCancelled }

    /// Best-known arrival time: live estimate if we have one, else the timetable.
    var bestArrival: Date? {
        estimatedArrival ?? scheduledArrival
    }
}
```

In `ios/Sources/TrainAlarm/Providers/RttProvider.swift`, inside `enum RttMapper`:

1. Replace the private `isCancelled(_:)` function with:
```swift
    private static func isActivityCancelled(_ activity: [String: Any]?) -> Bool {
        activity?["isCancelled"] as? Bool ?? false
    }

    /// `displayAs` is per location, not per activity. Absent or JSON null is nil;
    /// an unrecognised string is `.unknown`.
    private static func displayAs(_ raw: Any?) -> StopDisplay? {
        guard let value = raw as? String else { return nil }
        switch value {
        case "CALL": return .call
        case "CANCELLED": return .cancelled
        case "DIVERTED": return .diverted
        case "STARTS": return .starts
        case "TERMINATES": return .terminates
        case "PASS": return .pass
        default: return .unknown
        }
    }

    /// Builds a `Stop` from one location's `temporalData`. The `pass` activity is
    /// deliberately not mapped: a PASS stop has nil arrival and departure.
    private static func makeStop(station: Station, temporal: [String: Any]) -> Stop {
        let arrival = temporal["arrival"] as? [String: Any]
        let departure = temporal["departure"] as? [String: Any]
        return Stop(
            station: station,
            scheduledArrival: scheduled(arrival),
            scheduledDeparture: scheduled(departure),
            estimatedArrival: bestRealtime(arrival),
            estimatedDeparture: bestRealtime(departure),
            isArrivalCancelled: isActivityCancelled(arrival),
            isDepartureCancelled: isActivityCancelled(departure),
            displayAs: displayAs(temporal["displayAs"]),
            hasArrived: parseTime(arrival?["realtimeActual"] as? String) != nil
        )
    }
```
2. In `stop(from:)`, delete the `let arrival`/`let departure` lines and replace the whole `return Stop(...)` with:
```swift
        return makeStop(station: station(from: locationJSON), temporal: temporal)
```
3. In `serviceSummary`, replace the `let thisStop = Stop(...)` block with:
```swift
        let thisStop = makeStop(station: atStation, temporal: temporal)
```

- [ ] **Step 6: Implement on Android**

In `android/app/src/main/java/com/trainalarm/app/model/Stop.kt`, replace the file with:
```kotlin
package com.trainalarm.app.model

import java.time.Instant

/**
 * What the provider says this location is for the train (RTT `displayAs`, a
 * per-location field). [Stop.displayAs] is null when the provider didn't say.
 */
enum class StopDisplay { CALL, CANCELLED, DIVERTED, STARTS, TERMINATES, PASS, UNKNOWN }

/**
 * One station within a [Journey]. Scheduled times come from the timetable;
 * estimated times are refined by live running data where available (may be
 * null if the provider has nothing live yet, or if this stop is in the
 * past). See docs/spec.md §4 for how these get updated as a journey runs.
 */
data class Stop(
    val station: Station,
    val scheduledArrival: Instant?,
    val scheduledDeparture: Instant?,
    val estimatedArrival: Instant?,
    val estimatedDeparture: Instant?,
    val isArrivalCancelled: Boolean = false,
    val isDepartureCancelled: Boolean = false,
    val displayAs: StopDisplay? = null,
    /** True once the train has arrived here (the arrival has a `realtimeActual`). */
    val hasArrived: Boolean = false
) {
    /** Either activity cancelled. Use the per-activity flags when the difference matters. */
    val isCancelled: Boolean
        get() = isArrivalCancelled || isDepartureCancelled

    /** Best-known arrival time: live estimate if we have one, else the timetable. */
    val bestArrival: Instant?
        get() = estimatedArrival ?: scheduledArrival
}
```

In `android/app/src/main/java/com/trainalarm/app/provider/RttProvider.kt`:

1. Add `import com.trainalarm.app.model.StopDisplay`, and after the imports add:
```kotlin
/**
 * Null for a missing key or a JSON null. Real Android org.json's
 * `optString(key, null)` returns the string "null" for a JSON null, so never use that form.
 */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key)
```
2. In `RttMapper`, replace `bestRealtime`, `scheduled` and `isCancelled` with:
```kotlin
    /** Best real-time instant for one activity (arrival/departure), per IndividualTemporalData. */
    private fun bestRealtime(temporal: JSONObject?): Instant? {
        if (temporal == null) return null
        return parseTime(temporal.optStringOrNull("realtimeActual"))
            ?: parseTime(temporal.optStringOrNull("realtimeForecast"))
            ?: parseTime(temporal.optStringOrNull("realtimeEstimate"))
    }

    private fun scheduled(temporal: JSONObject?): Instant? =
        parseTime(temporal?.optStringOrNull("scheduleAdvertised"))

    private fun isActivityCancelled(activity: JSONObject?): Boolean =
        activity?.optBoolean("isCancelled", false) ?: false

    /** Absent or JSON null is null; an unrecognised string is UNKNOWN. */
    private fun displayAs(raw: String?): StopDisplay? = when (raw) {
        null -> null
        "CALL" -> StopDisplay.CALL
        "CANCELLED" -> StopDisplay.CANCELLED
        "DIVERTED" -> StopDisplay.DIVERTED
        "STARTS" -> StopDisplay.STARTS
        "TERMINATES" -> StopDisplay.TERMINATES
        "PASS" -> StopDisplay.PASS
        else -> StopDisplay.UNKNOWN
    }

    /**
     * Builds a [Stop] from one location's `temporalData`. The `pass` activity is
     * deliberately not mapped: a PASS stop has null arrival and departure.
     */
    private fun makeStop(station: Station, temporal: JSONObject): Stop {
        val arrival = temporal.optJSONObject("arrival")
        val departure = temporal.optJSONObject("departure")
        return Stop(
            station = station,
            scheduledArrival = scheduled(arrival),
            scheduledDeparture = scheduled(departure),
            estimatedArrival = bestRealtime(arrival),
            estimatedDeparture = bestRealtime(departure),
            isArrivalCancelled = isActivityCancelled(arrival),
            isDepartureCancelled = isActivityCancelled(departure),
            displayAs = displayAs(temporal.optStringOrNull("displayAs")),
            hasArrived = parseTime(arrival?.optStringOrNull("realtimeActual")) != null
        )
    }
```
3. In `stop(...)`, replace the body after `val temporal = ...` (the `arrival`/`departure` vals and `return Stop(...)`) with `return makeStop(location, temporal)`.
4. In `serviceSummary`, replace the `val thisStop = Stop(...)` block with `val thisStop = makeStop(atStation, temporal)`.
5. Use the helper for the other optional strings too (spec §4: all optional string fields). In both `serviceSummary` and `fullService`, change `operatorName = scheduleMetadata.optJSONObject("operator")?.optString("name") ?: "Unknown",` to `operatorName = scheduleMetadata.optJSONObject("operator")?.optStringOrNull("name") ?: "Unknown",`. In `station(...)`, change `geographicLocation.optString("description", "UNKNOWN")` to `geographicLocation.optStringOrNull("description") ?: "UNKNOWN"` and `name = geographicLocation.optString("description", id),` to `name = geographicLocation.optStringOrNull("description") ?: id,`.

- [ ] **Step 7: Run both suites to verify they pass**

Same commands as Step 4. Expected: iOS `TEST SUCCEEDED` with `0 failures`; Android no output and exit code 0 (`echo $?` → `0`). Every previously existing test still passes (the old `isCancelled` assertions in `RttMapperTests`/`RttMapperTest` keep working through the computed property).

- [ ] **Step 8: Commit (local only, do not push)**

```bash
git add ios android
git diff --cached --stat   # secret check (.ai/git.md): no tokens, local.properties, *.xcodeproj, signing files
git commit -m "BugFix: Map per-activity cancellation, displayAs and hasArrived on Stop

Stage 2 needs to tell a cancelled arrival from a cancelled departure and to
see when the train no longer calls at a stop. Android JSON nulls now map to
null instead of the string \"null\".

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Task 2: `ProviderError`, and both mappers fail the same way (both platforms)

**Spec:** §1 (type, iOS rename, "where Android wraps `JSONException`", empty `locations`, `searchStations`). Network behaviour (HTTP, 204, cancellation) is Task 3.

**Files:**
- Create iOS: `ios/Sources/TrainAlarm/Providers/ProviderError.swift`, `ios/Tests/TrainAlarmTests/Provider/RttProviderTests.swift`
- Create Android: `.../provider/ProviderError.kt`, `android/app/src/test/java/com/trainalarm/app/provider/RttProviderTest.kt`
- Modify iOS: `Providers/RttProvider.swift` (delete `RttError`, rename its uses), `Tests/TrainAlarmTests/Provider/RttMapperTests.swift`
- Modify Android: `provider/RttProvider.kt` (mapper wrapping, `searchStations`), `provider/RttMapperTest.kt`

**Interfaces:**
- Consumes: Task 1's `Stop` and the `makeStop` helper (unchanged here).
- Produces: iOS `enum ProviderError: Error, Equatable { network(String), http(Int), malformed(String), notImplemented(String) }`. Android `sealed class ProviderError : Exception` with `Network(message, cause?)`, `Http(code)`, `Malformed(reason, cause?)`, `NotImplemented(message)`. After this task both mappers throw `ProviderError.malformed` / `Malformed` for a missing required field or an empty `locations` array, and `searchStations` throws `notImplemented` / `NotImplemented`. Task 3 adds the network cases.

- [ ] **Step 1: Write the failing iOS tests**

Create `ios/Tests/TrainAlarmTests/Provider/RttProviderTests.swift`:
```swift
import XCTest
@testable import TrainAlarm

final class RttProviderTests: XCTestCase {

    func testSearchStationsIsNotImplemented() async {
        let provider = RttProvider(accessToken: "test")
        do {
            _ = try await provider.searchStations(query: "Reading")
            XCTFail("expected ProviderError.notImplemented")
        } catch let error as ProviderError {
            guard case .notImplemented = error else {
                return XCTFail("expected notImplemented, got \(error)")
            }
        } catch {
            XCTFail("unexpected error \(error)")
        }
    }
}
```

In `RttMapperTests.swift`, rename every `RttError.malformedResponse` to `ProviderError.malformed` (lines 12, 16, 86, 87):
```bash
sed -i '' 's/RttError\.malformedResponse/ProviderError.malformed/g' ios/Tests/TrainAlarmTests/Provider/RttMapperTests.swift
```
and append inside the class:
```swift
    func testFullServiceThrowsMalformedForEmptyLocations() {
        let service: [String: Any] = [
            "scheduleMetadata": ["uniqueIdentity": "gb-nr:X:2026-09-13"],
            "locations": [[String: Any]](),
        ]
        XCTAssertThrowsError(try RttMapper.fullService(service)) { error in
            XCTAssertEqual(error as? ProviderError, .malformed("service had no locations"))
        }
    }

    func testStopThrowsMalformedWhenTemporalDataIsMissing() {
        let location: [String: Any] = ["location": ["description": "Reading"]]
        XCTAssertThrowsError(try RttMapper.stop(from: location)) { error in
            guard case ProviderError.malformed = error else {
                return XCTFail("expected malformed, got \(error)")
            }
        }
    }
```

- [ ] **Step 2: Write the failing Android tests**

Create `android/app/src/test/java/com/trainalarm/app/provider/RttProviderTest.kt`:
```kotlin
package com.trainalarm.app.provider

import kotlinx.coroutines.runBlocking
import org.junit.Test

class RttProviderTest {

    @Test(expected = ProviderError.NotImplemented::class)
    fun searchStations_isNotImplemented() {
        runBlocking { RttProvider("test").searchStations("Reading") }
    }
}
```

In `RttMapperTest.kt`: change the annotation of `fullService_throwsOnMissingLocationsField` to `@Test(expected = ProviderError.Malformed::class)`, add `import org.junit.Assert.assertThrows`, and append inside the class:
```kotlin
    @Test
    fun fullService_throwsMalformedForEmptyLocations() {
        val service = JSONObject(
            """{"scheduleMetadata": {"uniqueIdentity": "gb-nr:X:2026-09-13"}, "locations": []}"""
        )
        val error = assertThrows(ProviderError.Malformed::class.java) { RttMapper.fullService(service) }
        assertEquals("service had no locations", error.reason)
    }

    @Test(expected = ProviderError.Malformed::class)
    fun stop_throwsMalformedWhenTemporalDataIsMissing() {
        RttMapper.stop(JSONObject("""{"location": {"description": "Reading"}}"""))
    }
```

- [ ] **Step 3: Run both suites to verify they fail**

Commands as in Task 1 Step 4. Expected: iOS `cannot find 'ProviderError' in scope`; Android `Unresolved reference: ProviderError`.

- [ ] **Step 4: Implement on iOS**

Create `ios/Sources/TrainAlarm/Providers/ProviderError.swift`:
```swift
import Foundation

/// How a `TrainDataProvider` call can fail. Identical on every provider and on
/// both platforms, so tracking code can treat "the feed is down" the same
/// everywhere. Cancellation is NOT an error: it propagates as `CancellationError`.
enum ProviderError: Error, Equatable {
    /// Transport failure: no connection, timeout, DNS, TLS, or a non-HTTP response.
    case network(String)
    /// Non-2xx HTTP status.
    case http(Int)
    /// Body was not valid JSON, was empty on a 200, or a required field was missing.
    case malformed(String)
    /// A capability the provider deliberately lacks (e.g. free-text station search).
    case notImplemented(String)
}
```

Then rename (run from the repo root) and delete the old enum:
```bash
sed -i '' -e 's/RttError\.malformedResponse/ProviderError.malformed/g' \
          -e 's/RttError\.httpError/ProviderError.http/g' \
          -e 's/RttError\.notImplemented/ProviderError.notImplemented/g' \
          ios/Sources/TrainAlarm/Providers/RttProvider.swift
```
In `RttProvider.swift` delete the `enum RttError: Error { ... }` block at the top of the file (the `notImplemented`, `malformedResponse`, `httpError` cases). Verify: `grep -rn RttError ios` prints nothing.

- [ ] **Step 5: Implement on Android**

Create `android/app/src/main/java/com/trainalarm/app/provider/ProviderError.kt`:
```kotlin
package com.trainalarm.app.provider

/**
 * How a [TrainDataProvider] call can fail. Identical on every provider and on
 * both platforms. Cancellation is NOT an error: CancellationException propagates unchanged.
 */
sealed class ProviderError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Transport failure: no connection, timeout, DNS, TLS, interrupted read. */
    class Network(message: String, cause: Throwable? = null) : ProviderError(message, cause)

    /** Non-2xx HTTP status. */
    class Http(val code: Int) : ProviderError("HTTP $code")

    /** Body was not valid JSON, was empty on a 200, or a required field was missing. */
    class Malformed(val reason: String, cause: Throwable? = null) : ProviderError(reason, cause)

    /** A capability the provider deliberately lacks (e.g. free-text station search). */
    class NotImplemented(message: String) : ProviderError(message)
}
```

In `RttProvider.kt`: change `throw NotImplementedError(` to `throw ProviderError.NotImplemented(`; add `import org.json.JSONException`; in `RttMapper` add:
```kotlin
    /** Wraps org.json's JSONException (missing required field) so both mappers fail alike. */
    private inline fun <T> asMalformed(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: JSONException) {
            throw ProviderError.Malformed("$what: ${e.message}", e)
        }
```
and replace `stop`, `serviceSummary` and `fullService` with (note `fullService` now rejects an empty `locations` array before `stops.first()` can throw `NoSuchElementException`):
```kotlin
    /** Maps one entry of NetworkRailServiceLocations into a [Stop]. */
    fun stop(serviceLocation: JSONObject): Stop = asMalformed("service location") {
        val location = station(serviceLocation.getJSONObject("location"))
        makeStop(location, serviceLocation.getJSONObject("temporalData"))
    }

    /**
     * A /gb-nr/location entry only carries this one station's temporal data
     * plus origin/destination - not the full calling pattern. This builds a
     * single-stop Journey suitable for a departure-board picker screen;
     * call [RttProvider.serviceDetails] afterward for the full stop list.
     */
    fun serviceSummary(lineUp: JSONObject, atStation: Station): Service = asMalformed("location line-up") {
        val scheduleMetadata = lineUp.getJSONObject("scheduleMetadata")
        val thisStop = makeStop(atStation, lineUp.getJSONObject("temporalData"))
        val originPairs = lineUp.optJSONArray("origin")
        val destinationPairs = lineUp.optJSONArray("destination")
        val origin = if (originPairs != null && originPairs.length() > 0)
            station(originPairs.getJSONObject(0).getJSONObject("location")) else atStation
        val destination = if (destinationPairs != null && destinationPairs.length() > 0)
            station(destinationPairs.getJSONObject(0).getJSONObject("location")) else atStation

        Service(
            id = scheduleMetadata.getString("uniqueIdentity"),
            operatorName = scheduleMetadata.optJSONObject("operator")?.optStringOrNull("name") ?: "Unknown",
            journey = Journey(origin, destination, listOf(thisStop))
        )
    }

    /** Maps a full /gb-nr/service response body into a [Service] with its complete stop list. */
    fun fullService(service: JSONObject): Service = asMalformed("service") {
        val scheduleMetadata = service.getJSONObject("scheduleMetadata")
        val locations = service.getJSONArray("locations")
        if (locations.length() == 0) throw ProviderError.Malformed("service had no locations")
        val stops = (0 until locations.length()).map { i -> stop(locations.getJSONObject(i)) }

        val originPairs = service.optJSONArray("origin")
        val destinationPairs = service.optJSONArray("destination")
        val origin = if (originPairs != null && originPairs.length() > 0)
            station(originPairs.getJSONObject(0).getJSONObject("location")) else stops.first().station
        val destination = if (destinationPairs != null && destinationPairs.length() > 0)
            station(destinationPairs.getJSONObject(0).getJSONObject("location")) else stops.last().station

        Service(
            id = scheduleMetadata.getString("uniqueIdentity"),
            operatorName = scheduleMetadata.optJSONObject("operator")?.optStringOrNull("name") ?: "Unknown",
            journey = Journey(origin, destination, stops)
        )
    }
```

- [ ] **Step 6: Run both suites to verify they pass**

Commands as in Task 1 Step 4 (`xcodegen generate` is required here: this task adds new Swift files, and without it the new types are not found). Expected: iOS `TEST SUCCEEDED`; Android exit code 0. Then:
```bash
grep -rn "RttError\|NotImplementedError" ios/Sources ios/Tests android/app/src
```
Expected: no output.

- [ ] **Step 7: Commit (local only, do not push)**

```bash
git add ios android
git diff --cached --stat   # secret check (.ai/git.md)
git commit -m "BugFix: Add typed ProviderError and make both mappers fail the same way

Android had no typed error and threw JSONException or NoSuchElementException
for bad data, so Stage 2 could not report a failure kind identically on both
platforms. iOS's RttError becomes the provider-neutral ProviderError.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Task 3: Provider network errors, HTTP 204, cancellation, Android timeouts (both platforms)

**Spec:** §1 (iOS and Android wrapping, cancellation, HTTP 204, empty body), §3 (transport seam, timeouts). **Schema:** `downloads/RTT.GH.API-spec:1166-1167` (204 "no services found" on `/gb-nr/location`); `/gb-nr/service` documents only 200 and 404.

**Files:**
- Modify iOS: `ios/Sources/TrainAlarm/Providers/RttProvider.swift` (`getJSON`, `departureBoard`, `serviceDetails`), `ios/Tests/TrainAlarmTests/Provider/RttProviderTests.swift`
- Create Android: `.../provider/HttpTransport.kt`, `android/app/src/test/java/com/trainalarm/app/provider/HttpUrlConnectionTransportTest.kt`
- Modify Android: `.../provider/RttProvider.kt`, `.../provider/RttProviderTest.kt`

**Interfaces:**
- Consumes: Task 2's `ProviderError`.
- Produces: iOS `getJSON(path:query:) async throws -> [String: Any]?` (nil means HTTP 204). Android `interface HttpTransport { suspend fun get(url: String, headers: Map<String, String>): HttpResult }`, `data class HttpResult(val status: Int, val body: String)`, `class HttpUrlConnectionTransport` (default; `internal companion fun configure(connection, headers)`, `TIMEOUT_MS = 10_000`), and `RttProvider(accessToken: String, transport: HttpTransport = HttpUrlConnectionTransport())`.
- Behaviour after this task: non-2xx → `http(code)` / `Http(code)`; transport failure (`URLError`, `IOException` incl. `SocketTimeoutException`) and a non-HTTP response → `network` / `Network`; bad JSON, a JSON array, an empty 200 body, a missing `service` object → `malformed` / `Malformed`; 204 → `[]` for `departureBoard`, `malformed` for `serviceDetails`; cancellation is never wrapped (iOS `URLError(.cancelled)` → `CancellationError()`; Android `CancellationException` escapes).

- [ ] **Step 1: Write the failing iOS tests**

Replace `ios/Tests/TrainAlarmTests/Provider/RttProviderTests.swift` with (this keeps Task 2's test):
```swift
import XCTest
@testable import TrainAlarm

/// Serves canned responses so RttProvider's error mapping is tested without a network.
final class StubURLProtocol: URLProtocol {
    enum Reply {
        case response(status: Int, body: Data)
        case nonHTTP
        case fail(URLError.Code)
    }
    static var reply: Reply = .response(status: 200, body: Data())

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        switch Self.reply {
        case let .response(status, body):
            let http = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        case .nonHTTP:
            let response = URLResponse(url: request.url!, mimeType: nil, expectedContentLength: 0, textEncodingName: nil)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocolDidFinishLoading(self)
        case let .fail(code):
            client?.urlProtocol(self, didFailWithError: URLError(code))
        }
    }

    override func stopLoading() {}
}

final class RttProviderTests: XCTestCase {
    private let waterloo = Station(id: "WAT", name: "London Waterloo", latitude: 51.5031, longitude: -0.1132)

    private func makeProvider() -> RttProvider {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return RttProvider(accessToken: "test", session: URLSession(configuration: configuration))
    }

    private func reply(_ status: Int, _ body: String = "") {
        StubURLProtocol.reply = .response(status: status, body: Data(body.utf8))
    }

    private func thrownError(_ operation: () async throws -> Void) async -> Error? {
        do { try await operation(); return nil } catch { return error }
    }

    private func serviceDetailsError() async -> Error? {
        let provider = makeProvider()
        return await thrownError {
            _ = try await provider.serviceDetails(serviceId: "gb-nr:L01525:2026-09-13", date: Date())
        }
    }

    private func fixtureData(_ name: String) throws -> Data {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: "json", subdirectory: "Fixtures"))
        return try Data(contentsOf: url)
    }

    func testSearchStationsIsNotImplemented() async {
        let provider = RttProvider(accessToken: "test")
        do {
            _ = try await provider.searchStations(query: "Reading")
            XCTFail("expected ProviderError.notImplemented")
        } catch let error as ProviderError {
            guard case .notImplemented = error else {
                return XCTFail("expected notImplemented, got \(error)")
            }
        } catch {
            XCTFail("unexpected error \(error)")
        }
    }

    func testServiceDetailsParsesAGoodResponse() async throws {
        StubURLProtocol.reply = .response(status: 200, body: try fixtureData("gb-nr-service"))
        let service = try await makeProvider().serviceDetails(serviceId: "x", date: Date())
        XCTAssertEqual(service.id, "gb-nr:L01525:2026-09-13")
    }

    func testHttpErrorsCarryTheStatusCode() async {
        for status in [401, 404, 429, 500] {  // 401 bad token, 429 rate limit
            reply(status)
            let error = await serviceDetailsError()
            XCTAssertEqual(error as? ProviderError, .http(status))
        }
    }

    func testTransportFailureIsNetwork() async {
        StubURLProtocol.reply = .fail(.notConnectedToInternet)
        let error = await serviceDetailsError()
        guard case .network? = error as? ProviderError else { return XCTFail("expected network, got \(String(describing: error))") }
    }

    func testNonHTTPResponseIsNetwork() async {
        StubURLProtocol.reply = .nonHTTP
        let error = await serviceDetailsError()
        guard case .network? = error as? ProviderError else { return XCTFail("expected network, got \(String(describing: error))") }
    }

    func testCancelledRequestIsCancellationErrorNotNetwork() async {
        StubURLProtocol.reply = .fail(.cancelled)
        let error = await serviceDetailsError()
        XCTAssertTrue(error is CancellationError, "got \(String(describing: error))")
    }

    func testNonJsonBodyIsMalformed() async {
        reply(200, "<html>oops</html>")
        let error = await serviceDetailsError()
        guard case .malformed? = error as? ProviderError else { return XCTFail("expected malformed, got \(String(describing: error))") }
    }

    func testEmptyOkBodyIsMalformed() async {
        reply(200, "")
        let error = await serviceDetailsError()
        guard case .malformed? = error as? ProviderError else { return XCTFail("expected malformed, got \(String(describing: error))") }
    }

    func testJsonArrayBodyIsMalformed() async {
        reply(200, "[]")
        let error = await serviceDetailsError()
        guard case .malformed? = error as? ProviderError else { return XCTFail("expected malformed, got \(String(describing: error))") }
    }

    func testMissingServiceObjectIsMalformed() async {
        reply(200, "{}")
        let error = await serviceDetailsError()
        XCTAssertEqual(error as? ProviderError, .malformed("missing 'service' object"))
    }

    func testServiceDetails204IsMalformed() async {
        reply(204)
        let error = await serviceDetailsError()
        XCTAssertEqual(error as? ProviderError, .malformed("empty (204) response"))
    }

    func testDepartureBoard204IsAnEmptyBoardNotAnError() async throws {
        reply(204)
        let board = try await makeProvider().departureBoard(station: waterloo, from: Date())
        XCTAssertEqual(board, [])
    }
}
```

- [ ] **Step 2: Write the failing Android tests**

Replace `android/app/src/test/java/com/trainalarm/app/provider/RttProviderTest.kt` with (keeps Task 2's test):
```kotlin
package com.trainalarm.app.provider

import com.trainalarm.app.model.Station
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

private class FakeTransport(private val reply: () -> HttpResult) : HttpTransport {
    var lastHeaders: Map<String, String> = emptyMap()

    override suspend fun get(url: String, headers: Map<String, String>): HttpResult {
        lastHeaders = headers
        return reply()
    }
}

class RttProviderTest {
    private val waterloo = Station("WAT", "London Waterloo", 51.5031, -0.1132)

    private fun provider(reply: () -> HttpResult) = RttProvider("token", FakeTransport(reply))

    /** Runs [block] and returns whatever it threw (caught inside the coroutine), or null. */
    private fun thrown(block: suspend () -> Unit): Throwable? = runBlocking {
        try {
            block()
            null
        } catch (e: Throwable) {
            e
        }
    }

    private fun serviceDetailsError(reply: () -> HttpResult): Throwable? =
        thrown { provider(reply).serviceDetails("gb-nr:L01525:2026-09-13", LocalDate.of(2026, 9, 13)) }

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().readText()

    @Test(expected = ProviderError.NotImplemented::class)
    fun searchStations_isNotImplemented() {
        runBlocking { RttProvider("test").searchStations("Reading") }
    }

    @Test
    fun serviceDetails_parsesAGoodResponse() = runBlocking<Unit> {
        val service = provider { HttpResult(200, fixture("gb-nr-service.json")) }
            .serviceDetails("x", LocalDate.of(2026, 9, 13))
        assertEquals("gb-nr:L01525:2026-09-13", service.id)
    }

    @Test
    fun request_carriesTheBearerTokenAndAcceptHeader() = runBlocking<Unit> {
        val transport = FakeTransport { HttpResult(200, fixture("gb-nr-service.json")) }
        RttProvider("token", transport).serviceDetails("x", LocalDate.of(2026, 9, 13))
        assertEquals("Bearer token", transport.lastHeaders["Authorization"])
        assertEquals("application/json", transport.lastHeaders["Accept"])
    }

    @Test
    fun httpErrors_carryTheStatusCode() {
        for (status in listOf(401, 404, 429, 500)) {  // 401 bad token, 429 rate limit
            val error = serviceDetailsError { HttpResult(status, "") }
            assertTrue("status $status gave $error", error is ProviderError.Http)
            assertEquals(status, (error as ProviderError.Http).code)
        }
    }

    @Test
    fun ioFailure_isNetwork() {
        assertTrue(serviceDetailsError { throw IOException("boom") } is ProviderError.Network)
    }

    @Test
    fun socketTimeout_isNetworkSoThePollLoopCanRetry() {
        assertTrue(serviceDetailsError { throw SocketTimeoutException("read timed out") } is ProviderError.Network)
    }

    @Test
    fun cancellation_propagatesUnwrapped() {
        val error = serviceDetailsError { throw CancellationException("stop") }
        assertTrue("got $error", error is CancellationException)
    }

    @Test
    fun nonJsonBody_isMalformed() {
        assertTrue(serviceDetailsError { HttpResult(200, "<html>oops</html>") } is ProviderError.Malformed)
    }

    @Test
    fun emptyOkBody_isMalformed() {
        assertTrue(serviceDetailsError { HttpResult(200, "") } is ProviderError.Malformed)
    }

    @Test
    fun jsonArrayBody_isMalformed() {
        assertTrue(serviceDetailsError { HttpResult(200, "[]") } is ProviderError.Malformed)
    }

    @Test
    fun missingServiceObject_isMalformed() {
        val error = serviceDetailsError { HttpResult(200, "{}") }
        assertTrue("got $error", error is ProviderError.Malformed)
        assertEquals("missing 'service' object", (error as ProviderError.Malformed).reason)
    }

    @Test
    fun serviceDetails204_isMalformed() {
        val error = serviceDetailsError { HttpResult(204, "") }
        assertTrue("got $error", error is ProviderError.Malformed)
        assertEquals("empty (204) response", (error as ProviderError.Malformed).reason)
    }

    @Test
    fun departureBoard204_isAnEmptyBoardNotAnError() = runBlocking<Unit> {
        val board = provider { HttpResult(204, "") }.departureBoard(waterloo, Instant.parse("2026-09-13T08:00:00Z"))
        assertEquals(emptyList<Any>(), board)
    }
}
```

Create `android/app/src/test/java/com/trainalarm/app/provider/HttpUrlConnectionTransportTest.kt`. An unconnected `HttpURLConnection` can be built without any network, so the timeouts are tested directly:
```kotlin
package com.trainalarm.app.provider

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class HttpUrlConnectionTransportTest {

    @Test
    fun configure_setsTenSecondTimeoutsMethodAndHeaders() {
        val connection = URL("https://example.invalid/").openConnection() as HttpURLConnection
        HttpUrlConnectionTransport.configure(connection, mapOf("Accept" to "application/json"))
        assertEquals(10_000, connection.connectTimeout)
        assertEquals(10_000, connection.readTimeout)
        assertEquals("GET", connection.requestMethod)
        assertEquals("application/json", connection.getRequestProperty("Accept"))
    }
}
```

- [ ] **Step 3: Run both suites to verify they fail**

Commands as in Task 1 Step 4. Expected: iOS compiles, and these fail: `testTransportFailureIsNetwork` and `testCancelledRequestIsCancellationErrorNotNetwork` (a raw `URLError` escapes), `testNonHTTPResponseIsNetwork`, `testNonJsonBodyIsMalformed` and `testEmptyOkBodyIsMalformed` (a raw JSON `NSError` escapes), `testServiceDetails204IsMalformed` and `testDepartureBoard204IsAnEmptyBoardNotAnError` (a JSON error on the empty body). `testHttpErrorsCarryTheStatusCode`, `testJsonArrayBodyIsMalformed`, `testMissingServiceObjectIsMalformed` and the good-response test already pass: they pin behaviour that must not regress. Android fails to compile: `Unresolved reference: HttpTransport`, `HttpResult`, `HttpUrlConnectionTransport`.

- [ ] **Step 4: Implement on iOS**

In `ios/Sources/TrainAlarm/Providers/RttProvider.swift`, replace `departureBoard`, `serviceDetails` and `getJSON` (everything between `searchStations` and the end of `final class RttProvider`) with:
```swift
    func departureBoard(station: Station, from: Date) async throws -> [Service] {
        let iso = ISO8601DateFormatter().string(from: from)
        // 204 is "valid query, no services found": an empty board, not an error.
        guard let json = try await getJSON(path: "/gb-nr/location", query: ["code": station.id, "timeFrom": iso]) else {
            return []
        }
        let services = (json["services"] as? [Any] ?? []).compactMap { $0 as? [String: Any] }
        return try services.map { try RttMapper.serviceSummary($0, atStation: station) }
    }

    /// `date` is currently ignored: `serviceId` already identifies the day. Reuse `service.id` when polling.
    func serviceDetails(serviceId: String, date: Date) async throws -> Service {
        guard let json = try await getJSON(path: "/gb-nr/service", query: ["uniqueIdentity": serviceId]) else {
            throw ProviderError.malformed("empty (204) response")
        }
        guard let service = json["service"] as? [String: Any] else {
            throw ProviderError.malformed("missing 'service' object")
        }
        return try RttMapper.fullService(service)
    }

    /// Returns nil for HTTP 204. Maps every failure to `ProviderError`, except
    /// cancellation, which must stay a `CancellationError` so callers can stop cleanly.
    private func getJSON(path: String, query: [String: String]) async throws -> [String: Any]? {
        var components = URLComponents(url: baseUrl.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        components.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }

        var request = URLRequest(url: components.url!)
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let result: (Data, URLResponse)
        do {
            result = try await session.data(for: request)
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch let error as URLError {
            throw ProviderError.network(error.localizedDescription)
        }
        let (data, response) = result
        guard let http = response as? HTTPURLResponse else {
            throw ProviderError.network("response was not HTTP")
        }
        if http.statusCode == 204 { return nil }
        guard (200...299).contains(http.statusCode) else {
            throw ProviderError.http(http.statusCode)
        }
        guard !data.isEmpty else {
            throw ProviderError.malformed("empty response body")
        }
        let object: Any
        do {
            object = try JSONSerialization.jsonObject(with: data)
        } catch {
            throw ProviderError.malformed("invalid JSON: \(error.localizedDescription)")
        }
        guard let json = object as? [String: Any] else {
            throw ProviderError.malformed("top-level response was not a JSON object")
        }
        return json
    }
```
Also add to the `date` parameter's doc in `ios/Sources/TrainAlarm/Providers/TrainDataProvider.swift` (above `serviceDetails`): `/// \`date\` is currently ignored: \`serviceId\` already identifies the day. Reuse \`service.id\` when polling.`

- [ ] **Step 5: Implement on Android**

Create `android/app/src/main/java/com/trainalarm/app/provider/HttpTransport.kt`:
```kotlin
package com.trainalarm.app.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** A status code and body. Also returned for 4xx/5xx, so the caller can report `Http(code)`. */
data class HttpResult(val status: Int, val body: String)

/** The one place [RttProvider] touches the network, so tests can fake it. */
interface HttpTransport {
    /** Throws IOException for transport failures (including timeouts); never wraps cancellation. */
    suspend fun get(url: String, headers: Map<String, String>): HttpResult
}

class HttpUrlConnectionTransport : HttpTransport {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResult =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            configure(connection, headers)
            try {
                val status = connection.responseCode
                // inputStream throws on 4xx/5xx; errorStream carries that body.
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                HttpResult(status, stream?.bufferedReader()?.use(BufferedReader::readText) ?: "")
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        const val TIMEOUT_MS = 10_000

        internal fun configure(connection: HttpURLConnection, headers: Map<String, String>) {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        }
    }
}
```

In `RttProvider.kt`: remove the now-unused imports (`Dispatchers`, `withContext`, `BufferedReader`, `HttpURLConnection`, `URL`) and add `import java.io.IOException`. Replace the class declaration and everything inside `RttProvider` (up to its closing brace; `RttMapper` below it is untouched) with the following. `searchStations` is included unchanged from Task 2 so nothing is lost:
```kotlin
class RttProvider(
    private val accessToken: String,
    private val transport: HttpTransport = HttpUrlConnectionTransport()
) : TrainDataProvider {

    private val baseUrl = "https://data.rtt.io"

    override suspend fun searchStations(query: String): List<Station> {
        // RTT's API is location-code based (CRS/TIPLOC), not a free-text station
        // search endpoint - see docs/spec.md §3. Left unimplemented rather than guessed.
        throw ProviderError.NotImplemented(
            "RTT has no free-text station search endpoint; resolve to a " +
                "CRS/TIPLOC code via a static station list first."
        )
    }

    override suspend fun departureBoard(station: Station, from: Instant): List<Service> {
        // 204 is "valid query, no services found": an empty board, not an error.
        val json = getJson(
            "/gb-nr/location",
            mapOf("code" to station.id, "timeFrom" to from.toString())
        ) ?: return emptyList()
        val services = json.optJSONArray("services") ?: JSONArray()
        return (0 until services.length())
            .mapNotNull { services.optJSONObject(it) }
            .map { RttMapper.serviceSummary(it, station) }
    }

    /** [date] is currently ignored: [serviceId] already identifies the day. Reuse `service.id` when polling. */
    override suspend fun serviceDetails(serviceId: String, date: java.time.LocalDate): Service {
        val json = getJson("/gb-nr/service", mapOf("uniqueIdentity" to serviceId))
            ?: throw ProviderError.Malformed("empty (204) response")
        val service = json.optJSONObject("service")
            ?: throw ProviderError.Malformed("missing 'service' object")
        return RttMapper.fullService(service)
    }

    /**
     * Returns null for HTTP 204. Only IOException and JSONException are caught, never
     * Exception, so CancellationException propagates unchanged.
     */
    private suspend fun getJson(path: String, query: Map<String, String>): JSONObject? {
        val qs = query.entries.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val result = try {
            transport.get(
                "$baseUrl$path?$qs",
                mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json")
            )
        } catch (e: IOException) {
            throw ProviderError.Network(e.message ?: "I/O failure", e)
        }
        if (result.status == 204) return null
        if (result.status !in 200..299) throw ProviderError.Http(result.status)
        if (result.body.isBlank()) throw ProviderError.Malformed("empty response body")
        return try {
            JSONObject(result.body)
        } catch (e: JSONException) {
            throw ProviderError.Malformed("invalid JSON: ${e.message}", e)
        }
    }
}
```
Also add the same `date` note to the `serviceDetails` KDoc in `TrainDataProvider.kt`.

- [ ] **Step 6: Run both suites to verify they pass**

Commands as in Task 1 Step 4. Expected: iOS `TEST SUCCEEDED`; Android exit code 0.

- [ ] **Step 7: Commit (local only, do not push)**

```bash
git add ios android
git diff --cached --stat   # secret check (.ai/git.md)
git commit -m "BugFix: Map provider network failures to ProviderError and add Android timeouts

A cancelled poll no longer looks like a network error on iOS, an HTTP 204
from the departure board is an empty list, and Android requests time out
after 10 seconds instead of hanging the poll loop. RttProvider on Android
takes an injectable HttpTransport so these paths are unit-testable.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Task 4: Departure board fixture and tests (both platforms)

**Spec:** Testing section (`gb-nr-location.json`, `serviceSummary` fallback test). **Schema to read first:** `downloads/RTT.GH.API-spec:454-507` (`NetworkRailLocationLineUpObject`: `scheduleMetadata`, `temporalData`, `origin[]`, `destination[]`) and `:1130-1165` (response: `services[]`).

This task tests code that already exists (`RttMapper.serviceSummary` from Tasks 1-2, `departureBoard` from Task 3), so the new tests are expected to pass on the first run. Step 5 proves they actually test something by temporarily breaking the code.

**Files:**
- Create (both fixture folders, byte-identical): `gb-nr-location.json`
- Modify iOS: `ios/Tests/TrainAlarmTests/Provider/RttMapperTests.swift`, `.../RttProviderTests.swift`
- Modify Android: `.../provider/RttMapperTest.kt`, `.../provider/RttProviderTest.kt`

**Interfaces:**
- Consumes: `RttMapper.serviceSummary(_:atStation:)`, `RttProvider.departureBoard(station:from:)`, `StubURLProtocol` and `fixtureData` (iOS, Task 3), `FakeTransport` and `fixture(...)` (Android, Task 3).
- Produces: nothing new (tests and a fixture only).

- [ ] **Step 1: Create the fixture (schema-built, not captured)**

Write `ios/Tests/TrainAlarmTests/Fixtures/gb-nr-location.json`, then copy it to `android/app/src/test/resources/fixtures/`:
```json
{
  "query": {
    "location": { "namespace": "gb-nr", "description": "London Waterloo", "shortCodes": ["WAT"], "longCodes": ["WATRLMN"] },
    "timeFrom": "2026-09-13T14:00:00Z"
  },
  "services": [
    {
      "scheduleMetadata": {
        "uniqueIdentity": "gb-nr:L02001:2026-09-13",
        "namespace": "gb-nr",
        "identity": "L02001",
        "departureDate": "2026-09-13",
        "operator": { "code": "SW", "name": "South Western Railway" },
        "modeType": "TRAIN",
        "inPassengerService": true
      },
      "temporalData": {
        "departure": { "scheduleAdvertised": "2026-09-13T14:00:00Z", "realtimeForecast": "2026-09-13T14:02:00Z", "isCancelled": false },
        "displayAs": "CALL"
      },
      "origin": [
        { "location": { "namespace": "gb-nr", "description": "London Waterloo", "shortCodes": ["WAT"], "longCodes": ["WATRLMN"] } }
      ],
      "destination": [
        { "location": { "namespace": "gb-nr", "description": "Basingstoke", "shortCodes": ["BSK"], "longCodes": ["BASINGS"] } }
      ]
    },
    {
      "scheduleMetadata": {
        "uniqueIdentity": "gb-nr:L02002:2026-09-13",
        "namespace": "gb-nr",
        "identity": "L02002",
        "departureDate": "2026-09-13",
        "operator": { "code": "SW", "name": "South Western Railway" },
        "modeType": "TRAIN",
        "inPassengerService": true
      },
      "temporalData": {
        "departure": { "scheduleAdvertised": "2026-09-13T14:30:00Z", "isCancelled": true, "cancellationReasonCode": "TB" },
        "displayAs": "CANCELLED"
      },
      "origin": [
        { "location": { "namespace": "gb-nr", "description": "London Waterloo", "shortCodes": ["WAT"], "longCodes": ["WATRLMN"] } }
      ],
      "destination": [
        { "location": { "namespace": "gb-nr", "description": "Southampton Central", "shortCodes": ["SOU"], "longCodes": ["SOTON"] } }
      ]
    }
  ]
}
```
```bash
cp ios/Tests/TrainAlarmTests/Fixtures/gb-nr-location.json android/app/src/test/resources/fixtures/
diff -r ios/Tests/TrainAlarmTests/Fixtures android/app/src/test/resources/fixtures && echo fixtures identical
```
Expected: `fixtures identical`.

- [ ] **Step 2: Write the iOS tests**

Append inside `RttMapperTests` in `RttMapperTests.swift`:
```swift
    private let waterloo = Station(id: "WAT", name: "London Waterloo", latitude: 51.5031, longitude: -0.1132)

    private func lineUps() throws -> [[String: Any]] {
        try loadFixture("gb-nr-location")["services"] as! [[String: Any]]
    }

    func testServiceSummaryMapsALineUpEntry() throws {
        let service = try RttMapper.serviceSummary(try lineUps()[0], atStation: waterloo)

        XCTAssertEqual(service.id, "gb-nr:L02001:2026-09-13")
        XCTAssertEqual(service.operatorName, "South Western Railway")
        XCTAssertEqual(service.journey.origin.id, "WAT")
        XCTAssertEqual(service.journey.destination.id, "BSK")
        XCTAssertEqual(service.journey.stops.count, 1)
        let stop = service.journey.stops[0]
        let formatter = ISO8601DateFormatter()
        XCTAssertEqual(stop.scheduledDeparture, formatter.date(from: "2026-09-13T14:00:00Z"))
        XCTAssertEqual(stop.estimatedDeparture, formatter.date(from: "2026-09-13T14:02:00Z"))
        XCTAssertEqual(stop.displayAs, .call)
        XCTAssertFalse(stop.isCancelled)
    }

    func testServiceSummaryMapsACancelledLineUp() throws {
        let service = try RttMapper.serviceSummary(try lineUps()[1], atStation: waterloo)

        XCTAssertEqual(service.journey.destination.id, "SOU")
        let stop = service.journey.stops[0]
        XCTAssertFalse(stop.isArrivalCancelled)
        XCTAssertTrue(stop.isDepartureCancelled)
        XCTAssertEqual(stop.displayAs, .cancelled)
    }

    func testServiceSummaryFallsBackToTheBoardStationWithoutOriginOrDestination() throws {
        let lineUp: [String: Any] = [
            "scheduleMetadata": ["uniqueIdentity": "gb-nr:X:2026-09-13"],
            "temporalData": ["departure": ["scheduleAdvertised": "2026-09-13T14:00:00Z"]],
        ]
        let service = try RttMapper.serviceSummary(lineUp, atStation: waterloo)
        XCTAssertEqual(service.journey.origin.id, "WAT")
        XCTAssertEqual(service.journey.destination.id, "WAT")
    }

    func testServiceSummaryThrowsMalformedWithoutScheduleMetadata() {
        let lineUp: [String: Any] = ["temporalData": [String: Any]()]
        XCTAssertThrowsError(try RttMapper.serviceSummary(lineUp, atStation: waterloo)) { error in
            guard case ProviderError.malformed = error else {
                return XCTFail("expected malformed, got \(error)")
            }
        }
    }
```
Append inside `RttProviderTests` in `RttProviderTests.swift`:
```swift
    func testDepartureBoardReturnsEveryLineUp() async throws {
        StubURLProtocol.reply = .response(status: 200, body: try fixtureData("gb-nr-location"))
        let board = try await makeProvider().departureBoard(station: waterloo, from: Date())
        XCTAssertEqual(board.map(\.id), ["gb-nr:L02001:2026-09-13", "gb-nr:L02002:2026-09-13"])
    }

    func testDepartureBoardWithNoServicesKeyIsEmpty() async throws {
        reply(200, "{}")
        let board = try await makeProvider().departureBoard(station: waterloo, from: Date())
        XCTAssertEqual(board, [])
    }
```

- [ ] **Step 3: Write the Android tests**

Append inside `RttMapperTest` in `RttMapperTest.kt` (`Station` is already imported by Task 1):
```kotlin
    private val waterloo = Station("WAT", "London Waterloo", 51.5031, -0.1132)

    private fun lineUp(index: Int): JSONObject =
        loadFixture("gb-nr-location.json").getJSONArray("services").getJSONObject(index)

    @Test
    fun serviceSummary_mapsALineUpEntry() {
        val service = RttMapper.serviceSummary(lineUp(0), waterloo)

        assertEquals("gb-nr:L02001:2026-09-13", service.id)
        assertEquals("South Western Railway", service.operatorName)
        assertEquals("WAT", service.journey.origin.id)
        assertEquals("BSK", service.journey.destination.id)
        assertEquals(1, service.journey.stops.size)
        val stop = service.journey.stops[0]
        assertEquals(Instant.parse("2026-09-13T14:00:00Z"), stop.scheduledDeparture)
        assertEquals(Instant.parse("2026-09-13T14:02:00Z"), stop.estimatedDeparture)
        assertEquals(StopDisplay.CALL, stop.displayAs)
        assertFalse(stop.isCancelled)
    }

    @Test
    fun serviceSummary_mapsACancelledLineUp() {
        val service = RttMapper.serviceSummary(lineUp(1), waterloo)

        assertEquals("SOU", service.journey.destination.id)
        val stop = service.journey.stops[0]
        assertFalse(stop.isArrivalCancelled)
        assertTrue(stop.isDepartureCancelled)
        assertEquals(StopDisplay.CANCELLED, stop.displayAs)
    }

    @Test
    fun serviceSummary_fallsBackToTheBoardStationWithoutOriginOrDestination() {
        val bare = JSONObject(
            """{"scheduleMetadata": {"uniqueIdentity": "gb-nr:X:2026-09-13"},
                "temporalData": {"departure": {"scheduleAdvertised": "2026-09-13T14:00:00Z"}}}"""
        )
        val service = RttMapper.serviceSummary(bare, waterloo)
        assertEquals("WAT", service.journey.origin.id)
        assertEquals("WAT", service.journey.destination.id)
    }

    @Test(expected = ProviderError.Malformed::class)
    fun serviceSummary_throwsMalformedWithoutScheduleMetadata() {
        RttMapper.serviceSummary(JSONObject("""{"temporalData": {}}"""), waterloo)
    }
```
Append inside `RttProviderTest` in `RttProviderTest.kt`:
```kotlin
    @Test
    fun departureBoard_returnsEveryLineUp() = runBlocking<Unit> {
        val board = provider { HttpResult(200, fixture("gb-nr-location.json")) }
            .departureBoard(waterloo, Instant.parse("2026-09-13T14:00:00Z"))
        assertEquals(listOf("gb-nr:L02001:2026-09-13", "gb-nr:L02002:2026-09-13"), board.map { it.id })
    }

    @Test
    fun departureBoard_withNoServicesKey_isEmpty() = runBlocking<Unit> {
        val board = provider { HttpResult(200, "{}") }.departureBoard(waterloo, Instant.parse("2026-09-13T14:00:00Z"))
        assertEquals(emptyList<Any>(), board)
    }
```

- [ ] **Step 4: Run both suites to verify they pass**

Commands as in Task 1 Step 4. Expected: iOS `TEST SUCCEEDED`; Android exit code 0.

- [ ] **Step 5: Prove the new tests can fail (mutation check)**

Temporarily break the mapper on each platform, confirm the new tests catch it, then restore from git (the source files are unmodified relative to the Task 3 commit, so this is safe):

1. iOS: in `serviceSummary` only (the first occurrence, the line ending `?? atStation`; `fullService` has the same text), change `originPairs?.first` to `destinationPairs?.first`. Run `xcodebuild test ... -only-testing:TrainAlarmTests/RttMapperTests`. Expected: `testServiceSummaryMapsALineUpEntry` FAILS (origin is `BSK`, not `WAT`). Restore: `git checkout -- ios/Sources/TrainAlarm/Providers/RttProvider.swift`.
2. Android: in `departureBoard`, change `.mapNotNull { services.optJSONObject(it) }` to `.take(1).mapNotNull { services.optJSONObject(it) }`. Run `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew test --offline -q` (from `android/`). Expected: `departureBoard_returnsEveryLineUp` FAILS. Restore: `git checkout -- android/app/src/main/java/com/trainalarm/app/provider/RttProvider.kt`.
3. Re-run both suites: green again. `git status --short` shows only the test and fixture files.

- [ ] **Step 6: Commit (local only, do not push)**

```bash
git add ios android
git diff --cached --stat   # secret check (.ai/git.md)
git commit -m "Test: Cover the departure board path on both platforms

serviceSummary and departureBoard had no tests on either platform. Adds a
/gb-nr/location fixture built from the real schema and mapper and provider
tests, including the no-services and missing-origin cases.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Task 5: Docs and final verification

**Spec:** §6 (docs) and the acceptance checklist. Docs describe what the code now does; do not claim more.

**Files:**
- Modify: `docs/spec.md` (§8, Stage 1 entry, lines 120-124), `.ai/tech-stack.md` (lines 38-48, "Error handling"), `.ai/testing-guide.md` (Shared rules, line ~56), `README.md` (lines 26-27)
- No `CHANGELOG.md` exists in this repo, so no changelog entry.

**Interfaces:** consumes everything from Tasks 1-4; produces docs only.

- [ ] **Step 1: Update `docs/spec.md` §8, Stage 1**

In `docs/spec.md`, replace lines 122-124:
```
   `RttProvider` (departure board + full service lookup) on both
   platforms, all unit-tested against a fixture built from RTT's real
   verified schema (§3). The station dataset is now built (`data/stations.json`,
```
with:
```
   `RttProvider` (departure board + full service lookup) on both
   platforms, unit-tested against fixtures built from RTT's real verified
   schema (§3), including the departure board (no real captured RTT
   responses are in the repo yet). A pre-Stage-2 audit added a typed
   `ProviderError` (network / http / malformed / notImplemented) shared by
   both platforms, per-activity cancellation, `displayAs` and `hasArrived`
   on `Stop`, 10s Android timeouts, and HTTP 204 handling (an empty
   departure board). The station dataset is now built (`data/stations.json`,
```

- [ ] **Step 2: Update `.ai/tech-stack.md` "Error handling"**

Replace the bullets from `- iOS: typed \`RttError\` enum` through `so both platforms fail the same way.` (lines 41-46) with:
```
- Both platforms: a typed `ProviderError` — `network`, `http(code)`,
  `malformed(reason)`, `notImplemented`. Mappers throw `malformed` on a
  missing required field or an empty `locations` array; they don't return
  partial data. Cancellation is never wrapped (`CancellationError` /
  `CancellationException` propagate). HTTP 204 from the departure board is an
  empty list, not an error.
- Android requests have 10s connect and read timeouts; `HttpTransport` is
  injectable so the provider can be tested without a network.
```
Keep the first bullet (`Provider methods are ...`, line 40) and the last bullet (`Live-change handling ... modelled as data`, line 47).

In the same file's "Shared rules", change the fixtures bullet's `(\`gb-nr-service*.json\`)` to `(\`gb-nr-*.json\`)`, because `gb-nr-location.json` is added.

- [ ] **Step 3: Update `.ai/testing-guide.md`**

In "Shared rules", after the `Alarm delivery (Stage 3)` bullet, add:
```
- **Provider network tests** use no mocking library: iOS passes an ephemeral
  `URLSession` whose `protocolClasses` is a `URLProtocol` stub
  (`StubURLProtocol`) to `RttProvider`; Android passes a fake `HttpTransport`.
- In a fresh git worktree `android/local.properties` is absent (it is
  gitignored), so run Gradle with the SDK path set:
  `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew test --offline`.
```
In the `## TBD` list, replace the bullet beginning `- Mocking approach for \`TrainDataProvider\`` (line 62, the last bullet in the file) with:
```
- Mocking approach for `TrainDataProvider` itself (Stage 2 will use hand-written fakes).
```

- [ ] **Step 4: Update `README.md`**

Replace lines 26-27:
```
Stage 0 — repo scaffold. See `docs/spec.md` §9 (Development project plan)
for the full stage list.
```
with:
```
Stage 1 done (journey model, UK provider, station data); Stage 2 (tracking,
ETA and live-change logic) is next. See `docs/spec.md` §8 for the full stage
list.
```

- [ ] **Step 5: Final verification (fresh evidence for every claim)**

```bash
# the old names are gone everywhere
grep -rn "RttError\|NotImplementedError" ios android .ai docs/spec.md README.md
# fixtures are the same on both platforms
diff -r ios/Tests/TrainAlarmTests/Fixtures android/app/src/test/resources/fixtures && echo fixtures identical
# full suites, both platforms
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=<name>" 2>&1 | grep -E "Executed|TEST (FAILED|SUCCEEDED)"
cd ../android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew test --offline -q; echo "android exit: $?"
# only the intended commits exist locally
cd .. && git log origin/main..HEAD --oneline
```
Expected: the grep prints nothing; `fixtures identical`; iOS `TEST SUCCEEDED` with `0 failures`; `android exit: 0`; the log lists the spec commit, the plan commit, and the Task 1-4 commits (and this task's, once committed).

- [ ] **Step 6: Commit (local only, do not push)**

```bash
git add docs/spec.md .ai README.md
git diff --cached --stat   # secret check (.ai/git.md)
git commit -m "Docs: Update Stage 1 docs for the audit fixes

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

