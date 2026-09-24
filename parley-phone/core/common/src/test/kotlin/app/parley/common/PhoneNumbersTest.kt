package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {
    @Test fun e164_international_forms() {
        assertEquals("+33612345678", PhoneNumbers.toE164("+33 6 12 34 56 78", "FR"))
        assertEquals("+33612345678", PhoneNumbers.toE164("0033612345678", "FR"))
        assertEquals("+33612345678", PhoneNumbers.toE164("06 12 34 56 78", "FR"))
    }

    @Test fun e164_nanp() {
        assertEquals("+14155550123", PhoneNumbers.toE164("(415) 555-0123", "US"))
        assertEquals("+14155550123", PhoneNumbers.toE164("1-415-555-0123", "US"))
        assertEquals("+442071234567", PhoneNumbers.toE164("011 44 20 7123 4567", "US"))
    }

    @Test fun e164_italy_keeps_zero() {
        assertEquals("+390612345678", PhoneNumbers.toE164("06 1234 5678", "IT"))
    }

    @Test fun e164_rejects_short_and_service_codes() {
        assertNull(PhoneNumbers.toE164("112", "DE"))
        assertNull(PhoneNumbers.toE164("*100#", "IN"))
    }

    @Test fun e164_regional_prefixes() {
        assertEquals("+442071234567", PhoneNumbers.toE164("0011 44 20 7123 4567", "AU"))
        assertEquals("+61412345678", PhoneNumbers.toE164("0412 345 678", "AU"))
        assertEquals("+79161234567", PhoneNumbers.toE164("8 916 123-45-67", "RU"))
        assertEquals("+33612345678", PhoneNumbers.toE164("33612345678", "FR"))
        assertEquals("+34612345678", PhoneNumbers.toE164("612 34 56 78", "ES"))
        assertEquals("+36301234567", PhoneNumbers.toE164("06 30 123 4567", "HU"))
    }

    @Test fun same_number_across_formats() {
        assertTrue(PhoneNumbers.same("+33 6 12 34 56 78", "06 12 34 56 78", "FR"))
        assertTrue(PhoneNumbers.same("+33612345678", "0612345678", null))
        assertFalse(PhoneNumbers.same("0612345678", "0612345679", "FR"))
    }

    @Test fun letters_are_converted() {
        assertEquals("18003569377", PhoneNumbers.clean("1-800-FLOWERS"))
    }

    @Test fun service_codes() {
        assertTrue(PhoneNumbers.isServiceCode("*#06#"))
        assertFalse(PhoneNumbers.isServiceCode("+4912345"))
    }

    @Test fun neighbour_spoof() {
        assertTrue(PhoneNumbers.looksLikeNeighbourSpoof("+14155550999", listOf("+14155550123"), "US"))
        assertFalse(PhoneNumbers.looksLikeNeighbourSpoof("+14155550123", listOf("+14155550123"), "US"))
        assertFalse(PhoneNumbers.looksLikeNeighbourSpoof("+12125550123", listOf("+14155550123"), "US"))
    }
}
