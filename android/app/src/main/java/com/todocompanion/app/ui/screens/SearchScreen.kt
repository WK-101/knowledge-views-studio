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

/** Search result filters. */
private enum class SF(val label: String) { ALL("All"), TODAY("Today"), OVERDUE("Overdue"), FLAGGED("Flagged"), HIGH("High priority"), DONE("Completed"), TRASH("Trashed") }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, query: String, modifier: Modifier = Modifier, onOpenHabit: (String) -> Unit = {}) {
    val tasks by vm.tasks.collectAsState()
    val habits by vm.habits.collectAsState()
    // R54 — FTS-accelerated for large histories, instant in-memory for small sets (see vm.searchAsync).
    val results by androidx.compose.runtime.produceState(initialValue = emptyList<com.todocompanion.app.data.entity.TaskEntity>(), query, tasks) {
        value = vm.searchAsync(query)
    }
    // E1: habits are searchable too — shown only under the "All" filter (task filters don't apply).
    val habitResults = remember(query, habits) { vm.searchHabits(query) }
    // R56 — attachment names are searchable; map taskId → the matched file name for the "📎 …" hint.
    val attachHits = remember(query) { vm.searchAttachmentNames(query).associate { it.taskId to it.fileName } }
    val lists by vm.lists.collectAsState()
    val folders by vm.folders.collectAsState()
    var filter by remember { mutableStateOf(SF.ALL) }
    val zone = java.time.ZoneId.systemDefault()
    val shown = remember(results, filter) {
        val today = java.time.LocalDate.now(); val nowMs = System.currentTimeMillis()
        results.filter { t ->
            // Trashed tasks appear ONLY under the Trashed filter — every other filter hides them so the
            // default results stay clean, while nothing is unfindable (R56).
            when (filter) {
                SF.TRASH -> t.trashed
                SF.ALL -> !t.trashed
                SF.TODAY -> !t.trashed && t.dueDate?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today } == true
                SF.OVERDUE -> !t.trashed && t.dueDate?.let { it < nowMs && !t.completed } == true
                SF.FLAGGED -> !t.trashed && t.flagId != null
                SF.HIGH -> !t.trashed && com.todocompanion.app.domain.priority.PriorityLevel.from(t.importance, t.urgency) == com.todocompanion.app.domain.priority.PriorityLevel.HIGH
                SF.DONE -> !t.trashed && t.completed
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        // The search field lives in the app top bar; this screen renders filters + results.
        if (query.isNotBlank()) {
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SF.entries.forEach { f ->
                    androidx.compose.material3.FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
        }
        val showHabits = filter == SF.ALL && habitResults.isNotEmpty()
        when {
            query.isBlank() -> SearchHint("Search everything", "Find any task or habit by title, note, #tag, @context or 📎 attachment name — completed, someday and archived included; tap Trashed to search the bin")
            shown.isEmpty() && !showHabits -> SearchHint("No matches", "Nothing found for “$query”", off = true)
            else -> {
                val totalN = shown.size + (if (showHabits) habitResults.size else 0)
                Text("$totalN result${if (totalN == 1) "" else "s"}",
                    Modifier.padding(start = 18.dp, top = 2.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (showHabits) {
                        item(key = "habits-header") {
                            Text("HABITS", Modifier.padding(start = 18.dp, top = 4.dp, bottom = 2.dp),
                                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                        items(habitResults, key = { "h:" + it.id }) { h ->
                            Surface(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                                shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().clickable { onOpenHabit(h.id) }.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background((h.colorArgb?.let { androidx.compose.ui.graphics.Color(it) } ?: MaterialTheme.colorScheme.primary).copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                                        Text(h.emoji ?: "🔁", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(h.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                        val sub = listOfNotNull(h.category.ifBlank { null }, h.identity.ifBlank { null }, h.description.ifBlank { null }).firstOrNull()
                                        if (sub != null) Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Habit" + (if (h.paused) " · paused" else "") + (if (h.archived) " · archived" else ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        if (shown.isNotEmpty()) item(key = "tasks-header") {
                            Text("TASKS", Modifier.padding(start = 18.dp, top = 10.dp, bottom = 2.dp),
                                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    items(shown, key = { it.id }) { task ->
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
                                    // R56 — when the match came from an attachment name, show which file, so an
                                    // expert instantly sees why a task surfaced.
                                    attachHits[task.id]?.let { fn ->
                                        Text("📎 $fn", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                    // Location: the task's list, or — for a task captured straight into a
                                    // folder (empty listId) — the folder name, rather than a wrong "Inbox".
                                    val loc = lists.firstOrNull { it.id == task.listId }?.name
                                        ?: task.folderId?.let { fid -> folders.firstOrNull { it.id == fid }?.let { "📁 " + it.name } }
                                        ?: "Inbox"
                                    val state = when {
                                        task.trashed -> " · 🗑 Trash"
                                        task.completed -> " · done"
                                        task.someday -> " · someday"
                                        else -> ""
                                    }
                                    Text(loc + state, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
