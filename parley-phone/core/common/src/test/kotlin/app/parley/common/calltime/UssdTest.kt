package app.parley.common.calltime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UssdTest {
    @Test fun balance_codes_are_ussd() {
        listOf("*100#", "#123#", "*123*1#", "*555*2*1#", " *101# ").forEach { assertTrue(it, Ussd.isUssd(it)) }
    }

    @Test fun supplementary_services_and_others_are_not() {
        listOf(
            "*#06#", "**21*0612345678#", "##21#", "*#21#", "*43#", "#31#", "*31#", "**04*1234*5678*5678#",
            "*#*#4636#*#*", "0612345678", "*31#0612345678", "*#", "##", "#",
        ).forEach { assertFalse(it, Ussd.isUssd(it)) }
    }

    @Test fun tidy_and_history() {
        assertEquals("Balance: 5.00\nValid till 30/09", Ussd.tidy("\n Balance: 5.00  \nValid till 30/09\n\n"))
        val h = (1..60).fold(emptyList<UssdEntry>()) { acc, i -> Ussd.append(acc, UssdEntry("*$i#", "r", i.toLong(), true)) }
        assertEquals(50, h.size)
        assertEquals("*60#", h.first().code)
    }
}
