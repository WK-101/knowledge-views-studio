package com.wkhan.hexis.domain.voice

/**
 * Composes short, speakable answers and a daily briefing from a snapshot of the user's own data —
 * entirely on-device, no LLM, no network. Pure and deterministic so it can be unit-tested; the
 * ViewModel gathers the [Snapshot] from its already-computed state flows and hands it here.
 */
object SpokenAnswers {

    /** A read-only slice of the day, taken from state the app has already computed. */
    data class Snapshot(
        val greeting: String,
        val todayCount: Int,
        val doNextCount: Int,
        val needsAttention: Int,
        val habitsLoggedToday: Int,
    )

    /** A spoken morning briefing stitched from the snapshot. */
    fun briefing(s: Snapshot): String {
        val parts = mutableListOf<String>()
        parts += s.greeting.trimEnd('.') + "."
        parts += if (s.todayCount == 0) {
            "Nothing is due today."
        } else {
            "You have ${count(s.todayCount, "task")} due today."
        }
        if (s.doNextCount > 0) parts += "${count(s.doNextCount, "task")} to do next."
        if (s.needsAttention > 0) parts += "${count(s.needsAttention, "task")} need attention."
        if (s.habitsLoggedToday > 0) parts += "You've logged ${count(s.habitsLoggedToday, "habit")} today."
        if (s.todayCount == 0 && s.needsAttention == 0) parts += "You're all clear — nice."
        return parts.joinToString(" ")
    }

    /** Answers a spoken question against the snapshot; falls back to a short help line. */
    fun answer(question: String, s: Snapshot): String {
        val q = question.lowercase()
        return when {
            q.containsAny("brief", "summary", "my day", "rundown", "stand up", "standup") -> briefing(s)
            q.containsAny("attention", "overdue", "behind") ->
                if (s.needsAttention == 0) "Nothing needs attention — you're on top of it." else "${count(s.needsAttention, "task")} need attention."
            q.containsAny("next", "what should i", "what now") ->
                if (s.doNextCount == 0) "Your Do Next list is empty." else "You have ${count(s.doNextCount, "task")} to do next."
            q.containsAny("habit") ->
                if (s.habitsLoggedToday == 0) "You haven't logged any habits yet today." else "You've logged ${count(s.habitsLoggedToday, "habit")} today."
            q.containsAny("due", "today", "how many task", "tasks do i") ->
                if (s.todayCount == 0) "Nothing is due today." else "You have ${count(s.todayCount, "task")} due today."
            else -> "I can tell you what's due today, what to do next, what needs attention, your habits, or give a daily briefing."
        }
    }

    private fun count(n: Int, noun: String): String = "$n $noun${if (n == 1) "" else "s"}"

    private fun String.containsAny(vararg needles: String): Boolean = needles.any { it in this }
}
