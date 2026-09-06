# Changelog

All notable changes are documented here. Dates are approximate; versions map to
`versionName` / `versionCode` in `app/build.gradle.kts`.

## 3.82.0 — Accessibility & UI polish
- RSVP speed-reader is now fully theme-aware (was hardcoded dark).
- Reduced-motion: honours the system "Remove animations" setting.
- Larger touch targets in the Library tree; bottom nav capped at 5 and made taller.
- Reader Star/Save now announce their state to TalkBack.

## 3.81.0 — Security at rest
- WebDAV password encrypted at rest via the Android Keystore; excluded from exported backups.
- WebDAV now requires HTTPS.
- Broken-link checking made opt-in (off by default) with a Settings toggle.

## 3.80.0 — Foundations (performance, reliability, build)
- Article extraction moved off the main thread; no `runBlocking` on cold start.
- New `AppLog` local diagnostics; failures are logged, not swallowed.
- Removed destructive DB migration fallback.
- R8 minify + resource shrinking enabled (APK ~15 MB → ~3.7 MB); lint + unit tests wired
  into CI; explicit network-security config; hardened headless WebView.

## 3.79.0 — "Your data, forever" headline
- Dedicated anti-shutdown panel surfacing every export/backup path; onboarding refresh.

## 3.78.0 — EPUB / send-to-Kindle + snapshots
- EPUB 3 export (single article or whole library) and self-contained HTML snapshots.

## 3.77.0 — Markdown / Obsidian export
- Per-article and whole-library Markdown export with YAML frontmatter, tags, and highlights.

## 3.76.0 — Offline spaced-repetition recall
- SM-2 scheduler + deterministic cloze turns highlights into recall cards; Review pane.

## Earlier (0.1 → 3.75)
Foundation and feature build-out: native zero-WebView reader, Raindrop-style library
(collections, nested tags, highlights, FTS), read-it-later inbox, the no-RSS feed collector,
Discover, PDF import/viewer, backup/restore + WebDAV + device transfer, on-device "smart
without AI" features (semantic search, TextRank summaries, daily Brief, focus scoring,
link-rot healing), theming (12 accents, dynamic colour, AMOLED), tablet two-pane, and
immersive reading. See git history for the full progression.

## Unreleased / planned
- Full internationalization (string externalization + additional locales).
- Transparent whole-database encryption (SQLCipher) — pending on-device migration QA.
- Instrumented Room-migration and Compose UI tests.
- God-file decomposition and a hardened domain-model boundary.
