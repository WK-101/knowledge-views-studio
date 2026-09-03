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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
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
import com.todocompanion.app.ui.components.OptionChips
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** R56 — expert filters for the entries manager. */
private enum class EScope(val label: String) {
    ALL("All"), UPCOMING("Upcoming"), PAST("Past"), REPEATING("Repeating"), ALLDAY("All-day"),
    DUPLICATES("Duplicates"), STALE("Old (6m+)")
}
private enum class ESort(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), TITLE("Title A–Z"), DURATION("Longest") }

/**
 * R55/R56 — "All entries": see and manage every event of one calendar (or all combined) in one place, now
 * expert-grade. Filter by scope (upcoming / past / repeating / all-day), find DUPLICATES and STALE old
 * events for cleanup, sort four ways, search, tap to edit, delete one, or MULTI-SELECT to bulk-delete or
 * bulk-MOVE to another calendar — the exact bulk management Google/Outlook make you do one-by-one. Offline.
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
    val now = System.currentTimeMillis()
    val staleBefore = now - 182L * 24 * 3600 * 1000  // ~6 months

    var calFilter by remember { mutableStateOf(initialCalId) }   // null = All calendars combined
    var scope by remember { mutableStateOf(EScope.ALL) }
    var sort by remember { mutableStateOf(ESort.NEWEST) }
    var query by remember { mutableStateOf("") }
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmBulk by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<EventEntity?>(null) }
    var seriesFor by remember { mutableStateOf<String?>(null) }   // R57 per-instance cleanup: series event id

    val q = query.trim().lowercase()
    // Base set: one row per series (overrides hidden), calendar + search filtered.
    val base = remember(allEvents, calFilter, q) {
        allEvents
            .filter { calFilter == null || it.calendarId == calFilter }
            .filter { it.recurrenceParentId == null }
            .filter { q.isBlank() || it.title.lowercase().contains(q) || it.location.lowercase().contains(q) || it.notes.lowercase().contains(q) }
    }
    // Duplicate detection: same title + same start instant appearing more than once.
    val dupIds = remember(base) {
        base.groupBy { it.title.trim().lowercase() + "|" + it.startMillis }
            .filter { it.value.size > 1 }.values.flatten().map { it.id }.toSet()
    }
    val scoped = remember(base, scope, dupIds, now, staleBefore) {
        base.filter { e ->
            when (scope) {
                EScope.ALL -> true
                EScope.UPCOMING -> e.rrule.isNotBlank() || e.endMillis >= now
                EScope.PAST -> e.rrule.isBlank() && e.endMillis < now
                EScope.REPEATING -> e.rrule.isNotBlank()
                EScope.ALLDAY -> e.allDay
                EScope.DUPLICATES -> e.id in dupIds
                EScope.STALE -> e.rrule.isBlank() && e.startMillis < staleBefore
            }
        }
    }
    val shown = remember(scoped, sort) {
        when (sort) {
            ESort.NEWEST -> scoped.sortedByDescending { it.startMillis }
            ESort.OLDEST -> scoped.sortedBy { it.startMillis }
            ESort.TITLE -> scoped.sortedBy { it.title.lowercase() }
            ESort.DURATION -> scoped.sortedByDescending { it.endMillis - it.startMillis }
        }
    }
    val df = DateTimeFormatter.ofPattern("EEE d MMM yyyy · h:mm a")

    fun fmtDur(e: EventEntity): String {
        if (e.allDay) return "all day"
        val m = ((e.endMillis - e.startMillis) / 60000L).toInt().coerceAtLeast(0)
        val h = m / 60; val mm = m % 60
        return when { h > 0 && mm > 0 -> "${h}h ${mm}m"; h > 0 -> "${h}h"; else -> "${mm}m" }
    }

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
            Spacer(Modifier.height(6.dp))
            // Scope / cleanup finders.
            OptionChips(EScope.entries, scope, { scope = it }, spacing = 6) { sc ->
                sc.label + (if (sc == EScope.DUPLICATES && dupIds.isNotEmpty()) " ${dupIds.size}" else "")
            }
            Spacer(Modifier.height(6.dp))
            AppTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search title, place, notes") })
            // Sort row.
            OptionChips(ESort.entries, sort, { sort = it }, modifier = Modifier.padding(top = 4.dp), spacing = 6) { it.label }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selectMode) {
                    Text("${selected.size} selected", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { selected = if (selected.size == shown.size) emptySet() else shown.map { it.id }.toSet() }) {
                        Text(if (selected.size == shown.size && shown.isNotEmpty()) "None" else "All")
                    }
                    TextButton(onClick = { if (selected.isNotEmpty()) moveTarget = true }, enabled = selected.isNotEmpty()) { Text("Move") }
                    TextButton(onClick = { if (selected.isNotEmpty()) confirmBulk = true }, enabled = selected.isNotEmpty()) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { selectMode = false; selected = emptySet() }) { Text("Done") }
                } else {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { selectMode = true }) { Text("Select") }
                }
            }

            if (shown.isEmpty()) {
                Text("No events here.", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.title.ifBlank { "(untitled)" }, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (e.id in dupIds) {
                                    Spacer(Modifier.width(6.dp))
                                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 6.dp, vertical = 1.dp)) {
                                        Text("dup", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                    }
                                }
                            }
                            Text(Instant.ofEpochMilli(e.startMillis).atZone(zone).format(df) +
                                " · " + fmtDur(e) +
                                (if (e.rrule.isNotBlank()) " · repeats" else "") +
                                (cal?.let { " · " + it.name } ?: ""),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!selectMode && e.rrule.isNotBlank()) IconButton(onClick = { seriesFor = e.id }) { Icon(Icons.Filled.DateRange, "Occurrences", tint = MaterialTheme.colorScheme.primary) }
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
    if (moveTarget) {
        AlertDialog(
            onDismissRequest = { moveTarget = false },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveTarget = false }) { Text("Cancel") } },
            title = { Text("Move ${selected.size} to…") },
            text = {
                Column {
                    calendars.sortedBy { it.orderIndex }.forEach { c ->
                        Row(Modifier.fillMaxWidth().clickable {
                            selected.forEach { vm.moveEventToCalendar(it, c.id) }
                            moveTarget = false; selectMode = false; selected = emptySet()
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(c.colorArgb)))
                            Spacer(Modifier.width(12.dp))
                            Text(c.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
        )
    }
    // R57 — per-instance recurring-series cleanup: expand the series and delete individual occurrences.
    seriesFor?.let { sid ->
        val series = allEvents.firstOrNull { it.id == sid }
        if (series == null) { seriesFor = null; return@let }
        val now = System.currentTimeMillis()
        val occs = remember(series, allEvents) {
            com.todocompanion.app.domain.calendar.CalendarEngine
                .expand(listOf(series), now, now + 365L * 24 * 3600 * 1000, zone)
                .sortedBy { it.startMillis }.take(60)
        }
        val odf = DateTimeFormatter.ofPattern("EEE d MMM · h:mm a")
        AlertDialog(
            onDismissRequest = { seriesFor = null },
            confirmButton = { TextButton(onClick = { seriesFor = null }) { Text("Done") } },
            title = { Text("Occurrences of “${series.title.ifBlank { "(untitled)" }}”") },
            text = {
                Column {
                    Text("Delete individual dates without ending the whole series.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    if (occs.isEmpty()) Text("No upcoming occurrences.", style = MaterialTheme.typography.bodyMedium)
                    else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(occs, key = { it.startMillis }) { o ->
                            val day = Instant.ofEpochMilli(o.startMillis).atZone(zone).toLocalDate().toEpochDay()
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(Instant.ofEpochMilli(o.startMillis).atZone(zone).format(odf), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                IconButton(onClick = { vm.deleteEvent(series.id, "this", day) }) { Icon(Icons.Filled.Delete, "Delete this date", tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            },
        )
    }
}
