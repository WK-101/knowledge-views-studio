package com.todocompanion.app

import com.todocompanion.app.domain.DayWindow
import com.todocompanion.app.domain.PeriodRange
import com.todocompanion.app.domain.YearReviewed
import com.todocompanion.app.domain.weekStartOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Coherence Move 7 — the one canonical period model shared by Day Review, Statistics, Recap and The
 * Record. Covers each period's inclusive epoch-day bounds, today-clamping of the current period, the
 * weekStart handling (Mon / Sun / locale-default), and that YEAR reuses the canonical calendar-year window.
 */
class PeriodRangeTest {

    // A fixed "today" — Friday, 15 Mar 2024 — so every clamp/bound assertion is deterministic.
    private val today: LocalDate = LocalDate.of(2024, 3, 15)
    private val td: Long = today.toEpochDay()
    private fun ed(y: Int, m: Int, d: Int): Long = LocalDate.of(y, m, d).toEpochDay()

    private fun win(range: PeriodRange, anchor: Long, weekStart: Int = 1): DayWindow =
        range.window(anchor, weekStart, td)

    @Test fun day_isTheAnchorDayItself() {
        val w = win(PeriodRange.DAY, ed(2024, 1, 10))
        assertEquals(ed(2024, 1, 10), w.startDay)
        assertEquals(ed(2024, 1, 10), w.endDay)
        assertEquals(1, w.days)
    }

    @Test fun day_isClampedToToday_forAFutureAnchor() {
        val w = win(PeriodRange.DAY, td + 5)
        assertEquals(td, w.startDay)
        assertEquals(td, w.endDay)
        assertEquals(1, w.days)
    }

    @Test fun week_mondayStart_wrapsTheWholeWeekOfAPastAnchor() {
        // 2024-01-10 is a Wednesday; with Monday-start the week is Mon 8th … Sun 14th (all before today).
        val w = win(PeriodRange.WEEK, ed(2024, 1, 10), weekStart = 1)
        assertEquals(ed(2024, 1, 8), w.startDay)
        assertEquals(ed(2024, 1, 14), w.endDay)
        assertEquals(7, w.days)
    }

    @Test fun week_sundayStart_shiftsTheWeekBounds() {
        // Same Wednesday, but Sunday-start: Sun 7th … Sat 13th.
        val w = win(PeriodRange.WEEK, ed(2024, 1, 10), weekStart = 7)
        assertEquals(ed(2024, 1, 7), w.startDay)
        assertEquals(ed(2024, 1, 13), w.endDay)
        assertEquals(7, w.days)
    }

    @Test fun week_currentWeekIsClampedToToday() {
        // today = Fri 15 Mar 2024; Monday-start week is Mon 11th … Sun 17th, clamped to Fri 15th.
        val w = win(PeriodRange.WEEK, td, weekStart = 1)
        assertEquals(ed(2024, 3, 11), w.startDay)
        assertEquals(td, w.endDay)
        assertEquals(5, w.days)
    }

    @Test fun week_localeDefaultStart_stillWrapsExactlyTheWeekContainingTheAnchor() {
        // weekStart = 0 → the locale's first day. Whatever it is, the window is a valid week that
        // contains the anchor and never runs past today.
        val anchor = ed(2024, 1, 10)
        val w = win(PeriodRange.WEEK, anchor, weekStart = 0)
        val ws = weekStartOf(LocalDate.ofEpochDay(anchor), 0)
        assertEquals(ws.toEpochDay(), w.startDay)
        assertTrue(anchor in w.startDay..w.endDay)
        assertEquals(7, w.days) // this week is entirely before today, so nothing is clamped
    }

    @Test fun month_wrapsTheFullCalendarMonthOfAPastAnchor() {
        val w = win(PeriodRange.MONTH, ed(2024, 1, 10))
        assertEquals(ed(2024, 1, 1), w.startDay)
        assertEquals(ed(2024, 1, 31), w.endDay)
        assertEquals(31, w.days)
    }

    @Test fun month_currentMonthIsClampedToToday() {
        val w = win(PeriodRange.MONTH, td)
        assertEquals(ed(2024, 3, 1), w.startDay)
        assertEquals(td, w.endDay) // clamped to the 15th, not 31 Mar
        assertEquals(15, w.days)
    }

    @Test fun year_reusesTheCanonicalCalendarYearWindow() {
        // A fully-past year is the whole calendar year.
        val past = win(PeriodRange.YEAR, ed(2023, 6, 15))
        val (cs, ce) = YearReviewed.calendarYearWindow(2023, td)
        assertEquals(cs, past.startDay)
        assertEquals(ce, past.endDay)
        assertEquals(ed(2023, 1, 1), past.startDay)
        assertEquals(ed(2023, 12, 31), past.endDay)
    }

    @Test fun year_currentYearIsClampedToToday() {
        val w = win(PeriodRange.YEAR, td)
        val (cs, ce) = YearReviewed.calendarYearWindow(2024, td)
        assertEquals(cs, w.startDay)
        assertEquals(ce, w.endDay)
        assertEquals(ed(2024, 1, 1), w.startDay)
        assertEquals(td, w.endDay) // clamped, not 31 Dec 2024
    }

    @Test fun all_isEpochZeroThroughToday() {
        val w = win(PeriodRange.ALL, td)
        assertEquals(0L, w.startDay)
        assertEquals(td, w.endDay)
        assertEquals((td + 1).toInt(), w.days)
    }

    @Test fun everyPeriodEndsOnOrBeforeToday() {
        for (p in PeriodRange.ALL_PERIODS) {
            // both a past anchor and today itself
            for (anchor in listOf(ed(2023, 11, 20), td)) {
                val w = p.window(anchor, 1, td)
                assertTrue("$p ($anchor) must not end past today", w.endDay <= td)
                assertTrue("$p ($anchor) must be non-empty", w.startDay <= w.endDay)
            }
        }
    }

    @Test fun labelsAreTheFiveExpectedStrings() {
        assertEquals(listOf("Day", "Week", "Month", "Year", "All"), PeriodRange.ALL_PERIODS.map { it.label })
    }
}
