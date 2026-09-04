package com.todocompanion.app

import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.ExecutionScore
import com.todocompanion.app.domain.ReviewRollup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Track 2.1 — the weekly execution score vs the 85% benchmark. */
class ExecutionScoreTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private fun ms(day: Long) = day * 86_400_000L

    private fun task(
        id: String, due: Long? = null, start: Long? = null,
        completed: Boolean = false, trashed: Boolean = false, abandoned: Boolean = false,
    ) = TaskEntity(id = id, listId = "l", title = id, dueDate = due?.let { ms(it) }, startDate = start?.let { ms(it) },
        completed = completed, trashed = trashed, abandoned = abandoned, createdAt = 0L, updatedAt = 0L)

    private fun rollup(start: Long, end: Long, hc: List<ReviewRollup.HabitConsistency>) = ReviewRollup.Rollup(
        startDay = start, endDay = end, periodDays = (end - start + 1).toInt(), reviewedDays = 0, ratedDays = 0,
        avgRating = 0.0, ratingTrend = emptyList(), wins = emptyList(), moreWins = 0, reflections = emptyList(),
        moreReflections = 0, habitConsistency = hc, topActivities = emptyList(), questionAverages = emptyList(),
    )

    private fun hc(id: String, kept: Int, expected: Int) =
        ReviewRollup.HabitConsistency(id, id, null, null, kept, expected)

    @Test fun scoreMathAndVerdictBands() {
        // 8 of 10 planned → 80% → below the 85 benchmark.
        val below = ExecutionScore.Score(plannedTasks = 6, doneTasks = 5, expectedHabits = 4, keptHabits = 3)
        assertEquals(10, below.planned)
        assertEquals(8, below.completed)
        assertEquals(80, below.pct)
        assertEquals(ExecutionScore.Verdict.BELOW, below.verdict)
        assertTrue(below.hasData)

        // 88% sits in the on-track band (85..90).
        val onTrack = ExecutionScore.Score(plannedTasks = 25, doneTasks = 22, expectedHabits = 0, keptHabits = 0)
        assertEquals(88, onTrack.pct)
        assertEquals(ExecutionScore.Verdict.ON_TRACK, onTrack.verdict)

        // 100% is clearly above.
        val above = ExecutionScore.Score(plannedTasks = 5, doneTasks = 5, expectedHabits = 0, keptHabits = 0)
        assertEquals(100, above.pct)
        assertEquals(ExecutionScore.Verdict.ABOVE, above.verdict)
    }

    @Test fun emptyPlanHasNoData() {
        val s = ExecutionScore.Score(0, 0, 0, 0)
        assertFalse(s.hasData)
        assertEquals(0, s.pct)
        assertEquals(ExecutionScore.Verdict.BELOW, s.verdict)
    }

    @Test fun taskCommitmentsCountsDueOrScheduledInWindow_andSkipsTrashedAbandonedOutside() {
        val tasks = listOf(
            task("dueIn", due = 102, completed = true),        // planned + done (due in window)
            task("dueInOpen", due = 103),                       // planned, not done
            task("startIn", start = 101, completed = true),     // planned + done (scheduled in window)
            task("outside", due = 200, completed = true),        // outside window → ignored
            task("trashed", due = 102, completed = true, trashed = true),   // trashed → ignored
            task("wontdo", due = 102, abandoned = true),         // abandoned → ignored (deliberately released)
            task("nodate", completed = true),                    // no date → not a planned commitment
        )
        val (planned, done) = ExecutionScore.taskCommitments(tasks, 100, 106, zone)
        assertEquals(3, planned)
        assertEquals(2, done)
    }

    @Test fun fromRollupPoolsTasksAndHabits() {
        val tasks = listOf(
            task("a", due = 101, completed = true),
            task("b", due = 102, completed = true),
            task("c", due = 103),
        )
        val r = rollup(100, 106, listOf(hc("h1", kept = 5, expected = 7), hc("h2", kept = 2, expected = 3)))
        val s = ExecutionScore.fromRollup(r, tasks, zone)
        assertEquals(3, s.plannedTasks)
        assertEquals(2, s.doneTasks)
        assertEquals(10, s.expectedHabits)  // 7 + 3
        assertEquals(7, s.keptHabits)       // 5 + 2
        assertEquals(13, s.planned)
        assertEquals(9, s.completed)
        assertEquals(69, s.pct)             // 9/13 = 69.2 → 69
        assertEquals(ExecutionScore.Verdict.BELOW, s.verdict)
    }
}
