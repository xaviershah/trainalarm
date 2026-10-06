"""Build data/stations.json from NaPTAN rail-area XML (Open Government Licence v3.0).

Source: https://naptan.api.dft.gov.uk/v1/access-nodes?dataFormat=xml&atcoAreaCodes=910
See data/STATIONS-SOURCE.md for the rules applied and the attribution.
"""
import json
import math
import re
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
