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
