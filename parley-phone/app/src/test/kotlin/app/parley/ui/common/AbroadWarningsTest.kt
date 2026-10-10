package app.parley.ui.common

import app.parley.PendingCall
import app.parley.common.calls.AssistedDial
import app.parley.data.DialWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Once the converted number is taken, the dial guard's warnings are the ones for that number. */
class AbroadWarningsTest {
    private val plan = AssistedDial.Plan("+19005550123", "+1 900-555-0123", "US", "MX", alsoLocal = false)
    private val premium = DialWarning("Premium-rate number", "Calls to this number can cost a lot per minute, on top of your plan.", severe = true)
    private val typed = DialWarning("Listed number", "Reported by others.")

    @Test fun the_converted_number_brings_its_own_warnings() {
        val p = PendingCall("900 555 0123", null, false, false, warnings = listOf(typed), abroad = plan, abroadWarnings = listOf(premium))
        val next = p.dialling(plan)
        assertEquals("+19005550123", next.number)
        assertNull(next.abroad)
        assertEquals(listOf(premium), next.warnings)
        assertTrue(next.abroadWarnings.isEmpty())
    }

    @Test fun no_warnings_for_the_converted_number_means_none_are_shown() {
        val p = PendingCall("201 555 0123", null, false, false, warnings = listOf(typed), abroad = plan)
        assertTrue(p.dialling(plan).warnings.isEmpty())
        // "Dial as typed" keeps the typed number's warnings.
        assertEquals(listOf(typed), p.copy(abroad = null).warnings)
    }
}
