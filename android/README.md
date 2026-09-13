# TrainAlarm — Android

Kotlin + Jetpack Compose, native (no cross-platform framework — see
docs/spec.md §6 for why).

## Opening this project

1. Open Android Studio (Ladybird or later) → Open → select this `android/`
   folder.
2. Let Android Studio sync Gradle. The wrapper JAR isn't committed (binary,
   fetched by Android Studio on first sync) — if `./gradlew` complains
   about a missing wrapper before that, run `gradle wrapper` once with a
   local Gradle install, or just let Android Studio's sync handle it.
3. Run on a real device once you reach Stage 3 (alarm delivery) — background
   location + exact alarms don't behave reliably on the emulator.

## Structure

- `app/src/main/java/com/trainalarm/app/model/` — Stage 1
- `app/src/main/java/com/trainalarm/app/provider/` — Stage 1
- `app/src/main/java/com/trainalarm/app/tracking/` — Stage 2
- `app/src/main/java/com/trainalarm/app/alarm/` — Stage 3
- `MainActivity.kt` — Stage 4 replaces the current placeholder screen.

## Minimum SDK

`minSdk = 29` (Android 10) — chosen because `ACCESS_BACKGROUND_LOCATION`
as a distinct runtime permission starts there; below it, background
location ships bundled with the foreground permission, which changes the
permission-request flow in Stage 3. Revisit if this floor turns out to be
excluding too many real users.
