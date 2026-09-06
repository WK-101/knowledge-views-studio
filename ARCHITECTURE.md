# Cairn — Architecture

A single-module Android app, layered `ui → domain → data`, with Hilt wiring the graph.

## Layers

### `data/`
The source of truth. Room database (`CairnDatabase`, schema v14 with 13 checked-in,
additive migrations under `app/schemas/`) plus:
- **DAOs** — `ItemDao`, `SourceDao`, `HighlightDao`, `TagDao`, `CollectionDao`, `RuleDao`,
  `InsightsDao`, `SyncDao`. List screens read flat JOIN projections (`ItemListRow`, …); no
  N+1. FTS (`ItemFtsEntity`) backs full-text search.
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

### `work/`
`WorkManager` coroutine workers (Hilt-injected): periodic sync → extract → index, and
scheduled backup. Constraints honour the user's Wi-Fi/charging/interval preferences.

### `util/`
`AppLog` (Logcat + on-device rotating diagnostics log, `Result.orLog {}` helpers),
`SecretStore` (Keystore AES-GCM), `reduceMotion()`.

## Cross-cutting principles

- **On-device only.** No app server, no telemetry. Network I/O touches only user-added
  feeds/pages plus a few clearly-disclosed, opt-in third parties (dictionary, wayback,
  link-check).
- **Threading is owned low.** Network/DB never block the main thread; extraction is
  dispatched off it.
- **Fail loud, locally.** Errors are logged, not swallowed; a missing DB migration crashes in
  dev/CI rather than wiping data.
- **Nothing locked in.** Every artifact can leave as JSON, a zip archive, Markdown, or EPUB.

## Testing

`app/src/test/` holds JVM unit tests for the deterministic core (SM-2, cloze, exporters,
URL cleaner, sanitizer, extractor). CI runs `testDebugUnitTest` + `lintDebug` on every push.
Instrumented Room-migration and Compose UI tests are the planned next layer (need a device).
