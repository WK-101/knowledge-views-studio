package com.todocompanion.app.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Wave N — the living two-way `.md` mirror. Turns Kairo's one-shot folder export/import into a working
 * copy you can also edit in Obsidian / sync with Syncthing: on each sync we compare, per note, a stored
 * baseline hash (from the last sync) against the current on-disk file and the current in-app note, and
 * decide whether to push, pull, or raise a conflict — never last-writer-wins.
 *
 * Pure (no Android): the hashing is over a CANONICAL projection of user-meaningful content (title, body,
 * tags, kind, flags, colour, emoji) — deliberately excluding volatile timestamps — so a plain re-save
 * doesn't look like a change. The baseline map lives in a folder-local `.kairo/mirror.json`, so the
 * exported folder is self-describing and carries its own sync state (printnotes' idea), with no DB
 * migration. Only the file I/O and the conflict UI live outside this object.
 */
object NoteMirror {

    /** What to do with one note on this sync. */
    enum class Action { NONE, PUSH, PULL, CONFLICT, NEW_LOCAL, NEW_DB }

    /** One note's last-synced state, persisted in `.kairo/mirror.json`. */
    @Serializable data class Base(val fileName: String, val hash: String)
    @Serializable data class Baseline(val notes: MutableMap<String, Base> = mutableMapOf())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun encodeBaseline(b: Baseline): String = json.encodeToString(Baseline.serializer(), b)
    fun decodeBaseline(s: String?): Baseline =
        if (s.isNullOrBlank()) Baseline() else runCatching { json.decodeFromString(Baseline.serializer(), s) }.getOrDefault(Baseline())

    /** A canonical, timestamp-free string of a note's user-meaningful content — hashed on both sides so
     *  formatting/metadata churn never registers as a real change. */
    fun canonical(
        title: String, body: String, tags: List<String>, kind: String,
        pinned: Boolean, favorite: Boolean, colorArgb: Long?, emoji: String?,
    ): String = buildString {
        append(title.trim()); append('')
        append(body); append('')
        append(tags.map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(",")); append('')
        append(kind); append('')
        append(pinned); append('|'); append(favorite); append('|')
        append(colorArgb?.toString() ?: ""); append('|'); append(emoji ?: "")
    }

    fun hash(canonical: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /**
     * Decide one note's fate. [baseline] is the hash at last sync (null = never synced), [disk] the
     * current on-disk hash (null = no file), [db] the current in-app hash (null = no such note).
     *
     * Deletions are treated conservatively — a missing file re-exports the note (we never delete a note
     * because a file vanished), and a note deleted in-app while its file is unchanged is left alone
     * (we never resurrect or delete the user's file automatically).
     */
    fun reconcile(baseline: String?, disk: String?, db: String?): Action {
        if (disk != null && db != null && disk == db) return Action.NONE       // already identical
        if (baseline == null) return when {
            disk != null && db == null -> Action.NEW_LOCAL                      // a file with no note yet → import
            db != null && disk == null -> Action.NEW_DB                         // a note not yet on disk → export
            disk != null && db != null -> Action.CONFLICT                       // both exist and differ, no shared history
            else -> Action.NONE
        }
        val diskChanged = disk != null && disk != baseline
        val dbChanged = db != null && db != baseline
        return when {
            disk == null && db != null -> Action.PUSH                           // file removed externally → re-export, keep the note
            db == null && disk != null -> if (diskChanged) Action.PULL else Action.NONE // note gone; import only if the file itself moved on
            diskChanged && dbChanged -> Action.CONFLICT
            diskChanged -> Action.PULL
            dbChanged -> Action.PUSH
            else -> Action.NONE
        }
    }
}
