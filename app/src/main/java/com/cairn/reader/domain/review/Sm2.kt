package com.cairn.reader.domain.review

import kotlin.math.roundToInt

/** A review grade — how well you recalled the highlight. */
enum class Grade { AGAIN, HARD, GOOD, EASY }

/** The scheduling state of a card (a subset of the highlight's SM-2 columns). */
data class SrState(
    val intervalDays: Int = 0,
    val ease: Int = 250,   // ×100
    val reps: Int = 0,
    val lapses: Int = 0,
)

/** The outcome of grading a card: the new state plus when it's next due. */
data class SrResult(val state: SrState, val dueAt: Long)

/**
 * The classic SM-2 spaced-repetition algorithm (Anki's ancestor) — fully deterministic, on-device,
 * no model. Grading a highlight recomputes its ease factor and next interval so well-remembered
 * cards spread out over weeks and months while forgotten ones come back quickly.
 */
object Sm2 {
    private const val MIN_EASE = 130
    private const val LEARN_AGAIN_MS = 60_000L      // "Again" reappears in ~1 minute (same session)
    private const val LEARN_HARD_MS = 10 * 60_000L  // a brand-new "Hard" gets a short step
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun review(state: SrState, grade: Grade, now: Long): SrResult {
        // A lapse: reset the streak, nudge ease down, and re-learn shortly.
        if (grade == Grade.AGAIN) {
            val ease = (state.ease - 20).coerceAtLeast(MIN_EASE)
            return SrResult(state.copy(intervalDays = 0, ease = ease, reps = 0, lapses = state.lapses + 1), now + LEARN_AGAIN_MS)
        }

        // Adjust ease per SM-2 (Hard lowers it, Good keeps it, Easy raises it).
        val easeDelta = when (grade) {
            Grade.HARD -> -15
            Grade.GOOD -> 0
            Grade.EASY -> 15
            Grade.AGAIN -> 0
        }
        val ease = (state.ease + easeDelta).coerceAtLeast(MIN_EASE)

        val interval = when {
            state.reps == 0 -> when (grade) { Grade.HARD -> 1; Grade.EASY -> 4; else -> 1 }
            state.reps == 1 -> when (grade) { Grade.HARD -> 4; Grade.EASY -> 8; else -> 6 }
            else -> {
                val factor = when (grade) {
                    Grade.HARD -> 120                 // Hard grows slowly (×1.2)
                    Grade.EASY -> ease + 30           // Easy bonus
                    else -> ease
                }
                (state.intervalDays * factor / 100.0).roundToInt().coerceAtLeast(state.intervalDays + 1)
            }
        }

        // A fresh "Hard" is still in learning — give it a sub-day step instead of a full day.
        if (state.reps == 0 && grade == Grade.HARD) {
            return SrResult(state.copy(intervalDays = 0, ease = ease, reps = 1), now + LEARN_HARD_MS)
        }
        return SrResult(state.copy(intervalDays = interval, ease = ease, reps = state.reps + 1), now + interval * DAY_MS)
    }

    /** A human label for what grading a card will schedule, shown on the button. */
    fun preview(state: SrState, grade: Grade): String = when (grade) {
        Grade.AGAIN -> "<1m"
        else -> {
            val r = review(state, grade, 0L)
            val d = r.state.intervalDays
            when {
                d <= 0 -> "10m"
                d == 1 -> "1d"
                d < 30 -> "${d}d"
                d < 365 -> "${(d / 30.0).roundToInt()}mo"
                else -> "${(d / 365.0).roundToInt()}y"
            }
        }
    }
}
