package com.todocompanion.app.domain.habit

/** Habit streak/completion maths. Pure, so it's unit-testable. */
object HabitStats {

    /**
     * Current streak: the number of consecutive completed days ending today (or yesterday if today
     * isn't done yet, so an in-progress day doesn't break the streak).
     */
    fun streak(doneDays: Set<Long>, today: Long): Int {
        var d = if (today in doneDays) today else today - 1
        var n = 0
        while (d in doneDays) { n++; d-- }
        return n
    }

    /** Completion rate over the last [window] days ending today, as 0..1. */
    fun rate(doneDays: Set<Long>, today: Long, window: Int = 30): Float {
        if (window <= 0) return 0f
        val hits = (0 until window).count { (today - it) in doneDays }
        return hits.toFloat() / window
    }
}
