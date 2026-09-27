package app.parley.common.history

import app.parley.common.CallEntry
import app.parley.common.CallType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** New calls are appended to the index instead of rebuilding it, with exactly the result of a rebuild. */
class CallLogIndexAppendTest {
    private val anna = IndexContact(1, "anna", "Anna", listOf("+33 6 12 34 56 78", "01 23 45 67 89"))
    private val contacts = listOf(anna)

    private fun history(n: Int, start: Long = at(2020, 1, 1)): List<CallEntry> = (0 until n).map { i ->
        val number = when (i % 5) {
            0 -> "0612345678"
            1 -> "0123456789"
            else -> "06" + (10_000_000 + i % 3_000).toString()
        }
        call(number, CallType.entries[i % 4], start + i * 60_000L, dur = (i % 7).toLong() * 30)
    }.reversed() // newest first, as the history is kept

    private fun same(a: CallLogIndex, b: CallLogIndex) {
        assertEquals(a.calls, b.calls)
        assertEquals(a.people, b.people)
        assertEquals(a.totals(), b.totals())
        assertEquals(a.perPerson(), b.perPerson())
        assertEquals(a.unreturned(), b.unreturned())
        for (key in a.people.keys) assertEquals(a.calls(personKey = key), b.calls(personKey = key))
    }

    @Test fun appending_new_calls_matches_a_rebuild() {
        val old = history(500)
        val base = CallLogIndex.build(old, contacts, "FR", UTC)
        val newest = old.first().date
        val fresh = listOf(
            call("0612345678", CallType.MISSED, newest + 120_000),
            call("0799999999", CallType.INCOMING, newest + 60_000, dur = 40, name = "New person"),
            call("", CallType.INCOMING, newest + 90_000, hidden = true),
        ).sortedByDescending { it.date }
        val all = fresh + old
        val appended = base.appending(all)
        assertNotNull(appended)
        same(CallLogIndex.build(all, contacts, "FR", UTC), appended!!)
    }

    @Test fun anything_but_new_calls_in_front_needs_a_rebuild() {
        val old = history(50)
        val base = CallLogIndex.build(old, contacts, "FR", UTC)
        assertTrue(base.appending(old) === base)
        assertNull("a call removed", base.appending(old.drop(1)))
        assertNull("a call changed", base.appending(listOf(old[0].copy(durationSec = 999)) + old.drop(1)))
        assertNull("an older call added", base.appending(listOf(call("0612345678", CallType.OUTGOING, old.last().date - 1)) + old))
    }

    @Test fun fifty_thousand_calls_append_without_indexing_them_again() {
        val old = history(50_000)
        val t0 = System.nanoTime()
        val base = CallLogIndex.build(old, contacts, "FR", UTC)
        val buildMs = (System.nanoTime() - t0) / 1_000_000
        val all = listOf(call("0612345678", CallType.INCOMING, old.first().date + 1_000, dur = 10)) + old

        val t1 = System.nanoTime()
        val appended = base.appending(all)!!
        val appendMs = (System.nanoTime() - t1) / 1_000_000
        assertEquals(50_001, appended.calls.size)
        assertTrue("build $buildMs ms", buildMs < 60_000)
        assertTrue("append ($appendMs ms) should be far quicker than a build ($buildMs ms)", appendMs * 3 < buildMs)
        same(CallLogIndex.build(all, contacts, "FR", UTC), appended)
    }
}
