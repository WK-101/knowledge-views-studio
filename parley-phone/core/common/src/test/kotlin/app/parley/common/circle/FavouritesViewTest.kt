package app.parley.common.circle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouritesViewTest {
    @Test fun the_switch_shows_only_where_the_layout_puts_it_and_once_the_circle_has_people() {
        assertTrue(FavouritesView.switchShown(hasSwitch = true, members = 1))
        assertFalse(FavouritesView.switchShown(hasSwitch = true, members = 0))
        // The Circle tab shown: the tab is its home, no switch.
        assertFalse(FavouritesView.switchShown(hasSwitch = false, members = 5))
    }

    @Test fun the_last_choice_is_kept_but_never_shows_an_empty_or_absent_circle() {
        assertEquals(FavouritesView.CIRCLE, FavouritesView.of(hasSwitch = true, members = 2, circleChosen = true))
        assertEquals(FavouritesView.FAVOURITES, FavouritesView.of(hasSwitch = true, members = 2, circleChosen = false))
        assertEquals(FavouritesView.FAVOURITES, FavouritesView.of(hasSwitch = true, members = 0, circleChosen = true))
        assertEquals(FavouritesView.FAVOURITES, FavouritesView.of(hasSwitch = false, members = 2, circleChosen = true))
    }

    @Test fun the_choice_is_stored_under_the_old_fold_so_nothing_new_is_written() {
        val json = CircleConfig.encode(CircleConfig(circleChosen = true))
        assertTrue(json, json.contains("\"favoritesSectionCollapsed\":true"))
        val old = CircleConfig.decode("""{"favoritesSectionCollapsed":true}""")
        assertTrue(old.circleChosen)
    }
}
