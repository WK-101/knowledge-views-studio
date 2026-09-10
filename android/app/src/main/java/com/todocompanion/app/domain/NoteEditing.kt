package com.todocompanion.app.domain

/**
 * Wave A — the pure text mechanics behind the Notes editor's "feel". No Compose, no Android: just
 * (text, selection) → (text, selection) transforms, so the whole thing is unit-testable and the
 * Compose layer stays a thin shell. Mirrors the [NoteLinks] pattern (pure domain + a test peer).
 *
 *   • [wrapInline]        — bold/italic/strike/code: wrap the selection, or drop an empty pair with
 *                           the caret parked inside (the WriteOn `TextRange` trick, improved to wrap
 *                           a real selection when one exists).
 *   • [insertLinePrefix]  — heading/quote/bullet/checkbox: prepend a marker to the caret's line.
 *   • [continueList]      — smart list continuation on Enter (bullets, auto-incrementing numbers,
 *                           `- [ ]`, preserved indent; an empty marker line exits the list).
 *   • [toggleCheckboxAtLine] — flip `[ ]`↔`[x]` on one source line, for tappable preview checkboxes.
 */
object NoteEditing {
    /** A text edit plus where the selection should land afterwards (a collapsed caret when start==end). */
    data class Edit(val text: String, val selStart: Int, val selEnd: Int)

    // Checkbox must be tested before the plain bullet (a checkbox line also matches the bullet shape).
    private val CHECKBOX = Regex("""^(\s*)([-*+])\s+\[([ xX])]\s*(.*)$""")
    private val BULLET = Regex("""^(\s*)([-*+])\s+(.*)$""")
    private val ORDERED = Regex("""^(\s*)(\d+)([.)])\s+(.*)$""")

    /** Wrap the selection with [marker] on both sides; with no selection, insert `marker+marker` and
     *  place the caret between the two markers, ready to type. The returned selection covers the inner
     *  text (so a follow-up wrap or typing replaces exactly what was wrapped). */
    fun wrapInline(text: String, selStart: Int, selEnd: Int, marker: String): Edit {
        val s = selStart.coerceIn(0, text.length)
        val e = selEnd.coerceIn(s, text.length)
        return if (s == e) {
            val nt = text.substring(0, s) + marker + marker + text.substring(s)
            Edit(nt, s + marker.length, s + marker.length)
        } else {
            val sel = text.substring(s, e)
            val nt = text.substring(0, s) + marker + sel + marker + text.substring(e)
            Edit(nt, s + marker.length, e + marker.length)
        }
    }

    /** Prepend [prefix] (e.g. `"# "`, `"> "`, `"- "`, `"- [ ] "`) to the line the caret sits on. */
    fun insertLinePrefix(text: String, selStart: Int, prefix: String): Edit {
        val caret = selStart.coerceIn(0, text.length)
        val lineStart = lineStartOf(text, caret)
        val nt = text.substring(0, lineStart) + prefix + text.substring(lineStart)
        return Edit(nt, caret + prefix.length, caret + prefix.length)
    }

    /**
     * Called after a single character was inserted. If that character was a newline that ended a list
     * item, either continue the list (insert the next marker, auto-incrementing ordered lists) or —
     * when the item was an empty marker — exit the list by clearing that marker line. Returns null when
     * no list logic applies, so the caller keeps the raw edit.
     *
     * [text] is the NEW text and [caret] the collapsed caret position (just past the inserted `\n`).
     */
    fun continueList(text: String, caret: Int): Edit? {
        if (caret <= 0 || caret > text.length || text.getOrNull(caret - 1) != '\n') return null
        val prevLineEnd = caret - 1
        val prevLineStart = lineStartOf(text, prevLineEnd)
        val prevLine = text.substring(prevLineStart, prevLineEnd)

        CHECKBOX.matchEntire(prevLine)?.let { m ->
            val (indent, bullet, _, content) = m.destructured
            if (content.isBlank()) return exitList(text, prevLineStart, prevLineEnd, indent)
            return continueWith(text, caret, "$indent$bullet [ ] ")
        }
        BULLET.matchEntire(prevLine)?.let { m ->
            val (indent, bullet, content) = m.destructured
            if (content.isBlank()) return exitList(text, prevLineStart, prevLineEnd, indent)
            return continueWith(text, caret, "$indent$bullet ")
        }
        ORDERED.matchEntire(prevLine)?.let { m ->
            val (indent, num, sep, content) = m.destructured
            if (content.isBlank()) return exitList(text, prevLineStart, prevLineEnd, indent)
            val next = (num.toIntOrNull() ?: 0) + 1
            return continueWith(text, caret, "$indent$next$sep ")
        }
        return null
    }

    /** Wave E — wrap the first plain (un-bracketed) occurrence of [title] in `[[ ]]`, turning an unlinked
     *  mention into a real wiki-link. No-op if the title isn't present or is already bracketed there. */
    fun linkMention(body: String, title: String): String {
        if (title.isBlank()) return body
        val idx = body.indexOf(title, ignoreCase = true)
        if (idx < 0) return body
        if (idx >= 2 && body.substring(idx - 2, idx) == "[[") return body
        val actual = body.substring(idx, idx + title.length)
        return body.substring(0, idx) + "[[" + actual + "]]" + body.substring(idx + title.length)
    }

    /** Flip the checkbox state on [lineIndex] (0-based split on `\n`); no-op if that line isn't a box. */
    fun toggleCheckboxAtLine(text: String, lineIndex: Int): String {
        val lines = text.split("\n").toMutableList()
        if (lineIndex !in lines.indices) return text
        val m = Regex("""^(\s*[-*+]\s+)\[([ xX])](.*)$""").find(lines[lineIndex]) ?: return text
        val (pre, state, rest) = m.destructured
        val next = if (state.equals("x", ignoreCase = true)) " " else "x"
        lines[lineIndex] = "$pre[$next]$rest"
        return lines.joinToString("\n")
    }

    private fun continueWith(text: String, caret: Int, marker: String): Edit {
        val nt = text.substring(0, caret) + marker + text.substring(caret)
        return Edit(nt, caret + marker.length, caret + marker.length)
    }

    private fun exitList(text: String, prevLineStart: Int, prevLineEnd: Int, indent: String): Edit {
        // Replace the empty marker line with just its indentation, and drop the trailing newline we added.
        val nt = text.substring(0, prevLineStart) + indent + text.substring(prevLineEnd + 1)
        val caret = prevLineStart + indent.length
        return Edit(nt, caret, caret)
    }

    private fun lineStartOf(text: String, index: Int): Int {
        if (index <= 0) return 0
        val nl = text.lastIndexOf('\n', index - 1)
        return if (nl < 0) 0 else nl + 1
    }
}
