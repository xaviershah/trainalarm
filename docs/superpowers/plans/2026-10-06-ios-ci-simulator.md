# iOS CI simulator selection: plan (lightweight)

**Goal:** Stop the iOS CI job failing when the runner has no "iPhone 16" simulator, and make the testing guide correct now that Xcode is installed locally.

**Why:** PR #1's `pull_request` iOS run failed with "Unable to find a device matching ... name:iPhone 16" on the same runner image (`macos-15-arm64` 20260907.0337.1, Xcode 16.4) where the earlier push run passed. A re-run passed, so the pinned name is fragile. Locally the Mac has iOS 26.5 with iPhone 17 simulators and no iPhone 16.

**Approach:** A workflow step picks the newest available iPhone simulator from `xcrun simctl` and exports its UDID; `xcodebuild` uses `id=<UDID>`. It fails with a clear message if no iPhone simulator exists. No new dependencies (`python3` and `xcrun` ship on the runner and the Mac).

## Task 1: Pick the simulator in CI

**Files:** Modify `.github/workflows/ios.yml`

- [ ] Add this step between "Generate Xcode project" and "Build & test":

```yaml
      - name: Pick an available iPhone simulator
        run: |
          UDID=$(xcrun simctl list devices available -j | python3 -c '
          import json, re, sys
          runtimes = json.load(sys.stdin)["devices"]
          newest_first = sorted(
              (r for r in runtimes if "iOS" in r),
              key=lambda r: [int(n) for n in re.findall(r"\d+", r)],
              reverse=True,
          )
          for runtime in newest_first:
              for device in runtimes[runtime]:
                  if device["name"].startswith("iPhone"):
                      print(device["udid"])
                      sys.exit(0)
          sys.exit("No available iPhone simulator on this runner")
          ')
          echo "Using simulator $UDID"
          echo "SIM_UDID=$UDID" >> "$GITHUB_ENV"
```

- [ ] Change the test destination in the "Build & test" step from `-destination "platform=iOS Simulator,name=iPhone 16"` to `-destination "platform=iOS Simulator,id=$SIM_UDID"`.

- [ ] Verify locally (Xcode 26.5, no iPhone 16 present): run the same Python snippet against `xcrun simctl list devices available -j` and confirm it prints an iPhone 17-family UDID; then `cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,id=<that UDID>" -skipMacroValidation` and confirm `** TEST SUCCEEDED **` with 27 tests, 0 failures.

- [ ] Commit: `Fix: Pick an available iPhone simulator in iOS CI`.

## Task 2: Docs

**Files:** Modify `.ai/testing-guide.md` (lines 16-20, the iOS "Run it" block).

- [ ] Replace the sentence "Run it (needs full Xcode 26 — until it is installed locally, iOS tests only run in CI):" with "Run it (needs full Xcode 26; CI runs the same tests on every push/PR that touches `ios/`):".
- [ ] Replace the `-destination "platform=iOS Simulator,name=iPhone 16"` line with `-destination "platform=iOS Simulator,name=<an installed iPhone>"` and add one line after the code block: "List installed simulators with `xcrun simctl list devices available iPhone` (the name varies by Xcode version; CI picks one automatically)."
- [ ] Check `README.md`, `ios/README.md` and `ios/CLAUDE.md` for the same stale claim (`grep -rn "iPhone 16\|only run in CI\|not this linked" README.md ios/README.md ios/CLAUDE.md .ai`) and fix any hit that says iOS tests cannot run locally.
- [ ] Commit: `Docs: Update iOS testing guide for local Xcode`.

## Verification (before the PR)

- Local run from Task 1 passes (27 tests).
- After pushing, the PR's iOS CI run must show "Using simulator <UDID>" in the new step and `** TEST SUCCEEDED **`. If CI fails, fix before asking for review.
- Python and Android suites are untouched; no need to re-run them.
