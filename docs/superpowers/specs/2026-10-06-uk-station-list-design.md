# UK station list: design

Branch: `feature/uk-station-list` (from `main`)
Context: closes the "static UK station dataset" follow-up from `docs/spec.md` §3 and §8 (Stage 1 left-overs). Unblocks Stage 2 (GPS tracking needs station coordinates) and name-to-CRS resolution.

## Requirement

Add a static dataset of all National Rail stations in Great Britain (about 2,500), each with a CRS code, a name, and a latitude and longitude. It must:

- come from a real, openly licensed source whose licence and fields are verified, not invented;
- be usable in a possibly commercial app (hence an OGL-style licence, not share-alike);
- live as one canonical file in the repo, synced into both native apps;
- be loadable on both platforms through a matching `StationDirectory`, supporting lookup by CRS and name search.

## Ships when

On both platforms, `StationDirectory` loads the bundled dataset and the tests pass: record count is in range, CRS codes are unique and well-formed, all coordinates are inside the UK bounding box, KGX / MAN / EDB / CDF resolve to the right stations, name search works, and each platform's bundled copy is identical to `data/stations.json`. Android runs locally; iOS runs in CI until Xcode 26 is installed locally.

## Decisions

| Decision | Choice | Why |
|---|---|---|
| Source | NaPTAN (Department for Transport), Open Government Licence v3.0 | Official, lightest licence, allows commercial use with attribution. |
| Rejected | `davwheat/uk-railway-stations` (ODbL, "any modification must be published") | Share-alike terms are a risk for a possibly commercial app; covers only Darwin-queryable stations. |
| Rejected | ORR station usage estimates | Licence and fields unconfirmed; it is a usage-statistics table. |
| Build method | Repeatable generator script, not a one-off hand-copy | Re-running it refreshes the data with the same output shape. |
| Shared code | None. Script is a dev tool; apps stay native-only | Spec §6 decision stands. |

## Design

### 1. Generator and committed files

- `tools/stations/` holds a Python 3 (standard library only) script that reads the downloaded NaPTAN files and writes `data/stations.json`.
- It keeps only National Rail stations and only CRS, name and coordinates.
- Raw NaPTAN downloads are not committed.
- `data/STATIONS-SOURCE.md` records the source URL, retrieval date, licence (OGL v3.0) and the required attribution wording.

### 2. Data format

JSON array of `{ "crs", "name", "lat", "lon" }`, sorted by `crs`, one record per station. `crs` maps to the existing `Station.id`; the `Station` model is unchanged.

### 3. Sync into the apps

- `tools/stations/sync` copies `data/stations.json` into `ios/Sources/TrainAlarm/Resources/stations.json` and the Android `assets/` folder (exact path decided in the plan, checked against the Gradle and XcodeGen setup).
- A test on each platform checks its copy is byte-identical to `data/stations.json`.

### 4. Loaders

- `StationDirectory` in `Providers/` (iOS) and `provider/` (Android): the rail-data layer.
- API: `station(crs:)` and `search(name:)`. Search is case-insensitive: prefix matches first, then contains.
- A missing or malformed file throws, matching the existing rule that parsers throw rather than return partial data. iOS uses a typed error; Android follows the existing platform-exception pattern.
- Not wired into `RttProvider.searchStations` here. That is a follow-up for Stage 2 or the UI.

### 5. Verification

- Both platforms: record count in range (about 2,400–2,700; exact bounds set from the real data), CRS unique and three uppercase letters, coordinates inside the UK bounding box, spot checks (KGX, MAN, EDB, CDF), search queries, bundled copy identical to the canonical file.
- Generator test against a small hand-made sample written in the real NaPTAN column layout.
- Android: `./gradlew test`. iOS: CI.

## First task: verify the real data (gate)

The NaPTAN file layout is **not yet verified**. Confirmed so far: the licence is OGL v3.0 on the official site; the download page offers national and per-area files. Not confirmed: file names, the columns that join stops to CRS codes, and how rail stations are identified (the `9100<TIPLOC>` ATCO form and a RailReferences file were reported by search results only). The first plan task is to download the real files and check these against the generator design. If NaPTAN does not provide CRS, name and coordinates for GB rail stations, stop and bring the findings back before building anything.

## Out of scope

- `RttProvider.searchStations` wiring, Stage 2 tracking/ETA, any UI.
- Non-GB stations (Northern Ireland is not in National Rail data).
- Automatic scheduled refresh of the data (re-run the script by hand for now).

## Risks

- NaPTAN may include non-National-Rail rail stops (trams, metro, heritage). The generator's filter must be checked against the real data; the CRS-format and count tests catch gross errors.
- Attribution wording must be shown to users eventually (UI stage); the source note records what is required.
