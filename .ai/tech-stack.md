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

## Tooling

- Python 3, standard library only, for `tools/stations/` (build-time only, not bundled in the apps).

## External services

- **Realtime Trains API** (bearer token). Schema: `downloads/RTT.GH.API-spec`.
  The legacy `api.rtt.io` shuts down 30 Sep 2026; don't use it.
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

**TBD.** Nothing chosen yet. Candidates: `os.Logger` (iOS) and
`android.util.Log` / Timber (Android). Decide before Stage 3; background
alarm failures will be hard to debug without logs.

## TBD

- Secrets handling for the RTT token (Keychain / EncryptedSharedPreferences?).
- Swift 6 language mode.
- Monetisation / analytics SDKs (none planned).
