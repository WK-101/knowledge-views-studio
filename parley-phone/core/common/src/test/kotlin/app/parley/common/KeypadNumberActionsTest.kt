package app.parley.common

import app.parley.common.KeypadNumberActions.Action.ADD_TO_CONTACT
import app.parley.common.KeypadNumberActions.Action.CREATE_CONTACT
import app.parley.common.KeypadNumberActions.Action.MESSAGE_OR_CALL
import app.parley.common.KeypadNumberActions.Action.SAVE_TEMPORARY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeypadNumberActionsTest {
    private fun place(typed: String, contactMatches: Boolean = false, known: Boolean = false, textSearch: Boolean = false, serviceCode: Boolean = false) =
        KeypadNumberActions.place(typed, textSearch, serviceCode, contactMatches, known)

    @Test fun no_match_puts_every_action_in_the_list_once_and_no_chips() {
        val p = place("0000")
        assertEquals(listOf(MESSAGE_OR_CALL, CREATE_CONTACT, ADD_TO_CONTACT, SAVE_TEMPORARY), p.rows)
        assertTrue(p.chips.isEmpty())
    }

    @Test fun contact_matches_move_the_actions_to_the_chips() {
        val p = place("0612", contactMatches = true)
        assertEquals(listOf(MESSAGE_OR_CALL, CREATE_CONTACT, ADD_TO_CONTACT, SAVE_TEMPORARY), p.chips)
        assertTrue(p.rows.isEmpty())
    }

    @Test fun a_saved_number_can_only_be_reached() {
        assertEquals(listOf(MESSAGE_OR_CALL), place("0612345678", contactMatches = true, known = true).chips)
    }

    @Test fun short_numbers_are_not_offered_for_saving() {
        assertEquals(listOf(MESSAGE_OR_CALL), place("06").rows)
        assertEquals(listOf(MESSAGE_OR_CALL), place("+4", contactMatches = true).chips)
        // Three digits, however they're formatted.
        assertEquals(4, place("+1 2-3").rows.size)
    }

    @Test fun nothing_for_empty_input_name_search_or_codes() {
        assertEquals(KeypadNumberActions.Placement.NONE, place(""))
        assertEquals(KeypadNumberActions.Placement.NONE, place("  "))
        assertEquals(KeypadNumberActions.Placement.NONE, place("ana", textSearch = true))
        assertEquals(KeypadNumberActions.Placement.NONE, place("*#06#", serviceCode = true))
    }

    @Test fun no_action_ever_shows_in_both_places() {
        for (matches in listOf(false, true)) for (known in listOf(false, true)) for (typed in listOf("1", "123", "0612345678")) {
            val p = place(typed, contactMatches = matches, known = known && matches)
            assertTrue(p.rows.isEmpty() || p.chips.isEmpty())
            assertEquals(p.rows.distinct(), p.rows)
            assertEquals(p.chips.distinct(), p.chips)
        }
    }
}
