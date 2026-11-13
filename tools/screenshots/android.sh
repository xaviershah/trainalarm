#!/bin/bash
# Captures every demo scenario on a running Android emulator, in both themes, into
# docs/ui-screenshots/android/. Start the emulator first (see the Stage 4 plan, Task 12 Step 0).
# Usage: tools/screenshots/android.sh
set -euo pipefail
cd "$(dirname "$0")/../.."

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
OUT="docs/ui-screenshots/android"
SCENARIOS="home board confirm tracking tracking-late cancelled dropped feed-lost alarm-not-set ringing ended settings"

if ! "$ADB" devices | grep -q "emulator-.*device$"; then
  echo "No running emulator. Start one first." >&2
  exit 1
fi

mkdir -p "$OUT"
(cd android && ./gradlew assembleDebug --offline -q)
"$ADB" install -r android/app/build/outputs/apk/debug/app-debug.apk > /dev/null
"$ADB" shell am start -n com.trainalarm.app/.MainActivity > /dev/null   # first launch settles the app
"$ADB" shell "cmd uimode night no" > /dev/null

for mode in no yes; do
  name=light; [ "$mode" = yes ] && name=dark
  "$ADB" shell "cmd uimode night $mode" > /dev/null
  for scenario in $SCENARIOS; do
    "$ADB" shell am force-stop com.trainalarm.app
    "$ADB" shell am start -n com.trainalarm.app/.MainActivity --es scenario "$scenario" > /dev/null
    sleep 4
    file="$OUT/$scenario-$name.png"
    "$ADB" exec-out screencap -p > "$file"
    sips -Z 1300 "$file" > /dev/null
  done
done
"$ADB" shell am force-stop com.trainalarm.app
"$ADB" shell "cmd uimode night no" > /dev/null
echo "Wrote $(ls "$OUT" | wc -l | tr -d ' ') screenshots to $OUT"
