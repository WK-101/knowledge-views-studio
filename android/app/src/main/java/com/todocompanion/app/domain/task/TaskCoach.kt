package com.todocompanion.app.domain.task

import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TaskRevisionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * R37 — the TASK-COACH brain. The habit-science levers, ported to tasks & projects: personal-kanban WIP,
 * just-in-time productivity micro-lessons, the deferral chain ("never defer twice"), and a reliability
 * horizon for recurring tasks. On-device, rules-only, no network, no LLM. Pure functions over entities
 * the app already holds.
 */
object TaskCoach {

    private fun day(millis: Long, zone: ZoneId, dayStartMin: Int): Long =
        Instant.ofEpochMilli(millis - dayStartMin * 60_000L).atZone(zone).toLocalDate().toEpochDay()

    private fun isOpen(t: TaskEntity) = !t.completed && !t.trashed && !t.abandoned && !t.isNote

    // ── Port 1 · personal-kanban WIP ──────────────────────────────────────────────────────────────
    /** Tasks that are "in progress": open, and started (their start date has arrived). The cheapest,
     *  schema-free signal of active work — a personal-kanban WIP count. */
    fun startedOpen(tasks: List<TaskEntity>, now: Long, zone: ZoneId = ZoneId.systemDefault(), dayStartMin: Int = 0): List<TaskEntity> {
        val today = day(now, zone, dayStartMin)
        return tasks.filter { t -> isOpen(t) && t.startDate != null && day(t.startDate!!, zone, dayStartMin) <= today }
    }

    data class Wip(val count: Int, val limit: Int, val overCap: Boolean)

    fun wip(tasks: List<TaskEntity>, limit: Int, now: Long, zone: ZoneId = ZoneId.systemDefault(), dayStartMin: Int = 0): Wip {
        val n = startedOpen(tasks, now, zone, dayStartMin).size
        return Wip(n, limit, limit > 0 && n >= limit)
    }

    // ── Port 3 · deferral chain ("never defer twice") ─────────────────────────────────────────────
    /** Should this task raise a "you've pushed this twice — shrink it or schedule it for real" nudge?
     *  Reads the stored deferral chain; two or more consecutive day-pushes trips it. */
    fun deferWarning(task: TaskEntity): Boolean = task.deferCount >= 2 && isOpen(task)

    // ── Port 2 · just-in-time productivity micro-lessons ──────────────────────────────────────────
    data class Lesson(val id: String, val emoji: String, val title: String, val body: String)

    /** A lesson for a single task, keyed to its situation right now — or null. [childCount] = subtask count. */
    fun taskLesson(task: TaskEntity, childCount: Int, hour: Int, now: Long, zone: ZoneId = ZoneId.systemDefault(), dayStartMin: Int = 0): Lesson? {
        if (!isOpen(task)) return null
        val today = day(now, zone, dayStartMin)
        val due = task.dueDate?.let { day(it, zone, dayStartMin) }
        if (task.deferCount >= 2) return LESSONS.getValue("defer")
        if (due != null && due < today - 2) return LESSONS.getValue("two_minute")
        val est = task.estimateMax ?: task.estimateMin ?: task.durationMin ?: 0
        if (est >= 90 && childCount == 0) return LESSONS.getValue("break_down")
        if (hour < 11 && task.importance >= 4 && due != null && due <= today) return LESSONS.getValue("frog")
        return null
    }

    /** One lesson for the Today list, chosen across today's open tasks. [childCounts] maps taskId → subtask count. */
    fun todayLesson(tasks: List<TaskEntity>, childCounts: Map<String, Int>, hour: Int, now: Long, zone: ZoneId = ZoneId.systemDefault(), dayStartMin: Int = 0): Lesson? {
        val today = day(now, zone, dayStartMin)
        val dueToday = tasks.filter { isOpen(it) && it.dueDate != null && day(it.dueDate!!, zone, dayStartMin) <= today }
        // Priority order matches taskLesson's own ranking.
        dueToday.firstOrNull { it.deferCount >= 2 }?.let { return LESSONS.getValue("defer") }
        dueToday.firstOrNull { day(it.dueDate!!, zone, dayStartMin) < today - 2 }?.let { return LESSONS.getValue("two_minute") }
        if (hour < 11) dueToday.firstOrNull { it.importance >= 4 }?.let { return LESSONS.getValue("frog") }
        dueToday.firstOrNull { (it.estimateMax ?: it.estimateMin ?: it.durationMin ?: 0) >= 90 && (childCounts[it.id] ?: 0) == 0 }?.let { return LESSONS.getValue("break_down") }
        return null
    }

    private val LESSONS: Map<String, Lesson> = listOf(
        Lesson("two_minute", "⏱️", "Try the two-minute version", "This has sat a while. Don't do the whole thing — do two minutes of it. Open the doc, write one line, make the call. Starting is the hard part; momentum handles the rest."),
        Lesson("break_down", "🧩", "Break it into first steps", "A big, vague task is a big, vague dread. Split it into the smallest concrete next action — \"draft the outline\", not \"write the report\". You can only ever start the next step anyway."),
        Lesson("frog", "🐸", "Eat the frog", "It's morning and your hardest task is due. Do it first, before the day fills up. Finishing the thing you're avoiding buys calm for everything after it (Brian Tracy)."),
        Lesson("defer", "🪃", "You've pushed this twice", "Rescheduling twice is a signal, not a plan. Either it's not really important — drop it — or it is, and it needs to be smaller or booked into a real time slot today."),
    ).associateBy { it.id }

    // ── Port 10 · recurring-task reliability horizon ───────────────────────────────────────────────
    /** A repeating task's honest analog to habit automaticity: your rolling on-time completion rate,
     *  read from the task's own revision history. Null if too little history. */
    data class Reliability(val completions: Int, val onTime: Int, val ratePct: Int)

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun reliability(task: TaskEntity, revisions: List<TaskRevisionEntity>, zone: ZoneId = ZoneId.systemDefault()): Reliability? {
        if (task.rrule.isNullOrBlank()) return null
        val mine = revisions.filter { it.taskId == task.id }
        // Each past "completed" snapshot is one occurrence; on-time = completed on/before its due date.
        data class Done(val completedDay: Long, val onTime: Boolean)
        val done = LinkedHashMap<Long, Done>()
        mine.forEach { rev ->
            val snap = runCatching { json.decodeFromString(TaskEntity.serializer(), rev.snapshotJson) }.getOrNull() ?: return@forEach
            val cAt = snap.completedAt ?: return@forEach
            if (!snap.completed) return@forEach
            val cDay = Instant.ofEpochMilli(cAt).atZone(zone).toLocalDate().toEpochDay()
            val dDay = snap.dueDate?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() }
            done[cDay] = Done(cDay, dDay == null || cDay <= dDay)
        }
        if (done.size < 3) return null
        val onTime = done.values.count { it.onTime }
        return Reliability(done.size, onTime, (onTime * 100 / done.size))
    }
}
