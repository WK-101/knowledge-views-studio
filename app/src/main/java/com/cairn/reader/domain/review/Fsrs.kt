package com.cairn.reader.domain.review

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/** A card's learning phase under FSRS. */
enum class SrPhase { NEW, LEARNING, REVIEW, RELEARNING }

/**
 * FSRS memory state: stability (expected days until retrievability decays to the target), difficulty
 * (1..10, how intrinsically hard the item is), the current [phase], and streak bookkeeping.
 */
data class FsrsState(
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val phase: SrPhase = SrPhase.NEW,
    val reps: Int = 0,
    val lapses: Int = 0,
)

/** The outcome of grading a card: the new memory state, when it's next due, and the interval used. */
data class FsrsResult(val state: FsrsState, val dueAt: Long, val intervalDays: Int)

/**
 * FSRS (Free Spaced Repetition Scheduler), v5 — the modern successor to SM-2 that Anki ships as its
 * scheduler. It models memory as Difficulty + Stability and picks each interval to land the card at
 * a chosen **desired retention** (e.g. 90%), so intervals adapt to how hard each item actually is
 * for you rather than a fixed ease ladder. Fully deterministic, on-device, no network, no model.
 *
 * The weights are the published FSRS-5 defaults; the only knobs the app exposes are the desired
 * retention and the maximum interval. A lapse ("Again") relearns in-session (~10 min) like the
 * classic scheduler, so a review sitting stays usable; every other grade schedules by the model.
 */
object Fsrs {
    /** FSRS-5 default parameters (19). */
    val DEFAULT_W = doubleArrayOf(
        0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046, 1.54575,
        0.1192, 1.01925, 1.9395, 0.11, 0.29605, 2.2698, 0.2315, 2.9898, 0.51655, 0.6621,
    )

    private const val DECAY = -0.5
    private val FACTOR = 19.0 / 81.0
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val AGAIN_STEP_MS = 10 * 60_000L      // a lapse relearns in ~10 minutes, same session
    private const val MIN_STABILITY = 0.01
    private const val MAX_DIFFICULTY = 10.0
    private const val MIN_DIFFICULTY = 1.0

    /** Probability the card is still recalled after [elapsedDays] given stability [s]. */
    fun retrievability(elapsedDays: Double, s: Double): Double {
        if (s <= 0.0) return 0.0
        return (1.0 + FACTOR * elapsedDays / s).pow(DECAY)
    }

    /** Days until the card next decays to [requestRetention], clamped to [1, maxIntervalDays]. */
    private fun nextInterval(s: Double, requestRetention: Double, maxIntervalDays: Int): Int {
        val ivl = (s / FACTOR) * (requestRetention.pow(1.0 / DECAY) - 1.0)
        return ivl.roundToInt().coerceIn(1, maxIntervalDays)
    }

    private fun initDifficulty(w: DoubleArray, g: Int): Double =
        (w[4] - exp(w[5] * (g - 1)) + 1.0).coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)

    private fun initStability(w: DoubleArray, g: Int): Double = w[g - 1].coerceAtLeast(MIN_STABILITY)

    /** Difficulty update with FSRS-5 linear damping + mean reversion toward the Easy-grade baseline. */
    private fun nextDifficulty(w: DoubleArray, d: Double, g: Int): Double {
        val deltaD = -w[6] * (g - 3)
        val damped = d + deltaD * (10.0 - d) / 9.0
        val reverted = w[7] * initDifficulty(w, 4) + (1.0 - w[7]) * damped
        return reverted.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
    }

    private fun nextStabilityOnRecall(w: DoubleArray, d: Double, s: Double, r: Double, g: Int): Double {
        val hardPenalty = if (g == 2) w[15] else 1.0
        val easyBonus = if (g == 4) w[16] else 1.0
        val inc = exp(w[8]) * (11.0 - d) * s.pow(-w[9]) *
            (exp(w[10] * (1.0 - r)) - 1.0) * hardPenalty * easyBonus
        return (s * (1.0 + inc)).coerceAtLeast(MIN_STABILITY)
    }

    private fun nextStabilityOnForget(w: DoubleArray, d: Double, s: Double, r: Double): Double {
        val sf = w[11] * d.pow(-w[12]) * ((s + 1.0).pow(w[13]) - 1.0) * exp(w[14] * (1.0 - r))
        // A lapse must not increase stability.
        return sf.coerceIn(MIN_STABILITY, s.coerceAtLeast(MIN_STABILITY))
    }

    /**
     * Grade [state] with [grade]; [lastReviewedAt] is when it was last seen (null for a brand-new
     * card). Returns the updated memory state and next due time. Deterministic given its inputs.
     */
    fun review(
        state: FsrsState,
        grade: Grade,
        now: Long,
        lastReviewedAt: Long?,
        requestRetention: Double = 0.90,
        maxIntervalDays: Int = 36500,
        w: DoubleArray = DEFAULT_W,
    ): FsrsResult {
        val g = grade.ordinal + 1  // AGAIN=1, HARD=2, GOOD=3, EASY=4
        val req = requestRetention.coerceIn(0.70, 0.99)

        // First exposure: seed stability/difficulty from the grade.
        if (state.phase == SrPhase.NEW || state.stability <= 0.0) {
            val seeded = FsrsState(
                stability = initStability(w, g),
                difficulty = initDifficulty(w, g),
                phase = if (g == 1) SrPhase.LEARNING else SrPhase.REVIEW,
                reps = 1,
                lapses = if (g == 1) 1 else 0,
            )
            return if (g == 1) FsrsResult(seeded, now + AGAIN_STEP_MS, 0)
            else scheduleByInterval(seeded, now, req, maxIntervalDays)
        }

        val elapsedDays = lastReviewedAt?.let { ((now - it).toDouble() / DAY_MS).coerceAtLeast(0.0) } ?: 0.0
        val r = retrievability(elapsedDays, state.stability)
        val difficulty = nextDifficulty(w, state.difficulty, g)

        return if (g == 1) {
            val s = nextStabilityOnForget(w, state.difficulty, state.stability, r)
            FsrsResult(
                state.copy(stability = s, difficulty = difficulty, phase = SrPhase.RELEARNING, reps = state.reps + 1, lapses = state.lapses + 1),
                now + AGAIN_STEP_MS, 0,
            )
        } else {
            val s = nextStabilityOnRecall(w, state.difficulty, state.stability, r, g)
            scheduleByInterval(
                state.copy(stability = s, difficulty = difficulty, phase = SrPhase.REVIEW, reps = state.reps + 1),
                now, req, maxIntervalDays,
            )
        }
    }

    private fun scheduleByInterval(state: FsrsState, now: Long, req: Double, maxIvl: Int): FsrsResult {
        val ivl = nextInterval(state.stability, req, maxIvl)
        return FsrsResult(state, now + ivl * DAY_MS, ivl)
    }

    /** The interval (days) grading with [grade] would schedule, for the on-button preview. */
    fun previewIntervalDays(
        state: FsrsState,
        grade: Grade,
        lastReviewedAt: Long?,
        now: Long = System.currentTimeMillis(),
        requestRetention: Double = 0.90,
        maxIntervalDays: Int = 36500,
    ): Int = review(state, grade, now, lastReviewedAt, requestRetention, maxIntervalDays).intervalDays
}
