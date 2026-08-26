package com.todocompanion.app

import com.todocompanion.app.domain.nlp.QuickAddParser
import com.todocompanion.app.domain.port.Ics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Test

/** CU1 + CU3 — the natural-language parser enrichments and the .ics import bridge. */
class TierCUTests {

    // A fixed "now": Wednesday 26 Aug 2026, 10:00.
    private val now = LocalDateTime.of(2026, 8, 26, 10, 0)
    private fun p(s: String) = QuickAddParser.parse(s, now)

    // ---- CU1: relative dates ----
    @Test fun inAWeek() {
        val r = p("buy milk in a week")
        assertEquals(LocalDate.of(2026, 9, 2), r.dateTime?.toLocalDate())
        assertEquals("buy milk", r.title)
    }
    @Test fun inNMonths() {
        assertEquals(LocalDate.of(2026, 10, 26), p("plan trip in 2 months").dateTime?.toLocalDate())
    }
    @Test fun nextMonth() {
        assertEquals(LocalDate.of(2026, 9, 26), p("rent next month").dateTime?.toLocalDate())
    }
    @Test fun dayAfterTomorrow() {
        assertEquals(LocalDate.of(2026, 8, 28), p("ship day after tomorrow").dateTime?.toLocalDate())
    }
    @Test fun weekendIsSaturday() {
        assertEquals(DayOfWeek.SATURDAY, p("hike this weekend").dateTime?.dayOfWeek)
    }
    @Test fun dayOfMonthRollsToNextMonthWhenPast() {
        // the 15th already passed (today is the 26th) → next month's 15th.
        assertEquals(LocalDate.of(2026, 9, 15), p("pay card the 15th").dateTime?.toLocalDate())
    }
    @Test fun endOfMonth() {
        assertEquals(LocalDate.of(2026, 8, 31), p("invoice end of month").dateTime?.toLocalDate())
    }

    // ---- CU1: times & recurrence ----
    @Test fun bareAtHourAssumesAfternoon() {
        val r = p("standup at 3")
        assertTrue(r.hasTime)
        assertEquals(15, r.dateTime?.hour)
    }
    @Test fun everyOtherWeekdayRecurs() {
        val r = p("review every other friday")
        assertNotNull(r.rrule)
        assertTrue(r.title.contains("review"))
    }
    @Test fun everyOtherWeekRecurs() {
        assertNotNull(p("groceries every other week").rrule)
    }
    @Test fun plainTaskHasNoDate() {
        val r = p("call the plumber")
        assertNull(r.dateTime)
        assertEquals("call the plumber", r.title)
    }

    // ---- CU3: .ics import ----
    @Test fun icsParsesTimedAndAllDay() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:1@x
            SUMMARY:Dentist
            DTSTART;TZID=Europe/London:20260901T090000
            DTEND:20260901T100000
            END:VEVENT
            BEGIN:VEVENT
            UID:2@x
            SUMMARY:Holiday
            DTSTART;VALUE=DATE:20260910
            DTEND;VALUE=DATE:20260911
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val events = Ics.parse(ics)
        assertEquals(2, events.size)
        val d = events[0]
        assertEquals("Dentist", d.summary)
        assertEquals(LocalDateTime.of(2026, 9, 1, 9, 0), d.start)
        assertEquals(LocalDateTime.of(2026, 9, 1, 10, 0), d.end)
        assertTrue(!d.allDay)
        val h = events[1]
        assertEquals("Holiday", h.summary)
        assertTrue(h.allDay)
        assertEquals(LocalDate.of(2026, 9, 10), h.start.toLocalDate())
    }
    @Test fun icsUnescapesAndFolds() {
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nSUMMARY:Buy eggs\\, milk\r\nDTSTART:20260901T080000\r\nEND:VEVENT\r\nEND:VCALENDAR"
        val e = Ics.parse(ics).single()
        assertEquals("Buy eggs, milk", e.summary)
    }
    @Test fun icsEmptyWhenNoEvents() {
        assertTrue(Ics.parse("not a calendar").isEmpty())
    }
}
