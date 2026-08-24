package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.priorityColor
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

private val MODES = listOf("list" to "List", "day" to "Day", "3day" to "3-Day", "week" to "Week", "month" to "Month", "year" to "Year")

private fun Modifier.swipeNav(onPrev: () -> Unit, onNext: () -> Unit): Modifier = pointerInput(onPrev, onNext) {
    var total = 0f
    detectHorizontalDragGestures(onDragEnd = { if (total > 80) onPrev() else if (total < -80) onNext(); total = 0f }) { _, dragAmount -> total += dragAmount }
}

@Composable
fun CalendarScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, onAddOnDate: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val s by vm.settings.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val zone = ZoneId.systemDefault()

    var mode by remember { mutableStateOf(s.calendarDefaultMode) }
    var anchor by remember { mutableStateOf(LocalDate.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }

    val firstDow = if (s.weekStart in 1..7) DayOfWeek.of(s.weekStart) else WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val dueByDate = remember(tasks) {
        tasks.filter { !it.trashed && !it.completed && !it.abandoned && it.dueDate != null }
            .groupBy { Instant.ofEpochMilli(it.dueDate!!).atZone(zone).toLocalDate() }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MODES.forEach { (k, label) ->
                val on = mode == k
                Text(label,
                    Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { mode = k }.padding(horizontal = 13.dp, vertical = 7.dp),
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge)
            }
        }

        val onToggle: (TaskEntity) -> Unit = { vm.toggleComplete(it) }
        when (mode) {
            "month" -> MonthView(anchor, selected, dueByDate, firstDow, onSelect = { selected = it }, onPrev = { anchor = anchor.minusMonths(1) }, onNext = { anchor = anchor.plusMonths(1) }, onToday = { anchor = LocalDate.now(); selected = LocalDate.now() }, onOpenTask = onOpenTask, onToggle = onToggle, onAdd = { onAddOnDate(selected) })
            "week" -> MultiDayView(startOfWeek(anchor, firstDow), 7, dueByDate, onPrev = { anchor = anchor.minusWeeks(1) }, onNext = { anchor = anchor.plusWeeks(1) }, onToday = { anchor = LocalDate.now() }, onOpenTask = onOpenTask, onToggle = onToggle)
            "3day" -> MultiDayView(anchor, 3, dueByDate, onPrev = { anchor = anchor.minusDays(3) }, onNext = { anchor = anchor.plusDays(3) }, onToday = { anchor = LocalDate.now() }, onOpenTask = onOpenTask, onToggle = onToggle)
            "day" -> DayView(anchor, dueByDate, zone, onPrev = { anchor = anchor.minusDays(1) }, onNext = { anchor = anchor.plusDays(1) }, onToday = { anchor = LocalDate.now() }, onOpenTask = onOpenTask, onToggle = onToggle, onAdd = { onAddOnDate(anchor) })
            "year" -> YearView(anchor, dueByDate, onPrev = { anchor = anchor.minusYears(1) }, onNext = { anchor = anchor.plusYears(1) }, onMonth = { m -> anchor = m.atDay(1); mode = "month" })
            else -> AgendaView(dueByDate, onOpenTask, onToggle)
        }
    }
}

private fun startOfWeek(d: LocalDate, firstDow: DayOfWeek): LocalDate {
    val diff = (d.dayOfWeek.value - firstDow.value + 7) % 7
    return d.minusDays(diff.toLong())
}

@Composable
private fun NavHeader(label: String, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
        Text(label, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
        TextButton(onClick = onToday) { Text("Today") }
    }
}

@Composable
private fun MonthView(anchor: LocalDate, selected: LocalDate, dueByDate: Map<LocalDate, List<TaskEntity>>, firstDow: DayOfWeek, onSelect: (LocalDate) -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit, onOpenTask: (String) -> Unit, onToggle: (TaskEntity) -> Unit, onAdd: () -> Unit) {
    val ym = YearMonth.from(anchor)
    NavHeader("${ym.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${ym.year}", onPrev, onNext, onToday)
    val labels = (0..6).map { firstDow.plus(it.toLong()) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        labels.forEach { d -> Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    val first = ym.atDay(1)
    val leading = (first.dayOfWeek.value - firstDow.value + 7) % 7
    val cells = buildList<LocalDate?> {
        repeat(leading) { add(null) }
        for (day in 1..ym.lengthOfMonth()) add(ym.atDay(day))
        while (size % 7 != 0) add(null)
    }
    val today = LocalDate.now()
    val primary = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).swipeNav(onPrev, onNext)) {
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    val isToday = date == today
                    val isSelected = date == selected
                    // TickTick grammar: today is a solid filled circle; a selected non-today day gets a ring.
                    val ringMod = when {
                        isToday -> Modifier.background(primary, CircleShape)
                        isSelected -> Modifier.border(1.5.dp, primary, CircleShape)
                        else -> Modifier
                    }
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).padding(3.dp)
                            .clip(CircleShape).clickable(enabled = date != null) { date?.let(onSelect) }
                            .then(ringMod),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (date != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = when {
                                    isToday -> MaterialTheme.colorScheme.onPrimary
                                    date.month != anchor.month -> MaterialTheme.colorScheme.outline
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Spacer(Modifier.size(2.dp))
                            if (dueByDate.containsKey(date)) Box(Modifier.size(5.dp).clip(CircleShape).background(if (isToday) MaterialTheme.colorScheme.onPrimary else primary))
                            else Spacer(Modifier.size(5.dp))
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.size(6.dp))
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (selected == today) "TODAY" else "${selected.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()} ${selected.dayOfMonth} ${selected.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()}",
            Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onAdd) { Text("＋ Add") }
    }
    val agenda = dueByDate[selected].orEmpty()
    if (agenda.isEmpty()) Text("Nothing due — enjoy the day", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 2.dp, bottom = 100.dp)) {
        items(agenda, key = { it.id }) { TaskLine(it, onOpenTask, onToggle) }
    }
}

@Composable
private fun MultiDayView(start: LocalDate, n: Int, dueByDate: Map<LocalDate, List<TaskEntity>>, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit, onOpenTask: (String) -> Unit, onToggle: (TaskEntity) -> Unit) {
    val days = (0 until n).map { start.plusDays(it.toLong()) }
    NavHeader("${start.dayOfMonth} ${start.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} – ${days.last().dayOfMonth} ${days.last().month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}", onPrev, onNext, onToday)
    LazyColumn(Modifier.fillMaxSize().swipeNav(onPrev, onNext), contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 100.dp)) {
        days.forEach { d ->
            item(key = d.toString()) { DayHeader(d) }
            val list = dueByDate[d].orEmpty()
            if (list.isEmpty()) item(key = "empty$d") { Text("Nothing due", Modifier.padding(start = 16.dp, top = 2.dp, bottom = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
            else items(list, key = { "$d${it.id}" }) { TaskLine(it, onOpenTask, onToggle) }
        }
    }
}

@Composable
private fun DayHeader(d: LocalDate) {
    val isToday = d == LocalDate.now()
    val accent = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), style = MaterialTheme.typography.labelLarge, color = accent)
        Spacer(Modifier.size(6.dp))
        Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.labelLarge, color = accent)
        if (isToday) { Spacer(Modifier.size(6.dp)); Box(Modifier.clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 7.dp, vertical = 1.dp)) { Text("Today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary) } }
    }
}

@Composable
private fun DayView(day: LocalDate, dueByDate: Map<LocalDate, List<TaskEntity>>, zone: ZoneId, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit, onOpenTask: (String) -> Unit, onToggle: (TaskEntity) -> Unit, onAdd: () -> Unit) {
    NavHeader("${day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}", onPrev, onNext, onToday)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) { TextButton(onClick = onAdd) { Text("＋ Add on this day") } }
    val list = dueByDate[day].orEmpty()
    // "All day" = tasks with no explicit time (kept at the 9:00 default / all-day flag).
    val allDay = list.filter { it.isAllDay || !hasTime(it.dueDate!!, zone) }
    val timed = list.filter { !it.isAllDay && hasTime(it.dueDate!!, zone) }
    val byHour = timed.groupBy { Instant.ofEpochMilli(it.dueDate!!).atZone(zone).hour }
    val nowHour = if (day == LocalDate.now()) java.time.LocalTime.now().hour else -1

    LazyColumn(Modifier.fillMaxSize().swipeNav(onPrev, onNext), contentPadding = PaddingValues(top = 2.dp, bottom = 100.dp)) {
        if (allDay.isNotEmpty()) {
            item(key = "allday") {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("ALL DAY", Modifier.padding(start = 54.dp, bottom = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            items(allDay, key = { "ad${it.id}" }) { TaskLine(it, onOpenTask, onToggle) }
            item(key = "adgap") { Spacer(Modifier.size(6.dp)) }
        }
        items((6..22).toList(), key = { it }) { h ->
            Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 8.dp)) {
                Text("%02d:00".format(h), Modifier.width(46.dp).padding(top = 1.dp), style = MaterialTheme.typography.labelSmall, color = if (h == nowHour) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, textAlign = TextAlign.End)
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    if (h == nowHour) Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.error))
                    byHour[h].orEmpty().forEach { t ->
                        val level = PriorityLevel.from(t.importance, t.urgency)
                        val c = if (level == PriorityLevel.NONE) MaterialTheme.colorScheme.primary else priorityColor(level)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
                                .background(c.copy(alpha = 0.12f)).clickable { onOpenTask(t.id) }.padding(start = 0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(4.dp).height(26.dp).background(c))
                            Spacer(Modifier.size(8.dp))
                            Text(t.title, Modifier.weight(1f).padding(vertical = 5.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(timeLabel(t.dueDate!!, zone), Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YearView(anchor: LocalDate, dueByDate: Map<LocalDate, List<TaskEntity>>, onPrev: () -> Unit, onNext: () -> Unit, onMonth: (YearMonth) -> Unit) {
    NavHeader(anchor.year.toString(), onPrev, onNext) {}
    val countByMonth = remember(dueByDate, anchor.year) {
        dueByDate.entries.filter { it.key.year == anchor.year }.groupBy { YearMonth.from(it.key) }.mapValues { e -> e.value.sumOf { it.value.size } }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp)) {
        (1..12).chunked(3).forEach { rowMonths ->
            item(key = "r${rowMonths.first()}") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowMonths.forEach { m ->
                        val ym = YearMonth.of(anchor.year, m)
                        val c = countByMonth[ym] ?: 0
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)).clickable { onMonth(ym) }.padding(10.dp)) {
                            Text(ym.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()), style = MaterialTheme.typography.labelLarge)
                            Text(if (c == 0) "—" else "$c due", style = MaterialTheme.typography.labelSmall, color = if (c == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
            }
        }
    }
}

@Composable
private fun AgendaView(dueByDate: Map<LocalDate, List<TaskEntity>>, onOpenTask: (String) -> Unit, onToggle: (TaskEntity) -> Unit) {
    val days = dueByDate.keys.sorted()
    if (days.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No scheduled tasks", color = MaterialTheme.colorScheme.onSurfaceVariant) }; return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 2.dp, bottom = 100.dp)) {
        days.forEach { d ->
            item(key = "h$d") { DayHeader(d) }
            items(dueByDate[d].orEmpty(), key = { it.id }) { TaskLine(it, onOpenTask, onToggle) }
        }
    }
}

/** TickTick-style calendar task pill: priority-tinted, with a completion checkbox and a time label. */
@Composable
private fun TaskLine(task: TaskEntity, onOpenTask: (String) -> Unit, onToggle: (TaskEntity) -> Unit) {
    val zone = ZoneId.systemDefault()
    val level = PriorityLevel.from(task.importance, task.urgency)
    val accent = if (level == PriorityLevel.NONE) MaterialTheme.colorScheme.primary else priorityColor(level)
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (level == PriorityLevel.NONE) MaterialTheme.colorScheme.surface else accent.copy(alpha = 0.09f),
        tonalElevation = if (level == PriorityLevel.NONE) 1.dp else 0.dp,
    ) {
        Row(Modifier.height(IntrinsicSize.Min).clickable { onOpenTask(task.id) }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
            com.todocompanion.app.ui.components.PriorityCheckbox(task.completed, level) { onToggle(task) }
            Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
                Text(
                    task.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (task.completed) androidx.compose.ui.text.style.TextDecoration.LineThrough else androidx.compose.ui.text.style.TextDecoration.None,
                    color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                if (task.note.isNotBlank()) Text(task.note.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            task.dueDate?.let { if (!task.isAllDay && hasTime(it, zone)) { Text(timeLabel(it, zone), Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelMedium, color = accent) } }
        }
    }
}

private fun hasTime(millis: Long, zone: ZoneId): Boolean {
    val dt = Instant.ofEpochMilli(millis).atZone(zone)
    return !(dt.hour == 9 && dt.minute == 0)
}

private fun timeLabel(millis: Long, zone: ZoneId): String {
    val dt = Instant.ofEpochMilli(millis).atZone(zone)
    return "%02d:%02d".format(dt.hour, dt.minute)
}
