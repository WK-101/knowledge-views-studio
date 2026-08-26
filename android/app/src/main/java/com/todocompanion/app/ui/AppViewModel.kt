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
)

enum class UndoKind { COMPLETED, ABANDONED, TRASHED }

/** What the full-screen habit editor is editing. A null [habit] means "create a new habit". */
data class HabitEditRequest(val habit: com.todocompanion.app.data.entity.HabitEntity? = null)
data class UndoEvent(val kind: UndoKind, val taskId: String, val message: String)

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
    val folders = combine(repo.allFolders, activeWs) { f, ws -> f.filter { it.workspaceId == ws } }.state(emptyList())
    val lists = combine(repo.allLists, activeWs) { l, ws -> l.filter { it.workspaceId == ws || it.id == ListEntity.INBOX_ID } }.state(emptyList())
    val tags: StateFlow<List<TagEntity>> = repo.allTags.state(emptyList())
    val contexts: StateFlow<List<ContextEntity>> = repo.allContexts.state(emptyList())
    val flags: StateFlow<List<FlagEntity>> = repo.allFlags.state(emptyList())
    val templates: StateFlow<List<TemplateEntity>> = repo.allTemplates.state(emptyList())
    val countdowns = repo.allCountdowns.state(emptyList())
    fun saveCountdown(id: String?, title: String, targetMillis: Long, emoji: String?, colorArgb: Long?) = viewModelScope.launch {
        val existing = id?.let { cid -> countdowns.value.firstOrNull { it.id == cid } }
        repo.upsertCountdown(
            (existing ?: com.todocompanion.app.data.entity.CountdownEntity(id = UUID.randomUUID().toString(), title = title, targetMillis = targetMillis, createdAt = System.currentTimeMillis()))
                .copy(title = title.trim().ifBlank { "Countdown" }, targetMillis = targetMillis, emoji = emoji, colorArgb = colorArgb)
        )
        com.todocompanion.app.widget.CountdownWidget.refresh(appCtx)
    }
    fun deleteCountdown(id: String) = viewModelScope.launch { repo.deleteCountdown(id); com.todocompanion.app.widget.CountdownWidget.refresh(appCtx) }
    fun toggleCountdownPin(c: com.todocompanion.app.data.entity.CountdownEntity) = viewModelScope.launch { repo.upsertCountdown(c.copy(pinned = !c.pinned)); com.todocompanion.app.widget.CountdownWidget.refresh(appCtx) }
    val filters = combine(repo.allFilters, activeWs) { f, ws -> f.filter { it.workspaceId == ws } }.state(emptyList())
    val habits = combine(repo.allHabits, activeWs) { h, ws -> h.filter { it.workspaceId == ws && !it.archived } }.state(emptyList())
    val habitCheckins = repo.allCheckins.state(emptyList())
    val focusSessions = repo.allFocusSessions.state(emptyList())
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

    /** "Day starts at" rollover, in minutes past midnight, for Today/Tomorrow/overdue math. */
    private val dayStartMin: Int get() = settings.value.dayStartHour.coerceIn(0, 6) * 60

    /** Live task count per smart list, for the drawer. */
    val smartCounts: StateFlow<Map<SmartKind, Int>> =
        combine(wsTasks, repo.allDependencies) { t, deps ->
            val now = System.currentTimeMillis()
            SmartKind.entries.associateWith { k ->
                when (k) {
                    // Dependency-aware, so it can't go through the pure filterSmart path.
                    SmartKind.WAITING -> {
                        val byId = t.associateBy { it.id }
                        val blocked = PriorityEngine.computeBlocked(deps, byId, now)
                        t.count { !it.trashed && !it.completed && !it.abandoned && it.id in blocked }
                    }
                    else -> TaskViews.filterSmart(t, k, now, zone, dayStartMin).size
                }
            }
        }.state(emptyMap())

    // P1: reliability (0–100) for every recurring task, from the completion activity trail.
    val taskReliability: StateFlow<Map<String, com.todocompanion.app.domain.task.TaskReliability.Reliability>> =
        combine(wsTasks, repo.allActivity) { t, acts ->
            val now = System.currentTimeMillis()
            t.filter { !it.rrule.isNullOrBlank() && !it.trashed }
                .mapNotNull { task -> com.todocompanion.app.domain.task.TaskReliability.score(task, acts, now, zone)?.let { task.id to it } }
                .toMap()
        }.state(emptyMap())

    private data class Cfg(val view: ViewRef, val group: GroupMode, val sort: SortMode, val prio: PriorityEngine.Config, val flags: List<FlagEntity>, val timeAvail: Int? = null, val energyAvail: Int? = null)

    /** "I have N minutes" planner: when set, Do-Next hides tasks whose estimate exceeds N. null = off. */
    val timeAvailableMin = MutableStateFlow<Int?>(null)

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
    )

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
            combine(currentView, groupMode, sortMode, settings, combine(repo.allFlags, timeAvailableMin, energyAvailable) { fl, ta, ea -> Triple(fl, ta, ea) }) { v, g, s, set, fte -> Cfg(v, g, s, set.priorityConfig(), fte.first, fte.second, fte.third) },
            repo.taskTagRefs,
            combine(repo.taskContextRefs, repo.allContexts, repo.allFilters, repo.allLists, repo.allFolders) { r, c, f, l, fo -> ViewCtx(r, c, f, l, fo) },
            repo.allDependencies,
        ) { all, cfg, ttRefs, vc, deps ->
            val tcRefs = vc.tcRefs; val ctxEntities = vc.contexts; val filterList = vc.filters
            val now = System.currentTimeMillis()
            val filtered = when (val v = cfg.view) {
                is ViewRef.Smart -> {
                    when (v.kind) {
                        SmartKind.DO_NEXT -> {
                            val base = TaskViews.filterSmart(all, v.kind, now, zone, dayStartMin)
                            val ranked = rankDoNext(base, all, now, cfg.prio, deps, tcRefs, ctxEntities)
                            // "Time available" planner: keep tasks that fit the slot (unestimated always fit).
                            val timed = cfg.timeAvail?.let { avail -> ranked.filter { t -> (t.estimateMin ?: t.estimateMax)?.let { it <= avail } ?: true } } ?: ranked
                            // "Energy right now" planner: keep tasks needing at most that energy (untagged always fit).
                            cfg.energyAvail?.let { cap -> timed.filter { t -> (t.energy ?: 0) <= cap } } ?: timed
                        }
                        // Waiting-on: open tasks currently blocked by an incomplete prerequisite.
                        SmartKind.WAITING -> {
                            val byId = all.associateBy { it.id }
                            val blocked = PriorityEngine.computeBlocked(deps, byId, now)
                            all.filter { !it.trashed && !it.completed && !it.abandoned && it.id in blocked }
                        }
                        else -> TaskViews.filterSmart(all, v.kind, now, zone, dayStartMin)
                    }
                }
                is ViewRef.FilterView -> {
                    val q = com.todocompanion.app.domain.view.Filters.parse(filterList.firstOrNull { it.id == v.filterId }?.queryJson)
                    val tagsByTask = ttRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.tagId }.toSet() }
                    val ctxByTask = tcRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.contextId }.toSet() }
                    val hit = all.filter { com.todocompanion.app.domain.view.Filters.matches(q, it, tagsByTask[it.id].orEmpty(), ctxByTask[it.id].orEmpty(), now, zone) }
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
                    val ids = ttRefs.filter { it.tagId == v.tagId }.map { it.taskId }.toSet()
                    all.filter { it.id in ids && !it.trashed && !it.completed && !it.abandoned }
                }
                is ViewRef.ContextView -> {
                    val ids = tcRefs.filter { it.contextId == v.contextId }.map { it.taskId }.toSet()
                    all.filter { it.id in ids && !it.trashed && !it.completed && !it.abandoned }
                }
            }
            val flagRank = cfg.flags.sortedBy { it.sortOrder }.mapIndexed { i, f -> f.id to i }.toMap()
            val sorted = if ((cfg.view as? ViewRef.Smart)?.kind == SmartKind.DO_NEXT) filtered
            else TaskViews.sort(filtered, cfg.sort, flagRank)
            val gm = if ((cfg.view as? ViewRef.Smart)?.kind == SmartKind.DO_NEXT) GroupMode.NONE else cfg.group
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
            } else TaskViews.group(sorted, gm, now, zone, dayStartMin)
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
            combine(repo.taskTagRefs, repo.taskContextRefs, repo.allFilters) { tt, tc, f -> Triple(tt, tc, f) },
        ) { all, v, on, refs ->
            if (!on) return@combine emptyList()
            val (ttRefs, tcRefs, filters) = refs
            val now = System.currentTimeMillis()
            val matched: Set<String> = when (v) {
                is ViewRef.FilterView -> {
                    val q = com.todocompanion.app.domain.view.Filters.parse(filters.firstOrNull { it.id == v.filterId }?.queryJson)
                    val tagsByTask = ttRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.tagId }.toSet() }
                    val ctxByTask = tcRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.contextId }.toSet() }
                    val hit = all.filter { com.todocompanion.app.domain.view.Filters.matches(q, it, tagsByTask[it.id].orEmpty(), ctxByTask[it.id].orEmpty(), now, zone) }.map { it.id }.toSet()
                    if (q.includeChildren) expandWithDescendants(hit, all) else hit
                }
                is ViewRef.TagView -> ttRefs.filter { it.tagId == v.tagId }.map { it.taskId }.toSet()
                is ViewRef.ContextView -> tcRefs.filter { it.contextId == v.contextId }.map { it.taskId }.toSet()
                else -> return@combine emptyList()
            }
            buildFilteredOutline(all.filter { !it.trashed }, matched)
        }.state(emptyList())

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
    fun taskRevisions(id: String): Flow<List<com.todocompanion.app.data.entity.TaskRevisionEntity>> = repo.taskRevisions(id)
    fun restoreRevision(revisionId: String) = viewModelScope.launch { repo.restoreRevision(revisionId) }
    /** How many times each task has been rescheduled — the procrastination signal (E2). */
    suspend fun rescheduleCounts(): Map<String, Int> =
        repo.getActivitiesOnce().filter { it.type == "rescheduled" }.groupingBy { it.taskId }.eachCount()

    // ---------- navigation ----------
    fun select(view: ViewRef) {
        currentView.value = view
        groupMode.value = if (view is ViewRef.ListView) GroupMode.NONE else GroupMode.DATE
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
        is ViewRef.Smart -> v.kind.title
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

    /** A legible breakdown of a task's Do-Next priority score — surfaced in the task detail. */
    fun explainScore(task: TaskEntity): PriorityEngine.ScoreBreakdown {
        val byId = tasks.value.associateBy { it.id }
        val cfg = settings.value.priorityConfig()
        val boost = PriorityEngine.dependencyBoosts(dependencies.value, byId, cfg)[task.id] ?: 0.0
        return PriorityEngine.explain(task, System.currentTimeMillis(), byId, cfg, boost)
    }

    // ---------- quick add ----------
    fun submitQuickAdd(text: String, opts: QuickAddOptions) = viewModelScope.launch {
        val parsed = QuickAddParser.parse(text)
        if (parsed.title.isBlank() && parsed.tags.isEmpty()) return@launch
        val due = opts.dueMillis ?: parsed.dateTime?.atZone(zone)?.toInstant()?.toEpochMilli()
        val level = opts.priority ?: parsed.priority
        // No priority chosen ⇒ "None" (importance/urgency 2), not Low.
        val imp = level?.importance ?: com.todocompanion.app.domain.priority.PriorityLevel.NONE.importance
        val urg = level?.urgency ?: com.todocompanion.app.domain.priority.PriorityLevel.NONE.urgency
        // ~list resolves to an existing list by name (case-insensitive); otherwise fall back to the
        // current view's target, which for a folder view is the folder itself (no list).
        val explicitList = opts.listId
            ?: parsed.list?.let { name -> lists.value.firstOrNull { !it.archived && it.name.equals(name, ignoreCase = true) }?.id }
        val (listId, folderId) = if (explicitList != null) explicitList to null else resolveAddTarget()
        val id = repo.createTask(listId, parsed.title.ifBlank { "Untitled" }, importance = imp, urgency = urg, dueDate = due, folderId = folderId)

        val tagIds = opts.tagIds.toMutableList()
        if (parsed.tags.isNotEmpty()) {
            val existing = repo.getTagsOnce().associateBy { it.name.lowercase() }
            parsed.tags.forEach { name ->
                tagIds += existing[name.lowercase()]?.id ?: UUID.randomUUID().toString().also { repo.upsertTag(TagEntity(it, name)) }
            }
        }
        if (tagIds.isNotEmpty()) repo.setTaskTags(id, tagIds.distinct())

        // @contexts resolve to existing contexts by name, creating any that are new.
        if (parsed.contexts.isNotEmpty()) {
            val existingCtx = repo.getContextsOnce().associateBy { it.name.lowercase() }
            val ctxIds = parsed.contexts.map { name ->
                existingCtx[name.lowercase()]?.id ?: UUID.randomUUID().toString().also { repo.upsertContext(ContextEntity(id = it, name = name)) }
            }
            repo.setTaskContexts(id, ctxIds.distinct())
        }

        // Natural-language recurrence ("every Tuesday", "monthly", "every 2 weeks") + optional note.
        // V9: also apply inline-token estimate and star.
        if (parsed.rrule != null || opts.note.isNotBlank() || opts.estimateMin != null || opts.star) repo.getTask(id)?.let {
            repo.saveTask(it.copy(
                rrule = parsed.rrule ?: it.rrule,
                note = opts.note.ifBlank { it.note },
                estimateMin = opts.estimateMin ?: it.estimateMin,
                star = it.star || opts.star,
            ))
        }

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
            repo.saveTask(t.copy(dueDate = nextDue, startDate = t.startDate?.plus(delta), rrule = newRule, completed = false, completedAt = null))
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
        repo.saveTask(t.copy(dueDate = nextDue, startDate = t.startDate?.plus(delta), rrule = newRule))
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
    fun trashMany(ids: Set<String>) = viewModelScope.launch { ids.forEach { repo.setTrashed(it, true) } }
    fun setPriorityMany(ids: Set<String>, level: PriorityLevel) = viewModelScope.launch {
        ids.mapNotNull { repo.getTask(it) }.forEach { repo.saveTask(it.copy(importance = level.importance, urgency = level.urgency)) }
    }
    fun moveMany(ids: Set<String>, listId: String) = viewModelScope.launch { ids.forEach { repo.moveToList(it, listId) } }
    fun trash(t: TaskEntity) = viewModelScope.launch {
        repo.setTrashed(t.id, true)
        undoEvents.tryEmit(UndoEvent(UndoKind.TRASHED, t.id, "Moved to Trash"))
    }
    fun undo(e: UndoEvent) = viewModelScope.launch {
        when (e.kind) {
            UndoKind.COMPLETED -> repo.getTask(e.taskId)?.let { repo.setCompleted(it, false) }
            UndoKind.ABANDONED -> repo.getTask(e.taskId)?.let { repo.setAbandoned(it, false) }
            UndoKind.TRASHED -> repo.setTrashed(e.taskId, false)
        }
    }
    fun restore(t: TaskEntity) = viewModelScope.launch { repo.setTrashed(t.id, false) }
    fun deleteForever(t: TaskEntity) = viewModelScope.launch { repo.deleteSubtree(t.id) }
    fun emptyTrash() = viewModelScope.launch { repo.emptyTrash() }
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
    val habitMatrixMode = MutableStateFlow(false)          // list (false) vs all-habits matrix (true)
    val habitDensity = MutableStateFlow(1)                 // matrix density: 0 compact · 1 medium · 2 large
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
    fun addAttachment(taskId: String, uri: Uri) = viewModelScope.launch {
        val cr = appCtx.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val name = displayNameOf(uri) ?: "attachment"
        val bytes = withContext(Dispatchers.IO) { runCatching { cr.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
        if (bytes == null) { toast("Could not read file"); return@launch }
        if (bytes.size > repo.maxAttachmentBytes) { toast("File too large (max 25 MB per file)"); return@launch }
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
    }
    fun removeAttachment(id: String) = viewModelScope.launch { repo.deleteAttachment(id) }

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
        val focusMin = focusSessions.value.filter { it.epochDay in weekDays }.sumOf { it.minutes }
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
        val focusWeek = focusSessions.value.filter { it.epochDay in weekDays }.sumOf { it.minutes }
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
        // V9: strip inline tokens (#t25, *, !) first and carry them into the created task.
        val tok = com.todocompanion.app.domain.nlp.QuickTokens.parse(text)
        val trimmed = tok.text.trim()
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
            submitQuickAdd(trimmed, QuickAddOptions(estimateMin = tok.estimateMin, star = tok.star))
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
    /** U13: publish a launcher shortcut per activity ("Track: Deep work") that fires the track deep link. */
    fun refreshTrackShortcuts() = viewModelScope.launch {
        com.todocompanion.app.util.TrackShortcuts.refresh(appCtx, timeActivities.value.filter { !it.archived })
    }
    fun updateTimeActivity(a: com.todocompanion.app.data.entity.TimeActivityEntity) = viewModelScope.launch { repo.upsertTimeActivity(a); refreshTimeWidget(); refreshTrackShortcuts() }
    fun deleteTimeActivity(id: String) = viewModelScope.launch { repo.deleteTimeActivity(id); refreshTimeWidget(); refreshTrackShortcuts() }
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
        plan.proposals.forEach { p -> repo.saveTask(p.task.copy(dueDate = p.newDueMillis, isAllDay = false)) }
        if (!settings.value.autoTrackPrompt) repo.saveSettings(settings.value.copy(autoTrackPrompt = true))
        rescheduleTrackPrompts()
        onDone(plan.proposals.size)
    }

    // ── W8 · per-item reminder mute ─────────────────────────────────────────────────────────────
    fun toggleMutedHabit(id: String) = viewModelScope.launch {
        val cur = settings.value.mutedHabits
        repo.saveSettings(settings.value.copy(mutedHabits = if (id in cur) cur - id else cur + id))
        com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(appCtx, repo)
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
            strength = hs.strength(h, done, skip, relapse, today)
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
    fun keystoneInsight(windowDays: Int = 60): String? {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val today = java.time.LocalDate.now(zone).toEpochDay()
        val universe = (0 until windowDays).map { today - it }
        val uniSet = universe.toSet()
        val metric = tasksCompletedByDay(today - windowDays + 1, today)
        var best: Pair<Double, String>? = null
        habits.value.filter { !it.paused && !it.archived && it.habitType != "break" }.forEach { h ->
            val done = habitCheckins.value.filter { it.habitId == h.id && it.status == "done" && hs.meetsGoal(h, it.count) }
                .map { it.epochDay }.filter { it in uniSet }.toSet()
            if (done.size < 5) return@forEach
            val k = com.todocompanion.app.domain.Reasoning.keystone(universe, metric, done)
            if (k.withN >= 5 && k.withoutN >= 5 && k.avgWith > k.avgWithout && k.lift >= 0.15) {
                val pct = Math.round(k.lift * 100).toInt()
                val s = "On days you keep ‘${h.name}’, you finish ${pct}% more tasks (${fmt1(k.avgWith)} vs ${fmt1(k.avgWithout)} a day)."
                if (best == null || k.lift > best!!.first) best = k.lift to s
            }
        }
        return best?.second
    }

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
        val cal = planVsActualWeek().calibration
        val f = com.todocompanion.app.domain.Reasoning.forecast(remaining.map { est(it) }, cal, availMin)
        val slipTitles = if (f.willSlip > 0) remaining.takeLast(f.willSlip).map { it.title } else emptyList()
        return DayForecast(f.willFinish, f.willSlip, f.neededMin, availMin, f.calibrated, slipTitles)
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
    fun applyRhythmSuggestion(s: RhythmSuggestion) {
        val h = habits.value.firstOrNull { it.id == s.habitId } ?: return
        val days = s.weekdays.sorted().joinToString(",")
        saveHabit(h.copy(freqType = "weekly", scheduleDays = days))
        toast("Schedule matched to your rhythm")
    }

    // ── X7 · insights feed — what your data noticed this week ─────────────────────────────────────
    fun insightsFeed(): List<String> {
        val out = ArrayList<String>()
        keystoneInsight()?.let { out += it }
        val cal = planVsActualWeek().calibration
        if (cal != null && kotlin.math.abs(cal - 1.0) >= 0.2) {
            val pct = Math.round((cal - 1.0) * 100).toInt()
            out += if (cal > 1) "Your tasks take about ${pct}% longer than you estimate — the forecast corrects for it."
                   else "You beat your estimates by about ${-pct}% — you could plan a little more into a day."
        }
        focusPeakLabel()?.let { out += "You focus most between $it — a good window to protect for your hardest work." }
        crossTypeTagReport(7).firstOrNull()?.let { line ->
            if (line.tag.isNotBlank())
                out += "‘${line.tag}’ took the most of your week: ${line.minutes / 60}h ${line.minutes % 60}m, ${line.tasksDone} task${if (line.tasksDone == 1) "" else "s"}, ${line.habitDays} habit-day${if (line.habitDays == 1) "" else "s"}."
        }
        momentumLinks(60).firstOrNull()?.let { out += it }
        return out.distinct().take(4)
    }

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
        repo.addManualTimeEntry(act, start, end, note = "", taskId = b.taskId)
        refreshTimeWidget()
        toast("Logged ${b.durMin}m to ‘${b.title}’")
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
    fun cycleHabit(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, current: Int) = viewModelScope.launch {
        repo.cycleCheckin(h.id, epochDay, h.targetPerDay, current, h.clickIncrement, h.extraTarget)
        refreshHabitWidgets(); celebrateIfRewardReached(h); awardIfNewlyDone(h, epochDay, current)
    }
    /** Numeric / exact value entry for a day (also used to record a break-habit relapse amount). */
    fun setHabitValue(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, count: Int) = viewModelScope.launch {
        val old = repo.getHabitCheckinsOnce().firstOrNull { it.habitId == h.id && it.epochDay == epochDay }?.count ?: 0
        repo.setCheckinValue(h.id, epochDay, count); refreshHabitWidgets(); celebrateIfRewardReached(h); awardIfNewlyDone(h, epochDay, old)
    }
    /** V4/V12: when a build habit crosses into "done", earn a momentum point and show a random encouragement. */
    private suspend fun awardIfNewlyDone(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, oldCount: Int) {
        val hs = com.todocompanion.app.domain.habit.HabitStats
        if (h.habitType == "break") return
        val newCount = repo.getHabitCheckinsOnce().firstOrNull { it.habitId == h.id && it.epochDay == epochDay }?.count ?: 0
        if (hs.meetsGoal(h, newCount) && !hs.meetsGoal(h, oldCount)) {
            repo.awardPoints(1)
            h.encouragementList().takeIf { it.isNotEmpty() }?.let { toast(it.random()) }
        }
    }
    fun skipHabitDay(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, reason: String = "") = viewModelScope.launch {
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
        val byDay = focusSessions.value.groupBy { it.epochDay }.mapValues { e -> e.value.sumOf { it.minutes } }
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

    // ---------- focus ----------
    fun recordFocus(startMillis: Long, minutes: Int, kind: String, taskId: String? = null, habitId: String? = null) = viewModelScope.launch {
        if (minutes <= 0) return@launch
        val day = java.time.Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate().toEpochDay()
        repo.addFocusSession(day, startMillis, minutes, kind, taskId)
        // T1 (I1): when the Time module is on, mirror this session onto the one tracker timeline so that
        // "time" is counted once, from a single source. The activity is the task's / habit's linked one,
        // else a generic "Focus" activity.
        val M = com.todocompanion.app.domain.Modules
        if (M.isEnabled(settings.value, M.TIME)) {
            val actId = taskId?.let { tid -> tasks.value.firstOrNull { it.id == tid }?.defaultActivityId }
                ?: habitId?.let { hid -> habits.value.firstOrNull { it.id == hid }?.timeActivityId }
                ?: repo.ensureFocusActivity()
            repo.mirrorFocusInterval(startMillis, startMillis + minutes * 60_000L, actId, taskId, habitId)
        }
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
                rrule = newRule, completed = false, completedAt = null, createdAt = nowMs, updatedAt = nowMs,
                sortOrder = t.sortOrder + 0.0001))
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
    fun createFlag(name: String, colorArgb: Long, icon: String = "flag") = viewModelScope.launch { repo.createFlag(name, colorArgb, icon) }
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
    fun createTag(name: String, parentId: String? = null) = viewModelScope.launch { repo.upsertTag(TagEntity(UUID.randomUUID().toString(), name.trim(), parentId = parentId)) }
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
    fun createContext(name: String, parentId: String? = null) = viewModelScope.launch { repo.upsertContext(ContextEntity(id = UUID.randomUUID().toString(), name = name.trim(), parentId = parentId)) }
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

    /** Import tasks from a Todoist/TickTick CSV or MLO OPML file. Returns (ok, message). */
    fun importExternal(uri: Uri, onDone: (Boolean, String) -> Unit) = viewModelScope.launch {
        val text = runCatching { appCtx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        importExternalText(text, onDone)
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
                    val tagExisting = repo.getTagsOnce().associateBy { it.name.lowercase() }
                    val ids = row.tags.map { name -> tagExisting[name.lowercase()]?.id ?: UUID.randomUUID().toString().also { repo.upsertTag(TagEntity(it, name)) } }
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
    // ---- Full in-app file browser (needs storage access; browse + search the real filesystem) ----
    fun canBrowseStorage(): Boolean = com.todocompanion.app.util.FileExport.canBrowseStorage(appCtx)
    fun browseDir(dir: java.io.File, allTypes: Boolean = false, onDone: (List<com.todocompanion.app.util.FileExport.Entry>) -> Unit) = viewModelScope.launch {
        onDone(withContext(Dispatchers.IO) { com.todocompanion.app.util.FileExport.listDir(dir, allTypes) })
    }
    fun searchFilesystem(q: String, onDone: (List<java.io.File>) -> Unit) = viewModelScope.launch {
        onDone(withContext(Dispatchers.IO) { com.todocompanion.app.util.FileExport.searchFiles(q) })
    }
    /** Import a browsed file by wrapping it as a SavedFile and reusing the restore pipeline. */
    fun importBrowsedFile(file: java.io.File, onDone: (Boolean, String) -> Unit) {
        restoreSaved(com.todocompanion.app.util.FileExport.SavedFile(file.name, file.parent ?: "", file.lastModified(), null, file), onDone)
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
        val text = withContext(Dispatchers.IO) {
            runCatching { if (s.uri != null) appCtx.contentResolver.openInputStream(s.uri)?.bufferedReader()?.use { it.readText() } else s.file?.readText() }.getOrNull()
        }
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
        val name = displayNameOf(uri) ?: ""
        val text = withContext(Dispatchers.IO) {
            runCatching { appCtx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        }
        if (text.isNullOrBlank()) { onDone(false, "Couldn't read that file. Try 'Share → ToDo Companion' from your file manager."); return@launch }
        val external = name.endsWith(".csv", true) || name.endsWith(".opml", true) ||
            name.endsWith(".md", true) || name.endsWith(".txt", true) || name.endsWith(".ics", true)
        val looksJson = !external && (name.endsWith(".json", true) ||
            text.trimStart().firstOrNull()?.let { it == '{' || it == '[' } == true)
        if (looksJson) {
            val ok = runCatching {
                val plain = com.todocompanion.app.data.sync.Crypto.decrypt(text, settings.value.syncPassphrase) ?: text
                if (merge) repo.importJsonMerge(plain) else repo.importJsonReplace(plain)
                AlarmScheduler.rescheduleAll(appCtx, repo); true
            }.getOrDefault(false)
            val verb = if (merge) "Merged" else "Restored"
            onDone(ok, if (ok) "$verb ${name.ifBlank { "your backup" }}" else "That file isn't a valid ToDo Companion backup")
        } else importExternalText(text, onDone)
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
