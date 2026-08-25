package com.todocompanion.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarViewMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Today
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.compositeOver
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.domain.SwipeAction
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

/** All calendar view modes, in picker order. */
val CAL_MODES = listOf("list" to "List", "day" to "Day", "3day" to "3-Day", "week" to "Week", "weekly" to "Weekly", "month" to "Month", "year" to "Year")

@Composable
fun CalendarScreen(
    vm: AppViewModel, onOpenTask: (String) -> Unit, mode: String, onModeChange: (String) -> Unit,
    onAddOnDate: (LocalDate) -> Unit, onAddAt: (LocalDate, Int) -> Unit = { d, _ -> onAddOnDate(d) },
    onOpenDrawer: () -> Unit = {}, onOpenFilter: () -> Unit = {}, filterActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val s by vm.settings.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val zone = ZoneId.systemDefault()

    var anchor by remember { mutableStateOf(LocalDate.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }

    val firstDow = if (s.weekStart in 1..7) DayOfWeek.of(s.weekStart) else WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val listFilter = s.calendarListFilter
    val dueByDate = remember(tasks, listFilter) {
        tasks.filter { !it.trashed && !it.completed && !it.abandoned && it.dueDate != null && (listFilter.isEmpty() || it.listId in listFilter) }
            .groupBy { Instant.ofEpochMilli(it.dueDate!!).atZone(zone).toLocalDate() }
    }

    val onResize: (String, Int) -> Unit = { id, dur -> vm.setDuration(id, dur) }
    val onMoveTaskTo: (LocalDate, String, Int) -> Unit = { d, id, min -> vm.rescheduleToMinute(id, d, min) }
    val onJump: (YearMonth) -> Unit = { ym -> anchor = ym.atDay(1) }
    // One swipe config for every calendar task row, straight from the global swipe settings.
    val swipe = CalSwipe(s.swipeRight, s.swipeRightFar, s.swipeLeft, s.swipeLeftFar) { a, t ->
        when (a) {
            SwipeAction.COMPLETE -> vm.toggleComplete(t)
            SwipeAction.TRASH -> vm.trash(t)
            SwipeAction.STAR -> vm.toggleStar(t)
            SwipeAction.WONT_DO -> vm.setAbandoned(t, !t.abandoned)
            SwipeAction.CYCLE_PRIORITY -> vm.cyclePriority(t)
            SwipeAction.SCHEDULE_TOMORROW -> {
                val ms = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli()
                vm.save(t.copy(dueDate = ms))
            }
            SwipeAction.EDIT -> onOpenTask(t.id)
            SwipeAction.NONE -> {}
        }
    }

    // Period label + step direction depend on the active mode.
    fun step(dir: Int) {
        anchor = when (mode) {
            "month" -> anchor.plusMonths(dir.toLong())
            "week", "weekly" -> anchor.plusWeeks(dir.toLong())
            "3day" -> anchor.plusDays(3L * dir)
            "day" -> anchor.plusDays(dir.toLong())
            "year" -> anchor.plusYears(dir.toLong())
            else -> anchor
        }
    }
    val label = when (mode) {
        "month" -> "${anchor.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${anchor.year}"
        "week", "weekly" -> { val st = startOfWeek(anchor, firstDow); rangeTitle(st, st.plusDays(6)) }
        "3day" -> rangeTitle(anchor, anchor.plusDays(2))
        "day" -> "${anchor.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${anchor.dayOfMonth} ${anchor.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"
        "year" -> anchor.year.toString()
        else -> "Agenda"
    }
    val showNav = mode != "list"

    Column(modifier.fillMaxSize()) {
        // Single combined top bar: menu · prev · period ▾ · next · today · view-type · filter.
        CalHeader(
            label = label, current = YearMonth.from(anchor), showNav = showNav,
            onPrev = { step(-1) }, onNext = { step(1) }, onToday = { anchor = LocalDate.now(); selected = LocalDate.now() },
            onPick = onJump, onOpenDrawer = onOpenDrawer, mode = mode, onModeChange = onModeChange,
            onOpenFilter = onOpenFilter, filterActive = filterActive,
        )
        when (mode) {
            "month" -> MonthView(anchor, selected, dueByDate, firstDow, onSelect = { selected = it }, onPrev = { step(-1) }, onNext = { step(1) }, onOpenTask = onOpenTask, swipe = swipe, onAdd = { onAddOnDate(selected) })
            "week" -> {
                val start = startOfWeek(anchor, firstDow)
                TimelineView((0..6).map { start.plusDays(it.toLong()) }, dueByDate, zone, onPrev = { step(-1) }, onNext = { step(1) }, onOpenTask = onOpenTask, onAddOnDate = onAddOnDate, onAddAt = onAddAt, onResize = onResize, onMoveAt = onMoveTaskTo)
            }
            "weekly" -> WeeklyView(startOfWeek(anchor, firstDow), dueByDate, onPrev = { step(-1) }, onNext = { step(1) }, onOpenTask = onOpenTask, onAddOnDate = onAddOnDate)
            "3day" -> TimelineView((0..2).map { anchor.plusDays(it.toLong()) }, dueByDate, zone, onPrev = { step(-1) }, onNext = { step(1) }, onOpenTask = onOpenTask, onAddOnDate = onAddOnDate, onAddAt = onAddAt, onResize = onResize, onMoveAt = onMoveTaskTo)
            "day" -> TimelineView(listOf(anchor), dueByDate, zone, onPrev = { step(-1) }, onNext = { step(1) }, onOpenTask = onOpenTask, onAddOnDate = onAddOnDate, onAddAt = onAddAt, onResize = onResize, onMoveAt = onMoveTaskTo)
            "year" -> YearView(anchor, dueByDate, onPrev = { step(-1) }, onNext = { step(1) }, onMonth = { m -> anchor = m.atDay(1); onModeChange("month") }, onDay = { d -> anchor = d; onModeChange("day") })
            else -> AgendaView(dueByDate, onOpenTask, swipe)
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

/** The single combined calendar top bar — menu · prev · clickable period label (opens a month/year
 *  picker) · next · Today · view-type menu · list filter. Replaces both the old "Calendar" app-bar
 *  banner and the separate in-screen nav row, so everything lives on one space-saving line. */
@Composable
private fun CalHeader(
    label: String, current: YearMonth, showNav: Boolean,
    onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit, onPick: (YearMonth) -> Unit,
    onOpenDrawer: () -> Unit, mode: String, onModeChange: (String) -> Unit,
    onOpenFilter: () -> Unit, filterActive: Boolean,
) {
    var showPicker by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    Row(
        // No status-bar inset here: the Scaffold already applies the top inset to the calendar
        // content when its app-bar is hidden. Adding it again left a blank band above the header.
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, "Menu") }
        if (showNav) IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(enabled = showNav) { showPicker = true }.padding(vertical = 6.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showNav) Icon(Icons.Filled.ArrowDropDown, "Pick period", modifier = Modifier.size(20.dp))
        }
        if (showNav) IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
        IconButton(onClick = onToday) { Icon(Icons.Filled.Today, "Today") }
        Box {
            IconButton(onClick = { typeMenu = true }) { Icon(Icons.Filled.CalendarViewMonth, "View type") }
            androidx.compose.material3.DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                CAL_MODES.forEach { (k, l) ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(l) },
                        leadingIcon = { if (mode == k) Icon(Icons.Filled.Check, null) else Spacer(Modifier.width(24.dp)) },
                        onClick = { onModeChange(k); typeMenu = false },
                    )
                }
            }
        }
        IconButton(onClick = onOpenFilter) { Icon(Icons.Filled.FilterList, "Filter lists", tint = if (filterActive) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current) }
    }
    if (showPicker) MonthYearPicker(current, onDismiss = { showPicker = false }) { ym -> onPick(ym); showPicker = false }
}

/** A quick month/year chooser: a year stepper over a 3×4 grid of month chips. */
@Composable
private fun MonthYearPicker(current: YearMonth, onDismiss: () -> Unit, onPick: (YearMonth) -> Unit) {
    var year by remember { mutableStateOf(current.year) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { year-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous year") }
                Text(year.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                IconButton(onClick = { year++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next year") }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..3).forEach { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..3).forEach { c ->
                            val m = r * 3 + c
                            val ym = YearMonth.of(year, m)
                            val sel = ym == current
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))
                                    .clickable { onPick(ym) }.padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(java.time.Month.of(m).getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                                    color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (sel) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun MonthView(anchor: LocalDate, selected: LocalDate, dueByDate: Map<LocalDate, List<TaskEntity>>, firstDow: DayOfWeek, onSelect: (LocalDate) -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onOpenTask: (String) -> Unit, swipe: CalSwipe, onAdd: () -> Unit) {
    val ym = YearMonth.from(anchor)
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
        items(agenda, key = { it.id }) { TaskLine(it, onOpenTask, swipe) }
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
    days: List<LocalDate>, dueByDate: Map<LocalDate, List<TaskEntity>>, zone: ZoneId,
    onPrev: () -> Unit, onNext: () -> Unit, onOpenTask: (String) -> Unit, onAddOnDate: (LocalDate) -> Unit,
    onAddAt: (LocalDate, Int) -> Unit, onResize: (String, Int) -> Unit, onMoveAt: (LocalDate, String, Int) -> Unit,
) {
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
                DayColumn(d, timed, zone, onOpenTask, onAddAt, onResize, onMoveAt = { id, min -> onMoveAt(d, id, min) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DayColumn(day: LocalDate, timed: List<TaskEntity>, zone: ZoneId, onOpenTask: (String) -> Unit, onAddAt: (LocalDate, Int) -> Unit, onResize: (String, Int) -> Unit, onMoveAt: (String, Int) -> Unit, modifier: Modifier) {
    val placed = remember(timed, zone) { layoutEvents(timed, zone) }
    val dens = LocalDensity.current
    val isToday = day == LocalDate.now()
    val nowMin = if (isToday) java.time.LocalTime.now().let { it.hour * 60 + it.minute } else -1
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.height((HOUR_DP * 24).dp).pointerInput(day) {
            // Tap an empty slot to time-block a task at that half-hour.
            detectTapGestures { offset ->
                val minute = ((offset.y / size.height.toFloat()) * 1440f).toInt().coerceIn(0, 1439)
                onAddAt(day, (minute / 30) * 30)
            }
        },
    ) {
        val colW = maxWidth
        // Hour gridlines
        (0..24).forEach { h ->
            Box(Modifier.fillMaxWidth().height(1.dp).offset(y = (HOUR_DP * h).dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)))
        }
        // Right divider between day columns
        Box(Modifier.fillMaxHeight().width(1.dp).offset(x = colW - 1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)))
        // Events
        val hourPx = with(dens) { HOUR_DP.dp.toPx() }
        placed.forEach { p ->
            val level = PriorityLevel.from(p.task.importance, p.task.urgency)
            val c = if (level == PriorityLevel.NONE) MaterialTheme.colorScheme.primary else priorityColor(level)
            val laneW = (colW - 2.dp) / p.lanes
            // Live start + duration while dragging (snapped to 15 min); reset when the saved span changes.
            var liveStart by remember(p.task.id, p.startMin) { mutableStateOf(p.startMin) }
            var liveDur by remember(p.task.id, p.endMin - p.startMin) { mutableStateOf(p.endMin - p.startMin) }
            var dragging by remember(p.task.id) { mutableStateOf(false) }
            fun snap(v: Int) = ((v / 15f).roundToInt() * 15)
            val top = (HOUR_DP * liveStart / 60f).dp
            val h = ((HOUR_DP * liveDur / 60f).dp).coerceAtLeast(24.dp)
            Row(
                Modifier.offset(x = laneW * p.lane + 1.dp, y = top).width(laneW - 1.dp).height(h - 2.dp)
                    .clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = if (dragging) 0.30f else 0.16f))
                    // Long-press then drag to move the block to another time; tap opens the task.
                    .pointerInput(p.task.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = true },
                            onDrag = { _, off -> liveStart = snap((liveStart + (off.y / hourPx * 60f).toInt())).coerceIn(0, 1440 - liveDur) },
                            onDragEnd = { dragging = false; onMoveAt(p.task.id, liveStart) },
                            onDragCancel = { dragging = false },
                        )
                    }
                    .clickable { onOpenTask(p.task.id) },
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(c))
                Column(Modifier.padding(horizontal = 5.dp, vertical = 3.dp)) {
                    Text(p.task.title, style = MaterialTheme.typography.labelSmall, maxLines = if (h > 46.dp) 2 else 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                    if (h >= 50.dp || dragging) Text("${minLabel(liveStart)} – ${minLabel(liveStart + liveDur)}", style = MaterialTheme.typography.labelSmall, color = c)
                }
            }
            // Bottom resize grip: a comfortable full-width target; drag to change duration (snapped 15 min).
            Box(
                Modifier.offset(x = laneW * p.lane + 1.dp, y = top + h - 16.dp).width(laneW - 1.dp).height(18.dp)
                    .pointerInput(p.task.id) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = true },
                            onVerticalDrag = { _, dy -> liveDur = snap((liveDur + (dy / hourPx * 60f)).toInt()).coerceIn(15, 24 * 60 - liveStart) },
                            onDragEnd = { dragging = false; onResize(p.task.id, liveDur) },
                            onDragCancel = { dragging = false },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.width(26.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c)) }
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
private fun YearView(anchor: LocalDate, dueByDate: Map<LocalDate, List<TaskEntity>>, onPrev: () -> Unit, onNext: () -> Unit, onMonth: (YearMonth) -> Unit, onDay: (LocalDate) -> Unit) {
    val daysWithTasks = remember(dueByDate, anchor.year) {
        dueByDate.keys.filter { it.year == anchor.year }.toSet()
    }
    // Four weighted rows of three months fill the whole screen height instead of cramming at the top.
    Column(Modifier.fillMaxSize().swipeNav(onPrev, onNext).padding(horizontal = 8.dp, vertical = 6.dp)) {
        (1..12).chunked(3).forEach { rowMonths ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowMonths.forEach { m ->
                    MiniMonth(YearMonth.of(anchor.year, m), daysWithTasks, onMonth = onMonth, onDay = onDay,
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp))
                }
            }
        }
    }
}

/** A compact month for the Year view. Task-days get a tinted circle; tap a day to open it,
 *  or the month name to open the month. */
@Composable
private fun MiniMonth(ym: YearMonth, daysWithTasks: Set<LocalDate>, onMonth: (YearMonth) -> Unit, onDay: (LocalDate) -> Unit, modifier: Modifier) {
    val today = LocalDate.now()
    val first = ym.atDay(1)
    val lead = (first.dayOfWeek.value - 1 + 7) % 7   // week starts Monday for the mini grid
    val cells = buildList<LocalDate?> {
        repeat(lead) { add(null) }
        for (d in 1..ym.lengthOfMonth()) add(ym.atDay(d))
        while (size % 7 != 0) add(null)
    }
    Column(modifier) {
        Text(ym.month.getDisplayName(TextStyle.FULL, Locale.getDefault()),
            Modifier.clip(RoundedCornerShape(6.dp)).clickable { onMonth(ym) }.padding(horizontal = 2.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (YearMonth.from(today) == ym) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.size(3.dp))
        // Weeks share the remaining cell height so the month expands to fill its slot.
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                week.forEach { d ->
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        if (d != null) {
                            val isToday = d == today
                            val hasTasks = d in daysWithTasks
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(1f).padding(1.dp).clip(CircleShape)
                                    .background(when {
                                        isToday -> MaterialTheme.colorScheme.primary
                                        hasTasks -> MaterialTheme.colorScheme.primary.copy(alpha = .20f)
                                        else -> androidx.compose.ui.graphics.Color.Transparent
                                    })
                                    .clickable { onDay(d) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (hasTasks || isToday) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** TickTick-style Weekly view: one card per day of the week, each listing that day's tasks as
 *  colour-coded pills. Swipe left/right to move week-by-week. */
@Composable
private fun WeeklyView(weekStart: LocalDate, dueByDate: Map<LocalDate, List<TaskEntity>>, onPrev: () -> Unit, onNext: () -> Unit, onOpenTask: (String) -> Unit, onAddOnDate: (LocalDate) -> Unit) {
    val days = (0..6).map { weekStart.plusDays(it.toLong()) }
    val today = LocalDate.now()
    LazyColumn(
        Modifier.fillMaxSize().swipeNav(onPrev, onNext),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(days, key = { it.toString() }) { d ->
            val list = dueByDate[d].orEmpty().sortedBy { it.dueDate }
            val isToday = d == today
            val accent = MaterialTheme.colorScheme.primary
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            color = if (isToday) accent else MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.size(6.dp))
                        Text("${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (isToday) { Spacer(Modifier.size(6.dp)); Box(Modifier.clip(RoundedCornerShape(999.dp)).background(accent).padding(horizontal = 7.dp, vertical = 1.dp)) { Text("Today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary) } }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onAddOnDate(d) }, modifier = Modifier.size(30.dp)) { Icon(Icons.Filled.Add, "Add on this day", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
                    }
                    if (list.isEmpty()) {
                        Text("—", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    } else {
                        Spacer(Modifier.size(4.dp))
                        list.forEach { t -> WeekChip(t, onOpenTask) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekChip(task: TaskEntity, onOpenTask: (String) -> Unit) {
    val level = PriorityLevel.from(task.importance, task.urgency)
    val c = if (level == PriorityLevel.NONE) MaterialTheme.colorScheme.primary else priorityColor(level)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(7.dp))
            .background(c.copy(alpha = 0.14f)).clickable { onOpenTask(task.id) }.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Spacer(Modifier.size(8.dp))
        Text(task.title.ifBlank { "Untitled" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (task.completed) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun AgendaView(dueByDate: Map<LocalDate, List<TaskEntity>>, onOpenTask: (String) -> Unit, swipe: CalSwipe) {
    val days = dueByDate.keys.sorted()
    if (days.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No scheduled tasks", color = MaterialTheme.colorScheme.onSurfaceVariant) }; return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 2.dp, bottom = 100.dp)) {
        days.forEach { d ->
            item(key = "h$d") { DayHeader(d) }
            items(dueByDate[d].orEmpty(), key = { it.id }) { TaskLine(it, onOpenTask, swipe) }
        }
    }
}

/** Swipe config for calendar task rows, mirroring the global list swipe settings. */
private data class CalSwipe(
    val rightNear: SwipeAction, val rightFar: SwipeAction, val leftNear: SwipeAction, val leftFar: SwipeAction,
    val onAct: (SwipeAction, TaskEntity) -> Unit,
)

private fun calSwipeVisual(action: SwipeAction): Pair<Color, androidx.compose.ui.graphics.vector.ImageVector> = when (action) {
    SwipeAction.COMPLETE -> Color(0xFF12A594) to Icons.Filled.Check
    SwipeAction.TRASH -> Color(0xFFE5484D) to Icons.Filled.Delete
    SwipeAction.STAR -> Color(0xFFF5A623) to Icons.Filled.Star
    SwipeAction.WONT_DO -> Color(0xFF64748B) to Icons.Filled.Close
    SwipeAction.CYCLE_PRIORITY -> Color(0xFF3E7BFA) to Icons.Filled.Flag
    SwipeAction.EDIT -> Color(0xFF5B57D9) to Icons.Filled.Edit
    else -> Color.Transparent to Icons.Filled.Check
}

/** TickTick-style calendar task pill: priority-tinted, tap to edit, and a two-level swipe (near +
 *  full, per direction) driven by the same global swipe settings as the task list. */
@Composable
private fun TaskLine(task: TaskEntity, onOpenTask: (String) -> Unit, swipe: CalSwipe) {
    val zone = ZoneId.systemDefault()
    val level = PriorityLevel.from(task.importance, task.urgency)
    val accent = if (level == PriorityLevel.NONE) MaterialTheme.colorScheme.primary else priorityColor(level)
    val scope = rememberCoroutineScope()
    val dens = LocalDensity.current
    val nearPx = with(dens) { 76.dp.toPx() }
    val farPx = with(dens) { 176.dp.toPx() }
    val maxPx = with(dens) { 230.dp.toPx() }
    val offsetX = remember(task.id) { Animatable(0f) }
    val goingRight = offsetX.value > 0
    val pendingAction = when {
        goingRight && offsetX.value >= farPx && swipe.rightFar != SwipeAction.NONE -> swipe.rightFar
        goingRight -> swipe.rightNear
        !goingRight && -offsetX.value >= farPx && swipe.leftFar != SwipeAction.NONE -> swipe.leftFar
        else -> swipe.leftNear
    }

    Box(Modifier.padding(horizontal = 12.dp, vertical = 3.dp)) {
        if (offsetX.value != 0f && pendingAction != SwipeAction.NONE) {
            val (c, icon) = calSwipeVisual(pendingAction)
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)).background(c).padding(horizontal = 20.dp),
                contentAlignment = if (goingRight) Alignment.CenterStart else Alignment.CenterEnd) {
                Icon(icon, null, tint = Color.White)
            }
        }
        Surface(
            Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { d -> scope.launch { offsetX.snapTo((offsetX.value + d).coerceIn(-maxPx, maxPx)) } },
                    onDragStopped = {
                        val v = offsetX.value
                        when {
                            v >= farPx && swipe.rightFar != SwipeAction.NONE -> swipe.onAct(swipe.rightFar, task)
                            v >= nearPx -> swipe.onAct(swipe.rightNear, task)
                            v <= -farPx && swipe.leftFar != SwipeAction.NONE -> swipe.onAct(swipe.leftFar, task)
                            v <= -nearPx -> swipe.onAct(swipe.leftNear, task)
                        }
                        offsetX.animateTo(0f)
                    },
                ),
            shape = RoundedCornerShape(12.dp),
            // OPAQUE fill: a translucent priority tint would let the swipe-action panel bleed through
            // the whole pill (the old "colours the whole row" bug), so composite it over the surface.
            color = if (level == PriorityLevel.NONE) MaterialTheme.colorScheme.surface
                    else accent.copy(alpha = 0.09f).compositeOver(MaterialTheme.colorScheme.surface),
            tonalElevation = if (level == PriorityLevel.NONE) 1.dp else 0.dp,
        ) {
            Row(Modifier.height(IntrinsicSize.Min).clickable { onOpenTask(task.id) }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
                com.todocompanion.app.ui.components.PriorityCheckbox(task.completed, level, onCheckedChange = { swipe.onAct(SwipeAction.COMPLETE, task) })
                Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
                    Text(
                        task.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
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
    // Midnight is the all-day sentinel; every other time is a real time.
    val dt = Instant.ofEpochMilli(millis).atZone(zone)
    return !(dt.hour == 0 && dt.minute == 0)
}

private fun timeLabel(millis: Long, zone: ZoneId): String {
    val dt = Instant.ofEpochMilli(millis).atZone(zone)
    return "%02d:%02d".format(dt.hour, dt.minute)
}

private fun minLabel(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
