package app.parley.common.calls

import kotlin.math.abs

/**
 * The rules of the incoming screen's slide control (docs/CALL_SCREEN_DESIGN.md): the knob starts in the middle of
 * the track, decline is at the left end and answer at the right. A position is the knob's share of its travel, from
 * -1 (all the way to decline) through 0 (resting in the middle) to 1 (all the way to answer).
 *
 * A call is answered or declined only by a deliberate drag: past [COMMIT], or past [FLICK_COMMIT] with a fast flick
 * towards the same end. Anything less springs back, so a brush in a pocket does nothing.
 */
object AnswerSlide {
    /** Released past this share of the travel, the call is answered or declined. */
    const val COMMIT = 0.6f

    /** A flick counts from this share of the travel on, when it is at least [FLICK_DP_PER_SECOND] fast. */
    const val FLICK_COMMIT = 0.3f

    /** How fast a flick must be, towards the end it is heading for, in dp per second. */
    const val FLICK_DP_PER_SECOND = 1200f

    /** The hint under the track has faded out once the knob has moved this far. */
    const val HINT_FADE = 0.3f

    enum class Outcome { ANSWER, DECLINE, SPRING_BACK }

    /** What a moment on the track calls for: nothing, the "you can let go now" tick, or a softer one on backing off. */
    enum class Tick { NONE, ARMED, DISARMED }

    /** The knob's position from its offset in pixels and its full travel to either end (both from the middle). */
    fun position(offsetPx: Float, travelPx: Float): Float = if (travelPx <= 0f) 0f else (offsetPx / travelPx).coerceIn(-1f, 1f)

    /** Which end is armed at [position]: 1 answer, -1 decline, 0 neither. */
    fun armed(position: Float): Int = when {
        position >= COMMIT -> 1
        position <= -COMMIT -> -1
        else -> 0
    }

    /** What the finger's move from [before] to [after] should feel like. */
    fun tick(before: Float, after: Float): Tick {
        val was = armed(before)
        val now = armed(after)
        return when {
            was == now -> Tick.NONE
            now != 0 -> Tick.ARMED
            else -> Tick.DISARMED
        }
    }

    /** The outcome of letting go at [position] with a horizontal speed of [velocityDpPerSecond] (right is positive). */
    fun release(position: Float, velocityDpPerSecond: Float = 0f): Outcome {
        val side = armed(position)
        if (side == 1) return Outcome.ANSWER
        if (side == -1) return Outcome.DECLINE
        // A quick flick only counts when it has already come some way and keeps going the same way.
        val flick = abs(position) >= FLICK_COMMIT && abs(velocityDpPerSecond) >= FLICK_DP_PER_SECOND &&
            (position > 0f) == (velocityDpPerSecond > 0f)
        return when {
            !flick -> Outcome.SPRING_BACK
            position > 0f -> Outcome.ANSWER
            else -> Outcome.DECLINE
        }
    }

    /** How visible the hint under the track is: full at rest, fading as the knob moves, gone by [HINT_FADE]. */
    fun hintAlpha(position: Float): Float = (1f - abs(position) / HINT_FADE).coerceIn(0f, 1f)

    /** How far an end target has "lit up" (0..1): it fills as the knob approaches and is full once armed. */
    fun targetFill(position: Float, end: Int): Float {
        val toward = if (end > 0) position else -position
        return (toward / COMMIT).coerceIn(0f, 1f)
    }
}
