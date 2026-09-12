package com.todocompanion.app.domain

/**
 * The one place the note Markdown "dialect" is defined. Before this, the same handful of patterns —
 * the inline #tag, a [[wiki-link]], a [text](url) link, an ATX heading — were re-declared (slightly
 * differently) across the live-styling engine, the block renderer, the related/wrapped analysers and the
 * editor's info panel. That drift is the classic source of "styled here but not matched there" bugs.
 *
 * Everything here matches on the FULL match (group 0); [TAG] additionally exposes the tag text in group 1
 * for callers that want it. Keeping these `val`s compiled once also avoids re-compiling the same pattern
 * on every scan.
 */
object NoteGrammar {
    /** An inline hashtag: `#tag`, `#nested/tag`, not `#123` and not part of a word or a `##` run. Group 1 = the tag text (no `#`). */
    val TAG = Regex("(?<![\\w#/])#([A-Za-z][\\w/-]*)")

    /** A `[[wiki-link]]` (single line). */
    val WIKI_LINK = Regex("\\[\\[[^\\]\\n]+]]")

    /** A Markdown `[text](url)` link (single line). */
    val MD_LINK = Regex("\\[[^\\]\\n]*]\\([^)\\n]+\\)")

    /** An ATX heading line with non-blank content — `^(#{1,6})\s+\S`. Group 1 = the `#` run (its length is the level). */
    val HEADING_LINE = Regex("^(#{1,6})\\s+\\S")

    /** An ATX heading line, capturing its text — `^(#{1,6})\s+(.*)$`. Group 1 = `#` run, group 2 = heading text. */
    val HEADING_CONTENT = Regex("^(#{1,6})\\s+(.*)$")
}
