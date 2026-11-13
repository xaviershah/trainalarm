# Tech stack

## iOS

| | |
|---|---|
| Language | Swift (`SWIFT_VERSION` 5.0 in `project.yml`) |
| UI | SwiftUI |
| Deployment target | iOS 26.0 (required by AlarmKit) |
| Project | XcodeGen (`ios/project.yml`); `.xcodeproj` is generated, not committed |
| Alarms | AlarmKit; `NSAlarmKitUsageDescription` in Info.plist, no entitlement key added |
| Location | Core Location, background `location` mode |
| Networking/JSON | `URLSession` + `JSONSerialization` (no third-party deps) |
| Concurrency | `async`/`await` |
| UI state | `@Observable` `SessionModel`; settings in `UserDefaults` |
| Fonts | Anton and Doto (SIL OFL 1.1), bundled in `Sources/TrainAlarm/Resources/Fonts`; the Doto runtime name is `Doto-Black_Bold` |
| Secrets | Optional gitignored `ios/Config/Secrets.xcconfig`, applied to the Debug configuration only and read through Info.plist (`RTTAccessToken`) |

## Android

| | |
|---|---|
| Language | Kotlin 2.0.20, JVM target 17 |
| UI | Jetpack Compose (BOM 2024.09.02), Material 3 |
| SDK | minSdk 29, target/compileSdk 35 |
| Build | Gradle 8.9, AGP 8.6.0, Kotlin DSL (`.kts`) |
| Alarms | `setAlarmClock` + `SCHEDULE_EXACT_ALARM`, `mediaPlayback` foreground service, `USE_FULL_SCREEN_INTENT` |
| Networking/JSON | `org.json` (platform), no third-party HTTP client |
| Concurrency | Kotlin coroutines 1.9.0 |
| UI state | Compose state in `SessionModel`; settings in `SharedPreferences` |
| Fonts | Anton and Doto (variable font at weight 700, SIL OFL 1.1) in `res/font` |
| Secrets | Optional `rtt.accessToken` in gitignored `local.properties`, as `BuildConfig.RTT_ACCESS_TOKEN` in the `debug` build type only (`release` is empty) |

## Tooling

- Python 3, standard library only, for `tools/stations/` (build-time only, not bundled in the apps).

## External services

- **Realtime Trains API** (bearer token). Schema: `downloads/RTT.GH.API-spec`.
  The legacy `api.rtt.io` shuts down 30 Sep 2026; don't use it.
- **RTT token handling.** The token is build-time config for the developer's own devices only, and only debug builds read it. RTT's
  terms forbid shipping it inside a distributed app, so a backend proxy is needed before any beta.
  Demo mode (debug builds) needs no token. Demo code is not in release builds.
- CI: GitHub Actions (`macos-15` for iOS, `ubuntu-latest` + JDK 17 for Android).

## Error handling

- Provider methods are `async throws` (iOS) / `suspend` (Android).
- Both platforms: a typed `ProviderError` — `network`, `http(code)`,
  `malformed(reason)`, `notImplemented`. Mappers throw `malformed` on a
  missing required field or an empty `locations` array; they don't return
  partial data. Cancellation is never wrapped (`CancellationError` /
  `CancellationException` propagate). HTTP 204 from the departure board is an
  empty list, not an error.
- Android requests have 10s connect and read timeouts; `HttpTransport` is
  injectable so the provider can be tested without a network.
- Live-change handling (cancellations, dropped stops, delays) is modelled as
  data, not as errors (spec §4).

## Logging

`os.Logger` (iOS, category `alarm`) and `android.util.Log` (Android alarm code).
The Android tracker reports a failed alarm cancel through an injectable
`onAlarmError` callback, which the Stage 4 composition root wires to `Log.e`.
A failed alarm schedule is not logged: it surfaces as the "alarm not set" state
in the app (`TrackingDisplay.alarmNotSet`).

## TBD

- A backend proxy that holds the RTT token, before any beta.
- Swift 6 language mode.
- Monetisation / analytics SDKs (none planned).
