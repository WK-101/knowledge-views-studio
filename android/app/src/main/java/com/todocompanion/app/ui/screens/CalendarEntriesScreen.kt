package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.todocompanion.app.data.entity.EventCalendarEntity
import com.todocompanion.app.data.entity.EventEntity
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppTextField
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * R55 — "All entries": see and manage every event of one calendar (or all calendars combined) in one
 * place. Search, sort, tap to edit, delete — and, uniquely versus Google/Outlook (whose "loudest pain"
 * is that you can't), MULTI-SELECT to bulk-delete many events at once. Offline; reads the local store.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalendarEntriesSheet(
    vm: AppViewModel,
    calendars: List<EventCalendarEntity>,
    initialCalId: String?,
    onEdit: (EventEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val allEvents by vm.events.collectAsState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val calById = remember(calendars) { calendars.associateBy { it.id } }

    var calFilter by remember { mutableStateOf(initialCalId) }   // null = All calendars combined
    var query by remember { mutableStateOf("") }
    var newestFirst by remember { mutableStateOf(true) }
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmBulk by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<EventEntity?>(null) }

    val q = query.trim().lowercase()
    val shown = remember(allEvents, calFilter, q, newestFirst) {
        allEvents
            .filter { calFilter == null || it.calendarId == calFilter }
            .filter { it.recurrenceParentId == null }   // one row per series (overrides are hidden)
            .filter { q.isBlank() || it.title.lowercase().contains(q) || it.location.lowercase().contains(q) || it.notes.lowercase().contains(q) }
            .sortedByDescending { if (newestFirst) it.startMillis else -it.startMillis }
    }
    val df = DateTimeFormatter.ofPattern("EEE d MMM yyyy · h:mm a")

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("All entries", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${shown.size} event${if (shown.size == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            // Calendar filter: All + each.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = calFilter == null, onClick = { calFilter = null }, label = { Text("🗂️ All") })
                calendars.sortedBy { it.orderIndex }.forEach { c ->
                    FilterChip(selected = calFilter == c.id, onClick = { calFilter = c.id },
                        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c.colorArgb))) },
                        label = { Text(c.name) })
                }
            }
            Spacer(Modifier.height(8.dp))
            AppTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search title, place, notes") })
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { newestFirst = !newestFirst }) { Text(if (newestFirst) "Newest first ↓" else "Oldest first ↑") }
                Spacer(Modifier.weight(1f))
                if (selectMode) {
                    Text("${selected.size} selected", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { if (selected.isNotEmpty()) confirmBulk = true }, enabled = selected.isNotEmpty()) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { selectMode = false; selected = emptySet() }) { Text("Done") }
                } else {
                    TextButton(onClick = { selectMode = true }) { Text("Select") }
                }
            }

            if (shown.isEmpty()) {
                Text("No events here.", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                items(shown, key = { it.id }) { e ->
                    val cal = calById[e.calendarId]
                    Row(Modifier.fillMaxWidth().clickable {
                        if (selectMode) selected = if (e.id in selected) selected - e.id else selected + e.id
                        else onEdit(e)
                    }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selectMode) {
                            Icon(if (e.id in selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                                Modifier.size(22.dp), tint = if (e.id in selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                        } else {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(cal?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.primary))
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(e.title.ifBlank { "(untitled)" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Instant.ofEpochMilli(e.startMillis).atZone(zone).format(df) + (if (e.rrule.isNotBlank()) " · repeats" else ""),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!selectMode) IconButton(onClick = { pendingDelete = e }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    pendingDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = { TextButton(onClick = { vm.deleteEvent(e.id); pendingDelete = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
            title = { Text("Delete event?") },
            text = { Text("“${e.title.ifBlank { "(untitled)" }}”${if (e.rrule.isNotBlank()) " and its whole repeating series" else ""} will be permanently deleted.") },
        )
    }
    if (confirmBulk) {
        AlertDialog(
            onDismissRequest = { confirmBulk = false },
            confirmButton = { TextButton(onClick = {
                selected.forEach { vm.deleteEvent(it) }; confirmBulk = false; selectMode = false; selected = emptySet()
            }) { Text("Delete ${selected.size}", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmBulk = false }) { Text("Cancel") } },
            title = { Text("Delete ${selected.size} events?") },
            text = { Text("The selected events (and any repeating series among them) will be permanently deleted. This can't be undone.") },
        )
    }
}
