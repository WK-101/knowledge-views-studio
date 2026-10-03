package app.parley.common.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallTallyTest {
    private val gb = "GB"
    private fun call(n: String, at: Long, name: String? = null) = NumberMemory.PastCall(n, at, name)

    private val calls = listOf(
        call("07700 900123", 100, "Plumber Mike"),
        call("+44 7700 900123", 300),
        call("+447700900123", 200, "Mike P"),
        call("020 7946 0000", 50),
        call("", 10),
    )

    @Test fun addingDayByDayGivesWhatReadingEverythingGives() {
        val whole = CallTally.empty(gb).plus(calls, "5:5")
        var stepwise = CallTally.empty(gb)
        calls.forEachIndexed { i, c -> stepwise = stepwise.plus(listOf(c), "$i") }
        assertEquals(whole.lines, stepwise.lines)
        assertEquals(whole.entries().toSet(), NumberMemory.pastCalls(calls, gb).toSet())
        val mike = whole.entries().filter { it.number == "+44 7700 900123" }.associate { it.hint.source to it.hint }
        assertEquals(3, mike.getValue(MemorySource.CALLS).count)
        assertEquals(100L, mike.getValue(MemorySource.CALLS).since)
        assertEquals("Mike P", mike.getValue(MemorySource.ARCHIVE_NAME).name)
    }

    @Test fun itRoundTripsAndRejectsGarbage() {
        val t = CallTally.empty(gb).plus(calls, "5:9")
        assertEquals(t, CallTally.decode(t.encode()))
        assertNull(CallTally.decode("not a tally".toByteArray()))
    }
}
