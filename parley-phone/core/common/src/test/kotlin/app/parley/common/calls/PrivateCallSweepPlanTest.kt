package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateCallSweepPlanTest {
    @Test fun the_first_check_is_at_once_and_the_checks_grow_within_the_window() {
        val checks = PrivateCallSweepPlan.CHECKS_MS
        assertEquals(0L, checks.first())
        assertTrue("sub-second first retries", checks[1] < 1_000L)
        assertEquals(checks.sorted(), checks)
        assertTrue(checks.all { PrivateCallSweepPlan.watching(it) })
    }

    @Test fun a_marker_sweeps_back_far_enough_for_a_long_call() {
        val end = 10L * 60 * 60 * 1000
        assertEquals(end - PrivateCallSweepPlan.LOOK_BACK_MS, PrivateCallSweepPlan.sinceFor(end))
        assertEquals(0L, PrivateCallSweepPlan.sinceFor(1_000L))
    }

    @Test fun watching_stops_after_the_window() {
        assertFalse(PrivateCallSweepPlan.watching(PrivateCallSweepPlan.WINDOW_MS + 1))
        assertFalse(PrivateCallSweepPlan.watching(-1))
    }
}
