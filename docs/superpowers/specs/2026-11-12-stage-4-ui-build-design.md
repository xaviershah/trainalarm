# Stage 4: UI build, design

Status: draft for review, with the independent audit applied. Source of truth for the
product is `docs/spec.md`; this file is the feature spec for Stage 4 (§8, item 4).
References: `docs/ux-reference/` (screenshots and palette) and
`docs/ui-mockup/trainalarm-ui-direction.html` (the agreed mockup, open it in a browser).

## 1. Goal

Replace the Stage 0 placeholder UI with the real app on both platforms (SwiftUI on iOS,
Jetpack Compose on Android), wired to the Stage 1–3 layers, so a user can pick a train,
set an alarm and be woken before their stop **while the app is open**. Background
tracking is Stage 5 and the map is Stage 4b (sections 8 and 9).

**Ships when** (all on both platforms unless stated):

1. A `SessionModel` (section 6), driven by the existing test fakes, passes through every
   state in section 4 and handles every `TrackerEvent` as in the table in section 6,
   including permission refusals, first run, empty board, From equal to To, rate limiting
   and `alarmSchedulingFailed`. Unit-tested.
2. The provider returns only trains calling at the destination (`callingAt`), stations from
   RTT are resolved to real coordinates through `StationDirectory`, and the picker's search
   is backed by `StationDirectory`. Unit-tested.
3. Every screen in section 4 has a dark and a light screenshot, checked against the mockup:
   iOS from the simulator (`xcrun simctl io booted screenshot`, `xcrun simctl ui booted
   appearance`), Android from an emulator. No emulator is installed today, so a plan task
   installs the `emulator` package and one `system-images;android-35` image first, with the
   user's approval (a multi-gigabyte download). Screenshots are produced locally and
   committed under `docs/ui-screenshots/<platform>/`; CI does not produce them. Each
   platform has a debug scenario launcher (an iOS launch argument, an Android intent
   extra) that opens a named scenario (`home`, `confirm`, `tracking`, `tracking-late`,
   `cancelled`, `ringing`, `settings`, ...) on a scripted `SessionModel`, so no screen needs
   live data.
4. A debug-only demo mode runs the whole flow end to end with no RTT token (section 6).
5. Both platforms build and their unit tests and lint pass locally, and CI passes on the
   final push. The first plan task makes `xcodegen generate && xcodebuild test` and
   `./gradlew test lint assembleDebug` pass with no secrets file present, before any UI work.

**Not part of "ships when", and recorded as not done:** a live-data check against RTT (no
token yet), the Android alarm device test from Stage 3 (on hold), background tracking, and
the iOS in-app ringing signal on a device if its plan task cannot complete. A simulator or
emulator result is not a pass for alarm delivery. Stage 4 must not change the Stage 3 status
line in `docs/spec.md` §8, and the PR text says Stage 3 is still not done.

## 2. Scope

In: journey picker (home), confirm sheet, tracking with a drawn route strip, alarm
ringing, settings (theme, default lead time, permission status, test alarm, attribution),
the tracking states, just-in-time permission flows, light and dark themes, bundled fonts,
the real clock and location sources, the app wiring, the RTT token configuration, request
budget handling.

Out (section 9): the map, background tracking (foreground service, ongoing notification,
background location), resuming a journey after the app is killed, saved or recent
journeys and an alarm-list home, iOS Live Activity, the backend proxy, Darwin, onboarding,
accounts, multi-leg journeys, UI-test frameworks (XCUITest, `androidTest`).

## 3. Facts checked and facts not checked

Checked against real files in this repo:

- `GET /gb-nr/location` accepts `filterTo` (`downloads/RTT.GH.API-spec:1042`) and
  `filterFrom` (`:1035`). The line-up response gives only the origin station's times and the
  train's terminus, not the arrival at the filtered station (`:488-506`).
- RTT's own terms (`downloads/RTT.GH.API-spec:19`): "no token is placed in a distributable
  user application unless specifically authorised by us ... If we identify a token in a
  downstream user application, it will be revoked." End-user apps are expected to proxy
  through a server.
- A token is either a long-life access token or a long-life refresh token exchanged through
  `/api/get_access_token` for a short-life access token (`:12-21`, `:1644`).
- `departureBoard` takes only `station` and `from` today (`TrainDataProvider.swift`,
  `TrainDataProvider.kt`); `RttProvider` sends `code` and `timeFrom` only.
- Stations built from RTT responses have latitude and longitude 0
  (`RttProvider.swift:112`, `RttProvider.kt:119`); `RttMapper.station` is used for board and
  service stops.
- Only the `TrackerClock` and `LocationSource` protocols exist; no implementation exists and
  nothing creates a tracker. The Android tracker's `events` flow is collect-once
  (`JourneyTracker.kt:38`).
- The tracker ends itself only on arrival; cancelled, dropped and feed-lost leave it running
  (`JourneyMonitor`). `TrackerEvent` has no "alarm fired" case.
- Android declares one foreground service, `AlarmService` (`mediaPlayback`); there is no
  tracking foreground service (`AndroidManifest.xml:35-38`). iOS has the `location`
  background mode but nothing uses it.
- The shared fixtures and the fakes (`FakeClock`, `FakeProvider`, `ServiceBuilder`,
  `FakeAlarmScheduler`) exist only in the test source sets of both platforms.
- iOS presents the alarm through AlarmKit with a hard-coded title, a "Stop" button and a
  blue tint (`AlarmKitClient.swift:27-30`).
- No Android emulator or system image is installed; the iOS simulator is.
- `ios/project.yml` has no `configFiles`; `android/app/build.gradle.kts` has no
  `buildConfig` feature; `.gitignore` ignores `.env` and `*.local.properties` only.
- Contrast ratios were computed with a script (section 5); the plan recomputes them in tests.

Reported by pages I read, not verified by use: RTT's October 2026 limits are 10 per minute,
100 per hour, 1,000 per day; pricing and commercial terms are not stated. Darwin's data and
registration are unconfirmed (project rule: do not rely on it).

Not checked, so each is a plan task with a stop-and-report gate: how the iOS app learns
that an AlarmKit alarm is ringing (the update stream is the candidate; no use on a device
yet); whether AlarmKit accepts the button text "I'm off" and a palette tint; whether the
issued RTT token is an access or a refresh token (no token yet); the font licence texts.

## 4. Screens and flow

```
App opens -> HOME   (resuming a journey after the app was killed is not in Stage 4)
HOME: From (search) -> To (search) -> board of trains calling at To
      tap a train -> CONFIRM sheet
CONFIRM: lead time on a ruler (5, 10, 15, 20, 25, 30 min), a dial showing alarm and
      arrival times, permission warnings, "Start tracking"
      Start -> just-in-time permission requests -> TRACKING
TRACKING: route strip, destination, time left, delay badge, journey progress, alarm
      status, next stop, data and GPS health, "Keep the app open for live updates",
      "I'm off" pinned
      tracker events -> state cards (section 6)
      alarm fires -> RINGING
RINGING: dark core in an orange and teal glow, countdown, "I'm off"
      "I'm off" or arrival -> ENDED -> HOME
SETTINGS (gear on Home and Tracking): theme (System, Dark, Light), default lead time,
      permission rows with a Fix button, Test alarm, attribution
```

Rules:

- **Board rows** show the departure at From (scheduled and expected), platform if present,
  operator, and the terminus ("towards X"). They do not show the arrival at To. The CONFIRM
  sheet fetches `serviceDetails` once for the tapped train and finds the arrival at To after
  the boarding stop. Rows are never enriched per train, to protect the request budget. The
  board starts at the current instant, shows the rows the provider returns, and has no
  pagination; no result-count parameter was checked. The board is refreshed by the user
  (pull to refresh, with a cooldown), never automatically.
- **Lead time** has no default in `TrackerConfig`. On first run no value is selected and
  "Start tracking" is disabled until one is chosen. After the first Start the chosen value is
  stored as the default and can be changed in Settings.
- **From equal to To** disables the board until they differ. **An empty board** (HTTP 204 is
  an empty list) shows "No trains found" with a retry. If the arrival is sooner than the lead
  time, the scheduler fires at once, so CONFIRM says "The alarm will ring straight away".
  A missing live estimate falls back to the scheduled time (`Stop.bestArrival`).
- **Nearest: X** is a chip on From, never a silent default. Location is requested only when
  the user taps it, and the field works fully if they refuse.
- **"I'm off"** is on screen at all times while tracking and while ringing. It stops the
  tracker, cancels the alarm and returns to HOME.
- **Permissions** are requested just before Start, each with a one-line reason the user can
  cancel, and never re-prompted automatically. Required: iOS AlarmKit authorisation;
  Android exact-alarm access and notifications. Refusing a required permission blocks
  Start: the button reads "Allow alarms to continue" and opens Settings. Optional: location
  (while in use only; Stage 4 never requests background location) and, on Android 14 and
  later, full-screen intent. Refusing an optional one is a specific, non-blocking warning
  on the CONFIRM sheet with an "Open settings" button.
- **Backgrounding.** Stage 4 does not track in the background. If the app is suspended,
  polling may stop; the timetable alarm scheduled at Start still fires. The tracking
  screen says so ("Keep the app open for live updates").
- **Time.** All times are shown in `Europe/London`, whatever the device zone. Countdowns use
  absolute instants and are correct across midnight and the BST and GMT changes. A train
  departing on a later date than today is labelled with its date.
- **Ringing.** On iOS the lock-screen alert is Apple's system alert: we set its title,
  button text and tint, and the in-app ringing view is best effort. On Android the ringing
  UI is our full-screen `AlarmActivity`, restyled.

## 5. Visual system

References: the palette image, the alarm references (dial, dot-matrix times, ruler), the
glow reference (ringing screen) and the map reference (tracking layout, route strip now,
map in 4b).

| Token | Dark | Light |
|---|---|---|
| bg | `#201D1C` | `#CFC3B6` |
| surface | `#2C2724` | `#E4DCD2` |
| surface2 | `#3A322E` | `#F1EBE3` |
| text | `#CFC3B6` | `#201D1C` |
| head | `#F3ECE3` | `#201D1C` |
| muted | `#B7AA9C` | `#6C584C` |
| accent (orange) | `#F36509` | `#F36509` |
| onAccent | `#201D1C` | `#201D1C` |
| teal | `#015D67` | `#015D67` |
| onTeal | `#F3ECE3` | `#F3ECE3` |
| tealText | `#2FA3B1` | `#015D67` |
| bad | `#FF8F7A` | `#A32C1B` |

Orange and teal are fills only: near-black text on orange (5.3:1) and cream on teal
(7.6:1) pass; teal text on near-black (2.2:1) and orange text on beige (1.8:1) fail. Small
text on the beige ground uses `text`, never `muted` (3.9:1); `muted` is allowed on
`surface` (4.9:1). The ringing screen is the same in both themes. A unit test computes the
WCAG ratio from the token values and asserts at least 4.5:1 for: text on bg, text on surface,
head on surface, muted on surface, onAccent on accent, onTeal on teal, tealText on surface,
bad on surface, in both themes.

Type: Anton for headlines and buttons, Doto (dot-matrix) for every time readout, the system
font for body text. Both faces are SIL OFL, downloaded from Google Fonts. Files live in
`ios/Sources/TrainAlarm/Resources/Fonts/` (registered with `UIAppFonts` in `project.yml`) and
`android/app/src/main/res/font/`, with their licence files. The first plan task checks the
licence text. A runtime check logs a warning if a font failed to load, because a missing
font silently falls back. **The colon in a time is drawn by us as two dots**, not taken from
the Doto glyph, which reads as a plus sign. One `TimeText` helper per platform formats and
draws times.

Theme: System, Dark, Light, stored in `UserDefaults` / `SharedPreferences`, applied
app-wide, with a dark-aware window background on Android so the first frame does not flash
light (`res/values/themes.xml`).

## 6. Architecture

Both platforms, in the matching layer (`App/` on iOS, package root on Android):

- `SessionModel`: the single observable holder of session state (`idle`, `selecting`,
  `confirming`, `tracking`, `ringing`, `ended`) and what each screen shows. It owns one
  `JourneyTracker`, collects its events once and re-exposes display state, so a screen
  rebuild never loses events. It holds no tracking logic. Dependencies are injected:
  `TrainDataProvider`, `AlarmScheduler`, `StationDirectory`, `LocationSource`,
  `TrackerClock`, `SettingsStore`.
  - iOS: an `@Observable` class created once in `TrainAlarmApp`.
  - Android: an application-scoped holder (not a plain `ViewModel`, which would lose the
    tracker with the Activity). Process death ends the session; resume is out of scope.
  - "I'm off" calls `JourneyTracker.stop()` and clears state. Board requests are cancelled
    when the user leaves the screen (the providers propagate cancellation).
- Screens: one view per screen in section 4, dumb views over `SessionModel` state. On
  Android a hand-rolled state switch replaces a navigation library.
- `SystemClock`; a real `LocationSource` (Core Location on iOS; the platform
  `LocationManager` on Android, avoiding Play Services); the "clock and GPS plumbing" Stage 2
  left for this stage.
- **Real coordinates.** A decorating step (in the composition root) resolves each `Station`
  in a board or service by CRS through `StationDirectory`; `TrackerPlan.destinationCoordinate`
  comes from the same lookup. The 11 Elizabeth line stations with no coordinate stay absent.
- `Theme`, `TimeText`, `SettingsStore`, permission helpers, `RouteStrip` (draws the calling
  points with the train between the last departed stop and the next, from `Stop.hasArrived`
  and times).
- **Demo mode** (debug builds only): a scripted `DemoProvider` that builds its services in
  code (the test fixtures are not in the app bundles), a `DemoClock` that can speed up time,
  and a no-op `AlarmScheduler` so demo mode never arms a real alarm. The UI labels demo
  content as demo.
- **Alarm presentation.** `AlarmKitClient.schedule` and `AlarmKitScheduler.schedule` get
  parameters for title, button text and tint instead of the hard-coded values.
- **Ringing signal.** Android: `AlarmService` publishes ringing state through an in-process
  `StateFlow` in an `AlarmEvents` object, which `SessionModel` collects; "I'm off" in
  `AlarmActivity` also calls `SessionModel`. iOS: a plan task finds the supported signal
  (the AlarmKit update stream is the candidate), verifies it on a device, and stops and
  reports if there is none; the fallback is that the in-app view appears when the app returns
  to the foreground after the fire time.
- Android dependencies added: `androidx.lifecycle` runtime and viewmodel compose artifacts
  only if a plan task needs them, checked against the BOM and `.ai/tech-stack.md`. No map
  package and no navigation library. State management is MVVM with `SessionModel`, and
  dependency injection is constructor injection from one composition root per platform;
  both close TBDs in `.ai/`.

Event handling (the model is idempotent: events are state transitions, not toggles, and a
test feeds `feedLost` twice and `destinationLost` after `destinationRestored`):

| Tracker event | Display |
|---|---|
| `etaChanged` | arrival time, delay badge, alarm time |
| `etaEstimated` | "estimated" label (GPS pace or last known) |
| `destinationLost`, `serviceCancelled` | urgent card; tracker keeps running; alarm stays armed |
| `destinationRestored` | clears the card |
| `feedLost` | data-health badge, last good update time |
| `feedRecovered` | clears the badge |
| `alarmSchedulingFailed` | blocking card "Your alarm is NOT set", with retry (the tracker retries on its next tick) |
| `arrived` | ENDED |
| HTTP 429 (`FailureKind.http(429)`) | "Rate limited, retrying" state |

## 7. Provider changes and the token

- `departureBoard` gains an optional `callingAt: Station?` on both platforms. When set,
  `RttProvider` adds `filterTo=<CRS>` to `/gb-nr/location` (not the generic `/rtt/location`).
  Existing calls and tests are unchanged; new tests cover the query and the default.
- `RttProvider.searchStations` stays unwired as the project rule says, until this stage
  deliberately connects it: the picker's search calls `StationDirectory.search` through a
  small adapter. The plan picks delegation or the adapter and updates `docs/spec.md` §3.
- **Token.** RTT's terms forbid a token in a distributable app (section 3). Therefore a
  build that contains a token is for the developer's own devices only: no TestFlight, Play
  testing or shared APK is built with a token until a proxy exists (Stage 6 gate). The token
  is read at build time and is extractable from the binary, which is why.
  - iOS: `ios/Config/Secrets.xcconfig`, attached through `configFiles` in `project.yml`
    with `#include?` so a missing file does not break the build, surfaced in Info.plist
    through a build setting. `ios/Config/Secrets.xcconfig` is added to `.gitignore`.
  - Android: read from `android/local.properties` into a `BuildConfig` field, with
    `buildFeatures { buildConfig = true }` and an empty default when the file is absent
    (CI and fresh worktrees have none).
  - Stage 4 supports a long-life access token. If the issued token turns out to be a
    refresh token, the exchange through `/api/get_access_token` is added then.
  - If the token is missing, the app shows "Live data is not set up" and debug builds offer
    demo mode.
- **Request budget.** The tracker polls every 45 s today, about 80 requests an hour for one
  user, against a limit of 100 an hour (unverified). The app builds `TrackerConfig` with a
  60 s poll interval; `maxFixAge` is derived from it (3 polls, 180 s). Board refresh is
  manual with a cooldown. HTTP 429 shows the rate-limited state. This is a stopgap until a
  proxy or higher limits exist.

## 8. Tracking visual in Stage 4, and the map

Stage 4 draws a route strip in the mockup's map position: a line through the calling
points, with the train marker between the last departed stop and the next, the destination
marked, and progress shown as in the mockup's scrubber. It uses only data we have.

The map is Stage 4b, with its own spec. Reasons: an Android map cannot be screenshot-checked
until an emulator is installed; a map package adds a key, billing, licence and attribution
questions on both platforms that are unchecked; the palette look may not be reachable with
platform map styles; and we hold station coordinates only, so a route line would be a
polyline through stops, not the railway. The 4b spec starts with the verification spike.

## 9. Deferred and open

- **Stage 4b:** the map (provider spike, styling, keys, attribution).
- **Stage 5:** background tracking (foreground service, ongoing notification, background
  location), resuming a journey after the app is killed.
- Saved or recent journeys and an alarm-list home: the first thing after Stage 4.
- iOS Live Activity. UI-test frameworks (the testing-guide TBD is decided here: not added).
- **Backend proxy, a decision before any beta.** RTT requires it for distributed apps, and
  one shared key cannot serve many users within the rate limits; a server that holds the key
  and shares one poll between everyone on the same train solves both. The UI is unaffected
  because it talks only to `TrainDataProvider`. Open: RTT's authorisation terms, higher
  limits, whether the issued token is an access or refresh token.
- Darwin registration (Data Feed Request Form): the user may start it in parallel; no code.
- Android alarm device test from Stage 3: on hold, still not done.

## 10. Verification

- Unit tests (XCTest, JUnit): `SessionModel` through every state and every event in the
  table, permission refusals per permission, the blocking alarm permission, first run,
  empty board, From equal to To, 429, idempotent events; `TimeText` (separator, formatting,
  23:59 to 00:01, a BST and GMT change, `Europe/London` from another device zone); the
  `callingAt` query and its default; coordinate resolution; the search adapter;
  `SettingsStore`; the contrast test.
- Screenshots: all screens, dark and light, via the scenario launcher (section 1, item 3).
- Builds: `xcodebuild test` and `./gradlew test lint assembleDebug` locally. Nothing is
  pushed during implementation; the squash push is the one push, and CI runs on it. CI
  is path-filtered, so a docs-only change does not run it.
- Not verified in this stage, and said so in the PR: live RTT data, alarm delivery on
  Android, background behaviour, and the iOS in-app ringing signal unless its plan task
  completes on a device.

## 11. Files

Both platforms add UI code (`App/` and the package root), fonts and licences, the
provider change, and the tests above. Touched: the provider protocol and `RttProvider`,
`ContentView` and `MainActivity` (replaced), `AlarmKitClient` and `AlarmKitScheduler`
(presentation parameters), `AlarmActivity` and `AlarmService` (restyle, ringing signal), the
Android manifest (the stale "for Stage 5" comment on the location permissions) and
`themes.xml`, `ios/project.yml`, `android/app/build.gradle.kts`, `.gitignore`.
`docs/ui-mockup/` and `docs/ux-reference/` are committed in the spec commit as reference
material; the latter contains other designers' work and the repo should not be made public
with it. Docs updated in the final task: `README.md`, `.ai/architecture.md` (state
management, DI), `.ai/tech-stack.md` (secrets), `.ai/testing-guide.md` (UI tests),
`docs/spec.md` (§3 search wiring, §8 Stage 4 status and Stage 4b entry).
