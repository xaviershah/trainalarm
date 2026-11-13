# One-Minute Lead Time Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user pick any whole minute from 1 to 30 as the lead time, on the Confirm sheet and in Settings, on iOS and Android.

**Architecture:** One list per platform (`SettingsStore.leadChoices` / `LEAD_CHOICES`) defines the choices, so widening it to 1...30 flows through the model and both screens. The ruler view is reworked around a small pure layout type (`RulerLayout`) so ticks, marker, labels and tap/drag all use the same positions, plus a value line ("7 minutes before arrival") and a singular-aware `minutesText`. Demo scenarios and the screenshot script get small changes so the four ruler screenshots can be retaken.

**Tech Stack:** Swift 5 mode, SwiftUI, XCTest (iOS 26, XcodeGen). Kotlin, Jetpack Compose, JUnit 4. No new packages.

**Spec:** `docs/superpowers/specs/2026-11-13-one-minute-lead-time-design.md` (read it first).

## Plan in plain English

| # | Task | Files touched | What it proves |
|---|---|---|---|
| 1 | Widen the choices to 1 to 30, harden the Android stored-value read | `SettingsStore` (both), their tests, step tests, model tests, new `SharedPrefsKeyValuesTest` | Every minute 1-30 accepted, nothing else; old values still read; a wrong-typed Android value reads as unset |
| 2 | Tracker tests at a 60 s lead | `JourneyMonitorTests`, `JourneyMonitorTest` | A 1-minute lead schedules, clamps and reschedules correctly (tests only, no production change) |
| 3 | Pure helpers: `minutesText`, `leadValueLine`, `RulerLayout`, `leadForProgress` | `Components.swift`, `Components.kt`, their tests | Singular wording; even tick spacing; every choice found from its own position; major ticks at multiples of 5 |
| 4 | Rework `LeadRuler` and the dial's accessibility text | `Components.swift`, `Components.kt` | Ruler builds and all tests pass; ticks centred on the marker positions; value line shown |
| 5 | Demo scenarios, screenshot filter, retake four iOS screenshots | `Scenario.swift/.kt`, `AppEnvironment.swift` (call site), scenario tests, `ios.sh`, `android.sh`, 4 PNGs | The iOS ruler seen in both themes, on Confirm and Settings |
| 6 | Docs | `docs/spec.md`, `.ai/testing-guide.md` | Docs match what was built and what was seen |

## Global Constraints

- Native only: SwiftUI and Jetpack Compose. No new third-party packages. No shared cross-platform layer.
- Both platforms get every feature, in the matching layer.
- Choices are every whole minute from 1 to 30. No more than 30. First run (no stored lead time) stays empty and Start stays disabled until one is chosen.
- A stored value outside 1 to 30 reads as unset; setters ignore values outside 1 to 30; old stored multiples of 5 stay valid.
- Orange `#F36509` and teal `#015D67` are fills only. Never use them as small text. Anton for headlines and buttons, Doto for time readouts, system font for body. Draw the time colon as two dots, never the Doto glyph.
- Times display in `Europe/London`.
- Never request background location.
- Do not invent RTT response shapes. Do not touch `RttProvider`.
- A simulator or emulator result is not a pass for alarm delivery. Do not change the Stage 3 status line in `docs/spec.md`.
- The Android emulator was skipped by the user and there is no Android phone: Android screens are compiled, lint-checked and unit-tested only. Never write that they were seen. Never claim the new ruler was checked on a device until the user has done it.
- Commit messages follow the global format and end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Sonnet for implementers and reviewers; never Opus unless the user says so.
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
- Run the iOS and Android builds one at a time (the laptop struggles). Stop Gradle (`cd android && ./gradlew --stop`) and the simulator (`xcrun simctl shutdown all`) when idle. Android builds need `ANDROID_HOME=$HOME/Library/Android/sdk` (a worktree has no `local.properties`). If cloud sync leaves duplicate `* 2.*` files in `android/app/build`, delete them there only; a stray `ios/TrainAlarm 2.xcodeproj` can be deleted too.
- Commands: iOS tests `cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17"` (pick another name from `xcrun simctl list devices available iPhone` if absent); Android `cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew test lint assembleDebug assembleRelease --offline`.
- Baseline before this plan (main at c6c1802): iOS 277 tests, Android debug 273 / release 260 unit tests, 0 failures.

## Review Focus

Inputs and conditions the spec implies that no single task's happy path exercises, most likely first:

1. First launch, nothing chosen: the ruler shows "Not chosen", no marker, Start stays disabled; the first accessibility step from nothing gives 2 up and 1 down (Tasks 1, 3).
2. An old stored value (a multiple of 5) still reads back; a stored 0, 31 or 99 reads as unset; on Android a wrong-typed stored value reads as unset instead of crashing at launch (Task 1).
3. A tap or drag beyond either end of the ruler selects 1 or 30, and a tap on a tick centre selects exactly that minute at widths 320, 375 and 402 (Task 3).
4. A 1-minute lead near arrival: starting inside the last minute fires now, and a 1-minute ETA slip reschedules (Task 2).
5. "1 minute" singular in the value line, accessibility value and the dial's description (Tasks 3, 4).

---

### Task 1: Widen the choices to 1 to 30, harden the Android stored-value read

**Files:**
- Modify: `ios/Sources/TrainAlarm/App/SettingsStore.swift`, `android/app/src/main/java/com/trainalarm/app/SettingsStore.kt`
- Modify (tests): `ios/Tests/TrainAlarmTests/App/SettingsStoreTests.swift`, `android/app/src/test/java/com/trainalarm/app/SettingsStoreTest.kt`, `ios/Tests/TrainAlarmTests/App/ComponentsTests.swift`, `android/app/src/test/java/com/trainalarm/app/ui/ComponentsTest.kt`, `ios/Tests/TrainAlarmTests/App/SessionModelTests.swift`, `android/app/src/test/java/com/trainalarm/app/SessionModelTest.kt`
- Create (test): `android/app/src/test/java/com/trainalarm/app/SharedPrefsKeyValuesTest.kt`

**Interfaces:**
- Produces: `SettingsStore.leadChoices == Array(1...30)` (iOS) and `SettingsStore.LEAD_CHOICES == (1..30).toList()` (Android). `SessionModel.setLead` and `SettingsStore.setDefaultLeadMinutes` already guard with these lists, so they now accept 1...30 and ignore everything else. `SharedPrefsKeyValues.getInt` returns null for a wrong-typed value.
- Four existing tests assumed 5-minute steps and are rewritten here: the two step tests (`ComponentsTests.swift` `testLeadStepMovesOneChoiceAndClampsAtTheEnds`, `ComponentsTest.kt` `leadStepMovesOneChoiceAndClampsAtTheEnds`) and the two "outside the choices" store tests (`testALeadTimeOutsideTheChoicesIsIgnored`, `aLeadTimeOutsideTheChoices_isIgnored`).

- [ ] **Step 1: Write the failing tests (iOS)**

In `ios/Tests/TrainAlarmTests/App/SettingsStoreTests.swift`, replace `testALeadTimeOutsideTheChoicesIsIgnored` with these six tests (keep every other test in the file):

```swift
    func testTheChoicesAreEveryMinuteFromOneToThirty() {
        XCTAssertEqual(SettingsStore.leadChoices, Array(1...30))
    }

    func testOnlyLeadTimesFromOneToThirtyAreAccepted() {
        let store = SettingsStore(defaults: defaults)
        store.setDefaultLeadMinutes(0)
        XCTAssertNil(store.defaultLeadMinutes)
        store.setDefaultLeadMinutes(31)
        XCTAssertNil(store.defaultLeadMinutes)
        store.setDefaultLeadMinutes(1)
        XCTAssertEqual(store.defaultLeadMinutes, 1)
        store.setDefaultLeadMinutes(30)
        XCTAssertEqual(store.defaultLeadMinutes, 30)
        store.setDefaultLeadMinutes(17)
        XCTAssertEqual(store.defaultLeadMinutes, 17)
    }

    func testAnOldStoredFiveMinuteStepStillReads() {
        defaults.set(15, forKey: "defaultLeadMinutes")
        XCTAssertEqual(SettingsStore(defaults: defaults).defaultLeadMinutes, 15)
    }

    func testAStoredSeventeenNowReadsBackAsSeventeen() {
        defaults.set(17, forKey: "defaultLeadMinutes")
        XCTAssertEqual(SettingsStore(defaults: defaults).defaultLeadMinutes, 17)
    }

    func testAStoredZeroOrThirtyOneReadsAsUnset() {
        for stored in [0, 31] {
            defaults.set(stored, forKey: "defaultLeadMinutes")
            XCTAssertNil(SettingsStore(defaults: defaults).defaultLeadMinutes, "stored \(stored)")
        }
    }
```

In `ios/Tests/TrainAlarmTests/App/ComponentsTests.swift`, replace `testLeadStepMovesOneChoiceAndClampsAtTheEnds` with:

```swift
    func testLeadStepMovesOneMinuteAndClampsAtTheEnds() {
        let c = SettingsStore.leadChoices
        XCTAssertEqual(LeadRuler.step(c, from: 15, up: true), 16)
        XCTAssertEqual(LeadRuler.step(c, from: 15, up: false), 14)
        XCTAssertEqual(LeadRuler.step(c, from: 30, up: true), 30)
        XCTAssertEqual(LeadRuler.step(c, from: 1, up: false), 1)
        XCTAssertEqual(LeadRuler.step(c, from: nil, up: true), 2)
        XCTAssertEqual(LeadRuler.step(c, from: nil, up: false), 1)
    }
```

In `ios/Tests/TrainAlarmTests/App/SessionModelTests.swift`, add these two tests directly after `testFirstRunHasNoLeadTimeAndCannotStartUntilOneIsChosen` (the happy-path arrival in this harness is 08:52:00, which is why a 15-minute lead gives 08:37:00):

```swift
    func testEveryMinuteFromOneToThirtyIsAcceptedAndMovesTheAlarmTime() async throws {
        let h = try makeHarness()
        await confirmed(h, lead: nil)
        h.model.setLead(1)
        XCTAssertEqual(h.model.alarmAt, ServiceBuilder.time("08:51:00"))
        h.model.setLead(7)
        XCTAssertEqual(h.model.alarmAt, ServiceBuilder.time("08:45:00"))
        h.model.setLead(30)
        XCTAssertEqual(h.model.alarmAt, ServiceBuilder.time("08:22:00"))
    }

    func testALeadTimeOutsideOneToThirtyIsIgnored() async throws {
        let h = try makeHarness()
        await confirmed(h, lead: 10)
        h.model.setLead(0)
        XCTAssertEqual(h.model.confirm?.leadMinutes, 10)
        h.model.setLead(31)
        XCTAssertEqual(h.model.confirm?.leadMinutes, 10)
    }
```

- [ ] **Step 2: Write the failing tests (Android)**

In `android/app/src/test/java/com/trainalarm/app/SettingsStoreTest.kt`, replace `aLeadTimeOutsideTheChoices_isIgnored` with (keep the rest):

```kotlin
    @Test fun theChoices_areEveryMinuteFromOneToThirty() {
        assertEquals((1..30).toList(), SettingsStore.LEAD_CHOICES)
    }

    @Test fun onlyLeadTimesFromOneToThirty_areAccepted() {
        val store = SettingsStore(MemoryKeyValues())
        store.setDefaultLeadMinutes(0)
        assertNull(store.defaultLeadMinutes.value)
        store.setDefaultLeadMinutes(31)
        assertNull(store.defaultLeadMinutes.value)
        store.setDefaultLeadMinutes(1)
        assertEquals(1, store.defaultLeadMinutes.value)
        store.setDefaultLeadMinutes(30)
        assertEquals(30, store.defaultLeadMinutes.value)
        store.setDefaultLeadMinutes(17)
        assertEquals(17, store.defaultLeadMinutes.value)
    }

    @Test fun anOldStoredFiveMinuteStep_stillReads() {
        val kv = MemoryKeyValues().apply { putInt("defaultLeadMinutes", 15) }
        assertEquals(15, SettingsStore(kv).defaultLeadMinutes.value)
    }

    @Test fun aStoredSeventeen_nowReadsBackAsSeventeen() {
        val kv = MemoryKeyValues().apply { putInt("defaultLeadMinutes", 17) }
        assertEquals(17, SettingsStore(kv).defaultLeadMinutes.value)
    }

    @Test fun aStoredZeroOrThirtyOne_readsAsUnset() {
        for (stored in listOf(0, 31)) {
            val kv = MemoryKeyValues().apply { putInt("defaultLeadMinutes", stored) }
            assertNull("stored $stored", SettingsStore(kv).defaultLeadMinutes.value)
        }
    }
```

In `android/app/src/test/java/com/trainalarm/app/ui/ComponentsTest.kt`, replace `leadStepMovesOneChoiceAndClampsAtTheEnds` with:

```kotlin
    @Test
    fun leadStepMovesOneMinuteAndClampsAtTheEnds() {
        val c = SettingsStore.LEAD_CHOICES
        assertEquals(16, stepLead(c, 15, up = true))
        assertEquals(14, stepLead(c, 15, up = false))
        assertEquals(30, stepLead(c, 30, up = true))
        assertEquals(1, stepLead(c, 1, up = false))
        assertEquals(2, stepLead(c, null, up = true))
        assertEquals(1, stepLead(c, null, up = false))
    }
```

In `android/app/src/test/java/com/trainalarm/app/SessionModelTest.kt`, add these two tests directly after `firstRunHasNoLeadTimeAndCannotStartUntilOneIsChosen` (same harness style, same 08:52:00 arrival):

```kotlin
    @Test fun everyMinuteFromOneToThirtyIsAcceptedAndMovesTheAlarmTime() = run {
        confirmed(lead = null)
        model.setLead(1)
        assertEquals(ServiceBuilder.time("08:51:00"), model.alarmAt)
        model.setLead(7)
        assertEquals(ServiceBuilder.time("08:45:00"), model.alarmAt)
        model.setLead(30)
        assertEquals(ServiceBuilder.time("08:22:00"), model.alarmAt)
    }

    @Test fun aLeadTimeOutsideOneToThirtyIsIgnored() = run {
        confirmed(lead = 10)
        model.setLead(0)
        assertEquals(10, model.confirm?.leadMinutes)
        model.setLead(31)
        assertEquals(10, model.confirm?.leadMinutes)
    }
```

Create `android/app/src/test/java/com/trainalarm/app/SharedPrefsKeyValuesTest.kt`:

```kotlin
package com.trainalarm.app

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedPrefsKeyValuesTest {
    /** Only the reads the store uses; a wrong-typed value throws ClassCastException, like the real thing. */
    private class FakePrefs(private val map: MutableMap<String, Any>) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = when (val v = map[key]) {
            null -> defValue
            is String -> v
            else -> throw ClassCastException()
        }
        override fun getInt(key: String?, defValue: Int): Int = when (val v = map[key]) {
            null -> defValue
            is Int -> v
            else -> throw ClassCastException()
        }
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = throw UnsupportedOperationException()
        override fun getLong(key: String?, defValue: Long): Long = throw UnsupportedOperationException()
        override fun getFloat(key: String?, defValue: Float): Float = throw UnsupportedOperationException()
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = throw UnsupportedOperationException()
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test fun aStoredInt_isRead() {
        val kv = SharedPrefsKeyValues(FakePrefs(mutableMapOf("defaultLeadMinutes" to 12)))
        assertEquals(12, kv.getInt("defaultLeadMinutes"))
    }

    @Test fun aMissingKey_readsAsNull() {
        assertNull(SharedPrefsKeyValues(FakePrefs(mutableMapOf())).getInt("defaultLeadMinutes"))
    }

    @Test fun aWrongTypedValue_readsAsNullInsteadOfThrowing() {
        val kv = SharedPrefsKeyValues(FakePrefs(mutableMapOf("defaultLeadMinutes" to "oops")))
        assertNull(kv.getInt("defaultLeadMinutes"))
    }

    @Test fun aWrongTypedLeadTime_leavesTheSettingsStoreUnsetAtLaunch() {
        val store = SettingsStore(SharedPrefsKeyValues(FakePrefs(mutableMapOf("defaultLeadMinutes" to "oops"))))
        assertNull(store.defaultLeadMinutes.value)
    }
}
```

- [ ] **Step 3: Run the new tests to see them fail (RED), one platform at a time**

iOS: `cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/SettingsStoreTests -only-testing:TrainAlarmTests/ComponentsTests -only-testing:TrainAlarmTests/SessionModelTests 2>&1 | grep -E "error:|failed|Executed|TEST (SUCCEEDED|FAILED)" | tail -15`
Expected: failures, for example `testTheChoicesAreEveryMinuteFromOneToThirty`, `testOnlyLeadTimesFromOneToThirtyAreAccepted`, `testEveryMinuteFromOneToThirtyIsAcceptedAndMovesTheAlarmTime` (the choices are still 5-minute steps).

Android: `cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests 'com.trainalarm.app.SettingsStoreTest' --tests 'com.trainalarm.app.SharedPrefsKeyValuesTest' --tests 'com.trainalarm.app.ui.ComponentsTest' --tests 'com.trainalarm.app.SessionModelTest' --offline 2>&1 | tail -25`
Expected: failures in the same tests, and `aWrongTypedValue_readsAsNullInsteadOfThrowing` fails with `ClassCastException`.

- [ ] **Step 4: Implement**

`ios/Sources/TrainAlarm/App/SettingsStore.swift`: replace `static let leadChoices = [5, 10, 15, 20, 25, 30]` with:

```swift
    /// Every whole minute from 1 to 30.
    static let leadChoices = Array(1...30)
```

`android/app/src/main/java/com/trainalarm/app/SettingsStore.kt`: replace `val LEAD_CHOICES = listOf(5, 10, 15, 20, 25, 30)` with:

```kotlin
        /** Every whole minute from 1 to 30. */
        val LEAD_CHOICES = (1..30).toList()
```

and replace `SharedPrefsKeyValues.getInt` with:

```kotlin
    override fun getInt(key: String): Int? = if (prefs.contains(key)) {
        try { prefs.getInt(key, 0) } catch (e: ClassCastException) { null }  // a wrong-typed value reads as unset
    } else null
```

- [ ] **Step 5: Run the tests to see them pass (GREEN), then the full suites, one platform at a time**

Re-run both focused commands from Step 3 (expect 0 failures). Then the full iOS suite and the full Android command from Global Constraints. Expected: iOS about 277 + 6 new - 1 replaced tests (about 282), 0 failures; Android `BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add ios android
git commit -m "Feature: Let the lead time be any minute from 1 to 30

The lead-time choices were fixed at multiples of 5. Both platforms now offer
every whole minute from 1 to 30, with old stored values still valid. The
Android stored-value read also survives a wrong-typed value.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Tracker tests at a 60 s lead

**Files:**
- Modify (tests only): `ios/Tests/TrainAlarmTests/Tracking/JourneyMonitorTests.swift`, `android/app/src/test/java/com/trainalarm/app/tracking/JourneyMonitorTest.kt`

**Interfaces:**
- Consumes: `MonitorHarness(leadTime:start:)` (iOS `leadTime: TimeInterval`, Android `leadTime: Double`), `happy()`/`t()` helpers already in both test files, `h.state.alarmDue`, `h.scheduled`. The happy-path service's destination ETA is 08:52:00 (`ServiceBuilder.happyPath`).
- Produces: nothing new. These tests pass against the existing production code; they pin behaviour that the ruler change makes reachable (a 1-minute lead). No production code changes in this task.

- [ ] **Step 1: Add the iOS tests**

In `JourneyMonitorTests.swift`, directly after `testASlipAfterTheScheduledAlarmTimeHasPassedDoesNotRearmIt` (end of the "Scenarios 21-22 and the clamp" group), add:

```swift
    // MARK: A one-minute lead time (the ruler allows 1 to 30 minutes)

    func testAOneMinuteLeadSchedulesOneMinuteBeforeTheEta() {
        let h = MonitorHarness(leadTime: 60)
        XCTAssertEqual(h.start(happy()), [.etaChanged(oldEta: nil, newEta: t("08:52:00"), fireAt: t("08:51:00"))])
        XCTAssertEqual(h.scheduled, [t("08:51:00")])
        XCTAssertFalse(h.state.alarmDue)
    }

    func testAOneMinuteLeadStartedInsideTheLastMinuteSchedulesNow() {
        let h = MonitorHarness(leadTime: 60, start: t("08:51:30"))  // the fire time, 08:51:00, is already past
        XCTAssertEqual(h.start(happy()), [.etaChanged(oldEta: nil, newEta: t("08:52:00"), fireAt: t("08:51:30"))])
        XCTAssertEqual(h.scheduled, [t("08:51:30")])
        XCTAssertTrue(h.state.alarmDue)
    }

    func testAOneMinuteEtaSlipReschedulesAOneMinuteLead() {
        let h = MonitorHarness(leadTime: 60)
        h.start(happy())
        XCTAssertEqual(h.poll(happy("08:53:00")),
                       [.etaChanged(oldEta: t("08:52:00"), newEta: t("08:53:00"), fireAt: t("08:52:00"))])
        XCTAssertEqual(h.scheduled, [t("08:51:00"), t("08:52:00")])
    }
```

- [ ] **Step 2: Add the Android tests**

In `JourneyMonitorTest.kt`, directly after `aSlipAfterTheScheduledAlarmTimeHasPassedDoesNotRearmIt`, add:

```kotlin
    // A one-minute lead time (the ruler allows 1 to 30 minutes)

    @Test
    fun aOneMinuteLeadSchedulesOneMinuteBeforeTheEta() {
        val h = MonitorHarness(leadTime = 60.0)
        assertEquals(listOf(etaChanged(null, "08:52:00", "08:51:00")), h.start(happy()))
        assertEquals(listOf(t("08:51:00")), h.scheduled)
        assertTrue(!h.state.alarmDue)
    }

    @Test
    fun aOneMinuteLeadStartedInsideTheLastMinuteSchedulesNow() {
        val h = MonitorHarness(leadTime = 60.0, start = t("08:51:30"))  // the fire time, 08:51:00, is already past
        assertEquals(listOf(etaChanged(null, "08:52:00", "08:51:30")), h.start(happy()))
        assertEquals(listOf(t("08:51:30")), h.scheduled)
        assertTrue(h.state.alarmDue)
    }

    @Test
    fun aOneMinuteEtaSlipReschedulesAOneMinuteLead() {
        val h = MonitorHarness(leadTime = 60.0)
        h.start(happy())
        assertEquals(listOf(etaChanged("08:52:00", "08:53:00", "08:52:00")), h.poll(happy("08:53:00")))
        assertEquals(listOf(t("08:51:00"), t("08:52:00")), h.scheduled)
    }
```

- [ ] **Step 3: Run them (one platform at a time) and read the result**

iOS: `cd ios && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/JourneyMonitorTests 2>&1 | grep -E "error:|failed|Executed|TEST (SUCCEEDED|FAILED)" | tail -8`
Android: `cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests 'com.trainalarm.app.tracking.JourneyMonitorTest' --offline 2>&1 | tail -8`
Expected: all pass first time (the production code already handles a 60 s lead). If one fails, do NOT change production code: stop and report which assertion differs, with the actual values, so the controller can rule on whether the spec's assumption or the code is wrong.

- [ ] **Step 4: Commit**

```bash
git add ios/Tests android/app/src/test
git commit -m "Test: Pin the tracker's behaviour at a one-minute lead time

The lead-time ruler now allows 1 minute, and the tracker tests only used a
10-minute lead. These cover the schedule time, the past-fire clamp inside the
last minute and a 1-minute ETA slip.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Pure helpers: `minutesText`, `leadValueLine`, `RulerLayout`, `leadForProgress`

**Files:**
- Modify: `ios/Sources/TrainAlarm/App/UI/Components.swift`, `android/app/src/main/java/com/trainalarm/app/ui/Components.kt` (add the helpers above `LeadRuler`; no view changes yet)
- Modify (tests): `ios/Tests/TrainAlarmTests/App/ComponentsTests.swift`, `android/app/src/test/java/com/trainalarm/app/ui/ComponentsTest.kt`

**Interfaces:**
- Consumes: `SettingsStore.leadChoices` / `LEAD_CHOICES` from Task 1.
- Produces (iOS, top level in `Components.swift`): `func minutesText(_ n: Int) -> String`; `func leadValueLine(_ minutes: Int?) -> String`; `struct RulerLayout: Equatable { let count: Int; let width: CGFloat; let inset: CGFloat; var spacing: CGFloat; func x(at index: Int) -> CGFloat; func index(at x: CGFloat) -> Int; static func isMajor(_ minutes: Int) -> Bool }`.
- Produces (Android, top level in `Components.kt`, package `com.trainalarm.app.ui`): `fun minutesText(n: Int): String`; `fun leadValueLine(minutes: Int?): String`; `data class RulerLayout(val count: Int, val width: Float, val inset: Float)` with `val spacing: Float`, `fun x(index: Int): Float`, `fun indexAt(x: Float): Int` and `companion object { fun isMajor(minutes: Int): Boolean }`; `fun leadForProgress(choices: List<Int>, value: Float): Int`. Widths and positions are points (iOS) or pixels (Android): the same units for ticks, marker, labels and gestures.

- [ ] **Step 1: Write the failing tests (iOS)**

Add to `ComponentsTests.swift` (inside the class):

```swift
    func testMinutesTextIsSingularForOne() {
        XCTAssertEqual(minutesText(1), "1 minute")
        XCTAssertEqual(minutesText(2), "2 minutes")
        XCTAssertEqual(minutesText(30), "30 minutes")
    }

    func testTheValueLineNamesTheChosenMinuteOrSaysNoneIsChosen() {
        XCTAssertEqual(leadValueLine(nil), "Not chosen")
        XCTAssertEqual(leadValueLine(1), "1 minute before arrival")
        XCTAssertEqual(leadValueLine(7), "7 minutes before arrival")
    }

    func testRulerLayoutSpacesEveryChoiceEvenlyBetweenTheInsets() {
        for width in [320.0, 375.0, 402.0] {
            let layout = RulerLayout(count: 30, width: CGFloat(width), inset: 16)
            XCTAssertEqual(layout.x(at: 0), 16, accuracy: 0.001)
            XCTAssertEqual(layout.x(at: 29), CGFloat(width) - 16, accuracy: 0.001)
            let expected = (CGFloat(width) - 32) / 29
            for i in 1..<30 {
                XCTAssertEqual(layout.x(at: i) - layout.x(at: i - 1), expected, accuracy: 0.001)
            }
        }
    }

    func testRulerLayoutFindsEveryChoiceFromItsOwnPosition() {
        // The real ruler is the screen width minus 18 points of padding on each side.
        for screen in [320.0, 375.0, 402.0] {
            let layout = RulerLayout(count: 30, width: CGFloat(screen) - 36, inset: 16)
            for i in 0..<30 { XCTAssertEqual(layout.index(at: layout.x(at: i)), i, "screen \(screen), tick \(i)") }
            for i in 1..<30 { XCTAssertGreaterThan(layout.x(at: i), layout.x(at: i - 1)) }   // a tick at every choice, in order
            XCTAssertEqual(layout.index(at: layout.x(at: 6) + 3), 6)   // a little off a tick still picks it
            XCTAssertEqual(layout.index(at: -50), 0)                    // beyond either end clamps
            XCTAssertEqual(layout.index(at: 9999), 29)
            XCTAssertEqual(layout.index(at: .nan), 0)
        }
    }

    func testRulerLayoutKeepsAtLeastEightPointsBetweenTicksOnNarrowPhones() {
        // The ruler is the screen width minus 18 points of padding on each side.
        for screen in [320.0, 375.0, 402.0] {
            let layout = RulerLayout(count: 30, width: CGFloat(screen) - 36, inset: 16)
            XCTAssertGreaterThanOrEqual(layout.spacing, 8, "screen \(screen)")
        }
    }

    func testOnlyMultiplesOfFiveAreMajorTicks() {
        XCTAssertEqual(SettingsStore.leadChoices.filter(RulerLayout.isMajor), [5, 10, 15, 20, 25, 30])
    }
```

- [ ] **Step 2: Write the failing tests (Android)**

Add to `ComponentsTest.kt` (inside the class; add `import org.junit.Assert.assertTrue` is already there):

```kotlin
    @Test
    fun minutesTextIsSingularForOne() {
        assertEquals("1 minute", minutesText(1))
        assertEquals("2 minutes", minutesText(2))
        assertEquals("30 minutes", minutesText(30))
    }

    @Test
    fun theValueLineNamesTheChosenMinuteOrSaysNoneIsChosen() {
        assertEquals("Not chosen", leadValueLine(null))
        assertEquals("1 minute before arrival", leadValueLine(1))
        assertEquals("7 minutes before arrival", leadValueLine(7))
    }

    @Test
    fun rulerLayoutSpacesEveryChoiceEvenlyBetweenTheInsets() {
        for (width in listOf(320f, 375f, 402f)) {
            val layout = RulerLayout(count = 30, width = width, inset = 16f)
            assertEquals(16f, layout.x(0), 0.001f)
            assertEquals(width - 16f, layout.x(29), 0.001f)
            val expected = (width - 32f) / 29f
            for (i in 1 until 30) assertEquals(expected, layout.x(i) - layout.x(i - 1), 0.001f)
        }
    }

    @Test
    fun rulerLayoutFindsEveryChoiceFromItsOwnPosition() {
        // The real ruler is the screen width minus 18 dp of padding on each side.
        for (screen in listOf(320f, 375f, 402f)) {
            val layout = RulerLayout(count = 30, width = screen - 36f, inset = 16f)
            for (i in 0 until 30) assertEquals("screen $screen, tick $i", i, layout.indexAt(layout.x(i)))
            for (i in 1 until 30) assertTrue(layout.x(i) > layout.x(i - 1))  // a tick at every choice, in order
            assertEquals(6, layout.indexAt(layout.x(6) + 3f))  // a little off a tick still picks it
            assertEquals(0, layout.indexAt(-50f))              // beyond either end clamps
            assertEquals(29, layout.indexAt(9999f))
            assertEquals(0, layout.indexAt(Float.NaN))
        }
    }

    @Test
    fun rulerLayoutKeepsAtLeastEightDpBetweenTicksOnNarrowPhones() {
        // The ruler is the screen width minus 18 dp of padding on each side.
        for (screen in listOf(320f, 375f, 402f)) {
            val layout = RulerLayout(count = 30, width = screen - 36f, inset = 16f)
            assertTrue("screen $screen: ${layout.spacing}", layout.spacing >= 8f)
        }
    }

    @Test
    fun onlyMultiplesOfFiveAreMajorTicks() {
        assertEquals(listOf(5, 10, 15, 20, 25, 30), SettingsStore.LEAD_CHOICES.filter(RulerLayout::isMajor))
    }

    @Test
    fun talkBackProgressMapsToTheNearestMinuteAndClamps() {
        val c = SettingsStore.LEAD_CHOICES
        assertEquals(1, leadForProgress(c, 0f))
        assertEquals(30, leadForProgress(c, 29f))
        assertEquals(7, leadForProgress(c, 6.4f))
        assertEquals(1, leadForProgress(c, -3f))
        assertEquals(30, leadForProgress(c, 99f))
    }
```

- [ ] **Step 3: Run to see them fail (RED), one platform at a time**

iOS: `cd ios && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/ComponentsTests 2>&1 | grep -E "error:|Executed|TEST (SUCCEEDED|FAILED)" | tail -8`
Expected: build errors `cannot find 'minutesText' in scope` (and the other new names).
Android: `cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests 'com.trainalarm.app.ui.ComponentsTest' --offline 2>&1 | tail -15`
Expected: `Unresolved reference: minutesText` (and the others).

- [ ] **Step 4: Implement (iOS)**

In `ios/Sources/TrainAlarm/App/UI/Components.swift`, add above the `/// Picks a lead time from ...` comment that precedes `struct LeadRuler`:

```swift
/// "1 minute", "7 minutes".
func minutesText(_ n: Int) -> String { n == 1 ? "1 minute" : "\(n) minutes" }

/// The line above the lead-time ruler: what is chosen, in words.
func leadValueLine(_ minutes: Int?) -> String {
    minutes.map { "\(minutesText($0)) before arrival" } ?? "Not chosen"
}

/// Where the ruler puts each choice, in points from its left edge. Pure, so it is tested without drawing:
/// the ticks, the marker, the labels and tap/drag all use these same positions.
struct RulerLayout: Equatable {
    let count: Int
    let width: CGFloat
    let inset: CGFloat

    var spacing: CGFloat { count > 1 ? max(0, width - inset * 2) / CGFloat(count - 1) : 0 }

    /// The x of the choice at `index`.
    func x(at index: Int) -> CGFloat { inset + spacing * CGFloat(index) }

    /// The choice nearest to `x`, clamped to the first and last.
    func index(at x: CGFloat) -> Int {
        guard count > 1, spacing > 0 else { return 0 }
        let raw = ((x - inset) / spacing).rounded()
        guard raw.isFinite else { return 0 }
        return Int(min(max(raw, 0), CGFloat(count - 1)))
    }

    /// Ticks at multiples of 5 are taller and carry a label.
    static func isMajor(_ minutes: Int) -> Bool { minutes % 5 == 0 }
}
```

- [ ] **Step 5: Implement (Android)**

In `android/app/src/main/java/com/trainalarm/app/ui/Components.kt`, add above the `/** The next choice up or down ... */` comment that precedes `stepLead` (add `import kotlin.math.roundToInt` if it is not already imported; `LeadRuler` already uses it):

```kotlin
/** "1 minute", "7 minutes". */
fun minutesText(n: Int): String = if (n == 1) "1 minute" else "$n minutes"

/** The line above the lead-time ruler: what is chosen, in words. */
fun leadValueLine(minutes: Int?): String = minutes?.let { "${minutesText(it)} before arrival" } ?: "Not chosen"

/**
 * Where the ruler puts each choice, in pixels from its left edge. Pure, so it is tested without drawing:
 * the ticks, the marker, the labels and tap/drag all use these same positions.
 */
data class RulerLayout(val count: Int, val width: Float, val inset: Float) {
    val spacing: Float get() = if (count > 1) maxOf(0f, width - inset * 2) / (count - 1) else 0f

    /** The x of the choice at [index]. */
    fun x(index: Int): Float = inset + spacing * index

    /** The choice nearest to [x], clamped to the first and last. */
    fun indexAt(x: Float): Int {
        if (count < 2 || spacing <= 0f) return 0
        val raw = (x - inset) / spacing
        return if (raw.isFinite()) raw.roundToInt().coerceIn(0, count - 1) else 0
    }

    companion object {
        /** Ticks at multiples of 5 are taller and carry a label. */
        fun isMajor(minutes: Int): Boolean = minutes % 5 == 0
    }
}

/** The choice for a TalkBack progress value (an index into [choices]), clamped to the ends. */
fun leadForProgress(choices: List<Int>, value: Float): Int = choices[value.roundToInt().coerceIn(0, choices.size - 1)]
```

- [ ] **Step 6: Run the focused tests (GREEN), then each full suite, one at a time**

Re-run the two focused commands (expect 0 failures), then the full iOS suite and the full Android command from Global Constraints (expect 0 failures; Android lint clean).

- [ ] **Step 7: Commit**

```bash
git add ios android
git commit -m "Feature: Add the lead-time ruler's layout and wording helpers

Pure, tested helpers for the one-minute ruler: singular minutes text, the value
line, the tick/marker/label layout with its inverse, and the TalkBack progress
mapping. The ruler view uses them in the next commit.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Rework `LeadRuler` and the dial's accessibility text

**Files:**
- Modify: `ios/Sources/TrainAlarm/App/UI/Components.swift`, `android/app/src/main/java/com/trainalarm/app/ui/Components.kt`

**Interfaces:**
- Consumes: Task 3's `minutesText`, `leadValueLine`, `RulerLayout`, `leadForProgress`; `LeadRuler.step` / `stepLead` (unchanged).
- Produces: the same `LeadRuler(choices:selected:onSelect:)` call signature on both platforms (so `ConfirmSheet` and `SettingsView`/`SettingsScreen` need no change), now drawing one tick per choice centred on the marker positions, taller ticks and labels at multiples of 5, and a value line above the strip.

There is no new unit test in this task: the view's behaviour that can be unit-tested is already covered in Task 3 (positions, wording, stepping, progress). This task is verified by the build, the full test suites, and (for iOS) the screenshots in Task 5.

- [ ] **Step 1: Rework the iOS `LeadRuler`**

In `Components.swift`, replace the whole `struct LeadRuler: View { ... }` (keep its doc comment and the `static func step`) with:

```swift
/// Picks a lead time from `choices`: tap or drag along the tick ruler. The marker sits on the choice, and a line above
/// the ruler says what is chosen. One tick per choice; ticks at multiples of 5 are taller and labelled.
struct LeadRuler: View {
    /// The next choice up or down from `selected`, clamped at both ends. Nothing chosen starts at the first choice.
    static func step(_ choices: [Int], from selected: Int?, up: Bool) -> Int {
        let index = selected.flatMap { choices.firstIndex(of: $0) } ?? 0
        return choices[up ? min(choices.count - 1, index + 1) : max(0, index - 1)]
    }

    let choices: [Int]
    let selected: Int?
    let onSelect: (Int) -> Void
    @Environment(\.colors) private var c
    private let inset: CGFloat = 16

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(leadValueLine(selected)).font(.callout.weight(.semibold))
                .foregroundStyle(selected == nil ? c.muted : c.head)
            GeometryReader { geo in
                let layout = RulerLayout(count: choices.count, width: geo.size.width, inset: inset)
                VStack(spacing: 6) {
                    ZStack(alignment: .leading) {
                        Canvas { context, size in
                            for (index, minutes) in choices.enumerated() {
                                let height = size.height * (RulerLayout.isMajor(minutes) ? 0.52 : 0.36)
                                let rect = CGRect(x: layout.x(at: index) - 0.75, y: (size.height - height) / 2, width: 1.5, height: height)
                                context.fill(Path(rect), with: .color(c.muted))
                            }
                        }
                        if let selected, let index = choices.firstIndex(of: selected) {
                            Rectangle().fill(c.accent).frame(width: 3).offset(x: layout.x(at: index) - 1.5)
                        }
                    }
                    .frame(height: 44)
                    .background(c.surface2, in: RoundedRectangle(cornerRadius: 12))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .contentShape(Rectangle())
                    .gesture(DragGesture(minimumDistance: 0).onChanged { value in
                        onSelect(choices[layout.index(at: value.location.x)])
                    })
                    ZStack(alignment: .leading) {
                        ForEach(Array(choices.enumerated()), id: \.element) { index, minutes in
                            if RulerLayout.isMajor(minutes) {
                                Text("\(minutes)").font(.dots(17)).foregroundStyle(minutes == selected ? c.head : c.muted)
                                    .position(x: layout.x(at: index), y: 9)
                            }
                        }
                    }
                    .frame(height: 18)
                }
            }
            .frame(height: 70)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Minutes before arrival")
        .accessibilityValue(selected.map(minutesText) ?? "not chosen")
        .accessibilityAdjustableAction { direction in
            onSelect(Self.step(choices, from: selected, up: direction == .increment))
        }
    }
}
```

In the same file, in `AlarmDial`, change the accessibility label line to use `minutesText`:

```swift
        .accessibilityLabel(lead.map { "Alarm \(minutesText($0)) before arrival" } ?? "No alarm time chosen")
```

- [ ] **Step 2: Rework the Android `LeadRuler`**

In `Components.kt`, replace the whole `@Composable fun LeadRuler(...) { ... }` (keep its doc comment; `stepLead` stays) with the version below. Add the imports it needs if missing: `androidx.compose.ui.platform.LocalDensity`, `androidx.compose.ui.text.font.FontWeight`. Remove imports that become unused (for example `kotlin.math.max`) so lint stays clean.

```kotlin
/** Picks a lead time from [choices]: tap or drag along the tick ruler. The marker sits on the choice, and a line above
 *  the ruler says what is chosen. One tick per choice; ticks at multiples of 5 are taller and labelled. */
@Composable
fun LeadRuler(choices: List<Int>, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val inset = 16.dp
    val density = LocalDensity.current
    fun layoutFor(widthPx: Float) = RulerLayout(choices.size, widthPx, with(density) { inset.toPx() })
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = "Minutes before arrival"
            stateDescription = selected?.let { minutesText(it) } ?: "not chosen"
            // Adjustable for TalkBack: the same steps as the iOS adjustable action, through the same onSelect.
            progressBarRangeInfo = ProgressBarRangeInfo(
                (selected?.let { choices.indexOf(it) }?.coerceAtLeast(0) ?: 0).toFloat(), 0f..(choices.size - 1).toFloat(), choices.size - 2)
            setProgress { value -> onSelect(leadForProgress(choices, value)); true }
            customActions = listOf(
                CustomAccessibilityAction("Increase minutes") { onSelect(stepLead(choices, selected, up = true)); true },
                CustomAccessibilityAction("Decrease minutes") { onSelect(stepLead(choices, selected, up = false)); true }
            )
        }
    ) {
        Text(
            leadValueLine(selected), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = if (selected == null) c.muted else c.head, modifier = Modifier.padding(bottom = 8.dp)
        )
        Box(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2)
                .pointerInput(choices) { detectTapGestures { onSelect(choices[layoutFor(size.width.toFloat()).indexAt(it.x)]) } }
                .pointerInput(choices) {
                    detectHorizontalDragGestures { change, _ -> onSelect(choices[layoutFor(size.width.toFloat()).indexAt(change.position.x)]) }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val layout = RulerLayout(choices.size, size.width, inset.toPx())
                val tickWidth = 1.5.dp.toPx()
                choices.forEachIndexed { index, minutes ->
                    val height = size.height * if (RulerLayout.isMajor(minutes)) 0.52f else 0.36f
                    drawRect(c.muted, Offset(layout.x(index) - tickWidth / 2, (size.height - height) / 2),
                        androidx.compose.ui.geometry.Size(tickWidth, height))
                }
                val index = selected?.let { choices.indexOf(it) } ?: -1
                if (index >= 0) {
                    drawRect(c.accent, Offset(layout.x(index) - 1.5.dp.toPx(), 0f),
                        androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp).padding(top = 4.dp)) {
            val layout = layoutFor(with(density) { maxWidth.toPx() })
            choices.forEachIndexed { index, minutes ->
                if (RulerLayout.isMajor(minutes)) {
                    // A 32 dp slot centred on the tick, so two-digit labels sit under their tick.
                    Box(
                        Modifier.offset(x = with(density) { layout.x(index).toDp() } - 16.dp).width(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            minutes.toString(), fontFamily = Doto, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                            color = if (minutes == selected) c.head else c.muted
                        )
                    }
                }
            }
        }
    }
}
```

In the same file, in `AlarmDial`, change the content description:

```kotlin
            contentDescription = lead?.let { "Alarm ${minutesText(it)} before arrival" } ?: "No alarm time chosen"
```

- [ ] **Step 3: Build and run the full suites, one platform at a time**

Run the full iOS suite and the full Android command from Global Constraints. Expected: everything compiles, 0 test failures, Android lint reports no errors (new warnings: report them; do not suppress). If Android lint complains about an unused import, remove the import.

- [ ] **Step 4: Commit**

```bash
git add ios android
git commit -m "Feature: Show every minute on the lead-time ruler

The ruler now draws one tick per minute, centred on the positions the marker
can take, with taller ticks and labels at the multiples of 5 and a value line
above it. Tap and drag snap to the nearest minute, and the accessibility text
says '1 minute' in the singular.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Demo scenarios, screenshot filter, retake four iOS screenshots

**Files:**
- Modify: `ios/Sources/TrainAlarm/App/Demo/Scenario.swift`, `ios/Sources/TrainAlarm/App/AppEnvironment.swift` (the one call site of `apply`), `android/app/src/debug/java/com/trainalarm/app/demo/Scenario.kt` and `android/app/src/debug/java/com/trainalarm/app/demo/DemoEntry.kt` (the one call site, line 28)
- Modify (tests): `ios/Tests/TrainAlarmTests/App/ScenarioTests.swift`, `android/app/src/testDebug/java/com/trainalarm/app/demo/ScenarioTest.kt`
- Modify: `tools/screenshots/ios.sh`, `tools/screenshots/android.sh`
- Replace (output): `docs/ui-screenshots/ios/confirm-dark.png`, `confirm-light.png`, `settings-dark.png`, `settings-light.png`

**Interfaces:**
- Consumes: the reworked `LeadRuler` (Task 4); `SettingsStore.setDefaultLeadMinutes`.
- Note: on the user's phone `-scenario settings` writes the saved default lead time (it uses the real `UserDefaults`), the same way the tracking scenarios already do through `start()`. Debug-only and accepted.
- Produces: `Scenario.apply(to:settings:directory:now:)` on iOS and `Scenario.apply(model, settings, directory, now)` on Android (a new `settings` parameter); constants `Scenario.settingsLead = 7` and `Scenario.confirmLead = 7`. The `settings` scenario now sets the default lead to 7; the `confirm` scenario uses a lead of 7; every tracking-family scenario still uses 15. The screenshot scripts accept `SCENARIOS="confirm settings"` to run a subset (default: all 12).

- [ ] **Step 1: Update the scenario tests first (RED)**

iOS `ios/Tests/TrainAlarmTests/App/ScenarioTests.swift` (the whole file is inside `#if DEBUG`). Today `make()` builds the `SettingsStore` inline, so tests cannot reach it. Make three edits:

1. Add a stored property `private var settings: SettingsStore!` next to `private var suite`, and in `make()` build it first: `settings = SettingsStore(defaults: UserDefaults(suiteName: suite)!)`, then pass `settings: settings` to the `SessionModel` initialiser in place of the inline `SettingsStore(...)`.
2. In `run(_:)` change the call to `await scenario.apply(to: model, settings: settings, directory: directory, now: clock.now())`. (`run` keeps returning `(SessionModel, Date)`, so no other call site changes.)
3. In `testBoardAndConfirmScenarios` change `XCTAssertEqual(confirmModel.confirm?.leadMinutes, 15)` to `7`.

Then add these two tests to the class (`settings` is the stored property from edit 1; each `run` call builds a fresh store, so read it right after the run it belongs to):

```swift
    func testTheConfirmScenarioUsesAnOddLeadTimeAndTheTrackingScenariosKeepFifteen() async throws {
        let (confirmModel, _) = try await run(.confirm)
        XCTAssertEqual(confirmModel.confirm?.leadMinutes, 7)
        let (trackingModel, _) = try await run(.tracking)
        // `confirm` is expected to survive `start()`. If it is nil there, assert on the alarm time instead:
        // trackingModel.display.alarmAt equals the arrival minus 15 minutes.
        XCTAssertEqual(trackingModel.confirm?.leadMinutes, 15)
    }

    func testTheSettingsScenarioSetsTheDefaultLeadTimeToSeven() async throws {
        _ = try await run(.settings)
        XCTAssertEqual(settings.defaultLeadMinutes, 7)
    }
```

Android `android/app/src/testDebug/java/com/trainalarm/app/demo/ScenarioTest.kt`. Today `run(scope, scenario)` builds `SettingsStore(MemoryKeyValues())` inline. Make three edits:

1. Add `private var lastSettings: SettingsStore? = null` to the class. In `run`, build it first: `val settings = SettingsStore(MemoryKeyValues()).also { lastSettings = it }`, and pass `settings` to the `SessionModel` constructor in place of the inline store.
2. Change the call to `scenario.apply(model, settings, directory, clock.now())`.
3. In `boardAndConfirmScenarios` change `assertEquals(15, model.confirm?.leadMinutes)` to `7`.

Then add:

```kotlin
    @Test fun theConfirmScenarioUsesAnOddLeadTimeAndTheTrackingScenariosKeepFifteen() {
        scenario(Scenario.CONFIRM) { model -> assertEquals(7, model.confirm?.leadMinutes) }
        scenario(Scenario.TRACKING) { model -> assertEquals(15, model.confirm?.leadMinutes) }
    }

    @Test fun theSettingsScenarioSetsTheDefaultLeadTimeToSeven() {
        scenario(Scenario.SETTINGS) { _ -> assertEquals(7, lastSettings!!.defaultLeadMinutes.value) }
    }
```

Run the focused scenario tests, one platform at a time. Expected RED: compile errors for the extra `settings:` argument and, once those compile, failures for the 7s.
- iOS: `cd ios && xcodegen generate && xcodebuild test -scheme TrainAlarm -destination "platform=iOS Simulator,name=iPhone 17" -only-testing:TrainAlarmTests/ScenarioTests 2>&1 | grep -E "error:|failed|Executed|TEST (SUCCEEDED|FAILED)" | tail -10`
- Android: `cd android && ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:testDebugUnitTest --tests 'com.trainalarm.app.demo.ScenarioTest' --offline 2>&1 | tail -15`

- [ ] **Step 2: Implement the scenario changes**

iOS `Scenario.swift`: add the constants and the `settings` parameter, and use them:

```swift
    /// The default lead time the `settings` scenario shows, and the lead time of the `confirm` scenario.
    /// Both are not multiples of 5, so the screenshots show a minute that has no label of its own.
    static let settingsLead = 7
    static let confirmLead = 7
```

```swift
    func apply(to model: SessionModel, settings: SettingsStore, directory: StationDirectory, now: Date) async {
        if self == .settings { settings.setDefaultLeadMinutes(Self.settingsLead) }
        guard self != .home, self != .settings,
```
(the `guard` line otherwise unchanged) and replace `model.setLead(15)` with:

```swift
        model.setLead(self == .confirm ? Self.confirmLead : 15)
```

Update the single production call site in `AppEnvironment.swift`:

```swift
                applyScenario: scenario.map { s in { await s.apply(to: model, settings: settings, directory: directory, now: Date()) } })
```

Android `Scenario.kt` (debug source set): add to the enum's companion (create one if it has none; keep any existing members):

```kotlin
        /** The default lead time the `settings` scenario shows, and the lead time of the `confirm` scenario.
         *  Both are not multiples of 5, so the screenshots show a minute that has no label of its own. */
        const val SETTINGS_LEAD = 7
        const val CONFIRM_LEAD = 7
```

```kotlin
    suspend fun apply(model: SessionModel, settings: SettingsStore, directory: StationDirectory, now: Instant) {
        if (this == SETTINGS) settings.setDefaultLeadMinutes(SETTINGS_LEAD)
        if (this == HOME || this == SETTINGS) return
```
and replace `model.setLead(15)` with `model.setLead(if (this == CONFIRM) CONFIRM_LEAD else 15)`. Update its call site in `android/app/src/debug/java/com/trainalarm/app/demo/DemoEntry.kt` (line 28): `scenario?.apply(model, settings, directory, Instant.now())` (the `settings` parameter of `DemoEntry.create` is already in scope).

- [ ] **Step 3: Add the scenario filter to both screenshot scripts**

`tools/screenshots/ios.sh`: replace the `SCENARIOS=...` line with:

```bash
SCENARIOS="${SCENARIOS:-home board confirm tracking tracking-late cancelled dropped feed-lost alarm-not-set ringing ended settings}"
```
and add a usage line under the existing one: `# Optional: SCENARIOS="confirm settings" tools/screenshots/ios.sh   (a subset; default: all 12)`. Make the same change in `tools/screenshots/android.sh`. Keep the executable bit (`git ls-files -s tools/screenshots/*.sh` must still show mode 100755).


- [ ] **Step 4: Run the scenario tests (GREEN), then the full suites, one platform at a time**

Re-run the two focused scenario commands (0 failures), then the full iOS suite and the full Android command from Global Constraints. Expected: 0 failures; the iOS Release build must still succeed (`xcodebuild build -scheme TrainAlarm -configuration Release -destination "generic/platform=iOS Simulator"`) and `assembleRelease` is already in the Android command (demo code must stay out of release on both).

- [ ] **Step 5: Retake the four screenshots (iOS)**

```bash
SCENARIOS="confirm settings" tools/screenshots/ios.sh
git status --short
```
Expected: the script prints `Wrote 24 screenshots to docs/ui-screenshots/ios` (it counts the whole folder, not the run), and `git status --short` shows exactly four modified PNGs: `docs/ui-screenshots/ios/confirm-dark.png`, `confirm-light.png`, `settings-dark.png`, `settings-light.png`, and nothing else under `docs/ui-screenshots/` (plus the source/script edits). If other PNGs changed, restore them with `git checkout -- <file>`. `ls docs/ui-screenshots/ios | wc -l` must still print 24.

- [ ] **Step 6: Open and check all four screenshots**

Open each of the four PNGs with the Read tool. For each, check and write down in your report: a tick per minute (about 30 ticks across the strip); the ticks at 5, 10, 15, 20, 25, 30 taller than the rest; labels only at those six numbers; the orange marker standing on a tick (the 7th tick, between the 5 and 10 labels, closer to 5); the value line above the ruler reads "7 minutes before arrival"; nothing clipped or overlapping (labels, value line, the dial on the Confirm sheet); both themes look right (dark and light). On Confirm the dial's centre number reads 7. If anything is wrong, fix the code (not the image) and run Step 5 again.

- [ ] **Step 7: Commit**

```bash
git add ios android tools docs/ui-screenshots
git commit -m "Feature: Retake the ruler screenshots and let the demo show an odd lead time

The settings demo scenario now sets its own default lead time (7) and the
confirm scenario uses 7, so the screenshots show a minute with no label of its
own. The screenshot scripts take an optional SCENARIOS filter. Only the four
screenshots with a ruler were retaken.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Docs

**Files:**
- Modify: `docs/spec.md`, `.ai/testing-guide.md`

**Interfaces:**
- Consumes: everything built in Tasks 1 to 5 and the ledger (what was seen, what was not).
- Produces: docs that match the code. Do NOT touch the Stage 3 status line in `docs/spec.md` §8. Do NOT write that the new ruler was checked on a device or that any Android screen was seen.

- [ ] **Step 1: Update `docs/spec.md` §8, Stage 4**

Find the end of the Stage 4 item (the sentence that ends "...and background tracking (Stage 5).") and append, on the same paragraph:

```text
 The lead-time ruler picks any whole minute from 1 to 30 (a follow-up to Stage 4): the iOS ruler and its four screenshots were seen in the simulator; the Android ruler was compiled and unit-tested only, never seen.
```

Also read §4 and §5 of `docs/spec.md` for any sentence that describes the lead time as steps of 5 or a fixed list; there should be none (`grep -n -i "5, 10\|steps of 5\|5 min" docs/spec.md` returned nothing when this plan was written). If one exists now, correct it.

- [ ] **Step 2: Update `.ai/testing-guide.md`**

`.ai/testing-guide.md` already describes the screenshot script in the "UI is checked by ..." bullet (about lines 67 to 71). Append this to that bullet (as a new sub-sentence or a following bullet in the same list):

```text
- **Screenshots:** `tools/screenshots/ios.sh` retakes all 12 demo scenarios in both themes; set `SCENARIOS="confirm settings"` to retake only some, so the other PNGs stay untouched (the clock text inside the images changes on every run). The Android script takes the same filter and has never been run (no emulator).
```

- [ ] **Step 3: Verify**

`git diff --stat` shows only `docs/spec.md` and `.ai/testing-guide.md`; `git diff docs/spec.md` shows no hunk touching the Stage 3 status line (the paragraph beginning "3. Alarm delivery spike"). No build is needed for a docs-only change.

- [ ] **Step 4: Commit**

```bash
git add docs/spec.md .ai/testing-guide.md
git commit -m "Docs: Describe the one-minute lead time and the screenshot filter

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Running the app (for the user's iPhone check, after the PR is open)

This is not a task for an implementer. After the final review the controller builds a debug build for the user's iPhone (command in the project memory: `DEVELOPMENT_TEAM` and `PRODUCT_BUNDLE_IDENTIFIER=com.xaviershah.trainalarm.spike` on the command line only, phone UDID from `xcrun xctrace list devices`), launches `-scenario confirm`, and asks the user to drag the ruler and confirm it lands on single minutes (spec acceptance item 3).
