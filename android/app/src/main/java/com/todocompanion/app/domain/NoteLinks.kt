package com.todocompanion.app.domain

/**
 * Phase 3 — the expert connection layer for Notes. Pure, on-device text parsing (no network, no schema):
 *   • [[wiki-links]] between notes, resolved by title (like Obsidian/Logseq), so renaming a note and its
 *     links stay title-consistent and backlinks are computed from bodies — no extra table, no migration.
 *   • Markdown checkbox extraction, so a note's "- [ ] …" lines can become real tasks.
 */
object NoteLinks {
    private val WIKI = Regex("""\[\[([^\[\]]+)]]""")
    private val UNCHECKED = Regex("""^\s*[-*+]\s*\[ ]\s+(.*\S)\s*$""")

    /** Distinct, trimmed note titles referenced as `[[Title]]` in [body], in first-seen order. */
    fun outgoingTitles(body: String): List<String> =
        WIKI.findAll(body).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.distinct().toList()

    /** The text of each unchecked Markdown checkbox line (`- [ ] text`, `* [ ] text`, `+ [ ] text`). */
    fun uncheckedCheckboxes(body: String): List<String> =
        body.lineSequence().mapNotNull { line -> UNCHECKED.find(line)?.groupValues?.get(1)?.trim() }
            .filter { it.isNotBlank() }
            .toList()

    /** True if [body] references a note titled [title] via a wiki-link (case-insensitive) — for backlinks. */
    fun links(body: String, title: String): Boolean {
        if (title.isBlank()) return false
        return outgoingTitles(body).any { it.equals(title.trim(), ignoreCase = true) }
    }
}
