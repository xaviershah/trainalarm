Alarm delivery on Android (Stage 3). See docs/spec.md §5.

- `AlarmScheduler` (interface) is what the journey tracker calls.
- `ExactAlarmScheduler` implements it through `ExactAlarmGateway` (`SystemExactAlarmGateway` is the real
  one, backed by `AlarmManager.setAlarmClock`); it clamps times in the past to now plus 2 s.
- `AlarmReceiver` starts `AlarmService` (a `mediaPlayback` foreground service that plays the sound via
  `AlarmPlayer`) and `AlarmNotifications` posts the full-screen-intent notification that opens
  `AlarmActivity`.
- `AlarmPermissions` and `DevAlarmScreen` are the permission checks and the debug-only harness.

Built and unit-tested. NOT yet verified on a physical Android 14+ phone: Stage 3 is not done until the
Android checklist has run on one.
