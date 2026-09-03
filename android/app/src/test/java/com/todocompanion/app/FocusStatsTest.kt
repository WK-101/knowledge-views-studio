package com.todocompanion.app

import com.todocompanion.app.data.entity.FocusSessionEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.domain.FocusStats
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** R83 — coverage for the pure focus-stats math extracted from AppViewModel. */
class FocusStatsTest {
    private val zone = ZoneId.of("UTC")
    private val now = 1_700_000_000_000L                    // fixed "now"
    private val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
    private val minute = 60_000L

    private fun te(id: String, startAgoMin: Long, durMin: Long?, kind: String = "focus") =
        TimeEntryEntity(id = id, activityId = "a", startMillis = now - startAgoMin * minute,
            endMillis = durMin?.let { now - startAgoMin * minute + it * minute }, kind = kind)

    private fun legacy(id: String, epochDay: Long, minutes: Int) =
        FocusSessionEntity(id = id, epochDay = epochDay, startMillis = 0, minutes = minutes)

    @Test fun viewsClampRunningAndExcludeNonFocus() {
        val entries = listOf(
            te("done", startAgoMin = 30, durMin = 10),   // 10 min finished focus
            te("running", startAgoMin = 5, durMin = null), // running → clamped to now = 5 min
            te("manual", startAgoMin = 60, durMin = 20, kind = "manual"), // not focus → excluded
        )
        val views = FocusStats.views(entries, emptyList(), zone, now)
        assertEquals(setOf("done", "running"), views.map { it.id }.toSet())
        assertEquals(10, views.first { it.id == "done" }.minutes)
        assertEquals(5, views.first { it.id == "running" }.minutes)
    }

    @Test fun legacySessionsFoldInOnlyForUncoveredDays() {
        val entries = listOf(te("t", startAgoMin = 30, durMin = 10))       // timeline focus today
        val legacy = listOf(legacy("oldToday", today, 99), legacy("oldYesterday", today - 1, 42))
        val views = FocusStats.views(entries, legacy, zone, now)
        // today's legacy is dropped (day already covered by the timeline entry); yesterday's is kept.
        assertEquals(setOf("t", "oldYesterday"), views.map { it.id }.toSet())
    }

    @Test fun minutesByDaySumsPerDay() {
        val entries = listOf(te("a", 40, 10), te("b", 20, 15))            // both today → 25
        assertEquals(25, FocusStats.minutesByDay(entries, emptyList(), zone, now)[today])
    }

    @Test fun streakCountsBackAndTodayInProgressDoesNotBreak() {
        val met = mapOf(today to 60L.toInt(), today - 1 to 60, today - 2 to 10)
        assertEquals(2, FocusStats.streakDays(met, goalMin = 60, today = today))
        // Today still below goal (in progress) but the prior two days met it → streak stays 2.
        val inProgress = mapOf(today to 5, today - 1 to 60, today - 2 to 60, today - 3 to 0)
        assertEquals(2, FocusStats.streakDays(inProgress, goalMin = 60, today = today))
    }
}
