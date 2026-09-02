package com.todocompanion.app

import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.done.Accomplishment
import com.todocompanion.app.domain.done.DoneKind
import com.todocompanion.app.domain.done.Impact
import com.todocompanion.app.domain.done.Percentile
import com.todocompanion.app.domain.task.TaskCoach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * R76 — unit coverage for three pure, user-facing engines that had none: the private-percentile
 * "was that big for me?" ranker, the Impact goal-rollup graph, and the TaskCoach rules brain.
 * All are pure functions over entities, so they're deterministic with injected dates.
 */

private fun acc(
    refId: String,
    epochDay: Long,
    kind: DoneKind = DoneKind.TASK,
    durationMin: Int = 0,
    outcome: String? = null,
) = Accomplishment(
    kind = kind, refId = refId, title = refId, whenMillis = epochDay * 86_400_000L,
    epochDay = epochDay, durationMin = durationMin, outcome = outcome,
)

private fun te(
    id: String,
    parentId: String? = null,
    title: String = id,
    isGoal: Boolean = false,
    isProject: Boolean = false,
    completed: Boolean = false,
    trashed: Boolean = false,
    abandoned: Boolean = false,
    isNote: Boolean = false,
    startDate: Long? = null,
    dueDate: Long? = null,
    deferCount: Int = 0,
    importance: Int = 3,
    estimateMax: Int? = null,
) = TaskEntity(
    id = id, listId = "l", parentId = parentId, title = title, isGoal = isGoal, isProject = isProject,
    completed = completed, trashed = trashed, abandoned = abandoned, isNote = isNote,
    startDate = startDate, dueDate = dueDate, deferCount = deferCount, importance = importance,
    estimateMax = estimateMax, createdAt = 0, updatedAt = 0,
)

class PercentileTest {
    @Test fun effortRankSurfacesOnlyTopQuartile() {
        val all = (1..5).map { acc("t$it", epochDay = it.toLong(), durationMin = it * 10) } // 10..50
        // The 50-minute finish beats 4 of 5 → top 20% → surfaces.
        val top = Percentile.effortRank(all.last(), all)
        assertEquals("Top 20% effort you've logged", top)
        // The 10-minute finish is bottom of the pack → nothing worth saying.
        assertNull(Percentile.effortRank(all.first(), all))
    }

    @Test fun effortRankNeedsAHistory() {
        val few = (1..4).map { acc("t$it", epochDay = it.toLong(), durationMin = it * 10) }
        assertNull("fewer than 5 prior efforts → no rank", Percentile.effortRank(few.last(), few))
        // A zero-duration item never ranks.
        assertNull(Percentile.effortRank(acc("z", epochDay = 9, durationMin = 0), few))
    }

    @Test fun bestWeekSinceCrowsOnlyAboutABusyWeek() {
        val today = LocalDate.of(2026, 6, 17) // a Wednesday
        val td = today.toEpochDay()
        // Three finishes this week, none earlier → the best week on record.
        val busy = listOf(acc("a", td), acc("b", td), acc("c", td))
        assertEquals("Your most-finished week on record — 3 done", Percentile.bestWeekSince(busy, today))
        // A quiet week (two finishes) stays silent.
        assertNull(Percentile.bestWeekSince(busy.take(2), today))
    }

    @Test fun todayStandoutRanksAmongActiveDays() {
        val today = LocalDate.of(2026, 6, 17)
        val td = today.toEpochDay()
        // Today: 3 finishes. Four earlier distinct days: 1 each → 5 active days, today is the biggest.
        val items = listOf(acc("a", td), acc("b", td), acc("c", td)) +
            (1..4).map { acc("d$it", td - it) }
        assertEquals("Your biggest day ever — 3 finished", Percentile.todayStandout(items, today))
    }
}

class ImpactTest {
    @Test fun tasksRollUpUnderTheirGoalAncestor() {
        val goal = te("g", isGoal = true, title = "Ship v1")
        val child = te("t", parentId = "g", title = "Write docs")
        val graph = Impact.build(
            items = listOf(acc("t", epochDay = 100, durationMin = 30, outcome = "shipped")),
            tasks = listOf(goal, child),
        )
        assertEquals(1, graph.finished)
        assertEquals(1, graph.goalsServed)
        assertEquals(1, graph.outcomes)
        assertEquals(1, graph.nodes.size)
        val node = graph.nodes.single()
        assertEquals("g", node.goalId)
        assertEquals("Ship v1", node.goalTitle)
        assertEquals(30, node.totalMinutes)
        assertEquals(1, node.items.size)
    }

    @Test fun taskWithNoGoalAncestorBecomesDirectWork() {
        val loose = te("t", title = "Loose task") // no parent, not a goal
        val graph = Impact.build(listOf(acc("t", epochDay = 100)), listOf(loose))
        val node = graph.nodes.single()
        assertNull(node.goalId)
        assertEquals("Direct work", node.goalTitle)
        assertFalse(node.isGoalDone)
    }
}

class TaskCoachTest {
    private val zone = ZoneId.of("UTC")
    private val now = 1_700_000_000_000L
    private val twoDaysAgo = now - 2 * 86_400_000L
    private val twoDaysAhead = now + 2 * 86_400_000L

    @Test fun wipCountsStartedOpenTasksAndFlagsOverCap() {
        val tasks = listOf(te("a", startDate = twoDaysAgo), te("b", startDate = twoDaysAgo))
        assertEquals(2, TaskCoach.wip(tasks, limit = 2, now = now, zone = zone).count)
        assertTrue(TaskCoach.wip(tasks, limit = 2, now = now, zone = zone).overCap)
        assertFalse(TaskCoach.wip(tasks, limit = 3, now = now, zone = zone).overCap)
    }

    @Test fun startedOpenExcludesFutureCompletedAndNotes() {
        val tasks = listOf(
            te("started", startDate = twoDaysAgo),
            te("future", startDate = twoDaysAhead),
            te("done", startDate = twoDaysAgo, completed = true),
            te("note", startDate = twoDaysAgo, isNote = true),
        )
        val open = TaskCoach.startedOpen(tasks, now, zone).map { it.id }
        assertEquals(listOf("started"), open)
    }

    @Test fun deferWarningTripsOnTheSecondPush() {
        assertTrue(TaskCoach.deferWarning(te("x", deferCount = 2)))
        assertFalse(TaskCoach.deferWarning(te("x", deferCount = 1)))
        // Even a twice-deferred task stays quiet once it's done.
        assertFalse(TaskCoach.deferWarning(te("x", deferCount = 2, completed = true)))
    }

    @Test fun taskLessonFlagsABigUnbrokenTask() {
        // A 90-minute task with no subtasks → the "break it down" lesson.
        val lesson = TaskCoach.taskLesson(te("big", estimateMax = 90), childCount = 0, hour = 14, now = now, zone = zone)
        assertNotNull(lesson)
        assertEquals("break_down", lesson!!.id)
    }
}
