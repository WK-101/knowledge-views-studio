package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.priorityColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * A Gantt-style timeline: every open task with a start and/or due date is a horizontal bar on a
 * day axis (start → due), sized by its span. A fixed left column holds the task titles; the bar
 * area and the date header share one horizontal scroll, so they move together. Tap a bar to open
 * the task. Fully offline — just a layout over the same tasks.
 */
@Composable
fun TimelineScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, modifier: Modifier = Modifier) {
    val tasks by vm.tasks.collectAsState()
    val lists by vm.lists.collectAsState()
    val zone = ZoneId.systemDefault()
    val today = remember { LocalDate.now(zone) }

    val listColor = remember(lists) { lists.associate { it.id to it.colorArgb } }
    fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    // Filters: which lists to show (empty = all) and whether to include completed tasks.
    var selectedLists by remember { mutableStateOf(setOf<String>()) }
    var showDone by remember { mutableStateOf(false) }

    val allDated = remember(tasks) {
        tasks.filter { !it.trashed && !it.abandoned && (it.startDate != null || it.dueDate != null) }
    }
    val dated = remember(allDated, selectedLists, showDone) {
        allDated.filter { (showDone || !it.completed) && (selectedLists.isEmpty() || it.listId in selectedLists) }
            .sortedBy { (it.startDate ?: it.dueDate) }
    }

    if (allDated.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.ViewTimeline, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(10.dp))
                Text("Nothing to schedule yet", style = MaterialTheme.typography.titleMedium)
                Text("Give a task a start or due date to see it on the timeline.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    // Day range: from the earliest task edge (or today) to the latest (or two weeks out), capped at 180 days.
    val starts = dated.map { dayOf(it.startDate ?: it.dueDate!!) }
    val ends = dated.map { dayOf(it.dueDate ?: it.startDate!!) }
    val minDate = (starts + today).minOf { it }
    var maxDate = (ends + today.plusDays(14)).maxOf { it }
    if (ChronoUnit.DAYS.between(minDate, maxDate) > 180) maxDate = minDate.plusDays(180)
    val totalDays = (ChronoUnit.DAYS.between(minDate, maxDate) + 1).toInt()

    val dayWidth = 40.dp
    val titleWidth = 132.dp
    val rowHeight = 44.dp
    val hScroll = rememberScrollState()

    Column(modifier.fillMaxSize()) {
        // Filter bar: pick specific lists (empty = all) and toggle completed tasks.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.FilterChip(selected = selectedLists.isEmpty(), onClick = { selectedLists = emptySet() }, label = { Text("All lists") })
            lists.filter { !it.archived }.forEach { l ->
                androidx.compose.material3.FilterChip(
                    selected = l.id in selectedLists,
                    onClick = { selectedLists = if (l.id in selectedLists) selectedLists - l.id else selectedLists + l.id },
                    label = { Text((l.emoji?.plus(" ") ?: "") + l.name) },
                )
            }
            androidx.compose.material3.FilterChip(selected = showDone, onClick = { showDone = !showDone }, label = { Text("Show done") })
        }
        androidx.compose.material3.HorizontalDivider()
        if (dated.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No tasks match this filter", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
        // Header: month band + day-of-month numbers, scrolling with the bars.
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(titleWidth))
            Row(Modifier.horizontalScroll(hScroll)) {
                (0 until totalDays).forEach { i ->
                    val d = minDate.plusDays(i.toLong())
                    val isToday = d == today
                    val weekend = d.dayOfWeek.value >= 6
                    Column(
                        Modifier.width(dayWidth).padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (d.dayOfMonth == 1 || i == 0) d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()) else "",
                            style = MaterialTheme.typography.labelSmall, maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(
                            Modifier.size(24.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                d.dayOfMonth.toString(), style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    isToday -> MaterialTheme.colorScheme.onPrimary
                                    weekend -> MaterialTheme.colorScheme.outline
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
        androidx.compose.material3.HorizontalDivider()

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            dated.forEach { task ->
                val s = dayOf(task.startDate ?: task.dueDate!!)
                val e = dayOf(task.dueDate ?: task.startDate!!)
                val lo = if (s.isBefore(e)) s else e
                val hi = if (e.isAfter(s)) e else s
                val startIdx = ChronoUnit.DAYS.between(minDate, lo).toInt().coerceIn(0, totalDays - 1)
                val endIdx = ChronoUnit.DAYS.between(minDate, hi).toInt().coerceIn(0, totalDays - 1)
                val spanDays = (endIdx - startIdx + 1).coerceAtLeast(1)
                val level = PriorityLevel.from(task.importance, task.urgency)
                val bar = task.flagColorArgb?.let { Color(it) }
                    ?: listColor[task.listId]?.let { Color(it) }
                    ?: priorityColor(level)

                Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        task.title,
                        Modifier.width(titleWidth).clickable { onOpenTask(task.id) }.padding(horizontal = 10.dp),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Box(Modifier.fillMaxWidth().horizontalScroll(hScroll)) {
                        Box(Modifier.width(dayWidth * totalDays).height(rowHeight)) {
                            // Today's column marker behind the bar.
                            if (today in minDate..maxDate) {
                                val ti = ChronoUnit.DAYS.between(minDate, today).toInt()
                                Box(Modifier.padding(start = dayWidth * ti).width(dayWidth).height(rowHeight)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)))
                            }
                            Box(
                                Modifier.padding(start = dayWidth * startIdx, top = 8.dp, bottom = 8.dp)
                                    .width(dayWidth * spanDays)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(bar.copy(alpha = if (task.completed) 0.4f else 0.9f))
                                    .clickable { onOpenTask(task.id) }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (spanDays >= 2) Text(
                                    task.title, style = MaterialTheme.typography.labelMedium,
                                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
            Spacer(Modifier.height(100.dp))
        }
        }
    }
}
