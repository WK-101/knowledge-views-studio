package com.todocompanion.app.domain

/**
 * Wave Q — inline Markdown "live styling" (Bear's Panda / Obsidian's Live Preview): the single most-loved
 * editing interaction in the notes landscape. This is the pure core — it maps a Markdown string to a list
 * of styled [Span]s over the ORIGINAL character offsets (identity mapping: no characters are added or
 * removed, so the syntax markers stay visible but styled, and caret/selection math is untouched). The
 * Compose layer ([com.todocompanion.app.ui.components.MarkdownVisualTransformation]) turns these spans
 * into an AnnotatedString.
 *
 * Because it only decorates, imperfect overlaps are cosmetic, never corrupting — but the scan is careful:
 * it is fenced-code aware (nothing inside a ``` block is treated as markup, and the whole block is dimmed
 * to code), block styles (headings, quotes, list/checkbox markers) are line-anchored, and inline spans
 * (bold, italic, str\~through, `code`, [[wiki]], [links], #tags) are found within non-code text.
 */
object MarkdownStyle {

    enum class Kind {
        H1, H2, H3,          // heading lines (by level; 4-6 fold into H3)
        QUOTE,               // blockquote line
        MARKER,              // list bullet / number / checkbox marker (the token, dimmed)
        CODE,                // inline `code` and fenced blocks (incl. the fence lines)
        BOLD, ITALIC, BOLD_ITALIC, STRIKE,
        LINK, WIKILINK, TAG, // [text](url) whole match, [[wiki]] whole match, #tag
        SYNTAX,              // faint emphasis/marker characters (**, *, ~~, #, >) — dimmed, not hidden
    }

    data class Span(val start: Int, val end: Int, val kind: Kind)

    private val fenceLine = Regex("^\\s*(`{3,}|~{3,})")
    private val heading = Regex("^(#{1,6})\\s+\\S")
    private val quote = Regex("^\\s*>\\s?")
    private val listMarker = Regex("^(\\s*)([-*+]\\s|\\d+[.)]\\s)")
    private val checkbox = Regex("^(\\s*)([-*+]\\s)\\[[ xX]\\]\\s")

    // Inline scanners (applied to a single non-code line, offsets made absolute by the caller).
    private val inlineCode = Regex("`[^`\\n]+`")
    private val boldItalic = Regex("\\*\\*\\*[^*\\n]+\\*\\*\\*")
    private val bold = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
    private val italic = Regex("(?<![*_\\w])([*_])(?=\\S)([^*_\\n]+?)(?<=\\S)\\1(?![*_\\w])")
    private val strike = Regex("~~(?=\\S)(.+?)(?<=\\S)~~")
    private val wikiLink = Regex("\\[\\[[^\\]\\n]+]]")
    private val mdLink = Regex("\\[[^\\]\\n]*]\\([^)\\n]+\\)")
    private val tag = Regex("(?<![\\w#/])#[A-Za-z][\\w/-]*")

    fun spans(text: String): List<Span> {
        val out = ArrayList<Span>()
        var offset = 0
        var inFence = false
        val lines = text.split("\n")
        for ((idx, line) in lines.withIndex()) {
            val start = offset
            val end = start + line.length
            val isFence = fenceLine.containsMatchIn(line)
            if (isFence) {
                out.add(Span(start, end, Kind.CODE))
                inFence = !inFence
            } else if (inFence) {
                if (line.isNotEmpty()) out.add(Span(start, end, Kind.CODE))
            } else {
                styleLine(line, start, out)
            }
            offset = end + 1 // account for the '\n'
        }
        return out
    }

    private fun styleLine(line: String, base: Int, out: MutableList<Span>) {
        // ── block-level ──
        heading.find(line)?.let { m ->
            val level = m.groupValues[1].length
            val kind = when (level) { 1 -> Kind.H1; 2 -> Kind.H2; else -> Kind.H3 }
            out.add(Span(base, base + line.length, kind))
            out.add(Span(base, base + level, Kind.SYNTAX)) // dim the leading #'s
            return
        }
        quote.find(line)?.let { m ->
            out.add(Span(base, base + line.length, Kind.QUOTE))
            out.add(Span(base + m.range.first, base + m.range.last + 1, Kind.SYNTAX))
            // fall through: allow inline styling inside the quote too
        }
        (checkbox.find(line) ?: listMarker.find(line))?.let { m ->
            // dim the marker token only (indent + bullet/number [+ checkbox])
            out.add(Span(base + m.range.first, base + m.range.last + 1, Kind.MARKER))
        }
        // ── inline ── (record code spans first so we can skip markup inside them)
        val codeRanges = ArrayList<IntRange>()
        for (m in inlineCode.findAll(line)) {
            codeRanges.add(m.range)
            out.add(Span(base + m.range.first, base + m.range.last + 1, Kind.CODE))
        }
        fun inCode(r: IntRange) = codeRanges.any { r.first >= it.first && r.last <= it.last }
        fun add(m: MatchResult, k: Kind) { if (!inCode(m.range)) out.add(Span(base + m.range.first, base + m.range.last + 1, k)) }

        for (m in boldItalic.findAll(line)) add(m, Kind.BOLD_ITALIC)
        for (m in bold.findAll(line)) add(m, Kind.BOLD)
        for (m in italic.findAll(line)) add(m, Kind.ITALIC)
        for (m in strike.findAll(line)) add(m, Kind.STRIKE)
        for (m in wikiLink.findAll(line)) add(m, Kind.WIKILINK)
        for (m in mdLink.findAll(line)) add(m, Kind.LINK)
        for (m in tag.findAll(line)) add(m, Kind.TAG)
    }
}
