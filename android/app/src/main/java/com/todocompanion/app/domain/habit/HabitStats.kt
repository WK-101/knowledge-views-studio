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

    /** Parse a "1,3,5" schedule string into day-of-week numbers (1=Mon..7=Sun). Empty = every day. */
    fun parseSchedule(s: String): Set<Int> = s.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()

    private fun dow(epochDay: Long): Int = java.time.LocalDate.ofEpochDay(epochDay).dayOfWeek.value

    fun isScheduled(epochDay: Long, schedule: Set<Int>): Boolean = schedule.isEmpty() || dow(epochDay) in schedule

    /** Schedule-aware streak: consecutive *scheduled* days done, skipping (not breaking on) off days. */
    fun streak(doneDays: Set<Long>, today: Long, schedule: Set<Int>): Int {
        if (schedule.isEmpty()) return streak(doneDays, today)
        var d = today
        // An in-progress scheduled day that isn't done yet shouldn't break the streak — step past it.
        if (dow(d) in schedule && d !in doneDays) d--
        var n = 0
        var guard = 0
        while (guard++ < 4000) {
            if (dow(d) in schedule) { if (d in doneDays) { n++; d-- } else break } else d--
        }
        return n
    }

    /** Schedule-aware rate: done scheduled days / scheduled days, over the window. */
    fun rate(doneDays: Set<Long>, today: Long, schedule: Set<Int>, window: Int = 30): Float {
        if (schedule.isEmpty()) return rate(doneDays, today, window)
        var scheduled = 0; var hits = 0
        for (i in 0 until window) { val d = today - i; if (dow(d) in schedule) { scheduled++; if (d in doneDays) hits++ } }
        return if (scheduled == 0) 0f else hits.toFloat() / scheduled
    }
}
