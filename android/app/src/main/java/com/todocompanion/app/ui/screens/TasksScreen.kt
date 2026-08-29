package com.todocompanion.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.window.Dialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
    // Deleting from Trash is permanent — confirm first (single swipe, and multi-select).
    var pendingPermanentDelete by remember { mutableStateOf<TaskEntity?>(null) }
    var pendingBulkDelete by remember { mutableStateOf<Set<String>?>(null) }
    // The searchable move-to-list/folder picker: holds the task ids to move (single swipe or bulk).
    var pendingMove by remember { mutableStateOf<Set<String>?>(null) }
    // "Make subtask of…": holds the selected ids awaiting a parent-task pick.
    var pendingSubtaskOf by remember { mutableStateOf<Set<String>?>(null) }
    val allVisibleIds = remember(groups) { groups.flatMap { g -> g.tasks.map { it.id } }.toSet() }

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
    // In smart lists (which span lists) and folder views (which span their lists), show each task's
    // list as a detail — like MLO/TickTick. In a folder, tasks captured with no list read "No list".
    val lists by vm.lists.collectAsState()
    val inFolderView = view is ViewRef.FolderView
    val showList = view is ViewRef.Smart || inFolderView
    val listNameById = remember(lists) { lists.associate { it.id to it.name } }
    fun listLabel(listId: String): String? {
        if (!showList) return null
        return listNameById[listId]?.takeIf { it != "Inbox" } ?: if (inFolderView) "No list" else null
    }
    // Tap a task's list/context/tag label to jump to that view (labels resolve name→id here).
    val ctxIdByName = remember(allContexts) { allContexts.associate { it.name to it.id } }
    val tagIdByName = remember(allTags) { allTags.associate { it.name to it.id } }
    val labelNav = remember(ctxIdByName, tagIdByName) {
        TaskLabelNav(
            onList = { lid -> vm.select(ViewRef.ListView(lid)) },
            onContext = { name -> ctxIdByName[name]?.let { vm.select(ViewRef.ContextView(it)) } },
            onTag = { name -> tagIdByName[name]?.let { vm.select(ViewRef.TagView(it)) } },
        )
    }

    if (groups.isEmpty() || groups.all { it.tasks.isEmpty() }) { EmptyState(view); return }

    val sortMode by vm.sortMode.collectAsState()
    // A single ungrouped list gets the TickTick-style long-press drag: drag up/down reorders (persisted
    // only under MANUAL sort), drag right nests as a subtask, drag left un-nests. A stationary long-press
    // (no drag) still enters multi-select, so both gestures live on the same list.
    val singleFlat = groups.size == 1 && groups.first().title.isBlank() && !isTrash

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
    val allFolders by vm.folders.collectAsState()
    // A list's / folder's optional description, shown as a banner atop its tasks.
    val viewDescription = when (view) {
        is ViewRef.ListView -> allLists.firstOrNull { it.id == (view as ViewRef.ListView).listId }?.description
        is ViewRef.FolderView -> allFolders.firstOrNull { it.id == (view as ViewRef.FolderView).folderId }?.description
        else -> null
    }?.takeIf { it.isNotBlank() }

    // P5: celebrate finishing a goal or project — a milestone, like a habit reward unlocking.
    val celebCtx = LocalContext.current
    LaunchedEffect(Unit) {
        vm.goalCelebration.collect { msg ->
            if (msg != null) {
                android.widget.Toast.makeText(celebCtx, msg, android.widget.Toast.LENGTH_LONG).show()
                vm.goalCelebration.value = null
            }
        }
    }

    Box(modifier.fillMaxSize()) {
      if (singleFlat) {
        // TickTick-style long-press drag (reorder + drag-to-nest) with multi-select on a stationary
        // long-press. The same top strips (habits due, countdowns, workload, description) ride along.
        ManualReorderList(vm, groups.first().tasks, settings.density, ctxByTask, tagsByTask,
            listNameOf = { listId -> listLabel(listId) }, labelNav = labelNav,
            rightNear = settings.swipeRight, rightFar = settings.swipeRightFar, leftNear = settings.swipeLeft, leftFar = settings.swipeLeftFar,
            selected = selected, selectionMode = selectionMode, onToggleSel = { toggleSel(it) },
            sortIsManual = sortMode == com.todocompanion.app.domain.view.SortMode.MANUAL,
            onOpenTask = onOpenTask, modifier = Modifier.fillMaxSize(),
            header = { taskListHeaders(vm, view, viewDescription) })
      } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = if (selectionMode) 120.dp else 100.dp)) {
            taskListHeaders(vm, view, viewDescription)
            items(groups, key = { it.key }) { group ->
                val open = collapsed[group.key] != true
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    if (group.title.isNotBlank()) GroupHeader(group.title, group.tasks.size, open) { collapsed[group.key] = open }
                    AnimatedVisibility(visible = open) {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                group.tasks.forEachIndexed { i, task ->
                                  androidx.compose.runtime.key(task.id) {
                                    if (i > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                                    TaskListItem(task, settings.density,
                                        contexts = ctxByTask[task.id].orEmpty(), tags = tagsByTask[task.id].orEmpty(),
                                        listName = listLabel(task.listId), labelNav = labelNav,
                                        rightNear = if (isTrash) SwipeAction.COMPLETE else settings.swipeRight,
                                        rightFar = if (isTrash) SwipeAction.NONE else settings.swipeRightFar,
                                        leftNear = if (isTrash) SwipeAction.TRASH else settings.swipeLeft,
                                        leftFar = if (isTrash) SwipeAction.NONE else settings.swipeLeftFar, isTrash = isTrash,
                                        selected = task.id in selected, selectionMode = selectionMode, onLongPress = { toggleSel(task.id) },
                                        onOpen = { if (selectionMode) toggleSel(task.id) else onOpenTask(task.id) },
                                        onAct = { a -> onSwipe(vm, a, task, isTrash, { pendingPermanentDelete = it }, { pendingMove = setOf(task.id) }) { onOpenTask(task.id) } },
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
      }
        if (selectionMode) SelectionBar(
            count = selected.size, allSelected = selected.size == allVisibleIds.size && allVisibleIds.isNotEmpty(),
            onComplete = { vm.completeMany(selected); selected = emptySet() },
            // In Trash, "Delete" is a permanent erase — confirm first (matches the single-swipe behaviour).
            onDelete = { if (isTrash) pendingBulkDelete = selected else { vm.trashMany(selected); selected = emptySet() } },
            dangerousDelete = isTrash,
            onPriority = { lvl -> vm.setPriorityMany(selected, lvl); selected = emptySet() },
            onMoveClick = { pendingMove = selected },
            onSubtask = { pendingSubtaskOf = selected },
            onSelectAll = { selected = if (selected.size == allVisibleIds.size) emptySet() else allVisibleIds },
            onClear = { selected = emptySet() },
            canSubtask = !isTrash,
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
        pendingBulkDelete?.let { ids ->
            AlertDialog(
                onDismissRequest = { pendingBulkDelete = null },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.deleteForeverMany(ids); pendingBulkDelete = null; selected = emptySet() }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingBulkDelete = null }) { Text("Cancel") } },
                title = { Text("Delete ${ids.size} permanently?") },
                text = { Text("These ${ids.size} item${if (ids.size == 1) "" else "s"} and their subtasks will be erased for good. This can't be undone.") },
            )
        }
        pendingMove?.let { ids ->
            MoveTargetDialog(
                folders = allFolders, lists = allLists.filter { !it.archived }, pinnedRefs = settings.pinnedRefs,
                onPinToggle = { ref -> vm.togglePinnedRef(ref) },
                onPickList = { listId -> vm.moveMany(ids, listId); pendingMove = null; selected = emptySet() },
                onPickFolder = { folderId -> vm.moveManyToFolder(ids, folderId); pendingMove = null; selected = emptySet() },
                onDismiss = { pendingMove = null },
            )
        }
        pendingSubtaskOf?.let { ids ->
            // Candidate parents: every visible task except the ones being nested (nestManyUnder still guards
            // against cycles, so this list is just to keep the picker tidy).
            val candidates = groups.flatMap { it.tasks }.filter { it.id !in ids }
            SubtaskParentDialog(
                candidates = candidates,
                onPick = { parentId -> vm.nestManyUnder(ids, parentId); pendingSubtaskOf = null; selected = emptySet() },
                onDismiss = { pendingSubtaskOf = null },
            )
        }
    }
}

/** A slim picker over the current view's tasks: choose which one the multi-selected tasks become subtasks of. */
@Composable
private fun SubtaskParentDialog(candidates: List<TaskEntity>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Make subtask of…") },
        text = {
            if (candidates.isEmpty()) {
                Text("No other task in this view to nest under.", style = MaterialTheme.typography.bodyMedium)
            } else {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(candidates, key = { it.id }) { t ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(t.id) }.padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(t.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun SelectionBar(
    count: Int, allSelected: Boolean,
    onComplete: () -> Unit, onDelete: () -> Unit, onPriority: (PriorityLevel) -> Unit,
    onMoveClick: () -> Unit, onSubtask: () -> Unit, onSelectAll: () -> Unit, onClear: () -> Unit,
    dangerousDelete: Boolean = false, canSubtask: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // Sits at the very bottom, covering where the nav bar was (the Scaffold already insets us above the
    // system navigation bar, so no extra inset is needed here).
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Clear selection") }
            Text("$count", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            // Select-all / none toggle.
            androidx.compose.material3.TextButton(onClick = onSelectAll) { Text(if (allSelected) "None" else "All") }
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
            // Nest the selection under a chosen parent task (multi-select "make subtask of…").
            if (canSubtask) androidx.compose.material3.IconButton(onClick = onSubtask) { Icon(Icons.AutoMirrored.Filled.FormatIndentIncrease, "Make subtask of…") }
            androidx.compose.material3.IconButton(onClick = onMoveClick) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Move to list or folder") }
            androidx.compose.material3.IconButton(onClick = onDelete) { Icon(if (dangerousDelete) Icons.Filled.DeleteForever else Icons.Filled.Delete, if (dangerousDelete) "Delete forever" else "Delete", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

/**
 * A searchable move-to picker over BOTH folders and lists (the old menu showed lists only). Pinned
 * favourites float to the top and each row can be pinned/unpinned in place, so moving into a frequently
 * used list/folder never means scrolling a long list again. Reused for single-swipe and bulk moves.
 */
@Composable
internal fun MoveTargetDialog(
    folders: List<com.todocompanion.app.data.entity.FolderEntity>,
    lists: List<com.todocompanion.app.data.entity.ListEntity>,
    pinnedRefs: List<String>,
    onPinToggle: (String) -> Unit,
    onPickList: (String) -> Unit,
    onPickFolder: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    data class Target(val ref: String, val name: String, val sub: String?, val isFolder: Boolean, val id: String)
    val folderById = remember(folders) { folders.associateBy { it.id } }
    val all = remember(folders, lists) {
        folders.map { Target("folder:${it.id}", it.name, "Folder", true, it.id) } +
            lists.map { Target("list:${it.id}", it.name, it.folderId?.let { fid -> folderById[fid]?.name }, false, it.id) }
    }
    var query by remember { mutableStateOf("") }
    val filtered = all.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    val pinnedSet = pinnedRefs.toSet()
    val pinned = filtered.filter { it.ref in pinnedSet }
    val rest = filtered.filter { it.ref !in pinnedSet }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Move to", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                com.todocompanion.app.ui.components.AppTextField(
                    query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Search lists & folders") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    if (pinned.isNotEmpty()) {
                        item { MoveSectionLabel("Pinned") }
                        items(pinned, key = { it.ref }) { t ->
                            MoveTargetRow(t.name, t.sub, t.isFolder, pinned = true,
                                onClick = { if (t.isFolder) onPickFolder(t.id) else onPickList(t.id) },
                                onPin = { onPinToggle(t.ref) })
                        }
                    }
                    if (rest.isNotEmpty()) {
                        if (pinned.isNotEmpty()) item { MoveSectionLabel("All") }
                        items(rest, key = { it.ref }) { t ->
                            MoveTargetRow(t.name, t.sub, t.isFolder, pinned = false,
                                onClick = { if (t.isFolder) onPickFolder(t.id) else onPickList(t.id) },
                                onPin = { onPinToggle(t.ref) })
                        }
                    }
                    if (filtered.isEmpty()) item {
                        Text("No match", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun MoveSectionLabel(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun MoveTargetRow(name: String, sub: String?, isFolder: Boolean, pinned: Boolean, onClick: () -> Unit, onPin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onClick() }.padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isFolder) Icons.Filled.Folder else Icons.AutoMirrored.Filled.List,
            null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.IconButton(onClick = onPin) {
            Icon(
                if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                if (pinned) "Unpin" else "Pin",
                tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun onSwipe(vm: AppViewModel, action: SwipeAction, task: TaskEntity, isTrash: Boolean, onConfirmDelete: (TaskEntity) -> Unit, onMove: (TaskEntity) -> Unit, onOpen: () -> Unit) {
    if (isTrash) { if (action == SwipeAction.COMPLETE) vm.restore(task) else onConfirmDelete(task); return }
    if (action == SwipeAction.MOVE) { onMove(task); return }
    if (!vm.applyAction(action, task)) onOpen()
}

/**
 * Arrange a flat manual-sorted list into a subtask outline: each parent immediately followed by its
 * descendants that are present in the same list. Roots (no parent, or a parent filtered out of this
 * view) keep their given manual order. This is what makes a drag-created subtask VISIBLE — without it
 * the child stays a top-level row at the same indent and the nest looks like it did nothing.
 */
private fun arrangeSubtaskOutline(tasks: List<TaskEntity>): List<TaskEntity> {
    val present = tasks.associateBy { it.id }
    val childrenByParent = tasks.groupBy { t -> t.parentId?.takeIf { present.containsKey(it) } }
    val out = ArrayList<TaskEntity>(tasks.size)
    val seen = HashSet<String>()
    fun emit(t: TaskEntity) {
        if (!seen.add(t.id)) return
        out += t
        childrenByParent[t.id]?.forEach { emit(it) }
    }
    tasks.filter { it.parentId == null || !present.containsKey(it.parentId) }.forEach { emit(it) }
    // Safety net: append any task not reached (e.g. a parentId cycle) so nothing is dropped.
    tasks.forEach { if (it.id !in seen) out += it }
    return out
}

/** Indent depth of a task within a list — count of ancestors that are also present. */
private fun subtaskDepth(task: TaskEntity, byId: Map<String, TaskEntity>): Int {
    var d = 0; var p = task.parentId; var guard = 0
    while (p != null && guard++ < 100) { val par = byId[p] ?: break; d++; p = par.parentId }
    return d
}

/** Tap-a-label navigation: jump to a task's list / context / tag view. */
class TaskLabelNav(val onList: (String) -> Unit, val onContext: (String) -> Unit, val onTag: (String) -> Unit)

/** The top strips (list description + smart-view helpers) shared by both list layouts. */
private fun androidx.compose.foundation.lazy.LazyListScope.taskListHeaders(vm: AppViewModel, view: ViewRef, viewDescription: String?) {
    viewDescription?.let { desc -> item(key = "viewdesc") {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)) {
            Text(desc, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } }
    // Workload forecast bar at the top of Next-7-Days: committed estimate vs daily capacity.
    if ((view as? ViewRef.Smart)?.kind == SmartKind.NEXT7) item(key = "workload") { WorkloadStrip(vm) }
    // Fusion F1: today's still-due habits sit alongside tasks in Today / Do-Next.
    (view as? ViewRef.Smart)?.kind?.let { k ->
        if (k == SmartKind.TODAY || k == SmartKind.DO_NEXT) {
            item(key = "recovery") { RecoveryStrip(vm) }   // P2: kind triage when overdue piles up
            item(key = "habitsdue") { HabitsDueStrip(vm) }
        }
        // Countdowns whose target falls in this list's window show up here too, so a countdown you
        // set surfaces in Today / Next-7-days / Scheduled — not only on the dedicated Countdowns hub.
        if (k == SmartKind.TODAY || k == SmartKind.DO_NEXT || k == SmartKind.NEXT7 || k == SmartKind.SCHEDULED) {
            item(key = "countdowns") { CountdownDueStrip(vm, k) }
        }
    }
}

@Composable
private fun ManualReorderList(
    vm: AppViewModel, tasks: List<TaskEntity>, density: Density,
    ctxByTask: Map<String, List<Pair<String, Long?>>>, tagsByTask: Map<String, List<Pair<String, Long?>>>,
    listNameOf: (String) -> String?, labelNav: TaskLabelNav? = null,
    rightNear: SwipeAction, rightFar: SwipeAction, leftNear: SwipeAction, leftFar: SwipeAction,
    selected: Set<String> = emptySet(), selectionMode: Boolean = false, onToggleSel: (String) -> Unit = {},
    sortIsManual: Boolean = true,
    onOpenTask: (String) -> Unit, modifier: Modifier,
    header: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
) {
    // Local working order for the drag gesture.
    val listState = rememberLazyListState()
    var draggingId by remember { mutableStateOf<String?>(null) }
    var delta by remember { mutableFloatStateOf(0f) }
    var dx by remember { mutableFloatStateOf(0f) }
    var items by remember { mutableStateOf(arrangeSubtaskOutline(tasks)) }
    // Resync with upstream whenever the tasks change (reorder, add/remove, nest, OR an edited
    // field like star/flag/title) — but never mid-drag, so the gesture isn't disrupted. Re-arranged
    // into a subtask outline each time so a just-nested child appears indented under its parent.
    androidx.compose.runtime.LaunchedEffect(tasks) { if (draggingId == null) items = arrangeSubtaskOutline(tasks) }
    val byId = remember(items) { items.associateBy { it.id } }
    val nestThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    val dragVisualCap = with(LocalDensity.current) { 72.dp.toPx() }
    // Below this much finger travel a long-press counts as "held, not dragged" → toggles selection.
    val moveSlop = with(LocalDensity.current) { 14.dp.toPx() }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().androidx_pointerReorder(
            listState = listState,
            draggingId = { draggingId },
            setDraggingId = { draggingId = it },
            delta = { delta }, setDelta = { delta = it },
            dx = { dx }, setDx = { dx = it },
            indexOfId = { id -> items.indexOfFirst { it.id == id } },
            moveItem = { from, to -> items = items.toMutableList().also { it.add(to, it.removeAt(from)) } },
            onCommit = { finalDx ->
                val id = draggingId
                val idx = items.indexOfFirst { it.id == id }
                val moved = kotlin.math.abs(delta) > moveSlop || kotlin.math.abs(finalDx) > moveSlop
                when {
                    id == null || idx < 0 -> {}
                    // Stationary long-press (or already selecting) toggles multi-select instead of moving.
                    selectionMode || !moved -> { onToggleSel(id); items = arrangeSubtaskOutline(tasks) }
                    // Drag right past the threshold → nest under the task directly above it.
                    finalDx > nestThreshold && idx > 0 -> vm.nestUnder(id, items[idx - 1].id)
                    // Drag left past the threshold → un-nest (promote to top level).
                    finalDx < -nestThreshold -> vm.nestUnder(id, null)
                    // Vertical reorder only persists under MANUAL sort; otherwise snap back to source order.
                    sortIsManual -> vm.setManualOrder(items.map { it.id })
                    else -> items = arrangeSubtaskOutline(tasks)
                }
            },
        ),
        contentPadding = PaddingValues(top = 6.dp, bottom = if (selectionMode) 120.dp else 100.dp, start = 12.dp, end = 12.dp),
    ) {
        header?.invoke(this)
        itemsIndexed(items, key = { _, t -> t.id }) { _, task ->
            val dragging = task.id == draggingId
            val depth = subtaskDepth(task, byId)
            val isSel = task.id in selected
            Surface(
                Modifier
                    .padding(start = (depth * 18).dp, top = 3.dp, bottom = 3.dp)
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragging) delta else 0f
                        translationX = if (dragging) dx.coerceIn(-dragVisualCap, dragVisualCap) else 0f
                    },
                shape = RoundedCornerShape(16.dp),
                color = if (isSel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f) else MaterialTheme.colorScheme.surface,
                shadowElevation = if (dragging) 8.dp else 1.dp,
            ) {
                // Same reveal-action swipe as the flat list; disabled mid-reorder and during multi-select
                // so the gestures never fight. Long-press starts a drag (handled on the LazyColumn).
                SwipeActionBox(
                    taskId = task.id, rightNear = rightNear, rightFar = rightFar, leftNear = leftNear, leftFar = leftFar,
                    enabled = draggingId == null && !selectionMode, isTrashRestore = false,
                    onAct = { a -> onSwipe(vm, a, task, false, {}, { onOpenTask(task.id) }, { onOpenTask(task.id) }) },
                ) {
                    ReorderRow(task, density, ctxByTask[task.id].orEmpty(), tagsByTask[task.id].orEmpty(), listNameOf(task.listId), labelNav,
                        onOpen = { if (selectionMode) onToggleSel(task.id) else onOpenTask(task.id) }, onToggle = { vm.toggleComplete(task) },
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
    labelNav: TaskLabelNav? = null,
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
            com.todocompanion.app.ui.components.TaskTrailingLabels(contexts, tags, listName,
                onListClick = labelNav?.let { { it.onList(task.listId) } },
                onContextClick = labelNav?.let { nav -> { name -> nav.onContext(name) } },
                onTagClick = labelNav?.let { nav -> { name -> nav.onTag(name) } })
        }
    }
}

/** Long-press reorder gesture for a LazyColumn, factored out to keep the list readable. */
private fun Modifier.androidx_pointerReorder(
    listState: androidx.compose.foundation.lazy.LazyListState,
    draggingId: () -> String?, setDraggingId: (String?) -> Unit,
    delta: () -> Float, setDelta: (Float) -> Unit,
    dx: () -> Float = { 0f }, setDx: (Float) -> Unit = {},
    indexOfId: (String) -> Int, moveItem: (Int, Int) -> Unit, onCommit: (Float) -> Unit,
): Modifier = this.pointerInput(Unit) {
    detectDragGesturesAfterLongPress(
        onDragStart = { off ->
            val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull { off.y.toInt() in it.offset..(it.offset + it.size) }
            // Only pick up actual task rows — header strips (habits/countdowns/description) aren't draggable.
            val key = hit?.key as? String
            setDraggingId(if (key != null && indexOfId(key) >= 0) key else null); setDelta(0f); setDx(0f)
        },
        onDragEnd = { if (draggingId() != null) onCommit(dx()); setDraggingId(null); setDelta(0f); setDx(0f) },
        onDragCancel = { setDraggingId(null); setDelta(0f); setDx(0f) },
        onDrag = { change, drag ->
            change.consume()
            setDelta(delta() + drag.y)
            setDx(dx() + drag.x)
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
/**
 * P2 — recovery mode for tasks: when overdue items pile into a wall of red, offer a kind two-tap way
 * out instead of a guilt trip. Borrowed from the habit recovery card. Shows only past a small threshold.
 */
@Composable
private fun RecoveryStrip(vm: AppViewModel) {
    val tasks by vm.tasks.collectAsState()
    val overdue = remember(tasks) { vm.overdueOpenTasks() }
    if (overdue.size < 4) return
    val ctx = LocalContext.current
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .7f)) {
        Column(Modifier.padding(14.dp)) {
            Text("${overdue.size} tasks are overdue — let's reset.", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text("A pile-up isn't a verdict. Pull them to today, push to tomorrow, or open them one at a time.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(top = 2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                androidx.compose.material3.FilledTonalButton(onClick = {
                    vm.rescheduleOverdue(toTomorrow = false) { n -> android.widget.Toast.makeText(ctx, "Moved $n to today", android.widget.Toast.LENGTH_SHORT).show() }
                }) { Text("Bring to today") }
                androidx.compose.material3.TextButton(onClick = {
                    vm.rescheduleOverdue(toTomorrow = true) { n -> android.widget.Toast.makeText(ctx, "Pushed $n to tomorrow", android.widget.Toast.LENGTH_SHORT).show() }
                }) { Text("Push to tomorrow") }
            }
        }
    }
}

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
                    // Mirror the calendar's day-habit pills: soft outline + leading dot, no checkmark
                    // (a filled pill is the done-signal). Tap completes it, so it drops out of the due list.
                    Row(
                        Modifier.clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = .12f))
                            .border(1.dp, color.copy(alpha = .45f), RoundedCornerShape(20.dp))
                            .clickable { vm.setHabitValue(h, today, h.targetPerDay.coerceAtLeast(1)) }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color))
                        Spacer(Modifier.size(7.dp))
                        Text((h.emoji?.plus(" ") ?: "") + h.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/** Countdowns whose target falls in the current smart-list's window, so a countdown surfaces in
 *  Today / Next-7-days / Scheduled, not only on the dedicated Countdowns hub. */
@Composable
private fun CountdownDueStrip(vm: AppViewModel, kind: SmartKind) {
    val countdowns by vm.countdowns.collectAsState()
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val relevant = remember(countdowns, kind, today) {
        countdowns.mapNotNull { cd ->
            val d = java.time.Instant.ofEpochMilli(cd.targetMillis).atZone(zone).toLocalDate()
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, d)
            val inWindow = when (kind) {
                SmartKind.TODAY, SmartKind.DO_NEXT -> d == today
                SmartKind.NEXT7 -> days in 0..6
                SmartKind.SCHEDULED -> days >= 0
                else -> false
            }
            if (inWindow) cd to days else null
        }.sortedBy { it.second }
    }
    if (relevant.isEmpty()) return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.padding(12.dp)) {
            Text("Countdowns", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
            relevant.forEachIndexed { i, (cd, days) ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                val color = cd.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.secondary
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(cd.emoji ?: "🎯", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(10.dp))
                    Text(cd.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when { days == 0L -> "Today"; days == 1L -> "Tomorrow"; days > 1 -> "in $days days"; else -> "${-days}d ago" },
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color,
                    )
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
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
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
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
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
    action == SwipeAction.MOVE -> Color(0xFF0EA5A0) to Icons.AutoMirrored.Filled.DriveFileMove
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
    onSetPriority: ((PriorityLevel) -> Unit)? = null, labelNav: TaskLabelNav? = null,
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
                com.todocompanion.app.ui.components.TaskTrailingLabels(contexts, tags, listName,
                    onListClick = labelNav?.let { { it.onList(task.listId) } },
                    onContextClick = labelNav?.let { nav -> { name -> nav.onContext(name) } },
                    onTagClick = labelNav?.let { nav -> { name -> nav.onTag(name) } })
            }
        }
    }
}
