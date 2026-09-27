package app.parley.common.people

import kotlin.math.abs
import kotlin.math.sign

/** What a touch on a swipeable row turned out to be. */
enum class SwipeIntent {
    /** Not moved past the touch slop yet. */
    UNDECIDED,
    /** Clearly sideways: the row takes the gesture. */
    HORIZONTAL,
    /** Up, down or diagonal: the list scrolls and the row leaves the gesture alone. */
    VERTICAL,
}

/**
 * The maths of a row swipe, kept apart from Compose so it can be tested. A swipe starts only after a clear
 * sideways move (past the slop and at least [ANGLE_RATIO] times more sideways than up or down), never while the
 * list is still flinging, and commits past a positional threshold or with a quick flick the same way.
 */
object SwipeGesture {
    const val ANGLE_RATIO = 2f

    /** Flick speed that commits a swipe from a quarter of the threshold, in dp per second. */
    const val FLICK_DP_PER_S = 1000f

    /** How far a row moves towards a side without an action (a small rubber band), in dp. */
    const val RUBBER_DP = 20f

    fun classify(dx: Float, dy: Float, touchSlop: Float, listFlinging: Boolean): SwipeIntent {
        if (listFlinging) return SwipeIntent.VERTICAL
        val ax = abs(dx)
        val ay = abs(dy)
        return when {
            ay >= touchSlop -> SwipeIntent.VERTICAL
            ax < touchSlop -> SwipeIntent.UNDECIDED
            ax > ANGLE_RATIO * ay -> SwipeIntent.HORIZONTAL
            else -> SwipeIntent.VERTICAL
        }
    }

    /** The distance that commits a swipe: a third of the row, between 72 and 180 dp. */
    fun threshold(widthPx: Float, density: Float): Float = (widthPx * 0.33f).coerceIn(72f * density, 180f * density)

    /**
     * Where the row sits for a raw drag of [raw] px (positive = rightwards). A side without an action only gives a
     * little ([RUBBER_DP], damped); a side with one follows the finger up to the row's width.
     */
    fun offset(raw: Float, rightAllowed: Boolean, leftAllowed: Boolean, widthPx: Float, density: Float): Float {
        val allowed = if (raw >= 0) rightAllowed else leftAllowed
        if (allowed) return raw.coerceIn(-widthPx, widthPx)
        val max = RUBBER_DP * density
        return sign(raw) * minOf(abs(raw) * 0.2f, max)
    }

    /**
     * True when letting go at [offset] px with [velocity] px/s runs the action: past [threshold], or flicked the
     * same way faster than [flickVelocity] from a quarter of it. A flick back cancels even past the threshold.
     */
    fun commits(offset: Float, velocity: Float, threshold: Float, flickVelocity: Float, allowed: Boolean = true): Boolean {
        if (!allowed || offset == 0f) return false
        val fast = abs(velocity) >= flickVelocity
        if (fast && sign(velocity) != sign(offset)) return false
        if (fast) return abs(offset) >= threshold / 4
        return abs(offset) >= threshold
    }
}
