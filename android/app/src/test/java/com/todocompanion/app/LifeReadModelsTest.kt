package com.todocompanion.app

import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.domain.LifeReadModels
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * R78 — coverage for the pure read-models extracted from AppViewModel. Focuses on the four that derive
 * purely from tasks / time entries (no LifeEvent/Moments fixtures needed), with a fixed zone + date.
 */
class LifeReadModelsTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 6, 15)

    private fun at(y: Int, mo: Int, d: Int, h: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    private fun task(id: String, completedAt: Long? = null, star: Boolean = false, importance: Int = 3) =
        TaskEntity(id = id, listId = "l", title = id, star = star, importance = importance,
            completed = completedAt != null, completedAt = completedAt, createdAt = 0, updatedAt = 0)

    private fun entry(id: String, start: Long, end: Long?) =
        TimeEntryEntity(id = id, activityId = "a", startMillis = start, endMillis = end)

    @Test fun trackedHoursThisYearClampsToYearStartAndIgnoresRunning() {
        val entries = listOf(
            entry("a", at(2026, 3, 1, 10), at(2026, 3, 1, 12)),   // 2h, fully in year
            entry("b", at(2025, 12, 31, 22), at(2026, 1, 1, 2)),  // crosses boundary → only the 2h in 2026 count
            entry("c", at(2026, 4, 1, 9), null),                  // running → 0
        )
        assertEquals(4, LifeReadModels.trackedHoursThisYear(entries, today, zone))
    }

    @Test fun lifeChaptersLabelsYearsByFullness() {
        val tasks = buildList {
            add(task("t24", at(2024, 6, 1)))
            repeat(4) { add(task("t25_$it", at(2025, 6, 1))) }
            repeat(2) { add(task("t26_$it", at(2026, 6, 1))) }
        }
        val chapters = LifeReadModels.lifeChapters(tasks, zone)
        assertEquals(listOf(2026, 2025, 2024), chapters.map { it.year })   // newest first
        assertEquals("your fullest year", chapters.first { it.year == 2025 }.label)
        assertEquals("a quiet chapter", chapters.first { it.year == 2024 }.label)
    }

    @Test fun lifeChaptersNeedsTwoYears() {
        assertEquals(emptyList<LifeReadModels.Chapter>(), LifeReadModels.lifeChapters(listOf(task("t", at(2026, 6, 1))), zone))
    }

    @Test fun onThisDayMatchesMonthDayAcrossPriorYears() {
        val tasks = listOf(
            task("y2025", at(2025, 6, 15, 9)),   // 1 year ago today
            task("y2024", at(2024, 6, 15, 9)),   // 2 years ago today
            task("other", at(2025, 6, 16, 9)),   // wrong day
            task("thisYear", at(2026, 6, 15, 9)), // same year → excluded
        )
        val res = LifeReadModels.onThisDay(tasks, today, zone)
        assertEquals(listOf(1, 2), res.map { it.first })            // ascending years-ago
        assertEquals(listOf("y2025", "y2024"), res.map { it.second.id })
    }

    @Test fun achievementAnniversariesKeepsOnlyNotableFinishes() {
        val tasks = listOf(
            task("starred", at(2024, 6, 15), star = true, importance = 1),     // notable via star
            task("important", at(2025, 6, 15), star = false, importance = 4),  // notable via importance
            task("trivial", at(2023, 6, 15), star = false, importance = 1),    // filtered out
        )
        val res = LifeReadModels.achievementAnniversaries(tasks, today, zone)
        assertEquals(listOf(2, 1), res.map { it.first })                        // most-distant first
        assertEquals(listOf("starred", "important"), res.map { it.second.id })
    }
}
