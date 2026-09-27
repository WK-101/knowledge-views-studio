package app.parley.common.calls

import app.parley.common.DialText
import app.parley.common.PhoneNumbers
import app.parley.common.calltime.Ussd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DialTargetTest {
    @Test fun hash_codes_survive_every_step_before_dialling() {
        listOf("*100#", "#123*1#", "*#06#", "**21*+4915112345678#", "#31#0612345678", "*43#").forEach { code ->
            assertEquals(code, DialText.sanitize(code))
            if ('+' !in code) assertEquals(code, PhoneNumbers.clean(code))
            assertEquals(code, DialTarget.pick(code, topMatch = "+15550100"))
        }
        // A pasted number keeps a trailing '#' (voicemail PINs, extensions).
        assertEquals("0612345678,1234#", DialText.sanitize("06 12 34 56 78,1234#"))
    }

    @Test fun ussd_and_mmi_codes() {
        assertTrue(Ussd.isUssd("*100#"))
        assertTrue(Ussd.isUssd("#123*1#"))
        assertTrue(Ussd.isUssd("*123*2*1#"))
        // Supplementary services (forwarding, waiting, caller ID, IMEI) are dialled like a call, '#' included.
        assertFalse(Ussd.isUssd("**21*+4915112345678#"))
        assertFalse(Ussd.isUssd("*43#"))
        assertFalse(Ussd.isUssd("*#06#"))
        assertFalse(Ussd.isUssd("#31#0612345678"))
        assertTrue(PhoneNumbers.isServiceCode("*#06#"))
        assertEquals("4636", DialCodes.secretCode("*#*#4636#*#*"))
        assertNull(DialCodes.secretCode("*#06#"))
        assertFalse(Ussd.isUssd("*#*#4636#*#*"))
    }

    @Test fun call_button_dials_the_typed_number_not_the_top_match() {
        // "555" matches Ana's +1 555 0100 first: the Call button still dials 555.
        assertEquals("555", DialTarget.pick("555", topMatch = "+15550100"))
        assertEquals("0612", DialTarget.pick(" 0612 ", topMatch = "+33612345678"))
        assertEquals("+33612345678;42", DialTarget.pick("+33612345678;42", topMatch = "+33612345678"))
        // Only a name typed on a hardware keyboard calls the best match.
        assertEquals("+15550100", DialTarget.pick("ana", topMatch = "+15550100"))
        assertNull(DialTarget.pick("ana", topMatch = null))
        assertNull(DialTarget.pick("  ", topMatch = "+15550100"))
    }
}
