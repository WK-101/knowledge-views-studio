package app.parley.common.circle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Circle is opt-in: its section elsewhere stays out of the way until someone is in it. */
class CircleSectionTest {
    @Test fun an_empty_circle_offers_no_suggestions_outside_its_tab() {
        assertFalse(CircleSuggestions.offerInSection(members = 0, suggestions = 5, dismissed = false, searching = false))
    }

    @Test fun a_used_circle_offers_suggestions_until_dismissed() {
        assertTrue(CircleSuggestions.offerInSection(members = 2, suggestions = 5, dismissed = false, searching = false))
        assertFalse(CircleSuggestions.offerInSection(members = 2, suggestions = 5, dismissed = true, searching = false))
    }

    @Test fun nothing_to_offer_or_a_search_shows_no_suggestions() {
        assertFalse(CircleSuggestions.offerInSection(members = 2, suggestions = 0, dismissed = false, searching = false))
        assertFalse(CircleSuggestions.offerInSection(members = 2, suggestions = 5, dismissed = false, searching = true))
    }
}
