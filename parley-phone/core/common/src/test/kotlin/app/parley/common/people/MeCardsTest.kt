package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeCardsTest {
    @Test fun me_card_merges_parley_and_profile_and_makes_a_vcard() {
        val own = MeCards.fromMyDetails("Anna Maria Smith", "+44 7700 900123")
        val profile = MeCard(name = "A. Smith", phones = listOf("+447700900123", "+441234"), emails = listOf("Anna@X.org"), company = "Acme")
        val m = MeCards.merge(own, profile)
        assertEquals("Anna Maria Smith", m.name)
        assertEquals(listOf("+44 7700 900123", "+441234"), m.phones)
        assertEquals("Acme", m.company)
        val v = MeCards.vcard(m.copy(note = "secret"), setOf(MeCards.Part.NAME, MeCards.Part.PHONES))
        assertTrue(v.contains("N:Smith;Anna Maria;;;"))
        assertTrue(v.contains("TEL;TYPE=CELL:+44 7700 900123"))
        assertFalse("Only the chosen parts are shared", v.contains("Acme"))
        assertFalse("The private note is never shared", v.contains("secret"))
        assertEquals("a\\,b\\;c", MeCards.esc("a,b;c"))
        assertTrue(MeCard().isEmpty)
    }
}
