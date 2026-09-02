package com.todocompanion.app.reminders

import android.content.Context
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.ReminderEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.reminders.ReminderPresets
import java.util.UUID

/**
 * Creates, retunes and cancels task reminders, translating the user's default intensity tier into the
 * engine's `annoying`/`escalate` flags and keeping the alarm schedule in sync.
 *
 * R77 — split out of AppViewModel so the god-object shrinks and reminder wiring lives with the rest of
 * the reminders package. Holds no UI state: a `Context` (for AlarmScheduler / Notifications), the
 * repository, and a provider for the current default reminder tier. All methods are suspend; the
 * ViewModel keeps only its thin `viewModelScope.launch { … }` wrappers, so behaviour is unchanged.
 */
class ReminderController(
    private val context: Context,
    private val repo: AppRepository,
    private val defaultTierProvider: () -> Int,
) {
    // New reminders inherit the user's default intensity tier (Gentle/Persistent/Insistent); an explicit
    // annoying=true from a caller still forces at least Persistent.
    private fun defaultTierFlags(annoying: Boolean): Pair<Boolean, Boolean> {
        val tier = defaultTierProvider()
        return Pair(annoying || ReminderPresets.tierAnnoying(tier), ReminderPresets.tierEscalate(tier))
    }

    suspend fun addAbsolute(task: TaskEntity, atMillis: Long, annoying: Boolean = false) {
        val (ann, esc) = defaultTierFlags(annoying)
        val r = ReminderEntity(UUID.randomUUID().toString(), taskId = task.id, type = "absolute", atTime = atMillis, annoying = ann, escalate = esc)
        repo.upsertReminder(r)
        AlarmScheduler.schedule(context, r, task)
    }

    /** A reminder relative to the task's due or start ([type] = relativeToDue / relativeToStart). */
    suspend fun addRelative(task: TaskEntity, type: String, offsetMin: Int, annoying: Boolean = false) {
        val (ann, esc) = defaultTierFlags(annoying)
        val r = ReminderEntity(UUID.randomUUID().toString(), taskId = task.id, type = type, offsetMin = offsetMin, annoying = ann, escalate = esc)
        repo.upsertReminder(r)
        AlarmScheduler.schedule(context, r, task)
    }

    /** An expert reminder on the unified model: relativeToDeadline / dueDayAt (offsetMin = minute-of-day) /
     *  whenOverdue / random, optionally recurring-with-count. Inherits the default tier. */
    suspend fun addExpert(task: TaskEntity, type: String, offsetMin: Int = 0, repeatEveryMin: Int? = null, repeatCount: Int? = null) {
        val (ann, esc) = defaultTierFlags(false)
        val r = ReminderEntity(UUID.randomUUID().toString(), taskId = task.id, type = type, offsetMin = offsetMin,
            annoying = ann, escalate = esc, repeatEveryMin = repeatEveryMin, repeatCount = repeatCount)
        repo.upsertReminder(r)
        AlarmScheduler.schedule(context, r, task)
    }

    /** A permission-free place reminder: armed against a named place, fired when the user says they've
     *  arrived (NFC tag / QR / shortcut → todocompanion://arrive?place=…). No location tracking. */
    suspend fun addPlace(task: TaskEntity, placeName: String, onEnter: Boolean = true) {
        val r = ReminderEntity(UUID.randomUUID().toString(), taskId = task.id, type = "location",
            placeName = placeName.trim().ifBlank { "here" }, onEnter = onEnter)
        repo.upsertReminder(r)
    }

    /** Fire every place reminder matching [place] (blank = all) — the arrival trigger. */
    suspend fun fireArrivals(place: String) {
        val target = place.trim().lowercase()
        repo.allRemindersOnce().filter { it.type == "location" }.forEach { r ->
            val pn = (r.placeName ?: "").trim().lowercase()
            val match = target.isBlank() || pn.isBlank() || pn == target || pn.contains(target) || target.contains(pn)
            if (!match) return@forEach
            val t = repo.getTask(r.taskId) ?: return@forEach
            if (!t.completed && !t.trashed && !t.abandoned)
                Notifications.show(context, t.id, t.title, r.id, r.annoying, r.escalate, 0,
                    (if (r.onEnter) "📍 Arrived" else "📍 Leaving") + (r.placeName?.let { ": $it" } ?: ""))
        }
    }

    /** Toggle a reminder's persistent ("annoying") alarm — re-fires until the task is done. */
    suspend fun setAnnoying(reminder: ReminderEntity, task: TaskEntity, on: Boolean) {
        val nr = reminder.copy(annoying = on)
        repo.upsertReminder(nr)
        AlarmScheduler.cancel(context, reminder, task); AlarmScheduler.schedule(context, nr, task)
    }

    /** Set a reminder's intensity tier (0 Gentle · 1 Persistent · 2 Insistent), surfacing the engine's
     *  existing annoying/escalate behaviour behind one control. */
    suspend fun setTier(reminder: ReminderEntity, task: TaskEntity, tier: Int) {
        val nr = reminder.copy(annoying = ReminderPresets.tierAnnoying(tier), escalate = ReminderPresets.tierEscalate(tier))
        repo.upsertReminder(nr)
        AlarmScheduler.cancel(context, reminder, task); AlarmScheduler.schedule(context, nr, task)
    }

    suspend fun delete(reminder: ReminderEntity, task: TaskEntity) {
        repo.deleteReminder(reminder.id)
        AlarmScheduler.cancel(context, reminder, task)
    }
}
