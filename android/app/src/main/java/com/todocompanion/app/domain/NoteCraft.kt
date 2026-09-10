package com.todocompanion.app.domain

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Wave M — pure "editor craft" helpers, no Compose/Android so they unit-test cleanly:
 *   - [NoteOutline] — a document table-of-contents from the heading AST + word/line/reading stats.
 *   - [NoteLint]    — lightweight Markdown nits (heading spacing/punctuation, stray blank lines,
 *                     trailing spaces), skipping fenced code.
 *   - [NoteTokens]  — `{{date}}` / `{{time}}` / `{{date:PATTERN}}` / `{{time:PATTERN}}` expansion
 *                     (used by quick-insert and, later, cross-module templates).
 */
object NoteOutline {
    data class Head(val title: String, val level: Int, val line: Int)
    data class Stats(val words: Int, val lines: Int, val chars: Int, val readMinutes: Int)

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")

    /** Headings outside fenced code blocks, in document order. */
    fun outline(md: String): List<Head> {
        val out = ArrayList<Head>()
        var fenced = false
        md.split("\n").forEachIndexed { i, raw ->
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) { fenced = !fenced; return@forEachIndexed }
            if (fenced) return@forEachIndexed
            HEADING.matchEntire(line)?.let { m ->
                val t = m.groupValues[2].trim()
                if (t.isNotEmpty()) out.add(Head(t, m.groupValues[1].length, i))
            }
        }
        return out
    }

    fun stats(md: String): Stats {
        val words = Regex("\\S+").findAll(md).count()
        val lines = if (md.isEmpty()) 0 else md.count { it == '\n' } + 1
        val read = if (words == 0) 0 else Math.max(1, Math.ceil(words / 200.0).toInt())  // ~200 wpm
        return Stats(words, lines, md.length, read)
    }
}

object NoteLint {
    data class Issue(val line: Int, val message: String)

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val HEADING_NO_SPACE = Regex("^#{1,6}[^#\\s]")

    fun lint(md: String): List<Issue> {
        val out = ArrayList<Issue>()
        var fenced = false
        var blankRun = 0
        md.split("\n").forEachIndexed { i, raw ->
            if (raw.trimStart().startsWith("```")) { fenced = !fenced; blankRun = 0; return@forEachIndexed }
            if (fenced) return@forEachIndexed
            if (raw.isBlank()) { blankRun++; if (blankRun == 4) out.add(Issue(i, "More than 3 blank lines in a row")); return@forEachIndexed }
            blankRun = 0
            if (HEADING_NO_SPACE.containsMatchIn(raw)) out.add(Issue(i, "Heading needs a space after #"))
            HEADING.matchEntire(raw.trimEnd())?.let { m ->
                val t = m.groupValues[2].trimEnd()
                if (t.isNotEmpty() && t.last() in ".,;:!") out.add(Issue(i, "Heading ends with punctuation"))
            }
            if (raw.length - raw.trimEnd().length >= 3) out.add(Issue(i, "Trailing whitespace"))
        }
        return out
    }
}

object NoteTokens {
    private val TOKEN = Regex("\\{\\{(date|time)(?::([^}]+))?}}")

    /** Expand date/time tokens against [now] (injected so it's deterministic in tests). Unknown patterns
     *  are left verbatim rather than throwing. */
    fun expand(text: String, now: LocalDateTime = LocalDateTime.now()): String =
        TOKEN.replace(text) { m ->
            val kind = m.groupValues[1]
            val pattern = m.groupValues[2]
            runCatching {
                when {
                    pattern.isNotBlank() -> now.format(DateTimeFormatter.ofPattern(pattern))
                    kind == "date" -> now.toLocalDate().toString()
                    else -> "%02d:%02d".format(now.hour, now.minute)
                }
            }.getOrDefault(m.value)
        }

    fun hasTokens(text: String): Boolean = TOKEN.containsMatchIn(text)
}
