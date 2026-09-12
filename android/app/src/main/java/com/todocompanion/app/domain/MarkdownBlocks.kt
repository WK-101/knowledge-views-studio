package com.todocompanion.app.domain

/**
 * Wave R — modern editing. Two pure, unit-tested block models the UI edits visually instead of by hand:
 *
 *  • [MarkdownTable] — parse ⇄ serialize a GFM pipe table, so a visual grid editor can round-trip it.
 *  • [MarkdownSections] — split a note into heading-delimited sections and reorder them, so a note can
 *    be restructured (move a whole section up/down) without cut-and-paste. Fenced-code aware, so a `#`
 *    inside a ``` block is never mistaken for a heading.
 */
object MarkdownTable {
    data class Table(val headers: List<String>, val rows: List<List<String>>) {
        val cols: Int get() = headers.size.coerceAtLeast(1)
    }

    /** An empty table with [cols] columns and [rows] body rows. */
    fun empty(cols: Int = 2, rows: Int = 2): Table =
        Table(List(cols) { "" }, List(rows) { List(cols) { "" } })

    private fun cells(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.substring(0, s.length - 1)
        // split on unescaped pipes
        val out = ArrayList<String>(); val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] == '|') { sb.append('|'); i += 2; continue }
            if (c == '|') { out.add(sb.toString().trim()); sb.setLength(0) } else sb.append(c)
            i++
        }
        out.add(sb.toString().trim())
        return out
    }

    private fun isDivider(line: String): Boolean {
        val t = line.trim().trim('|').trim()
        return t.isNotEmpty() && t.all { it == '-' || it == ':' || it == '|' || it == ' ' } && t.contains('-')
    }

    /** Parse a GFM table (header row, `---` divider, body rows). Returns null if it isn't one. */
    fun parse(block: String): Table? {
        val lines = block.trim('\n').split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2 || !isDivider(lines[1])) return null
        val headers = cells(lines[0])
        val body = lines.drop(2).map { cells(it) }
        val cols = headers.size
        val norm = body.map { r -> List(cols) { r.getOrElse(it) { "" } } }
        return Table(headers, norm)
    }

    /** Serialize to a GFM table string (no surrounding blank lines). */
    fun serialize(t: Table): String {
        val cols = t.cols
        fun row(cs: List<String>) = "| " + (0 until cols).joinToString(" | ") { (cs.getOrElse(it) { "" }).replace("|", "\\|").ifBlank { " " }.trim().ifBlank { " " } } + " |"
        val sb = StringBuilder()
        sb.append(row(t.headers)).append("\n")
        sb.append("| ").append((0 until cols).joinToString(" | ") { "---" }).append(" |").append("\n")
        for (r in t.rows) sb.append(row(r)).append("\n")
        return sb.toString().trimEnd('\n')
    }
}

object MarkdownSections {
    /** [heading] is the raw heading line ("" for the pre-heading preamble); [body] is everything under it
     *  up to the next heading (the heading line itself is NOT included in [body]). */
    data class Section(val heading: String, val level: Int, val body: String)

    private val fence = Regex("^\\s*(`{3,}|~{3,})")
    private val heading = NoteGrammar.HEADING_LINE

    fun sections(text: String): List<Section> {
        val out = ArrayList<Section>()
        var curHeading = ""; var curLevel = 0
        val buf = StringBuilder()
        var inFence = false
        fun flush() { out.add(Section(curHeading, curLevel, buf.toString().trimEnd('\n'))); buf.setLength(0) }
        for (line in text.split("\n")) {
            if (fence.containsMatchIn(line)) inFence = !inFence
            val h = if (!inFence) heading.find(line) else null
            if (h != null) {
                // close the running section (preamble or previous heading) and open a new one
                if (curHeading.isNotEmpty() || buf.isNotBlank() || out.isNotEmpty()) flush()
                else buf.setLength(0)
                curHeading = line; curLevel = h.groupValues[1].length
            } else {
                buf.append(line).append("\n")
            }
        }
        flush()
        // drop a leading empty preamble section if it carries nothing
        return if (out.isNotEmpty() && out[0].heading.isEmpty() && out[0].body.isBlank() && out.size > 1) out.drop(1) else out
    }

    fun render(sections: List<Section>): String = sections.joinToString("\n") { s ->
        if (s.heading.isEmpty()) s.body else if (s.body.isEmpty()) s.heading else s.heading + "\n" + s.body
    }.trim('\n')

    /** Move the section at [from] to [to] (clamped), returning the rewritten document. */
    fun move(text: String, from: Int, to: Int): String {
        val secs = sections(text).toMutableList()
        if (from !in secs.indices) return text
        val target = to.coerceIn(0, secs.size - 1)
        if (target == from) return text
        val s = secs.removeAt(from)
        secs.add(target, s)
        return render(secs)
    }
}
