package com.todocompanion.app

import com.todocompanion.app.domain.ReviewCadence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Phase F — the cadence mechanics' pure logic: the adaptive evening-reminder time, and the review
 * streak with single-miss recovery (tokens, single-gap vs multi-gap, exhaustion) plus the period key.
 */
class ReviewCadenceTest {

    // ── adaptive evening reminder ──────────────────────────────────────────────────────────────────

    @Test fun adaptive_fallsBackBelowMinSamples() {
        // Only two samples (< MIN_SAMPLES) → keep the user's own fixed time untouched.
        assertEquals(1290, ReviewCadence.adaptiveReminderMinuteOfDay(listOf(1300, 1310), fallbackMinuteOfDay = 1290))
    }

    @Test fun adaptive_usesMedianWhenInsideWindow() {
        // 20:00, 21:00, 22:00 → median 21:00 (1260), inside the 17:00–23:00 window.
        assertEquals(1260, ReviewCadence.adaptiveReminderMinuteOfDay(listOf(1200, 1260, 1320), fallbackMinuteOfDay = 1290))
    }

    @Test fun adaptive_clampsMedianToWindow() {
        // A cluster of 10:00 closes → median clamped up to the window start (17:00 = 1020).
        assertEquals(ReviewCadence.WINDOW_START_MIN, ReviewCadence.adaptiveReminderMinuteOfDay(listOf(600, 600, 600, 605), fallbackMinuteOfDay = 1290))
        // A cluster near midnight → clamped down to the window end (23:00 = 1380).
        assertEquals(ReviewCadence.WINDOW_END_MIN, ReviewCadence.adaptiveReminderMinuteOfDay(listOf(1439, 1439, 1439), fallbackMinuteOfDay = 1290))
    }

    // ── review streak + single-miss recovery ───────────────────────────────────────────────────────

    @Test fun streak_countsConsecutiveThroughToday() {
        val s = ReviewCadence.computeStreak(reviewedDays = setOf(100L, 99L, 98L), repairedDays = emptySet(), today = 100L, tokensAvailable = 2)
        assertEquals(3, s.streak)
        assertFalse(s.brokenBySingleGap)
        assertNull(s.repairableDay)
    }

    @Test fun streak_singleGapIsRepairableWithAToken() {
        // A 3-day streak ended at day 98; yesterday (99) is the sole miss; today (100) not yet reviewed.
        val s = ReviewCadence.computeStreak(reviewedDays = setOf(98L, 97L, 96L), repairedDays = emptySet(), today = 100L, tokensAvailable = 1)
        assertTrue(s.brokenBySingleGap)
        assertEquals(99L, s.repairableDay)
        assertEquals(1, s.tokensAvailable)
    }

    @Test fun streak_multiDayGapIsNotRepairable() {
        // Both 99 and 98 are missing → a genuine break, never offered a repair.
        val s = ReviewCadence.computeStreak(reviewedDays = setOf(97L, 96L), repairedDays = emptySet(), today = 100L, tokensAvailable = 2)
        assertFalse(s.brokenBySingleGap)
        assertNull(s.repairableDay)
    }

    @Test fun streak_singleGapWithNoTokensOffersNoRepair() {
        // The gap is single (repairable in principle) but the allowance is spent → no repair offered.
        val s = ReviewCadence.computeStreak(reviewedDays = setOf(98L, 97L), repairedDays = emptySet(), today = 100L, tokensAvailable = 0)
        assertTrue(s.brokenBySingleGap)
        assertNull(s.repairableDay)
    }

    @Test fun streak_repairedDaysCountTowardStreak() {
        // 99 was repaired (settings overlay) so it bridges 98 → the streak reads 2 across 99 and 98.
        val s = ReviewCadence.computeStreak(reviewedDays = setOf(98L), repairedDays = setOf(99L), today = 100L, tokensAvailable = 1)
        assertEquals(2, s.streak)
    }

    // ── monthly token allowance ─────────────────────────────────────────────────────────────────────

    @Test fun tokens_keepStoredWithinSamePeriod() {
        assertEquals(1, ReviewCadence.tokensForPeriod(1, "2026-09", "2026-09"))
    }

    @Test fun tokens_refillOnNewPeriodAndCoerceToCap() {
        assertEquals(ReviewCadence.STREAK_REPAIR_CAP, ReviewCadence.tokensForPeriod(0, "2026-08", "2026-09"))
        assertEquals(ReviewCadence.STREAK_REPAIR_CAP, ReviewCadence.tokensForPeriod(99, "2026-09", "2026-09"))
    }

    @Test fun periodKey_isYearMonth() {
        assertEquals("2026-09", ReviewCadence.periodKey(LocalDate.of(2026, 9, 4).toEpochDay()))
    }
}
