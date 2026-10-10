package app.parley.calls

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Call with a reason…" checks for an emergency number first, quickly, and fails towards placing the call. */
class CallReasonsEmergencyTest {
    @Test fun the_fallback_list_needs_no_platform_call() = runBlocking {
        var asked = false
        assertTrue(CallReasons.emergency("112") { asked = true; false })
        assertTrue(CallReasons.emergency("9 1 1") { asked = true; false })
        assertFalse("no binder call for a known emergency number", asked)
    }

    @Test fun the_platform_list_is_asked_next() = runBlocking {
        assertTrue(CallReasons.emergency("7777 777") { it == "7777 777" })
        assertFalse(CallReasons.emergency("+44 20 7946 0000") { false })
    }

    @Test fun a_hung_or_failing_check_places_the_call() = runBlocking {
        val started = System.nanoTime()
        assertTrue(CallReasons.emergency("+44 20 7946 0000") { awaitCancellation() })
        assertTrue((System.nanoTime() - started) / 1_000_000 < CallReasons.EMERGENCY_CHECK_MS * 10)
        assertTrue(CallReasons.emergency("+44 20 7946 0000") { error("binder died") })
    }
}
