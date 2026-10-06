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
