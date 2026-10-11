package app.parley.common.calls

import app.parley.common.SimAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimChoiceTest {
    private val work = SimAccount("a", "Work", null, 0xFF2196F3.toInt(), 0)
    private val personal = SimAccount("b", "Vodafone", null, 0, 1)

    @Test fun cards_keep_the_phones_order_and_mark_only_the_suggested_sim() {
        val cards = SimChoice.cards(listOf(work, personal), emptyMap(), emptySet(), SimSuggestion("b", SimReason.FEWER_DROPS))
        assertEquals(listOf("a", "b"), cards.map { it.id })
        assertNull(cards[0].suggested)
        assertEquals(SimReason.FEWER_DROPS, cards[1].suggested)
        // A suggestion for a SIM that's gone marks nothing.
        val gone = SimChoice.cards(listOf(work, personal), emptyMap(), emptySet(), SimSuggestion("x", SimReason.FEWER_DROPS))
        assertEquals(listOf(null, null), gone.map { it.suggested })
    }

    @Test fun the_carrier_shows_only_when_it_says_more_than_the_name() {
        val lines = mapOf("a" to SimLine(carrier = "EE", number = " +44 7700 900123 "), "b" to SimLine(carrier = "vodafone", number = ""))
        val cards = SimChoice.cards(listOf(work, personal), lines, setOf("b"), null)
        assertEquals("EE", cards[0].carrier)
        assertEquals("+44 7700 900123", cards[0].number)
        assertNull(cards[1].carrier)
        assertNull(cards[1].number)
        assertEquals(listOf(false, true), cards.map { it.roaming })
    }
}
