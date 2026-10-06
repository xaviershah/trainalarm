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


if __name__ == "__main__":
    unittest.main()
