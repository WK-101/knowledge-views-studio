package com.todocompanion.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.Density
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.ui.OutlineRow

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
            val (color, icon, align) = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> Triple(Color(0xFF12A594), Icons.Filled.Check, Alignment.CenterStart)
                SwipeToDismissBoxValue.EndToStart -> Triple(Color(0xFFE5484D), Icons.Filled.Delete, Alignment.CenterEnd)
                else -> Triple(Color.Transparent, Icons.Filled.Check, Alignment.CenterStart)
            }
            Box(Modifier.fillMaxSize().background(color).padding(horizontal = 20.dp), contentAlignment = align) {
                if (dir != SwipeToDismissBoxValue.Settled) Icon(icon, null, tint = Color.White)
            }
        },
    ) {
        val task = row.task
        val level = PriorityLevel.from(task.importance, task.urgency)
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .combinedClickable(onClick = onClick, onLongClick = onZoom)
                .padding(start = (6 + row.depth * 18).dp, end = 6.dp, top = rowVerticalPadding(density) / 2, bottom = rowVerticalPadding(density) / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.hasChildren) {
                IconButton(onClick = onToggleCollapse, modifier = Modifier.size(30.dp)) {
                    Icon(if (row.collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, if (row.collapsed) "Expand" else "Collapse")
                }
            } else Spacer(Modifier.width(30.dp))

            PriorityCheckbox(task.completed, level, onToggleComplete)
            Spacer(Modifier.width(4.dp))
            Text(
                task.title, Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None,
                color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            task.dueDate?.let { DueChip(it); Spacer(Modifier.width(2.dp)) }
            FlagStar(task.flagColorArgb, task.star, onCycleFlag, onToggleStar, iconSize = flagStarSize(density))
        }
    }
}
