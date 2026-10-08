# TrainAlarm — technical spec (extract for coding sessions)

This is the condensed, code-relevant extract of the full product spec.
The full spec (competitive research, UX flow detail, visual references)
lives in the published artifact — ask Xavier for the link if it's not
already in context.

## 1. Core loop

1. User selects a journey: origin, destination, and the specific service
   from a live departure board (manual selection only in v1 — no
   auto-detection of the boarded train yet).
2. App tracks the journey: polls live data + GPS.
3. Alarm fires at a configurable lead time before arrival, escalating in
   urgency, with an always-visible "I'm off" dismiss.

## 2. Data model (shared concept, implemented per platform)

- `Station` — id, name, coordinates, country, provider-specific code(s).
- `Stop` — a station within a journey, with scheduled + live estimated
  arrival/departure.
- `Journey` — origin, destination, ordered list of `Stop`s.
- `Service` — a specific train run on a specific day (operator, service
  ID, its `Journey`).
- `TrainDataProvider` interface — station lookup, live departure boards,
  a service's full stop timetable, live running/delay info. All tracking/
  ETA/alarm logic depends only on this interface, never on a raw API
  response shape directly.

## 3. UK provider (v1) — RttProvider implemented

The legacy RTT pull API at `api.rtt.io` is being decommissioned
**30 September 2026** and was never built against. `RttProvider` targets
RTT's next-generation bearer-token API instead, whose real OpenAPI spec
Xavier retrieved directly (JS-rendered Swagger docs, so it couldn't be
fetched by URL) and which is now checked into
`downloads/RTT.GH.API-spec` at the repo root for reference. Requires a
paid subscription via api-portal.rtt.io (no free tier found as of this
research pass) — accepted as the v1 cost.

**Verified facts this implementation relies on** (see the spec file for
the full schema):
- Base URL `https://data.rtt.io`, bearer-token auth (`Authorization:
  Bearer <token>`).
- `/gb-nr/location?code=<CRS>&timeFrom=<ISO8601>` — a departure board:
  one `NetworkRailLocationLineUpObject` per service, with *only that
  station's* temporal data, not the full calling pattern.
- `/gb-nr/service?uniqueIdentity=<id>` — full stop-by-stop detail via
  `service.locations[]`, each with `location` (station identity) and
  `temporalData.{arrival,departure}` (each an `IndividualTemporalData`:
  `scheduleAdvertised`, `realtimeForecast`, `realtimeEstimate`,
  `realtimeActual`, `isCancelled`).
- No free-text station search endpoint — resolution from a name a user
  types to a CRS/TIPLOC code needs a static station dataset. It is now
  built: `data/stations.json` (2,626 Great Britain stations from NaPTAN
  under OGL v3.0, see `data/STATIONS-SOURCE.md`), loaded by
  `StationDirectory` on both platforms. Still to do: wiring
  `RttProvider.searchStations` to it (it deliberately throws for now
  rather than guessing at an endpoint that doesn't exist).
- `GeographicLocation` has no coordinates — GPS lat/lon comes from that
  same station dataset (`StationDirectory`), not from RTT.

Production migration target once the RTT integration is proven out:
National Rail's Darwin feed via the National Rail Data Portal (free
in principle, registration is a slower form-based approval process) —
confirm hands-on before relying on it.

## 4. Live-change handling (must be built, not bolted on later)

- **Delay**: destination ETA shifts → recompute alarm fire time → reschedule
  the notification, but only on a meaningful shift (60s or more, measured
  against the currently scheduled time), to avoid churn.
- **Calling-pattern change**: re-check the *whole* stop sequence each poll,
  not just the destination's time. If the destination drops out of the
  calling pattern (diversion/early termination/cancellation), that's a
  distinct, urgent alert — not a delay, and not silence.
- **Feed outage**: fall back to last-known trajectory + GPS-derived pace;
  keep retrying the live poll on schedule.

Poll interval: roughly every 30–60s while a journey is active.

## 5. Alarm delivery

- **iOS**: AlarmKit (iOS 26+) — lets third-party apps fire alarms that
  bypass Silent/Focus mode, same standing as the built-in Clock app. Do
  *not* build against the old Critical Alerts entitlement path — Apple's
  manual review restricts that to health/safety/security apps and a train
  alarm would likely be rejected.
- **Android**: exact alarms are gated by default since Android 14
  (`SCHEDULE_EXACT_ALARM` needs explicit user grant via
  `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, or use `USE_EXACT_ALARM` which is
  pre-granted but restricted by Play policy to alarm-clock/calendar
  category apps). A background location service needs
  `FOREGROUND_SERVICE_LOCATION` + `ACCESS_BACKGROUND_LOCATION`. Battery-
  optimisation allowlisting is the standard extra step for reliability.
- A timetable-based local notification is scheduled as a fallback the
  moment a journey starts, independent of whether live tracking is still
  running in the background — belt-and-braces against either OS killing
  the process.

## 6. Decided

- **Name**: TrainAlarm.
- **Platform**: native. Separate Swift/SwiftUI (iOS) and Kotlin/Jetpack
  Compose (Android) codebases — no React Native / Flutter. Rationale:
  almost none of the hard part (AlarmKit, Android's exact-alarm/foreground-
  service permission model) is shareable code either way, and React
  Native's leading background-location library needs a paid Android
  licence for release builds.

## 7. Open (not yet decided)

- Minimum iOS version acceptability, given AlarmKit requires iOS 26.
- Monetisation model, if any.
- Multi-leg/connection journeys, auto-detection of boarded train, and a
  second country's data provider are all explicitly deferred (v2/v3).

## 8. Stages

0. Repo scaffold (this stage).
1. Journey model & UK provider (RTT) — DONE. `Station`/`Stop`/`Journey`/
   `Service` model, `TrainDataProvider` interface, and a concrete
   `RttProvider` (departure board + full service lookup) on both
   platforms, unit-tested against fixtures built from RTT's real verified
   schema (§3), including the departure board (no real captured RTT
   responses are in the repo yet). A pre-Stage-2 audit added a typed
   `ProviderError` (network / http / malformed / notImplemented) shared by
   both platforms, per-activity cancellation, `displayAs` and `hasArrived`
   on `Stop`, 10s Android timeouts, and HTTP 204 handling (an empty
   departure board). The station dataset is now built (`data/stations.json`,
   2,626 GB stations from NaPTAN under OGL v3.0, with `StationDirectory`
   on both platforms for CRS lookup, name search and coordinates). Not yet
   done: wiring `RttProvider.searchStations` to the directory, and showing
   the OGL attribution in the app UI (UI stage). Known gaps: 11 Elizabeth
   line codes (e.g. PDX, FDX) have no NaPTAN coordinates and are absent;
   names are NaPTAN's (e.g. "Edinburgh", not "Edinburgh Waverley").
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
3. Alarm delivery spike — highest risk, real-device test only, both
   platforms, success = alarm fires locked+silent at a scheduled time.
4. UI build — once UX reference screenshots are in.
5. Background-reliability hardening — dedicated stage; this is the #1
   failure mode across every competitor found in research.
6. Closed beta (TestFlight / Play internal testing).
7. Store submission (AlarmKit review on iOS; exact-alarm category
   qualification on Android).
