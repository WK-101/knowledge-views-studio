# Obliviate

**Overwrite the ghosts of deleted files.**

Obliviate is a modern, non‑root Android app for storage hygiene: it overwrites
free space so previously‑deleted files can't be recovered by ordinary software,
shreds individual files, and cleans junk — while being **honest** about what is
and isn't achievable on flash storage.

Built with Kotlin + Jetpack Compose (Material 3). Ships as a sideloadable,
signed APK — no Play Store or root required.

---

## Why "honest"?

Most "military‑grade eraser" apps oversell themselves. Here's the reality this
app is designed around:

- **Deleting a file doesn't erase its bytes.** The OS just drops the pointer;
  the data lingers until overwritten. Overwriting free space defeats ordinary
  "undelete"/file‑carving recovery.
- **On phone flash (NAND), overwriting is best‑effort.** The Flash Translation
  Layer does *wear‑leveling* and keeps hidden spare cells, so an overwrite may
  not land on the same physical cells that held the old data. No unrooted app
  can guarantee every physical trace is gone.
- **The genuinely reliable erase is a factory reset (cryptographic erase).**
  Android encrypts by default; a reset destroys the key, making all data
  mathematically unreadable. NIST SP 800‑88 treats key‑destruction as the
  dependable method for device disposal.

The in‑app **About** screen explains all of this to the user in plain language.

## Features

| Feature | What it does | Permissions |
|---|---|---|
| **Wipe free space** | Fills the accessible free space of the internal (`/data`) or shared volume with random/zero data, `fsync`s it to the storage chip, then releases it. Runs in a foreground service with a progress notification; cancellable; keeps 300 MB free for stability. | None (uses the app's own storage area). Notification prompt on Android 13+. |
| **Shred files** | Pick specific files via the system file picker, overwrite their bytes (1‑pass random / zero / 3‑pass DoD), then delete. | None broad — Storage Access Framework grants access only to chosen files. |
| **Clean junk** | Clears the app's own caches, shows an internal/shared storage breakdown, and optionally scans shared storage for clearly‑disposable junk (`.tmp`, `.log`, `.part`, empty folders). | Cache/breakdown: none. Full junk scan: opt‑in "All files access". |
| **About** | Transparent explanation of flash limitations, scoped storage, and the factory‑reset/crypto‑erase recommendation. | — |

## Tech stack & architecture

- **Kotlin 2.0**, **Jetpack Compose**, **Material 3** (dynamic color + dark mode)
- **AGP 8.6**, Gradle 8.14, `minSdk 26`, `targetSdk 34`
- Coroutines/Flow; a **foreground `Service`** (`dataSync`) with a wake lock for
  long wipes; state shared to the UI via a `StateFlow`.

```
app/src/main/java/com/obliviate/app/
├── ObliviateApp.kt            # Application (notification channel)
├── MainActivity.kt            # single activity, edge-to-edge Compose host
├── core/
│   ├── FileExt.kt             # byte/speed/duration formatting, tree helpers
│   ├── wipe/
│   │   ├── WipeModels.kt      # targets, methods, config, progress, UI state
│   │   ├── FreeSpaceWiper.kt  # the free-space overwrite engine
│   │   └── ShredEngine.kt     # SAF overwrite-then-delete
│   ├── clean/JunkCleaner.kt   # cache clear, storage stats, junk scan
│   └── service/WipeService.kt # foreground service + progress notification
└── ui/
    ├── theme/                 # Color / Type / Theme (Material 3)
    ├── components/            # gauge, cards, banners, stat rows
    ├── ObliviateRoot.kt       # Scaffold + bottom nav + NavHost
    └── screens/               # Home, Wipe, Shred (+VM), Clean (+VM), About
```

## Build

```bash
# Point the build at your Android SDK (or set ANDROID_HOME):
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# Debug build (auto-signed with the debug key):
./gradlew :app:assembleDebug

# Release build:
#   Optional — create keystore.properties for a stable release key:
#     storeFile=obliviate-release.jks
#     storePassword=...
#     keyAlias=...
#     keyPassword=...
#   (If absent, the release build falls back to the debug signing key.)
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

## Install (sideload)

1. Copy the APK to your phone.
2. Settings → Apps → Special access → **Install unknown apps** → allow your
   file manager / browser.
3. Tap the APK to install.

> The APK is self‑signed for sideloading, not Play‑signed. To publish on Google
> Play you would enroll in Play App Signing and pass the All‑files‑access review
> for the optional junk scanner.

## Limitations (read the About screen)

- Free‑space wiping is **best‑effort on flash** — see "Why honest?" above.
- A non‑root app cannot reach the raw storage chip, other apps' private data, or
  system partitions.
- For disposing of a device: run a free‑space wipe, **then factory reset**.

## Research references

- NIST SP 800‑88 Rev. 2 — Media Sanitization (2025): factory reset = "Clear",
  cryptographic erase = "Purge".
- *In Search of Lost Data: A Study of Flash Sanitization Practices* (arXiv
  2505.14067): file deletion, free‑space overwriting, and TRIM are unreliable on
  flash; device‑level crypto erase is the dependable method.
- Android scoped storage (`source.android.com/docs/core/storage/scoped`) and the
  Play "All files access" policy.
- Prior art: Extirpater (F‑Droid, open source), iShredder, Shreddit.

## License

MIT (see repository root).
