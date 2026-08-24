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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
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

private fun Modifier.swipeNav(onPrev: () -> Unit, onNext: () -> Unit): Modifier = pointerInput(onPrev, onNext) {
    var total = 0f
    detectHorizontalDragGestures(onDragEnd = { if (total > 80) onPrev() else if (total < -80) onNext(); total = 0f }) { _, dragAmount -> total += dragAmount }
}

@Composable
fun CalendarScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, mode: String, onModeChange: (String) -> Unit, onAddOnDate: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val s by vm.settings.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val zone = ZoneId.systemDefault()

    var anchor by remember { mutableStateOf(LocalDate.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }

    val firstDow = if (s.weekStart in 1..7) DayOfWeek.of(s.weekStart) else WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val dueByDate = remember(tasks) {
        tasks.filter { !it.trashed && !it.completed && !it.abandoned && it.dueDate != null }
            .groupBy { Instant.ofEpochMilli(it.dueDate!!).atZone(zone).toLocalDate() }
    }

    Column(modifier.fillMaxSize()) {
        val onToggle: (TaskEntity) -> Unit = { vm.toggleComplete(it) }
        val onTrash: (TaskEntity) -> Unit = { vm.trash(it) }
        when (mode) {
            "month" -> MonthView(anchor, selected, dueByDate, firstDow, onSelect = { selected = it }, onPrev = { anchor = anchor.minusMonths(1) }, onNext = { anchor = anchor.plusMonths(1) }, onToday = { anchor = LocalDate.now(); selected = LocalDate.now() }, onOpenTask = onOpenTask, onToggle = onToggle, onTrash = onTrash, onAdd = { onAddOnDate(selected) })
            "week" -> {
                val start = startOfWeek(anchor, firstDow)
                TimelineView((0..6).map { start.plusDays(it.toLong()) }, dueByDate, zone, rangeTitle(start, start.plusDays(6)), onPrev = { anchor = anchor.minusWeeks(1) }, onNext = { anchor = anchor.plusWeeks(1) }, onToday = { anchor = LocalDate.now() }, onOpenTask = onOpenTask, onAddOnDate = onAddOnDate)
            }
            "3day" -> TimelineView((0..2).map { anchor.plusDays(it.toLong()) }, dueByDate, zone, rangeTitle(anchor, anchor.plusDays(2)), onPrev = { anchor = anchor.minusDays(3) }, onNext = { anchor = anchor.plusDays(3) }, onToday = { anchor = LocalDate.now() }, onOpenTask = onOpenTask, onAddOnDate = onAddOnDate)
            "day" -> TimelineView(listOf(anchor), dueByDate, zone, "${anchor.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${anchor.dayOfMonth} ${anchor.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}", onPrev = { anchor = anchor.minusDays(1) }, onNext = { anchor = anchor.plusDays(1) }, onToday = { anchor = LocalDate.now() }, onOpenTask = onOpenTask, onAddOnDate = onAddOnDate)
            "year" -> YearView(anchor, dueByDate, onPrev = { anchor = anchor.minusYears(1) }, onNext = { anchor = anchor.plusYears(1) }, onMonth = { m -> anchor = m.atDay(1); onModeChange("month") })
            else -> AgendaView(dueByDate, onOpenTask, onToggle, onTrash)
        }
    }
}

private fun rangeTitle(a: LocalDate, b: LocalDate): String {
    val am = a.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    val bm = b.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    return if (a.month == b.month) "${a.dayOfMonth} – ${b.dayOfMonth} $am" else "${a.dayOfMonth} $am – ${b.dayOfMonth} $bm"
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
private fun MonthView(anchor: LocalDate, selected: LocalDate, dueByDate: Map<LocalDate, List<TaskEntity>>, firstDow: DayOfWeek, onSelect: (LocalDate) -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit, onOpenTask: (String) -> Unit, onToggle: (TaskEntity) -> Unit, onTrash: (TaskEntity) -> Unit, onAdd: () -> Unit) {
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
        items(agenda, key = { it.id }) { TaskLine(it, onOpenTask, onToggle, onTrash) }
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

// ---------- TickTick-style timeline grid (Day / 3-Day / Week) ----------

private const val GUTTER_DP = 46
private const val HOUR_DP = 56

/** One positioned event: minutes-from-midnight span plus its lane within an overlap cluster. */
private class Placed(val task: TaskEntity, val startMin: Int, val endMin: Int, val lane: Int, val lanes: Int)

private fun layoutEvents(tasks: List<TaskEntity>, zone: ZoneId): List<Placed> {
    val evs = tasks.map { t ->
        val dt = Instant.ofEpochMilli(t.dueDate!!).atZone(zone)
        val start = dt.hour * 60 + dt.minute
        val dur = (t.durationMin ?: 30).coerceAtLeast(20)
        Triple(t, start, minOf(start + dur, 24 * 60))
    }.sortedBy { it.second }
    val out = ArrayList<Placed>(evs.size)
    var i = 0
    while (i < evs.size) {
        val cluster = arrayListOf(evs[i])
        var clusterEnd = evs[i].third
        var j = i + 1
        while (j < evs.size && evs[j].second < clusterEnd) { cluster.add(evs[j]); clusterEnd = maxOf(clusterEnd, evs[j].third); j++ }
        val laneEnds = ArrayList<Int>()
        val laneOf = IntArray(cluster.size)
        cluster.forEachIndexed { idx, e ->
            var lane = laneEnds.indexOfFirst { it <= e.second }
            if (lane == -1) { lane = laneEnds.size; laneEnds.add(e.third) } else laneEnds[lane] = e.third
            laneOf[idx] = lane
        }
        val lanes = laneEnds.size
        cluster.forEachIndexed { idx, e -> out.add(Placed(e.first, e.second, e.third, laneOf[idx], lanes)) }
        i = j
    }
    return out
}

@Composable
private fun TimelineView(
    days: List<LocalDate>, dueByDate: Map<LocalDate, List<TaskEntity>>, zone: ZoneId, title: String,
    onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit, onOpenTask: (String) -> Unit, onAddOnDate: (LocalDate) -> Unit,
) {
    NavHeader(title, onPrev, onNext, onToday)

    val allDayByDay = days.associateWith { d -> dueByDate[d].orEmpty().filter { it.isAllDay || !hasTime(it.dueDate!!, zone) } }
    val hasAllDay = allDayByDay.values.any { it.isNotEmpty() }
    val today = LocalDate.now()

    val scroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.LaunchedEffect(days.firstOrNull()) {
        scroll.scrollTo(with(density) { (7 * HOUR_DP).dp.toPx() }.toInt())
    }

    Column(Modifier.fillMaxSize().swipeNav(onPrev, onNext)) {
        // Column headers (multi-day only)
        if (days.size > 1) {
            Row(Modifier.fillMaxWidth().padding(start = GUTTER_DP.dp, top = 2.dp, bottom = 2.dp)) {
                days.forEach { d ->
                    val isToday = d == today
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.size(26.dp).clip(CircleShape).background(if (isToday) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent), contentAlignment = Alignment.Center) {
                            Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.labelMedium, color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
        // All-day strip
        if (hasAllDay) {
            Row(Modifier.fillMaxWidth().padding(start = GUTTER_DP.dp).heightIn(max = 76.dp)) {
                days.forEach { d ->
                    Column(Modifier.weight(1f).padding(horizontal = 2.dp)) {
                        allDayByDay[d].orEmpty().take(3).forEach { t -> AllDayChip(t, onOpenTask) }
                    }
                }
            }
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        }
        // Scrollable hour grid
        Row(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) {
            // Hour gutter
            Box(Modifier.width(GUTTER_DP.dp).height((HOUR_DP * 24).dp)) {
                (1..23).forEach { h ->
                    Text("%02d:00".format(h), Modifier.offset(y = (HOUR_DP * h - 7).dp).fillMaxWidth().padding(end = 6.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.End)
                }
            }
            days.forEach { d ->
                val timed = dueByDate[d].orEmpty().filter { !it.isAllDay && hasTime(it.dueDate!!, zone) }
                DayColumn(d, timed, zone, onOpenTask, onAddOnDate, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DayColumn(day: LocalDate, timed: List<TaskEntity>, zone: ZoneId, onOpenTask: (String) -> Unit, onAddOnDate: (LocalDate) -> Unit, modifier: Modifier) {
    val placed = remember(timed, zone) { layoutEvents(timed, zone) }
    val isToday = day == LocalDate.now()
    val nowMin = if (isToday) java.time.LocalTime.now().let { it.hour * 60 + it.minute } else -1
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.height((HOUR_DP * 24).dp).clickable { onAddOnDate(day) },
    ) {
        val colW = maxWidth
        // Hour gridlines
        (0..24).forEach { h ->
            Box(Modifier.fillMaxWidth().height(1.dp).offset(y = (HOUR_DP * h).dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)))
        }
        // Right divider between day columns
        Box(Modifier.fillMaxHeight().width(1.dp).offset(x = colW - 1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)))
        // Events
        placed.forEach { p ->
            val level = PriorityLevel.from(p.task.importance, p.task.urgency)
            val c = if (level == PriorityLevel.NONE) MaterialTheme.colorScheme.primary else priorityColor(level)
            val laneW = (colW - 2.dp) / p.lanes
            val top = (HOUR_DP * p.startMin / 60f).dp
            val h = ((HOUR_DP * (p.endMin - p.startMin) / 60f).dp).coerceAtLeast(24.dp)
            Row(
                Modifier.offset(x = laneW * p.lane + 1.dp, y = top).width(laneW - 1.dp).height(h - 2.dp)
                    .clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.16f)).clickable { onOpenTask(p.task.id) },
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(c))
                Column(Modifier.padding(horizontal = 5.dp, vertical = 3.dp)) {
                    Text(p.task.title, style = MaterialTheme.typography.labelSmall, maxLines = if (h > 46.dp) 2 else 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                    if (h >= 50.dp) Text("${minLabel(p.startMin)} – ${minLabel(p.endMin)}", style = MaterialTheme.typography.labelSmall, color = c)
                }
            }
        }
        // Current-time line
        if (nowMin >= 0) {
            val y = (HOUR_DP * nowMin / 60f).dp
            Box(Modifier.fillMaxWidth().offset(y = y).height(2.dp).background(MaterialTheme.colorScheme.error))
            Box(Modifier.size(7.dp).offset(y = y - 3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
        }
    }
}

@Composable
private fun AllDayChip(task: TaskEntity, onOpenTask: (String) -> Unit) {
    val level = PriorityLevel.from(task.importance, task.urgency)
    val c = if (level == PriorityLevel.NONE) MaterialTheme.colorScheme.primary else priorityColor(level)
    Text(
        task.title,
        Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(5.dp)).background(c.copy(alpha = 0.16f)).clickable { onOpenTask(task.id) }.padding(horizontal = 5.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
    )
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
private fun AgendaView(dueByDate: Map<LocalDate, List<TaskEntity>>, onOpenTask: (String) -> Unit, onToggle: (TaskEntity) -> Unit, onTrash: (TaskEntity) -> Unit) {
    val days = dueByDate.keys.sorted()
    if (days.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No scheduled tasks", color = MaterialTheme.colorScheme.onSurfaceVariant) }; return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 2.dp, bottom = 100.dp)) {
        days.forEach { d ->
            item(key = "h$d") { DayHeader(d) }
            items(dueByDate[d].orEmpty(), key = { it.id }) { TaskLine(it, onOpenTask, onToggle, onTrash) }
        }
    }
}

/** TickTick-style calendar task pill: priority-tinted, swipeable (complete / trash), tap to edit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskLine(task: TaskEntity, onOpenTask: (String) -> Unit, onToggle: (TaskEntity) -> Unit, onTrash: (TaskEntity) -> Unit) {
    val zone = ZoneId.systemDefault()
    val level = PriorityLevel.from(task.importance, task.urgency)
    val accent = if (level == PriorityLevel.NONE) MaterialTheme.colorScheme.primary else priorityColor(level)
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> { onToggle(task); false }
            SwipeToDismissBoxValue.EndToStart -> { onTrash(task); false }
            else -> false
        }
    })
    SwipeToDismissBox(
        state = state,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
        backgroundContent = {
            val dir = state.dismissDirection
            val (c, icon, align) = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> Triple(Color(0xFF12A594), Icons.Filled.Check, Alignment.CenterStart)
                SwipeToDismissBoxValue.EndToStart -> Triple(Color(0xFFE5484D), Icons.Filled.Delete, Alignment.CenterEnd)
                else -> Triple(Color.Transparent, Icons.Filled.Check, Alignment.CenterStart)
            }
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(c).padding(horizontal = 20.dp), contentAlignment = align) {
                if (dir != SwipeToDismissBoxValue.Settled) Icon(icon, null, tint = Color.White)
            }
        },
    ) {
        Surface(
            Modifier.fillMaxWidth(),
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
}

private fun hasTime(millis: Long, zone: ZoneId): Boolean {
    val dt = Instant.ofEpochMilli(millis).atZone(zone)
    return !(dt.hour == 9 && dt.minute == 0)
}

private fun timeLabel(millis: Long, zone: ZoneId): String {
    val dt = Instant.ofEpochMilli(millis).atZone(zone)
    return "%02d:%02d".format(dt.hour, dt.minute)
}

private fun minLabel(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
