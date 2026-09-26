package app.parley.common.circle

/**
 * R9: promises are note lines that start with `[ ]` (open) or `[x]` (done). There is no language parsing: it's a
 * plain convention the note editor explains and inserts with a checkbox button. A leading "- " (Markdown task
 * lists) and spaces before the box are allowed, so notes pasted from elsewhere work too.
 */
object Promises {
    /** What the checkbox button inserts. */
    const val OPEN = "[ ] "

    /** One promise: its [line] index in the note, the text after the box, and whether it's ticked off. */
    data class Item(val line: Int, val text: String, val done: Boolean)

    private val box = Regex("""^(\s*(?:[-*]\s+)?)\[( |x|X)]\s?(.*)$""")

    fun parse(note: String?): List<Item> {
        if (note.isNullOrBlank()) return emptyList()
        return note.lines().mapIndexedNotNull { i, line ->
            val m = box.find(line) ?: return@mapIndexedNotNull null
            val text = m.groupValues[3].trim()
            if (text.isEmpty()) null else Item(i, text, m.groupValues[2] != " ")
        }
    }

    fun open(note: String?): List<Item> = parse(note).filterNot { it.done }

    /** [note] with the box on [line] set to [done]; unchanged when that line isn't a promise. */
    fun setDone(note: String, line: Int, done: Boolean): String {
        val lines = note.lines().toMutableList()
        val current = lines.getOrNull(line) ?: return note
        val m = box.find(current) ?: return note
        lines[line] = m.groupValues[1] + (if (done) "[x]" else "[ ]") + " " + m.groupValues[3].trimStart()
        return lines.joinToString("\n")
    }

    /**
     * The editor's checkbox button: puts [OPEN] at the start of the line the cursor is on, or on a new line when
     * that line already has text. Returns the new text and cursor position.
     */
    fun insertBox(text: String, cursor: Int): Pair<String, Int> {
        val at = cursor.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        val lineEnd = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        if (box.containsMatchIn(line) || line.trimStart().startsWith("[")) return text to at
        return if (line.isBlank()) {
            val out = text.substring(0, lineStart) + OPEN + text.substring(lineEnd)
            out to lineStart + OPEN.length
        } else {
            val out = text.substring(0, lineEnd) + "\n" + OPEN + text.substring(lineEnd)
            out to lineEnd + 1 + OPEN.length
        }
    }

    /** A note for one-line display: promise boxes become "☐ " / "☑ ", blank lines go. */
    fun preview(note: String): String = note.lines().filter { it.isNotBlank() }.joinToString(" · ") { line ->
        val m = box.find(line)
        if (m == null) line.trim() else (if (m.groupValues[2] == " ") "☐ " else "☑ ") + m.groupValues[3].trim()
    }
}
