"""Build data/stations.json from NaPTAN rail-area XML (Open Government Licence v3.0).

Source: https://naptan.api.dft.gov.uk/v1/access-nodes?dataFormat=xml&atcoAreaCodes=910
See data/STATIONS-SOURCE.md for the rules applied and the attribution.
"""
import argparse
import json
import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

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
