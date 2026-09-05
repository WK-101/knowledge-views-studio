package com.todocompanion.app.domain

/**
 * Phase B — pure, on-device scoring for the Goals surface. No network, no new storage; everything is
 * derived from the Goal objects + the GoalReview log already stored in settings JSON.
 *
 *   • the 12-week execution cycle (1.3) — which week you're in, days left, whether pace is on track,
 *   • review-due detection + the *integrity chain* (0.4 / moat #5) — the run of consecutive review
 *     periods you actually showed up for,
 *   • the scoreboard rollup — the recent execution trend the 4DX "compelling scoreboard" needs.
 */
object GoalScore {

    data class Cycle(
        val weekIndex: Int,       // 1-based week within the cycle
        val totalWeeks: Int,
        val daysElapsed: Int,
        val daysLeft: Int,
        val elapsedFraction: Double,   // 0..1 of the window spent
    ) {
        val started get() = daysElapsed >= 0 && weekIndex >= 1
    }

    /** The 12-week (or N-week) cycle a goal is in, or null when it has no cycle / hasn't started. */
    fun cycle(g: Goal, today: Long): Cycle? {
        if (!g.hasCycle) return null
        val start = g.cycleStartEpochDay
        val totalDays = g.cycleWeeks * 7
        val end = start + totalDays
        if (today < start) return null
        val daysElapsed = (today - start).toInt().coerceAtLeast(0)
        val daysLeft = (end - today).toInt().coerceAtLeast(0)
        val weekIndex = (daysElapsed / 7 + 1).coerceIn(1, g.cycleWeeks)
        val frac = if (totalDays == 0) 0.0 else (daysElapsed.toDouble() / totalDays).coerceIn(0.0, 1.0)
        return Cycle(weekIndex, g.cycleWeeks, daysElapsed, daysLeft, frac)
    }

    /** Whether pace is on track: overall completion should keep up with time elapsed (within 10%). */
    fun onTrack(overall: Double, elapsedFraction: Double): Boolean = overall >= elapsedFraction - 0.10

    /** Reviews for a scope (a goal id, or "" for portfolio), newest first. */
    private fun scoped(reviews: List<GoalReview>, goalId: String): List<GoalReview> =
        reviews.filter { it.goalId == goalId }.sortedByDescending { it.epochDay }

    /** The most recent review for a scope, or null. */
    fun lastReview(reviews: List<GoalReview>, goalId: String = ""): GoalReview? = scoped(reviews, goalId).firstOrNull()

    /** True when the scope is due (or overdue) for a review given its cadence. */
    fun reviewDue(reviews: List<GoalReview>, cadenceDays: Int, today: Long, goalId: String = ""): Boolean {
        val last = lastReview(reviews, goalId) ?: return true
        return today - last.epochDay >= cadenceDays.coerceAtLeast(1)
    }

    /** Days until the next review is due (negative = overdue by that many days), or 0 if never reviewed. */
    fun daysUntilReview(reviews: List<GoalReview>, cadenceDays: Int, today: Long, goalId: String = ""): Int {
        val last = lastReview(reviews, goalId) ?: return 0
        return (last.epochDay + cadenceDays.coerceAtLeast(1) - today).toInt()
    }

    /**
     * The integrity chain: how many consecutive cadence-periods, counting back from the current one,
     * carry at least one review. A kept-your-word-to-yourself streak — the anti-fragile version of a
     * habit streak, measured in review sittings, not check-ins.
     */
    fun integrityChain(reviews: List<GoalReview>, cadenceDays: Int, today: Long, goalId: String = ""): Int {
        val cadence = cadenceDays.coerceAtLeast(1)
        val days = scoped(reviews, goalId).map { it.epochDay }.toSortedSet()
        if (days.isEmpty()) return 0
        var chain = 0
        // Walk back one cadence-window at a time; count while each window holds a review.
        var windowEnd = today
        // Only start counting once we're inside a window that actually contains a review — this keeps
        // a fresh streak alive right up to the cadence boundary before it's considered broken.
        var guard = 0
        while (guard++ < 520) {
            val windowStart = windowEnd - cadence + 1
            val hit = days.any { it in windowStart..windowEnd }
            if (hit) { chain++; windowEnd = windowStart - 1 }
            else if (chain == 0) {
                // No review in the current window yet — allow the grace of the current, still-open
                // period: slide the window back once to see if the previous period was kept.
                windowEnd = windowStart - 1
                if (windowEnd < days.first()) break
            } else break
        }
        return chain
    }

    /** Recent execution percentages for a scope, oldest→newest, for the scoreboard sparkline. */
    fun executionTrend(reviews: List<GoalReview>, goalId: String = "", limit: Int = 8): List<Int> =
        reviews.filter { it.goalId == goalId }.sortedBy { it.epochDay }.map { it.executionPct }.takeLast(limit)

    /** Average execution over the recent reviews (0..100), or null when there are none. */
    fun avgExecution(reviews: List<GoalReview>, goalId: String = "", limit: Int = 8): Int? {
        val t = executionTrend(reviews, goalId, limit)
        return if (t.isEmpty()) null else t.average().toInt()
    }
}
