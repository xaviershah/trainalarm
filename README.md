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
- `data/` — station dataset (`stations.json`) and its source note.
- `tools/stations/` — Python scripts that generate and sync the dataset.
- `docs/` — spec, architecture notes, per-stage plans, the UI mockup (`ui-mockup/`), screenshots
  (`ui-screenshots/`) and `ux-reference/` (other designers' inspiration images, kept for private
  reference: remove them before the repo is made public).

No code is shared between `ios/` and `android/` — only the `TrainDataProvider`
contract and the overall architecture, documented in `docs/spec.md`, are
shared. Each platform is a fully separate native codebase.

## Status

Stages 1-2 done (journey model, UK provider, station data; tracking, ETA and
live-change logic). Stage 3 (alarm delivery spike, real device only): iOS
verified on a real iPhone, Android built and unit-tested, Android device test
pending, so not yet done. Stage 4 (UI build): both platforms have the core
screens (home, confirm, tracking, ringing, ended, settings) and a debug demo
mode that needs no RTT token. The iOS screens were seen in the simulator and
have screenshots in `docs/ui-screenshots/ios/`. The Android screens compiled and
passed unit tests and lint but have never been seen on an emulator or device, so
there are no Android screenshots yet. Nothing has been run against live RTT data
(no token yet), no alarm delivery was checked by Stage 4, and the map is Stage 4b.
See `docs/spec.md` §8 for the full stage list.

## Running the app

- **Demo (debug builds, no token needed):** a debug build without an RTT token opens in
  demo mode with scripted trains from King's Cross to Edinburgh. iOS also takes
  `-scenario <name>` and Android the `scenario` intent extra, to open one screen directly
  (`home`, `board`, `confirm`, `tracking`, `tracking-late`, `cancelled`, `dropped`,
  `feed-lost`, `alarm-not-set`, `ringing`, `ended`, `settings`).
- **With an RTT token (your own devices only):** iOS: copy `ios/Config/Secrets.xcconfig.example`
  to `ios/Config/Secrets.xcconfig` and paste the token. Android: add
  `rtt.accessToken=<token>` to `android/local.properties`. Both files are gitignored. Only debug
  builds read the token (release builds never contain one), because RTT's terms forbid shipping a
  token inside a distributed app; a backend proxy is needed first (`docs/spec.md` §7).
- **Faster demo:** add `-demo-speed 60` (iOS launch argument) or `--ei demoSpeed 60` (Android intent
  extra) to run the demo clock 60 times faster, so the alarm and the arrival happen within minutes.
- **Screenshots:** `tools/screenshots/ios.sh` regenerates `docs/ui-screenshots/ios/`.
  `tools/screenshots/android.sh` (needs a running emulator) is committed but has never been run.

## Station data

`data/stations.json` lists 2,626 Great Britain rail stations (`crs`, `name`,
`lat`, `lon`), generated from NaPTAN (Department for Transport) under the Open
Government Licence v3.0. Attribution wording and the exact source are in
[`data/STATIONS-SOURCE.md`](data/STATIONS-SOURCE.md); the attribution is
shown on the Settings screen. Names are NaPTAN's, and 11 Elizabeth line
codes with no NaPTAN coordinates are absent.

To regenerate, follow the "Regenerating" steps in `data/STATIONS-SOURCE.md`
(download the NaPTAN rail XML, run `tools/stations/generate_stations.py`, then
`tools/stations/sync_stations.py` to copy it into both app bundles). Tests:
`python3 -m unittest discover -s tools/stations -p "test_*.py" -v`.
