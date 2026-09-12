package com.todocompanion.app.domain

/**
 * Wave 2 · Threads (cross-entity Maps of Content). A "thread" is a note (kind = "thread") whose body lists
 * its items in order as `[[wiki-links]]` — to notes, and equally to real tasks and events. This reads that
 * order and, for any note, reports the threads it belongs to and its previous/next neighbour in each, so
 * the editor can show a prev/next bar. No new table: a thread is just an ordered link set in a note's body.
 */
object NoteThreads {
    const val KIND = "thread"
    private val LINK = Regex("\\[\\[([^\\[\\]\\n]+)]]")

    data class Position(
        val threadId: String, val threadTitle: String,
        val index: Int, val size: Int, val prevTitle: String?, val nextTitle: String?,
    )

    /** The ordered, de-duplicated `[[titles]]` a thread note lists (heading anchors stripped). */
    fun items(body: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (m in LINK.findAll(body)) {
            val t = m.groupValues[1].substringBefore("#").trim()
            if (t.isNotEmpty()) seen.add(t)
        }
        return seen.toList()
    }

    /** Append a title to a thread body as a new ordered bullet, unless it's already listed. */
    fun addItem(body: String, title: String): String {
        if (items(body).any { it.equals(title, ignoreCase = true) }) return body
        val line = "- [[${title}]]"
        return if (body.isBlank()) line else body.trimEnd() + "\n" + line
    }

    /** For [noteTitle], its position in every thread that lists it. [threads] = (id, title, body). */
    fun positionsFor(noteTitle: String, threads: List<Triple<String, String, String>>): List<Position> {
        val key = noteTitle.trim().lowercase()
        if (key.isEmpty()) return emptyList()
        val out = ArrayList<Position>()
        for ((id, title, body) in threads) {
            val list = items(body)
            val idx = list.indexOfFirst { it.trim().lowercase() == key }
            if (idx >= 0) out.add(
                Position(id, title, idx, list.size, list.getOrNull(idx - 1), list.getOrNull(idx + 1)),
            )
        }
        return out
    }
}
