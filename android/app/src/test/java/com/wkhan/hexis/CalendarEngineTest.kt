package com.wkhan.hexis

import com.wkhan.hexis.data.entity.EventEntity
import com.wkhan.hexis.domain.calendar.CalendarEngine
import com.wkhan.hexis.domain.recurrence.Freq
import com.wkhan.hexis.domain.recurrence.Recur
import com.wkhan.hexis.domain.recurrence.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * R109 (Tier-3) — the calendar brain drives what EVERY calendar view shows, and it had no tests. A bug in
 * recurrence expansion silently mis-dates or drops events. These pin the orchestration [CalendarEngine]
 * does on top of [Recurrence]: window intersection, DAILY/interval/COUNT/UNTIL expansion, EXDATE skips,
 * per-instance overrides, the fast-forward that keeps a long-running series visible, and the free-slot /
 * conflict / heat-map analytics. Pure JVM (java.time), so plain JUnit — no device, no Robolectric.
 */
class CalendarEngineTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        LocalDate.of(y, mo, d).atTime(h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun epochDay(y: Int, mo: Int, d: Int): Long = LocalDate.of(y, mo, d).toEpochDay()

    private fun ld(ms: Long): LocalDate = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    @Suppress("LongParameterList")   // intentional test-fixture builder — all but id/start/end are optional
    private fun ev(
        id: String,
        startMs: Long,
        endMs: Long,
        rrule: String = "",
        exDates: String = "",
        allDay: Boolean = false,
        busy: Boolean = true,
        parentId: String? = null,
        recDate: Long = 0L,
    ) = EventEntity(
        id = id, calendarId = "c1", title = id, startMillis = startMs, endMillis = endMs,
        rrule = rrule, exDates = exDates, allDay = allDay, busy = busy,
        recurrenceParentId = parentId, recurrenceDate = recDate, createdAt = 0L, updatedAt = 0L,
    )

    private fun daily(interval: Int = 1, count: Int? = null, until: Long? = null): String =
        Recurrence.encode(Recur(Freq.DAILY, interval = interval, count = count, untilEpochDay = until))

    // ── window intersection (non-recurring) ─────────────────────────────────────────────────────────

    @Test fun nonRecurring_insideWindow_emitsOnce() {
        val e = ev("a", at(2024, 6, 10, 9), at(2024, 6, 10, 10))
        val occ = CalendarEngine.expand(listOf(e), at(2024, 6, 10), at(2024, 6, 11), zone)
        assertEquals(1, occ.size)
        assertEquals(at(2024, 6, 10, 9), occ[0].startMillis)
    }

    @Test fun nonRecurring_outsideWindow_excluded() {
        val e = ev("a", at(2024, 6, 1, 9), at(2024, 6, 1, 10))
        val occ = CalendarEngine.expand(listOf(e), at(2024, 6, 10), at(2024, 6, 11), zone)
        assertTrue(occ.isEmpty())
    }

    @Test fun nonRecurring_straddlingWindowStart_included() {
        // Starts the evening before the window, ends inside it — must still show.
        val e = ev("a", at(2024, 6, 9, 23), at(2024, 6, 10, 1))
        val occ = CalendarEngine.expand(listOf(e), at(2024, 6, 10), at(2024, 6, 11), zone)
        assertEquals(1, occ.size)
    }

    // ── recurring expansion ─────────────────────────────────────────────────────────────────────────

    @Test fun daily_expandsAcrossWindow() {
        val e = ev("d", at(2024, 6, 10, 9), at(2024, 6, 10, 10), rrule = daily())
        val occ = CalendarEngine.expand(listOf(e), at(2024, 6, 10), at(2024, 6, 14, 23, 59), zone)
        assertEquals(5, occ.size)   // 10,11,12,13,14
        assertEquals(at(2024, 6, 10, 9), occ.first().startMillis)
        assertEquals(at(2024, 6, 14, 9), occ.last().startMillis)
    }

    @Test fun dailyInterval2_skipsEveryOtherDay() {
        val e = ev("d", at(2024, 6, 10, 9), at(2024, 6, 10, 10), rrule = daily(interval = 2))
        val occ = CalendarEngine.expand(listOf(e), at(2024, 6, 10), at(2024, 6, 16, 23, 59), zone)
        val days = occ.map { ld(it.startMillis).dayOfMonth }
        assertEquals(listOf(10, 12, 14, 16), days)
    }

    @Test fun count_limitsOccurrences() {
        val e = ev("d", at(2024, 6, 10, 9), at(2024, 6, 10, 10), rrule = daily(count = 3))
        val occ = CalendarEngine.expand(listOf(e), at(2024, 6, 10), at(2024, 6, 30, 23, 59), zone)
        assertEquals(3, occ.size)   // count wins over the wide window
    }

    @Test fun until_stopsAtUntilDayInclusive() {
        val e = ev("d", at(2024, 6, 10, 9), at(2024, 6, 10, 10), rrule = daily(until = epochDay(2024, 6, 12)))
        val occ = CalendarEngine.expand(listOf(e), at(2024, 6, 10), at(2024, 6, 30, 23, 59), zone)
        assertEquals(3, occ.size)   // 10, 11, 12 — the UNTIL day is inclusive
    }

    @Test fun exDate_skipsThatInstanceOnly() {
        val e = ev(
            "d", at(2024, 6, 10, 9), at(2024, 6, 10, 10),
            rrule = daily(), exDates = epochDay(2024, 6, 11).toString(),
        )
        val occ = CalendarEngine.expand(listOf(e), at(2024, 6, 10), at(2024, 6, 13, 23, 59), zone)
        val days = occ.map { ld(it.startMillis).dayOfMonth }
        assertEquals(listOf(10, 12, 13), days)   // 11 excluded
    }

    @Test fun longRunningDaily_fastForwardsIntoFarWindow() {
        // Regression guard: a daily series begun years before the viewed window must still appear in it
        // (the old flat iteration cap made such a series silently vanish from every view).
        val e = ev("d", at(2020, 1, 1, 9), at(2020, 1, 1, 10), rrule = daily())
        val occ = CalendarEngine.expand(listOf(e), at(2024, 6, 10), at(2024, 6, 12, 23, 59), zone)
        assertEquals(3, occ.size)
        assertEquals(at(2024, 6, 10, 9), occ.first().startMillis)
    }

    @Test fun perInstanceOverride_showsMovedTime_andSuppressesBase() {
        val base = ev("P", at(2024, 6, 10, 9), at(2024, 6, 10, 10), rrule = daily())
        // Override the 06-11 instance, moved to the afternoon.
        val override = ev(
            "O", at(2024, 6, 11, 14), at(2024, 6, 11, 15),
            parentId = "P", recDate = epochDay(2024, 6, 11),
        )
        val occ = CalendarEngine.expand(listOf(base, override), at(2024, 6, 10), at(2024, 6, 12, 23, 59), zone)
        assertEquals(3, occ.size)   // 10 (base), 11 (override), 12 (base) — base 06-11 is suppressed
        val on11 = occ.first { ld(it.startMillis).dayOfMonth == 11 }
        assertTrue("the 06-11 occurrence is the override", on11.isOverride)
        assertEquals(at(2024, 6, 11, 14), on11.startMillis)
        // The base's original 9am slot on the 11th must not also appear.
        assertFalse(occ.any { !it.isOverride && it.startMillis == at(2024, 6, 11, 9) })
    }

    // ── onDay ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun onDay_returnsOnlyIntersectingDay() {
        val a = ev("a", at(2024, 6, 10, 10), at(2024, 6, 10, 11))
        val b = ev("b", at(2024, 6, 11, 10), at(2024, 6, 11, 11))
        val occ = CalendarEngine.onDay(listOf(a, b), epochDay(2024, 6, 10), zone)
        assertEquals(1, occ.size)
        assertEquals("a", occ[0].event.id)
    }

    // ── free-slot finder ────────────────────────────────────────────────────────────────────────────

    @Test fun freeSlots_findsGapsWithinWorkHours() {
        val day = epochDay(2024, 6, 10)
        val busy = listOf(
            at(2024, 6, 10, 9) to at(2024, 6, 10, 10),
            at(2024, 6, 10, 11) to at(2024, 6, 10, 12),
        )
        val slots = CalendarEngine.freeSlots(busy, day, workStartHour = 9, workEndHour = 17, minMinutes = 30, zone = zone)
        assertEquals(2, slots.size)
        assertEquals(at(2024, 6, 10, 10) to at(2024, 6, 10, 11), slots[0].startMillis to slots[0].endMillis)
        assertEquals(at(2024, 6, 10, 12) to at(2024, 6, 10, 17), slots[1].startMillis to slots[1].endMillis)
    }

    // ── conflict detection ──────────────────────────────────────────────────────────────────────────

    @Test fun conflicts_detectsOverlappingBusyPair() {
        val a = ev("a", at(2024, 6, 10, 9), at(2024, 6, 10, 11))
        val b = ev("b", at(2024, 6, 10, 10), at(2024, 6, 10, 12))
        val occ = CalendarEngine.expand(listOf(a, b), at(2024, 6, 10), at(2024, 6, 11), zone)
        assertEquals(1, CalendarEngine.conflicts(occ).size)
    }

    @Test fun clashes_flagsProposedSlotAgainstBusy() {
        val a = ev("a", at(2024, 6, 10, 9), at(2024, 6, 10, 11))
        val occ = CalendarEngine.expand(listOf(a), at(2024, 6, 10), at(2024, 6, 11), zone)
        assertEquals(1, CalendarEngine.clashes(occ, at(2024, 6, 10, 10), at(2024, 6, 10, 10, 30)).size)
        assertTrue(CalendarEngine.clashes(occ, at(2024, 6, 10, 12), at(2024, 6, 10, 13)).isEmpty())
    }

    // ── heat-map ────────────────────────────────────────────────────────────────────────────────────

    @Test fun busyMinutesByDay_splitsAcrossMidnight() {
        // 23:00 → 01:00 next day: 60 minutes fall on each of the two local days.
        val e = ev("night", at(2024, 6, 10, 23), at(2024, 6, 11, 1))
        val map = CalendarEngine.busyMinutesByDay(listOf(e), epochDay(2024, 6, 10), days = 2, zone = zone)
        assertEquals(60, map[epochDay(2024, 6, 10)])
        assertEquals(60, map[epochDay(2024, 6, 11)])
    }
}

