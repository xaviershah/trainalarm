# TrainAlarm — Android conventions

Kotlin, Jetpack Compose, Gradle Kotlin DSL (`.kts`). Package root:
`com.trainalarm.app`.

## Commands
- Build: `./gradlew assembleDebug` (from `android/`)
- Unit tests: `./gradlew test`
- Lint: `./gradlew lint`

## Conventions
- One package per architectural layer (`model`, `provider`, `tracking`,
  `alarm`, and UI directly under `app`) — matches the stages in
  ../docs/spec.md §8, don't reorganise without updating that doc too.
- `TrainDataProvider` is an interface in `provider/`; the RTT
  implementation is a separate class implementing it — keep call sites
  depending on the interface, never the concrete RTT type, per the
  provider-boundary decision in docs/spec.md §2.
- Background-location and exact-alarm permission requests are real,
  user-facing runtime flows (not manifest-only) — see AndroidManifest.xml
  comments and docs/spec.md §5 before touching Stage 3 code.
