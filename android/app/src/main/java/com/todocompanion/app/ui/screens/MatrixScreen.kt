package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.todocompanion.app.domain.priority.PriorityEngine
import com.todocompanion.app.ui.AppViewModel
import kotlin.math.roundToInt

private val QUAD = listOf(
    Triple("Urgent & Important", "Do first", Color(0xFFE5484D)),
    Triple("Not Urgent & Important", "Schedule", Color(0xFFF59E0B)),
    Triple("Urgent & Unimportant", "Delegate", Color(0xFF3E7BFA)),
    Triple("Not Urgent & Unimportant", "Later", Color(0xFF12A594)),
)
private val ROMAN = listOf("I", "II", "III", "IV")

@Composable
fun MatrixScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, modifier: Modifier = Modifier) {
    val s by vm.settings.collectAsState()
    val tasks by vm.tasks.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    val visible = tasks.filter { !it.trashed && !it.abandoned && (s.matrixShowCompleted || !it.completed) }
    val byQuad = visible.groupBy { PriorityEngine.quadrant(it, s.matrixImportanceThreshold, s.matrixUrgencyThreshold) }
        .mapValues { (_, list) -> list.sortedByDescending { maxOf(it.importance, it.urgency) } }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Matrix", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showSettings = !showSettings }) { Icon(Icons.Filled.Tune, "Matrix settings") }
        }

        if (showSettings) {
            MatrixSettings(vm, s)
        }

        val onToggle: (TaskEntity) -> Unit = { vm.toggleComplete(it) }
        if (s.matrixHideEmpty) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                QUAD.indices.forEach { q ->
                    val list = byQuad[q].orEmpty()
                    if (list.isNotEmpty()) item(key = "q$q") { QuadrantCard(q, s.matrixNames.getOrElse(q) { QUAD[q].first }, list, onOpenTask, onToggle, Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 340.dp).padding(vertical = 4.dp)) }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 2.dp)) {
                for (rowIdx in 0..1) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        for (colIdx in 0..1) {
                            val q = rowIdx * 2 + colIdx
                            QuadrantCard(q, s.matrixNames.getOrElse(q) { QUAD[q].first }, byQuad[q].orEmpty(), onOpenTask, onToggle, Modifier.weight(1f).fillMaxSize().padding(3.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuadrantCard(q: Int, title: String, tasks: List<TaskEntity>, onOpenTask: (String) -> Unit, onToggle: (TaskEntity) -> Unit, modifier: Modifier) {
    val color = QUAD[q].third
    androidx.compose.material3.Surface(
        modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            // Header: roman-numeral badge + coloured title (TickTick grammar).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                    Text(ROMAN[q], style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.size(7.dp))
                Text(title, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.size(6.dp))
            if (tasks.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("No tasks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f))
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(tasks, key = { it.id }) { t ->
                        val lvl = com.todocompanion.app.domain.priority.PriorityLevel.from(t.importance, t.urgency)
                        Row(Modifier.fillMaxWidth().clickable { onOpenTask(t.id) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            com.todocompanion.app.ui.components.SmallCheck(t.completed, if (lvl == com.todocompanion.app.domain.priority.PriorityLevel.NONE) color else com.todocompanion.app.ui.components.priorityColor(lvl)) { onToggle(t) }
                            Spacer(Modifier.size(6.dp))
                            Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                                Text(
                                    t.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    textDecoration = if (t.completed) androidx.compose.ui.text.style.TextDecoration.LineThrough else androidx.compose.ui.text.style.TextDecoration.None,
                                    color = if (t.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                )
                                t.dueDate?.let { com.todocompanion.app.ui.components.DueChip(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatrixSettings(vm: AppViewModel, s: com.todocompanion.app.domain.AppSettings) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("Quadrant names", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        (0..3).forEach { q ->
            NameRow(QUAD[q].third, s.matrixNames.getOrElse(q) { QUAD[q].first }) { newName ->
                val names = s.matrixNames.toMutableList().also { while (it.size < 4) it.add(""); it[q] = newName }
                vm.saveSettings(s.copy(matrixNames = names))
            }
        }
        Spacer(Modifier.height(8.dp))
        ThresholdRow("Important when importance ≥", s.matrixImportanceThreshold) { vm.saveSettings(s.copy(matrixImportanceThreshold = it)) }
        ThresholdRow("Urgent when urgency ≥", s.matrixUrgencyThreshold) { vm.saveSettings(s.copy(matrixUrgencyThreshold = it)) }
        ToggleRow("Show completed", s.matrixShowCompleted) { vm.saveSettings(s.copy(matrixShowCompleted = it)) }
        ToggleRow("List view (hide empty quadrants)", s.matrixHideEmpty) { vm.saveSettings(s.copy(matrixHideEmpty = it)) }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun NameRow(color: Color, value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true,
            modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ThresholdRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label $value", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt().coerceIn(2, 5)) }, valueRange = 2f..5f, steps = 2, modifier = Modifier.width(150.dp))
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
