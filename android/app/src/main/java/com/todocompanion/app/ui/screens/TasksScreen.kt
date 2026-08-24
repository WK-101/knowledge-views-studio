package com.todocompanion.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
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

    val groups by vm.groups.collectAsState()
    val isTrash = (view as? ViewRef.Smart)?.kind == SmartKind.TRASH
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

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

    if (groups.isEmpty() || groups.all { it.tasks.isEmpty() }) { EmptyState(view); return }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = 100.dp)) {
        items(groups, key = { it.key }) { group ->
            val open = collapsed[group.key] != true
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                if (group.title.isNotBlank()) GroupHeader(group.title, group.tasks.size, open) { collapsed[group.key] = open }
                AnimatedVisibility(visible = open) {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            group.tasks.forEachIndexed { i, task ->
                                if (i > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                                TaskListItem(task, settings.density,
                                    contexts = ctxByTask[task.id].orEmpty(), tags = tagsByTask[task.id].orEmpty(),
                                    rightAction = if (isTrash) SwipeAction.COMPLETE else settings.swipeRight,
                                    leftAction = if (isTrash) SwipeAction.TRASH else settings.swipeLeft, isTrash = isTrash,
                                    onOpen = { onOpenTask(task.id) },
                                    onAct = { a -> onSwipe(vm, a, task, isTrash) { onOpenTask(task.id) } },
                                    onCycleFlag = { vm.cycleFlag(task) }, onToggleStar = { vm.toggleStar(task) })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun onSwipe(vm: AppViewModel, action: SwipeAction, task: TaskEntity, isTrash: Boolean, onOpen: () -> Unit) {
    if (isTrash) { if (action == SwipeAction.COMPLETE) vm.restore(task) else vm.deleteForever(task); return }
    if (!vm.applyAction(action, task)) onOpen()
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
        SmartKind.SCHEDULED -> Triple(Icons.Outlined.Schedule, "Nothing scheduled", "Tasks with a date will appear here")
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
private fun OutlineList(vm: AppViewModel, density: Density, onOpenTask: (String) -> Unit, modifier: Modifier) {
    val rows by vm.outlineRows.collectAsState()
    if (rows.isEmpty()) { EmptyState(); return }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
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
                            onDelete = { vm.trash(row.task) })
                    }
                }
            }
        }
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

private fun swipeVisual(action: SwipeAction, isTrashRestore: Boolean): Pair<Color, androidx.compose.ui.graphics.vector.ImageVector> = when {
    isTrashRestore -> Color(0xFF12A594) to Icons.Filled.Restore
    action == SwipeAction.COMPLETE -> Color(0xFF12A594) to Icons.Filled.Check
    action == SwipeAction.TRASH -> Color(0xFFE5484D) to Icons.Filled.Delete
    action == SwipeAction.STAR -> Color(0xFFF5A623) to Icons.Filled.Star
    action == SwipeAction.WONT_DO -> Color(0xFF64748B) to Icons.Filled.Cancel
    action == SwipeAction.CYCLE_PRIORITY -> Color(0xFF3E7BFA) to Icons.Filled.Flag
    action == SwipeAction.EDIT -> Color(0xFF5B57D9) to Icons.Filled.Edit
    else -> Color.Transparent to Icons.Filled.Check
}

@Composable
private fun TaskListItem(
    task: TaskEntity, density: Density,
    contexts: List<Pair<String, Long?>>, tags: List<Pair<String, Long?>>,
    rightAction: SwipeAction, leftAction: SwipeAction, isTrash: Boolean,
    onOpen: () -> Unit, onAct: (SwipeAction) -> Unit, onCycleFlag: () -> Unit, onToggleStar: () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> { onAct(rightAction); false }
            SwipeToDismissBoxValue.EndToStart -> { onAct(leftAction); false }
            else -> false
        }
    })
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val dir = state.dismissDirection
            val (color, icon, align) = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> { val (c, i) = swipeVisual(rightAction, isTrash); Triple(c, i, Alignment.CenterStart) }
                SwipeToDismissBoxValue.EndToStart -> { val (c, i) = swipeVisual(leftAction, false); Triple(c, i, Alignment.CenterEnd) }
                else -> Triple(Color.Transparent, Icons.Filled.Check, Alignment.CenterStart)
            }
            Box(Modifier.fillMaxSize().background(color).padding(horizontal = 22.dp), contentAlignment = align) {
                if (dir != SwipeToDismissBoxValue.Settled) Icon(icon, null, tint = Color.White)
            }
        },
    ) {
        val level = PriorityLevel.from(task.importance, task.urgency)
        val done = task.completed || task.abandoned
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable { onOpen() }
                .padding(start = 6.dp, end = 8.dp, top = rowVerticalPadding(density), bottom = rowVerticalPadding(density)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PriorityCheckbox(task.completed, level) { onAct(SwipeAction.COMPLETE) }
            Spacer(Modifier.width(2.dp))
            Column(Modifier.weight(1f).padding(vertical = 1.dp)) {
                Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                TaskMeta(dueMillis = task.dueDate, contexts = contexts, tags = tags, note = task.note)
            }
            Spacer(Modifier.width(2.dp))
            FlagStar(task.flagColorArgb, task.star, onCycleFlag, onToggleStar)
        }
    }
}
