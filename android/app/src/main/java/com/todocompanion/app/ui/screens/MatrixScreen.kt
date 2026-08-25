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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatrixScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, showSettings: Boolean, onDismissSettings: () -> Unit, modifier: Modifier = Modifier) {
    val s by vm.settings.collectAsState()
    val tasks by vm.tasks.collectAsState()

    val now = System.currentTimeMillis()
    val visible = tasks.filter {
        !it.trashed && !it.abandoned && (s.matrixShowCompleted || !it.completed) &&
            // List filter (empty = all).
            (s.matrixListFilter.isEmpty() || it.listId in s.matrixListFilter) &&
            // Duration cap: keep tasks estimated to fit; unestimated tasks always pass.
            (s.matrixMaxDuration == 0 || ((it.estimateMin ?: it.estimateMax ?: it.durationMin)?.let { d -> d <= s.matrixMaxDuration } ?: true)) &&
            // Overdue-only: past its due date and not yet done.
            (!s.matrixOverdueOnly || (it.dueDate != null && it.dueDate!! < now && !it.completed))
    }
    val byQuad = visible.groupBy { PriorityEngine.quadrant(it, s.matrixImportanceThreshold, s.matrixUrgencyThreshold) }
        .mapValues { (_, list) -> list.sortedByDescending { maxOf(it.importance, it.urgency) } }

    // Drag-to-move: long-press a task and drop it into another quadrant. Tracked in window coordinates
    // so it works in both the 2×2 grid and the stacked list layout.
    var draggingTask by remember { mutableStateOf<TaskEntity?>(null) }
    var pointerWin by remember { mutableStateOf(Offset.Zero) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    val quadRects = remember { mutableStateMapOf<Int, Rect>() }
    val dens = LocalDensity.current
    val dropTarget = draggingTask?.let { (0..3).firstOrNull { q -> quadRects[q]?.contains(pointerWin) == true } }
    val drag = MatrixDrag(
        draggingId = draggingTask?.id,
        onBounds = { q, r -> quadRects[q] = r },
        onStart = { id -> draggingTask = visible.firstOrNull { it.id == id } },
        onDrag = { win -> pointerWin = win },
        onEnd = {
            val t = draggingTask
            if (t != null) {
                val cur = PriorityEngine.quadrant(t, s.matrixImportanceThreshold, s.matrixUrgencyThreshold)
                val target = (0..3).firstOrNull { q -> quadRects[q]?.contains(pointerWin) == true }
                if (target != null && target != cur) vm.setMatrixQuadrant(t, target, s.matrixImportanceThreshold, s.matrixUrgencyThreshold)
            }
            draggingTask = null
        },
    )

    // The settings button lives in the app top bar now, so the grid uses the full screen.
    Box(modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionInWindow() }) {
      Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
        val onToggle: (TaskEntity) -> Unit = { vm.toggleComplete(it) }
        if (s.matrixHideEmpty) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                QUAD.indices.forEach { q ->
                    val list = byQuad[q].orEmpty()
                    if (list.isNotEmpty()) item(key = "q$q") { QuadrantCard(q, s.matrixNames.getOrElse(q) { QUAD[q].first }, list, onOpenTask, onToggle, drag, dropTarget == q, Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 400.dp).padding(vertical = 4.dp)) }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 2.dp)) {
                for (rowIdx in 0..1) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        for (colIdx in 0..1) {
                            val q = rowIdx * 2 + colIdx
                            QuadrantCard(q, s.matrixNames.getOrElse(q) { QUAD[q].first }, byQuad[q].orEmpty(), onOpenTask, onToggle, drag, dropTarget == q, Modifier.weight(1f).fillMaxSize().padding(4.dp))
                        }
                    }
                }
            }
        }
      }
      // Floating chip that follows the finger while dragging.
      draggingTask?.let { t ->
          val local = pointerWin - rootOrigin
          androidx.compose.material3.Surface(
              Modifier.zIndex(3f).offset { IntOffset((local.x - with(dens) { 70.dp.toPx() }).roundToInt(), (local.y - with(dens) { 20.dp.toPx() }).roundToInt()) },
              shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 10.dp,
          ) {
              Text(t.title, Modifier.padding(horizontal = 12.dp, vertical = 8.dp).width(140.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                  style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
          }
      }
    }

    if (showSettings) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismissSettings) {
            MatrixSettings(vm, s)
            Spacer(Modifier.height(20.dp))
        }
    }
}

private class MatrixDrag(
    val draggingId: String?,
    val onBounds: (Int, Rect) -> Unit,
    val onStart: (String) -> Unit,
    val onDrag: (Offset) -> Unit,
    val onEnd: () -> Unit,
)

@Composable
private fun QuadrantCard(q: Int, title: String, tasks: List<TaskEntity>, onOpenTask: (String) -> Unit, onToggle: (TaskEntity) -> Unit, drag: MatrixDrag, isDropTarget: Boolean, modifier: Modifier) {
    val color = QUAD[q].third
    androidx.compose.material3.Surface(
        modifier.onGloballyPositioned { drag.onBounds(q, it.boundsInWindow()) }
            .then(if (isDropTarget) Modifier.border(2.dp, color, RoundedCornerShape(16.dp)) else Modifier),
        shape = RoundedCornerShape(16.dp),
        color = if (isDropTarget) color.copy(alpha = .10f) else MaterialTheme.colorScheme.surface,
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
                        var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                        val beingDragged = drag.draggingId == t.id
                        Row(Modifier.fillMaxWidth()
                            .onGloballyPositioned { rowCoords = it }
                            .then(if (beingDragged) Modifier.background(color.copy(alpha = .14f), RoundedCornerShape(8.dp)) else Modifier)
                            .clickable { onOpenTask(t.id) }
                            .pointerInput(t.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { off -> drag.onStart(t.id); rowCoords?.let { drag.onDrag(it.localToWindow(off)) } },
                                    onDrag = { change, _ -> rowCoords?.let { drag.onDrag(it.localToWindow(change.position)) }; change.consume() },
                                    onDragEnd = { drag.onEnd() },
                                    onDragCancel = { drag.onEnd() },
                                )
                            }
                            .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MatrixSettings(vm: AppViewModel, s: com.todocompanion.app.domain.AppSettings) {
    val lists by vm.lists.collectAsState()
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text("Matrix settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
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

        androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Filters", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))

        // Overdue-only.
        ToggleRow("Overdue only", s.matrixOverdueOnly) { vm.saveSettings(s.copy(matrixOverdueOnly = it)) }

        // Duration cap. 0 = Any; steps of 15 min up to 4h.
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val durLabel = if (s.matrixMaxDuration == 0) "Any" else "≤ ${s.matrixMaxDuration} min"
            Text("Max estimated time  $durLabel", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = s.matrixMaxDuration.toFloat(), onValueChange = { vm.saveSettings(s.copy(matrixMaxDuration = (it / 15f).roundToInt() * 15)) },
                valueRange = 0f..240f, steps = 15, modifier = Modifier.width(150.dp),
            )
        }

        // Lists to include (empty = all).
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Lists", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.matrixListFilter.isNotEmpty()) Text("Clear", Modifier.clickable { vm.saveSettings(s.copy(matrixListFilter = emptySet())) },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val allLabel = s.matrixListFilter.isEmpty()
            FilterChipCell("All", allLabel) { vm.saveSettings(s.copy(matrixListFilter = emptySet())) }
            lists.filter { !it.archived }.forEach { l ->
                val on = l.id in s.matrixListFilter
                FilterChipCell((l.emoji?.plus(" ") ?: "") + l.name, on) {
                    val next = if (on) s.matrixListFilter - l.id else s.matrixListFilter + l.id
                    vm.saveSettings(s.copy(matrixListFilter = next))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipCell(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
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
