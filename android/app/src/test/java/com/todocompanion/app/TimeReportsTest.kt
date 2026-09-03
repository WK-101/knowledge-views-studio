package com.todocompanion.app

import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TagEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TaskTagCrossRef
import com.todocompanion.app.domain.TimeReports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * R86 — coverage for the pure cross-type reports extracted from AppViewModel (TimeReports).
 * Uses zero time entries so the task/habit contributions are fully under the test's control
 * (the time contribution routes through the already-tested TimeInsights.totalsByTag).
 */
class TimeReportsTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.now(zone)
    private val todayDay = today.toEpochDay()
    private val noonToday = today.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun doneTask(id: String) =
        TaskEntity(id = id, listId = "l", title = id, completed = true, completedAt = noonToday,
            createdAt = 0L, updatedAt = 0L)

    private fun tags() = listOf(TagEntity(id = "gw", name = "Work"))
    private fun refs() = listOf(TaskTagCrossRef("t1", "gw"), TaskTagCrossRef("t2", "gw"))
    private fun habits() = listOf(HabitEntity(id = "h", name = "Run", category = "health", createdAt = 0L))
    private fun checkins() = (0..2).map {
        HabitCheckinEntity(habitId = "h", epochDay = todayDay - it, count = 1, status = "done")
    }

    @Test fun crossTypeTagReportBucketsTasksAndHabitsByTag() {
        val tasks = listOf(doneTask("t1"), doneTask("t2"))
        val report = TimeReports.crossTypeTagReport(
            tasks, emptyList(), habits(), checkins(), tags(), refs(), zone, noonToday, windowDays = 7)
        val byTag = report.associateBy { it.tag }
        assertEquals(2, byTag["work"]?.tasksDone)      // both tasks tagged Work, completed today
        assertEquals(0, byTag["work"]?.minutes)        // no time entries
        assertEquals(3, byTag["health"]?.habitDays)    // three "done" days meeting the goal
        // weight = minutes + tasksDone*30 + habitDays*30 → health 90 > work 60, so health sorts first.
        assertEquals("health", report.first().tag)
    }

    @Test fun balanceBreakdownNormalizesShares() {
        val tasks = listOf(doneTask("t1"), doneTask("t2"))
        val slices = TimeReports.balanceBreakdown(
            tasks, emptyList(), habits(), checkins(), tags(), refs(), zone, noonToday, windowDays = 7)
        assertEquals(2, slices.size)
        assertEquals("health", slices.first().area)
        assertEquals(90, slices.first().weight)                 // 3 habit-days × 30
        assertEquals(0.6, slices.first().share, 1e-9)           // 90 / (90 + 60)
        assertEquals(1.0, slices.sumOf { it.share }, 1e-9)      // shares sum to 1
    }

    @Test fun untrackedTodayBlocksEmptyWithoutDueTasks() {
        val tasks = listOf(TaskEntity(id = "t", listId = "l", title = "t", createdAt = 0L, updatedAt = 0L))  // no due date → no block
        assertTrue(TimeReports.untrackedTodayBlocks(tasks, emptyList(), zone, noonToday).isEmpty())
    }
}
