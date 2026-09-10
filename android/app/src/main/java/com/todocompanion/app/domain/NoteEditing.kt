package com.todocompanion.app.domain

/**
 * Wave A — the pure text mechanics behind the Notes editor's "feel". No Compose, no Android: just
 * (text, selection) -> (text, selection) transforms, so the whole thing is unit-testable and the
 * Compose layer stays a thin shell. Mirrors the [NoteLinks] pattern (pure domain + a test peer).
 *
 *   - [wrapInline]         — bold/italic/strike/code: wrap the selection, or drop an empty pair with
 *                            the caret parked inside.
 *   - [insertLinePrefix]   — heading/quote/bullet/checkbox: prepend a marker to the caret's line.
 *   - [continueList]       — smart list continuation on Enter (bullets, auto-incrementing numbers,
 *                            `- [ ]`, preserved indent; an empty marker line exits the list).
 *   - [toggleCheckboxAtLine] — flip `[ ]`<->`[x]` on one source line, for tappable preview checkboxes.
 *   - Wave G: the `/` quick-insert palette ([quickQuery]/[filterQuick]/[applyQuick]).
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

    // ── Wave G · the "/" quick-insert palette (Markleaf-style) ──────────────────────────────────────
    /** A slash-command: [label] shown, [hint] the syntax preview, [snippet] inserted verbatim, and
     *  [caretOffset] = where the caret lands inside the snippet (-1 = its end). [dynamic] commands
     *  (date/time) carry an empty snippet, resolved by the caller at insert time. */
    data class QuickCommand(
        val id: String, val label: String, val hint: String,
        val snippet: String, val caretOffset: Int = -1, val dynamic: Boolean = false,
    )

    val QUICK_COMMANDS: List<QuickCommand> = listOf(
        QuickCommand("h1", "Heading 1", "# ", "# ", 2),
        QuickCommand("h2", "Heading 2", "## ", "## ", 3),
        QuickCommand("h3", "Heading 3", "### ", "### ", 4),
        QuickCommand("bullet", "Bullet list", "- ", "- ", 2),
        QuickCommand("number", "Numbered list", "1. ", "1. ", 3),
        QuickCommand("todo", "Checklist item", "- [ ] ", "- [ ] ", 6),
        QuickCommand("quote", "Quote", "> ", "> ", 2),
        QuickCommand("callout", "Callout", "> [!NOTE]", "> [!NOTE]\n> "),
        QuickCommand("code", "Code block", "``` ```", "```\n\n```", 4),
        QuickCommand("table", "Table", "| col | col |", "| Column | Column |\n| --- | --- |\n|  |  |\n"),
        QuickCommand("divider", "Divider", "---", "\n---\n"),
        QuickCommand("wikilink", "Wiki-link", "[[ ]]", "[[]]", 2),
        QuickCommand("date", "Today's date", "YYYY-MM-DD", "", dynamic = true),
        QuickCommand("time", "Current time", "HH:mm", "", dynamic = true),
    )

    /** If the caret sits in a `/query` token that begins its own line, return the query (may be empty just
     *  after typing `/`); else null. Whitespace in the token dismisses the palette. */
    fun quickQuery(text: String, caret: Int): String? {
        val c = caret.coerceIn(0, text.length)
        val lineStart = lineStartOf(text, c)
        if (lineStart >= text.length || text[lineStart] != '/') return null
        val seg = text.substring(lineStart + 1, c)
        if (seg.any { it.isWhitespace() }) return null
        return seg
    }

    /** Commands matching [query] (prefix on id/label ranks first; then substring). */
    fun filterQuick(query: String): List<QuickCommand> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return QUICK_COMMANDS
        return QUICK_COMMANDS
            .filter { it.id.contains(q) || it.label.lowercase().contains(q) }
            .sortedByDescending { it.id.startsWith(q) || it.label.lowercase().startsWith(q) }
    }

    /** Replace the `/query` token on the caret's line with [snippet], landing the caret at [caretOffset]
     *  within it (-1 = end). For a [QuickCommand.dynamic] command pass the resolved text as [snippet]. */
    fun applyQuick(text: String, caret: Int, snippet: String, caretOffset: Int = -1): Edit {
        val c = caret.coerceIn(0, text.length)
        val lineStart = lineStartOf(text, c)
        val nt = text.substring(0, lineStart) + snippet + text.substring(c)
        val within = if (caretOffset in 0..snippet.length) caretOffset else snippet.length
        val sel = lineStart + within
        return Edit(nt, sel, sel)
    }

    // ── Wave M · cursor-anchored [[wiki]] / #tag autocomplete (FastLog-style backward scan) ──────────
    /** If the caret sits inside an open `[[…` (no closing `]]`, `[[`, or newline between), return the
     *  partial title being typed (may be empty right after `[[`); else null. */
    fun linkAutocompleteQuery(text: String, caret: Int): String? {
        val c = caret.coerceIn(0, text.length)
        val open = text.lastIndexOf("[[", (c - 1).coerceAtLeast(0))
        if (open < 0 || open + 2 > c) return null
        val seg = text.substring(open + 2, c)
        if (seg.contains('\n') || seg.contains("]]") || seg.contains("[[")) return null
        return seg
    }

    /** If the caret is at the end of an inline `#token` (≥1 char after `#`, tag chars only, preceded by
     *  whitespace/start so a `# heading` doesn't trigger), return the partial tag; else null. */
    fun tagAutocompleteQuery(text: String, caret: Int): String? {
        val c = caret.coerceIn(0, text.length)
        var i = c
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        val token = text.substring(i, c)
        if (token.length < 2 || token[0] != '#') return null
        val body = token.substring(1)
        if (body.any { !(it.isLetterOrDigit() || it == '-' || it == '_' || it == '/') }) return null
        return body
    }

    /** Replace the open `[[query` at the caret with a finished `[[title]]`, caret after the `]]`. */
    fun applyLink(text: String, caret: Int, title: String): Edit {
        val c = caret.coerceIn(0, text.length)
        val open = text.lastIndexOf("[[", (c - 1).coerceAtLeast(0))
        if (open < 0) return Edit(text, c, c)
        val nt = text.substring(0, open) + "[[" + title + "]]" + text.substring(c)
        val sel = open + 2 + title.length + 2
        return Edit(nt, sel, sel)
    }

    /** Replace the `#query` token at the caret with `#tag ` (trailing space), caret after it. */
    fun applyTag(text: String, caret: Int, tag: String): Edit {
        val c = caret.coerceIn(0, text.length)
        var i = c
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        val nt = text.substring(0, i) + "#" + tag + " " + text.substring(c)
        val sel = i + 1 + tag.length + 1
        return Edit(nt, sel, sel)
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
