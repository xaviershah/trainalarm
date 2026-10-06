# UK Station List Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a static dataset of all National Rail stations in Great Britain (CRS, name, lat/lon) from NaPTAN, synced into both native apps and loadable through a matching `StationDirectory`.

**Architecture:** A stdlib-only Python generator turns the NaPTAN rail-area XML into one canonical `data/stations.json`. A sync script copies it into each app's bundle. Each platform gets a `StationDirectory` (lookup by CRS, name search) in its rail-data layer, tested against the bundled file.

**Tech Stack:** Python 3 stdlib (`xml.etree`, `json`, `unittest`) for tooling; Swift/XCTest (`JSONSerialization`); Kotlin/JUnit 4 (`org.json`).

**Spec:** `docs/superpowers/specs/2026-10-06-uk-station-list-design.md`

## Global Constraints

- Source is NaPTAN under **Open Government Licence v3.0**; attribution text goes in `data/STATIONS-SOURCE.md`. No other data source. Nothing invented: every record comes from the real file.
- Native-only: no shared app-code layer. The Python tools are build-time only and are not bundled in either app.
- `Station` models (`id`, `name`, `latitude`, `longitude`) are **unchanged**; `crs` maps to `Station.id`.
- No new third-party dependencies on any platform.
- `StationDirectory` lives in `Providers/` (iOS) and `provider/` (Android); same name and API on both.
- Not wired into `RttProvider.searchStations` (out of scope).
- Commits: `<Type>: <Short description>` + body (why) + trailer `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Run `git diff --cached` before each commit. Never force-push.
- iOS tests run in CI only (no Xcode locally). Do not claim iOS passes until the CI run is green.
- When `superpowers:subagent-driven-development` finishes and invokes `superpowers:finishing-a-development-branch`, let it run its verification steps but **do not present its options menu** (merge locally / push and create PR / keep as-is). Instead proceed to Step 12 of `gl-starting-a-feature`. Step 12 runs in full, then Step 13 handles the finishing sequence (delete spec+plan, squash implementation commits, push, exit worktree, open the PR).
- Subagents use **Sonnet**. Never Opus unless the user explicitly says so.

## Verified NaPTAN facts (checked against the real files on 2026-10-06)

- Rail-only XML: `GET https://naptan.api.dft.gov.uk/v1/access-nodes?dataFormat=xml&atcoAreaCodes=910` (about 27 MB, no auth; rate limit 200/hour). The national CSV has **no CRS column** (rail stops there are keyed by TIPLOC), so XML is the source.
- XML namespace `http://www.naptan.org.uk/`; root `NaPTAN/StopPoints/StopPoint`. Fields per `StopPoint`:
  - `@Status` (`active`/`inactive`), `AtcoCode` (e.g. `9100KNGX`), `Descriptor/CommonName` (e.g. `London Kings Cross Rail Station`)
  - CRS: `StopClassification/OffStreet/Rail/AnnotatedRailRef/CrsRef`
  - Coordinates: `Place/Location/Translation/Longitude` and `.../Latitude` (decimal degrees; **absent** on 11 records)
- 2,673 StopPoints carry a CRS (all 3 characters); 2,640 unique. 2 inactive. 11 have no coordinates (Elizabeth line codes such as PDX, FDX; the CSV has none either). 29 CRS codes occur on more than one StopPoint: 19 are the same station (same name, metres apart); 10 differ in name (BIA, SGB, NRC, LVC, NXG, NWX, RMD, SHT, WIJ, ZZT) and need explicit overrides.
- Names are NaPTAN's: most end in ` Rail Station` (2,633), 9 in ` Station`, 31 have no suffix. They include `&` and parentheses (`Harrow & Wealdstone`, `Richmond (London)`); none are non-ASCII.
- Expected result: **2,626** stations after the rules in Task 2. KGX = London Kings Cross, MAN = Manchester Piccadilly, EDB = Edinburgh, CDF = Cardiff Central.

## Review Focus

Failure modes the spec implies that no task's happy-path tests would catch:

1. Same CRS on two active StopPoints at different places with no override: the generator must fail loudly, not silently pick one. (Task 2)
2. Search with an empty or whitespace-only query returns an empty list, not every station. (Tasks 5, 6)
3. CRS lookup is forgiving (`kgx`, ` KGX `) and unknown codes return nil/null, including the 11 codes NaPTAN has no coordinates for (e.g. `PDX`). (Tasks 5, 6)
4. Names with `&` and parentheses (`Harrow & Wealdstone`, `Richmond (London)`) load and are searchable; search never treats the query as a pattern. (Tasks 5, 6)
5. A malformed file (missing field, duplicate CRS, not an array) throws; no partial directory is returned. (Tasks 5, 6)

## File Map

| Path | Responsibility |
|---|---|
| `tools/stations/generate_stations.py` | Parse NaPTAN XML, apply selection rules, validate, write JSON |
| `tools/stations/test_generate_stations.py` | Unit tests for the generator (inline XML fixtures) |
| `tools/stations/overrides.json` | Explicit per-CRS decisions for the 10 name-conflicting codes |
| `tools/stations/sync_stations.py` | Copy `data/stations.json` into both app bundles; `--check` mode |
| `tools/stations/test_sync_stations.py` | Tests for sync/check |
| `data/stations.json` | Canonical dataset (committed) |
| `data/STATIONS-SOURCE.md` | Source URL, retrieval date, licence, attribution, rules applied |
| `ios/Sources/TrainAlarm/Resources/stations.json` | iOS bundled copy |
| `ios/Sources/TrainAlarm/Providers/StationDirectory.swift` | iOS loader/lookup/search |
| `ios/Tests/TrainAlarmTests/Provider/StationDirectoryTests.swift` | iOS tests |
| `android/app/src/main/assets/stations.json` | Android bundled copy |
| `android/app/src/main/java/com/trainalarm/app/provider/StationDirectory.kt` | Android loader/lookup/search |
| `android/app/src/test/java/com/trainalarm/app/provider/StationDirectoryTest.kt` | Android tests |
| Modify: `.gitignore`, `.github/workflows/{ios,android}.yml`, `README.md`, `.ai/architecture.md`, `.ai/testing-guide.md` | Cache ignore, CI path triggers, docs |

Run all Python tests from the worktree root with:
`python3 -m unittest discover -s tools/stations -p "test_*.py" -v`

---

### Task 1: Parse NaPTAN rail XML

**Files:**
- Create: `tools/stations/generate_stations.py`
- Test: `tools/stations/test_generate_stations.py`

**Interfaces:**
- Produces: `parse_naptan(source) -> list[dict]`. `source` is a path or file object. Each dict has keys `atco: str`, `crs: str`, `name: str`, `lat: float | None`, `lon: float | None`, `status: str`. One dict per `StopPoint` that has a `CrsRef`; StopPoints without one are skipped.

- [ ] **Step 1: Write the failing tests**

Create `tools/stations/test_generate_stations.py`:

```python
import io
import unittest

import generate_stations as gs


def stop(atco, crs, name, lat="51.5", lon="-0.1", status="active"):
    place = (
        f"<Place><Location><Translation><Longitude>{lon}</Longitude>"
        f"<Latitude>{lat}</Latitude></Translation></Location></Place>"
        if lat is not None
        else "<Place/>"
    )
    rail = (
        "<StopClassification><OffStreet><Rail><AnnotatedRailRef>"
        f"<CrsRef>{crs}</CrsRef></AnnotatedRailRef></Rail></OffStreet></StopClassification>"
        if crs is not None
        else "<StopClassification><StopType>RLY</StopType></StopClassification>"
    )
    return (
        f'<StopPoint Status="{status}"><AtcoCode>{atco}</AtcoCode>'
        f"<Descriptor><CommonName>{name}</CommonName></Descriptor>{place}{rail}</StopPoint>"
    )


def doc(*stops):
    xml = (
        '<NaPTAN xmlns="http://www.naptan.org.uk/"><StopPoints>'
        + "".join(stops)
        + "</StopPoints></NaPTAN>"
    )
    return io.BytesIO(xml.encode("utf-8"))


class ParseNaptanTests(unittest.TestCase):
    def test_reads_crs_name_coordinates_and_status(self):
        records = gs.parse_naptan(
            doc(stop("9100KNGX", "KGX", "London Kings Cross Rail Station", "51.53088", "-0.12292"))
        )
        self.assertEqual(
            records,
            [{
                "atco": "9100KNGX", "crs": "KGX", "name": "London Kings Cross Rail Station",
                "lat": 51.53088, "lon": -0.12292, "status": "active",
            }],
        )

    def test_skips_stop_points_without_a_crs(self):
        records = gs.parse_naptan(doc(stop("9100X", None, "No CRS"), stop("9100KNGX", "KGX", "Kings Cross")))
        self.assertEqual([r["crs"] for r in records], ["KGX"])

    def test_missing_coordinates_become_none(self):
        records = gs.parse_naptan(doc(stop("9100PADTLL", "PDX", "Paddington", lat=None)))
        self.assertIsNone(records[0]["lat"])
        self.assertIsNone(records[0]["lon"])

    def test_reads_inactive_status(self):
        records = gs.parse_naptan(doc(stop("9100ANGLRD", "AGR", "Angel Road", status="inactive")))
        self.assertEqual(records[0]["status"], "inactive")

    def test_names_with_ampersand_and_parentheses_survive(self):
        records = gs.parse_naptan(doc(stop("9100HROW", "HRW", "Harrow &amp; Wealdstone Rail Station")))
        self.assertEqual(records[0]["name"], "Harrow & Wealdstone Rail Station")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run to verify it fails**

Run: `python3 -m unittest discover -s tools/stations -p "test_*.py" -v`
Expected: error `ModuleNotFoundError: No module named 'generate_stations'`.

- [ ] **Step 3: Write minimal implementation**

Create `tools/stations/generate_stations.py`:

```python
"""Build data/stations.json from NaPTAN rail-area XML (Open Government Licence v3.0).

Source: https://naptan.api.dft.gov.uk/v1/access-nodes?dataFormat=xml&atcoAreaCodes=910
See data/STATIONS-SOURCE.md for the rules applied and the attribution.
"""
import xml.etree.ElementTree as ET

NS = "{http://www.naptan.org.uk/}"


def _p(path):
    """Namespace-qualify a slash-separated element path."""
    return "/".join(NS + part for part in path.split("/"))


def _float(text):
    return float(text) if text else None


def parse_naptan(source):
    """Return one record per StopPoint that carries a CRS code."""
    records = []
    for sp in ET.parse(source).getroot().iter(NS + "StopPoint"):
        crs = sp.findtext(_p("StopClassification/OffStreet/Rail/AnnotatedRailRef/CrsRef"))
        if not crs:
            continue
        records.append({
            "atco": sp.findtext(_p("AtcoCode")),
            "crs": crs.strip(),
            "name": sp.findtext(_p("Descriptor/CommonName")),
            "lat": _float(sp.findtext(_p("Place/Location/Translation/Latitude"))),
            "lon": _float(sp.findtext(_p("Place/Location/Translation/Longitude"))),
            "status": sp.get("Status"),
        })
    return records
```

- [ ] **Step 4: Run to verify it passes**

Run: `python3 -m unittest discover -s tools/stations -p "test_*.py" -v`
Expected: 5 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add tools/stations/generate_stations.py tools/stations/test_generate_stations.py
git diff --cached
git commit -m "Feature: Parse NaPTAN rail XML for station records" -m "First step of the station list generator: reads CRS, name, coordinates and status from the real NaPTAN structure." -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Selection rules and overrides

**Files:**
- Modify: `tools/stations/generate_stations.py`
- Modify: `tools/stations/test_generate_stations.py`
- Create: `tools/stations/overrides.json`

**Interfaces:**
- Consumes: `parse_naptan` record dicts from Task 1.
- Produces:
  - `normalise_name(raw: str) -> str` strips a trailing ` Rail Station` / ` Station` and surrounding whitespace.
  - `class ConflictError(Exception)`.
  - `load_overrides(path) -> dict[str, dict]` (`{crs: {"atco": str | None, "reason": str}}`).
  - `select_records(records, overrides) -> (stations, report)`. `stations` is a list of `{"crs", "name", "lat", "lon"}` sorted by `crs` with `lat`/`lon` rounded to 6 decimals. `report` is a dict of lists: `inactive`, `no_coordinates`, `collapsed`, `dropped_by_override` (each holds CRS codes).

Rules, in order, per CRS: (1) drop records whose status is not `active`; (2) drop records with no coordinates; (3) if the CRS has an override, use it (`atco: null` drops the CRS; an `atco` that is not among the remaining records raises `ConflictError`); (4) a single record is kept; (5) several records are collapsed to the lowest ATCO code only if their normalised names are identical and all lie within 500 m of it; (6) anything else raises `ConflictError` naming the CRS.

- [ ] **Step 1: Write the failing tests**

Add to `tools/stations/test_generate_stations.py` (above the `if __name__` line):

```python
def rec(atco, crs, name, lat=51.5, lon=-0.1, status="active"):
    return {"atco": atco, "crs": crs, "name": name, "lat": lat, "lon": lon, "status": status}


class NormaliseNameTests(unittest.TestCase):
    def test_strips_rail_station_suffix(self):
        self.assertEqual(gs.normalise_name("London Kings Cross Rail Station"), "London Kings Cross")

    def test_strips_plain_station_suffix(self):
        self.assertEqual(gs.normalise_name("Newbury Park Station"), "Newbury Park")

    def test_leaves_names_without_suffix_alone(self):
        self.assertEqual(gs.normalise_name("Abbey Wood"), "Abbey Wood")

    def test_keeps_parentheses_and_ampersands(self):
        self.assertEqual(gs.normalise_name("Richmond (London) Rail Station"), "Richmond (London)")
        self.assertEqual(gs.normalise_name("Harrow & Wealdstone Rail Station"), "Harrow & Wealdstone")


class SelectRecordsTests(unittest.TestCase):
    def select(self, records, overrides=None):
        return gs.select_records(records, overrides or {})

    def test_drops_inactive_and_reports_it(self):
        stations, report = self.select([rec("A", "AGR", "Angel Road Rail Station", status="inactive")])
        self.assertEqual(stations, [])
        self.assertEqual(report["inactive"], ["AGR"])

    def test_drops_records_without_coordinates_and_reports_it(self):
        stations, report = self.select([rec("A", "PDX", "Paddington", lat=None, lon=None)])
        self.assertEqual(stations, [])
        self.assertEqual(report["no_coordinates"], ["PDX"])

    def test_collapses_same_station_to_lowest_atco(self):
        stations, report = self.select([
            rec("9100CLPHMJ2", "CLJ", "Clapham Junction Rail Station", 51.46418, -0.17022),
            rec("9100CLPHMJ1", "CLJ", "Clapham Junction Rail Station", 51.46419, -0.17023),
        ])
        self.assertEqual(stations, [{"crs": "CLJ", "name": "Clapham Junction", "lat": 51.46419, "lon": -0.17023}])
        self.assertEqual(report["collapsed"], ["CLJ"])

    def test_same_name_far_apart_is_a_conflict(self):
        with self.assertRaises(gs.ConflictError) as ctx:
            self.select([
                rec("A", "XYZ", "Same Name Rail Station", 51.5, -0.1),
                rec("B", "XYZ", "Same Name Rail Station", 52.5, -1.1),
            ])
        self.assertIn("XYZ", str(ctx.exception))

    def test_different_names_without_override_is_a_conflict(self):
        with self.assertRaises(gs.ConflictError) as ctx:
            self.select([
                rec("9100ILFENBP", "NRC", "Newbury Park Station", 51.575, 0.0897),
                rec("9100NEWBRYR", "NRC", "Newbury Racecourse Rail Station", 51.398, -1.308),
            ])
        self.assertIn("NRC", str(ctx.exception))

    def test_override_picks_the_named_record(self):
        records = [
            rec("9100ILFENBP", "NRC", "Newbury Park Station", 51.575, 0.0897),
            rec("9100NEWBRYR", "NRC", "Newbury Racecourse Rail Station", 51.398, -1.308),
        ]
        stations, _ = self.select(records, {"NRC": {"atco": "9100NEWBRYR", "reason": "test"}})
        self.assertEqual([s["name"] for s in stations], ["Newbury Racecourse"])

    def test_override_with_null_atco_drops_the_crs(self):
        stations, report = self.select(
            [rec("A", "ZZT", "Alston Rail Station")], {"ZZT": {"atco": None, "reason": "test"}}
        )
        self.assertEqual(stations, [])
        self.assertEqual(report["dropped_by_override"], ["ZZT"])

    def test_override_naming_an_atco_not_in_the_data_raises(self):
        with self.assertRaises(gs.ConflictError):
            self.select([rec("A", "XYZ", "Place Rail Station")], {"XYZ": {"atco": "NOPE", "reason": "test"}})

    def test_output_is_sorted_by_crs_and_rounded_to_six_places(self):
        stations, _ = self.select([
            rec("B", "MAN", "Manchester Piccadilly Rail Station", 53.47736139295, -2.23090989998),
            rec("A", "EDB", "Edinburgh Rail Station", 55.95239282275, -3.18822768403),
        ])
        self.assertEqual([s["crs"] for s in stations], ["EDB", "MAN"])
        self.assertEqual((stations[1]["lat"], stations[1]["lon"]), (53.477361, -2.23091))
```

- [ ] **Step 2: Run to verify it fails**

Run: `python3 -m unittest discover -s tools/stations -p "test_*.py" -v`
Expected: the new tests error with `AttributeError: module 'generate_stations' has no attribute 'normalise_name'` (and similar); the 5 Task 1 tests still pass.

- [ ] **Step 3: Write minimal implementation**

In `tools/stations/generate_stations.py`, change the import line to `import json`, `import math`, `import re`, then `import xml.etree.ElementTree as ET`, and append:

```python
CONFLICT_RADIUS_M = 500
_SUFFIX = re.compile(r"\s+(Rail\s+)?Station$")


class ConflictError(Exception):
    """A CRS code could not be resolved to one station by the rules."""


def normalise_name(raw):
    return _SUFFIX.sub("", raw.strip())


def load_overrides(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _distance_m(a, b):
    # Equirectangular approximation: accurate enough well under 1 km.
    dlat = math.radians(b["lat"] - a["lat"])
    dlon = math.radians(b["lon"] - a["lon"]) * math.cos(math.radians(a["lat"]))
    return 6371000 * math.hypot(dlat, dlon)


def _choose(crs, group, overrides, report):
    """Return the record to keep for one CRS, None to drop it; raise ConflictError if unresolved."""
    if crs in overrides:
        atco = overrides[crs]["atco"]
        if atco is None:
            report["dropped_by_override"].append(crs)
            return None
        for r in group:
            if r["atco"] == atco:
                return r
        raise ConflictError(f"{crs}: override names {atco}, which is not an active record with coordinates")
    if len(group) == 1:
        return group[0]
    first = min(group, key=lambda r: r["atco"])
    same_name = len({normalise_name(r["name"]) for r in group}) == 1
    close = all(_distance_m(first, r) <= CONFLICT_RADIUS_M for r in group)
    if same_name and close:
        report["collapsed"].append(crs)
        return first
    detail = ", ".join(f"{r['atco']} ({r['name']})" for r in group)
    raise ConflictError(f"{crs}: unresolved conflict between {detail}; add an entry to overrides.json")


def select_records(records, overrides):
    """Apply the selection rules; return (stations, report)."""
    report = {"inactive": [], "no_coordinates": [], "collapsed": [], "dropped_by_override": []}
    groups = {}
    for r in records:
        if r["status"] != "active":
            report["inactive"].append(r["crs"])
        elif r["lat"] is None or r["lon"] is None:
            report["no_coordinates"].append(r["crs"])
        else:
            groups.setdefault(r["crs"], []).append(r)
    stations = []
    for crs, group in groups.items():
        chosen = _choose(crs, group, overrides, report)
        if chosen is not None:
            stations.append({
                "crs": crs,
                "name": normalise_name(chosen["name"]),
                "lat": round(chosen["lat"], 6),
                "lon": round(chosen["lon"], 6),
            })
    stations.sort(key=lambda s: s["crs"])
    return stations, report
```

- [ ] **Step 4: Run to verify it passes**

Run: `python3 -m unittest discover -s tools/stations -p "test_*.py" -v`
Expected: 18 tests, all PASS.

- [ ] **Step 5: Create the overrides file**

Create `tools/stations/overrides.json`. These are the 10 CRS codes whose NaPTAN records have different names (found by inspecting the real data). **The user reviews this file on GitHub with the plan.** `atco: null` drops the code.

```json
{
  "BIA": {"atco": "9100BSAUKLD", "reason": "Two names for one CRS, about 270 m apart: 'Bishop Auckland' vs 'Bishop Auckland West'. Chose the unqualified name."},
  "SGB": {"atco": "9100GALTILL", "reason": "Same spot, 'Smethwick Galton Bridge' vs '... High Level'. Chose the unqualified name."},
  "NRC": {"atco": "9100NEWBRYR", "reason": "Genuinely different places: Newbury Park (Essex, 9100ILFENBP) vs Newbury Racecourse (Berkshire). NRC is the Newbury Racecourse code; the Newbury Park record is a NaPTAN mislabel."},
  "LVC": {"atco": "9100LVRPLCH", "reason": "'Liverpool Central' vs 'Liverpool Central Loop Line'. Chose the main station name."},
  "NXG": {"atco": "9100NEWXGTE", "reason": "'New Cross Gate' vs 'New Cross Gate ELL' (East London Line variant). Chose the unqualified name."},
  "NWX": {"atco": "9100NWCROSS", "reason": "'New Cross' vs 'New Cross ELL' (East London Line variant). Chose the unqualified name."},
  "RMD": {"atco": "9100RICHMND", "reason": "'Richmond (London)' vs 'Richmond NLL' (North London Line variant). Chose the unqualified name."},
  "SHT": {"atco": "9100SHOTTON", "reason": "'Shotton' vs 'Shotton High Level'. Chose the unqualified name."},
  "WIJ": {"atco": "9100WLSDJHL", "reason": "'Willesden Junction' vs 'Willesden Junction Low Level'. Chose the unqualified name."},
  "ZZT": {"atco": null, "reason": "Groups three unrelated places (Alston, Kirkhaugh, Lintley); not a real station code. Dropped."}
}
```

- [ ] **Step 6: Commit**

```bash
git add tools/stations/generate_stations.py tools/stations/test_generate_stations.py tools/stations/overrides.json
git diff --cached
git commit -m "Feature: Apply station selection rules and overrides" -m "Drops inactive and coordinate-less records, collapses same-station duplicates, and fails on any unresolved CRS conflict. The 10 name-conflicting codes are decided explicitly in overrides.json." -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Validate, write, and generate the real dataset

**Files:**
- Modify: `tools/stations/generate_stations.py`, `tools/stations/test_generate_stations.py`, `.gitignore`
- Create: `data/stations.json`, `data/STATIONS-SOURCE.md`

**Interfaces:**
- Consumes: `parse_naptan`, `select_records`, `load_overrides`, `ConflictError` from Tasks 1-2.
- Produces:
  - Constants `UK_LAT = (49.8, 60.9)`, `UK_LON = (-8.7, 1.8)`, `COUNT_RANGE = (2600, 2650)`, `EXPECTED_NAMES = {"KGX": "London Kings Cross", "MAN": "Manchester Piccadilly", "EDB": "Edinburgh", "CDF": "Cardiff Central"}`.
  - `class ValidationError(Exception)`.
  - `validate(stations, count_range=COUNT_RANGE, expected=EXPECTED_NAMES)` raises `ValidationError` listing every problem found.
  - `build(source, overrides, count_range=COUNT_RANGE, expected=EXPECTED_NAMES) -> (stations, report)`: parse, select, validate.
  - `write_json(stations, path)`: one record per line, keys in order `crs, name, lat, lon`, UTF-8, no ASCII escaping, trailing newline.
  - `main(argv=None) -> int`: CLI `--xml PATH` (required), `--overrides PATH`, `--out PATH` (default `data/stations.json`).

- [ ] **Step 1: Write the failing tests**

Add to `tools/stations/test_generate_stations.py` (above the `if __name__` line), and add `import json`, `import os`, `import tempfile` to the imports at the top:

```python
GOOD = [
    {"crs": "KGX", "name": "London Kings Cross", "lat": 51.530883, "lon": -0.122926},
    {"crs": "MAN", "name": "Manchester Piccadilly", "lat": 53.477361, "lon": -2.23091},
]


class ValidateTests(unittest.TestCase):
    def check(self, stations, count_range=(1, 10), expected=None):
        gs.validate(stations, count_range=count_range, expected=expected or {})

    def test_accepts_valid_stations(self):
        self.check(GOOD)

    def test_rejects_count_out_of_range(self):
        with self.assertRaisesRegex(gs.ValidationError, "count 2"):
            self.check(GOOD, count_range=(100, 200))

    def test_rejects_malformed_crs(self):
        for bad in ("kgx", "KGXX", "K1X"):
            with self.subTest(bad=bad), self.assertRaisesRegex(gs.ValidationError, "bad CRS"):
                self.check([{**GOOD[0], "crs": bad}])

    def test_rejects_duplicate_crs(self):
        with self.assertRaisesRegex(gs.ValidationError, "duplicate CRS KGX"):
            self.check([GOOD[0], GOOD[0]])

    def test_rejects_coordinates_outside_the_uk(self):
        with self.assertRaisesRegex(gs.ValidationError, "outside the UK"):
            self.check([{**GOOD[0], "lat": 0.0, "lon": 0.0}])

    def test_rejects_empty_name(self):
        with self.assertRaisesRegex(gs.ValidationError, "empty name"):
            self.check([{**GOOD[0], "name": ""}])

    def test_rejects_wrong_or_missing_spot_check_name(self):
        with self.assertRaisesRegex(gs.ValidationError, "EDB"):
            self.check(GOOD, expected={"EDB": "Edinburgh"})


class WriteJsonTests(unittest.TestCase):
    def test_one_record_per_line_utf8_and_round_trips(self):
        stations = GOOD + [{"crs": "HRW", "name": "Harrow & Wealdstone", "lat": 51.592169, "lon": -0.334571}]
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "stations.json")
            gs.write_json(stations, path)
            with open(path, encoding="utf-8") as f:
                text = f.read()
        lines = text.split("\n")
        self.assertEqual(lines[0], "[")
        self.assertEqual(lines[1], '{"crs": "KGX", "name": "London Kings Cross", "lat": 51.530883, "lon": -0.122926},')
        self.assertEqual(lines[-3], '{"crs": "HRW", "name": "Harrow & Wealdstone", "lat": 51.592169, "lon": -0.334571}')
        self.assertEqual(lines[-2:], ["]", ""])
        self.assertEqual(json.loads(text), stations)


class BuildTests(unittest.TestCase):
    def test_builds_validated_stations_from_xml(self):
        source = doc(stop("9100KNGX", "KGX", "London Kings Cross Rail Station", "51.53088", "-0.12292"))
        stations, report = gs.build(source, {}, count_range=(1, 5), expected={"KGX": "London Kings Cross"})
        self.assertEqual(stations, [{"crs": "KGX", "name": "London Kings Cross", "lat": 51.53088, "lon": -0.12292}])
        self.assertEqual(report["inactive"], [])

    def test_build_fails_validation_rather_than_returning_bad_data(self):
        source = doc(stop("9100KNGX", "KGX", "London Kings Cross Rail Station"))
        with self.assertRaises(gs.ValidationError):
            gs.build(source, {}, count_range=(100, 200), expected={})
```

- [ ] **Step 2: Run to verify it fails**

Run: `python3 -m unittest discover -s tools/stations -p "test_*.py" -v`
Expected: the new tests error with `AttributeError: module 'generate_stations' has no attribute 'ValidationError'` (and `validate`, `write_json`, `build`); the 18 earlier tests still pass.

- [ ] **Step 3: Write minimal implementation**

In `tools/stations/generate_stations.py`, change the imports to `import argparse`, `import json`, `import math`, `import re`, `import sys`, `import xml.etree.ElementTree as ET` and `from pathlib import Path`, then append:

```python
UK_LAT = (49.8, 60.9)
UK_LON = (-8.7, 1.8)
COUNT_RANGE = (2600, 2650)
EXPECTED_NAMES = {
    "KGX": "London Kings Cross",
    "MAN": "Manchester Piccadilly",
    "EDB": "Edinburgh",
    "CDF": "Cardiff Central",
}
REPO = Path(__file__).resolve().parents[2]


class ValidationError(Exception):
    """The generated dataset failed a sanity check."""


def validate(stations, count_range=COUNT_RANGE, expected=EXPECTED_NAMES):
    problems = []
    low, high = count_range
    if not low <= len(stations) <= high:
        problems.append(f"count {len(stations)} outside {low}-{high}")
    seen = set()
    for s in stations:
        crs = s["crs"]
        if not re.fullmatch(r"[A-Z]{3}", crs):
            problems.append(f"bad CRS {crs!r}")
        if crs in seen:
            problems.append(f"duplicate CRS {crs}")
        seen.add(crs)
        if not s["name"]:
            problems.append(f"{crs}: empty name")
        if not (UK_LAT[0] <= s["lat"] <= UK_LAT[1] and UK_LON[0] <= s["lon"] <= UK_LON[1]):
            problems.append(f"{crs}: coordinates outside the UK")
    by_crs = {s["crs"]: s for s in stations}
    for crs, name in expected.items():
        actual = by_crs.get(crs, {}).get("name")
        if actual != name:
            problems.append(f"{crs}: expected name {name!r}, got {actual!r}")
    if problems:
        raise ValidationError("; ".join(problems))


def build(source, overrides, count_range=COUNT_RANGE, expected=EXPECTED_NAMES):
    stations, report = select_records(parse_naptan(source), overrides)
    validate(stations, count_range, expected)
    return stations, report


def write_json(stations, path):
    lines = [json.dumps(s, ensure_ascii=False) for s in stations]
    with open(path, "w", encoding="utf-8") as f:
        f.write("[\n" + ",\n".join(lines) + "\n]\n")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--xml", required=True, help="NaPTAN rail-area XML file")
    ap.add_argument("--overrides", default=str(Path(__file__).with_name("overrides.json")))
    ap.add_argument("--out", default=str(REPO / "data" / "stations.json"))
    args = ap.parse_args(argv)
    stations, report = build(args.xml, load_overrides(args.overrides))
    write_json(stations, args.out)
    print(f"{len(stations)} stations written to {args.out}")
    for key, codes in report.items():
        print(f"  {key}: {len(codes)}" + (f" ({', '.join(codes)})" if codes else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 4: Run to verify it passes**

Run: `python3 -m unittest discover -s tools/stations -p "test_*.py" -v`
Expected: 28 tests, all PASS.

- [ ] **Step 5: Download the real data (gitignored)**

Add to `.gitignore` (after the `# Environment / secrets` block):

```
# Station generator downloads
tools/stations/.cache/
```

Run:
```bash
mkdir -p tools/stations/.cache
curl -sf -o tools/stations/.cache/naptan-rail.xml "https://naptan.api.dft.gov.uk/v1/access-nodes?dataFormat=xml&atcoAreaCodes=910"
ls -l tools/stations/.cache/naptan-rail.xml
```
Expected: a file of roughly 27 MB. If `curl` fails (rate limit 200/hour, or a network error), wait and retry; do not substitute another source.

- [ ] **Step 6: Generate the dataset**

Run: `python3 tools/stations/generate_stations.py --xml tools/stations/.cache/naptan-rail.xml`
Expected output (counts as of 2026-10-06):
```
2626 stations written to <repo>/data/stations.json
  inactive: 2 (AGR, STQ)
  no_coordinates: 11 (ABX, BDS, CWX, FDX, LSX, PDX, TCR, WHX, WWC, CUS, BGV)
  collapsed: 19 (...)
  dropped_by_override: 1 (ZZT)
```
Code order within each line may differ. If the generator raises `ConflictError` or `ValidationError`, **stop and report the message to the user** (NaPTAN may have changed); do not edit the rules or overrides to make it pass.

- [ ] **Step 7: Check the output**

Run: `wc -l data/stations.json` — expected `2628` lines (2,626 records plus the brackets).
Run: `grep -E '"crs": "(KGX|MAN|EDB|CDF|HRW)"' data/stations.json`
Expected five lines, including `{"crs": "KGX", "name": "London Kings Cross", "lat": 51.530883, "lon": -0.122926}` and a `HRW` line whose name is `Harrow & Wealdstone`.

- [ ] **Step 8: Write the source note**

Create `data/STATIONS-SOURCE.md`:

````markdown
# Station dataset: source and licence

`stations.json` lists every National Rail station in Great Britain that NaPTAN
gives a CRS code and coordinates for: `crs`, `name`, `lat`, `lon`.

## Source

- Dataset: National Public Transport Access Nodes (NaPTAN), Department for Transport.
- Retrieved: 2026-10-06 (use the real download date if different) from
  `https://naptan.api.dft.gov.uk/v1/access-nodes?dataFormat=xml&atcoAreaCodes=910`
  (ATCO area 910 = rail).
- Licence: Open Government Licence v3.0,
  https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/

## Attribution (must be shown to users in the app, at the UI stage)

> Contains public sector information licensed under the Open Government Licence v3.0.
> Station data: Department for Transport, National Public Transport Access Nodes (NaPTAN).

## Regenerating

```bash
mkdir -p tools/stations/.cache
curl -sf -o tools/stations/.cache/naptan-rail.xml "https://naptan.api.dft.gov.uk/v1/access-nodes?dataFormat=xml&atcoAreaCodes=910"
python3 tools/stations/generate_stations.py --xml tools/stations/.cache/naptan-rail.xml
python3 tools/stations/sync_stations.py
```

## Rules applied (tools/stations/generate_stations.py)

- Only StopPoints with a CRS code (`StopClassification/OffStreet/Rail/AnnotatedRailRef/CrsRef`).
- Inactive records dropped. Records with no coordinates dropped (11 codes, e.g. PDX, FDX:
  NaPTAN has no coordinates for them, so none are invented).
- Names have a trailing " Rail Station" / " Station" removed; otherwise they are NaPTAN's
  names, so "Edinburgh" not "Edinburgh Waverley".
- Same-station duplicates (same name, within 500 m) keep the lowest ATCO code.
- Ten codes with conflicting names are decided in `tools/stations/overrides.json`, each with a reason.
- Coordinates rounded to 6 decimal places.

## Known gaps

- The 11 coordinate-less codes are missing, so a lookup for them returns nothing.
- Names are not always the public name (see above).
````

- [ ] **Step 9: Re-run the tests and commit**

Run: `python3 -m unittest discover -s tools/stations -p "test_*.py"` — expected 28 tests PASS.

```bash
git add tools/stations/generate_stations.py tools/stations/test_generate_stations.py .gitignore data/stations.json data/STATIONS-SOURCE.md
git status --short
git diff --cached --stat
git commit -m "Feature: Generate canonical UK station dataset from NaPTAN" -m "Adds validation (count, CRS format, UK bounds, spot checks), the JSON writer and CLI, and the generated data/stations.json with its OGL v3.0 source note." -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```
Expected: `git status --short` shows nothing under `tools/stations/.cache/` (it is ignored).

---

### Task 4: Sync script

**Files:**
- Create: `tools/stations/sync_stations.py`, `tools/stations/test_sync_stations.py`
- Create (by running the script): `ios/Sources/TrainAlarm/Resources/stations.json`, `android/app/src/main/assets/stations.json`

**Interfaces:**
- Consumes: `data/stations.json` from Task 3.
- Produces: `sync(source, targets)` copies bytes, creating parent folders; `check(source, targets) -> list[Path]` returns targets that are missing or differ; `main(argv=None) -> int` (`--check` exits 1 and lists stale copies). Module constants `SOURCE` and `TARGETS` (the two app paths above).

- [ ] **Step 1: Write the failing tests**

Create `tools/stations/test_sync_stations.py`:

```python
import tempfile
import unittest
from pathlib import Path

import sync_stations as ss


class SyncTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.source = root / "data" / "stations.json"
        self.source.parent.mkdir()
        self.source.write_bytes('[\n{"crs": "KGX"}\n]\n'.encode("utf-8"))
        self.targets = [root / "ios" / "stations.json", root / "android" / "assets" / "stations.json"]

    def test_sync_copies_bytes_and_creates_folders(self):
        ss.sync(self.source, self.targets)
        for t in self.targets:
            self.assertEqual(t.read_bytes(), self.source.read_bytes())

    def test_check_is_empty_after_sync(self):
        ss.sync(self.source, self.targets)
        self.assertEqual(ss.check(self.source, self.targets), [])

    def test_check_reports_missing_and_stale_copies(self):
        ss.sync(self.source, self.targets)
        self.targets[0].write_text("stale")
        self.targets[1].unlink()
        self.assertEqual(ss.check(self.source, self.targets), self.targets)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run to verify it fails**

Run: `python3 -m unittest discover -s tools/stations -p "test_*.py" -v`
Expected: `ModuleNotFoundError: No module named 'sync_stations'`.

- [ ] **Step 3: Write minimal implementation**

Create `tools/stations/sync_stations.py`:

```python
"""Copy data/stations.json into each app bundle; --check fails if a copy is out of date."""
import argparse
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / "data" / "stations.json"
TARGETS = [
    REPO / "ios" / "Sources" / "TrainAlarm" / "Resources" / "stations.json",
    REPO / "android" / "app" / "src" / "main" / "assets" / "stations.json",
]


def sync(source=SOURCE, targets=TARGETS):
    for target in targets:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)


def check(source=SOURCE, targets=TARGETS):
    """Return the targets that are missing or differ from the source."""
    want = source.read_bytes()
    return [t for t in targets if not t.exists() or t.read_bytes() != want]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--check", action="store_true", help="exit 1 if any bundled copy is stale")
    args = ap.parse_args(argv)
    if args.check:
        stale = check()
        for t in stale:
            print(f"out of date: {t.relative_to(REPO)}", file=sys.stderr)
        return 1 if stale else 0
    sync()
    for t in TARGETS:
        print(f"synced {t.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 4: Run, sync, and verify**

Run: `python3 -m unittest discover -s tools/stations -p "test_*.py"` — expected 31 tests PASS.
Run: `python3 tools/stations/sync_stations.py` — expected two `synced ...` lines.
Run: `python3 tools/stations/sync_stations.py --check; echo "exit=$?"` — expected `exit=0` and no output.
Run: `cmp data/stations.json android/app/src/main/assets/stations.json && cmp data/stations.json ios/Sources/TrainAlarm/Resources/stations.json && echo identical` — expected `identical`.

- [ ] **Step 5: Commit**

```bash
git add tools/stations/sync_stations.py tools/stations/test_sync_stations.py ios/Sources/TrainAlarm/Resources/stations.json android/app/src/main/assets/stations.json
git diff --cached --stat
git commit -m "Feature: Sync station dataset into both app bundles" -m "One canonical file, copied byte-for-byte; --check lets CI or a test detect drift." -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Android `StationDirectory`

**Files:**
- Create: `android/app/src/main/java/com/trainalarm/app/provider/StationDirectory.kt`
- Test: `android/app/src/test/java/com/trainalarm/app/provider/StationDirectoryTest.kt`

**Interfaces:**
- Consumes: `Station(id, name, latitude, longitude)` from `model/Station.kt`; the bundled `assets/stations.json` from Task 4.
- Produces:
  - `StationDirectory.parse(json: String): StationDirectory` throws `org.json.JSONException` for a non-array or a missing or mistyped field, and `IllegalArgumentException` for a duplicate CRS. It never returns a partial directory.
  - `StationDirectory.load(context: Context): StationDirectory` reads `assets/stations.json` as UTF-8.
  - `val stations: List<Station>`; `station(crs: String): Station?` (trims, upper-cases); `search(name: String): List<Station>`: trimmed, case-insensitive, blank returns an empty list, prefix matches first, then other substring matches, each group sorted by name. Plain substring matching; the query is never a pattern.

- [ ] **Step 0: Make the Android build runnable in the worktree**

`android/local.properties` is gitignored, so the worktree has none. If `./gradlew` reports a missing SDK, copy it: `cp /Users/Xavier/Desktop/personal_projects/TrainAlarm/android/local.properties android/local.properties` (never commit it). If no Android SDK is available at all, stop and tell the user; Android tests then run in CI only and must not be reported as locally verified.

- [ ] **Step 1: Write the failing tests**

Create `android/app/src/test/java/com/trainalarm/app/provider/StationDirectoryTest.kt`:

```kotlin
package com.trainalarm.app.provider

import org.json.JSONException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StationDirectoryTest {
    // Gradle runs unit tests with the module directory (android/app) as the working directory.
    private val bundled = File("src/main/assets/stations.json")
    private val canonical = File("../../data/stations.json")
    private val directory by lazy { StationDirectory.parse(bundled.readText(Charsets.UTF_8)) }

    private fun small(vararg names: String) = StationDirectory.parse(
        names.mapIndexed { i, n ->
            """{"crs":"A${('A' + i)}${('A' + i)}","name":"$n","lat":51.5,"lon":-0.1}"""
        }.joinToString(",", "[", "]")
    )

    @Test fun bundledCopyIsIdenticalToCanonicalFile() =
        assertArrayEquals(canonical.readBytes(), bundled.readBytes())

    @Test fun recordCountIsInExpectedRange() =
        assertTrue(directory.stations.size in 2600..2650)

    @Test fun everyCrsIsThreeUppercaseLettersAndUnique() {
        val ids = directory.stations.map { it.id }
        assertTrue(ids.all { Regex("[A-Z]{3}").matches(it) })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun everyCoordinateIsInsideTheUkBoundingBox() =
        assertTrue(directory.stations.all { it.latitude in 49.8..60.9 && it.longitude in -8.7..1.8 })

    @Test fun knownStationsResolve() {
        assertEquals("London Kings Cross", directory.station("KGX")?.name)
        assertEquals("Manchester Piccadilly", directory.station("MAN")?.name)
        assertEquals("Edinburgh", directory.station("EDB")?.name)
        assertEquals("Cardiff Central", directory.station("CDF")?.name)
        assertEquals(51.5309, directory.station("KGX")!!.latitude, 0.001)
        assertEquals(-0.1229, directory.station("KGX")!!.longitude, 0.001)
    }

    @Test fun lookupIgnoresCaseAndWhitespace() {
        assertEquals("KGX", directory.station("kgx")?.id)
        assertEquals("KGX", directory.station(" KGX ")?.id)
    }

    @Test fun unknownAndCoordinateLessCodesReturnNull() {
        assertNull(directory.station("ZZZ"))
        assertNull(directory.station("PDX")) // NaPTAN has no coordinates for PDX
    }

    @Test fun searchPutsPrefixMatchesBeforeOtherMatches() {
        val dir = small("Waltham Cross", "Gerrards Cross", "Cross Gates")
        assertEquals(listOf("Cross Gates", "Gerrards Cross", "Waltham Cross"), dir.search("cross").map { it.name })
    }

    @Test fun searchIsCaseInsensitiveAndFindsRealStations() {
        assertTrue(directory.search("KINGS CROSS").any { it.id == "KGX" })
    }

    @Test fun blankQueryReturnsNothing() {
        assertTrue(directory.search("").isEmpty())
        assertTrue(directory.search("   ").isEmpty())
    }

    @Test fun ampersandsAndParenthesesAreSearchableAndNeverPatterns() {
        assertTrue(directory.search("harrow & wealdstone").any { it.id == "HRW" })
        assertTrue(directory.search("richmond (london)").any { it.id == "RMD" })
        directory.search("(") // must not throw
        directory.search("[a-")
    }

    @Test fun parseRejectsAMissingField() {
        assertThrows(JSONException::class.java) { StationDirectory.parse("""[{"crs":"KGX","name":"X","lat":51.5}]""") }
    }

    @Test fun parseRejectsANonArray() {
        assertThrows(JSONException::class.java) { StationDirectory.parse("""{"crs":"KGX"}""") }
    }

    @Test fun parseRejectsDuplicateCrs() {
        val dup = """[{"crs":"KGX","name":"A","lat":51.5,"lon":-0.1},{"crs":"KGX","name":"B","lat":51.5,"lon":-0.1}]"""
        assertThrows(IllegalArgumentException::class.java) { StationDirectory.parse(dup) }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew test --tests "com.trainalarm.app.provider.StationDirectoryTest"`
Expected: compilation FAILS with `Unresolved reference: StationDirectory`.

- [ ] **Step 3: Write minimal implementation**

Create `android/app/src/main/java/com/trainalarm/app/provider/StationDirectory.kt`:

```kotlin
package com.trainalarm.app.provider

import android.content.Context
import com.trainalarm.app.model.Station
import org.json.JSONArray

/**
 * Static UK station directory loaded from the bundled `assets/stations.json`
 * (NaPTAN, Open Government Licence v3.0 - see data/STATIONS-SOURCE.md).
 * [Station.id] is the CRS code.
 */
class StationDirectory private constructor(val stations: List<Station>) {
    private val byCrs: Map<String, Station> = stations.associateBy { it.id }

    fun station(crs: String): Station? = byCrs[crs.trim().uppercase()]

    /** Case-insensitive substring search; prefix matches first. Blank query returns nothing. */
    fun search(name: String): List<Station> {
        val query = name.trim().lowercase()
        if (query.isEmpty()) return emptyList()
        val (prefix, rest) = stations
            .filter { it.name.lowercase().contains(query) }
            .partition { it.name.lowercase().startsWith(query) }
        return prefix.sortedBy { it.name } + rest.sortedBy { it.name }
    }

    companion object {
        fun parse(json: String): StationDirectory {
            val array = JSONArray(json)
            val stations = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Station(
                    id = o.getString("crs"),
                    name = o.getString("name"),
                    latitude = o.getDouble("lat"),
                    longitude = o.getDouble("lon")
                )
            }
            val duplicate = stations.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()
            require(duplicate == null) { "Duplicate CRS $duplicate" }
            return StationDirectory(stations)
        }

        fun load(context: Context): StationDirectory =
            parse(context.assets.open("stations.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd android && ./gradlew test --tests "com.trainalarm.app.provider.StationDirectoryTest"` — expected 14 tests PASS.
Run: `cd android && ./gradlew test lint` — expected BUILD SUCCESSFUL (existing tests still pass).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/trainalarm/app/provider/StationDirectory.kt android/app/src/test/java/com/trainalarm/app/provider/StationDirectoryTest.kt
git diff --cached --stat
git commit -m "Feature: Add Android StationDirectory" -m "CRS lookup and name search over the bundled NaPTAN dataset, with tests against the real file." -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: iOS `StationDirectory` and CI triggers

**Files:**
- Create: `ios/Sources/TrainAlarm/Providers/StationDirectory.swift`
- Test: `ios/Tests/TrainAlarmTests/Provider/StationDirectoryTests.swift`
- Create: `.github/workflows/stations.yml`
- Modify: `.github/workflows/ios.yml`, `.github/workflows/android.yml`

**Interfaces:**
- Consumes: `Station(id:name:latitude:longitude:)` from `Models/Station.swift`; the bundled `Resources/stations.json` from Task 4 (XcodeGen includes non-source files under `Sources/TrainAlarm` as bundle resources; `project.yml` excludes only `README.md`).
- Produces:
  - `enum StationDirectoryError: Error, Equatable { case resourceMissing; case malformed(String) }`.
  - `struct StationDirectory` with `let stations: [Station]`, `static func parse(_ data: Data) throws -> StationDirectory`, `static func bundledFileURL(bundle: Bundle? = nil) -> URL?`, `static func load(from bundle: Bundle? = nil) throws -> StationDirectory` (a `nil` bundle means the app bundle), `func station(crs: String) -> Station?`, `func search(name: String) -> [Station]`. Behaviour is identical to Task 5: trim, case-insensitive, blank search returns `[]`, prefix matches first then other substring matches (each sorted by name), plain substring matching.
  - `parse` throws `.malformed("stations file was not a JSON array of objects")`, `.malformed("record <i> is missing crs, name, lat or lon")` or `.malformed("duplicate CRS <crs>")`.

There is no local Xcode, so **the red phase cannot run locally**; CI is the only verification. Do not report iOS as passing until the CI run is green.

- [ ] **Step 1: Write the tests**

Create `ios/Tests/TrainAlarmTests/Provider/StationDirectoryTests.swift`:

```swift
import XCTest
@testable import TrainAlarm

final class StationDirectoryTests: XCTestCase {

    private func bundled() throws -> StationDirectory { try StationDirectory.load() }

    private func small(_ names: [String]) throws -> StationDirectory {
        let records = names.enumerated().map { i, name -> String in
            let c = Character(UnicodeScalar(UInt8(65 + i)))
            return "{\"crs\":\"A\(c)\(c)\",\"name\":\"\(name)\",\"lat\":51.5,\"lon\":-0.1}"
        }
        return try StationDirectory.parse(Data("[\(records.joined(separator: ","))]".utf8))
    }

    private func assertMalformed(_ json: String, _ message: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try StationDirectory.parse(Data(json.utf8)), file: file, line: line) {
            XCTAssertEqual($0 as? StationDirectoryError, .malformed(message), file: file, line: line)
        }
    }

    func testBundledCopyIsIdenticalToCanonicalFile() throws {
        let bundledURL = try XCTUnwrap(StationDirectory.bundledFileURL())
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }   // up to the repo root
        let canonical = root.appendingPathComponent("data/stations.json")
        XCTAssertEqual(try Data(contentsOf: bundledURL), try Data(contentsOf: canonical))
    }

    func testRecordCountIsInExpectedRange() throws {
        XCTAssertTrue((2600...2650).contains(try bundled().stations.count))
    }

    func testEveryCrsIsThreeUppercaseLettersAndUnique() throws {
        let ids = try bundled().stations.map(\.id)
        XCTAssertTrue(ids.allSatisfy { $0.range(of: "^[A-Z]{3}$", options: .regularExpression) != nil })
        XCTAssertEqual(ids.count, Set(ids).count)
    }

    func testEveryCoordinateIsInsideTheUkBoundingBox() throws {
        XCTAssertTrue(try bundled().stations.allSatisfy {
            (49.8...60.9).contains($0.latitude) && (-8.7...1.8).contains($0.longitude)
        })
    }

    func testKnownStationsResolve() throws {
        let dir = try bundled()
        XCTAssertEqual(dir.station(crs: "KGX")?.name, "London Kings Cross")
        XCTAssertEqual(dir.station(crs: "MAN")?.name, "Manchester Piccadilly")
        XCTAssertEqual(dir.station(crs: "EDB")?.name, "Edinburgh")
        XCTAssertEqual(dir.station(crs: "CDF")?.name, "Cardiff Central")
        XCTAssertEqual(try XCTUnwrap(dir.station(crs: "KGX")).latitude, 51.5309, accuracy: 0.001)
        XCTAssertEqual(try XCTUnwrap(dir.station(crs: "KGX")).longitude, -0.1229, accuracy: 0.001)
    }

    func testLookupIgnoresCaseAndWhitespace() throws {
        let dir = try bundled()
        XCTAssertEqual(dir.station(crs: "kgx")?.id, "KGX")
        XCTAssertEqual(dir.station(crs: " KGX ")?.id, "KGX")
    }

    func testUnknownAndCoordinateLessCodesReturnNil() throws {
        let dir = try bundled()
        XCTAssertNil(dir.station(crs: "ZZZ"))
        XCTAssertNil(dir.station(crs: "PDX"))   // NaPTAN has no coordinates for PDX
    }

    func testSearchPutsPrefixMatchesBeforeOtherMatches() throws {
        let dir = try small(["Waltham Cross", "Gerrards Cross", "Cross Gates"])
        XCTAssertEqual(dir.search(name: "cross").map(\.name), ["Cross Gates", "Gerrards Cross", "Waltham Cross"])
    }

    func testSearchIsCaseInsensitiveAndFindsRealStations() throws {
        XCTAssertTrue(try bundled().search(name: "KINGS CROSS").contains { $0.id == "KGX" })
    }

    func testBlankQueryReturnsNothing() throws {
        let dir = try bundled()
        XCTAssertTrue(dir.search(name: "").isEmpty)
        XCTAssertTrue(dir.search(name: "   ").isEmpty)
    }

    func testAmpersandsAndParenthesesAreSearchableAndNeverPatterns() throws {
        let dir = try bundled()
        XCTAssertTrue(dir.search(name: "harrow & wealdstone").contains { $0.id == "HRW" })
        XCTAssertTrue(dir.search(name: "richmond (london)").contains { $0.id == "RMD" })
        _ = dir.search(name: "(")
        _ = dir.search(name: "[a-")
    }

    func testParseRejectsAMissingField() {
        assertMalformed(#"[{"crs":"KGX","name":"X","lat":51.5}]"#, "record 0 is missing crs, name, lat or lon")
    }

    func testParseRejectsANonArray() {
        assertMalformed(#"{"crs":"KGX"}"#, "stations file was not a JSON array of objects")
    }

    func testParseRejectsDuplicateCrs() {
        assertMalformed(
            #"[{"crs":"KGX","name":"A","lat":51.5,"lon":-0.1},{"crs":"KGX","name":"B","lat":51.5,"lon":-0.1}]"#,
            "duplicate CRS KGX")
    }

    func testLoadThrowsResourceMissingWhenTheBundleHasNoFile() {
        // The test bundle does not contain stations.json (only the app bundle does).
        XCTAssertThrowsError(try StationDirectory.load(from: Bundle(for: Self.self))) {
            XCTAssertEqual($0 as? StationDirectoryError, .resourceMissing)
        }
    }
}
```

- [ ] **Step 2: Write the implementation**

Create `ios/Sources/TrainAlarm/Providers/StationDirectory.swift`:

```swift
import Foundation

enum StationDirectoryError: Error, Equatable {
    case resourceMissing
    case malformed(String)
}

private final class BundleToken {}

/// Static UK station directory loaded from the bundled `stations.json`
/// (NaPTAN, Open Government Licence v3.0 - see data/STATIONS-SOURCE.md).
/// `Station.id` is the CRS code.
struct StationDirectory {
    let stations: [Station]
    private let byCRS: [String: Station]

    private init(stations: [Station]) {
        self.stations = stations
        self.byCRS = Dictionary(uniqueKeysWithValues: stations.map { ($0.id, $0) })
    }

    /// Throws rather than returning a partial directory.
    static func parse(_ data: Data) throws -> StationDirectory {
        guard let array = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else {
            throw StationDirectoryError.malformed("stations file was not a JSON array of objects")
        }
        var seen = Set<String>()
        var stations: [Station] = []
        for (index, object) in array.enumerated() {
            guard let crs = object["crs"] as? String,
                  let name = object["name"] as? String,
                  let lat = object["lat"] as? Double,
                  let lon = object["lon"] as? Double else {
                throw StationDirectoryError.malformed("record \(index) is missing crs, name, lat or lon")
            }
            guard seen.insert(crs).inserted else {
                throw StationDirectoryError.malformed("duplicate CRS \(crs)")
            }
            stations.append(Station(id: crs, name: name, latitude: lat, longitude: lon))
        }
        return StationDirectory(stations: stations)
    }

    static func bundledFileURL(bundle: Bundle? = nil) -> URL? {
        (bundle ?? Bundle(for: BundleToken.self)).url(forResource: "stations", withExtension: "json")
    }

    static func load(from bundle: Bundle? = nil) throws -> StationDirectory {
        guard let url = bundledFileURL(bundle: bundle) else { throw StationDirectoryError.resourceMissing }
        return try parse(Data(contentsOf: url))
    }

    func station(crs: String) -> Station? {
        byCRS[crs.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()]
    }

    /// Case-insensitive substring search; prefix matches first. Blank query returns nothing.
    func search(name: String) -> [Station] {
        let query = name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !query.isEmpty else { return [] }
        let matches = stations.filter { $0.name.lowercased().contains(query) }
        let prefix = matches.filter { $0.name.lowercased().hasPrefix(query) }
        let rest = matches.filter { !$0.name.lowercased().hasPrefix(query) }
        return prefix.sorted { $0.name < $1.name } + rest.sorted { $0.name < $1.name }
    }
}
```

- [ ] **Step 3: Update the CI workflows**

In `.github/workflows/ios.yml` and `.github/workflows/android.yml`, add `- "data/**"` to **both** the `push` and `pull_request` `paths` lists (so editing the canonical file reruns the bundled-copy test). Then create `.github/workflows/stations.yml`:

```yaml
name: Station tools CI

on:
  push:
    paths:
      - "tools/stations/**"
      - "data/**"
      - "ios/Sources/TrainAlarm/Resources/**"
      - "android/app/src/main/assets/**"
      - ".github/workflows/stations.yml"
  pull_request:
    paths:
      - "tools/stations/**"
      - "data/**"
      - "ios/Sources/TrainAlarm/Resources/**"
      - "android/app/src/main/assets/**"
      - ".github/workflows/stations.yml"

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Generator and sync tests
        run: python3 -m unittest discover -s tools/stations -p "test_*.py" -v
      - name: Bundled copies match data/stations.json
        run: python3 tools/stations/sync_stations.py --check
```

- [ ] **Step 4: Commit, push, and watch CI**

```bash
git add ios/Sources/TrainAlarm/Providers/StationDirectory.swift ios/Tests/TrainAlarmTests/Provider/StationDirectoryTests.swift .github/workflows/stations.yml .github/workflows/ios.yml .github/workflows/android.yml
git diff --cached --stat
git commit -m "Feature: Add iOS StationDirectory and station CI" -m "Mirrors the Android directory; CI also reruns on data changes and checks the bundled copies match." -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
git push
gh run list --branch feature/uk-station-list --limit 5
gh run watch   # pick the iOS run
```
Expected: iOS CI, Android CI and Station tools CI all green. If iOS fails with `resourceMissing`, XcodeGen did not bundle the JSON: add `buildPhase: resources` to the source entry for `Sources/TrainAlarm/Resources` in `ios/project.yml`, regenerate, push again. If it fails for any other reason, invoke `superpowers:systematic-debugging`; do not weaken a test.

---

### Task 7: Documentation

**Files:**
- Modify: `README.md`, `CLAUDE.md`, `docs/spec.md`, `.ai/architecture.md`, `.ai/tech-stack.md`, `.ai/testing-guide.md`, `ios/Sources/TrainAlarm/Providers/README.md`, `android/app/src/main/java/com/trainalarm/app/provider/README.md`

No `CHANGELOG.md` exists yet (it is planned for Stage 6), so there is none to update.

- [ ] **Step 1: Find the stale statements**

Run: `grep -n -i "station dataset\|static UK station\|not yet built\|station-search\|station coordinates" README.md CLAUDE.md docs/spec.md .ai/*.md`

- [ ] **Step 2: Update each file**

- `docs/spec.md`: where §3 and §8 say the station dataset is "not yet built" or that coordinates need it, say instead that it is built (`data/stations.json`, 2,626 GB stations from NaPTAN under OGL v3.0, `StationDirectory` on both platforms) and that wiring `searchStations` into `RttProvider` is still to do.
- `CLAUDE.md` ("Do not" list): replace the station-coordinates bullet with: station coordinates come only from `data/stations.json` (NaPTAN); never invent them. Keep "do not fake a station-search endpoint" (`RttProvider.searchStations` stays unwired).
- `.ai/architecture.md`: add `data/` and `tools/stations/` to the folder tree; add a `Station directory` row (`Providers/`, `provider/`, done); delete the "Static UK station dataset" TBD bullet.
- `.ai/tech-stack.md`: add a "Tooling" line: Python 3, standard library only, for `tools/stations/` (build-time only, not bundled).
- `.ai/testing-guide.md`: add the Python command (`python3 -m unittest discover -s tools/stations -p "test_*.py" -v`) and under "Shared rules": `stations.json` is bundled on both platforms; each platform's `StationDirectory` tests assert the bundled copy equals `data/stations.json`.
- `README.md`: add a short "Station data" section: what `data/stations.json` is, the licence and attribution (point to `data/STATIONS-SOURCE.md`), and how to regenerate.
- The two `Providers`/`provider` READMEs: add one line each describing `StationDirectory`.

- [ ] **Step 3: Verify and commit**

Run: `grep -n -i "not yet built\|TBD.*station" README.md CLAUDE.md docs/spec.md .ai/*.md` — expected: no stale station-dataset statements remain (other TBD items are unrelated).

```bash
git add README.md CLAUDE.md docs/spec.md .ai ios/Sources/TrainAlarm/Providers/README.md android/app/src/main/java/com/trainalarm/app/provider/README.md
git diff --cached --stat
git commit -m "Docs: Document the UK station dataset" -m "The dataset now exists, so the spec, project rules and guides no longer describe it as missing." -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```
