package app.parley.common.ux

/**
 * Layout rules for a row of icon-only filter chips (Recents in the Rich style): every chip is an icon on a 48 dp
 * target, and the selected ones also show their name when the whole row still fits on one line. A row that
 * would need to scroll sideways keeps every chip icon-only instead, so all filters stay in view.
 */
object CompactChips {
    /**
     * Whether the selected chips can show their names: [chips] icon targets of [chipWidth] with [gap] between
     * them, plus the extra width each shown name takes ([labelWidths]), fit in [available]. All values in the
     * same unit (pixels).
     */
    fun labelsFit(available: Int, chips: Int, chipWidth: Int, gap: Int, labelWidths: List<Int>): Boolean {
        if (chips <= 0) return true
        val icons = chips.toLong() * chipWidth + (chips - 1).toLong() * gap
        return icons + labelWidths.sumOf { it.coerceAtLeast(0).toLong() } <= available
    }

    /** Whether even the icon-only row fits without scrolling sideways. */
    fun iconsFit(available: Int, chips: Int, chipWidth: Int, gap: Int): Boolean = labelsFit(available, chips, chipWidth, gap, emptyList())

    /**
     * The letter that stands for a saved filter's [name] on its icon chip: the first letter or digit, upper-cased
     * (whole code points, so a letter outside the basic plane isn't cut in half). Empty when the name has none, and
     * the chip then shows a generic icon.
     */
    fun monogram(name: String): String {
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) return String(Character.toChars(cp)).uppercase()
            i += Character.charCount(cp)
        }
        return ""
    }
}
