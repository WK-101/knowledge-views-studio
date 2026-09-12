package com.todocompanion.app.domain

/**
 * Wave 2 · Evergreen Resurfacing — the spaced-review cadence that turns notes from a write-only graveyard
 * into a living library. A note carries a [reviewEvery] (days; 0 = off); the app's existing alarm engine
 * brings it back when it falls due. Pure date math, unit-testable — the anchor is the later of the last
 * deliberate review and the last edit, so editing a note also counts as reviewing it.
 */
object NoteReview {
    const val DAY = 24L * 60 * 60 * 1000

    /** The cadences offered in the picker (days). 0 disables review. */
    val CADENCES = listOf(0, 1, 3, 7, 14, 30, 90)

    fun label(days: Int): String = when (days) {
        0 -> "Off"; 1 -> "Daily"; 3 -> "Every 3 days"; 7 -> "Weekly"
        14 -> "Fortnightly"; 30 -> "Monthly"; 90 -> "Quarterly"; else -> "Every $days days"
    }

    /** When the note is next due to resurface, or null when review is off. */
    fun nextDue(reviewEvery: Int, lastReviewedAt: Long, updatedAt: Long): Long? {
        if (reviewEvery <= 0) return null
        val anchor = maxOf(lastReviewedAt, updatedAt)
        return anchor + reviewEvery.toLong() * DAY
    }

    fun isDue(reviewEvery: Int, lastReviewedAt: Long, updatedAt: Long, now: Long): Boolean =
        nextDue(reviewEvery, lastReviewedAt, updatedAt)?.let { it <= now } == true
}
