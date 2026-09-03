package com.todocompanion.app

import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.DoNext
import com.todocompanion.app.domain.priority.PriorityEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * R90 — coverage for the pure Do-Next focus logic extracted from AppViewModel. Exercises the
 * focused() layer's actionability + budget filters (the deeper ranking is covered by the existing
 * PriorityEngine tests). dayStartMin = 0 so "due day" is just the calendar day of the due date.
 */
class DoNextTest {
    private val zone = ZoneOffset.UTC
    private val now = 1_700_000_000_000L
    private val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    private val dueToday = today.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val dayMs = 86_400_000L

    private fun t(
        id: String, due: Long? = null, start: Long? = null, star: Boolean = false,
        estimate: Int? = null, energy: Int? = null,
    ) = TaskEntity(id = id, listId = "l", title = id, dueDate = due, startDate = start, star = star,
        estimateMin = estimate, energy = energy, createdAt = 0L, updatedAt = 0L)

    private fun focus(all: List<TaskEntity>, timeAvail: Int? = null, energyAvail: Int? = null) =
        DoNext.focused(all, now, PriorityEngine.Config(), emptyList(), emptyList(), emptyList(),
            timeAvail, energyAvail, zone, 0).map { it.id }.toSet()

    @Test fun keepsDueTodayAndStarred_dropsFutureAndUndated() {
        val all = listOf(
            t("due", due = dueToday, estimate = 30, energy = 3),
            t("starred", star = true),
            t("future", due = dueToday, start = now + dayMs),   // starts tomorrow → not yet actionable
            t("undated"),                                        // no due, not starred/flagged → not surfaced
        )
        val ids = focus(all)
        assertTrue("due today is actionable", "due" in ids)
        assertTrue("starred is actionable", "starred" in ids)
        assertFalse("future-start excluded", "future" in ids)
        assertFalse("undated/unstarred excluded", "undated" in ids)
    }

    @Test fun timeBudgetDropsOverEstimateButKeepsUnestimated() {
        val all = listOf(t("due", due = dueToday, estimate = 30), t("starred", star = true))
        val ids = focus(all, timeAvail = 10)      // 10-minute budget
        assertFalse("30-min task over a 10-min budget is dropped", "due" in ids)
        assertTrue("unestimated task is kept", "starred" in ids)
    }

    @Test fun energyBudgetDropsHighEnergyButKeepsUntagged() {
        val all = listOf(t("due", due = dueToday, energy = 3), t("starred", star = true))
        val ids = focus(all, energyAvail = 1)     // low-energy only
        assertFalse("high-energy task dropped under a low-energy cap", "due" in ids)
        assertTrue("untagged-energy task kept", "starred" in ids)
    }
}
