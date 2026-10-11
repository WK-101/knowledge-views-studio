package app.parley.common.cases

import app.parley.common.CallType
import app.parley.common.circle.Promises
import app.parley.common.testing.testCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseTimelineTest {
    private val day = 86_400_000L
    private val bank = "+442079460000"

    private fun entry(id: Long, at: Long, type: CallType, sec: Long = 0, hidden: Boolean = false) =
        testCall(id, bank, null, type, at, sec, presentationHidden = hidden)

    private val case = CaseFile(
        "a", "Barclays", listOf(bank), CaseMode.AUTO,
        references = listOf(CaseReference("r1", "Claim", "sealed", 3 * day + 120_000)),
        calls = listOf(
            // The same call as the log's, a minute later (when the call screen saw it start): its hold time joins it.
            CaseCall(3 * day + 60_000, incoming = false, durationSec = 1_500, holdSec = 900, connected = true, menu = "21"),
            CaseCall(2 * day, incoming = false, durationSec = 600, holdSec = 300, connected = true, menu = "3"),
            // The log trimmed this one: only the case file has it.
            CaseCall(day, incoming = true, durationSec = 200, connected = true),
        ),
    )

    @Test fun calls_notes_and_references_in_one_dated_timeline() {
        val history = listOf(
            entry(1, 3 * day, CallType.OUTGOING, 1_500),
            entry(2, 2 * day + 30_000, CallType.OUTGOING, 600),
            entry(3, 4 * day, CallType.MISSED),
            entry(4, 5 * day, CallType.INCOMING, 30, hidden = true),
        )
        val notes = listOf(CaseNote(3 * day, "They said a callback within 5 days\n[ ] Call back if no letter"), CaseNote(0, "  "))
        val promises = Promises.parse("[ ] Call back if no letter\n[x] Send the form")
        val t = CaseTimelines.assemble(case, history, notes, promises)

        assertEquals(
            listOf(4 * day, 3 * day + 120_000, 3 * day, 3 * day, 2 * day + 30_000, day),
            t.entries.map { it.at },
        )
        // At the same moment the call comes before its note.
        assertTrue(t.entries[2] is CaseEntry.Call)
        assertTrue(t.entries[3] is CaseEntry.Note)
        val merged = t.entries[2] as CaseEntry.Call
        assertEquals(CallType.OUTGOING, merged.type)
        assertEquals(900L, merged.holdSec)
        assertEquals("21", merged.menu)
        val onlyKept = t.entries.last() as CaseEntry.Call
        assertEquals(null, onlyKept.type)
        assertTrue(onlyKept.incoming)
        assertEquals(CaseEntry.Reference(3 * day + 120_000, "r1", "Claim"), t.entries[1])

        val s = t.summary
        assertEquals(4, s.calls)
        assertEquals(day, s.firstCallAt)
        assertEquals(4 * day, s.lastCallAt)
        assertEquals(2, s.heldCalls)
        assertEquals(1_200L, s.totalHoldSec)
        assertEquals(600L, s.averageHoldSec)
        assertEquals(900L, s.longestHoldSec)
        assertEquals("21", s.menu)
        assertEquals(1, s.references)
        assertEquals(1, s.openPromises)
        assertEquals(listOf("Call back if no letter"), t.promises.map { it.text })
    }

    @Test fun an_organisation_without_a_case_file_shows_its_calls() {
        val t = CaseTimelines.assemble(null, listOf(entry(1, day, CallType.OUTGOING, 60)), emptyList(), emptyList())
        assertEquals(1, t.summary.calls)
        assertEquals(0, t.summary.heldCalls)
        assertEquals(0L, t.summary.averageHoldSec)
        assertTrue(CaseTimelines.assemble(null, emptyList(), emptyList(), emptyList()).summary.isEmpty)
    }

    @Test fun calls_far_apart_are_not_joined() {
        val far = case.copy(calls = listOf(CaseCall(day + CaseTimelines.SAME_CALL_MS + 1, incoming = false, holdSec = 50)))
        val t = CaseTimelines.assemble(far, listOf(entry(1, day, CallType.OUTGOING, 60)), emptyList(), emptyList())
        assertEquals(2, t.summary.calls)
        assertEquals(0L, (t.entries.last() as CaseEntry.Call).holdSec)
    }
}
