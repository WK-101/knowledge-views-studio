package app.parley.common.people

import app.parley.common.people.PeopleWidgetPlan.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleWidgetPlanTest {
    @Test fun widgets_placed_before_keep_what_they_showed() {
        // A Favourites widget from before has no mode stored: it still shows Favourites.
        assertEquals(Mode.FAVOURITES, PeopleWidgetPlan.mode(null))
    }

    @Test fun a_new_widget_shows_what_was_chosen() {
        assertEquals(Mode.CIRCLE, PeopleWidgetPlan.mode("CIRCLE"))
        assertEquals(Mode.FAVOURITES, PeopleWidgetPlan.mode("FAVOURITES"))
        // Something unreadable (a newer version's mode, after a downgrade) falls back to Favourites.
        assertEquals(Mode.FAVOURITES, PeopleWidgetPlan.mode("LABEL"))
    }

    @Test fun the_circle_fits_its_size_and_is_never_cut_off() {
        // 1×1 in portrait, or too low for one person (1×1 in landscape): the compact drawing.
        assertTrue(PeopleWidgetPlan.circleFit(57, 102, people = 3, dates = 3).compact)
        assertTrue(PeopleWidgetPlan.circleFit(127, 51, people = 3, dates = 3).compact)
        // 2×1: one row, no title.
        assertEquals(PeopleWidgetPlan.CircleFit(compact = false, title = false, people = 1, dates = 0), PeopleWidgetPlan.circleFit(130, 102, 3, 3))
        // 2×2 and 4×2: the title, people, then dates as room allows.
        assertEquals(PeopleWidgetPlan.CircleFit(compact = false, title = true, people = 3, dates = 1), PeopleWidgetPlan.circleFit(130, 220, 3, 3))
        assertEquals(PeopleWidgetPlan.CircleFit(compact = false, title = true, people = 1, dates = 2), PeopleWidgetPlan.circleFit(130, 220, 1, 2))
        // Everything drawn fits, at every size.
        for (w in 40..600 step 9) for (h in 40..600 step 9) {
            val f = PeopleWidgetPlan.circleFit(w, h, 3, 3)
            if (f.compact) continue
            val used = PeopleWidgetPlan.CIRCLE_PADDING_DP + (if (f.title) PeopleWidgetPlan.CIRCLE_TITLE_DP else 0) +
                f.people * PeopleWidgetPlan.CIRCLE_PERSON_DP +
                (if (f.dates > 0) PeopleWidgetPlan.CIRCLE_DATES_TITLE_DP + f.dates * PeopleWidgetPlan.CIRCLE_DATE_DP else 0)
            assertTrue("$w×$h: $f", used <= h)
        }
    }
}
