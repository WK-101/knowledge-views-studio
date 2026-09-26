package app.parley.common.extras

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * C5: one Markdown file per person, for Obsidian and other note apps: YAML front-matter (name, phones, e-mails,
 * dates, labels) and then the pinned note, the contact's note, the Circle rhythm and the timeline of calls and
 * logged interactions. Export only: Parley never imports anything from these files. Each file carries a fingerprint of
 * itself ([MARKER], see [isUntouched]), so Parley can tell its own untouched files from ones the user edited, and
 * never overwrites or deletes an edited one.
 *
 * Texts that the reader sees (headings, kinds of entries) are passed in already translated ([Headings],
 * [Entry.kind]); keys, dates and numbers are fixed formats (Locale.ROOT) so the files stay machine-readable.
 */
object MarkdownNotes {
    data class Field(val label: String, val value: String)

    /** One timeline line: when, what ("Call · 4 min", "Met") and an optional note. */
    data class Entry(val time: Long, val kind: String, val note: String? = null)

    data class Person(
        val name: String,
        val phones: List<Field> = emptyList(),
        val emails: List<Field> = emptyList(),
        /** Dates as stored ("1990-05-02", or "--05-02" without a year). */
        val dates: List<Field> = emptyList(),
        val labels: List<String> = emptyList(),
        val company: String = "",
        val pinnedNote: String = "",
        val note: String = "",
        /** "Keep in touch every 30 days", already worded; null outside the Circle. */
        val keepInTouch: String? = null,
        val keepInTouchDays: Int? = null,
        val timeline: List<Entry> = emptyList(),
        /** R9 promises from all of this person's notes, as Markdown tasks (Obsidian renders them as checkboxes). */
        val promises: List<app.parley.common.circle.Promises.Item> = emptyList(),
    )

    data class Headings(
        val pinnedNote: String = "Pinned note",
        val note: String = "Note",
        val circle: String = "Circle",
        val timeline: String = "Timeline",
        val promises: String = "Promises",
    )

    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    /** A YAML double-quoted scalar: safe for any text (colons, quotes, leading dashes, emoji). */
    fun yaml(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when {
                ch == '\\' -> append("\\\\")
                ch == '"' -> append("\\\"")
                ch == '\n' -> append("\\n")
                ch == '\r' -> Unit
                ch == '\t' -> append("\\t")
                ch.code < 0x20 -> append(String.format(Locale.ROOT, "\\x%02X", ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun StringBuilder.list(key: String, values: List<String>) {
        if (values.isEmpty()) return
        append(key).append(":\n")
        values.forEach { append("  - ").append(yaml(it)).append('\n') }
    }

    private fun field(f: Field) = if (f.label.isBlank()) f.value.trim() else "${f.value.trim()} (${f.label.trim()})"

    /** Front-matter key of the file's own [fingerprint], written last in the front-matter. */
    const val MARKER = "parley_export"

    /** The whole file, sealed with its [fingerprint]. [exportedAt] and [zone] date the file and the timeline. */
    fun render(p: Person, zone: ZoneId, exportedAt: Long, h: Headings = Headings()): String {
        val body = renderBody(p, zone, exportedAt, h)
        val end = body.indexOf("\n---\n", 3)
        return body.substring(0, end + 1) + "$MARKER: ${fingerprint(body)}\n" + body.substring(end + 1)
    }

    /**
     * C5: a hash of a file's content without its [MARKER] line and with the `exported:` date blanked, so it names what
     * Parley wrote about the person (the same person exported on another day has the same fingerprint).
     */
    fun fingerprint(text: String): String {
        val t = text.replace("\r\n", "\n")
        val end = if (t.startsWith("---\n")) t.indexOf("\n---\n", 3) else -1
        val normalised = if (end < 0) t else {
            t.substring(0, end).split('\n').filterNot { it.startsWith("$MARKER:") }
                .joinToString("\n") { if (it.startsWith("exported:")) "exported:" else it } + t.substring(end)
        }
        return app.parley.common.backup.RecordJson.sha256Hex(normalised.toByteArray(Charsets.UTF_8))
    }

    /** The fingerprint a file says it has, or null without one. */
    fun markerOf(text: String): String? {
        val t = text.replace("\r\n", "\n")
        val end = if (t.startsWith("---\n")) t.indexOf("\n---\n", 3) else -1
        if (end < 0) return null
        return t.substring(0, end).split('\n').firstOrNull { it.startsWith("$MARKER:") }?.substringAfter(':')?.trim()?.ifEmpty { null }
    }

    /** True when [text] is a file Parley wrote and nobody changed since: its fingerprint still matches its content. */
    fun isUntouched(text: String): Boolean = markerOf(text)?.let { it == fingerprint(text) } == true

    private fun renderBody(p: Person, zone: ZoneId, exportedAt: Long, h: Headings): String = buildString {
        append("---\n")
        append("name: ").append(yaml(p.name)).append('\n')
        if (p.company.isNotBlank()) append("company: ").append(yaml(p.company.trim())).append('\n')
        list("phones", p.phones.filter { it.value.isNotBlank() }.map(::field))
        list("emails", p.emails.filter { it.value.isNotBlank() }.map(::field))
        list("dates", p.dates.filter { it.value.isNotBlank() }.map { d -> if (d.label.isBlank()) d.value.trim() else "${d.label.trim()}: ${d.value.trim()}" })
        list("labels", p.labels.filter { it.isNotBlank() }.map { it.trim() }.distinct().sortedBy { it.lowercase() })
        p.keepInTouchDays?.let { append("keep_in_touch_days: ").append(it).append('\n') }
        append("exported: ").append(DAY.format(Instant.ofEpochMilli(exportedAt).atZone(zone))).append('\n')
        append("---\n\n")
        append("# ").append(oneLine(p.name).ifEmpty { "?" }).append('\n')
        fun section(title: String, body: String) {
            if (body.isBlank()) return
            append("\n## ").append(title).append("\n\n").append(body.trim()).append('\n')
        }
        section(h.pinnedNote, p.pinnedNote)
        section(h.note, p.note)
        p.keepInTouch?.let { section(h.circle, it) }
        section(h.promises, p.promises.distinctBy { it.text to it.done }.joinToString("\n") { "- [${if (it.done) "x" else " "}] ${oneLine(it.text)}" })
        if (p.timeline.isNotEmpty()) {
            append("\n## ").append(h.timeline).append("\n\n")
            p.timeline.sortedByDescending { it.time }.forEach { e ->
                append("- ").append(MINUTE.format(Instant.ofEpochMilli(e.time).atZone(zone))).append(" · ").append(oneLine(e.kind))
                e.note?.let(::oneLine)?.takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
                append('\n')
            }
        }
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s*[\\r\\n]+\\s*"), " ").trim()

    private val forbidden = Regex("[\\\\/:*?\"<>|#^\\[\\]\\p{Cntrl}]")

    /**
     * A safe file name for [name] ("Ana / Marco?" → "Ana Marco.md"): no characters that break Windows, Android
     * storage or Obsidian links, no leading dots, at most 80 characters. [taken] holds the names already used in
     * this export (compared ignoring case) and receives the new one; a clash gets " (2)", " (3)"…
     */
    fun fileName(name: String, taken: MutableSet<String>): String {
        val base = baseName(name)
        var candidate = "$base.md"
        var n = 2
        while (candidate.lowercase(Locale.ROOT) in taken) candidate = "$base ($n).md".also { n++ }
        taken += candidate.lowercase(Locale.ROOT)
        return candidate
    }

    private fun baseName(name: String) =
        forbidden.replace(name, " ").replace(Regex("\\s+"), " ").trim().trimStart('.', ' ').trimEnd('.', ' ').take(80).trim().ifEmpty { "Contact" }

    /**
     * C5: the file for [name] in a folder: the first of "Ana.md", "Ana (2).md"… that no one else got in this run
     * ([used], lower case, receives the choice) and that is either free or Parley's own untouched file ([ours] is asked
     * about files in [existing], lower-cased name → actual name). A file the user wrote or edited is stepped over and
     * never reused. Returns the actual name (an existing file keeps its spelling).
     */
    fun chooseFile(name: String, used: MutableSet<String>, existing: Map<String, String>, ours: (String) -> Boolean): String {
        val base = baseName(name)
        var n = 1
        while (true) {
            val candidate = if (n == 1) "$base.md" else "$base ($n).md"
            n++
            val lc = candidate.lowercase(Locale.ROOT)
            if (lc in used) continue
            val there = existing[lc]
            if (there == null || ours(there)) {
                used += lc
                return there ?: candidate
            }
        }
    }
}
