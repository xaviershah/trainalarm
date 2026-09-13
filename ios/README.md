# TrainAlarm — iOS

Swift + SwiftUI, native (no cross-platform framework — see docs/spec.md §6
for why). The Xcode project itself is **not** committed as a hand-written
`.xcodeproj` — that file format is fragile to author by hand and easy to
corrupt. Instead this uses [XcodeGen](https://github.com/yonaskolb/XcodeGen)
(`project.yml`), a plain-text, git-diff-friendly project definition that
Xcode-generates a real `.xcodeproj` from.

## Opening this project

1. Install XcodeGen once (needs Homebrew, on your actual Mac — not this
   linked session): `brew install xcodegen`
2. From inside `ios/`: `xcodegen generate`
3. Open the generated `TrainAlarm.xcodeproj` in Xcode.

`TrainAlarm.xcodeproj` is git-ignored on purpose — regenerate it from
`project.yml` any time; don't hand-edit the generated project, edit
`project.yml` instead and regenerate.

## Minimum iOS version

`26.0` — required by AlarmKit (docs/spec.md §5). Revisit if this excludes
too much of the real user base by the time this ships.

## Structure

- `Sources/TrainAlarm/Models/` — Stage 1
- `Sources/TrainAlarm/Providers/` — Stage 1
- `Sources/TrainAlarm/Tracking/` — Stage 2
- `Sources/TrainAlarm/Alarm/` — Stage 3
- `Sources/TrainAlarm/App/` — Stage 4 replaces the current placeholder view.
- `Sources/TrainAlarm/TrainAlarm.entitlements` — has a TODO for Stage 3;
  AlarmKit's exact entitlement requirements need checking against Apple's
  current docs, not guessed at.
