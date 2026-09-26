package app.parley.common.extras

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * C5: one Markdown file per person, for Obsidian and other note apps: YAML front-matter (name, phones, e-mails,
 * dates, labels) and then the pinned note, the contact's note, the Circle rhythm and the timeline of calls and
 * logged interactions. Export only: Parley never reads these files back.
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

    /** The whole file. [exportedAt] and [zone] date the file and the timeline. */
    fun render(p: Person, zone: ZoneId, exportedAt: Long, h: Headings = Headings()): String = buildString {
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
        val base = forbidden.replace(name, " ").replace(Regex("\\s+"), " ").trim().trimStart('.', ' ').trimEnd('.', ' ').take(80).trim()
            .ifEmpty { "Contact" }
        var candidate = "$base.md"
        var n = 2
        while (candidate.lowercase(Locale.ROOT) in taken) candidate = "$base ($n).md".also { n++ }
        taken += candidate.lowercase(Locale.ROOT)
        return candidate
    }
}
