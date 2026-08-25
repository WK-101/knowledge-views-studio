package com.todocompanion.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoNotDisturb
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.domain.habit.HabitStats
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.Density
import com.todocompanion.app.domain.SwipeAction
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.domain.view.SmartKind
import com.todocompanion.app.domain.view.ViewRef
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.FlagStar
import com.todocompanion.app.ui.components.PriorityCheckbox
import com.todocompanion.app.ui.components.TaskMeta
import com.todocompanion.app.ui.components.rowVerticalPadding

@Composable
fun TasksScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, modifier: Modifier = Modifier) {
    val outline by vm.outlineMode.collectAsState()
    val settings by vm.settings.collectAsState()
    val view by vm.currentView.collectAsState()

    if (outline && vm.canOutline()) { OutlineList(vm, settings.density, onOpenTask, modifier); return }

    val hierarchy by vm.filterHierarchy.collectAsState()
    if (hierarchy && vm.canHierarchy()) { HierarchyList(vm, settings.density, onOpenTask, modifier); return }

    val groups by vm.groups.collectAsState()
    val isTrash = (view as? ViewRef.Smart)?.kind == SmartKind.TRASH
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    // Deleting from Trash is permanent — confirm first.
    var pendingPermanentDelete by remember { mutableStateOf<TaskEntity?>(null) }

    // Per-task @context / #tag lookups, MLO-style detail on each row.
    val allTags by vm.tags.collectAsState()
    val allContexts by vm.contexts.collectAsState()
    val tagRefs by vm.taskTags.collectAsState()
    val ctxRefs by vm.taskContexts.collectAsState()
    val tagsByTask = remember(tagRefs, allTags) {
        val byId = allTags.associateBy { it.id }
        tagRefs.groupBy { it.taskId }.mapValues { (_, refs) -> refs.mapNotNull { byId[it.tagId] }.map { it.name to it.colorArgb } }
    }
    val ctxByTask = remember(ctxRefs, allContexts) {
        val byId = allContexts.associateBy { it.id }
        ctxRefs.groupBy { it.taskId }.mapValues { (_, refs) -> refs.mapNotNull { byId[it.contextId] }.map { it.name to it.colorArgb } }
    }
    // In smart lists (which span lists), show each task's list as a detail — like MLO/TickTick.
    val lists by vm.lists.collectAsState()
    val showList = view is ViewRef.Smart
    val listNameById = remember(lists) { lists.associate { it.id to it.name } }

    if (groups.isEmpty() || groups.all { it.tasks.isEmpty() }) { EmptyState(view); return }

    // Manual sort on a single ungrouped list → long-press drag to reorder.
    val sortMode by vm.sortMode.collectAsState()
    if (sortMode == com.todocompanion.app.domain.view.SortMode.MANUAL && groups.size == 1 && groups.first().title.isBlank() && !isTrash) {
        ManualReorderList(vm, groups.first().tasks, settings.density, ctxByTask, tagsByTask,
            listNameOf = { id -> if (showList) listNameById[id]?.takeIf { it != "Inbox" } else null },
            rightNear = settings.swipeRight, rightFar = settings.swipeRightFar, leftNear = settings.swipeLeft, leftFar = settings.swipeLeftFar,
            onOpenTask = onOpenTask, modifier = modifier)
        return
    }

    // Multi-select: long-press a row to enter selection mode; a bottom action bar batches edits.
    var selected by remember { mutableStateOf(setOf<String>()) }
    val selectionMode = selected.isNotEmpty()
    fun toggleSel(id: String) { selected = if (id in selected) selected - id else selected + id }
    // Tell the shell so it can hide the add FAB while the selection bar is up (no overlap).
    androidx.compose.runtime.LaunchedEffect(selectionMode) { vm.selectionActive.value = selectionMode }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.selectionActive.value = false } }
    // Back exits selection first (clears it and stays in the app) instead of leaving the screen.
    androidx.activity.compose.BackHandler(enabled = selectionMode) { selected = emptySet() }
    val allLists by vm.lists.collectAsState()

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = if (selectionMode) 120.dp else 100.dp)) {
            // Workload forecast bar at the top of Next-7-Days: committed estimate vs daily capacity.
            if ((view as? ViewRef.Smart)?.kind == SmartKind.NEXT7) item(key = "workload") { WorkloadStrip(vm) }
            // Fusion F1: today's still-due habits sit alongside tasks in Today / Do-Next.
            (view as? ViewRef.Smart)?.kind?.let { k ->
                if (k == SmartKind.TODAY || k == SmartKind.DO_NEXT) item(key = "habitsdue") { HabitsDueStrip(vm) }
            }
            items(groups, key = { it.key }) { group ->
                val open = collapsed[group.key] != true
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    if (group.title.isNotBlank()) GroupHeader(group.title, group.tasks.size, open) { collapsed[group.key] = open }
                    AnimatedVisibility(visible = open) {
                        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                group.tasks.forEachIndexed { i, task ->
                                  androidx.compose.runtime.key(task.id) {
                                    if (i > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                                    TaskListItem(task, settings.density,
                                        contexts = ctxByTask[task.id].orEmpty(), tags = tagsByTask[task.id].orEmpty(),
                                        listName = if (showList) listNameById[task.listId]?.takeIf { it != "Inbox" } else null,
                                        rightNear = if (isTrash) SwipeAction.COMPLETE else settings.swipeRight,
                                        rightFar = if (isTrash) SwipeAction.NONE else settings.swipeRightFar,
                                        leftNear = if (isTrash) SwipeAction.TRASH else settings.swipeLeft,
                                        leftFar = if (isTrash) SwipeAction.NONE else settings.swipeLeftFar, isTrash = isTrash,
                                        selected = task.id in selected, selectionMode = selectionMode, onLongPress = { toggleSel(task.id) },
                                        onOpen = { if (selectionMode) toggleSel(task.id) else onOpenTask(task.id) },
                                        onAct = { a -> onSwipe(vm, a, task, isTrash, { pendingPermanentDelete = it }) { onOpenTask(task.id) } },
                                        onCycleFlag = { vm.cycleFlag(task) }, onToggleStar = { vm.toggleStar(task) },
                                        onSetPriority = { vm.setPriority(task, it) })
                                  }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (selectionMode) SelectionBar(
            count = selected.size, lists = allLists.filter { !it.archived },
            onComplete = { vm.completeMany(selected); selected = emptySet() },
            onDelete = { vm.trashMany(selected); selected = emptySet() },
            onPriority = { lvl -> vm.setPriorityMany(selected, lvl); selected = emptySet() },
            onMove = { listId -> vm.moveMany(selected, listId); selected = emptySet() },
            onClear = { selected = emptySet() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        pendingPermanentDelete?.let { t ->
            AlertDialog(
                onDismissRequest = { pendingPermanentDelete = null },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.deleteForever(t); pendingPermanentDelete = null }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingPermanentDelete = null }) { Text("Cancel") } },
                title = { Text("Delete permanently?") },
                text = { Text("“${t.title.ifBlank { "Untitled" }}” and its subtasks will be erased for good. This can't be undone.") },
            )
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int, lists: List<com.todocompanion.app.data.entity.ListEntity>,
    onComplete: () -> Unit, onDelete: () -> Unit, onPriority: (PriorityLevel) -> Unit, onMove: (String) -> Unit, onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Clear selection") }
            Text("$count", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.IconButton(onClick = onComplete) { Icon(Icons.Filled.Check, "Complete") }
            var prioMenu by remember { mutableStateOf(false) }
            Box {
                androidx.compose.material3.IconButton(onClick = { prioMenu = true }) { Icon(Icons.Outlined.Flag, "Priority") }
                androidx.compose.material3.DropdownMenu(expanded = prioMenu, onDismissRequest = { prioMenu = false }) {
                    listOf(PriorityLevel.HIGH to "High", PriorityLevel.MEDIUM to "Medium", PriorityLevel.LOW to "Low", PriorityLevel.NONE to "None").forEach { (lvl, l) ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(l) }, onClick = { prioMenu = false; onPriority(lvl) })
                    }
                }
            }
            var moveMenu by remember { mutableStateOf(false) }
            Box {
                androidx.compose.material3.IconButton(onClick = { moveMenu = true }) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Move") }
                androidx.compose.material3.DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                    lists.forEach { l -> androidx.compose.material3.DropdownMenuItem(text = { Text(l.name) }, onClick = { moveMenu = false; onMove(l.id) }) }
                }
            }
            androidx.compose.material3.IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun onSwipe(vm: AppViewModel, action: SwipeAction, task: TaskEntity, isTrash: Boolean, onConfirmDelete: (TaskEntity) -> Unit, onOpen: () -> Unit) {
    if (isTrash) { if (action == SwipeAction.COMPLETE) vm.restore(task) else onConfirmDelete(task); return }
    if (!vm.applyAction(action, task)) onOpen()
}

@Composable
private fun ManualReorderList(
    vm: AppViewModel, tasks: List<TaskEntity>, density: Density,
    ctxByTask: Map<String, List<Pair<String, Long?>>>, tagsByTask: Map<String, List<Pair<String, Long?>>>,
    listNameOf: (String) -> String?,
    rightNear: SwipeAction, rightFar: SwipeAction, leftNear: SwipeAction, leftFar: SwipeAction,
    onOpenTask: (String) -> Unit, modifier: Modifier,
) {
    // Local working order for the drag gesture.
    val listState = rememberLazyListState()
    var draggingId by remember { mutableStateOf<String?>(null) }
    var delta by remember { mutableFloatStateOf(0f) }
    var items by remember { mutableStateOf(tasks) }
    // Resync with upstream whenever the tasks change (reorder, add/remove, OR an edited
    // field like star/flag/title) — but never mid-drag, so the gesture isn't disrupted.
    androidx.compose.runtime.LaunchedEffect(tasks) { if (draggingId == null) items = tasks }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().androidx_pointerReorder(
            listState = listState,
            draggingId = { draggingId },
            setDraggingId = { draggingId = it },
            delta = { delta }, setDelta = { delta = it },
            indexOfId = { id -> items.indexOfFirst { it.id == id } },
            moveItem = { from, to -> items = items.toMutableList().also { it.add(to, it.removeAt(from)) } },
            onCommit = { vm.setManualOrder(items.map { it.id }) },
        ),
        contentPadding = PaddingValues(top = 6.dp, bottom = 100.dp, start = 12.dp, end = 12.dp),
    ) {
        itemsIndexed(items, key = { _, t -> t.id }) { _, task ->
            val dragging = task.id == draggingId
            Surface(
                Modifier
                    .padding(vertical = 3.dp)
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) delta else 0f },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = if (dragging) 8.dp else 1.dp,
            ) {
                // Same reveal-action swipe as the flat list; disabled mid-reorder so the two
                // gestures never fight. Long-press still starts a drag (handled on the LazyColumn).
                SwipeActionBox(
                    taskId = task.id, rightNear = rightNear, rightFar = rightFar, leftNear = leftNear, leftFar = leftFar,
                    enabled = draggingId == null, isTrashRestore = false,
                    onAct = { a -> onSwipe(vm, a, task, false, {}) { onOpenTask(task.id) } },
                ) {
                    ReorderRow(task, density, ctxByTask[task.id].orEmpty(), tagsByTask[task.id].orEmpty(), listNameOf(task.id),
                        onOpen = { onOpenTask(task.id) }, onToggle = { vm.toggleComplete(task) },
                        onCycleFlag = { vm.cycleFlag(task) }, onToggleStar = { vm.toggleStar(task) },
                        onSetPriority = { vm.setPriority(task, it) })
                }
            }
        }
    }
}

@Composable
private fun ReorderRow(
    task: TaskEntity, density: Density, contexts: List<Pair<String, Long?>>, tags: List<Pair<String, Long?>>, listName: String?,
    onOpen: () -> Unit, onToggle: () -> Unit, onCycleFlag: () -> Unit, onToggleStar: () -> Unit,
    onSetPriority: ((PriorityLevel) -> Unit)? = null,
) {
    val level = PriorityLevel.from(task.importance, task.urgency)
    val done = task.completed || task.abandoned
    Row(
        // Long-press anywhere on the row starts the drag — no handle needed.
        Modifier.fillMaxWidth().clickable { onOpen() }.padding(start = 8.dp, end = 8.dp, top = rowVerticalPadding(density), bottom = rowVerticalPadding(density)),
        verticalAlignment = Alignment.Top,
    ) {
        if (task.isNote) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Notes, "Note", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
        } else PriorityCheckbox(task.completed, level, { onToggle() }, onSetLevel = onSetPriority)
        Spacer(Modifier.width(2.dp))
        Column(Modifier.weight(1f).padding(top = 8.dp, bottom = 2.dp)) {
            TaskTitle(task, done)
            com.todocompanion.app.ui.components.TaskLeftMeta(task.dueDate, task.note, !task.rrule.isNullOrBlank())
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.widthIn(max = 116.dp), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlagStar(task.flagColorArgb, task.star, onCycleFlag, onToggleStar, iconSize = com.todocompanion.app.ui.components.flagStarSize(density))
            }
            com.todocompanion.app.ui.components.TaskTrailingLabels(contexts, tags, listName)
        }
    }
}

/** Long-press reorder gesture for a LazyColumn, factored out to keep the list readable. */
private fun Modifier.androidx_pointerReorder(
    listState: androidx.compose.foundation.lazy.LazyListState,
    draggingId: () -> String?, setDraggingId: (String?) -> Unit,
    delta: () -> Float, setDelta: (Float) -> Unit,
    indexOfId: (String) -> Int, moveItem: (Int, Int) -> Unit, onCommit: () -> Unit,
): Modifier = this.pointerInput(Unit) {
    detectDragGesturesAfterLongPress(
        onDragStart = { off ->
            val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull { off.y.toInt() in it.offset..(it.offset + it.size) }
            setDraggingId(hit?.key as? String); setDelta(0f)
        },
        onDragEnd = { if (draggingId() != null) onCommit(); setDraggingId(null); setDelta(0f) },
        onDragCancel = { setDraggingId(null); setDelta(0f) },
        onDrag = { change, drag ->
            change.consume()
            setDelta(delta() + drag.y)
            val id = draggingId()
            val info = listState.layoutInfo.visibleItemsInfo
            val cur = id?.let { d -> info.firstOrNull { it.key == d } }
            if (id != null && cur != null) {
                val center = cur.offset + cur.size / 2 + delta()
                val target = info.firstOrNull { it.key != id && center.toInt() in it.offset..(it.offset + it.size) }
                if (target != null) {
                    val from = indexOfId(id); val to = indexOfId(target.key as String)
                    if (from >= 0 && to >= 0 && from != to) {
                        moveItem(from, to)
                        setDelta(delta() + (cur.offset - target.offset))
                    }
                }
            }
        },
    )
}

/** Compact 7-day workload forecast: each upcoming day's committed estimate against the daily
 *  capacity (Settings → Planning). Over-committed days show a red bar. Planning intelligence
 *  neither MLO nor TickTick offers — and it reuses the estimate every task already carries. */
/** Fusion F1: the habits still due today, shown at the top of Today / Do-Next so they compete for
 *  attention with tasks. Tap a chip to complete it — the answer to "what now?" includes habits. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HabitsDueStrip(vm: AppViewModel) {
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val today = java.time.LocalDate.now().toEpochDay()
    val due = habits.filter { h ->
        val hc = checkins.filter { it.habitId == h.id }
        val doneDays = hc.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
        HabitStats.dueToday(h, today, doneDays, hc.firstOrNull { it.epochDay == today }?.count ?: 0)
    }
    if (due.isEmpty()) return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.padding(12.dp)) {
            Text("Habits · ${due.size} due today", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                due.forEach { h ->
                    val color = h.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                    Row(
                        Modifier.clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = .12f))
                            .clickable { vm.setHabitValue(h, today, h.targetPerDay.coerceAtLeast(1)) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text((h.emoji?.plus(" ") ?: "") + h.name, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Check, "Complete", tint = color, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkloadStrip(vm: AppViewModel) {
    val tasks by vm.tasks.collectAsState()
    val settings by vm.settings.collectAsState()
    val habits by vm.habits.collectAsState()   // Fusion F3: habit time counts toward the daily load.
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    // Each day's capacity in minutes — a per-weekday override when set, else the flat daily figure.
    fun capMin(d: java.time.LocalDate) = (settings.capacityHoursFor(d.dayOfWeek) * 60).coerceAtLeast(30)
    // Minutes a habit costs on a day it's scheduled: a time habit uses its target, else a light default.
    fun habitMinutes(h: HabitEntity) = if (h.unit?.startsWith("min") == true) h.targetPerDay.coerceAtLeast(1) else 10
    val loads = remember(tasks, habits, settings.dailyCapacityHours, settings.capacityByDay) {
        (0..6).map { off ->
            val d = today.plusDays(off.toLong())
            val epochDay = d.toEpochDay()
            val dayTasks = tasks.filter {
                !it.completed && !it.trashed && !it.abandoned && it.dueDate != null &&
                    java.time.Instant.ofEpochMilli(it.dueDate!!).atZone(zone).toLocalDate() == d
            }
            val dayHabits = habits.filter { !it.paused && (HabitStats.isExpectedDay(it, epochDay) || it.freqType == HabitStats.FREQ_TIMES_WEEK || it.freqType == HabitStats.FREQ_TIMES_MONTH) }
            val min = dayTasks.sumOf { it.estimateMin ?: it.estimateMax ?: it.durationMin ?: 0 } + dayHabits.sumOf { habitMinutes(it) }
            Triple(d, min, dayTasks.size + dayHabits.size)
        }
    }
    if (loads.all { it.third == 0 }) return
    val over = loads.count { it.second > capMin(it.first) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Workload · next 7 days", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    if (over == 0) "On track" else "$over day${if (over == 1) "" else "s"} over",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (over == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "Each bar sums the time estimate of tasks due that day, measured against your daily capacity.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.size(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                loads.forEach { (d, min, count) ->
                    val cap = capMin(d).toFloat()
                    val frac = (min.toFloat() / cap).coerceIn(0f, 1.25f)
                    val overCap = min > cap
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (min > 0) "${(min + 30) / 60}h" else if (count > 0) "$count" else "",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                        Spacer(Modifier.size(3.dp))
                        Box(
                            Modifier.fillMaxWidth().height((5 + frac * 44).dp).clip(RoundedCornerShape(5.dp))
                                .background(if (overCap) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary.copy(alpha = if (count == 0) .14f else .85f)),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (d == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(view: ViewRef? = null) {
    val kind = (view as? ViewRef.Smart)?.kind
    val (icon, title, subtitle) = when (kind) {
        SmartKind.INBOX -> Triple(Icons.Outlined.Inbox, "Inbox zero", "Nothing waiting to be sorted")
        SmartKind.TODAY -> Triple(Icons.Outlined.WbSunny, "Nothing due today", "Enjoy the clear day")
        SmartKind.TOMORROW -> Triple(Icons.Outlined.Coffee, "Tomorrow is open", "No tasks scheduled yet")
        SmartKind.NEXT7 -> Triple(Icons.Outlined.DateRange, "The week ahead is clear", "Nothing due in the next 7 days")
        SmartKind.COMPLETED -> Triple(Icons.Outlined.CheckCircle, "No completed tasks", "Finished tasks will collect here")
        SmartKind.WONT_DO -> Triple(Icons.Outlined.DoNotDisturb, "Nothing dropped", "Tasks you won't do will land here")
        SmartKind.TRASH -> Triple(Icons.Outlined.Delete, "Trash is empty", "Deleted tasks stay here for a while")
        SmartKind.FLAGGED -> Triple(Icons.Outlined.Flag, "Nothing flagged", "Flag a task to keep it in view")
        SmartKind.GOALS -> Triple(Icons.Outlined.EmojiEvents, "No goals yet", "Mark a task as a goal to track it here")
        SmartKind.SCHEDULED -> Triple(Icons.Outlined.Schedule, "Nothing scheduled", "Tasks with a date will appear here")
        SmartKind.NEEDS_ATTENTION -> Triple(Icons.Outlined.Notifications, "Nothing neglected", "Undated tasks you haven't touched in a while show up here")
        SmartKind.WAITING -> Triple(Icons.Outlined.HourglassEmpty, "Nothing on hold", "Tasks blocked by an unfinished prerequisite show up here")
        else -> Triple(Icons.Outlined.CheckCircle, "All clear", "Tap + to add a task")
    }
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.size(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun HierarchyList(vm: AppViewModel, density: Density, onOpenTask: (String) -> Unit, modifier: Modifier) {
    val rows by vm.hierarchyRows.collectAsState()
    if (rows.isEmpty()) { EmptyState(); return }
    Column(modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Text("Matches shown in their outline — ancestors dimmed", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        rows.forEachIndexed { i, row ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                            com.todocompanion.app.ui.components.TaskRow(row, density,
                                onClick = { onOpenTask(row.task.id) },
                                onToggleComplete = { vm.toggleComplete(row.task) },
                                onToggleCollapse = {}, onCycleFlag = { vm.cycleFlag(row.task) }, onToggleStar = { vm.toggleStar(row.task) },
                                onDelete = { vm.trash(row.task) }, onSetPriority = { vm.setPriority(row.task, it) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutlineList(vm: AppViewModel, density: Density, onOpenTask: (String) -> Unit, modifier: Modifier) {
    val rows by vm.outlineRows.collectAsState()
    val zoom by vm.outlineZoom.collectAsState()
    Column(modifier.fillMaxSize()) {
        // Zoom breadcrumb: long-press a parent task to focus its subtree; tap here to exit.
        if (zoom != null) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().clickable { vm.zoomInto(null) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Close, "Exit zoom", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Zoomed in · tap to exit", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        // if/else, never a non-local return out of this inline Column lambda — an early return
        // there desyncs Compose's group bookkeeping when rows flip empty↔populated and crashes.
        if (rows.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            rows.forEachIndexed { i, row ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                                com.todocompanion.app.ui.components.TaskRow(row, density,
                                    onClick = { onOpenTask(row.task.id) },
                                    onToggleComplete = { vm.toggleComplete(row.task) },
                                    onToggleCollapse = { vm.toggleCollapsed(row.task) },
                                    onCycleFlag = { vm.cycleFlag(row.task) }, onToggleStar = { vm.toggleStar(row.task) },
                                    onDelete = { vm.trash(row.task) },
                                    onZoom = { if (row.hasChildren) vm.zoomInto(row.task.id) }, onSetPriority = { vm.setPriority(row.task, it) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Task title line, shared by every row. A bounded-width Row keeps the Text laying out
 *  reliably (an unconstrained Row around an ellipsized Text could collapse to nothing). */
@Composable
private fun TaskTitle(task: TaskEntity, done: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (task.pinned) {
            Icon(Icons.Filled.PushPin, "Pinned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            task.title.ifBlank { "Untitled" },
            modifier = Modifier.weight(1f),
            maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun GroupHeader(title: String, count: Int, open: Boolean, onToggle: () -> Unit) {
    val a by animateFloatAsState(if (open) 0f else -90f, label = "chev")
    Row(Modifier.fillMaxWidth().clickable { onToggle() }.padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.8.sp)
        Spacer(Modifier.width(8.dp))
        Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f))
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).rotate(a))
    }
}

/**
 * Reveal-action horizontal swipe, factored out so both the flat list and the manual-reorder list
 * (custom lists default to manual sort) get identical swipe behaviour. The [content] should paint
 * its own opaque background so it slides cleanly over the coloured action revealed behind it.
 */
@Composable
private fun SwipeActionBox(
    taskId: String,
    rightNear: SwipeAction, rightFar: SwipeAction, leftNear: SwipeAction, leftFar: SwipeAction,
    enabled: Boolean, isTrashRestore: Boolean, onAct: (SwipeAction) -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dens = LocalDensity.current
    val nearPx = with(dens) { 76.dp.toPx() }
    val farPx = with(dens) { 176.dp.toPx() }
    val maxPx = with(dens) { 230.dp.toPx() }
    val offsetX = remember(taskId) { Animatable(0f) }
    val goingRight = offsetX.value > 0
    val pendingAction = when {
        goingRight && offsetX.value >= farPx && rightFar != SwipeAction.NONE -> rightFar
        goingRight -> rightNear
        !goingRight && -offsetX.value >= farPx && leftFar != SwipeAction.NONE -> leftFar
        else -> leftNear
    }
    val (bgColor, bgIcon) = swipeVisual(pendingAction, isTrashRestore && goingRight)
    Box(Modifier.fillMaxWidth()) {
        if (offsetX.value != 0f && pendingAction != SwipeAction.NONE) {
            Box(
                Modifier.matchParentSize().background(bgColor).padding(horizontal = 24.dp),
                contentAlignment = if (goingRight) Alignment.CenterStart else Alignment.CenterEnd,
            ) { Icon(bgIcon, null, tint = Color.White) }
        }
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .draggable(
                    orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                    enabled = enabled,
                    state = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
                        scope.launch { offsetX.snapTo((offsetX.value + delta).coerceIn(-maxPx, maxPx)) }
                    },
                    onDragStopped = {
                        val v = offsetX.value
                        when {
                            v >= farPx && rightFar != SwipeAction.NONE -> onAct(rightFar)
                            v >= nearPx -> onAct(rightNear)
                            v <= -farPx && leftFar != SwipeAction.NONE -> onAct(leftFar)
                            v <= -nearPx -> onAct(leftNear)
                        }
                        offsetX.animateTo(0f)
                    },
                ),
        ) { content() }
    }
}

private fun swipeVisual(action: SwipeAction, isTrashRestore: Boolean): Pair<Color, androidx.compose.ui.graphics.vector.ImageVector> = when {
    isTrashRestore -> Color(0xFF12A594) to Icons.Filled.Restore
    action == SwipeAction.COMPLETE -> Color(0xFF12A594) to Icons.Filled.Check
    action == SwipeAction.TRASH -> Color(0xFFE5484D) to Icons.Filled.Delete
    action == SwipeAction.STAR -> Color(0xFFF5A623) to Icons.Filled.Star
    action == SwipeAction.WONT_DO -> Color(0xFF64748B) to Icons.Filled.Cancel
    action == SwipeAction.CYCLE_PRIORITY -> Color(0xFF3E7BFA) to Icons.Filled.Flag
    action == SwipeAction.SCHEDULE_TOMORROW -> Color(0xFF8B5CF6) to Icons.Filled.Event
    action == SwipeAction.EDIT -> Color(0xFF5B57D9) to Icons.Filled.Edit
    else -> Color.Transparent to Icons.Filled.Check
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TaskListItem(
    task: TaskEntity, density: Density,
    contexts: List<Pair<String, Long?>>, tags: List<Pair<String, Long?>>, listName: String?,
    rightNear: SwipeAction, rightFar: SwipeAction, leftNear: SwipeAction, leftFar: SwipeAction, isTrash: Boolean,
    selected: Boolean, selectionMode: Boolean, onLongPress: () -> Unit,
    onOpen: () -> Unit, onAct: (SwipeAction) -> Unit, onCycleFlag: () -> Unit, onToggleStar: () -> Unit,
    onSetPriority: ((PriorityLevel) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val dens = LocalDensity.current
    val nearPx = with(dens) { 76.dp.toPx() }
    val farPx = with(dens) { 176.dp.toPx() }
    val maxPx = with(dens) { 230.dp.toPx() }
    val offsetX = remember(task.id) { Animatable(0f) }

    // Which action the current offset would trigger, and its background visual.
    val goingRight = offsetX.value > 0
    val pendingAction = when {
        goingRight && offsetX.value >= farPx && rightFar != SwipeAction.NONE -> rightFar
        goingRight -> rightNear
        !goingRight && -offsetX.value >= farPx && leftFar != SwipeAction.NONE -> leftFar
        else -> leftNear
    }
    val (bgColor, bgIcon) = swipeVisual(pendingAction, isTrash && goingRight)

    Box(Modifier.fillMaxWidth()) {
        if (offsetX.value != 0f && pendingAction != SwipeAction.NONE) {
            Box(
                Modifier.matchParentSize().background(bgColor).padding(horizontal = 24.dp),
                contentAlignment = if (goingRight) Alignment.CenterStart else Alignment.CenterEnd,
            ) { Icon(bgIcon, null, tint = Color.White) }
        }
        val level = PriorityLevel.from(task.importance, task.urgency)
        val done = task.completed || task.abandoned
        Row(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                .draggable(
                    orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                    enabled = !selectionMode,
                    state = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
                        scope.launch { offsetX.snapTo((offsetX.value + delta).coerceIn(-maxPx, maxPx)) }
                    },
                    onDragStopped = {
                        val v = offsetX.value
                        when {
                            v >= farPx && rightFar != SwipeAction.NONE -> onAct(rightFar)
                            v >= nearPx -> onAct(rightNear)
                            v <= -farPx && leftFar != SwipeAction.NONE -> onAct(leftFar)
                            v <= -nearPx -> onAct(leftNear)
                        }
                        offsetX.animateTo(0f)
                    },
                )
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                .padding(start = 6.dp, end = 8.dp, top = rowVerticalPadding(density), bottom = rowVerticalPadding(density)),
            verticalAlignment = Alignment.Top,
        ) {
            if (selectionMode) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(24.dp))
            } else if (task.isNote) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Notes, "Note", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
            } else PriorityCheckbox(task.completed, level, { onAct(SwipeAction.COMPLETE) }, onSetLevel = onSetPriority)
            Spacer(Modifier.width(2.dp))
            // Left: title, date/repeat, note.
            Column(Modifier.weight(1f).padding(top = 8.dp, bottom = 2.dp)) {
                TaskTitle(task, done)
                com.todocompanion.app.ui.components.TaskLeftMeta(task.dueDate, task.note, !task.rrule.isNullOrBlank())
            }
            Spacer(Modifier.width(6.dp))
            // Right (MLO): flag + star, with @contexts / #tags / list beneath. Width-bounded so a long
            // list/tag label can never squeeze the weighted title column to nothing.
            Column(Modifier.widthIn(max = 116.dp), horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlagStar(task.flagColorArgb, task.star, onCycleFlag, onToggleStar, iconSize = com.todocompanion.app.ui.components.flagStarSize(density))
                }
                com.todocompanion.app.ui.components.TaskTrailingLabels(contexts, tags, listName)
            }
        }
    }
}
