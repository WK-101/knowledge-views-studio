package com.todocompanion.app

import com.todocompanion.app.data.entity.DayLogEntity
import com.todocompanion.app.data.entity.FocusSessionEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TimeActivityEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.domain.DailyQuestion
import com.todocompanion.app.domain.DailyQuestions
import com.todocompanion.app.domain.FeltState
import com.todocompanion.app.domain.ReviewRollup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Phase D — the pure reflection roll-up: empty period, a normal week, and the correlation threshold. */
class ReviewRollupTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val now = System.currentTimeMillis()

    private fun dayMillis(epochDay: Long, hour: Int) =
        LocalDate.ofEpochDay(epochDay).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun log(
        day: Long, rating: Int = 0, good1: String = "", good2: String = "",
        lesson: String = "", promptAnswer: String = "", scores: Map<String, Int> = emptyMap(),
    ) = DayLogEntity(
        epochDay = day, dayRating = rating, good1 = good1, good2 = good2, lesson = lesson,
        promptAnswer = promptAnswer, dailyScoresJson = if (scores.isEmpty()) "" else DailyQuestions.scoresToJson(scores),
    )

    // An every-day build habit that started well before the test window.
    private fun habit(id: String) = HabitEntity(id = id, name = "Meditate", createdAt = dayMillis(50, 0))
    private fun checkin(id: String, day: Long) = HabitCheckinEntity(habitId = id, epochDay = day, count = 1, status = "done")

    // ── 1. Empty period ──
    @Test fun emptyPeriodHasNothing() {
        val r = ReviewRollup.compute(
            startDay = 100, endDay = 106, dayLogs = emptyList(), questions = emptyList(),
            habits = emptyList(), checkins = emptyList(), timeEntries = emptyList(), activities = emptyList(),
            zone = zone, now = now,
        )
        assertEquals(7, r.periodDays)
        assertEquals(0, r.reviewedDays)
        assertEquals(0, r.ratedDays)
        assertEquals(0.0, r.avgRating, 0.0001)
        assertTrue(r.wins.isEmpty())
        assertTrue(r.reflections.isEmpty())
        assertTrue(r.habitConsistency.isEmpty())
        assertTrue(r.topActivities.isEmpty())
        assertTrue(r.questionAverages.isEmpty())
        assertFalse(r.hasData)
    }

    // ── 2. A normal week: averages, wins, consistency, questions and time all correct ──
    @Test fun weekAggregatesCorrectly() {
        val q = DailyQuestion("q1", "Did I do my best to be present?")
        val logs = listOf(
            log(100, rating = 5, good1 = "Walk", lesson = "Sleep earlier", scores = mapOf("q1" to 4)),
            log(101, rating = 4, good1 = "walk", promptAnswer = "Rested well", scores = mapOf("q1" to 2)),
            log(102, rating = 3, good1 = "Read", scores = mapOf("q1" to 3)),
            log(103, rating = 2),
            log(104, rating = 1),
        )
        val checkins = listOf(checkin("h1", 100), checkin("h1", 101), checkin("h1", 102))
        val entries = listOf(TimeEntryEntity(id = "e1", activityId = "act1", startMillis = dayMillis(100, 9), endMillis = dayMillis(100, 10)))
        val activities = listOf(TimeActivityEntity(id = "act1", name = "Reading", createdAt = now))

        val r = ReviewRollup.compute(
            startDay = 100, endDay = 106, dayLogs = logs, questions = listOf(q),
            habits = listOf(habit("h1")), checkins = checkins, timeEntries = entries, activities = activities,
            zone = zone, now = now,
        )

        // Ratings: 5+4+3+2+1 = 15 over 5 rated days → 3.0.
        assertEquals(5, r.reviewedDays)
        assertEquals(5, r.ratedDays)
        assertEquals(3.0, r.avgRating, 0.0001)
        assertEquals(7, r.ratingTrend.size)
        assertEquals(5, r.ratingTrend[0])
        assertEquals(null, r.ratingTrend[6])

        // Wins: "Walk"/"walk" condense to one tally of 2; "Read" is separate.
        assertEquals(2, r.wins.size)
        assertEquals("Walk", r.wins[0].text)
        assertEquals(2, r.wins[0].count)

        // Reflections digest holds the lesson + the prompt answer.
        assertTrue(r.reflections.any { it.text == "Sleep earlier" })
        assertTrue(r.reflections.any { it.text == "Rested well" })

        // Habit consistency: expected every one of the 7 days, kept 3 → 42%.
        assertEquals(1, r.habitConsistency.size)
        val hc = r.habitConsistency[0]
        assertEquals(3, hc.kept)
        assertEquals(7, hc.expected)
        assertEquals(42, hc.pct)

        // Top activity: one hour on Reading.
        assertEquals(1, r.topActivities.size)
        assertEquals("Reading", r.topActivities[0].name)
        assertEquals(60, r.topActivities[0].minutes)

        // Daily question: (4+2+3)/3 = 3.0 over 3 scored days.
        assertEquals(1, r.questionAverages.size)
        assertEquals(3.0, r.questionAverages[0].avg, 0.0001)
        assertEquals(3, r.questionAverages[0].count)

        assertTrue(r.hasData)
    }

    // ── 3. Track 1 — the felt lane is the shared [FeltState] fold, embedded and delegated to. ──
    @Test fun feltSummaryIsEmbeddedAndDelegatesConvenienceFields() {
        val logs = listOf(log(100, rating = 5), log(101, rating = 4))
        val r = ReviewRollup.compute(
            startDay = 100, endDay = 106, dayLogs = logs, questions = emptyList(),
            habits = emptyList(), checkins = emptyList(), timeEntries = emptyList(), activities = emptyList(),
            zone = zone, now = now,
        )
        // The stored FeltSummary is exactly what FeltState.summarize produces for the same window/logs …
        assertEquals(FeltState.summarize(logs, 100, 106), r.felt)
        // … and the convenience fields are thin views over it (single source of truth).
        assertEquals(r.felt.avgRating, r.avgRating, 0.0)
        assertEquals(r.felt.ratedDays, r.ratedDays)
        assertEquals(r.felt.ratingTrend, r.ratingTrend)
        assertEquals(r.felt.avgMood, r.avgMood, 0.0)
        assertEquals(r.felt.moodDays, r.moodCount)
        assertEquals(r.felt.moodTrend, r.moodTrend)
    }

    // ── 4. Track 1 — the cross-engine period counts the recap / digest now DERIVE from, folded once. ──
    @Test fun foldsCrossEnginePeriodCounts() {
        val tasks = listOf(
            TaskEntity(id = "t1", listId = "l", title = "done", createdAt = now, updatedAt = now, completedAt = dayMillis(101, 12)),
            TaskEntity(id = "t2", listId = "l", title = "open", createdAt = now, updatedAt = now),
            TaskEntity(id = "t3", listId = "l", title = "out", createdAt = now, updatedAt = now, completedAt = dayMillis(200, 12)),
        )
        val checkins = listOf(checkin("h1", 100), checkin("h1", 101), checkin("h1", 102))
        val entries = listOf(TimeEntryEntity(id = "e1", activityId = "act1", startMillis = dayMillis(100, 9), endMillis = dayMillis(100, 10)))
        val activities = listOf(TimeActivityEntity(id = "act1", name = "Reading", createdAt = now))
        val focus = listOf(
            FocusSessionEntity(id = "f1", epochDay = 100, startMillis = dayMillis(100, 9), minutes = 30),
            FocusSessionEntity(id = "f2", epochDay = 101, startMillis = dayMillis(101, 9), minutes = 20),
            FocusSessionEntity(id = "f3", epochDay = 200, startMillis = dayMillis(200, 9), minutes = 99), // outside the window
        )
        val r = ReviewRollup.compute(
            startDay = 100, endDay = 106, dayLogs = emptyList(), questions = emptyList(),
            habits = listOf(habit("h1")), checkins = checkins, timeEntries = entries, activities = activities,
            zone = zone, now = now, tasks = tasks, focusSessions = focus,
        )
        assertEquals(1, r.completedTasks)       // only t1's completedAt falls in [100,106]
        assertEquals(3, r.checkinsMeetingGoal)  // three done + goal-meeting check-ins
        assertEquals(50, r.focusMinutes)        // 30 + 20 (day 200 excluded)
        assertEquals(60, r.trackedMinutes)      // one tracked hour on Reading
    }
}
