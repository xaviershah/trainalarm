# Stage 4 UI Build Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Stage 0 placeholder with the real TrainAlarm UI on iOS and Android, wired to the Stage 1–3 layers, working while the app is open.

**Architecture:** One `SessionModel` per platform owns a `JourneyTracker`, collects its events once and exposes display state. Dumb SwiftUI and Compose screens render it. A token-based theme (dark and light), dot-matrix time readouts, and a drawn route strip carry the look. Real clock and location sources, coordinate resolution, and a debug-only demo mode complete the wiring.

**Tech Stack:** Swift 5 mode, SwiftUI, iOS 26, XcodeGen, XCTest. Kotlin 2.0.20, Jetpack Compose (BOM 2024.09.02), Material 3, JUnit 4. No new packages.

**Spec:** `docs/superpowers/specs/2026-11-12-stage-4-ui-build-design.md` (read it first). Mockup: `docs/ui-mockup/trainalarm-ui-direction.html`.

## Plan in plain English

| # | Task | Files touched | What it proves |
|---|---|---|---|
| 1 | Baseline and environment | none | Both suites pass with no secrets; emulator approval asked |
| 2 | Palette tokens and contrast test | `App/Theme`, `theme/` | Every token pair meets 4.5:1 in both themes |
| 3 | `TimeText` (London time, two-dot colon) | `App/`, root pkg | Times format right across midnight and BST |
| 4 | Provider: `callingAt`, coordinates, search adapter | `Providers/`, `provider/` | Board filtered to destination; stations have real coordinates |
| 5 | Real `SystemClock` and `LocationSource` | `Tracking/`, `tracking/` | The tracker's missing inputs exist |
| 6 | `SettingsStore` (theme, default lead time) | `App/`, root pkg | Settings persist |
| 7 | `SessionModel` part 1: picking, confirming, permissions, start (includes the 60 s poll and the 429 board state) | `App/`, root pkg | Selecting, first run, empty board, refused permissions, alarm scheduled |
| 8 | `SessionModel` part 2: tracker events, ringing, arrival | same | Every tracker event handled, idempotent |
| 9 | RTT token from gitignored build-time files | `project.yml`, gradle, `.gitignore` | Builds with and without a token; token never committed |
| 10 | Demo mode and scenario launcher (debug only) | debug sources | Every screen state reachable without live data; absent from release |
| 11 | Real permissions, GPS control, "nearest station" (model level, before the screens) | `App/`, root pkg, `Providers/`, `provider/` | Refusals block or warn; permission asked once; nearest fills From |
| 12 | UI foundation: fonts, theme, components, composition root, Home and Confirm screens (emulator install gate) | `App/`, `ui/`, `theme/`, `Resources/Fonts` | Screens match the mockup in both themes (seen on iOS) |
| 13 | Tracking screen, route strip, state cards, Ended screen | `App/`, `App/UI`, `ui/` | Route progress and card logic tested; screens match the mockup (seen on iOS) |
| 14 | Ringing: screen, AlarmKit text and tint, Android ringing signal, iOS signal check | `Alarm/`, `alarm/` | Ringing view appears; iOS signal verified or reported |
| 15 | Settings screen: theme, lead time, permission rows, test alarm, attribution | `App/UI`, `ui/` | Settings match the mockup |
| 16 | Screenshots: all scenarios, both themes, both platforms | `docs/ui-screenshots/` | Every screen seen and checked |
| 17 | Docs, manifest comment, dark window background | docs, manifest, `themes.xml` | Docs match reality |

## Global Constraints

- Native only: SwiftUI and Jetpack Compose. No React Native, Flutter or KMP.
- iOS deployment target 26.0; Android minSdk 29, target and compile SDK 35.
- No new third-party packages. No map package (the map is Stage 4b). No navigation library on Android.
- Orange `#F36509` and teal `#015D67` are fills only. Never use them as small text.
- Anton for headlines and buttons, Doto for time readouts, system font for body. Draw the time colon as two dots, never the Doto glyph.
- Times display in `Europe/London`. Countdowns use absolute instants.
- Never request background location. Stage 4 does not track in the background.
- Do not invent RTT response shapes: read `downloads/RTT.GH.API-spec` first. Use `/gb-nr/location`, not `/rtt/location`. Do not use `api.rtt.io`.
- Coordinates come only from `data/stations.json` through `StationDirectory`.
- A token must never be committed. Token builds are for the developer's own devices only.
- A simulator or emulator result is not a pass for alarm delivery. Do not change the Stage 3 status line in `docs/spec.md`.
- Both platforms get every feature, in the matching layer.
- Commit messages follow the global format and end with the `Co-Authored-By` trailer. Sonnet for implementers and reviewers; never Opus unless the user says so.
- When `superpowers:subagent-driven-development` finishes and invokes `superpowers:finishing-a-development-branch`, let it run its verification steps but **do not present the following options menu**:
  ```
  1. Merge back to <base-branch> locally
  2. Push and create a Pull Request
  3. Keep the branch as-is (I'll handle it later)
  ```
  Instead, proceed to Step 12 of `gl-starting-a-feature`. Step 12 runs in full, then Step 13 handles the finishing sequence (delete spec+plan, squash implementation commits, push, exit worktree, open the PR).
- Never merge the PR: the user reviews it and squash-merges it on GitHub.
- Never run `git push --force` or `git push --force-with-lease`. If history has diverged, stop, tell the user which branch and commits are affected, and leave the push to them.
- Do not push during implementation. Every task commits locally only, and every platform is verified locally (build and tests). The first push of implementation work happens in Step 13, after the squash, so CI runs once on the final commits. If a platform genuinely cannot be verified locally, stop and tell the user rather than pushing the PR branch to find out.

## Review Focus

Inputs and conditions the spec implies that no single task's happy path exercises, most likely first:

1. First launch with no stored lead time: Start stays disabled until one is chosen (test in Task 7).
2. Alarm permission refused: Start is blocked, not warned (Task 7, Task 14).
3. The same tracker event twice, or a restore after a loss: the display does not toggle wrongly (Task 8).
4. A device in another time zone, or a journey crossing midnight or a BST change: times stay London time (Task 3).
5. A station with no coordinates (an Elizabeth line code): no crash, no 0,0 position (Task 4).

---

### Task 1: Baseline and environment

**Files:** none changed.

**Interfaces:** Produces: a recorded baseline (iOS 128 tests passing; Android `test lint assembleDebug` succeeding) that later tasks must not regress.

- [ ] **Step 1: Run the iOS suite with no secrets file**

First run `ls -d ios/*.xcodeproj`. There must be exactly one, `ios/TrainAlarm.xcodeproj`. A second one (for example `ios/TrainAlarm 2.xcodeproj`) is a generated, gitignored duplicate that cloud sync can create; delete it before going on, because `xcodebuild` may refuse to choose between two projects. If cloud sync keeps making duplicates of build files, tell the controller.

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" 2>&1 | grep -E "Executed [0-9]+ tests|TEST (SUCCEEDED|FAILED)|error:" | tail -5
```
Expected: `Executed 128 tests, with 0 failures` and `** TEST SUCCEEDED **`. If `iPhone 17` is absent, pick a name from `xcrun simctl list devices available iPhone`.

- [ ] **Step 2: Run the Android suite with no secrets file**

```bash
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew test lint assembleDebug --offline 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`. Both commands were run during planning and passed.

- [ ] **Step 3: Record the baseline and the environment gate**

Write the counts into the task ledger. Report to the controller that no Android emulator or `system-images` exists under `~/Library/Android/sdk`; the controller asks the user to approve the emulator install, which runs in Task 12 (Step 0). If either step fails, stop and report; do not start Task 2.

---

### Task 2: Palette tokens and contrast test

**Files:**
- Create: `ios/Sources/TrainAlarm/App/Theme/Palette.swift`, `ios/Tests/TrainAlarmTests/App/PaletteContrastTests.swift`
- Create: `android/app/src/main/java/com/trainalarm/app/theme/Palette.kt`, `android/app/src/test/java/com/trainalarm/app/theme/PaletteContrastTest.kt`

**Interfaces:**
- Produces (iOS): `struct PaletteTokens` with `UInt32` fields `bg, surface, surface2, text, head, muted, accent, onAccent, teal, onTeal, tealText, bad`; `enum Palette { static let dark, light: PaletteTokens }`; `enum Contrast { static func ratio(_ a: UInt32, _ b: UInt32) -> Double }`.
- Produces (Android): the same names in package `com.trainalarm.app.theme`, values as `Long` hex (`0xFF201D1C`).

- [ ] **Step 1: Write the failing iOS test**

```swift
import XCTest
@testable import TrainAlarm

final class PaletteContrastTests: XCTestCase {
    private func assertReadable(_ name: String, _ p: PaletteTokens, file: StaticString = #filePath, line: UInt = #line) {
        let pairs: [(String, UInt32, UInt32)] = [
            ("text on bg", p.text, p.bg), ("text on surface", p.text, p.surface),
            ("head on surface", p.head, p.surface), ("muted on surface", p.muted, p.surface),
            ("onAccent on accent", p.onAccent, p.accent), ("onTeal on teal", p.onTeal, p.teal),
            ("tealText on surface", p.tealText, p.surface), ("bad on surface", p.bad, p.surface),
        ]
        for (label, fg, bg) in pairs {
            XCTAssertGreaterThanOrEqual(Contrast.ratio(fg, bg), 4.5, "\(name): \(label)", file: file, line: line)
        }
    }

    func testDarkThemeMeetsAA() { assertReadable("dark", Palette.dark) }
    func testLightThemeMeetsAA() { assertReadable("light", Palette.light) }

    func testRatioMatchesKnownValues() {
        XCTAssertEqual(Contrast.ratio(0xFFFFFF, 0x000000), 21.0, accuracy: 0.01)
        XCTAssertEqual(Contrast.ratio(0xCFC3B6, 0x201D1C), 9.68, accuracy: 0.05)
    }

    func testOrangeAndTealAreNotUsableAsTextOnTheWrongGround() {
        XCTAssertLessThan(Contrast.ratio(0x015D67, 0x201D1C), 3.0)
        XCTAssertLessThan(Contrast.ratio(0xF36509, 0xCFC3B6), 3.0)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/PaletteContrastTests 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | head -3
```
Expected: build error, `cannot find 'Palette' in scope`.

- [ ] **Step 3: Implement iOS `Palette.swift`**

```swift
import Foundation

/// Colour tokens as 0xRRGGBB. Same names and values on Android. See spec section 5.
struct PaletteTokens: Equatable {
    let bg, surface, surface2, text, head, muted: UInt32
    let accent, onAccent, teal, onTeal, tealText, bad: UInt32
}

enum Palette {
    static let dark = PaletteTokens(
        bg: 0x201D1C, surface: 0x2C2724, surface2: 0x3A322E, text: 0xCFC3B6, head: 0xF3ECE3, muted: 0xB7AA9C,
        accent: 0xF36509, onAccent: 0x201D1C, teal: 0x015D67, onTeal: 0xF3ECE3, tealText: 0x2FA3B1, bad: 0xFF8F7A)
    static let light = PaletteTokens(
        bg: 0xCFC3B6, surface: 0xE4DCD2, surface2: 0xF1EBE3, text: 0x201D1C, head: 0x201D1C, muted: 0x6C584C,
        accent: 0xF36509, onAccent: 0x201D1C, teal: 0x015D67, onTeal: 0xF3ECE3, tealText: 0x015D67, bad: 0xA32C1B)
}

/// WCAG 2.x contrast ratio between two 0xRRGGBB colours.
enum Contrast {
    static func ratio(_ a: UInt32, _ b: UInt32) -> Double {
        let (la, lb) = (luminance(a), luminance(b))
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
    private static func luminance(_ hex: UInt32) -> Double {
        func channel(_ shift: UInt32) -> Double {
            let c = Double((hex >> shift) & 0xFF) / 255
            return c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Same command as Step 2. Expected: `** TEST SUCCEEDED **`. If a pair fails, report the ratio; do not weaken the 4.5 threshold. Adjust the token only if the spec's table allows, otherwise stop and report.

- [ ] **Step 5: Write the failing Android test**

```kotlin
package com.trainalarm.app.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteContrastTest {
    private fun assertReadable(name: String, p: PaletteTokens) {
        val pairs = listOf(
            "text on bg" to (p.text to p.bg), "text on surface" to (p.text to p.surface),
            "head on surface" to (p.head to p.surface), "muted on surface" to (p.muted to p.surface),
            "onAccent on accent" to (p.onAccent to p.accent), "onTeal on teal" to (p.onTeal to p.teal),
            "tealText on surface" to (p.tealText to p.surface), "bad on surface" to (p.bad to p.surface),
        )
        for ((label, pair) in pairs) {
            val ratio = Contrast.ratio(pair.first, pair.second)
            assertTrue("$name: $label is $ratio", ratio >= 4.5)
        }
    }

    @Test fun darkThemeMeetsAA() = assertReadable("dark", Palette.dark)
    @Test fun lightThemeMeetsAA() = assertReadable("light", Palette.light)

    @Test fun ratioMatchesKnownValues() {
        assertEquals(21.0, Contrast.ratio(0xFFFFFF, 0x000000), 0.01)
        assertEquals(9.68, Contrast.ratio(0xCFC3B6, 0x201D1C), 0.05)
    }

    @Test fun orangeAndTealAreNotUsableAsTextOnTheWrongGround() {
        assertTrue(Contrast.ratio(0x015D67, 0x201D1C) < 3.0)
        assertTrue(Contrast.ratio(0xF36509, 0xCFC3B6) < 3.0)
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

```bash
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew testDebugUnitTest --offline --tests "com.trainalarm.app.theme.PaletteContrastTest" 2>&1 | grep -E "error:|e: |FAILED|BUILD" | head -4
```
Expected: compile error, unresolved reference `Palette`.

- [ ] **Step 7: Implement Android `Palette.kt`**

```kotlin
package com.trainalarm.app.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Colour tokens as 0xRRGGBB. Same names and values on iOS. See spec section 5. */
data class PaletteTokens(
    val bg: Int, val surface: Int, val surface2: Int, val text: Int, val head: Int, val muted: Int,
    val accent: Int, val onAccent: Int, val teal: Int, val onTeal: Int, val tealText: Int, val bad: Int,
)

object Palette {
    val dark = PaletteTokens(
        bg = 0x201D1C, surface = 0x2C2724, surface2 = 0x3A322E, text = 0xCFC3B6, head = 0xF3ECE3, muted = 0xB7AA9C,
        accent = 0xF36509, onAccent = 0x201D1C, teal = 0x015D67, onTeal = 0xF3ECE3, tealText = 0x2FA3B1, bad = 0xFF8F7A,
    )
    val light = PaletteTokens(
        bg = 0xCFC3B6, surface = 0xE4DCD2, surface2 = 0xF1EBE3, text = 0x201D1C, head = 0x201D1C, muted = 0x6C584C,
        accent = 0xF36509, onAccent = 0x201D1C, teal = 0x015D67, onTeal = 0xF3ECE3, tealText = 0x015D67, bad = 0xA32C1B,
    )
}

/** WCAG 2.x contrast ratio between two 0xRRGGBB colours. */
object Contrast {
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(hex: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((hex shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
```

- [ ] **Step 8: Run it to verify it passes**

Same command as Step 6. Expected: `BUILD SUCCESSFUL`. (Kotlin hex literals above `0x7FFFFFFF` overflow `Int`; every value here is below `0x01000000`, so `Int` is safe.)

- [ ] **Step 9: Commit**

```bash
git add ios/Sources/TrainAlarm/App/Theme ios/Tests/TrainAlarmTests/App android/app/src/main/java/com/trainalarm/app/theme android/app/src/test/java/com/trainalarm/app/theme
git commit -m "Feature: Add palette tokens and contrast tests on both platforms

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `TimeText` (London time, countdown, date label)

**Files:**
- Create: `ios/Sources/TrainAlarm/App/TimeText.swift`, `ios/Tests/TrainAlarmTests/App/TimeTextTests.swift`
- Create: `android/app/src/main/java/com/trainalarm/app/TimeText.kt`, `android/app/src/test/java/com/trainalarm/app/TimeTextTest.kt`

**Interfaces:**
- Produces (iOS): `enum TimeText { static let london: TimeZone; struct HM: Equatable { let hours: String; let minutes: String; var plain: String }; static func clock(_ date: Date) -> HM; static func countdown(_ seconds: TimeInterval) -> HM; static func dateLabel(for date: Date, now: Date) -> String? }`.
- Produces (Android): `object TimeText` with `london: ZoneId`, `data class HM(hours, minutes)` with `plain`, `clock(Instant): HM`, `countdown(Long): HM` (seconds), `dateLabel(date: Instant, now: Instant): String?`.
- The two-dot colon is drawn by the views in Task 12. This task only produces the digits.

- [ ] **Step 1: Write the failing iOS test**

```swift
import XCTest
@testable import TrainAlarm

final class TimeTextTests: XCTestCase {
    private func date(_ s: String) -> Date { ISO8601DateFormatter().date(from: s)! }

    func testClockUsesLondonTimeInSummer() {
        XCTAssertEqual(TimeText.clock(date("2026-07-01T17:50:00Z")), TimeText.HM(hours: "18", minutes: "50"))
    }

    func testClockAcrossTheMarchChangeover() {
        XCTAssertEqual(TimeText.clock(date("2026-03-29T00:59:00Z")), TimeText.HM(hours: "00", minutes: "59"))
        XCTAssertEqual(TimeText.clock(date("2026-03-29T01:01:00Z")), TimeText.HM(hours: "02", minutes: "01"))
    }

    func testClockAcrossMidnight() {
        XCTAssertEqual(TimeText.clock(date("2026-07-01T22:59:00Z")), TimeText.HM(hours: "23", minutes: "59"))
        XCTAssertEqual(TimeText.clock(date("2026-07-01T23:01:00Z")), TimeText.HM(hours: "00", minutes: "01"))
    }

    func testClockIgnoresTheDeviceZone() {
        let saved = NSTimeZone.default
        NSTimeZone.default = TimeZone(identifier: "Asia/Tokyo")!
        defer { NSTimeZone.default = saved }
        XCTAssertEqual(TimeText.clock(date("2026-07-01T17:50:00Z")).plain, "18:50")
    }

    func testCountdownRoundsDownToTheMinute() {
        XCTAssertEqual(TimeText.countdown(158 * 60 + 59), TimeText.HM(hours: "2", minutes: "38"))
        XCTAssertEqual(TimeText.countdown(12 * 60), TimeText.HM(hours: "0", minutes: "12"))
    }

    func testCountdownNeverGoesNegative() {
        XCTAssertEqual(TimeText.countdown(-30), TimeText.HM(hours: "0", minutes: "00"))
    }

    func testDateLabelIsNilOnTheSameLondonDay() {
        XCTAssertNil(TimeText.dateLabel(for: date("2026-07-01T22:59:00Z"), now: date("2026-07-01T22:50:00Z")))
    }

    func testDateLabelAppearsAfterLondonMidnight() {
        // 23:10Z on 1 July is 00:10 BST on 2 July.
        XCTAssertEqual(TimeText.dateLabel(for: date("2026-07-01T23:10:00Z"), now: date("2026-07-01T22:50:00Z")), "Thu 2 Jul")
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/TimeTextTests 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | head -3
```
Expected: `cannot find 'TimeText' in scope`.

- [ ] **Step 3: Implement `TimeText.swift`**

```swift
import Foundation

/// Time text for the UI. Times are always shown in London time, whatever the device zone,
/// and countdowns come from absolute instants. See spec section 4.
enum TimeText {
    static let london = TimeZone(identifier: "Europe/London")!

    struct HM: Equatable {
        let hours: String
        let minutes: String
        var plain: String { "\(hours):\(minutes)" }
    }

    private static var calendar: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = london
        return c
    }

    static func clock(_ date: Date) -> HM {
        let c = calendar.dateComponents([.hour, .minute], from: date)
        return HM(hours: String(format: "%02d", c.hour ?? 0), minutes: String(format: "%02d", c.minute ?? 0))
    }

    /// Time left as hours and minutes, rounded down. Negative values show 0:00.
    static func countdown(_ seconds: TimeInterval) -> HM {
        let totalMinutes = max(0, Int(seconds)) / 60
        return HM(hours: String(totalMinutes / 60), minutes: String(format: "%02d", totalMinutes % 60))
    }

    /// nil on the same London day as `now`, otherwise a label such as "Thu 2 Jul".
    /// The fixed en_US_POSIX locale keeps month names identical on both platforms.
    static func dateLabel(for date: Date, now: Date) -> String? {
        guard !calendar.isDate(date, inSameDayAs: now) else { return nil }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = london
        formatter.dateFormat = "EEE d MMM"
        return formatter.string(from: date)
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Same command as Step 2. Expected: `** TEST SUCCEEDED **`, 8 tests.

- [ ] **Step 5: Write the failing Android test**

```kotlin
package com.trainalarm.app

import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeTextTest {
    private fun at(s: String) = Instant.parse(s)

    @Test fun clock_usesLondonTimeInSummer() =
        assertEquals(TimeText.HM("18", "50"), TimeText.clock(at("2026-07-01T17:50:00Z")))

    @Test fun clock_acrossTheMarchChangeover() {
        assertEquals(TimeText.HM("00", "59"), TimeText.clock(at("2026-03-29T00:59:00Z")))
        assertEquals(TimeText.HM("02", "01"), TimeText.clock(at("2026-03-29T01:01:00Z")))
    }

    @Test fun clock_acrossMidnight() {
        assertEquals(TimeText.HM("23", "59"), TimeText.clock(at("2026-07-01T22:59:00Z")))
        assertEquals(TimeText.HM("00", "01"), TimeText.clock(at("2026-07-01T23:01:00Z")))
    }

    @Test fun clock_ignoresTheDeviceZone() {
        val saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        try {
            assertEquals("18:50", TimeText.clock(at("2026-07-01T17:50:00Z")).plain)
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test fun countdown_roundsDownToTheMinute() {
        assertEquals(TimeText.HM("2", "38"), TimeText.countdown(158L * 60 + 59))
        assertEquals(TimeText.HM("0", "12"), TimeText.countdown(12L * 60))
    }

    @Test fun countdown_neverGoesNegative() =
        assertEquals(TimeText.HM("0", "00"), TimeText.countdown(-30))

    @Test fun dateLabel_isNullOnTheSameLondonDay() =
        assertNull(TimeText.dateLabel(at("2026-07-01T22:59:00Z"), at("2026-07-01T22:50:00Z")))

    @Test fun dateLabel_appearsAfterLondonMidnight() =
        assertEquals("Thu 2 Jul", TimeText.dateLabel(at("2026-07-01T23:10:00Z"), at("2026-07-01T22:50:00Z")))
}
```

- [ ] **Step 6: Run it to verify it fails**

```bash
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew testDebugUnitTest --offline --tests "com.trainalarm.app.TimeTextTest" 2>&1 | grep -E "e: |FAILED|BUILD" | head -3
```
Expected: unresolved reference `TimeText`.

- [ ] **Step 7: Implement `TimeText.kt`**

```kotlin
package com.trainalarm.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Time text for the UI. Times are always shown in London time, whatever the device zone,
 * and countdowns come from absolute instants. See spec section 4.
 */
object TimeText {
    val london: ZoneId = ZoneId.of("Europe/London")

    data class HM(val hours: String, val minutes: String) {
        val plain: String get() = "$hours:$minutes"
    }

    private fun two(n: Number) = String.format(Locale.ROOT, "%02d", n)

    fun clock(at: Instant): HM {
        val t = at.atZone(london)
        return HM(two(t.hour), two(t.minute))
    }

    /** Time left as hours and minutes, rounded down. Negative values show 0:00. */
    fun countdown(seconds: Long): HM {
        val totalMinutes = maxOf(0L, seconds) / 60
        return HM((totalMinutes / 60).toString(), two(totalMinutes % 60))
    }

    /** null on the same London day as [now], otherwise a label such as "Thu 2 Jul". Locale.US keeps month names identical to iOS. */
    fun dateLabel(date: Instant, now: Instant): String? {
        val day = date.atZone(london).toLocalDate()
        if (day == now.atZone(london).toLocalDate()) return null
        return day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US))
    }
}
```

- [ ] **Step 8: Run it to verify it passes**

Same command as Step 6. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add ios/Sources/TrainAlarm/App/TimeText.swift ios/Tests/TrainAlarmTests/App/TimeTextTests.swift android/app/src/main/java/com/trainalarm/app/TimeText.kt android/app/src/test/java/com/trainalarm/app/TimeTextTest.kt
git commit -m "Feature: Add London-time text helpers on both platforms

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Provider: `callingAt` filter and a directory-backed provider

**Files:**
- Modify: `ios/Sources/TrainAlarm/Providers/TrainDataProvider.swift`, `ios/Sources/TrainAlarm/Providers/RttProvider.swift`
- Create: `ios/Sources/TrainAlarm/Providers/DirectoryBackedProvider.swift`
- Modify (tests): `ios/Tests/TrainAlarmTests/Provider/RttProviderTests.swift`, `ios/Tests/TrainAlarmTests/Tracking/TrackingFakes.swift`
- Create (tests): `ios/Tests/TrainAlarmTests/Provider/DirectoryBackedProviderTests.swift`
- Modify: `android/app/src/main/java/com/trainalarm/app/provider/TrainDataProvider.kt`, `.../provider/RttProvider.kt`
- Create: `android/app/src/main/java/com/trainalarm/app/provider/DirectoryBackedProvider.kt`
- Modify (tests): `android/app/src/test/java/com/trainalarm/app/provider/RttProviderTest.kt`, `.../tracking/TrackingFakes.kt`
- Create (tests): `android/app/src/test/java/com/trainalarm/app/provider/DirectoryBackedProviderTest.kt`

**Interfaces:**
- Produces: `departureBoard(station:from:callingAt:)` (iOS, plus a two-argument convenience in a protocol extension) and `departureBoard(station, from, callingAt = null)` (Android). With `callingAt` set, the request adds `filterTo=<CRS>` to `/gb-nr/location`.
- Produces: `DirectoryBackedProvider(base:directory:)` on both platforms, a `TrainDataProvider` that (a) answers `searchStations` from `StationDirectory`, (b) replaces each station in returned services with the directory's station when its CRS is known, so coordinates are real, and (c) leaves unknown stations untouched. `SessionModel.search` (Task 7) calls `StationDirectory` directly, so the app does not go through this decorator's `searchStations`; it is there, and tested, so any other caller of the provider gets the same directory-backed search.
- Produces: `Station.hasCoordinate` (true unless latitude and longitude are both 0).
- Consumes: `StationDirectory.parse` / `station(crs:)` / `search(name:)` (Android `search(name)`).
- `RttProvider.searchStations` stays throwing and unwired (project rule). The connection to the directory is made deliberately here, in the decorator.

- [ ] **Step 1: iOS, write the failing tests**

In `RttProviderTests.swift`, add `static var lastRequest: URLRequest?` to `StubURLProtocol` and set it as the first line of `startLoading()`:

```swift
    static var lastRequest: URLRequest?

    override func startLoading() {
        Self.lastRequest = request
```

Then add to `RttProviderTests`:

```swift
    private let edinburgh = Station(id: "EDB", name: "Edinburgh", latitude: 55.9521, longitude: -3.1894)

    private func queryItems() -> [String: String] {
        let url = StubURLProtocol.lastRequest!.url!
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)!.queryItems ?? []
        return Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
    }

    func testDepartureBoardSendsFilterToWhenCallingAtIsGiven() async throws {
        reply(204)
        _ = try await makeProvider().departureBoard(station: waterloo, from: Date(), callingAt: edinburgh)
        XCTAssertEqual(queryItems()["filterTo"], "EDB")
        XCTAssertEqual(queryItems()["code"], "WAT")
        XCTAssertEqual(StubURLProtocol.lastRequest!.url!.path, "/gb-nr/location")
    }

    func testDepartureBoardOmitsFilterToByDefault() async throws {
        reply(204)
        _ = try await makeProvider().departureBoard(station: waterloo, from: Date())
        XCTAssertNil(queryItems()["filterTo"])
    }
```

Create `DirectoryBackedProviderTests.swift`:

```swift
import XCTest
@testable import TrainAlarm

final class DirectoryBackedProviderTests: XCTestCase {
    private final class RecordingProvider: TrainDataProvider {
        let service: Service
        var lastCallingAt: Station?
        init(service: Service) { self.service = service }
        func searchStations(query: String) async throws -> [Station] { throw ProviderError.notImplemented("fake") }
        func departureBoard(station: Station, from: Date, callingAt: Station?) async throws -> [Service] {
            lastCallingAt = callingAt
            return [service]
        }
        func serviceDetails(serviceId: String, date: Date) async throws -> Service { service }
    }

    private let json = #"[{"crs":"EDB","name":"Edinburgh","lat":55.9521,"lon":-3.1894},{"crs":"KGX","name":"London Kings Cross","lat":51.5309,"lon":-0.1233}]"#

    private func rtt(_ id: String, _ name: String) -> Station { Station(id: id, name: name, latitude: 0, longitude: 0) }

    private func makeService() throws -> Service {
        func stop(_ s: Station) -> Stop {
            Stop(station: s, scheduledArrival: nil, scheduledDeparture: nil, estimatedArrival: nil, estimatedDeparture: nil)
        }
        let kgx = rtt("KGX", "London Kings Cross"), xyz = rtt("XYZ", "Nowhere"), edb = rtt("EDB", "Edinburgh Waverley")
        return Service(id: "s1", operatorName: "LNER",
                       journey: try Journey(origin: kgx, destination: edb, stops: [stop(kgx), stop(xyz), stop(edb)]))
    }

    private func makeProvider() throws -> (DirectoryBackedProvider, RecordingProvider) {
        let base = RecordingProvider(service: try makeService())
        return (DirectoryBackedProvider(base: base, directory: try StationDirectory.parse(Data(json.utf8))), base)
    }

    func testServiceDetailsGetsRealCoordinatesForKnownStations() async throws {
        let (provider, _) = try makeProvider()
        let service = try await provider.serviceDetails(serviceId: "s1", date: Date())
        XCTAssertEqual(service.journey.destination.latitude, 55.9521, accuracy: 0.0001)
        XCTAssertEqual(service.journey.destination.name, "Edinburgh")
        XCTAssertEqual(service.journey.stops[0].station.longitude, -0.1233, accuracy: 0.0001)
    }

    func testUnknownStationKeepsItsNameAndHasNoCoordinate() async throws {
        let (provider, _) = try makeProvider()
        let unknown = try await provider.serviceDetails(serviceId: "s1", date: Date()).journey.stops[1].station
        XCTAssertEqual(unknown.name, "Nowhere")
        XCTAssertFalse(unknown.hasCoordinate)
    }

    func testDepartureBoardIsResolvedAndPassesCallingAtOn() async throws {
        let (provider, base) = try makeProvider()
        let edb = rtt("EDB", "Edinburgh")
        let board = try await provider.departureBoard(station: rtt("KGX", "London Kings Cross"), from: Date(), callingAt: edb)
        XCTAssertEqual(base.lastCallingAt, edb)
        XCTAssertTrue(board[0].journey.destination.hasCoordinate)
    }

    func testSearchStationsAnswersFromTheDirectory() async throws {
        let (provider, _) = try makeProvider()
        let names = try await provider.searchStations(query: "edin").map(\.name)
        XCTAssertEqual(names, ["Edinburgh"])
    }
}
```

- [ ] **Step 2: iOS, run to verify failure**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/RttProviderTests -only-testing:TrainAlarmTests/DirectoryBackedProviderTests 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | head -4
```
Expected: errors such as `extra argument 'callingAt' in call` and `cannot find 'DirectoryBackedProvider'`.

- [ ] **Step 3: iOS, implement**

`TrainDataProvider.swift`: replace the `departureBoard` requirement and add the extension at the end of the file:

```swift
    /// Live/scheduled departures from `station` at or after `from`. With `callingAt` set, only
    /// trains that subsequently call at that station are returned.
    func departureBoard(station: Station, from: Date, callingAt: Station?) async throws -> [Service]
```
```swift
extension TrainDataProvider {
    /// A board with no destination filter.
    func departureBoard(station: Station, from: Date) async throws -> [Service] {
        try await departureBoard(station: station, from: from, callingAt: nil)
    }
}
```

`RttProvider.swift`: replace the first lines of `departureBoard`:

```swift
    func departureBoard(station: Station, from: Date, callingAt: Station?) async throws -> [Service] {
        let iso = ISO8601DateFormatter().string(from: from)
        var query = ["code": station.id, "timeFrom": iso]
        // filterTo: only trains that subsequently call at this location (/gb-nr/location in the RTT spec).
        if let callingAt { query["filterTo"] = callingAt.id }
        // 204 is "valid query, no services found": an empty board, not an error.
        guard let json = try await getJSON(path: "/gb-nr/location", query: query) else {
```

`TrackingFakes.swift` line 99: `func departureBoard(station: Station, from: Date, callingAt: Station?) async throws -> [Service] { throw ProviderError.notImplemented("fake") }`

Create `DirectoryBackedProvider.swift`:

```swift
import Foundation

extension Station {
    /// RTT stations carry latitude and longitude 0 until resolved through `StationDirectory`.
    var hasCoordinate: Bool { latitude != 0 || longitude != 0 }
}

/// Wraps a provider so that stations get real coordinates and station search works. RTT has no
/// search endpoint and its stations have no coordinates (docs/spec.md section 3), so both come from
/// the bundled NaPTAN directory, keyed by CRS. Stations the directory does not know stay as RTT
/// gave them, with `hasCoordinate == false`.
struct DirectoryBackedProvider: TrainDataProvider {
    let base: TrainDataProvider
    let directory: StationDirectory

    func searchStations(query: String) async throws -> [Station] { directory.search(name: query) }

    func departureBoard(station: Station, from: Date, callingAt: Station?) async throws -> [Service] {
        try await base.departureBoard(station: station, from: from, callingAt: callingAt).map(resolved)
    }

    func serviceDetails(serviceId: String, date: Date) async throws -> Service {
        try resolved(await base.serviceDetails(serviceId: serviceId, date: date))
    }

    private func resolve(_ station: Station) -> Station { directory.station(crs: station.id) ?? station }

    private func resolved(_ service: Service) throws -> Service {
        let journey = service.journey
        let stops = journey.stops.map { stop in
            Stop(station: resolve(stop.station), scheduledArrival: stop.scheduledArrival,
                 scheduledDeparture: stop.scheduledDeparture, estimatedArrival: stop.estimatedArrival,
                 estimatedDeparture: stop.estimatedDeparture, isArrivalCancelled: stop.isArrivalCancelled,
                 isDepartureCancelled: stop.isDepartureCancelled, displayAs: stop.displayAs,
                 hasArrived: stop.hasArrived)
        }
        return Service(id: service.id, operatorName: service.operatorName,
                       journey: try Journey(origin: resolve(journey.origin),
                                            destination: resolve(journey.destination), stops: stops))
    }
}
```

- [ ] **Step 4: iOS, run to verify pass**

Same command as Step 2. Expected: `** TEST SUCCEEDED **`. Then run the full suite (`xcodebuild test ...` without `-only-testing`); expected: all tests pass (128 from the baseline plus the new ones).

- [ ] **Step 5: Android, write the failing tests**

In `RttProviderTest.kt`, make `FakeTransport` record the URL (`var lastUrl = ""`, set at the top of `get`), then add:

```kotlin
    private val edinburgh = Station("EDB", "Edinburgh", 55.9521, -3.1894)

    @Test
    fun departureBoard_sendsFilterToWhenCallingAtIsGiven() = runBlocking<Unit> {
        val transport = FakeTransport { HttpResult(204, "") }
        RttProvider("token", transport).departureBoard(waterloo, Instant.parse("2026-09-13T14:00:00Z"), edinburgh)
        assertTrue(transport.lastUrl.contains("/gb-nr/location?"))
        assertTrue(transport.lastUrl.contains("filterTo=EDB"))
        assertTrue(transport.lastUrl.contains("code=WAT"))
    }

    @Test
    fun departureBoard_omitsFilterToByDefault() = runBlocking<Unit> {
        val transport = FakeTransport { HttpResult(204, "") }
        RttProvider("token", transport).departureBoard(waterloo, Instant.parse("2026-09-13T14:00:00Z"))
        assertTrue(!transport.lastUrl.contains("filterTo"))
    }
```

Create `DirectoryBackedProviderTest.kt`:

```kotlin
package com.trainalarm.app.provider

import com.trainalarm.app.model.Journey
import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.model.Stop
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryBackedProviderTest {
    private class RecordingProvider(val service: Service) : TrainDataProvider {
        var lastCallingAt: Station? = null
        override suspend fun searchStations(query: String): List<Station> = throw ProviderError.NotImplemented("fake")
        override suspend fun departureBoard(station: Station, from: Instant, callingAt: Station?): List<Service> {
            lastCallingAt = callingAt
            return listOf(service)
        }
        override suspend fun serviceDetails(serviceId: String, date: LocalDate): Service = service
    }

    private val json = """[{"crs":"EDB","name":"Edinburgh","lat":55.9521,"lon":-3.1894},{"crs":"KGX","name":"London Kings Cross","lat":51.5309,"lon":-0.1233}]"""

    private fun rtt(id: String, name: String) = Station(id, name, 0.0, 0.0)
    private fun stop(s: Station) = Stop(s, null, null, null, null)

    private fun make(): Pair<DirectoryBackedProvider, RecordingProvider> {
        val kgx = rtt("KGX", "London Kings Cross")
        val xyz = rtt("XYZ", "Nowhere")
        val edb = rtt("EDB", "Edinburgh Waverley")
        val service = Service("s1", "LNER", Journey(kgx, edb, listOf(stop(kgx), stop(xyz), stop(edb))))
        val base = RecordingProvider(service)
        return DirectoryBackedProvider(base, StationDirectory.parse(json)) to base
    }

    @Test fun serviceDetails_getsRealCoordinatesForKnownStations() = runBlocking<Unit> {
        val (provider, _) = make()
        val service = provider.serviceDetails("s1", LocalDate.of(2026, 9, 13))
        assertEquals(55.9521, service.journey.destination.latitude, 0.0001)
        assertEquals("Edinburgh", service.journey.destination.name)
        assertEquals(-0.1233, service.journey.stops[0].station.longitude, 0.0001)
    }

    @Test fun unknownStation_keepsItsNameAndHasNoCoordinate() = runBlocking<Unit> {
        val (provider, _) = make()
        val unknown = provider.serviceDetails("s1", LocalDate.of(2026, 9, 13)).journey.stops[1].station
        assertEquals("Nowhere", unknown.name)
        assertFalse(unknown.hasCoordinate)
    }

    @Test fun departureBoard_isResolvedAndPassesCallingAtOn() = runBlocking<Unit> {
        val (provider, base) = make()
        val edb = rtt("EDB", "Edinburgh")
        val board = provider.departureBoard(rtt("KGX", "London Kings Cross"), Instant.parse("2026-09-13T14:00:00Z"), edb)
        assertEquals(edb, base.lastCallingAt)
        assertTrue(board[0].journey.destination.hasCoordinate)
    }

    @Test fun searchStations_answersFromTheDirectory() = runBlocking<Unit> {
        val (provider, _) = make()
        assertEquals(listOf("Edinburgh"), provider.searchStations("edin").map { it.name })
    }
}
```

- [ ] **Step 6: Android, run to verify failure**

```bash
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew testDebugUnitTest --offline --tests "com.trainalarm.app.provider.*" 2>&1 | grep -E "^e: |FAILED|BUILD" | head -4
```
Expected: compile errors (`Too many arguments`, unresolved `DirectoryBackedProvider`).

- [ ] **Step 7: Android, implement**

`TrainDataProvider.kt`, replace the `departureBoard` declaration:

```kotlin
    /**
     * Live/scheduled departures from [station] at or after [from]. With [callingAt] set, only trains
     * that subsequently call at that station are returned.
     */
    suspend fun departureBoard(station: Station, from: Instant, callingAt: Station? = null): List<Service>
```

`RttProvider.kt`, replace the start of `departureBoard` (an override repeats no default):

```kotlin
    override suspend fun departureBoard(station: Station, from: Instant, callingAt: Station?): List<Service> {
        // filterTo: only trains that subsequently call at this location (/gb-nr/location in the RTT spec).
        val query = buildMap {
            put("code", station.id)
            put("timeFrom", from.toString())
            if (callingAt != null) put("filterTo", callingAt.id)
        }
        // 204 is "valid query, no services found": an empty board, not an error.
        val json = getJson("/gb-nr/location", query) ?: return emptyList()
```

`TrackingFakes.kt` line 81: `override suspend fun departureBoard(station: Station, from: Instant, callingAt: Station?): List<Service> =`

Create `DirectoryBackedProvider.kt`:

```kotlin
package com.trainalarm.app.provider

import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import java.time.Instant
import java.time.LocalDate

/** RTT stations carry latitude and longitude 0 until resolved through [StationDirectory]. */
val Station.hasCoordinate: Boolean get() = latitude != 0.0 || longitude != 0.0

/**
 * Wraps a provider so that stations get real coordinates and station search works. RTT has no
 * search endpoint and its stations have no coordinates (docs/spec.md section 3), so both come from
 * the bundled NaPTAN directory, keyed by CRS. Stations the directory does not know stay as RTT
 * gave them, with `hasCoordinate == false`.
 */
class DirectoryBackedProvider(
    private val base: TrainDataProvider,
    private val directory: StationDirectory
) : TrainDataProvider {

    override suspend fun searchStations(query: String): List<Station> = directory.search(query)

    override suspend fun departureBoard(station: Station, from: Instant, callingAt: Station?): List<Service> =
        base.departureBoard(station, from, callingAt).map(::resolved)

    override suspend fun serviceDetails(serviceId: String, date: LocalDate): Service =
        resolved(base.serviceDetails(serviceId, date))

    private fun resolve(station: Station): Station = directory.station(station.id) ?: station

    private fun resolved(service: Service): Service {
        val journey = service.journey
        return service.copy(
            journey = journey.copy(
                origin = resolve(journey.origin),
                destination = resolve(journey.destination),
                stops = journey.stops.map { it.copy(station = resolve(it.station)) }
            )
        )
    }
}
```

- [ ] **Step 8: Android, run to verify pass**

Same command as Step 6, then the full suite: `./gradlew test lint assembleDebug --offline`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add ios android
git commit -m "Feature: Filter the departure board by destination and resolve station coordinates

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Real `TrackerClock` and `LocationSource`

**Files:**
- Create: `ios/Sources/TrainAlarm/Tracking/SystemTrackerClock.swift`, `ios/Sources/TrainAlarm/Tracking/CoreLocationSource.swift`
- Create (tests): `ios/Tests/TrainAlarmTests/Tracking/SystemTrackerClockTests.swift`, `ios/Tests/TrainAlarmTests/Tracking/LocationFixTests.swift`
- Create: `android/app/src/main/java/com/trainalarm/app/tracking/SystemTrackerClock.kt`, `.../tracking/PlatformLocationSource.kt`
- Create (tests): `android/app/src/test/java/com/trainalarm/app/tracking/SystemTrackerClockTest.kt`, `.../tracking/LocationFixesTest.kt`

**Interfaces:**
- Consumes: `TrackerClock` (`now()`, `sleep`), `LocationSource.latestFix()`, `LocationFix(latitude:longitude:timestamp:accuracy:)` from `TrackerInterfaces`.
- Produces: `SystemTrackerClock` (both platforms); `CoreLocationSource` with `start()`, `stop()` (iOS); `PlatformLocationSource(context)` with `start(): Boolean`, `stop()` (Android); `LocationFix(location:)` (iOS) and `LocationFixes.from(latitude, longitude, timeMillis, accuracyMeters)` (Android). An invalid accuracy (negative on iOS, absent on Android) becomes infinity, so the tracker's `maxFixAccuracy` check drops the fix.
- Location is requested while in use only. Callers start these only after the user allows it (Task 11).

- [ ] **Step 1: Write the failing tests**

iOS `SystemTrackerClockTests.swift`:

```swift
import XCTest
@testable import TrainAlarm

final class SystemTrackerClockTests: XCTestCase {
    func testNowIsCloseToTheSystemTime() {
        XCTAssertEqual(SystemTrackerClock().now().timeIntervalSinceNow, 0, accuracy: 1)
    }

    func testSleepWaitsAtLeastTheRequestedTime() async throws {
        let start = Date()
        try await SystemTrackerClock().sleep(for: 0.05)
        XCTAssertGreaterThanOrEqual(Date().timeIntervalSince(start), 0.04)
    }

    func testSleepThrowsCancellationWhenTheTaskIsCancelled() async {
        let task = Task { try await SystemTrackerClock().sleep(for: 30) }
        task.cancel()
        do {
            try await task.value
            XCTFail("sleep should have thrown")
        } catch {
            XCTAssertTrue(error is CancellationError)
        }
    }
}
```

iOS `LocationFixTests.swift`:

```swift
import CoreLocation
import XCTest
@testable import TrainAlarm

final class LocationFixTests: XCTestCase {
    private func location(accuracy: Double) -> CLLocation {
        CLLocation(coordinate: CLLocationCoordinate2D(latitude: 51.5, longitude: -0.12), altitude: 0,
                   horizontalAccuracy: accuracy, verticalAccuracy: 0, timestamp: Date(timeIntervalSince1970: 1_000))
    }

    func testFixCarriesPositionTimeAndAccuracy() {
        let fix = LocationFix(location: location(accuracy: 25))
        XCTAssertEqual(fix, LocationFix(latitude: 51.5, longitude: -0.12, timestamp: Date(timeIntervalSince1970: 1_000), accuracy: 25))
    }

    func testNegativeAccuracyMeansInvalidAndBecomesInfinite() {
        XCTAssertEqual(LocationFix(location: location(accuracy: -1)).accuracy, .infinity)
    }

    func testSourceKeepsTheLatestFixFromTheDelegate() {
        let source = CoreLocationSource()
        XCTAssertNil(source.latestFix())
        source.locationManager(CLLocationManager(), didUpdateLocations: [location(accuracy: 10), location(accuracy: 30)])
        XCTAssertEqual(source.latestFix()?.accuracy, 30)
    }
}
```

Android `SystemTrackerClockTest.kt`:

```kotlin
package com.trainalarm.app.tracking

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTrackerClockTest {
    @Test fun now_isCloseToTheSystemTime() {
        assertTrue(Duration.between(SystemTrackerClock().now(), Instant.now()).abs() < Duration.ofSeconds(1))
    }

    @Test fun sleep_waitsAtLeastTheRequestedTime() = runBlocking<Unit> {
        val start = System.nanoTime()
        SystemTrackerClock().sleep(0.05)
        assertTrue((System.nanoTime() - start) / 1_000_000 >= 40)
    }

    @Test fun sleep_throwsCancellationWhenCancelled() = runBlocking<Unit> {
        var thrown: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                SystemTrackerClock().sleep(30.0)
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }
        job.cancelAndJoin()
        assertTrue(thrown is CancellationException)
    }
}
```

Android `LocationFixesTest.kt`:

```kotlin
package com.trainalarm.app.tracking

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationFixesTest {
    @Test fun fixCarriesPositionTimeAndAccuracy() {
        assertEquals(
            LocationFix(51.5, -0.12, Instant.ofEpochMilli(1_000_000), 25.0),
            LocationFixes.from(51.5, -0.12, 1_000_000, 25f)
        )
    }

    @Test fun absentAccuracyBecomesInfinite() =
        assertEquals(Double.POSITIVE_INFINITY, LocationFixes.from(51.5, -0.12, 0, null).accuracy, 0.0)

    @Test fun negativeAccuracyBecomesInfinite() =
        assertEquals(Double.POSITIVE_INFINITY, LocationFixes.from(51.5, -0.12, 0, -1f).accuracy, 0.0)
}
```

- [ ] **Step 2: Run to verify failure**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/SystemTrackerClockTests -only-testing:TrainAlarmTests/LocationFixTests 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | head -3
cd ../android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew testDebugUnitTest --offline --tests "com.trainalarm.app.tracking.SystemTrackerClockTest" --tests "com.trainalarm.app.tracking.LocationFixesTest" 2>&1 | grep -E "^e: |BUILD" | head -3
```
Expected: unresolved `SystemTrackerClock`, `CoreLocationSource`, `LocationFixes`.

- [ ] **Step 3: Implement**

iOS `SystemTrackerClock.swift`:

```swift
import Foundation

/// The real clock for the tracker. `sleep` throws `CancellationError` when the task is cancelled.
struct SystemTrackerClock: TrackerClock {
    func now() -> Date { Date() }

    func sleep(for seconds: TimeInterval) async throws {
        try await Task.sleep(nanoseconds: UInt64(max(0, seconds) * 1_000_000_000))
    }
}
```

iOS `CoreLocationSource.swift`:

```swift
import CoreLocation
import Foundation

extension LocationFix {
    /// Core Location reports a negative horizontal accuracy for an invalid fix. It becomes infinite
    /// so the tracker's `maxFixAccuracy` check drops it.
    init(location: CLLocation) {
        self.init(latitude: location.coordinate.latitude, longitude: location.coordinate.longitude,
                  timestamp: location.timestamp,
                  accuracy: location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : .infinity)
    }
}

/// Keeps the latest GPS fix for the tracker. Location while in use only; never background location.
final class CoreLocationSource: NSObject, LocationSource, CLLocationManagerDelegate {
    private let manager: CLLocationManager
    private let lock = NSLock()
    private var latest: LocationFix?

    init(manager: CLLocationManager = CLLocationManager()) {
        self.manager = manager
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    /// Call only after the user has allowed location while in use.
    func start() { manager.startUpdatingLocation() }
    func stop() { manager.stopUpdatingLocation() }

    func latestFix() -> LocationFix? {
        lock.lock()
        defer { lock.unlock() }
        return latest
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        lock.lock()
        latest = LocationFix(location: location)
        lock.unlock()
    }
}
```

Android `SystemTrackerClock.kt`:

```kotlin
package com.trainalarm.app.tracking

import java.time.Instant
import kotlinx.coroutines.delay

/** The real clock for the tracker. `sleep` throws `CancellationException` when the coroutine is cancelled. */
class SystemTrackerClock : TrackerClock {
    override fun now(): Instant = Instant.now()

    override suspend fun sleep(seconds: Double) = delay((maxOf(0.0, seconds) * 1000).toLong())
}
```

Android `PlatformLocationSource.kt`:

```kotlin
package com.trainalarm.app.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import java.time.Instant

object LocationFixes {
    /** An absent or negative accuracy becomes infinite so the tracker's `maxFixAccuracy` check drops the fix. */
    fun from(latitude: Double, longitude: Double, timeMillis: Long, accuracyMeters: Float?): LocationFix =
        LocationFix(
            latitude, longitude, Instant.ofEpochMilli(timeMillis),
            accuracyMeters?.takeIf { it >= 0f }?.toDouble() ?: Double.POSITIVE_INFINITY
        )
}

/** Keeps the latest GPS fix for the tracker, using the platform LocationManager (no Play Services). While in use only. */
class PlatformLocationSource(context: Context) : LocationSource, LocationListener {
    private val manager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile private var latest: LocationFix? = null

    /** Call only after the user has allowed location while in use. False if permission or GPS is missing. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean = try {
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000L, 0f, this, Looper.getMainLooper())
        true
    } catch (e: SecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    fun stop() = manager.removeUpdates(this)

    override fun latestFix(): LocationFix? = latest

    override fun onLocationChanged(location: Location) {
        latest = LocationFixes.from(
            location.latitude, location.longitude, location.time,
            if (location.hasAccuracy()) location.accuracy else null
        )
    }

    // These three are only optional from API 30, and minSdk is 29, so they must exist.
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Only called on API 28 and below")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
```

- [ ] **Step 4: Run to verify pass**

Same commands as Step 2. Expected: `** TEST SUCCEEDED **` (6 iOS tests: 3 in each class) and `BUILD SUCCESSFUL`. Then `./gradlew lint --offline`; expected no new errors.

- [ ] **Step 5: Commit**

```bash
git add ios android
git commit -m "Feature: Add the real tracker clock and location source on both platforms

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: `SettingsStore` (theme and default lead time)

**Files:**
- Create: `ios/Sources/TrainAlarm/App/SettingsStore.swift`, `ios/Tests/TrainAlarmTests/App/SettingsStoreTests.swift`
- Create: `android/app/src/main/java/com/trainalarm/app/SettingsStore.kt`, `android/app/src/test/java/com/trainalarm/app/SettingsStoreTest.kt`

**Interfaces:**
- Produces (iOS): `enum ThemeChoice: String, CaseIterable { case system, dark, light }`; `@Observable final class SettingsStore` with `init(defaults: UserDefaults = .standard)`, `private(set) var theme: ThemeChoice`, `private(set) var defaultLeadMinutes: Int?`, `setTheme(_:)`, `setDefaultLeadMinutes(_:)`, `static let leadChoices = [5, 10, 15, 20, 25, 30]`.
- Produces (Android): `enum class ThemeChoice { SYSTEM, DARK, LIGHT }`; `interface KeyValues`; `SharedPrefsKeyValues(prefs)`; `SettingsStore(store: KeyValues)` with `theme: StateFlow<ThemeChoice>`, `defaultLeadMinutes: StateFlow<Int?>`, `setTheme(choice)`, `setDefaultLeadMinutes(minutes)`, `SettingsStore.LEAD_CHOICES`.
- `defaultLeadMinutes` is nil/null until chosen: first run. A value outside the choices is ignored, and a stored invalid value reads as unset.

- [ ] **Step 0 (iOS only): Rename the Stage 2 `Observation` type**

The existing `struct Observation` (`MonitorState.swift`) shadows Apple's `Observation` module, and `@Observable` expands to `Observation.Observable`, so the macro fails to compile anywhere in this app (found in the plan dry run). Rename it to `TickObservation` before using `@Observable`. Android keeps `Observation`; there is no clash there.

```bash
perl -pi -e 's/\bObservation\b/TickObservation/g' ios/Sources/TrainAlarm/Tracking/MonitorState.swift ios/Sources/TrainAlarm/Tracking/JourneyMonitor.swift ios/Sources/TrainAlarm/Tracking/JourneyTracker.swift ios/Tests/TrainAlarmTests/Tracking/MonitorHarness.swift
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | tail -2
cd .. && git add ios && git commit -m "Refactor: Rename iOS Observation to TickObservation

It shadowed Apple's Observation module, which breaks the Observable macro.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```
Expected: `** TEST SUCCEEDED **` and no remaining `Observation` identifiers (`grep -rn "\bObservation\b" ios/Sources ios/Tests` prints nothing except `import Observation`). Never name an app type `Observation`.

- [ ] **Step 1: Write the failing tests**

iOS:

```swift
import XCTest
@testable import TrainAlarm

final class SettingsStoreTests: XCTestCase {
    private var suite = ""
    private var defaults: UserDefaults!

    override func setUp() {
        suite = "settings-test-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
    }

    override func tearDown() { defaults.removePersistentDomain(forName: suite) }

    func testFirstRunHasSystemThemeAndNoLeadTime() {
        let store = SettingsStore(defaults: defaults)
        XCTAssertEqual(store.theme, .system)
        XCTAssertNil(store.defaultLeadMinutes)
    }

    func testThemeAndLeadTimePersistAcrossInstances() {
        let first = SettingsStore(defaults: defaults)
        first.setTheme(.light)
        first.setDefaultLeadMinutes(20)
        let second = SettingsStore(defaults: defaults)
        XCTAssertEqual(second.theme, .light)
        XCTAssertEqual(second.defaultLeadMinutes, 20)
    }

    func testALeadTimeOutsideTheChoicesIsIgnored() {
        let store = SettingsStore(defaults: defaults)
        store.setDefaultLeadMinutes(17)
        XCTAssertNil(store.defaultLeadMinutes)
    }

    func testAnUnknownStoredThemeFallsBackToSystem() {
        defaults.set("neon", forKey: "theme")
        XCTAssertEqual(SettingsStore(defaults: defaults).theme, .system)
    }

    func testAnInvalidStoredLeadTimeReadsAsUnset() {
        defaults.set(99, forKey: "defaultLeadMinutes")
        XCTAssertNil(SettingsStore(defaults: defaults).defaultLeadMinutes)
    }
}
```

Android:

```kotlin
package com.trainalarm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsStoreTest {
    private class MemoryKeyValues : KeyValues {
        val map = mutableMapOf<String, Any>()
        override fun getString(key: String) = map[key] as? String
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getInt(key: String) = map[key] as? Int
        override fun putInt(key: String, value: Int) { map[key] = value }
    }

    @Test fun firstRun_hasSystemThemeAndNoLeadTime() {
        val store = SettingsStore(MemoryKeyValues())
        assertEquals(ThemeChoice.SYSTEM, store.theme.value)
        assertNull(store.defaultLeadMinutes.value)
    }

    @Test fun themeAndLeadTime_persistAcrossInstances() {
        val kv = MemoryKeyValues()
        SettingsStore(kv).apply { setTheme(ThemeChoice.LIGHT); setDefaultLeadMinutes(20) }
        val second = SettingsStore(kv)
        assertEquals(ThemeChoice.LIGHT, second.theme.value)
        assertEquals(20, second.defaultLeadMinutes.value)
    }

    @Test fun aLeadTimeOutsideTheChoices_isIgnored() {
        val store = SettingsStore(MemoryKeyValues())
        store.setDefaultLeadMinutes(17)
        assertNull(store.defaultLeadMinutes.value)
    }

    @Test fun anUnknownStoredTheme_fallsBackToSystem() {
        val kv = MemoryKeyValues().apply { putString("theme", "NEON") }
        assertEquals(ThemeChoice.SYSTEM, SettingsStore(kv).theme.value)
    }

    @Test fun anInvalidStoredLeadTime_readsAsUnset() {
        val kv = MemoryKeyValues().apply { putInt("defaultLeadMinutes", 99) }
        assertNull(SettingsStore(kv).defaultLeadMinutes.value)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/SettingsStoreTests 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | head -3
cd ../android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew testDebugUnitTest --offline --tests "com.trainalarm.app.SettingsStoreTest" 2>&1 | grep -E "^e: |BUILD" | head -3
```
Expected: unresolved `SettingsStore`.

- [ ] **Step 3: Implement**

iOS `SettingsStore.swift`:

```swift
import Foundation
import Observation

enum ThemeChoice: String, CaseIterable { case system, dark, light }

/// User settings, kept in UserDefaults. See spec sections 4 and 5.
@Observable
final class SettingsStore {
    static let leadChoices = [5, 10, 15, 20, 25, 30]

    private(set) var theme: ThemeChoice
    /// nil until the user has chosen a lead time (first run).
    private(set) var defaultLeadMinutes: Int?

    @ObservationIgnored private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        theme = defaults.string(forKey: "theme").flatMap(ThemeChoice.init(rawValue:)) ?? .system
        let stored = defaults.object(forKey: "defaultLeadMinutes") as? Int
        defaultLeadMinutes = stored.flatMap { Self.leadChoices.contains($0) ? $0 : nil }
    }

    func setTheme(_ choice: ThemeChoice) {
        theme = choice
        defaults.set(choice.rawValue, forKey: "theme")
    }

    /// Ignores a value that is not one of `leadChoices`.
    func setDefaultLeadMinutes(_ minutes: Int) {
        guard Self.leadChoices.contains(minutes) else { return }
        defaultLeadMinutes = minutes
        defaults.set(minutes, forKey: "defaultLeadMinutes")
    }
}
```

Android `SettingsStore.kt`:

```kotlin
package com.trainalarm.app

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeChoice { SYSTEM, DARK, LIGHT }

/** The few reads and writes the store needs, so tests can use memory instead of SharedPreferences. */
interface KeyValues {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getInt(key: String): Int?
    fun putInt(key: String, value: Int)
}

class SharedPrefsKeyValues(private val prefs: SharedPreferences) : KeyValues {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun getInt(key: String): Int? = if (prefs.contains(key)) prefs.getInt(key, 0) else null
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
}

/** User settings. See spec sections 4 and 5. */
class SettingsStore(private val store: KeyValues) {
    private val _theme = MutableStateFlow(
        store.getString(THEME)?.let { name -> ThemeChoice.values().firstOrNull { it.name == name } } ?: ThemeChoice.SYSTEM
    )
    val theme: StateFlow<ThemeChoice> = _theme

    /** null until the user has chosen a lead time (first run). */
    private val _defaultLeadMinutes = MutableStateFlow(store.getInt(LEAD)?.takeIf { it in LEAD_CHOICES })
    val defaultLeadMinutes: StateFlow<Int?> = _defaultLeadMinutes

    fun setTheme(choice: ThemeChoice) {
        store.putString(THEME, choice.name)
        _theme.value = choice
    }

    /** Ignores a value that is not one of [LEAD_CHOICES]. */
    fun setDefaultLeadMinutes(minutes: Int) {
        if (minutes !in LEAD_CHOICES) return
        store.putInt(LEAD, minutes)
        _defaultLeadMinutes.value = minutes
    }

    companion object {
        val LEAD_CHOICES = listOf(5, 10, 15, 20, 25, 30)
        private const val THEME = "theme"
        private const val LEAD = "defaultLeadMinutes"
    }
}
```

- [ ] **Step 4: Run to verify pass**

Same commands as Step 2. Expected: `** TEST SUCCEEDED **` (5 iOS tests) and `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add ios android
git commit -m "Feature: Add the settings store on both platforms

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 7: `SessionModel` part 1: picking, confirming, permissions, starting

**Files:**
- Create: `ios/Sources/TrainAlarm/App/Permissions.swift`, `ios/Sources/TrainAlarm/App/SessionTypes.swift`, `ios/Sources/TrainAlarm/App/SessionModel.swift`
- Create (tests): `ios/Tests/TrainAlarmTests/App/SessionModelTests.swift`
- Create: `android/app/src/main/java/com/trainalarm/app/Permissions.kt`, `.../SessionTypes.kt`, `.../SessionModel.kt`
- Create (tests): `android/app/src/test/java/com/trainalarm/app/SessionModelTest.kt`

**Interfaces:**
- Consumes: `TrainDataProvider` with `departureBoard(station:from:callingAt:)` (Task 4), `StationDirectory.search/station`, `SettingsStore` (Task 6), `Station.hasCoordinate` (Task 4), `TrackerClock`, `LocationSource`, `AlarmScheduler`, `JourneyTracker`, `TrackerPlan`, `TrackerConfig`, `Coordinate`.
- Produces: `PermissionKind { alarm, notifications, location, fullScreenIntent }`, `PermissionStatus { granted, denied, notDetermined }`, `PermissionsGateway` (`requiredKinds`, `optionalKinds`, `status(_:)`, `request(_:)`). Required refusals block Start; optional refusals only warn. The real gateways are Task 11.
- Produces: `SessionPhase { selecting, confirming, tracking, ringing, ended(EndReason) }` (the spec's `idle` is the empty `selecting` state), `EndReason { arrived, imOff }`, `BoardState { idle, loading, loaded([Service]), empty, failed(String), rateLimited }`, `ConfirmState(service, arrival, leadMinutes)`.
- Produces: `SessionModel` with state `phase, from, to, board, confirm, blockedPermission, permissionWarnings, trackerConfig`; and `search`, `canLoadBoard`, `selectFrom/selectTo`, `loadBoard(force:)`, `choose(_:)`, `setLead(_:)`, `alarmAt`, `ringsStraightAway`, `canStart`, `start()`, `imOff()`, `returnHome()`, `backToSelecting()`. Constants: `boardCooldown = 20` s, `pollInterval = 60` s (the request-budget stopgap, spec section 7).
- iOS: `@MainActor @Observable`. Android: Compose `mutableStateOf` properties with `private set`, and a `CoroutineScope` for the tracker.

- [ ] **Step 1: iOS, write the failing tests**

```swift
import XCTest
@testable import TrainAlarm

@MainActor
final class SessionModelTests: XCTestCase {
    final class ScriptedProvider: TrainDataProvider {
        var board: Result<[Service], Error> = .success([])
        var details: Result<Service, Error> = .success(ServiceBuilder.happyPath())
        private(set) var boardCalls = 0
        private(set) var lastCallingAt: Station?
        func searchStations(query: String) async throws -> [Station] { [] }
        func departureBoard(station: Station, from: Date, callingAt: Station?) async throws -> [Service] {
            boardCalls += 1
            lastCallingAt = callingAt
            return try board.get()
        }
        func serviceDetails(serviceId: String, date: Date) async throws -> Service { try details.get() }
    }

    final class FakePermissions: PermissionsGateway {
        var requiredKinds: [PermissionKind] = [.alarm]
        var optionalKinds: [PermissionKind] = [.location]
        var statuses: [PermissionKind: PermissionStatus] = [.alarm: .granted, .location: .granted]
        var answers: [PermissionKind: PermissionStatus] = [:]
        private(set) var requested: [PermissionKind] = []
        func status(_ kind: PermissionKind) -> PermissionStatus { statuses[kind] ?? .granted }
        func request(_ kind: PermissionKind) async -> PermissionStatus {
            requested.append(kind)
            return answers[kind] ?? status(kind)
        }
    }

    struct Harness {
        let model: SessionModel
        let provider: ScriptedProvider
        let scheduler: FakeAlarmScheduler
        let clock: FakeClock
        let permissions: FakePermissions
        let settings: SettingsStore
        let wat: Station
        let bsk: Station
    }

    private let directoryJSON = #"[{"crs":"WAT","name":"London Waterloo","lat":51.5031,"lon":-0.1132},{"crs":"WOK","name":"Woking","lat":51.3189,"lon":-0.5569},{"crs":"BSK","name":"Basingstoke","lat":51.2686,"lon":-1.0872}]"#
    private var suite = ""

    override func setUp() { suite = "session-test-\(UUID().uuidString)" }
    override func tearDown() { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }

    private func makeHarness(now: String = "07:00:00") throws -> Harness {
        let directory = try StationDirectory.parse(Data(directoryJSON.utf8))
        let provider = ScriptedProvider()
        provider.board = .success([ServiceBuilder.happyPath()])
        let scheduler = FakeAlarmScheduler()
        let clock = FakeClock(start: ServiceBuilder.time(now))
        let permissions = FakePermissions()
        let settings = SettingsStore(defaults: UserDefaults(suiteName: suite)!)
        let model = SessionModel(provider: provider, scheduler: scheduler, directory: directory,
                                 location: FakeLocationSource(), clock: clock, permissions: permissions, settings: settings)
        return Harness(model: model, provider: provider, scheduler: scheduler, clock: clock, permissions: permissions,
                       settings: settings, wat: directory.station(crs: "WAT")!, bsk: directory.station(crs: "BSK")!)
    }

    /// Picks WAT to BSK, loads the board, and chooses the first train.
    private func confirmed(_ h: Harness, lead: Int? = 15) async {
        h.model.selectFrom(h.wat)
        h.model.selectTo(h.bsk)
        await h.model.loadBoard()
        guard case let .loaded(services) = h.model.board else { return XCTFail("board not loaded") }
        await h.model.choose(services[0])
        if let lead { h.model.setLead(lead) }
    }

    func testSearchUsesTheDirectory() throws {
        XCTAssertEqual(try makeHarness().model.search("woki").map(\.id), ["WOK"])
    }

    func testTheBoardNeedsTwoDifferentStations() async throws {
        let h = try makeHarness()
        h.model.selectFrom(h.wat)
        XCTAssertFalse(h.model.canLoadBoard)
        h.model.selectTo(h.wat)
        XCTAssertFalse(h.model.canLoadBoard)
        await h.model.loadBoard()
        XCTAssertEqual(h.model.board, .idle)
        XCTAssertEqual(h.provider.boardCalls, 0)
    }

    func testTheBoardIsFilteredToTrainsCallingAtTheDestination() async throws {
        let h = try makeHarness()
        h.model.selectFrom(h.wat)
        h.model.selectTo(h.bsk)
        await h.model.loadBoard()
        XCTAssertEqual(h.provider.lastCallingAt?.id, "BSK")
        if case .loaded(let services) = h.model.board { XCTAssertEqual(services.count, 1) } else { XCTFail("not loaded") }
    }

    func testAnEmptyBoardIsEmptyNotAnError() async throws {
        let h = try makeHarness()
        h.provider.board = .success([])
        h.model.selectFrom(h.wat)
        h.model.selectTo(h.bsk)
        await h.model.loadBoard()
        XCTAssertEqual(h.model.board, .empty)
    }

    func testHttp429ShowsTheRateLimitedState() async throws {
        let h = try makeHarness()
        h.provider.board = .failure(ProviderError.http(429))
        h.model.selectFrom(h.wat)
        h.model.selectTo(h.bsk)
        await h.model.loadBoard()
        XCTAssertEqual(h.model.board, .rateLimited)
    }

    func testAnotherErrorShowsAMessage() async throws {
        let h = try makeHarness()
        h.provider.board = .failure(ProviderError.network("offline"))
        h.model.selectFrom(h.wat)
        h.model.selectTo(h.bsk)
        await h.model.loadBoard()
        XCTAssertEqual(h.model.board, .failed("Can't reach the train data. Check your connection and try again."))
    }

    func testRefreshingTheBoardTooSoonIsIgnoredUnlessForced() async throws {
        let h = try makeHarness()
        h.model.selectFrom(h.wat)
        h.model.selectTo(h.bsk)
        await h.model.loadBoard()
        await h.model.loadBoard()
        XCTAssertEqual(h.provider.boardCalls, 1)
        h.clock.tick(advancing: 21)
        await h.model.loadBoard()
        XCTAssertEqual(h.provider.boardCalls, 2)
        await h.model.loadBoard(force: true)
        XCTAssertEqual(h.provider.boardCalls, 3)
    }

    func testChoosingATrainFindsTheArrivalAtTheDestination() async throws {
        let h = try makeHarness()
        await confirmed(h, lead: nil)
        XCTAssertEqual(h.model.phase, .confirming)
        XCTAssertEqual(h.model.confirm?.arrival, ServiceBuilder.time("08:52:00"))
    }

    func testATrainThatDoesNotCallAtTheDestinationCannotStart() async throws {
        let h = try makeHarness()
        h.provider.details = .success(ServiceBuilder.service(stops: [ServiceBuilder.stop("WAT"), ServiceBuilder.stop("WOK", scheduledArrival: "08:23:00")]))
        await confirmed(h)
        XCTAssertNil(h.model.confirm?.arrival)
        XCTAssertFalse(h.model.canStart)
    }

    func testFirstRunHasNoLeadTimeAndCannotStartUntilOneIsChosen() async throws {
        let h = try makeHarness()
        await confirmed(h, lead: nil)
        XCTAssertNil(h.model.confirm?.leadMinutes)
        XCTAssertFalse(h.model.canStart)
        h.model.setLead(15)
        XCTAssertTrue(h.model.canStart)
        XCTAssertEqual(h.model.alarmAt, ServiceBuilder.time("08:37:00"))
    }

    func testASavedDefaultLeadTimePrefillsTheChoice() async throws {
        let h = try makeHarness()
        h.settings.setDefaultLeadMinutes(10)
        await confirmed(h, lead: nil)
        XCTAssertEqual(h.model.confirm?.leadMinutes, 10)
    }

    func testAnArrivalSoonerThanTheLeadTimeRingsStraightAway() async throws {
        let h = try makeHarness(now: "08:50:00")
        await confirmed(h, lead: 10)
        XCTAssertTrue(h.model.ringsStraightAway)
    }

    func testARefusedAlarmPermissionBlocksStart() async throws {
        let h = try makeHarness()
        h.permissions.statuses[.alarm] = .denied
        await confirmed(h)
        await h.model.start()
        XCTAssertEqual(h.model.phase, .confirming)
        XCTAssertEqual(h.model.blockedPermission, .alarm)
        XCTAssertEqual(h.scheduler.attempts, [])
        XCTAssertEqual(h.permissions.requested, [], "a denied permission is never re-prompted automatically")
    }

    func testARefusedLocationPermissionIsAWarningNotABlock() async throws {
        let h = try makeHarness()
        h.permissions.statuses[.location] = .notDetermined
        h.permissions.answers[.location] = .denied
        await confirmed(h)
        await h.model.start()
        XCTAssertEqual(h.model.phase, .tracking)
        XCTAssertEqual(h.model.permissionWarnings, [.location])
        await h.model.imOff()
    }

    func testStartSchedulesTheTimetableAlarmAndSavesTheLeadTime() async throws {
        let h = try makeHarness()
        await confirmed(h, lead: 15)
        await h.model.start()
        await h.clock.waitForSleepCount(1)
        XCTAssertEqual(h.model.phase, .tracking)
        XCTAssertEqual(h.scheduler.scheduled.first, ServiceBuilder.time("08:37:00"))
        XCTAssertEqual(h.settings.defaultLeadMinutes, 15)
        XCTAssertEqual(h.model.trackerConfig?.pollInterval, 60)
        XCTAssertEqual(h.model.trackerConfig?.leadTime, 900)
        await h.model.imOff()
    }

    func testASecondStartWhileAPermissionPromptIsOpenDoesNothing() async throws {
        let h = try makeHarness()
        h.permissions.statuses[.alarm] = .notDetermined
        h.permissions.answers[.alarm] = .granted
        await confirmed(h, lead: 15)
        async let first: Void = h.model.start()
        async let second: Void = h.model.start()
        _ = await (first, second)
        await h.clock.waitForSleepCount(1)
        XCTAssertEqual(h.scheduler.attempts.count, 1)
        await h.model.imOff()
    }

    func testImOffStopsTheTrackerCancelsTheAlarmAndEnds() async throws {
        let h = try makeHarness()
        await confirmed(h)
        await h.model.start()
        await h.clock.waitForSleepCount(1)
        await h.model.imOff()
        XCTAssertEqual(h.model.phase, .ended(.imOff))
        XCTAssertEqual(h.scheduler.cancelCount, 1)
        h.model.returnHome()
        XCTAssertEqual(h.model.phase, .selecting)
        XCTAssertEqual(h.model.board, .idle)
    }
}
```

- [ ] **Step 2: iOS, run to verify failure**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/SessionModelTests 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | head -3
```
Expected: `cannot find 'SessionModel' in scope`.

- [ ] **Step 3: iOS, implement**

`Permissions.swift`:

```swift
import Foundation

enum PermissionKind: Equatable, Hashable { case alarm, notifications, location, fullScreenIntent }
enum PermissionStatus: Equatable { case granted, denied, notDetermined }

/// What the app asks the OS for. Real implementations are in Task 11; tests use a fake.
protocol PermissionsGateway {
    /// Refusing any of these blocks Start.
    var requiredKinds: [PermissionKind] { get }
    /// Refusing any of these only shows a warning.
    var optionalKinds: [PermissionKind] { get }
    func status(_ kind: PermissionKind) -> PermissionStatus
    /// Shows the system prompt when the status is `notDetermined`; returns the resulting status.
    func request(_ kind: PermissionKind) async -> PermissionStatus
}
```

`SessionTypes.swift`:

```swift
import Foundation

enum EndReason: Equatable { case arrived, imOff }

enum SessionPhase: Equatable { case selecting, confirming, tracking, ringing, ended(EndReason) }

enum BoardState: Equatable {
    case idle, loading
    case loaded([Service])
    case empty
    case failed(String)
    case rateLimited
}

struct ConfirmState: Equatable {
    let service: Service
    /// Best arrival at the destination after the boarding stop; nil if the train does not call there.
    let arrival: Date?
    var leadMinutes: Int?
}
```

`SessionModel.swift`:

```swift
import Foundation
import Observation

/// The one holder of session state. It owns the tracker and shows what the screens need; it
/// holds no tracking logic (that is `JourneyMonitor`). See spec section 6.
@MainActor @Observable
final class SessionModel {
    static let boardCooldown: TimeInterval = 20
    /// Polling every 45 s would use about 80 of RTT's 100 requests an hour. Stopgap until a proxy exists.
    static let pollInterval: TimeInterval = 60

    private(set) var phase: SessionPhase = .selecting
    private(set) var from: Station?
    private(set) var to: Station?
    private(set) var board: BoardState = .idle
    private(set) var confirm: ConfirmState?
    private(set) var blockedPermission: PermissionKind?
    private(set) var permissionWarnings: [PermissionKind] = []
    private(set) var trackerConfig: TrackerConfig?

    @ObservationIgnored private let provider: TrainDataProvider
    @ObservationIgnored private let scheduler: AlarmScheduler
    @ObservationIgnored private let directory: StationDirectory
    @ObservationIgnored private let location: LocationSource
    @ObservationIgnored private let clock: TrackerClock
    @ObservationIgnored private let permissions: PermissionsGateway
    @ObservationIgnored private let settings: SettingsStore
    @ObservationIgnored private var lastBoardLoad: Date?
    @ObservationIgnored private var tracker: JourneyTracker?

    init(provider: TrainDataProvider, scheduler: AlarmScheduler, directory: StationDirectory,
         location: LocationSource, clock: TrackerClock, permissions: PermissionsGateway, settings: SettingsStore) {
        self.provider = provider
        self.scheduler = scheduler
        self.directory = directory
        self.location = location
        self.clock = clock
        self.permissions = permissions
        self.settings = settings
    }

    // MARK: Picking

    func search(_ query: String) -> [Station] { directory.search(name: query) }

    var canLoadBoard: Bool {
        guard let from, let to else { return false }
        return from.id != to.id
    }

    func selectFrom(_ station: Station?) { guard isEditable else { return }; from = station; resetBoard() }
    func selectTo(_ station: Station?) { guard isEditable else { return }; to = station; resetBoard() }

    private var isEditable: Bool { phase == .selecting || phase == .confirming }

    private func resetBoard() {
        board = .idle
        lastBoardLoad = nil
        confirm = nil
        phase = .selecting
    }

    /// Loads the departures from `from` that call at `to`. Ignored within `boardCooldown` of the last
    /// load unless `force`, to protect the request budget.
    func loadBoard(force: Bool = false) async {
        guard canLoadBoard, let from, let to else { return }
        let now = clock.now()
        if !force, let last = lastBoardLoad, now.timeIntervalSince(last) < Self.boardCooldown { return }
        lastBoardLoad = now
        board = .loading
        do {
            let services = try await provider.departureBoard(station: from, from: now, callingAt: to)
            board = services.isEmpty ? .empty : .loaded(services)
        } catch is CancellationError {
            board = .idle
        } catch ProviderError.http(429) {
            board = .rateLimited
        } catch {
            board = .failed(Self.describe(error))
        }
    }

    // MARK: Confirming

    /// Fetches the tapped train's full stops once, to find the arrival at the destination.
    func choose(_ service: Service) async {
        guard let from, let to else { return }
        do {
            let details = try await provider.serviceDetails(serviceId: service.id, date: clock.now())
            confirm = ConfirmState(service: details, arrival: Self.arrival(of: details, from: from.id, to: to.id),
                                   leadMinutes: settings.defaultLeadMinutes)
            blockedPermission = nil
            permissionWarnings = []
            phase = .confirming
        } catch is CancellationError {
        } catch {
            board = .failed(Self.describe(error))
        }
    }

    func backToSelecting() {
        confirm = nil
        blockedPermission = nil
        permissionWarnings = []
        phase = .selecting
    }

    func setLead(_ minutes: Int) {
        guard SettingsStore.leadChoices.contains(minutes) else { return }
        confirm?.leadMinutes = minutes
    }

    var alarmAt: Date? {
        guard let confirm, let arrival = confirm.arrival, let lead = confirm.leadMinutes else { return nil }
        return arrival.addingTimeInterval(-Double(lead) * 60)
    }

    /// The scheduler fires at once when the alarm time has passed; the confirm sheet says so.
    var ringsStraightAway: Bool {
        guard let alarmAt else { return false }
        return alarmAt <= clock.now()
    }

    /// True from the moment `start()` begins until it returns, so a second tap while a permission
    /// prompt is open cannot start a second tracker.
    private(set) var isStarting = false

    var canStart: Bool { phase == .confirming && alarmAt != nil && !isStarting }

    // MARK: Starting and ending

    func start() async {
        guard canStart, let confirm, let from, let to, let lead = confirm.leadMinutes else { return }
        isStarting = true
        defer { isStarting = false }
        blockedPermission = nil
        for kind in permissions.requiredKinds {
            var status = permissions.status(kind)
            if status == .notDetermined { status = await permissions.request(kind) }
            if status != .granted { blockedPermission = kind; return }
        }
        var warnings: [PermissionKind] = []
        for kind in permissions.optionalKinds {
            var status = permissions.status(kind)
            if status == .notDetermined { status = await permissions.request(kind) }
            if status != .granted { warnings.append(kind) }
        }
        permissionWarnings = warnings
        let config = TrackerConfig(leadTime: Double(lead) * 60, pollInterval: Self.pollInterval)
        trackerConfig = config
        let plan = TrackerPlan(
            boardingId: from.id, destinationId: to.id,
            destinationCoordinate: to.hasCoordinate ? Coordinate(latitude: to.latitude, longitude: to.longitude) : nil,
            config: config)
        let tracker = JourneyTracker(plan: plan, snapshot: confirm.service, provider: provider,
                                     locationSource: location,
                                     scheduler: ReportingScheduler(base: scheduler, onScheduled: { [weak self] in self?.alarmScheduled($0) }),
                                     clock: clock)
        self.tracker = tracker
        settings.setDefaultLeadMinutes(lead)
        phase = .tracking
        await tracker.start()
    }

    /// Stops the tracker, cancels the alarm and ends the session.
    func imOff() async {
        await tracker?.stop()
        tracker = nil
        phase = .ended(.imOff)
    }

    /// Back to the empty picker after the session has ended.
    func returnHome() {
        board = .idle
        lastBoardLoad = nil
        confirm = nil
        blockedPermission = nil
        permissionWarnings = []
        phase = .selecting
    }

    // MARK: Helpers

    static func arrival(of service: Service, from fromId: String, to toId: String) -> Date? {
        let stops = service.journey.stops
        guard let boardIndex = stops.firstIndex(where: { $0.station.id == fromId }) else { return nil }
        return stops.dropFirst(boardIndex + 1).first { $0.station.id == toId }?.bestArrival
    }

    private static func describe(_ error: Error) -> String {
        switch error as? ProviderError {
        case .network(_)?: return "Can't reach the train data. Check your connection and try again."
        case .http(let code)?: return "The train data service returned an error (\(code)). Try again in a moment."
        case .malformed(_)?: return "The train data was not in the expected form."
        default: return "Something went wrong loading trains. Try again."
        }
    }
}
```

- [ ] **Step 4: iOS, run to verify pass**

Same command as Step 2. Expected: `** TEST SUCCEEDED **`, 17 tests. If `testStartSchedulesTheTimetableAlarm...` reports a different first scheduled time, print `h.scheduler.scheduled` and check `JourneyMonitor.start` before changing the expectation; do not change the model to fit a guess.

- [ ] **Step 5: Android, write the failing tests**

```kotlin
package com.trainalarm.app

import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.provider.ProviderError
import com.trainalarm.app.provider.StationDirectory
import com.trainalarm.app.provider.TrainDataProvider
import com.trainalarm.app.tracking.FakeAlarmScheduler
import com.trainalarm.app.tracking.FakeClock
import com.trainalarm.app.tracking.FakeLocationSource
import com.trainalarm.app.tracking.ServiceBuilder
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionModelTest {
    private class ScriptedProvider : TrainDataProvider {
        var board: () -> List<Service> = { listOf(ServiceBuilder.happyPath()) }
        var details: () -> Service = { ServiceBuilder.happyPath() }
        var boardCalls = 0
        var lastCallingAt: Station? = null
        override suspend fun searchStations(query: String): List<Station> = emptyList()
        override suspend fun departureBoard(station: Station, from: Instant, callingAt: Station?): List<Service> {
            boardCalls += 1
            lastCallingAt = callingAt
            return board()
        }
        override suspend fun serviceDetails(serviceId: String, date: LocalDate): Service = details()
    }

    private class FakePermissions : PermissionsGateway {
        override var requiredKinds = listOf(PermissionKind.ALARM)
        override var optionalKinds = listOf(PermissionKind.LOCATION)
        val statuses = mutableMapOf(PermissionKind.ALARM to PermissionStatus.GRANTED, PermissionKind.LOCATION to PermissionStatus.GRANTED)
        val answers = mutableMapOf<PermissionKind, PermissionStatus>()
        val requested = mutableListOf<PermissionKind>()
        override fun status(kind: PermissionKind) = statuses[kind] ?: PermissionStatus.GRANTED
        override suspend fun request(kind: PermissionKind): PermissionStatus {
            kotlinx.coroutines.yield()   // a real prompt suspends; let a second caller run meanwhile
            requested += kind
            return answers[kind] ?: status(kind)
        }
    }

    private class MemoryKeyValues : KeyValues {
        val map = mutableMapOf<String, Any>()
        override fun getString(key: String) = map[key] as? String
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getInt(key: String) = map[key] as? Int
        override fun putInt(key: String, value: Int) { map[key] = value }
    }

    private class Harness(scope: CoroutineScope, now: String) {
        val directory = StationDirectory.parse(
            """[{"crs":"WAT","name":"London Waterloo","lat":51.5031,"lon":-0.1132},{"crs":"WOK","name":"Woking","lat":51.3189,"lon":-0.5569},{"crs":"BSK","name":"Basingstoke","lat":51.2686,"lon":-1.0872}]"""
        )
        val provider = ScriptedProvider()
        val scheduler = FakeAlarmScheduler()
        val clock = FakeClock(ServiceBuilder.time(now))
        val permissions = FakePermissions()
        val settings = SettingsStore(MemoryKeyValues())
        val model = SessionModel(provider, scheduler, directory, FakeLocationSource(), clock, permissions, settings, scope)
        val wat = directory.station("WAT")!!
        val bsk = directory.station("BSK")!!
    }

    private fun run(now: String = "07:00:00", block: suspend Harness.() -> Unit) = runBlocking<Unit> { Harness(this, now).block() }

    /** Picks WAT to BSK, loads the board, and chooses the first train. */
    private suspend fun Harness.confirmed(lead: Int? = 15) {
        model.selectFrom(wat)
        model.selectTo(bsk)
        model.loadBoard()
        val loaded = model.board as BoardState.Loaded
        model.choose(loaded.services[0])
        if (lead != null) model.setLead(lead)
    }

    @Test fun search_usesTheDirectory() = run { assertEquals(listOf("WOK"), model.search("woki").map { it.id }) }

    @Test fun theBoardNeedsTwoDifferentStations() = run {
        model.selectFrom(wat)
        assertFalse(model.canLoadBoard)
        model.selectTo(wat)
        assertFalse(model.canLoadBoard)
        model.loadBoard()
        assertEquals(BoardState.Idle, model.board)
        assertEquals(0, provider.boardCalls)
    }

    @Test fun theBoardIsFilteredToTrainsCallingAtTheDestination() = run {
        model.selectFrom(wat)
        model.selectTo(bsk)
        model.loadBoard()
        assertEquals("BSK", provider.lastCallingAt?.id)
        assertEquals(1, (model.board as BoardState.Loaded).services.size)
    }

    @Test fun anEmptyBoardIsEmptyNotAnError() = run {
        provider.board = { emptyList() }
        model.selectFrom(wat)
        model.selectTo(bsk)
        model.loadBoard()
        assertEquals(BoardState.Empty, model.board)
    }

    @Test fun http429ShowsTheRateLimitedState() = run {
        provider.board = { throw ProviderError.Http(429) }
        model.selectFrom(wat)
        model.selectTo(bsk)
        model.loadBoard()
        assertEquals(BoardState.RateLimited, model.board)
    }

    @Test fun anotherErrorShowsAMessage() = run {
        provider.board = { throw ProviderError.Network("offline") }
        model.selectFrom(wat)
        model.selectTo(bsk)
        model.loadBoard()
        assertEquals(BoardState.Failed("Can't reach the train data. Check your connection and try again."), model.board)
    }

    @Test fun refreshingTheBoardTooSoonIsIgnoredUnlessForced() = run {
        model.selectFrom(wat)
        model.selectTo(bsk)
        model.loadBoard()
        model.loadBoard()
        assertEquals(1, provider.boardCalls)
        clock.tick(21.0)
        model.loadBoard()
        assertEquals(2, provider.boardCalls)
        model.loadBoard(force = true)
        assertEquals(3, provider.boardCalls)
    }

    @Test fun choosingATrainFindsTheArrivalAtTheDestination() = run {
        confirmed(lead = null)
        assertEquals(SessionPhase.Confirming, model.phase)
        assertEquals(ServiceBuilder.time("08:52:00"), model.confirm?.arrival)
    }

    @Test fun aTrainThatDoesNotCallAtTheDestinationCannotStart() = run {
        provider.details = { ServiceBuilder.service(listOf(ServiceBuilder.stop("WAT"), ServiceBuilder.stop("WOK", scheduledArrival = "08:23:00"))) }
        confirmed()
        assertNull(model.confirm?.arrival)
        assertFalse(model.canStart)
    }

    @Test fun firstRunHasNoLeadTimeAndCannotStartUntilOneIsChosen() = run {
        confirmed(lead = null)
        assertNull(model.confirm?.leadMinutes)
        assertFalse(model.canStart)
        model.setLead(15)
        assertTrue(model.canStart)
        assertEquals(ServiceBuilder.time("08:37:00"), model.alarmAt)
    }

    @Test fun aSavedDefaultLeadTimePrefillsTheChoice() = run {
        settings.setDefaultLeadMinutes(10)
        confirmed(lead = null)
        assertEquals(10, model.confirm?.leadMinutes)
    }

    @Test fun anArrivalSoonerThanTheLeadTimeRingsStraightAway() = run(now = "08:50:00") {
        confirmed(lead = 10)
        assertTrue(model.ringsStraightAway)
    }

    @Test fun aRefusedAlarmPermissionBlocksStart() = run {
        permissions.statuses[PermissionKind.ALARM] = PermissionStatus.DENIED
        confirmed()
        model.start()
        assertEquals(SessionPhase.Confirming, model.phase)
        assertEquals(PermissionKind.ALARM, model.blockedPermission)
        assertEquals(emptyList<Instant>(), scheduler.attempts)
        assertEquals(emptyList<PermissionKind>(), permissions.requested)
    }

    @Test fun aRefusedLocationPermissionIsAWarningNotABlock() = run {
        permissions.statuses[PermissionKind.LOCATION] = PermissionStatus.NOT_DETERMINED
        permissions.answers[PermissionKind.LOCATION] = PermissionStatus.DENIED
        confirmed()
        model.start()
        assertEquals(SessionPhase.Tracking, model.phase)
        assertEquals(listOf(PermissionKind.LOCATION), model.permissionWarnings)
        model.imOff()
    }

    @Test fun startSchedulesTheTimetableAlarmAndSavesTheLeadTime() = run {
        confirmed(lead = 15)
        model.start()
        clock.awaitSleepCount(1)
        assertEquals(SessionPhase.Tracking, model.phase)
        assertEquals(ServiceBuilder.time("08:37:00"), scheduler.scheduled.first())
        assertEquals(15, settings.defaultLeadMinutes.value)
        assertEquals(60.0, model.trackerConfig?.pollInterval)
        assertEquals(900.0, model.trackerConfig?.leadTime)
        model.imOff()
    }

    @Test fun aSecondStartWhileAPermissionPromptIsOpenDoesNothing() = run {
        permissions.statuses[PermissionKind.ALARM] = PermissionStatus.NOT_DETERMINED
        permissions.answers[PermissionKind.ALARM] = PermissionStatus.GRANTED
        confirmed(lead = 15)
        coroutineScope {
            launch { model.start() }
            launch { model.start() }
        }
        clock.awaitSleepCount(1)
        assertEquals(1, scheduler.attempts.size)
        model.imOff()
    }

    @Test fun imOffStopsTheTrackerCancelsTheAlarmAndEnds() = run {
        confirmed()
        model.start()
        clock.awaitSleepCount(1)
        model.imOff()
        assertEquals(SessionPhase.Ended(EndReason.IM_OFF), model.phase)
        assertEquals(1, scheduler.cancelCount)
        model.returnHome()
        assertEquals(SessionPhase.Selecting, model.phase)
        assertEquals(BoardState.Idle, model.board)
    }
}
```

- [ ] **Step 6: Android, run to verify failure**

```bash
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew testDebugUnitTest --offline --tests "com.trainalarm.app.SessionModelTest" 2>&1 | grep -E "^e: |BUILD" | head -3
```
Expected: unresolved `SessionModel`, `PermissionsGateway`, and others.

- [ ] **Step 7: Android, implement**

`Permissions.kt`:

```kotlin
package com.trainalarm.app

enum class PermissionKind { ALARM, NOTIFICATIONS, LOCATION, FULL_SCREEN_INTENT }
enum class PermissionStatus { GRANTED, DENIED, NOT_DETERMINED }

/** What the app asks the OS for. Real implementations are in Task 11; tests use a fake. */
interface PermissionsGateway {
    /** Refusing any of these blocks Start. */
    val requiredKinds: List<PermissionKind>

    /** Refusing any of these only shows a warning. */
    val optionalKinds: List<PermissionKind>

    fun status(kind: PermissionKind): PermissionStatus

    /** Shows the system prompt when the status is NOT_DETERMINED; returns the resulting status. */
    suspend fun request(kind: PermissionKind): PermissionStatus
}
```

`SessionTypes.kt`:

```kotlin
package com.trainalarm.app

import com.trainalarm.app.model.Service
import java.time.Instant

enum class EndReason { ARRIVED, IM_OFF }

sealed interface SessionPhase {
    data object Selecting : SessionPhase
    data object Confirming : SessionPhase
    data object Tracking : SessionPhase
    data object Ringing : SessionPhase
    data class Ended(val reason: EndReason) : SessionPhase
}

sealed interface BoardState {
    data object Idle : BoardState
    data object Loading : BoardState
    data class Loaded(val services: List<Service>) : BoardState
    data object Empty : BoardState
    data class Failed(val message: String) : BoardState
    data object RateLimited : BoardState
}

/** [arrival] is the best arrival at the destination after the boarding stop; null if the train does not call there. */
data class ConfirmState(val service: Service, val arrival: Instant?, val leadMinutes: Int?)
```

`SessionModel.kt`:

```kotlin
package com.trainalarm.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.trainalarm.app.alarm.AlarmScheduler
import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.provider.ProviderError
import com.trainalarm.app.provider.StationDirectory
import com.trainalarm.app.provider.TrainDataProvider
import com.trainalarm.app.provider.hasCoordinate
import com.trainalarm.app.tracking.Coordinate
import com.trainalarm.app.tracking.JourneyTracker
import com.trainalarm.app.tracking.LocationSource
import com.trainalarm.app.tracking.TrackerClock
import com.trainalarm.app.tracking.TrackerConfig
import com.trainalarm.app.tracking.TrackerPlan
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/**
 * The one holder of session state. It owns the tracker and shows what the screens need; it holds
 * no tracking logic (that is JourneyMonitor). See spec section 6.
 */
class SessionModel(
    private val provider: TrainDataProvider,
    private val scheduler: AlarmScheduler,
    private val directory: StationDirectory,
    private val location: LocationSource,
    private val clock: TrackerClock,
    private val permissions: PermissionsGateway,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val onAlarmError: (String, Throwable) -> Unit = { _, _ -> }
) {
    var phase: SessionPhase by mutableStateOf(SessionPhase.Selecting)
        private set
    var from: Station? by mutableStateOf(null)
        private set
    var to: Station? by mutableStateOf(null)
        private set
    var board: BoardState by mutableStateOf(BoardState.Idle)
        private set
    var confirm: ConfirmState? by mutableStateOf(null)
        private set
    var blockedPermission: PermissionKind? by mutableStateOf(null)
        private set
    var permissionWarnings: List<PermissionKind> by mutableStateOf(emptyList())
        private set
    var trackerConfig: TrackerConfig? by mutableStateOf(null)
        private set

    private var lastBoardLoad: Instant? = null
    private var tracker: JourneyTracker? = null

    // Picking

    fun search(query: String): List<Station> = directory.search(query)

    val canLoadBoard: Boolean
        get() {
            val f = from
            val t = to
            return f != null && t != null && f.id != t.id
        }

    private val isEditable: Boolean get() = phase == SessionPhase.Selecting || phase == SessionPhase.Confirming

    fun selectFrom(station: Station?) { if (isEditable) { from = station; resetBoard() } }
    fun selectTo(station: Station?) { if (isEditable) { to = station; resetBoard() } }

    private fun resetBoard() {
        board = BoardState.Idle
        lastBoardLoad = null
        confirm = null
        phase = SessionPhase.Selecting
    }

    /**
     * Loads the departures from `from` that call at `to`. Ignored within [BOARD_COOLDOWN_SECONDS] of
     * the last load unless [force], to protect the request budget.
     */
    suspend fun loadBoard(force: Boolean = false) {
        val f = from ?: return
        val t = to ?: return
        if (!canLoadBoard) return
        val now = clock.now()
        val last = lastBoardLoad
        if (!force && last != null && Duration.between(last, now).seconds < BOARD_COOLDOWN_SECONDS) return
        lastBoardLoad = now
        board = BoardState.Loading
        board = try {
            val services = provider.departureBoard(f, now, t)
            if (services.isEmpty()) BoardState.Empty else BoardState.Loaded(services)
        } catch (e: CancellationException) {
            board = BoardState.Idle
            throw e
        } catch (e: ProviderError.Http) {
            if (e.code == 429) BoardState.RateLimited else BoardState.Failed(describe(e))
        } catch (e: ProviderError) {
            BoardState.Failed(describe(e))
        }
    }

    // Confirming

    /** Fetches the tapped train's full stops once, to find the arrival at the destination. */
    suspend fun choose(service: Service) {
        val f = from ?: return
        val t = to ?: return
        try {
            val details = provider.serviceDetails(service.id, java.time.LocalDate.now(TimeText.london))
            confirm = ConfirmState(details, arrival(details, f.id, t.id), settings.defaultLeadMinutes.value)
            blockedPermission = null
            permissionWarnings = emptyList()
            phase = SessionPhase.Confirming
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderError) {
            board = BoardState.Failed(describe(e))
        }
    }

    fun backToSelecting() {
        confirm = null
        blockedPermission = null
        permissionWarnings = emptyList()
        phase = SessionPhase.Selecting
    }

    fun setLead(minutes: Int) {
        if (minutes !in SettingsStore.LEAD_CHOICES) return
        confirm = confirm?.copy(leadMinutes = minutes)
    }

    val alarmAt: Instant?
        get() {
            val c = confirm ?: return null
            val arrival = c.arrival ?: return null
            val lead = c.leadMinutes ?: return null
            return arrival.minusSeconds(lead * 60L)
        }

    /** The scheduler fires at once when the alarm time has passed; the confirm sheet says so. */
    val ringsStraightAway: Boolean
        get() = alarmAt?.let { !it.isAfter(clock.now()) } ?: false

    /** True from the moment `start()` begins until it returns, so a second tap during a permission prompt does nothing. */
    private var isStarting = false

    val canStart: Boolean get() = phase == SessionPhase.Confirming && alarmAt != null && !isStarting

    // Starting and ending

    suspend fun start() {
        if (!canStart) return
        isStarting = true
        try { begin() } finally { isStarting = false }
    }

    private suspend fun begin() {
        val c = confirm ?: return
        val f = from ?: return
        val t = to ?: return
        val lead = c.leadMinutes ?: return
        blockedPermission = null
        for (kind in permissions.requiredKinds) {
            var status = permissions.status(kind)
            if (status == PermissionStatus.NOT_DETERMINED) status = permissions.request(kind)
            if (status != PermissionStatus.GRANTED) { blockedPermission = kind; return }
        }
        val warnings = mutableListOf<PermissionKind>()
        for (kind in permissions.optionalKinds) {
            var status = permissions.status(kind)
            if (status == PermissionStatus.NOT_DETERMINED) status = permissions.request(kind)
            if (status != PermissionStatus.GRANTED) warnings += kind
        }
        permissionWarnings = warnings
        val config = TrackerConfig(leadTime = lead * 60.0, pollInterval = POLL_INTERVAL_SECONDS)
        trackerConfig = config
        val plan = TrackerPlan(
            boardingId = f.id,
            destinationId = t.id,
            destinationCoordinate = if (t.hasCoordinate) Coordinate(t.latitude, t.longitude) else null,
            config = config
        )
        val reporting = ReportingScheduler(scheduler) { fireAt -> alarmScheduled(fireAt) }
        val newTracker = JourneyTracker(plan, c.service, provider, location, reporting, clock, onAlarmError)
        tracker = newTracker
        settings.setDefaultLeadMinutes(lead)
        phase = SessionPhase.Tracking
        newTracker.start(scope)
    }

    /** Stops the tracker, cancels the alarm and ends the session. */
    suspend fun imOff() {
        tracker?.stop()
        tracker = null
        phase = SessionPhase.Ended(EndReason.IM_OFF)
    }

    /** Back to the empty picker after the session has ended. */
    fun returnHome() {
        board = BoardState.Idle
        lastBoardLoad = null
        confirm = null
        blockedPermission = null
        permissionWarnings = emptyList()
        phase = SessionPhase.Selecting
    }

    companion object {
        const val BOARD_COOLDOWN_SECONDS = 20L

        /** Polling every 45 s would use about 80 of RTT's 100 requests an hour. Stopgap until a proxy exists. */
        const val POLL_INTERVAL_SECONDS = 60.0

        /** Best arrival at [toId] after the boarding stop [fromId]; null if the train does not call there. */
        fun arrival(service: Service, fromId: String, toId: String): Instant? {
            val stops = service.journey.stops
            val boardIndex = stops.indexOfFirst { it.station.id == fromId }
            if (boardIndex < 0) return null
            return stops.drop(boardIndex + 1).firstOrNull { it.station.id == toId }?.bestArrival
        }

        private fun describe(error: ProviderError): String = when (error) {
            is ProviderError.Network -> "Can't reach the train data. Check your connection and try again."
            is ProviderError.Http -> "The train data service returned an error (${error.code}). Try again in a moment."
            is ProviderError.Malformed -> "The train data was not in the expected form."
            is ProviderError.NotImplemented -> "Something went wrong loading trains. Try again."
        }
    }
}
```

- [ ] **Step 8: Android, run to verify pass**

Same command as Step 6. Expected: `BUILD SUCCESSFUL`, 17 tests. Then the full suite: `./gradlew test lint assembleDebug --offline`.

- [ ] **Step 9: Commit**

```bash
git add ios android
git commit -m "Feature: Add session model for picking, confirming and starting a journey

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 8: `SessionModel` part 2: tracker events, ringing, arrival

**Files:**
- Modify: `ios/Sources/TrainAlarm/App/SessionTypes.swift`, `ios/Sources/TrainAlarm/App/SessionModel.swift`, `ios/Tests/TrainAlarmTests/App/SessionModelTests.swift`
- Modify: `android/app/src/main/java/com/trainalarm/app/SessionTypes.kt`, `.../SessionModel.kt`, `android/app/src/test/java/com/trainalarm/app/SessionModelTest.kt`

**Interfaces:**
- Consumes: everything from Task 7; `TrackerEvent`, `FailureKind`, `EtaSource` from `Tracking/`.
- Produces: `TrackingDisplay` (`eta`, `scheduledArrival`, `alarmAt`, `isEstimated`, `estimateSource`, `feedLost`, `lastGoodAt`, `rateLimited`, `destinationDropped`, `cancelled`, `alarmNotSet`, computed `delayMinutes: Int?`); `SessionModel.display`; `SessionModel.handle(_ event:)` (the whole event table of spec section 6); `SessionModel.alarmRang()` (platform glue calls it, Task 14); `SessionModel.destinationStop(of:from:to:)`.
- Rules: events are state transitions, never toggles, so repeating one changes nothing. Events that arrive after the session has ended are ignored. `destinationLost` and `serviceCancelled` leave the tracker running and the alarm armed. `arrived` stops the tracker (clearing the alarm) and ends the session. The model starts one event collector per session.

- [ ] **Step 1: iOS, add the failing tests**

Add inside `SessionModelTests` (before its final `}`):

```swift
    // MARK: Tracker events

    private func tracking(_ h: Harness) async {
        await confirmed(h, lead: 15)
        await h.model.start()
        await h.clock.waitForSleepCount(1)
    }

    private func waitUntil(_ condition: () -> Bool, file: StaticString = #filePath, line: UInt = #line) async {
        for _ in 0..<2000 {
            if condition() { return }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("timed out", file: file, line: line)
    }

    private func t(_ hhmmss: String) -> Date { ServiceBuilder.time(hhmmss) }

    func testStartShowsTheInitialDisplay() async throws {
        let h = try makeHarness()
        await tracking(h)
        XCTAssertEqual(h.model.display.eta, t("08:52:00"))
        XCTAssertEqual(h.model.display.scheduledArrival, t("08:47:00"))
        XCTAssertEqual(h.model.display.delayMinutes, 5)
        XCTAssertEqual(h.model.display.alarmAt, t("08:37:00"))
        await h.model.imOff()
    }

    func testEtaChangedUpdatesArrivalDelayAndAlarm() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.etaChanged(oldEta: t("08:52:00"), newEta: t("08:57:00"), fireAt: t("08:42:00")))
        XCTAssertEqual(h.model.display.eta, t("08:57:00"))
        XCTAssertEqual(h.model.display.delayMinutes, 10)
        XCTAssertEqual(h.model.display.alarmAt, t("08:42:00"))
        XCTAssertFalse(h.model.display.isEstimated)
        await h.model.imOff()
    }

    func testEtaEstimatedIsLabelledAsAnEstimate() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.etaEstimated(eta: t("08:55:00"), source: .gpsPace, staleness: 120, rescheduled: true))
        XCTAssertEqual(h.model.display.eta, t("08:55:00"))
        XCTAssertTrue(h.model.display.isEstimated)
        XCTAssertEqual(h.model.display.estimateSource, .gpsPace)
        await h.model.imOff()
    }

    func testCancelledAndDroppedShowCardsAndKeepTrackingAndTheAlarm() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.serviceCancelled(at: t("08:10:00")))
        await h.model.handle(.destinationLost(at: t("08:11:00")))
        XCTAssertTrue(h.model.display.cancelled)
        XCTAssertTrue(h.model.display.destinationDropped)
        XCTAssertEqual(h.model.phase, .tracking)
        XCTAssertEqual(h.scheduler.cancelCount, 0, "the tracker leaves the alarm armed")
        await h.model.imOff()
    }

    func testDestinationRestoredClearsTheCards() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.destinationLost(at: t("08:11:00")))
        await h.model.handle(.serviceCancelled(at: t("08:12:00")))
        await h.model.handle(.destinationRestored(newEta: t("08:53:00")))
        XCTAssertFalse(h.model.display.cancelled)
        XCTAssertFalse(h.model.display.destinationDropped)
        XCTAssertEqual(h.model.display.eta, t("08:53:00"))
        await h.model.imOff()
    }

    func testFeedLostShowsHealthAndRecoveredClearsIt() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.feedLost(lastGoodAt: t("08:00:00"), kind: .network))
        XCTAssertTrue(h.model.display.feedLost)
        XCTAssertEqual(h.model.display.lastGoodAt, t("08:00:00"))
        XCTAssertFalse(h.model.display.rateLimited)
        await h.model.handle(.feedRecovered(outage: 300, freshEta: t("08:56:00"), deltaFromFallback: nil))
        XCTAssertFalse(h.model.display.feedLost)
        XCTAssertEqual(h.model.display.eta, t("08:56:00"))
        await h.model.imOff()
    }

    func testAFeedLossFromHttp429ShowsRateLimited() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.feedLost(lastGoodAt: nil, kind: .http(429)))
        XCTAssertTrue(h.model.display.rateLimited)
        await h.model.handle(.feedRecovered(outage: 60, freshEta: nil, deltaFromFallback: nil))
        XCTAssertFalse(h.model.display.rateLimited)
        await h.model.imOff()
    }

    func testAlarmSchedulingFailedShowsAlarmNotSetUntilAnEtaChangeSucceeds() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.alarmSchedulingFailed(fireAt: t("08:37:00"), message: "refused"))
        XCTAssertTrue(h.model.display.alarmNotSet)
        await h.model.handle(.etaChanged(oldEta: t("08:52:00"), newEta: t("08:53:00"), fireAt: t("08:38:00")))
        XCTAssertFalse(h.model.display.alarmNotSet)
        await h.model.imOff()
    }

    func testARetryThatSucceedsClearsAlarmNotSetWithoutAnEtaChange() async throws {
        let h = try makeHarness()
        h.scheduler.failuresRemaining = 1
        await tracking(h)
        await waitUntil { h.model.display.alarmNotSet }
        h.clock.tick(advancing: 60)
        await waitUntil { !h.model.display.alarmNotSet }
        XCTAssertEqual(h.scheduler.scheduled.count, 1)
        await h.model.imOff()
    }

    func testRepeatedEventsDoNotToggleState() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.feedLost(lastGoodAt: t("08:00:00"), kind: .network))
        let once = h.model.display
        await h.model.handle(.feedLost(lastGoodAt: t("08:00:00"), kind: .network))
        XCTAssertEqual(h.model.display, once)
        await h.model.handle(.destinationLost(at: t("08:11:00")))
        await h.model.handle(.destinationRestored(newEta: nil))
        await h.model.handle(.destinationLost(at: t("08:20:00")))
        XCTAssertTrue(h.model.display.destinationDropped)
        await h.model.imOff()
    }

    func testArrivedStopsTheTrackerAndEndsTheSession() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.handle(.arrived(at: t("08:52:00")))
        XCTAssertEqual(h.model.phase, .ended(.arrived))
        XCTAssertEqual(h.scheduler.cancelCount, 1)
    }

    func testEventsAfterTheSessionEndedAreIgnored() async throws {
        let h = try makeHarness()
        await tracking(h)
        await h.model.imOff()
        let before = h.model.display
        await h.model.handle(.feedLost(lastGoodAt: nil, kind: .network))
        XCTAssertEqual(h.model.display, before)
        XCTAssertEqual(h.model.phase, .ended(.imOff))
    }

    func testTheAlarmRingingMovesToRingingAndImOffStillEndsTheSession() async throws {
        let h = try makeHarness()
        h.model.alarmRang()
        XCTAssertEqual(h.model.phase, .selecting, "alarmRang is ignored when not tracking")
        await tracking(h)
        h.model.alarmRang()
        XCTAssertEqual(h.model.phase, .ringing)
        await h.model.imOff()
        XCTAssertEqual(h.model.phase, .ended(.imOff))
    }

    func testTrackerDelaysFlowThroughToTheDisplay() async throws {
        let h = try makeHarness()
        await tracking(h)
        h.provider.details = .success(ServiceBuilder.happyPath(destinationEstimate: "08:57:00"))
        h.clock.tick(advancing: 60)
        await waitUntil { h.model.display.eta == self.t("08:57:00") }
        XCTAssertEqual(h.model.display.delayMinutes, 10)
        await h.model.imOff()
    }
```

- [ ] **Step 2: iOS, run to verify failure**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/SessionModelTests 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | head -3
```
Expected: `value of type 'SessionModel' has no member 'display'` and similar.

- [ ] **Step 3: iOS, implement**

Append to `SessionTypes.swift`:

```swift
/// What the tracking screen shows, derived from tracker events. See the table in spec section 6.
struct TrackingDisplay: Equatable {
    var eta: Date?
    var scheduledArrival: Date?
    var alarmAt: Date?
    var isEstimated = false
    var estimateSource: EtaSource?
    var feedLost = false
    var lastGoodAt: Date?
    var rateLimited = false
    var destinationDropped = false
    var cancelled = false
    /// True when the tracker could not schedule the alarm: the screen must say the alarm is NOT set.
    var alarmNotSet = false

    /// Minutes late (positive) or early, rounded; nil without both times.
    var delayMinutes: Int? {
        guard let eta, let scheduledArrival else { return nil }
        return Int((eta.timeIntervalSince(scheduledArrival) / 60).rounded())
    }
}
```

In `SessionModel.swift`, add two properties after `private(set) var trackerConfig: TrackerConfig?`:

```swift
    private(set) var display = TrackingDisplay()
    @ObservationIgnored private var eventTask: Task<Void, Never>?
```

In `start()`, replace the last four statements (`self.tracker = tracker` to `await tracker.start()`) with:

```swift
        self.tracker = tracker
        settings.setDefaultLeadMinutes(lead)
        let destination = Self.destinationStop(of: confirm.service, from: from.id, to: to.id)
        display = TrackingDisplay(eta: destination?.bestArrival, scheduledArrival: destination?.scheduledArrival, alarmAt: alarmAt)
        phase = .tracking
        eventTask = Task { [weak self] in
            for await event in tracker.events { await self?.handle(event) }
        }
        await tracker.start()
```

In `returnHome()`, add `display = TrackingDisplay()` before `phase = .selecting`. Add these before `// MARK: Helpers`:

```swift
    // MARK: Tracker events

    /// Applies one tracker event (spec section 6). Events are state transitions, so a repeat changes
    /// nothing, and events after the session ended are ignored.
    func handle(_ event: TrackerEvent) async {
        guard phase == .tracking || phase == .ringing else { return }
        switch event {
        case let .etaChanged(_, newEta, fireAt):
            display.eta = newEta
            display.alarmAt = fireAt
            display.isEstimated = false
            display.estimateSource = nil
            display.alarmNotSet = false
        case let .etaEstimated(eta, source, _, _):
            display.eta = eta
            display.isEstimated = true
            display.estimateSource = source
        case .destinationLost:
            display.destinationDropped = true
        case .serviceCancelled:
            display.cancelled = true
        case let .destinationRestored(newEta):
            display.destinationDropped = false
            display.cancelled = false
            if let newEta { display.eta = newEta }
        case let .feedLost(lastGoodAt, kind):
            display.feedLost = true
            display.lastGoodAt = lastGoodAt
            display.rateLimited = (kind == .http(429))
        case let .feedRecovered(_, freshEta, _):
            display.feedLost = false
            display.rateLimited = false
            if let freshEta {
                display.eta = freshEta
                display.isEstimated = false
                display.estimateSource = nil
            }
        case .alarmSchedulingFailed:
            display.alarmNotSet = true
        case .arrived:
            await tracker?.stop()
            tracker = nil
            phase = .ended(.arrived)
        }
    }

    /// Called by the platform when the alarm starts ringing (Task 14).
    func alarmRang() {
        if phase == .tracking { phase = .ringing }
    }

    /// The scheduler accepted `fireAt`, so the alarm is armed whatever failed before. The tracker
    /// announces nothing when a retry succeeds on an unchanged ETA, so the model learns it here.
    private func alarmScheduled(_ fireAt: Date) {
        guard phase == .tracking || phase == .ringing else { return }
        display.alarmAt = fireAt
        display.alarmNotSet = false
    }

    /// Passes every call through and reports each success to the model.
    private struct ReportingScheduler: AlarmScheduler {
        let base: AlarmScheduler
        let onScheduled: @MainActor (Date) -> Void
        func schedule(fireAt: Date) async throws {
            try await base.schedule(fireAt: fireAt)
            await onScheduled(fireAt)
        }
        func cancel() async { await base.cancel() }
    }
```

Replace the `arrival(of:from:to:)` helper with these two:

```swift
    static func destinationStop(of service: Service, from fromId: String, to toId: String) -> Stop? {
        let stops = service.journey.stops
        guard let boardIndex = stops.firstIndex(where: { $0.station.id == fromId }) else { return nil }
        return stops.dropFirst(boardIndex + 1).first { $0.station.id == toId }
    }

    static func arrival(of service: Service, from fromId: String, to toId: String) -> Date? {
        destinationStop(of: service, from: fromId, to: toId)?.bestArrival
    }
```

- [ ] **Step 4: iOS, run to verify pass**

Same command as Step 2. Expected: `** TEST SUCCEEDED **`, 31 tests (17 from Task 7 plus 14 new). If `testTrackerDelaysFlowThroughToTheDisplay` times out, check that the poll saw the new estimate by printing `h.scheduler.scheduled`; do not lengthen the timeout.

- [ ] **Step 5: Android, add the failing tests**

Add these imports to `SessionModelTest.kt`: `com.trainalarm.app.tracking.EtaSource`, `com.trainalarm.app.tracking.FailureKind`, `com.trainalarm.app.tracking.TrackerEvent`, `kotlinx.coroutines.withTimeout`, `kotlinx.coroutines.yield`. Then add inside the class (before its final `}`):

```kotlin
    // Tracker events

    private fun t(hhmmss: String) = ServiceBuilder.time(hhmmss)

    private suspend fun Harness.tracking() {
        confirmed(lead = 15)
        model.start()
        clock.awaitSleepCount(1)
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(2_000) { while (!condition()) yield() }

    @Test fun startShowsTheInitialDisplay() = run {
        tracking()
        assertEquals(t("08:52:00"), model.display.eta)
        assertEquals(t("08:47:00"), model.display.scheduledArrival)
        assertEquals(5, model.display.delayMinutes)
        assertEquals(t("08:37:00"), model.display.alarmAt)
        model.imOff()
    }

    @Test fun etaChangedUpdatesArrivalDelayAndAlarm() = run {
        tracking()
        model.handle(TrackerEvent.EtaChanged(t("08:52:00"), t("08:57:00"), t("08:42:00")))
        assertEquals(t("08:57:00"), model.display.eta)
        assertEquals(10, model.display.delayMinutes)
        assertEquals(t("08:42:00"), model.display.alarmAt)
        assertFalse(model.display.isEstimated)
        model.imOff()
    }

    @Test fun etaEstimatedIsLabelledAsAnEstimate() = run {
        tracking()
        model.handle(TrackerEvent.EtaEstimated(t("08:55:00"), EtaSource.GPS_PACE, 120.0, true))
        assertEquals(t("08:55:00"), model.display.eta)
        assertTrue(model.display.isEstimated)
        assertEquals(EtaSource.GPS_PACE, model.display.estimateSource)
        model.imOff()
    }

    @Test fun cancelledAndDroppedShowCardsAndKeepTrackingAndTheAlarm() = run {
        tracking()
        model.handle(TrackerEvent.ServiceCancelled(t("08:10:00")))
        model.handle(TrackerEvent.DestinationLost(t("08:11:00")))
        assertTrue(model.display.cancelled)
        assertTrue(model.display.destinationDropped)
        assertEquals(SessionPhase.Tracking, model.phase)
        assertEquals(0, scheduler.cancelCount)
        model.imOff()
    }

    @Test fun destinationRestoredClearsTheCards() = run {
        tracking()
        model.handle(TrackerEvent.DestinationLost(t("08:11:00")))
        model.handle(TrackerEvent.ServiceCancelled(t("08:12:00")))
        model.handle(TrackerEvent.DestinationRestored(t("08:53:00")))
        assertFalse(model.display.cancelled)
        assertFalse(model.display.destinationDropped)
        assertEquals(t("08:53:00"), model.display.eta)
        model.imOff()
    }

    @Test fun feedLostShowsHealthAndRecoveredClearsIt() = run {
        tracking()
        model.handle(TrackerEvent.FeedLost(t("08:00:00"), FailureKind.Network))
        assertTrue(model.display.feedLost)
        assertEquals(t("08:00:00"), model.display.lastGoodAt)
        assertFalse(model.display.rateLimited)
        model.handle(TrackerEvent.FeedRecovered(300.0, t("08:56:00"), null))
        assertFalse(model.display.feedLost)
        assertEquals(t("08:56:00"), model.display.eta)
        model.imOff()
    }

    @Test fun aFeedLossFromHttp429ShowsRateLimited() = run {
        tracking()
        model.handle(TrackerEvent.FeedLost(null, FailureKind.Http(429)))
        assertTrue(model.display.rateLimited)
        model.handle(TrackerEvent.FeedRecovered(60.0, null, null))
        assertFalse(model.display.rateLimited)
        model.imOff()
    }

    @Test fun alarmSchedulingFailedShowsAlarmNotSetUntilAnEtaChangeSucceeds() = run {
        tracking()
        model.handle(TrackerEvent.AlarmSchedulingFailed(t("08:37:00"), "refused"))
        assertTrue(model.display.alarmNotSet)
        model.handle(TrackerEvent.EtaChanged(t("08:52:00"), t("08:53:00"), t("08:38:00")))
        assertFalse(model.display.alarmNotSet)
        model.imOff()
    }

    @Test fun aRetryThatSucceedsClearsAlarmNotSetWithoutAnEtaChange() = run {
        scheduler.failuresRemaining = 1
        tracking()
        waitUntil { model.display.alarmNotSet }
        clock.tick(60.0)
        waitUntil { !model.display.alarmNotSet }
        assertEquals(1, scheduler.scheduled.size)
        model.imOff()
    }

    @Test fun repeatedEventsDoNotToggleState() = run {
        tracking()
        model.handle(TrackerEvent.FeedLost(t("08:00:00"), FailureKind.Network))
        val once = model.display
        model.handle(TrackerEvent.FeedLost(t("08:00:00"), FailureKind.Network))
        assertEquals(once, model.display)
        model.handle(TrackerEvent.DestinationLost(t("08:11:00")))
        model.handle(TrackerEvent.DestinationRestored(null))
        model.handle(TrackerEvent.DestinationLost(t("08:20:00")))
        assertTrue(model.display.destinationDropped)
        model.imOff()
    }

    @Test fun arrivedStopsTheTrackerAndEndsTheSession() = run {
        tracking()
        model.handle(TrackerEvent.Arrived(t("08:52:00")))
        assertEquals(SessionPhase.Ended(EndReason.ARRIVED), model.phase)
        assertEquals(1, scheduler.cancelCount)
    }

    @Test fun eventsAfterTheSessionEndedAreIgnored() = run {
        tracking()
        model.imOff()
        val before = model.display
        model.handle(TrackerEvent.FeedLost(null, FailureKind.Network))
        assertEquals(before, model.display)
        assertEquals(SessionPhase.Ended(EndReason.IM_OFF), model.phase)
    }

    @Test fun theAlarmRingingMovesToRingingAndImOffStillEndsTheSession() = run {
        model.alarmRang()
        assertEquals(SessionPhase.Selecting, model.phase)
        tracking()
        model.alarmRang()
        assertEquals(SessionPhase.Ringing, model.phase)
        model.imOff()
        assertEquals(SessionPhase.Ended(EndReason.IM_OFF), model.phase)
    }

    @Test fun trackerDelaysFlowThroughToTheDisplay() = run {
        tracking()
        provider.details = { ServiceBuilder.happyPath(destinationEstimate = "08:57:00") }
        clock.tick(60.0)
        waitUntil { model.display.eta == t("08:57:00") }
        assertEquals(10, model.display.delayMinutes)
        model.imOff()
    }
```

- [ ] **Step 6: Android, run to verify failure**

```bash
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew testDebugUnitTest --offline --tests "com.trainalarm.app.SessionModelTest" 2>&1 | grep -E "^e: |BUILD" | head -3
```
Expected: unresolved references `display`, `handle`, `alarmRang`.

- [ ] **Step 7: Android, implement**

Append to `SessionTypes.kt` (add `import com.trainalarm.app.tracking.EtaSource` at the top):

```kotlin
/** What the tracking screen shows, derived from tracker events. See the table in spec section 6. */
data class TrackingDisplay(
    val eta: Instant? = null,
    val scheduledArrival: Instant? = null,
    val alarmAt: Instant? = null,
    val isEstimated: Boolean = false,
    val estimateSource: EtaSource? = null,
    val feedLost: Boolean = false,
    val lastGoodAt: Instant? = null,
    val rateLimited: Boolean = false,
    val destinationDropped: Boolean = false,
    val cancelled: Boolean = false,
    /** True when the tracker could not schedule the alarm: the screen must say the alarm is NOT set. */
    val alarmNotSet: Boolean = false
) {
    /** Minutes late (positive) or early, rounded; null without both times. */
    val delayMinutes: Int?
        get() {
            val e = eta ?: return null
            val s = scheduledArrival ?: return null
            return Math.round((e.epochSecond - s.epochSecond) / 60.0).toInt()
        }
}
```

In `SessionModel.kt`: add imports `com.trainalarm.app.tracking.EtaSource`? (not needed), `com.trainalarm.app.tracking.FailureKind`, `com.trainalarm.app.tracking.TrackerEvent`, `kotlinx.coroutines.Job`, `kotlinx.coroutines.launch`, `com.trainalarm.app.model.Stop`. Add after `var trackerConfig ... private set`:

```kotlin
    var display: TrackingDisplay by mutableStateOf(TrackingDisplay())
        private set
```
and after `private var tracker: JourneyTracker? = null`: `private var eventJob: Job? = null`.

In `start()`, replace the last four statements (`tracker = newTracker` to `newTracker.start(scope)`) with:

```kotlin
        tracker = newTracker
        settings.setDefaultLeadMinutes(lead)
        val destination = destinationStop(c.service, f.id, t.id)
        display = TrackingDisplay(
            eta = destination?.bestArrival,
            scheduledArrival = destination?.scheduledArrival,
            alarmAt = alarmAt
        )
        phase = SessionPhase.Tracking
        eventJob = scope.launch { newTracker.events.collect { handle(it) } }
        newTracker.start(scope)
```
In `returnHome()` add `display = TrackingDisplay()` before the phase line. Add before `companion object`:

```kotlin
    // Tracker events

    /**
     * Applies one tracker event (spec section 6). Events are state transitions, so a repeat changes
     * nothing, and events after the session ended are ignored.
     */
    suspend fun handle(event: TrackerEvent) {
        if (phase != SessionPhase.Tracking && phase != SessionPhase.Ringing) return
        when (event) {
            is TrackerEvent.EtaChanged -> display = display.copy(
                eta = event.newEta, alarmAt = event.fireAt, isEstimated = false, estimateSource = null, alarmNotSet = false
            )
            is TrackerEvent.EtaEstimated -> display = display.copy(
                eta = event.eta, isEstimated = true, estimateSource = event.source
            )
            is TrackerEvent.DestinationLost -> display = display.copy(destinationDropped = true)
            is TrackerEvent.ServiceCancelled -> display = display.copy(cancelled = true)
            is TrackerEvent.DestinationRestored -> display = display.copy(
                destinationDropped = false, cancelled = false, eta = event.newEta ?: display.eta
            )
            is TrackerEvent.FeedLost -> display = display.copy(
                feedLost = true, lastGoodAt = event.lastGoodAt, rateLimited = event.kind == FailureKind.Http(429)
            )
            is TrackerEvent.FeedRecovered -> display = display.copy(
                feedLost = false,
                rateLimited = false,
                eta = event.freshEta ?: display.eta,
                isEstimated = if (event.freshEta != null) false else display.isEstimated,
                estimateSource = if (event.freshEta != null) null else display.estimateSource
            )
            is TrackerEvent.AlarmSchedulingFailed -> display = display.copy(alarmNotSet = true)
            is TrackerEvent.Arrived -> {
                tracker?.stop()
                tracker = null
                phase = SessionPhase.Ended(EndReason.ARRIVED)
            }
        }
    }

    /** Called by the platform when the alarm starts ringing (Task 14). */
    fun alarmRang() {
        if (phase == SessionPhase.Tracking) phase = SessionPhase.Ringing
    }

    /**
     * The scheduler accepted [fireAt], so the alarm is armed whatever failed before. The tracker announces
     * nothing when a retry succeeds on an unchanged ETA, so the model learns it here.
     */
    private fun alarmScheduled(fireAt: Instant) {
        if (phase != SessionPhase.Tracking && phase != SessionPhase.Ringing) return
        display = display.copy(alarmAt = fireAt, alarmNotSet = false)
    }

    /** Passes every call through and reports each success to the model. */
    private class ReportingScheduler(private val base: AlarmScheduler, private val onScheduled: (Instant) -> Unit) : AlarmScheduler {
        override suspend fun schedule(fireAt: Instant) {
            base.schedule(fireAt)
            onScheduled(fireAt)
        }

        override suspend fun cancel() = base.cancel()
    }
```
Inside `companion object`, replace `fun arrival(...)` with:

```kotlin
        /** The stop at [toId] after the boarding stop [fromId]; null if the train does not call there. */
        fun destinationStop(service: Service, fromId: String, toId: String): Stop? {
            val stops = service.journey.stops
            val boardIndex = stops.indexOfFirst { it.station.id == fromId }
            if (boardIndex < 0) return null
            return stops.drop(boardIndex + 1).firstOrNull { it.station.id == toId }
        }

        fun arrival(service: Service, fromId: String, toId: String): Instant? =
            destinationStop(service, fromId, toId)?.bestArrival
```

- [ ] **Step 8: Android, run to verify pass**

Same command as Step 6. Expected: `BUILD SUCCESSFUL`, 31 tests. Then `./gradlew test lint assembleDebug --offline`.

- [ ] **Step 9: Commit**

```bash
git add ios android
git commit -m "Feature: Show tracker events, ringing and arrival in the session model

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 9: RTT token configuration (build-time, never committed)

**Files:**
- Create: `ios/Config/Base.xcconfig`, `ios/Config/Secrets.xcconfig.example`, `ios/Sources/TrainAlarm/App/RttToken.swift`, `ios/Tests/TrainAlarmTests/App/RttTokenTests.swift`
- Modify: `ios/project.yml`, `.gitignore`, `android/app/build.gradle.kts`
- Create: `android/app/src/main/java/com/trainalarm/app/RttToken.kt`, `android/app/src/test/java/com/trainalarm/app/RttTokenTest.kt`

**Interfaces:**
- Produces: `RttToken.parse(_ raw: Any?) -> String?` (iOS) and `RttToken.parse(raw: String?): String?` (Android): trims, and returns nil for nil, empty, whitespace, or an unsubstituted `$(RTT_ACCESS_TOKEN)` placeholder. `RttToken.current` reads the real value: Info.plist key `RTTAccessToken` on iOS, `BuildConfig.RTT_ACCESS_TOKEN` on Android.
- The token comes from `ios/Config/Secrets.xcconfig` (gitignored, optional) and `android/local.properties` key `rtt.accessToken` (already gitignored, optional). With neither file, both builds succeed and `current` is nil. A build with a token is for the developer's own devices only (RTT's terms, spec section 7), and only Debug builds read it: iOS applies `Secrets.xcconfig` to the Debug configuration only, and Android sets the field in the `debug` build type, so a Release or TestFlight build cannot carry a token.
- Consumed later by the composition root (Task 10 environment, Task 12).

- [ ] **Step 1: Write the failing tests**

iOS `RttTokenTests.swift`:

```swift
import XCTest
@testable import TrainAlarm

final class RttTokenTests: XCTestCase {
    func testMissingOrEmptyValuesAreNoToken() {
        XCTAssertNil(RttToken.parse(nil))
        XCTAssertNil(RttToken.parse(""))
        XCTAssertNil(RttToken.parse("   "))
        XCTAssertNil(RttToken.parse(42))
    }

    func testAnUnsubstitutedPlaceholderIsNoToken() {
        XCTAssertNil(RttToken.parse("$(RTT_ACCESS_TOKEN)"))
    }

    func testARealValueIsTrimmed() {
        XCTAssertEqual(RttToken.parse("  abc123\n"), "abc123")
    }
}
```

Android `RttTokenTest.kt`:

```kotlin
package com.trainalarm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RttTokenTest {
    @Test fun missingOrEmptyValuesAreNoToken() {
        assertNull(RttToken.parse(null))
        assertNull(RttToken.parse(""))
        assertNull(RttToken.parse("   "))
    }

    @Test fun aRealValueIsTrimmed() = assertEquals("abc123", RttToken.parse("  abc123\n"))
}
```

- [ ] **Step 2: Run to verify failure**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/RttTokenTests 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | head -3
cd ../android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew testDebugUnitTest --offline --tests "com.trainalarm.app.RttTokenTest" 2>&1 | grep -E "^e: |BUILD" | head -3
```
Expected: unresolved `RttToken`.

- [ ] **Step 3: Implement the code**

iOS `RttToken.swift`:

```swift
import Foundation

/// The RTT bearer token, set at build time for the developer's own devices only. RTT forbids a
/// token in a distributable app (spec section 7), so builds that contain one are never shared.
enum RttToken {
    /// nil for nothing, whitespace, or the unsubstituted build-setting placeholder.
    static func parse(_ raw: Any?) -> String? {
        guard let text = (raw as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !text.isEmpty, text != "$(RTT_ACCESS_TOKEN)" else { return nil }
        return text
    }

    static var current: String? { parse(Bundle.main.object(forInfoDictionaryKey: "RTTAccessToken")) }
}
```

Android `RttToken.kt`:

```kotlin
package com.trainalarm.app

/**
 * The RTT bearer token, set at build time for the developer's own devices only. RTT forbids a
 * token in a distributable app (spec section 7), so builds that contain one are never shared.
 */
object RttToken {
    /** null for nothing or whitespace. */
    fun parse(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    val current: String? get() = parse(BuildConfig.RTT_ACCESS_TOKEN)
}
```

- [ ] **Step 4: Implement the build configuration**

Create `ios/Config/Base.xcconfig`:

```
// Optional, gitignored. Copy Secrets.xcconfig.example to Secrets.xcconfig and add your token.
// #include? means a missing file is not an error, so CI and fresh checkouts still build.
#include? "Secrets.xcconfig"
```

Create `ios/Config/Secrets.xcconfig.example` (the real file is never committed; a value containing `//` would be cut off as a comment):

```
RTT_ACCESS_TOKEN = paste-your-token-here
```

In `ios/project.yml`, add at the top level (beside `options:`) and one Info.plist property under `TrainAlarm.info.properties`. Only the Debug configuration gets the secrets file; Release has none, so `RTTAccessToken` stays the unsubstituted `$(RTT_ACCESS_TOKEN)` placeholder and `RttToken.parse` reads it as no token:

```yaml
configFiles:
  Debug: Config/Base.xcconfig
```
```yaml
        RTTAccessToken: "$(RTT_ACCESS_TOKEN)"
```

Append to `.gitignore` under `# Environment / secrets`:

```
ios/Config/Secrets.xcconfig
```

In `android/app/build.gradle.kts`: add `import java.util.Properties` as the very first line, add this above the `android {` block, replace the line `        release {` inside `buildTypes` with the block below (a `debug` entry that reads the token, and an empty token for `release`; the existing `isMinifyEnabled` and `proguardFiles` lines stay under `release`), and set `buildConfig = true` in `buildFeatures`:

```kotlin
// For the developer's own devices only (spec section 7). local.properties is gitignored and may be absent.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
```
```kotlin
        debug {
            // Only debug builds carry a token: RTT's terms forbid shipping one in a distributed app.
            buildConfigField("String", "RTT_ACCESS_TOKEN", "\"${localProperties.getProperty("rtt.accessToken", "").trim()}\"")
        }
        release {
            buildConfigField("String", "RTT_ACCESS_TOKEN", "\"\"")   // release builds never contain a token
```
```kotlin
    buildFeatures {
        compose = true
        buildConfig = true
    }
```

- [ ] **Step 5: Run to verify pass, with and without a token**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/RttTokenTests 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | head -3
cd ../android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew testDebugUnitTest --offline --tests "com.trainalarm.app.RttTokenTest" 2>&1 | grep -E "^e: |BUILD" | head -3
```
Expected: pass, with no secrets files present. Then prove the token reaches both apps using a dummy value (never a real token), and clean up:

```bash
test ! -e ios/Config/Secrets.xcconfig && test ! -e android/local.properties || { echo "A real secrets file or local.properties exists: do not overwrite it. Run this check in a fresh worktree."; exit 1; }
printf 'RTT_ACCESS_TOKEN = dry-run-not-a-real-token\n' > ios/Config/Secrets.xcconfig
printf 'rtt.accessToken=dry-run-not-a-real-token\n' > android/local.properties
(cd ios && xcodegen generate && xcodebuild build -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -derivedDataPath /tmp/ta-token-check 2>&1 | grep -E "error:|BUILD (SUCCEEDED|FAILED)")
/usr/libexec/PlistBuddy -c "Print :RTTAccessToken" "$(find /tmp/ta-token-check -name TrainAlarm.app -type d | head -1)/Info.plist"
(cd ios && xcodebuild -scheme TrainAlarm -configuration Release -showBuildSettings 2>/dev/null | grep -c RTT_ACCESS_TOKEN)
(cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew generateDebugBuildConfig generateReleaseBuildConfig --offline -q && grep -h RTT_ACCESS_TOKEN app/build/generated/source/buildConfig/debug/com/trainalarm/app/BuildConfig.java app/build/generated/source/buildConfig/release/com/trainalarm/app/BuildConfig.java)
git check-ignore ios/Config/Secrets.xcconfig android/local.properties
rm ios/Config/Secrets.xcconfig android/local.properties && rm -rf /tmp/ta-token-check
git status --short
```
Expected: `BUILD SUCCEEDED`, the plist prints `dry-run-not-a-real-token`, the iOS Release settings list no `RTT_ACCESS_TOKEN` (the count prints `0`), the debug `BuildConfig.java` contains `"dry-run-not-a-real-token"` and the release one contains an empty `""`, `git check-ignore` prints both paths, and the final `git status` shows only the intended changes (no secrets file).

- [ ] **Step 6: Commit**

```bash
git add .gitignore ios android
git commit -m "Chore: Read the RTT token from gitignored local files at build time

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Demo mode and the scenario launcher (debug builds only)

**Files:**
- Create: `ios/Sources/TrainAlarm/App/Demo/DemoProvider.swift`, `ios/Sources/TrainAlarm/App/Demo/Scenario.swift` (both wrapped in `#if DEBUG`)
- Create (tests): `ios/Tests/TrainAlarmTests/App/ScenarioTests.swift` (wrapped in `#if DEBUG`)
- Create: `android/app/src/debug/java/com/trainalarm/app/demo/DemoProvider.kt`, `.../demo/Scenario.kt`
- Create (tests): `android/app/src/testDebug/java/com/trainalarm/app/demo/ScenarioTest.kt` (a `testDebug` source set, because the release unit tests cannot see debug code)

**Interfaces:**
- Consumes: `SessionModel` and its `handle`/`alarmRang` (Tasks 7, 8), `PermissionsGateway`, `AlarmScheduler`, `StationDirectory`, `ServiceBuilder`-style `Stop`/`Journey` constructors.
- Produces: `DemoProvider(clock)`, a `TrainDataProvider` with three trains from KGX to EDB (on time, 4 minutes late, cancelled); times are relative to the clock. `NoopAlarmScheduler`, `DemoClock(speed:)` (a clock that runs faster: iOS `-demo-speed 60`, Android `--ei demoSpeed 60`), `DemoAlarmScheduler` (arms nothing with the system, but tells the app when the demo clock reaches the fire time, so a demo run reaches the ringing screen and the arrival), `DemoPermissions` (the alarm permission, granted; nothing else is listed). The provider's timetable is fixed when it is made, so polls see the same times. `Scenario` with `from(arguments:)` (iOS: `-scenario <name>`; Android: the `scenario` intent extra), `opensSettings`, and `apply(to:directory:now:)` that drives a `SessionModel` into the named state: `home, board, confirm, tracking, tracking-late, cancelled, dropped, feed-lost, alarm-not-set, ringing, ended, settings`.
- The app entry that reads the launch argument or intent extra and builds the demo environment is wired in Task 12 with the screens. Nothing here may be reachable in a release build.

- [ ] **Step 1: iOS, write the failing tests**

```swift
#if DEBUG
import XCTest
@testable import TrainAlarm

@MainActor
final class ScenarioTests: XCTestCase {
    private let json = #"[{"crs":"KGX","name":"London Kings Cross","lat":51.5309,"lon":-0.1233},{"crs":"PBO","name":"Peterborough","lat":52.5748,"lon":-0.2504},{"crs":"EDB","name":"Edinburgh","lat":55.9521,"lon":-3.1894}]"#
    private var suite = ""

    override func setUp() { suite = "scenario-test-\(UUID().uuidString)" }
    override func tearDown() { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }

    private func make() throws -> (SessionModel, StationDirectory, FakeClock) {
        let directory = try StationDirectory.parse(Data(json.utf8))
        let clock = FakeClock(start: Date(timeIntervalSince1970: 1_783_000_000))
        let model = SessionModel(provider: DemoProvider(clock: clock), scheduler: NoopAlarmScheduler(), directory: directory,
                                 location: FakeLocationSource(), clock: clock, permissions: DemoPermissions(),
                                 settings: SettingsStore(defaults: UserDefaults(suiteName: suite)!))
        return (model, directory, clock)
    }

    private func run(_ scenario: Scenario) async throws -> (SessionModel, Date) {
        let (model, directory, clock) = try make()
        await scenario.apply(to: model, directory: directory, now: clock.now())
        return (model, clock.now())
    }

    func testTheLaunchArgumentNamesAScenario() {
        XCTAssertEqual(Scenario.from(arguments: ["app", "-scenario", "tracking-late"]), .trackingLate)
        XCTAssertEqual(Scenario.from(arguments: ["-scenario", "feed-lost"]), .feedLost)
        XCTAssertNil(Scenario.from(arguments: ["-scenario", "nonsense"]))
        XCTAssertNil(Scenario.from(arguments: ["-scenario"]))
        XCTAssertNil(Scenario.from(arguments: []))
    }

    func testEveryScenarioNameRoundTrips() {
        for scenario in Scenario.allCases { XCTAssertEqual(Scenario(rawValue: scenario.rawValue), scenario) }
        XCTAssertEqual(Scenario.allCases.count, 12)
    }

    func testDemoBoardHasThreeTrainsOneCancelledAndOnlyFromKingsCross() async throws {
        let clock = FakeClock(start: Date(timeIntervalSince1970: 1_783_000_000))
        let provider = DemoProvider(clock: clock)
        let kgx = Station(id: "KGX", name: "London Kings Cross", latitude: 0, longitude: 0)
        let edb = Station(id: "EDB", name: "Edinburgh", latitude: 0, longitude: 0)
        let board = try await provider.departureBoard(station: kgx, from: clock.now(), callingAt: edb)
        XCTAssertEqual(board.count, 3)
        XCTAssertTrue(board[2].journey.stops[0].isCancelled)
        let none = try await provider.departureBoard(station: edb, from: clock.now(), callingAt: kgx)
        XCTAssertEqual(none, [])
        let details = try await provider.serviceDetails(serviceId: "demo-1", date: clock.now())
        XCTAssertEqual(details.journey.stops.count, 5)
    }

    func testHomeAndSettingsLeaveTheModelAlone() async throws {
        for scenario in [Scenario.home, .settings] {
            let (model, _) = try await run(scenario)
            XCTAssertEqual(model.phase, .selecting)
            XCTAssertNil(model.from)
        }
        XCTAssertTrue(Scenario.settings.opensSettings)
        XCTAssertFalse(Scenario.home.opensSettings)
    }

    func testBoardAndConfirmScenarios() async throws {
        let (boardModel, _) = try await run(.board)
        guard case .loaded(let services) = boardModel.board else { return XCTFail("board not loaded") }
        XCTAssertEqual(services.count, 3)
        let (confirmModel, _) = try await run(.confirm)
        XCTAssertEqual(confirmModel.phase, .confirming)
        XCTAssertEqual(confirmModel.confirm?.leadMinutes, 15)
        XCTAssertNotNil(confirmModel.alarmAt)
    }

    func testTrackingScenariosShowTheirStates() async throws {
        let (tracking, _) = try await run(.tracking)
        XCTAssertEqual(tracking.phase, .tracking)
        XCTAssertEqual(tracking.display.delayMinutes, 0)
        await tracking.imOff()

        let (late, _) = try await run(.trackingLate)
        XCTAssertEqual(late.display.delayMinutes, 4)
        await late.imOff()

        let (cancelled, _) = try await run(.cancelled)
        XCTAssertTrue(cancelled.display.cancelled)
        await cancelled.imOff()

        let (dropped, _) = try await run(.dropped)
        XCTAssertTrue(dropped.display.destinationDropped)
        await dropped.imOff()

        let (feed, _) = try await run(.feedLost)
        XCTAssertTrue(feed.display.feedLost)
        XCTAssertNotNil(feed.display.lastGoodAt)
        await feed.imOff()

        let (unset, _) = try await run(.alarmNotSet)
        XCTAssertTrue(unset.display.alarmNotSet)
        await unset.imOff()
    }

    func testRingingAndEndedScenarios() async throws {
        let (ringing, _) = try await run(.ringing)
        XCTAssertEqual(ringing.phase, .ringing)
        await ringing.imOff()
        let (ended, _) = try await run(.ended)
        XCTAssertEqual(ended.phase, .ended(.arrived))
    }

    func testDemoSpeedArgument() {
        XCTAssertEqual(Scenario.speed(from: ["app", "-demo-speed", "60"]), 60)
        XCTAssertEqual(Scenario.speed(from: ["app"]), 1)
        XCTAssertEqual(Scenario.speed(from: ["-demo-speed"]), 1)
        XCTAssertEqual(Scenario.speed(from: ["-demo-speed", "0"]), 1)
        XCTAssertEqual(Scenario.speed(from: ["-demo-speed", "fast"]), 1)
    }

    func testDemoClockRunsFasterThanTheWallClock() async throws {
        let clock = DemoClock(speed: 60)
        let before = clock.now()
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertGreaterThan(clock.now().timeIntervalSince(before), 3)   // 0.1 s of wall time is 6 s of demo time
    }

    func testDemoClockSleepIsShortenedBySpeed() async throws {
        let start = Date()
        try await DemoClock(speed: 60).sleep(for: 6)   // 0.1 s of wall time
        XCTAssertLessThan(Date().timeIntervalSince(start), 1.0)
    }

    func testDemoAlarmRingsWhenTheClockReachesTheFireTime() async throws {
        let clock = FakeClock(start: Date(timeIntervalSince1970: 1_000))
        var rang = 0
        let scheduler = DemoAlarmScheduler(clock: clock) { rang += 1 }
        try await scheduler.schedule(fireAt: clock.now().addingTimeInterval(30))
        await clock.waitForSleepCount(1)
        XCTAssertEqual(rang, 0)
        clock.tick(advancing: 30)
        for _ in 0..<2000 where rang == 0 { try await Task.sleep(nanoseconds: 1_000_000) }
        XCTAssertEqual(rang, 1)
    }

    func testCancellingTheDemoAlarmStopsItRinging() async throws {
        let clock = FakeClock(start: Date(timeIntervalSince1970: 1_000))
        var rang = 0
        let scheduler = DemoAlarmScheduler(clock: clock) { rang += 1 }
        try await scheduler.schedule(fireAt: clock.now().addingTimeInterval(30))
        await clock.waitForSleepCount(1)
        await scheduler.cancel()
        clock.tick(advancing: 30)
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(rang, 0)
    }

    func testDemoTimesDoNotMoveWhenTheClockDoes() async throws {
        let clock = FakeClock(start: Date(timeIntervalSince1970: 1_783_000_000))
        let provider = DemoProvider(clock: clock)
        let first = try await provider.serviceDetails(serviceId: "demo-1", date: clock.now())
        clock.tick(advancing: 120)
        let second = try await provider.serviceDetails(serviceId: "demo-1", date: clock.now())
        XCTAssertEqual(first.journey.stops.map(\.scheduledDeparture), second.journey.stops.map(\.scheduledDeparture))
    }
}
#endif
```

- [ ] **Step 2: iOS, run to verify failure**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/ScenarioTests 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | head -3
```
Expected: `cannot find 'DemoProvider' in scope`.

- [ ] **Step 3: iOS, implement**

`DemoProvider.swift`:

```swift
#if DEBUG
import Foundation

/// Scripted trains for demo mode and screenshots. Debug builds only. All times are relative to the clock
/// at the moment the provider was made, and stay fixed after that: a later poll must see the same timetable,
/// or the ETA would drift and every poll would look like a delay.
struct DemoProvider: TrainDataProvider {
    private let epoch: Date

    init(clock: TrackerClock) { epoch = clock.now() }

    private struct Call { let crs: String; let name: String; let minutes: Int }
    private struct Train { let id: String; let departsInMinutes: Int; let delay: Int; let cancelled: Bool }

    private static let route = [
        Call(crs: "KGX", name: "London Kings Cross", minutes: 0), Call(crs: "PBO", name: "Peterborough", minutes: 45),
        Call(crs: "NCL", name: "Newcastle", minutes: 190), Call(crs: "BWK", name: "Berwick-upon-Tweed", minutes: 220),
        Call(crs: "EDB", name: "Edinburgh", minutes: 250),
    ]
    private static let trains = [
        Train(id: "demo-1", departsInMinutes: 20, delay: 0, cancelled: false),
        Train(id: "demo-2", departsInMinutes: 50, delay: 4, cancelled: false),
        Train(id: "demo-3", departsInMinutes: 80, delay: 0, cancelled: true),
    ]

    func searchStations(query: String) async throws -> [Station] { [] }

    func departureBoard(station: Station, from: Date, callingAt: Station?) async throws -> [Service] {
        guard station.id == "KGX" else { return [] }
        if let callingAt, !Self.route.dropFirst().contains(where: { $0.crs == callingAt.id }) { return [] }
        return try Self.trains.map { try service($0, summaryOnly: true) }
    }

    func serviceDetails(serviceId: String, date: Date) async throws -> Service {
        guard let train = Self.trains.first(where: { $0.id == serviceId }) else { throw ProviderError.http(404) }
        return try service(train, summaryOnly: false)
    }

    private func service(_ train: Train, summaryOnly: Bool) throws -> Service {
        let now = epoch.timeIntervalSince1970
        let base = Date(timeIntervalSince1970: (now / 60).rounded(.down) * 60 + Double(train.departsInMinutes) * 60)
        let stops: [Stop] = Self.route.enumerated().map { index, call in
            let scheduled = base.addingTimeInterval(Double(call.minutes) * 60)
            let estimated = train.cancelled ? nil : scheduled.addingTimeInterval(Double(train.delay) * 60)
            let isOrigin = index == 0
            return Stop(
                station: Station(id: call.crs, name: call.name, latitude: 0, longitude: 0),
                scheduledArrival: isOrigin ? nil : scheduled, scheduledDeparture: scheduled,
                estimatedArrival: isOrigin ? nil : estimated, estimatedDeparture: estimated,
                isArrivalCancelled: train.cancelled && !isOrigin, isDepartureCancelled: train.cancelled,
                displayAs: train.cancelled ? .cancelled : .call, hasArrived: false)
        }
        let shown = summaryOnly ? [stops[0]] : stops
        let journey = try Journey(origin: stops[0].station, destination: stops[stops.count - 1].station, stops: shown)
        return Service(id: train.id, operatorName: "LNER", journey: journey)
    }
}

/// Demo mode never arms a real alarm.
struct NoopAlarmScheduler: AlarmScheduler {
    func schedule(fireAt: Date) async throws {}
    func cancel() async {}
}

/// A clock that runs `speed` times faster than the wall clock, so a demo can reach the alarm and the arrival
/// without waiting hours (`-demo-speed 60`). Speed 1 is real time.
struct DemoClock: TrackerClock {
    private let start = Date()
    let speed: Double

    init(speed: Double) { self.speed = max(speed, 1) }

    func now() -> Date { start.addingTimeInterval(Date().timeIntervalSince(start) * speed) }

    func sleep(for seconds: TimeInterval) async throws {
        try await Task.sleep(nanoseconds: UInt64(max(seconds, 0) / speed * 1_000_000_000))
    }
}

/// Demo mode's alarm: nothing is armed with the system, but when the demo clock reaches the fire time the
/// app is told the alarm rang, so a demo run can reach the ringing screen. Replaces any earlier alarm.
final class DemoAlarmScheduler: AlarmScheduler, @unchecked Sendable {
    private let clock: TrackerClock
    private let onRing: @MainActor () -> Void
    private let lock = NSLock()
    private var task: Task<Void, Never>?

    init(clock: TrackerClock, onRing: @escaping @MainActor () -> Void) {
        self.clock = clock
        self.onRing = onRing
    }

    func schedule(fireAt: Date) async throws {
        let delay = max(fireAt.timeIntervalSince(clock.now()), 0)
        let next = Task { [clock, onRing] in
            do { try await clock.sleep(for: delay) } catch { return }
            await onRing()
        }
        lock.lock(); let previous = task; task = next; lock.unlock()
        previous?.cancel()
    }

    func cancel() async {
        lock.lock(); let previous = task; task = nil; lock.unlock()
        previous?.cancel()
    }
}

/// Lets the demo scheduler reach the model, which is made after it.
final class RingBox {
    var handler: (@MainActor () -> Void)?
}

/// Demo mode asks for nothing and everything is granted.
struct DemoPermissions: PermissionsGateway {
    var requiredKinds: [PermissionKind] { [.alarm] }   // listed (granted) so Settings shows a row; location stays unlisted so the demo never starts GPS
    var optionalKinds: [PermissionKind] { [] }
    func status(_ kind: PermissionKind) -> PermissionStatus { .granted }
    func request(_ kind: PermissionKind) async -> PermissionStatus { .granted }
}
#endif
```

`Scenario.swift`:

```swift
#if DEBUG
import Foundation

/// Opens the app in a named state with no live data, for screenshots and demos. Debug builds only.
/// iOS reads `-scenario <name>` from the launch arguments.
enum Scenario: String, CaseIterable {
    case home, board, confirm, tracking
    case trackingLate = "tracking-late"
    case cancelled, dropped
    case feedLost = "feed-lost"
    case alarmNotSet = "alarm-not-set"
    case ringing, ended, settings

    static func from(arguments: [String]) -> Scenario? {
        guard let index = arguments.firstIndex(of: "-scenario"), arguments.indices.contains(index + 1) else { return nil }
        return Scenario(rawValue: arguments[index + 1])
    }

    var opensSettings: Bool { self == .settings }

    /// `-demo-speed N` runs the demo clock N times faster (default 1; anything below 1 or not a number is 1).
    static func speed(from arguments: [String]) -> Double {
        guard let index = arguments.firstIndex(of: "-demo-speed"), arguments.indices.contains(index + 1),
              let value = Double(arguments[index + 1]), value >= 1 else { return 1 }
        return value
    }

    /// Drives the model from its empty state to this scenario, using the demo trains from KGX to EDB.
    @MainActor
    func apply(to model: SessionModel, directory: StationDirectory, now: Date) async {
        guard self != .home, self != .settings,
              let from = directory.station(crs: "KGX"), let to = directory.station(crs: "EDB") else { return }
        model.selectFrom(from)
        model.selectTo(to)
        await model.loadBoard()
        if self == .board { return }
        guard case let .loaded(services) = model.board else { return }
        await model.choose(services[0])
        model.setLead(15)
        if self == .confirm { return }
        await model.start()
        // The tracker publishes its own start-up events. Let them arrive first, or they overwrite ours.
        try? await Task.sleep(nanoseconds: 500_000_000)
        let eta = model.display.eta ?? now
        let alarm = model.display.alarmAt ?? now
        switch self {
        case .trackingLate: await model.handle(.etaChanged(oldEta: eta, newEta: eta + 240, fireAt: alarm + 240))
        case .cancelled: await model.handle(.serviceCancelled(at: now))
        case .dropped: await model.handle(.destinationLost(at: now))
        case .feedLost: await model.handle(.feedLost(lastGoodAt: now.addingTimeInterval(-360), kind: .network))
        case .alarmNotSet: await model.handle(.alarmSchedulingFailed(fireAt: alarm, message: "demo"))
        case .ringing: model.alarmRang()
        case .ended: await model.handle(.arrived(at: now))
        default: break
        }
    }
}
#endif
```

- [ ] **Step 4: iOS, run to verify pass**

Same command as Step 2. Expected: `** TEST SUCCEEDED **`, 13 tests. Then confirm a Release build still compiles without the demo code: `cd ios && xcodebuild build -scheme TrainAlarm -configuration Release -destination "generic/platform=iOS Simulator" 2>&1 | grep -E "error:|BUILD (SUCCEEDED|FAILED)"`. Expected: `BUILD SUCCEEDED`.

- [ ] **Step 5: Android, write the failing tests**

```kotlin
package com.trainalarm.app.demo

import com.trainalarm.app.EndReason
import com.trainalarm.app.BoardState
import com.trainalarm.app.SessionModel
import com.trainalarm.app.SessionPhase
import com.trainalarm.app.SettingsStore
import com.trainalarm.app.KeyValues
import com.trainalarm.app.model.Station
import com.trainalarm.app.provider.StationDirectory
import com.trainalarm.app.tracking.FakeClock
import com.trainalarm.app.tracking.FakeLocationSource
import java.time.Instant
import java.time.LocalDate
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioTest {
    private class MemoryKeyValues : KeyValues {
        val map = mutableMapOf<String, Any>()
        override fun getString(key: String) = map[key] as? String
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getInt(key: String) = map[key] as? Int
        override fun putInt(key: String, value: Int) { map[key] = value }
    }

    private val json = """[{"crs":"KGX","name":"London Kings Cross","lat":51.5309,"lon":-0.1233},{"crs":"PBO","name":"Peterborough","lat":52.5748,"lon":-0.2504},{"crs":"EDB","name":"Edinburgh","lat":55.9521,"lon":-3.1894}]"""
    private val start = Instant.parse("2026-07-01T12:00:00Z")

    private suspend fun run(scope: CoroutineScope, scenario: Scenario): SessionModel {
        val directory = StationDirectory.parse(json)
        val clock = FakeClock(start)
        val model = SessionModel(
            DemoProvider(clock), NoopAlarmScheduler(), directory, FakeLocationSource(), clock,
            DemoPermissions(), SettingsStore(MemoryKeyValues()), scope
        )
        scenario.apply(model, directory, clock.now())
        return model
    }

    private fun scenario(name: Scenario, block: suspend (SessionModel) -> Unit) = runBlocking<Unit> {
        val model = run(this, name)
        block(model)
        if (model.phase == SessionPhase.Tracking || model.phase == SessionPhase.Ringing) model.imOff()
    }

    @Test fun theIntentExtraNamesAScenario() {
        assertEquals(Scenario.TRACKING_LATE, Scenario.from("tracking-late"))
        assertEquals(Scenario.FEED_LOST, Scenario.from("feed-lost"))
        assertNull(Scenario.from("nonsense"))
        assertNull(Scenario.from(null))
    }

    @Test fun everyScenarioNameRoundTrips() {
        for (s in Scenario.values()) assertEquals(s, Scenario.from(s.argument))
        assertEquals(12, Scenario.values().size)
    }

    @Test fun demoBoardHasThreeTrainsOneCancelledAndOnlyFromKingsCross() = runBlocking<Unit> {
        val provider = DemoProvider(FakeClock(start))
        val kgx = Station("KGX", "London Kings Cross", 0.0, 0.0)
        val edb = Station("EDB", "Edinburgh", 0.0, 0.0)
        val board = provider.departureBoard(kgx, start, edb)
        assertEquals(3, board.size)
        assertTrue(board[2].journey.stops[0].isCancelled)
        assertEquals(emptyList<Any>(), provider.departureBoard(edb, start, kgx))
        assertEquals(5, provider.serviceDetails("demo-1", LocalDate.of(2026, 7, 1)).journey.stops.size)
    }

    @Test fun homeAndSettingsLeaveTheModelAlone() {
        for (s in listOf(Scenario.HOME, Scenario.SETTINGS)) scenario(s) { model ->
            assertEquals(SessionPhase.Selecting, model.phase)
            assertNull(model.from)
        }
        assertTrue(Scenario.SETTINGS.opensSettings)
        assertFalse(Scenario.HOME.opensSettings)
    }

    @Test fun boardAndConfirmScenarios() {
        scenario(Scenario.BOARD) { model -> assertEquals(3, (model.board as BoardState.Loaded).services.size) }
        scenario(Scenario.CONFIRM) { model ->
            assertEquals(SessionPhase.Confirming, model.phase)
            assertEquals(15, model.confirm?.leadMinutes)
            assertNotNull(model.alarmAt)
        }
    }

    @Test fun trackingScenariosShowTheirStates() {
        scenario(Scenario.TRACKING) { assertEquals(SessionPhase.Tracking, it.phase); assertEquals(0, it.display.delayMinutes) }
        scenario(Scenario.TRACKING_LATE) { assertEquals(4, it.display.delayMinutes) }
        scenario(Scenario.CANCELLED) { assertTrue(it.display.cancelled) }
        scenario(Scenario.DROPPED) { assertTrue(it.display.destinationDropped) }
        scenario(Scenario.FEED_LOST) { assertTrue(it.display.feedLost); assertNotNull(it.display.lastGoodAt) }
        scenario(Scenario.ALARM_NOT_SET) { assertTrue(it.display.alarmNotSet) }
    }

    @Test fun ringingAndEndedScenarios() {
        scenario(Scenario.RINGING) { assertEquals(SessionPhase.Ringing, it.phase) }
        scenario(Scenario.ENDED) { assertEquals(SessionPhase.Ended(EndReason.ARRIVED), it.phase) }
    }

    @Test fun demoClockRunsFasterThanTheWallClock() = runBlocking<Unit> {
        val clock = DemoClock(60.0)
        val before = clock.now()
        delay(100)
        assertTrue(Duration.between(before, clock.now()).seconds >= 3)   // 0.1 s of wall time is 6 s of demo time
    }

    @Test fun demoClockSleepIsShortenedBySpeed() = runBlocking<Unit> {
        val start = System.nanoTime()
        DemoClock(60.0).sleep(6.0)   // 0.1 s of wall time
        assertTrue(System.nanoTime() - start < 1_000_000_000L)
    }

    @Test fun demoAlarmRingsWhenTheClockReachesTheFireTime() = runBlocking<Unit> {
        val clock = FakeClock(Instant.parse("2026-11-12T08:00:00Z"))
        var rang = 0
        val scheduler = DemoAlarmScheduler(clock, this) { rang += 1 }
        scheduler.schedule(clock.now().plusSeconds(30))
        clock.awaitSleepCount(1)
        assertEquals(0, rang)
        clock.tick(30.0)
        withTimeout(2_000) { while (rang == 0) yield() }
        assertEquals(1, rang)
    }

    @Test fun cancellingTheDemoAlarmStopsItRinging() = runBlocking<Unit> {
        val clock = FakeClock(Instant.parse("2026-11-12T08:00:00Z"))
        var rang = 0
        val scheduler = DemoAlarmScheduler(clock, this) { rang += 1 }
        scheduler.schedule(clock.now().plusSeconds(30))
        clock.awaitSleepCount(1)
        scheduler.cancel()
        clock.tick(30.0)
        repeat(20) { yield() }
        assertEquals(0, rang)
    }

    @Test fun demoTimesDoNotMoveWhenTheClockDoes() = runBlocking<Unit> {
        val clock = FakeClock(Instant.parse("2026-11-12T08:00:00Z"))
        val provider = DemoProvider(clock)
        val first = provider.serviceDetails("demo-1", LocalDate.of(2026, 11, 12))
        clock.tick(120.0)
        val second = provider.serviceDetails("demo-1", LocalDate.of(2026, 11, 12))
        assertEquals(first.journey.stops.map { it.scheduledDeparture }, second.journey.stops.map { it.scheduledDeparture })
    }
}
```

- [ ] **Step 6: Android, run to verify failure**

```bash
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew testDebugUnitTest --offline --tests "com.trainalarm.app.demo.ScenarioTest" 2>&1 | grep -E "^e: |BUILD" | head -3
```
Expected: unresolved `DemoProvider`, `Scenario`.

- [ ] **Step 7: Android, implement**

`DemoProvider.kt`:

```kotlin
package com.trainalarm.app.demo

import com.trainalarm.app.PermissionKind
import com.trainalarm.app.PermissionStatus
import com.trainalarm.app.PermissionsGateway
import com.trainalarm.app.alarm.AlarmScheduler
import com.trainalarm.app.model.Journey
import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.model.Stop
import com.trainalarm.app.model.StopDisplay
import com.trainalarm.app.provider.ProviderError
import com.trainalarm.app.provider.TrainDataProvider
import com.trainalarm.app.tracking.TrackerClock
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Scripted trains for demo mode and screenshots. Debug builds only. All times are relative to the clock at
 * the moment the provider was made, and stay fixed after that. No real alarm is ever armed (see [DemoAlarmScheduler]).
 */
class DemoProvider(clock: TrackerClock) : TrainDataProvider {
    /** Fixed when the provider is made: a later poll must see the same timetable, or the ETA would drift. */
    private val epoch = clock.now()

    private class Call(val crs: String, val name: String, val minutes: Long)
    private class Train(val id: String, val departsInMinutes: Long, val delay: Long, val cancelled: Boolean)

    override suspend fun searchStations(query: String): List<Station> = emptyList()

    override suspend fun departureBoard(station: Station, from: Instant, callingAt: Station?): List<Service> {
        if (station.id != "KGX") return emptyList()
        if (callingAt != null && route.drop(1).none { it.crs == callingAt.id }) return emptyList()
        return trains.map { service(it, summaryOnly = true) }
    }

    override suspend fun serviceDetails(serviceId: String, date: LocalDate): Service {
        val train = trains.firstOrNull { it.id == serviceId } ?: throw ProviderError.Http(404)
        return service(train, summaryOnly = false)
    }

    private fun service(train: Train, summaryOnly: Boolean): Service {
        val base = epoch.truncatedTo(ChronoUnit.MINUTES).plusSeconds(train.departsInMinutes * 60)
        val stops = route.mapIndexed { index, call ->
            val scheduled = base.plusSeconds(call.minutes * 60)
            val estimated = if (train.cancelled) null else scheduled.plusSeconds(train.delay * 60)
            val isOrigin = index == 0
            Stop(
                station = Station(call.crs, call.name, 0.0, 0.0),
                scheduledArrival = if (isOrigin) null else scheduled,
                scheduledDeparture = scheduled,
                estimatedArrival = if (isOrigin) null else estimated,
                estimatedDeparture = estimated,
                isArrivalCancelled = train.cancelled && !isOrigin,
                isDepartureCancelled = train.cancelled,
                displayAs = if (train.cancelled) StopDisplay.CANCELLED else StopDisplay.CALL,
                hasArrived = false
            )
        }
        val shown = if (summaryOnly) listOf(stops.first()) else stops
        return Service(train.id, "LNER", Journey(stops.first().station, stops.last().station, shown))
    }

    private companion object {
        val route = listOf(
            Call("KGX", "London Kings Cross", 0), Call("PBO", "Peterborough", 45),
            Call("NCL", "Newcastle", 190), Call("BWK", "Berwick-upon-Tweed", 220), Call("EDB", "Edinburgh", 250)
        )
        val trains = listOf(
            Train("demo-1", 20, 0, false), Train("demo-2", 50, 4, false), Train("demo-3", 80, 0, true)
        )
    }
}

/** Demo mode never arms a real alarm. */
class NoopAlarmScheduler : AlarmScheduler {
    override suspend fun schedule(fireAt: Instant) {}
    override suspend fun cancel() {}
}

/** A clock that runs [speed] times faster than the wall clock (`--ei demoSpeed 60`), so a demo reaches the alarm and the arrival quickly. */
class DemoClock(speed: Double, private val wall: () -> Instant = { Instant.now() }) : TrackerClock {
    private val speed = maxOf(speed, 1.0)
    private val start = wall()

    override fun now(): Instant = start.plusNanos((Duration.between(start, wall()).toNanos() * speed).toLong())

    override suspend fun sleep(seconds: Double) = delay((maxOf(seconds, 0.0) / speed * 1000).toLong())
}

/**
 * Demo mode's alarm: nothing is armed with the system, but when the demo clock reaches the fire time the app is told the
 * alarm rang, so a demo run can reach the ringing screen. Replaces any earlier alarm.
 */
class DemoAlarmScheduler(private val clock: TrackerClock, private val scope: CoroutineScope, private val onRing: () -> Unit) : AlarmScheduler {
    private var job: Job? = null

    override suspend fun schedule(fireAt: Instant) {
        job?.cancel()
        val seconds = maxOf(Duration.between(clock.now(), fireAt).toMillis() / 1000.0, 0.0)
        job = scope.launch {
            clock.sleep(seconds)
            onRing()
        }
    }

    override suspend fun cancel() {
        job?.cancel()
        job = null
    }
}

/** Demo mode asks for nothing and everything is granted. */
class DemoPermissions : PermissionsGateway {
    override val requiredKinds: List<PermissionKind> = listOf(PermissionKind.ALARM)   // listed (granted) so Settings shows a row; location stays unlisted so the demo never starts GPS
    override val optionalKinds: List<PermissionKind> = emptyList()
    override fun status(kind: PermissionKind) = PermissionStatus.GRANTED
    override suspend fun request(kind: PermissionKind) = PermissionStatus.GRANTED
}
```

`Scenario.kt`:

```kotlin
package com.trainalarm.app.demo

import com.trainalarm.app.BoardState
import com.trainalarm.app.SessionModel
import com.trainalarm.app.provider.StationDirectory
import com.trainalarm.app.tracking.FailureKind
import com.trainalarm.app.tracking.TrackerEvent
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * Opens the app in a named state with no live data, for screenshots and demos. Debug builds only.
 * Android reads the `scenario` intent extra.
 */
enum class Scenario(val argument: String) {
    HOME("home"), BOARD("board"), CONFIRM("confirm"), TRACKING("tracking"),
    TRACKING_LATE("tracking-late"), CANCELLED("cancelled"), DROPPED("dropped"),
    FEED_LOST("feed-lost"), ALARM_NOT_SET("alarm-not-set"), RINGING("ringing"),
    ENDED("ended"), SETTINGS("settings");

    val opensSettings: Boolean get() = this == SETTINGS

    /** Drives the model from its empty state to this scenario, using the demo trains from KGX to EDB. */
    suspend fun apply(model: SessionModel, directory: StationDirectory, now: Instant) {
        if (this == HOME || this == SETTINGS) return
        val from = directory.station("KGX") ?: return
        val to = directory.station("EDB") ?: return
        model.selectFrom(from)
        model.selectTo(to)
        model.loadBoard()
        if (this == BOARD) return
        val loaded = model.board as? BoardState.Loaded ?: return
        model.choose(loaded.services[0])
        model.setLead(15)
        if (this == CONFIRM) return
        model.start()
        // The tracker publishes its own start-up events. Let them arrive first, or they overwrite ours.
        delay(500)
        val eta = model.display.eta ?: now
        val alarm = model.display.alarmAt ?: now
        when (this) {
            TRACKING_LATE -> model.handle(TrackerEvent.EtaChanged(eta, eta.plusSeconds(240), alarm.plusSeconds(240)))
            CANCELLED -> model.handle(TrackerEvent.ServiceCancelled(now))
            DROPPED -> model.handle(TrackerEvent.DestinationLost(now))
            FEED_LOST -> model.handle(TrackerEvent.FeedLost(now.minusSeconds(360), FailureKind.Network))
            ALARM_NOT_SET -> model.handle(TrackerEvent.AlarmSchedulingFailed(alarm, "demo"))
            RINGING -> model.alarmRang()
            ENDED -> model.handle(TrackerEvent.Arrived(now))
            else -> Unit
        }
    }

    companion object {
        fun from(argument: String?): Scenario? = values().firstOrNull { it.argument == argument }
    }
}
```

- [ ] **Step 8: Android, run to verify pass**

Same command as Step 6. Expected: `BUILD SUCCESSFUL`, 12 tests. Then prove the release unit tests and build do not see debug code: `./gradlew test lint assembleDebug assembleRelease --offline`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add ios android
git commit -m "Feature: Add demo mode and a scenario launcher for debug builds

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Real permissions, GPS control and the "nearest station" suggestion

**Files:**
- Create: `ios/Sources/TrainAlarm/App/SystemPermissions.swift`, `ios/Sources/TrainAlarm/Providers/StationDirectory+Nearest.swift`
- Modify: `ios/Sources/TrainAlarm/App/Permissions.swift`, `.../App/SessionTypes.swift`, `.../App/SessionModel.swift`
- Create (tests): `ios/Tests/TrainAlarmTests/App/PermissionMappingTests.swift`, `.../App/SessionModelNearestTests.swift`, `.../Provider/StationDirectoryNearestTests.swift`
- Create: `android/app/src/main/java/com/trainalarm/app/SystemPermissions.kt`, `.../provider/StationDirectoryNearest.kt`
- Modify: `android/app/src/main/java/com/trainalarm/app/Permissions.kt`, `.../SessionTypes.kt`, `.../SessionModel.kt`
- Create (tests): `android/app/src/test/java/com/trainalarm/app/SystemPermissionsTest.kt`, `.../SessionModelNearestTest.kt`, `.../provider/StationDirectoryNearestTest.kt`

**Interfaces:**
- Consumes: `PermissionsGateway`, `SessionModel` (Tasks 7 and 8), `CoreLocationSource.start/stop` and `PlatformLocationSource.start/stop` (Task 5), `Geo.distanceMetres`, `AlarmKitClient`, `AlarmPermissions` (existing Android helper).
- Produces: `LocationControl` (iOS `start()`, `stop()`; Android `start(): Boolean`, `stop()`), and `SessionModel(..., locationControl: LocationControl? = nil)`. The model starts it at Start when location is allowed, and stops it on "I'm off" and on arrival.
- Produces: `StationDirectory.nearest(latitude:longitude:) -> Station?`; `SessionModel.useNearestStation()`, `isFindingNearest`, `nearestFailure: NearestFailure?` (`permissionDenied`, `noFix`), `fixWaitAttempts = 10`, `fixWaitInterval = 0.5` s. It asks for location only when called (the user taps the chip), never re-prompts a refusal, fills From on success, and stops the GPS after.
- Produces (iOS): `PermissionMapping.status(_:)` for `AlarmAuthorization` and `CLAuthorizationStatus`; `SystemPermissions` (required `.alarm`; optional `.location`; `.notifications` and `.fullScreenIntent` are always granted on iOS).
- Produces (Android): `PermissionPlatform` (`isGranted`, `prompt`), `AskedStore`, `KeyValuesAskedStore`, `SystemPermissions(platform, asked)` (required `ALARM`, `NOTIFICATIONS`; optional `LOCATION`, `FULL_SCREEN_INTENT`), `PermissionPrompter`, `AndroidPermissionPlatform`, `ActivityPermissionPrompter` (constructed in `MainActivity.onCreate`, Task 12), and `PlatformLocationSource.asControl()`. Policy: a permission is prompted once; a refusal is `DENIED` afterwards and is never re-prompted automatically.

- [ ] **Step 1: iOS, write the failing tests**

`PermissionMappingTests.swift`:

```swift
import CoreLocation
import XCTest
@testable import TrainAlarm

final class PermissionMappingTests: XCTestCase {
    func testAlarmKitAuthorizationMapsToPermissionStatus() {
        XCTAssertEqual(PermissionMapping.status(AlarmAuthorization.authorized), .granted)
        XCTAssertEqual(PermissionMapping.status(AlarmAuthorization.denied), .denied)
        XCTAssertEqual(PermissionMapping.status(AlarmAuthorization.notDetermined), .notDetermined)
    }

    func testLocationAuthorizationMapsToPermissionStatus() {
        XCTAssertEqual(PermissionMapping.status(CLAuthorizationStatus.authorizedWhenInUse), .granted)
        XCTAssertEqual(PermissionMapping.status(CLAuthorizationStatus.authorizedAlways), .granted)
        XCTAssertEqual(PermissionMapping.status(CLAuthorizationStatus.denied), .denied)
        XCTAssertEqual(PermissionMapping.status(CLAuthorizationStatus.restricted), .denied)
        XCTAssertEqual(PermissionMapping.status(CLAuthorizationStatus.notDetermined), .notDetermined)
    }

    func testIosRequiresAlarmsAndOnlyAsksForLocationOptionally() {
        let permissions = SystemPermissions()
        XCTAssertEqual(permissions.requiredKinds, [.alarm])
        XCTAssertEqual(permissions.optionalKinds, [.location])
        XCTAssertEqual(permissions.status(.notifications), .granted)
        XCTAssertEqual(permissions.status(.fullScreenIntent), .granted)
    }
}
```

`StationDirectoryNearestTests.swift`:

```swift
import XCTest
@testable import TrainAlarm

final class StationDirectoryNearestTests: XCTestCase {
    private let json = #"[{"crs":"WAT","name":"London Waterloo","lat":51.5031,"lon":-0.1132},{"crs":"WOK","name":"Woking","lat":51.3189,"lon":-0.5569},{"crs":"BSK","name":"Basingstoke","lat":51.2686,"lon":-1.0872}]"#

    func testNearestPicksTheClosestStation() throws {
        let directory = try StationDirectory.parse(Data(json.utf8))
        XCTAssertEqual(directory.nearest(latitude: 51.32, longitude: -0.56)?.id, "WOK")
        XCTAssertEqual(directory.nearest(latitude: 51.50, longitude: -0.12)?.id, "WAT")
        XCTAssertEqual(directory.nearest(latitude: 51.0, longitude: -1.5)?.id, "BSK")
    }

    func testNearestOfAnEmptyDirectoryIsNil() throws {
        XCTAssertNil(try StationDirectory.parse(Data("[]".utf8)).nearest(latitude: 51, longitude: 0))
    }
}
```

`SessionModelNearestTests.swift`:

```swift
import XCTest
@testable import TrainAlarm

@MainActor
final class SessionModelNearestTests: XCTestCase {
    final class FakeLocationControl: LocationControl {
        private(set) var started = 0
        private(set) var stopped = 0
        func start() { started += 1 }
        func stop() { stopped += 1 }
    }

    struct Harness {
        let model: SessionModel
        let provider: SessionModelTests.ScriptedProvider
        let location: FakeLocationSource
        let control: FakeLocationControl
        let permissions: SessionModelTests.FakePermissions
        let clock: FakeClock
    }

    private let json = #"[{"crs":"WAT","name":"London Waterloo","lat":51.5031,"lon":-0.1132},{"crs":"WOK","name":"Woking","lat":51.3189,"lon":-0.5569},{"crs":"BSK","name":"Basingstoke","lat":51.2686,"lon":-1.0872}]"#
    private var suite = ""

    override func setUp() { suite = "nearest-test-\(UUID().uuidString)" }
    override func tearDown() { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }

    private func make() throws -> Harness {
        let provider = SessionModelTests.ScriptedProvider()
        provider.board = .success([ServiceBuilder.happyPath()])
        let location = FakeLocationSource()
        let control = FakeLocationControl()
        let permissions = SessionModelTests.FakePermissions()
        let clock = FakeClock(start: ServiceBuilder.time("07:00:00"))
        let model = SessionModel(provider: provider, scheduler: FakeAlarmScheduler(),
                                 directory: try StationDirectory.parse(Data(json.utf8)), location: location, clock: clock,
                                 permissions: permissions, settings: SettingsStore(defaults: UserDefaults(suiteName: suite)!),
                                 locationControl: control)
        return Harness(model: model, provider: provider, location: location, control: control, permissions: permissions, clock: clock)
    }

    private func fix(_ lat: Double, _ lon: Double, accuracy: Double = 20) -> LocationFix {
        LocationFix(latitude: lat, longitude: lon, timestamp: ServiceBuilder.time("07:00:00"), accuracy: accuracy)
    }

    func testNearestFillsFromAndStopsTheGps() async throws {
        let h = try make()
        h.location.fix = fix(51.32, -0.56)
        await h.model.useNearestStation()
        XCTAssertEqual(h.model.from?.id, "WOK")
        XCTAssertNil(h.model.nearestFailure)
        XCTAssertFalse(h.model.isFindingNearest)
        XCTAssertEqual(h.control.started, 1)
        XCTAssertEqual(h.control.stopped, 1)
    }

    func testNearestAsksForLocationOnlyWhenNotDeterminedAndReportsARefusal() async throws {
        let h = try make()
        h.permissions.statuses[.location] = .notDetermined
        h.permissions.answers[.location] = .denied
        await h.model.useNearestStation()
        XCTAssertEqual(h.permissions.requested, [.location])
        XCTAssertEqual(h.model.nearestFailure, .permissionDenied)
        XCTAssertNil(h.model.from)
        XCTAssertEqual(h.control.started, 0)
    }

    func testNearestNeverReprompsADeniedPermission() async throws {
        let h = try make()
        h.permissions.statuses[.location] = .denied
        await h.model.useNearestStation()
        XCTAssertEqual(h.permissions.requested, [])
        XCTAssertEqual(h.model.nearestFailure, .permissionDenied)
    }

    func testNearestWithNoFixInTimeIsReported() async throws {
        let h = try make()
        let task = Task { await h.model.useNearestStation() }
        for attempt in 1...SessionModel.fixWaitAttempts {
            await h.clock.waitForSleepCount(attempt)
            h.clock.tick(advancing: SessionModel.fixWaitInterval)
        }
        await task.value
        XCTAssertEqual(h.model.nearestFailure, .noFix)
        XCTAssertNil(h.model.from)
        XCTAssertEqual(h.control.stopped, 1)
    }

    func testAFixWithUnknownAccuracyIsNotUsed() async throws {
        let h = try make()
        h.location.fix = fix(51.32, -0.56, accuracy: .infinity)
        let task = Task { await h.model.useNearestStation() }
        for attempt in 1...SessionModel.fixWaitAttempts {
            await h.clock.waitForSleepCount(attempt)
            h.clock.tick(advancing: SessionModel.fixWaitInterval)
        }
        await task.value
        XCTAssertEqual(h.model.nearestFailure, .noFix)
    }

    func testStartBeginsTheGpsAndImOffStopsIt() async throws {
        let h = try make()
        let wat = h.model.search("waterloo")[0], bsk = h.model.search("basing")[0]
        h.model.selectFrom(wat)
        h.model.selectTo(bsk)
        await h.model.loadBoard()
        guard case let .loaded(services) = h.model.board else { return XCTFail("board not loaded") }
        await h.model.choose(services[0])
        h.model.setLead(15)
        await h.model.start()
        await h.clock.waitForSleepCount(1)
        XCTAssertEqual(h.control.started, 1)
        await h.model.imOff()
        XCTAssertEqual(h.control.stopped, 1)
    }
}
```

- [ ] **Step 2: iOS, run to verify failure**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/PermissionMappingTests -only-testing:TrainAlarmTests/StationDirectoryNearestTests -only-testing:TrainAlarmTests/SessionModelNearestTests 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | head -3
```
Expected: `cannot find 'PermissionMapping' in scope` and similar.

- [ ] **Step 3: iOS, implement**

Append to `Permissions.swift`:

```swift

/// Starts and stops GPS updates. The tracker only reads `LocationSource.latestFix()`, so something
/// has to start the updates: the model does, once location is allowed.
protocol LocationControl {
    func start()
    func stop()
}

extension CoreLocationSource: LocationControl {}
```

Append to `SessionTypes.swift`:

```swift

enum NearestFailure: Equatable { case permissionDenied, noFix }
```

Create `StationDirectory+Nearest.swift`:

```swift
import Foundation

extension StationDirectory {
    /// The station closest to a point, or nil if the directory is empty.
    func nearest(latitude: Double, longitude: Double) -> Station? {
        func distance(_ station: Station) -> Double {
            Geo.distanceMetres(fromLatitude: latitude, longitude: longitude,
                               toLatitude: station.latitude, longitude: station.longitude)
        }
        return stations.min { distance($0) < distance($1) }
    }
}
```

Create `SystemPermissions.swift`:

```swift
import CoreLocation
import Foundation

enum PermissionMapping {
    static func status(_ authorization: AlarmAuthorization) -> PermissionStatus {
        switch authorization {
        case .authorized: return .granted
        case .denied: return .denied
        case .notDetermined: return .notDetermined
        }
    }

    static func status(_ authorization: CLAuthorizationStatus) -> PermissionStatus {
        switch authorization {
        case .authorizedAlways, .authorizedWhenInUse: return .granted
        case .denied, .restricted: return .denied
        case .notDetermined: return .notDetermined
        @unknown default: return .denied
        }
    }
}

/// The real iOS permissions: AlarmKit (required) and location while in use (optional). Notification
/// and full-screen-intent permissions are Android only, so they are always granted here.
final class SystemPermissions: NSObject, PermissionsGateway, CLLocationManagerDelegate {
    var requiredKinds: [PermissionKind] { [.alarm] }
    var optionalKinds: [PermissionKind] { [.location] }

    private let alarmClient: AlarmKitClient
    private let manager = CLLocationManager()
    private var pending: CheckedContinuation<PermissionStatus, Never>?

    init(alarmClient: AlarmKitClient = SystemAlarmKitClient()) {
        self.alarmClient = alarmClient
        super.init()
        manager.delegate = self
    }

    func status(_ kind: PermissionKind) -> PermissionStatus {
        switch kind {
        case .alarm: return PermissionMapping.status(alarmClient.authorization)
        case .location: return PermissionMapping.status(manager.authorizationStatus)
        case .notifications, .fullScreenIntent: return .granted
        }
    }

    func request(_ kind: PermissionKind) async -> PermissionStatus {
        switch kind {
        case .alarm:
            guard let result = try? await alarmClient.requestAuthorization() else { return .denied }
            return PermissionMapping.status(result)
        case .location:
            guard manager.authorizationStatus == .notDetermined else { return status(.location) }
            return await withCheckedContinuation { continuation in
                pending = continuation
                manager.requestWhenInUseAuthorization()
            }
        case .notifications, .fullScreenIntent:
            return .granted
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard manager.authorizationStatus != .notDetermined, let continuation = pending else { return }
        pending = nil
        continuation.resume(returning: PermissionMapping.status(manager.authorizationStatus))
    }
}
```

In `SessionModel.swift`: (a) add `locationControl: LocationControl? = nil` as the last `init` parameter (after `settings: SettingsStore`), store it with `@ObservationIgnored private let locationControl: LocationControl?` and `self.locationControl = locationControl`. (b) Add the two properties after `private(set) var display = TrackingDisplay()`:

```swift
    private(set) var isFindingNearest = false
    private(set) var nearestFailure: NearestFailure?
```
(c) In `start()`, after `permissionWarnings = warnings` add:

```swift
        if permissions.status(.location) == .granted { locationControl?.start() }
```
(d) In `imOff()` and in the `.arrived` case of `handle`, add `locationControl?.stop()` right after `tracker = nil`. (e) Add before `// MARK: Helpers`:

```swift
    // MARK: Nearest station

    static let fixWaitAttempts = 10
    static let fixWaitInterval: TimeInterval = 0.5

    /// The "Nearest" chip. Asks for location only now, never re-prompts a refusal, fills From with the
    /// closest station, and stops the GPS again.
    func useNearestStation() async {
        guard isEditable else { return }
        isFindingNearest = true
        nearestFailure = nil
        defer { isFindingNearest = false }
        var status = permissions.status(.location)
        if status == .notDetermined { status = await permissions.request(.location) }
        guard status == .granted else { nearestFailure = .permissionDenied; return }
        locationControl?.start()
        for attempt in 0...Self.fixWaitAttempts {
            if let fix = location.latestFix(), fix.accuracy.isFinite,
               let station = directory.nearest(latitude: fix.latitude, longitude: fix.longitude) {
                locationControl?.stop()
                selectFrom(station)
                return
            }
            if attempt == Self.fixWaitAttempts { break }
            do { try await clock.sleep(for: Self.fixWaitInterval) } catch { break }
        }
        locationControl?.stop()
        nearestFailure = .noFix
    }
```

- [ ] **Step 4: iOS, run to verify pass**

Same command as Step 2. Expected: `** TEST SUCCEEDED **`, 10 tests (3 + 2 + 5... plus the model start test = 3 + 2 + 6 = 11; report the actual number). Then the whole iOS suite.

- [ ] **Step 5: Android, write the failing tests**

`StationDirectoryNearestTest.kt`:

```kotlin
package com.trainalarm.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationDirectoryNearestTest {
    private val json = """[{"crs":"WAT","name":"London Waterloo","lat":51.5031,"lon":-0.1132},{"crs":"WOK","name":"Woking","lat":51.3189,"lon":-0.5569},{"crs":"BSK","name":"Basingstoke","lat":51.2686,"lon":-1.0872}]"""

    @Test fun nearest_picksTheClosestStation() {
        val directory = StationDirectory.parse(json)
        assertEquals("WOK", directory.nearest(51.32, -0.56)?.id)
        assertEquals("WAT", directory.nearest(51.50, -0.12)?.id)
        assertEquals("BSK", directory.nearest(51.0, -1.5)?.id)
    }

    @Test fun nearest_ofAnEmptyDirectoryIsNull() = assertNull(StationDirectory.parse("[]").nearest(51.0, 0.0))
}
```

`SystemPermissionsTest.kt`:

```kotlin
package com.trainalarm.app

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.runBlocking

class SystemPermissionsTest {
    private class FakePlatform : PermissionPlatform {
        val granted = mutableSetOf<PermissionKind>()
        val grantOnPrompt = mutableSetOf<PermissionKind>()
        val prompts = mutableListOf<PermissionKind>()
        override fun isGranted(kind: PermissionKind) = kind in granted
        override suspend fun prompt(kind: PermissionKind) {
            prompts += kind
            if (kind in grantOnPrompt) granted += kind
        }
    }

    private class MemoryAsked : AskedStore {
        val asked = mutableSetOf<PermissionKind>()
        override fun wasAsked(kind: PermissionKind) = kind in asked
        override fun markAsked(kind: PermissionKind) { asked += kind }
    }

    private val platform = FakePlatform()
    private val asked = MemoryAsked()
    private val permissions = SystemPermissions(platform, asked)

    @Test fun androidRequiresAlarmsAndNotifications() {
        assertEquals(listOf(PermissionKind.ALARM, PermissionKind.NOTIFICATIONS), permissions.requiredKinds)
        assertEquals(listOf(PermissionKind.LOCATION, PermissionKind.FULL_SCREEN_INTENT), permissions.optionalKinds)
    }

    @Test fun statusIsGrantedNotDeterminedThenDeniedOnceAsked() {
        assertEquals(PermissionStatus.NOT_DETERMINED, permissions.status(PermissionKind.ALARM))
        asked.markAsked(PermissionKind.ALARM)
        assertEquals(PermissionStatus.DENIED, permissions.status(PermissionKind.ALARM))
        platform.granted += PermissionKind.ALARM
        assertEquals(PermissionStatus.GRANTED, permissions.status(PermissionKind.ALARM))
    }

    @Test fun aRequestPromptsOnceAndReturnsTheOutcome() = runBlocking<Unit> {
        platform.grantOnPrompt += PermissionKind.LOCATION
        assertEquals(PermissionStatus.GRANTED, permissions.request(PermissionKind.LOCATION))
        assertEquals(listOf(PermissionKind.LOCATION), platform.prompts)
    }

    @Test fun aRefusalIsNeverRepromptedAutomatically() = runBlocking<Unit> {
        assertEquals(PermissionStatus.DENIED, permissions.request(PermissionKind.NOTIFICATIONS))
        assertEquals(PermissionStatus.DENIED, permissions.request(PermissionKind.NOTIFICATIONS))
        assertEquals(listOf(PermissionKind.NOTIFICATIONS), platform.prompts)
    }

    @Test fun anAlreadyGrantedPermissionIsNotPrompted() = runBlocking<Unit> {
        platform.granted += PermissionKind.ALARM
        assertEquals(PermissionStatus.GRANTED, permissions.request(PermissionKind.ALARM))
        assertEquals(emptyList<PermissionKind>(), platform.prompts)
    }

    @Test fun theAskedStoreRemembersAcrossInstances() {
        val kv = object : KeyValues {
            val map = mutableMapOf<String, Any>()
            override fun getString(key: String) = map[key] as? String
            override fun putString(key: String, value: String) { map[key] = value }
            override fun getInt(key: String) = map[key] as? Int
            override fun putInt(key: String, value: Int) { map[key] = value }
        }
        KeyValuesAskedStore(kv).markAsked(PermissionKind.LOCATION)
        assertEquals(true, KeyValuesAskedStore(kv).wasAsked(PermissionKind.LOCATION))
        assertEquals(false, KeyValuesAskedStore(kv).wasAsked(PermissionKind.ALARM))
    }
}
```

`SessionModelNearestTest.kt`:

```kotlin
package com.trainalarm.app

import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.provider.StationDirectory
import com.trainalarm.app.provider.TrainDataProvider
import com.trainalarm.app.tracking.FakeAlarmScheduler
import com.trainalarm.app.tracking.FakeClock
import com.trainalarm.app.tracking.FakeLocationSource
import com.trainalarm.app.tracking.LocationFix
import com.trainalarm.app.tracking.ServiceBuilder
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SessionModelNearestTest {
    private class Provider : TrainDataProvider {
        override suspend fun searchStations(query: String): List<Station> = emptyList()
        override suspend fun departureBoard(station: Station, from: Instant, callingAt: Station?): List<Service> =
            listOf(ServiceBuilder.happyPath())
        override suspend fun serviceDetails(serviceId: String, date: LocalDate): Service = ServiceBuilder.happyPath()
    }

    private class Permissions : PermissionsGateway {
        override val requiredKinds = listOf(PermissionKind.ALARM)
        override val optionalKinds = listOf(PermissionKind.LOCATION)
        val statuses = mutableMapOf(PermissionKind.ALARM to PermissionStatus.GRANTED, PermissionKind.LOCATION to PermissionStatus.GRANTED)
        val answers = mutableMapOf<PermissionKind, PermissionStatus>()
        val requested = mutableListOf<PermissionKind>()
        override fun status(kind: PermissionKind) = statuses[kind] ?: PermissionStatus.GRANTED
        override suspend fun request(kind: PermissionKind): PermissionStatus {
            requested += kind
            return answers[kind] ?: status(kind)
        }
    }

    private class Control : LocationControl {
        var started = 0
        var stopped = 0
        override fun start(): Boolean { started += 1; return true }
        override fun stop() { stopped += 1 }
    }

    private class MemoryKeyValues : KeyValues {
        val map = mutableMapOf<String, Any>()
        override fun getString(key: String) = map[key] as? String
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getInt(key: String) = map[key] as? Int
        override fun putInt(key: String, value: Int) { map[key] = value }
    }

    private class Harness(scope: CoroutineScope) {
        val directory = StationDirectory.parse(
            """[{"crs":"WAT","name":"London Waterloo","lat":51.5031,"lon":-0.1132},{"crs":"WOK","name":"Woking","lat":51.3189,"lon":-0.5569},{"crs":"BSK","name":"Basingstoke","lat":51.2686,"lon":-1.0872}]"""
        )
        val location = FakeLocationSource()
        val control = Control()
        val permissions = Permissions()
        val clock = FakeClock(ServiceBuilder.time("07:00:00"))
        val model = SessionModel(
            Provider(), FakeAlarmScheduler(), directory, location, clock, permissions,
            SettingsStore(MemoryKeyValues()), scope, locationControl = control
        )
    }

    private fun run(block: suspend Harness.(CoroutineScope) -> Unit) = runBlocking<Unit> { Harness(this).block(this) }

    private fun fix(lat: Double, lon: Double, accuracy: Double = 20.0) =
        LocationFix(lat, lon, ServiceBuilder.time("07:00:00"), accuracy)

    @Test fun nearestFillsFromAndStopsTheGps() = run {
        location.fix = fix(51.32, -0.56)
        model.useNearestStation()
        assertEquals("WOK", model.from?.id)
        assertNull(model.nearestFailure)
        assertFalse(model.isFindingNearest)
        assertEquals(1, control.started)
        assertEquals(1, control.stopped)
    }

    @Test fun nearestAsksForLocationOnlyWhenNotDeterminedAndReportsARefusal() = run {
        permissions.statuses[PermissionKind.LOCATION] = PermissionStatus.NOT_DETERMINED
        permissions.answers[PermissionKind.LOCATION] = PermissionStatus.DENIED
        model.useNearestStation()
        assertEquals(listOf(PermissionKind.LOCATION), permissions.requested)
        assertEquals(NearestFailure.PERMISSION_DENIED, model.nearestFailure)
        assertNull(model.from)
        assertEquals(0, control.started)
    }

    @Test fun nearestNeverReprompsADeniedPermission() = run {
        permissions.statuses[PermissionKind.LOCATION] = PermissionStatus.DENIED
        model.useNearestStation()
        assertEquals(emptyList<PermissionKind>(), permissions.requested)
        assertEquals(NearestFailure.PERMISSION_DENIED, model.nearestFailure)
    }

    @Test fun nearestWithNoFixInTimeIsReported() = run { scope ->
        val job = scope.launch { model.useNearestStation() }
        for (attempt in 1..SessionModel.FIX_WAIT_ATTEMPTS) {
            clock.awaitSleepCount(attempt)
            clock.tick(SessionModel.FIX_WAIT_INTERVAL_SECONDS)
        }
        job.join()
        assertEquals(NearestFailure.NO_FIX, model.nearestFailure)
        assertNull(model.from)
        assertEquals(1, control.stopped)
    }

    @Test fun aFixWithUnknownAccuracyIsNotUsed() = run { scope ->
        location.fix = fix(51.32, -0.56, accuracy = Double.POSITIVE_INFINITY)
        val job = scope.launch { model.useNearestStation() }
        for (attempt in 1..SessionModel.FIX_WAIT_ATTEMPTS) {
            clock.awaitSleepCount(attempt)
            clock.tick(SessionModel.FIX_WAIT_INTERVAL_SECONDS)
        }
        job.join()
        assertEquals(NearestFailure.NO_FIX, model.nearestFailure)
    }

    @Test fun startBeginsTheGpsAndImOffStopsIt() = run {
        model.selectFrom(model.search("waterloo")[0])
        model.selectTo(model.search("basing")[0])
        model.loadBoard()
        model.choose((model.board as BoardState.Loaded).services[0])
        model.setLead(15)
        model.start()
        clock.awaitSleepCount(1)
        assertEquals(1, control.started)
        model.imOff()
        assertEquals(1, control.stopped)
    }
}
```

- [ ] **Step 6: Android, run to verify failure**

```bash
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew testDebugUnitTest --offline --tests "com.trainalarm.app.SystemPermissionsTest" --tests "com.trainalarm.app.SessionModelNearestTest" --tests "com.trainalarm.app.provider.StationDirectoryNearestTest" 2>&1 | grep -E "^e: |BUILD" | head -3
```
Expected: unresolved `SystemPermissions`, `LocationControl`, `nearest`.

- [ ] **Step 7: Android, implement**

Append to `Permissions.kt`:

```kotlin

/**
 * Starts and stops GPS updates. The tracker only reads `LocationSource.latestFix()`, so something
 * has to start the updates: the model does, once location is allowed. `start` is false if it cannot.
 */
interface LocationControl {
    fun start(): Boolean
    fun stop()
}
```

Append to `SessionTypes.kt`:

```kotlin

enum class NearestFailure { PERMISSION_DENIED, NO_FIX }
```

Create `provider/StationDirectoryNearest.kt`:

```kotlin
package com.trainalarm.app.provider

import com.trainalarm.app.model.Station
import com.trainalarm.app.tracking.Geo

/** The station closest to a point, or null if the directory is empty. */
fun StationDirectory.nearest(latitude: Double, longitude: Double): Station? =
    stations.minByOrNull { Geo.distanceMetres(latitude, longitude, it.latitude, it.longitude) }
```

Create `SystemPermissions.kt`:

```kotlin
package com.trainalarm.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.trainalarm.app.alarm.AlarmPermissions
import com.trainalarm.app.tracking.PlatformLocationSource
import kotlinx.coroutines.CompletableDeferred

/** The OS operations the gateway needs, so its policy is tested without Android. */
interface PermissionPlatform {
    fun isGranted(kind: PermissionKind): Boolean

    /** Shows the system prompt or settings screen for [kind] and returns once the user is back. */
    suspend fun prompt(kind: PermissionKind)
}

/** Remembers which permissions have been asked for, so a refusal is not re-prompted automatically. */
interface AskedStore {
    fun wasAsked(kind: PermissionKind): Boolean
    fun markAsked(kind: PermissionKind)
}

class KeyValuesAskedStore(private val store: KeyValues) : AskedStore {
    override fun wasAsked(kind: PermissionKind) = store.getInt("asked.${kind.name}") == 1
    override fun markAsked(kind: PermissionKind) = store.putInt("asked.${kind.name}", 1)
}

/**
 * The real Android permissions. Required: exact alarms and notifications. Optional: location (while
 * in use only) and, on Android 14 and later, full-screen intent. A permission is prompted once; after
 * a refusal it reads as DENIED and is never re-prompted automatically.
 */
class SystemPermissions(private val platform: PermissionPlatform, private val asked: AskedStore) : PermissionsGateway {
    override val requiredKinds = listOf(PermissionKind.ALARM, PermissionKind.NOTIFICATIONS)
    override val optionalKinds = listOf(PermissionKind.LOCATION, PermissionKind.FULL_SCREEN_INTENT)

    override fun status(kind: PermissionKind): PermissionStatus = when {
        platform.isGranted(kind) -> PermissionStatus.GRANTED
        asked.wasAsked(kind) -> PermissionStatus.DENIED
        else -> PermissionStatus.NOT_DETERMINED
    }

    override suspend fun request(kind: PermissionKind): PermissionStatus {
        if (platform.isGranted(kind)) return PermissionStatus.GRANTED
        if (asked.wasAsked(kind)) return PermissionStatus.DENIED
        asked.markAsked(kind)
        platform.prompt(kind)
        return if (platform.isGranted(kind)) PermissionStatus.GRANTED else PermissionStatus.DENIED
    }
}

/** Shows runtime prompts and system settings screens, suspending until the user returns. */
interface PermissionPrompter {
    suspend fun requestRuntimePermission(permission: String)
    suspend fun openAndAwaitReturn(intent: Intent)
}

class AndroidPermissionPlatform(private val context: Context, private val prompter: PermissionPrompter) : PermissionPlatform {
    override fun isGranted(kind: PermissionKind): Boolean = when (kind) {
        PermissionKind.ALARM -> AlarmPermissions.canScheduleExactAlarms(context)
        PermissionKind.NOTIFICATIONS -> AlarmPermissions.notificationsGranted(context)
        PermissionKind.FULL_SCREEN_INTENT -> AlarmPermissions.canUseFullScreenIntent(context)
        PermissionKind.LOCATION ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    override suspend fun prompt(kind: PermissionKind) {
        when (kind) {
            PermissionKind.ALARM -> AlarmPermissions.exactAlarmIntent(context)?.let { prompter.openAndAwaitReturn(it) }
            PermissionKind.NOTIFICATIONS -> prompter.requestRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
            PermissionKind.LOCATION -> prompter.requestRuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION)
            PermissionKind.FULL_SCREEN_INTENT -> AlarmPermissions.fullScreenIntentSettings(context)?.let { prompter.openAndAwaitReturn(it) }
        }
    }
}

/** Create this in `Activity.onCreate`, before the activity is started: it registers result launchers. */
class ActivityPermissionPrompter(activity: ComponentActivity) : PermissionPrompter {
    private var runtimeResult: CompletableDeferred<Unit>? = null
    private var returnResult: CompletableDeferred<Unit>? = null

    private val runtimeLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { runtimeResult?.complete(Unit) }
    private val settingsLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { returnResult?.complete(Unit) }

    override suspend fun requestRuntimePermission(permission: String) {
        val done = CompletableDeferred<Unit>().also { runtimeResult = it }
        runtimeLauncher.launch(permission)
        done.await()
    }

    override suspend fun openAndAwaitReturn(intent: Intent) {
        val done = CompletableDeferred<Unit>().also { returnResult = it }
        settingsLauncher.launch(intent)
        done.await()
    }
}

/** Lets the model start and stop the platform GPS source. */
fun PlatformLocationSource.asControl(): LocationControl = object : LocationControl {
    override fun start(): Boolean = this@asControl.start()
    override fun stop() = this@asControl.stop()
}
```

In `SessionModel.kt`: (a) add a trailing constructor parameter after `onAlarmError`: `private val locationControl: LocationControl? = null`. (b) Add after `var display ... private set`:

```kotlin
    var isFindingNearest: Boolean by mutableStateOf(false)
        private set
    var nearestFailure: NearestFailure? by mutableStateOf(null)
        private set
```
(c) In `start()`, after `permissionWarnings = warnings` add: `if (permissions.status(PermissionKind.LOCATION) == PermissionStatus.GRANTED) locationControl?.start()`. (d) In `imOff()` and in the `Arrived` branch of `handle`, add `locationControl?.stop()` right after `tracker = null`. (e) Add before `// Tracker events`:

```kotlin
    // Nearest station

    /**
     * The "Nearest" chip. Asks for location only now, never re-prompts a refusal, fills From with
     * the closest station, and stops the GPS again.
     */
    suspend fun useNearestStation() {
        if (!isEditable) return
        isFindingNearest = true
        nearestFailure = null
        try {
            var status = permissions.status(PermissionKind.LOCATION)
            if (status == PermissionStatus.NOT_DETERMINED) status = permissions.request(PermissionKind.LOCATION)
            if (status != PermissionStatus.GRANTED) {
                nearestFailure = NearestFailure.PERMISSION_DENIED
                return
            }
            locationControl?.start()
            for (attempt in 0..FIX_WAIT_ATTEMPTS) {
                val fix = location.latestFix()
                val station = fix?.takeIf { it.accuracy.isFinite() }?.let { directory.nearest(it.latitude, it.longitude) }
                if (station != null) {
                    locationControl?.stop()
                    selectFrom(station)
                    return
                }
                if (attempt == FIX_WAIT_ATTEMPTS) break
                clock.sleep(FIX_WAIT_INTERVAL_SECONDS)
            }
            locationControl?.stop()
            nearestFailure = NearestFailure.NO_FIX
        } finally {
            isFindingNearest = false
        }
    }
```
and add to the companion object: `const val FIX_WAIT_ATTEMPTS = 10` and `const val FIX_WAIT_INTERVAL_SECONDS = 0.5`, plus `import com.trainalarm.app.provider.nearest`.

- [ ] **Step 8: Android, run to verify pass**

Same command as Step 6. Expected: `BUILD SUCCESSFUL`. Then `./gradlew test lint assembleDebug --offline`. Lint must not report an error for the unguarded `Manifest.permission.POST_NOTIFICATIONS` constant (it only exists from API 33; the constant is inlined, so it is safe to reference on API 29).

- [ ] **Step 9: Commit**

```bash
git add ios android
git commit -m "Feature: Add real permissions, GPS control and the nearest station suggestion

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 12: UI foundation, Home and Confirm screens

**Files (iOS):**
- Create: `ios/Sources/TrainAlarm/Resources/Fonts/` (`Anton-Regular.ttf`, `Doto-Variable.ttf`, `OFL-Anton.txt`, `OFL-Doto.txt`)
- Modify: `ios/project.yml` (`UIAppFonts`), `ios/Sources/TrainAlarm/App/TrainAlarmApp.swift`
- Create: `.../App/Theme/Theme.swift`, `.../App/UI/Components.swift`, `.../App/UI/HomeView.swift`, `.../App/UI/StationSearchSheet.swift`, `.../App/UI/ConfirmSheet.swift`, `.../App/RootView.swift`, `.../App/AppEnvironment.swift`
- Delete: `.../App/ContentView.swift` (the Stage 0 placeholder)
- Create (tests): `ios/Tests/TrainAlarmTests/App/FontsTests.swift`

**Files (Android):**
- Create: `android/app/src/main/res/font/anton_regular.ttf`, `.../res/font/doto_variable.ttf`, `android/app/src/main/assets/licenses/OFL-Anton.txt`, `.../OFL-Doto.txt`
- Create: `.../com/trainalarm/app/theme/Theme.kt`, `.../ui/Components.kt`, `.../ui/HomeScreen.kt`, `.../ui/StationSearchSheet.kt`, `.../ui/ConfirmSheet.kt`, `.../ui/RootScreen.kt`, `.../AppEnvironment.kt`
- Create: `android/app/src/debug/java/com/trainalarm/app/demo/DemoEntry.kt`, `android/app/src/release/java/com/trainalarm/app/demo/DemoEntry.kt`
- Modify: `.../MainActivity.kt` (replace the Stage 0 placeholder)

**Interfaces:**
- Consumes: `SessionModel` and its state (Tasks 7, 8, 11), `Palette`/`PaletteTokens` (Task 2), `TimeText` (Task 3), `SettingsStore`/`ThemeChoice` (Task 6), `RttToken` (Task 9), `DemoProvider`, `NoopAlarmScheduler`, `DemoPermissions`, `Scenario` (Task 10), `SystemPermissions`, `CoreLocationSource`/`PlatformLocationSource` (Tasks 5, 11), `AlarmKitScheduler`, `ExactAlarmScheduler`.
- Produces: `AppColors` and `LocalColors`/`\.colors`; `Font.anton(_:)`, `Font.dots(_:)`, `Fonts.missing()`; components `DotTime`, `Pill`, `BigButton`, `LeadRuler`, `AlarmDial`; screens `HomeView`/`HomeScreen`, `ConfirmSheet`; `RootView`/`RootScreen`; `AppEnvironment` (composition root: real or demo model, `liveDataSetUp`, `isDemo`, `opensSettings`, `applyScenario`).
- `RootView`/`RootScreen` render the Tracking, Ringing, Ended and Settings placeholders listed below. Tasks 13, 14 and 15 replace them; they are stubs only to keep the app compiling and the scenarios openable.
- The time colon is always drawn by `ColonDots`, never taken from the font. Orange and teal are fills only (spec section 5).

- [ ] **Step 0: Android emulator gate (needs the user's approval; run once)**

The controller asks the user before this step (a multi-gigabyte download). The `--licenses` command below accepts Google's Android SDK licences, so the user runs it themselves (`! $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses` in the prompt) and answers its questions; do not answer for them. If the user declines, stop and report: Android screens can be compiled but not seen. Verified package names:

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "emulator" "system-images;android-35;google_apis;arm64-v8a"
echo no | $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd -n trainalarm_api35 -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_8
$ANDROID_HOME/emulator/emulator -list-avds
```
Expected: `trainalarm_api35` is listed. Boot it later with `$ANDROID_HOME/emulator/emulator -avd trainalarm_api35 -no-snapshot &` and wait for `$ANDROID_HOME/platform-tools/adb wait-for-device`.

- [ ] **Step 1: Fonts (both platforms)**

Both fonts are SIL OFL 1.1. Download into the app folders, check the names, and read the licence texts (below):

```bash
B=https://raw.githubusercontent.com/google/fonts/main/ofl
mkdir -p ios/Sources/TrainAlarm/Resources/Fonts android/app/src/main/res/font android/app/src/main/assets/licenses
curl -fsSL -o ios/Sources/TrainAlarm/Resources/Fonts/Anton-Regular.ttf $B/anton/Anton-Regular.ttf
curl -fsSL -o ios/Sources/TrainAlarm/Resources/Fonts/Doto-Variable.ttf "$B/doto/Doto%5BROND%2Cwght%5D.ttf"
curl -fsSL -o ios/Sources/TrainAlarm/Resources/Fonts/OFL-Anton.txt $B/anton/OFL.txt
curl -fsSL -o ios/Sources/TrainAlarm/Resources/Fonts/OFL-Doto.txt $B/doto/OFL.txt
cp ios/Sources/TrainAlarm/Resources/Fonts/Anton-Regular.ttf android/app/src/main/res/font/anton_regular.ttf
cp ios/Sources/TrainAlarm/Resources/Fonts/Doto-Variable.ttf android/app/src/main/res/font/doto_variable.ttf
cp ios/Sources/TrainAlarm/Resources/Fonts/OFL-Anton.txt ios/Sources/TrainAlarm/Resources/Fonts/OFL-Doto.txt android/app/src/main/assets/licenses/
fc-scan --format '%{postscriptname}\n' ios/Sources/TrainAlarm/Resources/Fonts/Anton-Regular.ttf ios/Sources/TrainAlarm/Resources/Fonts/Doto-Variable.ttf | grep -E "^(Anton-Regular|Doto_700wght)$"
shasum -a 256 ios/Sources/TrainAlarm/Resources/Fonts/*.ttf
```
**Licence gate.** Read both `OFL-*.txt` files. Confirm each says SIL Open Font License 1.1, note the copyright holder and any Reserved Font Name, and confirm that bundling the unmodified font in an app is allowed (it is: renaming is only required for modified versions, and we do not modify the files). If a file is not OFL 1.1, or the download differs from this, stop and report. Write the two `shasum` values into the ledger, so a later re-download can be compared; the URLs use `main`, which can change.
Expected: both `Anton-Regular` and `Doto_700wght` print. Doto is a variable font; `Doto_700wght` is its Bold instance as the file names it, but iOS exposes that instance at runtime as **`Doto-Black_Bold`** (found by listing `UIFont.fontNames(forFamilyName: "Doto")` in the plan dry run), and that is the name the code uses. In `ios/project.yml`, under `TrainAlarm.info.properties`, add:

```yaml
        UIAppFonts:
          - Anton-Regular.ttf
          - Doto-Variable.ttf
```

- [ ] **Step 2: iOS, write the failing font test**

`FontsTests.swift`:

```swift
import XCTest
@testable import TrainAlarm

final class FontsTests: XCTestCase {
    func testBundledFontsLoad() {
        XCTAssertEqual(Fonts.missing(), [], "UIAppFonts in project.yml must list the bundled font files")
    }
}
```

Run: `cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/FontsTests 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | head -3`. Expected: `cannot find 'Fonts' in scope`.

- [ ] **Step 3: iOS, theme and components**

`Theme.swift`:

```swift
import SwiftUI
import UIKit

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

/// The palette tokens as SwiftUI colours. Orange (`accent`) and teal are fills only (spec section 5).
struct AppColors {
    let bg, surface, surface2, text, head, muted: Color
    let accent, onAccent, teal, onTeal, tealText, bad: Color

    init(_ t: PaletteTokens) {
        bg = Color(hex: t.bg); surface = Color(hex: t.surface); surface2 = Color(hex: t.surface2)
        text = Color(hex: t.text); head = Color(hex: t.head); muted = Color(hex: t.muted)
        accent = Color(hex: t.accent); onAccent = Color(hex: t.onAccent); teal = Color(hex: t.teal)
        onTeal = Color(hex: t.onTeal); tealText = Color(hex: t.tealText); bad = Color(hex: t.bad)
    }

    static let dark = AppColors(Palette.dark)
    static let light = AppColors(Palette.light)
}

private struct AppColorsKey: EnvironmentKey { static let defaultValue = AppColors.dark }

extension EnvironmentValues {
    var colors: AppColors {
        get { self[AppColorsKey.self] }
        set { self[AppColorsKey.self] = newValue }
    }
}

extension Font {
    static func anton(_ size: CGFloat) -> Font { .custom("Anton-Regular", size: size) }
    /// The dot-matrix face (Doto, Bold instance). Times are drawn with `DotTime`, not plain `Text`.
    static func dots(_ size: CGFloat) -> Font { .custom("Doto-Black_Bold", size: size) }
}

enum Fonts {
    static let required = ["Anton-Regular", "Doto-Black_Bold"]

    /// Names of required fonts that failed to load (a missing font silently falls back to the system one).
    static func missing() -> [String] { required.filter { UIFont(name: $0, size: 12) == nil } }
}

/// Applies the theme: `preferredColorScheme` from the user's setting, and the matching colours below it.
struct ThemedRoot<Content: View>: View {
    let settings: SettingsStore
    @ViewBuilder let content: () -> Content

    var body: some View {
        Themed(content: content)
            .preferredColorScheme(settings.theme == .dark ? .dark : settings.theme == .light ? .light : nil)
    }
}

private struct Themed<Content: View>: View {
    let content: () -> Content
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        content().environment(\.colors, scheme == .dark ? AppColors.dark : AppColors.light)
    }
}
```

`Components.swift`:

```swift
import SwiftUI

/// Two dots, drawn by us: the Doto colon glyph reads as a plus sign.
struct ColonDots: View {
    let size: CGFloat

    var body: some View {
        VStack(spacing: size * 0.16) {
            Circle().frame(width: size * 0.13, height: size * 0.13)
            Circle().frame(width: size * 0.13, height: size * 0.13)
        }
        .frame(width: size * 0.3)
    }
}

/// A time or countdown in the dot-matrix face.
struct DotTime: View {
    let hm: TimeText.HM
    let size: CGFloat
    let color: Color

    var body: some View {
        HStack(spacing: 0) {
            Text(hm.hours).font(.dots(size))
            ColonDots(size: size)
            Text(hm.minutes).font(.dots(size))
        }
        .lineLimit(1)
        .fixedSize()
        .foregroundStyle(color)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(hm.plain)
    }
}

struct Pill: View {
    enum Style { case plain, late, bad, onTeal }
    let text: String
    var style: Style = .plain
    @Environment(\.colors) private var c

    var body: some View {
        Text(text)
            .font(.caption.bold())
            .padding(.horizontal, 11).padding(.vertical, 4)
            .foregroundStyle(foreground)
            .background(background, in: Capsule())
            .overlay { if style == .bad { Capsule().stroke(c.bad, lineWidth: 1.5) } }
            .fixedSize()
    }

    private var foreground: Color {
        switch style {
        case .plain: return c.text
        case .late: return c.onAccent
        case .bad: return c.bad
        case .onTeal: return c.teal
        }
    }

    private var background: Color {
        switch style {
        case .plain: return c.surface2
        case .late: return c.accent
        case .bad: return .clear
        case .onTeal: return c.onTeal
        }
    }
}

struct BigButton: View {
    enum Style { case accent, ghost, cream }
    let title: String
    var style: Style = .accent
    var isEnabled = true
    let action: () -> Void
    @Environment(\.colors) private var c

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.anton(26)).textCase(.uppercase).tracking(0.5)
                .frame(maxWidth: .infinity).padding(.vertical, 16)
                .foregroundStyle(foreground)
                .background(background, in: RoundedRectangle(cornerRadius: 18))
                .overlay { if style == .ghost { RoundedRectangle(cornerRadius: 18).stroke(c.muted.opacity(0.5), lineWidth: 1.5) } }
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
        .opacity(isEnabled ? 1 : 0.45)
    }

    private var foreground: Color {
        switch style {
        case .accent: return c.onAccent
        case .ghost: return c.head
        case .cream: return Color(hex: 0x201D1C)
        }
    }

    private var background: Color {
        switch style {
        case .accent: return c.accent
        case .ghost: return .clear
        case .cream: return Color(hex: 0xF3ECE3)
        }
    }
}

/// Picks a lead time from `choices`: tap or drag along the tick ruler. The marker sits on the choice.
struct LeadRuler: View {
    let choices: [Int]
    let selected: Int?
    let onSelect: (Int) -> Void
    @Environment(\.colors) private var c
    private let inset: CGFloat = 16

    var body: some View {
        GeometryReader { geo in
            let usable = geo.size.width - inset * 2
            VStack(spacing: 6) {
                ZStack(alignment: .leading) {
                    Canvas { context, size in
                        var x: CGFloat = 4.5
                        while x < size.width {
                            context.fill(Path(CGRect(x: x, y: size.height * 0.28, width: 1.5, height: size.height * 0.44)), with: .color(c.muted))
                            x += 9
                        }
                    }
                    if let selected, let index = choices.firstIndex(of: selected) {
                        Rectangle().fill(c.accent).frame(width: 3)
                            .offset(x: inset + usable * CGFloat(index) / CGFloat(choices.count - 1) - 1.5)
                    }
                }
                .frame(height: 44)
                .background(c.surface2, in: RoundedRectangle(cornerRadius: 12))
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0).onChanged { value in
                    let fraction = max(0, min(1, (value.location.x - inset) / usable))
                    onSelect(choices[Int((fraction * Double(choices.count - 1)).rounded())])
                })
                ZStack(alignment: .leading) {
                    ForEach(Array(choices.enumerated()), id: \.element) { index, minutes in
                        Text("\(minutes)").font(.dots(17)).foregroundStyle(minutes == selected ? c.head : c.muted)
                            .position(x: inset + usable * CGFloat(index) / CGFloat(choices.count - 1), y: 9)
                    }
                }
                .frame(height: 18)
            }
        }
        .frame(height: 70)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Minutes before arrival")
        .accessibilityValue(selected.map { "\($0) minutes" } ?? "not chosen")
        .accessibilityAdjustableAction { direction in
            let index = selected.flatMap { choices.firstIndex(of: $0) } ?? 0
            let next = direction == .increment ? min(choices.count - 1, index + 1) : max(0, index - 1)
            onSelect(choices[next])
        }
    }
}

/// A round dial: the alarm time (orange dot) and the arrival time (cream dot) at their minutes past the hour.
struct AlarmDial: View {
    let alarmAt: Date?
    let arrival: Date
    let lead: Int?
    @Environment(\.colors) private var c

    var body: some View {
        ZStack {
            Circle().fill(c.surface2)
            Canvas { context, size in
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                let radius = min(size.width, size.height) / 2
                for tick in 0..<60 {
                    let angle = Double(tick) * .pi / 30
                    var path = Path()
                    path.move(to: point(center, radius * 0.80, angle))
                    path.addLine(to: point(center, radius * 0.86, angle))
                    context.stroke(path, with: .color(c.muted), lineWidth: 1.4)
                }
                func dot(_ date: Date, _ color: Color) {
                    let minute = Double(TimeText.clock(date).minutes) ?? 0
                    let p = point(center, radius * 0.83, minute * .pi / 30)
                    context.fill(Path(ellipseIn: CGRect(x: p.x - 6, y: p.y - 6, width: 12, height: 12)), with: .color(color))
                }
                dot(arrival, c.head)
                if let alarmAt { dot(alarmAt, c.accent) }
            }
            Circle().fill(Color(hex: 0x201D1C)).padding(34)
            VStack(spacing: 0) {
                Text(lead.map(String.init) ?? "-").font(.dots(40)).foregroundStyle(Color(hex: 0xF3ECE3))
                Text("min before").font(.caption2.bold()).foregroundStyle(Color(hex: 0xF3ECE3))
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(lead.map { "Alarm \($0) minutes before arrival" } ?? "No alarm time chosen")
    }

    private func point(_ center: CGPoint, _ radius: CGFloat, _ angle: Double) -> CGPoint {
        CGPoint(x: center.x + radius * CGFloat(sin(angle)), y: center.y - radius * CGFloat(cos(angle)))
    }
}
```

- [ ] **Step 4: iOS, composition root and entry**

`AppEnvironment.swift`:

```swift
import Foundation

/// Builds the real app (RTT provider, AlarmKit, Core Location) or, in debug builds without a token or
/// with `-demo` or `-scenario <name>`, the demo app (spec section 6). Release builds never contain demo code.
@MainActor
struct AppEnvironment {
    let model: SessionModel
    let settings: SettingsStore
    /// False when there is no RTT token and this is not a demo: the picker says live data is not set up.
    let liveDataSetUp: Bool
    let isDemo: Bool
    let opensSettings: Bool
    let applyScenario: (() async -> Void)?

    static func make(arguments: [String] = ProcessInfo.processInfo.arguments) -> AppEnvironment {
        let settings = SettingsStore()
        let directory: StationDirectory
        do { directory = try StationDirectory.load() } catch {
            fatalError("The bundled station list is missing or malformed: \(error)")
        }
        let clock = SystemTrackerClock()
        let location = CoreLocationSource()
        let token = RttToken.current

        #if DEBUG
        let scenario = Scenario.from(arguments: arguments)
        if scenario != nil || token == nil || arguments.contains("-demo") {
            // The demo has its own clock (`-demo-speed N` makes it faster) and an alarm that tells the app when it rings.
            let demoClock = DemoClock(speed: Scenario.speed(from: arguments))
            let ring = RingBox()
            let model = SessionModel(
                provider: DirectoryBackedProvider(base: DemoProvider(clock: demoClock), directory: directory),
                scheduler: DemoAlarmScheduler(clock: demoClock) { ring.handler?() }, directory: directory, location: location,
                clock: demoClock, permissions: DemoPermissions(), settings: settings, locationControl: location)
            ring.handler = { [weak model] in model?.alarmRang() }
            return AppEnvironment(
                model: model, settings: settings, liveDataSetUp: true, isDemo: true,
                opensSettings: scenario?.opensSettings ?? false,
                applyScenario: scenario.map { s in { await s.apply(to: model, directory: directory, now: Date()) } })
        }
        #endif

        let base: TrainDataProvider = token.map { RttProvider(accessToken: $0) } ?? UnavailableProvider()
        let model = SessionModel(
            provider: DirectoryBackedProvider(base: base, directory: directory),
            scheduler: AlarmKitScheduler(client: SystemAlarmKitClient()), directory: directory, location: location,
            clock: clock, permissions: SystemPermissions(), settings: settings, locationControl: location)
        return AppEnvironment(model: model, settings: settings, liveDataSetUp: token != nil, isDemo: false,
                              opensSettings: false, applyScenario: nil)
    }
}

/// Used when there is no RTT token in a non-demo build. Every call fails, and the picker says why.
struct UnavailableProvider: TrainDataProvider {
    func searchStations(query: String) async throws -> [Station] { throw ProviderError.notImplemented("Live data is not set up") }
    func departureBoard(station: Station, from: Date, callingAt: Station?) async throws -> [Service] {
        throw ProviderError.notImplemented("Live data is not set up")
    }
    func serviceDetails(serviceId: String, date: Date) async throws -> Service { throw ProviderError.notImplemented("Live data is not set up") }
}
```

`TrainAlarmApp.swift` (replace the file) and delete `ContentView.swift` (`git rm ios/Sources/TrainAlarm/App/ContentView.swift`):

```swift
import os
import SwiftUI

@main
struct TrainAlarmApp: App {
    @State private var environment = AppEnvironment.make()

    init() {
        let missing = Fonts.missing()
        if !missing.isEmpty {
            Logger(subsystem: "com.trainalarm.app", category: "ui").warning("Fonts failed to load: \(missing.joined(separator: ", "))")
        }
    }

    var body: some Scene {
        WindowGroup { RootView(environment: environment) }
    }
}
```

- [ ] **Step 5: iOS, screens**

`HomeView.swift`:

```swift
import SwiftUI

struct HomeView: View {
    @Bindable var model: SessionModel
    let liveDataSetUp: Bool
    let isDemo: Bool
    let openSettings: () -> Void
    @Environment(\.colors) private var c
    @State private var picking: Field?

    enum Field: Identifiable {
        case from, to
        var id: Self { self }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Text("TrainAlarm").font(.anton(20)).textCase(.uppercase).foregroundStyle(c.head)
                    if isDemo { Pill(text: "Demo data") }
                    Spacer()
                    Button(action: openSettings) {
                        Image(systemName: "gearshape").font(.title3).foregroundStyle(c.head)
                            .frame(width: 40, height: 40).background(c.surface, in: Circle())
                    }
                    .accessibilityLabel("Settings")
                }
                Text("Where to?").font(.anton(58)).textCase(.uppercase).foregroundStyle(c.head)
                stationField("From", model.from) { picking = .from }
                if model.from == nil { nearestChip }
                stationField("To", model.to) { picking = .to }
                if liveDataSetUp { BoardSection(model: model) } else { setupCard }
            }
            .padding(18)
        }
        .refreshable { await model.loadBoard() }   // pull to refresh; the model's 20 s cooldown still applies
        .background(c.bg.ignoresSafeArea())
        .task(id: "\(model.from?.id ?? "")-\(model.to?.id ?? "")") { await model.loadBoard() }
        .sheet(item: $picking) { field in StationSearchSheet(model: model, isFrom: field == .from) }
        .sheet(isPresented: Binding(get: { model.phase == .confirming }, set: { if !$0 { model.backToSelecting() } })) {
            ConfirmSheet(model: model)
        }
    }

    private func stationField(_ title: String, _ station: Station?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.caption).foregroundStyle(c.muted)
                Text(station?.name ?? "Choose a station").font(.title3.bold()).foregroundStyle(station == nil ? c.muted : c.head)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16).padding(.vertical, 12)
            .background(c.surface, in: RoundedRectangle(cornerRadius: 18))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title): \(station?.name ?? "not chosen")")
    }

    private var nearestChip: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button { Task { await model.useNearestStation() } } label: {
                Text(model.isFindingNearest ? "Finding your nearest station..." : "Use my nearest station")
                    .font(.footnote).foregroundStyle(c.text)
                    .padding(.horizontal, 12).padding(.vertical, 5)
                    .overlay(Capsule().stroke(c.muted.opacity(0.6), lineWidth: 1.5))
            }
            .buttonStyle(.plain)
            .disabled(model.isFindingNearest)
            if let failure = model.nearestFailure {
                Text(failure == .permissionDenied ? "Location is off. Search for your station instead."
                                                  : "Could not find you yet. Search for your station instead.")
                    .font(.footnote).foregroundStyle(c.muted)
            }
        }
    }

    private var setupCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Live data is not set up").font(.headline).foregroundStyle(c.head)
            Text("This build has no train data token, so it cannot show departures yet.").font(.footnote).foregroundStyle(c.text)
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16)
        .background(c.surface, in: RoundedRectangle(cornerRadius: 18))
    }
}

private struct BoardSection: View {
    @Bindable var model: SessionModel
    @Environment(\.colors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            switch model.board {
            case .idle:
                Text("Choose where you are and where you are going.").font(.footnote).foregroundStyle(c.muted)
            case .loading:
                ProgressView().frame(maxWidth: .infinity).padding(.top, 12)
            case .loaded(let services):
                Text("Trains from \(model.from?.name ?? "") calling at \(model.to?.name ?? "")").font(.footnote.bold()).foregroundStyle(c.muted)
                ForEach(services, id: \.id) { service in
                    Button { Task { await model.choose(service) } } label: { BoardRow(service: service, now: Date()) }
                        .buttonStyle(.plain)
                }
            case .empty:
                message("No trains found", "No trains call at \(model.to?.name ?? "that station") soon. Try again in a few minutes.")
            case .failed(let text):
                message("Could not load trains", text)
            case .rateLimited:
                message("The train data is busy", "Wait a moment, then try again.", force: false)
            }
        }
    }

    /// `force: false` keeps the cooldown, so a rate-limited board is not hit again at once.
    private func message(_ title: String, _ detail: String, force: Bool = true) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline).foregroundStyle(c.head)
            Text(detail).font(.footnote).foregroundStyle(c.text)
            BigButton(title: "Try again", style: .ghost) { Task { await model.loadBoard(force: force) } }
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16)
        .background(c.surface, in: RoundedRectangle(cornerRadius: 18))
    }
}

private struct BoardRow: View {
    let service: Service
    let now: Date
    @Environment(\.colors) private var c

    var body: some View {
        let stop = service.journey.stops[0]
        let departure = stop.estimatedDeparture ?? stop.scheduledDeparture
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                if let departure {
                    DotTime(hm: TimeText.clock(departure), size: 34, color: c.head)
                    if let label = TimeText.dateLabel(for: departure, now: now) { Text(label).font(.caption2).foregroundStyle(c.muted) }
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(service.operatorName).font(.subheadline.bold()).foregroundStyle(c.text)
                Text("towards \(service.journey.destination.name)").font(.footnote).foregroundStyle(c.muted)
            }
            Spacer()
            status(stop)
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        .background(c.surface, in: RoundedRectangle(cornerRadius: 16))
        .opacity(stop.isCancelled ? 0.8 : 1)
    }

    @ViewBuilder
    private func status(_ stop: Stop) -> some View {
        if stop.isCancelled {
            Pill(text: "Cancelled", style: .bad)
        } else if let scheduled = stop.scheduledDeparture, let estimated = stop.estimatedDeparture,
                  Int((estimated.timeIntervalSince(scheduled) / 60).rounded()) > 0 {
            Pill(text: "+\(Int((estimated.timeIntervalSince(scheduled) / 60).rounded())) min", style: .late)
        } else {
            Pill(text: "On time")
        }
    }
}
```

`StationSearchSheet.swift`:

```swift
import SwiftUI

struct StationSearchSheet: View {
    @Bindable var model: SessionModel
    let isFrom: Bool
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colors) private var c
    @State private var query = ""

    var body: some View {
        let results = model.search(query)
        NavigationStack {
            List(results) { station in
                Button(station.name) {
                    if isFrom { model.selectFrom(station) } else { model.selectTo(station) }
                    dismiss()
                }
                .foregroundStyle(c.head)
                .listRowBackground(c.surface)
            }
            .scrollContentBackground(.hidden)
            .background(c.bg)
            .searchable(text: $query, prompt: isFrom ? "Where are you now?" : "Where are you going?")
            .navigationTitle(isFrom ? "From" : "To")
            .navigationBarTitleDisplayMode(.inline)
            .overlay {
                if query.isEmpty { Text("Type a station name").foregroundStyle(c.muted) }
                else if results.isEmpty { Text("No station called that").foregroundStyle(c.muted) }
            }
        }
        .presentationBackground(c.bg)
    }
}
```

`ConfirmSheet.swift`:

```swift
import SwiftUI
import UIKit

struct ConfirmSheet: View {
    @Bindable var model: SessionModel
    @Environment(\.colors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Wake me before \(model.to?.name ?? "your stop")")
                .font(.anton(30)).textCase(.uppercase).foregroundStyle(c.head)
            if let confirm = model.confirm {
                if let arrival = confirm.arrival {
                    HStack(spacing: 14) {
                        AlarmDial(alarmAt: model.alarmAt, arrival: arrival, lead: confirm.leadMinutes).frame(width: 150, height: 150)
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Alarm at").font(.caption).foregroundStyle(c.muted)
                            if let alarm = model.alarmAt { DotTime(hm: TimeText.clock(alarm), size: 42, color: c.head) }
                            else { Text("Choose minutes").font(.footnote).foregroundStyle(c.muted) }
                            Text("Arrives").font(.caption).foregroundStyle(c.muted)
                            DotTime(hm: TimeText.clock(arrival), size: 42, color: c.head.opacity(0.5))
                        }
                    }
                    LeadRuler(choices: SettingsStore.leadChoices, selected: confirm.leadMinutes) { model.setLead($0) }
                    if model.ringsStraightAway { notice("The alarm will ring straight away.") }
                } else {
                    notice("This train does not call at \(model.to?.name ?? "that station"). Pick another.")
                }
            }
            if let kind = model.blockedPermission {
                notice(kind == .alarm ? "Alarms are off for TrainAlarm. Allow them in Settings so it can wake you." : "A permission is off. Allow it in Settings to continue.")
            }
            ForEach(model.permissionWarnings, id: \.self) { kind in
                if kind == .location { notice("Location is off. Live tracking will rely on train data only.") }
            }
            BigButton(title: model.blockedPermission == nil ? "Start tracking" : "Allow alarms to continue",
                      isEnabled: model.canStart) {
                if model.blockedPermission != nil, let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                } else {
                    Task { await model.start() }
                }
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .presentationDetents([.fraction(0.7), .large])
        .presentationBackground(c.surface)
    }

    private func notice(_ text: String) -> some View {
        Text(text).font(.footnote).foregroundStyle(c.text)
            .frame(maxWidth: .infinity, alignment: .leading).padding(12)
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(c.bad, lineWidth: 1.5))
    }
}
```

`RootView.swift`:

```swift
import SwiftUI

struct RootView: View {
    let environment: AppEnvironment
    @State private var showingSettings = false

    var body: some View {
        ThemedRoot(settings: environment.settings) {
            ZStack {
                switch environment.model.phase {
                case .selecting, .confirming:
                    HomeView(model: environment.model, liveDataSetUp: environment.liveDataSetUp,
                             isDemo: environment.isDemo) { showingSettings = true }
                case .tracking, .ringing, .ended:
                    StubSessionView(model: environment.model)   // replaced in Tasks 13 and 14
                }
            }
            .task {
                await environment.applyScenario?()
                if environment.opensSettings { showingSettings = true }
            }
            .sheet(isPresented: $showingSettings) { Text("Settings") }   // replaced in Task 15
        }
    }
}

/// Keeps the app compiling and the scenarios openable until Tasks 13 and 14 add the real screens.
private struct StubSessionView: View {
    let model: SessionModel
    @Environment(\.colors) private var c

    var body: some View {
        VStack(spacing: 16) {
            Text("\(String(describing: model.phase))").foregroundStyle(c.head)
            BigButton(title: "I'm off") { Task { await model.imOff() } }
            if case .ended = model.phase { BigButton(title: "Back to start", style: .ghost) { model.returnHome() } }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(c.bg.ignoresSafeArea())
    }
}
```

- [ ] **Step 6: iOS, run the font test, build, and look at the screens**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" 2>&1 | grep -E "error:|Executed [0-9]+ tests, with [0-9]+ failures .* in [0-9.]+ \([0-9.]+\) seconds$|TEST (SUCCEEDED|FAILED)" | tail -3
```
Expected: `** TEST SUCCEEDED **`, including `FontsTests`. If a font is reported missing, list the available names with a one-off test that prints `UIFont.fontNames(forFamilyName: "Doto")`, and use the printed name in `Font.dots` and `Fonts.required`. Then view the screens: boot a simulator, build to a scratch folder, install, launch each scenario in both appearances, take screenshots and open them:

```bash
D="iPhone 17"; xcrun simctl boot "$D" 2>/dev/null; xcrun simctl bootstatus "$D" -b
xcodebuild build -scheme TrainAlarm -destination "platform=iOS Simulator,name=$D" -derivedDataPath /tmp/ta-shots 2>&1 | grep -E "error:|BUILD (SUCCEEDED|FAILED)"
APP=$(find /tmp/ta-shots -name TrainAlarm.app -type d | head -1); xcrun simctl install "$D" "$APP"
for mode in dark light; do
  xcrun simctl ui "$D" appearance $mode
  for s in home board confirm; do
    xcrun simctl terminate "$D" com.trainalarm.app 2>/dev/null
    xcrun simctl launch "$D" com.trainalarm.app -scenario $s > /dev/null; sleep 3
    xcrun simctl io "$D" screenshot "/tmp/ta-shots/ios-$s-$mode.png"
  done
done
```
Open the six PNGs and compare each with the mockup (`docs/ui-mockup/trainalarm-ui-direction.html`): dot-matrix times, the two-dot colon, orange and teal used as fills only, both themes readable. Fix what differs before committing. (The permanent screenshot set is Task 16.)

- [ ] **Step 7: Android, theme and components**

`theme/Theme.kt`:

```kotlin
package com.trainalarm.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.trainalarm.app.R
import com.trainalarm.app.ThemeChoice

private fun color(hex: Int) = Color(0xFF000000.toInt() or hex)

/** The palette tokens as Compose colours. Orange (`accent`) and teal are fills only (spec section 5). */
class AppColors(t: PaletteTokens) {
    val bg = color(t.bg)
    val surface = color(t.surface)
    val surface2 = color(t.surface2)
    val text = color(t.text)
    val head = color(t.head)
    val muted = color(t.muted)
    val accent = color(t.accent)
    val onAccent = color(t.onAccent)
    val teal = color(t.teal)
    val onTeal = color(t.onTeal)
    val tealText = color(t.tealText)
    val bad = color(t.bad)
}

val LocalColors = staticCompositionLocalOf { AppColors(Palette.dark) }

val Anton = FontFamily(Font(R.font.anton_regular))

/** The dot-matrix face (Doto, weight 700). Times are drawn with `DotTime`, not plain `Text`. */
@OptIn(ExperimentalTextApi::class)
val Doto = FontFamily(
    Font(R.font.doto_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

@Composable
fun TrainAlarmTheme(choice: ThemeChoice, content: @Composable () -> Unit) {
    val dark = when (choice) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
    }
    val colors = remember(dark) { AppColors(if (dark) Palette.dark else Palette.light) }
    val scheme = if (dark) {
        darkColorScheme(background = colors.bg, surface = colors.surface, onBackground = colors.text, onSurface = colors.text,
            primary = colors.accent, onPrimary = colors.onAccent)
    } else {
        lightColorScheme(background = colors.bg, surface = colors.surface, onBackground = colors.text, onSurface = colors.text,
            primary = colors.accent, onPrimary = colors.onAccent)
    }
    CompositionLocalProvider(LocalColors provides colors) { MaterialTheme(colorScheme = scheme, content = content) }
}
```
(`Palette` and `PaletteTokens` are in the same package, `com.trainalarm.app.theme`, from Task 2.)

`ui/Components.kt`:

```kotlin
package com.trainalarm.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trainalarm.app.TimeText
import com.trainalarm.app.theme.Anton
import com.trainalarm.app.theme.Doto
import com.trainalarm.app.theme.LocalColors
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Two dots, drawn by us: the Doto colon glyph reads as a plus sign. */
@Composable
fun ColonDots(size: Dp, color: Color) {
    Column(Modifier.width(size * 0.3f), Arrangement.spacedBy(size * 0.16f), Alignment.CenterHorizontally) {
        repeat(2) { Box(Modifier.size(size * 0.13f).background(color, CircleShape)) }
    }
}

/** A time or countdown in the dot-matrix face. */
@Composable
fun DotTime(hm: TimeText.HM, size: Dp, color: Color, modifier: Modifier = Modifier) {
    val fontSize = with(LocalDensity.current) { size.toSp() }
    Row(modifier.clearAndSetSemantics { contentDescription = hm.plain }, verticalAlignment = Alignment.CenterVertically) {
        Text(hm.hours, fontFamily = Doto, fontWeight = FontWeight.Bold, fontSize = fontSize, lineHeight = fontSize, color = color, maxLines = 1)
        ColonDots(size, color)
        Text(hm.minutes, fontFamily = Doto, fontWeight = FontWeight.Bold, fontSize = fontSize, lineHeight = fontSize, color = color, maxLines = 1)
    }
}

enum class PillStyle { PLAIN, LATE, BAD, ON_TEAL }

@Composable
fun Pill(text: String, style: PillStyle = PillStyle.PLAIN, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val foreground = when (style) {
        PillStyle.PLAIN -> c.text
        PillStyle.LATE -> c.onAccent
        PillStyle.BAD -> c.bad
        PillStyle.ON_TEAL -> c.teal
    }
    val background = when (style) {
        PillStyle.PLAIN -> c.surface2
        PillStyle.LATE -> c.accent
        PillStyle.BAD -> Color.Transparent
        PillStyle.ON_TEAL -> c.onTeal
    }
    val border = if (style == PillStyle.BAD) Modifier.border(1.5.dp, c.bad, CircleShape) else Modifier
    Text(text, color = foreground, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(CircleShape).background(background).then(border).padding(horizontal = 11.dp, vertical = 4.dp))
}

enum class ButtonStyle { ACCENT, GHOST, CREAM }

@Composable
fun BigButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: ButtonStyle = ButtonStyle.ACCENT, enabled: Boolean = true) {
    val c = LocalColors.current
    val foreground = when (style) {
        ButtonStyle.ACCENT -> c.onAccent
        ButtonStyle.GHOST -> c.head
        ButtonStyle.CREAM -> Color(0xFF201D1C)
    }
    val background = when (style) {
        ButtonStyle.ACCENT -> c.accent
        ButtonStyle.GHOST -> Color.Transparent
        ButtonStyle.CREAM -> Color(0xFFF3ECE3)
    }
    val shape = RoundedCornerShape(18.dp)
    val outline = if (style == ButtonStyle.GHOST) Modifier.border(1.5.dp, c.muted.copy(alpha = 0.5f), shape) else Modifier
    Box(
        modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f).clip(shape).background(background).then(outline)
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(title.uppercase(), fontFamily = Anton, fontSize = 26.sp, color = foreground, letterSpacing = 0.5.sp)
    }
}

/** Picks a lead time from [choices]: tap or drag along the tick ruler. The marker sits on the choice. */
@Composable
fun LeadRuler(choices: List<Int>, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val inset = 16.dp
    fun pick(x: Float, width: Float, insetPx: Float): Int {
        val fraction = ((x - insetPx) / max(1f, width - insetPx * 2)).coerceIn(0f, 1f)
        return choices[(fraction * (choices.size - 1)).roundToInt()]
    }
    Column(
        modifier.fillMaxWidth().semantics {
            contentDescription = "Minutes before arrival"
            stateDescription = selected?.let { "$it minutes" } ?: "not chosen"
        }
    ) {
        Box(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2)
                .pointerInput(choices) { detectTapGestures { onSelect(pick(it.x, size.width.toFloat(), inset.toPx())) } }
                .pointerInput(choices) { detectHorizontalDragGestures { change, _ -> onSelect(pick(change.position.x, size.width.toFloat(), inset.toPx())) } }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                var x = 4.5.dp.toPx()
                while (x < size.width) {
                    drawRect(c.muted, Offset(x, size.height * 0.28f), androidx.compose.ui.geometry.Size(1.5.dp.toPx(), size.height * 0.44f))
                    x += 9.dp.toPx()
                }
                val index = selected?.let { choices.indexOf(it) } ?: -1
                if (index >= 0) {
                    val usable = size.width - inset.toPx() * 2
                    val markerX = inset.toPx() + usable * index / (choices.size - 1) - 1.5.dp.toPx()
                    drawRect(c.accent, Offset(markerX, 0f), androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp).padding(top = 4.dp)) {
            val usable = maxWidth - inset * 2
            choices.forEachIndexed { index, minutes ->
                Text(
                    minutes.toString(), fontFamily = Doto, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    color = if (minutes == selected) c.head else c.muted,
                    modifier = Modifier.offset(x = inset + usable * (index.toFloat() / (choices.size - 1)) - 8.dp)
                )
            }
        }
    }
}

/** A round dial: the alarm time (orange dot) and the arrival time (cream dot) at their minutes past the hour. */
@Composable
fun AlarmDial(alarmAt: java.time.Instant?, arrival: java.time.Instant, lead: Int?, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val cream = Color(0xFFF3ECE3)
    Box(
        modifier.aspectRatio(1f).clearAndSetSemantics {
            contentDescription = lead?.let { "Alarm $it minutes before arrival" } ?: "No alarm time chosen"
        },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = min(size.width, size.height) / 2
            drawCircle(c.surface2, radius, center)
            fun point(r: Float, angle: Double) = Offset(center.x + r * sin(angle).toFloat(), center.y - r * cos(angle).toFloat())
            for (tick in 0 until 60) {
                val angle = tick * Math.PI / 30
                drawLine(c.muted, point(radius * 0.80f, angle), point(radius * 0.86f, angle), 1.4.dp.toPx())
            }
            fun dot(instant: java.time.Instant, color: Color) {
                val minute = TimeText.clock(instant).minutes.toDoubleOrNull() ?: 0.0
                drawCircle(color, 6.dp.toPx(), point(radius * 0.83f, minute * Math.PI / 30))
            }
            dot(arrival, c.head)
            if (alarmAt != null) dot(alarmAt, c.accent)
            drawCircle(Color(0xFF201D1C), radius - 34.dp.toPx(), center)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(lead?.toString() ?: "-", fontFamily = Doto, fontWeight = FontWeight.Bold, fontSize = 40.sp, color = cream)
            Text("min before", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = cream)
        }
    }
}
```

- [ ] **Step 8: Android, composition root, activity and screens**

`AppEnvironment.kt`:

```kotlin
package com.trainalarm.app

import android.content.Context
import android.content.Intent
import com.trainalarm.app.alarm.ExactAlarmScheduler
import com.trainalarm.app.alarm.SystemExactAlarmGateway
import com.trainalarm.app.demo.DemoEntry
import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.provider.DirectoryBackedProvider
import com.trainalarm.app.provider.ProviderError
import com.trainalarm.app.provider.RttProvider
import com.trainalarm.app.provider.StationDirectory
import com.trainalarm.app.provider.TrainDataProvider
import com.trainalarm.app.tracking.PlatformLocationSource
import com.trainalarm.app.tracking.SystemTrackerClock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** What the debug demo entry hands back (see `DemoEntry`). Release builds never get one. */
class DemoSetup(val model: SessionModel, val opensSettings: Boolean, val apply: suspend () -> Unit)

/** Lets the Activity that is on screen answer the model's permission requests. */
class SwitchablePrompter : PermissionPrompter {
    @Volatile var delegate: PermissionPrompter? = null
    override suspend fun requestRuntimePermission(permission: String) { delegate?.requestRuntimePermission(permission) }
    override suspend fun openAndAwaitReturn(intent: Intent) { delegate?.openAndAwaitReturn(intent) }
}

/**
 * The composition root: builds the real app (RTT provider, exact alarms, platform GPS) or, in debug builds
 * without a token or with the `scenario` intent extra, the demo app. One per process, so the session
 * survives Activity recreation. A killed process ends the session (resume is Stage 5).
 */
class AppEnvironment private constructor(
    val model: SessionModel,
    val settings: SettingsStore,
    val prompter: SwitchablePrompter,
    val liveDataSetUp: Boolean,
    val isDemo: Boolean,
    val opensSettings: Boolean,
    val applyScenario: (suspend () -> Unit)?
) {
    companion object {
        @Volatile private var instance: AppEnvironment? = null

        fun get(context: Context, scenarioExtra: String?, demoSpeed: Double = 1.0): AppEnvironment =
            instance ?: synchronized(this) { instance ?: build(context.applicationContext, scenarioExtra, demoSpeed).also { instance = it } }

        private fun build(context: Context, scenarioExtra: String?, demoSpeed: Double): AppEnvironment {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val keyValues = SharedPrefsKeyValues(context.getSharedPreferences("trainalarm", Context.MODE_PRIVATE))
            val settings = SettingsStore(keyValues)
            val directory = StationDirectory.load(context)
            val prompter = SwitchablePrompter()
            val token = RttToken.current

            val demo = DemoEntry.create(scenarioExtra, token == null, directory, settings, scope, demoSpeed)
            if (demo != null) {
                return AppEnvironment(demo.model, settings, prompter, true, true, demo.opensSettings, onceOnly(demo.apply))
            }
            val location = PlatformLocationSource(context)
            val base: TrainDataProvider = token?.let { RttProvider(it) } ?: UnavailableProvider()
            val model = SessionModel(
                provider = DirectoryBackedProvider(base, directory),
                scheduler = ExactAlarmScheduler(SystemExactAlarmGateway(context)),
                directory = directory, location = location, clock = SystemTrackerClock(),
                permissions = SystemPermissions(AndroidPermissionPlatform(context, prompter), KeyValuesAskedStore(keyValues)),
                settings = settings, scope = scope, locationControl = location.asControl()
            )
            return AppEnvironment(model, settings, prompter, token != null, false, false, null)
        }
    }
}

/** The scenario is applied once, even if the Activity is recreated (a second run would start a second tracker). */
private fun onceOnly(block: suspend () -> Unit): suspend () -> Unit {
    val done = java.util.concurrent.atomic.AtomicBoolean(false)
    return { if (done.compareAndSet(false, true)) block() }
}

/** Used when there is no RTT token in a non-demo build. Every call fails, and the picker says why. */
class UnavailableProvider : TrainDataProvider {
    override suspend fun searchStations(query: String): List<Station> = throw ProviderError.NotImplemented("Live data is not set up")
    override suspend fun departureBoard(station: Station, from: Instant, callingAt: Station?): List<Service> =
        throw ProviderError.NotImplemented("Live data is not set up")
    override suspend fun serviceDetails(serviceId: String, date: LocalDate): Service =
        throw ProviderError.NotImplemented("Live data is not set up")
}
```

`android/app/src/debug/java/com/trainalarm/app/demo/DemoEntry.kt`:

```kotlin
package com.trainalarm.app.demo

import com.trainalarm.app.DemoSetup
import com.trainalarm.app.SessionModel
import com.trainalarm.app.SettingsStore
import com.trainalarm.app.provider.DirectoryBackedProvider
import com.trainalarm.app.provider.StationDirectory
import com.trainalarm.app.tracking.LocationFix
import com.trainalarm.app.tracking.LocationSource
import com.trainalarm.app.tracking.SystemTrackerClock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope

/** Debug builds: the demo app when a scenario is named or there is no RTT token. */
object DemoEntry {
    fun create(scenarioExtra: String?, noToken: Boolean, directory: StationDirectory, settings: SettingsStore, scope: CoroutineScope, speed: Double): DemoSetup? {
        val scenario = Scenario.from(scenarioExtra)
        if (scenario == null && !noToken) return null
        val clock = DemoClock(speed)
        var modelRef: SessionModel? = null
        val model = SessionModel(
            provider = DirectoryBackedProvider(DemoProvider(clock), directory),
            scheduler = DemoAlarmScheduler(clock, scope) { modelRef?.alarmRang() }, directory = directory,
            location = object : LocationSource { override fun latestFix(): LocationFix? = null },
            clock = clock, permissions = DemoPermissions(), settings = settings, scope = scope
        )
        modelRef = model
        return DemoSetup(model, scenario?.opensSettings ?: false) { scenario?.apply(model, directory, Instant.now()) }
    }
}
```

`android/app/src/release/java/com/trainalarm/app/demo/DemoEntry.kt`:

```kotlin
package com.trainalarm.app.demo

import com.trainalarm.app.DemoSetup
import com.trainalarm.app.SettingsStore
import com.trainalarm.app.provider.StationDirectory
import kotlinx.coroutines.CoroutineScope

/** Release builds contain no demo code. */
object DemoEntry {
    @Suppress("UNUSED_PARAMETER")
    fun create(scenarioExtra: String?, noToken: Boolean, directory: StationDirectory, settings: SettingsStore, scope: CoroutineScope, speed: Double): DemoSetup? = null
}
```

`MainActivity.kt` (replace the file; `DevAlarmScreen` stays in the project and is linked from debug Settings in Task 15):

```kotlin
package com.trainalarm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.trainalarm.app.ui.RootScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val environment = AppEnvironment.get(this, intent.getStringExtra("scenario"), intent.getIntExtra("demoSpeed", 1).coerceAtLeast(1).toDouble())
        environment.prompter.delegate = ActivityPermissionPrompter(this)
        setContent { RootScreen(environment) }
    }
}
```

`ui/RootScreen.kt`:

```kotlin
package com.trainalarm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trainalarm.app.AppEnvironment
import com.trainalarm.app.SessionModel
import com.trainalarm.app.SessionPhase
import com.trainalarm.app.theme.LocalColors
import com.trainalarm.app.theme.TrainAlarmTheme
import kotlinx.coroutines.launch

@Composable
fun RootScreen(environment: AppEnvironment) {
    val choice by environment.settings.theme.collectAsState()
    TrainAlarmTheme(choice) {
        val c = LocalColors.current
        var showingSettings by remember { mutableStateOf(environment.opensSettings) }
        LaunchedEffect(Unit) { environment.applyScenario?.invoke() }
        Box(Modifier.fillMaxSize().background(c.bg)) {
            when (environment.model.phase) {
                SessionPhase.Selecting, SessionPhase.Confirming ->
                    HomeScreen(environment.model, environment.liveDataSetUp, environment.isDemo) { showingSettings = true }
                else -> StubSession(environment.model)   // replaced in Tasks 13 and 14
            }
            if (showingSettings) Text("Settings", color = c.head, modifier = Modifier.padding(24.dp))   // replaced in Task 15
        }
    }
}

/** Keeps the app compiling and the scenarios openable until Tasks 13 and 14 add the real screens. */
@Composable
private fun StubSession(model: SessionModel) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.spacedBy(16.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
        Text(model.phase.toString(), color = c.head)
        BigButton("I'm off", { scope.launch { model.imOff() } })
        if (model.phase is SessionPhase.Ended) BigButton("Back to start", { model.returnHome() }, style = ButtonStyle.GHOST)
    }
}
```

`ui/HomeScreen.kt`:

```kotlin
package com.trainalarm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trainalarm.app.BoardState
import com.trainalarm.app.NearestFailure
import com.trainalarm.app.SessionModel
import com.trainalarm.app.SessionPhase
import com.trainalarm.app.TimeText
import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.theme.Anton
import com.trainalarm.app.theme.LocalColors
import java.time.Instant
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: SessionModel, liveDataSetUp: Boolean, isDemo: Boolean, openSettings: () -> Unit) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<Boolean?>(null) }   // true = From, false = To
    LaunchedEffect(model.from?.id, model.to?.id) { model.loadBoard() }

    // Pull to refresh; the model's 20 s cooldown still applies.
    PullToRefreshBox(isRefreshing = model.board is BoardState.Loading, onRefresh = { scope.launch { model.loadBoard() } }) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TrainAlarm".uppercase(), fontFamily = Anton, fontSize = 20.sp, color = c.head)
            if (isDemo) Pill("Demo data")
            Box(Modifier.weight(1f))
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.surface).clickable(onClick = openSettings), Alignment.Center) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = c.head)
            }
        }
        Text("Where to?".uppercase(), fontFamily = Anton, fontSize = 58.sp, lineHeight = 58.sp, color = c.head)
        StationField("From", model.from) { picking = true }
        if (model.from == null) NearestChip(model) { scope.launch { model.useNearestStation() } }
        StationField("To", model.to) { picking = false }
        if (liveDataSetUp) BoardSection(model) else SetupCard()
    }
    }
    picking?.let { isFrom -> StationSearchSheet(model, isFrom) { picking = null } }
    if (model.phase == SessionPhase.Confirming) ConfirmSheet(model)
}

@Composable
private fun StationField(title: String, station: Station?, onClick: () -> Unit) {
    val c = LocalColors.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(title, fontSize = 12.sp, color = c.muted)
        Text(station?.name ?: "Choose a station", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (station == null) c.muted else c.head)
    }
}

@Composable
private fun NearestChip(model: SessionModel, onClick: () -> Unit) {
    val c = LocalColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (model.isFindingNearest) "Finding your nearest station..." else "Use my nearest station",
            fontSize = 13.sp, color = c.text,
            modifier = Modifier.border(1.5.dp, c.muted.copy(alpha = 0.6f), CircleShape).clip(CircleShape)
                .clickable(enabled = !model.isFindingNearest, onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp)
        )
        model.nearestFailure?.let {
            Text(
                if (it == NearestFailure.PERMISSION_DENIED) "Location is off. Search for your station instead."
                else "Could not find you yet. Search for your station instead.",
                fontSize = 13.sp, color = c.muted
            )
        }
    }
}

@Composable
private fun SetupCard() {
    val c = LocalColors.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).padding(16.dp), Arrangement.spacedBy(6.dp)) {
        Text("Live data is not set up", fontWeight = FontWeight.Bold, color = c.head)
        Text("This build has no train data token, so it cannot show departures yet.", fontSize = 13.sp, color = c.text)
    }
}

@Composable
private fun BoardSection(model: SessionModel) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val board = model.board) {
            BoardState.Idle -> Text("Choose where you are and where you are going.", fontSize = 13.sp, color = c.muted)
            BoardState.Loading -> Box(Modifier.fillMaxWidth().padding(top = 12.dp), Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            is BoardState.Loaded -> {
                Text("Trains from ${model.from?.name.orEmpty()} calling at ${model.to?.name.orEmpty()}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.muted)
                board.services.forEach { service -> BoardRow(service) { scope.launch { model.choose(service) } } }
            }
            BoardState.Empty -> Message("No trains found", "No trains call at ${model.to?.name ?: "that station"} soon. Try again in a few minutes.") { scope.launch { model.loadBoard(force = true) } }
            is BoardState.Failed -> Message("Could not load trains", board.message) { scope.launch { model.loadBoard(force = true) } }
            BoardState.RateLimited -> Message("The train data is busy", "Wait a moment, then try again.") { scope.launch { model.loadBoard() } }   // keeps the cooldown
        }
    }
}

@Composable
private fun Message(title: String, detail: String, retry: () -> Unit) {
    val c = LocalColors.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).padding(16.dp), Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Bold, color = c.head)
        Text(detail, fontSize = 13.sp, color = c.text)
        BigButton("Try again", retry, style = ButtonStyle.GHOST)
    }
}

@Composable
private fun BoardRow(service: Service, onClick: () -> Unit) {
    val c = LocalColors.current
    val stop = service.journey.stops[0]
    val departure = stop.estimatedDeparture ?: stop.scheduledDeparture
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            if (departure != null) {
                DotTime(TimeText.clock(departure), 34.dp, c.head)
                TimeText.dateLabel(departure, Instant.now())?.let { Text(it, fontSize = 11.sp, color = c.muted) }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(service.operatorName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.text)
            Text("towards ${service.journey.destination.name}", fontSize = 13.sp, color = c.muted)
        }
        val late = stop.scheduledDeparture?.let { s -> stop.estimatedDeparture?.let { e -> Math.round((e.epochSecond - s.epochSecond) / 60.0).toInt() } } ?: 0
        when {
            stop.isCancelled -> Pill("Cancelled", PillStyle.BAD)
            late > 0 -> Pill("+$late min", PillStyle.LATE)
            else -> Pill("On time")
        }
    }
}
```
(`Icons.Filled.Settings` resolved with no extra dependency in the plan dry run.)

`ui/StationSearchSheet.kt`:

```kotlin
package com.trainalarm.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trainalarm.app.SessionModel
import com.trainalarm.app.theme.LocalColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationSearchSheet(model: SessionModel, isFrom: Boolean, onDismiss: () -> Unit) {
    val c = LocalColors.current
    var query by remember { mutableStateOf("") }
    val results = model.search(query)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.bg, contentColor = c.text) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (isFrom) "Where are you now?" else "Where are you going?") }
            )
            when {
                query.isEmpty() -> Text("Type a station name", color = c.muted, modifier = Modifier.padding(vertical = 16.dp))
                results.isEmpty() -> Text("No station called that", color = c.muted, modifier = Modifier.padding(vertical = 16.dp))
                else -> LazyColumn {
                    items(results.take(30), key = { it.id }) { station ->
                        Text(
                            station.name, color = c.head,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (isFrom) model.selectFrom(station) else model.selectTo(station)
                                onDismiss()
                            }.padding(vertical = 14.dp)
                        )
                    }
                }
            }
        }
    }
}
```

`ui/ConfirmSheet.kt`:

```kotlin
package com.trainalarm.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trainalarm.app.PermissionKind
import com.trainalarm.app.SessionModel
import com.trainalarm.app.SettingsStore
import com.trainalarm.app.TimeText
import com.trainalarm.app.theme.Anton
import com.trainalarm.app.theme.LocalColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(model: SessionModel) {
    val c = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val confirm = model.confirm
    ModalBottomSheet(onDismissRequest = { model.backToSelecting() }, containerColor = c.surface, contentColor = c.text) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp), Arrangement.spacedBy(14.dp)) {
            Text("Wake me before ${model.to?.name ?: "your stop"}".uppercase(), fontFamily = Anton, fontSize = 30.sp, lineHeight = 30.sp, color = c.head)
            if (confirm != null) {
                val arrival = confirm.arrival
                if (arrival != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        AlarmDial(model.alarmAt, arrival, confirm.leadMinutes, Modifier.size(150.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Alarm at", fontSize = 12.sp, color = c.muted)
                            model.alarmAt?.let { DotTime(TimeText.clock(it), 42.dp, c.head) } ?: Text("Choose minutes", fontSize = 13.sp, color = c.muted)
                            Text("Arrives", fontSize = 12.sp, color = c.muted)
                            DotTime(TimeText.clock(arrival), 42.dp, c.head.copy(alpha = 0.5f))
                        }
                    }
                    LeadRuler(SettingsStore.LEAD_CHOICES, confirm.leadMinutes, { model.setLead(it) })
                    if (model.ringsStraightAway) Notice("The alarm will ring straight away.")
                } else {
                    Notice("This train does not call at ${model.to?.name ?: "that station"}. Pick another.")
                }
            }
            model.blockedPermission?.let {
                Notice(if (it == PermissionKind.ALARM) "Alarms are off for TrainAlarm. Allow them in Settings so it can wake you." else "A permission is off. Allow it in Settings to continue.")
            }
            model.permissionWarnings.forEach {
                when (it) {
                    PermissionKind.LOCATION -> Notice("Location is off. Live tracking will rely on train data only.")
                    PermissionKind.FULL_SCREEN_INTENT -> Notice("The alarm may not take over the lock screen. Allow full-screen alerts in Settings.")
                    else -> Unit
                }
            }
            BigButton(
                if (model.blockedPermission == null) "Start tracking" else "Allow alarms to continue",
                {
                    if (model.blockedPermission != null) {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    } else {
                        scope.launch { model.start() }
                    }
                },
                enabled = model.canStart
            )
        }
    }
}

@Composable
private fun Notice(text: String) {
    val c = LocalColors.current
    Text(text, fontSize = 13.sp, color = c.text,
        modifier = Modifier.fillMaxWidth().border(1.5.dp, c.bad, RoundedCornerShape(14.dp)).padding(12.dp))
}
```

- [ ] **Step 9: Android, build and lint**

```bash
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew test lint assembleDebug assembleRelease --offline 2>&1 | grep -E "^e: |FAILED|BUILD|Lint found" | head -8
```
Expected: `BUILD SUCCESSFUL`. With the emulator running (Step 0), install and look at the screens, in both themes:

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk; ADB=$ANDROID_HOME/platform-tools/adb
$ADB install -r android/app/build/outputs/apk/debug/app-debug.apk
for mode in no yes; do
  $ADB shell "cmd uimode night $mode"
  for s in home board confirm; do
    $ADB shell am force-stop com.trainalarm.app
    $ADB shell am start -n com.trainalarm.app/.MainActivity --es scenario $s > /dev/null; sleep 4
    $ADB exec-out screencap -p > "/tmp/ta-shots/android-$s-$mode.png"
  done
done
```
Open the six PNGs and compare with the mockup; fix differences before committing. If the emulator was not approved, say so in the ledger: Android screens compiled but unseen.

- [ ] **Step 10: Commit**

```bash
git add ios android
git rm -q ios/Sources/TrainAlarm/App/ContentView.swift
git commit -m "Feature: Add the theme, components, Home and Confirm screens on both platforms

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Tracking screen, route strip, state cards and the Ended screen

**Files (iOS):**
- Create: `ios/Sources/TrainAlarm/App/RouteProgress.swift`, `.../App/TrackingCards.swift`, `.../App/UI/TrackingView.swift`
- Modify: `ios/Sources/TrainAlarm/App/RootView.swift`
- Create (tests): `ios/Tests/TrainAlarmTests/App/RouteProgressTests.swift`, `.../App/TrackingCardsTests.swift`

**Files (Android):**
- Create: `android/app/src/main/java/com/trainalarm/app/RouteProgress.kt`, `.../TrackingCards.kt`, `.../ui/TrackingScreen.kt`
- Modify: `android/app/src/main/java/com/trainalarm/app/ui/RootScreen.kt`
- Create (tests): `android/app/src/test/java/com/trainalarm/app/RouteProgressTest.kt`, `.../TrackingCardsTest.kt`

**Interfaces:**
- Consumes: `SessionModel.display`, `confirm`, `from`, `to`, `imOff()`, `returnHome()` (Tasks 7, 8); `TrackingDisplay`; `TimeText`; the components and `AppColors` from Task 12.
- Produces: `RouteProgress.make(stops:from:to:eta:now:) -> RouteProgress?` with `fraction` (0 at departure, 1 at the destination), `nextStop: Stop?`, `stopsLeft: Int`. It is computed from the stops captured when tracking started, so intermediate stop times are not live; `eta` is live. Nil if the boarding or destination station is not in the stops, or the destination comes first.
- Produces: `StateCard(tone, title, detail)` and `TrackingCards.make(display:, destination:)`, most urgent first: alarm not set, cancelled, destination dropped, rate limited (replaces the plain feed-lost card), no live data, estimated arrival.
- Produces: `TrackingView`/`TrackingScreen` (teal hero with the countdown and arrival, alarm card, route strip, state cards, a "Keep the app open" note, "I'm off" pinned) and `EndedView`/`EndedScreen`. `RootView`/`RootScreen` use them for `tracking` and `ended`; `ringing` keeps its stub until Task 14.
- The map from the reference is Stage 4b; this screen draws a route strip in its place (spec section 8).

- [ ] **Step 1: iOS, write the failing tests**

`RouteProgressTests.swift`:

```swift
import XCTest
@testable import TrainAlarm

final class RouteProgressTests: XCTestCase {
    private func stop(_ id: String, dep: String? = nil, arr: String? = nil) -> Stop {
        Stop(station: ServiceBuilder.station(id), scheduledArrival: arr.map(ServiceBuilder.time),
             scheduledDeparture: dep.map(ServiceBuilder.time), estimatedArrival: nil, estimatedDeparture: nil)
    }

    private var stops: [Stop] { [stop("AAA", dep: "08:00:00"), stop("BBB", arr: "08:30:00"), stop("CCC", arr: "09:00:00")] }
    private func t(_ hhmmss: String) -> Date { ServiceBuilder.time(hhmmss) }

    func testBeforeDepartureTheTrainHasNotMoved() {
        let p = RouteProgress.make(stops: stops, from: "AAA", to: "CCC", eta: nil, now: t("07:50:00"))
        XCTAssertEqual(p?.fraction, 0)
        XCTAssertEqual(p?.nextStop?.station.id, "BBB")
        XCTAssertEqual(p?.stopsLeft, 2)
    }

    func testHalfwayThroughTheJourney() {
        let p = RouteProgress.make(stops: stops, from: "AAA", to: "CCC", eta: nil, now: t("08:30:00"))
        XCTAssertEqual(p?.fraction ?? 0, 0.5, accuracy: 0.001)
        XCTAssertEqual(p?.nextStop?.station.id, "CCC")
        XCTAssertEqual(p?.stopsLeft, 1)
    }

    func testAfterArrivalTheStripIsFull() {
        let p = RouteProgress.make(stops: stops, from: "AAA", to: "CCC", eta: nil, now: t("09:10:00"))
        XCTAssertEqual(p?.fraction, 1)
        XCTAssertNil(p?.nextStop)
        XCTAssertEqual(p?.stopsLeft, 0)
    }

    func testALiveEtaStretchesTheJourney() {
        let p = RouteProgress.make(stops: stops, from: "AAA", to: "CCC", eta: t("09:10:00"), now: t("08:35:00"))
        XCTAssertEqual(p?.fraction ?? 0, 0.5, accuracy: 0.001)
    }

    func testAStationThatIsNotOnTheRouteGivesNothing() {
        XCTAssertNil(RouteProgress.make(stops: stops, from: "ZZZ", to: "CCC", eta: nil, now: t("08:00:00")))
        XCTAssertNil(RouteProgress.make(stops: stops, from: "AAA", to: "ZZZ", eta: nil, now: t("08:00:00")))
    }

    func testADestinationBeforeTheBoardingStopGivesNothing() {
        XCTAssertNil(RouteProgress.make(stops: stops, from: "CCC", to: "AAA", eta: nil, now: t("08:00:00")))
    }

    func testMissingDepartureTimeKeepsTheMarkerAtTheStart() {
        let noTimes = [stop("AAA"), stop("BBB", arr: "08:30:00"), stop("CCC", arr: "09:00:00")]
        XCTAssertEqual(RouteProgress.make(stops: noTimes, from: "AAA", to: "CCC", eta: nil, now: t("08:45:00"))?.fraction, 0)
    }
}
```

`TrackingCardsTests.swift`:

```swift
import XCTest
@testable import TrainAlarm

final class TrackingCardsTests: XCTestCase {
    private func titles(_ d: TrackingDisplay) -> [String] { TrackingCards.make(display: d, destination: "Edinburgh").map(\.title) }

    func testNothingFlaggedShowsNoCards() {
        XCTAssertEqual(titles(TrackingDisplay()), [])
    }

    func testCardsAreOrderedMostUrgentFirst() {
        var d = TrackingDisplay()
        d.isEstimated = true
        d.feedLost = true
        d.destinationDropped = true
        d.cancelled = true
        d.alarmNotSet = true
        XCTAssertEqual(titles(d), ["Your alarm is NOT set", "Train cancelled", "Edinburgh is no longer on this route",
                                   "No live data", "Estimated arrival"])
    }

    func testRateLimitedReplacesTheNoLiveDataCard() {
        var d = TrackingDisplay()
        d.feedLost = true
        d.rateLimited = true
        XCTAssertEqual(titles(d), ["Train data is busy"])
    }

    func testNoLiveDataSaysWhenTheLastGoodUpdateWas() {
        var d = TrackingDisplay()
        d.feedLost = true
        d.lastGoodAt = ISO8601DateFormatter().date(from: "2026-09-13T08:00:00Z")
        let card = TrackingCards.make(display: d, destination: "Edinburgh")[0]
        XCTAssertEqual(card.detail, "Using the timetable and your GPS. Last good update 09:00.")
        XCTAssertEqual(card.tone, .plain)
    }

    func testEstimatedArrivalExplainsItsSource() {
        var d = TrackingDisplay()
        d.isEstimated = true
        d.estimateSource = .gpsPace
        XCTAssertEqual(TrackingCards.make(display: d, destination: "X")[0].detail, "Worked out from your speed and position.")
        d.estimateSource = .lastKnownTrajectory
        XCTAssertEqual(TrackingCards.make(display: d, destination: "X")[0].detail, "Worked out from the last live update.")
    }

    func testTonesMatchTheMockup() {
        var d = TrackingDisplay()
        d.cancelled = true
        XCTAssertEqual(TrackingCards.make(display: d, destination: "X")[0].tone, .warn)
        d = TrackingDisplay()
        d.alarmNotSet = true
        XCTAssertEqual(TrackingCards.make(display: d, destination: "X")[0].tone, .error)
        d = TrackingDisplay()
        d.isEstimated = true
        XCTAssertEqual(TrackingCards.make(display: d, destination: "X")[0].tone, .info)
    }
}
```

- [ ] **Step 2: iOS, run to verify failure**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/RouteProgressTests -only-testing:TrainAlarmTests/TrackingCardsTests 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | head -3
```
Expected: `cannot find 'RouteProgress' in scope`.

- [ ] **Step 3: iOS, implement the logic**

`RouteProgress.swift`:

```swift
import Foundation

/// Where the train is between the boarding station and the destination, for the route strip. Stop times are
/// the ones captured when tracking started (the tracker does not publish updated stops), so only `eta` is live.
struct RouteProgress: Equatable {
    /// 0 at departure from the boarding station, 1 at the destination.
    let fraction: Double
    let nextStop: Stop?
    /// Stops still to come, including the destination.
    let stopsLeft: Int

    static func make(stops: [Stop], from fromId: String, to toId: String, eta: Date?, now: Date) -> RouteProgress? {
        guard let boardIndex = stops.firstIndex(where: { $0.station.id == fromId }),
              let destIndex = stops.indices.first(where: { $0 > boardIndex && stops[$0].station.id == toId }) else { return nil }
        let ahead = stops[(boardIndex + 1)...destIndex].filter { !$0.hasArrived && ($0.bestArrival ?? .distantFuture) > now }
        var fraction = 0.0
        let board = stops[boardIndex]
        if let departure = board.estimatedDeparture ?? board.scheduledDeparture, let arrival = eta ?? stops[destIndex].bestArrival,
           arrival > departure {
            fraction = max(0, min(1, now.timeIntervalSince(departure) / arrival.timeIntervalSince(departure)))
        }
        return RouteProgress(fraction: fraction, nextStop: ahead.first, stopsLeft: ahead.count)
    }
}
```

`TrackingCards.swift`:

```swift
import Foundation

struct StateCard: Equatable {
    enum Tone { case warn, error, info, plain }
    let tone: Tone
    let title: String
    let detail: String
}

enum TrackingCards {
    /// The cards the tracking screen shows, most urgent first (spec section 6).
    static func make(display d: TrackingDisplay, destination: String) -> [StateCard] {
        var cards: [StateCard] = []
        if d.alarmNotSet {
            cards.append(StateCard(tone: .error, title: "Your alarm is NOT set",
                                   detail: "TrainAlarm could not schedule it and will keep trying. Do not rely on it yet."))
        }
        if d.cancelled {
            cards.append(StateCard(tone: .warn, title: "Train cancelled",
                                   detail: "This train will not run. Your alarm stays set until you tap I'm off."))
        }
        if d.destinationDropped {
            cards.append(StateCard(tone: .error, title: "\(destination) is no longer on this route",
                                   detail: "The train has been diverted. Your alarm stays set. Check with station staff."))
        }
        if d.rateLimited {
            cards.append(StateCard(tone: .plain, title: "Train data is busy", detail: "Too many requests. Retrying shortly."))
        } else if d.feedLost {
            let last = d.lastGoodAt.map { " Last good update \(TimeText.clock($0).plain)." } ?? ""
            cards.append(StateCard(tone: .plain, title: "No live data", detail: "Using the timetable and your GPS.\(last)"))
        }
        if d.isEstimated {
            let how = d.estimateSource == .gpsPace ? "Worked out from your speed and position." : "Worked out from the last live update."
            cards.append(StateCard(tone: .info, title: "Estimated arrival", detail: how))
        }
        return cards
    }
}
```

- [ ] **Step 4: iOS, run to verify pass**

Same command as Step 2. Expected: `** TEST SUCCEEDED **`, 13 tests.

- [ ] **Step 5: iOS, the screens**

`TrackingView.swift`:

```swift
import SwiftUI

struct TrackingView: View {
    @Bindable var model: SessionModel
    @Environment(\.colors) private var c

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            content(now: context.date)
        }
        .background(c.bg.ignoresSafeArea())
    }

    private func content(now: Date) -> some View {
        let display = model.display
        return ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                hero(display, now)
                alarmCard(display)
                if let progress = routeProgress(now) { RouteStripView(progress: progress, from: model.from?.name ?? "", to: model.to?.name ?? "") }
                ForEach(TrackingCards.make(display: display, destination: model.to?.name ?? "Your stop"), id: \.title) { StateCardView(card: $0) }
                Text("Keep the app open for live updates.").font(.footnote).foregroundStyle(c.muted)
            }
            .padding(18)
        }
        .safeAreaInset(edge: .bottom) {
            BigButton(title: "I'm off") { Task { await model.imOff() } }.padding(18).background(c.bg)
        }
    }

    private func routeProgress(_ now: Date) -> RouteProgress? {
        guard let confirm = model.confirm, let from = model.from, let to = model.to else { return nil }
        return RouteProgress.make(stops: confirm.service.journey.stops, from: from.id, to: to.id, eta: model.display.eta, now: now)
    }

    private func hero(_ d: TrackingDisplay, _ now: Date) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(model.to?.name ?? "").font(.anton(26)).textCase(.uppercase).foregroundStyle(c.onTeal)
            if let eta = d.eta {
                DotTime(hm: TimeText.countdown(eta.timeIntervalSince(now)), size: 84, color: c.onTeal)
                HStack(spacing: 10) {
                    Text("to go. Arrives").font(.footnote).foregroundStyle(c.onTeal)
                    DotTime(hm: TimeText.clock(eta), size: 22, color: c.onTeal)
                    if let delay = d.delayMinutes, delay > 0 { Pill(text: "\(delay) min late", style: .late) }
                    else if let delay = d.delayMinutes, delay < 0 { Pill(text: "\(-delay) min early", style: .onTeal) }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(c.teal, in: RoundedRectangle(cornerRadius: 28))
    }

    private func alarmCard(_ d: TrackingDisplay) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Alarm").font(.caption).foregroundStyle(c.muted)
                if let alarm = d.alarmAt { DotTime(hm: TimeText.clock(alarm), size: 34, color: c.head) }
                if let lead = model.confirm?.leadMinutes {
                    Text("\(lead) minutes before arrival").font(.footnote).foregroundStyle(c.muted)
                }
            }
            Spacer()
            if d.alarmNotSet { Pill(text: "NOT SET", style: .bad) }
            else { Text("Set").font(.subheadline.bold()).foregroundStyle(c.tealText) }
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .background(c.surface, in: RoundedRectangle(cornerRadius: 16))
    }
}

struct StateCardView: View {
    let card: StateCard
    @Environment(\.colors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(card.title).font(prominent ? .anton(24) : .headline).textCase(prominent ? .uppercase : nil).foregroundStyle(titleColor)
            Text(card.detail).font(.footnote).foregroundStyle(detailColor)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16).padding(.vertical, 14)
        .background(background, in: RoundedRectangle(cornerRadius: 18))
        .overlay { if card.tone == .error { RoundedRectangle(cornerRadius: 18).stroke(c.bad, lineWidth: 1.5) } }
        .accessibilityElement(children: .combine)
    }

    private var prominent: Bool { card.tone == .warn || card.tone == .info }
    private var titleColor: Color {
        switch card.tone { case .warn: return c.onAccent; case .info: return c.onTeal; case .error, .plain: return c.head }
    }
    private var detailColor: Color {
        switch card.tone { case .warn: return c.onAccent; case .info: return c.onTeal; case .error, .plain: return c.text }
    }
    private var background: Color {
        switch card.tone { case .warn: return c.accent; case .info: return c.teal; case .error, .plain: return c.surface }
    }
}

struct RouteStripView: View {
    let progress: RouteProgress
    let from: String
    let to: String
    @Environment(\.colors) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ProgressTrack(fraction: progress.fraction).frame(height: 22)
            HStack {
                Text(from)
                Spacer()
                Text(to)
            }
            .font(.caption).foregroundStyle(c.muted)
            if let next = progress.nextStop {
                HStack {
                    Text("Next stop").foregroundStyle(c.muted)
                    Text(next.station.name).bold().foregroundStyle(c.head)
                    Spacer()
                    if let time = next.bestArrival { DotTime(hm: TimeText.clock(time), size: 20, color: c.head) }
                }
                .font(.subheadline)
            }
            Text(progress.stopsLeft == 1 ? "1 stop to go" : "\(progress.stopsLeft) stops to go").font(.footnote).foregroundStyle(c.muted)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct ProgressTrack: View {
    let fraction: Double
    @Environment(\.colors) private var c

    var body: some View {
        Canvas { context, size in
            let filledUntil = size.width * CGFloat(fraction)
            var x: CGFloat = 0.75
            while x < size.width {
                context.fill(Path(CGRect(x: x, y: 0, width: 1.5, height: size.height)), with: .color(x < filledUntil ? c.teal : c.muted))
                x += 7
            }
            context.fill(Path(CGRect(x: max(0, min(size.width - 3, filledUntil - 1.5)), y: -3, width: 3, height: size.height + 6)), with: .color(c.accent))
        }
        .accessibilityHidden(true)
    }
}

struct EndedView: View {
    let model: SessionModel
    let reason: EndReason
    @Environment(\.colors) private var c

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            StateCardView(card: card)
            Spacer()
            BigButton(title: "Back to start") { model.returnHome() }
        }
        .padding(18)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(c.bg.ignoresSafeArea())
    }

    private var card: StateCard {
        switch reason {
        case .arrived:
            return StateCard(tone: .info, title: "Arrived", detail: "\(model.to?.name ?? "Your stop"). Tracking has stopped and the alarm is cleared.")
        case .imOff:
            return StateCard(tone: .plain, title: "Tracking stopped", detail: "Your alarm is cleared. Safe travels.")
        }
    }
}
```

In `RootView.swift`, replace the line `case .tracking, .ringing, .ended:` and the `StubSessionView` line under it with:

```swift
                case .tracking:
                    TrackingView(model: environment.model)
                case .ringing:
                    StubSessionView(model: environment.model)   // replaced in Task 14
                case .ended(let reason):
                    EndedView(model: environment.model, reason: reason)
```

- [ ] **Step 6: iOS, run the suite and look at the screens**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | tail -2
D="iPhone 17"; xcrun simctl boot "$D" 2>/dev/null; xcrun simctl bootstatus "$D" -b > /dev/null
xcodebuild build -scheme TrainAlarm -destination "platform=iOS Simulator,name=$D" -derivedDataPath /tmp/ta-shots 2>&1 | grep -E "error:|BUILD (SUCCEEDED|FAILED)"
APP=$(find /tmp/ta-shots -name TrainAlarm.app -type d | head -1); xcrun simctl install "$D" "$APP"
for mode in dark light; do
  xcrun simctl ui "$D" appearance $mode
  for s in tracking tracking-late cancelled dropped feed-lost alarm-not-set ended; do
    xcrun simctl terminate "$D" com.trainalarm.app 2>/dev/null
    xcrun simctl launch "$D" com.trainalarm.app -scenario $s > /dev/null; sleep 4
    xcrun simctl io "$D" screenshot "/tmp/ta-shots/ios-$s-$mode.png"
  done
done
```
Expected: `** TEST SUCCEEDED **`. Open the PNGs and compare with the mockup's tracking and states screens: a teal hero with the dot-matrix countdown, the delay pill, the alarm card, the route strip with a next stop, and each state card in its tone (orange for cancelled, outlined red for dropped and not-set, teal for estimated). Orange and teal appear only as fills. Fix any difference before committing.

- [ ] **Step 7: Android, write the failing tests**

`RouteProgressTest.kt`:

```kotlin
package com.trainalarm.app

import com.trainalarm.app.model.Stop
import com.trainalarm.app.tracking.ServiceBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteProgressTest {
    private fun stop(id: String, dep: String? = null, arr: String? = null) = Stop(
        ServiceBuilder.station(id), arr?.let(ServiceBuilder::time), dep?.let(ServiceBuilder::time), null, null
    )

    private val stops = listOf(stop("AAA", dep = "08:00:00"), stop("BBB", arr = "08:30:00"), stop("CCC", arr = "09:00:00"))
    private fun t(hhmmss: String) = ServiceBuilder.time(hhmmss)

    @Test fun beforeDeparture_theTrainHasNotMoved() {
        val p = RouteProgress.make(stops, "AAA", "CCC", null, t("07:50:00"))
        assertEquals(0.0, p?.fraction ?: -1.0, 0.0)
        assertEquals("BBB", p?.nextStop?.station?.id)
        assertEquals(2, p?.stopsLeft)
    }

    @Test fun halfwayThroughTheJourney() {
        val p = RouteProgress.make(stops, "AAA", "CCC", null, t("08:30:00"))
        assertEquals(0.5, p?.fraction ?: -1.0, 0.001)
        assertEquals("CCC", p?.nextStop?.station?.id)
        assertEquals(1, p?.stopsLeft)
    }

    @Test fun afterArrival_theStripIsFull() {
        val p = RouteProgress.make(stops, "AAA", "CCC", null, t("09:10:00"))
        assertEquals(1.0, p?.fraction ?: -1.0, 0.0)
        assertNull(p?.nextStop)
        assertEquals(0, p?.stopsLeft)
    }

    @Test fun aLiveEta_stretchesTheJourney() {
        val p = RouteProgress.make(stops, "AAA", "CCC", t("09:10:00"), t("08:35:00"))
        assertEquals(0.5, p?.fraction ?: -1.0, 0.001)
    }

    @Test fun aStationThatIsNotOnTheRoute_givesNothing() {
        assertNull(RouteProgress.make(stops, "ZZZ", "CCC", null, t("08:00:00")))
        assertNull(RouteProgress.make(stops, "AAA", "ZZZ", null, t("08:00:00")))
    }

    @Test fun aDestinationBeforeTheBoardingStop_givesNothing() =
        assertNull(RouteProgress.make(stops, "CCC", "AAA", null, t("08:00:00")))

    @Test fun missingDepartureTime_keepsTheMarkerAtTheStart() {
        val noTimes = listOf(stop("AAA"), stop("BBB", arr = "08:30:00"), stop("CCC", arr = "09:00:00"))
        assertEquals(0.0, RouteProgress.make(noTimes, "AAA", "CCC", null, t("08:45:00"))?.fraction ?: -1.0, 0.0)
    }
}
```

`TrackingCardsTest.kt`:

```kotlin
package com.trainalarm.app

import com.trainalarm.app.tracking.EtaSource
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingCardsTest {
    private fun titles(d: TrackingDisplay) = TrackingCards.make(d, "Edinburgh").map { it.title }

    @Test fun nothingFlagged_showsNoCards() = assertEquals(emptyList<String>(), titles(TrackingDisplay()))

    @Test fun cardsAreOrderedMostUrgentFirst() {
        val d = TrackingDisplay(isEstimated = true, feedLost = true, destinationDropped = true, cancelled = true, alarmNotSet = true)
        assertEquals(
            listOf("Your alarm is NOT set", "Train cancelled", "Edinburgh is no longer on this route", "No live data", "Estimated arrival"),
            titles(d)
        )
    }

    @Test fun rateLimited_replacesTheNoLiveDataCard() =
        assertEquals(listOf("Train data is busy"), titles(TrackingDisplay(feedLost = true, rateLimited = true)))

    @Test fun noLiveData_saysWhenTheLastGoodUpdateWas() {
        val card = TrackingCards.make(TrackingDisplay(feedLost = true, lastGoodAt = Instant.parse("2026-09-13T08:00:00Z")), "Edinburgh")[0]
        assertEquals("Using the timetable and your GPS. Last good update 09:00.", card.detail)
        assertEquals(StateCard.Tone.PLAIN, card.tone)
    }

    @Test fun estimatedArrival_explainsItsSource() {
        assertEquals("Worked out from your speed and position.",
            TrackingCards.make(TrackingDisplay(isEstimated = true, estimateSource = EtaSource.GPS_PACE), "X")[0].detail)
        assertEquals("Worked out from the last live update.",
            TrackingCards.make(TrackingDisplay(isEstimated = true, estimateSource = EtaSource.LAST_KNOWN_TRAJECTORY), "X")[0].detail)
    }

    @Test fun tonesMatchTheMockup() {
        assertEquals(StateCard.Tone.WARN, TrackingCards.make(TrackingDisplay(cancelled = true), "X")[0].tone)
        assertEquals(StateCard.Tone.ERROR, TrackingCards.make(TrackingDisplay(alarmNotSet = true), "X")[0].tone)
        assertEquals(StateCard.Tone.INFO, TrackingCards.make(TrackingDisplay(isEstimated = true), "X")[0].tone)
    }
}
```

- [ ] **Step 8: Android, run to verify failure**

```bash
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew testDebugUnitTest --offline --tests "com.trainalarm.app.RouteProgressTest" --tests "com.trainalarm.app.TrackingCardsTest" 2>&1 | grep -E "^e: |BUILD" | head -3
```
Expected: unresolved `RouteProgress`, `TrackingCards`, `StateCard`.

- [ ] **Step 9: Android, implement the logic and screens**

`RouteProgress.kt`:

```kotlin
package com.trainalarm.app

import com.trainalarm.app.model.Stop
import java.time.Duration
import java.time.Instant

/**
 * Where the train is between the boarding station and the destination, for the route strip. Stop times are
 * the ones captured when tracking started (the tracker does not publish updated stops), so only [eta] is live.
 */
data class RouteProgress(val fraction: Double, val nextStop: Stop?, val stopsLeft: Int) {
    companion object {
        fun make(stops: List<Stop>, fromId: String, toId: String, eta: Instant?, now: Instant): RouteProgress? {
            val boardIndex = stops.indexOfFirst { it.station.id == fromId }
            if (boardIndex < 0) return null
            val destIndex = stops.indices.firstOrNull { it > boardIndex && stops[it].station.id == toId } ?: return null
            val ahead = stops.subList(boardIndex + 1, destIndex + 1).filter { !it.hasArrived && (it.bestArrival ?: Instant.MAX) > now }
            var fraction = 0.0
            val board = stops[boardIndex]
            val departure = board.estimatedDeparture ?: board.scheduledDeparture
            val arrival = eta ?: stops[destIndex].bestArrival
            if (departure != null && arrival != null && arrival > departure) {
                fraction = (Duration.between(departure, now).toMillis().toDouble() / Duration.between(departure, arrival).toMillis()).coerceIn(0.0, 1.0)
            }
            return RouteProgress(fraction, ahead.firstOrNull(), ahead.size)
        }
    }
}
```

`TrackingCards.kt`:

```kotlin
package com.trainalarm.app

import com.trainalarm.app.tracking.EtaSource

data class StateCard(val tone: Tone, val title: String, val detail: String) {
    enum class Tone { WARN, ERROR, INFO, PLAIN }
}

object TrackingCards {
    /** The cards the tracking screen shows, most urgent first (spec section 6). */
    fun make(d: TrackingDisplay, destination: String): List<StateCard> {
        val cards = mutableListOf<StateCard>()
        if (d.alarmNotSet) {
            cards += StateCard(StateCard.Tone.ERROR, "Your alarm is NOT set", "TrainAlarm could not schedule it and will keep trying. Do not rely on it yet.")
        }
        if (d.cancelled) {
            cards += StateCard(StateCard.Tone.WARN, "Train cancelled", "This train will not run. Your alarm stays set until you tap I'm off.")
        }
        if (d.destinationDropped) {
            cards += StateCard(StateCard.Tone.ERROR, "$destination is no longer on this route", "The train has been diverted. Your alarm stays set. Check with station staff.")
        }
        if (d.rateLimited) {
            cards += StateCard(StateCard.Tone.PLAIN, "Train data is busy", "Too many requests. Retrying shortly.")
        } else if (d.feedLost) {
            val last = d.lastGoodAt?.let { " Last good update ${TimeText.clock(it).plain}." } ?: ""
            cards += StateCard(StateCard.Tone.PLAIN, "No live data", "Using the timetable and your GPS.$last")
        }
        if (d.isEstimated) {
            val how = if (d.estimateSource == EtaSource.GPS_PACE) "Worked out from your speed and position." else "Worked out from the last live update."
            cards += StateCard(StateCard.Tone.INFO, "Estimated arrival", how)
        }
        return cards
    }
}
```

`ui/TrackingScreen.kt`:

```kotlin
package com.trainalarm.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trainalarm.app.EndReason
import com.trainalarm.app.RouteProgress
import com.trainalarm.app.SessionModel
import com.trainalarm.app.StateCard
import com.trainalarm.app.TimeText
import com.trainalarm.app.TrackingCards
import com.trainalarm.app.TrackingDisplay
import com.trainalarm.app.theme.Anton
import com.trainalarm.app.theme.LocalColors
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TrackingScreen(model: SessionModel) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    val now by produceState(Instant.now()) { while (true) { value = Instant.now(); delay(1000) } }
    val d = model.display
    val progress = model.confirm?.let { confirm ->
        val from = model.from
        val to = model.to
        if (from != null && to != null) RouteProgress.make(confirm.service.journey.stops, from.id, to.id, d.eta, now) else null
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp), Arrangement.spacedBy(14.dp)) {
            Hero(model, d, now)
            AlarmCard(model, d)
            if (progress != null) RouteStrip(progress, model.from?.name.orEmpty(), model.to?.name.orEmpty())
            TrackingCards.make(d, model.to?.name ?: "Your stop").forEach { StateCardView(it) }
            Text("Keep the app open for live updates.", fontSize = 13.sp, color = c.muted)
        }
        BigButton("I'm off", { scope.launch { model.imOff() } }, Modifier.padding(18.dp))
    }
}

@Composable
private fun Hero(model: SessionModel, d: TrackingDisplay, now: Instant) {
    val c = LocalColors.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.teal).padding(18.dp), Arrangement.spacedBy(6.dp)) {
        Text((model.to?.name ?: "").uppercase(), fontFamily = Anton, fontSize = 26.sp, color = c.onTeal)
        val eta = d.eta
        if (eta != null) {
            DotTime(TimeText.countdown(Duration.between(now, eta).seconds), 84.dp, c.onTeal)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("to go. Arrives", fontSize = 13.sp, color = c.onTeal)
                DotTime(TimeText.clock(eta), 22.dp, c.onTeal)
                val delay = d.delayMinutes
                if (delay != null && delay > 0) Pill("$delay min late", PillStyle.LATE)
                else if (delay != null && delay < 0) Pill("${-delay} min early", PillStyle.ON_TEAL)
            }
        }
    }
}

@Composable
private fun AlarmCard(model: SessionModel, d: TrackingDisplay) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), Arrangement.spacedBy(2.dp)) {
            Text("Alarm", fontSize = 12.sp, color = c.muted)
            d.alarmAt?.let { DotTime(TimeText.clock(it), 34.dp, c.head) }
            model.confirm?.leadMinutes?.let { Text("$it minutes before arrival", fontSize = 13.sp, color = c.muted) }
        }
        if (d.alarmNotSet) Pill("NOT SET", PillStyle.BAD) else Text("Set", fontWeight = FontWeight.Bold, color = c.tealText)
    }
}

@Composable
fun StateCardView(card: StateCard) {
    val c = LocalColors.current
    val prominent = card.tone == StateCard.Tone.WARN || card.tone == StateCard.Tone.INFO
    val background = when (card.tone) {
        StateCard.Tone.WARN -> c.accent
        StateCard.Tone.INFO -> c.teal
        else -> c.surface
    }
    val titleColor = when (card.tone) {
        StateCard.Tone.WARN -> c.onAccent
        StateCard.Tone.INFO -> c.onTeal
        else -> c.head
    }
    val detailColor = when (card.tone) {
        StateCard.Tone.WARN -> c.onAccent
        StateCard.Tone.INFO -> c.onTeal
        else -> c.text
    }
    val outline = if (card.tone == StateCard.Tone.ERROR) Modifier.border(1.5.dp, c.bad, RoundedCornerShape(18.dp)) else Modifier
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(background).then(outline).padding(horizontal = 16.dp, vertical = 14.dp),
        Arrangement.spacedBy(3.dp)
    ) {
        if (prominent) Text(card.title.uppercase(), fontFamily = Anton, fontSize = 24.sp, lineHeight = 24.sp, color = titleColor)
        else Text(card.title, fontWeight = FontWeight.Bold, color = titleColor)
        Text(card.detail, fontSize = 13.sp, color = detailColor)
    }
}

@Composable
private fun RouteStrip(progress: RouteProgress, from: String, to: String) {
    val c = LocalColors.current
    Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
        ProgressTrack(progress.fraction, Modifier.fillMaxWidth().height(22.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(from, fontSize = 12.sp, color = c.muted, modifier = Modifier.weight(1f))
            Text(to, fontSize = 12.sp, color = c.muted)
        }
        progress.nextStop?.let { next ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Next stop", color = c.muted)
                Text(next.station.name, fontWeight = FontWeight.Bold, color = c.head, modifier = Modifier.weight(1f))
                next.bestArrival?.let { DotTime(TimeText.clock(it), 20.dp, c.head) }
            }
        }
        Text(if (progress.stopsLeft == 1) "1 stop to go" else "${progress.stopsLeft} stops to go", fontSize = 13.sp, color = c.muted)
    }
}

@Composable
private fun ProgressTrack(fraction: Double, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Canvas(modifier.clearAndSetSemantics { }) {
        val filledUntil = size.width * fraction.toFloat()
        var x = 0.75.dp.toPx()
        while (x < size.width) {
            drawRect(if (x < filledUntil) c.teal else c.muted, Offset(x, 0f), Size(1.5.dp.toPx(), size.height))
            x += 7.dp.toPx()
        }
        val markerX = (filledUntil - 1.5.dp.toPx()).coerceIn(0f, size.width - 3.dp.toPx())
        drawRect(c.accent, Offset(markerX, -3.dp.toPx()), Size(3.dp.toPx(), size.height + 6.dp.toPx()))
    }
}

@Composable
fun EndedScreen(model: SessionModel, reason: EndReason) {
    val card = when (reason) {
        EndReason.ARRIVED -> StateCard(StateCard.Tone.INFO, "Arrived", "${model.to?.name ?: "Your stop"}. Tracking has stopped and the alarm is cleared.")
        EndReason.IM_OFF -> StateCard(StateCard.Tone.PLAIN, "Tracking stopped", "Your alarm is cleared. Safe travels.")
    }
    Column(Modifier.fillMaxSize().padding(18.dp), Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        StateCardView(card)
        BigButton("Back to start", { model.returnHome() }, style = ButtonStyle.GHOST)
    }
}
```

In `RootScreen.kt`, replace the line `else -> StubSession(environment.model)   // replaced in Tasks 13 and 14` with:

```kotlin
                SessionPhase.Tracking -> TrackingScreen(environment.model)
                SessionPhase.Ringing -> StubSession(environment.model)   // replaced in Task 14
                is SessionPhase.Ended -> EndedScreen(environment.model, (environment.model.phase as SessionPhase.Ended).reason)
```

- [ ] **Step 10: Android, run to verify pass and look at the screens**

```bash
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew test lint assembleDebug assembleRelease --offline 2>&1 | grep -E "^e: |FAILED|BUILD|Lint found" | head -6
```
Expected: `BUILD SUCCESSFUL`. With the emulator running (Task 12 Step 0), capture `tracking tracking-late cancelled dropped feed-lost alarm-not-set ended` in both themes exactly as in Task 12 Step 9, open the PNGs and compare with the mockup. Without the emulator, record in the ledger that the Android screens compiled but were not seen.

- [ ] **Step 11: Commit**

```bash
git add ios android
git commit -m "Feature: Add the tracking screen, route strip, state cards and ended screen

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 14: Ringing screen, alarm wording and the ringing signal

**Files (iOS):**
- Create: `ios/Sources/TrainAlarm/App/RingTrigger.swift`, `.../App/UI/RingingView.swift`, `.../Alarm/AlarmWording.swift`
- Modify (debug only): `ios/Sources/TrainAlarm/App/DevAlarmView.swift` (a `Ring signal` line, for the device gate)
- Modify: `.../App/SessionModel.swift` (add `appBecameActive`), `.../App/RootView.swift`, `.../Alarm/AlarmKitClient.swift` (button text and tint), `.../Alarm/AlarmKitScheduler.swift` (title)
- Create (tests): `ios/Tests/TrainAlarmTests/App/RingTriggerTests.swift`, `.../Alarm/AlarmWordingTests.swift`
- Modify (tests): `ios/Tests/TrainAlarmTests/Alarm/AlarmKitSchedulerTests.swift`

**Files (Android):**
- Create: `android/app/src/main/java/com/trainalarm/app/alarm/AlarmEvents.kt`, `.../ui/RingingScreen.kt`
- Modify: `.../alarm/AlarmService.kt`, `.../alarm/AlarmActivity.kt`, `.../AppEnvironment.kt`, `.../ui/RootScreen.kt`, `android/app/src/main/res/values/strings.xml`
- Create (tests): `android/app/src/test/java/com/trainalarm/app/alarm/AlarmEventsTest.kt`

**Interfaces:**
- Consumes: `SessionModel.phase`, `display`, `to`, `alarmRang()`, `imOff()` (Tasks 7, 8); `DotTime`, `BigButton` (`.cream`/`CREAM`), `AppColors` (dark palette), `TimeText.countdown`.
- Produces (iOS): `RingTrigger.shouldRing(phase:alarmAt:now:) -> Bool` (true only while tracking, once the alarm time has been reached), `SessionModel.appBecameActive(now:)` (calls `alarmRang()` when it says so), `AlarmWording.title`/`stopText`/`tintHex`/`tint`, `AlarmRingSignal.isRinging(_:)` and `AlarmRingSignal.stream()` (an `AsyncStream<Void>` that yields while AlarmKit reports an alarm in the alerting state), and `RingingView`.
- Produces (Android): `AlarmEvents.ringing: StateFlow<Boolean>` (true from the moment `AlarmService` starts the player until it stops), `RingingScreen(destination, eta, onOff)`, and `AlarmActivity` showing that screen.
- The ringing screen is theme-independent: always the dark core in an orange and teal glow, a dot-matrix countdown and a cream "I'm off" button (spec section 5). "I'm off" ends the session through `imOff()`, which cancels the alarm. On Android the sound is stopped first and the session is ended after, because `imOff()` can wait up to 10 s for a network read to finish (the spec's Stage 2 note), and the alarm must not keep ringing meanwhile.
- Wording: the AlarmKit alert and the Android notification say **Nearly there**, and the stop button says **I'm off** (it was "Stop"), tinted with the brand orange (it was blue).
- **Device gate (iOS), checked on the user's iPhone.** The iOS ringing signal rests on `AlarmManager.alarmUpdates` and `Alarm.State.alerting`, which exist in the iOS 26.5 SDK (read in `AlarmKit.swiftinterface`) but whose behaviour has only been read, not seen. The fallback is `appBecameActive`: when the app returns to the foreground after the alarm time, the ringing screen shows. A simulator result proves neither. Two checks, in this order, each with a stop-and-report if it fails:
  1. **The signal and the wording (needs no live data).** Build the debug app to the phone, open Settings, then "Developer alarm harness" (Task 15; it shows a line `Ring signal: <time>`, added in Step 5 below), schedule a 10 s alarm with the app in the foreground. Expect: the `Ring signal` line updates when the alarm fires, and the system alarm reads "Nearly there" with an orange "I'm off" button. This also settles the spec's open fact: does AlarmKit accept that button text and a palette tint. If the line never updates, stop and report; the fallback still covers reopening the app.
  2. **The screen (needs a token, so it waits for one).** Track a real train whose alarm time is a few minutes away, keep the app in the foreground, and expect the ringing screen when the alarm fires. The Settings test alarm cannot show this: it is refused during a journey and does nothing to a session that is not tracking, by design.
  The spec's acceptance line for Stage 4 does not depend on check 2.
- Known limit: if the person stops the alarm from the lock screen and then opens the app, the ringing screen shows once. "I'm off" is the way out. Resume and background tracking are Stage 5.

- [ ] **Step 1: iOS, write the failing tests**

`RingTriggerTests.swift`:

```swift
import XCTest
@testable import TrainAlarm

final class RingTriggerTests: XCTestCase {
    private let alarm = Date(timeIntervalSince1970: 1_000_000)

    func testRingsOnceTheAlarmTimeHasPassedWhileTracking() {
        XCTAssertTrue(RingTrigger.shouldRing(phase: .tracking, alarmAt: alarm, now: alarm.addingTimeInterval(1)))
    }

    func testRingsExactlyAtTheAlarmTime() {
        XCTAssertTrue(RingTrigger.shouldRing(phase: .tracking, alarmAt: alarm, now: alarm))
    }

    func testDoesNotRingBeforeTheAlarmTime() {
        XCTAssertFalse(RingTrigger.shouldRing(phase: .tracking, alarmAt: alarm, now: alarm.addingTimeInterval(-1)))
    }

    func testDoesNotRingWithNoAlarmTime() {
        XCTAssertFalse(RingTrigger.shouldRing(phase: .tracking, alarmAt: nil, now: alarm))
    }

    func testOnlyTrackingCanStartRinging() {
        for phase: SessionPhase in [.selecting, .confirming, .ringing, .ended(.arrived), .ended(.imOff)] {
            XCTAssertFalse(RingTrigger.shouldRing(phase: phase, alarmAt: alarm, now: alarm.addingTimeInterval(60)), "\(phase)")
        }
    }
}
```

`AlarmWordingTests.swift`:

```swift
import AlarmKit
import XCTest
@testable import TrainAlarm

final class AlarmWordingTests: XCTestCase {
    func testTheStopButtonSaysImOffAndTheTitleIsNearlyThere() {
        XCTAssertEqual(AlarmWording.stopText, "I'm off")
        XCTAssertEqual(AlarmWording.title, "Nearly there")
    }

    func testTheTintIsTheBrandOrange() {
        XCTAssertEqual(AlarmWording.tintHex, 0xF36509)
    }

    func testAnAlertingAlarmCountsAsRinging() {
        XCTAssertTrue(AlarmRingSignal.isRinging([.scheduled, .alerting]))
    }

    func testScheduledOrPausedAlarmsDoNotCountAsRinging() {
        XCTAssertFalse(AlarmRingSignal.isRinging([.scheduled, .paused]))
        XCTAssertFalse(AlarmRingSignal.isRinging([]))
    }
}
```

In `AlarmKitSchedulerTests.swift`, make the fake record the title. Replace the line `    private(set) var scheduled: [(id: UUID, fireAt: Date)] = []` with:

```swift
    private(set) var scheduled: [(id: UUID, fireAt: Date)] = []
    private(set) var titles: [String] = []
```

and replace the line `        scheduled.append((id, fireAt))` with:

```swift
        scheduled.append((id, fireAt))
        titles.append(title)
```

Add this test just before `func testAuthorizedSchedulesAFixedAlarmWithoutRequesting`:

```swift
    func testTheAlarmIsTitledWithTheAppWording() async throws {
        let client = FakeAlarmKitClient()
        try await make(client).schedule(fireAt: later(600))
        XCTAssertEqual(client.titles, [AlarmWording.title])
    }

```

- [ ] **Step 2: iOS, run to verify failure**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/RingTriggerTests -only-testing:TrainAlarmTests/AlarmWordingTests -only-testing:TrainAlarmTests/AlarmKitSchedulerTests 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | head -3
```
Expected: `cannot find 'RingTrigger' in scope` (and `AlarmWording`).

- [ ] **Step 3: iOS, implement the logic and the wording**

`ios/Sources/TrainAlarm/App/RingTrigger.swift`:

```swift
import Foundation

/// The foreground fallback for "the alarm is ringing": the system alarm may have fired while the app was
/// away, so when the app comes back after the alarm time, show the ringing screen.
enum RingTrigger {
    static func shouldRing(phase: SessionPhase, alarmAt: Date?, now: Date) -> Bool {
        guard phase == .tracking, let alarmAt else { return false }
        return now >= alarmAt
    }
}
```

`ios/Sources/TrainAlarm/Alarm/AlarmWording.swift`:

```swift
import AlarmKit
import SwiftUI

/// What the system alarm says and how it is tinted (spec section 5: orange is a fill, and the alarm is the
/// one place the system draws our colour for us).
enum AlarmWording {
    static let title = "Nearly there"
    static let stopText = "I'm off"
    static let tintHex: UInt32 = Palette.dark.accent
    static var tint: Color { Color(hex: tintHex) }
}

/// Reads AlarmKit's own state so the app can show the ringing screen when the alarm fires in the foreground.
/// Verified on a device, not in unit tests; `isRinging` is the testable part.
enum AlarmRingSignal {
    static func isRinging(_ states: [Alarm.State]) -> Bool { states.contains(.alerting) }

    /// Yields once per update in which an alarm is alerting. Cancelling the consumer stops it.
    static func stream() -> AsyncStream<Void> {
        AsyncStream { continuation in
            let task = Task {
                for await alarms in AlarmManager.shared.alarmUpdates where isRinging(alarms.map(\.state)) {
                    continuation.yield()
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
```

In `AlarmKitClient.swift`, replace the first line of `schedule(id:fireAt:title:)` (`let stop = AlarmButton(text: "Stop", ...)`):

```swift
        let stop = AlarmButton(text: LocalizedStringResource(stringLiteral: AlarmWording.stopText),
                               textColor: .white, systemImageName: "stop.circle")
```

and replace `tintColor: .blue)` with `tintColor: AlarmWording.tint)`.

In `AlarmKitScheduler.swift`, replace `title: "TrainAlarm")` with `title: AlarmWording.title)`.

In `SessionModel.swift`, replace the `alarmRang()` function with:

```swift
    /// Called by the platform when the alarm starts ringing.
    func alarmRang() {
        if phase == .tracking { phase = .ringing }
    }

    /// Called when the app returns to the foreground: if the alarm time passed while the app was away,
    /// show the ringing screen.
    func appBecameActive(now: Date) {
        if RingTrigger.shouldRing(phase: phase, alarmAt: display.alarmAt, now: now) { alarmRang() }
    }
```

- [ ] **Step 4: iOS, run to verify pass**

Same command as Step 2. Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 5: iOS, the ringing screen and the root**

`ios/Sources/TrainAlarm/App/UI/RingingView.swift`:

```swift
import SwiftUI

/// Theme-independent: the dark core with an orange and teal glow, the countdown and one big button.
struct RingingView: View {
    let model: SessionModel
    private let c = AppColors.dark
    private let cream = Color(hex: 0xF3ECE3)

    var body: some View {
        ZStack {
            c.bg.ignoresSafeArea()
            glow.ignoresSafeArea()
            TimelineView(.periodic(from: .now, by: 1)) { context in content(now: context.date) }
        }
        .preferredColorScheme(.dark)   // keeps the status bar light on the dark core in a light-theme app
    }

    private var glow: some View {
        ZStack {
            RadialGradient(colors: [c.accent.opacity(0.55), .clear], center: .init(x: 0.3, y: 0.35), startRadius: 8, endRadius: 360)
            RadialGradient(colors: [c.teal.opacity(0.6), .clear], center: .init(x: 0.75, y: 0.65), startRadius: 8, endRadius: 380)
        }
    }

    private func content(now: Date) -> some View {
        VStack(spacing: 18) {
            Spacer()
            Text(AlarmWording.title).font(.anton(40)).textCase(.uppercase).foregroundStyle(cream)
            if let name = model.to?.name {
                Text(name).font(.anton(24)).textCase(.uppercase).foregroundStyle(cream.opacity(0.85))
            }
            if let eta = model.display.eta, eta > now {
                DotTime(hm: TimeText.countdown(eta.timeIntervalSince(now)), size: 110, color: cream)
                Text("to go").font(.subheadline).foregroundStyle(cream.opacity(0.8))
            } else if model.display.eta != nil {
                Text("Arriving now").font(.anton(30)).textCase(.uppercase).foregroundStyle(cream)
            }
            Spacer()
            BigButton(title: AlarmWording.stopText, style: .cream) { Task { await model.imOff() } }
        }
        .padding(24)
        .accessibilityElement(children: .contain)
    }
}
```

In `DevAlarmView.swift` (debug only), show when the ring signal last arrived. Add `    @State private var lastRing = "none yet"` under the `status` state line; add this line under `Text(status)...`:

```text
                Text("Ring signal: \(lastRing)").font(.footnote).foregroundStyle(.secondary)
```

and replace the closing lines of `body` (`            }` / `        }` / `    }` after `Text(status)` and the new line) with the same lines plus a `.task` on the `Form`:

```text
            }
        }
        .task { for await _ in AlarmRingSignal.stream() { lastRing = Date().formatted(date: .omitted, time: .standard) } }
    }
```

Replace `RootView.swift` with:

```swift
import SwiftUI

struct RootView: View {
    let environment: AppEnvironment
    @State private var showingSettings = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ThemedRoot(settings: environment.settings) {
            ZStack {
                switch environment.model.phase {
                case .selecting, .confirming:
                    HomeView(model: environment.model, liveDataSetUp: environment.liveDataSetUp,
                             isDemo: environment.isDemo) { showingSettings = true }
                case .tracking:
                    TrackingView(model: environment.model)
                case .ringing:
                    RingingView(model: environment.model)
                case .ended(let reason):
                    EndedView(model: environment.model, reason: reason)
                }
            }
            .task {
                await environment.applyScenario?()
                if environment.opensSettings { showingSettings = true }
            }
            .task {
                // Real builds only: the demo alarm scheduler never rings.
                guard !environment.isDemo else { return }
                for await _ in AlarmRingSignal.stream() { environment.model.alarmRang() }
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { environment.model.appBecameActive(now: Date()) }
            }
            .sheet(isPresented: $showingSettings) { Text("Settings") }   // replaced in Task 15
        }
    }
}
```

- [ ] **Step 6: iOS, run the suite and look at the screen**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | tail -2
D="iPhone 17"; xcrun simctl boot "$D" 2>/dev/null; xcrun simctl bootstatus "$D" -b > /dev/null
xcodebuild build -scheme TrainAlarm -destination "platform=iOS Simulator,name=$D" -derivedDataPath /tmp/ta-shots 2>&1 | grep -E "error:|BUILD (SUCCEEDED|FAILED)"
APP=$(find /tmp/ta-shots -name TrainAlarm.app -type d | head -1); xcrun simctl install "$D" "$APP"
for mode in dark light; do
  xcrun simctl ui "$D" appearance $mode
  xcrun simctl terminate "$D" com.trainalarm.app 2>/dev/null
  xcrun simctl launch "$D" com.trainalarm.app -scenario ringing > /dev/null; sleep 4
  xcrun simctl io "$D" screenshot "/tmp/ta-shots/ios-ringing-$mode.png"
done
```
Expected: `** TEST SUCCEEDED **`. Open both PNGs: they must be identical in look (dark core, orange glow top left, teal glow bottom right, "NEARLY THERE" and the destination in Anton, the dot-matrix countdown in cream with two drawn dots, a cream "I'M OFF" button). If the light screenshot is light, the screen is reading the theme by mistake.

- [ ] **Step 7: Android, write the failing test**

`android/app/src/test/java/com/trainalarm/app/alarm/AlarmEventsTest.kt`:

```kotlin
package com.trainalarm.app.alarm

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmEventsTest {
    @After fun reset() = AlarmEvents.stopped()

    @Test fun `not ringing until the service starts the player`() {
        assertFalse(AlarmEvents.ringing.value)
    }

    @Test fun `ringing from start until stop`() {
        AlarmEvents.started()
        assertTrue(AlarmEvents.ringing.value)
        AlarmEvents.stopped()
        assertFalse(AlarmEvents.ringing.value)
    }

    @Test fun `stopping twice is harmless`() {
        AlarmEvents.stopped()
        AlarmEvents.stopped()
        assertFalse(AlarmEvents.ringing.value)
    }
}
```

- [ ] **Step 8: Android, run to verify failure**

```bash
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew testDebugUnitTest --offline --tests "com.trainalarm.app.alarm.AlarmEventsTest" 2>&1 | grep -E "^e: |BUILD" | head -3
```
Expected: unresolved `AlarmEvents`.

- [ ] **Step 9: Android, implement the signal, the screen and the wiring**

`android/app/src/main/java/com/trainalarm/app/alarm/AlarmEvents.kt`:

```kotlin
package com.trainalarm.app.alarm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the alarm is ringing right now. `AlarmService` runs in the app's process, so a plain process-wide
 * flow is enough: the session model collects it and moves to the ringing phase.
 */
object AlarmEvents {
    private val state = MutableStateFlow(false)
    val ringing: StateFlow<Boolean> = state.asStateFlow()

    fun started() { state.value = true }
    fun stopped() { state.value = false }
}
```

`AlarmService.kt`: in `onStartCommand`, add `AlarmEvents.started()` on the line after `AlarmPlayer.start(this) { stopRinging() }`; in `onDestroy`, add `AlarmEvents.stopped()` after `AlarmPlayer.stop()`; in `stopRinging`, add `AlarmEvents.stopped()` after `AlarmPlayer.stop()`. The result reads:

```kotlin
        AlarmPlayer.start(this) { stopRinging() }
        AlarmEvents.started()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AlarmPlayer.stop()
        AlarmEvents.stopped()
        super.onDestroy()
    }

    private fun stopRinging() {
        Log.i(TAG, "alarm stopped")
        AlarmPlayer.stop()
        AlarmEvents.stopped()
        getSystemService(NotificationManager::class.java).cancel(AlarmNotifications.NOTIFICATION_ID)
        stopSelf()
    }
```

`strings.xml`: set `alarm_title` to `Nearly there` and `alarm_stop` to `I'm off` (write the apostrophe as `I\'m off` in the XML).

`android/app/src/main/java/com/trainalarm/app/ui/RingingScreen.kt`:

```kotlin
package com.trainalarm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trainalarm.app.TimeText
import com.trainalarm.app.theme.Anton
import com.trainalarm.app.theme.AppColors
import com.trainalarm.app.theme.Palette
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/** Theme-independent: the dark core with an orange and teal glow, the countdown and one big button. */
@Composable
fun RingingScreen(destination: String, eta: Instant?, onOff: () -> Unit) {
    val c = remember { AppColors(Palette.dark) }
    val cream = Color(0xFFF3ECE3)
    val now by produceState(Instant.now()) { while (true) { value = Instant.now(); delay(1000) } }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(
            listOf(c.accent.copy(alpha = 0.55f), Color.Transparent), center = Offset(300f, 700f), radius = 900f)))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(
            listOf(c.teal.copy(alpha = 0.6f), Color.Transparent), center = Offset(800f, 1700f), radius = 1000f)))
        Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.spacedBy(18.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
            Text("NEARLY THERE", fontFamily = Anton, fontSize = 40.sp, color = cream, textAlign = TextAlign.Center)
            if (destination.isNotEmpty()) Text(destination.uppercase(), fontFamily = Anton, fontSize = 24.sp, color = cream.copy(alpha = 0.85f), textAlign = TextAlign.Center)
            if (eta != null && eta.isAfter(now)) {
                DotTime(TimeText.countdown(Duration.between(now, eta).seconds), 110.dp, cream)
                Text("to go", fontSize = 15.sp, color = cream.copy(alpha = 0.8f))
            } else if (eta != null) {
                Text("ARRIVING NOW", fontFamily = Anton, fontSize = 30.sp, color = cream)
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(24.dp)) { BigButton("I'm off", onOff, style = ButtonStyle.CREAM) }
    }
}
```

`AlarmActivity.kt` (replace the whole file; the lock-screen flags stay, the plain Stop button goes):

```kotlin
package com.trainalarm.app.alarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.trainalarm.app.AppEnvironment
import com.trainalarm.app.SessionPhase
import com.trainalarm.app.ui.RingingScreen
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Shown over the lock screen while the alarm rings. If the process was started by the alarm itself
 * (no session in memory, resume is Stage 5) it shows the screen without a countdown.
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // The alarm must stay stoppable even if the app state cannot be built (for example, the process was
        // started by the alarm itself and loading the stations failed): then the screen has no countdown.
        val model = runCatching { AppEnvironment.get(this, null).model }.getOrNull()
        setContent {
            RingingScreen(model?.to?.name.orEmpty(), model?.display?.eta) {
                // Silence first: ending the session can wait on a network read. MainScope outlives this activity.
                startService(AlarmService.stopIntent(this@AlarmActivity))
                if (model != null && (model.phase == SessionPhase.Ringing || model.phase == SessionPhase.Tracking)) {
                    MainScope().launch { model.imOff() }
                }
                finish()
            }
        }
    }
}
```

`AppEnvironment.kt`: add the imports `import com.trainalarm.app.alarm.AlarmEvents` (next to the `ExactAlarmScheduler` import) and `import kotlinx.coroutines.launch` (after `import kotlinx.coroutines.SupervisorJob`). Replace the line `            return AppEnvironment(model, settings, prompter, token != null, false, false, null)` with:

```kotlin
            scope.launch { AlarmEvents.ringing.collect { if (it) model.alarmRang() } }
            return AppEnvironment(model, settings, prompter, token != null, false, false, null)
```

`RootScreen.kt`: replace the `SessionPhase.Ringing -> StubSession(environment.model)   // replaced in Task 14` line with the real screen, and delete the `StubSession` function and the imports it alone used (`Arrangement`, `Column`, `rememberCoroutineScope`, `Alignment`, `launch`) if lint reports them unused:

```kotlin
                SessionPhase.Ringing -> RingingScreen(environment.model.to?.name.orEmpty(), environment.model.display.eta) {
                    ringContext.startService(AlarmService.stopIntent(ringContext))   // silence first
                    ringScope.launch { environment.model.imOff() }
                }
```

and add `val ringScope = rememberCoroutineScope()` and `val ringContext = LocalContext.current` as the first two lines inside `TrainAlarmTheme(choice) {`. Add the imports `androidx.compose.ui.platform.LocalContext` and `com.trainalarm.app.alarm.AlarmService`; keep the `rememberCoroutineScope` and `launch` imports.

- [ ] **Step 10: Android, run to verify pass**

```bash
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew test lint assembleDebug assembleRelease --offline 2>&1 | grep -E "^e: |FAILED|BUILD|Lint found" | head -6
```
Expected: `BUILD SUCCESSFUL`. With the emulator running (Task 12 Step 0), capture the `ringing` scenario in both themes as in Task 12 Step 9 and compare with the iOS screenshots. Without it, record in the ledger that the Android screen compiled but was not seen.

Device check for Android, on the user's phone (Stage 3 test is still open and this rides on it): schedule a test alarm (the Settings button from Task 15, or the debug dev harness), lock the phone, let it ring: the lock-screen activity shows "NEARLY THERE" and a cream "I'm off" button, and tapping it stops the sound. A simulator or emulator run does not count.

- [ ] **Step 11: Commit**

```bash
git add ios android
git commit -m "Feature: Add the ringing screen, alarm wording and ringing signal

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 15: Settings screen

**Files (iOS):**
- Create: `ios/Sources/TrainAlarm/App/TestAlarmState.swift`, `.../App/UI/SettingsView.swift`
- Modify: `.../App/SessionModel.swift`, `.../App/RootView.swift`
- Create (tests): `ios/Tests/TrainAlarmTests/App/SessionModelSettingsTests.swift`

**Files (Android):**
- Create: `android/app/src/main/java/com/trainalarm/app/TestAlarmState.kt`, `.../ui/SettingsScreen.kt`
- Modify: `.../SessionModel.kt`, `.../ui/RootScreen.kt`
- Create (tests): `android/app/src/test/java/com/trainalarm/app/SessionModelSettingsTest.kt`

**Interfaces:**
- Consumes: `SettingsStore` (`theme`, `setTheme`, `defaultLeadMinutes`, `setDefaultLeadMinutes`, `leadChoices`; Task 6), `PermissionsGateway` (`requiredKinds`, `optionalKinds`, `status`, `request`; Task 7), `AlarmScheduler.schedule(fireAt:)`, `TrackerClock.now()`, `LeadRuler`, `Pill`, `BigButton` (Task 12), `DevAlarmView`/`DevAlarmScreen` (Stage 3, debug only).
- Produces on `SessionModel`: `settingsPermissionKinds` (required then optional), `permissionStatus(_:)`, `permissionRevision`, `refreshPermissions()`, `requestPermission(_:)`, `canTestAlarm`, `testAlarm: TestAlarmState` and `scheduleTestAlarm()`.
- Produces: `TestAlarmState` (`idle`, `scheduled(at)`, `failed`), `SettingsView`/`SettingsScreen`.
- The settings screen holds: **Appearance** (System, Dark, Light), **Alert time** (the default lead time, on the same ruler as the Confirm sheet), **Permissions** (one row per permission this platform asks for, with its status and a Fix button), **Test alarm** ("Test alarm in 10 seconds"), **About** (attribution) and, in debug builds only, a link to the Stage 3 dev harness.
- **The test alarm shares the single alarm slot.** `AlarmScheduler` holds one alarm at a time (Stage 3), so a test alarm during a journey would replace the real one. `canTestAlarm` is therefore true only while picking or confirming, and `scheduleTestAlarm()` does nothing otherwise. A test pins it.
- **Fix button.** If the permission has never been asked, Fix shows the system prompt. If it was refused, the system never prompts again, so Fix opens the system settings page for it (iOS: the app's settings; Android: the exact-alarm or full-screen page where one exists, else the app's details page). The status re-reads when the person comes back.
- Attribution text (spec section 8 licences): station data is NaPTAN under the Open Government Licence v3.0, train times are from Realtime Trains. Map attribution arrives with the map (Stage 4b).
- This is where the device checks of Task 14 get their alarm: tap "Test alarm in 10 seconds" on the user's phone.

- [ ] **Step 1: iOS, write the failing tests**

`ios/Tests/TrainAlarmTests/App/SessionModelSettingsTests.swift`:

```swift
import XCTest
@testable import TrainAlarm

@MainActor
final class SessionModelSettingsTests: XCTestCase {
    private struct BoardProvider: TrainDataProvider {
        func searchStations(query: String) async throws -> [Station] { [] }
        func departureBoard(station: Station, from: Date, callingAt: Station?) async throws -> [Service] { [ServiceBuilder.happyPath()] }
        func serviceDetails(serviceId: String, date: Date) async throws -> Service { ServiceBuilder.happyPath() }
    }

    private final class Permissions: PermissionsGateway {
        var requiredKinds: [PermissionKind] = [.alarm]
        var optionalKinds: [PermissionKind] = [.location]
        var statuses: [PermissionKind: PermissionStatus] = [.alarm: .granted, .location: .notDetermined]
        private(set) var requested: [PermissionKind] = []
        func status(_ kind: PermissionKind) -> PermissionStatus { statuses[kind] ?? .granted }
        func request(_ kind: PermissionKind) async -> PermissionStatus {
            requested.append(kind)
            statuses[kind] = .granted
            return .granted
        }
    }

    private struct Rig {
        let model: SessionModel
        let scheduler: FakeAlarmScheduler
        let clock: FakeClock
        let permissions: Permissions
        let wat: Station
        let bsk: Station
    }

    private let directoryJSON = #"[{"crs":"WAT","name":"London Waterloo","lat":51.5031,"lon":-0.1132},{"crs":"BSK","name":"Basingstoke","lat":51.2686,"lon":-1.0872}]"#

    private func makeRig() throws -> Rig {
        let directory = try StationDirectory.parse(Data(directoryJSON.utf8))
        let scheduler = FakeAlarmScheduler()
        let clock = FakeClock(start: ServiceBuilder.time("07:00:00"))
        let permissions = Permissions()
        let settings = SettingsStore(defaults: UserDefaults(suiteName: "settings-test-\(UUID().uuidString)")!)
        let model = SessionModel(provider: BoardProvider(), scheduler: scheduler, directory: directory,
                                 location: FakeLocationSource(), clock: clock, permissions: permissions, settings: settings)
        return Rig(model: model, scheduler: scheduler, clock: clock, permissions: permissions,
                   wat: directory.station(crs: "WAT")!, bsk: directory.station(crs: "BSK")!)
    }

    func testSettingsListsRequiredPermissionsThenOptionalOnes() throws {
        XCTAssertEqual(try makeRig().model.settingsPermissionKinds, [.alarm, .location])
    }

    func testPermissionStatusReadsTheGateway() throws {
        let rig = try makeRig()
        XCTAssertEqual(rig.model.permissionStatus(.alarm), .granted)
        XCTAssertEqual(rig.model.permissionStatus(.location), .notDetermined)
    }

    func testRequestingAPermissionAsksTheGatewayAndRefreshes() async throws {
        let rig = try makeRig()
        let before = rig.model.permissionRevision
        await rig.model.requestPermission(.location)
        XCTAssertEqual(rig.permissions.requested, [.location])
        XCTAssertEqual(rig.model.permissionStatus(.location), .granted)
        XCTAssertGreaterThan(rig.model.permissionRevision, before)
    }

    func testRefreshingPermissionsBumpsTheRevision() throws {
        let rig = try makeRig()
        let before = rig.model.permissionRevision
        rig.model.refreshPermissions()
        XCTAssertEqual(rig.model.permissionRevision, before + 1)
    }

    func testTestAlarmRingsTenSecondsFromNow() async throws {
        let rig = try makeRig()
        await rig.model.scheduleTestAlarm()
        let expected = rig.clock.now().addingTimeInterval(10)
        XCTAssertEqual(rig.scheduler.scheduled, [expected])
        XCTAssertEqual(rig.model.testAlarm, .scheduled(expected))
    }

    func testAFailedTestAlarmIsReported() async throws {
        let rig = try makeRig()
        rig.scheduler.failuresRemaining = 1
        await rig.model.scheduleTestAlarm()
        XCTAssertEqual(rig.model.testAlarm, .failed)
        XCTAssertTrue(rig.scheduler.scheduled.isEmpty)
    }

    func testTestAlarmIsRefusedWhileTrackingSoTheRealAlarmSurvives() async throws {
        let rig = try makeRig()
        rig.model.selectFrom(rig.wat)
        rig.model.selectTo(rig.bsk)
        await rig.model.loadBoard()
        guard case let .loaded(services) = rig.model.board else { return XCTFail("board not loaded") }
        await rig.model.choose(services[0])
        rig.model.setLead(15)
        await rig.model.start()
        await rig.clock.waitForSleepCount(1)
        let attempts = rig.scheduler.attempts.count

        XCTAssertFalse(rig.model.canTestAlarm)
        await rig.model.scheduleTestAlarm()

        XCTAssertEqual(rig.scheduler.attempts.count, attempts)
        XCTAssertEqual(rig.model.testAlarm, .idle)
        await rig.model.imOff()
    }
}
```

- [ ] **Step 2: iOS, run to verify failure**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/SessionModelSettingsTests 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | head -3
```
Expected: `value of type 'SessionModel' has no member 'settingsPermissionKinds'`.

- [ ] **Step 3: iOS, implement the model additions**

`ios/Sources/TrainAlarm/App/TestAlarmState.swift`:

```swift
import Foundation

/// The result of "Test alarm in 10 seconds" on the Settings screen.
enum TestAlarmState: Equatable {
    case idle
    case scheduled(Date)
    case failed
}
```

In `SessionModel.swift`, insert this block immediately before the line `    // MARK: Nearest station`:

```swift
    // MARK: Settings

    static let testAlarmDelay: TimeInterval = 10

    /// Bumped whenever the permission picture may have changed, so the Settings screen re-reads it.
    private(set) var permissionRevision = 0
    private(set) var testAlarm: TestAlarmState = .idle

    /// Every permission this platform asks for: the required ones first.
    var settingsPermissionKinds: [PermissionKind] { permissions.requiredKinds + permissions.optionalKinds }

    func permissionStatus(_ kind: PermissionKind) -> PermissionStatus {
        _ = permissionRevision   // makes a view that calls this re-render after a refresh
        return permissions.status(kind)
    }

    func refreshPermissions() { permissionRevision += 1 }

    func requestPermission(_ kind: PermissionKind) async {
        _ = await permissions.request(kind)
        refreshPermissions()
    }

    /// The scheduler holds one alarm, so a test alarm is only allowed before a journey starts.
    var canTestAlarm: Bool { phase == .selecting || phase == .confirming }

    func scheduleTestAlarm() async {
        guard canTestAlarm else { return }
        let fireAt = clock.now().addingTimeInterval(Self.testAlarmDelay)
        do {
            try await scheduler.schedule(fireAt: fireAt)
            testAlarm = .scheduled(fireAt)
        } catch {
            testAlarm = .failed
        }
    }

```

- [ ] **Step 4: iOS, run to verify pass**

Same command as Step 2. Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 5: iOS, the screen**

`ios/Sources/TrainAlarm/App/UI/SettingsView.swift`:

```swift
import SwiftUI

struct SettingsView: View {
    let model: SessionModel
    let settings: SettingsStore
    @Environment(\.colors) private var c
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    #if DEBUG
    @State private var showingDevHarness = false
    #endif

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack {
                    Text("Settings").font(.anton(34)).textCase(.uppercase).foregroundStyle(c.head)
                    Spacer()
                    Button("Done") { dismiss() }.font(.headline).foregroundStyle(c.head)
                }
                section("Appearance") {
                    Picker("Theme", selection: Binding(get: { settings.theme }, set: { settings.setTheme($0) })) {
                        Text("System").tag(ThemeChoice.system)
                        Text("Dark").tag(ThemeChoice.dark)
                        Text("Light").tag(ThemeChoice.light)
                    }
                    .pickerStyle(.segmented)
                }
                section("Alert time") {
                    LeadRuler(choices: SettingsStore.leadChoices, selected: settings.defaultLeadMinutes) { settings.setDefaultLeadMinutes($0) }
                    Text("Minutes before arrival. You can still change it for each journey.").font(.footnote).foregroundStyle(c.muted)
                }
                section("Permissions") {
                    ForEach(model.settingsPermissionKinds, id: \.self) { kind in permissionRow(kind) }
                }
                section("Test alarm") {
                    BigButton(title: "Test alarm in 10 seconds", style: .ghost, isEnabled: model.canTestAlarm) {
                        Task { await model.scheduleTestAlarm() }
                    }
                    testAlarmNote
                }
                section("About") {
                    Text("Station data: NaPTAN, Open Government Licence v3.0.").font(.footnote).foregroundStyle(c.text)
                    Text("Train times: Realtime Trains, realtimetrains.co.uk.").font(.footnote).foregroundStyle(c.text)
                    Text("Version \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")")
                        .font(.footnote).foregroundStyle(c.muted)
                }
                #if DEBUG
                Button("Developer alarm harness") { showingDevHarness = true }
                    .font(.footnote).foregroundStyle(c.muted)
                    .sheet(isPresented: $showingDevHarness) { DevAlarmView() }
                #endif
            }
            .padding(18)
        }
        .background(c.bg.ignoresSafeArea())
        .presentationBackground(c.bg)
        .onChange(of: scenePhase) { _, phase in if phase == .active { model.refreshPermissions() } }
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.anton(20)).textCase(.uppercase).foregroundStyle(c.head)
            VStack(alignment: .leading, spacing: 10) { content() }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .background(c.surface, in: RoundedRectangle(cornerRadius: 18))
        }
    }

    private func permissionRow(_ kind: PermissionKind) -> some View {
        let status = model.permissionStatus(kind)
        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(Self.title(kind)).font(.subheadline.bold()).foregroundStyle(c.head)
                Text(Self.reason(kind)).font(.footnote).foregroundStyle(c.muted)
            }
            Spacer()
            if status == .granted {
                Pill(text: "On", style: .plain)
            } else {
                Pill(text: status == .denied ? "Off" : "Not asked", style: .bad)
                Button("Fix") { fix(kind, status) }.font(.subheadline.bold()).foregroundStyle(c.tealText)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func fix(_ kind: PermissionKind, _ status: PermissionStatus) {
        if status == .notDetermined {
            Task { await model.requestPermission(kind) }
        } else if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    @ViewBuilder private var testAlarmNote: some View {
        switch model.testAlarm {
        case .idle:
            if !model.canTestAlarm { Text("Not while a journey is running.").font(.footnote).foregroundStyle(c.muted) }
        case .scheduled(let at):
            HStack(spacing: 6) {
                Text("Rings at").font(.footnote).foregroundStyle(c.muted)
                DotTime(hm: TimeText.clock(at), size: 18, color: c.head)
            }
        case .failed:
            Text("Could not set the test alarm. Check the permissions above.").font(.footnote).foregroundStyle(c.bad)
        }
    }

    static func title(_ kind: PermissionKind) -> String {
        switch kind {
        case .alarm: return "Alarms"
        case .notifications: return "Notifications"
        case .location: return "Location"
        case .fullScreenIntent: return "Full-screen alerts"
        }
    }

    static func reason(_ kind: PermissionKind) -> String {
        switch kind {
        case .alarm: return "Needed to wake you."
        case .notifications: return "Needed to show the alarm."
        case .location: return "Sharpens the arrival time. Optional."
        case .fullScreenIntent: return "Lets the alarm take over the lock screen. Optional."
        }
    }
}
```

In `RootView.swift`, replace `.sheet(isPresented: $showingSettings) { Text("Settings") }   // replaced in Task 15` with:

```swift
            .sheet(isPresented: $showingSettings) { SettingsView(model: environment.model, settings: environment.settings) }
```

- [ ] **Step 6: iOS, run the suite and look at the screen**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | tail -2
D="iPhone 17"; xcrun simctl boot "$D" 2>/dev/null; xcrun simctl bootstatus "$D" -b > /dev/null
xcodebuild build -scheme TrainAlarm -destination "platform=iOS Simulator,name=$D" -derivedDataPath /tmp/ta-shots 2>&1 | grep -E "error:|BUILD (SUCCEEDED|FAILED)"
APP=$(find /tmp/ta-shots -name TrainAlarm.app -type d | head -1); xcrun simctl install "$D" "$APP"
for mode in dark light; do
  xcrun simctl ui "$D" appearance $mode
  xcrun simctl terminate "$D" com.trainalarm.app 2>/dev/null
  xcrun simctl launch "$D" com.trainalarm.app -scenario settings > /dev/null; sleep 4
  xcrun simctl io "$D" screenshot "/tmp/ta-shots/ios-settings-$mode.png"
done
```
Expected: `** TEST SUCCEEDED **`. Open both PNGs and compare with the mockup's settings screen: Anton heading, the segmented theme control, the lead ruler, permission rows with a pill and Fix where a permission is off, the ghost test-alarm button, attribution. In the demo build all permissions read as granted, so also confirm the Off and Fix state by reading the code path; the unit tests cover the logic and the real prompts are a device check.

- [ ] **Step 7: Android, write the failing tests**

`android/app/src/test/java/com/trainalarm/app/SessionModelSettingsTest.kt`:

```kotlin
package com.trainalarm.app

import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.provider.StationDirectory
import com.trainalarm.app.provider.TrainDataProvider
import com.trainalarm.app.tracking.FakeAlarmScheduler
import com.trainalarm.app.tracking.FakeClock
import com.trainalarm.app.tracking.FakeLocationSource
import com.trainalarm.app.tracking.ServiceBuilder
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionModelSettingsTest {
    private class BoardProvider : TrainDataProvider {
        override suspend fun searchStations(query: String): List<Station> = emptyList()
        override suspend fun departureBoard(station: Station, from: Instant, callingAt: Station?): List<Service> = listOf(ServiceBuilder.happyPath())
        override suspend fun serviceDetails(serviceId: String, date: LocalDate): Service = ServiceBuilder.happyPath()
    }

    private class Permissions : PermissionsGateway {
        override val requiredKinds = listOf(PermissionKind.ALARM)
        override val optionalKinds = listOf(PermissionKind.LOCATION)
        val statuses = mutableMapOf(PermissionKind.ALARM to PermissionStatus.GRANTED, PermissionKind.LOCATION to PermissionStatus.NOT_DETERMINED)
        val requested = mutableListOf<PermissionKind>()
        override fun status(kind: PermissionKind) = statuses[kind] ?: PermissionStatus.GRANTED
        override suspend fun request(kind: PermissionKind): PermissionStatus {
            requested += kind
            statuses[kind] = PermissionStatus.GRANTED
            return PermissionStatus.GRANTED
        }
    }

    private class Memory : KeyValues {
        val map = mutableMapOf<String, Any>()
        override fun getString(key: String) = map[key] as? String
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getInt(key: String) = map[key] as? Int
        override fun putInt(key: String, value: Int) { map[key] = value }
    }

    private class Rig(scope: CoroutineScope) {
        val directory = StationDirectory.parse(
            """[{"crs":"WAT","name":"London Waterloo","lat":51.5031,"lon":-0.1132},{"crs":"BSK","name":"Basingstoke","lat":51.2686,"lon":-1.0872}]"""
        )
        val scheduler = FakeAlarmScheduler()
        val clock = FakeClock(ServiceBuilder.time("07:00:00"))
        val permissions = Permissions()
        val model = SessionModel(BoardProvider(), scheduler, directory, FakeLocationSource(), clock, permissions, SettingsStore(Memory()), scope)
    }

    private fun run(block: suspend Rig.() -> Unit) = runBlocking<Unit> { Rig(this).block() }

    @Test fun settingsListsRequiredPermissionsThenOptionalOnes() = run {
        assertEquals(listOf(PermissionKind.ALARM, PermissionKind.LOCATION), model.settingsPermissionKinds)
    }

    @Test fun permissionStatusReadsTheGateway() = run {
        assertEquals(PermissionStatus.GRANTED, model.permissionStatus(PermissionKind.ALARM))
        assertEquals(PermissionStatus.NOT_DETERMINED, model.permissionStatus(PermissionKind.LOCATION))
    }

    @Test fun requestingAPermissionAsksTheGatewayAndRefreshes() = run {
        val before = model.permissionRevision
        model.requestPermission(PermissionKind.LOCATION)
        assertEquals(listOf(PermissionKind.LOCATION), permissions.requested)
        assertEquals(PermissionStatus.GRANTED, model.permissionStatus(PermissionKind.LOCATION))
        assertTrue(model.permissionRevision > before)
    }

    @Test fun refreshingPermissionsBumpsTheRevision() = run {
        val before = model.permissionRevision
        model.refreshPermissions()
        assertEquals(before + 1, model.permissionRevision)
    }

    @Test fun testAlarmRingsTenSecondsFromNow() = run {
        model.scheduleTestAlarm()
        val expected = clock.now().plusSeconds(10)
        assertEquals(listOf(expected), scheduler.scheduled)
        assertEquals(TestAlarmState.Scheduled(expected), model.testAlarm)
    }

    @Test fun aFailedTestAlarmIsReported() = run {
        scheduler.failuresRemaining = 1
        model.scheduleTestAlarm()
        assertEquals(TestAlarmState.Failed, model.testAlarm)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test fun testAlarmIsRefusedWhileTrackingSoTheRealAlarmSurvives() = run {
        model.selectFrom(directory.station("WAT")!!)
        model.selectTo(directory.station("BSK")!!)
        model.loadBoard()
        model.choose((model.board as BoardState.Loaded).services[0])
        model.setLead(15)
        model.start()
        clock.awaitSleepCount(1)
        val attempts = scheduler.attempts.size

        assertFalse(model.canTestAlarm)
        model.scheduleTestAlarm()

        assertEquals(attempts, scheduler.attempts.size)
        assertEquals(TestAlarmState.Idle, model.testAlarm)
        model.imOff()
    }
}
```

- [ ] **Step 8: Android, run to verify failure**

```bash
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew testDebugUnitTest --offline --tests "com.trainalarm.app.SessionModelSettingsTest" 2>&1 | grep -E "^e: |BUILD" | head -3
```
Expected: unresolved `settingsPermissionKinds`, `TestAlarmState`.

- [ ] **Step 9: Android, implement the model additions and the screen**

`android/app/src/main/java/com/trainalarm/app/TestAlarmState.kt`:

```kotlin
package com.trainalarm.app

import java.time.Instant

/** How long "Test alarm in 10 seconds" waits. */
const val TEST_ALARM_DELAY_SECONDS = 10L

/** The result of the test alarm on the Settings screen. */
sealed interface TestAlarmState {
    object Idle : TestAlarmState
    data class Scheduled(val at: Instant) : TestAlarmState
    object Failed : TestAlarmState
}
```

In `SessionModel.kt`, insert this block immediately before the line `    companion object {`:

```kotlin
    // Settings

    /** Bumped whenever the permission picture may have changed, so the Settings screen re-reads it. */
    var permissionRevision: Int by mutableStateOf(0)
        private set
    var testAlarm: TestAlarmState by mutableStateOf(TestAlarmState.Idle)
        private set

    /** Every permission this platform asks for: the required ones first. */
    val settingsPermissionKinds: List<PermissionKind> get() = permissions.requiredKinds + permissions.optionalKinds

    fun permissionStatus(kind: PermissionKind): PermissionStatus =
        permissionRevision.let { permissions.status(kind) }   // reading the revision re-renders a screen after a refresh

    fun refreshPermissions() { permissionRevision += 1 }

    suspend fun requestPermission(kind: PermissionKind) {
        permissions.request(kind)
        refreshPermissions()
    }

    /** The scheduler holds one alarm, so a test alarm is only allowed before a journey starts. */
    val canTestAlarm: Boolean get() = phase == SessionPhase.Selecting || phase == SessionPhase.Confirming

    suspend fun scheduleTestAlarm() {
        if (!canTestAlarm) return
        val fireAt = clock.now().plusSeconds(TEST_ALARM_DELAY_SECONDS)
        testAlarm = try {
            scheduler.schedule(fireAt)
            TestAlarmState.Scheduled(fireAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TestAlarmState.Failed
        }
    }

```

`android/app/src/main/java/com/trainalarm/app/ui/SettingsScreen.kt`:

```kotlin
package com.trainalarm.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.trainalarm.app.BuildConfig
import com.trainalarm.app.PermissionKind
import com.trainalarm.app.PermissionStatus
import com.trainalarm.app.SessionModel
import com.trainalarm.app.SettingsStore
import com.trainalarm.app.TestAlarmState
import com.trainalarm.app.ThemeChoice
import com.trainalarm.app.TimeText
import com.trainalarm.app.alarm.AlarmPermissions
import com.trainalarm.app.alarm.DevAlarmScreen
import com.trainalarm.app.theme.Anton
import com.trainalarm.app.theme.LocalColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(model: SessionModel, settings: SettingsStore, onClose: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by settings.theme.collectAsState()
    val lead by settings.defaultLeadMinutes.collectAsState()
    var showingDev by remember { mutableStateOf(false) }
    BackHandler(onBack = if (showingDev) ({ showingDev = false }) else onClose)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refreshPermissions() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun fix(kind: PermissionKind, status: PermissionStatus) {
        if (status == PermissionStatus.NOT_DETERMINED) scope.launch { model.requestPermission(kind) }
        else context.startActivity(settingsIntent(context, kind))
    }

    if (showingDev) {
        Column(Modifier.fillMaxSize().background(c.bg).padding(18.dp)) {
            BigButton("Close dev harness", { showingDev = false }, style = ButtonStyle.GHOST)
            Box(Modifier.weight(1f)) { DevAlarmScreen(model.permissionRevision) }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(18.dp), Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("SETTINGS", fontFamily = Anton, fontSize = 34.sp, color = c.head)
            Text("Done", fontSize = 17.sp, color = c.head, modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
        }
        Section("Appearance") {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                listOf(ThemeChoice.SYSTEM to "System", ThemeChoice.DARK to "Dark", ThemeChoice.LIGHT to "Light").forEach { (choice, label) ->
                    val selected = theme == choice
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (selected) c.accent else c.surface2)
                            .clickable { settings.setTheme(choice) }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(label, fontSize = 15.sp, color = if (selected) c.onAccent else c.text) }
                }
            }
        }
        Section("Alert time") {
            LeadRuler(SettingsStore.LEAD_CHOICES, lead, { settings.setDefaultLeadMinutes(it) })
            Text("Minutes before arrival. You can still change it for each journey.", fontSize = 13.sp, color = c.muted)
        }
        Section("Permissions") {
            model.settingsPermissionKinds.forEach { kind ->
                val status = model.permissionStatus(kind)
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(permissionTitle(kind), fontSize = 15.sp, color = c.head)
                        Text(permissionReason(kind), fontSize = 13.sp, color = c.muted)
                    }
                    if (status == PermissionStatus.GRANTED) {
                        Pill("On")
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Pill(if (status == PermissionStatus.DENIED) "Off" else "Not asked", PillStyle.BAD)
                            Text("Fix", fontSize = 15.sp, color = c.tealText, modifier = Modifier.clickable { fix(kind, status) }.padding(6.dp))
                        }
                    }
                }
            }
        }
        Section("Test alarm") {
            BigButton("Test alarm in 10 seconds", { scope.launch { model.scheduleTestAlarm() } }, style = ButtonStyle.GHOST, enabled = model.canTestAlarm)
            when (val state = model.testAlarm) {
                TestAlarmState.Idle -> if (!model.canTestAlarm) Text("Not while a journey is running.", fontSize = 13.sp, color = c.muted)
                is TestAlarmState.Scheduled -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Rings at", fontSize = 13.sp, color = c.muted)
                    DotTime(TimeText.clock(state.at), 18.dp, c.head)
                }
                TestAlarmState.Failed -> Text("Could not set the test alarm. Check the permissions above.", fontSize = 13.sp, color = c.bad)
            }
        }
        Section("About") {
            Text("Station data: NaPTAN, Open Government Licence v3.0.", fontSize = 13.sp, color = c.text)
            Text("Train times: Realtime Trains, realtimetrains.co.uk.", fontSize = 13.sp, color = c.text)
            Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 13.sp, color = c.muted)
        }
        if (BuildConfig.DEBUG) {
            Text("Developer alarm harness", fontSize = 13.sp, color = c.muted, modifier = Modifier.clickable { showingDev = true }.padding(6.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = LocalColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title.uppercase(), fontFamily = Anton, fontSize = 20.sp, color = c.head)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).padding(14.dp), Arrangement.spacedBy(10.dp)) { content() }
    }
}

private fun permissionTitle(kind: PermissionKind) = when (kind) {
    PermissionKind.ALARM -> "Alarms"
    PermissionKind.NOTIFICATIONS -> "Notifications"
    PermissionKind.LOCATION -> "Location"
    PermissionKind.FULL_SCREEN_INTENT -> "Full-screen alerts"
}

private fun permissionReason(kind: PermissionKind) = when (kind) {
    PermissionKind.ALARM -> "Needed to wake you."
    PermissionKind.NOTIFICATIONS -> "Needed to show the alarm."
    PermissionKind.LOCATION -> "Sharpens the arrival time. Optional."
    PermissionKind.FULL_SCREEN_INTENT -> "Lets the alarm take over the lock screen. Optional."
}

/** The system page where this permission can be switched on, else the app's details page. */
private fun settingsIntent(context: android.content.Context, kind: PermissionKind): Intent {
    val specific = when (kind) {
        PermissionKind.ALARM -> AlarmPermissions.exactAlarmIntent(context)
        PermissionKind.FULL_SCREEN_INTENT -> AlarmPermissions.fullScreenIntentSettings(context)
        else -> null
    }
    return specific ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}
```

In `RootScreen.kt`, replace the line `            if (showingSettings) Text("Settings", color = c.head, modifier = Modifier.padding(24.dp))   // replaced in Task 15` with:

```kotlin
            if (showingSettings) SettingsScreen(environment.model, environment.settings) { showingSettings = false }
```

If `LocalLifecycleOwner` does not resolve from `androidx.compose.ui.platform` (it moved in newer Compose), use `androidx.lifecycle.compose.LocalLifecycleOwner` and add the `lifecycle-runtime-compose` dependency to the version catalog only if the build says it is missing.

- [ ] **Step 10: Android, run to verify pass and look at the screen**

```bash
cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew test lint assembleDebug assembleRelease --offline 2>&1 | grep -E "^e: |FAILED|BUILD|Lint found" | head -6
```
Expected: `BUILD SUCCESSFUL`. With the emulator running, capture the `settings` scenario in both themes as in Task 12 Step 9 and compare with the iOS screenshots. Without it, record that the Android screen compiled but was not seen.

- [ ] **Step 11: Commit**

```bash
git add ios android
git commit -m "Feature: Add the settings screen with theme, permissions and test alarm

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 16: Screenshots of every screen, both themes, both platforms

**Files:**
- Create: `tools/screenshots/ios.sh`, `tools/screenshots/android.sh`
- Create (output): `docs/ui-screenshots/ios/<scenario>-<dark|light>.png` and `docs/ui-screenshots/android/<scenario>-<dark|light>.png`, 24 per platform

**Interfaces:**
- Consumes: the demo scenarios of Task 10 (`home board confirm tracking tracking-late cancelled dropped feed-lost alarm-not-set ringing ended settings`) and every screen from Tasks 12 to 15.
- Produces: the committed screenshots that spec acceptance item 3 asks for, and two scripts that make them again.
- Screenshots are made on this machine and committed. CI does not make them. They are saved at about half size (longest side 1300 px) to keep the repo small.
- The iOS script pins the simulator status bar (9:41, full battery) so a re-run looks the same. The ringing screen is the same in both themes by design, and both files are still written.
- Android needs the emulator from Task 12 Step 0, which needs the user's approval for a multi-gigabyte download. Without it, only the iOS set is made, and the Android set is recorded as not made.

- [ ] **Step 1: Write the iOS script**

Create `tools/screenshots/ios.sh`:

```bash
#!/bin/bash
# Captures every demo scenario on the iOS simulator, in both themes, into docs/ui-screenshots/ios/.
# Usage: tools/screenshots/ios.sh ["iPhone 17"]
set -euo pipefail
cd "$(dirname "$0")/../.."

DEVICE="${1:-iPhone 17}"
BUNDLE="com.trainalarm.app"
OUT="docs/ui-screenshots/ios"
SCENARIOS="home board confirm tracking tracking-late cancelled dropped feed-lost alarm-not-set ringing ended settings"
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
```

- [ ] **Step 2: Write the Android script**

Create `tools/screenshots/android.sh`:

```bash
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
```

- [ ] **Step 3: Make the scripts executable and run the iOS one**

```bash
chmod +x tools/screenshots/ios.sh tools/screenshots/android.sh
tools/screenshots/ios.sh
ls docs/ui-screenshots/ios | wc -l
```
Expected: `Wrote 24 screenshots to docs/ui-screenshots/ios`, then `24`. If one scenario shows the wrong screen, the 4-second wait was too short for that scenario: raise `sleep` in the script and run again.

- [ ] **Step 4: Look at every iOS screenshot**

Open all 24 PNGs and compare each with `docs/ui-mockup/trainalarm-ui-direction.html` and with the Task 12 to 15 expectations. For each, check: the heading is Anton, times are dot-matrix with two drawn dots, orange and teal appear only as fills (never as text), nothing is cut off or overlaps, the light set is not a dark set, and the ringing screens are identical. Write the result per screen in the ledger. Fix any difference in the code (not the PNG) and run the script again.

- [ ] **Step 5: Android, only if the emulator is running**

```bash
$ANDROID_HOME/emulator/emulator -avd trainalarm_api35 -no-snapshot &
$ANDROID_HOME/platform-tools/adb wait-for-device
tools/screenshots/android.sh
ls docs/ui-screenshots/android | wc -l
```
Expected: `Wrote 24 screenshots to docs/ui-screenshots/android`, then `24`. Open all 24 and run the same checks, plus the Android-only ones: the Doto glyphs are the dotted face (not the system font), `Anton` loads, and the status and navigation bars do not cover content. If the emulator was not approved or could not run, write "Android screenshots not made; Android screens compiled and passed tests but were never seen" in the ledger and do not create the folder.

- [ ] **Step 6: Commit**

```bash
git add tools/screenshots docs/ui-screenshots
git commit -m "Docs: Add screenshots of every screen in both themes

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 17: Docs, the stale manifest comment and the dark window background

**Files:**
- Modify: `README.md`, `.ai/architecture.md`, `.ai/tech-stack.md`, `.ai/testing-guide.md`, `docs/spec.md`
- Modify: `android/app/src/main/AndroidManifest.xml` (comment), `android/app/src/main/res/values/themes.xml`, `android/app/src/main/java/com/trainalarm/app/AppEnvironment.kt` (log the alarm-error callback)
- Create: `android/app/src/main/res/values-night/themes.xml`

**Interfaces:**
- Consumes: everything built in Tasks 1 to 16, and the ledger (what was seen, what was not).
- `MainActivity` is locked to portrait: a permission prompt can be open when the phone is rotated, and a recreated Activity would lose the answer and leave `start()` waiting forever. The alarm screen is not locked.
- Produces: docs that match the code. The edits below are exact: each "Replace" text occurs once in its file, so use Edit with it verbatim. `CHANGELOG.md` does not exist in this repo, so there is nothing to add.
- **Do not touch the Stage 3 status line in `docs/spec.md` §8** (iOS verified, Android device test pending, not done). Stage 4 does not change it.
- This task is a docs task with one real behaviour change: `AppEnvironment` now passes `onAlarmError` to the model, so a failed alarm schedule is logged. That makes the sentence in `tech-stack.md` true.

- [ ] **Step 1: Apply the doc and comment edits**

**1. `README.md`: Repo layout: the new docs folders.** Replace:

```text
- `docs/` — spec, architecture notes, per-stage plans.
```

with:

```text
- `docs/` — spec, architecture notes, per-stage plans, the UI mockup (`ui-mockup/`), screenshots
  (`ui-screenshots/`) and `ux-reference/` (other designers' inspiration images, kept for private
  reference: remove them before the repo is made public).
```

**2. `README.md`: Status and how to run it.** Replace:

```text
Stages 1-2 done (journey model, UK provider, station data; tracking, ETA and
live-change logic, with no UI yet). Stage 3 (alarm delivery spike, real device
only): iOS verified on a real iPhone, Android built and unit-tested, Android
device test pending, so not yet done. See `docs/spec.md` §8 for the full stage list.
```

with:

```text
Stages 1-2 done (journey model, UK provider, station data; tracking, ETA and
live-change logic). Stage 3 (alarm delivery spike, real device only): iOS
verified on a real iPhone, Android built and unit-tested, Android device test
pending, so not yet done. Stage 4 (UI build): both platforms have the core
screens (home, confirm, tracking, ringing, ended, settings), a debug demo mode
that needs no RTT token, and screenshots in `docs/ui-screenshots/`. It has not
been run against live RTT data (no token yet), and the map is Stage 4b. See
`docs/spec.md` §8 for the full stage list.

## Running the app

- **Demo (debug builds, no token needed):** a debug build without an RTT token opens in
  demo mode with scripted trains from King's Cross to Edinburgh. iOS also takes
  `-scenario <name>` and Android the `scenario` intent extra, to open one screen directly
  (`home`, `board`, `confirm`, `tracking`, `tracking-late`, `cancelled`, `dropped`,
  `feed-lost`, `alarm-not-set`, `ringing`, `ended`, `settings`).
- **With an RTT token (your own devices only):** iOS: copy `ios/Config/Secrets.xcconfig.example`
  to `ios/Config/Secrets.xcconfig` and paste the token. Android: add
  `rtt.accessToken=<token>` to `android/local.properties`. Both files are gitignored. Only debug
  builds read the token (release builds never contain one), because RTT's terms forbid shipping a
  token inside a distributed app; a backend proxy is needed first (`docs/spec.md` §7).
- **Faster demo:** add `-demo-speed 60` (iOS launch argument) or `--ei demoSpeed 60` (Android intent
  extra) to run the demo clock 60 times faster, so the alarm and the arrival happen within minutes.
- **Screenshots:** `tools/screenshots/ios.sh` and `tools/screenshots/android.sh` regenerate
  `docs/ui-screenshots/` (the Android one needs a running emulator).
```

**3. `README.md`: Attribution is now shown.** Replace (the text ends mid-line, with no line break):

```text
the attribution must be
shown in the app UI (not yet done).
```

with:

```text
the attribution is
shown on the Settings screen.
```

**4. `.ai/architecture.md`: The UI layer row.** Replace:

```text
| UI / app entry | `App/` | package root | 4 |
```

with:

```text
| UI: screens, `SessionModel`, theme, composition root (`AppEnvironment`) | `App/`, `App/UI/`, `App/Theme/` | package root, `ui/`, `theme/` | 4 (done) |
```

**5. `.ai/architecture.md`: State management and DI decisions.** Replace:

```text
- **Spec-first stages.** Each stage in spec §8 has a written plan before code;
  its "ships when" line is the acceptance criterion.
```

with:

```text
- **Spec-first stages.** Each stage in spec §8 has a written plan before code;
  its "ships when" line is the acceptance criterion.
- **State management.** One `SessionModel` per platform (iOS `@MainActor @Observable`;
  Android Compose `mutableStateOf` with private setters). It owns the one `JourneyTracker`,
  collects its events once and exposes display state; it holds no tracking logic. Screens
  read it and call its methods. Phases: selecting, confirming, tracking, ringing, ended.
- **Dependency injection.** Constructor injection and one composition root per platform
  (`AppEnvironment`), which builds the real app or, in debug builds only, the demo app.
  No DI framework.
- **Station search** runs on `StationDirectory` through `DirectoryBackedProvider`, which
  also gives RTT's stations their real coordinates. `RttProvider.searchStations` stays unwired.
```

**6. `.ai/architecture.md`: Close the two TBDs.** Replace:

```text
- State management / UI architecture pattern (MVVM etc.) — decide at Stage 4.
- Dependency injection approach.
```

with:

nothing (delete it).

**7. `.ai/tech-stack.md`: iOS UI rows.** Replace:

```text
| Concurrency | `async`/`await` |
```

with:

```text
| Concurrency | `async`/`await` |
| UI state | `@Observable` `SessionModel`; settings in `UserDefaults` |
| Fonts | Anton and Doto (SIL OFL 1.1), bundled in `Sources/TrainAlarm/Resources/Fonts`; the Doto runtime name is `Doto-Black_Bold` |
| Secrets | Optional gitignored `ios/Config/Secrets.xcconfig`, applied to the Debug configuration only and read through Info.plist (`RTTAccessToken`) |
```

**8. `.ai/tech-stack.md`: Android UI rows.** Replace:

```text
| Concurrency | Kotlin coroutines 1.9.0 |
```

with:

```text
| Concurrency | Kotlin coroutines 1.9.0 |
| UI state | Compose state in `SessionModel`; settings in `SharedPreferences` |
| Fonts | Anton and Doto (variable font at weight 700, SIL OFL 1.1) in `res/font` |
| Secrets | Optional `rtt.accessToken` in gitignored `local.properties`, as `BuildConfig.RTT_ACCESS_TOKEN` in the `debug` build type only (`release` is empty) |
```

**9. `.ai/tech-stack.md`: External services: the token rule.** Replace (the text ends mid-line, with no line break):

```text
- CI: GitHub Actions
```

with:

```text
- **RTT token handling.** The token is build-time config for the developer's own devices only, and only debug builds read it. RTT's
  terms forbid shipping it inside a distributed app, so a backend proxy is needed before any beta.
  Demo mode (debug builds) needs no token. Demo code is not in release builds.
- CI: GitHub Actions
```

**10. `.ai/tech-stack.md`: Logging: the callback is now wired.** Replace (the text ends mid-line, with no line break):

```text
callback; Stage 4 wires it to `Log`.
```

with:

```text
callback; the Stage 4 composition root wires it to `Log.e`.
```

**11. `.ai/tech-stack.md`: Secrets TBD becomes the proxy decision.** Replace:

```text
- Secrets handling for the RTT token (Keychain / EncryptedSharedPreferences?).
```

with:

```text
- A backend proxy that holds the RTT token, before any beta.
```

**12. `.ai/testing-guide.md`: UI is checked by screenshots.** Replace:

```text
- In a fresh git worktree `android/local.properties` is absent (it is
```

with:

```text
- **UI is checked by unit tests on the model plus screenshots.** `SessionModel` is tested with the same
  hand-written fakes as the tracker. `tools/screenshots/ios.sh` and `tools/screenshots/android.sh` open every
  demo scenario in both themes and save the PNGs to `docs/ui-screenshots/`; look at them against
  `docs/ui-mockup/`. The Android script needs a running emulator. A screenshot is never a pass for alarm delivery.
- **Demo mode** (debug builds, no RTT token) runs the whole flow on scripted trains; scenario tests in
  `android/app/src/testDebug/.../demo/ScenarioTest.kt` and `ios/Tests/TrainAlarmTests/App/ScenarioTests.swift` pin each named scenario.
- In a fresh git worktree `android/local.properties` is absent (it is
```

**13. `.ai/testing-guide.md`: UI tests: decision recorded.** Replace:

```text
- Instrumented/UI tests (`androidTest`, XCUITest) — none yet; decide at Stage 4.
```

with:

```text
- Instrumented/UI tests (`androidTest`, XCUITest) — none: Stage 4 checks the UI with model unit tests and
  screenshots. Revisit if UI regressions start to bite.
```

**14. `docs/spec.md`: §3: search wiring.** Replace (the text ends mid-line, with no line break):

```text
`StationDirectory` on both platforms. Still to do: wiring
  `RttProvider.searchStations` to it (it deliberately throws for now
  rather than guessing at an endpoint that doesn't exist).
```

with:

```text
`StationDirectory` on both platforms. The app's station picker
  searches `StationDirectory` directly (Stage 4); `RttProvider.searchStations`
  still deliberately throws rather than guess at an endpoint that doesn't exist.
```

**15. `docs/spec.md`: §7: the token decision.** Replace:

```text
- Monetisation model, if any.
```

with:

```text
- Monetisation model, if any.
- RTT token distribution: RTT's terms forbid a token inside a distributed app, so builds with a token are
  for the developer's own devices only. A small backend proxy that holds the token is needed before any beta.
```

**16. `docs/spec.md`: §8 Stage 1: search and attribution.** Replace (the text ends mid-line, with no line break):

```text
Not yet
   done: wiring `RttProvider.searchStations` to the directory, and showing
   the OGL attribution in the app UI (UI stage). Known gaps:
```

with:

```text
The picker
   searches the directory (Stage 4), Settings shows the OGL attribution, and
   `RttProvider.searchStations` stays unwired. Known gaps:
```

**17. `docs/spec.md`: §8 Stage 2: plumbing arrived.** Replace (the text ends mid-line, with no line break):

```text
Not done: the real
   clock and GPS plumbing and the app wiring (Stage 4), and background execution
   (Stage 5).
```

with:

```text
The real clock, GPS plumbing and app wiring arrived in Stage 4. Not done: background execution
   (Stage 5).
```

**18. `docs/spec.md`: §8 Stage 4 status and the Stage 4b entry.** Replace:

```text
4. UI build — once UX reference screenshots are in.
```

with:

```text
4. UI build — built; checked in demo mode and on the iOS simulator; not run against live RTT data. Core screens on both
   platforms (home with nearest station, confirm, tracking with a route strip, ringing, ended, settings) in a
   dark and a light theme (orange `#F36509`, near-black `#201D1C`, teal `#015D67`; Anton and Doto fonts), a
   debug demo mode needing no token, and screenshots in `docs/ui-screenshots/`. Not done: a live check against
   RTT (no token yet), the iOS foreground ringing signal on a device, and background tracking (Stage 5).
4b. Map screen — own spec: the route map from the UX reference, with its attribution, once a map
   library is chosen.
```

**19. `android/app/src/main/AndroidManifest.xml`: Lock the main screen to portrait.** Replace:

```text
            android:name=".MainActivity"
            android:exported="true"
```

with:

```text
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait"
```

**20. `android/app/src/main/AndroidManifest.xml`: Stale comment.** Replace (the text ends mid-line, with no line break):

```text
the location permissions are for Stage 5.
```

with:

```text
location is used while the app is open (Stage 4); background location is for Stage 5.
```

**21. `android/app/src/main/java/com/trainalarm/app/AppEnvironment.kt`: Wire the alarm-error callback to the log.** Replace:

```text
                settings = settings, scope = scope, locationControl = location.asControl()
```

with:

```text
                settings = settings, scope = scope, locationControl = location.asControl(),
                onAlarmError = { message, error -> Log.e("TrainAlarm", message, error) }
```

**22. `android/app/src/main/java/com/trainalarm/app/AppEnvironment.kt`: Its import.** Replace:

```text
import android.content.Intent
```

with:

```text
import android.content.Intent
import android.util.Log
```

- [ ] **Step 2: The Android window background (no light flash in the dark theme)**

Today `themes.xml` is a light Material theme, so a dark-mode phone flashes a light window before Compose draws. Replace the whole of `android/app/src/main/res/values/themes.xml` with:

```xml
<resources>
    <style name="Theme.TrainAlarm" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:windowBackground">#CFC3B6</item>
    </style>
</resources>
```

Create `android/app/src/main/res/values-night/themes.xml`:

```xml
<resources>
    <style name="Theme.TrainAlarm" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">#201D1C</item>
    </style>
</resources>
```

The in-app Dark choice on a light-mode phone can still flash light for a moment; that is accepted, because the theme setting is read after the window opens.

- [ ] **Step 3: Add the Android status of Stage 4 to the spec**

In `docs/spec.md` §8 item 4, after the sentence that ends "and screenshots in docs/ui-screenshots/." add one sentence, whichever is true from the ledger: `The Android screens were seen on an emulator.` or `The Android screens compiled, passed tests and lint, but were never seen (no emulator).`

- [ ] **Step 4: Check the claims against the repo**

```bash
ls ios/Sources/TrainAlarm/Resources/Fonts android/app/src/main/res/font
ls ios/Tests/TrainAlarmTests/App/ScenarioTests.swift android/app/src/testDebug/java/com/trainalarm/app/demo/ScenarioTest.kt
grep -rn "stations.json" README.md | head -2
```
Expected: the font folder and the scenario test folders named in the docs exist. If a path in the text above is wrong, fix the doc text to the real path.

- [ ] **Step 5: Full verification, one platform at a time**

```bash
cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" 2>&1 | grep -E "error:|TEST (SUCCEEDED|FAILED)" | tail -2
cd ../android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew test lint assembleDebug assembleRelease --offline 2>&1 | grep -E "^e: |FAILED|BUILD|Lint found" | head -6
cd .. && python3 -m unittest discover -s tools/stations -p "test_*.py" 2>&1 | tail -3
```
Expected: `** TEST SUCCEEDED **`, `BUILD SUCCESSFUL`, and the station tool tests `OK`. Do not run the two builds at the same time: it slows the machine and once broke Gradle's cache.

- [ ] **Step 6: Commit**

```bash
git add README.md .ai docs/spec.md android
git commit -m "Docs: Update the docs for Stage 4 and fix the manifest comment and window background

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```
