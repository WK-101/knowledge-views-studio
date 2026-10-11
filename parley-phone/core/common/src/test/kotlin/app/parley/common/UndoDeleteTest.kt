package app.parley.common

import app.parley.common.history.HistoryMerge
import app.parley.common.testing.testCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoDeleteTest {
    /** A French and a Spanish mobile that share their last 9 digits. */
    private val fr = "+33612345678"

    @Test fun restore_skips_rows_already_present() {
        val rows = listOf("a|1", "b|2", "b|2", "c|3")
        assertEquals(listOf("a|1", "b|2", "c|3"), HistoryMerge.missing(rows, emptyList()) { it })
        assertEquals(listOf("c|3"), HistoryMerge.missing(rows, listOf("a|1", "b|2")) { it })
        assertTrue(HistoryMerge.missing(rows, rows) { it }.isEmpty())
        // Keys ignore milliseconds, like the dedupe everywhere else.
        fun e(ms: Long) = testCall(0, fr, null, CallType.INCOMING, ms, 10)
        assertTrue(HistoryMerge.missing(listOf(e(10_500)), listOf(e(10_000)), HistoryMerge::key).isEmpty())
    }
}
