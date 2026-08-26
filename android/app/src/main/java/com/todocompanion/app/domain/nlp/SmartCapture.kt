package com.todocompanion.app.domain.nlp

/**
 * R3 — one capture box that decides whether a line is a *habit* (something you do repeatedly to build
 * consistency) or a *task* (a one-off action, usually with a deadline), then routes it to the matching
 * parser. Pure, offline, and deliberately humble: it picks a smart default and the UI lets the user flip
 * it with one tap, so an ambiguous line ("every Friday…") is never a dead end.
 */
object SmartCapture {
    enum class Kind { TASK, HABIT }
    data class Guess(val kind: Kind, val reason: String)

    // Strong "recurring for consistency" cues → habit.
    private val habitCues = listOf(
        Regex("""\b(every\s*day|everyday|daily|each\s*day)\b"""),
        Regex("""\bevery\s+(morning|evening|night|afternoon)\b"""),
        Regex("""\b\d+\s*x\s*(?:/|per|a)?\s*(?:day|week|month)\b"""),
        Regex("""\b\d+\s*times?\s*(?:a|per)\s*(?:day|week|month)\b"""),
        Regex("""\b(weekdays?|weekends?)\b"""),
        Regex("""\bevery\s+\d+\s*days?\b"""),
        Regex("""\b(twice|thrice)\s+(?:a|per)\s+(?:day|week)\b"""),
    )

    // Cues that a line is a concrete one-off action → task (a specific deadline / clock time).
    private val taskCues = listOf(
        Regex("""\b(today|tomorrow|tonight|tmrw|next\s+\w+)\b"""),
        Regex("""\bin\s+\d+\s*(?:min|hour|day|week)s?\b"""),
        Regex("""\b\d{1,2}(:\d{2})?\s*(am|pm)\b"""),
        Regex("""\bby\s+(mon|tue|wed|thu|fri|sat|sun|\d)"""),
        Regex("""!"""), // priority marker used by the task quick-add
    )

    fun classify(raw: String): Guess {
        val t = " " + raw.trim().lowercase() + " "
        if (t.isBlank()) return Guess(Kind.TASK, "")

        val habitHit = habitCues.firstOrNull { it.containsMatchIn(t) }
        val taskHit = taskCues.firstOrNull { it.containsMatchIn(t) }

        // A repeat-for-consistency cue wins unless it's clearly a one-off with an explicit clock time
        // AND no daily/weekly repetition. ("gym at 6pm" is a habit; "call Sam at 6pm" is a task —
        // we can't perfectly separate those, so recurrence language is the tie-breaker and the user flips
        // the rest.)
        return when {
            habitHit != null -> Guess(Kind.HABIT, "repeats — “${habitHit.find(t)?.value?.trim()}”")
            taskHit != null -> Guess(Kind.TASK, "one-off — has a deadline")
            else -> Guess(Kind.TASK, "no repeat cue — filed as a task")
        }
    }
}
