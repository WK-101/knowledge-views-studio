package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissedCallsTest {
    private fun call(n: String, t: Long, sim: String? = null, hidden: Boolean = false) = MissedCall(n, t, sim, hidden, "k$n")

    @Test fun groups_by_caller_newest_first_with_counts() {
        val g = MissedCalls.group(listOf(call("1", 100), call("2", 300, "sim2"), call("1", 200, "sim1"), call("", 50, hidden = true), call("x", 40, hidden = true)))
        assertEquals(listOf("k2", "k1", MissedCalls.HIDDEN), g.map { it.key })
        val one = g[1]
        assertEquals(2, one.count)
        assertEquals(200L, one.latest)
        assertEquals(100L, one.first)
        assertEquals("sim1", one.accountId)
        assertEquals(2, g[2].count)
        assertTrue(g[2].hidden)
    }

    @Test fun titles() {
        assertEquals("Missed call", MissedCalls.title(1))
        assertEquals("3 missed calls", MissedCalls.title(3))
        assertEquals("5 missed calls from 3 callers", MissedCalls.summaryTitle(5, 3))
        assertEquals("2 missed calls", MissedCalls.summaryTitle(2, 1))
    }
}
