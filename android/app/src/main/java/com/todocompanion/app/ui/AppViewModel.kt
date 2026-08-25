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
)

enum class UndoKind { COMPLETED, ABANDONED, TRASHED }
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
    private val wsTasks: Flow<List<TaskEntity>> =
        combine(repo.allTasks, activeListIds) { all, ids -> all.filter { it.listId in ids } }

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
        val today = java.time.LocalDate.now(zone)
        val capacityMin = (0 until days).sumOf { settings.value.capacityHoursFor(today.plusDays(it.toLong()).dayOfWeek) * 60 }
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

    fun autoScheduleToday(onDone: (Int, Int) -> Unit = { _, _ -> }) = viewModelScope.launch {
        val s = settings.value
        val startHour = s.workStartHour.coerceIn(0, 23)
        val endHour = s.workEndHour.coerceIn(startHour + 1, 24)
        val dayStart = java.time.LocalDate.now(zone).atStartOfDay(zone)
        val windowEnd = dayStart.plusHours(endHour.toLong())
        // Rhythm-aware start: begin at your learned peak hour when it sits inside the work window,
        // so the highest-energy tasks (placed first below) land on your best time of day.
        val peak = peakHour().coerceIn(startHour, endHour - 1)
        var cursor = dayStart.plusHours(peak.toLong())
        // Never schedule into the past: if the window has already begun, start from the next quarter-hour.
        val now = System.currentTimeMillis()
        if (cursor.toInstant().toEpochMilli() < now) {
            val nowZ = java.time.Instant.ofEpochMilli(now).atZone(zone).withSecond(0).withNano(0)
            cursor = nowZ.withMinute(0).plusMinutes((((nowZ.minute) / 15) + 1) * 15L)
        }
        val todayDate = java.time.LocalDate.now(zone)
        val ranked = doNextRanked().filter { t ->
            t.dueDate == null || java.time.Instant.ofEpochMilli(t.dueDate!!).atZone(zone).toLocalDate() <= todayDate
        }
        // Stable partition: high-energy (deep-work) tasks keep their ranked order but take the prime
        // slots first at the peak; lighter tasks fill the time after.
        val candidates = ranked.filter { (it.energy ?: 0) >= 3 } + ranked.filter { (it.energy ?: 0) < 3 }
        var scheduled = 0; var skipped = 0
        for (t in candidates) {
            val dur = (t.estimateMin ?: t.estimateMax ?: t.durationMin ?: 30).coerceIn(10, 480)
            val end = cursor.plusMinutes(dur.toLong())
            if (end.isAfter(windowEnd)) { skipped++; continue }
            repo.saveTask(t.copy(dueDate = cursor.toInstant().toEpochMilli(), isAllDay = false))
            cursor = end
            scheduled++
        }
        onDone(scheduled, skipped)
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
        outlineMode.value = false
        // Remember the last place, when the user opted into resuming there.
        val s = settings.value
        if (s.resumeLastView) viewModelScope.launch { repo.saveSettings(repo.settingsSnapshot().copy(lastViewRef = com.todocompanion.app.domain.view.ViewTabs.refOf(view))) }
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
        val imp = level?.importance ?: 3
        val urg = level?.urgency ?: 3
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
        if (parsed.rrule != null || opts.note.isNotBlank()) repo.getTask(id)?.let {
            repo.saveTask(it.copy(rrule = parsed.rrule ?: it.rrule, note = opts.note.ifBlank { it.note }))
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
            }
        }
    }

    /** A short, pleasant two-note chime on completing a task. Built-in tones — no bundled audio. */
    private fun playCompletionChime() = runCatching {
        val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70)
        tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
        viewModelScope.launch { kotlinx.coroutines.delay(600); tg.release() }
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
    fun deleteHabit(id: String) = viewModelScope.launch { repo.deleteHabit(id); com.todocompanion.app.widget.HabitsWidget.refresh(appCtx) }
    fun cycleHabit(h: com.todocompanion.app.data.entity.HabitEntity, epochDay: Long, current: Int) = viewModelScope.launch {
        repo.cycleCheckin(h.id, epochDay, h.targetPerDay, current)
        com.todocompanion.app.widget.HabitsWidget.refresh(appCtx)
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
    fun recordFocus(startMillis: Long, minutes: Int, kind: String, taskId: String? = null) = viewModelScope.launch {
        if (minutes <= 0) return@launch
        val day = java.time.Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate().toEpochDay()
        repo.addFocusSession(day, startMillis, minutes, kind, taskId)
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
    fun renameContext(c: ContextEntity, name: String) = viewModelScope.launch { repo.upsertContext(c.copy(name = name.trim())) }
    fun setContextColor(c: ContextEntity, argb: Long?) = viewModelScope.launch { repo.upsertContext(c.copy(colorArgb = argb)) }
    /** Give a context a geofence so arriving there auto-surfaces its tasks (E3). */
    fun setContextLocation(c: ContextEntity, lat: Double, lng: Double, radiusM: Double = 150.0) = viewModelScope.launch {
        val nc = c.copy(latitude = lat, longitude = lng, radiusM = radiusM)
        repo.upsertContext(nc)
        com.todocompanion.app.reminders.LocationReminders.registerContext(appCtx, nc)
    }
    fun clearContextLocation(c: ContextEntity) = viewModelScope.launch {
        com.todocompanion.app.reminders.LocationReminders.unregisterContext(appCtx, c)
        repo.upsertContext(c.copy(latitude = null, longitude = null, radiusM = null))
    }
    fun setContextActive(c: ContextEntity, active: Boolean) = viewModelScope.launch { repo.upsertContext(c.copy(active = active)) }
    fun setContextHours(c: ContextEntity, json: String?) = viewModelScope.launch { repo.upsertContext(c.copy(openHoursJson = json)) }
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
    /** A geofence-style reminder that fires on arriving at / leaving a point. Fully on-device. */
    fun addLocationReminder(task: TaskEntity, lat: Double, lng: Double, radiusM: Double, placeName: String?, onEnter: Boolean) = viewModelScope.launch {
        val r = ReminderEntity(UUID.randomUUID().toString(), taskId = task.id, type = "location",
            latitude = lat, longitude = lng, radiusM = radiusM, placeName = placeName, onEnter = onEnter)
        repo.upsertReminder(r)
        com.todocompanion.app.reminders.LocationReminders.register(appCtx, r, task)
    }
    fun deleteReminder(reminder: ReminderEntity, task: TaskEntity) = viewModelScope.launch {
        repo.deleteReminder(reminder.id)
        if (reminder.type == "location") com.todocompanion.app.reminders.LocationReminders.unregister(appCtx, reminder, task)
        else AlarmScheduler.cancel(appCtx, reminder, task)
    }
    /** Called after the user grants location permission, to arm any pending place reminders. */
    fun rearmLocationReminders() = viewModelScope.launch {
        com.todocompanion.app.reminders.LocationReminders.registerAll(appCtx, repo)
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
    // Drag-reorder persistence for the drawer sections.
    fun setTagOrder(ids: List<String>) = viewModelScope.launch { repo.setTagOrder(ids) }
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
        val ok = runCatching {
            val text = appCtx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@runCatching null
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
