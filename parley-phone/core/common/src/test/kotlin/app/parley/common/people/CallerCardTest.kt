package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallerCardTest {
    @Test fun caller_card_lines_respect_discreet_mode() {
        assertEquals("Plumber · Acme", CallerCard.subtitle("Plumber", "Acme"))
        assertEquals("Acme", CallerCard.subtitle(" ", "Acme"))
        assertNull(CallerCard.subtitle(null, ""))
        assertEquals("Fixed the boiler", CallerCard.context("  Fixed \n the   boiler "))
        assertEquals(10, CallerCard.context("x".repeat(50), 10)!!.length)
        assertNull(CallerCard.missedCallLine(isPrivate = true, hideVault = true, subtitle = "Acme", context = "Plumber"))
        assertEquals("Plumber", CallerCard.missedCallLine(isPrivate = true, hideVault = false, subtitle = "Acme", context = "Plumber"))
        assertEquals("Acme", CallerCard.missedCallLine(isPrivate = false, hideVault = true, subtitle = "Acme", context = null))
    }
}
