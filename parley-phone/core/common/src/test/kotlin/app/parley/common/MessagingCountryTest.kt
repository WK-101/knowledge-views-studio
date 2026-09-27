package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MessagingCountryTest {
    @Test fun unavailable_reason_matches_link_building() {
        assertNull(MessengerLinks.unavailableReason("+923001234567"))
        assertTrue(MessengerLinks.unavailableReason(null) != null)
        assertTrue(MessengerLinks.unavailableReason("+12345") != null) // too short: the link would be null
        assertTrue(MessengerLinks.unavailableReason("+1234567890123456") != null) // too long
        for (app in MessengerApp.entries) {
            for (n in listOf("+923001234567", "+12345", "+1234567890123456")) {
                assertEquals(MessengerLinks.unavailableReason(n) == null, MessengerLinks.build(app, n) != null)
            }
        }
    }

    @Test fun country_picker_search() {
        val all = NumberText.regions(Locale.ENGLISH)
        assertTrue(all.size > 200)
        assertTrue(NumberText.searchRegions(all, "fran").any { it.code == "FR" })
        assertTrue(NumberText.searchRegions(all, "+33").any { it.code == "FR" })
        assertTrue(NumberText.searchRegions(all, "pk").any { it.code == "PK" })
        assertEquals(all, NumberText.searchRegions(all, " "))
        // A national number reads differently with another country.
        assertEquals("+923001234567", NumberText.toE164("0300 1234567", "PK"))
        assertNotEquals(NumberText.toE164("0300 1234567", "PK"), NumberText.toE164("0300 1234567", "GB"))
    }
}
