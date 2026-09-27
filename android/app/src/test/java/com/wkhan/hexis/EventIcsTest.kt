package com.wkhan.hexis

import com.wkhan.hexis.data.entity.EventEntity
import com.wkhan.hexis.domain.calendar.EventIcs
import com.wkhan.hexis.domain.recurrence.Freq
import com.wkhan.hexis.domain.recurrence.Recur
import com.wkhan.hexis.domain.recurrence.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * R109 (Tier-3) — the ICS import/export is the calendar's only interop surface (subscribe/share, meeting
 * invites) and had no tests. These pin the round-trip (a Hexis event survives export→import) and the
 * timezone parsing that a real-world .ics throws at us: a `Z` (UTC) stamp, a `TZID=` parameter, and an
 * all-day `VALUE=DATE`. Pure java.time, so plain JUnit.
 */
class EventIcsTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    @Suppress("LongParameterList")   // date/time components + optional zone — a readable test time builder
    private fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, zone: ZoneId = utc): Long =
        LocalDate.of(y, mo, d).atTime(h, mi).atZone(zone).toInstant().toEpochMilli()

    @Suppress("LongParameterList")   // intentional test-fixture builder — every field is optional
    private fun ev(
        title: String = "Standup",
        startMs: Long = at(2024, 6, 10, 9),
        endMs: Long = at(2024, 6, 10, 10),
        location: String = "",
        notes: String = "",
        rrule: String = "",
        exDates: String = "",
        allDay: Boolean = false,
    ) = EventEntity(
        id = "e1", calendarId = "c1", title = title, location = location, notes = notes,
        startMillis = startMs, endMillis = endMs, allDay = allDay, rrule = rrule, exDates = exDates,
        createdAt = 0L, updatedAt = 0L,
    )

    // ── round-trip ──────────────────────────────────────────────────────────────────────────────────

    @Test fun roundTrip_timedEvent_preservesFields() {
        val e = ev(title = "Sync; with, escapes", location = "Room 4", notes = "bring laptop")
        val back = EventIcs.import(EventIcs.export(listOf(e), utc), calendarId = "cal", zone = utc)
        assertEquals(1, back.size)
        val r = back[0]
        assertEquals("Sync; with, escapes", r.title)   // esc/unesc round-trips the special chars
        assertEquals("Room 4", r.location)
        assertEquals("bring laptop", r.notes)
        assertEquals(e.startMillis, r.startMillis)
        assertEquals(e.endMillis, r.endMillis)
        assertEquals("cal", r.calendarId)
        assertTrue(!r.allDay)
    }

    @Test fun roundTrip_allDayEvent() {
        val e = ev(title = "Holiday", startMs = at(2024, 6, 10), endMs = at(2024, 6, 11), allDay = true)
        val r = EventIcs.import(EventIcs.export(listOf(e), utc), calendarId = "cal", zone = utc).single()
        assertTrue(r.allDay)
        assertEquals(at(2024, 6, 10), r.startMillis)
        assertEquals(r.startMillis, r.endMillis)   // import collapses an all-day event's end to its start
    }

    @Test fun roundTrip_dailyRecurrence_preservesFreq() {
        val e = ev(rrule = Recurrence.encode(Recur(Freq.DAILY)))
        val r = EventIcs.import(EventIcs.export(listOf(e), utc), calendarId = "cal", zone = utc).single()
        assertEquals(Freq.DAILY, Recurrence.parse(r.rrule)?.freq)
    }

    @Test fun roundTrip_exDates() {
        val skip = LocalDate.of(2024, 6, 11).toEpochDay()
        val e = ev(rrule = Recurrence.encode(Recur(Freq.DAILY)), exDates = skip.toString())
        val r = EventIcs.import(EventIcs.export(listOf(e), utc), calendarId = "cal", zone = utc).single()
        val days = r.exDates.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
        assertEquals(setOf(skip), days)
    }

    @Test fun export_usesHexisProdId() {
        val ics = EventIcs.export(listOf(ev()), utc)
        assertTrue("PRODID must be branded Hexis", ics.contains("PRODID:-//Hexis//"))
        assertTrue(ics.contains("BEGIN:VCALENDAR"))
    }

    // ── real-world import parsing ───────────────────────────────────────────────────────────────────

    @Test fun import_honorsUtcZSuffix_regardlessOfZoneParam() {
        // A `Z` stamp is UTC no matter what local zone we import under.
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nSUMMARY:UTC call\r\n" +
            "DTSTART:20240610T140000Z\r\nDTEND:20240610T150000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val r = EventIcs.import(ics, calendarId = "cal", zone = ZoneId.of("America/New_York")).single()
        assertEquals(at(2024, 6, 10, 14, 0, utc), r.startMillis)   // 14:00 UTC, not shifted by the NY param
        assertEquals(at(2024, 6, 10, 15, 0, utc), r.endMillis)
    }

    @Test fun import_honorsTzidParameter() {
        val ny = ZoneId.of("America/New_York")
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:y\r\nSUMMARY:NY meeting\r\n" +
            "DTSTART;TZID=America/New_York:20240610T090000\r\nDTEND;TZID=America/New_York:20240610T100000\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"
        val r = EventIcs.import(ics, calendarId = "cal", zone = utc).single()
        val expected = LocalDateTime.of(2024, 6, 10, 9, 0).atZone(ny).toInstant().toEpochMilli()
        assertEquals(expected, r.startMillis)   // 09:00 America/New_York, honoring TZID over the UTC param
    }
}
