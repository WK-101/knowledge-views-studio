package app.parley.common.ux

/** Material's window size classes, by width (in dp): a phone, a small tablet or unfolded book, a tablet or desktop. */
enum class WindowWidth {
    COMPACT,
    MEDIUM,
    EXPANDED,
    ;

    companion object {
        fun of(widthDp: Int): WindowWidth = when {
            widthDp < 600 -> COMPACT
            widthDp < 840 -> MEDIUM
            else -> EXPANDED
        }
    }
}

/**
 * How Home uses a window of [widthDp] × [heightDp]. Phones keep the one-column layout with a bottom bar. From a medium
 * width the tabs move to a navigation rail. Where a list and what it opens both fit at a comfortable width (a tablet
 * either way up, an unfolded foldable held sideways, a large desktop window) Contacts and Recents show the list and the
 * open item side by side ([listDetail]). A phone held sideways is wide but short: it keeps one column, as before.
 */
data class WindowLayout(val widthDp: Int, val heightDp: Int) {
    val width: WindowWidth get() = WindowWidth.of(widthDp)

    /** A phone in landscape (or a short split-screen window): too short for two scrolling panes. */
    val short: Boolean get() = heightDp < SHORT_BELOW_DP

    /** The tabs are in a navigation rail rather than a bottom bar. */
    val rail: Boolean get() = width != WindowWidth.COMPACT

    /** The list and the open item side by side. */
    val listDetail: Boolean get() = widthDp >= LIST_DETAIL_FROM_DP && !short

    /**
     * The list pane's width: about 40 % of the window, never narrower than a compact phone list nor wider than a
     * comfortable list row, so the detail pane keeps at least a phone's width.
     */
    val listPaneDp: Int get() = (widthDp * LIST_SHARE).toInt().coerceIn(LIST_MIN_DP, LIST_MAX_DP)

    /** A screen whose content would otherwise stretch edge to edge (a keypad, a grid of favourites) caps it at this. */
    val contentMaxDp: Int get() = if (width == WindowWidth.EXPANDED) CONTENT_MAX_DP else Int.MAX_VALUE

    /**
     * The Keypad tab's width: a dial pad is used with one thumb, so beyond a phone it stays a phone-sized column in the
     * middle rather than keys spread across a tablet. A phone held sideways keeps its own landscape layout.
     */
    val keypadMaxDp: Int get() = if (rail && !short) KEYPAD_MAX_DP else Int.MAX_VALUE

    companion object {
        /** Material's compact height. */
        const val SHORT_BELOW_DP = 480

        /**
         * A rail (80 dp), a 320 dp list and a 360 dp page, the width of a small phone. A 10-inch tablet upright is
         * 800 dp; an unfolded book-style foldable upright (about 670–720 dp) stays one column, like a large phone.
         */
        const val LIST_DETAIL_FROM_DP = 760
        const val LIST_MIN_DP = 320
        const val LIST_MAX_DP = 420
        const val LIST_SHARE = 0.4
        const val CONTENT_MAX_DP = 840
        const val KEYPAD_MAX_DP = 560
    }
}

/**
 * What a detail pane shows, newest last. A tap in the list starts over with that item ([select]); a link inside the
 * pane (a relation, the contact a number belongs to) goes on top ([push]), and Back returns to the one before
 * ([back]) until the pane is empty. Items are plain strings, so the stack is kept in saved state as it is.
 */
data class PaneStack(val entries: List<String> = emptyList()) {
    val top: String? get() = entries.lastOrNull()
    val isEmpty: Boolean get() = entries.isEmpty()

    fun select(item: String): PaneStack = PaneStack(listOf(item))

    /** [item] on top; opening what is already shown changes nothing. The stack keeps at most [MAX] items. */
    fun push(item: String): PaneStack = if (top == item) this else PaneStack((entries + item).takeLast(MAX))

    fun back(): PaneStack = PaneStack(entries.dropLast(1))

    companion object {
        /** Enough for any chain of links someone follows by hand; older ones drop off the bottom. */
        const val MAX = 20
    }
}
