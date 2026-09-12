package com.todocompanion.app.domain

/**
 * L5 — Shared Checkboxes: the two-way membrane between a note's `- [ ]` lines and real tasks. A checkbox
 * line that carries a `[[Title]]` wiki-link resolving to a task is *bound* to it. Kairo converges the two:
 *   • on save, a bound line's state is pushed to its task (tick it in the note ⇒ the task completes);
 *   • on open, a bound line's state is pulled from its task (complete it elsewhere ⇒ the note shows it).
 * A notes-only app can't do this — it doesn't own the task. Pure text logic here; the repository wires it.
 */
object NoteCheckboxSync {
    // g1 = prefix through the '[', g2 = state char, g3 = ']' + gap, g4 = the line's text.
    private val BOX = Regex("""^(\s*[-*+]\s+\[)([ xX])(]\s+)(.*)$""")
    private val WIKI = Regex("""\[\[([^\[\]]+)]]""")

    data class Box(val checked: Boolean, val title: String, val text: String)

    /** Every checkbox line bound to an entity via a `[[wiki-link]]`: its checked state and the linked title. */
    fun boundBoxes(body: String): List<Box> = body.lineSequence().mapNotNull { line ->
        val m = BOX.matchEntire(line) ?: return@mapNotNull null
        val title = WIKI.find(m.groupValues[4])?.groupValues?.get(1)?.trim()
        if (title.isNullOrBlank()) null else Box(m.groupValues[2].lowercase() == "x", title, m.groupValues[4])
    }.toList()

    /** True if [body] has any bound checkbox line — a cheap gate before touching the task table. */
    fun hasBound(body: String): Boolean = body.lineSequence().any { line ->
        BOX.matchEntire(line)?.let { WIKI.containsMatchIn(it.groupValues[4]) } == true
    }

    /** Rewrite each bound checkbox line's state char to match [checkedByTitle] (null ⇒ leave as-is); unbound
     *  lines are never touched. Compare the result with `!=` to know whether anything changed. */
    fun reconcile(body: String, checkedByTitle: (String) -> Boolean?): String =
        body.split("\n").joinToString("\n") { line ->
            val m = BOX.matchEntire(line) ?: return@joinToString line
            val title = WIKI.find(m.groupValues[4])?.groupValues?.get(1)?.trim()
            if (title.isNullOrBlank()) return@joinToString line
            val want = checkedByTitle(title) ?: return@joinToString line
            m.groupValues[1] + (if (want) "x" else " ") + m.groupValues[3] + m.groupValues[4]
        }

    /** Append a `[[Title]]` binding to a checkbox line's text if it has no wiki-link yet (used by extract). */
    fun bindText(text: String, title: String): String =
        if (WIKI.containsMatchIn(text)) text else "$text [[${title.trim()}]]"
}
