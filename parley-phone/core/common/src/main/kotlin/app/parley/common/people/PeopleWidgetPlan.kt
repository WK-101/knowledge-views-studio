package app.parley.common.people

/**
 * The People widget: one home-screen widget that shows your Favourites or your Circle, chosen when it is placed (and
 * again from its settings). It took over the Favourites widget's place in the widget list, so every Favourites widget
 * already placed is a People widget showing Favourites. A Circle widget placed before keeps its own entry, drawn by the
 * same code in Circle mode, and that entry leaves the widget list once none is placed. Like both before, it never
 * shows private contacts: the launcher, another app, draws it.
 */
object PeopleWidgetPlan {
    enum class Mode { FAVOURITES, CIRCLE }

    /**
     * The mode of a widget: Circle for one placed as a Circle widget ([legacyCircle]), else the [stored] choice;
     * Favourites when nothing (or nothing readable) was stored, as for every Favourites widget placed before.
     */
    fun mode(stored: String?, legacyCircle: Boolean): Mode = when {
        legacyCircle -> Mode.CIRCLE
        else -> Mode.entries.firstOrNull { it.name == stored } ?: Mode.FAVOURITES
    }

    /** Whether the old Circle widget's entry stays in the widget list: only while one of them is still placed. */
    fun keepCircleEntry(placed: Int): Boolean = placed > 0
}
