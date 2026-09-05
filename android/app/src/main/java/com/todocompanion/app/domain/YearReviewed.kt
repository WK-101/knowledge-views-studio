package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.DayLogEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TimeActivityEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.domain.done.DoneKind
import com.todocompanion.app.domain.done.DoneRecord
import com.todocompanion.app.domain.habit.HabitStats
import java.time.LocalDate
import java.time.ZoneId

/**
 * Wave 3 (feature B) — a fully-local "Year, Reviewed": a private, on-device year-in-review built entirely
 * from data the app already holds (day logs, habits + check-ins, tracked time). It folds a rolling
 * 365-day window (or any inclusive epoch-day range the caller passes) into the handful of figures a calm,
 * Spotify-Wrapped-style recap wants — days reviewed, average rating + mood with a monthly trend, total
 * tracked hours + top activities, habit consistency, wins / three-good-things counted, the longest review
 * streak, the most-common emotion word, and a standout highlight.
 *
 * Pure and Compose-free so it unit-tests as plain Kotlin, mirroring ReviewRollup / ReviewInsights.
 * Nothing leaves the device; everything is computed on the fly (no persistence, no schema change).
 *
 * Track 1.3 — this is now the single "year spine". Beyond the felt recap it also folds the achievement
 * counts the Record's "Wrapped" slides want (tasks finished, focus hours, habit days, the longest active
 * streak, the biggest month, wins marked). Those counts are derived by reusing [DoneRecord] over the same
 * window, so they are identical to what the Record's own feed reports — one source of truth for the year.
 */
object YearReviewed {

    /** The default look-back window when the caller wants "the last year". */
    const val WINDOW_DAYS = 365

    /** How many activities / habits to surface in the recap so each panel stays scannable. */
    private const val MAX_ACTIVITIES = 5
    private const val MAX_HABITS = 5

    data class TopActivity(val name: String, val emoji: String?, val colorArgb: Long?, val minutes: Int)

    data class HabitConsistency(val name: String, val emoji: String?, val kept: Int, val expected: Int) {
        val pct: Int get() = if (expected <= 0) 0 else (kept * 100) / expected
    }

    /** The immutable recap the UI renders. Empty-ish (see [hasData]) when there is nothing to show yet. */
    data class Recap(
        val startDay: Long,
        val endDay: Long,
        val periodDays: Int,
        val daysReviewed: Int,
        val avgRating: Double,
        val ratedDays: Int,
        val avgMood: Double,
        val moodDays: Int,
        // Per-calendar-month averages across the window (null = no data that month), oldest month first.
        val ratingTrend: List<Int?>,
        val moodTrend: List<Int?>,
        val trackedMinutes: Int,
        val topActivities: List<TopActivity>,
        val habitConsistency: List<HabitConsistency>,
        val winsCount: Int,                 // total "three good things" entries filled across the window
        val longestStreakDays: Int,         // longest run of consecutive reviewed days
        val topEmotionWord: String,         // "" = none named enough to report
        val topEmotionCount: Int,
        val highlightText: String,          // the standout highlight ("" = none)
        val highlightEpochDay: Long,
        val highlightRating: Int,
        // Track 1.3 — achievement counts folded from the same window via DoneRecord (the Record's spine).
        val tasksFinished: Int = 0,         // completed tasks (DoneKind.TASK) in the window
        val focusMinutesDone: Int = 0,      // minutes of finished focus sessions in the window
        val habitDaysKept: Int = 0,         // habit check-ins that met their goal, one per (habit, day)
        val winsMarked: Int = 0,            // items flagged as a "win"
        val activeDays: Int = 0,            // distinct days with any completed task / kept habit / focus
        val longestActiveStreakDays: Int = 0, // longest run of consecutive active days
        val biggestMonthValue: Int = 0,     // 1..12 of the month with the most accomplishments (0 = none)
        val biggestMonthCount: Int = 0,
        // Track 1 — the shared felt fold this recap's rating / mood / dominant-emotion figures are derived from,
        // so the year view and every other felt surface report the same numbers (the trend arrays above stay a
        // per-calendar-month roll-up, which is unique to the year view's sparkline).
        val felt: FeltState.FeltSummary = FeltState.EMPTY,
    ) {
        val trackedHours: Int get() = trackedMinutes / 60
        val focusHoursDone: Int get() = focusMinutesDone / 60
        /** True when the year has enough recorded for a recap to be worth showing at all. */
        val hasData: Boolean
            get() = daysReviewed > 0 || ratedDays > 0 || moodDays > 0 || trackedMinutes > 0 ||
                habitConsistency.isNotEmpty() || winsCount > 0 || highlightText.isNotBlank()
    }

    /**
     * Fold the loaded slice into a [Recap] for the inclusive epoch-day window [startDay]..[endDay]. All
     * inputs are plain data the caller already holds (day logs must already be workspace-scoped, as the
     * Day Review scopes them); habits should be the active, non-archived set.
     */
    fun compute(
        startDay: Long,
        endDay: Long,
        dayLogs: List<DayLogEntity>,
        habits: List<HabitEntity>,
        checkins: List<HabitCheckinEntity>,
        timeEntries: List<TimeEntryEntity>,
        activities: List<TimeActivityEntity>,
        zone: ZoneId,
        now: Long,
        tasks: List<TaskEntity> = emptyList(),
    ): Recap {
        if (endDay < startDay) {
            return Recap(startDay, endDay, 0, 0, 0.0, 0, 0.0, 0, emptyList(), emptyList(), 0, emptyList(), emptyList(), 0, 0, "", 0, "", 0, 0)
        }
        val periodDays = (endDay - startDay + 1).toInt()
        val logByDay = dayLogs.filter { it.epochDay in startDay..endDay }.associateBy { it.epochDay }
        val checkinByKey = checkins.associateBy { it.habitId to it.epochDay }

        // ── Reviewed days + longest consecutive review streak ──
        val reviewedDaysSet = logByDay.values.filter { ReviewRollup.isReviewed(it) }.map { it.epochDay }.toSortedSet()
        val daysReviewed = reviewedDaysSet.size
        val longestStreak = longestRun(reviewedDaysSet)

        // ── Rating & mood & dominant emotion: derived from the shared [FeltState] fold, so the window averages,
        //    rated/mood-day counts and the most-common emotion word match every other felt surface. Only the
        //    per-calendar-month trend arrays are computed here (a year-view-specific sparkline, not a per-day one).
        val felt = FeltState.summarize(dayLogs, startDay, endDay)
        val avgRating = felt.avgRating
        val avgMood = felt.avgMood
        val ratingTrend = monthlyTrend(startDay, endDay, logByDay) { it.dayRating.takeIf { r -> r in 1..5 } }
        val moodTrend = monthlyTrend(startDay, endDay, logByDay) { it.pmMood.takeIf { m -> m in 1..5 } }

        // ── Tracked time: total + top activities over the whole window ──
        val (winStart, winEnd) = millisWindow(startDay, endDay, zone)
        val actTotals = TimeTracking.totalsByActivity(timeEntries, winStart, winEnd, now)
        val trackedMinutes = actTotals.sumOf { it.minutes }
        val topActivities = actTotals.take(MAX_ACTIVITIES).map { at ->
            val a = activities.firstOrNull { it.id == at.activityId }
            TopActivity(a?.name ?: "—", a?.emoji, a?.colorArgb, at.minutes)
        }

        // ── Habit consistency: kept / expected scheduled days over the window, most-kept first ──
        val habitConsistency = habits.map { h ->
            var expected = 0
            var kept = 0
            for (d in startDay..endDay) {
                if (!HabitStats.isExpectedDay(h, d)) continue
                expected++
                val c = checkinByKey[h.id to d]
                if (c != null && c.status == "done" && HabitStats.meetsGoal(h, c.count)) kept++
            }
            HabitConsistency(h.name, h.emoji, kept, expected)
        }.filter { it.expected > 0 }.sortedWith(compareByDescending<HabitConsistency> { it.kept }.thenByDescending { it.pct }).take(MAX_HABITS)

        // ── Wins: total three-good-things entries filled across the window ──
        val winsCount = logByDay.values.sumOf { l -> listOf(l.good1, l.good2, l.good3).count { it.isNotBlank() } }

        // ── Most-common precise emotion word — from the shared felt fold (only known words count) ──
        val topEmotionWord = felt.dominantEmotion
        val topEmotionCount = felt.dominantEmotionCount

        // ── Standout highlight: the highlight from the highest-rated day (tie → most recent) ──
        val highlightDay = logByDay.values
            .filter { it.highlight.isNotBlank() }
            .maxWithOrNull(compareBy<DayLogEntity>({ it.dayRating }, { it.epochDay }))
        val highlightText = highlightDay?.highlight?.trim().orEmpty()

        // ── Achievement counts: reuse the Record's own feed over this window, so the numbers are identical ──
        val feed = DoneRecord.build(tasks, habits, checkins, timeEntries, zone).filter { it.epochDay in startDay..endDay }
        val ds = DoneRecord.stats(feed)
        val biggestMonth = feed
            .groupingBy { LocalDate.ofEpochDay(it.epochDay).monthValue }
            .eachCount().maxByOrNull { it.value }
        val focusMinutesDone = feed.filter { it.kind == DoneKind.FOCUS }.sumOf { it.durationMin }

        return Recap(
            startDay = startDay, endDay = endDay, periodDays = periodDays,
            daysReviewed = daysReviewed,
            avgRating = avgRating, ratedDays = felt.ratedDays,
            avgMood = avgMood, moodDays = felt.moodDays,
            ratingTrend = ratingTrend, moodTrend = moodTrend,
            trackedMinutes = trackedMinutes, topActivities = topActivities,
            habitConsistency = habitConsistency,
            winsCount = winsCount, longestStreakDays = longestStreak,
            topEmotionWord = topEmotionWord, topEmotionCount = topEmotionCount,
            highlightText = highlightText,
            highlightEpochDay = highlightDay?.epochDay ?: 0L,
            highlightRating = highlightDay?.dayRating ?: 0,
            tasksFinished = ds.totalTasks,
            focusMinutesDone = focusMinutesDone,
            habitDaysKept = ds.habitCheckins,
            winsMarked = ds.totalWins,
            activeDays = ds.activeDays,
            longestActiveStreakDays = ds.longestStreakDays,
            biggestMonthValue = biggestMonth?.key ?: 0,
            biggestMonthCount = biggestMonth?.value ?: 0,
            felt = felt,
        )
    }

    /** The longest run of consecutive epoch days present in [days]. */
    private fun longestRun(days: Set<Long>): Int {
        if (days.isEmpty()) return 0
        val sorted = days.toList()
        var best = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i] == sorted[i - 1] + 1) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    /**
     * Bucket a 1–5 signal by calendar month across the window and return the rounded per-month average
     * (null where a month has no data), oldest month first — a compact yearly sparkline.
     */
    private fun monthlyTrend(startDay: Long, endDay: Long, logByDay: Map<Long, DayLogEntity>, pick: (DayLogEntity) -> Int?): List<Int?> {
        val firstMonth = LocalDate.ofEpochDay(startDay).withDayOfMonth(1)
        val lastMonth = LocalDate.ofEpochDay(endDay).withDayOfMonth(1)
        val buckets = LinkedHashMap<String, MutableList<Int>>()
        var m = firstMonth
        while (!m.isAfter(lastMonth)) {
            buckets[m.toString().substring(0, 7)] = mutableListOf()
            m = m.plusMonths(1)
        }
        for (d in startDay..endDay) {
            val log = logByDay[d] ?: continue
            val v = pick(log) ?: continue
            val key = LocalDate.ofEpochDay(d).toString().substring(0, 7)
            buckets[key]?.add(v)
        }
        return buckets.values.map { if (it.isEmpty()) null else it.average().let { a -> Math.round(a).toInt() } }
    }

    private fun millisWindow(startDay: Long, endDay: Long, zone: ZoneId): Pair<Long, Long> {
        val s = LocalDate.ofEpochDay(startDay).atStartOfDay(zone).toInstant().toEpochMilli()
        val e = LocalDate.ofEpochDay(endDay + 1).atStartOfDay(zone).toInstant().toEpochMilli()
        return s to e
    }
}
