# Kairo (Android)

A private, fully-offline task · habit · time · calendar manager — MyLifeOrganized-style outlining with a
TickTick-grade UI, plus habit-building, time-tracking, a dedicated calendar, and a "life systems" layer.
Native **Kotlin + Jetpack Compose (Material 3)**, **Room/SQLite encrypted with SQLCipher**, **no account,
no network permission**, everything free, lossless JSON export/import.

- **Package:** `com.wkhan.kairo` (the code namespace stays `com.todocompanion.app`, so class names, the
  `R` class and `FileProvider` authorities are unchanged).
- **Security & privacy threat model:** [`docs/SECURITY.md`](docs/SECURITY.md)
- **Accessibility & contrast audit:** [`docs/ACCESSIBILITY.md`](docs/ACCESSIBILITY.md)

## Privacy by construction

The app declares **no `INTERNET` permission**, so it physically cannot access the network — there is no
HTTP client, no analytics, no telemetry, no crash-reporting-to-cloud. The only permissions are local:
`POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `VIBRATE`,
`USE_FULL_SCREEN_INTENT`, `ACCESS_NOTIFICATION_POLICY`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — all for
on-device reminders. No location, no broad storage (attachments/backup/import use the Storage Access
Framework). Data at rest is encrypted with SQLCipher (AES-256), the key wrapped by the Android KeyStore.
See the threat model for the full analysis.

## What's in it

- **Tasks** — nested outlining, smart lists (Inbox, Today, Tomorrow, Next 7 Days, Do Next, Scheduled,
  Flagged, Waiting-on, All, Completed, Won't Do, Trash), folders & lists, tags & contexts, natural-language
  quick-add, deadlines distinct from due dates, recurrence, checklists, drag-to-nest.
- **Habits** — flexible schedules, numeric/timed goals, streaks & never-miss-twice, matrix & trends,
  habit-building journeys.
- **Focus & Time** — Pomodoro focus linked to tasks, unified time-tracking on one timeline, time goals and
  reports.
- **Calendar** — List · Day · 3-Day · Week · Month · Year, drag-to-reschedule, editable time blocks,
  events with recurrence/alerts, ICS import/export.
- **Eisenhower Matrix**, **Do Next** computed ranking, **workload forecast**, **life-systems** layer, an
  end-of-day review, home-screen **widgets** and **Quick-Settings tiles**.
- **Import** from Todoist / TickTick / MyLifeOrganized; **export** to JSON (lossless backup) and
  Markdown / CSV / iCalendar (shareable, with optional per-field note redaction).
- **Backup & sync** to a user-chosen folder (encrypted at rest), account-free merge sync.

Architecture: Compose UI → `AppViewModel` + focused pure-Kotlin domain modules (`ListPipeline`,
`PriorityEngine`, `QuickAddParser`, `DoNext`, `TimeReports`, `EntryCounts`, `SmartCounts`, `Backup`, …) →
`AppRepository` → Room. The domain modules are UI-free and unit-tested; the schema is exported.

## Quality gates

- **Tests:** 239, all on the JVM — domain unit tests → Robolectric Room DAO / repository / backup
  round-trip → Compose-UI semantics (accessibility labels + contrast). One instrumented migration test.
- **Lint:** `abortOnError` on; `lintRelease` is a CI gate, so an Error-severity finding fails the build
  before an APK is produced.
- **Release build:** R8 **code + resource shrinking** on (obfuscation off, so stack traces stay readable),
  a bundled baseline profile for the cold-start path.

## Getting an installable APK

### GitHub Actions (recommended, zero setup)
Every push touching `android/**` runs the **Android APK** workflow (full test suite + `lintRelease`, then
the signed, minified release). Open the run → **Artifacts** → download the release APK → unzip → install.

### Build locally
Requires JDK 17+ and the Android SDK (platform 35, build-tools 35.0.0):
```bash
cd android
./gradlew clean testDebugUnitTest lintRelease assembleRelease
# APK -> app/build/outputs/apk/release/app-release.apk
```
The build is deterministic given a fixed toolchain: versions are pinned (see below), the release variant is
the same shrunk, signed artifact locally and in CI, and no step reaches the network at assemble time. To
confirm the privacy invariant on the produced APK:
```bash
aapt dump xmltree app-release.apk AndroidManifest.xml | grep uses-permission
# expect: no INTERNET, no *_LOCATION, no *_EXTERNAL_STORAGE
```

## Installing on your phone
Copy `app-release.apk` to the device, open it with a file manager, allow **"Install unknown apps"** for
that app once (a per-source toggle — not signing, not an account), then tap install.

## Signing
CI signs the APK. Add these repo secrets for a **stable** release key (so updates install over the previous
version): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Without them, builds are
signed with the debug key (still installable). Keystores are never committed (`*.jks`,
`keystore.properties`, `local.properties` are git-ignored).

## Distribution status
Release-ready: shrunk & signed, `minSdk 26` (~98% of devices), `targetSdk 35` (Android 15, edge-to-edge
verified on device), F-Droid/fastlane store metadata committed under `fastlane/metadata/`. Not yet
published to a store — that is a deliberate, separate step, not a build gap.

## App identity
- **applicationId:** `com.wkhan.kairo` (stable, so updates install in place).
- **minSdk 26** (Android 8.0), **targetSdk 35**, **compileSdk 35** (Android 15).

## Toolchain
AGP 8.7.3 · Gradle 8.9 · Kotlin 2.0.20 · KSP 2.0.20-1.0.24 · Compose BOM 2024.12.01 (Compose 1.7.6) ·
Room 2.6.1 · SQLCipher 4.5.4 · kotlinx-serialization · Navigation-Compose · Robolectric 4.14.1.
