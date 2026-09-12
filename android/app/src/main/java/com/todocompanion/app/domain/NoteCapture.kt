package com.todocompanion.app.domain

/**
 * L8 — Capture-to-anything. A brain-dump note's checkbox lines aren't all tasks: some are recurring
 * intentions ("meditate every morning") that belong in the habit builder, not the inbox. This pure
 * classifier decides. Only Kairo can route a captured line to a habit — it owns the habit builder. The
 * default is always a task, so nothing is ever mis-captured silently; recurrence has to be explicit.
 */
object NoteCapture {
    // Explicit recurrence cues. Kept conservative so an ordinary task is never mistaken for a habit.
    private val RECUR = Regex(
        "\\b(every\\s?day|everyday|daily|each\\s?day|every\\s?morning|every\\s?evening|every\\s?night|" +
            "twice\\s?a\\s?day|weekly|every\\s?week|each\\s?week|monthly|every\\s?month|" +
            "every\\s?(mon|tue|wed|thu|fri|sat|sun)\\w*)\\b",
        RegexOption.IGNORE_CASE,
    )

    sealed interface Item {
        data class Task(val title: String) : Item
        data class Habit(val name: String) : Item
    }

    /** Classify one checkbox line's text. A recurrence cue ⇒ Habit (with the cue trimmed from the name). */
    fun classify(text: String): Item {
        val t = text.trim()
        return if (RECUR.containsMatchIn(t)) {
            val name = t.replace(RECUR, "").replace(Regex("\\s{2,}"), " ").trim().trim(',', '-', '–', ' ')
            Item.Habit(name.ifBlank { t })
        } else Item.Task(t)
    }
}
