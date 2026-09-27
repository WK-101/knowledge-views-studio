package app.parley.common.calls

import app.parley.common.calls.EmergencyPolicy.Facts
import app.parley.common.calls.EmergencyPolicy.Safeguard
import app.parley.common.calls.EmergencyPolicy.WindowMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyPolicyTest {
    private val hour = EmergencyPolicy.WINDOW_MS

    @Test fun an_emergency_call_bypasses_every_safeguard() {
        for (f in listOf(Facts(emergencyNumber = true), Facts(emergencyCallProperty = true))) {
            for (s in Safeguard.entries) assertTrue("$s $f", EmergencyPolicy.bypasses(s, f))
        }
    }

    @Test fun an_ordinary_call_keeps_every_safeguard() {
        for (s in Safeguard.entries) assertFalse("$s", EmergencyPolicy.bypasses(s, Facts()))
    }

    @Test fun the_window_lifts_screening_and_limits_but_not_the_users_own_questions() {
        val w = Facts(inWindow = true)
        assertTrue(EmergencyPolicy.bypasses(Safeguard.SCREENING, w))
        assertTrue(EmergencyPolicy.bypasses(Safeguard.CALL_LIMITS, w))
        assertTrue(EmergencyPolicy.bypasses(Safeguard.CALL_TIME_ALLOWANCE, w))
        for (s in listOf(Safeguard.CONFIRM_BEFORE_CALL, Safeguard.SIM_CHOICE, Safeguard.DIAL_GUARD, Safeguard.POCKET_GUARD)) {
            assertFalse("$s", EmergencyPolicy.bypasses(s, w))
        }
    }

    @Test fun a_listed_number_is_never_limited_but_otherwise_ordinary() {
        val gp = Facts(userListed = true)
        assertTrue(EmergencyPolicy.bypasses(Safeguard.CALL_LIMITS, gp))
        assertTrue(EmergencyPolicy.bypasses(Safeguard.CALL_TIME_ALLOWANCE, gp))
        assertFalse(EmergencyPolicy.bypasses(Safeguard.SCREENING, gp))
        assertFalse(EmergencyPolicy.bypasses(Safeguard.CONFIRM_BEFORE_CALL, gp))
    }

    @Test fun what_starts_the_window() {
        assertTrue(EmergencyPolicy.startsWindow(Facts(emergencyNumber = true), incoming = false))
        assertTrue(EmergencyPolicy.startsWindow(Facts(emergencyNumber = true), incoming = true))
        // Emergency callback mode: the operator calling back keeps the window open.
        assertTrue(EmergencyPolicy.startsWindow(Facts(emergencyCallProperty = true), incoming = true))
        assertTrue(EmergencyPolicy.startsWindow(Facts(userListed = true), incoming = false))
        assertFalse(EmergencyPolicy.startsWindow(Facts(userListed = true), incoming = true))
        assertFalse(EmergencyPolicy.startsWindow(Facts(inWindow = true), incoming = true))
        assertFalse(EmergencyPolicy.startsWindow(Facts(), incoming = false))
    }

    @Test fun the_window_runs_on_the_monotonic_clock() {
        val mark = WindowMark(elapsedMs = 10_000, bootCount = 3, wallMs = 1_000_000)
        assertEquals(hour, EmergencyPolicy.windowLeftMs(mark, 10_000, 3, 1_000_000))
        assertEquals(hour - 60_000, EmergencyPolicy.windowLeftMs(mark, 70_000, 3, 1_060_000))
        // The clock set back or forward a day: the window still runs by elapsed time.
        assertEquals(hour - 60_000, EmergencyPolicy.windowLeftMs(mark, 70_000, 3, 1_060_000 + 86_400_000))
        assertEquals(hour - 60_000, EmergencyPolicy.windowLeftMs(mark, 70_000, 3, 0))
        assertNull(EmergencyPolicy.windowLeftMs(mark, 10_000 + hour + 1, 3, 1_000_000))
        assertNull(EmergencyPolicy.windowLeftMs(null, 0, 3, 0))
    }

    @Test fun after_a_reboot_the_wall_clock_is_used() {
        val mark = WindowMark(elapsedMs = 5_000_000, bootCount = 3, wallMs = 1_000_000)
        assertEquals(hour - 120_000, EmergencyPolicy.windowLeftMs(mark, 30_000, 4, 1_120_000))
        assertNull(EmergencyPolicy.windowLeftMs(mark, 30_000, 4, 1_000_000 + hour + 1))
        // Marks from older versions (no boot count) use the wall clock too.
        val old = WindowMark(elapsedMs = 0, bootCount = -1, wallMs = 1_000_000)
        assertEquals(hour - 1_000, EmergencyPolicy.windowLeftMs(old, 999_999, 4, 1_001_000))
    }

    @Test fun fallback_numbers() {
        for (n in listOf("112", "911", "999", "000", "08", "110", "118", "119", "1 1 2", "(911)")) assertTrue(n, EmergencyPolicy.isFallbackEmergencyNumber(n))
        for (n in listOf(null, "", "1120", "+112", "*112#", "0612345678", "15", "17")) assertFalse("$n", EmergencyPolicy.isFallbackEmergencyNumber(n))
    }
}
