package app.parley.common.circle

/**
 * What the Favourites surface (their tab, or the section or strip at the top of Contacts) shows under its
 * Favourites · Circle switch: the Circle's one home while the Circle tab is hidden. The Circle is opt-in, so the switch
 * shows only once someone is in it; until then the favourites are all there is, as before.
 */
enum class FavouritesView {
    FAVOURITES, CIRCLE;

    companion object {
        /** The switch is drawn: the layout puts it here ([hasSwitch]) and the Circle has [members]. */
        fun switchShown(hasSwitch: Boolean, members: Int): Boolean = hasSwitch && members > 0

        /** The side shown now: the last choice ([circleChosen]) while the switch is drawn, else the favourites. */
        fun of(hasSwitch: Boolean, members: Int, circleChosen: Boolean): FavouritesView =
            if (circleChosen && switchShown(hasSwitch, members)) CIRCLE else FAVOURITES
    }
}
