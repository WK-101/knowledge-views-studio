package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.FocusSessionEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import java.time.Instant
import java.time.ZoneId

/**
 * R83 — the pure focus-statistics math, lifted out of AppViewModel. Focus is a MODE of time tracking:
 * every focus statistic is derived from the `kind="focus"` intervals on the one timeline, with legacy
 * persisted FocusSessions unioned in for days that predate the unification. These functions take plain
 * snapshots + a zone + `now`, so they're deterministic and independently unit-testable. The ViewModel
 * keeps the live control (start/stop, the running-interval StateFlow) and the best-task selection.
 */
object FocusStats {

    /** Focus intervals as synthetic [FocusSessionEntity] rows. A running interval is clamped to [now];
     *  legacy sessions are folded in only for days with no timeline focus, so nothing is double-counted. */
    fun views(
        timeEntries: List<TimeEntryEntity>,
        legacySessions: List<FocusSessionEntity>,
        zone: ZoneId,
        now: Long,
    ): List<FocusSessionEntity> {
        val fromTimeline = timeEntries.asSequence().filter { it.kind == "focus" }.map {
            FocusSessionEntity(
                id = it.id,
                epochDay = Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate().toEpochDay(),
                startMillis = it.startMillis,
                minutes = (((it.endMillis ?: now) - it.startMillis) / 60_000L).toInt().coerceAtLeast(0),
                kind = "focus",
                taskId = it.taskId,
                workspaceId = it.workspaceId,
            )
        }.toList()
        val timelineDays = fromTimeline.mapTo(HashSet()) { it.epochDay }
        val legacy = legacySessions.filter { it.epochDay !in timelineDays }
        return fromTimeline + legacy
    }

    /** Focused minutes per calendar day. */
    fun minutesByDay(
        timeEntries: List<TimeEntryEntity>,
        legacySessions: List<FocusSessionEntity>,
        zone: ZoneId,
        now: Long,
    ): Map<Long, Int> =
        views(timeEntries, legacySessions, zone, now).groupBy { it.epochDay }.mapValues { e -> e.value.sumOf { it.minutes } }

    /**
     * Consecutive goal-met days counting back from [today]. Today only counts once it has already met the
     * goal, so a day still in progress never breaks the streak.
     */
    fun streakDays(byDay: Map<Long, Int>, goalMin: Int, today: Long): Int {
        val goal = goalMin.coerceAtLeast(1)
        var streak = 0
        var d = if ((byDay[today] ?: 0) >= goal) today else today - 1
        while ((byDay[d] ?: 0) >= goal) { streak++; d-- }
        return streak
    }
}
