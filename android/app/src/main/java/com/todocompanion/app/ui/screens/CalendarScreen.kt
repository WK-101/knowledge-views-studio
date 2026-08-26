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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.CalendarViewMonth
import androidx.compose.material.icons.filled.ArrowDropDown
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
import androidx.compose.runtime.mutableStateMapOf
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

/** Advance the calendar anchor by one period for the active mode. */
fun calStep(mode: String, anchor: LocalDate, dir: Int): LocalDate = when (mode) {
    "month" -> anchor.plusMonths(dir.toLong())
    "week", "weekly" -> anchor.plusWeeks(dir.toLong())
    "3day" -> anchor.plusDays(3L * dir)
    "day" -> anchor.plusDays(dir.toLong())
    "year" -> anchor.plusYears(dir.toLong())
    else -> anchor
}

/** The period label shown in the calendar header for the active mode. */
fun calLabel(mode: String, anchor: LocalDate, firstDow: DayOfWeek): String = when (mode) {
    "month" -> "${anchor.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${anchor.year}"
    "week", "weekly" -> { val st = startOfWeek(anchor, firstDow); rangeTitle(st, st.plusDays(6)) }
    "3day" -> rangeTitle(anchor, anchor.plusDays(2))
    "day" -> "${anchor.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${anchor.dayOfMonth} ${anchor.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"
    "year" -> anchor.year.toString()
    else -> "Agenda"
}

@Composable
fun CalendarScreen(
    vm: AppViewModel, onOpenTask: (String) -> Unit, mode: String, onModeChange: (String) -> Unit,
    anchor: LocalDate, selected: LocalDate, onAnchor: (LocalDate) -> Unit, onSelected: (LocalDate) -> Unit,
    onAddOnDate: (LocalDate) -> Unit, onAddAt: (LocalDate, Int) -> Unit = { d, _ -> onAddOnDate(d) },
    modifier: Modifier = Modifier,
) {
    val s by vm.settings.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val zone = ZoneId.systemDefault()

    val firstDow = if (s.weekStart in 1..7) DayOfWeek.of(s.weekStart) else WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val listFilter = s.calendarListFilter
    val dueByDate = remember(tasks, listFilter) {
        tasks.filter { !it.trashed && !it.completed && !it.abandoned && it.dueDate != null && (listFilter.isEmpty() || it.listId in listFilter) }
            .groupBy { Instant.ofEpochMilli(it.dueDate!!).atZone(zone).toLocalDate() }
    }

    // M1: optionally draw timed habits as read-only blocks in the day/week grid. Opt-in (default off).
    val habits by vm.habits.collectAsState()
    val habitCheckins by vm.habitCheckins.collectAsState()
    val habitBlocksFor: (LocalDate) -> List<HabitBlock> = block@{ d ->
        if (!s.habitCalendarBlocks) return@block emptyList()
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val ed = d.toEpochDay()
        habits.filter { !it.archived && !it.paused && it.habitType != "break" }.flatMap { h ->
            val scheduled = hs.isExpectedDay(h, ed) || h.freqType == hs.FREQ_TIMES_WEEK || h.freqType == hs.FREQ_TIMES_MONTH
            // G2: an untimed-but-scheduled habit still gets a block — anchored at 09:00 — so turning the
            // toggle on actually shows something. Previously habits with no reminder time were silently dropped.
            val rawTimes = h.reminderTimes.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..1439 }
            val times = rawTimes.ifEmpty { listOf(9 * 60) }
            if (!scheduled) emptyList()
            else {
                val done = habitCheckins.any { it.habitId == h.id && it.epochDay == ed && it.status == "done" && hs.meetsGoal(h, it.count) }
                val dur = if (h.unit == "min") h.targetPerDay.coerceIn(10, 180) else 30
                val col = h.colorArgb?.let { androidx.compose.ui.graphics.Color(it) }
                times.map { m -> HabitBlock(h.id, (h.emoji?.plus(" ") ?: "") + h.name, col, m, dur, done) }
            }
        }
    }
    val onOpenHabit: (String) -> Unit = { id -> vm.habitDetailId.value = id }

    val onResize: (String, Int) -> Unit = { id, dur -> vm.setDuration(id, dur) }
    val onMoveTaskTo: (LocalDate, String, Int) -> Unit = { d, id, min -> vm.rescheduleToMinute(id, d, min) }
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
    val prev = { onAnchor(calStep(mode, anchor, -1)) }
    val next = { onAnchor(calStep(mode, anchor, 1)) }

    // The combined header lives in the app-bar slot (see AppRoot), so switching tabs never shifts
    // the content and the buttons line up with every other screen.
    Column(modifier.fillMaxSize()) {
        when (mode) {
            "month" -> MonthView(anchor, selected, dueByDate, firstDow, onSelect = { onSelected(it) }, onPrev = prev, onNext = next, onOpenTask = onOpenTask, swipe = swipe, onAdd = { onAddOnDate(selected) },
                habitBlocksFor = habitBlocksFor, onOpenHabit = onOpenHabit,
                onMoveToDay = { d, id ->
                    // Preserve the task's time-of-day when dropping it on another day; default 9am.
                    val min = tasks.firstOrNull { it.id == id }?.dueDate?.let { Instant.ofEpochMilli(it).atZone(zone).let { z -> z.hour * 60 + z.minute } } ?: 540
                    vm.rescheduleToMinute(id, d, min)
                })
            "week" -> {
                val start = startOfWeek(anchor, firstDow)
                TimelineView((0..6).map { start.plusDays(it.toLong()) }, dueByDate, zone, onPrev = prev, onNext = next, onOpenTask = onOpenTask, onAddOnDate = onAddOnDate, onAddAt = onAddAt, onResize = onResize, onMoveAt = onMoveTaskTo, habitBlocksFor = habitBlocksFor, onOpenHabit = onOpenHabit)
            }
            "weekly" -> WeeklyView(startOfWeek(anchor, firstDow), dueByDate, onPrev = prev, onNext = next, onOpenTask = onOpenTask, onAddOnDate = onAddOnDate)
            "3day" -> TimelineView((0..2).map { anchor.plusDays(it.toLong()) }, dueByDate, zone, onPrev = prev, onNext = next, onOpenTask = onOpenTask, onAddOnDate = onAddOnDate, onAddAt = onAddAt, onResize = onResize, onMoveAt = onMoveTaskTo, habitBlocksFor = habitBlocksFor, onOpenHabit = onOpenHabit)
            "day" -> TimelineView(listOf(anchor), dueByDate, zone, onPrev = prev, onNext = next, onOpenTask = onOpenTask, onAddOnDate = onAddOnDate, onAddAt = onAddAt, onResize = onResize, onMoveAt = onMoveTaskTo, habitBlocksFor = habitBlocksFor, onOpenHabit = onOpenHabit)
            "year" -> YearView(anchor, dueByDate, onPrev = prev, onNext = next, onMonth = { m -> onAnchor(m.atDay(1)); onModeChange("month") }, onDay = { d -> onAnchor(d); onModeChange("day") })
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
/** The calendar's combined app-bar: menu · prev · period ▾ · next · Today · view-type · filter.
 *  Rendered in the Scaffold's top-bar slot so its insets, height and icon placement match every
 *  other screen and switching to the calendar never shifts the layout. */
@Composable
fun CalHeader(
    label: String, anchor: LocalDate, showNav: Boolean,
    onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit, onPickDate: (LocalDate) -> Unit,
    onOpenDrawer: () -> Unit, mode: String, onModeChange: (String) -> Unit,
    onOpenFilter: () -> Unit, filterActive: Boolean,
) {
    var showPicker by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, "Menu") }
        // The prev/next chevrons are gone — swipe the calendar body left/right to move periods.
        // Dropping them gives the period label the full width it deserves.
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(enabled = showNav) { showPicker = true }.padding(vertical = 4.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showNav) { Spacer(Modifier.width(3.dp)); Icon(Icons.Filled.ArrowDropDown, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        IconButton(onClick = onToday) { Icon(Icons.Filled.Today, "Today") }
        Box {
            IconButton(onClick = { typeMenu = true }) { Icon(Icons.Filled.CalendarViewMonth, "View type") }
            androidx.compose.material3.DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                CAL_MODES.forEach { (k, l) ->
                    val sel = mode == k
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(l, color = if (sel) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal) },
                        onClick = { onModeChange(k); typeMenu = false },
                    )
                }
            }
        }
        IconButton(onClick = onOpenFilter) { Icon(Icons.Filled.FilterList, "Filter lists", tint = if (filterActive) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current) }
    }
    }
    if (showPicker) CalPeriodPicker(mode, anchor, onDismiss = { showPicker = false }) { d -> onPickDate(d); showPicker = false }
}

/** The header period-picker, matched to the active view: a specific-date picker for the day/week
 *  modes, a month grid for Month, and a year grid for Year. */
@Composable
private fun CalPeriodPicker(mode: String, anchor: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    when (mode) {
        "year" -> YearGridPicker(anchor.year, onDismiss) { y -> onPick(anchor.withYear(y)) }
        "month" -> MonthYearPicker(YearMonth.from(anchor), onDismiss) { ym -> onPick(ym.atDay(1)) }
        else -> DayPicker(anchor, onDismiss, onPick)   // day / 3day / week / weekly → pick an exact day
    }
}

/** A 3×4 grid of years with decade paging, styled like the month grid. */
@Composable
private fun YearGridPicker(currentYear: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var base by remember { mutableStateOf(currentYear - (currentYear.mod(12))) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { base -= 12 }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Earlier years") }
                Text("$base – ${base + 11}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(onClick = { base += 12 }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Later years") }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..3).forEach { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (0..2).forEach { c ->
                            val y = base + r * 3 + c
                            val sel = y == currentYear
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))
                                    .clickable { onPick(y) }.padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(y.toString(), color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        },
    )
}

/** A compact month calendar for choosing a specific day (used by the day/week header picker). */
@Composable
private fun DayPicker(anchor: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    var ym by remember { mutableStateOf(YearMonth.from(anchor)) }
    val firstDow = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val labels = (0..6).map { firstDow.plus(it.toLong()) }
    val first = ym.atDay(1)
    val leading = (first.dayOfWeek.value - firstDow.value + 7) % 7
    val cells = buildList<LocalDate?> {
        repeat(leading) { add(null) }
        for (day in 1..ym.lengthOfMonth()) add(ym.atDay(day))
        while (size % 7 != 0) add(null)
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { ym = ym.minusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
                Text("${ym.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${ym.year}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = { ym = ym.plusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month") }
            }
        },
        text = {
            Column {
                Row(Modifier.fillMaxWidth()) {
                    labels.forEach { d -> Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(4.dp))
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { d ->
                            if (d == null) Box(Modifier.weight(1f).padding(2.dp).height(36.dp))
                            else {
                                val sel = d == anchor
                                val today = d == LocalDate.now()
                                Box(
                                    Modifier.weight(1f).padding(2.dp).height(36.dp).clip(CircleShape)
                                        .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                                        .clickable { onPick(d) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium,
                                        color = when { sel -> MaterialTheme.colorScheme.onPrimary; today -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurface },
                                        fontWeight = if (sel || today) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            }
        },
    )
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
private fun MonthView(anchor: LocalDate, selected: LocalDate, dueByDate: Map<LocalDate, List<TaskEntity>>, firstDow: DayOfWeek, onSelect: (LocalDate) -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onOpenTask: (String) -> Unit, swipe: CalSwipe, onAdd: () -> Unit, habitBlocksFor: (LocalDate) -> List<HabitBlock>, onOpenHabit: (String) -> Unit, onMoveToDay: (LocalDate, String) -> Unit) {
    val ym = YearMonth.from(anchor)
    val labels = (0..6).map { firstDow.plus(it.toLong()) }
    val first = ym.atDay(1)
    val leading = (first.dayOfWeek.value - firstDow.value + 7) % 7
    val cells = buildList<LocalDate?> {
        repeat(leading) { add(null) }
        for (day in 1..ym.lengthOfMonth()) add(ym.atDay(day))
        while (size % 7 != 0) add(null)
    }
    val today = LocalDate.now()
    val primary = MaterialTheme.colorScheme.primary

    // Long-press a task in the agenda to drag it onto a day cell and reschedule. All coordinates in
    // window space so the finger, the cell bounds and the floating chip line up.
    var dragTask by remember { mutableStateOf<TaskEntity?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var boxOrigin by remember { mutableStateOf(Offset.Zero) }
    val cellBounds = remember { mutableStateMapOf<LocalDate, Rect>() }
    val targetDay = if (dragTask != null) cellBounds.entries.firstOrNull { it.value.contains(pointer) }?.key else null

    Box(Modifier.fillMaxSize().onGloballyPositioned { boxOrigin = it.boundsInWindow().topLeft }) {
      Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            labels.forEach { d -> Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).swipeNav(onPrev, onNext)) {
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        val isToday = date == today
                        val isSelected = date == selected
                        val isTarget = date != null && date == targetDay
                        // TickTick grammar: today is a solid filled circle; a selected non-today day gets a ring.
                        val ringMod = when {
                            isTarget -> Modifier.background(primary.copy(alpha = .28f), CircleShape).border(2.dp, primary, CircleShape)
                            isToday -> Modifier.background(primary, CircleShape)
                            isSelected -> Modifier.border(1.5.dp, primary, CircleShape)
                            else -> Modifier
                        }
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).padding(3.dp)
                                .then(if (date != null) Modifier.onGloballyPositioned { cellBounds[date] = it.boundsInWindow() } else Modifier)
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
                                // G2: a day can carry a task dot (primary) and/or a habit dot (tertiary),
                                // so scheduled habits are visible right in the month grid when the toggle is on.
                                val hasTask = dueByDate.containsKey(date)
                                val hasHabit = habitBlocksFor(date).isNotEmpty()
                                if (hasTask || hasHabit) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if (hasTask) Box(Modifier.size(5.dp).clip(CircleShape).background(if (isToday) MaterialTheme.colorScheme.onPrimary else primary))
                                    if (hasHabit) Box(Modifier.size(5.dp).clip(CircleShape).background(if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.tertiary))
                                } else Spacer(Modifier.size(5.dp))
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
        // G2: habits scheduled for the selected day, shown right under the date so month-view users
        // (the default view) see and can open their habits without switching to a timeline view.
        val dayHabits = habitBlocksFor(selected).distinctBy { it.id }
        if (dayHabits.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                dayHabits.forEach { hb ->
                    val c = hb.color ?: MaterialTheme.colorScheme.tertiary
                    Row(
                        Modifier.clip(RoundedCornerShape(20.dp))
                            .background(if (hb.done) c.copy(alpha = .18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))
                            .clickable { onOpenHabit(hb.id) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
                        Spacer(Modifier.size(6.dp))
                        Text((if (hb.done) "✓ " else "") + hb.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
        val agenda = dueByDate[selected].orEmpty()
        if (agenda.isEmpty()) Text("Nothing due — enjoy the day", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 2.dp, bottom = 100.dp)) {
            items(agenda, key = { it.id }) { task ->
                MonthAgendaRow(
                    task, onOpenTask, swipe, dragging = dragTask?.id == task.id,
                    onDragStart = { origin, off -> dragTask = task; pointer = origin + off },
                    onDrag = { d -> pointer += d },
                    onDragEnd = {
                        val tgt = cellBounds.entries.firstOrNull { it.value.contains(pointer) }?.key
                        if (tgt != null && tgt != selected) onMoveToDay(tgt, task.id)
                        dragTask = null
                    },
                    onDragCancel = { dragTask = null },
                )
            }
        }
      }
      // Floating chip that follows the finger, plus a hint.
      dragTask?.let { t ->
        androidx.compose.material3.Surface(
            Modifier.offset { IntOffset((pointer.x - boxOrigin.x).roundToInt() - 90, (pointer.y - boxOrigin.y).roundToInt() - 30) }
                .widthIn(max = 220.dp),
            shape = RoundedCornerShape(10.dp), color = primary, shadowElevation = 8.dp,
        ) {
            Text(
                (targetDay?.let { "→ ${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}: " } ?: "") + t.title,
                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                color = MaterialTheme.colorScheme.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
            )
        }
      }
    }
}

/** An agenda row that also supports long-press-drag onto a month day cell to reschedule. */
@Composable
private fun MonthAgendaRow(
    task: TaskEntity, onOpenTask: (String) -> Unit, swipe: CalSwipe, dragging: Boolean,
    onDragStart: (Offset, Offset) -> Unit, onDrag: (Offset) -> Unit, onDragEnd: () -> Unit, onDragCancel: () -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .onGloballyPositioned { origin = it.boundsInWindow().topLeft }
            .pointerInput(task.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { off -> onDragStart(origin, off) },
                    onDrag = { change, d -> change.consume(); onDrag(d) },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() },
                )
            }
            .then(if (dragging) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = .06f)) else Modifier),
    ) {
        TaskLine(task, onOpenTask, swipe)
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
    habitBlocksFor: (LocalDate) -> List<HabitBlock> = { emptyList() }, onOpenHabit: (String) -> Unit = {},
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
                DayColumn(d, timed, zone, onOpenTask, onAddAt, onResize, onMoveAt = { id, min -> onMoveAt(d, id, min) },
                    habitBlocks = habitBlocksFor(d), onOpenHabit = onOpenHabit, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DayColumn(day: LocalDate, timed: List<TaskEntity>, zone: ZoneId, onOpenTask: (String) -> Unit, onAddAt: (LocalDate, Int) -> Unit, onResize: (String, Int) -> Unit, onMoveAt: (String, Int) -> Unit,
    habitBlocks: List<HabitBlock> = emptyList(), onOpenHabit: (String) -> Unit = {}, modifier: Modifier) {
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
        // M1: when habit blocks share the column, tasks take the left ~60% and habits the right ~40%
        // so the two never collide and the task drag/resize math is otherwise unchanged.
        val taskAreaW = if (habitBlocks.isEmpty()) colW else colW * 0.6f
        val habitColor = MaterialTheme.colorScheme.tertiary
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
            val laneW = (taskAreaW - 2.dp) / p.lanes
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
        // M1: habit blocks in the right lane — read-only, tap opens the habit. Distinct dotted-ish look.
        if (habitBlocks.isNotEmpty()) {
            val habitAreaX = taskAreaW + 1.dp
            val habitAreaW = colW - taskAreaW - 2.dp
            habitBlocks.sortedBy { it.startMin }.forEach { hb ->
                val col = hb.color ?: habitColor
                val top = (HOUR_DP * hb.startMin / 60f).dp
                val hh = ((HOUR_DP * hb.durMin / 60f).dp).coerceAtLeast(22.dp)
                Row(
                    Modifier.offset(x = habitAreaX, y = top).width(habitAreaW).height(hh - 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(col.copy(alpha = if (hb.done) 0.30f else 0.12f))
                        .clickable { onOpenHabit(hb.id) },
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(col))
                    Text((if (hb.done) "✓ " else "") + hb.label, Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall, maxLines = if (hh > 46.dp) 2 else 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface)
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

/** M1: a timed habit drawn as a read-only block in the calendar's day/week grid. */
private data class HabitBlock(
    val id: String, val label: String, val color: androidx.compose.ui.graphics.Color?,
    val startMin: Int, val durMin: Int, val done: Boolean,
)

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
    // Task count per day drives the heatmap intensity (busier days read darker).
    val countByDay = remember(dueByDate, anchor.year) {
        dueByDate.filterKeys { it.year == anchor.year }.mapValues { it.value.size }
    }
    // Four weighted rows of three months fill the whole screen height instead of cramming at the top.
    Column(Modifier.fillMaxSize().swipeNav(onPrev, onNext).padding(horizontal = 8.dp, vertical = 6.dp)) {
        (1..12).chunked(3).forEach { rowMonths ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowMonths.forEach { m ->
                    MiniMonth(YearMonth.of(anchor.year, m), countByDay, onMonth = onMonth, onDay = onDay,
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp))
                }
            }
        }
    }
}

/** A compact month for the Year view. Task-days get a circle tinted by how many tasks fall on them
 *  (a real heatmap); tap a day to open it, or the month name to open the month. */
@Composable
private fun MiniMonth(ym: YearMonth, countByDay: Map<LocalDate, Int>, onMonth: (YearMonth) -> Unit, onDay: (LocalDate) -> Unit, modifier: Modifier) {
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
                            val n = countByDay[d] ?: 0
                            val hasTasks = n > 0
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(1f).padding(1.dp).clip(CircleShape)
                                    .background(when {
                                        isToday -> MaterialTheme.colorScheme.primary
                                        // Graded: 1 task ≈ 24% → 5+ tasks ≈ 72% opacity.
                                        hasTasks -> MaterialTheme.colorScheme.primary.copy(alpha = (0.12f + 0.12f * n.coerceAtMost(5)).coerceAtMost(0.72f))
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
