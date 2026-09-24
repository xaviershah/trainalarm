# Tech stack

## iOS

| | |
|---|---|
| Language | Swift (`SWIFT_VERSION` 5.0 in `project.yml`) |
| UI | SwiftUI |
| Deployment target | iOS 26.0 (required by AlarmKit) |
| Project | XcodeGen (`ios/project.yml`); `.xcodeproj` is generated, not committed |
| Alarms | AlarmKit — entitlement keys TBD, read from Apple docs before adding |
| Location | Core Location, background `location` mode |
| Networking/JSON | `URLSession` + `JSONSerialization` (no third-party deps) |
| Concurrency | `async`/`await` |

## Android

| | |
|---|---|
| Language | Kotlin 2.0.20, JVM target 17 |
| UI | Jetpack Compose (BOM 2024.09.02), Material 3 |
| SDK | minSdk 29, target/compileSdk 35 |
| Build | Gradle 8.9, AGP 8.6.0, Kotlin DSL (`.kts`) |
| Alarms | Foreground service + `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` |
| Networking/JSON | `org.json` (platform), no third-party HTTP client |
| Concurrency | Kotlin coroutines 1.9.0 |

## External services

- **Realtime Trains API** (bearer token). Schema: `downloads/RTT.GH.API-spec`.
  The legacy `api.rtt.io` shuts down 30 Sep 2026; don't use it.
- CI: GitHub Actions (`macos-15` for iOS, `ubuntu-latest` + JDK 17 for Android).

## Error handling

- Provider methods are `async throws` (iOS) / `suspend` (Android).
- iOS: typed `RttError` enum — `httpError(code)`, `malformedResponse(reason)`,
  `notImplemented`. Parsers throw on a missing required field; they don't
  return partial data.
- Android: no typed error class yet; relies on platform exceptions
  (`JSONException`, `IOException`). **TBD:** add a matching sealed error type
  so both platforms fail the same way.
- Live-change handling (cancellations, dropped stops, delays) is modelled as
  data, not as errors (spec §4).

## Logging

**TBD.** Nothing chosen yet. Candidates: `os.Logger` (iOS) and
`android.util.Log` / Timber (Android). Decide before Stage 3; background
alarm failures will be hard to debug without logs.

## TBD

- Secrets handling for the RTT token (Keychain / EncryptedSharedPreferences?).
- Swift 6 language mode.
- Monetisation / analytics SDKs (none planned).
