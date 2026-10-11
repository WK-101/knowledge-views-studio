package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Test

class RingingLinesTest {
    @Test fun ringing_keeps_three_lines_safety_then_who_then_agenda() {
        val present = setOf(CallerLine.AGENDA, CallerLine.CALLER_CARD, CallerLine.NATIVE_NAME, CallerLine.PROMISED, CallerLine.FRESH_NUMBER)
        assertEquals(setOf(CallerLine.FRESH_NUMBER, CallerLine.NATIVE_NAME, CallerLine.PROMISED), RingingLines.shown(present, ringing = true))
    }

    @Test fun the_agenda_shows_while_ringing_when_there_is_room() {
        val present = setOf(CallerLine.AGENDA, CallerLine.OCCASION)
        assertEquals(present, RingingLines.shown(present, ringing = true))
    }

    @Test fun safety_lines_are_never_pushed_out_by_who_lines() {
        val present = CallerLine.entries.toSet()
        val shown = RingingLines.shown(present, ringing = true)
        assertEquals(RingingLines.MAX_RINGING, shown.size)
        assertEquals(setOf(LineGroup.SAFETY), shown.map { it.group }.toSet())
    }

    @Test fun once_answered_every_line_shows() {
        val present = CallerLine.entries.toSet()
        assertEquals(present, RingingLines.shown(present, ringing = false))
    }

    @Test fun the_order_is_safety_who_agenda() {
        val groups = CallerLine.entries.map { it.group }
        assertEquals(groups.sortedBy { it.ordinal }, groups)
    }
}
