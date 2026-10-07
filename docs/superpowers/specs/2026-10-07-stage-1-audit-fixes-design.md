# Stage 1 audit fixes — design (rev 2)

Date: 2026-10-07. An independent audit of Stage 1 (before Stage 2 starts) found gaps
that Stage 2's tracker depends on. This PR fixes them on both platforms. The names and
shapes here are the ones the Stage 2 spec (branch `feature/tracking-eta-live-change`,
section "Prerequisite") relies on; do not rename them without updating that spec.
Rev 2 applies the audit of rev 1.

## Acceptance criterion ("ships when")

On both platforms, all of the following hold, and iOS and Android CI are green:

- Provider failures surface as the same typed `ProviderError`; cancellation is never
  turned into a `ProviderError`.
- `Stop` carries per-activity cancellation, `displayAs` and `hasArrived`, mapped from the
  real RTT schema.
- The departure-board path (`departureBoard`, `serviceSummary`) is tested, including
  HTTP 204 returning an empty list.
- Android requests have 10s connect and read timeouts (verified by review; the fake
  transport cannot observe them).
- Every other behaviour change is covered by a test that fails without it. The one
  exception is Android JSON-null handling on the JVM, covered by a helper test that
  simulates Android's behaviour (see §4).
- Doc checklist: `docs/spec.md` §8 no longer overstates test coverage; `.ai/tech-stack.md`
  no longer names `RttError` or lists the typed error as TBD; `README.md` status line is
  correct; `grep -rn "RttError\|NotImplementedError" ios android .ai docs/spec.md README.md`
  finds nothing.

## Verified facts (read from the repo, not assumed)

- iOS has `RttError { notImplemented, malformedResponse, httpError }`
  (`RttProvider.swift:3-7`). `URLError`s come out of `session.data(for:)` (`:62`) and
  `JSONSerialization` failures out of `:66`, both unwrapped. A non-`HTTPURLResponse`
  silently skips the status check (`:63`).
- Android has no typed error. `getJson` reads `connection.inputStream` (`RttProvider.kt:75`),
  so a 4xx/5xx surfaces as a message-only `IOException`; bad JSON as `JSONException`;
  `searchStations` throws `NotImplementedError`, an `Error`, not an `Exception` (`:39`).
  There are no connect or read timeouts (`:70-76`).
- `isCancelled` ORs arrival and departure cancellation on both platforms
  (`RttProvider.swift:114-118`, `RttProvider.kt:126-131`). `displayAs` is not mapped, and
  neither is the `pass` activity.
- Schema: `displayAs` is a per-location field at `temporalData` level, values `CALL,
  CANCELLED, DIVERTED, STARTS, TERMINATES, PASS`, null meaning PASS
  (`downloads/RTT.GH.API-spec:197-218`, `:285-306`). Times for passing points are in
  `temporalData.pass` (`:292-293`). `realtimeActual` is "the actual time for this
  activity" (`:243-285`).
- `/gb-nr/location` returns `services[]` of `NetworkRailLocationLineUpObject`
  (`scheduleMetadata`, `temporalData`, `origin[]`, `destination[]` of `LocationPair`);
  it documents **204** "Valid query, no services found" (`:454-507`, `:1130-1167`).
  `/gb-nr/service` documents only 200 and 404.
- `RttMapper.serviceSummary` and `RttProvider.departureBoard` have no tests on either
  platform (grep over `ios/Tests` and `android/app/src/test`).
- Android's real `optString(name, fallback)` returns the string `"null"` for a JSON
  null, whereas the org.json 20240303 test dependency returns the fallback (from
  knowledge of both implementations, not executed here). So on the JVM the existing
  `optString(key, null)` calls already pass a null fixture; on a device, `parseTime("null")`
  fails harmlessly to null, but a `displayAs` would wrongly become `unknown`.
- `serviceDetails(serviceId:date:)` ignores `date` (iOS `Date`, Android `LocalDate`); the
  id already embeds the date (`gb-nr:L01525:2026-09-13`).
- Existing `Stop(...)` call sites in tests use the five-argument form
  (`JourneyTests.swift:11`, `StopTests.swift:10,16`, `JourneyTest.kt:14`,
  `StopTest.kt:14,21`). The mapper passes `isCancelled:` at four sites
  (`RttProvider.swift:128-135`, `:148-155`; `RttProvider.kt:139-146`, `:158-165`).
- Android `Journey` requires at least one stop (`Journey.kt` `require`), but
  `fullService` calls `stops.first()` first, which throws `NoSuchElementException` on an
  empty `locations` array. iOS handles this explicitly (`RttProvider.swift:177-179`).
- Existing Android test `fullService_throwsOnMissingLocationsField` expects
  `JSONException` (`RttMapperTest.kt:83-89`).
- Not verified: real captured RTT responses (none in the repo). New fixtures are built
  from the schema, like the existing ones, and say so.

## Changes

### 1. `ProviderError` (replaces `RttError`)

Provider-neutral, defined next to `TrainDataProvider` (the contract, not the RTT
implementation; per `.ai/architecture.md` Stage 2 code must not depend on RTT types).
Names: Swift lowercase cases, Kotlin nested classes (`Network`, `Http`, `Malformed`,
`NotImplemented`). Stage 2's `feedLost` failure kind derives from it.

| Case | Meaning |
|---|---|
| `network(message)` | Transport failure: no connection, timeout, DNS, TLS, interrupted read, or a response that is not HTTP. |
| `http(code)` | Non-2xx HTTP status. |
| `malformed(reason)` | Body was not valid JSON, was empty on a 200, or a required field was missing. |
| `notImplemented(message)` | Capability deliberately absent (`searchStations`). |

- iOS: `ProviderError: Error, Equatable` in `Providers/`; `RttError` is deleted and its
  uses renamed (`RttProvider.swift`, `RttMapperTests.swift`). In `getJSON`: a
  `URLError` with code `.cancelled` is rethrown as `CancellationError()` (a cancelled
  `Task` surfaces from `URLSession` as `URLError(.cancelled)`, not `CancellationError`);
  any other `URLError` becomes `.network`; a non-`HTTPURLResponse` becomes `.network`;
  non-2xx becomes `.http(code)`; `JSONSerialization` failures and non-object bodies
  become `.malformed`. A `ProviderError` thrown by the mapper passes through unchanged.
- Android: `sealed class ProviderError : Exception` in `provider/`. `IOException`
  (including `SocketTimeoutException`) becomes `Network`; non-2xx becomes `Http(code)`.
  Only `IOException` and `JSONException` are caught, never `Exception`, so
  `CancellationException` propagates.
- **Where Android wraps `JSONException`:** in the mapper's public entry points (`stop`,
  `serviceSummary`, `fullService`), so both mappers throw `ProviderError.Malformed` for a
  missing required field and the same mapper-level test works on both platforms. `getJson`
  wraps only the body parse. `fullService` also throws an explicit `Malformed` for an
  empty `locations` array (today it throws `NoSuchElementException`).
- **HTTP 204:** `departureBoard` treats 204 as an empty list (a quiet station is not an
  error). `serviceDetails` treats 204 as `malformed`. A 200 with an empty body is
  `malformed`.
- `searchStations` throws `notImplemented` on both platforms.

### 2. `Stop` gains per-activity cancellation, `displayAs`, `hasArrived`

- `isArrivalCancelled`, `isDepartureCancelled` (default false). `isCancelled` becomes a
  computed property, their OR, so existing readers keep working.
- `displayAs: StopDisplay?` with cases `call, cancelled, diverted, starts, terminates,
  pass, unknown` (Swift lowercase; Kotlin `CALL` … `UNKNOWN`). Read from
  `temporalData.displayAs` (per location, not per activity). Absent or JSON null maps to
  nil (not `pass`: the tracker must tell "not reported" apart from "reported as pass",
  see the Stage 2 spec). An unrecognised string maps to `unknown`.
- `hasArrived: Bool`: true when the arrival activity has a parseable `realtimeActual`.
- The `pass` activity is **not** mapped: a PASS stop has nil arrival and departure.
  Stage 2 already holds the last ETA when `bestArrival` is nil.
- Mapped in `stop(from:)` and `serviceSummary`.
- Swift: the new fields are `var ... = default` (a `let` with a default would drop out of
  the memberwise init); `StopDisplay` is `Equatable`. Kotlin: new constructor parameters
  go last with defaults; `isCancelled` becomes a body getter. Test call sites are
  unchanged; the four mapper call sites that passed `isCancelled:` are updated.

### 3. Android timeouts and a test seam

- `interface HttpTransport { suspend fun get(url: String, headers: Map<String, String>): HttpResult }`
  and `data class HttpResult(val status: Int, val body: String)`. `RttProvider` takes an
  optional transport with a default (`RttProvider(accessToken)` keeps working; it has no
  other callers).
- The default transport runs in `withContext(Dispatchers.IO)`, sets `connectTimeout` and
  `readTimeout` to 10_000 ms, and reads `errorStream` for non-2xx (`inputStream` throws
  on 4xx/5xx), so the status code is reported as `Http(code)`.
- iOS already injects a `URLSession`; its error tests use an ephemeral session with a
  `URLProtocol` stub (`protocolClasses`), which can return any status, an empty 204, a
  non-HTTP response, or throw `URLError`.

### 4. Explicit-null handling on Android

`fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)`
(`isNull` is true for a missing key and for JSON null in both implementations), used for
all optional string fields including `displayAs`. Test: a `JSONObject` subclass that
overrides `optString(String, String)` to return `"null"` (Android semantics) proves the
helper still yields null. The explicit-null fixture is then a regression guard on both
platforms, not the proof.

### 5. `date` parameter

Documented in the `TrainDataProvider` contract on both platforms: "currently ignored (the
iOS `Date` / Android `LocalDate`); `serviceId` already identifies the day. Reuse
`service.id` when polling." No signature change.

### 6. Docs

- `docs/spec.md` §8 Stage 1 text: correct the test claim to what is true after this PR;
  note `ProviderError` and the `Stop` additions.
- `.ai/tech-stack.md` (the typed-error TBD and `RttError` mentions) and the `README.md`
  status line (says "Stage 0", cites a §9 that does not exist).
- A CHANGELOG entry only if a `CHANGELOG.md` exists (none today).

## Testing

Same fixtures and same scenarios on both platforms (`.ai/testing-guide.md`). New fixtures
go in both fixture folders and are built from the schema, not captured.

- `gb-nr-location.json`: a `/gb-nr/location` response with two `services[]`, mirroring
  `NetworkRailLocationLineUpObject` (`origin[]`/`destination[]` as `LocationPair` objects
  with `location`). Mapper tests: ids, operator, origin and destination, the single
  stop's times, cancelled flags; `departureBoard` returns both. A `serviceSummary` test
  for the fallback to `atStation` when `origin` or `destination` is absent.
- `gb-nr-service-departure-cancelled.json`: an intermediate stop with arrival fine and
  departure cancelled. Asserts `isArrivalCancelled == false`, `isDepartureCancelled ==
  true`, `isCancelled == true`. (A terminus normally has no departure.)
- `gb-nr-service-display-as.json`: one stop each for `displayAs` DIVERTED, CANCELLED,
  PASS (with its times under `pass`, asserting nil arrival/departure), TERMINATES, and an
  unrecognised value (`unknown`); and a stop with `realtimeActual` on arrival
  (`hasArrived == true`). The happy-path fixture covers `hasArrived == false`.
- `gb-nr-service-nulls.json`: explicit JSON nulls for `realtimeActual`, `realtimeForecast`
  and `displayAs`. Asserts nil on both platforms.
- Existing Android test `fullService_throwsOnMissingLocationsField` is rewritten to expect
  `ProviderError.Malformed`; a new test covers an empty `locations` array on both
  platforms.

Provider error tests (Android fake `HttpTransport`; iOS `URLProtocol` stub): HTTP 404 and
500 give `http(code)`; unreachable/`URLError`/`IOException` gives `network`; a non-JSON
body, a 200 with an empty body and a missing `service` give `malformed`; `departureBoard`
on 204 returns `[]`; `serviceDetails` on 204 gives `malformed`; `searchStations` gives
`notImplemented`; a non-`HTTPURLResponse` gives `network` (iOS). **Cancellation:** iOS
`URLError(.cancelled)` surfaces as `CancellationError`, not `.network`; Android: the fake
transport throws `CancellationException` and it escapes unwrapped.

Run `xcodegen generate && xcodebuild test ...` (see testing guide) and `./gradlew test`
locally before any push.

## Out of scope

All Stage 2 work, wiring `searchStations` to `StationDirectory`, a real captured RTT
fixture, running Android lint in CI, any UI.
