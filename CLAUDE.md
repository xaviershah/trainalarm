Follow the established feature/bugfix development workflow.
Project docs are in .ai/.

# TrainAlarm

Product and technical spec: `docs/spec.md` (source of truth). Platform-specific
commands and conventions: `ios/CLAUDE.md`, `android/CLAUDE.md`.

## Project rules

- Work is organised into stages (docs/spec.md §8). A stage's "ships when" line
  is its acceptance criterion; put it in the feature spec.
- Nothing is done without something to verify it: a test, a build, or (for UI)
  a screenshot. "Looks right" doesn't count.
- Build every feature on both platforms, in the matching layer (see .ai/architecture.md).

## Do not

- Do not invent RTT / National Rail API response shapes. Read the real schema
  in `downloads/RTT.GH.API-spec` before touching `RttProvider`/`RttMapper`, and
  don't extend the parser from memory. Don't use the legacy `api.rtt.io`
  (shut down 30 Sep 2026).
- Do not invent station coordinates or fake a station-search endpoint. Both
  wait for a static UK station dataset (CRS + name + lat/lon) that doesn't exist yet.
- Do not add a shared cross-platform code layer (React Native, Flutter, KMP).
  Native-only was a deliberate decision (spec §6).
- Do not treat a simulator or emulator result as a pass for alarm delivery
  (Stage 3). It needs a real device.
- Do not rely on Darwin / National Rail Data Portal until registration has
  been confirmed hands-on.
