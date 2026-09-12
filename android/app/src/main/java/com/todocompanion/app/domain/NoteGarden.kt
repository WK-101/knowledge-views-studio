package com.todocompanion.app.domain

/**
 * Wave 2 · The Note-Garden review — deterministic, on-device passes that surface entropy in your second
 * brain so a weekly ten-minute tending keeps it alive: orphans (nothing links in or out and no tags),
 * stale (untouched for N days), dropped intentions (a note that names a task title you never created),
 * and near-duplicates (high MinHash similarity). Pure functions over projections the VM assembles.
 */
object NoteGarden {
    data class NoteView(
        val id: String, val title: String, val body: String, val updatedAt: Long,
        val tagCount: Int, val outLinks: Int, val inLinks: Int,
    )

    /** Notes with a title but no tags and no links in or out — adrift in the graph. */
    fun orphans(notes: List<NoteView>): List<NoteView> =
        notes.filter { it.title.isNotBlank() && it.tagCount == 0 && it.outLinks == 0 && it.inLinks == 0 }

    /** Notes untouched for at least [days] days. */
    fun stale(notes: List<NoteView>, now: Long, days: Int = 90): List<NoteView> {
        val cut = now - days.toLong() * NoteReview.DAY
        return notes.filter { it.updatedAt in 1 until cut }.sortedBy { it.updatedAt }
    }

    /** Notes whose body names a task title that has no matching task — a dropped intention. */
    fun droppedIntentions(notes: List<NoteView>, taskTitles: Set<String>): List<Pair<NoteView, String>> {
        if (taskTitles.isEmpty()) return emptyList()
        val out = ArrayList<Pair<NoteView, String>>()
        val lowered = taskTitles.map { it.trim().lowercase() }.filter { it.length >= 4 }.toHashSet()
        // heuristic: a checkbox line whose text isn't (yet) a task, or a [[Link]] with no target — handled
        // elsewhere; here we just flag notes that literally contain a known non-existent task-like phrase.
        for (n in notes) {
            val body = n.body.lowercase()
            // look for "task: X" or a "- [ ] X" whose X isn't a task title
            val m = Regex("^\\s*[-*]\\s*\\[ ]\\s*(.+)$", RegexOption.MULTILINE).find(body) ?: continue
            val phrase = m.groupValues[1].trim().take(60)
            if (phrase.length >= 4 && phrase.trim().lowercase() !in lowered) out.add(n to phrase)
        }
        return out.take(20)
    }

    data class DupePair(val a: NoteView, val b: NoteView, val score: Float)

    /** Near-duplicate note pairs above [threshold] MinHash similarity. Callers should pass a capped set. */
    fun duplicates(notes: List<NoteView>, threshold: Float = 0.55f): List<DupePair> {
        val sigs = notes.map { NoteSemantic.signature(it.title + "\n" + it.body) }
        val out = ArrayList<DupePair>()
        for (i in notes.indices) for (j in i + 1 until notes.size) {
            val s = NoteSemantic.similarity(sigs[i], sigs[j])
            if (s >= threshold) out.add(DupePair(notes[i], notes[j], s))
        }
        return out.sortedByDescending { it.score }.take(20)
    }
}
