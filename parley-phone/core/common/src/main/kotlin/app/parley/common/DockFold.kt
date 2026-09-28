package app.parley.common

/**
 * The docked keypad's fold, as a fraction: 1 = unfolded, 0 = folded. Drags on the panel (or the folded
 * keypad button) move it with the finger; on release it settles open or folded by [settle]. A scroll
 * through the list under it folds it first ([preScroll]), then the list scrolls: the two never fight.
 */
object DockFold {
    /** A release faster than this (dp per second) folds or unfolds whatever the distance. */
    const val FLING_DP_PER_S = 400f

    /** Without a fling, a drag has to move the panel this far (of its height) to change its state. */
    const val DISTANCE = 0.3f

    /** The fold after the finger moved [deltaPx] (down is positive) over a panel [fullPx] tall. */
    fun dragged(fraction: Float, deltaPx: Float, fullPx: Float): Float {
        if (fullPx <= 0f) return fraction
        return (fraction - deltaPx / fullPx).coerceIn(0f, 1f)
    }

    /**
     * Whether the panel ends up unfolded when a drag that started [startedOpen] is released at [fraction] with
     * [velocityDpPerS] (down is positive).
     */
    fun settle(fraction: Float, velocityDpPerS: Float, startedOpen: Boolean): Boolean = when {
        velocityDpPerS > FLING_DP_PER_S -> false
        velocityDpPerS < -FLING_DP_PER_S -> true
        startedOpen -> fraction > 1f - DISTANCE
        else -> fraction >= DISTANCE
    }

    /**
     * How much of a list scroll of [deltaPx] (negative: the finger moves up, further into the list) the fold takes
     * before the list scrolls: only while scrolling on, only until the panel is folded, never to unfold.
     */
    fun preScroll(fraction: Float, deltaPx: Float, fullPx: Float): Float {
        if (deltaPx >= 0f || fraction <= 0f || fullPx <= 0f) return 0f
        return maxOf(deltaPx, -fraction * fullPx)
    }
}
