# TaskTree (Android)

A private, fully-offline task manager for Android — MyLifeOrganized-style outlining
with a TickTick-grade UI. Native **Kotlin + Jetpack Compose (Material 3)**, local
storage only, **no account, no network permission**.

> Status: **skeleton** — this stage exists to prove the direct-install APK pipeline
> end to end (build → sign → install → update). Real data model and features follow.

## Privacy by construction

The app declares **no `INTERNET` permission** in its manifest, so it is incapable of
network access. The only permission present is an AndroidX-internal, signature-level
self-permission (`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`) that grants nothing
network- or data-related.

## Getting an installable APK

### Option A — GitHub Actions (recommended, zero local setup)

Every push that touches `android/**` runs the **Android APK** workflow
(`.github/workflows/android.yml`). Open the run → **Artifacts** → download
**`TaskTree-release-apk`**, unzip, and transfer `app-release.apk` to your phone.

You can also trigger it manually from the Actions tab (**Run workflow**).

### Option B — Build locally

Requires JDK 17+ and the Android SDK (platform 34, build-tools 34.0.0). With a
`keystore.properties` present (see below) the release build is signed with your key;
without it, it falls back to the debug key.

```bash
cd android
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

## Installing on your phone

1. Copy `app-release.apk` to the device (USB, cloud drive, etc.).
2. Open it with a file manager. Android will ask to allow **"Install unknown apps"**
   for that app — enable it once. This is a per-source toggle, **not** signing and
   **not** an account.
3. Tap install.

## Signing — you never sign anything

Signing is automated. There are two modes:

- **Debug-key fallback (default, no setup):** builds are installable immediately, but
  each build has a different signature, so a new version won't install *over* an old
  one — you'd uninstall first.
- **Stable release key (recommended):** sign every build with one fixed key so new
  versions install *over* the previous one and your data is kept. To enable, add these
  **repository secrets** (Settings → Secrets and variables → Actions):

  | Secret | Value |
  | --- | --- |
  | `KEYSTORE_BASE64` | base64 of `app-signing.jks` |
  | `KEYSTORE_PASSWORD` | keystore password |
  | `KEY_ALIAS` | `tasktree` |
  | `KEY_PASSWORD` | key password |

The keystore itself is **never committed** (`*.jks`, `keystore.properties`, and
`local.properties` are git-ignored).

## App identity

- **applicationId:** `com.tasktree.app` (kept stable so updates install in place; the
  display name/label can change freely).
- **minSdk 26** (Android 8.0), **targetSdk 34**.

## Toolchain

AGP 8.5.2 · Gradle 8.9 · Kotlin 2.0.20 · Compose BOM 2024.09.02 · Material 3.
