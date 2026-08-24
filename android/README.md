# ToDo Companion (Android)

A private, fully-offline task manager — MyLifeOrganized-style outlining with a
TickTick-grade UI. Native **Kotlin + Jetpack Compose (Material 3)**, **Room/SQLite**,
**no account, no network permission**, everything free, lossless JSON export/import.

## Privacy by construction

The app declares **no `INTERNET` permission**, so it cannot access the network. The
only permissions are local: `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`,
`USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `VIBRATE` — all for on-device reminders.

## Phase 1 (implemented)

- **Outline** — unlimited nested tasks; collapse, complete, swipe to complete/delete.
- **Do Next** — the MLO-style computed-priority list (importance + urgency + due
  proximity + ancestor inheritance, with start/blocked/context gating).
- **Planner** — **Matrix** (Eisenhower quadrants) and **Calendar** (month + agenda),
  with the default set by Settings → First view.
- **Quick-add** — natural-language capture: `Pay rent tomorrow 5pm !! #home @errands`.
- **Task detail** — title, note, simple 4-level priority (advanced importance/urgency
  dials optional), due date/time, reminders, tags, contexts, star.
- **Reminders** — local exact alarms + notifications; rescheduled after reboot.
- **Backup** — Settings → Export/Import: complete, versioned **JSON**, on-device (SAF).
- Material 3 theming: system/light/dark + Material You dynamic color.

Architecture: Compose UI → `AppViewModel` → pure-Kotlin domain (`PriorityEngine`,
`QuickAddParser`, `Backup`) → `AppRepository` → Room. Unit tests cover the domain logic.

## Getting an installable APK

### GitHub Actions (recommended, zero setup)
Every push touching `android/**` runs the **Android APK** workflow. Open the run →
**Artifacts** → download **`ToDoCompanion-release-apk`** → unzip → install `app-release.apk`.
You can also trigger it manually (Actions → Run workflow).

### Build locally
Requires JDK 17+ and the Android SDK (platform 34, build-tools 34.0.0):
```bash
cd android
./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
./gradlew testReleaseUnitTest
```

## Installing on your phone
Copy `app-release.apk` to the device, open it with a file manager, allow **"Install
unknown apps"** for that app once (a per-source toggle, not signing, not an account),
then tap install.

## Signing — you never sign anything
CI signs the APK. Add these repo secrets for a **stable** release key (so updates install
over the previous version): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`. Without them, builds are signed with the debug key (still installable).
Keystores are never committed (`*.jks`, `keystore.properties`, `local.properties` are ignored).

## App identity
- **applicationId:** `com.todocompanion.app` (stable, so updates install in place).
- **minSdk 26** (Android 8.0), **targetSdk 34**.

## Toolchain
AGP 8.5.2 · Gradle 8.9 · Kotlin 2.0.20 · Compose BOM 2024.09.02 · Room 2.6.1 ·
kotlinx-serialization · Navigation-Compose.
