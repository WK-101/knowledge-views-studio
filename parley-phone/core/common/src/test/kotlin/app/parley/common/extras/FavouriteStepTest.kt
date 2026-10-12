package app.parley.common.extras

import org.junit.Assert.assertEquals
import org.junit.Test

/** "Add everyone to Favourites": who is starred, and whose Do Not Disturb record becomes the user's own star. */
class FavouriteStepTest {
    @Test fun an_unstarred_member_is_starred() {
        assertEquals(DndStars.FavouriteStep(star = true, claim = false), DndStars.favouriteStep(starred = false, device = true, recorded = false))
    }

    @Test fun a_member_starred_only_for_do_not_disturb_becomes_a_favourite_you_chose() {
        assertEquals(DndStars.FavouriteStep(star = false, claim = true), DndStars.favouriteStep(starred = true, device = true, recorded = true))
    }

    @Test fun a_record_left_beside_an_unstarred_member_goes_too_so_do_not_disturb_never_takes_the_new_star_back() {
        // Unstarred by hand in another app after Parley starred them: starring them now makes the star the user's.
        assertEquals(DndStars.FavouriteStep(star = true, claim = true), DndStars.favouriteStep(starred = false, device = true, recorded = true))
    }

    @Test fun a_private_contact_has_no_record_to_take() {
        assertEquals(DndStars.FavouriteStep(star = true, claim = false), DndStars.favouriteStep(starred = false, device = false, recorded = true))
    }

    @Test fun a_favourite_you_chose_is_left_alone() {
        assertEquals(DndStars.FavouriteStep(star = false, claim = false), DndStars.favouriteStep(starred = true, device = true, recorded = false))
    }
}
