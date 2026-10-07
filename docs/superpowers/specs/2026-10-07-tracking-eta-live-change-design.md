# Stage 2: Tracking, ETA and live-change logic — design

Date: 2026-10-07. Source of truth for the stage: `docs/spec.md` §4 and §8.

## Goal

Build the logic layer that tracks an active journey: poll live data, compute the
destination ETA and the alarm fire time, and detect live changes (delay,
cancellation, destination dropped from the calling pattern, feed outage). It has
no UI and no real alarm delivery. It depends only on `TrainDataProvider` and the
model types, never on `RttProvider` or raw RTT JSON.

Built on both platforms in the matching layer: `ios/Sources/TrainAlarm/Tracking/`
and `android/app/src/main/java/com/trainalarm/app/tracking/`.

## Acceptance criterion ("ships when")

On both platforms, a `JourneyTracker` driven by fixture-based fake providers, a
fake clock and a fake location source emits the correct events and reschedules
the alarm correctly for the delay, drop-out, cancellation and outage scenarios,
including the ~60s reschedule threshold. All of it is unit-tested, iOS and
Android CI are green, and no UI or real-device work is involved.

## Verified facts (read from the repo, not assumed)

- Existing fixtures, identical on both platforms: `gb-nr-service.json` (destination
  scheduled 08:47, estimate 08:52, so +5 min), `gb-nr-service-cancelled.json` (all
  stops `isCancelled`), `gb-nr-service-destination-dropped.json`,
  `gb-nr-service-malformed.json`.
- `Stop.isCancelled` is already mapped by `RttProvider`. `Journey.destinationStop`
  already returns nil when the destination is absent from the stops.
- Every fixture is a single snapshot. Tracking works on a sequence of polls, so the
  tests build sequences by loading these fixtures through the existing mapper and
  shifting times in a test helper. No new RTT response shapes are invented.
- Android has `kotlinx-coroutines-android` 1.9.0 but no `kotlinx-coroutines-test`.
  This design does not add it (see Testing).
- Not verified: real GPS behaviour on a moving train. The outage fallback maths is
  therefore deliberately conservative and is to be revisited in Stage 5 with
  real-device data.

## Approach

A pure decision core plus a thin driver. Chosen over a single stateful class
(async timing everywhere in tests, harder to keep Swift and Kotlin identical) and
a reactive operator pipeline (operator sets differ between Combine and Flow).

## Units

| Unit | Responsibility | Depends on |
|---|---|---|
| `AlarmScheduler` (protocol/interface) | `schedule(fireAt)`, `cancel()`. Stage 3 supplies the real one. | nothing |
| `LocationSource` (protocol/interface) | Latest GPS fix (lat, lon, time) or nil. | nothing |
| `Clock` (protocol/interface) | `now()` and `sleep(for:)`. | nothing |
| `TrackerConfig` (value type) | `leadTime`, `rescheduleThreshold` (60s), `pollInterval` (45s), fallback guard margin (default 60s), pace window (default 5 fixes), minimum pace (default 1 m/s). | nothing |
| `JourneyMonitor` (pure) | `(state, observation) -> (state, events, alarmAction)`. All ETA, threshold, drop-out and outage logic. | model types only |
| `JourneyTracker` (driver) | Poll loop, wiring, event stream. | `TrainDataProvider`, the three interfaces, `JourneyMonitor` |

`StationDirectory` coordinates give the destination position for the GPS pace
fallback; the destination `Station` already carries latitude and longitude.

## Events

Delivered on a stream (`AsyncStream` on iOS, `Flow` on Android).

| Event | When | Carries |
|---|---|---|
| `etaChanged` | Fire time moves more than the threshold from the currently scheduled one. | old and new ETA, new fire time |
| `destinationLost` | Destination absent from the stop sequence, once on entry. | time seen |
| `serviceCancelled` | Destination stop is cancelled, once on entry. | time seen |
| `feedLost` | First failed poll after a success, once. | last good time (nil if none), failure kind (network, HTTP, malformed) |
| `etaEstimated` | Every poll tick while the feed is down and a fallback ETA exists. | fallback ETA, source (`lastKnownTrajectory` or `gpsPace`), staleness |
| `feedRecovered` | First successful poll after an outage. | outage duration, fresh ETA, difference from the last fallback ETA |

`serviceCancelled` and `destinationLost` are deliberately separate: the user-facing
message differs ("your train is cancelled" versus "your train no longer stops at X").

## Monitor rules

State: last good ETA and when it was seen, currently scheduled fire time, feed-down
flag, destination-lost and cancelled flags, recent GPS fixes.

On a successful poll, in order:

1. If the destination is absent from the whole stop sequence, emit `destinationLost`
   on entry. If it later reappears, emit `etaChanged`.
2. If the destination stop is cancelled, emit `serviceCancelled` on entry.
3. Otherwise ETA = the destination's `bestArrival` and fire time = ETA minus
   `leadTime`. If the fire time differs from the currently scheduled fire time by
   more than 60s, call `schedule(newFireTime)` and emit `etaChanged`. The comparison
   is against the scheduled time, not the previous poll, so slow drift still
   triggers. A fire time already in the past is clamped to now.
4. If the feed was down, `feedRecovered` is emitted before any event from steps 1-3.

On a failed poll:

- Emit `feedLost` once on the first failure.
- Each tick compute a fallback ETA. With no GPS fix, or pace below the minimum
  pace, hold the last live ETA (`lastKnownTrajectory`). With enough fixes, use the
  straight-line distance from the latest fix to the destination divided by the pace
  over the window (`gpsPace`). Sanity guard: the fallback never moves the ETA
  earlier than the last live ETA minus the guard margin, because a missed stop is
  worse than a slightly early alarm.
- If the very first poll fails, there is no live ETA, so the fallback is the
  destination's scheduled arrival.
- The same 60s reschedule rule applies to fallback ETAs.
- Provider errors of every kind become one failure observation with a kind tag. The
  tracker never throws; outages and cancellations are data (tech-stack: "modelled
  as data, not as errors").
- `destinationLost` cannot be detected while the feed is down. `feedLost` is the
  signal that this protection is off.

Defaults decided here:

- On `destinationLost` or `serviceCancelled` the monitor leaves the already
  scheduled alarm in place. Cancelling it risks silence, which the spec rules out.
  How to present the urgent alert is a Stage 3/4 decision.
- No events for other calling-pattern changes (intermediate stops added or
  removed). They do not affect the alarm (YAGNI).
- Flapping: `feedRecovered` needs one clean poll, and `feedLost` fires once per
  outage, so alternating fail/succeed does not spam events beyond one pair per
  actual transition.

## Testing

Per `.ai/testing-guide.md`: XCTest under `ios/Tests/TrainAlarmTests/Tracking/` and
JUnit 4 under `android/app/src/test/java/com/trainalarm/app/tracking/`. Hand-written
fakes, no mocking library.

- Pure `JourneyMonitor` tests on scripted poll sequences built from the existing
  fixtures with shifted times: steady; delay under 60s (no reschedule); delay over
  60s; slow drift; delay then recovery; cancelled; destination dropped and
  reappearing; outage with GPS; outage without GPS; fallback guard; first poll
  failing; flapping; recovery that reveals a drop-out.
- A few `JourneyTracker` driver tests with a hand-written fake `Clock`, covering
  poll cadence, the event stream and the scheduler calls. This avoids adding
  `kotlinx-coroutines-test` on Android.
- The same scenario list on both platforms.

## Out of scope

UI, real alarm delivery (Stage 3), real GPS plumbing (Core Location / Fused
Location), background execution (Stage 5), wiring `RttProvider.searchStations`.

## Docs to update in the plan's final task

- `docs/spec.md` §8: add a "ships when" line to Stage 2 (the criterion above) and
  mark it done.
- `.ai/architecture.md` (Tracking row stage status), `.ai/testing-guide.md`
  (mocking approach: hand-written fakes), `README.md` if it lists stage status.
