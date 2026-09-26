package com.wkhan.hexis.domain

/**
 * P7 — the small pieces of a note that the home list needs but that are expensive to recompute from the
 * full Markdown body on every render/scan: a plain-prose preview snippet and "has open action items".
 * Materialized into [com.wkhan.hexis.data.entity.NoteEntity] columns on save (migration v73→v74),
 * so the card and the `hasOpenItems` Smart-View predicate read a column instead of regex-scanning the
 * body. Pure + deterministic, so it's unit-tested and reused as the fallback for un-materialized rows.
 */
object NoteDerived {
    /** Strip the most common Markdown marks so a card preview reads as plain prose (≤160 chars). */
    fun preview(body: String): String =
        body.lineSequence()
            .map { it.trim().trimStart('#', '>', '-', '*', '+', ' ', '`').trim() }
            .filter { it.isNotBlank() }
            .joinToString("  ")
            .replace(Regex("[*_`~]"), "")
            .take(160)

    /** True when the body has at least one unchecked `- [ ]` task item. */
    fun hasOpenItems(body: String): Boolean = NoteLinks.uncheckedCheckboxes(body).isNotEmpty()
}
