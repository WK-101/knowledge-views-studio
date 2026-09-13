package com.todocompanion.app.domain

/**
 * Wave 3 · Active Recall — turn a note's own text into spaced-repetition flashcards, on-device and
 * model-free. Two bits of grammar live inline in the body so cards are authored where the knowledge is:
 *
 *   • **Q&A / definition:** a line with `::` — `What is the capital of France? :: Paris`, or
 *     `Ubiquity :: the state of being everywhere at once`. Text before `::` is the prompt, text after
 *     is the answer.
 *   • **Cloze deletion:** a line with `==highlighted==` spans — `The mitochondria is the ==powerhouse==
 *     of the cell.` The highlighted words are blanked on the prompt and revealed on the answer.
 *
 * A card's schedule (SM-2) persists separately (note_cards), so editing the note re-derives the card's
 * *content* without losing its review history. This object is pure: parsing and the SM-2 math are
 * unit-testable with no Android, no storage and no network. It reuses the resurfacing cadence idea from
 * [NoteReview] but at the sentence level, and the app's existing alarm engine brings due cards back.
 */
object NoteCards {
    const val DAY = 24L * 60 * 60 * 1000
    const val MIN_EASE = 1.3
    private const val START_EASE = 2.5

    // A `::` card line. Guard against `://` (URLs) by requiring non-space around the separator loosely.
    private val CLOZE = Regex("==([^=\\n]+)==")
    // Skip code fences / headings / list-only lines from being misread as cards.
    private fun isSkippable(line: String): Boolean {
        val t = line.trim()
        return t.isEmpty() || t.startsWith("```") || t.startsWith("#") || t.startsWith(">")
    }

    data class Card(val id: String, val noteId: String, val front: String, val back: String, val kind: String)

    private fun norm(s: String): String = s.trim().lowercase().replace(Regex("\\s+"), " ")

    /** A stable id from the note + the normalized prompt, so a card keeps its schedule across edits. */
    fun cardId(noteId: String, front: String): String = "$noteId:${norm(front).hashCode()}"

    /** Extract the flashcards authored inline in [body]. Order-preserving, de-duplicated by prompt. */
    fun parse(noteId: String, body: String): List<Card> {
        val out = LinkedHashMap<String, Card>()
        for (raw in body.split("\n")) {
            if (isSkippable(raw)) continue
            val line = raw.trim()
            // Q&A / definition — a single `::` separator (ignore `:::` and URL `://`).
            val sep = findSeparator(line)
            if (sep >= 0) {
                val front = line.substring(0, sep).trim().removePrefix("-").removePrefix("*").trim()
                val back = line.substring(sep + 2).trim()
                if (front.length >= 2 && back.isNotEmpty()) {
                    val id = cardId(noteId, front)
                    out[id] = Card(id, noteId, front, back, "qa")
                    continue
                }
            }
            // Cloze — one card per line carrying ==highlights==.
            if (CLOZE.containsMatchIn(line)) {
                val answer = CLOZE.replace(line) { it.groupValues[1] }.trim()
                val prompt = CLOZE.replace(line) { " […] " }.trim()
                val id = cardId(noteId, prompt)
                out[id] = Card(id, noteId, prompt, answer, "cloze")
            }
        }
        return out.values.toList()
    }

    /** Index of a lone `::` separator, or -1. Avoids `:::` and `://`. */
    private fun findSeparator(line: String): Int {
        var i = line.indexOf("::")
        while (i >= 0) {
            val before = if (i > 0) line[i - 1] else ' '
            val after = if (i + 2 < line.length) line[i + 2] else ' '
            if (before != ':' && after != ':' && after != '/') return i
            i = line.indexOf("::", i + 2)
        }
        return -1
    }

    /** True when a note carries any card grammar (cheap check for the editor badge). */
    fun hasCards(body: String): Boolean =
        body.split("\n").any { !isSkippable(it) && (findSeparator(it.trim()) >= 0 || CLOZE.containsMatchIn(it)) }

    // ── SM-2-lite scheduling ────────────────────────────────────────────────────
    /** How the reviewer graded a card. Maps to SM-2 quality: again=2, hard=3, good=4, easy=5. */
    enum class Grade(val q: Int) { AGAIN(2), HARD(3), GOOD(4), EASY(5) }

    data class Sched(val easiness: Double, val intervalDays: Int, val reps: Int, val lapses: Int, val dueAt: Long)

    /**
     * Apply a grade to a card's schedule (SM-2, day-granular). A failed recall (AGAIN) resets the streak
     * and brings the card back within the same session; success grows the interval by the ease factor.
     */
    fun schedule(prev: Sched, grade: Grade, now: Long): Sched {
        if (grade == Grade.AGAIN) {
            return prev.copy(reps = 0, lapses = prev.lapses + 1, intervalDays = 0, dueAt = now + 60_000)
        }
        val q = grade.q
        var ease = prev.easiness + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02))
        if (ease < MIN_EASE) ease = MIN_EASE
        val reps = prev.reps + 1
        val interval = when (reps) {
            1 -> if (grade == Grade.EASY) 3 else 1
            2 -> 6
            else -> Math.round(prev.intervalDays.coerceAtLeast(1) * ease).toInt().coerceAtLeast(1)
        }.let { if (grade == Grade.HARD) it.coerceAtMost((it * 0.8).toInt().coerceAtLeast(1)) else it }
        return Sched(ease, interval, reps, prev.lapses, now + interval.toLong() * DAY)
    }

    /** A fresh card's schedule (due immediately). */
    fun fresh(now: Long): Sched = Sched(START_EASE, 0, 0, 0, now)
}
