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
