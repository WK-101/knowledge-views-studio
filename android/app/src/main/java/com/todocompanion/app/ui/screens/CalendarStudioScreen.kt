package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import com.todocompanion.app.ui.components.AppCard
import com.todocompanion.app.ui.components.AppTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.todocompanion.app.data.entity.EventCalendarEntity
import com.todocompanion.app.data.entity.EventEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.calendar.CalendarEngine
import com.todocompanion.app.domain.recurrence.Freq
import com.todocompanion.app.domain.recurrence.Recur
import com.todocompanion.app.domain.recurrence.Recurrence
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.DateTimePickerDialog
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private enum class CalView(val label: String) { MONTH("Month"), WEEK("Week"), DAY("Day"), AGENDA("Agenda") }

private val CAL_COLORS = listOf(
    0xFF4F46E5, 0xFFE5484D, 0xFFF59E0B, 0xFF12A594, 0xFF3E7BFA, 0xFFEC4899, 0xFF16A34A, 0xFF7C3AED,
)
// R59 (Wave 1) — event alert offsets come from the one shared preset set, so a "30 min" alert here means
// the same as a "30 min before" task reminder.
private val ALERT_CHOICES = com.todocompanion.app.domain.reminders.ReminderPresets.OFFSETS.map { it to com.todocompanion.app.domain.reminders.ReminderPresets.shortLabel(it) }

/**
 * R38 — the DEDICATED CALENDAR. Its own local event store (no calendar-provider, no network): month /
 * week / day / agenda views, colour-coded calendars, recurrence, alerts, natural-language quick add,
 * a real-load heat-map, conflict warnings, a free-slot "find a gap", and .ics import/export. Offline.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalendarStudioScreen(vm: AppViewModel, onBack: () -> Unit, onOpenTask: (String) -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val zone = vm.zoneId
    val events by vm.events.collectAsState()
    val calendars by vm.eventCalendars.collectAsState()
    val settings by vm.settings.collectAsState()
    val tasks by vm.tasks.collectAsState()   // R60 — so the agenda "booked" figure counts scheduled tasks too

    var view by remember { mutableStateOf(CalView.MONTH) }
    var monthAnchor by remember { mutableStateOf(YearMonth.now(zone)) }
    var selectedDay by remember { mutableStateOf(LocalDate.now(zone).toEpochDay()) }
    var editing by remember { mutableStateOf<EventEntity?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var editorSeedStart by remember { mutableStateOf(0L) }
    var editorSeedEnd by remember { mutableStateOf(0L) }
    var quickOpen by remember { mutableStateOf(false) }
    var calsOpen by remember { mutableStateOf(false) }
    var gapOpen by remember { mutableStateOf(false) }
    var blockOpen by remember { mutableStateOf(false) }
    var fabMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }

    val visibleCalIds = remember(calendars) { calendars.filter { it.visible }.map { it.id }.toSet() }
    val calById = remember(calendars) { calendars.associateBy { it.id } }
    val shownEvents = remember(events, visibleCalIds) { events.filter { it.calendarId in visibleCalIds } }

    // R45 — ICS import/export via SystemPicker (classic Activity startActivityForResult).

    fun openNew(start: Long, end: Long) { editing = null; editorSeedStart = start; editorSeedEnd = end; editorOpen = true }

    val periodLabel = when (view) {
        CalView.MONTH -> monthAnchor.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        CalView.WEEK -> {
            val d = LocalDate.ofEpochDay(selectedDay); val ws = weekStartOf(d, settings.weekStart)
            "${ws.format(DateTimeFormatter.ofPattern("MMM d"))} – ${ws.plusDays(6).format(DateTimeFormatter.ofPattern("MMM d"))}"
        }
        CalView.DAY -> LocalDate.ofEpochDay(selectedDay).format(DateTimeFormatter.ofPattern("EEE, MMM d"))
        CalView.AGENDA -> "Agenda"
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    title = { Text(periodLabel, style = MaterialTheme.typography.titleLarge, maxLines = 1) },
                    actions = {
                        IconButton(onClick = {
                            when (view) {
                                CalView.MONTH -> monthAnchor = monthAnchor.minusMonths(1)
                                CalView.WEEK -> selectedDay -= 7
                                CalView.DAY -> selectedDay -= 1
                                CalView.AGENDA -> {}
                            }
                        }) { Icon(Icons.Filled.KeyboardArrowLeft, "Previous") }
                        IconButton(onClick = {
                            when (view) {
                                CalView.MONTH -> monthAnchor = monthAnchor.plusMonths(1)
                                CalView.WEEK -> selectedDay += 7
                                CalView.DAY -> selectedDay += 1
                                CalView.AGENDA -> {}
                            }
                        }) { Icon(Icons.Filled.KeyboardArrowRight, "Next") }
                        IconButton(onClick = {
                            val t = LocalDate.now(zone); monthAnchor = YearMonth.from(t); selectedDay = t.toEpochDay()
                        }) { Icon(Icons.Filled.Today, "Today") }
                        Box {
                            IconButton(onClick = { viewMenu = true }) { Icon(Icons.Filled.CalendarMonth, "View") }
                            DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                                CalView.values().forEach { v ->
                                    DropdownMenuItem(
                                        text = { Text(v.label) },
                                        trailingIcon = { if (v == view) Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) },
                                        onClick = { view = v; viewMenu = false })
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                DropdownMenuItem(text = { Text("Calendars…") }, onClick = { overflow = false; calsOpen = true })
                                DropdownMenuItem(text = { Text("Find a gap…") }, onClick = { overflow = false; gapOpen = true })
                                DropdownMenuItem(text = { Text("Block time for a task…") }, onClick = { overflow = false; blockOpen = true })
                                DropdownMenuItem(text = { Text("Import .ics") }, onClick = { overflow = false; com.todocompanion.app.util.SystemPicker.openFile(arrayOf("text/calendar", "application/octet-stream", "*/*"), onError = { vm.toastMsg(it) }) { vm.importIcsEvents(it) } })
                                DropdownMenuItem(text = { Text("Export .ics (file)") }, onClick = { overflow = false; com.todocompanion.app.util.SystemPicker.createFile("text/calendar", "todocompanion-calendar.ics", onError = { vm.exportIcsEventsToDownloads() }) { vm.exportIcsEventsTo(it) } })
                                DropdownMenuItem(text = { Text("Export .ics (Downloads)") }, onClick = { overflow = false; vm.exportIcsEventsToDownloads() })
                            }
                        }
                    },
                )
            },
            floatingActionButton = {
                Box {
                    ExtendedFloatingActionButton(
                        onClick = { fabMenu = true },
                        icon = { Icon(Icons.Filled.Add, null) },
                        text = { Text("Add") },
                    )
                    DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                        DropdownMenuItem(text = { Text("Quick add (type it)") }, leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) }, onClick = { fabMenu = false; quickOpen = true })
                        DropdownMenuItem(text = { Text("New event") }, leadingIcon = { Icon(Icons.Filled.Event, null, Modifier.size(18.dp)) }, onClick = {
                            fabMenu = false
                            val d = LocalDate.ofEpochDay(selectedDay)
                            val s = d.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
                            openNew(s, s + 3_600_000L)
                        })
                    }
                }
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                when (view) {
                    CalView.MONTH -> MonthView(
                        shownEvents, calById, monthAnchor, selectedDay, settings.weekStart, zone,
                        onPickDay = { selectedDay = it },
                    )
                    CalView.WEEK -> WeekStrip(selectedDay, settings.weekStart, zone, shownEvents) { selectedDay = it }
                    CalView.DAY -> {}
                    CalView.AGENDA -> {}
                }
                when (view) {
                    CalView.AGENDA -> AgendaList(shownEvents, calById, selectedDay, zone, onOpenTask) { editing = it; editorOpen = true }
                    else -> DayAgenda(shownEvents, calById, selectedDay, zone, settings.workStartHour, settings.workEndHour, onOpenTask,
                        onOpen = { editing = it; editorOpen = true }, onNew = { s, e -> openNew(s, e) }, tasks = tasks)
                }
            }
        }
    }

    if (editorOpen) {
        EventEditor(
            vm = vm, zone = zone, calendars = calendars, existing = editing,
            seedStart = editorSeedStart, seedEnd = editorSeedEnd,
            onClose = { editorOpen = false; editing = null },
        )
    }
    if (quickOpen) QuickAddDialog(onDismiss = { quickOpen = false }) { text -> vm.quickAddCalendar(text, selectedDay); quickOpen = false }
    if (calsOpen) CalendarsManager(vm, calendars, onDismiss = { calsOpen = false })
    if (gapOpen) GapFinder(shownEvents, selectedDay, zone, settings.workStartHour, settings.workEndHour,
        onDismiss = { gapOpen = false }, tasks = tasks, onPick = { s, e -> gapOpen = false; openNew(s, e) })
    if (blockOpen) BlockTaskDialog(vm, selectedDay, zone, settings.workStartHour, shownEvents, onDismiss = { blockOpen = false })
}

// ── Block a task as a calendar time-block (the task ⇄ calendar moat) ───────────────────────────────
@Composable
internal fun BlockTaskDialog(vm: AppViewModel, day: Long, zone: ZoneId, workStart: Int, events: List<EventEntity>, onDismiss: () -> Unit) {
    val tasks by vm.tasks.collectAsState()
    val open = remember(tasks) { tasks.filter { !it.completed && !it.trashed && !it.abandoned && !it.isNote }.take(60) }
    // Default to the first free slot in working hours, else 9am. R60 — scheduled tasks are busy too, so
    // "block time for a task" never suggests a slot that already holds another timed task.
    val busy = remember(events, tasks, day) {
        CalendarEngine.onDay(events, day, zone).filter { it.event.busy && !it.event.allDay }.map { it.startMillis to it.endMillis } +
            com.todocompanion.app.domain.calendar.Availability.taskBusyIntervals(tasks, zone, events.mapNotNull { it.linkedTaskId }.toSet())
    }
    fun startFor(durMin: Int): Long {
        val slot = CalendarEngine.freeSlots(busy, day, workStart, 22, durMin, zone).firstOrNull()
        return slot?.startMillis ?: LocalDate.ofEpochDay(day).atTime(workStart.coerceIn(0, 22), 0).atZone(zone).toInstant().toEpochMilli()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Block time for a task") },
        text = {
            Column {
                Text("Pick a task — it's placed in the first free ${LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("EEE"))} slot as a time block linked back to the task.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (open.isEmpty()) Text("No open tasks.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.height(320.dp)) {
                    items(open) { t ->
                        val dur = (t.estimateMin ?: t.durationMin ?: 60).coerceIn(15, 480)
                        Row(Modifier.fillMaxWidth().clickable { vm.blockTaskAsEvent(t.id, startFor(dur), dur); onDismiss() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Event, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text(t.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(fmtDur(dur), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
    )
}

// ── Month grid ───────────────────────────────────────────────────────────────────────────────────
@Composable
private fun MonthView(
    events: List<EventEntity>, calById: Map<String, EventCalendarEntity>, month: YearMonth,
    selectedDay: Long, weekStart: Int, zone: ZoneId, onPickDay: (Long) -> Unit,
) {
    val first = month.atDay(1)
    val startDow = ((first.dayOfWeek.value - weekStartIso(weekStart)) % 7 + 7) % 7
    val gridStart = first.minusDays(startDow.toLong())
    val today = LocalDate.now(zone).toEpochDay()
    val windowStart = gridStart.atStartOfDay(zone).toInstant().toEpochMilli()
    val windowEnd = gridStart.plusDays(42).atStartOfDay(zone).toInstant().toEpochMilli()
    val occ = remember(events, month, weekStart) { CalendarEngine.expand(events, windowStart, windowEnd, zone) }
    val byDay = remember(occ) { occ.groupBy { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate().toEpochDay() } }
    val heat = remember(events, month) { CalendarEngine.busyMinutesByDay(events, gridStart.toEpochDay(), 42, zone) }
    val maxHeat = (heat.values.maxOrNull() ?: 1).coerceAtLeast(1)

    Column(Modifier.padding(horizontal = 8.dp)) {
        // weekday header
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            for (i in 0..6) {
                val dow = java.time.DayOfWeek.of(((weekStartIso(weekStart) - 1 + i) % 7) + 1)
                Text(dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        for (w in 0 until 6) {
            Row(Modifier.fillMaxWidth()) {
                for (d in 0 until 7) {
                    val date = gridStart.plusDays((w * 7 + d).toLong())
                    val epoch = date.toEpochDay()
                    val inMonth = YearMonth.from(date) == month
                    val isToday = epoch == today
                    val isSel = epoch == selectedDay
                    val dayOcc = byDay[epoch].orEmpty()
                    val busyMin = heat[epoch] ?: 0
                    val tint = if (busyMin > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.05f + 0.20f * (busyMin.toFloat() / maxHeat)) else Color.Transparent
                    Box(
                        Modifier.weight(1f).aspectRatio(0.82f).padding(1.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else tint)
                            .then(if (isSel) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable { onPickDay(epoch) }.padding(2.dp),
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(20.dp).clip(CircleShape).background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent), contentAlignment = Alignment.Center) {
                                    Text("${date.dayOfMonth}", style = MaterialTheme.typography.labelSmall,
                                        color = when { isToday -> MaterialTheme.colorScheme.onPrimary; inMonth -> MaterialTheme.colorScheme.onSurface; else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) })
                                }
                            }
                            dayOcc.take(3).forEach { o ->
                                val c = colorOf(o.event, calById)
                                Row(Modifier.fillMaxWidth().padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(5.dp).clip(CircleShape).background(c))
                                    Spacer(Modifier.width(2.dp))
                                    Text(o.event.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp))
                                }
                            }
                            if (dayOcc.size > 3) Text("+${dayOcc.size - 3}", style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ── Week strip (7-day date selector) ────────────────────────────────────────────────────────────
@Composable
private fun WeekStrip(selectedDay: Long, weekStart: Int, zone: ZoneId, events: List<EventEntity>, onPick: (Long) -> Unit) {
    val ws = weekStartOf(LocalDate.ofEpochDay(selectedDay), weekStart)
    val today = LocalDate.now(zone).toEpochDay()
    Row(Modifier.fillMaxWidth().padding(8.dp)) {
        for (i in 0..6) {
            val date = ws.plusDays(i.toLong()); val epoch = date.toEpochDay()
            val isSel = epoch == selectedDay; val isToday = epoch == today
            val has = remember(events, epoch) { CalendarEngine.onDay(events, epoch, zone).isNotEmpty() }
            Column(
                Modifier.weight(1f).padding(2.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onPick(epoch) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Box(Modifier.size(26.dp).clip(CircleShape).background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent), contentAlignment = Alignment.Center) {
                    Text("${date.dayOfMonth}", style = MaterialTheme.typography.bodyMedium, color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(2.dp))
                Box(Modifier.size(4.dp).clip(CircleShape).background(if (has) MaterialTheme.colorScheme.primary else Color.Transparent))
            }
        }
    }
}

// ── Single-day agenda (used under Month/Week/Day) ─────────────────────────────────────────────────
@Composable
private fun DayAgenda(
    events: List<EventEntity>, calById: Map<String, EventCalendarEntity>, day: Long, zone: ZoneId,
    workStart: Int, workEnd: Int, onOpenTask: (String) -> Unit,
    onOpen: (EventEntity) -> Unit, onNew: (Long, Long) -> Unit, tasks: List<TaskEntity> = emptyList(),
) {
    val occ = remember(events, day) { CalendarEngine.onDay(events, day, zone) }
    val conflicts = remember(occ) { CalendarEngine.conflicts(occ) }
    val conflictIds = conflicts.flatMap { listOf(it.first.event.id, it.second.event.id) }.toSet()
    val date = LocalDate.ofEpochDay(day)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                val eventMin = occ.filter { it.event.busy && !it.event.allDay }.sumOf { it.durationMin() }
                // R60 — scheduled tasks count as booked too (excluding those already blocked as an event).
                val dayStart0 = date.atStartOfDay(zone).toInstant().toEpochMilli(); val dayEnd0 = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val taskMin = com.todocompanion.app.domain.calendar.Availability.taskBusyIntervals(tasks, zone, events.mapNotNull { it.linkedTaskId }.toSet())
                    .filter { it.second > dayStart0 && it.first < dayEnd0 }
                    .sumOf { ((minOf(it.second, dayEnd0) - maxOf(it.first, dayStart0)) / 60000L).coerceAtLeast(0) }
                val busy = eventMin + taskMin
                if (busy > 0) Text(fmtDur(busy.toInt()) + " booked", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (conflicts.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("${conflicts.size} overlap${if (conflicts.size == 1) "" else "s"} today — two things booked at once.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(Modifier.height(6.dp))
        }
        if (occ.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Event, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text("Nothing scheduled", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { val s = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(); onNew(s, s + 3_600_000L) }) { Text("Add an event") }
            }
        }
        items(occ) { o -> EventRow(o, calById, o.event.id in conflictIds, zone, onOpenTask) { onOpen(o.event) } }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ── Multi-day agenda ──────────────────────────────────────────────────────────────────────────────
@Composable
private fun AgendaList(events: List<EventEntity>, calById: Map<String, EventCalendarEntity>, fromDay: Long, zone: ZoneId, onOpenTask: (String) -> Unit, onOpen: (EventEntity) -> Unit) {
    val start = LocalDate.ofEpochDay(fromDay).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = LocalDate.ofEpochDay(fromDay + 45).atStartOfDay(zone).toInstant().toEpochMilli()
    val occ = remember(events, fromDay) { CalendarEngine.expand(events, start, end, zone) }
    val byDay = occ.groupBy { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate().toEpochDay() }.toSortedMap()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (byDay.isEmpty()) item { Text("Nothing on the calendar in the next 45 days.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        byDay.forEach { (day, list) ->
            item {
                Text(LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("EEE, MMM d")),
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            items(list) { o -> EventRow(o, calById, false, zone, onOpenTask) { onOpen(o.event) } }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun EventRow(o: CalendarEngine.Occurrence, calById: Map<String, EventCalendarEntity>, conflict: Boolean, zone: ZoneId, onOpenTask: (String) -> Unit, onClick: () -> Unit) {
    val c = colorOf(o.event, calById)
    val hm = DateTimeFormatter.ofPattern("h:mm a")
    Card(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable {
            val t = o.event.linkedTaskId; if (t != null) onOpenTask(t) else onClick()
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(38.dp).clip(RoundedCornerShape(2.dp)).background(c))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.event.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    if (o.event.linkedTaskId != null) { Spacer(Modifier.width(6.dp)); Icon(Icons.Filled.Check, "task block", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary) }
                    if (o.event.rrule.isNotBlank()) { Spacer(Modifier.width(6.dp)); Icon(Icons.Filled.Repeat, "repeats", Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (conflict) { Spacer(Modifier.width(6.dp)); Icon(Icons.Filled.Warning, "overlap", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error) }
                }
                val time = if (o.event.allDay) "All day" else "${Instant.ofEpochMilli(o.startMillis).atZone(zone).format(hm)} – ${Instant.ofEpochMilli(o.endMillis).atZone(zone).format(hm)}"
                val loc = o.event.location.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                Text(time + loc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ── Quick add (natural language) ──────────────────────────────────────────────────────────────────
@Composable
internal fun QuickAddDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onAdd(text) }, enabled = text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Quick add") },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Lunch with Sam Fri 1pm for 90m at Cafe every week") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onAdd(text) }),
                )
                Spacer(Modifier.height(8.dp))
                Text("Understands day, time, duration (\"for 90m\"), \"at <place>\", repeats (\"every week\") and \"alert 30m\". Start with \"todo\" to make it a task.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

// ── Calendars manager ─────────────────────────────────────────────────────────────────────────────
@Composable
internal fun CalendarsManager(vm: AppViewModel, calendars: List<EventCalendarEntity>, onDismiss: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<EventCalendarEntity?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Calendars") },
        text = {
            Column {
                calendars.sortedBy { it.orderIndex }.forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(16.dp).clip(CircleShape).background(Color(c.colorArgb)))
                        Spacer(Modifier.width(10.dp))
                        Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = { renaming = c }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Edit, "Rename", Modifier.size(16.dp)) }
                        Switch(checked = c.visible, onCheckedChange = { vm.setEventCalendarVisible(c, it) })
                    }
                }
                TextButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New calendar") }
            }
        },
    )
    if (adding) CalendarEditDialog(null, onDismiss = { adding = false }, onSave = { n, col -> vm.createEventCalendar(n, col); adding = false }, onDelete = null)
    renaming?.let { c ->
        CalendarEditDialog(c, onDismiss = { renaming = null }, onSave = { n, col -> vm.renameEventCalendar(c, n, col); renaming = null },
            onDelete = if (c.isDefault) null else ({ vm.deleteEventCalendar(c.id); renaming = null }))
    }
}

@Composable
private fun CalendarEditDialog(existing: EventCalendarEntity?, onDismiss: () -> Unit, onSave: (String, Long) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var color by remember { mutableStateOf(existing?.colorArgb ?: CAL_COLORS.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim(), color) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        title = { Text(if (existing == null) "New calendar" else "Edit calendar") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(12.dp))
                ColorRow(color) { color = it }
            }
        },
    )
}

@Composable
private fun ColorRow(selected: Long, onPick: (Long) -> Unit) {
    // R58 — unified colour picker (rich palette + recents + custom HSV/hex).
    com.todocompanion.app.ui.components.AppColorPicker(current = selected, onPick = { onPick(it ?: selected) })
}

// ── Gap finder ──────────────────────────────────────────────────────────────────────────────────
@Composable
internal fun GapFinder(events: List<EventEntity>, day: Long, zone: ZoneId, workStart: Int, workEnd: Int, onDismiss: () -> Unit, tasks: List<TaskEntity> = emptyList(), onPick: (Long, Long) -> Unit) {
    var dur by remember { mutableStateOf(60) }
    val hm = DateTimeFormatter.ofPattern("h:mm a")
    // R60 — scheduled tasks block the day too, so a "gap" never lands on top of an already-timed task.
    val busy = remember(events, tasks, day) {
        CalendarEngine.onDay(events, day, zone).filter { it.event.busy && !it.event.allDay }.map { it.startMillis to it.endMillis } +
            com.todocompanion.app.domain.calendar.Availability.taskBusyIntervals(tasks, zone, events.mapNotNull { it.linkedTaskId }.toSet())
    }
    val slots = remember(busy, dur) { CalendarEngine.freeSlots(busy, day, workStart, workEnd.coerceAtLeast(workStart + 1), dur, zone) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Find a gap · ${LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("EEE, MMM d"))}") },
        text = {
            Column {
                Text("Need", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    listOf(30, 60, 90, 120).forEach { m ->
                        FilterChip(selected = dur == m, onClick = { dur = m }, label = { Text(fmtDur(m)) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (slots.isEmpty()) Text("No free ${fmtDur(dur)} block in your working hours today.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else slots.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(s.startMillis, s.startMillis + dur * 60000L) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("${Instant.ofEpochMilli(s.startMillis).atZone(zone).format(hm)} – ${Instant.ofEpochMilli(s.endMillis).atZone(zone).format(hm)}", Modifier.weight(1f))
                        Text("${s.minutes}m free", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    )
}

// ── Event editor ──────────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun EventEditor(
    vm: AppViewModel, zone: ZoneId, calendars: List<EventCalendarEntity>, existing: EventEntity?,
    seedStart: Long, seedEnd: Long, onClose: () -> Unit,
) {
    val defaultCal = calendars.firstOrNull { it.isDefault } ?: calendars.firstOrNull()
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var calId by remember { mutableStateOf(existing?.calendarId ?: defaultCal?.id ?: "") }
    var allDay by remember { mutableStateOf(existing?.allDay ?: false) }
    var start by remember { mutableStateOf(existing?.startMillis ?: seedStart) }
    var end by remember { mutableStateOf(existing?.endMillis ?: seedEnd) }
    var location by remember { mutableStateOf(existing?.location ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var organizer by remember { mutableStateOf(existing?.organizer ?: "") }
    var attendees by remember { mutableStateOf(existing?.attendees ?: "") }
    var rsvp by remember { mutableStateOf(existing?.rsvp ?: "") }
    var busy by remember { mutableStateOf(existing?.busy ?: true) }
    var rrule by remember { mutableStateOf(existing?.rrule ?: "") }
    // R59 (Wave 1) — a per-event colour override (null = inherit the calendar's colour), via the unified picker.
    var color by remember { mutableStateOf(existing?.colorArgb) }
    var alerts by remember { mutableStateOf((existing?.alertsMinutes ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()) }
    var showStart by remember { mutableStateOf(false) }
    var showEnd by remember { mutableStateOf(false) }
    var calMenu by remember { mutableStateOf(false) }
    var repeatMenu by remember { mutableStateOf(false) }
    var scopeDelete by remember { mutableStateOf(false) }
    var paintOpen by remember { mutableStateOf(false) }
    val isRecurring = existing != null && existing.rrule.isNotBlank()

    // R41 — templates, remembered travel time, and a pinned secondary time-zone.
    val templates by vm.eventTemplates.collectAsState()
    val travelMap by vm.travelTimes.collectAsState()
    val settings by vm.settings.collectAsState()
    var travelOn by remember { mutableStateOf(false) }
    var travelMin by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(location) {
        com.todocompanion.app.domain.calendar.TravelTimes.forPlace(travelMap, location)?.let { if (travelMin == 0) travelMin = it }
    }
    val secZone = settings.secondaryZoneId.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    val dfDate = DateTimeFormatter.ofPattern("EEE, MMM d")
    val dfTime = DateTimeFormatter.ofPattern("h:mm a")
    val cal = calendars.firstOrNull { it.id == calId }

    // R42 — the event editor is now a ModalBottomSheet built from the app's own components (AppTextField,
    // AppCard, the shared rows) so it matches the task editor and quick-add rather than a bare AlertDialog.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var actionMenu by remember { mutableStateOf(false) }
    fun persist() {
        var s = start; var e = end
        if (allDay) {
            val d = Instant.ofEpochMilli(s).atZone(zone).toLocalDate()
            s = d.atStartOfDay(zone).toInstant().toEpochMilli()
            e = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        } else if (e <= s) e = s + 3_600_000L
        vm.saveEvent(existing?.id, calId, title, location, notes, url, s, e, allDay, rrule,
            alerts.sorted().joinToString(","), color, busy = busy,
            organizer = organizer, attendees = attendees, rsvp = rsvp)
        if (travelOn && travelMin > 0 && !allDay) vm.addTravelBuffer(s, travelMin, location, calId)
        onClose()
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            // Header: title, overflow (edit only), Save.
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (existing == null) "New event" else "Edit event", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                if (existing != null) {
                    Box {
                        IconButton(onClick = { actionMenu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = actionMenu, onDismissRequest = { actionMenu = false }) {
                            DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) },
                                onClick = { actionMenu = false; vm.duplicateEvent(existing.id); onClose() })
                            DropdownMenuItem(text = { Text("Copy to dates…") }, leadingIcon = { Icon(Icons.Filled.CalendarMonth, null, Modifier.size(18.dp)) },
                                onClick = { actionMenu = false; paintOpen = true })
                            DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                                onClick = { actionMenu = false; if (isRecurring) scopeDelete = true else { vm.deleteEvent(existing.id, "series"); onClose() } })
                        }
                    }
                }
                Button(onClick = { persist() }, enabled = title.isNotBlank() && calId.isNotBlank()) { Text("Save") }
            }
            AppTextField(value = title, onValueChange = { title = it }, placeholder = { Text("Event title") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.titleMedium)
            // Start from a template (new events only): fills title, duration, colour, alerts.
            if (existing == null && templates.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("From a template", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    templates.forEach { t ->
                        FilterChip(selected = false, onClick = {
                            title = t.title
                            if (t.calendarId.isNotBlank()) calId = t.calendarId
                            end = start + t.durationMin.coerceAtLeast(5) * 60000L
                            busy = t.busy
                            if (t.location.isNotBlank()) location = t.location
                            alerts = t.alertsMinutes.split(",").mapNotNull { m -> m.trim().toIntOrNull() }.toSet()
                        }, label = { Text("${t.emoji} ${t.title}") })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            AppCard {
                Box {
                    EditorRow(Icons.Filled.CalendarMonth, "Calendar", cal?.name ?: "—", accent = cal?.let { Color(it.colorArgb) }) { calMenu = true }
                    DropdownMenu(expanded = calMenu, onDismissRequest = { calMenu = false }) {
                        calendars.forEach { c ->
                            DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(c.colorArgb))); Spacer(Modifier.width(8.dp)); Text(c.name) } }, onClick = { calId = c.id; calMenu = false })
                        }
                    }
                }
                EditorToggle("All-day", allDay) { allDay = it }
                EditorRow(Icons.Filled.Schedule, "Starts", if (allDay) Instant.ofEpochMilli(start).atZone(zone).format(dfDate) else "${Instant.ofEpochMilli(start).atZone(zone).format(dfDate)}  ${Instant.ofEpochMilli(start).atZone(zone).format(dfTime)}") { showStart = true }
                if (!allDay) EditorRow(Icons.Filled.Schedule, "Ends", "${Instant.ofEpochMilli(end).atZone(zone).format(dfDate)}  ${Instant.ofEpochMilli(end).atZone(zone).format(dfTime)}") { showEnd = true }
                if (secZone != null && !allDay) {
                    val zLabel = secZone.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                    Text("$zLabel · ${Instant.ofEpochMilli(start).atZone(secZone).format(dfTime)} – ${Instant.ofEpochMilli(end).atZone(secZone).format(dfTime)}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 32.dp, bottom = 4.dp))
                }
                Box {
                    EditorRow(Icons.Filled.Repeat, "Repeat", repeatLabelOf(rrule)) { repeatMenu = true }
                    DropdownMenu(expanded = repeatMenu, onDismissRequest = { repeatMenu = false }) {
                        listOf("" to "Does not repeat", "d" to "Daily", "wd" to "Weekdays", "w" to "Weekly", "m" to "Monthly", "y" to "Yearly").forEach { (k, l) ->
                            DropdownMenuItem(text = { Text(l) }, onClick = { rrule = encodeRepeat(k); repeatMenu = false })
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            AppCard {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Alarm, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp)); Text("Alerts", style = MaterialTheme.typography.bodyLarge)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ALERT_CHOICES.forEach { (m, l) ->
                        FilterChip(selected = m in alerts, onClick = { alerts = if (m in alerts) alerts - m else alerts + m }, label = { Text(l) })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // R59 (Wave 1) — a per-event colour, overriding the calendar's colour just for this event.
            AppCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Colour", style = MaterialTheme.typography.bodyLarge)
                        Text(if (color == null) "Using ${cal?.name ?: "calendar"} colour" else "Custom colour",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    com.todocompanion.app.ui.components.AppColorPicker(current = color ?: cal?.colorArgb, onPick = { color = it }, allowNone = true)
                }
            }
            Spacer(Modifier.height(10.dp))
            AppTextField(value = location, onValueChange = { location = it }, placeholder = { Text("Location") },
                leadingIcon = { Icon(Icons.Filled.LocationOn, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (location.isNotBlank() && !allDay) {
                EditorToggle("Add travel buffer", travelOn) { travelOn = it; if (it && travelMin == 0) travelMin = 15 }
                if (travelOn) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(10, 15, 20, 30, 45, 60).forEach { m ->
                            FilterChip(selected = travelMin == m, onClick = { travelMin = m }, label = { Text("${m}m") })
                        }
                    }
                    Text("Reserves ${travelMin}m before the event and remembers it for “${location.trim()}”.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            AppTextField(value = notes, onValueChange = { notes = it }, placeholder = { Text("Notes") },
                leadingIcon = { Icon(Icons.Filled.Notes, null) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            AppTextField(value = url, onValueChange = { url = it }, placeholder = { Text("Link (URL)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            // R52 — invitations. Paste a Teams/Meet/Zoom invite (or its link) and it becomes a
            // joinable, RSVP-able meeting in your own calendar. All offline — no accounts, no sync.
            run {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val joinUrl = url.trim().ifBlank { com.todocompanion.app.domain.calendar.EventIcs.detectUrl(notes) }
                    .ifBlank { com.todocompanion.app.domain.calendar.EventIcs.detectUrl(location) }
                val provider = com.todocompanion.app.domain.calendar.MeetingLink.provider(joinUrl)
                var inviteOpen by remember { mutableStateOf(existing?.let { it.organizer.isNotBlank() || it.attendees.isNotBlank() || it.rsvp.isNotBlank() } ?: false) }
                AppCard {
                    Row(Modifier.fillMaxWidth().clickable { inviteOpen = !inviteOpen }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Groups, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Meeting / invitation", style = MaterialTheme.typography.bodyLarge)
                            if (provider != null) Text("${com.todocompanion.app.domain.calendar.MeetingLink.emoji(provider)} $provider link detected",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Icon(if (inviteOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (joinUrl.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = {
                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(joinUrl)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.VideoCall, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(if (provider != null) "Join ${com.todocompanion.app.domain.calendar.MeetingLink.emoji(provider)} $provider" else "Join meeting")
                        }
                    }
                    if (inviteOpen) {
                        Spacer(Modifier.height(8.dp))
                        AppTextField(value = organizer, onValueChange = { organizer = it }, placeholder = { Text("Organizer (name / email)") },
                            leadingIcon = { Icon(Icons.Filled.Person, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        AppTextField(value = attendees, onValueChange = { attendees = it }, placeholder = { Text("Attendees (comma-separated)") },
                            leadingIcon = { Icon(Icons.Filled.Group, null) }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("Your RSVP", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("ACCEPTED" to "✅ Going", "TENTATIVE" to "🤔 Maybe", "DECLINED" to "🚫 Not going").forEach { (k, l) ->
                                FilterChip(selected = rsvp == k, onClick = { rsvp = if (rsvp == k) "" else k }, label = { Text(l) })
                            }
                        }
                        // R53 — a fully-offline app can't email a reply, but it can hand you a METHOD:REPLY
                        // .ics to forward yourself. Available once the event exists and has an organizer.
                        if (existing != null && organizer.isNotBlank() && rsvp.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            TextButton(onClick = { vm.shareRsvpReply(existing.id) }) {
                                Icon(Icons.Filled.Send, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Send my RSVP (.ics)")
                            }
                        }
                        Text("Your RSVP stays on your device. “Send” exports a reply file you forward yourself — nothing leaves the app on its own.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            AppCard { EditorToggle("Shows as busy", busy) { busy = it } }
            if (title.isNotBlank()) {
                TextButton(onClick = {
                    val durMin = (((end - start) / 60000L)).toInt().coerceAtLeast(5)
                    vm.saveEventTemplate(com.todocompanion.app.domain.calendar.EventTemplate(
                        id = java.util.UUID.randomUUID().toString(), title = title.trim(), durationMin = durMin,
                        calendarId = calId, colorArgb = existing?.colorArgb, location = location.trim(),
                        alertsMinutes = alerts.sorted().joinToString(","), busy = busy))
                }) { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Save as a template") }
            }
        }
    }

    if (paintOpen && existing != null) {
        val srcDay = Instant.ofEpochMilli(existing.startMillis).atZone(zone).toLocalDate()
        AlertDialog(
            onDismissRequest = { paintOpen = false },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { paintOpen = false }) { Text("Cancel") } },
            title = { Text("Copy “${existing.title}” to…") },
            text = {
                Column {
                    fun paint(days: List<Long>) { vm.paintEventToDates(existing.id, days); paintOpen = false; onClose() }
                    DropdownMenuItem(text = { Text("The next 7 days") }, onClick = { paint((1..7).map { srcDay.plusDays(it.toLong()).toEpochDay() }) })
                    DropdownMenuItem(text = { Text("Every weekday this week") }, onClick = {
                        val mon = srcDay.minusDays((srcDay.dayOfWeek.value - 1).toLong())
                        paint((0..4).map { mon.plusDays(it.toLong()).toEpochDay() }.filter { it != srcDay.toEpochDay() })
                    })
                    DropdownMenuItem(text = { Text("Mon · Wed · Fri (this week)") }, onClick = {
                        val mon = srcDay.minusDays((srcDay.dayOfWeek.value - 1).toLong())
                        paint(listOf(0L, 2L, 4L).map { mon.plusDays(it).toEpochDay() }.filter { it != srcDay.toEpochDay() })
                    })
                    DropdownMenuItem(text = { Text("The next 4 weeks (same weekday)") }, onClick = { paint((1..4).map { srcDay.plusWeeks(it.toLong()).toEpochDay() }) })
                }
            },
        )
    }
    if (showStart) DateTimePickerDialog(initial = start, onDismiss = { showStart = false }) { picked ->
        val delta = end - start; start = picked; if (end <= start) end = start + (if (delta > 0) delta else 3_600_000L); showStart = false
    }
    if (showEnd) DateTimePickerDialog(initial = end, onDismiss = { showEnd = false }) { picked -> end = picked; showEnd = false }
    if (scopeDelete && existing != null) {
        val instDay = Instant.ofEpochMilli(existing.startMillis).atZone(zone).toLocalDate().toEpochDay()
        AlertDialog(
            onDismissRequest = { scopeDelete = false },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { scopeDelete = false }) { Text("Cancel") } },
            title = { Text("Delete repeating event") },
            text = {
                Column {
                    DropdownMenuItem(text = { Text("This event only") }, onClick = { vm.deleteEvent(existing.id, "this", instDay); scopeDelete = false; onClose() })
                    DropdownMenuItem(text = { Text("This and following") }, onClick = { vm.deleteEvent(existing.id, "following", instDay); scopeDelete = false; onClose() })
                    DropdownMenuItem(text = { Text("All events in the series") }, onClick = { vm.deleteEvent(existing.id, "series"); scopeDelete = false; onClose() })
                }
            },
        )
    }
}

@Composable
private fun EditorRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, accent: Color? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = accent ?: MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun EditorToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ── helpers ─────────────────────────────────────────────────────────────────────────────────────
internal fun colorOf(e: EventEntity, calById: Map<String, EventCalendarEntity>): Color =
    Color(e.colorArgb ?: calById[e.calendarId]?.colorArgb ?: 0xFF4F46E5)

private fun weekStartIso(weekStart: Int): Int =
    if (weekStart in 1..7) weekStart else java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek.value
private fun fmtDur(min: Int): String = when { min < 60 -> "${min}m"; min % 60 == 0 -> "${min / 60}h"; else -> "${min / 60}h ${min % 60}m" }

private fun repeatLabelOf(rrule: String): String {
    val r = Recurrence.parse(rrule) ?: return "Does not repeat"
    return when (r.freq) {
        Freq.DAILY -> "Daily"; Freq.WEEKDAYS -> "Weekdays"; Freq.WEEKLY -> "Weekly"; Freq.MONTHLY -> "Monthly"; Freq.YEARLY -> "Yearly"
    }
}
private fun encodeRepeat(k: String): String = when (k) {
    "d" -> Recurrence.encode(Recur(Freq.DAILY)); "wd" -> Recurrence.encode(Recur(Freq.WEEKDAYS))
    "w" -> Recurrence.encode(Recur(Freq.WEEKLY)); "m" -> Recurrence.encode(Recur(Freq.MONTHLY)); "y" -> Recurrence.encode(Recur(Freq.YEARLY)); else -> ""
}
