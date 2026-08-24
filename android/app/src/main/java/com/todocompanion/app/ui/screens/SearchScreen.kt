package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.DueChip

@Composable
fun SearchScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val tasks by vm.tasks.collectAsState()
    val results = remember(query, tasks) { vm.search(query) }
    val lists by vm.lists.collectAsState()

    Column(modifier.fillMaxSize()) {
        // Clean filled search bar — no hard outlined box.
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search tasks, notes, #tags…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    BasicTextField(
                        value = query, onValueChange = { query = it },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) Icon(Icons.Filled.Close, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).clickable { query = "" })
            }
        }

        when {
            query.isBlank() -> SearchHint("Search everything", "Find any task by title, note, #tag or @context")
            results.isEmpty() -> SearchHint("No matches", "Nothing found for “$query”", off = true)
            else -> {
                Text("${results.size} result${if (results.size == 1) "" else "s"}",
                    Modifier.padding(start = 18.dp, top = 2.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(results, key = { it.id }) { task ->
                        val level = com.todocompanion.app.domain.priority.PriorityLevel.from(task.importance, task.urgency)
                        Surface(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenTask(task.id) }.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                com.todocompanion.app.ui.components.Dot(
                                    if (level == com.todocompanion.app.domain.priority.PriorityLevel.NONE) MaterialTheme.colorScheme.outlineVariant
                                    else com.todocompanion.app.ui.components.priorityColor(level), 8,
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(task.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                    if (task.note.isNotBlank()) Text(task.note.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    val listName = lists.firstOrNull { it.id == task.listId }?.name ?: "Inbox"
                                    Text(listName + (if (task.completed) " · done" else ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                task.dueDate?.let { Spacer(Modifier.width(6.dp)); DueChip(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHint(title: String, subtitle: String, off: Boolean = false) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(80.dp).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)), contentAlignment = Alignment.Center) {
            Icon(if (off) Icons.Outlined.SearchOff else Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.size(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
