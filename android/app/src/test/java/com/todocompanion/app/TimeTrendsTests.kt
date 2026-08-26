package com.todocompanion.app

import com.todocompanion.app.data.entity.TimeActivityEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.domain.TimeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Coverage for the Statistics "Trends & correlations" maths (weekday rhythm + cross-activity co-occurrence). */
class TimeTrendsTests {

    private val zone: ZoneId = ZoneId.of("UTC")
    private fun ms(d: LocalDate, hour: Int) = d.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()

    @Test fun trendsCountWeekdayAndActiveDays() {
        val anchor = LocalDate.of(2024, 1, 15)   // a Monday
        val e = TimeEntryEntity(id = "1", activityId = "a", startMillis = ms(anchor, 10), endMillis = ms(anchor, 11))
        val now = ms(anchor, 23)
        val t = TimeStats.trends(listOf(e), TimeStats.Range.WEEK, anchor, zone, now)
        assertEquals(60, t.totalMin)
        assertEquals(1, t.activeDays)
        assertEquals(7, t.windowDays)
        assertEquals(60, t.byWeekdayMin[0])       // Monday bucket
        assertEquals(0, t.byWeekdayMin[6])        // Sunday untouched
    }

    @Test fun correlationsFindCoOccurringActivities() {
        val today = LocalDate.now(zone)
        fun onDay(daysAgo: Long, act: String, hour: Int): TimeEntryEntity {
            val d = today.minusDays(daysAgo)
            return TimeEntryEntity(id = "$act-$daysAgo", activityId = act, startMillis = ms(d, hour), endMillis = ms(d, hour + 1))
        }
        // A tracked on 4 recent days; B on 3 of those same days.
        val entries = listOf(
            onDay(1, "A", 9), onDay(2, "A", 9), onDay(3, "A", 9), onDay(4, "A", 9),
            onDay(1, "B", 11), onDay(2, "B", 11), onDay(3, "B", 11),
        )
        val acts = listOf(
            TimeActivityEntity(id = "A", name = "Deep", createdAt = 0),
            TimeActivityEntity(id = "B", name = "Music", createdAt = 0),
        )
        val now = today.atStartOfDay(zone).plusHours(23).toInstant().toEpochMilli()
        val corr = TimeStats.correlations(entries, acts, zone, now)
        assertTrue(corr.isNotEmpty())
        val a = corr.first { it.aId == "A" }
        assertEquals(4, a.aDays)
        assertEquals(75, a.pct)          // 3 of 4 A-days also had B
        assertEquals("Music", a.bName)
    }
}
