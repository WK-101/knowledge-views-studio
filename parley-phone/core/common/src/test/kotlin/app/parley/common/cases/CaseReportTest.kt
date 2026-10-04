package app.parley.common.cases

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.circle.Promises
import app.parley.common.cases.CaseReport.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PDF's text, as lines: what a complaint gets, and that reference numbers are in it only when chosen. */
class CaseReportTest {
    private val day = 86_400_000L

    private object W : CaseReport.Words {
        override fun title(name: String) = "Case file: $name"
        override fun madeOn(date: String) = "Made on $date"
        override fun numbers(numbers: List<String>) = "Numbers: " + numbers.joinToString(", ")
        override val summary = "Summary"
        override fun calls(count: Int, first: String, last: String) = "$count calls between $first and $last"
        override val noCalls = "No calls"
        override fun hold(total: Long, average: Long, longest: Long, calls: Int) = "Hold $total/$average/$longest over $calls"
        override fun menu(keys: String) = "Menu $keys"
        override val references = "References"
        override fun reference(label: String, value: String, date: String) = "$label: $value ($date)"
        override fun referencesLeftOut(count: Int) = "$count left out"
        override val promises = "Promises"
        override fun promise(text: String) = "[ ] $text"
        override val timeline = "Timeline"
        override fun call(entry: CaseEntry.Call) = "Call ${entry.durationSec}s hold ${entry.holdSec}s"
        override fun note(text: String) = "Note: $text"
        override fun referenceAdded(label: String) = "Reference added: $label"
        override fun date(at: Long) = "d${at / 86_400_000L}"
    }

    private val case = CaseFile(
        "a", "Northwind Energy", listOf("+442079460000"),
        references = listOf(CaseReference("r1", "Complaint", "sealed", 2 * day)),
        calls = listOf(CaseCall(day, incoming = false, durationSec = 1_200, holdSec = 840, connected = true, menu = "3")),
    )
    private val timeline = CaseTimelines.assemble(
        case,
        listOf(CallEntry(1, "+442079460000", null, CallType.OUTGOING, day, 1_200, null, false, false)),
        listOf(CaseNote(day, "Promised a refund")),
        Promises.parse("[ ] Refund by the 20th"),
    )

    @Test fun the_report_without_references() {
        val lines = CaseReport.build(case.name, case.numbers, timeline, references = null, referenceCount = 1, now = 10 * day, w = W)
        assertEquals(
            listOf(
                Style.TITLE to "Case file: Northwind Energy",
                Style.SUBTITLE to "Made on d10",
                Style.SUBTITLE to "Numbers: +442079460000",
                Style.HEADING to "Summary",
                Style.BODY to "1 calls between d1 and d1",
                Style.BODY to "Hold 840/840/840 over 1",
                Style.BODY to "Menu 3",
                Style.HEADING to "References",
                Style.DETAIL to "1 left out",
                Style.HEADING to "Promises",
                Style.BODY to "[ ] Refund by the 20th",
                Style.HEADING to "Timeline",
                Style.DETAIL to "d1",
                Style.BODY to "Call 1200s hold 840s",
                Style.DETAIL to "d1",
                Style.BODY to "Note: Promised a refund",
                Style.DETAIL to "d2",
                Style.BODY to "Reference added: Complaint",
            ),
            lines.map { it.style to it.text },
        )
        assertFalse(lines.any { "CMP-7781" in it.text })
    }

    @Test fun references_only_when_chosen() {
        val refs = listOf(CaseReport.OpenReference("Complaint", "CMP-7781", 2 * day))
        val lines = CaseReport.build(case.name, case.numbers, timeline, refs, 1, 10 * day, W)
        assertTrue(lines.any { it.text == "Complaint: CMP-7781 (d2)" })
        assertFalse(lines.any { it.text.endsWith("left out") })
    }

    @Test fun nothing_yet() {
        val empty = CaseTimelines.assemble(null, emptyList(), emptyList(), emptyList())
        val lines = CaseReport.build("Council", emptyList(), empty, null, 0, 0, W)
        assertEquals(listOf("Case file: Council", "Made on d0", "Summary", "No calls"), lines.map { it.text })
    }

    @Test fun pages_never_end_on_a_heading_or_split_a_date_from_its_entry() {
        val lines = CaseReport.build(case.name, case.numbers, timeline, null, 1, 10 * day, W)
        val heights = lines.map { 10f }
        // Room for 9 lines: the "References" heading (index 7) would be 8th; with its line it fits; "Promises" (9) moves on.
        val pages = CaseReport.paginate(lines, heights, 90f)
        assertEquals(0 until 9, pages[0])
        pages.forEach { r ->
            assertTrue(lines[r.last].style != Style.HEADING)
            if (r.last + 1 < lines.size) assertFalse(lines[r.last].style == Style.DETAIL && lines[r.last + 1].style == Style.BODY)
        }
        assertEquals(lines.indices.toList(), pages.flatMap { it.toList() })
        assertEquals(listOf(0 until 0), CaseReport.paginate(emptyList(), emptyList(), 100f))
    }
}
