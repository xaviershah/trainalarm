# Stage 3 — Alarm delivery spike: design

Date: 2026-11-10. Branch: `feature/alarm-delivery-spike`. Source of truth for the product is
`docs/spec.md` (§5 Alarm delivery, §8 Stages).

## 1. Goal and "ships when"

Write the real `AlarmScheduler` on both platforms and prove, on a real device, that an alarm
fires **with the phone locked and the silent switch / ringer silenced** at a scheduled time.

**Ships when:**

- **iOS** — on the iPhone 12 Pro (iOS 26+, real device): an alarm scheduled 60 s ahead fires with
  the phone locked, the ring/silent switch on silent and a Focus mode on; a cancelled alarm does
  not fire; with authorization denied, `schedule` throws.
- **Android** — on a physical Android 14+ phone: the same, plus the exact-alarm, notification and
  full-screen-intent denied paths behave as specified in §5.
- **Both** — unit tests (fake-driven) pass and the app builds on each platform locally.

A simulator or emulator result is not a pass for alarm delivery (project rule).

**Stage status rule.** No Android phone is available at the time of writing. Stage 3 is recorded in
`docs/spec.md` §8 as "iOS verified on device; Android built and unit-tested, device test pending"
and is **not marked DONE** until the Android checklist (§7) has been run on a physical phone.

## 2. Scope

In scope:
- iOS `AlarmKitScheduler` with the authorization flow.
- Android `ExactAlarmScheduler`, receiver, foreground service, alarm activity, and the permission
  flows (exact alarm, notifications, full-screen intent).
- A debug-only harness on each platform: "alarm in N seconds" and "cancel".
- Minimal logging in the new alarm code (`os.Logger` / `android.util.Log`).
- Two Stage 2 tracker gaps (§6).

Out of scope (unchanged from the brief): the timetable fallback notification, background location,
battery-optimisation allowlisting and rescheduling after reboot (Stage 5), real UI (Stage 4),
`USE_EXACT_ALARM` / Play policy (Stage 7), custom alarm sounds, repeating alarms, countdowns.

## 3. Verified facts, and what could not be verified

Read on 2026-11-10 from Apple's and Google's own pages.

**iOS** (Apple: *Scheduling an alarm with AlarmKit*, `AlarmManager`, `Alarm.Schedule`,
`AlarmManager.AuthorizationState`, `AlarmManager.AlarmError`):
- AlarmKit is iOS 26.0+. The app needs `NSAlarmKitUsageDescription` in `Info.plist`; if the key is
  missing or empty, apps can't schedule alarms.
- Alarms "override both a device's focus and silent mode, if necessary".
- `Alarm.Schedule.fixed(Date)` is a one-shot alarm at an absolute time (not time-zone relative).
- `AlarmManager.shared.schedule(id:configuration:) async throws -> Alarm`;
  `cancel(id:) throws` (synchronous); `requestAuthorization() async throws -> AuthorizationState`;
  `authorizationState`; `AuthorizationState` is `.authorized | .denied | .notDetermined`.
- If `requestAuthorization()` is never called, AlarmKit requests it at the first schedule. If
  authorization is not granted, the alarm "isn't scheduled and subsequently doesn't alert".
- `AlarmError.maximumLimitReached` is the only documented schedule error.
- A widget extension is only needed when the app uses a countdown presentation. This design is
  alert-only.

**Android** (Google: *Schedule exact alarms are denied by default*, *Restrictions on starting a
foreground service from the background*, *Foreground service types are required*, *Android 14
behaviour changes*):
- `SCHEDULE_EXACT_ALARM` is pre-granted on API 31-33 and is **not** pre-granted on API 34+ for new
  installs targeting Android 13+ (denied by default). It is required
  for `setExact`, `setExactAndAllowWhileIdle` **and `setAlarmClock`**, otherwise a
  `SecurityException` is thrown. (An unofficial page claimed `setAlarmClock` is exempt; Google's page
  says it is not.)
- Flow: `canScheduleExactAlarms()`; if false, start `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` with the
  package name; re-check in `onResume()`; optionally listen for
  `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`.
- A foreground service may be started from the background when the app "invokes an exact alarm to
  complete an action that the user requests".
- Foreground service types are mandatory on Android 14 (targetSdk ≥ 34). `mediaPlayback` needs the
  normal permission `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and has no runtime prerequisites.
  `systemExempted` is allowed for apps holding `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` that use a
  foreground service "to continue alarms in the background".
- For apps targeting Android 14+, `USE_FULL_SCREEN_INTENT` is limited to calling and alarm apps;
  users can toggle it; `NotificationManager.canUseFullScreenIntent()` checks it and
  `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` opens the settings page.

**Not verified (to be settled on a device, or deferred):**
- Whether AlarmKit needs any entitlement or paid-account capability. None is listed in Apple's
  pages, but that is absence of evidence. **Gate:** if the first device build is rejected for
  signing/capabilities, stop and report; do not guess an entitlement key (`ios/CLAUDE.md`).
- What AlarmKit does with a `fixed` date in the past or "now". The tracker clamps late fire times to
  `now` (`JourneyMonitor.swift:155,169`), so this path is real (boarded late, lead time already
  passed). Both schedulers therefore apply a minimum lead (§4, §5) and the checklists record what
  happens with 0 s and past times.
- AlarmKit behaviour when scheduling under an id that already exists, and whether `cancel(id:)` stops
  an alarm that is currently alerting — device checks (§7).
- The exact `AlarmAttributes` / `AlarmPresentation.Alert` initialiser signatures (e.g. `tintColor`,
  `stopButton`) for the iOS point release in use — confirmed by compiling.
- `setAlarmClock` behaviour under Doze (documentation read did not cover it) — device check.
- Android 15/16 behaviour changes (targetSdk is 35); Play policy for `USE_EXACT_ALARM` and
  `USE_FULL_SCREEN_INTENT` (Stage 7).
- Whether `AlarmPresentation.Alert` accepts an empty/absent secondary button as used here, and the
  default alarm sound, are confirmed by compiling and running on the device.

## 4. iOS design (`ios/Sources/TrainAlarm/Alarm/`)

- `AlarmKitClient` (internal protocol): `authorizationState`, `requestAuthorization()`,
  `schedule(id:fireAt:title:)`, `cancel(id:)`. `SystemAlarmKitClient` implements it over
  `AlarmManager.shared`. Unit tests use a fake client; the real `AlarmManager` is only exercised on
  the device.
- `AlarmKitScheduler: AlarmScheduler` (init takes a client and a `UserDefaults` for the id):
  - Remembers the id of the **current alarm** (a UUID, persisted in `UserDefaults`).
  - `schedule(fireAt:)`: ensure authorized — `.authorized` proceeds; `.notDetermined` calls
    `requestAuthorization()` and proceeds only if it returns `.authorized`; `.denied` or a denied
    result throws `AlarmSchedulerError.notAuthorized`. The fire time is raised to at least
    `now + 2 s` (minimum lead; covers the tracker's clamp-to-now). **Make before break:** schedule a
    `.fixed(fireAt)` alert-only alarm (placeholder title, Stop button, no countdown, no secondary
    button) under a **fresh UUID**, then cancel the previous id (errors ignored: "nothing to cancel"
    is normal), then persist the new id. If the new schedule throws, the old alarm is left in place
    and the error propagates unchanged (e.g. `maximumLimitReached`) so the tracker's refusal path
    handles it.
  - `cancel()`: calls the client's `cancel(id:)` for the current id and swallows its error, because
    the protocol's `cancel() async` does not throw; then clears the stored id.
  - Authorization must be obtained in the foreground (the system prompt cannot appear from the
    background) — a Stage 4/5 constraint: request it before a journey starts. The harness exercises
    it.
- `AlarmAttributes` needs a metadata type: a small empty `AlarmKitMetadata: AlarmMetadata`.
- `Info.plist` gets `NSAlarmKitUsageDescription` via `project.yml` (the project's Info.plist is
  generated). `TrainAlarm.entitlements` stays as is unless the device build proves otherwise; its
  TODO comment is replaced with what was verified.
- **Harness** (`#if DEBUG`, `App/`): a screen with a seconds field, "Schedule alarm" and "Cancel"
  buttons, and the last result or error shown as text.

## 5. Android design (`android/app/src/main/java/com/trainalarm/app/alarm/`)

- `ExactAlarmGateway` (internal interface): `canScheduleExactAlarms()`,
  `setAlarmClock(fireAt: Instant)`, `cancel()`. `SystemExactAlarmGateway(context)` implements it with
  `AlarmManager` and one fixed `PendingIntent` (explicit intent to `AlarmReceiver`,
  `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT`, fixed request code), plus an `AlarmClockInfo` show-intent
  that opens `MainActivity`. Re-setting with the same `PendingIntent` replaces the previous alarm.
- `ExactAlarmScheduler : AlarmScheduler`:
  - `schedule(fireAt)`: if `!canScheduleExactAlarms()` throw
    `AlarmSchedulerException.ExactAlarmNotPermitted`; otherwise `setAlarmClock` at
    `max(fireAt, now + 2 s)` (same minimum lead as iOS). A `SecurityException` from the OS
    (permission revoked between check and call) is rethrown as the same typed exception.
  - `cancel()`: gateway cancel, **and** stops `AlarmService` if it is ringing (so the tracker's
    cancel-on-arrival and `stop()` silence a ringing alarm on Android).
- `AlarmReceiver` (`BroadcastReceiver`, not exported): starts `AlarmService` with
  `startForegroundService`. If that throws `ForegroundServiceStartNotAllowedException` (API 31+), it
  falls back to posting the full-screen notification and playing the sound without a service.
- `AlarmService` (not exported), `foregroundServiceType="mediaPlayback"`, started via
  `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)` immediately in
  `onStartCommand`, returns `START_NOT_STICKY`. The notification (channel `alarm`, high importance,
  category alarm, channel sound disabled because the service plays the audio itself) has a
  full-screen intent that opens `AlarmActivity` and a Stop action (`FLAG_IMMUTABLE` PendingIntent).
  Plays the default `TYPE_ALARM` ringtone (falling back to `TYPE_RINGTONE` if none) on
  `AudioAttributes.USAGE_ALARM`, looping, until Stop or a safety timeout of 5 minutes. The user's
  alarm volume is not overridden; "alarm volume at 0 is silent" is an expected observation.
- `AlarmActivity`: `setShowWhenLocked(true)` and `setTurnScreenOn(true)`, shows a "Stop" button that
  stops the service.
- `AlarmPermissions` helper: `exactAlarmIntent()` / `canScheduleExactAlarms()`, notification
  permission state (`POST_NOTIFICATIONS`), `canUseFullScreenIntent()` and its settings intent. The
  harness screen shows each state and a button to fix it. **API-level guards (minSdk is 29):**
  below API 31 `canScheduleExactAlarms()` is true; below API 33 notification permission counts as
  granted; below API 34 `canUseFullScreenIntent()` is true. `mediaPlayback` exists from API 29, and
  the newer permission names are harmless in the manifest on older versions. `lint` (`NewApi`) is the
  check.
- Manifest: add `USE_FULL_SCREEN_INTENT` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`; declare the
  receiver, service (with type) and activity. `FOREGROUND_SERVICE_LOCATION` stays declared for
  Stage 5 and is not used here. The "declared ahead of use" comment is updated.
- **Fallback** if `mediaPlayback` misbehaves on a device: `systemExempted` (needs
  `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` and a Play declaration). Recorded, not built.
- **Harness** (shown only when `applicationInfo.flags and FLAG_DEBUGGABLE` is set, since `BuildConfig`
  is not enabled): seconds field (0 allowed, to test the minimum lead), Schedule, Cancel, and the
  three permission states from `AlarmPermissions`.
- The scheduler and gateway seam are unit-tested on the JVM. The receiver, service and activity are
  verified by `assembleDebug` + `lint` and by the device checklist; no Robolectric is added.

## 6. Tracker gaps fixed in this stage (both platforms)

1. **Android `scheduler.cancel()` is unguarded** (`JourneyTracker.kt:110` on the `Cancel` action and
   `:55` in `stop()`). Change: a non-cancellation exception from `cancel()` is caught and logged and
   no new event type is added, so it cannot escape `run()` and crash the launching scope; `stop()`
   always reaches `channel.close()` (try/finally). `CancellationException` still propagates. iOS
   needs no change here because `AlarmScheduler.cancel()` does not throw. The Android
   `FakeAlarmScheduler` (`TrackingFakes.kt:48-68`) gets a `cancelFailure` so this is testable.
2. **`start()` after `stop()`** currently re-arms the alarm into an already-closed event stream
   (`JourneyTracker.kt:46-49`, `JourneyTracker.swift:41-44`); `stop()` resets the loop/job to null,
   so the existing guards don't catch it. Change: a `stopped` flag set by `stop()`; once set,
   `start()` does nothing. The doc comment says a tracker is single-use. One test per platform.
3. **Clamped fire time on iOS** is not covered by existing scheduler tests because no real scheduler
   existed; §7 adds a test that a `now` fire time is raised to the minimum lead.

The tracker's retry after a scheduler refusal already exists (Stage 2) and is not changed.

## 7. Verification plan

**Unit tests (fake-driven, run locally):**
- iOS (`ios/Tests/TrainAlarmTests/Alarm/AlarmKitSchedulerTests.swift`): authorized → schedules with
  `.fixed` date and the stored id; `notDetermined` → requests, then schedules; `notDetermined` →
  denied → throws `notAuthorized` and schedules nothing; `denied` → throws; replace order
  (new alarm scheduled under a fresh id, then the previous id cancelled, then the new id persisted;
  a failing schedule leaves the previous alarm and id in place); current id survives a new
  scheduler instance; a fire time at or before `now` is raised to `now + 2 s`; client `cancel` error
  swallowed by `cancel()`; `schedule` error propagates; plus the `start()`-after-`stop()` test in the
  tracker tests.
- Android (`.../alarm/ExactAlarmSchedulerTest.kt`): permitted → `setAlarmClock` with the instant;
  not permitted → `ExactAlarmNotPermitted`, nothing scheduled; `SecurityException` from the gateway →
  `ExactAlarmNotPermitted`; a fire time at or before `now` is raised to `now + 2 s`; `cancel()`
  delegates and stops a ringing service; plus tracker tests for a throwing `cancel()` on the arrival
  path and in `stop()` (stream still closes) and for `start()` after `stop()`.
- Commands: `xcodebuild test` (see `.ai/testing-guide.md`) and `./gradlew test assembleDebug lint`.
  CI (`.github/workflows/android.yml`) runs only `assembleDebug` and `test`, so `lint` is a
  **local-only gate** in this stage; adding it to CI would change a workflow file and is left to the
  user's decision.
- **Device signing:** `ios/project.yml` has no `DEVELOPMENT_TEAM` and `ios/Generated/` is gitignored.
  Supply the team without committing it: set it in Xcode's Signing UI after `xcodegen generate`, or
  pass `DEVELOPMENT_TEAM=<id>` on the `xcodebuild` command line. If `project.yml` entitlement
  `properties` ever becomes non-empty, update it and the entitlements file together (XcodeGen
  regenerates that file).

**iPhone 12 Pro checklist (real device, iOS 26+, Developer Mode on, build via Xcode):**
1. Fresh install; tap Schedule (60 s); the system permission prompt appears with the usage string.
   Allow. Lock the phone, silent switch on silent, a Focus mode on. The alarm sounds and shows the
   alert at 60 s; Stop ends it.
2. Schedule, then Cancel before it fires; confirm no alert at the time.
3. Settings → TrainAlarm → disable alarms; tap Schedule; the harness shows `notAuthorized`.
4. Schedule 60 s, then **force-quit the app** and lock the phone; the alarm must still fire.
5. Schedule with 0 s, and with a past time via a debug option; record what AlarmKit does.
6. Schedule twice in a row (second time later than the first); only the second must fire.
7. Cancel while the alarm is alerting; record whether it stops.
8. Record results (iOS version, pass/fail, any oddity) in the PR description.

**Android checklist (physical Android 14+ phone — pending):**
1. Install debug build; with exact alarms denied, Schedule shows `ExactAlarmNotPermitted`; the fix
   button opens the system page; grant; return; Schedule succeeds.
2. 60 s alarm, phone locked, ringer on silent and Do Not Disturb on: the alarm sounds on the alarm
   stream and `AlarmActivity` shows over the lock screen; Stop ends it.
3. Cancel before firing: nothing fires.
4. Notifications denied and full-screen intent denied: record what the user sees and hears; the
   alarm sound must still play.
5. Swipe the app away from recents after scheduling; the alarm must still fire.
6. Force Doze (`adb shell dumpsys deviceidle force-idle`, which works on a physical device) after
   scheduling, and separately test with battery saver on; the alarm must still fire.
7. Schedule with 0 s; record the behaviour. Cancel while ringing; the sound must stop.
8. Record device model and Android version.

## 8. Risks

- AlarmKit behaves differently from its docs on the real device (the reason this is a spike). The
  result is reported either way.
- No Android device now, so Android delivery stays unproven; the status rule in §1 keeps that visible.
- Free Apple ID signing may not be enough; the gate in §3 stops the work rather than guessing.

## 9. Docs to update in this stage

`docs/spec.md` §5 and §8, `.ai/tech-stack.md` (logging decision, foreground service type),
`.ai/architecture.md` (Alarm layer row), `ios/Sources/TrainAlarm/Alarm/README.md`,
`ios/CLAUDE.md` / `android/CLAUDE.md` if their statements become stale, and the entitlements and
manifest comments.
