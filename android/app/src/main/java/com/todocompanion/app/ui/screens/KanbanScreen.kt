package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.DueChip
import com.todocompanion.app.ui.components.priorityColor

private val COLUMNS = listOf(PriorityLevel.HIGH, PriorityLevel.MEDIUM, PriorityLevel.LOW, PriorityLevel.NONE)

@Composable
fun KanbanScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, modifier: Modifier = Modifier) {
    val groups by vm.groups.collectAsState()
    val tasks = remember(groups) { groups.flatMap { it.tasks } }
    val byLevel = tasks.groupBy { PriorityLevel.from(it.importance, it.urgency) }

    Row(modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)) {
        COLUMNS.forEach { level ->
            KanbanColumn(level, byLevel[level].orEmpty(), onOpenTask, onMove = { t, lvl -> vm.setPriority(t, lvl) })
        }
    }
}

@Composable
private fun KanbanColumn(level: PriorityLevel, tasks: List<TaskEntity>, onOpenTask: (String) -> Unit, onMove: (TaskEntity, PriorityLevel) -> Unit) {
    val color = priorityColor(level)
    Column(Modifier.width(268.dp).fillMaxHeight().padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(7.dp))
            Text(level.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.width(6.dp))
            Text(tasks.size.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.06f)).padding(6.dp)) {
            if (tasks.isEmpty()) Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                Text("Empty", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f))
            } else LazyColumn(contentPadding = PaddingValues(vertical = 2.dp)) {
                items(tasks, key = { it.id }) { t -> KanbanCard(t, level, color, onOpenTask, onMove) }
            }
        }
    }
}

@Composable
private fun KanbanCard(task: TaskEntity, level: PriorityLevel, color: androidx.compose.ui.graphics.Color, onOpenTask: (String) -> Unit, onMove: (TaskEntity, PriorityLevel) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val idx = COLUMNS.indexOf(level)
    Surface(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp,
    ) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(color))
            Column(Modifier.weight(1f).clickable { onOpenTask(task.id) }.padding(10.dp)) {
                Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                task.dueDate?.let { Spacer(Modifier.size(2.dp)); DueChip(it) }
            }
            Column(Modifier.padding(end = 2.dp), verticalArrangement = Arrangement.Center) {
                // Move to the higher-priority column (left) or lower (right).
                if (idx > 0) Icon(Icons.AutoMirrored.Filled.ArrowBack, "Raise priority", tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp).clickable { onMove(task, COLUMNS[idx - 1]) })
                if (idx < COLUMNS.lastIndex) Icon(Icons.AutoMirrored.Filled.ArrowForward, "Lower priority", tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp).clickable { onMove(task, COLUMNS[idx + 1]) })
            }
        }
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        COLUMNS.filter { it != level }.forEach { lvl -> DropdownMenuItem(text = { Text("Move to ${lvl.label}") }, onClick = { onMove(task, lvl); menu = false }) }
    }
}
