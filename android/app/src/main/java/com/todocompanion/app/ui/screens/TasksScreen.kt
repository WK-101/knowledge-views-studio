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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.domain.habit.HabitStats
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
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
fun TasksScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, modifier: Modifier = Modifier, onOpenOccasion: (String?) -> Unit = {}) {
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
    // R27 #1 — a folder's tasks are one flat set (its per-list headers are redundant with the list label
    // on each row), so folders get the SAME long-press drag/nest/select list as an ungrouped list. That's
    // the "make it consistent across lists AND folders" ask; only genuinely date-sectioned smart lists keep
    // the grouped layout (reordering across date sections is meaningless — TickTick doesn't allow it either).
    val useFlatDrag = !isTrash && (singleFlat || inFolderView)
    val flatDragTasks = if (singleFlat) groups.first().tasks else groups.flatMap { it.tasks }

    // Multi-select: long-press a row to enter selection mode; a bottom action bar batches edits.
    var selected by remember { mutableStateOf(setOf<String>()) }
    val selectionMode = selected.isNotEmpty()
    // R29 #1 — the selection bar is gated on the SAME StateFlow the nav bar reads (not on the local
    // `selected`). Both the nav bar (AppRoot) and this bar therefore appear/disappear on one flow emission,
    // in the SAME recomposition — killing the "bar flashes above the nav bar, then jumps over it" desync,
    // which was the local-state (frame N) vs. flow (frame N+1) gap.
    val selectingBar by vm.selectionActive.collectAsState()
    fun toggleSel(id: String) {
        val next = if (id in selected) selected - id else selected + id
        selected = next
        // Set EAGERLY (not via LaunchedEffect, which lags a frame) so the emission lands this frame.
        vm.selectionActive.value = next.isNotEmpty()
    }
    // Keeps the shell in sync for the exit/clear paths (bulk actions set `selected` directly).
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
      if (useFlatDrag) {
        // TickTick-style long-press drag (reorder + drag-to-nest) with multi-select on a stationary
        // long-press. The same top strips (habits due, countdowns, workload, description) ride along.
        ManualReorderList(vm, flatDragTasks, settings.density, ctxByTask, tagsByTask,
            listNameOf = { listId -> listLabel(listId) }, labelNav = labelNav,
            rightNear = settings.swipeRight, rightFar = settings.swipeRightFar, leftNear = settings.swipeLeft, leftFar = settings.swipeLeftFar,
            selected = selected, selectionMode = selectionMode, onToggleSel = { toggleSel(it) },
            sortIsManual = sortMode == com.todocompanion.app.domain.view.SortMode.MANUAL,
            onOpenTask = onOpenTask, modifier = Modifier.fillMaxSize(),
            header = { taskListHeaders(vm, view, viewDescription, onOpenOccasion) })
      } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = if (selectionMode) 120.dp else 100.dp)) {
            taskListHeaders(vm, view, viewDescription, onOpenOccasion)
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
        if (selectingBar && selectionMode) SelectionBar(
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
    // Sits at the very bottom, covering where the nav bar was. Pad for the system navigation bar so the
    // action row is never hidden underneath it (the reason it looked cut off when the nav bar was gone).
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
private fun androidx.compose.foundation.lazy.LazyListScope.taskListHeaders(vm: AppViewModel, view: ViewRef, viewDescription: String?, onOpenOccasion: (String?) -> Unit = {}) {
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
            item(key = "taskwip") { TaskWipStrip(vm) }      // R37 Port 1: personal-kanban WIP limit
            item(key = "freshstart") { FreshStartStrip(vm) } // R36 FW-11/12: temporal-landmark / transition reset
            item(key = "tasklesson") { TaskLessonStrip(vm) } // R37 Port 2: just-in-time productivity micro-lesson
            item(key = "twnudges") { NudgeStrip(vm) }       // R35 TW-B / R36 FW-14: right-now nudges (MRT)
            item(key = "bookend") { BookendCard(vm) }       // R35 TW-E: AM/PM intention-review bookend
            item(key = "habitsdue") { HabitsDueStrip(vm) }
            item(key = "shutdown") { ShutdownStrip(vm) }    // R36 FW-6: evening shutdown + carry-forward
        }
        // Countdowns whose target falls in this list's window show up here too, so a countdown you
        // set surfaces in Today / Next-7-days / Scheduled — not only on the dedicated Countdowns hub.
        if (k == SmartKind.TODAY || k == SmartKind.DO_NEXT || k == SmartKind.NEXT7 || k == SmartKind.SCHEDULED) {
            item(key = "countdowns") { CountdownDueStrip(vm, k, onOpenOccasion) }
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
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var draggingId by remember { mutableStateOf<String?>(null) }
    var delta by remember { mutableFloatStateOf(0f) }
    var dx by remember { mutableFloatStateOf(0f) }
    // R28 #8 — did the finger actually travel? Any real drag is a reorder/nest and must NEVER fall
    // through to "select"; only a stationary long-press selects. Fixes selection firing after a small move.
    var draggedFar by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(arrangeSubtaskOutline(tasks)) }
    // Resync with upstream whenever the tasks change (reorder, add/remove, nest, OR an edited
    // field like star/flag/title) — but never mid-drag, so the gesture isn't disrupted. Re-arranged
    // into a subtask outline each time so a just-nested child appears indented under its parent.
    androidx.compose.runtime.LaunchedEffect(tasks) { if (draggingId == null) items = arrangeSubtaskOutline(tasks) }
    val byId = remember(items) { items.associateBy { it.id } }
    val density2 = LocalDensity.current
    val nestThreshold = with(density2) { 40.dp.toPx() }
    val dragVisualCap = with(density2) { 72.dp.toPx() }
    // Below this much finger travel a long-press counts as "held, not dragged" → toggles selection.
    val moveSlop = with(density2) { 12.dp.toPx() }

    // R27 #1 — the gesture lives PER ROW, on the innermost content inside the swipe box, not on the
    // whole LazyColumn. That's the fix for drag-to-nest: the row's swipe (an outer sibling) used to eat
    // the horizontal movement before the container's drag ever saw it. As an inner detector the reorder
    // gesture only fires AFTER the long-press and then consumes every move, so a held-then-swipe nests
    // while a quick swipe (no hold) still reveals the swipe actions. Spec: tap = open · long-press = select
    // · long-press + vertical drag = reorder · long-press + drag right = nest / drag left = un-nest.
    fun startRowDrag(id: String) {
        draggingId = id; delta = 0f; dx = 0f; draggedFar = false
        // Immediate acknowledgement that the long-press registered (the card also lifts via draggingId),
        // so selection/drag never feels like it "did nothing" until release.
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
    }
    fun onRowDrag(dy: Float, dxAmt: Float) {
        delta += dy; dx += dxAmt
        if (kotlin.math.abs(delta) > moveSlop || kotlin.math.abs(dx) > moveSlop) draggedFar = true
        val id = draggingId ?: return
        val info = listState.layoutInfo.visibleItemsInfo
        val cur = info.firstOrNull { it.key == id } ?: return
        // Only reorder from vertical travel; a mostly-horizontal pull is a nest, so leave the order be.
        if (kotlin.math.abs(delta) <= kotlin.math.abs(dx)) return
        val center = cur.offset + cur.size / 2 + delta
        val target = info.firstOrNull { it.key != id && it.key is String && center.toInt() in it.offset..(it.offset + it.size) }
        if (target != null) {
            val from = items.indexOfFirst { it.id == id }
            val to = items.indexOfFirst { it.id == target.key }
            if (from >= 0 && to >= 0 && from != to) {
                items = items.toMutableList().also { it.add(to, it.removeAt(from)) }
                delta += (cur.offset - target.offset)
            }
        }
    }
    fun endRowDrag() {
        val id = draggingId; val finalDx = dx; val finalDy = delta
        val idx = items.indexOfFirst { it.id == id }
        // "moved" is sticky: any real travel makes this a drag. Released in place (or in a computed sort where
        // hand-ordering is meaningless) it's a SELECT instead — the predictable long-press-to-select.
        val moved = draggedFar
        when {
            id == null || idx < 0 -> {}
            !moved || !sortIsManual -> { onToggleSel(id); items = arrangeSubtaskOutline(tasks) }
            // A dominant horizontal pull nests: right → subtask of the row above, left → back to top level.
            finalDx > nestThreshold && kotlin.math.abs(finalDx) >= kotlin.math.abs(finalDy) && idx > 0 -> vm.nestUnder(id, items[idx - 1].id)
            finalDx < -nestThreshold && kotlin.math.abs(finalDx) >= kotlin.math.abs(finalDy) -> vm.nestUnder(id, null)
            // Otherwise it's a vertical reorder — persist the new manual order.
            else -> vm.setManualOrder(items.map { it.id })
        }
        draggingId = null; delta = 0f; dx = 0f; draggedFar = false
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp, bottom = if (selectionMode) 120.dp else 100.dp, start = 12.dp, end = 12.dp),
    ) {
        header?.invoke(this)
        itemsIndexed(items, key = { _, t -> t.id }) { _, task ->
            val dragging = task.id == draggingId
            val depth = subtaskDepth(task, byId)
            val isSel = task.id in selected
            // Live nest feedback: while dragging, a strong right pull = "make subtask of the row above",
            // a strong left pull = "promote to top level". Shown as an accent border + hint so it's clear.
            val willNest = dragging && dx > nestThreshold && kotlin.math.abs(dx) >= kotlin.math.abs(delta)
            val willUnnest = dragging && dx < -nestThreshold && kotlin.math.abs(dx) >= kotlin.math.abs(delta)
            val accent = MaterialTheme.colorScheme.primary
            // R30 #1 — no drag handle (it cluttered small screens). One gesture, disambiguated by movement
            // with strong immediate feedback: long-press LIFTS the row (haptic + shadow + scale) so you know
            // you've grabbed it; release without moving = SELECT; drag = reorder (vertical) or nest (drag
            // right → subtask of the row above, left → back to top level). Reorder/nest persist only under
            // MANUAL sort — in a computed sort the same long-press simply selects. Tap still opens.
            Surface(
                Modifier
                    .padding(start = (depth * 18).dp, top = 3.dp, bottom = 3.dp)
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragging) delta else 0f
                        translationX = if (dragging) dx.coerceIn(-dragVisualCap, dragVisualCap) else 0f
                        if (dragging) { scaleX = 1.03f; scaleY = 1.03f }
                    }
                    .then(if (willNest || willUnnest) Modifier.border(2.dp, accent, RoundedCornerShape(16.dp)) else Modifier),
                shape = RoundedCornerShape(16.dp),
                color = if (isSel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)
                    else if (willNest || willUnnest) accent.copy(alpha = .10f)
                    else if (dragging) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface,
                shadowElevation = if (dragging) 10.dp else 1.dp,
            ) {
              Box {
                if (willNest || willUnnest) Text(
                    if (willNest) "↳ subtask" else "↥ top level",
                    Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).zIndex(2f),
                    style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.SemiBold)
                // Reveal-action swipe, disabled during multi-select or an in-progress drag.
                SwipeActionBox(
                    taskId = task.id, rightNear = rightNear, rightFar = rightFar, leftNear = leftNear, leftFar = leftFar,
                    enabled = draggingId == null && !selectionMode, isTrashRestore = false,
                    onAct = { a -> onSwipe(vm, a, task, false, {}, { onOpenTask(task.id) }, { onOpenTask(task.id) }) },
                ) {
                    // R31 #1 (fixed) — ONE gesture arbiter on this inner Box, so a long-press can never ALSO
                    // register as a tap (the bug: hold selected AND opened). We race the long-press timeout
                    // against an early release (=tap → open) and a slop-exceeding move (=scroll/swipe → hand
                    // it back to the parent). Only a genuine hold grabs the row; then release-in-place selects
                    // and drag reorders/nests. The child completion-circle still wins its own taps because we
                    // bail the instant its change is consumed. There is no row-level clickable anymore — this
                    // is the sole tap/long-press authority, with matching a11y semantics below.
                    val openRow: () -> Unit = { if (selectionMode) onToggleSel(task.id) else onOpenTask(task.id) }
                    Box(
                        Modifier
                            .pointerInput(task.id, selectionMode) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var longPress = false
                                    var tap = false
                                    try {
                                        withTimeout(viewConfiguration.longPressTimeoutMillis) {
                                            var travel = Offset.Zero
                                            while (true) {
                                                val e = awaitPointerEvent()
                                                val c = e.changes.firstOrNull { it.id == down.id }
                                                if (c == null || c.isConsumed) return@withTimeout   // taken by a child (checkbox) or scroll
                                                if (!c.pressed) { tap = true; return@withTimeout }   // released in place → tap
                                                travel += c.positionChange()
                                                if (travel.getDistance() > viewConfiguration.touchSlop) return@withTimeout // moved → scroll/swipe
                                            }
                                        }
                                    } catch (_: PointerEventTimeoutCancellationException) {
                                        longPress = true                                           // held past the timeout → grab
                                    }
                                    if (longPress) {
                                        startRowDrag(task.id)
                                        currentEvent.changes.forEach { it.consume() }              // claim the stream so scroll/tap can't react
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) { change.consume(); break }
                                            val d = change.positionChange()
                                            if (d != Offset.Zero) { onRowDrag(d.y, d.x); change.consume() }
                                        }
                                        endRowDrag()
                                    } else if (tap) {
                                        openRow()
                                    }
                                }
                            }
                            .semantics {
                                onClick(label = "Open") { openRow(); true }
                                onLongClick(label = if (selectionMode) "Toggle selection" else "Select") { onToggleSel(task.id); true }
                            }
                    ) {
                        ReorderRow(task, density, ctxByTask[task.id].orEmpty(), tagsByTask[task.id].orEmpty(), listNameOf(task.listId), labelNav,
                            selectionMode = selectionMode, selected = isSel,
                            onToggle = { vm.toggleComplete(task) },
                            onCycleFlag = { vm.cycleFlag(task) }, onToggleStar = { vm.toggleStar(task) },
                            onSetPriority = { vm.setPriority(task, it) })
                    }
                }
              }
            }
        }
    }
}

@Composable
private fun ReorderRow(
    task: TaskEntity, density: Density, contexts: List<Pair<String, Long?>>, tags: List<Pair<String, Long?>>, listName: String?,
    labelNav: TaskLabelNav? = null,
    selectionMode: Boolean = false, selected: Boolean = false,
    onToggle: () -> Unit, onCycleFlag: () -> Unit, onToggleStar: () -> Unit,
    onSetPriority: ((PriorityLevel) -> Unit)? = null,
) {
    val level = PriorityLevel.from(task.importance, task.urgency)
    val done = task.completed || task.abandoned
    Row(
        // Tap/long-press/drag are all owned by the single gesture arbiter on the wrapping Box (R31 #1),
        // so this row carries NO clickable of its own — that split is exactly what let a hold both select
        // and open. The completion circle keeps its own tap target; the rest of the row is the grab area.
        Modifier.fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = rowVerticalPadding(density), bottom = rowVerticalPadding(density)),
        verticalAlignment = Alignment.Top,
    ) {
        if (selectionMode) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(24.dp))
        } else if (task.isNote) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
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
    // R34: the habits card is foldable — unfolded by default, but the header collapses it so the task
    // list is one tap away when the day's habits aren't the focus. State survives scroll & restart.
    var expanded by rememberSaveable { mutableStateOf(true) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Habits · ${due.size} due today", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    if (expanded) "Collapse habits" else "Expand habits",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f),
                )
            }
            if (expanded) {
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
}

/** R35 · TW-B — the offline JITAI nudge strip: right-now risk (a quit habit's urge window) or
 *  opportunity (a build habit due at its usual time). A local rules engine, surfaced in-app. */
@Composable
private fun NudgeStrip(vm: AppViewModel) {
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val cravings by vm.cravings.collectAsState()
    val now = java.time.LocalTime.now()
    val today = java.time.LocalDate.now().toEpochDay()
    val nudges = remember(habits, checkins, cravings, now.hour, today) {
        com.todocompanion.app.domain.habit.ThirdWave.nudges(habits, checkins, cravings, now.hour * 60 + now.minute, today)
    }
    // FW-14 · Personal Nudge MRT — reconcile past impressions once per day; pick a stable variant per
    // (habit, day) for each opportunity nudge shown, and log the impression so effectiveness can be read out.
    val opportunityVariants = remember(nudges, today) {
        nudges.filter { it.kind == "opportunity" }.associate { it.habitId to com.todocompanion.app.domain.habit.FourthWave.pickVariant(it.habitId.hashCode().toLong() + today) }
    }
    androidx.compose.runtime.LaunchedEffect(today) { vm.reconcileNudges() }
    androidx.compose.runtime.LaunchedEffect(opportunityVariants) {
        opportunityVariants.forEach { (habitId, variant) -> vm.logNudgeShown(habitId, variant, today) }
    }
    if (nudges.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        nudges.forEach { n ->
            val risk = n.kind == "risk"
            val text = if (n.kind == "opportunity") opportunityVariants[n.habitId]?.let { v -> "${com.todocompanion.app.domain.habit.FourthWave.NUDGE_VARIANTS[v]} — ${n.text}" } ?: n.text else n.text
            Surface(Modifier.fillMaxWidth().clickable { vm.habitDetailId.value = n.habitId }, shape = RoundedCornerShape(14.dp),
                color = if (risk) MaterialTheme.colorScheme.errorContainer.copy(alpha = .55f) else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .55f)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(n.emoji, Modifier.padding(end = 10.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (risk) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
}

/** R36 · FW-12/FW-11 — a fresh-start banner on Today: a temporal landmark (new week/month/quarter/year)
 *  or a live life-transition reset window. Tapping opens the Fresh-start windows screen. Calm, dismissible
 *  by its own nature (landmarks pass), and never nags. */
@Composable
private fun FreshStartStrip(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val today = java.time.LocalDate.now().toEpochDay()
    val landmark = remember(today) { com.todocompanion.app.domain.habit.FourthWave.temporalLandmark(today) }
    val transition = remember(settings, today) { com.todocompanion.app.domain.habit.FourthWave.transitionWindow(settings, today) }
    val emoji: String; val msg: String
    when {
        transition != null -> { emoji = "🌅"; msg = transition.message }
        landmark != null -> { emoji = landmark.emoji; msg = landmark.label }
        else -> return
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .6f)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.clickable { vm.lifeSystemsRoute.value = "freshstart" }, verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, Modifier.padding(end = 12.dp), style = MaterialTheme.typography.titleMedium)
                Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            // R37 · Port 8 — a landmark is when re-planning sticks: one tap pulls stale overdue onto today.
            androidx.compose.material3.TextButton(onClick = { vm.freshStartReschedule() }, modifier = Modifier.padding(top = 2.dp)) {
                Text("Plan the week — pull overdue onto today", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

/** R37 · Port 1 — personal-kanban WIP limit for tasks: when more tasks are "in progress" (started, not
 *  done) than the chosen cap, a calm nudge to finish one before starting another. */
@Composable
private fun TaskWipStrip(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    if (settings.taskWipLimit <= 0) return
    val tasks by vm.tasks.collectAsState()
    val now = System.currentTimeMillis()
    val wip = remember(tasks, settings.taskWipLimit) { com.todocompanion.app.domain.task.TaskCoach.wip(tasks, settings.taskWipLimit, now, dayStartMin = settings.dayStartHour * 60) }
    if (!wip.overCap) return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .55f)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🧯", Modifier.padding(end = 10.dp))
            Text("${wip.count} tasks in progress (your limit is ${wip.limit}). Finishing beats starting — close one before you pick up another.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

/** R37 · Port 2 — a just-in-time productivity micro-lesson, chosen from today's open tasks. */
@Composable
private fun TaskLessonStrip(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val now = System.currentTimeMillis()
    val hour = java.time.LocalTime.now().hour
    val childCounts = remember(tasks) { tasks.filter { it.parentId != null }.groupingBy { it.parentId!! }.eachCount() }
    val lesson = remember(tasks, hour) { com.todocompanion.app.domain.task.TaskCoach.todayLesson(tasks, childCounts, hour, now, dayStartMin = settings.dayStartHour * 60) }
    if (lesson == null) return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(lesson.emoji, Modifier.padding(end = 10.dp), style = MaterialTheme.typography.titleMedium)
                Text(lesson.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(lesson.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** R36 · FW-6 — the daily shutdown card: after ~5pm, the still-open tasks due today, with one tap to
 *  carry them all forward to tomorrow and close the day cleanly (Zeigarnik: an intentional close beats
 *  a nagging open loop). Opt-in feel — only appears in the evening when there's something to close. */
@Composable
private fun ShutdownStrip(vm: AppViewModel) {
    val tasks by vm.tasks.collectAsState()
    val today = java.time.LocalDate.now().toEpochDay()
    val hour = java.time.LocalTime.now().hour
    if (hour < 17) return
    val open = remember(tasks, today) { com.todocompanion.app.domain.habit.FourthWave.shutdownCarryForward(tasks, today) }
    if (open.isEmpty()) return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.padding(14.dp)) {
            Text("🌇 Daily shutdown", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text("${open.size} task${if (open.size == 1) "" else "s"} still open for today. Carry them forward and close the day — an intentional stop, not a loose end.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilledTonalButton(onClick = { vm.carryForwardTasks(open.map { it.id }) }) { Text("Carry ${open.size} to tomorrow") }
            }
        }
    }
}

/** R35 · TW-E — the daily AM/PM bookend: a morning intention before noon, an evening review after ~5pm.
 *  Opt-in (Settings). A tiny reflection loop that keeps monitoring alive between the weekly reviews. */
@Composable
private fun BookendCard(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    if (!settings.bookendsEnabled) return
    val dayLogs by vm.dayLogs.collectAsState()
    val today = java.time.LocalDate.now().toEpochDay()
    val log = dayLogs.firstOrNull { it.epochDay == today }
    val hour = java.time.LocalTime.now().hour
    val evening = hour >= 17
    val alreadyDone = if (evening) (log?.pmReflection?.isNotBlank() == true) else (log?.amIntention?.isNotBlank() == true)
    if (alreadyDone) return
    var text by remember(evening, today) { mutableStateOf("") }
    var mood by remember(evening, today) { mutableStateOf(0) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.padding(14.dp)) {
            Text(if (evening) "🌙 Evening review" else "🌅 Morning intention", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(if (evening) "One honest line on how today went." else "One line on what today is for.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(6.dp))
            com.todocompanion.app.ui.components.AppTextField(text, { text = it }, singleLine = true, label = { Text(if (evening) "How did it go?" else "Today, I will…") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Mood", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..5).forEach { n -> androidx.compose.material3.FilterChip(selected = mood == n, onClick = { mood = n }, label = { Text("$n") }) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(enabled = text.isNotBlank(), onClick = {
                    if (evening) vm.saveEveningReflection(today, text, mood) else vm.saveMorningIntention(today, text, mood)
                }) { Text("Save") }
            }
        }
    }
}

/** Countdowns whose target falls in the current smart-list's window, so a countdown surfaces in
 *  Today / Next-7-days / Scheduled, not only on the dedicated Countdowns hub. */
@Composable
private fun CountdownDueStrip(vm: AppViewModel, kind: SmartKind, onOpenOccasion: (String?) -> Unit = {}) {
    val countdowns by vm.countdowns.collectAsState()
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val relevant = remember(countdowns, kind, today) {
        countdowns.filter { !it.archived }.mapNotNull { cd ->
            // R43 — occasions surface on their NEXT occurrence (yearly birthdays roll forward).
            val days = com.todocompanion.app.domain.LifeEvent.daysUntil(cd, today)
            val inWindow = when (kind) {
                SmartKind.TODAY, SmartKind.DO_NEXT -> days == 0L
                SmartKind.NEXT7 -> days in 0..6
                SmartKind.SCHEDULED -> days >= 0
                else -> false
            }
            if (inWindow) cd to days else null
        }.sortedBy { it.second }
    }
    if (relevant.isEmpty()) return
    // R48 — the Scheduled list only shows the two closest occasions (with a "View all" into the hub);
    // the tighter windows (Today / Next-7) already self-limit, so they show all that fall inside them.
    val cap = if (kind == SmartKind.SCHEDULED) 2 else relevant.size
    val shown = relevant.take(cap)
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Occasions", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (relevant.size > shown.size) TextButton(onClick = { onOpenOccasion(null) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp, 0.dp)) {
                    Text("View all (${relevant.size})", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.size(6.dp))
            shown.forEachIndexed { i, (cd, days) ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                val color = cd.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.secondary
                Row(Modifier.fillMaxWidth().clickable { onOpenOccasion(cd.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(cd.emoji ?: com.todocompanion.app.domain.LifeEvent.type(cd).emoji, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(com.todocompanion.app.domain.LifeEvent.calendarLabel(cd), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        com.todocompanion.app.domain.LifeEvent.ageChip(cd, today)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
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
            // Completed: muted grey, no strike-through (the line made titles hard to read).
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f) else MaterialTheme.colorScheme.onSurface,
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
