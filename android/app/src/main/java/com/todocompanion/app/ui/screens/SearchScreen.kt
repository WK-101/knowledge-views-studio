package com.todocompanion.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.DueChip

@Composable
fun SearchScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    // recompute when tasks change too
    val tasks by vm.tasks.collectAsState()
    val results = remember(query, tasks) { vm.search(query) }
    val lists by vm.lists.collectAsState()

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            placeholder = { Text("Search tasks and notes…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
        )
        if (query.isBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Type to search everything", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matches for \"$query\"", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                items(results, key = { it.id }) { task ->
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().clickable { onOpenTask(task.id) }.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(task.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            val listName = lists.firstOrNull { it.id == task.listId }?.name ?: "Inbox"
                            Text(listName + (if (task.completed) " · done" else ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        task.dueDate?.let { Spacer(Modifier.width(6.dp)); DueChip(it) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}
