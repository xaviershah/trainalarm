# TrainAlarm — root conventions

Full spec: `docs/spec.md`. Read it before starting a new stage of work.

## Structure
- `ios/` and `android/` are independent native codebases. No shared code —
  only the `TrainDataProvider` contract and data model (docs/spec.md §7)
  are shared *in spec*, each platform implements it separately.
- Each has its own `CLAUDE.md` with platform-specific commands/conventions.

## Working style for this project
- Spec-first per stage: don't start a stage without a short written plan
  (use Plan Mode). docs/spec.md §9 lists the stages and each one's "ships
  when" test — treat that line as the actual acceptance criterion.
- Nothing is "done" without something to verify it against: a test, a
  build, or (for UI) a screenshot — never "looks right."
- Prefer a fresh session per stage rather than one long mixed session.
- UK rail data source for v1: Realtime Trains' new bearer-token API (not
  the legacy api.rtt.io, which is being shut down 30 Sep 2026). The new
  API's schema is NOT YET VERIFIED here — see docs/spec.md §3 before
  writing `RttProvider`. Darwin/National Rail Data Portal is the later
  production migration target (unverified registration process — confirm
  hands-on before relying on it).
- Alarm delivery is the highest-risk part of this project (see spec §8) —
  iOS via AlarmKit (requires iOS 26+), Android via foreground service +
  exact-alarm permission. Treat Stage 3 (the alarm delivery spike) as
  needing a real device, not a simulator/emulator, for a valid test.

## Do not
- Do not invent National Rail / RTT API response shapes — check the real
  docs/a real response before writing a parser against them.
- Do not add a shared cross-platform code layer (React Native etc.) —
  that was a deliberate, considered decision (spec §10), not an oversight.
