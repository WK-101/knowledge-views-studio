package app.parley.common.people

import app.parley.common.people.PeopleWidgetPlan.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleWidgetPlanTest {
    @Test fun widgets_placed_before_keep_what_they_showed() {
        // A Favourites widget from before has no mode stored: it still shows Favourites.
        assertEquals(Mode.FAVOURITES, PeopleWidgetPlan.mode(null, legacyCircle = false))
        // A Circle widget from before stays a Circle widget, whatever is stored.
        assertEquals(Mode.CIRCLE, PeopleWidgetPlan.mode(null, legacyCircle = true))
        assertEquals(Mode.CIRCLE, PeopleWidgetPlan.mode("FAVOURITES", legacyCircle = true))
    }

    @Test fun a_new_widget_shows_what_was_chosen() {
        assertEquals(Mode.CIRCLE, PeopleWidgetPlan.mode("CIRCLE", legacyCircle = false))
        assertEquals(Mode.FAVOURITES, PeopleWidgetPlan.mode("FAVOURITES", legacyCircle = false))
        // Something unreadable (a newer version's mode, after a downgrade) falls back to Favourites.
        assertEquals(Mode.FAVOURITES, PeopleWidgetPlan.mode("LABEL", legacyCircle = false))
    }
}
