package com.todocompanion.app.domain

/**
 * Track 3.4 — the pure "what's changed since you sealed this" diff for a letter to your future self. A
 * sealed letter stamps the accomplishment count at seal time; on reveal we compare it to the current
 * count and put the delta in plain words ("You've finished 143 more things since then"). This is the
 * Compose-free, deterministic core so it unit-tests as plain Kotlin — the notification and the reveal
 * dialog both read from here. Nothing leaves the device.
 */
object SealedLetters {

    /** The computed since-then diff. [delta] never goes below 0 (a smaller current count reads as 0). */
    data class Diff(val delta: Int, val daysSealed: Int) {
        /** The headline sentence for the reveal. */
        val phrase: String
            get() = when {
                delta <= 0 -> "No new finishes are on the record since you sealed this — yet."
                else -> "You've finished $delta more thing${if (delta == 1) "" else "s"} since then."
            }

        /** An optional pace line, present only when there's a delta and enough time to make it meaningful. */
        val paceLine: String?
            get() {
                if (delta <= 0 || daysSealed < 7) return null
                val perWeek = delta.toDouble() / (daysSealed / 7.0)
                return "That's about ${String.format(java.util.Locale.US, "%.1f", perWeek)} a week for ${weeksLabel(daysSealed)}."
            }
    }

    /**
     * Diff the accomplishment counts. [sealedCount] is what was stamped at seal time, [currentCount] the
     * count now; [createdEpochDay]/[todayEpochDay] bound how long it was sealed (for the pace line).
     */
    fun diff(sealedCount: Int, currentCount: Int, createdEpochDay: Long, todayEpochDay: Long): Diff {
        val delta = (currentCount - sealedCount).coerceAtLeast(0)
        val days = (todayEpochDay - createdEpochDay).coerceAtLeast(0).toInt()
        return Diff(delta, days)
    }

    private fun weeksLabel(days: Int): String {
        val w = days / 7
        return if (w <= 1) "the past week" else "$w weeks"
    }
}
