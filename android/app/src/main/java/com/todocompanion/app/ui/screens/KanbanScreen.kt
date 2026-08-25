package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.DueChip
import com.todocompanion.app.ui.components.priorityColor

private enum class KanbanBy(val label: String) { PRIORITY("Priority"), FLAG("Flag"), LIST("List") }

/** One board column: a key each task is bucketed under, a header, and how to move a task into it. */
private data class KCol(val key: String, val label: String, val color: Color, val move: (TaskEntity) -> Unit)

@Composable
fun KanbanScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, modifier: Modifier = Modifier) {
    val groups by vm.groups.collectAsState()
    val flags by vm.flags.collectAsState()
    val lists by vm.lists.collectAsState()
    val tasks = remember(groups) { groups.flatMap { it.tasks } }
    var by by remember { mutableStateOf(KanbanBy.PRIORITY) }
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary

    // Build the columns + a task→column-key mapping for the chosen grouping.
    val (columns, keyOf) = remember(by, flags, lists) {
        when (by) {
            KanbanBy.PRIORITY -> {
                val cols = listOf(PriorityLevel.HIGH, PriorityLevel.MEDIUM, PriorityLevel.LOW, PriorityLevel.NONE).map { lvl ->
                    KCol(lvl.name, lvl.label, priorityColor(lvl)) { t -> vm.setPriority(t, lvl) }
                }
                cols to { t: TaskEntity -> PriorityLevel.from(t.importance, t.urgency).name }
            }
            KanbanBy.FLAG -> {
                val cols = flags.sortedBy { it.sortOrder }.map { f ->
                    KCol(f.id, f.name, Color(f.colorArgb)) { t -> vm.setFlag(t, f.id) }
                } + KCol("none", "No flag", outline) { t -> vm.setFlag(t, null) }
                cols to { t: TaskEntity -> t.flagId ?: "none" }
            }
            KanbanBy.LIST -> {
                val cols = lists.filter { !it.archived }.map { l ->
                    KCol(l.id, l.name, l.colorArgb?.let { Color(it) } ?: primary) { t -> vm.moveToList(t, l.id) }
                }
                cols to { t: TaskEntity -> t.listId }
            }
        }
    }
    val byKey = tasks.groupBy(keyOf)

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Group:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
            KanbanBy.entries.forEach { m -> FilterChip(selected = by == m, onClick = { by = m }, label = { Text(m.label) }) }
        }
        Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)) {
            columns.forEachIndexed { i, col ->
                KanbanColumn(col, byKey[col.key].orEmpty(), onOpenTask,
                    onLeft = if (i > 0) ({ t: TaskEntity -> columns[i - 1].move(t) }) else null,
                    onRight = if (i < columns.lastIndex) ({ t: TaskEntity -> columns[i + 1].move(t) }) else null,
                    others = columns.filter { it.key != col.key })
            }
        }
    }
}

@Composable
private fun KanbanColumn(
    col: KCol, tasks: List<TaskEntity>, onOpenTask: (String) -> Unit,
    onLeft: ((TaskEntity) -> Unit)?, onRight: ((TaskEntity) -> Unit)?, others: List<KCol>,
) {
    Column(Modifier.width(268.dp).fillMaxHeight().padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(col.color))
            Spacer(Modifier.width(7.dp))
            Text(col.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = col.color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(6.dp))
            Text(tasks.size.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)).background(col.color.copy(alpha = 0.06f)).padding(6.dp)) {
            if (tasks.isEmpty()) Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                Text("Empty", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f))
            } else LazyColumn(contentPadding = PaddingValues(vertical = 2.dp)) {
                items(tasks, key = { it.id }) { t -> KanbanCard(t, col.color, onOpenTask, onLeft, onRight, others) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KanbanCard(
    task: TaskEntity, color: Color, onOpenTask: (String) -> Unit,
    onLeft: ((TaskEntity) -> Unit)?, onRight: ((TaskEntity) -> Unit)?, others: List<KCol>,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp,
    ) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(color))
            // Tap opens the task; long-press opens the "Move to…" column menu.
            Column(Modifier.weight(1f).combinedClickable(onClick = { onOpenTask(task.id) }, onLongClick = { menu = true }).padding(10.dp)) {
                Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                task.dueDate?.let { Spacer(Modifier.size(2.dp)); DueChip(it) }
            }
            Column(Modifier.padding(end = 2.dp), verticalArrangement = Arrangement.Center) {
                if (onLeft != null) Icon(Icons.AutoMirrored.Filled.ArrowBack, "Move left", tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp).clickable { onLeft(task) })
                if (onRight != null) Icon(Icons.AutoMirrored.Filled.ArrowForward, "Move right", tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp).clickable { onRight(task) })
            }
        }
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        others.forEach { c -> DropdownMenuItem(text = { Text("Move to ${c.label}") }, onClick = { c.move(task); menu = false }) }
    }
}
