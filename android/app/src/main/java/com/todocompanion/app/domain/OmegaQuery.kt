package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.FocusSessionEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TimeActivityEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.domain.habit.HabitStats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything the Ω features (local Q&A, any-period recap, the annual report) read. Assembled once in
 * the view-model from the live store; the domain functions are pure over it. Nothing here touches the
 * network — the whole point is that a private, on-device store can answer questions no cloud app can.
 */
data class OmegaContext(
    val tasks: List<TaskEntity>,
    val habits: List<HabitEntity>,
    val checkins: List<HabitCheckinEntity>,
    val focus: List<FocusSessionEntity>,
    val timeEntries: List<TimeEntryEntity>,
    val activities: List<TimeActivityEntity>,
    val zone: ZoneId,
    val today: Long,     // today's epoch-day
    val now: Long,       // now, epoch-millis
)

/** A resolved date window (inclusive epoch-days) plus a human label ("this week"). */
data class OmegaPeriod(val startDay: Long, val endDay: Long, val label: String) {
    fun days(): Set<Long> = (startDay..endDay).toSet()
    /** Millis window [start, end) for the time-tracking helpers, in the given zone. */
    fun millis(zone: ZoneId): Pair<Long, Long> {
        val s = LocalDate.ofEpochDay(startDay).atStartOfDay(zone).toInstant().toEpochMilli()
        val e = LocalDate.ofEpochDay(endDay + 1).atStartOfDay(zone).toInstant().toEpochMilli()
        return s to e
    }

    companion object {
        /** Parse a period phrase out of a question. Rolling 7-day weeks (matching the weekly digest);
         *  calendar months and years. Default when nothing matches: this week. */
        fun parse(qLower: String, today: Long, zone: ZoneId): OmegaPeriod {
            val td = LocalDate.ofEpochDay(today)
            return when {
                qLower.contains("today") -> OmegaPeriod(today, today, "today")
                qLower.contains("yesterday") -> OmegaPeriod(today - 1, today - 1, "yesterday")
                qLower.contains("last week") || qLower.contains("past week") -> OmegaPeriod(today - 13, today - 7, "last week")
                qLower.contains("last month") -> {
                    val firstThis = td.withDayOfMonth(1)
                    val lastMonthEnd = firstThis.minusDays(1)
                    val lastMonthStart = lastMonthEnd.withDayOfMonth(1)
                    OmegaPeriod(lastMonthStart.toEpochDay(), lastMonthEnd.toEpochDay(), "last month")
                }
                qLower.contains("this month") || qLower.contains("month") -> {
                    OmegaPeriod(td.withDayOfMonth(1).toEpochDay(), today, "this month")
                }
                qLower.contains("this year") || qLower.contains("year") -> {
                    OmegaPeriod(td.withDayOfYear(1).toEpochDay(), today, "this year")
                }
                else -> OmegaPeriod(today - 6, today, "this week")
            }
        }
    }
}

/** Format minutes as "1h 20m" / "45m" / "2h". */
internal fun fmtHm(min: Int): String = when {
    min <= 0 -> "0m"
    min < 60 -> "${min}m"
    min % 60 == 0 -> "${min / 60}h"
    else -> "${min / 60}h ${min % 60}m"
}

/** Strength (0..100) of every active build habit, as of [today] — shared by query, recap and report. */
internal fun habitStrengths(habits: List<HabitEntity>, checkins: List<HabitCheckinEntity>, today: Long): List<Pair<HabitEntity, Int>> {
    val active = habits.filter { !it.archived && !it.paused && it.habitType != "break" }
    return active.map { h ->
        val hc = checkins.filter { it.habitId == h.id }
        val done = hc.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
        val skip = hc.filter { it.status == "skip" }.map { it.epochDay }.toSet()
        val rel = hc.filter { HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
        h to HabitStats.strength(h, done, skip, rel, today)
    }
}

/**
 * Ω2 — the local query engine. Answers a small, well-defined family of questions across all three
 * modules with zero cloud: hours on an activity or tag over a period, tasks completed, focus time,
 * habit check-ins, and the strongest habit. Best-effort: returns ok=false with a hint if it can't.
 */
object OmegaQuery {
    data class Answer(val ok: Boolean, val text: String)

    fun answer(question: String, ctx: OmegaContext): Answer {
        val q = question.lowercase().trim().removeSuffix("?")
        if (q.isBlank()) return hint()
        val period = OmegaPeriod.parse(q, ctx.today, ctx.zone)
        val days = period.days()
        val (winStart, winEnd) = period.millis(ctx.zone)

        // Strongest habit / longest streak.
        if (q.contains("streak") || q.contains("strongest") || (q.contains("best") && q.contains("habit"))) {
            val best = habitStrengths(ctx.habits, ctx.checkins, ctx.today).maxByOrNull { it.second }
                ?: return Answer(true, "No active habits yet — add one and its strength shows up here.")
            return Answer(true, "Your strongest habit is “${best.first.name}” at ${best.second}%.")
        }

        // Tasks completed.
        if (q.contains("task")) {
            val n = ctx.tasks.count { t ->
                t.completedAt?.let { Instant.ofEpochMilli(it).atZone(ctx.zone).toLocalDate().toEpochDay() in days } == true
            }
            return Answer(true, "You completed $n task${if (n == 1) "" else "s"} ${period.label}.")
        }

        // Focus minutes.
        if (q.contains("focus") || q.contains("pomodoro")) {
            val m = ctx.focus.filter { it.epochDay in days }.sumOf { it.minutes }
            return Answer(true, "You focused ${fmtHm(m)} ${period.label}.")
        }

        // Habit check-ins kept.
        if (q.contains("check-in") || q.contains("checkin") || (q.contains("habit") && (q.contains("day") || q.contains("kept")))) {
            val n = ctx.checkins.count { c ->
                c.epochDay in days && c.status == "done" &&
                    ctx.habits.firstOrNull { it.id == c.habitId }?.let { HabitStats.meetsGoal(it, c.count) } == true
            }
            return Answer(true, "You kept $n habit check-in${if (n == 1) "" else "s"} ${period.label}.")
        }

        // Time — on a named activity, a tag, or in total.
        if (q.contains("hour") || q.contains("time") || q.contains("spent") || q.contains("track") || q.contains("minute")) {
            val actMatch = ctx.activities.filter { !it.archived }
                .filter { it.name.isNotBlank() && q.contains(it.name.lowercase()) }
                .maxByOrNull { it.name.length }
            if (actMatch != null) {
                val mins = TimeTracking.totalsByActivity(ctx.timeEntries, winStart, winEnd, ctx.now)
                    .firstOrNull { it.activityId == actMatch.id }?.minutes ?: 0
                return Answer(true, "You tracked ${fmtHm(mins)} on ${actMatch.name} ${period.label}.")
            }
            val tagMatch = TimeInsights.totalsByTag(ctx.timeEntries, winStart, winEnd, ctx.now)
                .filter { q.contains(it.tag.lowercase()) }.maxByOrNull { it.tag.length }
            if (tagMatch != null) {
                return Answer(true, "You tracked ${fmtHm(tagMatch.minutes)} on #${tagMatch.tag} ${period.label}.")
            }
            val total = TimeTracking.totalMinutes(ctx.timeEntries, winStart, winEnd, ctx.now)
            return Answer(true, "You tracked ${fmtHm(total)} in total ${period.label}.")
        }

        return hint()
    }

    private fun hint() = Answer(false,
        "Try “hours on Reading this week”, “tasks done last week”, “focus this month”, or “best habit”.")
}
