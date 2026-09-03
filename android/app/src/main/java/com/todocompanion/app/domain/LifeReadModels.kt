package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.CountdownEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * R78 — the "next frontier" read models, lifted out of AppViewModel. Every function here is a pure
 * computation over snapshots the app already holds (tasks, time entries, countdowns, habits) plus a
 * date and zone — no StateFlow, no repository, no side effects — so each is independently unit-testable.
 * The ViewModel keeps thin accessors that pass in `.value` snapshots.
 */
object LifeReadModels {

    data class WeekDigest(val occasions: Int, val tasksDue: Int, val habitsActive: Int, val nextLine: String?)
    data class YearInPeople(val moments: Int, val topPerson: String?, val topCount: Int, val milestones: Int, val birthdays: Int)
    data class Chapter(val year: Int, val count: Int, val label: String)

    /** #13 — total tracked hours in the current calendar year, for the honest "life spent" line. */
    fun trackedHoursThisYear(timeEntries: List<TimeEntryEntity>, today: LocalDate, zone: ZoneId): Int {
        val yStart = today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val ms = timeEntries.sumOf { e ->
            val end = e.endMillis ?: return@sumOf 0L
            val s = maxOf(e.startMillis, yStart)
            (end - s).coerceAtLeast(0L)
        }
        return (ms / 3_600_000L).toInt()
    }

    /** #12 — a one-glance "this week" digest fusing occasions, tasks due and habits. */
    fun weekDigest(countdowns: List<CountdownEntity>, tasks: List<TaskEntity>, habits: List<HabitEntity>, today: LocalDate, zone: ZoneId): WeekDigest {
        val weekEnd = today.plusDays(7)
        val occ = countdowns.filter { !it.archived && !it.countUp }
            .count { val d = LifeEvent.daysUntil(it, today); d in 0..7 }
        val endMs = weekEnd.atStartOfDay(zone).toInstant().toEpochMilli()
        val nowMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val due = tasks.count { !it.completed && !it.trashed && !it.abandoned && !it.someday && !it.isNote && it.dueDate != null && it.dueDate!! in nowMs until endMs }
        val habitsActive = habits.count { !it.archived }
        val nextOcc = countdowns.filter { !it.archived && !it.countUp }
            .minByOrNull { LifeEvent.daysUntil(it, today).let { d -> if (d < 0) Long.MAX_VALUE else d } }
        val line = nextOcc?.takeIf { LifeEvent.daysUntil(it, today) in 0..7 }?.let {
            "${it.personName.ifBlank { it.title }} ${LifeEvent.daysLabel(LifeEvent.daysUntil(it, today))}"
        }
        return WeekDigest(occ, due, habitsActive, line)
    }

    /** #25/#26 — people whose keep-in-touch cadence has lapsed, most overdue first. */
    fun driftPeople(countdowns: List<CountdownEntity>, today: LocalDate): List<CountdownEntity> =
        countdowns.filter { !it.archived && it.keepInTouchDays > 0 && Moments.cadenceOverdue(it, today) }
            .sortedByDescending { Moments.daysSinceLast(it, today) ?: Long.MAX_VALUE }

    /** #29 — "anniversaries of your wins": starred / high-priority tasks finished on this day in a past year. */
    fun achievementAnniversaries(tasks: List<TaskEntity>, today: LocalDate, zone: ZoneId): List<Pair<Int, TaskEntity>> {
        val out = ArrayList<Pair<Int, TaskEntity>>()
        tasks.forEach { t ->
            if (!t.star && t.importance < 2) return@forEach
            val at = t.completedAt ?: return@forEach
            val d = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            if (d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth && d.year < today.year)
                out.add((today.year - d.year) to t)
        }
        return out.sortedByDescending { it.first }
    }

    /** #34 — a private "year in people" recap. */
    fun yearInPeople(countdowns: List<CountdownEntity>, today: LocalDate): YearInPeople {
        val yearStart = today.withDayOfYear(1).toEpochDay()
        var total = 0; var topPerson: String? = null; var topCount = 0
        countdowns.forEach { c ->
            val n = Moments.parse(c).count { it.d >= yearStart }
            total += n
            if (n > topCount) { topCount = n; topPerson = c.personName.ifBlank { c.title } }
        }
        val milestones = countdowns.count { LifeEvent.milestone(it, today) != null }
        val birthdays = countdowns.count { !it.archived && LifeEvent.type(it) == LifeEvent.EventType.BIRTHDAY }
        return YearInPeople(total, topPerson, topCount, milestones, birthdays)
    }

    /** #30 — "chapters of your life": each year you've been recording, labelled by its relative fullness. */
    fun lifeChapters(tasks: List<TaskEntity>, zone: ZoneId): List<Chapter> {
        val byYear = tasks.mapNotNull { it.completedAt }
            .groupingBy { Instant.ofEpochMilli(it).atZone(zone).year }.eachCount()
        if (byYear.size < 2) return emptyList()
        val max = byYear.values.maxOrNull() ?: return emptyList()
        return byYear.entries.sortedByDescending { it.key }.take(6).map { (y, c) ->
            val label = when {
                c == max -> "your fullest year"
                c >= max * 0.6 -> "a full chapter"
                c <= max * 0.25 -> "a quiet chapter"
                else -> "a steady chapter"
            }
            Chapter(y, c, label)
        }
    }

    /** R45 — On-This-Day: tasks the user completed on this calendar day (month+day) in prior years. */
    fun onThisDay(tasks: List<TaskEntity>, today: LocalDate, zone: ZoneId): List<Pair<Int, TaskEntity>> {
        val out = ArrayList<Pair<Int, TaskEntity>>()
        tasks.forEach { t ->
            val at = t.completedAt ?: return@forEach
            val d = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            if (d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth && d.year < today.year)
                out.add((today.year - d.year) to t)
        }
        return out.sortedBy { it.first }
    }
}
