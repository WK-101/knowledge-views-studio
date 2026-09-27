package com.wkhan.hexis

import com.wkhan.hexis.domain.recurrence.Freq
import com.wkhan.hexis.domain.recurrence.Recur
import com.wkhan.hexis.domain.recurrence.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * R109 (Tier-3) — regression cover for surfaced #1 (monthly recurrence must keep its anchor day and not
 * drift inward at month-end). The existing recurrence tests cover the nth-weekday / last-weekday /
 * first-workday monthly variants but NOT the plain "monthly on day N" anchor + clamp, which is the exact
 * behavior that fix restored. Pure JVM, plain JUnit.
 */
class RecurrenceMonthlyTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private fun ms(y: Int, mo: Int, d: Int): Long = LocalDate.of(y, mo, d).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun next(r: Recur, from: Long): Long = Recurrence.next(r, from, zone)

    @Test fun monthlyOnDay15_advancesToSameDayNextMonth() {
        val r = Recur(Freq.MONTHLY, 1, byMonthDay = 15)
        assertEquals(ms(2026, 2, 15), next(r, ms(2026, 1, 15)))
    }

    @Test fun monthlyAnchorDay31_clampsToShortMonth_thenRestores() {
        val r = Recur(Freq.MONTHLY, 1, byMonthDay = 31)
        val feb = next(r, ms(2026, 1, 31))
        assertEquals("clamped to Feb 28 (2026 is not a leap year)", ms(2026, 2, 28), feb)
        // The crux of surfaced #1: the NEXT roll restores the 31st instead of staying stuck on the 28th.
        assertEquals("anchor restored — no permanent inward drift", ms(2026, 3, 31), next(r, feb))
    }

    @Test fun monthlyAnchorDay31_intoLeapFebruary_is29() {
        val r = Recur(Freq.MONTHLY, 1, byMonthDay = 31)
        assertEquals(ms(2024, 2, 29), next(r, ms(2024, 1, 31)))
    }

    @Test fun monthlyAnchorDay30_intoFebruary_clampsTo28() {
        val r = Recur(Freq.MONTHLY, 1, byMonthDay = 30)
        assertEquals(ms(2026, 2, 28), next(r, ms(2026, 1, 30)))
    }

    @Test fun monthlyInterval2_skipsAMonth() {
        val r = Recur(Freq.MONTHLY, 2, byMonthDay = 10)
        assertEquals(ms(2026, 3, 10), next(r, ms(2026, 1, 10)))
    }

    @Test fun monthlyLastDay_withoutExplicitAnchor_tracksLastDay() {
        // A rule with no anchor, sitting on the last day of the month, follows the last day.
        val r = Recur(Freq.MONTHLY, 1)
        assertEquals(ms(2026, 2, 28), next(r, ms(2026, 1, 31)))
    }

    @Test fun advance_capturesAnchorDay_soPlainMonthlyDoesNotDrift() {
        // The production path: a plain monthly rule (no anchor stored) advanced from Jan 31 must capture the
        // 31 and keep restoring it, not degrade to "the 28th forever" after one short February.
        val rule0 = Recurrence.encode(Recur(Freq.MONTHLY, 1))
        val (feb, rule1) = Recurrence.advance(rule0, ms(2026, 1, 31), zone)
        assertEquals(ms(2026, 2, 28), feb)
        val (mar, _) = Recurrence.advance(rule1!!, feb!!, zone)
        assertEquals(ms(2026, 3, 31), mar)
    }
}
