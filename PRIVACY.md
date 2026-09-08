# Cairn — Privacy & Security Model

Cairn is designed to be private by construction. This document states honestly what that
means — including current limitations — so the guarantees are real, not marketing.

## What Cairn never does

- **No account, no Cairn server.** There is nothing to sign into and no backend to phone home
  to. The app has no analytics, ads, crash-reporting SDK, or cloud AI.
- **No tracking.** Tracking/campaign parameters (`utm_*`, `fbclid`, …) are stripped from links
  it stores, opens, and shares. Saved article HTML is sanitized — scripts, inline event
  handlers, tracking pixels, and beacons are removed before storage.
- **Minimal permissions.** Internet, network-state, notifications, boot-completed, and
  foreground-service (media playback). No location, contacts, or broad storage access.

## Network activity

Cairn contacts only:
- **The feeds and pages you add** — to sync and to extract article text.
- **Opt-in / user-initiated third parties**, each disclosed:
  - *Broken-link checking* (contacts publishers to detect link rot) — **off by default**,
    enabled in Settings.
  - *Dictionary lookups* (`api.dictionaryapi.dev`) — only when you tap "Define".
  - *Wayback healing* (`archive.org`) — only when you ask to heal a broken link.

All app traffic pins trust to the system CA store (user-installed certificates are not
trusted). WebDAV backup requires an `https://` address.

## Data at rest

- **The library database is encrypted on disk** with SQLCipher (AES-256), keyed by a random
  passphrase held in the Android Keystore — the key never leaves the keystore and is not backed
  up. A pre-existing plaintext library is migrated to an encrypted copy fail-safe: the plaintext
  original is removed only once the encrypted copy verifies, and if that migration can't complete
  in a given session the app opens the existing database rather than risk any data loss.
- **Cached article bodies and images** (your offline copies, stored as files) and **settings**
  (DataStore) live in the app's private storage, protected by the Android app sandbox and
  file-based encryption while the device is locked. They are not additionally app-encrypted, so a
  rooted or forensically-imaged device should be treated as able to read them.
- **The WebDAV password is encrypted** with an AES-256-GCM key held in the Android Keystore
  (the key never leaves the keystore and is not backed up), and it is **never included in an
  exported backup** — it is re-entered on restore.
- **Google cloud auto-backup and device-transfer are disabled** for all app data (see
  `backup_rules.xml` / `data_extraction_rules.xml`); you export your own local archive
  explicitly.

## Your data, forever

Everything Cairn holds can leave it, in open formats you control:
- Full **JSON** data backup and a full **`.zip`** archive (data + offline copies).
- Scheduled backup to a **local folder** or your own **WebDAV / Nextcloud** server.
- Account-free **device-to-device transfer**.
- **Markdown / Obsidian vault** export (per article or the whole library).
- **EPUB** (send-to-Kindle) and self-contained **HTML snapshots**.

## Diagnostics

When something fails, Cairn records it to Logcat and a small rotating log file in its own
private storage (`util/AppLog`). That log stays on your device and is never uploaded.

*Last reviewed: as of app version 3.96.x.*
