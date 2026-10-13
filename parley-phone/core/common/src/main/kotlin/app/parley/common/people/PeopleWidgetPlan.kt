package app.parley.common.people

import app.parley.common.widgets.WidgetShape

/**
 * The People widget: one home-screen widget that shows your Favourites or your Circle, chosen when it is placed (and
 * again from its settings). It took over the Favourites widget's place in the widget list, so every Favourites widget
 * already placed is a People widget showing Favourites. The separate Circle widget is gone (its placed widgets went
 * with it on the update; Circle mode replaces it). Like both before, it never shows private contacts: the launcher,
 * another app, draws it.
 */
object PeopleWidgetPlan {
    enum class Mode { FAVOURITES, CIRCLE }

    /** The mode of a widget: the [stored] choice; Favourites when nothing (or nothing readable) was stored. */
    fun mode(stored: String?): Mode = Mode.entries.firstOrNull { it.name == stored } ?: Mode.FAVOURITES

    /**
     * What Circle mode shows at one size: [compact] (one icon and a label: the first person due, or "Circle"), else the
     * [title] line, [people] person rows (name, why, Call) and [dates] date rows under "Coming up".
     */
    data class CircleFit(val compact: Boolean, val title: Boolean, val people: Int, val dates: Int)

    /** Heights in dp of the Circle mode's parts, as its layout draws them. */
    const val CIRCLE_PADDING_DP = 16
    const val CIRCLE_TITLE_DP = 20
    const val CIRCLE_PERSON_DP = 46
    const val CIRCLE_DATES_TITLE_DP = 26
    const val CIRCLE_DATE_DP = 20
    const val CIRCLE_MAX_PEOPLE = 3
    const val CIRCLE_MAX_DATES = 3

    /** A height to plan for when the launcher gives none. */
    private const val UNKNOWN_HEIGHT_DP = 180

    /**
     * What fits in a Circle widget [widthDp]×[heightDp] of [people] due and [dates] coming up: nothing is drawn that
     * would be cut off. One cell across, or too low for a person row, gets the compact drawing.
     */
    fun circleFit(widthDp: Int, heightDp: Int, people: Int, dates: Int): CircleFit {
        val shape = WidgetShape.of(widthDp, heightDp)
        val inside = (if (heightDp > 0) heightDp else UNKNOWN_HEIGHT_DP) - CIRCLE_PADDING_DP
        if (shape == WidgetShape.TILE || inside < CIRCLE_PERSON_DP) return CircleFit(compact = true, title = false, people = 0, dates = 0)
        val title = shape == WidgetShape.BLOCK
        var room = inside - if (title) CIRCLE_TITLE_DP else 0
        val shownPeople = minOf(people, CIRCLE_MAX_PEOPLE, room / CIRCLE_PERSON_DP)
        room -= shownPeople * CIRCLE_PERSON_DP
        val shownDates = if (dates > 0 && room >= CIRCLE_DATES_TITLE_DP + CIRCLE_DATE_DP) {
            minOf(dates, CIRCLE_MAX_DATES, (room - CIRCLE_DATES_TITLE_DP) / CIRCLE_DATE_DP)
        } else {
            0
        }
        return CircleFit(compact = false, title = title, people = shownPeople, dates = shownDates)
    }
}
