# Cairn

**One quiet, private home for everything you read — and it's yours forever.**

Cairn is a privacy-first, **100% on-device** Android app that combines three tools in one:
an **RSS/feed reader**, a **read-it-later** inbox, and a **Raindrop-style library** with
collections, nested tags, highlights, and full-text search. No account, no server, no
tracking — it talks only to the feeds and pages *you* add, and everything you save is
stored locally and readable offline.

- **Kotlin · Jetpack Compose · Material 3**
- **Hilt** DI · **Room** (v14, 13 migrations) · **WorkManager** · **DataStore**
- **Zero-WebView native reader** (article HTML is parsed and rendered as Compose)
- minSdk 26 (Android 8) · targetSdk/compileSdk 36

## Highlights

**Read** — clean native reader (no browser chrome), Readability extraction on-device,
bundled Inter + Newsreader typography, per-article display controls, immersive full-screen,
pinch-to-zoom, bionic reading, RSVP speed-reader, offline TTS read-aloud, next/previous
navigation, tablet/foldable two-pane layout.

**Capture** — subscribe to RSS/Atom/JSON feeds, YouTube channels, and podcasts; follow
sites that *have no feed* via an on-device collector (sitemap → hidden CMS APIs → JSON-LD →
heuristic scrape → teach-by-example selector → JS-rendered fallback); OPML import/export;
share any link to Cairn; import Pocket / Instapaper / Raindrop / browser bookmarks.

**Keep** — a storage-first library with nested collections and tags, multi-colour
highlights + notes, reading progress, smart views (Unsorted / Untagged / Broken /
Duplicates), permanent offline copies, PDF import/viewer, and full-text (FTS) search
across your whole archive.

**Smarter without AI** — deterministic, on-device intelligence only: TF-IDF/cosine semantic
search & clustering, TextRank extractive summaries, a daily Brief, focus scoring, a
time-budget triage deck, link-rot detection & healing, and **SM-2 spaced-repetition recall**
of your highlights.

**Your data, forever** — full local backup (JSON / zip archive), scheduled backup to a
folder or your own WebDAV/Nextcloud, account-free device-to-device transfer, **Markdown /
Obsidian vault export**, and **EPUB / send-to-Kindle** + full-page HTML snapshots. Nothing
is locked in.

## Architecture

Single-module, cleanly layered — see [`ARCHITECTURE.md`](ARCHITECTURE.md) for detail.

```
com.cairn.reader
├─ data/      Room DB + DAOs + FTS, on-disk blob store, repositories, backup, prefs, net
├─ domain/    feed parse + discovery, Readability extraction, review (SM-2/cloze),
│             export (Markdown/EPUB/snapshot), summary, semantic, privacy sanitizer
├─ work/      WorkManager: sync → extract → index, scheduled backup
├─ ui/        Compose screens, the zero-WebView reader, theme, components
├─ widget/    home-screen widget · notifications/  rich new-article notifications
├─ util/      AppLog (local diagnostics), SecretStore (Keystore), reduced-motion
└─ di/        Hilt modules
```

**Threading** — networking and DB run off the main thread (OkHttp on `Dispatchers.IO`,
Room suspend/Flow DAOs); CPU-heavy article extraction runs on `Dispatchers.Default`.

**Reliability** — failures are logged (Logcat + a small on-device rotating log via `AppLog`),
never silently swallowed. Room ships every migration; there is no destructive fallback.

## Build

Requires JDK 17 and the Android SDK (compileSdk 36).

```bash
./gradlew :app:assembleDebug        # debug APK → app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # JVM unit tests (deterministic core)
./gradlew :app:lintDebug            # Android lint (baseline in app/lint-baseline.xml)
./gradlew :app:assembleRelease      # R8-minified release (needs signing; see below)
```

Release builds are **R8-minified + resource-shrunk** (~3.7 MB APK) and are signed only when
a real key is provided via `keystore.properties` or `CAIRN_KEYSTORE_*` env vars — otherwise
the release is left unsigned (never debug-signed). CI (GitHub Actions) runs unit tests +
lint + debug build on every push, and builds a signed release on `v*` tags.

### Toolchain

AGP 8.13.2 · Kotlin 2.0.21 · compileSdk 36 · minSdk 26 (Android 8+).

## Privacy & security

Cairn is built to be private by construction — see [`PRIVACY.md`](PRIVACY.md) for the full,
honest model (including what is and isn't encrypted at rest).

- No analytics, ads, accounts, or cloud AI. Minimal permissions (internet, network-state,
  notifications, boot, foreground-service).
- The native reader uses **no WebView**; saved article HTML is sanitized (scripts, trackers,
  beacons stripped) before storage.
- The WebDAV password is encrypted at rest via the Android Keystore and is excluded from
  exported backups; WebDAV requires HTTPS.
- The one automatic feature that contacts third parties (broken-link checking) is **opt-in,
  off by default**.

## License

See [`LICENSE`](LICENSE).
