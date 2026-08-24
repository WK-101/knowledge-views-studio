package com.todocompanion.app.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.Dot
import com.todocompanion.app.ui.components.DueChip
import com.todocompanion.app.ui.components.priorityColor

@Composable
fun DoNextScreen(
    vm: AppViewModel,
    onOpenTask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ranked by vm.doNext.collectAsState()
    if (ranked.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing to do right now 🎉", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(ranked, key = { it.task.id }) { r ->
            val task = r.task
            val level = PriorityLevel.from(task.importance, task.urgency)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenTask(task.id) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = task.completed, onCheckedChange = { vm.toggleComplete(task) })
                if (level != PriorityLevel.NONE) {
                    Dot(priorityColor(level)); Spacer(Modifier.width(6.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "score ${"%.1f".format(r.score)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                task.dueDate?.let { Spacer(Modifier.width(6.dp)); DueChip(it) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}
