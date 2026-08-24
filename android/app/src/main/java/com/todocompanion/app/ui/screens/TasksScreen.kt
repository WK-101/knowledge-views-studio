package com.todocompanion.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.Density
import com.todocompanion.app.domain.SwipeAction
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.domain.view.SmartKind
import com.todocompanion.app.domain.view.ViewRef
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.FlagStar
import com.todocompanion.app.ui.components.DueChip
import com.todocompanion.app.ui.components.PriorityCheckbox
import com.todocompanion.app.ui.components.rowVerticalPadding

@Composable
fun TasksScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, modifier: Modifier = Modifier) {
    val outline by vm.outlineMode.collectAsState()
    val view by vm.currentView.collectAsState()
    val settings by vm.settings.collectAsState()

    if (outline && vm.canOutline()) { OutlineList(vm, settings.density, onOpenTask, modifier); return }

    val groups by vm.groups.collectAsState()
    val isTrash = (view as? ViewRef.Smart)?.kind == SmartKind.TRASH
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    if (groups.isEmpty() || groups.all { it.tasks.isEmpty() }) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing here yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
        groups.forEach { group ->
            val open = collapsed[group.key] != true
            if (group.title.isNotBlank()) {
                item(key = "h_" + group.key) { GroupHeader(group.title, group.tasks.size, open) { collapsed[group.key] = open } }
            }
            if (open) items(group.tasks, key = { it.id }) { task ->
                val right = if (isTrash) SwipeAction.COMPLETE else settings.swipeRight  // COMPLETE reused as "restore" label below
                val left = if (isTrash) SwipeAction.TRASH else settings.swipeLeft
                TaskListItem(
                    task = task, density = settings.density, rightAction = right, leftAction = left, isTrash = isTrash,
                    onOpen = { onOpenTask(task.id) },
                    onAct = { a -> onSwipe(vm, a, task, isTrash) { onOpenTask(task.id) } },
                    onCycleFlag = { vm.cycleFlag(task) },
                    onToggleStar = { vm.toggleStar(task) },
                )
            }
        }
    }
}

private fun onSwipe(vm: AppViewModel, action: SwipeAction, task: TaskEntity, isTrash: Boolean, onOpen: () -> Unit) {
    if (isTrash) {
        if (action == SwipeAction.COMPLETE) vm.restore(task) else vm.deleteForever(task)
        return
    }
    if (!vm.applyAction(action, task)) onOpen()
}

@Composable
private fun OutlineList(vm: AppViewModel, density: Density, onOpenTask: (String) -> Unit, modifier: Modifier) {
    val rows by vm.outlineRows.collectAsState()
    if (rows.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Empty list — tap + to add", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(rows, key = { it.task.id }) { row ->
            com.todocompanion.app.ui.components.TaskRow(row, density,
                onClick = { onOpenTask(row.task.id) },
                onToggleComplete = { vm.toggleComplete(row.task) },
                onToggleCollapse = { vm.toggleCollapsed(row.task) },
                onCycleFlag = { vm.cycleFlag(row.task) },
                onToggleStar = { vm.toggleStar(row.task) },
                onDelete = { vm.trash(row.task) })
        }
    }
}

@Composable
private fun GroupHeader(title: String, count: Int, open: Boolean, onToggle: () -> Unit) {
    val a by animateFloatAsState(if (open) 0f else -90f, label = "chev")
    Row(Modifier.fillMaxWidth().clickable { onToggle() }.padding(start = 15.dp, end = 15.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).rotate(a))
    }
}

private fun swipeVisual(action: SwipeAction, isTrashRestore: Boolean): Pair<Color, ImageVector> = when {
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
    task: TaskEntity,
    density: Density,
    rightAction: SwipeAction,
    leftAction: SwipeAction,
    isTrash: Boolean,
    onOpen: () -> Unit,
    onAct: (SwipeAction) -> Unit,
    onCycleFlag: () -> Unit,
    onToggleStar: () -> Unit,
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
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable { onOpen() }
                .padding(horizontal = 10.dp, vertical = rowVerticalPadding(density)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PriorityCheckbox(task.completed, level) { onAct(SwipeAction.COMPLETE) }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(task.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                    color = if (task.completed || task.abandoned) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                task.dueDate?.let { DueChip(it) }
            }
            FlagStar(task.flagColorArgb, task.star, onCycleFlag, onToggleStar)
        }
    }
}
