package com.todocompanion.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.TaskRow

@Composable
fun OutlineScreen(
    vm: AppViewModel,
    onOpenTask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows by vm.outline.collectAsState()
    if (rows.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No tasks yet — tap + to add one", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(rows, key = { it.task.id }) { row ->
            TaskRow(
                row = row,
                onClick = { onOpenTask(row.task.id) },
                onToggleComplete = { vm.toggleComplete(row.task) },
                onToggleCollapse = { vm.toggleCollapsed(row.task) },
                onDelete = { vm.delete(row.task) },
            )
        }
    }
}
