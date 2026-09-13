# TrainAlarm

An alarm that knows which train you're on — and wakes you before your stop,
not somewhere near it. Tracks the specific UK rail service you board, blends
live delay data with GPS, and fires a loud, Do-Not-Disturb-overriding alarm
a configurable number of minutes before arrival.

Full product spec, competitive research, and the staged build plan live in
[`docs/spec.md`](docs/spec.md) (source of truth — this README is just the
front door).

## Repo layout

- `ios/` — native iOS app (Swift, SwiftUI, AlarmKit). See `ios/README.md`.
- `android/` — native Android app (Kotlin, Jetpack Compose). See `android/README.md`.
- `docs/` — spec, architecture notes, per-stage plans.

No code is shared between `ios/` and `android/` — only the `TrainDataProvider`
contract and the overall architecture, documented in `docs/spec.md`, are
shared. Each platform is a fully separate native codebase.

## Status

Stage 0 — repo scaffold. See `docs/spec.md` §9 (Development project plan)
for the full stage list.
