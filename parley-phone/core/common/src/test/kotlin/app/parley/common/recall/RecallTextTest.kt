package app.parley.common.recall

import app.parley.common.people.ContactSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecallTextTest {
    private fun q(s: String) = ContactSearch.Query(s)

    @Test fun marksIgnoreAccentsAndCase() {
        assertEquals(listOf(0..3), RecallText.marks(q("jose"), "José Lima"))
        assertEquals(listOf(0..3, 5..8), RecallText.marks(q("lima jose"), "José Lima"))
        // A letter that folds to two ("ß" is "ss") marks the one it came from.
        assertEquals(listOf(0..4), RecallText.marks(q("strass"), "Straße 4"))
    }

    @Test fun marksFindEveryPlaceAndJoinTouchingOnes() {
        assertEquals(listOf(0..1, 4..5), RecallText.marks(q("an"), "Ana Anne".substring(0, 6)))
        assertEquals(listOf(0..3), RecallText.merge(listOf(2..3, 0..1)))
        assertEquals(listOf(0..5, 8..9), RecallText.merge(listOf(0..3, 2..5, 8..9)))
    }

    @Test fun digitsAreMarkedThroughWhatNumbersAreWrittenWith() {
        assertEquals(listOf(5..11), RecallText.marks(q("912345"), "+351 912-345 678"))
        assertTrue(RecallText.numberMatches(q("912345"), "+351 912-345 678"))
        assertTrue(RecallText.numberMatches(q("ana 345"), "+351 912-345 678"))
        assertFalse(RecallText.numberMatches(q("ana"), "+351 912-345 678"))
    }

    @Test fun nothingMarkedForAnEmptyQuery() {
        assertEquals(emptyList<IntRange>(), RecallText.marks(q(""), "Ana"))
        assertTrue(RecallText.containsAll(q(""), "anything"))
        assertTrue(RecallText.containsAll(q("send bank"), "Send the invoice to the Bank"))
        assertFalse(RecallText.containsAll(q("send garden"), "Send the invoice to the Bank"))
    }

    @Test fun longNotesAreCutAroundTheMatch() {
        val text = "word ".repeat(40) + "the boiler needs a new valve " + "more ".repeat(40)
        val e = RecallText.excerpt(q("boiler"), text)
        assertTrue(e, e.startsWith("…") && e.endsWith("…"))
        assertTrue(e.contains("the boiler needs"))
        assertTrue(e.length <= RecallText.EXCERPT + 2)
        assertEquals("Short note", RecallText.excerpt(q("note"), "Short\nnote"))
    }
}
