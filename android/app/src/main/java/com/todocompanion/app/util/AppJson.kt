package com.todocompanion.app.util

import kotlinx.serialization.json.Json

/**
 * R108 audit C3 — the one [Json] configured the way ~30 call sites across the domain and repository
 * layers each configured a private copy: tolerate unknown keys (so a blob written by a newer build,
 * or an older one missing a field added later, still parses) and encode defaults (so a value that
 * happens to equal its default is still written, which the lossless backup relies on).
 *
 * kotlinx.serialization's [Json] is immutable and thread-safe once built, so a single shared instance
 * is safe to use everywhere and removes ~30 redundant serializer modules from the app. Behaviour is
 * byte-for-byte identical to the per-object builders it replaces.
 *
 * Sites that genuinely need a DIFFERENT shape keep their own builder on purpose and must NOT be pointed
 * here: the backup writer (compact, `encodeDefaults = false` — see domain/port/Backup.kt) and the
 * Markdown file-interop writers (`prettyPrint` — util/NoteMirror, util/NoteExport). Read-only decoders
 * configured with `ignoreUnknownKeys` alone are functionally covered by this instance, but are left as
 * they are where switching them over buys nothing.
 */
val AppJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
