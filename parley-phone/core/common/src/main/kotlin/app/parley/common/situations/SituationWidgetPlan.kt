package app.parley.common.situations

import app.parley.common.widgets.WidgetShape

/**
 * The Situations widget: the Situation on now and a tap to turn each one on or off, from the home screen. One cell
 * shows one toggle, for the one on now or else the one turned on most ([favourite]); wider, a row of chips; two cells
 * high, the line "Night is on" (or "No Situation") above rows of them. A tap goes through the same switch as the app
 * and the Quick Settings tile, with no end chosen (as the tile does). Situations are not private: like the tile, the
 * widget shows their names.
 */
object SituationWidgetPlan {
    const val PADDING_DP = 16
    const val TITLE_DP = 24

    /** About one chip per 56 dp across and 44 dp down; below 32 dp high not even one row fits. */
    const val CHIP_WIDTH_DP = 56
    const val CHIP_HEIGHT_DP = 44
    const val CHIP_MIN_HEIGHT_DP = 32
    const val MAX_PER_ROW = 5
    const val MAX_ROWS = 4

    /** A height to plan for when the launcher gives none (two rows). */
    private const val UNKNOWN_HEIGHT_DP = 120

    /** Chips drawn at one size: [title] above [rows] rows of [perRow]. */
    data class Fit(val title: Boolean, val perRow: Int, val rows: Int) {
        val capacity: Int get() = perRow * rows
    }

    /** The chips for a widget [widthDp]×[heightDp]; null: one cell, or too low for a row, so a single toggle. */
    fun fit(widthDp: Int, heightDp: Int): Fit? {
        val shape = WidgetShape.of(widthDp, heightDp)
        val inside = (if (heightDp > 0) heightDp else UNKNOWN_HEIGHT_DP) - PADDING_DP
        if (shape == WidgetShape.TILE || inside < CHIP_MIN_HEIGHT_DP) return null
        val title = shape == WidgetShape.BLOCK && inside >= TITLE_DP + CHIP_HEIGHT_DP
        val room = inside - if (title) TITLE_DP else 0
        val perRow = if (widthDp > 0) ((widthDp - PADDING_DP) / CHIP_WIDTH_DP).coerceIn(1, MAX_PER_ROW) else 2
        return Fit(title, perRow, (room / CHIP_HEIGHT_DP).coerceIn(1, MAX_ROWS))
    }

    /** The one a single toggle is for: the one on now, else the one turned on most, else the first; null: none. */
    fun favourite(ids: List<String>, activeId: String?, used: Map<String, Int>): String? =
        activeId?.takeIf { it in ids } ?: ids.maxByOrNull { used[it] ?: 0 }?.takeIf { (used[it] ?: 0) > 0 } ?: ids.firstOrNull()

    /**
     * The chips that fit [capacity], in the list's order: all of them when they fit, else the one on now first, then the
     * ones turned on most (ties: the list's order).
     */
    fun chips(ids: List<String>, activeId: String?, used: Map<String, Int>, capacity: Int): List<String> {
        if (ids.size <= capacity) return ids
        val picked = ids.withIndex()
            .sortedWith(compareBy({ it.value != activeId }, { -(used[it.value] ?: 0) }, { it.index }))
            .take(capacity.coerceAtLeast(0))
            .map { it.value }
            .toSet()
        return ids.filter { it in picked }
    }

    /**
     * How often each was turned on, after the one on became [activeNow] (from [lastSeen], whatever turned it on: the
     * app, the tile, the widget, a window or the car). Null when nothing new came on.
     */
    fun counted(used: Map<String, Int>, lastSeen: String?, activeNow: String?): Map<String, Int>? {
        if (activeNow == null || activeNow == lastSeen) return null
        return used + (activeNow to (used[activeNow] ?: 0) + 1)
    }
}
