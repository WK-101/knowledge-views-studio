package app.parley.common.people

import app.parley.common.CallType
import app.parley.common.EventDate
import app.parley.common.circle.InteractionType
import app.parley.common.circle.TimelineEntry
import app.parley.common.circle.TimelineFilter
import app.parley.common.circle.TimelineKind
import app.parley.common.testing.testCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactTimelineTest {
    private fun call(n: String, type: CallType, t: Long) = TimelineEntry.Call(testCall(t, n, null, type, t, 30))

    @Test fun timeline_filter_by_kind_and_words() {
        val entries = listOf(
            call("+44 20 7946 0000", CallType.INCOMING, 5),
            call("+44 20 7946 0000", CallType.MISSED, 4),
            TimelineEntry.Logged(1, 3, InteractionType.MEET, null, "Coffee at Café Rosa"),
            TimelineEntry.Note(2, 2, "Ask about the move"),
            TimelineEntry.Date(1, 3, null, EventDate(null, 1, 1)),
        )
        val none = { _: TimelineEntry -> "" }
        assertEquals(entries, TimelineFilter().apply(entries, none))
        assertEquals(listOf(entries[1]), TimelineFilter(kinds = setOf(TimelineKind.MISSED)).apply(entries, none))
        assertEquals(listOf(entries[0]), TimelineFilter(kinds = setOf(TimelineKind.CALL)).apply(entries, none))
        assertEquals(listOf(entries[2]), TimelineFilter("cafe rosa").apply(entries, none))
        assertEquals(listOf(entries[3]), TimelineFilter("MOVE", setOf(TimelineKind.NOTE, TimelineKind.LOGGED)).apply(entries, none))
        assertEquals(entries.take(2), TimelineFilter("7946").apply(entries, none))
        // The shown words (a localised type label) are searched too.
        assertEquals(listOf(entries[4]), TimelineFilter("birthday").apply(entries) { if (it is TimelineEntry.Date) "Birthday" else "" })
        assertTrue(TimelineFilter("zzz").apply(entries, none).isEmpty())
    }
}
