package app.parley.common.people

import app.parley.common.ContactSummary

/**
 * What the Favourites widget shows: a grid of favourites in the order of the Favourites tab, as many as fit its size.
 * The launcher (another app) draws the widget, so it only ever gets phone contacts: private contacts are never in it,
 * and with the app lock on, a locked phone shows only how many favourites there are, never names or photos.
 */
object FavoritesWidgetPlan {
    /** What a tap on a person does (chosen when the widget is placed, or later in its settings). */
    enum class Tap { CALL, OPEN }

    /** One person in the grid. [number]: what a tap calls (null: the tap opens their page instead). */
    data class Tile(val contactId: Long, val name: String, val number: String?, val photoUri: String?)

    data class Grid(val columns: Int, val rows: Int) {
        val capacity: Int get() = columns * rows
    }

    sealed interface Shown {
        /** App lock on and the phone locked: a count, nothing else. */
        data class Locked(val count: Int) : Shown

        /** No favourites yet. */
        data object Empty : Shown

        /** [more]: favourites that didn't fit. */
        data class Tiles(val tiles: List<Tile>, val more: Int) : Shown
    }

    /** About one person per 72 dp across and 80 dp down (photo, name and a margin), after the title line. */
    const val CELL_WIDTH_DP = 72
    const val CELL_HEIGHT_DP = 80
    const val TITLE_DP = 28
    const val MAX_COLUMNS = 6
    const val MAX_ROWS = 5

    /** The grid for a widget [widthDp]×[heightDp] (unknown sizes, 0 or less, give a 4×2 grid). */
    fun grid(widthDp: Int, heightDp: Int): Grid {
        val columns = if (widthDp > 0) (widthDp / CELL_WIDTH_DP).coerceIn(1, MAX_COLUMNS) else 4
        val rows = if (heightDp > 0) ((heightDp - TITLE_DP) / CELL_HEIGHT_DP).coerceIn(1, MAX_ROWS) else 2
        return Grid(columns, rows)
    }

    /**
     * The people to show from [favourites] (already in the Favourites tab's order): phone contacts only, in that
     * order, at most [capacity]. A favourite without a number is still shown; its tap opens their page.
     */
    fun select(favourites: List<ContactSummary>, capacity: Int): List<Tile> =
        eligible(favourites).take(capacity.coerceAtLeast(0)).map { c ->
            Tile(c.id, c.displayName, (c.phones.firstOrNull { it.isPrimary } ?: c.phones.firstOrNull())?.number, c.photoUri)
        }

    /** What the widget shows for [favourites] in [grid]; [locked]: app lock on and the phone locked. */
    fun shown(favourites: List<ContactSummary>, grid: Grid, locked: Boolean): Shown {
        val all = eligible(favourites)
        if (locked) return Shown.Locked(all.size)
        if (all.isEmpty()) return Shown.Empty
        val tiles = select(all, grid.capacity)
        return Shown.Tiles(tiles, all.size - tiles.size)
    }

    /** Starred phone contacts; a private contact (negative id) never reaches the launcher. */
    private fun eligible(favourites: List<ContactSummary>): List<ContactSummary> =
        favourites.filter { it.starred && it.id > 0 && !ContactRef.isPrivateKey(it.lookupKey) }
}
