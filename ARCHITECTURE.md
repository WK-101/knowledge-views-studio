# Cairn — Architecture

A single-module Android app, layered `ui → domain → data`, with Hilt wiring the graph.

## Layers

### `data/`
The source of truth. Room database (`CairnDatabase`, schema v14 with 13 checked-in,
additive migrations under `app/schemas/`) plus:
- **DAOs** — `ItemDao`, `SourceDao`, `HighlightDao`, `TagDao`, `CollectionDao`, `RuleDao`,
  `InsightsDao`, `SyncDao`. List screens read flat JOIN projections (`ItemListRow`, …); no
  N+1. FTS (`ItemFtsEntity`) backs full-text search. Shared list-column SQL is centralised in
  `const` fragments so the ~20 list queries can't drift apart.
- **At-rest encryption** — `db/DbCrypto` opens the database through **SQLCipher** with a key
  held in the Android Keystore (`util/SecretStore`). A fresh install is created encrypted; a
  legacy plaintext library is migrated to an encrypted copy on first launch, fail-safe (the
  original is removed only after the copy verifies). This one-time migration is warmed off the
  main thread from `CairnApplication` (`dagger.Lazy<CairnDatabase>` opened on `Dispatchers.IO`).
- **Typed status vocabularies** — `db/ItemType` and `db/ItemStatus` (`ExtractStatus`,
  `CacheStatus`, `ContentSource`, `LinkStatus`) wrap the small stored status columns so Kotlin
  comparisons go through enums, not string literals; the columns stay `String` for migration-free
  additive values, and SQL `@Query` strings keep the literal the enum documents.
- **Repositories** — one per concern: `ItemRepository`, `FeedRepository`, `SourceRepository`,
  `HighlightRepository`, `CollectionRepository`, `TagRepository`, `SemanticRepository`,
  `RuleRepository`, `InsightsRepository`. Constructor-injected and fakeable.
- **`blob/BlobStore`** — gzipped article bodies, cached images, imported PDFs on disk.
- **`prefs/PreferencesRepository`** — DataStore-backed settings (one `AppPreferences` +
  typed setters, JSON export/import). The WebDAV secret is Keystore-encrypted via
  `util/SecretStore`.
- **`backup/`** — JSON + full `.zip` archive export/import, WebDAV mirror.
- **`net/`** — OkHttp `HttpFetcher`, `WebDavClient` (HTTPS-only), `UrlCleaner`.
- **`export/`** — `MarkdownExportManager`, `EbookExportManager` (orchestrate the pure
  exporters in `domain/export`).

### `domain/`
Framework-light logic, mostly pure and unit-testable:
- **`extract/ArticleExtractor`** — Readability4J extraction (suspend, `Dispatchers.Default`).
- **`feed/`** — `FeedParser` (XML/JSON), `FeedDiscovery`, `SiteFeedBuilder` (the no-RSS
  collector chain).
- **`review/`** — `Sm2` (spaced-repetition scheduler) + `Cloze` (deterministic fill-in-blank).
- **`export/`** — `MarkdownExporter`, `EpubExporter`, `HtmlSnapshotExporter` (pure).
- **`summary/Summarizer`** (TextRank), **`semantic/`** (TF-IDF/cosine), **`privacy/
  ContentSanitizer`**, **`render/WebViewRenderer`** (offscreen JS render fallback, hardened).

### `ui/`
Jetpack Compose, Material 3. One shared shell (`CairnApp`) hosts every destination as an
in-place pane (drawer + bottom bar + one transition language); `CairnRoot` owns the NavHost
and the reader route. State flows via `StateFlow` + `collectAsStateWithLifecycle` in a
unidirectional pattern. `ui/theme/` holds the tokenised light/dark schemes, 12 accents,
dynamic colour, and the Inter/Newsreader type scale. The reader (`ui/reader/`) renders
sanitized article HTML as native Compose blocks — **no WebView**.

The largest screens are kept legible by splitting one screen into a thin composable plus
sibling files in the same package: e.g. `ReaderScreen` + `ReaderSheets`, `SettingsScreen` +
`SettingsSections` + `SettingsComponents`, `LibraryScreen` + `LibrarySheets`. The screen file
orchestrates; the sibling holds the section/sheet/dialog composables (`internal`, so only the
screen calls them). Numeric/duration/choice inputs use one control per category — preset
`FilterChip`s for discrete values, sliders only for continuous fine-tuning. Section headers
carry `heading()` semantics for TalkBack; every interactive icon carries a `contentDescription`.

### `work/`
`WorkManager` coroutine workers (Hilt-injected): periodic sync → extract → index, and
scheduled backup. Constraints honour the user's Wi-Fi/charging/interval preferences.

### `util/`
`AppLog` (Logcat + on-device rotating diagnostics log, `Result.orLog {}` helpers),
`SecretStore` (Keystore AES-GCM), `reduceMotion()`.

## Cross-cutting principles

- **On-device only, offline by default.** No app server, no telemetry. Network I/O touches only
  user-added feeds/pages plus a few clearly-disclosed third parties (dictionary, wayback,
  link-check) that are **off by default and opt-in** — the dictionary look-up is gated both in
  the UI and again in `ReaderViewModel.define`, so nothing selected leaves the device unless the
  user turns it on.
- **Encrypted at rest.** The whole database is SQLCipher-encrypted (Keystore-held key); the
  WebDAV secret is separately Keystore-encrypted.
- **Threading is owned low.** Network/DB never block the main thread; extraction runs on
  `Dispatchers.Default`; the one-time encryption migration is warmed on `Dispatchers.IO` at
  startup.
- **Fast start.** Per-ABI APK splits (~9 MB vs ~25 MB universal) and a bundled ART baseline
  profile (ProfileInstaller; `app/src/main/baseline-prof.txt` → `assets/dexopt/baseline.prof`)
  AOT-compile the hot startup/scroll paths.
- **Fail loud, locally.** Errors are logged, not swallowed; a missing DB migration crashes in
  dev/CI rather than wiping data.
- **Nothing locked in.** Every artifact can leave as JSON, a zip archive, Markdown, or EPUB.

## Testing

`app/src/test/` holds JVM + Robolectric unit tests for the deterministic core (SM-2, cloze,
exporters, URL cleaner, sanitizer, extractor, the TextRank summarizer, the item type/status
enums, playback-speed helpers) plus a Robolectric DataStore round-trip that proves the settings
backup export/import preserves every preference (including the `dictionaryOnline` opt-in). CI
runs `testDebugUnitTest` + `lintDebug` on every push. Instrumented Room-migration and Compose UI
tests are the planned next layer (need a device/emulator).
