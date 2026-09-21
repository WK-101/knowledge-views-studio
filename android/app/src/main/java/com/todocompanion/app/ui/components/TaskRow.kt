package com.todocompanion.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.Density
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.ui.OutlineRow
import com.todocompanion.app.ui.theme.LocalKairoColors

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun TaskRow(
    row: OutlineRow,
    density: Density,
    onClick: () -> Unit,
    onToggleComplete: () -> Unit,
    onToggleCollapse: () -> Unit,
    onCycleFlag: () -> Unit,
    onToggleStar: () -> Unit,
    onDelete: () -> Unit,
    onZoom: () -> Unit = {},
    onSetPriority: ((PriorityLevel) -> Unit)? = null,
) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> { onToggleComplete(); false }
            SwipeToDismissBoxValue.EndToStart -> { onDelete(); false }
            else -> false
        }
    })
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val dir = state.dismissDirection
            val k = LocalKairoColors.current
            val (color, icon, align) = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> Triple(k.good, Icons.Filled.Check, Alignment.CenterStart)
                SwipeToDismissBoxValue.EndToStart -> Triple(k.bad, Icons.Filled.Delete, Alignment.CenterEnd)
                else -> Triple(Color.Transparent, Icons.Filled.Check, Alignment.CenterStart)
            }
            Box(Modifier.fillMaxSize().background(color).padding(horizontal = 20.dp), contentAlignment = align) {
                if (dir != SwipeToDismissBoxValue.Settled) Icon(icon, null, tint = Color.White)
            }
        },
    ) {
        val task = row.task
        val level = PriorityLevel.from(task.importance, task.urgency)
        // Ceiling 2 — TalkBack / switch-access fallbacks for the gesture-only swipe actions (complete via
        // StartToEnd, delete via EndToStart) plus expand/collapse, so every action the row supports has a
        // non-gesture path. Without these a screen-reader user could not delete a task from the row at all.
        val rowActions = buildList {
            add(CustomAccessibilityAction(if (task.completed) "Mark not done" else "Mark done") { onToggleComplete(); true })
            add(CustomAccessibilityAction("Delete") { onDelete(); true })
            if (row.hasChildren) add(CustomAccessibilityAction(if (row.collapsed) "Expand subtasks" else "Collapse subtasks") { onToggleCollapse(); true })
        }
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                // Structural ancestors in a filtered outline are dimmed; matches stay solid.
                .graphicsLayer { alpha = if (row.matched) 1f else 0.5f }
                .combinedClickable(onClickLabel = "Open task", role = Role.Button, onLongClickLabel = "Zoom into subtask", onClick = onClick, onLongClick = onZoom)
                .semantics { customActions = rowActions }
                .padding(start = (6 + row.depth * 18).dp, end = 6.dp, top = rowVerticalPadding(density) / 2, bottom = rowVerticalPadding(density) / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.hasChildren) {
                // 48dp touch target (was 30dp — below the Material minimum); the leaf Spacer matches it so the
                // checkboxes stay aligned across rows with and without children.
                IconButton(onClick = onToggleCollapse, modifier = Modifier.size(48.dp)) {
                    Icon(if (row.collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, if (row.collapsed) "Expand" else "Collapse")
                }
            } else Spacer(Modifier.width(48.dp))

            PriorityCheckbox(task.completed, level, onToggleComplete, onSetLevel = onSetPriority)
            Spacer(Modifier.width(4.dp))
            Text(
                task.title, Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                // Completed: muted grey, no strike-through (a line makes the title hard to read).
                color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f) else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            task.dueDate?.let { DueChip(it); Spacer(Modifier.width(2.dp)) }
            FlagStar(task.flagColorArgb, task.star, onCycleFlag, onToggleStar, iconSize = flagStarSize(density))
        }
    }
}
