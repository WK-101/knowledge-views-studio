package com.todocompanion.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.todocompanion.app.App
import com.todocompanion.app.data.entity.ChecklistItemEntity
import com.todocompanion.app.data.entity.ContextEntity
import com.todocompanion.app.data.entity.DependencyEntity
import com.todocompanion.app.data.entity.FolderEntity
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.ReminderEntity
import com.todocompanion.app.data.entity.FlagEntity
import com.todocompanion.app.data.entity.TemplateEntity
import com.todocompanion.app.data.entity.TagEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.domain.nlp.QuickAddParser
import com.todocompanion.app.domain.priority.PriorityEngine
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.domain.view.GroupMode
import com.todocompanion.app.domain.view.SmartKind
import com.todocompanion.app.domain.view.SortMode
import com.todocompanion.app.domain.view.TaskGroup
import com.todocompanion.app.domain.view.TaskViews
import com.todocompanion.app.domain.view.ViewRef
import com.todocompanion.app.reminders.AlarmScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID

/** A flattened, indented outline row. [matched] is false for a structural ancestor shown only
 *  to keep a filtered match in its tree position (rendered dimmed). */
data class OutlineRow(val task: TaskEntity, val depth: Int, val hasChildren: Boolean, val collapsed: Boolean, val matched: Boolean = true)

/** Options captured by the quick-add option toolbar; override anything parsed from text. */
data class QuickAddOptions(
    val dueMillis: Long? = null,
    val hasTime: Boolean = false,
    val priority: PriorityLevel? = null,
    val listId: String? = null,
    val tagIds: List<String> = emptyList(),
    val reminderMillis: Long? = null,
    val note: String = "",
    // V9: applied from inline capture tokens (#t25, *).
    val estimateMin: Int? = null,
    val star: Boolean = false,
    // R21: the quick-add sheet now offers the same first-class options as the editor.
    val contextIds: List<String> = emptyList(),
    val folderId: String? = null,       // folder-direct capture (no list)
    val rrule: String? = null,          // recurrence chosen in the date sheet
    val durationMin: Int? = null,
    val attachmentUris: List<android.net.Uri> = emptyList(),
)

enum class UndoKind { COMPLETED, ABANDONED, TRASHED }

/** What the full-screen habit editor is editing. A null [habit] means "create a new habit". */
data class HabitEditRequest(val habit: com.todocompanion.app.data.entity.HabitEntity? = null)
/** [restore], when set, is the exact pre-action task snapshot to write back on Undo — used for a
 *  recurring task's roll-forward, where "uncomplete" isn't enough (the due date & rule advanced). */
data class UndoEvent(val kind: UndoKind, val taskId: String, val message: String, val restore: TaskEntity? = null)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx get() = getApplication<App>()
    private val repo get() = appCtx.repository

    /** One-shot events for the "Undo" snackbar after a completion / won't-do / trash. */
    val undoEvents = kotlinx.coroutines.flow.MutableSharedFlow<UndoEvent>(extraBufferCapacity = 4)

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val settings: StateFlow<AppSettings> =
        repo.allSettings.map { AppSettings.fromMap(it.associate { s -> s.key to s.value }) }
            // Mirror theme fields to a synchronous cache so the next cold start's first frame is correct.
            .onEach { com.todocompanion.app.domain.ThemePrefs.save(appCtx, it) }
            // Seed the initial value from that cache — no dark→light flash on launch.
            .state(com.todocompanion.app.domain.ThemePrefs.read(appCtx).let { (mode, dyn, accent) ->
                AppSettings(themeMode = mode, dynamicColor = dyn, accentArgb = accent)
            })

    // R28 #4 — the seeded [settings] above only carries theme fields, so every OTHER field reads its
    // default on the first frame (e.g. onboardedModules=false), which briefly flashed the module picker on
    // every launch. This flips true once the real settings load from the DB, so launch-time dialogs gated on
    // a settings value wait for the real value instead of the default.
    val settingsLoaded: StateFlow<Boolean> = repo.allSettings.map { true }.state(false)

    // R28 #5/#7 — a query the command palette can push into the Settings search box ("setting dark mode").
    val settingsSearchQuery = MutableStateFlow("")

    // ---------- workspaces ----------
    val workspaces = repo.allWorkspaces.state(emptyList())
    private val activeWs: Flow<String> = settings.map { it.activeWorkspaceId }
    /** The single isolation choke point: list ids belonging to the active workspace (+ the shared Inbox). */
    private val activeListIds: Flow<Set<String>> =
        combine(repo.allLists, activeWs) { all, ws -> all.filter { it.workspaceId == ws }.map { it.id }.toSet() + ListEntity.INBOX_ID }
    /** Folders in the active workspace — the route into the set for tasks captured directly into a
     *  folder with no list (listId == ""), which otherwise belong to no list and would be dropped. */
    private val activeFolderIds: Flow<Set<String>> =
        combine(repo.allFolders, activeWs) { all, ws -> all.filter { it.workspaceId == ws }.map { it.id }.toSet() }
    private val wsTasks: Flow<List<TaskEntity>> =
        combine(repo.allTasks, activeListIds, activeFolderIds) { all, listIds, folderIds ->
            all.filter { it.listId in listIds || (it.folderId != null && it.folderId in folderIds) }
        }

    val tasks: StateFlow<List<TaskEntity>> = wsTasks.state(emptyList())
    // R29 #4 — the recap counts what you actually finished, across every workspace (an accomplishment
    // recap shouldn't vanish when you switch spaces). Exposed as its own flow so the Recap screen can
    // collect it: that both warms it (WhileSubscribed) and re-runs the recap when tasks load/change, so
    // "Tasks done" can no longer read a stale/empty snapshot and show zero.
    val allTasksLive: StateFlow<List<TaskEntity>> = repo.allTasks.state(emptyList())
    val folders = combine(repo.allFolders, activeWs) { f, ws -> f.filter { it.workspaceId == ws } }.state(emptyList())
    val lists = combine(repo.allLists, activeWs) { l, ws -> l.filter { it.workspaceId == ws || it.id == ListEntity.INBOX_ID } }.state(emptyList())
    val tags: StateFlow<List<TagEntity>> = combine(repo.allTags, activeWs) { t, ws -> t.filter { it.workspaceId == ws } }.state(emptyList())
    val contexts: StateFlow<List<ContextEntity>> = combine(repo.allContexts, activeWs) { c, ws -> c.filter { it.workspaceId == ws } }.state(emptyList())
    val flags: StateFlow<List<FlagEntity>> = repo.allFlags.state(emptyList())
    val templates: StateFlow<List<TemplateEntity>> = repo.allTemplates.state(emptyList())
    val countdowns = repo.allCountdowns.state(emptyList())
    val sealedNotes = repo.allSealedNotes.state(emptyList())
    val cravings = repo.allCravings.state(emptyList())
    // R34 — life-systems layer flows.
    val coreValues = repo.allCoreValues.state(emptyList())
    val witnessEvents = repo.allWitnessEvents.state(emptyList())
    val scorecardItems = repo.allScorecardItems.state(emptyList())
    val buddies = repo.allBuddies.state(emptyList())
    val integrityReviews = repo.allIntegrityReviews.state(emptyList())
    // R35 — third-wave flows.
    val experiments = repo.allExperiments.state(emptyList())
    val activationItems = repo.allActivationItems.state(emptyList())
    val dayLogs = repo.allDayLogs.state(emptyList())
    // R36 — fourth-wave flows.
    val escrows = repo.allEscrows.state(emptyList())
    val nudgeEvents = repo.allNudgeEvents.state(emptyList())
    // R38 — dedicated-calendar flows. calendarRoute overlays the Events surface (agenda|editor:<id>|
    // calendars|gaps|heatmap|worldclock|import). eventEditorId holds the event being edited (or "new").
    val eventCalendars = repo.allEventCalendars.state(emptyList())
    val events = repo.allEvents.state(emptyList())
    val calendarRoute = MutableStateFlow<String?>(null)
    // Non-null → the Life-Systems hub/screen overlays the tab (route key: hub|values|scorecard|correlations|reviews|ledger|buddies|friction|experiments|activation|forecast|heatmap|valuestime|runner|companion).
    val lifeSystemsRoute = MutableStateFlow<String?>(null)
    fun saveCountdown(id: String?, title: String, targetMillis: Long, emoji: String?, colorArgb: Long?) = viewModelScope.launch {
        val existing = id?.let { cid -> countdowns.value.firstOrNull { it.id == cid } }
        repo.upsertCountdown(
            (existing ?: com.todocompanion.app.data.entity.CountdownEntity(id = UUID.randomUUID().toString(), title = title, targetMillis = targetMillis, createdAt = System.currentTimeMillis()))
                .copy(title = title.trim().ifBlank { "Countdown" }, targetMillis = targetMillis, emoji = emoji, colorArgb = colorArgb)
        )
        com.todocompanion.app.widget.CountdownWidget.refresh(appCtx)
    }

    /**
     * R43 — save a full life-event / occasion (birthday, anniversary, memorial, name day, holiday or a
     * plain countdown) and keep its optional "prepare" task in sync: when prepLeadDays > 0 we create or
     * re-date a task in the Inbox that falls that many days before the next occurrence — the one thing a
     * single-purpose reminder app can't do, because it doesn't own the task list. Fully offline.
     */
    /** R45 — save a fully-built occasion row and keep its auto "prepare" task in sync. */
    fun saveOccasionRow(input: com.todocompanion.app.data.entity.CountdownEntity) = viewModelScope.launch {
        var row = input.copy(
            title = input.title.trim().ifBlank { com.todocompanion.app.domain.LifeEvent.EventType.from(input.eventType).label },
            personName = input.personName.trim(), notes = input.notes.trim(),
        )
        val prepLeadDays = row.prepLeadDays
        // Keep the auto "prepare" task in step with the occasion.
        val next = com.todocompanion.app.domain.LifeEvent.nextOccurrence(row)
        if (prepLeadDays > 0) {
            val dueDay = next.minusDays(prepLeadDays.toLong())
            val dueMillis = dueDay.atStartOfDay(zone).toInstant().toEpochMilli()
            val who = row.personName.ifBlank { row.title }
            val t = com.todocompanion.app.domain.LifeEvent.type(row)
            val prepTitle = when (t) {
                com.todocompanion.app.domain.LifeEvent.EventType.BIRTHDAY -> "🎁 Gift for $who — birthday"
                com.todocompanion.app.domain.LifeEvent.EventType.ANNIVERSARY -> "🎁 Plan for $who — anniversary"
                com.todocompanion.app.domain.LifeEvent.EventType.MEMORIAL -> "🕯️ Remember $who"
                else -> "Prepare for ${row.title}"
            }
            val existingTask = row.prepTaskId?.let { repo.getTask(it) }
            if (existingTask != null && !existingTask.trashed) {
                repo.saveTask(existingTask.copy(title = prepTitle, dueDate = dueMillis, completed = false, completedAt = null))
            } else {
                // #11/#16 — turn the milestone into a real plan: seed a checklist (and last year's gift, so you
                // don't repeat it) as the task's notes, once at creation so later edits are never clobbered.
                val lastGift = com.todocompanion.app.domain.Moments.lastGift(row)
                val prepNote = buildString {
                    appendLine("Prep checklist:")
                    appendLine("• Idea / gift")
                    appendLine("• Card or message")
                    appendLine("• Plan the day / budget")
                    lastGift?.let { appendLine("Last year you gave: ${it.second} (${it.first.year}) — pick something new.") }
                }.trim()
                val newId = repo.createTask(listId = com.todocompanion.app.data.entity.ListEntity.INBOX_ID, title = prepTitle, dueDate = dueMillis)
                repo.getTask(newId)?.let { repo.saveTask(it.copy(note = prepNote)) }
                row = row.copy(prepTaskId = newId)
            }
        } else if (row.prepTaskId != null) {
            // Prep turned off — trash the auto task so it doesn't linger, and forget the link.
            row.prepTaskId?.let { pid -> repo.getTask(pid)?.let { repo.saveTask(it.copy(trashed = true, trashedAt = System.currentTimeMillis())) } }
            row = row.copy(prepTaskId = null)
        }
        repo.upsertCountdown(row)
        com.todocompanion.app.widget.CountdownWidget.refresh(appCtx)
    }

    fun deleteCountdown(id: String) = viewModelScope.launch {
        // Also clean up the occasion's auto "prepare" task, if any.
        countdowns.value.firstOrNull { it.id == id }?.prepTaskId?.let { pid ->
            repo.getTask(pid)?.let { repo.saveTask(it.copy(trashed = true, trashedAt = System.currentTimeMillis())) }
        }
        repo.deleteCountdown(id); com.todocompanion.app.widget.CountdownWidget.refresh(appCtx)
    }
    fun toggleCountdownPin(c: com.todocompanion.app.data.entity.CountdownEntity) = viewModelScope.launch { repo.upsertCountdown(c.copy(pinned = !c.pinned)); com.todocompanion.app.widget.CountdownWidget.refresh(appCtx) }
    fun toggleOccasionFavorite(c: com.todocompanion.app.data.entity.CountdownEntity) = viewModelScope.launch { repo.upsertCountdown(c.copy(favorite = !c.favorite)) }
    fun setOccasionArchived(c: com.todocompanion.app.data.entity.CountdownEntity, archived: Boolean) = viewModelScope.launch { repo.upsertCountdown(c.copy(archived = archived)) }
    /** R46 — relationship loop / know-them deck: log a moment (or a question's answer) against an occasion.
     *  Stored as JSON on the row, so it rides the existing backup/sync; refreshes the live-countdown notif. */
    fun logOccasionMoment(c: com.todocompanion.app.data.entity.CountdownEntity, note: String) = viewModelScope.launch {
        if (note.isBlank()) return@launch
        repo.upsertCountdown(c.copy(momentsJson = com.todocompanion.app.domain.Moments.add(c, note)))
        refreshOccasionNotification()
    }
    fun removeOccasionMoment(c: com.todocompanion.app.data.entity.CountdownEntity, moment: com.todocompanion.app.domain.Moment) = viewModelScope.launch {
        repo.upsertCountdown(c.copy(momentsJson = com.todocompanion.app.domain.Moments.remove(c, moment)))
    }
    /** #9 — refresh (or clear) the ongoing "next occasion" notification from the current data + setting.
     *  Posted on demand (app open, occasion saved) rather than by a background worker — no new permission. */
    fun refreshOccasionNotification() = viewModelScope.launch {
        val on = settings.value.occasionLiveNotif
        com.todocompanion.app.reminders.Notifications.refreshOccasion(appCtx, if (on) countdowns.value else emptyList())
    }

    // ---- R47 "next frontier" read models (all computed from data we already hold) -----------------
    /** #13 — total tracked hours in the current calendar year, for the honest "life spent" line. */
    fun trackedHoursThisYear(today: java.time.LocalDate = java.time.LocalDate.now(zone)): Int {
        val yStart = today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val ms = timeEntries.value.sumOf { e ->
            val end = e.endMillis ?: return@sumOf 0L
            val s = maxOf(e.startMillis, yStart)
            (end - s).coerceAtLeast(0L)
        }
        return (ms / 3_600_000L).toInt()
    }

    /** #12 — a one-glance "this week" digest fusing occasions, tasks due and habits. */
    data class WeekDigest(val occasions: Int, val tasksDue: Int, val habitsActive: Int, val nextLine: String?)
    fun weekDigest(today: java.time.LocalDate = java.time.LocalDate.now(zone)): WeekDigest {
        val weekEnd = today.plusDays(7)
        val occ = countdowns.value.filter { !it.archived && !it.countUp }
            .count { val d = com.todocompanion.app.domain.LifeEvent.daysUntil(it, today); d in 0..7 }
        val endMs = weekEnd.atStartOfDay(zone).toInstant().toEpochMilli()
        val nowMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val due = allTasksLive.value.count { !it.completed && !it.trashed && !it.abandoned && !it.isNote && it.dueDate != null && it.dueDate!! in nowMs until endMs }
        val habitsActive = habits.value.count { !it.archived }
        val nextOcc = countdowns.value.filter { !it.archived && !it.countUp }
            .minByOrNull { com.todocompanion.app.domain.LifeEvent.daysUntil(it, today).let { d -> if (d < 0) Long.MAX_VALUE else d } }
        val line = nextOcc?.takeIf { com.todocompanion.app.domain.LifeEvent.daysUntil(it, today) in 0..7 }?.let {
            "${it.personName.ifBlank { it.title }} ${com.todocompanion.app.domain.LifeEvent.daysLabel(com.todocompanion.app.domain.LifeEvent.daysUntil(it, today))}"
        }
        return WeekDigest(occ, due, habitsActive, line)
    }

    /** #25/#26 — people whose keep-in-touch cadence has lapsed, most overdue first. */
    fun driftPeople(today: java.time.LocalDate = java.time.LocalDate.now(zone)): List<com.todocompanion.app.data.entity.CountdownEntity> =
        countdowns.value.filter { !it.archived && it.keepInTouchDays > 0 && com.todocompanion.app.domain.Moments.cadenceOverdue(it, today) }
            .sortedByDescending { com.todocompanion.app.domain.Moments.daysSinceLast(it, today) ?: Long.MAX_VALUE }

    /** #29 — "anniversaries of your wins": starred / high-priority tasks you finished on this day in a past year. */
    fun achievementAnniversaries(today: java.time.LocalDate = java.time.LocalDate.now(zone)): List<Pair<Int, TaskEntity>> {
        val out = ArrayList<Pair<Int, TaskEntity>>()
        allTasksLive.value.forEach { t ->
            if (!t.star && t.importance < 2) return@forEach
            val at = t.completedAt ?: return@forEach
            val d = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            if (d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth && d.year < today.year)
                out.add((today.year - d.year) to t)
        }
        return out.sortedByDescending { it.first }
    }

    /** #34 — a private "year in people" recap. */
    data class YearInPeople(val moments: Int, val topPerson: String?, val topCount: Int, val milestones: Int, val birthdays: Int)
    fun yearInPeople(today: java.time.LocalDate = java.time.LocalDate.now(zone)): YearInPeople {
        val yearStart = today.withDayOfYear(1).toEpochDay()
        var total = 0; var topPerson: String? = null; var topCount = 0
        countdowns.value.forEach { c ->
            val n = com.todocompanion.app.domain.Moments.parse(c).count { it.d >= yearStart }
            total += n
            if (n > topCount) { topCount = n; topPerson = c.personName.ifBlank { c.title } }
        }
        val milestones = countdowns.value.count { com.todocompanion.app.domain.LifeEvent.milestone(it, today) != null }
        val birthdays = countdowns.value.count { !it.archived && com.todocompanion.app.domain.LifeEvent.type(it) == com.todocompanion.app.domain.LifeEvent.EventType.BIRTHDAY }
        return YearInPeople(total, topPerson, topCount, milestones, birthdays)
    }

    /** #27 — log a gift against an occasion (rides the moments store). */
    fun logOccasionGift(c: com.todocompanion.app.data.entity.CountdownEntity, gift: String) = viewModelScope.launch {
        if (gift.isBlank()) return@launch
        repo.upsertCountdown(c.copy(momentsJson = com.todocompanion.app.domain.Moments.addGift(c, gift)))
    }

    /** #30 — "chapters of your life": each year you've been recording, labelled by its relative fullness. */
    data class Chapter(val year: Int, val count: Int, val label: String)
    fun lifeChapters(): List<Chapter> {
        val byYear = allTasksLive.value.mapNotNull { it.completedAt }
            .groupingBy { java.time.Instant.ofEpochMilli(it).atZone(zone).year }.eachCount()
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
    fun onThisDay(today: java.time.LocalDate = java.time.LocalDate.now(zone)): List<Pair<Int, TaskEntity>> {
        val out = ArrayList<Pair<Int, TaskEntity>>()
        allTasksLive.value.forEach { t ->
            val at = t.completedAt ?: return@forEach
            val d = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            if (d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth && d.year < today.year)
                out.add((today.year - d.year) to t)
        }
        return out.sortedBy { it.first }
    }
    val filters = combine(repo.allFilters, activeWs) { f, ws -> f.filter { it.workspaceId == ws } }.state(emptyList())
    val habits = combine(repo.allHabits, activeWs) { h, ws -> h.filter { it.workspaceId == ws && !it.archived } }.state(emptyList())
    val habitCheckins = repo.allCheckins.state(emptyList())
    val focusSessions = repo.allFocusSessions.state(emptyList())
    // R37 · Port 5 — the receptive hour (0..23) learned from when you actually finish habits & tasks, or
    // null when there isn't enough signal / the setting is off. Feeds the daily-brief scheduler.
    val receptiveHour: StateFlow<Int?> = combine(habitCheckins, allTasksLive, settings) { c, t, s ->
        if (!s.receptivityTiming) null
        else com.todocompanion.app.domain.habit.FourthWave.receptivity(c, t, zone)?.let { (it.bestBucket * 3 + 1).coerceIn(0, 23) }
    }.state(null)
    // Tier S: time tracking.
    val timeActivities = repo.allTimeActivities.state(emptyList())
    val timeEntries = repo.allTimeEntries.state(emptyList())
    val taskTags = repo.taskTagRefs.state(emptyList())
    val taskContexts = repo.taskContextRefs.state(emptyList())
    val checklist = repo.allChecklist.state(emptyList())
    val reminders = repo.allReminders.state(emptyList())
    val dependencies = repo.allDependencies.state(emptyList())

    val currentView = MutableStateFlow<ViewRef>(ViewRef.Smart(SmartKind.TODAY))
    val groupMode = MutableStateFlow(GroupMode.DATE)
    val sortMode = MutableStateFlow(SortMode.MANUAL)
    val outlineMode = MutableStateFlow(false)
    val boardMode = MutableStateFlow(false)
    /** True while the task list is in multi-select mode — used to hide the add FAB so its
     *  action bar (cancel/complete/flag/move/delete) doesn't overlap the button. */
    val selectionActive = MutableStateFlow(false)
    /** MLO-style "show matches in the tree" for filter/tag/context views. */
    val filterHierarchy = MutableStateFlow(false)

    // Honour the configured time zone (Settings) for all date math; fall back to the device zone.
    private val zone: ZoneId get() = settings.value.timeZone.takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    /** Public read-only view of the configured zone, so calendar UI expands occurrences consistently. */
    val zoneId: ZoneId get() = zone

    /** "Day starts at" rollover, in minutes past midnight, for Today/Tomorrow/overdue math. */
    private val dayStartMin: Int get() = settings.value.dayStartHour.coerceIn(0, 6) * 60

    /** Live task count per smart list, for the drawer. */
    val smartCounts: StateFlow<Map<SmartKind, Int>> =
        combine(
            wsTasks, repo.allDependencies, settings,
            combine(repo.taskContextRefs, repo.allContexts) { r, c -> r to c },
        ) { t, deps, set, rc ->
            val now = System.currentTimeMillis()
            SmartKind.entries.associateWith { k ->
                when (k) {
                    // Dependency-aware, so it can't go through the pure filterSmart path.
                    SmartKind.WAITING -> {
                        val byId = t.associateBy { it.id }
                        val blocked = PriorityEngine.computeBlocked(deps, byId, now)
                        t.count { !it.trashed && !it.completed && !it.abandoned && it.id in blocked }
                    }
                    // Do-Next uses the SAME focus filter as the rendered list, so the badge matches the list.
                    // (The transient "I have N min / energy" planners aren't applied to the badge.)
                    SmartKind.DO_NEXT -> doNextFocused(t, now, set.priorityConfig(), deps, rc.first, rc.second, null, null).size
                    // Trash is per-workspace (matches the rendered list); the shared Inbox otherwise leaked
                    // trashed tasks into every workspace's count.
                    SmartKind.TRASH -> t.count { it.trashed && it.workspaceId == set.activeWorkspaceId }
                    else -> TaskViews.filterSmart(t, k, now, zone, dayStartMin).size
                }
            }
        }.state(emptyMap())

    /** Optional live entry counts for the drawer's lists / folders / tags / contexts / filters (R19 #13). */
    data class EntryCounts(
        val lists: Map<String, Int> = emptyMap(),
        val folders: Map<String, Int> = emptyMap(),
        val tags: Map<String, Int> = emptyMap(),
        val contexts: Map<String, Int> = emptyMap(),
        val filters: Map<String, Int> = emptyMap(),
    )

    /** Live entry counts for the drawer, mirroring each view's own filter (active = not trashed /
     *  completed / abandoned; filters honour their own query). Only computed when the user turns
     *  "Show entry counts" on, so the default drawer stays cheap. */
    val entryCounts: StateFlow<EntryCounts> =
        combine(
            wsTasks,
            combine(repo.taskTagRefs, repo.taskContextRefs) { tt, tc -> tt to tc },
            combine(repo.allLists, repo.allFolders) { l, f -> l to f },
            combine(repo.allTags, repo.allContexts, repo.allFilters) { t, c, fi -> Triple(t, c, fi) },
            settings,
        ) { all, refs, lf, tcf, set ->
            if (!set.showEntryCounts) return@combine EntryCounts()
            val now = System.currentTimeMillis()
            val (ttRefs, tcRefs) = refs
            val (lists, folders) = lf
            val (allTagsL, _, filtersL) = tcf
            val active = all.filter { !it.trashed && !it.completed && !it.abandoned }
            val activeIds = active.mapTo(HashSet()) { it.id }
            val listCounts = active.groupingBy { it.listId }.eachCount()
            val folderCounts = folders.associate { fo ->
                val ids = folderListIds(fo.id, lists, folders)
                fo.id to active.count { it.listId in ids || it.folderId == fo.id }
            }
            // Tag counts roll up the subtree, like folders: a parent tag's count includes every task
            // tagged with it OR any descendant tag (distinct tasks, so a task tagged with both parent
            // and child isn't double-counted).
            val tasksByTag = ttRefs.filter { it.taskId in activeIds }.groupBy { it.tagId }.mapValues { e -> e.value.mapTo(HashSet()) { it.taskId } }
            val tagChildren = allTagsL.groupBy { it.parentId }
            val tagCounts = allTagsL.associate { tg ->
                val seen = HashSet<String>(); val stack = ArrayDeque<String>().apply { add(tg.id) }
                val taskIds = HashSet<String>()
                while (stack.isNotEmpty()) { val id = stack.removeLast(); if (seen.add(id)) { tasksByTag[id]?.let { taskIds.addAll(it) }; tagChildren[id]?.forEach { stack.add(it.id) } } }
                tg.id to taskIds.size
            }.filterValues { it > 0 }
            val ctxCounts = tcRefs.filter { it.taskId in activeIds }.groupingBy { it.contextId }.eachCount()
            val tagsByTask = ttRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.tagId }.toSet() }
            val ctxByTask = tcRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.contextId }.toSet() }
            val listFolderById = lists.associate { it.id to it.folderId }
            fun folderOf(t: TaskEntity) = t.folderId ?: listFolderById[t.listId]
            val filterCounts = filtersL.associate { fl ->
                val q = com.todocompanion.app.domain.view.Filters.parse(fl.queryJson)
                fl.id to all.count { !it.trashed && com.todocompanion.app.domain.view.Filters.matches(q, it, tagsByTask[it.id].orEmpty(), ctxByTask[it.id].orEmpty(), now, zone, folderOf(it)) }
            }
            EntryCounts(listCounts, folderCounts, tagCounts, ctxCounts, filterCounts)
        }.state(EntryCounts())

    // P1: reliability (0–100) for every recurring task, from the completion activity trail.
    val taskReliability: StateFlow<Map<String, com.todocompanion.app.domain.task.TaskReliability.Reliability>> =
        combine(wsTasks, repo.allActivity) { t, acts ->
            val now = System.currentTimeMillis()
            t.filter { !it.rrule.isNullOrBlank() && !it.trashed }
                .mapNotNull { task -> com.todocompanion.app.domain.task.TaskReliability.score(task, acts, now, zone)?.let { task.id to it } }
                .toMap()
        }.state(emptyMap())

    private data class Cfg(val view: ViewRef, val group: GroupMode, val sort: SortMode, val prio: PriorityEngine.Config, val flags: List<FlagEntity>, val timeAvail: Int? = null, val energyAvail: Int? = null, val ws: String = "default")

    /** "I have N minutes" planner: when set, Do-Next hides tasks whose estimate exceeds N. null = off. */
    val timeAvailableMin = MutableStateFlow<Int?>(null)

    /** A counter the shared scaffold's FAB bumps to ask the Time tab to add a new entry — so the Time
     *  screen keeps its own dialog logic while its add button lives with every other tab's FAB. */
    val addTimeEntryRequests = MutableStateFlow(0)

    /** "Right now I have X energy" planner: when set (1/2/3), Do-Next keeps tasks needing at most that
     *  much energy (plus untagged). null = off. */
    val energyAvailable = MutableStateFlow<Int?>(null)

    /** Cross-ref + container context threaded into the groups combine. */
    private data class ViewCtx(
        val tcRefs: List<com.todocompanion.app.data.entity.TaskContextCrossRef>,
        val contexts: List<ContextEntity>,
        val filters: List<com.todocompanion.app.data.entity.FilterEntity>,
        val lists: List<ListEntity>,
        val folders: List<FolderEntity>,
        val tags: List<TagEntity>,
    )

    /** All ids in the subtree rooted at [rootId] — the root plus every descendant, following parent
     *  links. Cycle-safe. Used so a parent tag / context page rolls up its children's tasks (R29 #2). */
    private fun <T> subtreeIds(rootId: String, entities: List<T>, idOf: (T) -> String, parentOf: (T) -> String?): Set<String> {
        val children = entities.groupBy { parentOf(it) }
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque<String>().apply { add(rootId) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!out.add(cur)) continue
            children[cur].orEmpty().forEach { queue.add(idOf(it)) }
        }
        return out
    }

    /** All list ids inside a folder, including nested folders and nested lists. */
    private fun folderListIds(folderId: String, lists: List<ListEntity>, folders: List<FolderEntity>): Set<String> {
        val folderIds = mutableSetOf(folderId)
        var changed = true
        while (changed) {
            changed = false
            folders.forEach { if (it.parentId in folderIds && it.id !in folderIds) { folderIds.add(it.id); changed = true } }
        }
        return lists.filter { it.folderId in folderIds }.map { it.id }.toSet()
    }

    private fun AppSettings.priorityConfig() = PriorityEngine.Config(
        mode = when (priorityMode) { "importance" -> PriorityEngine.Mode.IMPORTANCE; "urgency" -> PriorityEngine.Mode.URGENCY; else -> PriorityEngine.Mode.BOTH },
        dueWeight = priorityDueWeight, startWeight = priorityStartWeight, goalWeight = priorityGoalWeight, overdueBoost = priorityOverdueBoost,
        starBoost = priorityStarBoost, curveBase = priorityCurveBase, computed = priorityComputed,
    )

    val groups: StateFlow<List<TaskGroup>> =
        combine(
            wsTasks,
            combine(currentView, groupMode, sortMode, settings, combine(repo.allFlags, timeAvailableMin, energyAvailable) { fl, ta, ea -> Triple(fl, ta, ea) }) { v, g, s, set, fte -> Cfg(v, g, s, set.priorityConfig(), fte.first, fte.second, fte.third, set.activeWorkspaceId) },
            repo.taskTagRefs,
            combine(repo.taskContextRefs, repo.allContexts, repo.allFilters, repo.allLists, combine(repo.allFolders, repo.allTags) { fo, tg -> fo to tg }) { r, c, f, l, foTg -> ViewCtx(r, c, f, l, foTg.first, foTg.second) },
            repo.allDependencies,
        ) { all, cfg, ttRefs, vc, deps ->
            val tcRefs = vc.tcRefs; val ctxEntities = vc.contexts; val filterList = vc.filters
            val now = System.currentTimeMillis()
            val filtered = when (val v = cfg.view) {
                is ViewRef.Smart -> {
                    when (v.kind) {
                        SmartKind.DO_NEXT -> doNextFocused(all, now, cfg.prio, deps, tcRefs, ctxEntities, cfg.timeAvail, cfg.energyAvail)
                        // Waiting-on: open tasks currently blocked by an incomplete prerequisite.
                        SmartKind.WAITING -> {
                            val byId = all.associateBy { it.id }
                            val blocked = PriorityEngine.computeBlocked(deps, byId, now)
                            all.filter { !it.trashed && !it.completed && !it.abandoned && it.id in blocked }
                        }
                        // R28 #3: Trash is per-workspace — the shared Inbox otherwise leaked trashed tasks
                        // into every workspace. A trashed task is stamped with the workspace it was deleted in.
                        SmartKind.TRASH -> all.filter { it.trashed && it.workspaceId == cfg.ws }
                        else -> TaskViews.filterSmart(all, v.kind, now, zone, dayStartMin)
                    }
                }
                is ViewRef.FilterView -> {
                    val q = com.todocompanion.app.domain.view.Filters.parse(filterList.firstOrNull { it.id == v.filterId }?.queryJson)
                    val tagsByTask = ttRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.tagId }.toSet() }
                    val ctxByTask = tcRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.contextId }.toSet() }
                    val listFolderById = vc.lists.associate { it.id to it.folderId }
                    val hit = all.filter { com.todocompanion.app.domain.view.Filters.matches(q, it, tagsByTask[it.id].orEmpty(), ctxByTask[it.id].orEmpty(), now, zone, it.folderId ?: listFolderById[it.listId]) }
                    if (q.includeChildren) {
                        val keep = expandWithDescendants(hit.map { it.id }.toSet(), all)
                        all.filter { it.id in keep && !it.trashed }
                    } else hit
                }
                is ViewRef.ListView -> all.filter { !it.trashed && !it.completed && !it.abandoned && it.listId == v.listId }
                is ViewRef.FolderView -> {
                    val listIds = folderListIds(v.folderId, vc.lists, vc.folders)
                    // Tasks in the folder's lists, plus tasks captured directly into the folder.
                    all.filter { !it.trashed && !it.completed && !it.abandoned && (it.listId in listIds || it.folderId == v.folderId) }
                }
                is ViewRef.TagView -> {
                    // R29 #2 — a parent tag's page rolls up every descendant tag's tasks (matching the
                    // sidebar count, which already summed the subtree), so opening #work shows #work/errands too.
                    val tagIds = subtreeIds(v.tagId, vc.tags, { it.id }, { it.parentId })
                    val ids = ttRefs.filter { it.tagId in tagIds }.map { it.taskId }.toSet()
                    all.filter { it.id in ids && !it.trashed && !it.completed && !it.abandoned }
                }
                is ViewRef.ContextView -> {
                    // Same subtree rollup for contexts / sub-contexts.
                    val ctxIds = subtreeIds(v.contextId, ctxEntities, { it.id }, { it.parentId })
                    val ids = tcRefs.filter { it.contextId in ctxIds }.map { it.taskId }.toSet()
                    all.filter { it.id in ids && !it.trashed && !it.completed && !it.abandoned }
                }
            }
            val flagRank = cfg.flags.sortedBy { it.sortOrder }.mapIndexed { i, f -> f.id to i }.toMap()
            val sorted = if ((cfg.view as? ViewRef.Smart)?.kind == SmartKind.DO_NEXT) filtered
            else TaskViews.sort(filtered, cfg.sort, flagRank)
            // Manual sort flattens the view into ONE ungrouped list so long-press drag (reorder + nest)
            // works everywhere — folders, lists and smart lists alike — not only when grouping is off.
            val gm = if ((cfg.view as? ViewRef.Smart)?.kind == SmartKind.DO_NEXT || cfg.sort == SortMode.MANUAL) GroupMode.NONE else cfg.group
            if (gm == GroupMode.FLAG) {
                // Group by flag, in the user's flag order; unflagged tasks fall into a trailing bucket.
                val ordered = cfg.flags.sortedBy { it.sortOrder }
                val nameById = ordered.associate { it.id to it.name }
                val orderById = ordered.mapIndexed { i, f -> f.id to i }.toMap()
                val buckets = LinkedHashMap<String, MutableList<TaskEntity>>()
                sorted.forEach { t ->
                    val key = t.flagId?.takeIf { it in nameById } ?: "￿No flag"
                    buckets.getOrPut(key) { mutableListOf() }.add(t)
                }
                buckets.entries
                    .sortedBy { (k, _) -> if (k.startsWith("￿")) Int.MAX_VALUE else (orderById[k] ?: Int.MAX_VALUE - 1) }
                    .map { (k, ts) ->
                        val label = if (k.startsWith("￿")) "No flag" else (nameById[k] ?: "Flag")
                        TaskGroup("flag:$k", label, ts)
                    }
            } else if (gm == GroupMode.CONTEXT) {
                // Active-by-context (GTD): group each task under every context it carries.
                val ctxNameById = ctxEntities.associate { it.id to it.name }
                val ctxByTask = tcRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.contextId } }
                val buckets = LinkedHashMap<String, MutableList<TaskEntity>>()
                sorted.forEach { t ->
                    val cids = ctxByTask[t.id].orEmpty()
                    if (cids.isEmpty()) buckets.getOrPut("￿No context") { mutableListOf() }.add(t)
                    else cids.forEach { cid -> buckets.getOrPut(ctxNameById[cid] ?: "?") { mutableListOf() }.add(t) }
                }
                buckets.entries.sortedBy { it.key }.map { (name, ts) ->
                    val label = if (name.startsWith("￿")) "No context" else "@$name"
                    TaskGroup("ctx:$name", label, ts)
                }
            } else {
                // R28 #2 — the Completed / Won't-Do views group by COMPLETION date, not the (always-past) due date.
                val doneView = (cfg.view as? ViewRef.Smart)?.kind.let { it == SmartKind.COMPLETED || it == SmartKind.WONT_DO }
                TaskViews.group(sorted, gm, now, zone, dayStartMin, byCompletion = doneView)
            }
        }.state(emptyList())

    /** When set, the outline is zoomed into this task's subtree (MLO-style focus). */
    val outlineZoom = MutableStateFlow<String?>(null)
    fun zoomInto(taskId: String?) { outlineZoom.value = taskId }

    val outlineRows: StateFlow<List<OutlineRow>> =
        combine(wsTasks, currentView, outlineZoom) { all, v, zoom ->
            val listId = (v as? ViewRef.ListView)?.listId ?: return@combine emptyList()
            val listTasks = all.filter { it.listId == listId && !it.trashed }
            // Zoom only holds while its task still exists in this list.
            val start = zoom?.takeIf { z -> listTasks.any { it.id == z } }
            buildOutline(listTasks, start)
        }.state(emptyList())

    /** Title of the current zoom root, for the breadcrumb, or null when not zoomed. */
    fun zoomTitle(): String? = outlineZoom.value?.let { z -> tasks.value.firstOrNull { it.id == z }?.title }

    /** True when the current view can render as a hierarchy-preserving filter (filter/tag/context). */
    fun canHierarchy(): Boolean = currentView.value.let { it is ViewRef.FilterView || it is ViewRef.TagView || it is ViewRef.ContextView }

    /**
     * MLO "outline filtering": the matched tasks of a filter/tag/context view rendered in their real
     * tree position — matches solid, structural ancestors dimmed. Empty unless [filterHierarchy] is on.
     */
    val hierarchyRows: StateFlow<List<OutlineRow>> =
        combine(
            wsTasks, currentView, filterHierarchy,
            combine(repo.taskTagRefs, repo.taskContextRefs, repo.allFilters, repo.allLists, combine(repo.allTags, repo.allContexts) { tg, cx -> tg to cx }) { tt, tc, f, ls, tgcx -> listOf(tt, tc, f, ls, tgcx.first, tgcx.second) },
        ) { all, v, on, refs ->
            if (!on) return@combine emptyList()
            @Suppress("UNCHECKED_CAST")
            val ttRefs = refs[0] as List<com.todocompanion.app.data.entity.TaskTagCrossRef>
            @Suppress("UNCHECKED_CAST")
            val tcRefs = refs[1] as List<com.todocompanion.app.data.entity.TaskContextCrossRef>
            @Suppress("UNCHECKED_CAST")
            val filters = refs[2] as List<com.todocompanion.app.data.entity.FilterEntity>
            @Suppress("UNCHECKED_CAST")
            val hLists = refs[3] as List<ListEntity>
            @Suppress("UNCHECKED_CAST")
            val hTags = refs[4] as List<TagEntity>
            @Suppress("UNCHECKED_CAST")
            val hContexts = refs[5] as List<ContextEntity>
            val listFolderById = hLists.associate { it.id to it.folderId }
            val now = System.currentTimeMillis()
            val matched: Set<String> = when (v) {
                is ViewRef.FilterView -> {
                    val q = com.todocompanion.app.domain.view.Filters.parse(filters.firstOrNull { it.id == v.filterId }?.queryJson)
                    val tagsByTask = ttRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.tagId }.toSet() }
                    val ctxByTask = tcRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.contextId }.toSet() }
                    val hit = all.filter { com.todocompanion.app.domain.view.Filters.matches(q, it, tagsByTask[it.id].orEmpty(), ctxByTask[it.id].orEmpty(), now, zone, it.folderId ?: listFolderById[it.listId]) }.map { it.id }.toSet()
                    if (q.includeChildren) expandWithDescendants(hit, all) else hit
                }
                is ViewRef.TagView -> {
                    val tagIds = subtreeIds(v.tagId, hTags, { it.id }, { it.parentId })
                    ttRefs.filter { it.tagId in tagIds }.map { it.taskId }.toSet()
                }
                is ViewRef.ContextView -> {
                    val ctxIds = subtreeIds(v.contextId, hContexts, { it.id }, { it.parentId })
                    tcRefs.filter { it.contextId in ctxIds }.map { it.taskId }.toSet()
                }
                else -> return@combine emptyList()
            }
            buildFilteredOutline(all.filter { !it.trashed }, matched)
        }.state(emptyList())

    /** The focused Do-Next list — engine ranking + the "right now" filter (not blocked, not future-start,
     *  due-today/overdue or starred/flagged, within the time/energy planner). Shared by the rendered list
     *  and the sidebar count so the two always agree. */
    private fun doNextFocused(
        all: List<TaskEntity>, now: Long, prioCfg: PriorityEngine.Config,
        deps: List<DependencyEntity>, tcRefs: List<com.todocompanion.app.data.entity.TaskContextCrossRef>,
        ctxs: List<ContextEntity>, timeAvail: Int?, energyAvail: Int?,
    ): List<TaskEntity> {
        val base = TaskViews.filterSmart(all, SmartKind.DO_NEXT, now, zone, dayStartMin)
        val ranked = rankDoNext(base, all, now, prioCfg, deps, tcRefs, ctxs)
        val byId = all.associateBy { it.id }
        val blocked = PriorityEngine.computeBlocked(deps, byId, now)
        val today = java.time.Instant.ofEpochMilli(now - dayStartMin * 60_000L).atZone(zone).toLocalDate()
        fun dueDay(t: TaskEntity) = t.dueDate?.let { java.time.Instant.ofEpochMilli(it - dayStartMin * 60_000L).atZone(zone).toLocalDate() }
        val actionable = ranked.filter { t ->
            if (t.id in blocked) return@filter false
            if (t.startDate != null && t.startDate!! > now) return@filter false
            val d = dueDay(t)
            if (d != null) !d.isAfter(today) else (t.star || t.flagId != null)
        }
        val timed = timeAvail?.let { avail -> actionable.filter { t -> (t.estimateMin ?: t.estimateMax ?: t.durationMin)?.let { it <= avail } ?: true } } ?: actionable
        return energyAvail?.let { cap -> timed.filter { t -> (t.energy ?: 0) <= cap } } ?: timed
    }

    private fun rankDoNext(
        base: List<TaskEntity>, all: List<TaskEntity>, now: Long, cfg: PriorityEngine.Config,
        deps: List<DependencyEntity>, tcRefs: List<com.todocompanion.app.data.entity.TaskContextCrossRef>, ctxs: List<ContextEntity>,
    ): List<TaskEntity> {
        val byParent = all.groupBy { it.parentId }
        val byId = all.associateBy { it.id }
        val blocked = PriorityEngine.computeBlocked(deps, byId, now)
        // "Complete subtasks in order": a task is gated while an earlier sibling under the same
        // ordered parent is still open — only the current step of the sequence surfaces.
        fun orderBlocked(id: String): Boolean {
            val t = byId[id] ?: return false
            val parent = t.parentId?.let { byId[it] } ?: return false
            if (!parent.completeInOrder) return false
            val sibs = byParent[parent.id].orEmpty().filter { !it.trashed && !it.abandoned }.sortedBy { it.sortOrder }
            val firstOpen = sibs.firstOrNull { !it.completed } ?: return false
            return firstOpen.id != id
        }
        // Context availability (open-hours), evaluated once for now.
        val dt = java.time.Instant.ofEpochMilli(now).atZone(zone)
        val dow = dt.dayOfWeek.value; val minute = dt.hour * 60 + dt.minute
        val availById = ctxs.associate { it.id to com.todocompanion.app.domain.context.ContextAvailability.isAvailable(it, dow, minute) }
        val ctxByTask = tcRefs.groupBy { it.taskId }
        // Dependency → priority propagation: a task blocking important work rises in the ranking.
        val depBoosts = PriorityEngine.dependencyBoosts(deps, byId, cfg)
        return PriorityEngine.doNext(
            all = base,
            now = now,
            blocked = blocked,
            hasIncompleteChild = { id -> byParent[id].orEmpty().any { !it.completed && !it.trashed && !it.abandoned } },
            contextAvailable = { id ->
                val ids = ctxByTask[id].orEmpty().map { it.contextId }
                ids.isEmpty() || ids.any { availById[it] == true }
            },
            orderBlocked = ::orderBlocked,
            cfg = cfg,
            depBoost = { id -> depBoosts[id] ?: 0.0 },
        ).map { it.task }
    }

    /** The full Do-Next ranking (priority + due urgency + dependency propagation + context
     *  open-hours availability), most-actionable first. */
    private fun doNextRanked(): List<TaskEntity> {
        val now = System.currentTimeMillis()
        val all = tasks.value
        val base = TaskViews.filterSmart(all, SmartKind.DO_NEXT, now, zone, dayStartMin)
        return rankDoNext(base, all, now, settings.value.priorityConfig(), dependencies.value, taskContexts.value, contexts.value)
    }

    /** The single best task to do right now. Powers "Suggest a task" in Focus. */
    fun topDoNext(): TaskEntity? = doNextRanked().firstOrNull()

    /** Ranked backlog candidates for "pick N for today": actionable, not already due today or earlier. */
    fun pickTodayCandidates(limit: Int = 20): List<TaskEntity> {
        val zone = this.zone
        val today = java.time.LocalDate.now(zone)
        return doNextRanked().filter { t ->
            t.dueDate == null || java.time.Instant.ofEpochMilli(t.dueDate!!).atZone(zone).toLocalDate().isAfter(today)
        }.take(limit)
    }
    /** Commit a task to today (due today, 9am) — the pick-N-today action. */
    fun commitToToday(t: TaskEntity) = viewModelScope.launch {
        val due = java.time.LocalDate.now(zone).atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli()
        repo.saveTask(t.copy(dueDate = due))
    }
    /** Place an existing task onto the calendar at [atMillis] as a timed block (G4). */
    fun scheduleTaskAt(taskId: String, atMillis: Long, durationMin: Int? = null) = viewModelScope.launch {
        val t = repo.getTask(taskId) ?: return@launch
        val dur = durationMin ?: t.durationMin ?: t.estimateMin ?: t.estimateMax ?: 60
        repo.saveTask(t.copy(dueDate = atMillis, isAllDay = false, durationMin = dur))
    }
    /** Ranked unscheduled candidates for dropping onto the calendar. */
    fun unscheduledForBlocking(limit: Int = 12): List<TaskEntity> =
        doNextRanked().filter { it.dueDate == null }.take(limit)

    /**
     * Lay today's actionable Do-Next tasks onto the timeline between the configured working hours,
     * packing each by its estimate (default 30 min) in ranked order. Undated and today/overdue tasks
     * are candidates; future-dated ones are left alone. Reports (scheduled, didn't-fit). Fully offline.
     */
    /** Deadline-risk radar (G3): does the week's committed work fit the time you actually have? */
    data class DeadlineRisk(val neededH: Double, val freeH: Double, val atRisk: Int, val days: Int) {
        val overCommitted get() = neededH > freeH
    }
    fun deadlineRisk(days: Int = 7): DeadlineRisk {
        val now = System.currentTimeMillis()
        val end = java.time.LocalDate.now(zone).plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        fun est(t: TaskEntity) = (t.estimateMin ?: t.estimateMax ?: t.durationMin ?: 30)
        val open = tasks.value.filter { !it.trashed && !it.completed && !it.abandoned && !it.isNote }
        // A task "has a deadline in the window" if its hard deadline OR its due date falls inside it.
        fun deadlineIn(t: TaskEntity): Boolean {
            val d = listOfNotNull(t.deadlineDate, t.dueDate).minOrNull() ?: return false
            return d in (now + 1)..end
        }
        val atRisk = open.filter { deadlineIn(it) }
        val neededMin = atRisk.sumOf { est(it) }
        // Free time = the window's capacity minus other planned (dated, non-deadline) work in it.
        val otherPlannedMin = open.filter { !deadlineIn(it) && it.dueDate != null && it.dueDate!! in (now + 1)..end }.sumOf { est(it) }
        // Sum each upcoming day's capacity (honours per-weekday overrides when set).
        // X3: if "honest capacity" is on and there's enough tracked signal, use your real median
        // focus-hours/day instead of the assumed figure — planning against reality, not optimism.
        val today = java.time.LocalDate.now(zone)
        val trackedCapH = if (settings.value.honestCapacity) trackedCapacityHours() else null
        val capacityMin = if (trackedCapH != null) trackedCapH * 60 * days
            else (0 until days).sumOf { settings.value.capacityHoursFor(today.plusDays(it.toLong()).dayOfWeek) * 60 }
        val freeMin = (capacityMin - otherPlannedMin).coerceAtLeast(0)
        return DeadlineRisk(neededMin / 60.0, freeMin / 60.0, atRisk.size, days)
    }

    /** The hour of day you most often finish things (learned from completion history) — your peak.
     *  Falls back to the configured work-start hour when there's not enough history. */
    fun peakHour(): Int {
        val hist = IntArray(24)
        tasks.value.forEach { t -> t.completedAt?.let { hist[java.time.Instant.ofEpochMilli(it).atZone(zone).hour]++ } }
        val total = hist.sum()
        if (total < 8) return settings.value.workStartHour.coerceIn(0, 23)  // too little signal
        return hist.indices.maxByOrNull { hist[it] } ?: settings.value.workStartHour
    }

    /** F4: one proposed placement — a task and the time the auto-scheduler would move it to. */
    data class ScheduleProposal(val task: TaskEntity, val newDueMillis: Long, val durationMin: Int)
    data class SchedulePlan(val proposals: List<ScheduleProposal>, val didNotFit: Int)

    /**
     * F4: compute the auto-schedule WITHOUT writing anything, so the user can preview, edit and
     * confirm. Same rhythm-aware placement as before; the caller applies the accepted subset.
     */
    fun computeAutoSchedule(): SchedulePlan {
        val s = settings.value
        val startHour = s.workStartHour.coerceIn(0, 23)
        val endHour = s.workEndHour.coerceIn(startHour + 1, 24)
        val dayStart = java.time.LocalDate.now(zone).atStartOfDay(zone)
        val windowEnd = dayStart.plusHours(endHour.toLong())
        // Rhythm-aware start: begin at your learned peak hour when it sits inside the work window.
        val peak = peakHour().coerceIn(startHour, endHour - 1)
        var cursor = dayStart.plusHours(peak.toLong())
        val now = System.currentTimeMillis()
        if (cursor.toInstant().toEpochMilli() < now) {
            val nowZ = java.time.Instant.ofEpochMilli(now).atZone(zone).withSecond(0).withNano(0)
            cursor = nowZ.withMinute(0).plusMinutes((((nowZ.minute) / 15) + 1) * 15L)
        }
        val todayDate = java.time.LocalDate.now(zone)
        val ranked = doNextRanked().filter { t ->
            t.dueDate == null || java.time.Instant.ofEpochMilli(t.dueDate!!).atZone(zone).toLocalDate() <= todayDate
        }
        val candidates = ranked.filter { (it.energy ?: 0) >= 3 } + ranked.filter { (it.energy ?: 0) < 3 }
        val proposals = ArrayList<ScheduleProposal>()
        var skipped = 0
        for (t in candidates) {
            val dur = (t.estimateMin ?: t.estimateMax ?: t.durationMin ?: 30).coerceIn(10, 480)
            val end = cursor.plusMinutes(dur.toLong())
            if (end.isAfter(windowEnd)) { skipped++; continue }
            proposals += ScheduleProposal(t, cursor.toInstant().toEpochMilli(), dur)
            cursor = end
        }
        return SchedulePlan(proposals, skipped)
    }

    /** F4: apply only the proposals the user kept, in order. */
    fun applyAutoSchedule(accepted: List<ScheduleProposal>, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        accepted.forEach { p -> repo.saveTask(p.task.copy(dueDate = p.newDueMillis, isAllDay = false)) }
        onDone(accepted.size)
    }

    /** P2: open tasks whose due date has already passed — the recovery-mode signal. */
    fun overdueOpenTasks(): List<TaskEntity> {
        val startToday = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        return tasks.value.filter { !it.completed && !it.trashed && !it.abandoned && it.dueDate != null && it.dueDate!! < startToday }
    }

    /**
     * P2 — recovery mode: bulk-move every overdue task to today or tomorrow, keeping its time of day.
     * The kind way out of a wall-of-red pileup, borrowed from the habit recovery mode.
     */
    fun rescheduleOverdue(toTomorrow: Boolean, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val target = java.time.LocalDate.now(zone).plusDays(if (toTomorrow) 1 else 0)
        val overdue = overdueOpenTasks()
        overdue.forEach { t ->
            val oldTime = java.time.Instant.ofEpochMilli(t.dueDate!!).atZone(zone).toLocalTime()
            val newDue = target.atTime(oldTime).atZone(zone).toInstant().toEpochMilli()
            repo.saveTask(t.copy(dueDate = newDue))
        }
        onDone(overdue.size)
    }

    fun observeTask(id: String): Flow<TaskEntity?> = repo.observeTask(id)

    /** The private, on-device activity trail for one task (created / completed / rescheduled …). */
    fun taskActivity(id: String): Flow<List<com.todocompanion.app.data.entity.ActivityEntity>> = repo.taskActivity(id)
    // R23: delete a single activity-log entry, or clear a task's whole history. Independent log rows, so
    // there's no cascade; a recurring task's reliability just recomputes from the remaining completions.
    fun deleteActivityEntry(id: String) = viewModelScope.launch { repo.deleteActivity(id) }
    fun clearTaskActivity(taskId: String) = viewModelScope.launch { repo.clearTaskActivity(taskId) }
    fun taskRevisions(id: String): Flow<List<com.todocompanion.app.data.entity.TaskRevisionEntity>> = repo.taskRevisions(id)
    fun restoreRevision(revisionId: String) = viewModelScope.launch { repo.restoreRevision(revisionId) }
    /** How many times each task has been rescheduled — the procrastination signal (E2). */
    suspend fun rescheduleCounts(): Map<String, Int> =
        repo.getActivitiesOnce().filter { it.type == "rescheduled" }.groupingBy { it.taskId }.eachCount()

    // ---------- navigation ----------
    fun select(view: ViewRef) {
        currentView.value = view
        groupMode.value = if (view is ViewRef.ListView) GroupMode.NONE else GroupMode.DATE
        // R28 #2 — the Completed / Won't-Do views open sorted by when things were finished (newest first).
        val doneKind = (view as? ViewRef.Smart)?.kind.let { it == SmartKind.COMPLETED || it == SmartKind.WONT_DO }
        if (doneKind) sortMode.value = SortMode.COMPLETED
        else if (sortMode.value == SortMode.COMPLETED) sortMode.value = SortMode.MANUAL
        // Seed outline mode from the list's own persisted viewMode so a list remembers nested vs flat.
        outlineMode.value = (view as? ViewRef.ListView)?.let { lv -> lists.value.firstOrNull { it.id == lv.listId }?.viewMode == "outline" } ?: false
        // Remember the last place, when the user opted into resuming there.
        val s = settings.value
        if (s.resumeLastView) viewModelScope.launch { repo.saveSettings(repo.settingsSnapshot().copy(lastViewRef = com.todocompanion.app.domain.view.ViewTabs.refOf(view))) }
    }

    /** Flip the current list's outline (nested) vs flat view and persist it on the ListEntity so it sticks. */
    fun toggleOutline() {
        val on = !outlineMode.value
        outlineMode.value = on
        (currentView.value as? ViewRef.ListView)?.let { lv ->
            viewModelScope.launch { lists.value.firstOrNull { it.id == lv.listId }?.let { repo.saveList(it.copy(viewMode = if (on) "outline" else "list")) } }
        }
    }

    /** On launch, open the resume-last view (if enabled) or the configured default view. */
    init {
        viewModelScope.launch {
            val s = repo.settingsSnapshot()
            val ref = if (s.resumeLastView && s.lastViewRef.isNotBlank()) s.lastViewRef else s.defaultViewRef.ifBlank { null }
            if (ref != null) com.todocompanion.app.domain.view.ViewTabs.viewOf(ref)?.let { v ->
                currentView.value = v
                groupMode.value = if (v is ViewRef.ListView) GroupMode.NONE else GroupMode.DATE
            }
        }
    }

    fun currentTitle(): String = when (val v = currentView.value) {
        is ViewRef.Smart -> com.todocompanion.app.domain.smartTitle(settings.value, v.kind)
        is ViewRef.ListView -> lists.value.firstOrNull { it.id == v.listId }?.name ?: "List"
        is ViewRef.FolderView -> folders.value.firstOrNull { it.id == v.folderId }?.name ?: "Folder"
        is ViewRef.TagView -> "#" + (tags.value.firstOrNull { it.id == v.tagId }?.name ?: "tag")
        is ViewRef.ContextView -> "@" + (contexts.value.firstOrNull { it.id == v.contextId }?.name ?: "context")
        is ViewRef.FilterView -> filters.value.firstOrNull { it.id == v.filterId }?.name ?: "Filter"
    }

    fun canOutline(): Boolean = currentView.value is ViewRef.ListView

    // Resolve where a new task lands from the current view, as (listId, folderId). A folder view
    // captures the task *directly into the folder* (empty listId + folderId set) — no phantom list —
    // so it shows in the folder alongside its lists' tasks.
    private fun resolveAddTarget(): Pair<String, String?> = when (val v = currentView.value) {
        is ViewRef.ListView -> v.listId to null
        is ViewRef.FolderView -> "" to v.folderId
        else -> ListEntity.INBOX_ID to null
    }

    // A new task captured from a date-scoped smart list inherits that date (TickTick behaviour): adding
    // from Today lands it today, from Tomorrow lands it tomorrow. Other views leave the date unset.
    private fun defaultDueForView(): Long? {
        val kind = (currentView.value as? ViewRef.Smart)?.kind ?: return null
        val day = when (kind) {
            SmartKind.TODAY -> java.time.LocalDate.now(zone)
            SmartKind.TOMORROW -> java.time.LocalDate.now(zone).plusDays(1)
            else -> return null
        }
        return day.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli()
    }

    /** A legible breakdown of a task's Do-Next priority score — surfaced in the task detail. */
    fun explainScore(task: TaskEntity): PriorityEngine.ScoreBreakdown {
        val byId = tasks.value.associateBy { it.id }
        val cfg = settings.value.priorityConfig()
        val boost = PriorityEngine.dependencyBoosts(dependencies.value, byId, cfg)[task.id] ?: 0.0
        return PriorityEngine.explain(task, System.currentTimeMillis(), byId, cfg, boost)
    }

    // ---------- quick add ----------
    fun submitQuickAdd(text: String, opts: QuickAddOptions) = viewModelScope.launch {
        // Single capture funnel: inline tokens (#t25 estimate, * star, !/!!/!!! priority) are applied
        // HERE so every entry point — the quick-add sheet and the omnibox alike — supports them
        // identically. `@` is deliberately left in the text (handleActivity = false) so QuickAddParser
        // reads it as a context. Explicit opts chosen via the sheet's icons win over inline tokens.
        val tok = com.todocompanion.app.domain.nlp.QuickTokens.parse(text, handleActivity = false)
        val parsed = QuickAddParser.parse(tok.text)
        if (parsed.title.isBlank() && parsed.tags.isEmpty()) return@launch
        val estimateMin = opts.estimateMin ?: tok.estimateMin
        val star = opts.star || tok.star
        val tokPriority = when (tok.priorityLevel) {
            3 -> com.todocompanion.app.domain.priority.PriorityLevel.HIGH
            2 -> com.todocompanion.app.domain.priority.PriorityLevel.MEDIUM
            1 -> com.todocompanion.app.domain.priority.PriorityLevel.LOW
            else -> null
        }
        val due = opts.dueMillis ?: parsed.dateTime?.atZone(zone)?.toInstant()?.toEpochMilli() ?: defaultDueForView()
        val level = opts.priority ?: tokPriority ?: parsed.priority
        // No priority chosen ⇒ "None" (importance/urgency 2), not Low.
        val imp = level?.importance ?: com.todocompanion.app.domain.priority.PriorityLevel.NONE.importance
        val urg = level?.urgency ?: com.todocompanion.app.domain.priority.PriorityLevel.NONE.urgency
        // ~list resolves to an existing list by name (case-insensitive); otherwise fall back to the
        // current view's target, which for a folder view is the folder itself (no list).
        val explicitList = opts.listId
            ?: parsed.list?.let { name -> lists.value.firstOrNull { !it.archived && it.name.equals(name, ignoreCase = true) }?.id }
        val (listId, folderId) = when {
            opts.folderId != null -> "" to opts.folderId       // explicit folder-direct capture (R21)
            explicitList != null -> explicitList to null
            else -> resolveAddTarget()
        }
        val id = repo.createTask(listId, parsed.title.ifBlank { "Untitled" }, importance = imp, urgency = urg, dueDate = due, folderId = folderId)

        val ws = settings.value.activeWorkspaceId
        val tagIds = opts.tagIds.toMutableList()
        if (parsed.tags.isNotEmpty()) {
            // Match names only within the active workspace so a same-named tag elsewhere isn't reused.
            val existing = repo.getTagsOnce().filter { it.workspaceId == ws }.associateBy { it.name.lowercase() }
            parsed.tags.forEach { name ->
                tagIds += existing[name.lowercase()]?.id ?: UUID.randomUUID().toString().also { repo.upsertTag(TagEntity(it, name, workspaceId = ws)) }
            }
        }
        if (tagIds.isNotEmpty()) repo.setTaskTags(id, tagIds.distinct())

        // @contexts (parsed) + contexts chosen in the sheet (R21) resolve to existing contexts by name,
        // creating any that are new.
        run {
            val ctxIds = ArrayList(opts.contextIds)
            if (parsed.contexts.isNotEmpty()) {
                val existingCtx = repo.getContextsOnce().filter { it.workspaceId == ws }.associateBy { it.name.lowercase() }
                parsed.contexts.forEach { name ->
                    ctxIds += existingCtx[name.lowercase()]?.id ?: UUID.randomUUID().toString().also { repo.upsertContext(ContextEntity(id = it, name = name, workspaceId = ws)) }
                }
            }
            if (ctxIds.isNotEmpty()) repo.setTaskContexts(id, ctxIds.distinct())
        }

        // Natural-language recurrence ("every Tuesday", "monthly", "every 2 weeks") or the date sheet's
        // recurrence + optional note / duration (R21). V9: inline-token estimate and star too.
        val rrule = opts.rrule ?: parsed.rrule
        if (rrule != null || opts.note.isNotBlank() || estimateMin != null || star || opts.durationMin != null) repo.getTask(id)?.let {
            repo.saveTask(it.copy(
                rrule = rrule ?: it.rrule,
                note = opts.note.ifBlank { it.note },
                estimateMin = estimateMin ?: it.estimateMin,
                durationMin = opts.durationMin ?: it.durationMin,
                star = it.star || star,
            ))
        }
        // Attachments picked in the sheet — applied after the task exists (each reads bytes off-thread).
        opts.attachmentUris.forEach { addAttachment(id, it) }

        val reminderAt = opts.reminderMillis ?: (if (parsed.hasTime && due != null) due else null)
        if (reminderAt != null) {
            val r = ReminderEntity(UUID.randomUUID().toString(), taskId = id, type = "absolute", atTime = reminderAt)
            repo.upsertReminder(r)
            repo.getTask(id)?.let { AlarmScheduler.schedule(appCtx, r, it) }
        }
        // "!30m / !2h / !1d" shortcut: a lead-time reminder relative to the due date.
        parsed.reminderOffsetMin?.let { off ->
            if (due != null) {
                val r = ReminderEntity(UUID.randomUUID().toString(), taskId = id, type = "relativeToDue", offsetMin = off)
                repo.upsertReminder(r)
                repo.getTask(id)?.let { AlarmScheduler.schedule(appCtx, r, it) }
            }
        }
    }

    // ---------- task actions ----------
    fun addTask(listId: String, parentId: String? = null, title: String = "New task") =
        viewModelScope.launch { repo.createTask(listId, title, parentId = parentId) }
    /** Create a task that lives directly in a folder (no list). Powers the folder-view capture row. */
    fun addTaskInFolder(folderId: String, title: String) = viewModelScope.launch {
        if (title.isNotBlank()) repo.createTask(listId = "", title = title.trim(), folderId = folderId)
    }
    fun toggleComplete(t: TaskEntity) = viewModelScope.launch {
        // Completing a repeating task rolls it forward to the next occurrence instead of closing it
        // — unless its recurrence has ended (until-date reached or count exhausted).
        val (nextDue, newRule) = if (!t.completed && !t.rrule.isNullOrBlank() && t.dueDate != null)
            com.todocompanion.app.domain.recurrence.Recurrence.advance(t.rrule!!, t.dueDate!!, zone, System.currentTimeMillis()) else null to null
        if (nextDue != null) {
            val delta = nextDue - t.dueDate!!
            // Roll the whole date bundle forward by the same delta so a repeating task keeps its shape:
            // the start→due lead time AND the hard deadline both move to the next occurrence. (Previously
            // the deadline stayed frozen on the first occurrence and then read as permanently overdue.)
            repo.saveTask(t.copy(dueDate = nextDue, startDate = t.startDate?.plus(delta),
                deadlineDate = t.deadlineDate?.plus(delta), rrule = newRule, completed = false, completedAt = null))
            repo.logRecurringCompletion(t.id)   // P1: record this occurrence so reliability can be scored
            if (settings.value.completionSound) playCompletionChime()
            // Reset the subtasks of a recurring task per its chosen mode (all / only-if-all-done / keep).
            val kids = tasks.value.filter { it.parentId == t.id && !it.trashed }
            val doneKids = kids.filter { it.completed }
            when (com.todocompanion.app.domain.recurrence.Recurrence.parse(t.rrule)?.subtaskReset ?: "all") {
                "keep" -> {}
                "allDone" -> if (kids.isNotEmpty() && doneKids.size == kids.size) doneKids.forEach { repo.setCompleted(it, false) }
                else -> doneKids.forEach { repo.setCompleted(it, false) }
            }
            val updated = repo.getTask(t.id)
            reminders.value.filter { it.taskId == t.id && it.atTime != null }.forEach { r ->
                val nr = r.copy(atTime = r.atTime!! + delta)
                repo.upsertReminder(nr)
                updated?.let { AlarmScheduler.schedule(appCtx, nr, it) }
            }
            // R31 #4 — a repeating task rolls forward silently; offer Undo too, restoring the exact
            // occurrence (due date + rule) so completing a repeat is as reversible as any other finish.
            undoEvents.tryEmit(UndoEvent(UndoKind.COMPLETED, t.id, "Completed “${t.title.take(30)}” — rolled to next occurrence", restore = t))
        } else {
            repo.setCompleted(t, !t.completed)
            if (!t.completed) {
                undoEvents.tryEmit(UndoEvent(UndoKind.COMPLETED, t.id, "Completed “${t.title.take(30)}”"))
                if (settings.value.completionSound) playCompletionChime()
                // P5/Q2: finishing a goal or project is a milestone — celebrate it, and lead with the
                // reward the user promised themselves if they set one.
                if (t.isGoal || t.isProject) goalCelebration.value =
                    if (t.rewardText.isNotBlank()) "“${t.title.take(32)}” done — you earned it: ${t.rewardText.take(50)} 🎉"
                    else "Milestone reached — “${t.title.take(40)}” done! 🎉"
                // PC3: a one-time celebration on the very first completion — the moment a trial becomes a habit.
                if (!settings.value.firstWinCelebrated) {
                    repo.saveSettings(settings.value.copy(firstWinCelebrated = true))
                    if (goalCelebration.value == null) goalCelebration.value = "Your first one, done 🎉 Small wins compound — you're off."
                }
            }
        }
    }

    /** P5: a completed goal/project title, surfaced to the tasks screen for a confetti + toast moment. */
    val goalCelebration = MutableStateFlow<String?>(null)

    /** A short, pleasant two-note chime on completing a task. Built-in tones — no bundled audio. */
    private fun playCompletionChime() = runCatching {
        val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70)
        tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
        viewModelScope.launch { kotlinx.coroutines.delay(600); tg.release() }
    }
    /**
     * Q5 — adaptive cadence: make a chronically-missed recurring task less demanding by easing it one
     * step (daily→less often, or a longer interval), the task analogue of the habit adaptive-goal ease.
     * Returns the new human label, or null if it can't be eased.
     */
    fun easeCadence(t: TaskEntity, onDone: (String?) -> Unit = {}) = viewModelScope.launch {
        val rec = com.todocompanion.app.domain.recurrence.Recurrence
        val r = t.rrule?.let { rec.parse(it) }
        if (r == null) { onDone(null); return@launch }
        val eased = when (r.freq) {
            com.todocompanion.app.domain.recurrence.Freq.WEEKDAYS ->
                r.copy(freq = com.todocompanion.app.domain.recurrence.Freq.DAILY, interval = 2, byDays = emptySet())
            com.todocompanion.app.domain.recurrence.Freq.DAILY ->
                if (r.interval < 2) r.copy(freq = com.todocompanion.app.domain.recurrence.Freq.WEEKLY, interval = 1) else r.copy(interval = r.interval + 1)
            else -> r.copy(interval = r.interval + 1)
        }
        val rule = rec.encode(eased)
        repo.saveTask(t.copy(rrule = rule))
        onDone(rec.label(rule))
    }

    /** Advance a repeating task to its next occurrence without logging a completion (MLO "skip"). */
    fun skipOccurrence(t: TaskEntity) = viewModelScope.launch {
        if (t.rrule.isNullOrBlank() || t.dueDate == null) return@launch
        val (nextDue, newRule) = com.todocompanion.app.domain.recurrence.Recurrence.advance(t.rrule!!, t.dueDate!!, zone, System.currentTimeMillis())
        if (nextDue == null) { repo.setCompleted(t, true); return@launch }
        val delta = nextDue - t.dueDate!!
        repo.saveTask(t.copy(dueDate = nextDue, startDate = t.startDate?.plus(delta),
            deadlineDate = t.deadlineDate?.plus(delta), rrule = newRule))
        val updated = repo.getTask(t.id)
        reminders.value.filter { it.taskId == t.id && it.atTime != null }.forEach { r ->
            val nr = r.copy(atTime = r.atTime!! + delta); repo.upsertReminder(nr); updated?.let { AlarmScheduler.schedule(appCtx, nr, it) }
        }
    }
    fun setAbandoned(t: TaskEntity, v: Boolean) = viewModelScope.launch {
        repo.setAbandoned(t, v)
        if (v) undoEvents.tryEmit(UndoEvent(UndoKind.ABANDONED, t.id, "Marked won't do"))
    }
    fun toggleCollapsed(t: TaskEntity) = viewModelScope.launch { repo.setCollapsed(t, !t.collapsed) }

    // ---------- batch actions (multi-select) ----------
    fun completeMany(ids: Set<String>) = viewModelScope.launch { ids.mapNotNull { repo.getTask(it) }.filter { !it.completed }.forEach { repo.setCompleted(it, true) } }
    fun trashMany(ids: Set<String>) = viewModelScope.launch { val ws = settings.value.activeWorkspaceId; ids.forEach { repo.setTrashed(it, true, ws) } }
    fun setPriorityMany(ids: Set<String>, level: PriorityLevel) = viewModelScope.launch {
        ids.mapNotNull { repo.getTask(it) }.forEach { repo.saveTask(it.copy(importance = level.importance, urgency = level.urgency)) }
    }
    fun moveMany(ids: Set<String>, listId: String) = viewModelScope.launch { ids.forEach { repo.moveToList(it, listId) } }
    fun moveManyToFolder(ids: Set<String>, folderId: String) = viewModelScope.launch { ids.forEach { repo.moveToFolder(it, folderId) } }
    fun moveTaskToFolder(t: TaskEntity, folderId: String) = viewModelScope.launch { repo.moveToFolder(t.id, folderId) }
    fun trash(t: TaskEntity) = viewModelScope.launch {
        repo.setTrashed(t.id, true, settings.value.activeWorkspaceId)
        undoEvents.tryEmit(UndoEvent(UndoKind.TRASHED, t.id, "Moved to Trash"))
    }
    fun undo(e: UndoEvent) = viewModelScope.launch {
        // A recurring roll-forward carries the exact pre-completion snapshot; restore it wholesale so the
        // due date and rule return to where they were, not just the completed flag.
        if (e.restore != null) { repo.saveTask(e.restore); return@launch }
        when (e.kind) {
            UndoKind.COMPLETED -> repo.getTask(e.taskId)?.let { repo.setCompleted(it, false) }
            UndoKind.ABANDONED -> repo.getTask(e.taskId)?.let { repo.setAbandoned(it, false) }
            UndoKind.TRASHED -> repo.setTrashed(e.taskId, false)
        }
    }
    fun restore(t: TaskEntity) = viewModelScope.launch { repo.setTrashed(t.id, false) }
    fun deleteForever(t: TaskEntity) = viewModelScope.launch { repo.deleteSubtree(t.id) }
    /** Permanently erase several tasks (and their subtrees) — the Trash multi-select "Delete forever". */
    fun deleteForeverMany(ids: Set<String>) = viewModelScope.launch { ids.forEach { repo.deleteSubtree(it) } }
    fun emptyTrash() = viewModelScope.launch { repo.emptyTrash(settings.value.activeWorkspaceId) }
    fun indent(t: TaskEntity) = viewModelScope.launch { repo.indent(t) }
    fun outdent(t: TaskEntity) = viewModelScope.launch { repo.outdent(t) }
    fun moveUp(t: TaskEntity) = viewModelScope.launch { repo.moveUp(t) }
    fun moveDown(t: TaskEntity) = viewModelScope.launch { repo.moveDown(t) }
    fun moveToList(t: TaskEntity, listId: String) = viewModelScope.launch { repo.moveToList(t.id, listId) }
    /** Drag-to-nest: make [childId] a subtask of [parentId] (null promotes it to top level). Cycle-safe. */
    fun nestUnder(childId: String, parentId: String?) = viewModelScope.launch {
        if (childId == parentId) return@launch
        val child = repo.getTask(childId) ?: return@launch
        if (child.parentId == parentId) return@launch
        if (parentId != null) {
            // Refuse to nest a task under one of its own descendants (would orphan a subtree).
            val all = tasks.value
            var p: String? = parentId
            var guard = 0
            while (p != null && guard++ < 1000) {
                if (p == childId) return@launch
                p = all.firstOrNull { it.id == p }?.parentId
            }
            val parent = repo.getTask(parentId)
            repo.saveTask(child.copy(parentId = parentId, listId = parent?.listId ?: child.listId, folderId = parent?.folderId ?: child.folderId))
        } else {
            repo.saveTask(child.copy(parentId = null))
        }
    }
    fun save(t: TaskEntity) = viewModelScope.launch { repo.saveTask(t) }

    // ---------- The Done Record (R27) ----------
    /** Flip the "this was a win" flag — one tap from the accomplishment feed or the task editor. */
    fun toggleWin(t: TaskEntity) = viewModelScope.launch { repo.saveTask(t.copy(winFlag = !t.winFlag)) }
    /** Save a generated Done-Record document (Markdown) to a reachable location, no system picker needed. */
    fun exportBragDoc(markdown: String, filename: String = "brag-document.md", onDone: (String?) -> Unit) = viewModelScope.launch {
        val loc = runCatching {
            com.todocompanion.app.util.FileExport.saveToDownloads(appCtx, filename, "text/markdown", markdown.toByteArray())
        }.getOrNull()
        onDone(loc)
    }
    /** Selection-bar "Make subtask of…": nest every selected task under [parentId] (the parent itself,
     *  if it happens to be in the selection, is skipped so it can't become its own child). Cycle-safe. */
    fun nestManyUnder(childIds: Set<String>, parentId: String) = viewModelScope.launch {
        val all = tasks.value
        val parent = repo.getTask(parentId) ?: return@launch
        childIds.filter { it != parentId }.forEach { cid ->
            val child = repo.getTask(cid) ?: return@forEach
            if (child.parentId == parentId) return@forEach
            // Refuse to nest a task under one of its own descendants.
            var p: String? = parentId; var guard = 0; var cyclic = false
            while (p != null && guard++ < 1000) { if (p == cid) { cyclic = true; break }; p = all.firstOrNull { it.id == p }?.parentId }
            if (cyclic) return@forEach
            repo.saveTask(child.copy(parentId = parentId, listId = parent.listId, folderId = parent.folderId))
        }
    }
    /** Persist a manual drag reorder by writing each id's index as its sortOrder. */
    fun setManualOrder(orderedIds: List<String>) = viewModelScope.launch {
        val byId = tasks.value.associateBy { it.id }
        orderedIds.forEachIndexed { i, id ->
            byId[id]?.let { if (it.sortOrder != i.toDouble()) repo.saveTask(it.copy(sortOrder = i.toDouble())) }
        }
    }

    // ---------- checklist ----------
    fun checklistFor(taskId: String) = checklist.value.filter { it.taskId == taskId }.sortedBy { it.sortOrder }
    fun addChecklistItem(taskId: String, text: String) = viewModelScope.launch { repo.addChecklistItem(taskId, text) }
    /** Break a task into steps at once: one checklist item per non-blank line (C2 breakdown). */
    fun addChecklistItems(taskId: String, lines: List<String>) = viewModelScope.launch {
        lines.map { it.trim() }.filter { it.isNotEmpty() }.forEach { repo.addChecklistItem(taskId, it) }
    }

    /** "Just start" (C2): a task pre-selected for the next time the Focus screen opens. */
    val pendingFocusTaskId = MutableStateFlow<String?>(null)
    /** Fusion F2: a habit pre-selected to Focus on; the Focus screen consumes it and auto-logs. */
    val pendingFocusHabitId = MutableStateFlow<String?>(null)

    // ---- Habits tab view-state, hoisted so the app's single top bar can drive it (one header) ----
    // Matrix mode and density are now persisted in settings, so the choice survives an app restart
    // (they used to reset to list/medium every launch).
    val habitMatrixMode: StateFlow<Boolean> = settings.map { it.habitMatrixMode }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val habitDensity: StateFlow<Int> = settings.map { it.habitDensity }.stateIn(viewModelScope, SharingStarted.Eagerly, 1)
    fun setHabitMatrixMode(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(habitMatrixMode = on)) }
    fun setHabitDensity(level: Int) = viewModelScope.launch { repo.saveSettings(settings.value.copy(habitDensity = level.coerceIn(0, 2))) }
    fun setTimeGridColumns(cols: Int) = viewModelScope.launch { repo.saveSettings(settings.value.copy(timeGridColumns = cols.coerceIn(2, 5))) }
    val habitDetailId = MutableStateFlow<String?>(null)    // non-null → the analytics screen overlays the tab
    val habitBatchOpen = MutableStateFlow(false)
    val habitPresetOpen = MutableStateFlow(false)
    val habitEditor = MutableStateFlow<HabitEditRequest?>(null)   // non-null → the full-screen editor is open
    val habitQuickAddOpen = MutableStateFlow(false)               // L6: natural-language "type a habit" dialog
    val habitTrendsOpen = MutableStateFlow(false)                 // M5: full trends & correlations dashboard
    /** Credit a finished Focus session's minutes to a habit's check-in (marks time habits done). */
    fun logHabitFocus(habitId: String, minutes: Int) = viewModelScope.launch {
        if (minutes <= 0) return@launch
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val cur = repo.getHabitCheckinsOnce().firstOrNull { it.habitId == habitId && it.epochDay == today }?.count ?: 0
        repo.setCheckinValue(habitId, today, cur + minutes)
        refreshHabitWidgets()
    }
    fun toggleChecklist(item: ChecklistItemEntity) = viewModelScope.launch { repo.saveChecklistItem(item.copy(checked = !item.checked)) }
    fun deleteChecklistItem(id: String) = viewModelScope.launch { repo.deleteChecklistItem(id) }

    // ---------- attachments ----------
    fun attachmentMeta(taskId: String) = repo.attachmentMeta(taskId)
    val allAttachments = repo.allAttachmentMeta.state(emptyList())
    /** Attachment bytes as Base64 — reads a file-backed attachment (F4) from disk, else the DB. */
    suspend fun attachmentContent(id: String): String? = withContext(Dispatchers.IO) {
        repo.attachmentFilePath(id)?.let { path ->
            val f = java.io.File(path)
            if (f.exists()) return@withContext android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
        }
        repo.attachmentContent(id)
    }
    /**
     * R46 — read a content:// URI's bytes robustly, the way Tasks.org does. Some providers on de-Googled
     * ROMs (and cloud/"recent" backends) refuse a plain openInputStream but serve a file descriptor, or
     * vice-versa, so we try both before giving up. Returns the bytes, or null with the failure reason.
     */
    private fun readUriBytes(uri: Uri): Pair<ByteArray?, String?> {
        val cr = appCtx.contentResolver
        // 1) the normal path.
        runCatching { cr.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()?.let { return it to null }
        val firstErr = runCatching { cr.openInputStream(uri) }.exceptionOrNull()
        // 2) the file-descriptor path — works for providers that only implement openFile().
        runCatching {
            cr.openFileDescriptor(uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
            }
        }.getOrNull()?.let { return it to null }
        val why = (firstErr ?: runCatching { cr.openFileDescriptor(uri, "r") }.exceptionOrNull())
            ?.javaClass?.simpleName ?: "no data"
        return null to why
    }

    /** After a one-shot attachment copy we no longer need the SAF grant MainActivity may have persisted, so
     *  release it to keep clear of the ~512 persisted-permission ceiling (backup/sync folders keep theirs). */
    private fun releasePersistedRead(uri: Uri) {
        runCatching {
            appCtx.contentResolver.releasePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    fun addAttachment(taskId: String, uri: Uri, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val cr = appCtx.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val name = displayNameOf(uri) ?: "attachment"
        val (bytes, why) = withContext(Dispatchers.IO) { readUriBytes(uri) }
        if (bytes == null) { toast("Couldn't read that file ($why). Try Share ▸ ToDo Companion from your file manager."); onDone(false); return@launch }
        if (bytes.size > repo.maxAttachmentBytes) { toast("File too large (max 50 MB per file)"); onDone(false); return@launch }
        // F4: write the bytes to an app-private file and store only the path — the DB stays lean.
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(appCtx.filesDir, "attachments").apply { mkdirs() }
                val f = java.io.File(dir, UUID.randomUUID().toString())
                f.writeBytes(bytes)
                repo.addAttachmentFile(taskId, name, mime, bytes.size.toLong(), f.absolutePath)
                true
            }.getOrDefault(false)
        }
        if (!ok) toast("Could not save attachment")
        releasePersistedRead(uri)
        onDone(ok)
    }
    fun removeAttachment(id: String) = viewModelScope.launch { repo.deleteAttachment(id) }
    /** R40 — attach several files at once from the system picker (SAF, multi-select). Each URI's bytes are
     *  copied into app-private storage; no storage permission is involved. [onDone] reports how many landed,
     *  so the editor can acknowledge the save (R46). */
    fun addAttachments(taskId: String, uris: List<Uri>, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        var ok = 0
        for (u in uris) {
            val cr = appCtx.contentResolver
            val mime = cr.getType(u) ?: "application/octet-stream"
            val name = displayNameOf(u) ?: "attachment"
            val (bytes, why) = withContext(Dispatchers.IO) { readUriBytes(u) }
            if (bytes == null) { toast("Couldn't read one file ($why). Try Share ▸ ToDo Companion from your file manager."); continue }
            if (bytes.size > repo.maxAttachmentBytes) { toast("Skipped one file over 50 MB"); continue }
            val wrote = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(appCtx.filesDir, "attachments").apply { mkdirs() }
                    val f = java.io.File(dir, UUID.randomUUID().toString())
                    f.writeBytes(bytes)
                    repo.addAttachmentFile(taskId, name, mime, bytes.size.toLong(), f.absolutePath)
                    true
                }.getOrDefault(false)
            }
            if (wrote) ok++
            releasePersistedRead(u)
        }
        if (ok > 0) toast(if (ok == 1) "Attachment added" else "$ok attachments added")
        onDone(ok)
    }

    /** Set a list's background image: decode, downscale to ≤1280px, re-encode JPEG, store as base64. */
    fun setListBackgroundFromUri(listId: String, uri: Uri) = viewModelScope.launch {
        val b64 = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = appCtx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                val maxDim = 1280
                val scale = minOf(1f, maxDim.toFloat() / maxOf(src.width, src.height).coerceAtLeast(1))
                val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
                android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            }.getOrNull()
        }
        if (b64 == null) { toast("Could not read image"); return@launch }
        repo.setListBackground(listId, b64)
    }
    fun clearListBackground(listId: String) = viewModelScope.launch { repo.setListBackground(listId, null) }

    /** R45 — decode+downscale a picked image to a small square-ish face (≤512px JPEG base64), for an
     *  occasion photo. Calls back on the main thread with the base64 (or null). Permission-free. */
    fun imageUriToBase64(uri: Uri, onDone: (String?) -> Unit) = viewModelScope.launch {
        val b64 = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = appCtx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                val maxDim = 512
                val scale = minOf(1f, maxDim.toFloat() / maxOf(src.width, src.height).coerceAtLeast(1))
                val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, out)
                android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            }.getOrNull()
        }
        onDone(b64)
    }
    /** Decode an attachment to a temp cache file and hand it to a local viewer app. */
    fun openAttachment(id: String, fileName: String, mime: String) = viewModelScope.launch {
        val b64 = attachmentContent(id) ?: return@launch
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                val dir = java.io.File(appCtx.cacheDir, "shared").apply { mkdirs() }
                val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "attachment" }
                val f = java.io.File(dir, safe).apply { writeBytes(bytes) }
                androidx.core.content.FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", f)
            }.getOrNull()
        } ?: return@launch
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appCtx.startActivity(intent) }.onFailure { toast("No app can open this file") }
    }
    private fun displayNameOf(uri: Uri): String? = runCatching {
        appCtx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    private fun toast(msg: String) = android.widget.Toast.makeText(appCtx, msg, android.widget.Toast.LENGTH_SHORT).show()
    /** R42 — public toast for UI-side fallback messages (e.g. a picker that couldn't open). */
    fun toastMsg(msg: String) = toast(msg)

    // ---------- folders / lists ----------
    fun createFolder(name: String, parentId: String? = null) = viewModelScope.launch { repo.createFolder(name, parentId, settings.value.activeWorkspaceId) }
    fun renameFolder(f: FolderEntity, name: String) = viewModelScope.launch { repo.saveFolder(f.copy(name = name)) }
    fun saveFolder(f: FolderEntity) = viewModelScope.launch { repo.saveFolder(f) }
    fun setFolderIcon(f: FolderEntity, icon: String?) = viewModelScope.launch { repo.saveFolder(f.copy(icon = icon)) }
    fun toggleFolder(f: FolderEntity) = viewModelScope.launch { repo.saveFolder(f.copy(collapsed = !f.collapsed)) }
    fun deleteFolder(id: String) = viewModelScope.launch { repo.deleteFolder(id) }
    fun createList(name: String, folderId: String?, colorArgb: Long?) = viewModelScope.launch { repo.createList(name, folderId, colorArgb, workspaceId = settings.value.activeWorkspaceId) }
    /** Create a nested list under [parent]. */
    fun createSubList(parent: ListEntity, name: String = "New list") = viewModelScope.launch {
        repo.createList(name, parent.folderId, null, workspaceId = settings.value.activeWorkspaceId, parentListId = parent.id)
    }
    /** Nest a list beneath the sibling directly above it (outline-style indent). */
    fun indentList(list: ListEntity) = viewModelScope.launch {
        val sibs = lists.value.filter { it.folderId == list.folderId && it.parentListId == list.parentListId && it.id != ListEntity.INBOX_ID && !it.archived }.sortedBy { it.sortOrder }
        val idx = sibs.indexOfFirst { it.id == list.id }
        if (idx > 0) repo.setListParent(list.id, sibs[idx - 1].id)
    }
    /** Move a nested list back up to its grandparent level. */
    fun outdentList(list: ListEntity) = viewModelScope.launch {
        val parent = lists.value.firstOrNull { it.id == list.parentListId } ?: return@launch
        repo.setListParent(list.id, parent.parentListId)
    }

    // ---------- workspaces ----------
    fun createWorkspace(name: String) = viewModelScope.launch {
        val id = repo.createWorkspace(name.trim())
        repo.saveSettings(settings.value.copy(activeWorkspaceId = id))
        currentView.value = ViewRef.Smart(SmartKind.TODAY)
    }
    fun switchWorkspace(id: String) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(activeWorkspaceId = id))
        currentView.value = ViewRef.Smart(SmartKind.TODAY)
    }
    fun renameWorkspace(w: com.todocompanion.app.data.entity.WorkspaceEntity, name: String) = viewModelScope.launch { repo.upsertWorkspace(w.copy(name = name.trim())) }
    fun deleteWorkspace(id: String) = viewModelScope.launch {
        repo.deleteWorkspace(id)
        if (settings.value.activeWorkspaceId == id) repo.saveSettings(settings.value.copy(activeWorkspaceId = com.todocompanion.app.data.entity.WorkspaceEntity.DEFAULT_ID))
    }

    // ---------- habits ----------
    fun createHabit(name: String, emoji: String?, colorArgb: Long?, target: Int, unit: String? = null, scheduleDays: String = "", reminderTimes: String = "") = viewModelScope.launch {
        repo.createHabit(name.trim(), emoji, colorArgb, target, settings.value.activeWorkspaceId, unit, scheduleDays, reminderTimes)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
        com.todocompanion.app.widget.HabitsWidget.refresh(appCtx)
    }
    fun saveHabit(h: com.todocompanion.app.data.entity.HabitEntity) = viewModelScope.launch {
        repo.upsertHabit(h)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
        com.todocompanion.app.widget.HabitsWidget.refresh(appCtx)
    }
    /** Create from a fully-built habit (Tier I editor). Workspace defaults to the active one. */
    fun addHabit(h: com.todocompanion.app.data.entity.HabitEntity) = viewModelScope.launch {
        repo.createHabit(h.copy(workspaceId = h.workspaceId.ifBlank { settings.value.activeWorkspaceId }))
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
        refreshHabitWidgets()
    }
    /** M4: render a habit's progress to a PNG on-device and open the share sheet. onDone gets the saved location. */
    fun shareHabitProgress(h: com.todocompanion.app.data.entity.HabitEntity, onDone: (String?) -> Unit) = viewModelScope.launch {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val cks = habitCheckins.value.filter { it.habitId == h.id }
        val done = cks.filter { it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
        val skip = cks.filter { it.status == "skip" }.map { it.epochDay }.toSet()
        val relapse = cks.filter { hs.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
        val today = java.time.LocalDate.now().toEpochDay()
        val strength = hs.strength(h, done, skip, relapse, today)
        val cur = hs.currentStreak(h, done, skip, relapse, today)
        val best = hs.bestStreak(h, done, skip, relapse, today)
        val total = if (h.unit != null) cks.sumOf { it.count } else done.size
        val safe = h.name.filter { it.isLetterOrDigit() }.take(20).ifBlank { "habit" }
        val res = withContext(Dispatchers.IO) {
            val bmp = com.todocompanion.app.util.ProgressCard.render(h.emoji, h.name, h.colorArgb, strength, cur, best, h.unit, total, done, skip, today)
            com.todocompanion.app.util.ProgressCard.saveAndShareUri(appCtx, bmp, "todo-companion-$safe-progress.png")
        }
        res.shareUri?.let { com.todocompanion.app.util.ProgressCard.share(appCtx, it) }
        onDone(res.savedLocation)
    }
    /** N4: render a shareable "your week" recap card (habits + tasks) on-device. */
    fun shareWeeklyRecap(onDone: (String?) -> Unit) = viewModelScope.launch {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val weekDays = (0 until 7).map { today - it }.toSet()
        val cks = habitCheckins.value
        val habitsList = habits.value.filter { !it.archived }
        val checkinsThisWeek = cks.count { it.epochDay in weekDays && it.status == "done" }
        val bestStreak = habitsList.maxOfOrNull { h ->
            val d = cks.filter { it.habitId == h.id && it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            val s = cks.filter { it.habitId == h.id && it.status == "skip" }.map { it.epochDay }.toSet()
            val r = cks.filter { it.habitId == h.id && hs.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
            hs.currentStreak(h, d, s, r, today)
        } ?: 0
        val tasksDone = tasks.value.count { t -> t.completedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() in weekDays } == true }
        val focusMin = focusViews().filter { it.epochDay in weekDays }.sumOf { it.minutes }
        val stats = listOf(
            "check-ins" to checkinsThisWeek.toString(),
            "tasks done" to tasksDone.toString(),
            "best streak" to "${bestStreak}d",
            "focus" to "${focusMin}m",
        )
        val sub = "Last 7 days · " + java.time.LocalDate.now(zone).format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
        val res = withContext(Dispatchers.IO) {
            val bmp = com.todocompanion.app.util.ProgressCard.renderStatsCard("Your week", sub, stats)
            com.todocompanion.app.util.ProgressCard.saveAndShareUri(appCtx, bmp, "todo-companion-week.png")
        }
        res.shareUri?.let { com.todocompanion.app.util.ProgressCard.share(appCtx, it) }
        onDone(res.savedLocation)
    }
    /** R1: render a shareable "momentum" card — the unified habit+task+focus snapshot — on-device. */
    fun shareMomentum(onDone: (String?) -> Unit) = viewModelScope.launch {
        val m = momentumSnapshot()
        val stats = listOf(
            "momentum" to "${m.momentum}",
            "habits" to (m.habitStrength?.let { "$it" } ?: "—"),
            "reliability" to (m.taskReliability?.let { "$it%" } ?: "—"),
            "focus (7d)" to "${m.focusWeek}m",
        )
        val sub = java.time.LocalDate.now(zone).format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
        val res = withContext(Dispatchers.IO) {
            val bmp = com.todocompanion.app.util.ProgressCard.renderStatsCard("Today's momentum", sub, stats)
            com.todocompanion.app.util.ProgressCard.saveAndShareUri(appCtx, bmp, "todo-companion-momentum.png")
        }
        res.shareUri?.let { com.todocompanion.app.util.ProgressCard.share(appCtx, it) }
        onDone(res.savedLocation)
    }

    /** V7 — Reality Replay: a shareable recap of the last 7 days across all three modules, on-device. */
    fun shareRecap(onDone: (String?) -> Unit) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now(zone)
        val weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val startDay = today.minusDays(6).toEpochDay(); val endDay = today.toEpochDay()
        val trackedMin = com.todocompanion.app.domain.TimeTracking.totalMinutes(timeEntries.value, weekStart, now + 1, now)
        val tasksDone = tasks.value.count { t -> t.completedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() in startDay..endDay } == true }
        val habitDays = habitCheckins.value.count { it.status == "done" && it.epochDay in startDay..endDay }
        val m = momentumSnapshot()
        val stats = listOf(
            "tracked" to "${trackedMin / 60}h ${trackedMin % 60}m",
            "tasks done" to "$tasksDone",
            "habit days" to "$habitDays",
            "momentum" to "${m.momentum}",
        )
        val sub = "${today.minusDays(6).format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))} – ${today.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))}"
        val res = withContext(Dispatchers.IO) {
            val bmp = com.todocompanion.app.util.ProgressCard.renderStatsCard("Your week in review", sub, stats)
            com.todocompanion.app.util.ProgressCard.saveAndShareUri(appCtx, bmp, "todo-companion-recap.png")
        }
        res.shareUri?.let { com.todocompanion.app.util.ProgressCard.share(appCtx, it) }
        onDone(res.savedLocation)
    }

    /** R1/R2: the unified momentum snapshot — one place both the dashboard, widget and digest read. */
    data class Momentum(val momentum: Int, val habitStrength: Int?, val taskReliability: Int?, val focusWeek: Int)
    fun momentumSnapshot(): Momentum {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val cks = habitCheckins.value
        val strengths = habits.value.filter { !it.archived }.map { h ->
            val d = cks.filter { it.habitId == h.id && it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            val s = cks.filter { it.habitId == h.id && it.status == "skip" }.map { it.epochDay }.toSet()
            val r = cks.filter { it.habitId == h.id && hs.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
            hs.strength(h, d, s, r, today)
        }
        val habitStrength = if (strengths.isEmpty()) null else strengths.average().toInt()
        val relVals = taskReliability.value.values.map { it.score }
        val taskRel = if (relVals.isEmpty()) null else relVals.average().toInt()
        val weekDays = (0 until 7).map { today - it }.toSet()
        val focusWeek = focusViews().filter { it.epochDay in weekDays }.sumOf { it.minutes }
        val parts = buildList {
            habitStrength?.let { add(it.toDouble() to 0.5) }
            taskRel?.let { add(it.toDouble() to 0.35) }
            add((focusWeek.coerceAtMost(300) / 300.0 * 100) to 0.15)
        }
        val wsum = parts.sumOf { it.second }
        val momentum = if (wsum == 0.0) 0 else (parts.sumOf { it.first * it.second } / wsum).toInt()
        return Momentum(momentum, habitStrength, taskRel, focusWeek)
    }

    /** R2: the weekly "state of you" digest — this week vs last, across habits, tasks and focus. */
    fun weeklyDigest(): com.todocompanion.app.domain.WeeklyDigest.Digest {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val now = System.currentTimeMillis()
        val wkStart = java.time.LocalDate.now(zone).minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val lastStart = java.time.LocalDate.now(zone).minusDays(13).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val TT = com.todocompanion.app.domain.TimeTracking
        val timeOn = com.todocompanion.app.domain.Modules.isEnabled(settings.value, com.todocompanion.app.domain.Modules.TIME)
        val tWk = if (timeOn) TT.totalMinutes(timeEntries.value, wkStart, dayEnd, now) else 0
        val tLast = if (timeOn) TT.totalMinutes(timeEntries.value, lastStart, wkStart, now) else 0
        return com.todocompanion.app.domain.WeeklyDigest.compute(
            habits.value, habitCheckins.value, tasks.value, focusSessions.value,
            momentumSnapshot().momentum, today, zone, tWk, tLast,
        )
    }

    /**
     * R3: unified capture — one line becomes a habit or a task. [forceKind] lets the UI honour a manual
     * flip of the auto-classification; null means "use the classifier's guess".
     */
    fun smartCapture(text: String, forceKind: com.todocompanion.app.domain.nlp.SmartCapture.Kind? = null, onDone: (com.todocompanion.app.domain.nlp.SmartCapture.Kind) -> Unit = {}) {
        // Classify task-vs-habit on the token-free text; the actual task creation passes the RAW text
        // to submitQuickAdd, which is the single funnel for inline tokens (#t25, *, !) AND @contexts.
        val trimmed = com.todocompanion.app.domain.nlp.QuickTokens.parse(text).text.trim()
        if (trimmed.isBlank()) return
        val hbt = com.todocompanion.app.domain.nlp.SmartCapture.Kind.HABIT
        val tsk = com.todocompanion.app.domain.nlp.SmartCapture.Kind.TASK
        val M = com.todocompanion.app.domain.Modules
        val s = settings.value
        val kind0 = forceKind ?: com.todocompanion.app.domain.nlp.SmartCapture.classify(trimmed).kind
        // I6: never file into a disabled module. A habit line with Habits off becomes a task (and vice
        // versa when Tasks is off but Habits is on).
        val kind = when {
            kind0 == hbt && !M.isEnabled(s, M.HABITS) -> tsk
            kind0 == tsk && !M.isEnabled(s, M.TASKS) && M.isEnabled(s, M.HABITS) -> hbt
            else -> kind0
        }
        if (kind == com.todocompanion.app.domain.nlp.SmartCapture.Kind.HABIT) {
            addHabit(com.todocompanion.app.domain.habit.HabitQuickParser.parse(trimmed))
        } else {
            submitQuickAdd(text, QuickAddOptions())
        }
        onDone(kind)
    }

    // ---------- Tier S: time tracking ----------
    private fun refreshTimeWidget() = com.todocompanion.app.widget.TimeWidget.refresh(appCtx)
    fun createTimeActivity(name: String, emoji: String?, colorArgb: Long?, goalMinutesPerDay: Int = 0) = viewModelScope.launch {
        repo.createTimeActivity(name, emoji, colorArgb, goalMinutesPerDay); refreshTimeWidget(); refreshTrackShortcuts()
    }
    /** U13: start tracking by activity name (from an NFC/QR deep link), creating it if unknown. */
    fun startTimeTrackingByName(name: String) = viewModelScope.launch {
        val existing = timeActivities.value.firstOrNull { it.name.equals(name.trim(), true) && !it.archived }
        val id = existing?.id ?: repo.createTimeActivity(name.trim().ifBlank { "Activity" }, null, null)
        repo.startTimeTracking(id, stopFirst = !settings.value.multiTimer)
        com.todocompanion.app.reminders.AutomationRunner.onStart(appCtx, repo, id); refreshTimeWidget()
    }
    /** Pin/unpin a time activity so it floats to the front of the one-tap tile grid. */
    fun toggleActivityPin(id: String) = viewModelScope.launch {
        val s = settings.value
        val next = if (id in s.pinnedActivities) s.pinnedActivities - id else s.pinnedActivities + id
        repo.saveSettings(s.copy(pinnedActivities = next))
    }
    /** Reassign the running (or any) time entry to a different activity — "start first, pick later". */
    fun reassignTimeEntry(entryId: String, activityId: String) = viewModelScope.launch {
        timeEntries.value.firstOrNull { it.id == entryId }?.let { repo.upsertTimeEntry(it.copy(activityId = activityId)); refreshTimeWidget() }
    }
    /** U13: publish a launcher shortcut per activity ("Track: Deep work") that fires the track deep link. */
    fun refreshTrackShortcuts() = viewModelScope.launch {
        com.todocompanion.app.util.TrackShortcuts.refresh(appCtx, timeActivities.value.filter { !it.archived })
    }
    fun updateTimeActivity(a: com.todocompanion.app.data.entity.TimeActivityEntity) = viewModelScope.launch { repo.upsertTimeActivity(a); refreshTimeWidget(); refreshTrackShortcuts() }
    fun deleteTimeActivity(id: String) = viewModelScope.launch {
        // Clear a paused timer on this activity too, or Resume would re-create an orphan entry (audit #7).
        if (_pausedTrack.value?.first == id) _pausedTrack.value = null
        repo.deleteTimeActivity(id); refreshTimeWidget(); refreshTrackShortcuts()
    }
    fun archiveTimeActivity(id: String) = viewModelScope.launch { repo.archiveTimeActivity(id); refreshTimeWidget(); refreshTrackShortcuts() }
    /** Nested activities: set (or clear, with null) an activity's parent. Stored in settings (no migration).
     *  Rejects a parent that would create a cycle (A→B→A), which would otherwise orphan the whole grid. */
    fun setActivityParent(childId: String, parentId: String?) = viewModelScope.launch {
        val s = settings.value
        val map = s.timeActivityParents
        val wouldCycle = parentId != null && run {
            var cur: String? = parentId
            var guard = 0
            while (cur != null && guard < 64) { if (cur == childId) return@run true; cur = map[cur]; guard++ }
            false
        }
        val next = if (parentId == null || parentId == childId || wouldCycle) map - childId
        else map + (childId to parentId)
        repo.saveSettings(s.copy(timeActivityParents = next))
    }
    /** Start (or switch) tracking. Passing the already-running activity is a no-op toggle handled by caller.
     *  U15: with multi-timer on, the running timer isn't stopped first. U12: on-start rules run after. */
    fun startTimeTracking(activityId: String, taskId: String? = null, habitId: String? = null) = viewModelScope.launch {
        repo.startTimeTracking(activityId, taskId, habitId, stopFirst = !settings.value.multiTimer)
        com.todocompanion.app.reminders.AutomationRunner.onStart(appCtx, repo, activityId)
        com.todocompanion.app.reminders.TimeIntentApi.broadcastStarted(appCtx, timeActivities.value.firstOrNull { it.id == activityId }?.name ?: "")
        refreshTimeWidget()
    }
    fun stopTimeTracking() = viewModelScope.launch {
        val nm = timeEntries.value.firstOrNull { it.running }?.let { r -> timeActivities.value.firstOrNull { it.id == r.activityId }?.name }
        repo.stopTimeTracking(); refreshHabitWidgets(); refreshTimeWidget()
        nm?.let { com.todocompanion.app.reminders.TimeIntentApi.broadcastStopped(appCtx, it) }
    }
    /**
     * One-tap "start now, decide later" — starts the clock against the most sensible activity without
     * making the user scan the grid first (Simple-Time-Tracker's decision-fatigue fix): last-used, else a
     * pinned one, else the first activity. Returns false only when there is no activity to start at all,
     * so the caller can open the new-activity dialog. The running card lets you reassign afterward.
     */
    fun startTimeTrackingSmart(): Boolean {
        val acts = timeActivities.value.filter { !it.archived }
        if (acts.isEmpty()) return false
        val lastUsed = timeEntries.value.maxByOrNull { it.startMillis }?.activityId?.let { id -> acts.firstOrNull { it.id == id } }
        val pinned = acts.firstOrNull { it.id in settings.value.pinnedActivities }
        val pick = lastUsed ?: pinned ?: acts.first()
        startTimeTracking(pick.id)
        return true
    }
    /** U15: stop one specific running timer (when several overlap). */
    fun stopTimeEntry(id: String) = viewModelScope.launch {
        val nm = timeEntries.value.firstOrNull { it.id == id }?.let { r -> timeActivities.value.firstOrNull { it.id == r.activityId }?.name }
        repo.stopTimeEntry(id); refreshHabitWidgets(); refreshTimeWidget()
        nm?.let { com.todocompanion.app.reminders.TimeIntentApi.broadcastStopped(appCtx, it) }
    }

    // ── U3 · pause / resume ─────────────────────────────────────────────────────────────────────
    // Pause finalizes the running interval (crediting any linked habit) and remembers what it was, so
    // Resume can start it again; the break in between is a real, honest untracked gap.
    private val _pausedTrack = MutableStateFlow<Triple<String, String?, String?>?>(null)
    val pausedTrack: StateFlow<Triple<String, String?, String?>?> = _pausedTrack
    fun pauseTracking() = viewModelScope.launch {
        val running = repo.runningTimeEntry() ?: return@launch
        _pausedTrack.value = Triple(running.activityId, running.taskId, running.habitId)
        repo.stopTimeTracking(); refreshHabitWidgets(); refreshTimeWidget()
    }
    fun resumeTracking() = viewModelScope.launch {
        val p = _pausedTrack.value ?: return@launch
        _pausedTrack.value = null
        repo.startTimeTracking(p.first, p.second, p.third, stopFirst = !settings.value.multiTimer); refreshTimeWidget()
    }
    fun clearPaused() { _pausedTrack.value = null }

    // ── U12 · automation rules ──────────────────────────────────────────────────────────────────
    fun automationRules(): List<com.todocompanion.app.domain.AutomationRule> =
        com.todocompanion.app.domain.AutomationRules.parse(settings.value.automationRulesJson)
    fun saveAutomationRules(rules: List<com.todocompanion.app.domain.AutomationRule>) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(automationRulesJson = com.todocompanion.app.domain.AutomationRules.encode(rules)))
    }

    // ── U2 · (re)schedule today's timebox → track prompts ───────────────────────────────────────
    fun rescheduleTrackPrompts() = viewModelScope.launch {
        if (settings.value.autoTrackPrompt) com.todocompanion.app.reminders.AlarmScheduler.scheduleTrackPrompts(appCtx, repo)
    }

    // ── U1 · untracked planned blocks + one-tap fill ────────────────────────────────────────────
    fun untrackedTodayBlocks(): List<com.todocompanion.app.domain.TimeInsights.PlannedBlock> {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val blocks = tasks.value.mapNotNull { t ->
            val due = t.dueDate ?: return@mapNotNull null
            if (t.completed || t.trashed || t.abandoned || t.isAllDay || t.isNote) return@mapNotNull null
            if (java.time.Instant.ofEpochMilli(due).atZone(zone).toLocalDate() != today) return@mapNotNull null
            val startMin = ((due - dayStart) / 60_000L).toInt().coerceIn(0, 1439)
            if (dayStart + startMin * 60_000L > now) return@mapNotNull null   // block hasn't started yet
            val dur = (t.durationMin ?: t.estimateMin ?: 30).coerceAtLeast(5)
            com.todocompanion.app.domain.TimeInsights.PlannedBlock(t.id, t.title, startMin, dur)
        }
        return com.todocompanion.app.domain.TimeInsights.untrackedBlocks(blocks, timeEntries.value, dayStart, now)
    }
    /** U1: backfill a planned block's time interval against its task, in one tap. */
    fun fillTrackedBlock(block: com.todocompanion.app.domain.TimeInsights.PlannedBlock) = viewModelScope.launch {
        val zone = java.time.ZoneId.systemDefault()
        val dayStart = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val start = dayStart + block.startMin * 60_000L
        val end = (start + block.durMin * 60_000L).coerceAtMost(System.currentTimeMillis())
        if (end <= start) return@launch
        val task = repo.getTask(block.taskId)
        val actId = task?.defaultActivityId?.takeIf { id -> timeActivities.value.any { it.id == id && !it.archived } }
            ?: repo.ensureTaskActivity()
        repo.addManualTimeEntry(actId, start, end, note = "", taskId = block.taskId)
        refreshTimeWidget()
    }

    // ── U6 · plan vs actual (this week) + calibration ───────────────────────────────────────────
    fun planVsActualWeek(): com.todocompanion.app.domain.TimeInsights.PlanActual {
        val zone = java.time.ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val weekStart = java.time.LocalDate.now(zone).minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val weekEndWindow = java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val trackedByTask = timeEntries.value.filter { it.taskId != null }.groupBy { it.taskId!! }
            .mapValues { (_, es) -> es.sumOf { com.todocompanion.app.domain.TimeTracking.minutesInWindow(it.startMillis, it.endMillis, weekStart, now + 1, now) } }
        val items = tasks.value.mapNotNull { t ->
            val planned = (t.estimateMin ?: t.durationMin ?: 0)
            if (planned <= 0) return@mapNotNull null
            val actual = trackedByTask[t.id] ?: 0
            val dueThisWeek = t.dueDate?.let { it in weekStart until weekEndWindow } ?: false
            if (actual <= 0 && !dueThisWeek) return@mapNotNull null
            com.todocompanion.app.domain.TimeInsights.PlanActualItem(t.id, t.title, planned, actual)
        }
        return com.todocompanion.app.domain.TimeInsights.planVsActual(items)
    }

    // ── U7 · cross-type correlation ("what moves your momentum") ────────────────────────────────
    fun momentumLinks(windowDays: Int = 60): List<String> {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val universe = (0 until windowDays).map { today - it }
        val entriesByDay = HashMap<Long, MutableSet<String>>()
        timeEntries.value.forEach { e ->
            val d = java.time.Instant.ofEpochMilli(e.startMillis).atZone(zone).toLocalDate().toEpochDay()
            entriesByDay.getOrPut(d) { mutableSetOf() }.add(e.activityId)
        }
        val actName = timeActivities.value.associate { it.id to ((it.emoji?.plus(" ") ?: "") + it.name) }
        val out = mutableListOf<Pair<Double, String>>()
        habits.value.filter { !it.paused && it.habitType != "break" }.forEach { h ->
            val hc = habitCheckins.value.filter { it.habitId == h.id }
            val doneDays = hc.filter { it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            val expected = universe.filter { hs.isExpectedDay(h, it) }
            if (expected.size < 12) return@forEach
            timeActivities.value.filter { !it.archived }.forEach { a ->
                val cond = expected.filter { entriesByDay[it]?.contains(a.id) == true }.toSet()
                if (cond.size < 4 || expected.size - cond.size < 4) return@forEach
                val c = com.todocompanion.app.domain.TimeInsights.conditionalRate(expected, doneDays, cond)
                if (c.lift >= 0.20) out += c.lift to
                    "Your ‘${h.name}’ habit lands ${(c.rateWith * 100).toInt()}% on days you track ${actName[a.id]}, vs ${(c.rateWithout * 100).toInt()}% otherwise."
            }
        }
        return out.sortedByDescending { it.first }.take(3).map { it.second }
    }

    // ── V6 · cross-type tag report — hours + tasks + habit-days grouped by one tag ──────────────
    data class TagLine(val tag: String, val minutes: Int, val tasksDone: Int, val habitDays: Int)
    fun crossTypeTagReport(windowDays: Int = 7): List<TagLine> {
        val zone = java.time.ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now(zone)
        val winStart = today.minusDays((windowDays - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val startDay = today.minusDays((windowDays - 1).toLong()).toEpochDay()
        val endDay = today.toEpochDay()
        val acc = HashMap<String, IntArray>()   // tag → [minutes, tasksDone, habitDays]
        fun bucket(tag: String) = acc.getOrPut(tag.trim().lowercase()) { IntArray(3) }
        // time (U11 tags)
        com.todocompanion.app.domain.TimeInsights.totalsByTag(timeEntries.value, winStart, now + 1, now).forEach { bucket(it.tag)[0] += it.minutes }
        // tasks completed in the window, by their tag names
        val tagName = tags.value.associate { it.id to it.name }
        val tagsByTask = taskTags.value.groupBy { it.taskId }.mapValues { e -> e.value.mapNotNull { tagName[it.tagId] } }
        tasks.value.forEach { t ->
            val ca = t.completedAt ?: return@forEach
            val d = java.time.Instant.ofEpochMilli(ca).atZone(zone).toLocalDate().toEpochDay()
            if (d in startDay..endDay) tagsByTask[t.id].orEmpty().forEach { if (it.isNotBlank()) bucket(it)[1] += 1 }
        }
        // habit "done" days in the window, keyed by the habit's category (used as a tag)
        val habById = habits.value.associateBy { it.id }
        val hs = com.todocompanion.app.domain.habit.HabitStats
        habitCheckins.value.forEach { ci ->
            if (ci.status != "done" || ci.epochDay !in startDay..endDay) return@forEach
            val h = habById[ci.habitId] ?: return@forEach
            if (h.category.isNotBlank() && hs.meetsGoal(h, ci.count)) bucket(h.category)[2] += 1
        }
        return acc.filter { it.value.any { v -> v > 0 } }
            .map { TagLine(it.key, it.value[0], it.value[1], it.value[2]) }
            .sortedByDescending { it.minutes + it.tasksDone * 30 + it.habitDays * 30 }
    }

    // ── V12 · rewards store ─────────────────────────────────────────────────────────────────────
    fun rewards(): List<com.todocompanion.app.domain.Reward> = com.todocompanion.app.domain.Rewards.parse(settings.value.rewardsJson)
    fun saveRewards(list: List<com.todocompanion.app.domain.Reward>) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(rewardsJson = com.todocompanion.app.domain.Rewards.encode(list)))
    }
    fun redeemReward(r: com.todocompanion.app.domain.Reward) = viewModelScope.launch {
        val s = settings.value
        if (s.pointsBalance >= r.cost) {
            val list = com.todocompanion.app.domain.Rewards.parse(s.rewardsJson).map { if (it.id == r.id) it.copy(redeemed = it.redeemed + 1) else it }
            repo.saveSettings(s.copy(pointsBalance = s.pointsBalance - r.cost, rewardsJson = com.todocompanion.app.domain.Rewards.encode(list)))
            toast("Enjoy your ${r.name}! 🎉")
        } else toast("${r.cost - s.pointsBalance} more point${if (r.cost - s.pointsBalance == 1) "" else "s"} to go")
    }

    // ══ Tier W ══════════════════════════════════════════════════════════════════════════════════

    // ── W1 · Omnibox — one field routes to timer / habit / task ─────────────────────────────────
    /** Detect a "track now" intent (a leading track verb, or a bare @activity) → start a timer; else
     *  fall through to smart capture (habit vs task). onDone reports "timer" | "habit" | "task". */
    fun omniCapture(text: String, onDone: (String) -> Unit = {}) {
        val raw = text.trim()
        if (raw.isBlank()) return
        val verb = Regex("^(?:track|start|timer)\\s+(.+)$", RegexOption.IGNORE_CASE).find(raw)
        val tok = com.todocompanion.app.domain.nlp.QuickTokens.parse(raw)
        val bareActivity = tok.activity != null && tok.text.isBlank()
        if ((verb != null || bareActivity) && com.todocompanion.app.domain.Modules.isEnabled(settings.value, com.todocompanion.app.domain.Modules.TIME)) {
            val actName = (tok.activity ?: verb!!.groupValues[1]).trim().removePrefix("@")
            if (actName.isNotBlank()) { startTimeTrackingByName(actName); onDone("timer"); return }
        }
        smartCapture(raw) { k -> onDone(if (k == com.todocompanion.app.domain.nlp.SmartCapture.Kind.HABIT) "habit" else "task") }
    }

    // ── W2 · Right Now — the single next best action across modules ──────────────────────────────
    data class RightNow(val kind: String, val title: String, val subtitle: String, val actionLabel: String,
                        val taskId: String? = null, val habitId: String? = null)
    fun rightNow(): RightNow? {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val zone = java.time.ZoneId.systemDefault()
        val nowMin = java.time.LocalTime.now(zone).let { it.hour * 60 + it.minute }
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val dueHabits = habits.value.filter { !it.paused && !it.archived }.filter { h ->
            val hc = habitCheckins.value.filter { it.habitId == h.id }
            val done = hc.filter { it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            hs.dueToday(h, today, done, hc.firstOrNull { it.epochDay == today }?.count ?: 0)
        }
        // 0) Y2 — your keystone habit, if it's still due today, beats everything: it's your highest lever.
        keystoneHabitId()?.let { kid -> dueHabits.firstOrNull { it.id == kid }?.let { k ->
            return RightNow("habit", (k.emoji?.plus(" ") ?: "") + k.name, "🗝️ Your keystone — the habit that lifts your whole day", "Check off", habitId = k.id)
        } }
        // 1) A due habit whose typical done-time is near now (rhythm) — the strongest "do this now" signal.
        val rhythm = dueHabits.mapNotNull { h ->
            val mins = habitCheckins.value.filter { it.habitId == h.id }.mapNotNull { it.doneAtMinute }
            if (mins.size < 3) null else {
                val typical = mins.sorted()[mins.size / 2]
                val delta = kotlin.math.abs(typical - nowMin)
                if (delta <= 90) h to delta else null
            }
        }.minByOrNull { it.second }?.first
        if (rhythm != null) return RightNow("habit", (rhythm.emoji?.plus(" ") ?: "") + rhythm.name, "You usually do this around now", "Check off", habitId = rhythm.id)
        // 2) The top do-next task.
        topDoNext()?.let { t -> return RightNow("task", t.title, if (t.dueDate != null) "Your top task, due soon" else "Your top task", "Start", taskId = t.id) }
        // 3) Any due habit.
        dueHabits.firstOrNull()?.let { return RightNow("habit", (it.emoji?.plus(" ") ?: "") + it.name, "Due today", "Check off", habitId = it.id) }
        return null
    }

    // ── W4 · Balance — where the week actually went, by life area (cross-type tags) ───────────────
    data class BalanceSlice(val area: String, val weight: Int, val share: Double)
    fun balanceBreakdown(windowDays: Int = 7): List<BalanceSlice> {
        val weighted = crossTypeTagReport(windowDays).map { it.tag to (it.minutes + it.tasksDone * 30 + it.habitDays * 30) }.filter { it.second > 0 }
        val total = weighted.sumOf { it.second }
        if (total == 0) return emptyList()
        return weighted.map { BalanceSlice(it.first, it.second, it.second.toDouble() / total) }.sortedByDescending { it.weight }
    }

    // ── W7 · Self-writing weekly review ─────────────────────────────────────────────────────────
    fun weeklyReviewText(): String {
        val zone = java.time.ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now(zone)
        val weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val startDay = today.minusDays(6).toEpochDay(); val endDay = today.toEpochDay()
        val tracked = com.todocompanion.app.domain.TimeTracking.totalMinutes(timeEntries.value, weekStart, now + 1, now)
        val tasksDone = tasks.value.count { t -> t.completedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() in startDay..endDay } == true }
        val habitDays = habitCheckins.value.count { it.status == "done" && it.epochDay in startDay..endDay }
        val digest = weeklyDigest()
        val pa = planVsActualWeek()
        val leftover = tasks.value.count { t -> !t.completed && !t.trashed && !t.abandoned && t.dueDate?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() <= endDay } == true }
        return buildString {
            appendLine("Your week — ${today.minusDays(6).format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))} to ${today.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))}")
            appendLine()
            appendLine("• ${tasksDone} task${if (tasksDone == 1) "" else "s"} finished, ${tracked / 60}h ${tracked % 60}m tracked, ${habitDays} habit check-in${if (habitDays == 1) "" else "s"}.")
            if (pa.items.isNotEmpty()) appendLine("• Planned ${pa.plannedMin / 60}h ${pa.plannedMin % 60}m vs tracked ${pa.actualMin / 60}h ${pa.actualMin % 60}m.")
            digest.bestHabit?.let { appendLine("• Strongest habit: $it.") }
            digest.slippingHabit?.let { appendLine("• Room to grow: $it.") }
            if (leftover > 0) appendLine("• ${leftover} open task${if (leftover == 1) "" else "s"} carry into next week.")
            appendLine()
            appendLine(digest.takeaway)
        }.trim()
    }

    // ── W6 · Routine tags ───────────────────────────────────────────────────────────────────────
    fun routines(): List<com.todocompanion.app.domain.Routine> = com.todocompanion.app.domain.Routines.parse(settings.value.routinesJson)
    fun saveRoutines(list: List<com.todocompanion.app.domain.Routine>) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(routinesJson = com.todocompanion.app.domain.Routines.encode(list)))
    }
    fun runRoutine(r: com.todocompanion.app.domain.Routine) = viewModelScope.launch {
        if (r.activityId.isNotBlank() && timeActivities.value.any { it.id == r.activityId && !it.archived }) {
            repo.startTimeTracking(r.activityId, stopFirst = !settings.value.multiTimer)
            com.todocompanion.app.reminders.AutomationRunner.onStart(appCtx, repo, r.activityId)
            com.todocompanion.app.reminders.TimeIntentApi.broadcastStarted(appCtx, timeActivities.value.firstOrNull { it.id == r.activityId }?.name ?: "")
            refreshTimeWidget()
        }
        toast("Routine “${r.name}” started")
    }
    fun runRoutineByName(name: String) = viewModelScope.launch {
        com.todocompanion.app.domain.Routines.byName(routines(), name)?.let { runRoutine(it) }
    }

    // ── W3 · Plan my day — auto-block, then measure the loop ────────────────────────────────────
    /** Lay today's estimated do-next tasks into time-blocks (rhythm-aware), and turn on the
     *  timebox→track prompt so each block asks to be tracked — closing plan → do → measure. */
    fun planMyDay(onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val plan = computeAutoSchedule()
        if (plan.proposals.isEmpty()) { onDone(0); return@launch }
        val dues = plan.proposals.joinToString(",") { "\"${it.task.id}\":\"${it.task.dueDate ?: 0L}\"" }   // Z6: old due dates for undo
        plan.proposals.forEach { p -> repo.saveTask(p.task.copy(dueDate = p.newDueMillis, isAllDay = false)) }
        if (!settings.value.autoTrackPrompt) repo.saveSettings(settings.value.copy(autoTrackPrompt = true))
        rescheduleTrackPrompts()
        appendAction("plan", "Scheduled ${plan.proposals.size} task${if (plan.proposals.size == 1) "" else "s"} for today", "{\"dues\":{$dues}}")
        onDone(plan.proposals.size)
    }

    // ── W8 · per-item reminder mute ─────────────────────────────────────────────────────────────
    fun toggleMutedHabit(id: String) = viewModelScope.launch {
        val cur = settings.value.mutedHabits
        val muting = id !in cur
        repo.saveSettings(settings.value.copy(mutedHabits = if (muting) cur + id else cur - id))
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
        // Y2: quietly guard the keystone — warn before silencing your highest-leverage habit.
        if (muting && keystoneHabitId() == id) toast("Heads up — this is your keystone habit")
    }
    fun toggleMutedList(id: String) = viewModelScope.launch {
        val cur = settings.value.mutedLists
        repo.saveSettings(settings.value.copy(mutedLists = if (id in cur) cur - id else cur + id))
    }

    // ══ Tier X · the reasoning layer ═════════════════════════════════════════════════════════════

    private fun fmt1(x: Double): String = String.format(java.util.Locale.US, "%.1f", x)
    private fun hourLabel(h24: Int): String {
        val h = ((h24 % 24) + 24) % 24
        val ampm = if (h < 12) "am" else "pm"; val h12 = ((h + 11) % 12) + 1
        return "$h12$ampm"
    }
    /** Tasks completed per epoch-day within [sinceDay]..[untilDay] inclusive. */
    private fun tasksCompletedByDay(sinceDay: Long, untilDay: Long): Map<Long, Int> {
        val m = HashMap<Long, Int>()
        tasks.value.forEach { t ->
            val ca = t.completedAt ?: return@forEach
            val d = java.time.Instant.ofEpochMilli(ca).atZone(zone).toLocalDate().toEpochDay()
            if (d in sinceDay..untilDay) m[d] = (m[d] ?: 0) + 1
        }
        return m
    }

    // ── X1 · Unified Goals ────────────────────────────────────────────────────────────────────────
    fun goals(): List<com.todocompanion.app.domain.Goal> = com.todocompanion.app.domain.Goals.parse(settings.value.goalsJson)
    fun saveGoals(list: List<com.todocompanion.app.domain.Goal>) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(goalsJson = com.todocompanion.app.domain.Goals.encode(list)))
    }
    data class GoalHealth(
        val goal: com.todocompanion.app.domain.Goal,
        val taskDone: Int, val taskTotal: Int,
        val habitStreak: Int, val habitStrength: Int,
        val minutesTracked: Int, val budgetMin: Int,
        val overall: Double, val daysLeft: Int?,
    )
    fun goalHealth(g: com.todocompanion.app.domain.Goal): GoalHealth {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val now = System.currentTimeMillis()
        var tDone = 0; var tTotal = 0
        if (g.hasTasks) {
            val inList = tasks.value.filter { it.listId == g.listId && !it.trashed && !it.isNote && !it.abandoned }
            tTotal = inList.size; tDone = inList.count { it.completed }
        }
        var streak = 0; var strength = 0
        if (g.hasHabit) habits.value.firstOrNull { it.id == g.habitId }?.let { h ->
            val hc = habitCheckins.value.filter { it.habitId == h.id }
            val done = hc.filter { it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            val skip = hc.filter { it.status == "skip" }.map { it.epochDay }.toSet()
            val relapse = hc.filter { hs.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
            val today = java.time.LocalDate.now(zone).toEpochDay()
            streak = hs.displayStreak(h, done, skip, relapse, today, settings.value.forgivingStreaks)
            strength = strengthOf(h)   // Z8: honours the graded-strength opt-in
        }
        var mins = 0
        if (g.hasBudget) mins = timeEntries.value.filter { it.activityId == g.activityId }.sumOf { it.minutes(now) }
        val fracs = ArrayList<Double>()
        if (g.hasTasks && tTotal > 0) fracs += tDone.toDouble() / tTotal
        if (g.hasHabit) fracs += strength / 100.0
        if (g.hasBudget && g.budgetMinutes > 0) fracs += (mins.toDouble() / g.budgetMinutes).coerceAtMost(1.0)
        val overall = if (fracs.isEmpty()) 0.0 else fracs.average()
        val daysLeft = if (g.targetEpochDay > 0) (g.targetEpochDay - java.time.LocalDate.now(zone).toEpochDay()).toInt() else null
        return GoalHealth(g, tDone, tTotal, streak, strength, mins, g.budgetMinutes, overall, daysLeft)
    }

    // ── X2 · keystone insight — the habit that lifts your output ─────────────────────────────────
    private fun bestKeystone(windowDays: Int = 60): Pair<com.todocompanion.app.data.entity.HabitEntity, com.todocompanion.app.domain.Reasoning.Keystone>? {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val universe = (0 until windowDays).map { today - it }
        val uniSet = universe.toSet()
        val metric = tasksCompletedByDay(today - windowDays + 1, today)
        var best: Pair<com.todocompanion.app.data.entity.HabitEntity, com.todocompanion.app.domain.Reasoning.Keystone>? = null
        habits.value.filter { !it.paused && !it.archived && it.habitType != "break" }.forEach { h ->
            val done = habitCheckins.value.filter { it.habitId == h.id && it.status == "done" && hs.meetsGoal(h, it.count) }
                .map { it.epochDay }.filter { it in uniSet }.toSet()
            if (done.size < 5) return@forEach
            val k = com.todocompanion.app.domain.Reasoning.keystone(universe, metric, done)
            if (k.withN >= 5 && k.withoutN >= 5 && k.avgWith > k.avgWithout && k.lift >= 0.15) {
                if (best == null || k.lift > best!!.second.lift) best = h to k
            }
        }
        return best
    }
    fun keystoneInsight(windowDays: Int = 60): String? {
        val (h, k) = bestKeystone(windowDays) ?: return null
        val pct = Math.round(k.lift * 100).toInt()
        return "On days you keep ‘${h.name}’, you finish ${pct}% more tasks (${fmt1(k.avgWith)} vs ${fmt1(k.avgWithout)} a day)."
    }
    /** Y2 — the id of your highest-leverage (keystone) habit, or null. */
    fun keystoneHabitId(): String? = bestKeystone()?.first?.id

    // ── X3 · honest capacity — plan against real tracked focus-hours ─────────────────────────────
    fun trackedFocusMinutesByDay(days: Int = 21): Map<Long, Int> {
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now(zone)
        val m = HashMap<Long, Int>()
        for (i in 0 until days) {
            val d = today.minusDays(i.toLong())
            val ds = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val de = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val mins = com.todocompanion.app.domain.TimeTracking.totalMinutes(timeEntries.value, ds, minOf(de, now + 1), now)
            if (mins > 0) m[d.toEpochDay()] = mins
        }
        return m
    }
    /** Median daily tracked minutes over the recent window, or null if too little signal. */
    fun trackedFocusMedianMinutes(): Int? =
        com.todocompanion.app.domain.Reasoning.medianDailyFocusMinutes(trackedFocusMinutesByDay())
    fun trackedCapacityHours(): Int? = trackedFocusMedianMinutes()?.let { Math.round(it / 60.0).toInt().coerceAtLeast(1) }

    // ── X4 · peak focus window ────────────────────────────────────────────────────────────────────
    fun focusByHour(days: Int = 30): IntArray {
        val agg = IntArray(24); val now = System.currentTimeMillis(); val today = java.time.LocalDate.now(zone)
        for (i in 0 until days) {
            val d = today.minusDays(i.toLong())
            val ds = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val de = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val bh = com.todocompanion.app.domain.TimeInsights.minutesByHour(timeEntries.value, ds, de, now)
            for (h in 0..23) agg[h] += bh[h]
        }
        return agg
    }
    fun focusPeakWindow(len: Int = 2): com.todocompanion.app.domain.Reasoning.PeakWindow? =
        com.todocompanion.app.domain.Reasoning.peakWindow(focusByHour(), len)
    fun focusPeakLabel(): String? = focusPeakWindow()?.let { "${hourLabel(it.startHour)}–${hourLabel(it.endHour)}" }

    // ── X5 · end-of-day forecast ──────────────────────────────────────────────────────────────────
    data class DayForecast(val willFinish: Int, val willSlip: Int, val neededMin: Int, val availMin: Int,
                           val calibrated: Boolean, val slipTitles: List<String>) {
        val total get() = willFinish + willSlip
    }
    fun dayForecast(): DayForecast? {
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now(zone)
        val endOfWorkMillis = today.atStartOfDay(zone).plusHours(settings.value.workEndHour.coerceIn(1, 24).toLong()).toInstant().toEpochMilli()
        val availMin = (((endOfWorkMillis - now) / 60_000L).toInt()).coerceAtLeast(0)
        val endToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val remaining = doNextRanked().filter { t -> t.dueDate != null && t.dueDate!! < endToday }
        if (remaining.isEmpty()) return null
        fun est(t: TaskEntity) = (t.estimateMin ?: t.estimateMax ?: t.durationMin ?: 30)
        // Y7: cost each task by its own activity's calibration when known, else the global factor.
        val global = planVsActualWeek().calibration
        val actCal = calibrationByActivity()
        fun factorFor(t: TaskEntity): Double = (dominantActivityOf(t.id)?.let { actCal[it] } ?: global ?: 1.0).coerceIn(0.25, 4.0)
        val costs = remaining.map { (Math.round(est(it) * factorFor(it)).toInt()).coerceAtLeast(1) }
        val needed = costs.sum()
        val (finish, slip) = com.todocompanion.app.domain.Reasoning.fitCount(costs, availMin)
        val slipTitles = if (slip > 0) remaining.takeLast(slip).map { it.title } else emptyList()
        return DayForecast(finish, slip, needed, availMin, global != null || actCal.isNotEmpty(), slipTitles)
    }

    // ── X6 · rhythm-matched schedule ──────────────────────────────────────────────────────────────
    data class RhythmSuggestion(val habitId: String, val weekdays: Set<Int>, val minute: Int?) {
        fun weekdayLabel(): String {
            val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            return weekdays.sorted().joinToString(", ") { names[it - 1] }
        }
    }
    fun rhythmSuggestion(habitId: String, windowDays: Int = 120): RhythmSuggestion? {
        val h = habits.value.firstOrNull { it.id == habitId } ?: return null
        if (!(h.freqType == "weekly" && h.scheduleDays.isBlank())) return null   // only for "every day" weekly habits
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val counts = IntArray(7)
        val hc = habitCheckins.value.filter { it.habitId == h.id && it.status == "done" }
        hc.forEach { ci ->
            if (today - ci.epochDay in 0 until windowDays && hs.meetsGoal(h, ci.count)) {
                val dow = java.time.LocalDate.ofEpochDay(ci.epochDay).dayOfWeek.value
                counts[dow - 1]++
            }
        }
        val wd = com.todocompanion.app.domain.Reasoning.rhythmWeekdays(counts) ?: return null
        return RhythmSuggestion(habitId, wd, hs.typicalDoneMinute(hc))
    }
    fun applyRhythmSuggestion(s: RhythmSuggestion) = viewModelScope.launch {
        val h = habits.value.firstOrNull { it.id == s.habitId } ?: return@launch
        val days = s.weekdays.sorted().joinToString(",")
        repo.upsertHabit(h.copy(freqType = "weekly", scheduleDays = days))
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
        com.todocompanion.app.widget.HabitsWidget.refresh(appCtx)
        appendAction("rhythm", "Matched ‘${h.name}’ to ${s.weekdayLabel()}", "{\"habit\":\"${h.id}\",\"freq\":\"${h.freqType}\",\"days\":\"${h.scheduleDays}\"}")   // Z6: undoable
        toast("Schedule matched to your rhythm")
    }

    // ── X7 · insights feed — what your data noticed (Z1 why · Z2 dismiss · Z3 confidence) ──────────
    /** [key] is stable so Z2 can dismiss/snooze it; [why] shows Z1's working; [confidence] gates Z3. */
    data class Insight(val key: String, val text: String, val why: String? = null, val confidence: String? = null)
    fun insightsFeed(): List<Insight> {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val prefs = insightPrefs()
        val out = ArrayList<Insight>()
        fun add(i: Insight) { if (!prefs.suppressed(i.key, today)) out += i }

        bestKeystone()?.let { (h, k) ->
            val pct = Math.round(k.lift * 100).toInt()
            val conf = if (k.withN >= 8 && k.withoutN >= 8) "High confidence" else "Early signal"
            add(Insight("keystone", "On days you keep ‘${h.name}’, you finish ${pct}% more tasks.",
                "Compared ${k.withN} days you kept it (avg ${fmt1(k.avgWith)} tasks) with ${k.withoutN} days you didn't (avg ${fmt1(k.avgWithout)}), over the last 60 days. This is a correlation, not proof that the habit causes the difference.", conf))
        }
        val pa = planVsActualWeek()
        pa.calibration?.let { cal -> if (kotlin.math.abs(cal - 1.0) >= 0.2) {
            val pct = Math.round((cal - 1.0) * 100).toInt()
            add(Insight("calibration",
                if (cal > 1) "Your tasks take about ${pct}% longer than you estimate — the forecast corrects for it."
                else "You beat your estimates by about ${-pct}% — you could plan a little more into a day.",
                "The median actual-over-estimate ratio across ${pa.items.size} tasks this week that had both an estimate and tracked time.",
                if (pa.items.size >= 6) "High confidence" else "Medium confidence"))
        } }
        focusPeakWindow()?.let { w -> add(Insight("peak",
            "You focus most between ${hourLabel(w.startHour)}–${hourLabel(w.endHour)} — a good window to protect for your hardest work.",
            "Summed your tracked minutes by hour over the last 30 days; that two-hour window held ${w.minutes} minutes, the most of any.", null)) }
        val actCal = calibrationByActivity()
        if (actCal.size >= 2) actCal.maxByOrNull { kotlin.math.abs(it.value - 1.0) }?.let { (actId, ratio) ->
            val name = timeActivities.value.firstOrNull { it.id == actId }?.name
            if (name != null && kotlin.math.abs(ratio - 1.0) >= 0.2) {
                val pct = Math.round((ratio - 1.0) * 100).toInt()
                add(Insight("actcal",
                    if (pct > 0) "‘$name’ runs about ${pct}% over your estimate — the forecast corrects it on its own."
                    else "‘$name’ comes in about ${-pct}% under your estimate — the forecast accounts for it.",
                    "From at least 3 ‘$name’ tasks with an estimate and tracked time in the last 4 weeks.", null))
            }
        }
        crossTypeTagReport(7).firstOrNull()?.let { line -> if (line.tag.isNotBlank())
            add(Insight("area", "‘${line.tag}’ took the most of your week: ${line.minutes / 60}h ${line.minutes % 60}m, ${line.tasksDone} task${if (line.tasksDone == 1) "" else "s"}, ${line.habitDays} habit-day${if (line.habitDays == 1) "" else "s"}.",
                "Tagged tracked time, completed tasks and kept habits over the last 7 days, grouped by tag.", null))
        }
        seasonality()?.let { add(Insight("seasonality", it, "From tracked minutes per weekday over the last 8 weeks.", null)) }
        momentumLinks(60).firstOrNull()?.let { add(Insight("link", it, "A conditional-rate correlation over the last 60 days. Not proof of cause.", null)) }
        return out.distinctBy { it.key }.take(5)
    }

    // ── Z2 · nudge control — dismiss / snooze / restore any insight ───────────────────────────────
    fun insightPrefs(): com.todocompanion.app.domain.InsightPrefs = com.todocompanion.app.domain.InsightPrefsCodec.parse(settings.value.insightPrefsJson)
    private fun saveInsightPrefs(p: com.todocompanion.app.domain.InsightPrefs) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(insightPrefsJson = com.todocompanion.app.domain.InsightPrefsCodec.encode(p)))
    }
    fun dismissInsight(key: String) = saveInsightPrefs(insightPrefs().dismiss(key))
    fun snoozeInsight(key: String, days: Int = 7) = saveInsightPrefs(insightPrefs().snooze(key, java.time.LocalDate.now(zone).toEpochDay() + days))
    fun restoreInsight(key: String) = saveInsightPrefs(insightPrefs().restore(key))
    fun isInsightSuppressed(key: String): Boolean = insightPrefs().suppressed(key, java.time.LocalDate.now(zone).toEpochDay())

    // ── X8 · day replay & one-tap backfill ────────────────────────────────────────────────────────
    data class ReplayBlock(val taskId: String, val title: String, val startMin: Int, val durMin: Int)
    fun dayReplay(): List<ReplayBlock> {
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val blocks = tasks.value.mapNotNull { t ->
            val due = t.dueDate ?: return@mapNotNull null
            if (t.isAllDay || t.trashed || t.isNote) return@mapNotNull null
            val d = java.time.Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
            if (d != today) return@mapNotNull null
            val startMin = ((due - dayStart) / 60_000L).toInt().coerceIn(0, 1439)
            val dur = (t.durationMin ?: t.estimateMin ?: t.estimateMax ?: 30).coerceIn(5, 600)
            com.todocompanion.app.domain.TimeInsights.PlannedBlock(t.id, t.title, startMin, dur)
        }
        val passed = blocks.filter { dayStart + (it.startMin + it.durMin) * 60_000L <= now }
        return com.todocompanion.app.domain.TimeInsights.untrackedBlocks(passed, timeEntries.value, dayStart, now)
            .map { ReplayBlock(it.taskId, it.label, it.startMin, it.durMin) }
    }
    fun backfillBlock(b: ReplayBlock, activityId: String? = null) = viewModelScope.launch {
        val dayStart = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val start = dayStart + b.startMin * 60_000L
        val end = start + b.durMin * 60_000L
        val act = activityId ?: repo.ensureTaskActivity()
        val id = java.util.UUID.randomUUID().toString()
        repo.upsertTimeEntry(com.todocompanion.app.data.entity.TimeEntryEntity(id, act, start, end, "", b.taskId, null, System.currentTimeMillis()))
        refreshTimeWidget()
        appendAction("backfill", "Logged ${b.durMin}m to ‘${b.title}’", "{\"entry\":\"$id\"}")   // Z6: undoable
        toast("Logged ${b.durMin}m to ‘${b.title}’")
    }

    // ══ Tier Y · the assistant acts on what it knows ═════════════════════════════════════════════

    private fun hm(min: Int): String = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
    private fun medianOf(xs: List<Double>): Double {
        val s = xs.sorted(); val n = s.size
        return if (n == 0) 0.0 else if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    // ── Y1 · self-coaching Goals ──────────────────────────────────────────────────────────────────
    data class GoalCoach(val text: String, val startActivityId: String?)
    /** For a goal that's behind pace or has a slipping arm, the single most useful nudge — plus the
     *  activity to start a catch-up session on, when the time arm is the one behind. */
    fun goalCoaching(g: com.todocompanion.app.domain.Goal): GoalCoach? {
        val gh = goalHealth(g)
        // Time arm: required run-rate to hit the target date.
        if (g.hasBudget && gh.daysLeft != null) {
            val remaining = (g.budgetMinutes - gh.minutesTracked).coerceAtLeast(0)
            if (remaining > 0) {
                val text = if (gh.daysLeft <= 0) "Past the target date with ${hm(remaining)} of the budget left — a session still counts."
                    else "To hit the target, about ${hm(remaining / gh.daysLeft.coerceAtLeast(1))}/day for ${gh.daysLeft} more day${if (gh.daysLeft == 1) "" else "s"}."
                return GoalCoach(text, g.activityId)
            }
        }
        // Habit arm slipping.
        if (g.hasHabit && gh.habitStrength in 1..39)
            return GoalCoach("Its habit is slipping (${gh.habitStrength}%) — a check-in today is the highest-leverage move.", null)
        // Task arm with a near deadline.
        if (g.hasTasks && gh.taskTotal > 0 && gh.taskDone < gh.taskTotal && gh.daysLeft != null && gh.daysLeft in 0..3)
            return GoalCoach("${gh.taskTotal - gh.taskDone} task${if (gh.taskTotal - gh.taskDone == 1) "" else "s"} left with the deadline near.", null)
        return null
    }
    fun startActivityTimer(activityId: String) = viewModelScope.launch {
        if (activityId.isNotBlank() && timeActivities.value.any { it.id == activityId && !it.archived }) {
            repo.startTimeTracking(activityId, stopFirst = !settings.value.multiTimer)
            com.todocompanion.app.reminders.AutomationRunner.onStart(appCtx, repo, activityId)
            refreshTimeWidget(); toast("Session started")
        }
    }

    // ── Y8 · goal contention — two goals drawing on the same hours ────────────────────────────────
    fun goalContention(): List<String> {
        val gs = goals().filter { it.hasBudget }
        val actName = timeActivities.value.associate { it.id to ((it.emoji?.plus(" ") ?: "") + it.name) }
        return gs.groupBy { it.activityId }.filter { it.value.size >= 2 }
            .map { (act, list) -> "‘${list[0].name}’ and ‘${list[1].name}’ both draw on ${actName[act] ?: "the same activity"} — they compete for the same hours." }
            .take(2)
    }

    // ── Y3 · what-if capacity — will new work fit your real hours? ─────────────────────────────────
    data class CapacitySnapshot(val committedMin: Int, val capacityMin: Int, val tracked: Boolean) {
        val freeMin get() = (capacityMin - committedMin).coerceAtLeast(0)
    }
    fun capacitySnapshot(days: Int = 14): CapacitySnapshot {
        val now = System.currentTimeMillis()
        val end = java.time.LocalDate.now(zone).plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        fun est(t: TaskEntity) = (t.estimateMin ?: t.estimateMax ?: t.durationMin ?: 30)
        val committed = tasks.value.filter { !it.trashed && !it.completed && !it.abandoned && !it.isNote && it.dueDate != null && it.dueDate!! in (now + 1)..end }.sumOf { est(it) }
        val trackedCapH = if (settings.value.honestCapacity) trackedCapacityHours() else null
        val today = java.time.LocalDate.now(zone)
        val capMin = if (trackedCapH != null) trackedCapH * 60 * days
            else (0 until days).sumOf { settings.value.capacityHoursFor(today.plusDays(it.toLong()).dayOfWeek) * 60 }
        return CapacitySnapshot(committed, capMin, trackedCapH != null)
    }

    // ── Y4 · your ideal day — a scaffold from your real patterns ──────────────────────────────────
    data class IdealBlock(val minute: Int, val label: String, val kind: String)
    fun idealDay(): List<IdealBlock> {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val out = ArrayList<IdealBlock>()
        focusPeakWindow(2)?.let { w -> out += IdealBlock(w.startHour * 60, "Deep work — your peak focus window", "focus") }
        val today = java.time.LocalDate.now(zone).toEpochDay()
        habits.value.filter { !it.paused && !it.archived }.forEach { h ->
            val hc = habitCheckins.value.filter { it.habitId == h.id }
            val done = hc.filter { it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            val due = hs.dueToday(h, today, done, hc.firstOrNull { it.epochDay == today }?.count ?: 0)
            if (due) hs.typicalDoneMinute(hc)?.let { m -> out += IdealBlock(m, (h.emoji?.plus(" ") ?: "") + h.name, "habit") }
        }
        return out.sortedBy { it.minute }
    }

    // ── Y6 · anti-burnout radar — hours up while habit adherence falls ────────────────────────────
    fun burnoutSignal(): String? {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now(zone)
        val wkStartMs = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val prevStartMs = today.minusDays(13).atStartOfDay(zone).toInstant().toEpochMilli()
        val hoursThis = com.todocompanion.app.domain.TimeTracking.totalMinutes(timeEntries.value, wkStartMs, now + 1, now) / 60.0
        val hoursPrev = com.todocompanion.app.domain.TimeTracking.totalMinutes(timeEntries.value, prevStartMs, wkStartMs, now) / 60.0
        fun adherence(startDay: Long, endDay: Long): Double {
            var exp = 0; var done = 0
            habits.value.filter { !it.paused && !it.archived && it.habitType != "break" }.forEach { h ->
                val dd = habitCheckins.value.filter { it.habitId == h.id && it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
                (startDay..endDay).forEach { d -> if (hs.isExpectedDay(h, d)) { exp++; if (d in dd) done++ } }
            }
            return if (exp == 0) -1.0 else done.toDouble() / exp
        }
        val rThis = adherence(today.minusDays(6).toEpochDay(), today.toEpochDay())
        val rPrev = adherence(today.minusDays(13).toEpochDay(), today.minusDays(7).toEpochDay())
        if (rThis < 0 || rPrev < 0) return null
        if (!com.todocompanion.app.domain.Reasoning.burnoutDiverges(hoursThis, hoursPrev, rThis, rPrev)) return null
        val up = Math.round((hoursThis - hoursPrev) / hoursPrev * 100).toInt()
        return "Your tracked hours are up ${up}% this week, but your health-building habits are slipping. That combination is the early shape of burnout — it may be a week to ease off and protect the habits."
    }

    // ── Y7 · per-activity calibration — sharper, category-specific forecasts ──────────────────────
    fun calibrationByActivity(windowDays: Int = 28): Map<String, Double> {
        val now = System.currentTimeMillis()
        val winStart = java.time.LocalDate.now(zone).minusDays((windowDays - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val byTask = timeEntries.value.filter { it.taskId != null }.groupBy { it.taskId!! }
        val ratiosByAct = HashMap<String, MutableList<Double>>()
        byTask.forEach { (taskId, es) ->
            val t = tasks.value.firstOrNull { it.id == taskId } ?: return@forEach
            val planned = (t.estimateMin ?: t.durationMin ?: 0); if (planned <= 0) return@forEach
            val actual = es.sumOf { com.todocompanion.app.domain.TimeTracking.minutesInWindow(it.startMillis, it.endMillis, winStart, now + 1, now) }
            if (actual <= 0) return@forEach
            val domAct = es.groupBy { it.activityId }.maxByOrNull { (_, v) -> v.sumOf { it.minutes(now) } }?.key ?: return@forEach
            ratiosByAct.getOrPut(domAct) { mutableListOf() } += actual.toDouble() / planned
        }
        return ratiosByAct.filter { it.value.size >= 3 }.mapValues { medianOf(it.value) }
    }
    /** The activity a task's tracked time predominantly falls under (all history), or null. */
    private fun dominantActivityOf(taskId: String): String? {
        val now = System.currentTimeMillis()
        return timeEntries.value.filter { it.taskId == taskId }
            .groupBy { it.activityId }.maxByOrNull { (_, v) -> v.sumOf { it.minutes(now) } }?.key
    }

    // ── Y8 · seasonality — heaviest / lightest weekday ────────────────────────────────────────────
    fun seasonality(weeks: Int = 8): String? {
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now(zone)
        val byWeekday = DoubleArray(7)
        for (i in 0 until weeks * 7) {
            val d = today.minusDays(i.toLong())
            val ds = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val de = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            byWeekday[d.dayOfWeek.value - 1] += com.todocompanion.app.domain.TimeTracking.totalMinutes(timeEntries.value, ds, minOf(de, now + 1), now).toDouble()
        }
        val hl = com.todocompanion.app.domain.Reasoning.heaviestLightestWeekday(byWeekday) ?: return null
        val names = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        return "${names[hl.first - 1]}s are your heaviest tracked day; ${names[hl.second - 1]}s your lightest — plan the demanding work accordingly."
    }

    // ══ Tier Z · the trustworthy assistant ═══════════════════════════════════════════════════════

    // ── Z4 · morning brief ────────────────────────────────────────────────────────────────────────
    fun setMorningBrief(enabled: Boolean, hour: Int) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(morningBriefEnabled = enabled, morningBriefHour = hour.coerceIn(0, 23)))
        if (enabled) com.todocompanion.app.reminders.AlarmScheduler.scheduleMorningBrief(appCtx, hour.coerceIn(0, 23))
        else com.todocompanion.app.reminders.AlarmScheduler.cancelMorningBrief(appCtx)
    }
    /** The full in-app brief — richer than the notification: next action + forecast + one insight. */
    fun morningBriefLines(): List<String> {
        val out = ArrayList<String>()
        rightNow()?.let { out += "▸ ${it.title} — ${it.subtitle}" }
        dayForecast()?.let { f ->
            out += if (f.willSlip == 0) "▸ All ${f.willFinish} remaining task${if (f.willFinish == 1) "" else "s"} fit today."
            else "▸ ${f.willFinish} of ${f.total} tasks fit today — ${f.willSlip} may slip."
        }
        insightsFeed().firstOrNull()?.let { out += "▸ ${it.text}" }
        return out
    }

    // ── Z6 · assistant action log & undo ──────────────────────────────────────────────────────────
    fun assistantLog(): List<com.todocompanion.app.domain.AssistantAction> = com.todocompanion.app.domain.AssistantLog.parse(settings.value.assistantLogJson)
    private suspend fun appendAction(kind: String, description: String, undo: String = "") {
        val a = com.todocompanion.app.domain.AssistantAction(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), kind, description, undo)
        val next = com.todocompanion.app.domain.AssistantLog.push(assistantLog(), a)
        repo.saveSettings(settings.value.copy(assistantLogJson = com.todocompanion.app.domain.AssistantLog.encode(next)))
    }
    fun undoAction(a: com.todocompanion.app.domain.AssistantAction) = viewModelScope.launch {
        if (!a.reversible) return@launch
        val el = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(a.undo) }.getOrNull()
        val obj = el as? kotlinx.serialization.json.JsonObject
        fun str(key: String): String? = (obj?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content
        when (a.kind) {
            "backfill" -> str("entry")?.let { repo.deleteTimeEntry(it); refreshTimeWidget() }
            "rhythm" -> {
                val hid = str("habit"); val freq = str("freq") ?: "weekly"; val days = str("days") ?: ""
                habits.value.firstOrNull { it.id == hid }?.let { repo.upsertHabit(it.copy(freqType = freq, scheduleDays = days)) }
                com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
            }
            "plan" -> {
                val dues = obj?.get("dues") as? kotlinx.serialization.json.JsonObject
                dues?.forEach { (taskId, v) ->
                    val old = (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                    repo.getTask(taskId)?.let { repo.saveTask(it.copy(dueDate = if (old == null || old == 0L) null else old)) }
                }
            }
        }
        val next = com.todocompanion.app.domain.AssistantLog.markUndone(assistantLog(), a.id)
        repo.saveSettings(settings.value.copy(assistantLogJson = com.todocompanion.app.domain.AssistantLog.encode(next)))
        toast("Undone")
    }

    // ── Z5 · you over time — monthly meta-metric snapshots ────────────────────────────────────────
    fun metricSnapshots(): List<com.todocompanion.app.domain.MetricSnapshot> = com.todocompanion.app.domain.MetricSnapshots.parse(settings.value.metricSnapshotsJson)
    fun recordMonthlySnapshotIfNeeded() = viewModelScope.launch {
        val ym = java.time.YearMonth.now(zone).toString()
        val list = metricSnapshots()
        if (list.any { it.yearMonth == ym }) return@launch
        val cal = planVsActualWeek().calibration?.let { Math.round((it - 1.0) * 100).toInt() }
        val capH = trackedCapacityHours()
        val ks = bestKeystone()?.let { Math.round(it.second.lift * 100).toInt() }
        val bal = balanceBreakdown(30).firstOrNull()
        val snap = com.todocompanion.app.domain.MetricSnapshot(ym, cal, capH, ks, bal?.area ?: "", bal?.let { (it.share * 100).toInt() })
        // Only bother storing a month that actually has some signal.
        if (cal == null && capH == null && ks == null && bal == null) return@launch
        repo.saveSettings(settings.value.copy(metricSnapshotsJson = com.todocompanion.app.domain.MetricSnapshots.encode(com.todocompanion.app.domain.MetricSnapshots.upsert(list, snap))))
    }

    // ── Z7 · trust dashboard — the data that exists, all on this device ───────────────────────────
    data class DataCounts(val tasks: Int, val habits: Int, val checkins: Int, val timeEntries: Int, val activities: Int, val focus: Int)
    fun dataCounts() = DataCounts(tasks.value.size, habits.value.size, habitCheckins.value.size, timeEntries.value.size, timeActivities.value.size, focusSessions.value.size + timeEntries.value.count { it.kind == "focus" })

    // ── Plan A · at-rest database encryption (SQLCipher). State lives in SecureDb's own prefs (it must
    // be readable before the DB opens), not in AppSettings — so these are simple synchronous getters. ──
    fun dbEncryptionDesired(): Boolean = com.todocompanion.app.data.security.SecureDb.desiredEncrypted(appCtx)
    fun dbEncryptionActual(): Boolean = com.todocompanion.app.data.security.SecureDb.fileEncrypted(appCtx)
    fun dbEncryptionPending(): Boolean = com.todocompanion.app.data.security.SecureDb.migrationPending(appCtx)
    fun dbEncryptionError(): String = com.todocompanion.app.data.security.SecureDb.lastError(appCtx)
    /** Flip the desired at-rest encryption state. The actual migration runs on the next app start
     *  (the UI prompts for a restart); returns nothing that changes until then. */
    fun setDbEncryption(want: Boolean) = com.todocompanion.app.data.security.SecureDb.setDesiredEncrypted(appCtx, want)

    // ── Z8 · graded strength — an opt-in that gives partial days partial credit ───────────────────
    /** Per-day fractional credit for build-habit days that were attempted but fell short of the goal. */
    private fun gradedCreditFor(h: com.todocompanion.app.data.entity.HabitEntity, hc: List<com.todocompanion.app.data.entity.HabitCheckinEntity>): Map<Long, Double> {
        if (h.habitType == "break") return emptyMap()
        val target = h.targetPerDay.coerceAtLeast(1)
        val hs = com.todocompanion.app.domain.habit.HabitStats
        return hc.filter { it.status == "done" && !hs.meetsGoal(h, it.count) && it.count > 0 }
            .associate { it.epochDay to (it.count.toDouble() / target).coerceIn(0.0, 0.99) }
    }
    /** The strength score honouring the graded-credit opt-in (Z8) when it's on. */
    fun strengthOf(h: com.todocompanion.app.data.entity.HabitEntity): Int {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val hc = habitCheckins.value.filter { it.habitId == h.id }
        val done = hc.filter { it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
        val skip = hc.filter { it.status == "skip" }.map { it.epochDay }.toSet()
        val relapse = hc.filter { hs.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val graded = if (settings.value.gradedStrength) gradedCreditFor(h, hc) else emptyMap()
        return hs.strength(h, done, skip, relapse, today, gradedCredit = graded)
    }
    /** Z8 preview — average strength across active build habits, binary vs graded, for the opt-in. */
    fun gradedStrengthPreview(): Pair<Int, Int>? {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val active = habits.value.filter { !it.archived && !it.paused && it.habitType != "break" }
        if (active.isEmpty()) return null
        val binary = ArrayList<Int>(); val graded = ArrayList<Int>()
        active.forEach { h ->
            val hc = habitCheckins.value.filter { it.habitId == h.id }
            val done = hc.filter { it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            val skip = hc.filter { it.status == "skip" }.map { it.epochDay }.toSet()
            val relapse = hc.filter { hs.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
            binary += hs.strength(h, done, skip, relapse, today)
            graded += hs.strength(h, done, skip, relapse, today, gradedCredit = gradedCreditFor(h, hc))
        }
        return binary.average().toInt() to graded.average().toInt()
    }
    fun setGradedStrength(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(gradedStrength = on)) }

    // ══ PC · polish & correctness ════════════════════════════════════════════════════════════════

    // PC1 · reduced motion; PC4 · dismissible discoverability tips.
    fun setReduceMotion(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(reduceMotion = on)) }
    fun dismissTip(key: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(dismissedTips = settings.value.dismissedTips + key)) }
    fun isTipDismissed(key: String): Boolean = key in settings.value.dismissedTips

    // PC6 · reminder-reliability self-check — warn when the OS is set up to throttle our alarms.
    data class ReminderHealth(val exactAlarms: Boolean, val batteryUnrestricted: Boolean, val notifications: Boolean) {
        val ok get() = exactAlarms && batteryUnrestricted && notifications
        val issues: List<String> get() = buildList {
            if (!notifications) add("Notifications are off — reminders can't appear. Turn them on for this app.")
            if (!exactAlarms) add("Exact alarms are blocked — reminders may fire late. Allow them below.")
            if (!batteryUnrestricted) add("Battery optimisation is on — Android may delay or drop alarms. Allow unrestricted below.")
        }
    }
    fun reminderHealth(): ReminderHealth {
        val exact = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            (appCtx.getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() ?: true) else true
        val battery = appCtx.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(appCtx.packageName) ?: true
        val notif = androidx.core.app.NotificationManagerCompat.from(appCtx).areNotificationsEnabled()
        return ReminderHealth(exact, battery, notif)
    }

    fun addManualTimeEntry(activityId: String, startMillis: Long, endMillis: Long, note: String = "") = viewModelScope.launch {
        if (endMillis > startMillis) repo.addManualTimeEntry(activityId, startMillis, endMillis, note)
    }
    fun updateTimeEntry(e: com.todocompanion.app.data.entity.TimeEntryEntity) = viewModelScope.launch { repo.upsertTimeEntry(e); refreshTimeWidget() }
    fun deleteTimeEntry(id: String) = viewModelScope.launch { repo.deleteTimeEntry(id); refreshTimeWidget() }
    /** U4: split a logged interval in two at [atMillis]. */
    fun splitTimeEntry(id: String, atMillis: Long) = viewModelScope.launch { repo.splitTimeEntry(id, atMillis); refreshTimeWidget() }

    // T2: start tracking time against a task (its default activity, else the generic "Tasks" bucket).
    fun startTimeTrackingForTask(task: TaskEntity) = viewModelScope.launch {
        val actId = task.defaultActivityId?.takeIf { id -> timeActivities.value.any { it.id == id && !it.archived } }
            ?: repo.ensureTaskActivity()
        repo.startTimeTracking(actId, taskId = task.id); refreshTimeWidget()
    }
    fun setTaskDefaultActivity(taskId: String, activityId: String?) = viewModelScope.launch {
        repo.getTask(taskId)?.let { repo.saveTask(it.copy(defaultActivityId = activityId)) }
    }
    // T3: start tracking time against a habit (its linked activity, else a generic bucket).
    fun startTimeTrackingForHabit(habit: com.todocompanion.app.data.entity.HabitEntity) = viewModelScope.launch {
        val actId = habit.timeActivityId?.takeIf { id -> timeActivities.value.any { it.id == id && !it.archived } }
            ?: repo.ensureFocusActivity()
        repo.startTimeTracking(actId, habitId = habit.id); refreshTimeWidget()
    }
    fun setHabitTimeActivity(habitId: String, activityId: String?) = viewModelScope.launch {
        habits.value.firstOrNull { it.id == habitId }?.let { repo.upsertHabit(it.copy(timeActivityId = activityId)) }
    }

    /** M2: create a whole themed routine at once (one reschedule/refresh for the batch). */
    fun addHabits(habits: List<com.todocompanion.app.data.entity.HabitEntity>) = viewModelScope.launch {
        val ws = settings.value.activeWorkspaceId
        habits.forEach { repo.createHabit(it.copy(workspaceId = it.workspaceId.ifBlank { ws })) }
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
        refreshHabitWidgets()
    }
    fun deleteHabit(id: String) = viewModelScope.launch {
        repo.deleteHabit(id); refreshHabitWidgets()
    }
    // N2: reward-unlock celebration — surfaced to the Habits screen (confetti + toast) and a notification.
    val rewardCelebration = MutableStateFlow<String?>(null)
    private fun celebrateIfRewardReached(h: com.todocompanion.app.data.entity.HabitEntity) {
        if (h.rewardText.isBlank() || h.rewardAtStreak <= 0 || h.habitType == "break") return
        viewModelScope.launch {
            val hs = com.todocompanion.app.domain.habit.HabitStats
            val cks = repo.getHabitCheckinsOnce().filter { it.habitId == h.id }
            val done = cks.filter { it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            val skip = cks.filter { it.status == "skip" }.map { it.epochDay }.toSet()
            val rel = cks.filter { hs.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
            val streak = hs.currentStreak(h, done, skip, rel, java.time.LocalDate.now().toEpochDay())
            if (streak == h.rewardAtStreak) {
                com.todocompanion.app.reminders.Notifications.showReward(appCtx, h.name, h.rewardText, streak)
                rewardCelebration.value = h.rewardText
            }
        }
    }
    /** A day cannot be logged before the habit began — no "history" earlier than the habit itself. */
    private fun beforeStart(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long): Boolean {
        if (epochDay >= h.startEpochDay(zone)) return false
        toast("You can only log from the day “${h.name}” started.")
        return true
    }
    fun cycleHabit(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, current: Int) = viewModelScope.launch {
        if (beforeStart(h, epochDay)) return@launch
        repo.cycleCheckin(h.id, epochDay, h.targetPerDay, current, h.clickIncrement, h.extraTarget)
        refreshHabitWidgets(); celebrateIfRewardReached(h); awardIfNewlyDone(h, epochDay, current)
    }
    /** Numeric / exact value entry for a day (also used to record a break-habit relapse amount). */
    fun setHabitValue(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, count: Int) = viewModelScope.launch {
        if (beforeStart(h, epochDay)) return@launch
        val old = repo.getHabitCheckinsOnce().firstOrNull { it.habitId == h.id && it.epochDay == epochDay }?.count ?: 0
        repo.setCheckinValue(h.id, epochDay, count); refreshHabitWidgets(); celebrateIfRewardReached(h); awardIfNewlyDone(h, epochDay, old)
    }
    /** R33 F6 — the "shine": a celebratory pulse surfaced to the Habits screen when a habit is completed. */
    data class HabitShine(val name: String, val emoji: String?, val phrase: String, val colorArgb: Long?)
    val habitShine = MutableStateFlow<HabitShine?>(null)

    /** V4/V12: when a build habit crosses into "done", earn a momentum point, celebrate, and — R33 F10 —
     *  ramp the target up if the plan says consistency has held. */
    private suspend fun awardIfNewlyDone(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, oldCount: Int) {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        if (h.habitType == "break") return
        val newCount = repo.getHabitCheckinsOnce().firstOrNull { it.habitId == h.id && it.epochDay == epochDay }?.count ?: 0
        if (hs.meetsGoal(h, newCount) && !hs.meetsGoal(h, oldCount)) {
            repo.awardPoints(1)
            // R35 · reward taper — a graduated habit has eased off celebration; it runs on its own now.
            if (!h.graduated) {
                val phrase = h.encouragementList().takeIf { it.isNotEmpty() }?.random()
                    ?: listOf("Nice — that's a vote for who you're becoming.", "Done. Small wins compound.", "Kept it going 💪", "That's the one.").random()
                habitShine.value = HabitShine(h.name, h.emoji, phrase, h.colorArgb)
            }
            // F10 auto ramp-up — bump the daily target once consistency holds over the step window.
            if (epochDay == java.time.LocalDate.now(zone).toEpochDay()) {
                val done = repo.getHabitCheckinsOnce().filter { it.habitId == h.id && it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
                com.todocompanion.app.domain.habit.HabitBuilder.rampNextTarget(h, done, epochDay)?.let { nt ->
                    repo.upsertHabit(h.copy(targetPerDay = nt, rampLastStepDay = epochDay))
                    toast("You've been consistent — ${h.name} nudged up to $nt${h.unit?.let { " $it" } ?: ""}/day")
                }
            }
        }
    }

    // ── R33 · habit-builder actions ─────────────────────────────────────────────────────────────
    /** F9 — spend a streak-freeze token to protect a specific missed day (logged as a neutral skip). */
    fun useFreeze(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long) = viewModelScope.launch {
        if (h.freezeTokens <= 0) { toast("No streak freezes left — earn one with an overachieving day."); return@launch }
        repo.setDay(h.id, epochDay, 0, "skip", "❄ streak freeze")
        repo.upsertHabit(h.copy(freezeTokens = h.freezeTokens - 1))
        toast("Streak protected ❄")
    }
    /** F12 — tap a daily pledge on a quit habit (a tiny recommitment ritual). */
    fun pledgeToday(h: com.todocompanion.app.data.entity.HabitEntity) = viewModelScope.launch {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        repo.upsertHabit(h.copy(lastPledgeDay = today))
        toast("Pledged for today. One day at a time.")
    }
    /** F12 — (re)start the clean-time clock for a quit habit from now. */
    fun startQuitClock(h: com.todocompanion.app.data.entity.HabitEntity) = viewModelScope.launch {
        repo.upsertHabit(h.copy(quitSinceMillis = System.currentTimeMillis()))
        toast("Clean-time started. Day one.")
    }
    /** F13 / LS10 — log an urge/craving after surfing it (or slipping), with optional HALT state and how
     *  long the urge lasted (the duration curve). A slip also records a relapse day. */
    fun logCraving(h: com.todocompanion.app.data.entity.HabitEntity, intensity: Int, trigger: String, surfed: Boolean, halt: String = "", durationSec: Int = 0) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val d = java.time.Instant.ofEpochMilli(now).atZone(zone)
        repo.upsertCraving(com.todocompanion.app.data.entity.CravingEventEntity(
            id = java.util.UUID.randomUUID().toString(), habitId = h.id, atMillis = now,
            epochDay = d.toLocalDate().toEpochDay(), minuteOfDay = d.hour * 60 + d.minute,
            intensity = intensity.coerceIn(1, 5), trigger = trigger.trim(), surfed = surfed,
            halt = halt, durationSec = durationSec.coerceAtLeast(0),
        ))
        if (!surfed) logSlip(h, trigger.ifBlank { "urge" })
        toast(if (surfed) "You rode it out 🌊 Nicely done." else "Logged. A slip isn't a relapse — back on it.")
    }
    fun deleteCraving(id: String) = viewModelScope.launch { repo.deleteCraving(id) }
    /** F16 — start a guided journey: create its habits with staggered start dates so each unlocks on its day. */
    fun startJourney(j: com.todocompanion.app.domain.habit.HabitJourneys.Journey) = viewModelScope.launch {
        if (repo.getHabitsOnce().any { it.journeyKey == j.key && !it.archived }) { toast("You're already on “${j.name}”."); return@launch }
        val today = java.time.LocalDate.now(zone)
        var order = (repo.getHabitsOnce().maxOfOrNull { it.sortOrder } ?: 0.0)
        j.steps.forEach { s ->
            order += 1.0
            val start = today.plusDays(s.dayOffset.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
            repo.upsertHabit(com.todocompanion.app.data.entity.HabitEntity(
                id = java.util.UUID.randomUUID().toString(), name = s.name, emoji = s.emoji,
                targetPerDay = s.target.coerceAtLeast(1), unit = s.unit, createdAt = System.currentTimeMillis(),
                startDate = start, sortOrder = order, description = s.why, journeyKey = j.key,
                workspaceId = settings.value.activeWorkspaceId,
            ))
        }
        toast("Started “${j.name}” — step one is ready today.")
        refreshHabitWidgets()
    }

    // ── R34 · life-systems actions ────────────────────────────────────────────────────────────────
    fun setChronotype(i: Int) = viewModelScope.launch { repo.saveSettings(settings.value.copy(chronotype = i.coerceIn(0, 2))) }
    fun setCalmMode(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(calmMode = on)) }
    fun addReward(text: String) = viewModelScope.launch {
        val t = text.trim(); if (t.isBlank()) return@launch
        if (t !in settings.value.rewardMenu) repo.saveSettings(settings.value.copy(rewardMenu = settings.value.rewardMenu + t))
    }
    fun removeReward(text: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(rewardMenu = settings.value.rewardMenu - text)) }

    // LS5 values → systems → habits
    fun saveValue(id: String?, name: String, emoji: String?, colorArgb: Long?, statement: String) = viewModelScope.launch {
        val existing = id?.let { vid -> coreValues.value.firstOrNull { it.id == vid } }
        val order = existing?.orderIndex ?: ((coreValues.value.maxOfOrNull { it.orderIndex } ?: 0) + 1)
        repo.upsertCoreValue(
            (existing ?: com.todocompanion.app.data.entity.CoreValueEntity(id = java.util.UUID.randomUUID().toString(), name = name, orderIndex = order, createdAt = System.currentTimeMillis()))
                .copy(name = name.trim().ifBlank { "Value" }, emoji = emoji, colorArgb = colorArgb, statement = statement.trim())
        )
    }
    fun deleteValue(id: String) = viewModelScope.launch {
        repo.deleteCoreValue(id)
        // Detach any habits pointing at it, so no dangling reference remains.
        repo.getHabitsOnce().filter { it.valueId == id }.forEach { repo.upsertHabit(it.copy(valueId = null)) }
    }
    fun assignHabitValue(h: com.todocompanion.app.data.entity.HabitEntity, valueId: String?) = viewModelScope.launch { repo.upsertHabit(h.copy(valueId = valueId)) }

    // LS · habit scorecard
    fun addScorecardItem(text: String, sign: Int) = viewModelScope.launch {
        val t = text.trim(); if (t.isBlank()) return@launch
        val order = (scorecardItems.value.maxOfOrNull { it.orderIndex } ?: 0) + 1
        repo.upsertScorecardItem(com.todocompanion.app.data.entity.ScorecardItemEntity(java.util.UUID.randomUUID().toString(), t, sign.coerceIn(-1, 1), order, System.currentTimeMillis()))
    }
    fun setScorecardSign(item: com.todocompanion.app.data.entity.ScorecardItemEntity, sign: Int) = viewModelScope.launch { repo.upsertScorecardItem(item.copy(sign = sign.coerceIn(-1, 1))) }
    fun deleteScorecardItem(id: String) = viewModelScope.launch { repo.deleteScorecardItem(id) }
    /** Turn a scorecard behaviour into a habit: a "+" becomes one to build, a "−" one to break. */
    fun scorecardToHabit(item: com.todocompanion.app.data.entity.ScorecardItemEntity) = viewModelScope.launch {
        if (item.sign == 0) { toast("Tag it good (+) or bad (−) first."); return@launch }
        val order = (repo.getHabitsOnce().maxOfOrNull { it.sortOrder } ?: 0.0) + 1
        repo.upsertHabit(com.todocompanion.app.data.entity.HabitEntity(
            id = java.util.UUID.randomUUID().toString(), name = item.text.trim(),
            habitType = if (item.sign > 0) "build" else "break",
            targetComparison = if (item.sign > 0) "atleast" else "atmost",
            targetPerDay = if (item.sign > 0) 1 else 0, sortOrder = order,
            createdAt = System.currentTimeMillis(), workspaceId = settings.value.activeWorkspaceId,
        ))
        toast(if (item.sign > 0) "Added “${item.text}” as a habit to build." else "Added “${item.text}” as a habit to break.")
        refreshHabitWidgets()
    }

    // LS7 commitment contract + witness sign-off
    fun addWitness(h: com.todocompanion.app.data.entity.HabitEntity, milestoneLabel: String, note: String) = viewModelScope.launch {
        val ref = h.refereeName.trim(); if (ref.isBlank()) { toast("Name a referee in the habit's editor first."); return@launch }
        repo.upsertWitness(com.todocompanion.app.data.entity.WitnessEventEntity(
            java.util.UUID.randomUUID().toString(), h.id, ref, milestoneLabel.trim().ifBlank { "Milestone" }, System.currentTimeMillis(), note.trim()))
        toast("$ref witnessed it ✍️")
    }
    fun deleteWitness(id: String) = viewModelScope.launch { repo.deleteWitness(id) }

    // LS7 self-forfeit + akrasia horizon
    /** A derail happened — escalate the forfeit level (each repeat raises the stake). */
    fun escalateForfeit(h: com.todocompanion.app.data.entity.HabitEntity) = viewModelScope.launch {
        repo.upsertHabit(h.copy(forfeitLevel = h.forfeitLevel + 1))
        toast("Forfeit owed" + (h.forfeitText.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") + ". Level ${h.forfeitLevel + 1}.")
    }
    /** Queue a "make it easier" change — it only takes effect after a one-week akrasia horizon. */
    fun queueEase(h: com.todocompanion.app.data.entity.HabitEntity, newTarget: Int) = viewModelScope.launch {
        val applyAt = System.currentTimeMillis() + 7L * 24 * 3600 * 1000
        repo.upsertHabit(h.copy(pendingEaseMillis = applyAt, pendingEaseTarget = newTarget.coerceAtLeast(0)))
        toast("Change queued — it applies in 7 days. No easing in the heat of the moment.")
    }
    fun cancelEase(h: com.todocompanion.app.data.entity.HabitEntity) = viewModelScope.launch { repo.upsertHabit(h.copy(pendingEaseMillis = 0, pendingEaseTarget = 0)) }
    /** Apply a queued easing whose horizon has passed (called when the detail screen opens). */
    fun applyPendingEaseIfDue(h: com.todocompanion.app.data.entity.HabitEntity) = viewModelScope.launch {
        if (h.pendingEaseMillis in 1..System.currentTimeMillis()) {
            repo.upsertHabit(h.copy(targetPerDay = h.pendingEaseTarget.coerceAtLeast(if (h.habitType == "break") 0 else 1), pendingEaseMillis = 0, pendingEaseTarget = 0))
        }
    }

    // LS2 context capture at check-in
    fun setCheckinContext(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, energy: Int, mood: Int, place: String) = viewModelScope.launch {
        repo.setCheckinContext(h.id, epochDay, energy.coerceIn(0, 5), mood.coerceIn(0, 5), place.trim())
    }

    // LS · buddy digest export / import
    fun exportBuddyDigest(name: String): String {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val digest = com.todocompanion.app.domain.habit.LifeSystems.buildDigest(name.ifBlank { "Me" }, habits.value, habitCheckins.value, today, settings.value.forgivingStreaks)
        return kotlinx.serialization.json.Json.encodeToString(com.todocompanion.app.domain.habit.LifeSystems.BuddyDigest.serializer(), digest)
    }
    fun importBuddyDigest(json: String) = viewModelScope.launch {
        val digest = runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(com.todocompanion.app.domain.habit.LifeSystems.BuddyDigest.serializer(), json) }.getOrNull()
        if (digest == null) { toast("That doesn't look like a buddy digest."); return@launch }
        repo.upsertBuddy(com.todocompanion.app.data.entity.BuddySnapshotEntity(java.util.UUID.randomUUID().toString(), digest.name, System.currentTimeMillis(), json))
        toast("Imported ${digest.name}'s progress 🤝")
    }
    fun deleteBuddy(id: String) = viewModelScope.launch { repo.deleteBuddy(id) }

    // LS6 save an integrity-review reflection
    fun saveIntegrityReview(kind: String, periodKey: String, note: String, statsJson: String) = viewModelScope.launch {
        repo.upsertIntegrityReview(com.todocompanion.app.data.entity.IntegrityReviewEntity(
            java.util.UUID.randomUUID().toString(), kind, periodKey, System.currentTimeMillis(), note.trim(), statsJson))
        toast("Review saved to your ledger.")
    }
    fun deleteIntegrityReview(id: String) = viewModelScope.launch { repo.deleteIntegrityReview(id) }

    // ── R35 · third-wave actions ──────────────────────────────────────────────────────────────────
    fun setBookends(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(bookendsEnabled = on)) }
    fun setCompanion(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(companionEnabled = on)) }
    fun setStrengthMeter(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(strengthMeter = on)) }

    // TW-B self-tuning reminder — accept the suggested time.
    fun applyReminderDrift(h: com.todocompanion.app.data.entity.HabitEntity, minute: Int) = viewModelScope.launch {
        val others = h.reminderTimes.split(",").mapNotNull { it.trim().toIntOrNull() }
        val typical = com.todocompanion.app.domain.habit.HabitStats.typicalDoneMinute(repo.getHabitCheckinsOnce().filter { it.habitId == h.id })
        val replaced = if (others.isEmpty()) listOf(minute) else {
            val nearest = others.minByOrNull { kotlin.math.abs(it - (typical ?: minute)) }
            (others - (nearest ?: minute) + minute).distinct().sorted()
        }
        repo.upsertHabit(h.copy(reminderTimes = replaced.joinToString(",")))
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
        toast("Reminder moved to ${com.todocompanion.app.domain.habit.HabitStats.minuteLabel(minute)}.")
    }

    // TW-D reward taper — graduate / un-graduate a habit that's reached automaticity.
    fun setGraduated(h: com.todocompanion.app.data.entity.HabitEntity, on: Boolean) = viewModelScope.launch {
        repo.upsertHabit(h.copy(graduated = on))
        toast(if (on) "🎓 Graduated — this one's part of you now. Prompts will ease off." else "Back to active coaching.")
    }

    // TW-F make-up ledger — repay a missed non-negotiable by completing a past expected day.
    fun logMakeUp(h: com.todocompanion.app.data.entity.HabitEntity, day: Long) = viewModelScope.launch {
        repo.setDay(h.id, day, h.targetPerDay.coerceAtLeast(1), "done", "make-up")
        refreshHabitWidgets()
        toast("Made up ${java.time.LocalDate.ofEpochDay(day)}. Debt cleared — not a failure.")
    }

    // TW-C n-of-1 experiments.
    fun startExperiment(habitId: String, outcome: String, blockLen: Int, blocks: Int) = viewModelScope.launch {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        repo.upsertExperiment(com.todocompanion.app.data.entity.ExperimentEntity(
            id = java.util.UUID.randomUUID().toString(), habitId = habitId, outcome = outcome,
            startDay = today, blockLenDays = blockLen.coerceIn(1, 14), blocks = blocks.coerceIn(2, 12), createdAt = System.currentTimeMillis()))
        toast("Experiment started. Follow the on/off blocks and log your ${outcome}.")
    }
    fun endExperiment(e: com.todocompanion.app.data.entity.ExperimentEntity) = viewModelScope.launch { repo.upsertExperiment(e.copy(active = false)) }
    fun deleteExperiment(id: String) = viewModelScope.launch { repo.deleteExperiment(id) }

    // TW-D behavioral activation.
    fun addActivation(text: String, valueId: String?, day: Long) = viewModelScope.launch {
        val t = text.trim(); if (t.isBlank()) return@launch
        repo.upsertActivationItem(com.todocompanion.app.data.entity.ActivationItemEntity(
            id = java.util.UUID.randomUUID().toString(), text = t, valueId = valueId, plannedDay = day, createdAt = System.currentTimeMillis()))
    }
    fun rateActivation(item: com.todocompanion.app.data.entity.ActivationItemEntity, pleasure: Int, mastery: Int) = viewModelScope.launch {
        repo.upsertActivationItem(item.copy(done = true, pleasure = pleasure.coerceIn(0, 5), mastery = mastery.coerceIn(0, 5)))
    }
    fun deleteActivation(id: String) = viewModelScope.launch { repo.deleteActivationItem(id) }

    // TW-E daily AM/PM bookends.
    fun saveMorningIntention(day: Long, text: String, mood: Int) = viewModelScope.launch {
        val cur = repo.dayLogFor(day) ?: com.todocompanion.app.data.entity.DayLogEntity(day)
        repo.upsertDayLog(cur.copy(amIntention = text.trim(), amMood = mood.coerceIn(0, 5), updatedAt = System.currentTimeMillis()))
    }
    fun saveEveningReflection(day: Long, text: String, mood: Int) = viewModelScope.launch {
        val cur = repo.dayLogFor(day) ?: com.todocompanion.app.data.entity.DayLogEntity(day)
        repo.upsertDayLog(cur.copy(pmReflection = text.trim(), pmMood = mood.coerceIn(0, 5), updatedAt = System.currentTimeMillis()))
    }

    // ── R36 · fourth-wave actions ───────────────────────────────────────────────────────────────────
    // FW-5 New-Habit WIP limiter.
    fun setHabitWipLimit(n: Int) = viewModelScope.launch { repo.saveSettings(settings.value.copy(habitWipLimit = n.coerceIn(0, 20))) }

    // FW-11 Transition detector + reset window.
    fun setTransition(label: String, startDay: Long) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(transitionLabel = label.trim(), transitionStartDay = startDay))
        if (label.isNotBlank()) toast("Transition noted. A 3-week reset window is open — a good time to re-choose your routines.")
    }
    fun clearTransition() = viewModelScope.launch { repo.saveSettings(settings.value.copy(transitionLabel = "", transitionStartDay = 0)) }

    // FW-6 Daily shutdown + carry-forward — push each still-open, due-today task to tomorrow.
    fun carryForwardTasks(taskIds: List<String>) = viewModelScope.launch {
        if (taskIds.isEmpty()) return@launch
        val tomorrowMillis = java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        var moved = 0
        taskIds.forEach { id ->
            val t = repo.getTask(id) ?: return@forEach
            if (!t.completed) { repo.saveTask(t.copy(dueDate = tomorrowMillis)); moved++ }
        }
        if (moved > 0) toast("Carried $moved task${if (moved == 1) "" else "s"} forward to tomorrow. Day closed.")
    }

    // FW-9 Self-escrow contingency reward.
    fun addEscrow(habitId: String?, description: String, kind: String, milestoneKind: String, milestoneValue: Int) = viewModelScope.launch {
        val d = description.trim(); if (d.isBlank()) return@launch
        repo.upsertEscrow(com.todocompanion.app.data.entity.EscrowEntity(
            id = java.util.UUID.randomUUID().toString(), habitId = habitId, description = d, kind = kind,
            milestoneKind = milestoneKind, milestoneValue = milestoneValue.coerceAtLeast(1), createdAt = System.currentTimeMillis()))
        toast(if (kind == "stake") "Stake locked. It's real now — reach the milestone or it's forfeit." else "Reward escrowed. Earn it at the milestone.")
    }
    fun releaseEscrow(e: com.todocompanion.app.data.entity.EscrowEntity, redeem: Boolean) = viewModelScope.launch {
        repo.upsertEscrow(e.copy(released = true, redeemed = redeem))
        toast(when {
            e.kind == "stake" && redeem -> "Stake paid. The contract held."
            e.kind == "stake" -> "Stake returned — you made it."
            redeem -> "Enjoy it — you earned this one. 🎉"
            else -> "Banked for later."
        })
    }
    fun deleteEscrow(id: String) = viewModelScope.launch { repo.deleteEscrow(id) }

    // FW-14 Personal Nudge MRT — record that an opportunity nudge (variant v) was shown for a habit today,
    // and reconcile past open impressions against whether the habit was completed.
    fun logNudgeShown(habitId: String, variant: Int, day: Long) = viewModelScope.launch {
        if (repo.nudgeForHabitDay(habitId, day) != null) return@launch   // one impression per habit per day
        repo.upsertNudgeEvent(com.todocompanion.app.data.entity.NudgeEventEntity(
            id = java.util.UUID.randomUUID().toString(), habitId = habitId, variant = variant, epochDay = day,
            createdAt = System.currentTimeMillis()))
    }
    /** Mark open nudge impressions from the last two weeks as acted/not, by whether the target (habit or
     *  task) was completed that day. R37: extended to task-reminder impressions (targetKind = "task"). */
    fun reconcileNudges() = viewModelScope.launch {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val open = repo.openNudgesSince(today - 14)
        if (open.isEmpty()) return@launch
        val checkins = repo.getHabitCheckinsOnce()
        val habits = repo.getHabitsOnce().associateBy { it.id }
        open.forEach { ev ->
            if (ev.epochDay >= today) return@forEach   // only reconcile past days
            val done = if (ev.targetKind == "task") {
                val t = repo.getTask(ev.habitId)
                t?.completed == true && t.completedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() } == ev.epochDay
            } else {
                val h = habits[ev.habitId] ?: return@forEach
                checkins.any { it.habitId == ev.habitId && it.epochDay == ev.epochDay && it.status == "done" && com.todocompanion.app.domain.habit.HabitStats.meetsGoal(h, it.count) }
            }
            if (done) repo.upsertNudgeEvent(ev.copy(acted = true))
        }
    }

    // ── R37 · habit-science ports to tasks ─────────────────────────────────────────────────────────
    fun setTaskWipLimit(n: Int) = viewModelScope.launch { repo.saveSettings(settings.value.copy(taskWipLimit = n.coerceIn(0, 20))) }
    fun setReceptivityTiming(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(receptivityTiming = on)) }

    /** Port 9 — link a project/task to a core value, so "living your values" counts real work. */
    fun setTaskValue(taskId: String, valueId: String?) = viewModelScope.launch {
        repo.getTask(taskId)?.let { repo.saveTask(it.copy(valueId = valueId)) }
    }

    /** Port 6 — a self-escrow that rides on shipping a specific task (milestoneKind = "taskdone"). */
    fun addTaskEscrow(taskId: String, description: String, kind: String) = viewModelScope.launch {
        val d = description.trim(); if (d.isBlank()) return@launch
        repo.upsertEscrow(com.todocompanion.app.data.entity.EscrowEntity(
            id = java.util.UUID.randomUUID().toString(), habitId = null, taskId = taskId, description = d, kind = kind,
            milestoneKind = "taskdone", milestoneValue = 1, createdAt = System.currentTimeMillis()))
        toast(if (kind == "stake") "Stake locked on shipping this. It's real now." else "Reward escrowed — earn it by finishing this.")
    }

    /** Port 4 — record that a task reminder (variant v) was shown, for the reminder-wording MRT. */
    fun logTaskNudgeShown(taskId: String, variant: Int, day: Long) = viewModelScope.launch {
        if (repo.nudgeForHabitDay(taskId, day) != null) return@launch
        repo.upsertNudgeEvent(com.todocompanion.app.data.entity.NudgeEventEntity(
            id = java.util.UUID.randomUUID().toString(), habitId = taskId, variant = variant, epochDay = day,
            targetKind = "task", createdAt = System.currentTimeMillis()))
    }

    /** Port 8 — fresh-start task planning: pull every stale overdue task onto today, so the week's plan
     *  starts clean. Landmarks are when re-planning sticks. */
    fun freshStartReschedule() = viewModelScope.launch {
        val zone = this@AppViewModel.zone
        val todayStart = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val today = java.time.LocalDate.now(zone).toEpochDay()
        var moved = 0
        repo.allTasksOnce().forEach { t ->
            if (t.completed || t.trashed || t.abandoned) return@forEach
            val due = t.dueDate ?: return@forEach
            val dueDay = java.time.Instant.ofEpochMilli(due).atZone(zone).toLocalDate().toEpochDay()
            if (dueDay < today) { repo.saveTask(t.copy(dueDate = todayStart)); moved++ }
        }
        toast(if (moved > 0) "Pulled $moved overdue task${if (moved == 1) "" else "s"} onto today — fresh start." else "Nothing overdue — you're clear.")
    }

    // ── R38 · dedicated calendar ────────────────────────────────────────────────────────────────────
    fun openCalendar() = viewModelScope.launch { ensureDefaultCalendar(); calendarRoute.value = "agenda" }
    /** R39 — make sure at least one event calendar exists (called when the unified Calendar screen opens). */
    fun ensureEventCalendar() = viewModelScope.launch { ensureDefaultCalendar() }

    private suspend fun ensureDefaultCalendar(): String {
        val existing = repo.eventCalendarsOnce()
        existing.firstOrNull { it.isDefault }?.let { return it.id }
        existing.firstOrNull()?.let { return it.id }
        val id = java.util.UUID.randomUUID().toString()
        repo.upsertEventCalendar(com.todocompanion.app.data.entity.EventCalendarEntity(
            id = id, name = "Personal", colorArgb = 0xFF4F46E5, isDefault = true, orderIndex = 0,
            workspaceId = settings.value.activeWorkspaceId, createdAt = System.currentTimeMillis()))
        return id
    }

    fun createEventCalendar(name: String, color: Long) = viewModelScope.launch {
        val n = name.trim(); if (n.isBlank()) return@launch
        repo.upsertEventCalendar(com.todocompanion.app.data.entity.EventCalendarEntity(
            id = java.util.UUID.randomUUID().toString(), name = n, colorArgb = color,
            orderIndex = repo.eventCalendarsOnce().size, workspaceId = settings.value.activeWorkspaceId, createdAt = System.currentTimeMillis()))
    }
    fun setEventCalendarVisible(c: com.todocompanion.app.data.entity.EventCalendarEntity, visible: Boolean) = viewModelScope.launch { repo.upsertEventCalendar(c.copy(visible = visible)) }
    fun renameEventCalendar(c: com.todocompanion.app.data.entity.EventCalendarEntity, name: String, color: Long) = viewModelScope.launch { repo.upsertEventCalendar(c.copy(name = name.trim().ifBlank { c.name }, colorArgb = color)) }
    fun deleteEventCalendar(id: String) = viewModelScope.launch {
        val evs = repo.eventsOnce().filter { it.calendarId == id }
        evs.forEach { com.todocompanion.app.reminders.AlarmScheduler.cancelEventAlerts(appCtx, it); repo.deleteEvent(it.id) }
        repo.deleteEventCalendar(id)
    }

    /** Create or update an event, then (re)schedule its alerts. */
    fun saveEvent(existingId: String?, calendarId: String, title: String, location: String, notes: String, url: String,
                  startMillis: Long, endMillis: Long, allDay: Boolean, rrule: String, alertsMinutes: String,
                  colorArgb: Long?, floating: Boolean = false, busy: Boolean = true) = viewModelScope.launch {
        val t = title.trim().ifBlank { "Event" }
        val old = existingId?.let { repo.eventById(it) }
        old?.let { com.todocompanion.app.reminders.AlarmScheduler.cancelEventAlerts(appCtx, it) }
        val e = (old ?: com.todocompanion.app.data.entity.EventEntity(
            id = java.util.UUID.randomUUID().toString(), calendarId = calendarId, title = t,
            startMillis = startMillis, endMillis = endMillis, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
            .copy(calendarId = calendarId, title = t, location = location.trim(), notes = notes.trim(), url = url.trim(),
                startMillis = startMillis, endMillis = endMillis, allDay = allDay, rrule = rrule, alertsMinutes = alertsMinutes,
                colorArgb = colorArgb, floating = floating, busy = busy, updatedAt = System.currentTimeMillis())
        repo.upsertEvent(e)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, e)
    }

    /** Delete an event. scope: "series" (all), "this" (add an exdate), "following" (end the series before this day). */
    fun deleteEvent(id: String, scope: String = "series", instanceDay: Long = 0) = viewModelScope.launch {
        val e = repo.eventById(id) ?: return@launch
        when {
            e.rrule.isBlank() || scope == "series" -> { com.todocompanion.app.reminders.AlarmScheduler.cancelEventAlerts(appCtx, e); repo.deleteEvent(id) }
            scope == "this" -> {
                val ex = (e.exDates.split(",").mapNotNull { it.trim().toLongOrNull() } + instanceDay).distinct().joinToString(",")
                repo.upsertEvent(e.copy(exDates = ex, updatedAt = System.currentTimeMillis()))
            }
            scope == "following" -> repo.upsertEvent(e.copy(rrule = capUntil(e.rrule, instanceDay - 1), updatedAt = System.currentTimeMillis()))
        }
    }
    private fun capUntil(rule: String, untilDay: Long): String {
        val r = com.todocompanion.app.domain.recurrence.Recurrence.parse(rule) ?: return rule
        return com.todocompanion.app.domain.recurrence.Recurrence.encode(r.copy(untilEpochDay = untilDay, count = null))
    }

    /** FW moat — turn a task into a scheduled time block (an event linked back to the task). */
    fun blockTaskAsEvent(taskId: String, startMillis: Long, durationMin: Int) = viewModelScope.launch {
        val task = repo.getTask(taskId) ?: return@launch
        val calId = ensureDefaultCalendar()
        val e = com.todocompanion.app.data.entity.EventEntity(
            id = java.util.UUID.randomUUID().toString(), calendarId = calId, title = task.title,
            startMillis = startMillis, endMillis = startMillis + durationMin.coerceAtLeast(15) * 60000L,
            linkedTaskId = taskId, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        repo.upsertEvent(e); toast("Blocked ${durationMin}m for “${task.title}”.")
    }

    // ── R43 · Third-horizon planner support ────────────────────────────────────────────────────────
    /** The daylight rail: store a latitude (999.0 = off) that the sunrise/sunset bands are computed from. */
    fun setDaylightLatitude(lat: Double?) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(daylightLatitude = lat?.coerceIn(-90.0, 90.0) ?: 999.0))
    }
    /** North-star allocation: set (or clear, share<=0) a target time-share for one calendar. */
    fun setNorthStarTarget(calId: String, share: Double) = viewModelScope.launch {
        val map = com.todocompanion.app.domain.calendar.ThirdHorizon.parseTargets(settings.value.northStarTargetsCsv).toMutableMap()
        if (share > 0) map[calId] = share.coerceIn(0.0, 1.0) else map.remove(calId)
        repo.saveSettings(settings.value.copy(northStarTargetsCsv = com.todocompanion.app.domain.calendar.ThirdHorizon.encodeTargets(map)))
    }
    fun clearNorthStarTargets() = viewModelScope.launch { repo.saveSettings(settings.value.copy(northStarTargetsCsv = "")) }

    /** Booked (event) minutes per epoch-day across a range — feeds the ghost week and recovery-buffer reads. */
    suspend fun bookedMinutesByDay(startDay: Long, endDay: Long): Map<Long, Long> {
        val evs = events.value
        val out = HashMap<Long, Long>()
        var d = startDay
        while (d <= endDay) {
            val occ = com.todocompanion.app.domain.calendar.CalendarEngine.onDay(evs, d, zone)
            out[d] = occ.filter { !it.event.allDay }.sumOf { it.durationMin() }
            d++
        }
        return out
    }

    /** Deadline-aware chunking: spread a task's remaining estimate across the days before its deadline,
     *  dropping a linked calendar block onto the first free slot of each chosen day. */
    fun applyDeadlineChunks(taskId: String, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val task = repo.getTask(taskId) ?: return@launch
        val est = task.estimateMin ?: return@launch
        val deadlineMs = task.deadlineDate ?: task.dueDate ?: return@launch
        val s = settings.value
        val fromDay = java.time.LocalDate.now(zone).toEpochDay()
        val deadlineDay = java.time.Instant.ofEpochMilli(deadlineMs).atZone(zone).toLocalDate().toEpochDay()
        val evs = events.value
        // Free minutes per day = working-window length minus what's already booked.
        val freeByDay = HashMap<Long, Int>()
        var d = fromDay
        while (d <= deadlineDay) {
            val occ = com.todocompanion.app.domain.calendar.CalendarEngine.onDay(evs, d, zone)
            val budget = com.todocompanion.app.domain.calendar.CalendarPlanner.dayBudget(occ, d, s.workStartHour, s.workEndHour, zone)
            freeByDay[d] = budget.remainingMin.coerceAtLeast(0)
            d++
        }
        val chunks = com.todocompanion.app.domain.calendar.ThirdHorizon.deadlineChunks(est, freeByDay, fromDay, deadlineDay)
        var placed = 0
        chunks.forEach { c ->
            val occ = com.todocompanion.app.domain.calendar.CalendarEngine.onDay(events.value, c.day, zone)
            val fromMin = if (c.day == fromDay) (java.time.LocalTime.now(zone).hour * 60 + java.time.LocalTime.now(zone).minute) else s.workStartHour * 60
            val slot = com.todocompanion.app.domain.calendar.CalendarPlanner.slideToFree(c.day, fromMin, c.minutes, occ.map { it.event }, s.workStartHour, s.workEndHour, zone)
                ?: java.time.LocalDate.ofEpochDay(c.day).atTime(s.workStartHour.coerceIn(0, 23), 0).atZone(zone).toInstant().toEpochMilli()
            val ev = com.todocompanion.app.data.entity.EventEntity(
                id = java.util.UUID.randomUUID().toString(), calendarId = ensureDefaultCalendar(), title = task.title,
                startMillis = slot, endMillis = slot + c.minutes * 60000L, linkedTaskId = taskId,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            repo.upsertEvent(ev); placed++
        }
        toast(if (placed > 0) "Spread “${task.title}” across $placed day${if (placed == 1) "" else "s"}." else "No free time before the deadline.")
        onDone(placed)
    }

    /** Time-debt repayment: raise a task's estimate to what it actually took, so next time it's pre-booked right. */
    fun bumpTaskEstimate(taskId: String, newEstimateMin: Int) = viewModelScope.launch {
        val t = repo.getTask(taskId) ?: return@launch
        repo.saveTask(t.copy(estimateMin = newEstimateMin.coerceAtLeast(5)))
        toast("Estimate updated to ${newEstimateMin / 60}h ${newEstimateMin % 60}m.")
    }

    /** Focus contract: arm the Focus ring for this task (open the Focus tab to begin). */
    fun armFocusForTask(taskId: String) {
        pendingFocusTaskId.value = taskId
        toast("Focus armed — open the Focus tab to start the session.")
    }

    /** Natural-language quick add on the calendar: parse → an event (or a task if it starts with todo/reminder). */
    fun quickAddCalendar(text: String, forDay: Long) = viewModelScope.launch {
        val calId = ensureDefaultCalendar()
        val draft = com.todocompanion.app.domain.calendar.EventParser.parse(text, java.time.LocalDate.ofEpochDay(forDay), zone)
        if (draft == null) {
            // No date/time recognised — make an all-day event on the focused day.
            val start = java.time.LocalDate.ofEpochDay(forDay).atStartOfDay(zone).toInstant().toEpochMilli()
            repo.upsertEvent(com.todocompanion.app.data.entity.EventEntity(
                id = java.util.UUID.randomUUID().toString(), calendarId = calId, title = text.trim().ifBlank { "Event" },
                startMillis = start, endMillis = start + 86_399_000L, allDay = true,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
            return@launch
        }
        if (draft.isTask) {
            repo.ensureInbox()
            repo.createTask(listId = com.todocompanion.app.data.entity.ListEntity.INBOX_ID, title = draft.title, dueDate = draft.startMillis)
                .let { id -> repo.getTask(id)?.let { repo.saveTask(it.copy(isAllDay = draft.allDay)) } }
            toast("Added task “${draft.title}”.")
            return@launch
        }
        val e = com.todocompanion.app.data.entity.EventEntity(
            id = java.util.UUID.randomUUID().toString(), calendarId = calId, title = draft.title, location = draft.location,
            startMillis = draft.startMillis, endMillis = draft.endMillis, allDay = draft.allDay, rrule = draft.rrule,
            alertsMinutes = draft.alertsMinutes, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        repo.upsertEvent(e); com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, e)
    }

    fun importIcsEvents(uri: android.net.Uri, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val calId = ensureDefaultCalendar()
        val text = withContext(Dispatchers.IO) { runCatching { appCtx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull() }
        if (text == null) { toast("Couldn't read that file."); onDone(0); return@launch }
        val evs = com.todocompanion.app.domain.calendar.EventIcs.import(text, calId, zone)
        repo.upsertEvents(evs); evs.forEach { com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, it) }
        toast("Imported ${evs.size} event${if (evs.size == 1) "" else "s"}."); onDone(evs.size)
    }
    fun exportIcsEventsTo(uri: android.net.Uri, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val ics = com.todocompanion.app.domain.calendar.EventIcs.export(repo.eventsOnce(), zone)
        val ok = withContext(Dispatchers.IO) { runCatching { appCtx.contentResolver.openOutputStream(uri)?.use { it.write(ics.toByteArray()) }; true }.getOrDefault(false) }
        toast(if (ok) "Calendar exported (.ics)." else "Export failed."); onDone(ok)
    }
    fun exportIcsEventsToDownloads(onDone: (String?) -> Unit = {}) = viewModelScope.launch {
        val ics = com.todocompanion.app.domain.calendar.EventIcs.export(repo.eventsOnce(), zone)
        val loc = withContext(Dispatchers.IO) { com.todocompanion.app.util.FileExport.saveToDownloads(appCtx, "todocompanion-calendar.ics", "text/calendar", ics.toByteArray()) }
        toast(if (loc != null) "Saved to $loc" else "Export failed."); onDone(loc)
    }

    // ── R41 · the planner (auto-schedule, self-healing habits, templates, audit) ─────────────────────
    /** Greedy auto-scheduler: place flexible tasks (with estimates, not yet done) into today's free
     *  slots as linked time blocks. Returns how many blocks were created. */
    fun autoScheduleDay(day: Long, chunkMin: Int = 90, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val calId = ensureDefaultCalendar()
        val evs = repo.eventsOnce()
        // Protected windows count as busy walls; the calibration bias pads each block toward its real length.
        val occ = com.todocompanion.app.domain.calendar.CalendarEngine.onDay(evs, day, zone).toMutableList()
        protectedIntervalsFor(day).forEach { (s, e) ->
            occ.add(com.todocompanion.app.domain.calendar.CalendarEngine.Occurrence(
                com.todocompanion.app.data.entity.EventEntity(id = "protected", calendarId = calId, title = "Protected",
                    startMillis = s, endMillis = e, busy = true, createdAt = 0, updatedAt = 0), s, e))
        }
        val tasks = repo.allTasksOnce().filter { it.workspaceId == settings.value.activeWorkspaceId }
        val nowFloor = if (day == java.time.LocalDate.now(zone).toEpochDay()) System.currentTimeMillis() else null
        val bias = estimateBias.value?.medianRatio ?: 1.0
        val placements = com.todocompanion.app.domain.calendar.CalendarPlanner.autoSchedule(
            tasks, occ, day, settings.value.workStartHour, settings.value.workEndHour, chunkMin,
            fromMillis = nowFloor, biasMultiplier = bias.coerceIn(0.75, 1.5), zone = zone)
        if (placements.isEmpty()) { toast("No free slots today for your unscheduled tasks — try clearing an event or lowering estimates."); onDone(0); return@launch }
        val now = System.currentTimeMillis()
        val newEvents = placements.map { p ->
            com.todocompanion.app.data.entity.EventEntity(
                id = java.util.UUID.randomUUID().toString(), calendarId = calId,
                title = if (p.parts > 1) "${p.task.title} (${p.part}/${p.parts})" else p.task.title,
                startMillis = p.startMillis, endMillis = p.endMillis,
                linkedTaskId = p.task.id, createdAt = now, updatedAt = now)
        }
        repo.upsertEvents(newEvents)
        toast("Scheduled ${newEvents.size} block${if (newEvents.size == 1) "" else "s"} into today's gaps."); onDone(newEvents.size)
    }

    /** Self-healing habit block: drop a busy block for [habit] near its preferred time, sliding it to the
     *  nearest free slot so a new event never simply buries the window. */
    fun placeHabitBlock(habit: com.todocompanion.app.data.entity.HabitEntity, day: Long, durationMin: Int = 30, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val calId = ensureDefaultCalendar()
        val evs = repo.eventsOnce()
        val prefMin = habit.reminderTimes.split(",").mapNotNull { it.trim().toIntOrNull() }.minOrNull()
            ?: (settings.value.workStartHour * 60)
        val start = com.todocompanion.app.domain.calendar.CalendarPlanner.slideToFree(
            day, prefMin, durationMin, evs, settings.value.workStartHour, settings.value.workEndHour, zone)
        if (start == null) { toast("No free time to defend “${habit.name}” today."); onDone(false); return@launch }
        val now = System.currentTimeMillis()
        val e = com.todocompanion.app.data.entity.EventEntity(
            id = java.util.UUID.randomUUID().toString(), calendarId = calId,
            title = "${habit.emoji ?: "🔁"} ${habit.name}".trim(),
            startMillis = start, endMillis = start + durationMin * 60000L,
            colorArgb = habit.colorArgb, createdAt = now, updatedAt = now)
        repo.upsertEvent(e); com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, e)
        val hm = java.time.Instant.ofEpochMilli(start).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
        toast("Protected “${habit.name}” at $hm."); onDone(true)
    }

    /** Duplicate an event (a fresh one-off copy, one day later if it recurs — the fast "again" action). */
    fun duplicateEvent(id: String) = viewModelScope.launch {
        val e = repo.eventById(id) ?: return@launch
        val now = System.currentTimeMillis()
        val copy = e.copy(id = java.util.UUID.randomUUID().toString(), rrule = "", recurrenceParentId = null,
            recurrenceDate = 0, exDates = "", createdAt = now, updatedAt = now)
        repo.upsertEvent(copy); com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, copy)
        toast("Duplicated “${e.title}”.")
    }

    // Event templates (stored in settings JSON) ------------------------------------------------------
    val eventTemplates: StateFlow<List<com.todocompanion.app.domain.calendar.EventTemplate>> =
        settings.map { com.todocompanion.app.domain.calendar.EventTemplates.parse(it.eventTemplatesJson) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun saveEventTemplate(t: com.todocompanion.app.domain.calendar.EventTemplate) = viewModelScope.launch {
        val list = com.todocompanion.app.domain.calendar.EventTemplates.upsert(eventTemplates.value, t)
        repo.saveSettings(settings.value.copy(eventTemplatesJson = com.todocompanion.app.domain.calendar.EventTemplates.encode(list)))
    }
    fun deleteEventTemplate(id: String) = viewModelScope.launch {
        val list = com.todocompanion.app.domain.calendar.EventTemplates.remove(eventTemplates.value, id)
        repo.saveSettings(settings.value.copy(eventTemplatesJson = com.todocompanion.app.domain.calendar.EventTemplates.encode(list)))
    }
    /** Drop a template onto the calendar at [startMillis]. */
    fun applyEventTemplate(t: com.todocompanion.app.domain.calendar.EventTemplate, startMillis: Long) = viewModelScope.launch {
        val calId = t.calendarId.ifBlank { ensureDefaultCalendar() }
        val now = System.currentTimeMillis()
        val e = com.todocompanion.app.data.entity.EventEntity(
            id = java.util.UUID.randomUUID().toString(), calendarId = calId, title = t.title,
            location = t.location, startMillis = startMillis, endMillis = startMillis + t.durationMin.coerceAtLeast(5) * 60000L,
            colorArgb = t.colorArgb, alertsMinutes = t.alertsMinutes, busy = t.busy, createdAt = now, updatedAt = now)
        repo.upsertEvent(e); com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, e)
        toast("Added “${t.title}”.")
    }

    fun setSecondaryZone(zoneId: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(secondaryZoneId = zoneId.trim())) }

    /** Self-calibrating estimate signal: median actual/planned ratio across tasks you both estimated and
     *  tracked. Null until there are ≥3 samples. Surfaced in the task editor's estimate field. */
    val estimateBias: StateFlow<com.todocompanion.app.domain.calendar.CalendarPlanner.EstimateBias?> =
        combine(tasks, timeEntries) { t, e -> com.todocompanion.app.domain.calendar.CalendarPlanner.estimateBias(t, e) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Remembered travel minutes per place (for the auto travel buffer). */
    val travelTimes: StateFlow<Map<String, Int>> =
        settings.map { com.todocompanion.app.domain.calendar.TravelTimes.parse(it.travelTimesJson) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Reserve a "leave by" travel block ending when [eventStart] begins, and remember the figure for [place]. */
    fun addTravelBuffer(eventStart: Long, minutes: Int, place: String, calendarId: String) = viewModelScope.launch {
        if (minutes <= 0) return@launch
        val calId = calendarId.ifBlank { ensureDefaultCalendar() }
        val now = System.currentTimeMillis()
        val e = com.todocompanion.app.data.entity.EventEntity(
            id = java.util.UUID.randomUUID().toString(), calendarId = calId,
            title = "🚗 Travel${if (place.isNotBlank()) " to ${place.trim()}" else ""}",
            startMillis = eventStart - minutes * 60000L, endMillis = eventStart,
            alertsMinutes = "0", busy = true, createdAt = now, updatedAt = now)
        repo.upsertEvent(e); com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, e)
        val map = com.todocompanion.app.domain.calendar.TravelTimes.remember(travelTimes.value, place, minutes)
        repo.saveSettings(settings.value.copy(travelTimesJson = com.todocompanion.app.domain.calendar.TravelTimes.encode(map)))
    }

    /** The weekly time-audit / retrospective — assembled off the four local data types. */
    suspend fun buildWeeklyAudit(weekStartDay: Long): com.todocompanion.app.domain.calendar.CalendarPlanner.Audit {
        val evs = repo.eventsOnce()
        val entries = repo.timeEntriesOnce()
        val tasks = repo.allTasksOnce()
        // Habit adherence over the week: checked habit-days meeting target ÷ (habits × 7).
        val ws = settings.value.activeWorkspaceId
        val habits = repo.getHabitsOnce().filter { it.workspaceId == ws && !it.archived }
        val targetById = habits.associate { it.id to it.targetPerDay.coerceAtLeast(1) }
        val adherence = if (habits.isEmpty()) null else {
            val checks = repo.getHabitCheckinsOnce().count { c ->
                c.epochDay in weekStartDay until weekStartDay + 7 && targetById.containsKey(c.habitId) && c.count >= (targetById[c.habitId] ?: 1)
            }
            ((checks * 100f) / (habits.size * 7)).toInt().coerceIn(0, 100)
        }
        val audit = com.todocompanion.app.domain.calendar.CalendarPlanner.weeklyAudit(
            evs, entries, tasks, weekStartDay, settings.value.workStartHour, settings.value.workEndHour, adherence, zone)
        // Moat — the correlated retrospective: tie habit consistency to meeting-load, a link only a unified
        // events+habits store can see. Compare habit completions on busy days vs quiet days this week.
        val checkins = repo.getHabitCheckinsOnce()
        val busyDays = audit.bookedMinByDay.filter { it.value >= 180 }.keys
        val quietDays = (weekStartDay until weekStartDay + 7).filter { it !in busyDays }.toSet()
        val extra = ArrayList(audit.advice)
        if (habits.isNotEmpty() && busyDays.size >= 2 && quietDays.size >= 2) {
            fun rate(days: Set<Long>) = if (days.isEmpty()) 0f else checkins.count { it.epochDay in days && it.count >= 1 }.toFloat() / (habits.size * days.size)
            val busyRate = rate(busyDays); val quietRate = rate(quietDays)
            if (quietRate > 0 && busyRate < quietRate * 0.7f)
                extra.add(0, "Your habits slip on busy days — ${(busyRate * 100).toInt()}% done on heavy days vs ${(quietRate * 100).toInt()}% on lighter ones. Defend those windows next week.")
        }
        return audit.copy(advice = extra)
    }

    // ── R42 · next-horizon planner (reflow, defragment, routines, protected windows, contexts, locks) ─
    /** Protected life-windows materialised as busy intervals on [day] (walls for the scheduler). */
    private fun protectedIntervalsFor(day: Long): List<Pair<Long, Long>> {
        val dow = java.time.LocalDate.ofEpochDay(day).dayOfWeek.value
        val base = java.time.LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
        return com.todocompanion.app.domain.calendar.ProtectedWindows.parse(settings.value.protectedWindowsJson)
            .filter { it.appliesTo(dow) }.map { (base + it.startMin * 60000L) to (base + it.endMin * 60000L) }
    }

    /** Targeted reflow ("heal conflicts"): only task-linked blocks that now overlap a fixed event slide. */
    fun reflowDay(day: Long, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val evs = repo.eventsOnce()
        val occ = com.todocompanion.app.domain.calendar.CalendarEngine.onDay(evs, day, zone)
        val fixed = occ.filter { it.event.busy && !it.event.allDay && it.event.linkedTaskId == null }.map { it.startMillis to it.endMillis } + protectedIntervalsFor(day)
        val flexible = occ.filter { it.event.linkedTaskId != null && !it.event.allDay }.map { Triple(it.event.id, it.startMillis, it.endMillis) }
        val moves = com.todocompanion.app.domain.calendar.CalendarPlanner.reflow(flexible, fixed, day, settings.value.workStartHour, settings.value.workEndHour, zone)
        applyMoves(moves); toast(if (moves.isEmpty()) "Nothing to heal — no task block clashes." else "Reflowed ${moves.size} block${if (moves.size == 1) "" else "s"} around your events."); onDone(moves.size)
    }

    /** Defragment the day: re-pack task blocks back-to-back to merge scattered gaps into focus. */
    fun defragmentDay(day: Long, onDone: (Int) -> Unit = {}) = viewModelScope.launch {
        val evs = repo.eventsOnce()
        val occ = com.todocompanion.app.domain.calendar.CalendarEngine.onDay(evs, day, zone)
        val fixed = occ.filter { it.event.busy && !it.event.allDay && it.event.linkedTaskId == null }.map { it.startMillis to it.endMillis } + protectedIntervalsFor(day)
        val nowFloor = if (day == java.time.LocalDate.now(zone).toEpochDay()) System.currentTimeMillis() else null
        val flexible = occ.filter { it.event.linkedTaskId != null && !it.event.allDay }
            .filter { nowFloor == null || it.startMillis >= nowFloor }.map { Triple(it.event.id, it.startMillis, it.endMillis) }
        val moves = com.todocompanion.app.domain.calendar.CalendarPlanner.defragment(flexible, fixed, day, settings.value.workStartHour, settings.value.workEndHour, zone)
        applyMoves(moves); toast(if (moves.isEmpty()) "Your day is already compact." else "Merged ${moves.size} block${if (moves.size == 1) "" else "s"} into a tighter focus window."); onDone(moves.size)
    }

    private suspend fun applyMoves(moves: List<com.todocompanion.app.domain.calendar.CalendarPlanner.Move>) {
        val now = System.currentTimeMillis()
        moves.forEach { m ->
            repo.eventById(m.id)?.let { e ->
                com.todocompanion.app.reminders.AlarmScheduler.cancelEventAlerts(appCtx, e)
                val ne = e.copy(startMillis = m.newStart, endMillis = m.newEnd, updatedAt = now)
                repo.upsertEvent(ne); com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, ne)
            }
        }
    }

    /** Paint-to-dates: duplicate an event (as one-offs) onto a set of epoch-days, keeping its time-of-day. */
    fun paintEventToDates(eventId: String, days: List<Long>) = viewModelScope.launch {
        val e = repo.eventById(eventId) ?: return@launch
        val srcDay = java.time.Instant.ofEpochMilli(e.startMillis).atZone(zone).toLocalDate().toEpochDay()
        val dur = e.endMillis - e.startMillis
        val now = System.currentTimeMillis(); var n = 0
        days.filter { it != srcDay }.forEach { d ->
            val newStart = java.time.Instant.ofEpochMilli(e.startMillis).atZone(zone)
                .withYear(java.time.LocalDate.ofEpochDay(d).year).withDayOfYear(java.time.LocalDate.ofEpochDay(d).dayOfYear)
                .toInstant().toEpochMilli()
            val copy = e.copy(id = java.util.UUID.randomUUID().toString(), startMillis = newStart, endMillis = newStart + dur,
                rrule = "", recurrenceParentId = null, recurrenceDate = 0, exDates = "", createdAt = now, updatedAt = now)
            repo.upsertEvent(copy); com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, copy); n++
        }
        toast(if (n > 0) "Copied “${e.title}” onto $n date${if (n == 1) "" else "s"}." else "No dates to copy onto.")
    }

    /** One-tap actual-logging: turn a past time block into a tracked entry (planned → actual). */
    fun logActualForBlock(eventId: String) = viewModelScope.launch {
        val e = repo.eventById(eventId) ?: return@launch
        val task = e.linkedTaskId?.let { repo.getTask(it) }
        val actId = task?.defaultActivityId?.takeIf { id -> timeActivities.value.any { it.id == id && !it.archived } } ?: repo.ensureTaskActivity()
        repo.addManualTimeEntry(actId, e.startMillis, minOf(e.endMillis, System.currentTimeMillis()), e.title, taskId = e.linkedTaskId)
        toast("Logged “${e.title}” as tracked time.")
    }

    // Day routines (settings JSON) --------------------------------------------------------------------
    val dayRoutines: StateFlow<List<com.todocompanion.app.domain.calendar.DayRoutine>> =
        settings.map { com.todocompanion.app.domain.calendar.DayRoutines.parse(it.dayRoutinesJson) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Capture today's timed (non-all-day) events as a reusable routine of offsets from the working start. */
    fun saveDayRoutineFromDay(name: String, day: Long) = viewModelScope.launch {
        val n = name.trim(); if (n.isBlank()) return@launch
        val base = java.time.LocalDate.ofEpochDay(day).atStartOfDay(zone).plusHours(settings.value.workStartHour.toLong()).toInstant().toEpochMilli()
        val blocks = com.todocompanion.app.domain.calendar.CalendarEngine.onDay(repo.eventsOnce(), day, zone)
            .filter { !it.event.allDay }.sortedBy { it.startMillis }.map { o ->
                com.todocompanion.app.domain.calendar.RoutineBlock(
                    title = o.event.title, startMin = (((o.startMillis - base) / 60000L)).toInt(),
                    durationMin = o.durationMin().toInt().coerceAtLeast(5), colorArgb = o.event.colorArgb)
            }
        if (blocks.isEmpty()) { toast("No timed events today to save as a routine."); return@launch }
        val list = com.todocompanion.app.domain.calendar.DayRoutines.upsert(dayRoutines.value,
            com.todocompanion.app.domain.calendar.DayRoutine(java.util.UUID.randomUUID().toString(), n, blocks = blocks))
        repo.saveSettings(settings.value.copy(dayRoutinesJson = com.todocompanion.app.domain.calendar.DayRoutines.encode(list)))
        toast("Saved routine “$n”.")
    }
    fun applyDayRoutine(routineId: String, day: Long) = viewModelScope.launch {
        val r = dayRoutines.value.firstOrNull { it.id == routineId } ?: return@launch
        val calId = ensureDefaultCalendar()
        val base = java.time.LocalDate.ofEpochDay(day).atStartOfDay(zone).plusHours(settings.value.workStartHour.toLong()).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val evs = r.blocks.map { b ->
            val s = base + b.startMin * 60000L
            com.todocompanion.app.data.entity.EventEntity(
                id = java.util.UUID.randomUUID().toString(), calendarId = calId, title = b.title,
                startMillis = s, endMillis = s + b.durationMin * 60000L, colorArgb = b.colorArgb, createdAt = now, updatedAt = now)
        }
        repo.upsertEvents(evs); evs.forEach { com.todocompanion.app.reminders.AlarmScheduler.scheduleEventAlerts(appCtx, it) }
        toast("Laid out “${r.name}” — ${evs.size} block${if (evs.size == 1) "" else "s"}.")
    }
    fun deleteDayRoutine(id: String) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(dayRoutinesJson = com.todocompanion.app.domain.calendar.DayRoutines.encode(com.todocompanion.app.domain.calendar.DayRoutines.remove(dayRoutines.value, id))))
    }

    // Protected windows -------------------------------------------------------------------------------
    val protectedWindows: StateFlow<List<com.todocompanion.app.domain.calendar.ProtectedWindow>> =
        settings.map { com.todocompanion.app.domain.calendar.ProtectedWindows.parse(it.protectedWindowsJson) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun saveProtectedWindow(name: String, startMin: Int, endMin: Int, days: List<Int>) = viewModelScope.launch {
        val n = name.trim(); if (n.isBlank() || endMin <= startMin) return@launch
        val list = com.todocompanion.app.domain.calendar.ProtectedWindows.upsert(protectedWindows.value,
            com.todocompanion.app.domain.calendar.ProtectedWindow(java.util.UUID.randomUUID().toString(), n, startMin, endMin, days))
        repo.saveSettings(settings.value.copy(protectedWindowsJson = com.todocompanion.app.domain.calendar.ProtectedWindows.encode(list)))
    }
    fun deleteProtectedWindow(id: String) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(protectedWindowsJson = com.todocompanion.app.domain.calendar.ProtectedWindows.encode(com.todocompanion.app.domain.calendar.ProtectedWindows.remove(protectedWindows.value, id))))
    }

    // Plan lock + lunar overlay -----------------------------------------------------------------------
    fun isPlanLocked(day: Long): Boolean = settings.value.planLockedDaysCsv.split(",").mapNotNull { it.trim().toLongOrNull() }.contains(day)
    fun setPlanLocked(day: Long, locked: Boolean) = viewModelScope.launch {
        val cur = settings.value.planLockedDaysCsv.split(",").mapNotNull { it.trim().toLongOrNull() }.toMutableSet()
        if (locked) cur.add(day) else cur.remove(day)
        repo.saveSettings(settings.value.copy(planLockedDaysCsv = cur.sorted().joinToString(",")))
        toast(if (locked) "Plan locked — auto-schedule will only fill gaps now." else "Plan unlocked.")
    }
    fun setLunarOverlay(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(lunarOverlay = on)) }

    // Context modes: a saved set of calendars; activating one shows those and hides the rest (Fantastical
    // Calendar Sets, minus the geofence). Reuses the existing per-calendar visibility — no new filtering.
    val calContexts: StateFlow<List<com.todocompanion.app.domain.calendar.CalContext>> =
        settings.map { com.todocompanion.app.domain.calendar.CalContexts.parse(it.calContextsJson) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun saveContext(name: String, calendarIds: List<String>) = viewModelScope.launch {
        val n = name.trim(); if (n.isBlank()) return@launch
        val list = com.todocompanion.app.domain.calendar.CalContexts.upsert(calContexts.value,
            com.todocompanion.app.domain.calendar.CalContext(java.util.UUID.randomUUID().toString(), n, calendarIds = calendarIds))
        repo.saveSettings(settings.value.copy(calContextsJson = com.todocompanion.app.domain.calendar.CalContexts.encode(list)))
    }
    fun deleteContext(id: String) = viewModelScope.launch {
        val next = if (settings.value.activeContextId == id) "" else settings.value.activeContextId
        repo.saveSettings(settings.value.copy(
            calContextsJson = com.todocompanion.app.domain.calendar.CalContexts.encode(com.todocompanion.app.domain.calendar.CalContexts.remove(calContexts.value, id)),
            activeContextId = next))
    }
    fun activateContext(id: String) = viewModelScope.launch {
        val ctx = calContexts.value.firstOrNull { it.id == id }
        if (ctx == null) { repo.saveSettings(settings.value.copy(activeContextId = "")); repo.eventCalendarsOnce().forEach { repo.upsertEventCalendar(it.copy(visible = true)) }; return@launch }
        repo.eventCalendarsOnce().forEach { repo.upsertEventCalendar(it.copy(visible = it.id in ctx.calendarIds)) }
        repo.saveSettings(settings.value.copy(activeContextId = id))
        toast("Context “${ctx.name}” — showing ${ctx.calendarIds.size} calendar${if (ctx.calendarIds.size == 1) "" else "s"}.")
    }

    fun skipHabitDay(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, reason: String = "") = viewModelScope.launch {
        if (beforeStart(h, epochDay)) return@launch
        repo.skipDay(h.id, epochDay, reason); refreshHabitWidgets()
    }
    /** N6: log a break-habit slip with an optional trigger (kept in the day's note for a trigger breakdown). */
    fun logSlip(h: com.todocompanion.app.data.entity.HabitEntity, trigger: String) = viewModelScope.launch {
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val existing = repo.getHabitCheckinsOnce().firstOrNull { it.habitId == h.id && it.epochDay == today }
        val count = (existing?.count ?: 0) + 1
        val note = (existing?.reason?.takeIf { it.isNotBlank() }?.plus("; ") ?: "") + trigger.trim().ifBlank { "slip" }
        repo.setDay(h.id, today, count, "done", note)
        refreshHabitWidgets()
    }
    fun clearHabitDay(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long) = viewModelScope.launch {
        repo.clearCheckin(h.id, epochDay); refreshHabitWidgets()
    }
    /** Write a whole day from the per-day editor: value, done/skip, and a free-text note. */
    fun setHabitDay(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, count: Int, status: String, note: String) = viewModelScope.launch {
        if (beforeStart(h, epochDay)) return@launch
        repo.setDay(h.id, epochDay, count, status, note); refreshHabitWidgets()
    }
    // ---- Tier K ----
    /** K2: spend one earned freeze to protect a missed day. */
    fun spendHabitFreeze(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val ok = repo.spendFreeze(h.id, epochDay); refreshHabitWidgets(); onDone(ok)
    }
    /** K5: attach a photo to a day — the picked image is downscaled and copied into app storage. */
    fun setHabitPhoto(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, uri: Uri?) = viewModelScope.launch {
        if (uri == null) { repo.setCheckinPhoto(h.id, epochDay, null); refreshHabitWidgets(); return@launch }
        val path = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = appCtx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                val maxDim = 1280
                val scale = minOf(1f, maxDim.toFloat() / maxOf(src.width, src.height).coerceAtLeast(1))
                val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, out)
                val dir = java.io.File(appCtx.filesDir, "habit_photos").apply { mkdirs() }
                val f = java.io.File(dir, UUID.randomUUID().toString() + ".jpg")
                f.writeBytes(out.toByteArray())
                f.absolutePath
            }.getOrNull()
        }
        if (path != null) { repo.setCheckinPhoto(h.id, epochDay, path); refreshHabitWidgets() } else toast("Couldn't read that image")
    }
    fun setHabitPaused(h: com.todocompanion.app.data.entity.HabitEntity, paused: Boolean) = viewModelScope.launch {
        repo.setHabitPaused(h.id, paused); com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo); refreshHabitWidgets()
    }
    fun pauseAllHabits(paused: Boolean) = viewModelScope.launch {
        repo.pauseAllHabits(settings.value.activeWorkspaceId, paused); com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo); refreshHabitWidgets()
    }
    private fun refreshHabitWidgets() {
        com.todocompanion.app.widget.HabitsWidget.refresh(appCtx)
        com.todocompanion.app.widget.HabitStatsWidget.refresh(appCtx)
    }

    // ---------- deep-work coach (H4) ----------
    data class DeepWorkStatus(val todayMin: Int, val goalMin: Int, val streakDays: Int, val best: TaskEntity?, val bestBlockMin: Int)

    /** Today's focused minutes against the daily goal, the current streak of goal-met days, and the
     *  single best task to sink a block into next (with a suggested block length from its estimate). */
    fun deepWorkStatus(): DeepWorkStatus {
        val goal = settings.value.deepWorkGoalMin.coerceAtLeast(1)
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val byDay = focusMinutesByDay()
        val todayMin = byDay[today] ?: 0
        // Count consecutive goal-met days back from today; today counting only once it's already met,
        // so a day still in progress never breaks the streak.
        var streak = 0
        var d = if (todayMin >= goal) today else today - 1
        while ((byDay[d] ?: 0) >= goal) { streak++; d-- }
        val best = topDoNext()
        val blockMin = (best?.estimateMin ?: best?.estimateMax ?: best?.durationMin ?: 25).coerceIn(10, 90)
        return DeepWorkStatus(todayMin, goal, streak, best, blockMin)
    }

    // ---------- focus  (a MODE of time tracking, never a second system) ----------
    // Robust integration (Round 17): pressing Start in Focus does NOT open a separate stopwatch — it starts
    // a running interval on the ONE timeline tagged kind="focus". So a focus block is *tracked time* the
    // instant it begins (it shows in the running-timer bar and the calendar), Stop finalizes it (crediting
    // any linked habit through the same finalizeEntry path a manual timer uses), and EVERY focus statistic
    // below is derived from those kind="focus" intervals. There is no FocusSession double-write any more,
    // so "time tracked" and "time focused" can never diverge into two contradictory totals.
    val focusTargetMin = MutableStateFlow(25)   // countdown length in minutes; 0 = open-ended stopwatch

    /** The single running focus interval, if a focus session is live right now (drives the ring). */
    val runningFocus: StateFlow<com.todocompanion.app.data.entity.TimeEntryEntity?> =
        timeEntries.map { list -> list.firstOrNull { it.running && it.kind == "focus" } }.state(null)

    /** Focus intervals as synthetic FocusSessionEntity rows (day, minutes, taskId) computed from the one
     *  timeline, so the stats / momentum / digest screens read the SAME source as the Time reports — never
     *  a second table. Legacy persisted FocusSessions (written before this unification, when Time was off)
     *  are unioned in so old history isn't lost. A running interval is clamped to now. */
    fun focusViews(): List<com.todocompanion.app.data.entity.FocusSessionEntity> {
        val now = System.currentTimeMillis()
        val fromTimeline = timeEntries.value.asSequence().filter { it.kind == "focus" }.map {
            com.todocompanion.app.data.entity.FocusSessionEntity(
                id = it.id,
                epochDay = java.time.Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate().toEpochDay(),
                startMillis = it.startMillis,
                minutes = (((it.endMillis ?: now) - it.startMillis) / 60_000L).toInt().coerceAtLeast(0),
                kind = "focus",
                taskId = it.taskId,
            )
        }.toList()
        // Days that already have a timeline focus interval are fully represented there; only fold in legacy
        // sessions from days with NO timeline focus, so a mirrored old session is never double-counted.
        val timelineDays = fromTimeline.mapTo(HashSet()) { it.epochDay }
        val legacy = focusSessions.value.filter { it.epochDay !in timelineDays }
        return fromTimeline + legacy
    }
    /** Focused minutes per calendar day, from kind="focus" intervals (a running one clamped to now). */
    fun focusMinutesByDay(): Map<Long, Int> =
        focusViews().groupBy { it.epochDay }.mapValues { e -> e.value.sumOf { it.minutes } }

    /** Start a focus session against [activityId] (or the task's / habit's linked activity, else a generic
     *  "Focus" activity). [remainingSec] lets Resume schedule the chime for exactly the time still left. */
    fun startFocusSession(
        activityId: String?, targetMin: Int, remainingSec: Int = targetMin * 60,
        taskId: String? = null, habitId: String? = null,
    ) = viewModelScope.launch {
        focusTargetMin.value = targetMin.coerceAtLeast(0)
        val actId = activityId
            ?: taskId?.let { tid -> tasks.value.firstOrNull { it.id == tid }?.defaultActivityId }
            ?: habitId?.let { hid -> habits.value.firstOrNull { it.id == hid }?.timeActivityId }
            ?: repo.ensureFocusActivity()
        repo.startTimeTracking(actId, taskId, habitId, stopFirst = !settings.value.multiTimer, kind = "focus")
        com.todocompanion.app.reminders.AutomationRunner.onStart(appCtx, repo, actId)
        if (targetMin > 0 && remainingSec > 0)
            com.todocompanion.app.reminders.AlarmScheduler.scheduleFocusDone(appCtx, System.currentTimeMillis() + remainingSec * 1000L)
        refreshTimeWidget()
    }
    /** Stop the running focus interval (finalize + credit any linked habit) and cancel its chime. Only ever
     *  stops a kind="focus" entry, so a paused-focus Finish can never accidentally stop a manual timer. */
    fun stopFocus() = viewModelScope.launch {
        val id = timeEntries.value.firstOrNull { it.running && it.kind == "focus" }?.id
        if (id != null) { repo.stopTimeEntry(id); refreshHabitWidgets(); refreshTimeWidget() }
        com.todocompanion.app.reminders.AlarmScheduler.cancelFocusDone(appCtx)
    }

    // ---------- saved filters ----------
    fun createFilter(name: String) = viewModelScope.launch {
        val id = repo.createFilter(name.trim(), settings.value.activeWorkspaceId)
        currentView.value = ViewRef.FilterView(id)
    }
    fun saveFilter(f: com.todocompanion.app.data.entity.FilterEntity) = viewModelScope.launch { repo.upsertFilter(f) }
    fun deleteFilter(f: com.todocompanion.app.data.entity.FilterEntity) = viewModelScope.launch {
        repo.deleteFilter(f.id)
        if (currentView.value == ViewRef.FilterView(f.id)) currentView.value = ViewRef.Smart(SmartKind.TODAY)
    }
    fun saveList(l: ListEntity) = viewModelScope.launch { repo.saveList(l) }
    fun deleteList(id: String) = viewModelScope.launch { repo.deleteList(id) }

    /** Convert a list into a folder, preserving its tasks in a same-named list inside it. */
    fun convertListToFolder(list: ListEntity) = viewModelScope.launch {
        val folderId = repo.createFolder(list.name, parentId = list.folderId)
        val newListId = repo.createList(list.name, folderId, list.colorArgb)
        tasks.value.filter { it.listId == list.id && it.parentId == null }.forEach { repo.moveToList(it.id, newListId) }
        repo.deleteList(list.id)
        if (currentView.value == ViewRef.ListView(list.id)) select(ViewRef.ListView(newListId))
    }

    /** Convert an empty folder into a list. Returns false (no-op) if the folder still has contents. */
    fun convertFolderToList(folder: FolderEntity): Boolean {
        val hasChildren = folders.value.any { it.parentId == folder.id } || lists.value.any { it.folderId == folder.id }
        if (hasChildren) return false
        viewModelScope.launch {
            val id = repo.createList(folder.name, folder.parentId, null)
            repo.deleteFolder(folder.id)
            select(ViewRef.ListView(id))
        }
        return true
    }
    fun moveListOrder(l: ListEntity, dir: Int) = viewModelScope.launch { repo.moveListOrder(l, dir) }
    /** Persist a drag reorder of sibling lists by writing each id's index as its sortOrder. */
    fun setListOrder(orderedIds: List<String>) = viewModelScope.launch {
        val byId = lists.value.associateBy { it.id }
        orderedIds.forEachIndexed { i, id -> byId[id]?.let { if (it.sortOrder != i.toDouble()) repo.saveList(it.copy(sortOrder = i.toDouble())) } }
    }
    /** Persist a drag reorder of sibling folders. */
    fun setFolderOrder(orderedIds: List<String>) = viewModelScope.launch {
        val byId = folders.value.associateBy { it.id }
        orderedIds.forEachIndexed { i, id -> byId[id]?.let { if (it.sortOrder != i.toDouble()) repo.saveFolder(it.copy(sortOrder = i.toDouble())) } }
    }
    fun moveFolderOrder(f: FolderEntity, dir: Int) = viewModelScope.launch { repo.moveFolderOrder(f, dir) }
    fun moveListToFolder(listId: String, folderId: String?) = viewModelScope.launch { repo.moveListToFolder(listId, folderId) }
    fun moveFolderToParent(folderId: String, parentId: String?) = viewModelScope.launch { repo.moveFolderToParent(folderId, parentId) }

    // ---------- row actions (flag / star / priority / swipes) ----------
    fun toggleStar(t: TaskEntity) = viewModelScope.launch { repo.saveTask(t.copy(star = !t.star)) }
    fun setPriority(t: TaskEntity, level: PriorityLevel) = viewModelScope.launch { repo.saveTask(t.copy(importance = level.importance, urgency = level.urgency)) }
    /** Drag-to-move in the Eisenhower matrix: set importance/urgency so [t] lands in quadrant [q]
     *  (0 UI, 1 NI, 2 UN, 3 NN) under the current thresholds. */
    fun setMatrixQuadrant(t: TaskEntity, q: Int, impThreshold: Int, urgThreshold: Int) = viewModelScope.launch {
        val important = q == 0 || q == 1
        val urgent = q == 0 || q == 2
        val imp = if (important) 5 else (impThreshold - 1).coerceIn(1, 5)
        val urg = if (urgent) 5 else (urgThreshold - 1).coerceIn(1, 5)
        if (t.importance != imp || t.urgency != urg) repo.saveTask(t.copy(importance = imp, urgency = urg))
    }
    fun setDuration(taskId: String, minutes: Int) = viewModelScope.launch { repo.getTask(taskId)?.let { repo.saveTask(it.copy(durationMin = minutes.coerceIn(15, 24 * 60))) } }
    /** Shift a task's start and due dates by [days] (Timeline drag-to-reschedule; preserves span). */
    fun shiftTaskDays(taskId: String, days: Int) = viewModelScope.launch {
        if (days == 0) return@launch
        repo.getTask(taskId)?.let { t ->
            val d = days * 86_400_000L
            repo.saveTask(t.copy(startDate = t.startDate?.plus(d), dueDate = t.dueDate?.plus(d)))
        }
    }
    /** Move a task's due time to [minute] of [day] (time-blocking drag on the calendar). */
    fun rescheduleToMinute(taskId: String, day: java.time.LocalDate, minute: Int) = viewModelScope.launch {
        repo.getTask(taskId)?.let { t ->
            val ms = day.atStartOfDay(zone).plusMinutes(minute.toLong().coerceIn(0, 1439)).toInstant().toEpochMilli()
            repo.saveTask(t.copy(dueDate = ms, isAllDay = false))
        }
    }
    fun togglePin(t: TaskEntity) = viewModelScope.launch { repo.saveTask(t.copy(pinned = !t.pinned)) }
    fun toggleNote(t: TaskEntity) = viewModelScope.launch { repo.saveTask(t.copy(isNote = !t.isNote)) }
    fun duplicateTask(t: TaskEntity) = viewModelScope.launch {
        val nowMs = System.currentTimeMillis()
        repo.saveTask(t.copy(id = UUID.randomUUID().toString(), title = t.title + " (copy)", completed = false, completedAt = null,
            abandoned = false, trashed = false, sortOrder = t.sortOrder + 0.0001, createdAt = nowMs, updatedAt = nowMs))
    }
    /**
     * Detach the current occurrence of a recurring task so it can be edited without touching the
     * series (B5 single-instance edit). The viewed task loses its rule (becomes a one-off you're free
     * to change); a fresh copy carries the series forward to the next occurrence.
     */
    fun detachOccurrence(t: TaskEntity, onSeries: (String) -> Unit = {}) = viewModelScope.launch {
        if (t.rrule.isNullOrBlank() || t.dueDate == null) return@launch
        val (nextDue, newRule) = com.todocompanion.app.domain.recurrence.Recurrence.advance(t.rrule!!, t.dueDate!!, zone, System.currentTimeMillis())
        // This occurrence keeps its date but drops the recurrence — now a standalone task.
        repo.saveTask(t.copy(rrule = null))
        if (nextDue != null) {
            val nowMs = System.currentTimeMillis()
            val delta = nextDue - t.dueDate!!
            val seriesId = UUID.randomUUID().toString()
            repo.saveTask(t.copy(id = seriesId, dueDate = nextDue, startDate = t.startDate?.plus(delta),
                deadlineDate = t.deadlineDate?.plus(delta), rrule = newRule, completed = false, completedAt = null,
                createdAt = nowMs, updatedAt = nowMs, sortOrder = t.sortOrder + 0.0001))
            onSeries(seriesId)
        }
    }
    /** Tap-to-flag on a row: cycle through the ordered flags, then back to none (MLO-style). */
    fun cycleFlag(t: TaskEntity) = viewModelScope.launch {
        val ordered = flags.value.sortedBy { it.sortOrder }
        if (ordered.isEmpty()) return@launch
        val idx = ordered.indexOfFirst { it.id == t.flagId }
        val next = when {
            t.flagId == null -> ordered.first().id
            idx < 0 || idx == ordered.lastIndex -> null
            else -> ordered[idx + 1].id
        }
        repo.setTaskFlag(t, next)
    }
    fun setFlag(t: TaskEntity, flagId: String?) = viewModelScope.launch { repo.setTaskFlag(t, flagId) }

    // ---------- flag management ----------
    fun createFlag(name: String, colorArgb: Long, icon: String = "bookmark") = viewModelScope.launch { repo.createFlag(name, colorArgb, icon) }
    fun updateFlag(f: FlagEntity) = viewModelScope.launch {
        repo.upsertFlag(f)
        // Keep the colour cache on tasks wearing this flag in sync with the edited colour.
        tasks.value.filter { it.flagId == f.id && it.flagColorArgb != f.colorArgb }.forEach { repo.saveTask(it.copy(flagColorArgb = f.colorArgb)) }
    }
    fun deleteFlag(id: String) = viewModelScope.launch { repo.deleteFlag(id) }
    fun moveFlag(f: FlagEntity, dir: Int) = viewModelScope.launch { repo.moveFlagOrder(f, dir) }

    // ---------- templates ----------
    fun saveAsTemplate(taskId: String, name: String) = viewModelScope.launch { repo.saveAsTemplate(taskId, name) }

    /** Repeated task shapes worth turning into a template (G5). */
    data class TemplateSuggestion(val title: String, val count: Int, val exampleId: String)
    fun suggestedTemplates(minCount: Int = 3): List<TemplateSuggestion> {
        val existing = templates.value.map { it.name.trim().lowercase() }.toSet()
        return tasks.value.asSequence()
            .filter { !it.trashed && !it.isNote && it.title.isNotBlank() }
            .groupBy { it.title.trim().lowercase() }
            .filter { (norm, list) -> list.size >= minCount && norm !in existing }
            .map { (_, list) ->
                val newest = list.maxByOrNull { it.createdAt }!!
                TemplateSuggestion(newest.title.trim(), list.size, newest.id)
            }
            .sortedByDescending { it.count }
            .take(5)
    }
    fun deleteTemplate(id: String) = viewModelScope.launch { repo.deleteTemplate(id) }
    fun renameTemplate(id: String, name: String) = viewModelScope.launch { repo.renameTemplate(id, name) }
    /** Drop a template into the current view's list (or Inbox), opening its new root if requested. */
    fun insertTemplateHere(templateId: String, onDone: (String?) -> Unit = {}) = viewModelScope.launch {
        val (listId, folderId) = resolveAddTarget()
        val id = repo.instantiateTemplate(templateId, listId, folderId = folderId)
        onDone(id)
    }
    fun cyclePriority(t: TaskEntity) = viewModelScope.launch {
        val next = when (PriorityLevel.from(t.importance, t.urgency)) {
            PriorityLevel.NONE -> PriorityLevel.LOW
            PriorityLevel.LOW -> PriorityLevel.MEDIUM
            PriorityLevel.MEDIUM -> PriorityLevel.HIGH
            PriorityLevel.HIGH -> PriorityLevel.NONE
        }
        repo.saveTask(t.copy(importance = next.importance, urgency = next.urgency))
    }
    /** Apply a configured swipe action. EDIT returns false so the caller can open the editor. */
    fun applyAction(action: com.todocompanion.app.domain.SwipeAction, t: TaskEntity): Boolean {
        when (action) {
            com.todocompanion.app.domain.SwipeAction.COMPLETE -> toggleComplete(t)
            com.todocompanion.app.domain.SwipeAction.TRASH -> trash(t)
            com.todocompanion.app.domain.SwipeAction.STAR -> toggleStar(t)
            com.todocompanion.app.domain.SwipeAction.WONT_DO -> setAbandoned(t, !t.abandoned)
            com.todocompanion.app.domain.SwipeAction.CYCLE_PRIORITY -> cyclePriority(t)
            com.todocompanion.app.domain.SwipeAction.SCHEDULE_TOMORROW -> {
                val ms = java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli()
                save(t.copy(dueDate = ms))
            }
            com.todocompanion.app.domain.SwipeAction.EDIT -> return false
            com.todocompanion.app.domain.SwipeAction.MOVE -> return false   // needs the move picker; caller handles
            com.todocompanion.app.domain.SwipeAction.NONE -> {}
        }
        return true
    }

    // ---------- tags / contexts ----------
    fun tagsForTask(taskId: String): List<TagEntity> {
        val ids = taskTags.value.filter { it.taskId == taskId }.map { it.tagId }.toSet()
        return tags.value.filter { it.id in ids }
    }
    fun contextsForTask(taskId: String): List<ContextEntity> {
        val ids = taskContexts.value.filter { it.taskId == taskId }.map { it.contextId }.toSet()
        return contexts.value.filter { it.id in ids }
    }
    fun setTags(taskId: String, tagIds: List<String>) = viewModelScope.launch { repo.setTaskTags(taskId, tagIds) }
    fun setContexts(taskId: String, ids: List<String>) = viewModelScope.launch { repo.setTaskContexts(taskId, ids) }
    fun createTag(name: String, parentId: String? = null) = viewModelScope.launch { repo.upsertTag(TagEntity(UUID.randomUUID().toString(), name.trim(), parentId = parentId, workspaceId = settings.value.activeWorkspaceId)) }
    fun renameTag(tag: TagEntity, name: String) = viewModelScope.launch { repo.upsertTag(tag.copy(name = name.trim())) }
    fun setTagColor(tag: TagEntity, argb: Long?) = viewModelScope.launch { repo.upsertTag(tag.copy(colorArgb = argb)) }
    fun deleteTag(tag: TagEntity) = viewModelScope.launch {
        // reparent children to this tag's parent so the subtree isn't orphaned
        tags.value.filter { it.parentId == tag.id }.forEach { repo.upsertTag(it.copy(parentId = tag.parentId)) }
        repo.deleteTag(tag.id)
        if (currentView.value == ViewRef.TagView(tag.id)) select(ViewRef.Smart(SmartKind.TODAY))
    }
    fun moveTagToParent(tagId: String, parentId: String?) = viewModelScope.launch {
        tags.value.firstOrNull { it.id == tagId }?.let { repo.upsertTag(it.copy(parentId = parentId)) }
    }
    fun createContext(name: String, parentId: String? = null) = viewModelScope.launch { repo.upsertContext(ContextEntity(id = UUID.randomUUID().toString(), name = name.trim(), parentId = parentId, workspaceId = settings.value.activeWorkspaceId)) }
    /**
     * The dialog hands each mutator an open-time [ContextEntity] snapshot that is never refreshed, so
     * a `c.copy(...)` on it silently reverts every field another mutator changed while the dialog was
     * open. Always read-modify-write the *current* persisted row so edits merge instead of clobbering.
     */
    private fun curContext(c: ContextEntity): ContextEntity = contexts.value.firstOrNull { it.id == c.id } ?: c
    fun renameContext(c: ContextEntity, name: String) = viewModelScope.launch { repo.upsertContext(curContext(c).copy(name = name.trim())) }
    fun setContextColor(c: ContextEntity, argb: Long?) = viewModelScope.launch { repo.upsertContext(curContext(c).copy(colorArgb = argb)) }
    fun setContextActive(c: ContextEntity, active: Boolean) = viewModelScope.launch { repo.upsertContext(curContext(c).copy(active = active)) }
    fun setContextHours(c: ContextEntity, json: String?) = viewModelScope.launch { repo.upsertContext(curContext(c).copy(openHoursJson = json)) }
    fun deleteContext(c: ContextEntity) = viewModelScope.launch {
        contexts.value.filter { it.parentId == c.id }.forEach { repo.upsertContext(it.copy(parentId = c.parentId)) }
        repo.deleteContext(c.id)
        if (currentView.value == ViewRef.ContextView(c.id)) select(ViewRef.Smart(SmartKind.TODAY))
    }
    fun moveContextToParent(contextId: String, parentId: String?) = viewModelScope.launch {
        contexts.value.firstOrNull { it.id == contextId }?.let { repo.upsertContext(it.copy(parentId = parentId)) }
    }

    // ---------- dependencies ----------
    fun dependenciesFor(taskId: String): List<DependencyEntity> = dependencies.value.filter { it.taskId == taskId }
    fun addDependency(taskId: String, dependsOn: String, mode: String = "AND") = viewModelScope.launch { repo.addDependency(taskId, dependsOn, mode) }
    fun removeDependency(dep: DependencyEntity) = viewModelScope.launch { repo.removeDependency(dep) }
    /** Set the blocking mode (AND = all must finish, OR = any one unblocks) for every blocker of a task. */
    fun setDependencyMode(taskId: String, mode: String) = viewModelScope.launch {
        dependencies.value.filter { it.taskId == taskId }.forEach { repo.removeDependency(it); repo.addDependency(it.taskId, it.dependsOnTaskId, mode, it.delayDays) }
    }
    /** Set the activation delay (days after the anchor prerequisite completes) for a task's blockers. */
    fun setDependencyDelay(taskId: String, days: Int) = viewModelScope.launch {
        dependencies.value.filter { it.taskId == taskId }.forEach { repo.removeDependency(it); repo.addDependency(it.taskId, it.dependsOnTaskId, it.mode, days) }
    }
    fun setCompleteInOrder(t: TaskEntity, v: Boolean) = viewModelScope.launch { repo.saveTask(t.copy(completeInOrder = v)) }
    fun setProject(t: TaskEntity, v: Boolean) = viewModelScope.launch { repo.saveTask(t.copy(isProject = v)) }
    fun setGoal(t: TaskEntity, v: Boolean) = viewModelScope.launch { repo.saveTask(t.copy(isGoal = v)) }
    fun setReviewEvery(t: TaskEntity, days: Int?) = viewModelScope.launch { repo.saveTask(t.copy(reviewEveryDays = days)) }
    fun markReviewed(t: TaskEntity) = viewModelScope.launch { repo.saveTask(t.copy(reviewedAt = System.currentTimeMillis())) }

    // ---------- reminders ----------
    fun addAbsoluteReminder(task: TaskEntity, atMillis: Long, annoying: Boolean = false) = viewModelScope.launch {
        val r = ReminderEntity(UUID.randomUUID().toString(), taskId = task.id, type = "absolute", atTime = atMillis, annoying = annoying)
        repo.upsertReminder(r)
        AlarmScheduler.schedule(appCtx, r, task)
    }
    /** A reminder relative to the task's due or start ([type] = relativeToDue / relativeToStart). */
    fun addRelativeReminder(task: TaskEntity, type: String, offsetMin: Int, annoying: Boolean = false) = viewModelScope.launch {
        val r = ReminderEntity(UUID.randomUUID().toString(), taskId = task.id, type = type, offsetMin = offsetMin, annoying = annoying)
        repo.upsertReminder(r)
        AlarmScheduler.schedule(appCtx, r, task)
    }
    /** Toggle a reminder's persistent ("annoying") alarm — re-fires until the task is done. */
    fun setReminderAnnoying(reminder: ReminderEntity, task: TaskEntity, on: Boolean) = viewModelScope.launch {
        val nr = reminder.copy(annoying = on)
        repo.upsertReminder(nr)
        AlarmScheduler.cancel(appCtx, reminder, task); AlarmScheduler.schedule(appCtx, nr, task)
    }
    fun deleteReminder(reminder: ReminderEntity, task: TaskEntity) = viewModelScope.launch {
        repo.deleteReminder(reminder.id)
        AlarmScheduler.cancel(appCtx, reminder, task)
    }

    // ---------- settings ----------
    fun saveSettings(s: AppSettings) = viewModelScope.launch { repo.saveSettings(s) }
    /** Rename a smart list (null/blank clears the custom name → reverts to the built-in title). */
    fun setSmartListName(kindName: String, name: String?) = viewModelScope.launch {
        val s = settings.value
        val next = s.smartListNames.toMutableMap()
        if (name.isNullOrBlank()) next.remove(kindName) else next[kindName] = name.trim()
        repo.saveSettings(s.copy(smartListNames = next))
    }

    // ---------- sidebar favourites (pin to top) ----------
    fun isPinned(ref: String): Boolean = ref in settings.value.pinnedRefs
    fun togglePinnedRef(ref: String) = viewModelScope.launch {
        val cur = settings.value.pinnedRefs
        repo.saveSettings(settings.value.copy(pinnedRefs = if (ref in cur) cur - ref else cur + ref))
    }

    // ---------- sidebar section fold + visibility (persisted) ----------
    fun toggleSidebarSection(key: String) = viewModelScope.launch {
        val cur = settings.value.sidebarCollapsed
        repo.saveSettings(settings.value.copy(sidebarCollapsed = if (key in cur) cur - key else cur + key))
    }
    fun setSidebarSectionHidden(key: String, hidden: Boolean) = viewModelScope.launch {
        val cur = settings.value.sidebarHidden
        repo.saveSettings(settings.value.copy(sidebarHidden = if (hidden) cur + key else cur - key))
    }
    // ---------- T0: modular module config ----------
    fun setPrimaryModule(module: String) = viewModelScope.launch {
        repo.saveSettings(settings.value.copy(primaryModule = module, disabledModules = settings.value.disabledModules - module))
    }
    fun setModuleEnabled(module: String, on: Boolean) = viewModelScope.launch {
        val s = settings.value
        if (!on && module == s.primaryModule) return@launch   // the primary module is never disabled
        repo.saveSettings(s.copy(disabledModules = if (on) s.disabledModules - module else s.disabledModules + module))
    }
    /** First-run picker result: choose a primary and which others stay on. */
    fun applyModulePreset(primary: String, enabledOthers: Set<String>) = viewModelScope.launch {
        val disabled = com.todocompanion.app.domain.Modules.ALL.filter { it != primary && it !in enabledOthers }.toSet()
        repo.saveSettings(settings.value.copy(primaryModule = primary, disabledModules = disabled, onboardedModules = true))
    }
    fun markModulesOnboarded() = viewModelScope.launch { repo.saveSettings(settings.value.copy(onboardedModules = true)) }

    // ── Tier Ω · the only-we frontier — command palette, local Q&A, recap & annual report ─────────
    /** One immutable snapshot of the whole store for the Ω domain functions (all pure over it). */
    private fun omegaCtx(): com.todocompanion.app.domain.OmegaContext = com.todocompanion.app.domain.OmegaContext(
        tasks = tasks.value, habits = habits.value, checkins = habitCheckins.value,
        focus = focusSessions.value, timeEntries = timeEntries.value, activities = timeActivities.value,
        zone = zone, today = java.time.LocalDate.now(zone).toEpochDay(), now = System.currentTimeMillis(),
    )

    /** Ω2 — answer a data question across all three modules, entirely on-device. */
    fun answerQuery(question: String): com.todocompanion.app.domain.OmegaQuery.Answer =
        com.todocompanion.app.domain.OmegaQuery.answer(question, omegaCtx())

    /** Ω5 — the cross-module recap for any date range (inclusive epoch-days). */
    fun periodRecap(startDay: Long, endDay: Long, title: String, tasksOverride: List<TaskEntity>? = null): com.todocompanion.app.domain.PeriodRecap.Recap =
        com.todocompanion.app.domain.PeriodRecap.compute(startDay, endDay, title,
            omegaCtx().let { if (tasksOverride != null) it.copy(tasks = tasksOverride) else it })

    /** Ω3 — adaptive hints suggesting a module the user would benefit from turning on. */
    fun moduleHints(): List<com.todocompanion.app.domain.ModuleHints.Hint> =
        com.todocompanion.app.domain.ModuleHints.compute(settings.value, tasks.value, habits.value)

    /** Ω4 — render the annual life report to a self-contained HTML file and hand it to the share sheet. */
    fun shareAnnualReport(year: Int, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val ctx = omegaCtx()
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val html = com.todocompanion.app.domain.LifeReport.buildHtml(year, ctx)
                val dir = java.io.File(appCtx.cacheDir, "shared").apply { mkdirs() }
                val f = java.io.File(dir, "modular-year-$year.html").apply { writeText(html) }
                androidx.core.content.FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", f)
            }.getOrNull()
        }
        if (uri == null) { toast("Couldn't build the report"); onDone(false); return@launch }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/html"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(send, "Your year in review").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appCtx.startActivity(chooser) }.onFailure { toast("No app to open it with") }
        onDone(true)
    }

    /** R29 Phase 5 — mint a proof-of-work receipt image from a finished item and hand it to the share sheet.
     *  Rendered locally with android.graphics; nothing leaves the device until the user picks where to send it. */
    fun shareReceipt(a: com.todocompanion.app.domain.done.Accomplishment, listName: String?, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val bmp = com.todocompanion.app.ui.util.ReceiptRenderer.render(a, listName, zone)
                val dir = java.io.File(appCtx.cacheDir, "shared").apply { mkdirs() }
                val f = java.io.File(dir, "receipt-${a.refId.take(8)}.png")
                java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                androidx.core.content.FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", f)
            }.getOrNull()
        }
        if (uri == null) { toast("Couldn't make the receipt"); onDone(false); return@launch }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(send, "Proof of work").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appCtx.startActivity(chooser) }.onFailure { toast("No app to share to") }
        onDone(true)
    }

    /** The whole accomplishment feed for the active workspace — the input to the integrity chain & impact graph. */
    fun doneFeed(): List<com.todocompanion.app.domain.done.Accomplishment> =
        com.todocompanion.app.domain.done.DoneRecord.build(tasks.value, habits.value, habitCheckins.value, timeEntries.value, zone)

    /** R29 Phase 7 — seal the record: store the current hash-chain head so a later back-date or edit of a
     *  sealed entry is detectable (the recomputed head no longer matches). Entirely local. */
    fun sealRecord() = viewModelScope.launch {
        val seal = com.todocompanion.app.domain.done.Integrity.seal(doneFeed())
        saveSettings(settings.value.copy(integritySeal = seal.encode()))
        toast("Record sealed — ${seal.count} entries")
    }
    fun clearSeal() = viewModelScope.launch { saveSettings(settings.value.copy(integritySeal = null)) }

    // ── R32 · sealed letter to your future self (Living Record #7) ──────────────────────────────────
    /** Seal a note now to be revealed on [revealEpochDay]. The anchor hash over the body proves it wasn't
     *  edited after sealing, and we stamp the current accomplishment count so the reveal can show the delta. */
    fun sealLetter(title: String, body: String, revealEpochDay: Long) = viewModelScope.launch {
        val today = java.time.LocalDate.now().toEpochDay()
        val hash = com.todocompanion.app.domain.done.Integrity.hash("${body}|$today")
        repo.upsertSealedNote(com.todocompanion.app.data.entity.SealedNoteEntity(
            id = java.util.UUID.randomUUID().toString(), createdEpochDay = today,
            revealEpochDay = revealEpochDay.coerceAtLeast(today + 1), title = title.trim().ifBlank { "A letter to future me" },
            body = body.trim(), anchorHash = hash, sealedCount = doneFeed().size,
        ))
        toast("Sealed — opens ${java.time.LocalDate.ofEpochDay(revealEpochDay)}")
    }
    fun acknowledgeLetter(n: com.todocompanion.app.data.entity.SealedNoteEntity) = viewModelScope.launch {
        repo.upsertSealedNote(n.copy(acknowledged = true))
    }
    fun deleteLetter(id: String) = viewModelScope.launch { repo.deleteSealedNote(id) }
    /** Whether a sealed note's body still matches its anchor hash (i.e. hasn't been tampered with). */
    fun letterIntact(n: com.todocompanion.app.data.entity.SealedNoteEntity): Boolean =
        com.todocompanion.app.domain.done.Integrity.hash("${n.body}|${n.createdEpochDay}") == n.anchorHash

    /** Frontier F2 — render a sealed-year certificate image for [year] and hand it to the share sheet. */
    fun shareYearCertificate(year: Int, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val ofYear = doneFeed().filter { java.time.LocalDate.ofEpochDay(it.epochDay).year == year }
        if (ofYear.isEmpty()) { toast("Nothing finished in $year yet"); onDone(false); return@launch }
        val stats = com.todocompanion.app.domain.done.DoneRecord.stats(ofYear)
        val head = com.todocompanion.app.domain.done.Integrity.headOf(ofYear.sortedBy { it.whenMillis })
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val bmp = com.todocompanion.app.ui.util.ReceiptRenderer.renderCertificate(year, stats, head, ofYear.size)
                val dir = java.io.File(appCtx.cacheDir, "shared").apply { mkdirs() }
                val f = java.io.File(dir, "certificate-$year.png")
                java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                androidx.core.content.FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", f)
            }.getOrNull()
        }
        if (uri == null) { toast("Couldn't make the certificate"); onDone(false); return@launch }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(send, "Certificate of work · $year").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appCtx.startActivity(chooser) }.onFailure { toast("No app to share to") }
        onDone(true)
    }

    /** R32 Living Record #6 — share a single milestone as a verifiable achievement card image. */
    fun shareMilestone(m: com.todocompanion.app.domain.done.LivingRecord.Milestone, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val feed = doneFeed()
        val head = com.todocompanion.app.domain.done.Integrity.headOf(feed.sortedBy { it.whenMillis })
        val payload = "TDCM|${m.key}|${feed.size}|$head"
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val bmp = com.todocompanion.app.ui.util.ReceiptRenderer.renderMilestoneCard(m.emoji, m.label, m.detail, payload)
                val dir = java.io.File(appCtx.cacheDir, "shared").apply { mkdirs() }
                val f = java.io.File(dir, "milestone-${m.key}.png")
                java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                androidx.core.content.FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", f)
            }.getOrNull()
        }
        if (uri == null) { toast("Couldn't make the card"); onDone(false); return@launch }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { appCtx.startActivity(android.content.Intent.createChooser(send, m.label).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast("No app to share to") }
        onDone(true)
    }

    // Drag-reorder persistence for the drawer sections.
    fun setTagOrder(ids: List<String>) = viewModelScope.launch { repo.setTagOrder(ids) }
    fun setHabitOrder(ids: List<String>) = viewModelScope.launch { repo.setHabitOrder(ids); refreshHabitWidgets() }
    fun setContextOrder(ids: List<String>) = viewModelScope.launch { repo.setContextOrder(ids) }
    fun setFilterOrder(ids: List<String>) = viewModelScope.launch { repo.setFilterOrder(ids) }
    /** Persisted per-list Board/List layout choice. */
    fun isBoardList(listId: String): Boolean = listId in settings.value.boardLists
    fun setBoardList(listId: String, board: Boolean) = viewModelScope.launch {
        val cur = settings.value.boardLists
        repo.saveSettings(settings.value.copy(boardLists = if (board) cur + listId else cur - listId))
    }
    fun setSmartOrder(ids: List<String>) = viewModelScope.launch { repo.saveSettings(settings.value.copy(smartOrder = ids)) }
    fun setViewsOrder(ids: List<String>) = viewModelScope.launch { repo.saveSettings(settings.value.copy(viewsOrder = ids)) }

    // ---------- saved view tabs ----------
    val viewTabs: StateFlow<List<com.todocompanion.app.domain.view.ViewTab>> =
        settings.map { com.todocompanion.app.domain.view.ViewTabs.decode(it.viewTabsJson) }.state(emptyList())

    fun saveCurrentAsTab(name: String) = viewModelScope.launch {
        val tab = com.todocompanion.app.domain.view.ViewTab(
            id = UUID.randomUUID().toString(), name = name.trim().ifBlank { "View" },
            ref = com.todocompanion.app.domain.view.ViewTabs.refOf(currentView.value),
            group = groupMode.value.name, sort = sortMode.value.name,
            outline = outlineMode.value, hierarchy = filterHierarchy.value, zoom = outlineZoom.value,
        )
        val next = viewTabs.value + tab
        repo.saveSettings(settings.value.copy(viewTabsJson = com.todocompanion.app.domain.view.ViewTabs.encode(next)))
    }

    fun applyTab(tab: com.todocompanion.app.domain.view.ViewTab) {
        val v = com.todocompanion.app.domain.view.ViewTabs.viewOf(tab.ref) ?: return
        currentView.value = v
        groupMode.value = runCatching { GroupMode.valueOf(tab.group) }.getOrDefault(GroupMode.DATE)
        sortMode.value = runCatching { SortMode.valueOf(tab.sort) }.getOrDefault(SortMode.MANUAL)
        outlineMode.value = tab.outline
        filterHierarchy.value = tab.hierarchy
        outlineZoom.value = tab.zoom
    }

    fun deleteTab(id: String) = viewModelScope.launch {
        val next = viewTabs.value.filterNot { it.id == id }
        repo.saveSettings(settings.value.copy(viewTabsJson = com.todocompanion.app.domain.view.ViewTabs.encode(next)))
    }

    fun renameTab(id: String, name: String) = viewModelScope.launch {
        val next = viewTabs.value.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }
        repo.saveSettings(settings.value.copy(viewTabsJson = com.todocompanion.app.domain.view.ViewTabs.encode(next)))
    }

    // ---------- search ----------
    fun search(query: String): List<TaskEntity> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        val q2 = q.removePrefix("#").removePrefix("@")
        val tagIds = tags.value.filter { it.name.lowercase().contains(q2) }.map { it.id }.toSet()
        val ctxIds = contexts.value.filter { it.name.lowercase().contains(q2) }.map { it.id }.toSet()
        val byTag = taskTags.value.filter { it.tagId in tagIds }.map { it.taskId }.toSet()
        val byCtx = taskContexts.value.filter { it.contextId in ctxIds }.map { it.taskId }.toSet()
        return tasks.value.filter {
            !it.trashed && (it.title.lowercase().contains(q) || it.note.lowercase().contains(q) || it.id in byTag || it.id in byCtx)
        }
    }

    /** E1: search across all habits too — name, description, identity/"why", category and unit. */
    fun searchHabits(query: String): List<com.todocompanion.app.data.entity.HabitEntity> {
        val q = query.trim().lowercase().removePrefix("#").removePrefix("@")
        if (q.isBlank()) return emptyList()
        return habits.value.filter { h ->
            !h.archived && (h.name.lowercase().contains(q) || h.description.lowercase().contains(q) ||
                h.identity.lowercase().contains(q) || h.category.lowercase().contains(q) ||
                (h.unit?.lowercase()?.contains(q) == true))
        }.sortedBy { it.sortOrder }
    }

    // ---------- export / import ----------
    fun exportTo(uri: Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = runCatching {
            val json = repo.exportJson()
            appCtx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
        }.isSuccess
        onDone(ok)
    }
    fun exportMarkdownTo(uri: Uri, includeCompleted: Boolean, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = runCatching {
            val md = repo.exportMarkdown(includeCompleted)
            appCtx.contentResolver.openOutputStream(uri)?.use { it.write(md.toByteArray()) }
        }.isSuccess
        onDone(ok)
    }
    fun exportCsvTo(uri: Uri, includeCompleted: Boolean, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = runCatching {
            val csv = repo.exportCsv(includeCompleted)
            appCtx.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
        }.isSuccess
        onDone(ok)
    }
    fun exportIcsTo(uri: Uri, includeCompleted: Boolean, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = runCatching {
            val ics = repo.exportIcs(includeCompleted)
            appCtx.contentResolver.openOutputStream(uri)?.use { it.write(ics.toByteArray()) }
        }.isSuccess
        onDone(ok)
    }
    fun exportHabitsCsvTo(uri: Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = runCatching {
            val csv = repo.exportHabitsCsv()
            appCtx.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
        }.isSuccess
        onDone(ok)
    }
    /**
     * SAF-free export fallback: write the chosen export straight into the public Downloads folder
     * (or the app's files dir on older devices). Used when the device has no system document picker.
     * [onDone] receives a user-facing location like "Downloads/todo-companion-backup.json", or null.
     */
    fun exportToDownloads(kind: String, onDone: (String?) -> Unit) = viewModelScope.launch {
        val loc = runCatching {
            val (content, name, mime) = when (kind) {
                "json" -> Triple(repo.exportJson(), "todo-companion-backup.json", "application/json")
                "md" -> Triple(repo.exportMarkdown(true), "todo-companion.md", "text/markdown")
                "csv" -> Triple(repo.exportCsv(true), "todo-companion.csv", "text/csv")
                "ics" -> Triple(repo.exportIcs(false), "todo-companion.ics", "text/calendar")
                "habits" -> Triple(repo.exportHabitsCsv(), "todo-companion-habits.csv", "text/csv")
                else -> return@runCatching null
            }
            com.todocompanion.app.util.FileExport.saveToDownloads(appCtx, name, mime, content.toByteArray())
        }.getOrNull()
        // U10: a successful full backup stamps the "last backup" time the Momentum data-safety card reads.
        if (kind == "json" && loc != null) repo.saveSettings(settings.value.copy(lastSyncAt = System.currentTimeMillis()))
        onDone(loc)
    }
    fun importHabitsCsv(uri: Uri, onDone: (Boolean, String) -> Unit) = viewModelScope.launch {
        val n = runCatching {
            val text = appCtx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@runCatching -1
            repo.importHabitsCsv(text)
        }.getOrDefault(-1)
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
        com.todocompanion.app.widget.HabitsWidget.refresh(appCtx)
        when { n < 0 -> onDone(false, "Couldn't read that CSV — export from Loop, or our habit CSV"); n == 0 -> onDone(false, "No check-ins found in that file"); else -> onDone(true, "Imported $n habit check-ins") }
    }

    // ── CU3 · import an .ics calendar into tasks (the other half of the 2-way bridge) ──────────────
    fun importIcs(uri: Uri, onDone: (Boolean, String) -> Unit) = viewModelScope.launch {
        val text = runCatching { appCtx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        if (text == null) { onDone(false, "Couldn't read that file"); return@launch }
        val events = com.todocompanion.app.domain.port.Ics.parse(text)
        if (events.isEmpty()) { onDone(false, "No events found in that .ics"); return@launch }
        var n = 0
        events.forEach { e ->
            val due = e.start.atZone(zone).toInstant().toEpochMilli()
            val id = repo.createTask(com.todocompanion.app.data.entity.ListEntity.INBOX_ID, e.summary.ifBlank { "Event" }, dueDate = due)
            val durMin = if (!e.allDay && e.end != null) java.time.Duration.between(e.start, e.end).toMinutes().toInt().coerceIn(0, 1440) else null
            if (e.allDay || durMin != null) repo.getTask(id)?.let { repo.saveTask(it.copy(isAllDay = e.allDay, durationMin = durMin)) }
            n++
        }
        onDone(true, "Imported $n event${if (n == 1) "" else "s"} as tasks")
    }

    // ── CU4 · one-tap handoff — share a full copy through the system share sheet (0 permission) ────
    fun shareBackupCopy(onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val json = repo.exportJson()
                val dir = java.io.File(appCtx.cacheDir, "shared").apply { mkdirs() }
                val f = java.io.File(dir, "modular-backup-${java.time.LocalDate.now(zone)}.json").apply { writeText(json) }
                androidx.core.content.FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", f)
            }.getOrNull()
        }
        if (uri == null) { toast("Couldn't prepare the copy"); onDone(false); return@launch }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(send, "Send a copy to another device").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appCtx.startActivity(chooser) }.onFailure { toast("No app to share with") }
        onDone(true)
    }

    // ── CU5 · accountability snapshot — share a goal's progress as an image card (0 permission) ────
    fun shareGoalSnapshot(g: com.todocompanion.app.domain.Goal, onDone: (String?) -> Unit = {}) = viewModelScope.launch {
        val h = goalHealth(g)
        val stats = buildList {
            add("progress" to "${(h.overall * 100).toInt()}%")
            if (g.hasTasks) add("tasks" to "${h.taskDone}/${h.taskTotal}")
            if (g.hasHabit) add("streak" to "${h.habitStreak}d")
            if (g.hasBudget) add("time" to "${h.minutesTracked / 60}h/${(h.budgetMin / 60)}h")
        }.take(4)
        val res = withContext(Dispatchers.IO) {
            val bmp = com.todocompanion.app.util.ProgressCard.renderStatsCard("${g.emoji} ${g.name}", "Goal progress", stats)
            com.todocompanion.app.util.ProgressCard.saveAndShareUri(appCtx, bmp, "modular-goal.png")
        }
        res.shareUri?.let { com.todocompanion.app.util.ProgressCard.share(appCtx, it) }
        onDone(res.savedLocation)
    }

    // ---------- Tier D: folder backup & account-free sync ----------
    private fun ensureDeviceId(): String {
        val cur = settings.value.deviceId
        if (cur.isNotBlank()) return cur
        val id = UUID.randomUUID().toString().take(8)
        viewModelScope.launch { repo.saveSettings(settings.value.copy(deviceId = id)) }
        return id
    }
    fun setSyncFolder(uri: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(syncFolder = uri, syncEnabled = uri.isNotBlank())) }
    fun setAutoBackupFolder(uri: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(autoBackupFolder = uri, autoBackupEnabled = uri.isNotBlank())) }
    fun setAutoBackupEnabled(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(autoBackupEnabled = on)) }
    fun setSyncEnabled(on: Boolean) = viewModelScope.launch { repo.saveSettings(settings.value.copy(syncEnabled = on)) }
    fun markOnboarded() = viewModelScope.launch { repo.saveSettings(settings.value.copy(onboarded = true)) }
    fun replayOnboarding() = viewModelScope.launch { repo.saveSettings(settings.value.copy(onboarded = false)) }

    fun setSyncPassphrase(pass: String) = viewModelScope.launch { repo.saveSettings(settings.value.copy(syncPassphrase = pass)) }

    fun runSyncNow(onDone: (Boolean, String) -> Unit) = viewModelScope.launch {
        val folder = settings.value.syncFolder
        if (folder.isBlank()) { onDone(false, "Choose a sync folder first"); return@launch }
        val dev = ensureDeviceId()
        val r = com.todocompanion.app.data.sync.SyncEngine.sync(appCtx, repo, folder, dev, settings.value.syncPassphrase)
        if (r.ok) {
            repo.saveSettings(settings.value.copy(lastSyncAt = System.currentTimeMillis(), deviceId = dev, lastSyncSummary = r.message))
            AlarmScheduler.rescheduleAll(appCtx, repo)
        }
        onDone(r.ok, r.message)
    }

    fun runBackupNow(onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val folder = settings.value.autoBackupFolder.ifBlank { settings.value.syncFolder }
        if (folder.isBlank()) { onDone(false); return@launch }
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        onDone(com.todocompanion.app.data.sync.SyncEngine.backup(appCtx, repo, folder, "todo-backup-$stamp.json", settings.value.syncPassphrase))
    }

    /** Import tasks from a Todoist/TickTick CSV or MLO OPML/.mlobak file. Returns (ok, message). */
    fun importExternal(uri: Uri, onDone: (Boolean, String) -> Unit) = viewModelScope.launch {
        val text = readImportText(uri = uri)
        importExternalText(text, onDone)
    }
    /** Read an import file as text, unzipping/decoding MLO `.mlobak` (ZIP / UTF-16 XML) so it isn't read
     *  as garbage. Accepts either a content Uri or a File. */
    private suspend fun readImportText(uri: Uri? = null, file: java.io.File? = null): String? = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            when {
                uri != null -> appCtx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                file != null -> file.readBytes()
                else -> null
            }
        }.getOrNull() ?: return@withContext null
        com.todocompanion.app.data.sync.Importers.bytesToText(bytes)
    }
    /** Core of external (Todoist/TickTick CSV, MLO OPML) import — reused by the in-app restore browser. */
    private suspend fun importExternalText(text: String?, onDone: (Boolean, String) -> Unit) {
        val ok = runCatching {
            if (text == null) return@runCatching null
            val parsed = com.todocompanion.app.data.sync.Importers.parse(text) ?: return@runCatching null
            val listIds = HashMap<String, String>()
            val existing = lists.value.associateBy { it.name.lowercase() }
            parsed.rows.forEach { row ->
                val listId = listIds.getOrPut(row.list) {
                    existing[row.list.lowercase()]?.id ?: repo.createList(row.list)
                }
                val id = repo.createTask(listId, row.title, importance = row.importance, urgency = row.urgency, dueDate = row.dueMillis)
                if (row.note.isNotBlank() || row.completed) repo.getTask(id)?.let { t ->
                    repo.saveTask(t.copy(note = row.note, completed = row.completed, completedAt = if (row.completed) System.currentTimeMillis() else null))
                }
                if (row.tags.isNotEmpty()) {
                    val ws = settings.value.activeWorkspaceId
                    val tagExisting = repo.getTagsOnce().filter { it.workspaceId == ws }.associateBy { it.name.lowercase() }
                    val ids = row.tags.map { name -> tagExisting[name.lowercase()]?.id ?: UUID.randomUUID().toString().also { repo.upsertTag(TagEntity(it, name, workspaceId = ws)) } }
                    repo.setTaskTags(id, ids.distinct())
                }
            }
            parsed.source to parsed.rows.size
        }.getOrNull()
        if (ok == null) onDone(false, "Couldn't read that file — export a Todoist/TickTick CSV or MLO OPML")
        else onDone(true, "Imported ${ok.second} tasks from ${ok.first}")
    }

    // ---- In-app restore / import (no system picker needed) ----
    // [broad] = true lists any JSON/CSV a user dropped into the import inbox, not just our own backups.
    fun loadSavedBackups(broad: Boolean = false, onDone: (List<com.todocompanion.app.util.FileExport.SavedFile>) -> Unit) = viewModelScope.launch {
        val list = withContext(Dispatchers.IO) { com.todocompanion.app.util.FileExport.listSaved(appCtx, broad) }
        onDone(list)
    }
    /** The folder a user copies a backup into to import it with no picker and no permission. */
    fun importInboxHint(): String = com.todocompanion.app.util.FileExport.importInboxHint(appCtx)
    /**
     * Import from text the user pasted in (a backup copied from another device / the clipboard) — the
     * last-resort channel that needs no file, no picker and no storage access. JSON restores the whole
     * store; anything else routes through the Todoist/TickTick/MLO parser.
     */
    fun importPastedText(text: String, onDone: (Boolean, String) -> Unit) = viewModelScope.launch {
        val t = text.trim()
        if (t.isEmpty()) { onDone(false, "Nothing to import — paste a backup first"); return@launch }
        if (t.startsWith("{") || t.startsWith("[")) {
            val ok = runCatching {
                val plain = com.todocompanion.app.data.sync.Crypto.decrypt(t, settings.value.syncPassphrase) ?: t
                repo.importJsonReplace(plain); AlarmScheduler.rescheduleAll(appCtx, repo); true
            }.getOrDefault(false)
            onDone(ok, if (ok) "Restored from pasted backup" else "That text isn't a valid ToDo Companion backup")
        } else importExternalText(t, onDone)
    }
    fun restoreSaved(s: com.todocompanion.app.util.FileExport.SavedFile, onDone: (Boolean, String) -> Unit) = viewModelScope.launch {
        val text = readImportText(uri = s.uri, file = s.file)
        if (text == null) { onDone(false, "Couldn't read that file"); return@launch }
        if (s.name.endsWith(".json", ignoreCase = true)) {
            val ok = runCatching {
                val plain = com.todocompanion.app.data.sync.Crypto.decrypt(text, settings.value.syncPassphrase) ?: text
                repo.importJsonReplace(plain); AlarmScheduler.rescheduleAll(appCtx, repo); true
            }.getOrDefault(false)
            onDone(ok, if (ok) "Restored from ${s.name}" else "Restore failed — is this a ToDo Companion backup?")
        } else importExternalText(text, onDone)
    }

    /**
     * E9: import a backup a file manager handed us via "Open with" / "Share" (a content:// URI that
     * grants a temporary read — no storage permission needed). Reuses the restore pipeline; sniffs
     * JSON vs external (CSV/OPML) when the filename carries no usable extension.
     */
    fun importFromIntent(uri: Uri, merge: Boolean = false, onDone: (Boolean, String) -> Unit) = viewModelScope.launch {
        // Robust filename: content providers give DISPLAY_NAME; a file:// URI falls back to the last path
        // segment so the extension is still known (de-Googled file managers use both).
        val name = (displayNameOf(uri) ?: uri.lastPathSegment ?: "").lowercase()
        val ext = name.substringAfterLast('.', "")
        val text = readImportText(uri = uri)
        if (text.isNullOrBlank()) { onDone(false, "Couldn't read that file. Try 'Share → ToDo Companion' from your file manager."); return@launch }
        val external = com.todocompanion.app.data.sync.Importers.parse(text)
        // Route by intent, not by a fragile first-character sniff. A file from another app (.mlobak/.ml/
        // .opml/.csv/.xml) is NEVER our JSON backup, so it must never reach the JSON path — that misroute
        // is what showed "isn't a valid ToDo Companion backup" for a real MLO .mlobak (R23).
        val externalExt = ext in setOf("mlobak", "ml", "mlt", "opml", "csv", "tsv", "xml")
        val jsonExt = ext == "json" || ext == "todobackup"
        val looksJson = jsonExt || (ext.isEmpty() && text.trimStart().firstOrNull()?.let { it == '{' || it == '[' } == true)
        when {
            externalExt -> {
                if (external != null) importExternalText(text, onDone)
                else onDone(false, "Couldn't read that ${ext.uppercase()} file. In MyLifeOrganized, use Menu ▸ Backup/Export ▸ OPML and share the .opml — some .mlobak archives are password-protected or in a format we can't open.")
            }
            external != null && !looksJson -> importExternalText(text, onDone)
            looksJson -> {
                val ok = runCatching {
                    val plain = com.todocompanion.app.data.sync.Crypto.decrypt(text, settings.value.syncPassphrase) ?: text
                    if (merge) repo.importJsonMerge(plain) else repo.importJsonReplace(plain)
                    AlarmScheduler.rescheduleAll(appCtx, repo); true
                }.getOrDefault(false)
                val verb = if (merge) "Merged" else "Restored"
                if (ok) onDone(true, "$verb ${name.ifBlank { "your backup" }}")
                else if (external != null) importExternalText(text, onDone) // last resort: try as external export
                else onDone(false, "That file isn't a valid ToDo Companion backup")
            }
            else -> importExternalText(text, onDone)
        }
    }

    fun importFrom(uri: Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = runCatching {
            val raw = appCtx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@runCatching false
            // Transparently decrypt an encrypted backup (G1) using the stored passphrase.
            val text = com.todocompanion.app.data.sync.Crypto.decrypt(raw, settings.value.syncPassphrase) ?: return@runCatching false
            repo.importJsonReplace(text)
            AlarmScheduler.rescheduleAll(appCtx, repo)
            true
        }.getOrDefault(false)
        onDone(ok)
    }

    private fun buildOutline(all: List<TaskEntity>, startId: String? = null): List<OutlineRow> {
        val byParent = all.groupBy { it.parentId }
        val out = ArrayList<OutlineRow>(all.size)
        fun dfs(parentId: String?, depth: Int) {
            byParent[parentId]?.sortedBy { it.sortOrder }?.forEach { t ->
                val kids = byParent[t.id].orEmpty()
                out.add(OutlineRow(t, depth, kids.isNotEmpty(), t.collapsed))
                if (!t.collapsed) dfs(t.id, depth + 1)
            }
        }
        if (startId != null) {
            val root = all.firstOrNull { it.id == startId } ?: return emptyList()
            val kids = byParent[startId].orEmpty()
            out.add(OutlineRow(root, 0, kids.isNotEmpty(), root.collapsed))
            if (!root.collapsed) dfs(startId, 1)
        } else dfs(null, 0)
        return out
    }

    /** Grow an id set to include every descendant of its members (for "include subtasks" filters). */
    private fun expandWithDescendants(ids: Set<String>, all: List<TaskEntity>): Set<String> {
        val byParent = all.groupBy { it.parentId }
        val out = HashSet(ids)
        val stack = ArrayDeque(ids)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            byParent[id].orEmpty().forEach { if (out.add(it.id)) stack.addLast(it.id) }
        }
        return out
    }

    /** Build an outline of the [matched] tasks plus every ancestor needed to place them in the tree.
     *  Ancestors that aren't themselves matches are flagged (rendered dimmed). Ignores collapse. */
    private fun buildFilteredOutline(all: List<TaskEntity>, matched: Set<String>): List<OutlineRow> {
        if (matched.isEmpty()) return emptyList()
        val byId = all.associateBy { it.id }
        val included = HashSet<String>()
        matched.forEach { id ->
            var cur: String? = id
            while (cur != null && cur !in included && cur in byId) { included.add(cur); cur = byId[cur]?.parentId }
        }
        val inc = all.filter { it.id in included }
        val byParent = inc.groupBy { it.parentId }
        val out = ArrayList<OutlineRow>(inc.size)
        fun dfs(t: TaskEntity, depth: Int) {
            val kids = byParent[t.id].orEmpty()
            out.add(OutlineRow(t, depth, kids.isNotEmpty(), collapsed = false, matched = t.id in matched))
            kids.sortedBy { it.sortOrder }.forEach { dfs(it, depth + 1) }
        }
        // Roots = included tasks whose parent isn't part of this filtered forest.
        inc.filter { it.parentId == null || it.parentId !in included }
            .sortedWith(compareBy({ it.listId }, { it.sortOrder }))
            .forEach { dfs(it, 0) }
        return out
    }
}
