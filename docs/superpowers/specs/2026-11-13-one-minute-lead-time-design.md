# One-minute lead time: design

## Why

The lead-time ruler (how many minutes before arrival the alarm rings) draws a tick for
every minute, but you can only land on 5, 10, 15, 20, 25 or 30. On the phone this felt
wrong: the user wants to pick 1 minute, 2 minutes, 3 minutes and so on. A short lead time
matters for a short last leg, and "5 minutes minimum" is not what the ruler looks like it
offers.

## What changes

Every whole minute from 1 to 30 can be chosen, on both platforms, on both screens that use
the ruler (Confirm sheet: this journey; Settings: the default for new journeys).

Decisions, with the assumptions marked:

1. **Range 1 to 30.** The upper limit stays at 30 (assumption: the user did not ask for
   more). The lower limit becomes 1.
2. **One tick per choice, centred on its position.** Today the ticks are drawn every 9 pt
   whatever the choices are, left-aligned at x, and only the marker is placed by choice
   (centred). Ticks become one per minute, drawn centred on the same x the marker takes for
   that minute. Ticks at multiples of 5 are taller, to find your place quickly.
3. **Labels at the multiples of 5** (5, 10, 15, 20, 25, 30) as landmarks. Labelling all 30
   would not fit. Minute 1 has no label; its tick is the first one. A label is highlighted
   only when the selected minute is one of them.
4. **The chosen minute is always readable.** The ruler shows a value line above the strip:
   "7 minutes before arrival", "1 minute before arrival", or "Not chosen". Without it a
   selected 7 would be an unlabelled marker, and Settings has no other readout (the Confirm
   sheet's dial shows the number, but Settings does not). One component, so both screens
   get it. The existing footnote in Settings ("Minutes before arrival. You can still change
   it for each journey.") stays.
5. **Tap or drag snaps to the nearest minute.** Same gesture as today, finer result.
   Spacing between ticks is about 11.5 pt at 402 pt wide, 10.6 pt at 375 pt, and 8.7 dp at a
   320 dp Android width (screen padding 18 and ruler inset 16 on each side). The tap target
   stays the full 44 pt strip. A single tap can miss by a minute on a narrow phone, so the
   value line shows what was picked and a drag fixes it; VoiceOver and TalkBack step by one.
6. **VoiceOver and TalkBack step by one minute** (adjustable action; Android Increase and
   Decrease actions and `setProgress`). Behaviour from "not chosen" is unchanged: the first
   step starts from the first choice (up gives 2, down gives 1). Accessibility text says
   "1 minute", not "1 minutes" (see Singular wording).
7. **First run stays empty.** No stored lead time means Start stays disabled until one is
   chosen (assumption: unchanged from Stage 4).
8. **Stored values.** A value saved earlier (5, 10, ... 30) is still valid and reads back
   unchanged, so no migration. A stored value outside 1 to 30 reads as unset, and the
   setters ignore values outside 1 to 30, as now. On Android the stored-value read also
   survives a wrong-typed value (it reads as unset instead of throwing at launch).
9. **Starting a journey still saves that lead as the default**, as now (`SessionModel`
   `start()` calls `setDefaultLeadMinutes`). A one-off pick therefore becomes the next
   default. This is existing, intended behaviour and is kept.

### Singular wording

A small helper per platform, `minutesText(n)`, returns "1 minute" or "n minutes". It is used
in the ruler's value line, its accessibility value, and the alarm dial's accessibility label
(today these build "\(n) minutes" inline, which would read "1 minutes").

### Tracker and alarm

No change to the tracker or the schedulers. Verified: `TrackerConfig.leadTime` is a plain
seconds value with no lower bound (`JourneyMonitor` uses `eta - leadTime`), and a fire time
already past is clamped to now (`JourneyMonitor`), with a further 2 s minimum from now in
both schedulers (`AlarmKitScheduler`, `ExactAlarmScheduler`). A 1-minute lead leaves the
traveller about a minute, plus up to one poll interval of latency if the ETA changes late.
This is accepted. Tracker tests only use a 10-minute lead today, so one monitor test per
platform with a 60 s lead is added (start inside the last minute: the clamp fires now; a
1-minute ETA slip: it reschedules).

## Where

One list per platform defines the choices, and everything else reads it.

| | iOS | Android |
|---|---|---|
| Choices | `SettingsStore.leadChoices` (`App/SettingsStore.swift`) | `SettingsStore.LEAD_CHOICES` (`SettingsStore.kt`) |
| Ruler | `LeadRuler` (`App/UI/Components.swift`) | `LeadRuler` (`ui/Components.kt`) |
| Users of the list | `SessionModel.setLead`, `SettingsStore`, `ConfirmSheet`, `SettingsView` | the same four on Android |
| Unchanged consumers | `AlarmDial` (shows the lead number verbatim), `TimeText` | the same |

The stepping helpers (`LeadRuler.step`, `stepLead`) keep their logic: they step through
whatever list they are given. The ruler's position maths moves out of the view body into
one pure function per platform so it can be tested (see Tests).

## Tests

Four existing tests are rewritten, because they assumed 5-minute steps; all other existing
tests that call `setLead(15)` or `setDefaultLeadMinutes(10)` keep working unchanged.

- **Rewritten:** the two step tests (`ComponentsTests.swift:20`, `ComponentsTest.kt:28`) and
  the two "outside the choices" store tests (`SettingsStoreTests.swift:30`,
  `SettingsStoreTest.kt:30`).
- **Store (both platforms):** the choices are exactly 1 to 30; 1 and 30 are accepted; 0 and
  31 are ignored; a stored 17 now reads back as 17; a stored 99 and a stored 0 read as
  unset; a stored 15 (an old value) still reads 15. Android: a wrong-typed stored value
  reads as unset.
- **Model (both platforms):** `setLead(1)` and `setLead(7)` are accepted and move the alarm
  time by that many minutes; `setLead(0)` and `setLead(31)` are ignored.
- **Stepping (both platforms):** from 15 up gives 16 and down gives 14; clamps at 1 and 30;
  from nothing chosen up gives 2 and down gives 1.
- **Ruler layout (both platforms), a pure function** (for example
  `rulerLayout(count:width:inset:)` and its inverse `indexAt(x:...)`), tested with
  independent assertions, not by calling the same function twice: the first tick at `inset`,
  the last at `width - inset`, equal spacing `(width - 2·inset)/(count - 1)` between all
  neighbours, a tick at every choice, "major" true exactly for multiples of 5, the inverse
  round-trips every tick centre to its own index, x outside the bounds clamps to the first or
  last index, and spacing is at least 8 at widths 320, 375 and 402 with the real insets.
  Android lays labels out in the same units as the ticks (px).
- **`minutesText` (both platforms):** 1 gives "1 minute"; 2 and 30 give "n minutes". The
  value line gives "Not chosen" for nil.
- **Android `setProgress` mapping:** progress index 0 selects 1 and 29 selects 30 (a pure
  helper, if cheap).
- **Tracker (both platforms):** the 60 s lead monitor tests above.

## Screenshots

The ruler appears in four committed iOS screenshots: `confirm-dark`, `confirm-light`,
`settings-dark`, `settings-light`. Retake those four with a scenario filter and check them.

- `tools/screenshots/ios.sh` gets an optional `SCENARIOS="..."` environment override
  (default: all 12), so a partial run does not touch the other 20 files. Check with
  `git status` that the other 20 are unchanged.
- The demo `settings` scenario currently returns before touching anything, so its marker
  depends on leftover simulator state. It now sets a default lead of 7 explicitly. The demo
  `confirm` scenario uses a lead of 7 (the tracking-family scenarios keep 15). Both on iOS
  and Android (debug sources), and any `ScenarioTests` that assume the old values are
  updated.
- Check in the screenshots that the 7 sits on a tick, that its value line reads
  "7 minutes before arrival", and that the labels at 5, 10 ... 30 are not clipped. At a
  1-minute lead the alarm dot on the dial sits one mark (6 degrees) from the arrival dot and
  overlaps it slightly; the alarm dot is drawn on top, which is accepted.

Android screens are still not seen (no emulator or phone); the Android ruler is compiled and
unit-tested only.

## Acceptance (ships when)

1. On both platforms every whole minute from 1 to 30 can be selected on the Confirm sheet
   and in Settings, and nothing else can; the accessibility value reads "1 minute" for 1.
2. On the iOS simulator the four screenshots show a tick per minute, taller ticks at the
   multiples of 5, labels at the multiples of 5, the marker on a tick, and the value line.
3. On the user's iPhone, dragging along the ruler lands on single minutes, and a 1-minute
   lead time starts and shows the alarm one minute before arrival in the Confirm sheet.
4. iOS and Android suites and Android lint pass.

## Not in scope

More than 30 minutes; haptic feedback while dragging; plus and minus buttons beside the
ruler (a possible later fix for tap precision on narrow phones); changing how the alarm or
tracker work; a changed first-run default; large-text (Dynamic Type) layout of the ruler
labels, which predates this change; Android on a device.
