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
