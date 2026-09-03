package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.runtime.mutableIntStateOf
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
fun TimelineScreen(
    vm: AppViewModel, onOpenTask: (String) -> Unit,
    selectedLists: Set<String> = emptySet(), showDone: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val tasks by vm.tasks.collectAsState()
    val lists by vm.lists.collectAsState()
    val zone = ZoneId.systemDefault()
    val today = remember { LocalDate.now(zone) }

    val listColor = remember(lists) { lists.associate { it.id to it.colorArgb } }
    fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

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
                Text("Nothing to schedule yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
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
                        // R31 #6 — explicit colour so the frozen label column stays legible on the raw
                        // background (esp. AMOLED), not the inherited default black.
                        color = MaterialTheme.colorScheme.onSurface,
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
                            // Long-press then drag a bar horizontally to shift its dates by whole days.
                            var dragDays by remember(task.id, startIdx) { mutableIntStateOf(0) }
                            var dragging by remember(task.id) { mutableStateOf(false) }
                            val dayPx = with(androidx.compose.ui.platform.LocalDensity.current) { dayWidth.toPx() }
                            Box(
                                Modifier.padding(start = dayWidth * (startIdx + dragDays).coerceAtLeast(0), top = 8.dp, bottom = 8.dp)
                                    .width(dayWidth * spanDays)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(bar.copy(alpha = if (dragging) 1f else if (task.completed) 0.4f else 0.9f))
                                    .pointerInput(task.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { dragging = true },
                                            onDrag = { _, off -> dragDays = ((dragDays * dayPx + off.x) / dayPx).toInt() },
                                            onDragEnd = { dragging = false; vm.shiftTaskDays(task.id, dragDays); dragDays = 0 },
                                            onDragCancel = { dragging = false; dragDays = 0 },
                                        )
                                    }
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
