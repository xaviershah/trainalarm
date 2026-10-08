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
- `docs/` — spec, architecture notes, per-stage plans.

No code is shared between `ios/` and `android/` — only the `TrainDataProvider`
contract and the overall architecture, documented in `docs/spec.md`, are
shared. Each platform is a fully separate native codebase.

## Status

Stages 1-2 done (journey model, UK provider, station data; tracking, ETA and
live-change logic, with no UI yet); Stage 3 (alarm delivery spike, real device
only) is next. See `docs/spec.md` §8 for the full stage list.

## Station data

`data/stations.json` lists 2,626 Great Britain rail stations (`crs`, `name`,
`lat`, `lon`), generated from NaPTAN (Department for Transport) under the Open
Government Licence v3.0. Attribution wording and the exact source are in
[`data/STATIONS-SOURCE.md`](data/STATIONS-SOURCE.md); the attribution must be
shown in the app UI (not yet done). Names are NaPTAN's, and 11 Elizabeth line
codes with no NaPTAN coordinates are absent.

To regenerate, follow the "Regenerating" steps in `data/STATIONS-SOURCE.md`
(download the NaPTAN rail XML, run `tools/stations/generate_stations.py`, then
`tools/stations/sync_stations.py` to copy it into both app bundles). Tests:
`python3 -m unittest discover -s tools/stations -p "test_*.py" -v`.
