package com.wkhan.hexis

import com.wkhan.hexis.domain.nlp.QuickAddParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * R109 (Tier-3) — regression cover for the "in N hours" quick-add fix (Tier-1): a relative-hours phrase must
 * advance the DATE and time together, so "in 2 hours" at 23:00 lands tomorrow at 01:00 rather than today at
 * 01:00 (~22h in the past). Pure JVM, plain JUnit.
 */
class QuickAddRelativeHoursTest {

    @Test fun inNHours_crossingMidnight_landsOnNextDay() {
        val p = QuickAddParser.parse("call mom in 2 hours", LocalDateTime.of(2026, 1, 1, 23, 0))
        assertEquals(LocalDateTime.of(2026, 1, 2, 1, 0), p.dateTime)
        assertTrue(p.hasTime)
        assertEquals("call mom", p.title)
    }

    @Test fun inNHours_sameDay() {
        val p = QuickAddParser.parse("call mom in 2 hours", LocalDateTime.of(2026, 1, 1, 10, 0))
        assertEquals(LocalDateTime.of(2026, 1, 1, 12, 0), p.dateTime)
        assertTrue(p.hasTime)
    }

    @Test fun inAnHour_advancesByOne() {
        val p = QuickAddParser.parse("stretch in an hour", LocalDateTime.of(2026, 1, 1, 8, 30))
        assertEquals(LocalDateTime.of(2026, 1, 1, 9, 30), p.dateTime)
        assertTrue(p.hasTime)
    }
}
