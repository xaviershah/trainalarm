Alarm delivery on iOS (Stage 3). See docs/spec.md §5.

- `AlarmScheduler` (protocol) is what the journey tracker calls.
- `AlarmKitScheduler` implements it with a single one-shot AlarmKit alarm, replacing any earlier one,
  and clamps times in the past to now plus 2 s.
- `AlarmKitClient` is the thin seam over AlarmKit (authorization, schedule, cancel) so the scheduler
  can be unit-tested with a fake. `SystemAlarmKitClient` is the real one.
- Needs `NSAlarmKitUsageDescription` in Info.plist; no entitlement key.

Verified on a real iPhone 12 Pro (iOS 26.6.1); see the Stage 3 entry in docs/spec.md §8.
