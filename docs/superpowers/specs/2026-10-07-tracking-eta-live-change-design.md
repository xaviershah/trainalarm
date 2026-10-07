# Stage 2: Tracking, ETA and live-change logic — design (rev 2)

Date: 2026-10-07. Source of truth for the stage: `docs/spec.md` §4 and §8.
Rev 2 applies the findings of an independent audit of Stage 1 and of rev 1.

## Goal

Build the logic layer that tracks an active journey: poll live data, compute the
destination ETA and the alarm fire time, and detect live changes (delay,
cancellation, destination no longer called at, feed outage, arrival). It has no UI
and no real alarm delivery. It depends only on `TrainDataProvider` and the model
types, never on `RttProvider` or raw RTT JSON.

Built on both platforms in the matching layer: `ios/Sources/TrainAlarm/Tracking/`
and `android/app/src/main/java/com/trainalarm/app/tracking/`.

## Prerequisite: Stage 1 fixes (separate PR, lands first)

Stage 2 is built on these. They are specified here so both PRs agree on the shapes.

- **Typed provider errors, identical on both platforms.** `ProviderError`:
  `network`, `http(code)`, `malformed(reason)`, `notImplemented`. Providers wrap the
  platform errors (`URLError`, `IOException`, `JSONSerialization`/`JSONException`
  failures) into it. A 404 is just `http(404)`; the tracker does not treat a gone
  service specially, it is an outage and the UI decides what to show.
- **Per-activity cancellation and `displayAs` on `Stop`.** Add `isArrivalCancelled`,
  `isDepartureCancelled` (existing `isCancelled` stays, derived as their OR), and
  `displayAs` with cases `call`, `cancelled`, `diverted`, `starts`, `terminates`,
  `pass`, plus `unknown` for unrecognised strings and nil when absent. Values are the
  schema's `LocationDisplayAs` (`downloads/RTT.GH.API-spec:197-210`). Add
  `hasArrived` (true when the arrival has `realtimeActual`).
- **Android HTTP timeouts** (10s connect and read).
- `docs/spec.md` §8: correct the "all unit-tested" claim, or add `departureBoard`
  fixture and mapper tests.
- New fixtures, on both platforms: destination with only departure cancelled;
  destination with `displayAs` DIVERTED, CANCELLED and PASS; an explicit-null
  realtime field; a stop with `realtimeActual` on arrival.

## Acceptance criterion ("ships when")

On both platforms, a `JourneyTracker` driven by fixture-based fakes (provider, clock,
location source, alarm scheduler) produces exactly the events and scheduler calls in
the shared scenario table below, covering delay, drop-out, cancellation,
outage-with-GPS, outage-without-GPS, recovery, flapping, the past-fire clamp and
arrival, including the 60s reschedule threshold. Both platforms' scenario lists are
identical, all of it is unit-tested, and iOS and Android CI are green. No UI or
real-device work is involved.

## Verified facts (read from the repo, not assumed)

- Existing fixtures, identical on both platforms: `gb-nr-service.json` (destination
  scheduled 08:47, estimate 08:52, +5 min), `gb-nr-service-cancelled.json` (all stops
  cancelled), `gb-nr-service-destination-dropped.json`, `gb-nr-service-malformed.json`.
  Every fixture time is on a whole minute.
- The mapper gives every `Station` latitude 0 and longitude 0
  (`RttProvider.swift:89-90`, `RttProvider.kt:101-102`). Real coordinates exist only in
  `StationDirectory` (`data/stations.json`). Provider stations are matched by `id`.
- `Journey.destination` is the service's own terminus, not the user's alighting
  station (`RttProvider.swift:182-184`).
- `serviceDetails(serviceId:date:)` ignores `date`; the service id already embeds it
  (`gb-nr:L01525:2026-09-13`), so reusing `service.id` makes midnight rollover a
  non-issue.
- `realtimeEstimate` needs a token entitlement (`downloads/RTT.GH.API-spec:255-262`).
  Without it a delay can be invisible until `realtimeForecast` appears.
- A missing `displayAs` means PASS per the schema. This has not been checked against
  real data, so the tracker treats a missing `displayAs` as "calling" and never raises
  `destinationLost` on it alone.
- Android has `kotlinx-coroutines-android` 1.9.0 (core comes in transitively) but no
  `kotlinx-coroutines-test`. This design does not add it.
- Not verified: real GPS behaviour on a moving train. The outage fallback is
  deliberately conservative and is to be revisited in Stage 5 with real-device data.

## Approach

A pure decision core plus a thin driver. Chosen over a single stateful class (async
timing everywhere in tests, harder to keep Swift and Kotlin identical) and a reactive
operator pipeline (operator sets differ between Combine and Flow).

## Tracker inputs

The caller (later the UI) supplies:

- the initial `Service` snapshot fetched at selection time (`serviceDetails`),
- `boardingId` and `destinationId` (station ids),
- `destinationCoordinate` (optional), resolved by the caller through
  `StationDirectory`. Tracking does not depend on `StationDirectory`. If there is no
  coordinate (for example the 11 Elizabeth line codes), the `gpsPace` fallback is
  disabled and only `lastKnownTrajectory` is used,
- a `TrackerConfig`.

The tracker never uses `Journey.destination` or `Journey.destinationStop`. The
**destination stop** is the first stop with `destinationId` located after the boarding
stop in `stops`. "Absent" means no such stop exists. This also handles loops where a
station appears twice.

## Units

| Unit | Responsibility | Depends on |
|---|---|---|
| `AlarmScheduler` (protocol/interface, lives in `Alarm/`/`alarm/`; Stage 3 implements it, tests use a fake) | `schedule(fireAt)` with replace-semantics (idempotent), may throw; `cancel()`. | nothing |
| `LocationSource` (protocol/interface) | Latest fix: lat, lon, timestamp, accuracy; or nil. | nothing |
| `TrackerClock` (protocol/interface) | `now()` and `sleep(for:)`. Named to avoid Swift's `Clock` and `java.time.Clock`. | nothing |
| `TrackerConfig` (value type) | `leadTime`, `rescheduleThreshold` 60s, `pollInterval` 45s, `guardMargin` 60s, `minPace` 1 m/s, `maxFixAge` 3 polls, `maxFixAccuracy` 200 m, `arrivalGrace` 10 min. Provider times have minute resolution (documented here). | nothing |
| `JourneyMonitor` (pure) | `(state, observation) -> (state, events, alarmAction)`. All ETA, threshold, classification and outage logic. | model types only |
| `JourneyTracker` (driver; an `actor` on iOS) | Poll loop, wiring, event stream, `stop()`. | `TrainDataProvider`, the three interfaces, `JourneyMonitor` |

## Events

Delivered on a stream (`AsyncStream` on iOS, a `Channel`-backed `Flow` on Android;
never a `MutableSharedFlow` without a buffer, which drops events emitted before a
collector subscribes).

| Event | When | Carries |
|---|---|---|
| `etaChanged` | Initial schedule (old ETA nil), and whenever the fire time moves by the threshold or more from the currently scheduled one (live data only). | old and new ETA, new fire time |
| `destinationLost` | Destination not called at (see classification), once on entry. | time seen |
| `serviceCancelled` | Whole service cancelled, once on entry. | time seen |
| `destinationRestored` | Destination is a normal call again after lost or cancelled. | new ETA |
| `feedLost` | First failed poll after a success (or at start), once per outage. | last good time (nil if none), failure kind |
| `etaEstimated` | Every tick while the feed is down and a fallback ETA exists. | fallback ETA, source (`lastKnownTrajectory` or `gpsPace`), staleness, `rescheduled: Bool` |
| `feedRecovered` | First successful poll after an outage. | outage duration, fresh ETA, difference from the last fallback ETA |
| `alarmSchedulingFailed` | `AlarmScheduler.schedule` threw. The scheduled fire time is not updated, so the next tick retries. | fire time, error |
| `arrived` | Destination arrival confirmed, or grace expired (see arrival). The stream then ends. | time |

`serviceCancelled` and `destinationLost` are deliberately separate: the user-facing
message differs ("your train is cancelled" versus "your train no longer stops at X").

## Monitor rules

State: last live ETA and when it was seen, whether any live time has been seen, the
currently scheduled fire time, feed-down flag, lost / cancelled flags, `alarmDue`, and
recent GPS fixes.

**At start.** From the initial snapshot, before any poll: ETA = the destination stop's
`bestArrival`, fire time = ETA minus `leadTime`, call `schedule(fireAt)` and emit
`etaChanged` (old ETA nil). This is the timetable-based fallback alarm of spec §5. If
`ETA - leadTime <= now`, schedule immediately (see clamp).

**On every tick**, sample the location source and store the fix (also while the feed
is up, so the window is not empty when an outage starts).

**On a successful poll**, in order:

0. If the feed was down, emit `feedRecovered` first.
1. Find the destination stop (see inputs). If absent: `destinationLost` on entry.
2. If every stop is cancelled (arrival or departure): `serviceCancelled` on entry.
3. Destination classification, using the destination stop's *arrival* activity only
   (never its departure):
   - arrival cancelled, or `displayAs` is `cancelled`, `diverted` or `pass`
     → `destinationLost` on entry. A nil or `unknown` `displayAs` is not enough on its
     own (see verified facts).
   - otherwise the stop is a normal call. If the lost or cancelled flag was set, clear
     it and emit `destinationRestored`, then fall through to rule 4.
4. ETA and fire time:
   - ETA = `bestArrival`. If it is nil, hold the last ETA, mark stale, emit nothing.
   - If live times had been seen and this poll has none (the estimate vanished and the
     stop has not arrived), treat it as stale: hold the last live ETA. Do not revert to
     the timetable and silently lose a known delay.
   - desired fire time = ETA - `leadTime`, unclamped.
   - If `abs(desired - scheduled) >= rescheduleThreshold` (inclusive; times are
     minute-granular so a one-minute shift is exactly 60s), call `schedule` and emit
     `etaChanged`. The comparison is against the currently scheduled time, not the
     previous poll, so slow drift triggers.
5. Arrival: if the destination stop `hasArrived`, or `now > ETA + arrivalGrace`, emit
   `arrived`, call `cancel()` and end the stream.

**Past-fire clamp.** When desired fire time `<= now` and the alarm is not yet due:
call `schedule(now)` once and set `alarmDue`. After that the monitor never reschedules
and never emits `etaChanged` for the fire time; only `arrived` and the classification
events can still occur. The threshold is evaluated on the unclamped desired time, so a
clamped time never causes a reschedule loop.

**On a failed poll:**

- Emit `feedLost` once on the first failure of an outage. The failure kind comes from
  `ProviderError`; any other error is `other`. A cancellation (`CancellationException`,
  `CancellationError`) is rethrown, never turned into a failed poll, so `stop()` works.
- Each tick compute a fallback ETA:
  - Without a usable GPS window, or without a destination coordinate: hold the last
    live ETA (`lastKnownTrajectory`).
  - With GPS: pace = approach speed = `(d_oldest - d_newest) / (t_newest - t_oldest)`,
    where `d` is the Haversine great-circle distance in metres to the destination
    coordinate, over the fixes in the window. Fixes older than `maxFixAge` or less
    accurate than `maxFixAccuracy` are ignored; at least two fixes are needed. If pace
    is below `minPace` (including negative), hold. Otherwise
    fallback ETA = `now + d_newest / pace`.
  - Sanity guard: the fallback ETA is never earlier than the last live ETA minus
    `guardMargin`. Straight-line distance understates route length, so GPS pace tends
    to be optimistic, and a missed stop is worse than a slightly early alarm.
- If the first ever poll fails, the last live ETA is the initial snapshot's
  `bestArrival`.
- Emit `etaEstimated`. The same threshold rule applies to fallback ETAs and decides its
  `rescheduled` flag. `etaChanged` is reserved for live data.
- `destinationLost` and `serviceCancelled` cannot be detected while the feed is down;
  `feedLost` is the signal that this protection is off.

**Scheduler failure.** If `schedule` throws, emit `alarmSchedulingFailed`, leave the
scheduled fire time unchanged, and retry on the next tick. A silent missed alarm is the
worst outcome.

**Defaults decided here:**

- On `destinationLost` or `serviceCancelled` the monitor leaves the already scheduled
  alarm in place. Cancelling it risks silence, which the spec rules out. How to present
  the urgent alert is a Stage 3/4 decision.
- No events for other calling-pattern changes (stops added or removed that do not touch
  the destination). They do not affect the alarm (YAGNI).
- Flapping: `feedLost` fires once per outage and `feedRecovered` on the first clean
  poll, so fail, succeed, fail yields two lost and one recovered, never more.

## Driver

`stop()` cancels the scheduler and ends the stream; it is the hook for "I'm off"
(spec §1). The poll loop is: sample location, poll, feed the monitor, apply the
action, sleep `pollInterval` on the `TrackerClock`.

## Testing

Per `.ai/testing-guide.md`: XCTest under `ios/Tests/TrainAlarmTests/Tracking/` and
JUnit 4 under `android/app/src/test/java/com/trainalarm/app/tracking/`. Hand-written
fakes, no mocking library, no `kotlinx-coroutines-test`.

- **Pure `JourneyMonitor` tests** on scripted poll sequences. A test helper builds
  sequences from the existing fixtures by rebuilding the (immutable) `Stop`s with
  shifted times, and must also rewrite `Service.id` when chaining fixtures, since the
  cancelled and dropped fixtures have different ids.
- **Driver tests:** on Android, `runBlocking` with a fake `TrackerClock` whose `sleep`
  suspends on a `CompletableDeferred`/`Channel` released by the test, and events read
  from a `Channel(UNLIMITED)`-backed flow under `withTimeout`. On iOS, an `AsyncStream`
  with the default unbounded buffer and a continuation-based fake clock. Includes
  `stop()` and cancellation propagation.
- **Identical numeric test vectors** for the Haversine pace maths on both platforms.
- **Shared scenario table** (below). Both platforms implement exactly these rows.

### Shared scenario table

| # | Scenario | Expected |
|---|---|---|
| 1 | Start, steady live data | initial `schedule`, one `etaChanged` (old nil), no more |
| 2 | 30s shift | no `schedule`, no event |
| 3 | Exactly 60s shift | one `schedule`, `etaChanged` |
| 4 | Drift: 30s then another 30s | second poll triggers `schedule` |
| 5 | Delay of 5 min, then back to on time | two `schedule` calls, two `etaChanged` |
| 6 | Whole service cancelled | `serviceCancelled` once, alarm left scheduled |
| 7 | Destination absent from stops | `destinationLost` once |
| 8 | Destination `displayAs` DIVERTED / CANCELLED / PASS | `destinationLost` |
| 9 | Only the destination's departure cancelled | no loss event (arrival is fine) |
| 10 | Destination arrival cancelled, other stops fine | `destinationLost` |
| 11 | Lost then restored | `destinationLost`, then `destinationRestored` (+ `schedule` if moved) |
| 12 | Nil `bestArrival` | hold ETA, no event |
| 13 | Live estimate vanishes, stop not arrived | hold last live ETA, no `etaChanged` |
| 14 | First poll fails | `feedLost` (last good nil), fallback from snapshot |
| 15 | Outage, no GPS | `feedLost`, `etaEstimated(lastKnownTrajectory)` each tick |
| 16 | Outage, GPS approaching | `etaEstimated(gpsPace)`, guard respected |
| 17 | Outage, no destination coordinate | trajectory only |
| 18 | Outage recovery with a delay | `feedRecovered`, then `etaChanged` |
| 19 | Recovery reveals destination dropped | `feedRecovered`, then `destinationLost` |
| 20 | Flapping fail, ok, fail | exactly two `feedLost`, one `feedRecovered` |
| 21 | Fire time already past at start | one `schedule(now)`, `alarmDue`, no reschedule loop |
| 22 | ETA less than `leadTime` away at start | immediate `schedule(now)` |
| 23 | Scheduler throws | `alarmSchedulingFailed`, retried next tick |
| 24 | Destination `hasArrived` | `arrived`, `cancel()`, stream ends |
| 25 | `stop()` | `cancel()`, stream ends, no further polls |
| 26 | Cancellation during sleep | propagates, not reported as `feedLost` |

## Out of scope

UI, real alarm delivery (Stage 3), real GPS plumbing (Core Location / Fused Location),
background execution (Stage 5), wiring `RttProvider.searchStations`.

## Docs to update in the plan's final task

- `docs/spec.md` §8: add the Stage 2 "ships when" line and mark it done; §4 threshold
  wording from "~>60s" to ">=60s".
- `.ai/tech-stack.md`: the typed error (no longer TBD). `.ai/architecture.md`: Tracking
  row status. `.ai/testing-guide.md`: hand-written fakes as the mocking approach.
- `README.md` status line (still says Stage 0 and cites a §9 that does not exist).
