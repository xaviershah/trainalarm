#!/bin/bash
# Captures every demo scenario on the iOS simulator, in both themes, into docs/ui-screenshots/ios/.
# Usage: tools/screenshots/ios.sh ["iPhone 17"]
# Optional: SCENARIOS="confirm settings" tools/screenshots/ios.sh   (a subset; default: all 12)
set -euo pipefail
cd "$(dirname "$0")/../.."

DEVICE="${1:-iPhone 17}"
BUNDLE="com.trainalarm.app"
OUT="docs/ui-screenshots/ios"
SCENARIOS="${SCENARIOS:-home board confirm tracking tracking-late cancelled dropped feed-lost alarm-not-set ringing ended settings}"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"; xcrun simctl status_bar "$DEVICE" clear 2>/dev/null || true' EXIT

mkdir -p "$OUT"
(cd ios && xcodegen generate > /dev/null)
xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl bootstatus "$DEVICE" -b > /dev/null
xcodebuild build -project ios/TrainAlarm.xcodeproj -scheme TrainAlarm \
  -destination "platform=iOS Simulator,name=$DEVICE" -derivedDataPath "$BUILD" -quiet
APP="$(find "$BUILD" -name TrainAlarm.app -type d | head -1)"
xcrun simctl install "$DEVICE" "$APP"
# A fixed status bar, so two runs of this script produce comparable images.
xcrun simctl status_bar "$DEVICE" override --time 9:41 --batteryState charged --batteryLevel 100 --cellularBars 4 --wifiBars 3

for mode in dark light; do
  xcrun simctl ui "$DEVICE" appearance "$mode"
  for scenario in $SCENARIOS; do
    xcrun simctl terminate "$DEVICE" "$BUNDLE" 2>/dev/null || true
    xcrun simctl launch "$DEVICE" "$BUNDLE" -scenario "$scenario" > /dev/null
    sleep 4
    file="$OUT/$scenario-$mode.png"
    xcrun simctl io "$DEVICE" screenshot "$file" 2> /dev/null
    sips -Z 1300 "$file" > /dev/null   # half size keeps the repo small
  done
done
xcrun simctl terminate "$DEVICE" "$BUNDLE" 2>/dev/null || true
echo "Wrote $(ls "$OUT" | wc -l | tr -d ' ') screenshots to $OUT"
