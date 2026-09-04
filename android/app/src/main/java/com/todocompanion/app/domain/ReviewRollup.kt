package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.DayLogEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TimeActivityEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.domain.habit.HabitStats
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Phase D — the reflection roll-up. A pure fold over an already-loaded slice of the store (the day
 * logs, active daily questions, habits + check-ins, time entries + activities) that aggregates a
 * *reviewed period* — a week or a month — into the read-only cards the Day Review shows in Week/Month
 * mode. It is the reflection counterpart to the cross-module {@link PeriodRecap}: where the recap tells
 * the "what you did vs. before" story, this tells the "what your reviews add up to" story — wins,
 * recurring lessons, habit consistency, top activities, daily-question averages, and a light,
 * descriptive "your best days share…" pattern.
 *
 * Everything is computed on the fly (no persistence, no schema change) and entirely on-device. Kept
 * free of Compose so it unit-tests as plain Kotlin, mirroring HabitStats / DayPrompts / DailyQuestions.
 */
object ReviewRollup {

    /** Caps so the read-only lists stay scannable; the overflow is surfaced as "+N more". */
    const val MAX_WINS = 12
    const val MAX_REFLECTIONS = 12
    const val MAX_ACTIVITIES = 5

    /** One aggregated "good thing", condensed across the period with how many days it recurred. */
    data class WinTally(val text: String, val count: Int)

    /**
     * One dated reflection fragment for the digest — a lesson, a prompt answer, or an evening reflection —
     * carried together with that day's key at-a-glance numbers (feature 6), so a note is never read
     * without its context. [rating]/[mood] are 0 when unset; [topActivityName] is null when no time was
     * tracked that day. All metrics are computed on the fly from the loaded data (no schema change).
     */
    data class ReflectionEntry(
        val epochDay: Long,
        val label: String,
        val text: String,
        val rating: Int = 0,
        val mood: Int = 0,
        val tasksDone: Int = 0,
        val topActivityEmoji: String? = null,
        val topActivityName: String? = null,
    )

    /** Per-habit kept-vs-expected over the period. [pct] is the consistency percentage for the meter bar. */
    data class HabitConsistency(
        val habitId: String, val name: String, val emoji: String?, val colorArgb: Long?,
        val kept: Int, val expected: Int,
    ) {
        val pct: Int get() = if (expected <= 0) 0 else (kept * 100) / expected
    }

    /** One time-activity's total minutes over the period, with its display attributes resolved. */
    data class ActivityDuration(
        val activityId: String, val name: String, val emoji: String?, val colorArgb: Long?, val minutes: Int,
    )

    /** One active daily question's average effort score over the period, plus a per-day trend for the sparkline. */
    data class QuestionAverage(
        val id: String, val text: String, val avg: Double, val count: Int, val trend: List<Int?>,
    )

    /** The immutable roll-up result the UI renders. Each list is empty when the period has nothing to show. */
    data class Rollup(
        val startDay: Long,
        val endDay: Long,
        val periodDays: Int,
        val reviewedDays: Int,
        val ratedDays: Int,
        val avgRating: Double,
        val ratingTrend: List<Int?>,
        val wins: List<WinTally>,
        val moreWins: Int,
        val reflections: List<ReflectionEntry>,
        val moreReflections: Int,
        val habitConsistency: List<HabitConsistency>,
        val topActivities: List<ActivityDuration>,
        val questionAverages: List<QuestionAverage>,
        // Phase E — goals the user marked as advanced during the period, most-advanced first ([count] = days).
        val goalsMoved: List<WinTally> = emptyList(),
        // Mood aggregation (evening mood 1–5): average, per-day trend (null = none), and how many days logged.
        val avgMood: Double = 0.0,
        val moodTrend: List<Int?> = emptyList(),
        val moodCount: Int = 0,
    ) {
        /** True when there is anything worth rendering beyond an empty-period hint. */
        val hasData: Boolean
            get() = reviewedDays > 0 || wins.isNotEmpty() || reflections.isNotEmpty() ||
                habitConsistency.isNotEmpty() || topActivities.isNotEmpty() || questionAverages.isNotEmpty() ||
                goalsMoved.isNotEmpty() || moodCount > 0
    }

    /** A day counts as "reviewed" once any close-the-day field is filled — mirrors the Day Review's own tally. */
    fun isReviewed(log: DayLogEntity): Boolean =
        log.pmReflection.isNotBlank() || log.dayRating > 0 || log.amIntention.isNotBlank() ||
            log.highlight.isNotBlank() || log.gratitude.isNotBlank() || log.lesson.isNotBlank() ||
            log.tomorrowFocus.isNotBlank()

    /**
     * Fold the loaded slice into a [Rollup] for the inclusive epoch-day window [startDay]..[endDay].
     * All inputs are plain data the caller already holds (day logs must already be workspace-scoped, as
     * the Day Review scopes them); habits should be the active, non-archived set.
     */
    fun compute(
        startDay: Long,
        endDay: Long,
        dayLogs: List<DayLogEntity>,
        questions: List<DailyQuestion>,
        habits: List<HabitEntity>,
        checkins: List<HabitCheckinEntity>,
        timeEntries: List<TimeEntryEntity>,
        activities: List<TimeActivityEntity>,
        zone: ZoneId,
        now: Long,
        goals: List<Goal> = emptyList(),
        tasks: List<TaskEntity> = emptyList(),
    ): Rollup {
        if (endDay < startDay) {
            return Rollup(startDay, endDay, 0, 0, 0, 0.0, emptyList(), emptyList(), 0, emptyList(), 0, emptyList(), emptyList(), emptyList())
        }
        val periodDays = (endDay - startDay + 1).toInt()
        val logByDay = dayLogs.filter { it.epochDay in startDay..endDay }.associateBy { it.epochDay }
        // One-time check-in index keyed by (habit, day) — the check-in table is unique on that pair.
        val checkinByKey = checkins.associateBy { it.habitId to it.epochDay }

        val reviewedDays = logByDay.values.count { isReviewed(it) }

        // ── 1. Ratings: average over rated days, and a per-day trend for the sparkline (null = no rating).
        val ratingTrend = (startDay..endDay).map { d -> logByDay[d]?.dayRating?.takeIf { it > 0 } }
        val ratings = ratingTrend.filterNotNull()
        val avgRating = if (ratings.isEmpty()) 0.0 else ratings.average()

        // ── 1b. Mood: evening mood (1–5) averaged over the days it was logged, plus a per-day trend.
        val moodTrend = (startDay..endDay).map { d -> logByDay[d]?.pmMood?.takeIf { it in 1..5 } }
        val moods = moodTrend.filterNotNull()
        val avgMood = if (moods.isEmpty()) 0.0 else moods.average()

        // ── 2. Wins: every "good thing" across the period, condensed case-insensitively, recurrences counted.
        val winsByKey = LinkedHashMap<String, WinTally>()
        for (d in startDay..endDay) {
            val log = logByDay[d] ?: continue
            listOf(log.good1, log.good2, log.good3).map { it.trim() }.filter { it.isNotEmpty() }.forEach { w ->
                val key = w.lowercase(Locale.getDefault())
                val cur = winsByKey[key]
                winsByKey[key] = cur?.copy(count = cur.count + 1) ?: WinTally(w, 1)
            }
        }
        val allWins = winsByKey.values.sortedByDescending { it.count }
        val wins = allWins.take(MAX_WINS)
        val moreWins = (allWins.size - wins.size).coerceAtLeast(0)

        // ── 3. Reflections digest: lessons, prompt answers and evening reflections, most-recent day first.
        // Feature 6 — each entry carries that day's key numbers (rating, mood, tasks done, top tracked
        // activity), computed on the fly, so the "why" always travels with the day it came from.
        val allReflections = buildList {
            for (d in endDay downTo startDay) {
                val log = logByDay[d] ?: continue
                if (log.lesson.isBlank() && log.promptAnswer.isBlank() && log.pmReflection.isBlank()) continue
                val (ws, we) = millisWindow(d, d, zone)
                val tasksDone = tasks.count { it.completed && !it.trashed && it.completedAt != null && it.completedAt!! in ws until we }
                val top = TimeTracking.totalsByActivity(timeEntries, ws, we, now).firstOrNull()
                val topAct = top?.let { at -> activities.firstOrNull { it.id == at.activityId } }
                fun entry(label: String, text: String) = ReflectionEntry(
                    epochDay = d, label = label, text = text,
                    rating = log.dayRating, mood = log.pmMood, tasksDone = tasksDone,
                    topActivityEmoji = topAct?.emoji, topActivityName = topAct?.name,
                )
                if (log.lesson.isNotBlank()) add(entry("💡 Lesson", log.lesson.trim()))
                if (log.promptAnswer.isNotBlank()) add(entry(DayPrompts.promptFor(d), log.promptAnswer.trim()))
                if (log.pmReflection.isNotBlank()) add(entry("🌙 Reflection", log.pmReflection.trim()))
            }
        }
        val reflections = allReflections.take(MAX_REFLECTIONS)
        val moreReflections = (allReflections.size - reflections.size).coerceAtLeast(0)

        // ── 4. Habit consistency: kept scheduled days / expected scheduled days over the period.
        val habitConsistency = habits.map { h ->
            var expected = 0
            var kept = 0
            for (d in startDay..endDay) {
                if (!HabitStats.isExpectedDay(h, d)) continue
                expected++
                val c = checkinByKey[h.id to d]
                if (c != null && c.status == "done" && HabitStats.meetsGoal(h, c.count)) kept++
            }
            HabitConsistency(h.id, h.name, h.emoji, h.colorArgb, kept, expected)
        }.filter { it.expected > 0 }.sortedByDescending { it.pct }

        // ── 5. Top time activities by total tracked minutes over the whole window.
        val (winStart, winEnd) = millisWindow(startDay, endDay, zone)
        val topActivities = TimeTracking.totalsByActivity(timeEntries, winStart, winEnd, now)
            .take(MAX_ACTIVITIES)
            .map { at ->
                val a = activities.firstOrNull { it.id == at.activityId }
                ActivityDuration(at.activityId, a?.name ?: "—", a?.emoji, a?.colorArgb, at.minutes)
            }

        // ── 6. Daily-question averages + per-day trend (reuses the Phase C scores map).
        val scoresByDay = (startDay..endDay).associateWith { d -> DailyQuestions.parseScores(logByDay[d]?.dailyScoresJson ?: "") }
        val questionAverages = questions.map { q ->
            val trend = (startDay..endDay).map { d -> scoresByDay[d]?.get(q.id) }
            val vals = trend.filterNotNull()
            QuestionAverage(q.id, q.text, if (vals.isEmpty()) 0.0 else vals.average(), vals.size, trend)
        }.filter { it.count > 0 }

        // ── 7. Phase E — goals advanced across the period, counted from each day's alignment blob and
        // resolved to live goal names/emoji (goals live in settings, passed in). Most-advanced first.
        val goalsMoved = if (goals.isEmpty()) emptyList() else {
            val countById = LinkedHashMap<String, Int>()
            for (d in startDay..endDay) {
                val log = logByDay[d] ?: continue
                DayAlignments.parse(log.alignmentJson).movedGoalIds.forEach { gid ->
                    countById[gid] = (countById[gid] ?: 0) + 1
                }
            }
            countById.mapNotNull { (gid, count) ->
                goals.firstOrNull { it.id == gid }?.let { WinTally("${it.emoji} ${it.name}", count) }
            }.sortedByDescending { it.count }
        }

        return Rollup(
            startDay = startDay, endDay = endDay, periodDays = periodDays, reviewedDays = reviewedDays,
            ratedDays = ratings.size, avgRating = avgRating, ratingTrend = ratingTrend,
            wins = wins, moreWins = moreWins, reflections = reflections, moreReflections = moreReflections,
            habitConsistency = habitConsistency, topActivities = topActivities,
            questionAverages = questionAverages,
            avgMood = avgMood, moodTrend = moodTrend, moodCount = moods.size, goalsMoved = goalsMoved,
        )
    }

    /** Millis window [start, end) for the inclusive day range, in [zone]. */
    private fun millisWindow(startDay: Long, endDay: Long, zone: ZoneId): Pair<Long, Long> {
        val s = LocalDate.ofEpochDay(startDay).atStartOfDay(zone).toInstant().toEpochMilli()
        val e = LocalDate.ofEpochDay(endDay + 1).atStartOfDay(zone).toInstant().toEpochMilli()
        return s to e
    }
}
