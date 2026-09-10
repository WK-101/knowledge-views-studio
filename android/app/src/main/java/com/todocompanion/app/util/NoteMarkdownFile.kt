package com.todocompanion.app.util

import com.todocompanion.app.data.entity.NoteEntity

/**
 * Wave K — one `.md` file per note, with a YAML front-matter header (the Obsidian / Bear / Foam
 * convention). This is the *interop* layer: a note leaves the app as a plain Markdown file any editor
 * can open, and a folder of such files can be read back in — with the note's identity and metadata
 * preserved, so an export → edit-elsewhere → import round-trip merges by id instead of duplicating.
 *
 * The body is written and read back **byte-for-byte** (we never re-render or re-parse the Markdown),
 * and the header uses a deliberately tiny, deterministic YAML subset we both emit and read: every
 * string is double-quoted with `\`, `"` and newlines escaped, so any title/tag round-trips exactly
 * regardless of punctuation. A hand-dropped `.md` with no header still imports — title comes from the
 * first `# ` heading, else the file name. Pure Kotlin: no Android, so it unit-tests cleanly.
 */
object NoteMarkdownFile {

    /** The fields we can recover from a `.md` file. Unknown/absent keys fall back to sane defaults. */
    data class Parsed(
        val id: String?,
        val title: String,
        val body: String,
        val tags: List<String>,
        val kind: String,
        val pinned: Boolean,
        val favorite: Boolean,
        val colorArgb: Long?,
        val coverEmoji: String?,
        val createdAt: Long?,
        val updatedAt: Long?,
        val dayEpoch: Long?,
    )

    private const val FENCE = "---"

    // ── Emit ──────────────────────────────────────────────────────────────────

    /** Serialize [note] (+ its resolved [tagNames]) to a front-matter Markdown document. */
    fun serialize(note: NoteEntity, tagNames: List<String> = emptyList()): String = buildString {
        append(FENCE).append('\n')
        line("id", q(note.id))
        line("title", q(note.title))
        if (tagNames.isNotEmpty()) line("tags", tagNames.joinToString(", ", "[", "]") { q(it) })
        line("kind", q(note.kind))
        if (note.pinned) line("pinned", "true")
        if (note.favorite) line("favorite", "true")
        note.colorArgb?.let { line("color", it.toString()) }
        note.coverEmoji?.takeIf { it.isNotBlank() }?.let { line("emoji", q(it)) }
        if (note.createdAt > 0L) line("created", note.createdAt.toString())
        if (note.updatedAt > 0L) line("updated", note.updatedAt.toString())
        note.dayEpoch?.let { line("day", it.toString()) }
        append(FENCE).append('\n')
        append('\n')
        append(note.body)
    }

    private fun StringBuilder.line(key: String, value: String) {
        append(key).append(": ").append(value).append('\n')
    }

    // ── Parse ─────────────────────────────────────────────────────────────────

    fun parse(fileName: String, content: String): Parsed {
        val normalized = content.replace("\r\n", "\n")
        if (!normalized.startsWith("$FENCE\n") && normalized != FENCE) {
            return bare(fileName, content)   // no header — treat the whole file as body
        }
        // Find the closing fence: a line that is exactly "---" after the opening one.
        val afterOpen = normalized.indexOf('\n') + 1
        val closeIdx = findClosingFence(normalized, afterOpen)
        if (closeIdx < 0) return bare(fileName, content)

        val header = normalized.substring(afterOpen, closeIdx)
        // Body starts after the closing fence line's newline; drop exactly one blank separator line.
        var bodyStart = normalized.indexOf('\n', closeIdx)
        bodyStart = if (bodyStart < 0) normalized.length else bodyStart + 1
        if (bodyStart < normalized.length && normalized[bodyStart] == '\n') bodyStart += 1
        val body = if (bodyStart >= normalized.length) "" else normalized.substring(bodyStart)

        val map = HashMap<String, String>()
        for (raw in header.split('\n')) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            val sep = t.indexOf(':')
            if (sep <= 0) continue
            map[t.substring(0, sep).trim()] = t.substring(sep + 1).trim()
        }
        val title = map["title"]?.let(::unq).orEmpty()
        return Parsed(
            id = map["id"]?.let(::unq)?.takeIf { it.isNotBlank() },
            title = title.ifBlank { titleFromBody(body) ?: nameToTitle(fileName) },
            body = body,
            tags = map["tags"]?.let(::parseFlowList).orEmpty(),
            kind = map["kind"]?.let(::unq)?.takeIf { it.isNotBlank() } ?: "note",
            pinned = map["pinned"].toBoolean(),
            favorite = map["favorite"].toBoolean(),
            colorArgb = map["color"]?.trim()?.toLongOrNull(),
            coverEmoji = map["emoji"]?.let(::unq)?.takeIf { it.isNotBlank() },
            createdAt = map["created"]?.trim()?.toLongOrNull(),
            updatedAt = map["updated"]?.trim()?.toLongOrNull(),
            dayEpoch = map["day"]?.trim()?.toLongOrNull(),
        )
    }

    private fun findClosingFence(s: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            val end = s.indexOf('\n', i).let { if (it < 0) s.length else it }
            if (s.substring(i, end).trim() == FENCE) return i
            i = end + 1
        }
        return -1
    }

    private fun bare(fileName: String, content: String): Parsed {
        val body = content.replace("\r\n", "\n")
        return Parsed(
            id = null,
            title = titleFromBody(body) ?: nameToTitle(fileName),
            body = body,
            tags = emptyList(), kind = "note", pinned = false, favorite = false,
            colorArgb = null, coverEmoji = null, createdAt = null, updatedAt = null, dayEpoch = null,
        )
    }

    private fun titleFromBody(body: String): String? =
        body.lineSequence().firstOrNull { it.isNotBlank() }
            ?.takeIf { it.trimStart().startsWith("# ") }
            ?.trimStart()?.removePrefix("# ")?.trim()?.takeIf { it.isNotBlank() }

    private fun nameToTitle(fileName: String): String =
        fileName.substringAfterLast('/').removeSuffix(".md").removeSuffix(".markdown")
            .replace('-', ' ').replace('_', ' ').trim()

    // ── A file name for a note (sanitized title, collision-free within a batch) ──

    private val illegal = Regex("[/\\\\:*?\"<>|\\u0000-\\u001F]")

    fun fileName(note: NoteEntity, taken: MutableSet<String>): String {
        val base = illegal.replace(note.title, " ").trim().take(60).ifBlank { "note" }
        var name = "$base.md"
        var n = 2
        while (!taken.add(name.lowercase())) { name = "$base ($n).md"; n++ }
        return name
    }

    // ── Minimal YAML scalar codec (only what serialize emits) ──

    private fun q(s: String): String = buildString {
        append('"')
        for (ch in s) when (ch) {
            '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n")
            '\t' -> append("\\t"); '\r' -> append("\\r")
            else -> append(ch)
        }
        append('"')
    }

    /** Reverse of [q]; tolerant of an unquoted hand-typed scalar. */
    private fun unq(raw: String): String {
        val s = raw.trim()
        if (s.length < 2 || s.first() != '"') return s
        val out = StringBuilder()
        var i = 1
        while (i < s.length) {
            val c = s[i]
            if (c == '"') break
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
                    '"' -> out.append('"'); '\\' -> out.append('\\'); else -> out.append(s[i + 1])
                }
                i += 2
            } else { out.append(c); i++ }
        }
        return out.toString()
    }

    /** Parse a flow list `["a", "b"]` (or a bare comma list) of quoted scalars. */
    private fun parseFlowList(raw: String): List<String> {
        val inner = raw.trim().removePrefix("[").removeSuffix("]")
        if (inner.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            when {
                c == '"' -> { sb.append(c); inQuote = !inQuote }
                c == '\\' && inQuote && i + 1 < inner.length -> { sb.append(c).append(inner[i + 1]); i++ }
                c == ',' && !inQuote -> { out.add(unq(sb.toString())); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotBlank()) out.add(unq(sb.toString()))
        return out.map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun String?.toBoolean(): Boolean = this?.trim().equals("true", ignoreCase = true)
}
