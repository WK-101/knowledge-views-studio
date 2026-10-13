package app.parley.common.widgets

/**
 * The shape a home-screen widget is drawn in at one size, so a small widget gets a layout made for it instead of a
 * big one cut off. Android 12 and later list every size the launcher may show (portrait, landscape, a foldable's two
 * screens); earlier versions give a portrait and a landscape size. Each is drawn in its own shape.
 *
 * On a phone one cell is about 57 dp across and 102 dp high in portrait, and 127 × 51 dp in landscape; two cells are
 * about 130 dp across and 220 dp high (117 in landscape).
 */
enum class WidgetShape {
    /** About one cell across: one face or icon, with a short label when there is height for it. */
    TILE,

    /** Wider than one cell but about one cell high: one row, no title. */
    STRIP,

    /** Two by two cells or more: a title and as much as fits. */
    BLOCK,
    ;

    companion object {
        /** Narrower than this is one cell across. */
        const val NARROW_DP = 100

        /** Lower than this is one cell high. */
        const val LOW_DP = 110

        /** Lower than this, a one-cell widget shows its icon or face alone (the label is still read out). */
        const val LABEL_MIN_DP = 72

        /** Whether a one-cell widget [heightDp] high has room for a label under its icon (unknown: yes). */
        fun labelFits(heightDp: Int): Boolean = heightDp <= 0 || heightDp >= LABEL_MIN_DP

        /** The shape for [widthDp] × [heightDp]; an unknown side (0 or less) counts as roomy. */
        fun of(widthDp: Int, heightDp: Int): WidgetShape = when {
            widthDp in 1 until NARROW_DP -> TILE
            heightDp in 1 until LOW_DP -> STRIP
            else -> BLOCK
        }
    }
}
