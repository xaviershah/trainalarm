# Architecture

Source of truth: `docs/spec.md`. This file is the short version.

## Folder structure

```
TrainAlarm/
├── ios/                 Native iOS app (Swift/SwiftUI), XcodeGen project
│   ├── Sources/TrainAlarm/{App,Models,Providers,Tracking,Alarm}/
│   └── Tests/TrainAlarmTests/{Model,Provider,Fixtures}/
├── android/             Native Android app (Kotlin/Compose)
│   └── app/src/{main,test}/java/com/trainalarm/app/{model,provider,tracking,alarm}/
├── docs/spec.md         Product + technical spec, stage plan (§8)
├── downloads/RTT.GH.API-spec   Real RTT API schema — read before touching the parser
└── .github/workflows/   ios.yml, android.yml (CI)
```

## Where new code goes

One folder/package per architectural layer, identical on both platforms:

| Layer | iOS | Android | Stage |
|---|---|---|---|
| Data model (`Station`, `Stop`, `Service`, `Journey`) | `Models/` | `model/` | 1 (done) |
| Rail data (`TrainDataProvider` + `RttProvider`) | `Providers/` | `provider/` | 1 (done) |
| Tracking, ETA, live-change logic | `Tracking/` | `tracking/` | 2 |
| Alarm delivery | `Alarm/` | `alarm/` | 3 |
| UI / app entry | `App/` | package root | 4 |

A new feature is built on **both** platforms, in the matching layer.

## Key decisions

- **Native only, no shared code.** Separate Swift and Kotlin codebases; only the
  data model and `TrainDataProvider` contract are shared *in spec* (spec §6).
  No React Native/Flutter/KMP layer.
- **Provider boundary.** Tracking, ETA and alarm code depend only on the
  `TrainDataProvider` protocol/interface and model types — never on
  `RttProvider` or raw API JSON.
- **UK data source v1:** Realtime Trains bearer-token API. Darwin/NRDP is the
  later migration target (TBD — registration unverified).
- **Alarms:** iOS via AlarmKit (iOS 26+); Android via foreground service +
  exact-alarm permission. Highest-risk area; needs real-device testing.
- **Spec-first stages.** Each stage in spec §8 has a written plan before code;
  its "ships when" line is the acceptance criterion.

## TBD

- Static UK station dataset (CRS + name + lat/lon) — source and location in repo.
- State management / UI architecture pattern (MVVM etc.) — decide at Stage 4.
- Dependency injection approach.
- Minimum iOS version acceptability (spec §7).
