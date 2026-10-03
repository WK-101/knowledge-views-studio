package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHandOffTest {
    private fun facts(ringing: Boolean = true, emergency: Boolean = false, conference: Boolean = false, deflect: Boolean = true) =
        CallHandOff.Facts(ringing, emergency, conference, deflect)

    @Test fun deflect_only_while_ringing_and_where_the_network_supports_it() {
        assertTrue(CallHandOff.deflectOffered(facts()))
        assertFalse(CallHandOff.deflectOffered(facts(deflect = false)))
        assertFalse(CallHandOff.deflectOffered(facts(ringing = false)))
        assertFalse(CallHandOff.deflectOffered(facts(emergency = true)))
        assertFalse(CallHandOff.deflectOffered(facts(conference = true)))
    }

    @Test fun the_target_is_a_dialable_number() {
        assertEquals("+441632960123", CallHandOff.target(" +44 (1632) 960-123 "))
        assertEquals("*2134#", CallHandOff.target("*2134#"))
        assertEquals("0123456", CallHandOff.target("0123,456"))
        assertNull(CallHandOff.target("12"))
        assertNull(CallHandOff.target("  "))
        assertNull(CallHandOff.target(null))
        assertNull(CallHandOff.target("call me"))
        // Digits from other scripts become ASCII; a + only counts in front.
        assertEquals("0123", CallHandOff.target("٠١٢٣"))
        assertEquals("1234", CallHandOff.target("12+34"))
    }

    private val saved = listOf(
        VerifyCallBack.Saved("Sam Taylor", "+44 7700 900123", "Mobile"),
        VerifyCallBack.Saved("Ana Lopez", "+34 600 111 222"),
        VerifyCallBack.Saved("Ana Lopez", "+34600111222"),
        VerifyCallBack.Saved("Reception", "020 7946 0000", organisation = true),
        VerifyCallBack.Saved("No number", ""),
    )

    @Test fun matches_by_name_word_or_digits() {
        assertEquals(listOf("Ana Lopez", "Reception", "Sam Taylor"), CallHandOff.matches("", saved).map { it.name })
        assertEquals(listOf("Sam Taylor"), CallHandOff.matches("tay", saved).map { it.name })
        assertEquals(listOf("Ana Lopez"), CallHandOff.matches("an", saved).map { it.name })
        assertEquals(listOf("Reception"), CallHandOff.matches("7946", saved).map { it.name })
        assertEquals(listOf("Sam Taylor"), CallHandOff.matches("900 12", saved).map { it.name })
        assertTrue(CallHandOff.matches("zz", saved).isEmpty())
        assertEquals(1, CallHandOff.matches("", saved, limit = 1).size)
    }
}
