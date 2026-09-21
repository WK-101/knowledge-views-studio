package com.todocompanion.app.ui

import com.todocompanion.app.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Safe partial decomposition of the AppViewModel coordinator — the **event / calendar CRUD** surface, lifted
 * out into its own collaborator (same proven pattern as timeVm / notesVm / tasksVm).
 *
 * It owns calendar creation/visibility/default/rename/delete and event save/quick-add/backfill/NL-parse/
 * move/delete/task-blocking, then (re)schedules the events' alarms. The two genuinely-shared touchpoints stay
 * on the coordinator and are reached through `app`: [AppViewModel.ensureDefaultCalendar] (8+ other methods —
 * task-blocking, the planner, backfill — need the default-calendar id) and [AppViewModel.quickAddOne] (a
 * non-time-like line typed on the calendar routes to the task-capture path). AppViewModel keeps one-line
 * forwarding shims so every screen call site (`vm.saveEvent(...)`, `vm.deleteEvent(...)`, …) is unchanged.
 *
 * Bodies are unchanged from the AppViewModel originals; only the receivers move (`viewModelScope`→`scope`,
 * `ensureDefaultCalendar()`→`app.ensureDefaultCalendar()`, `appCtx`→`app.appCtx`, `toast`→`app.toast`,
 * `quickAddOne`→`app.quickAddOne`, `settings`→`app.settings`).
 */
class CalendarViewModel(
    app: AppViewModel,
    scope: CoroutineScope,
    private val repo: AppRepository,
) : FeatureViewModel(app, scope) {

    fun createEventCalendar(name: String, color: Long) = scope.launch {
        val n = name.trim(); if (n.isBlank()) return@launch
        repo.upsertEventCalendar(com.todocompanion.app.data.entity.EventCalendarEntity(
            id = java.util.UUID.randomUUID().toString(), name = n, colorArgb = color,
            orderIndex = repo.eventCalendarsOnce().size, workspaceId = app.settings.value.activeWorkspaceId, createdAt = System.currentTimeMillis()))
    }
    fun setEventCalendarVisible(c: com.todocompanion.app.data.entity.EventCalendarEntity, visible: Boolean) = scope.launch { repo.upsertEventCalendar(c.copy(visible = visible)) }
    /** Make [id] the default calendar new events land in; clears the flag on every other calendar in this
     *  workspace so exactly one is default. */
    fun setDefaultEventCalendar(id: String) = scope.launch {
        val ws = app.settings.value.activeWorkspaceId
        repo.eventCalendarsOnce().filter { it.workspaceId == ws }.forEach { c ->
            val shouldBe = c.id == id
            if (c.isDefault != shouldBe) repo.upsertEventCalendar(c.copy(isDefault = shouldBe))
        }
    }
    fun renameEventCalendar(c: com.todocompanion.app.data.entity.EventCalendarEntity, name: String, color: Long) = scope.launch { repo.upsertEventCalendar(c.copy(name = name.trim().ifBlank { c.name }, colorArgb = color)) }
    fun deleteEventCalendar(id: String) = scope.launch {
        val evs = repo.eventsOnce().filter { it.calendarId == id }
        evs.forEach { com.todocompanion.app.reminders.AlarmScheduler.cancelEventAlerts(app.appCtx, it); repo.deleteEvent(it.id) }
        repo.deleteEventCalendar(id)
    }

    /** Create or update an event, then (re)schedule its alerts. */
    fun saveEvent(existingId: String?, calendarId: String, title: String, location: String, notes: String, url: String,
                  startMillis: Long, endMillis: Long, allDay: Boolean, rrule: String, alertsMinutes: String,
                  colorArgb: Long?, floating: Boolean = false, busy: Boolean = true,
                  organizer: String = "", attendees: String = "", rsvp: String = "") = scope.launch {
        val t = title.trim().ifBlank { "Event" }
        val old = existingId?.let { repo.eventById(it) }
        old?.let { com.todocompanion.app.reminders.AlarmScheduler.cancelEventAlerts(app.appCtx, it) }
        val e = (old ?: com.todocompanion.app.data.entity.EventEntity(
            id = java.util.UUID.randomUUID().toString(), calendarId = calendarId, title = t,
            startMillis = startMillis, endMillis = endMillis, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
            .copy(calendarId = calendarId, title = t, location = location.trim(), notes = notes.trim(), url = url.trim(),
                startMillis = startMillis, endMillis = endMillis, allDay = allDay, rrule = rrule, alertsMinutes = alertsMinutes,
                colorArgb = colorArgb, floating = floating, busy = busy,
                organizer = organizer.trim(), attendees = attendees.trim(), rsvp = rsvp.trim(),
                updatedAt = System.currentTimeMillis())
        repo.upsertEvent(e)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(app.appCtx, e)
    }

    /** A2 — "the self-writing day": turn a lived, untracked stretch into a calendar event in one tap,
     *  using the default event calendar (created on demand). No alerts — it's a record of what happened. */
    fun addQuickEvent(title: String, startMillis: Long, endMillis: Long) = scope.launch {
        if (endMillis <= startMillis) return@launch
        val calId = app.ensureDefaultCalendar()
        saveEvent(null, calId, title.ifBlank { "Logged" }, "", "", "", startMillis, endMillis, false, "", "", null)
        app.toastMsg("Added to the calendar")
    }

    /** A2 (classified) — record a lived, tracked-but-uncalendared stretch as what it actually was, instead
     *  of always minting a generic "Tracked time" event. [kind]:
     *   • "event"    → a plain calendar event (a meeting, appointment, something that happened),
     *   • "task"     → an event linked to [taskId] so it reads as time spent on that task,
     *   • "activity" → leave it exactly as tracked activity time (no calendar entry is created). */
    fun backfillLivedTime(kind: String, title: String, startMillis: Long, endMillis: Long, taskId: String?) = scope.launch {
        if (endMillis <= startMillis) return@launch
        when (kind) {
            "activity" -> app.toastMsg("Kept as tracked time")
            "task" -> {
                val calId = app.ensureDefaultCalendar()
                val now = System.currentTimeMillis()
                repo.upsertEvent(com.todocompanion.app.data.entity.EventEntity(
                    id = java.util.UUID.randomUUID().toString(), calendarId = calId,
                    title = title.trim().ifBlank { "Task time" }, startMillis = startMillis, endMillis = endMillis,
                    linkedTaskId = taskId, createdAt = now, updatedAt = now))
                app.toastMsg("Logged against the task")
            }
            else -> {
                val calId = app.ensureDefaultCalendar()
                saveEvent(null, calId, title.trim().ifBlank { "Logged" }, "", "", "", startMillis, endMillis, false, "", "", null)
                app.toastMsg("Added to the calendar")
            }
        }
    }

    /** Phase 2 P2 — a natural-language line typed on the calendar becomes an event (or a task, if it reads
     *  like one) entirely on-device via [EventParser]. [anchorMillis] is the moment relative words like
     *  "3pm"/"tomorrow" resolve against — the day the user is viewing — so the bar is contextual. */
    fun quickAddFromCalendar(text: String, anchorMillis: Long) = scope.launch {
        val raw = text.trim(); if (raw.isEmpty()) return@launch
        val zone = runCatching { if (app.settings.value.timeZone.isNotBlank()) java.time.ZoneId.of(app.settings.value.timeZone) else java.time.ZoneId.systemDefault() }
            .getOrDefault(java.time.ZoneId.systemDefault())
        val anchorDay = java.time.Instant.ofEpochMilli(anchorMillis).atZone(zone).toLocalDate()
        val draft = com.todocompanion.app.domain.calendar.EventParser.parse(raw, anchorDay, zone)
        if (draft == null || draft.isTask) {
            // Nothing time-like, or it opens with "remind me to…"/"todo" — let the task capture path own it.
            app.quickAddOne(raw, QuickAddOptions())
            app.toast(if (draft?.isTask == true) "Task added" else "Added to Inbox")
            return@launch
        }
        val calId = app.ensureDefaultCalendar()
        val e = com.todocompanion.app.data.entity.EventEntity(
            id = java.util.UUID.randomUUID().toString(), calendarId = calId, title = draft.title.ifBlank { "Event" },
            location = draft.location, startMillis = draft.startMillis, endMillis = draft.endMillis, allDay = draft.allDay,
            rrule = draft.rrule, alertsMinutes = draft.alertsMinutes,
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        repo.upsertEvent(e)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(app.appCtx, e)
        app.toast("Added “${e.title}”")
    }

    /** R56 — move an event (its whole series) to another calendar; used by the entries manager's bulk edit. */
    fun moveEventToCalendar(id: String, calendarId: String) = scope.launch {
        val e = repo.eventById(id) ?: return@launch
        if (e.calendarId == calendarId) return@launch
        repo.upsertEvent(e.copy(calendarId = calendarId, updatedAt = System.currentTimeMillis()))
    }

    /** Delete an event. scope: "series" (all), "this" (add an exdate), "following" (end the series before this day). */
    fun deleteEvent(id: String, delScope: String = "series", instanceDay: Long = 0) = scope.launch {
        val e = repo.eventById(id) ?: return@launch
        when {
            e.rrule.isBlank() || delScope == "series" -> { com.todocompanion.app.reminders.AlarmScheduler.cancelEventAlerts(app.appCtx, e); repo.deleteEvent(id) }
            delScope == "this" -> {
                val ex = (e.exDates.split(",").mapNotNull { it.trim().toLongOrNull() } + instanceDay).distinct().joinToString(",")
                repo.upsertEvent(e.copy(exDates = ex, updatedAt = System.currentTimeMillis()))
            }
            delScope == "following" -> repo.upsertEvent(e.copy(rrule = capUntil(e.rrule, instanceDay - 1), updatedAt = System.currentTimeMillis()))
        }
    }
    private fun capUntil(rule: String, untilDay: Long): String {
        val r = com.todocompanion.app.domain.recurrence.Recurrence.parse(rule) ?: return rule
        return com.todocompanion.app.domain.recurrence.Recurrence.encode(r.copy(untilEpochDay = untilDay, count = null))
    }

    /** FW moat — turn a task into a scheduled time block (an event linked back to the task). */
    fun blockTaskAsEvent(taskId: String, startMillis: Long, durationMin: Int) = scope.launch {
        val task = repo.getTask(taskId) ?: return@launch
        val calId = app.ensureDefaultCalendar()
        val e = com.todocompanion.app.data.entity.EventEntity(
            id = java.util.UUID.randomUUID().toString(), calendarId = calId, title = task.title,
            startMillis = startMillis, endMillis = startMillis + durationMin.coerceAtLeast(15) * 60000L,
            linkedTaskId = taskId, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        repo.upsertEvent(e); app.toast("Blocked ${durationMin}m for “${task.title}”.")
    }
}
