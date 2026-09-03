package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TagEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TaskTagCrossRef
import com.todocompanion.app.data.entity.TimeActivityEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.domain.habit.HabitStats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * R86 — the pure cross-type time/insight reports, lifted out of AppViewModel (Tier S/V/W). Each is a
 * deterministic function of plain snapshots plus a zone and `now`, so it is independently
 * unit-testable and holds no Android or coroutine dependency. The ViewModel keeps thin wrappers that
 * pass its StateFlow `.value` snapshots. Behaviour is identical to the previous in-ViewModel bodies —
 * only the ambient reads (`tasks.value`, `ZoneId.systemDefault()`, `System.currentTimeMillis()`) are
 * turned into parameters. The imperative timer control (start/stop/pause) stays in the ViewModel.
 */
object TimeReports {
    data class TagLine(val tag: String, val minutes: Int, val tasksDone: Int, val habitDays: Int)
    data class BalanceSlice(val area: String, val weight: Int, val share: Double)

    /** Today's already-elapsed planned task blocks that carry no tracked time yet (U-tier "untracked"). */
    fun untrackedTodayBlocks(
        tasks: List<TaskEntity>,
        timeEntries: List<TimeEntryEntity>,
        zone: ZoneId,
        now: Long,
    ): List<TimeInsights.PlannedBlock> {
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val blocks = tasks.mapNotNull { t ->
            val due = t.dueDate ?: return@mapNotNull null
            if (t.completed || t.trashed || t.abandoned || t.isAllDay || t.isNote) return@mapNotNull null
            if (Instant.ofEpochMilli(due).atZone(zone).toLocalDate() != today) return@mapNotNull null
            val startMin = ((due - dayStart) / 60_000L).toInt().coerceIn(0, 1439)
            if (dayStart + startMin * 60_000L > now) return@mapNotNull null   // block hasn't started yet
            val dur = (t.durationMin ?: t.estimateMin ?: 30).coerceAtLeast(5)
            TimeInsights.PlannedBlock(t.id, t.title, startMin, dur)
        }
        return TimeInsights.untrackedBlocks(blocks, timeEntries, dayStart, now)
    }

    /** U6 — planned (estimate/duration) vs tracked minutes for this week's tasks. */
    fun planVsActualWeek(
        tasks: List<TaskEntity>,
        timeEntries: List<TimeEntryEntity>,
        zone: ZoneId,
        now: Long,
    ): TimeInsights.PlanActual {
        val weekStart = LocalDate.now(zone).minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val weekEndWindow = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val trackedByTask = timeEntries.filter { it.taskId != null }.groupBy { it.taskId!! }
            .mapValues { (_, es) -> es.sumOf { TimeTracking.minutesInWindow(it.startMillis, it.endMillis, weekStart, now + 1, now) } }
        val items = tasks.mapNotNull { t ->
            val planned = (t.estimateMin ?: t.durationMin ?: 0)
            if (planned <= 0) return@mapNotNull null
            val actual = trackedByTask[t.id] ?: 0
            val dueThisWeek = t.dueDate?.let { it in weekStart until weekEndWindow } ?: false
            if (actual <= 0 && !dueThisWeek) return@mapNotNull null
            TimeInsights.PlanActualItem(t.id, t.title, planned, actual)
        }
        return TimeInsights.planVsActual(items)
    }

    /** U7 — "what moves your momentum": activities whose tracked days correlate with habit success. */
    fun momentumLinks(
        habits: List<HabitEntity>,
        habitCheckins: List<HabitCheckinEntity>,
        timeActivities: List<TimeActivityEntity>,
        timeEntries: List<TimeEntryEntity>,
        zone: ZoneId,
        windowDays: Int = 60,
    ): List<String> {
        val hs = HabitStats
        val today = LocalDate.now(zone).toEpochDay()
        val universe = (0 until windowDays).map { today - it }
        val entriesByDay = HashMap<Long, MutableSet<String>>()
        timeEntries.forEach { e ->
            val d = Instant.ofEpochMilli(e.startMillis).atZone(zone).toLocalDate().toEpochDay()
            entriesByDay.getOrPut(d) { mutableSetOf() }.add(e.activityId)
        }
        val actName = timeActivities.associate { it.id to ((it.emoji?.plus(" ") ?: "") + it.name) }
        val out = mutableListOf<Pair<Double, String>>()
        habits.filter { !it.paused && it.habitType != "break" }.forEach { h ->
            val hc = habitCheckins.filter { it.habitId == h.id }
            val doneDays = hc.filter { it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            val expected = universe.filter { hs.isExpectedDay(h, it) }
            if (expected.size < 12) return@forEach
            timeActivities.filter { !it.archived }.forEach { a ->
                val cond = expected.filter { entriesByDay[it]?.contains(a.id) == true }.toSet()
                if (cond.size < 4 || expected.size - cond.size < 4) return@forEach
                val c = TimeInsights.conditionalRate(expected, doneDays, cond)
                if (c.lift >= 0.20) out += c.lift to
                    "Your ‘${h.name}’ habit lands ${(c.rateWith * 100).toInt()}% on days you track ${actName[a.id]}, vs ${(c.rateWithout * 100).toInt()}% otherwise."
            }
        }
        return out.sortedByDescending { it.first }.take(3).map { it.second }
    }

    /** V6 — hours + tasks-done + habit-days grouped by one cross-type tag. */
    fun crossTypeTagReport(
        tasks: List<TaskEntity>,
        timeEntries: List<TimeEntryEntity>,
        habits: List<HabitEntity>,
        habitCheckins: List<HabitCheckinEntity>,
        tags: List<TagEntity>,
        taskTags: List<TaskTagCrossRef>,
        zone: ZoneId,
        now: Long,
        windowDays: Int = 7,
    ): List<TagLine> {
        val today = LocalDate.now(zone)
        val winStart = today.minusDays((windowDays - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val startDay = today.minusDays((windowDays - 1).toLong()).toEpochDay()
        val endDay = today.toEpochDay()
        val acc = HashMap<String, IntArray>()   // tag → [minutes, tasksDone, habitDays]
        fun bucket(tag: String) = acc.getOrPut(tag.trim().lowercase()) { IntArray(3) }
        // time (U11 tags)
        TimeInsights.totalsByTag(timeEntries, winStart, now + 1, now).forEach { bucket(it.tag)[0] += it.minutes }
        // tasks completed in the window, by their tag names
        val tagName = tags.associate { it.id to it.name }
        val tagsByTask = taskTags.groupBy { it.taskId }.mapValues { e -> e.value.mapNotNull { tagName[it.tagId] } }
        tasks.forEach { t ->
            val ca = t.completedAt ?: return@forEach
            val d = Instant.ofEpochMilli(ca).atZone(zone).toLocalDate().toEpochDay()
            if (d in startDay..endDay) tagsByTask[t.id].orEmpty().forEach { if (it.isNotBlank()) bucket(it)[1] += 1 }
        }
        // habit "done" days in the window, keyed by the habit's category (used as a tag)
        val habById = habits.associateBy { it.id }
        val hs = HabitStats
        habitCheckins.forEach { ci ->
            if (ci.status != "done" || ci.epochDay !in startDay..endDay) return@forEach
            val h = habById[ci.habitId] ?: return@forEach
            if (h.category.isNotBlank() && hs.meetsGoal(h, ci.count)) bucket(h.category)[2] += 1
        }
        return acc.filter { it.value.any { v -> v > 0 } }
            .map { TagLine(it.key, it.value[0], it.value[1], it.value[2]) }
            .sortedByDescending { it.minutes + it.tasksDone * 30 + it.habitDays * 30 }
    }

    /** W4 — where the week went, by life area (cross-type tags), as normalized shares. */
    fun balanceBreakdown(
        tasks: List<TaskEntity>,
        timeEntries: List<TimeEntryEntity>,
        habits: List<HabitEntity>,
        habitCheckins: List<HabitCheckinEntity>,
        tags: List<TagEntity>,
        taskTags: List<TaskTagCrossRef>,
        zone: ZoneId,
        now: Long,
        windowDays: Int = 7,
    ): List<BalanceSlice> {
        val weighted = crossTypeTagReport(tasks, timeEntries, habits, habitCheckins, tags, taskTags, zone, now, windowDays)
            .map { it.tag to (it.minutes + it.tasksDone * 30 + it.habitDays * 30) }.filter { it.second > 0 }
        val total = weighted.sumOf { it.second }
        if (total == 0) return emptyList()
        return weighted.map { BalanceSlice(it.first, it.second, it.second.toDouble() / total) }.sortedByDescending { it.weight }
    }
}
