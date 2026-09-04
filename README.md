# Cairn

**One reader for everything you read.** A privacy-first, offline-first Android app to
**capture** articles from anywhere, **read** them in a clean native reader, and
**archive** them with tags, collections, and full-text search — no account, no
trackers, no cloud.

> Working codename. This is a fresh project, currently developed on an isolated branch
> until it moves to a standalone repository.

## Status — v0.1 (in progress)

Foundation is in place and builds:

- Native **Kotlin + Jetpack Compose**, Material 3 design system (brand palette +
  dynamic color, reading-first type scale).
- **Hilt** DI and **WorkManager** wiring for background work.
- Edge-to-edge navigation shell: **Inbox · Library · Settings**.
- Adaptive launcher icon; privacy-preserving backup rules (nothing leaves the device).

Next: Room data layer (`Item`/`ItemState` + FTS), the feed parse → Readability
extraction → index pipeline, native offline reader, and share targets.

## Architecture

Single-module, cleanly layered:

```
com.cairn.reader
├─ data/      Room DB, DAOs, FTS, on-disk blob store, repositories
├─ domain/    feed parsing + discovery, Readability extraction, sync
├─ work/      WorkManager: sync → extract → index
├─ ui/        Compose screens, native reader (zero WebView), theme
└─ di/        Hilt modules
```

Design decisions and the research behind them are documented in the project plan
(three-part teardown of 13 read-it-all apps, two by APK decompile).

## Build

Requires JDK 17+ and the Android SDK (compileSdk 36).

```bash
./gradlew :app:assembleDebug     # debug APK → app/build/outputs/apk/debug/
```

CI (GitHub Actions) builds the debug APK on every push; tagging `v*` builds a signed
release APK and attaches it to a GitHub Release (requires signing secrets).

### Toolchain

AGP 8.13.2 · Kotlin 2.0.21 · Gradle 8.14.3 · compileSdk 36 · minSdk 26 (Android 8+).

## Privacy

No analytics, no ads, no account, no cloud AI. The app talks only to the feeds and
pages you add. Everything you save is stored locally and readable offline.

## License

TBD.
