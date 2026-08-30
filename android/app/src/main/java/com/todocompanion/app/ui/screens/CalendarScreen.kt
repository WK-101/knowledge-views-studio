package com.todocompanion.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.CalendarViewMonth
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Repeat
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
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.unit.sp
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
    // R39 — event tools invoked from the shared header's events menu ("new"|"calendars"|"gap"|"import"
    // |"export"|"block"); the calendar owns the dialogs so events live inside this one calendar.
    eventAction: String? = null, onEventActionConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val s by vm.settings.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val zone = ZoneId.systemDefault()

    // R39 — dedicated-calendar EVENTS folded into this one calendar (no separate calendar screen).
    val eventsAll by vm.events.collectAsState()
    val eventCals by vm.eventCalendars.collectAsState()
    val visEventCalIds = remember(eventCals) { eventCals.filter { it.visible }.map { it.id }.toSet() }
    val eventCalById = remember(eventCals) { eventCals.associateBy { it.id } }
    val visEvents = remember(eventsAll, visEventCalIds) { eventsAll.filter { it.calendarId in visEventCalIds } }
    val eventOccForDay: (LocalDate) -> List<com.todocompanion.app.domain.calendar.CalendarEngine.Occurrence> = { d ->
        com.todocompanion.app.domain.calendar.CalendarEngine.onDay(visEvents, d.toEpochDay(), zone)
    }
    val eventBlocksFor: (LocalDate) -> List<EventBlock> = { d ->
        eventOccForDay(d).filter { !it.event.allDay }.map { o ->
            val st = Instant.ofEpochMilli(o.startMillis).atZone(zone)
            val startMin = (st.hour * 60 + st.minute).coerceIn(0, 1439)
            val durMin = ((o.endMillis - o.startMillis) / 60000L).toInt().coerceIn(15, 1440 - startMin)
            EventBlock(o.event.id, o.event.title, colorOf(o.event, eventCalById), startMin, durMin)
        }
    }
    // Event editor / management dialog state (reuses the R38 dialogs, now folded into this calendar).
    var eventEditing by remember { mutableStateOf<com.todocompanion.app.data.entity.EventEntity?>(null) }
    var eventEditorOpen by remember { mutableStateOf(false) }
    var eventSeedStart by remember { mutableStateOf(0L) }
    var eventSeedEnd by remember { mutableStateOf(0L) }
    var eventCalsOpen by remember { mutableStateOf(false) }
    var eventGapOpen by remember { mutableStateOf(false) }
    var eventBlockOpen by remember { mutableStateOf(false) }
    var plannerOpen by remember { mutableStateOf(false) }
    var plannerTab by remember { mutableStateOf(0) }
    val openEvent: (String) -> Unit = { id -> eventEditing = eventsAll.firstOrNull { e -> e.id == id }; if (eventEditing != null) eventEditorOpen = true }
    // R43 — robust import via the layered chain (OPEN_DOCUMENT → GET_CONTENT → chooser), registered
    // at the top level, real error surfaced. See util/SystemPickers.kt.
    val eventImport = com.todocompanion.app.util.rememberFilePicker(onError = { vm.toastMsg(it) }) { uris -> uris.firstOrNull()?.let { vm.importIcsEvents(it) } }
    val eventExport = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/calendar")) { uri -> if (uri != null) vm.exportIcsEventsTo(uri) }

    val firstDow = if (s.weekStart in 1..7) DayOfWeek.of(s.weekStart) else WeekFields.of(Locale.getDefault()).firstDayOfWeek
    // The calendar filter set holds list IDs AND folder IDs now (R23): a task matches if its list, its
    // direct folder, or the folder its list lives in is selected. Empty = show everything.
    val calLists by vm.lists.collectAsState()
    val listFilter = s.calendarListFilter
    val listFolderById = remember(calLists) { calLists.associate { it.id to it.folderId } }
    val showCompleted = s.calendarShowCompleted
    val dueByDate = remember(tasks, listFilter, listFolderById, showCompleted) {
        tasks.filter {
            // R27 #6: completed tasks are hidden by default (the grid stays a plan of what's left);
            // the "Show completed" toggle folds them back in so the day is a full record.
            !it.trashed && (showCompleted || !it.completed) && !it.abandoned && it.dueDate != null &&
                (listFilter.isEmpty() || it.listId in listFilter || it.folderId in listFilter || listFolderById[it.listId] in listFilter)
        }.groupBy { Instant.ofEpochMilli(it.dueDate!!).atZone(zone).toLocalDate() }
    }

    // Countdowns land on the calendar at their target date — a dot in the month grid and a chip under
    // the selected day, so a countdown you set is visible where you'd look for a dated event.
    val countdowns by vm.countdowns.collectAsState()
    val countdownsFor: (LocalDate) -> List<com.todocompanion.app.data.entity.CountdownEntity> = { d ->
        // R43 — occasions land on their NEXT occurrence (a yearly birthday shows on this year's date).
        countdowns.filter { com.todocompanion.app.domain.LifeEvent.nextOccurrence(it, d) == d }
    }

    // M1: optionally draw timed habits as read-only blocks in the day/week grid. Opt-in (default off).
    val habits by vm.habits.collectAsState()
    val habitCheckins by vm.habitCheckins.collectAsState()
    val todayEd = LocalDate.now(zone).toEpochDay()
    val habitBlocksFor: (LocalDate) -> List<HabitBlock> = block@{ d ->
        if (!s.habitCalendarBlocks) return@block emptyList()
        val hs = com.todocompanion.app.domain.habit.HabitStats
        val ed = d.toEpochDay()
        habits.filter { !it.archived && !it.paused && it.habitType != "break" }.flatMap { h ->
            val scheduled = hs.isExpectedDay(h, ed) || h.freqType == hs.FREQ_TIMES_WEEK || h.freqType == hs.FREQ_TIMES_MONTH
            val rawTimes = h.reminderTimes.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..1439 }
            // A habit only carries a real time if it has a reminder time; otherwise it's untimed and must
            // NOT be pinned to a fake 09:00 on the grid (R23 — that's why they all overlapped).
            val untimed = rawTimes.isEmpty()
            val checkin = habitCheckins.firstOrNull { it.habitId == h.id && it.epochDay == ed }
            val done = checkin != null && checkin.status == "done" && hs.meetsGoal(h, checkin.count)
            val progressed = checkin != null && (checkin.status == "done" || checkin.count > 0)
            // On PAST days, only show habits that were actually done (full or partial) — a scheduled-but-
            // skipped habit shouldn't clutter history (R23). Today/future show every scheduled habit.
            val show = scheduled && (ed >= todayEd || progressed)
            if (!show) emptyList()
            else {
                val dur = if (h.unit == "min") h.targetPerDay.coerceIn(10, 180) else 30
                val col = h.colorArgb?.let { androidx.compose.ui.graphics.Color(it) }
                rawTimes.ifEmpty { listOf(0) }.map { m ->
                    HabitBlock(h.id, (h.emoji?.plus(" ") ?: "") + h.name, col, m, dur, done, progressed && !done, untimed)
                }
            }
        }
    }

    // Round 14: the "actual" spine — tracked time intervals drawn as a thin read-only rail beside the
    // planned task/habit blocks (planned vs actual), gated by the Time module being on.
    val timeEntries by vm.timeEntries.collectAsState()
    val timeActivities by vm.timeActivities.collectAsState()
    val timeOn = com.todocompanion.app.domain.Modules.isEnabled(s, com.todocompanion.app.domain.Modules.TIME)
    // Precompute per-day tracked blocks ONCE (epochDay → blocks) instead of scanning every entry per
    // calendar cell / day column — the month grid and pinch-zoom were O(cells × entries) before (audit #4/#5).
    val trackedByDay: Map<Long, List<TrackedBlock>> = remember(timeEntries, timeActivities, timeOn) {
        if (!timeOn) emptyMap() else {
            val now = System.currentTimeMillis()
            val colorOf = timeActivities.associate { it.id to it.colorArgb }
            val map = HashMap<Long, MutableList<TrackedBlock>>()
            timeEntries.forEach { e ->
                val end = e.endMillis ?: now
                if (end <= e.startMillis) return@forEach
                val col = colorOf[e.activityId]?.let { androidx.compose.ui.graphics.Color(it) }
                var d = Instant.ofEpochMilli(e.startMillis).atZone(zone).toLocalDate()
                val lastD = Instant.ofEpochMilli(end - 1).atZone(zone).toLocalDate()
                var guard = 0
                while (!d.isAfter(lastD) && guard < 400) {
                    guard++
                    val dayStart = d.atStartOfDay(zone).toInstant().toEpochMilli()
                    val dayEnd = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val lo = maxOf(e.startMillis, dayStart); val hi = minOf(end, dayEnd)
                    if (hi > lo) {
                        val startMin = ((lo - dayStart) / 60000L).toInt().coerceIn(0, 1439)
                        val durMin = ((hi - lo) / 60000L).toInt().coerceAtLeast(1)
                        map.getOrPut(d.toEpochDay()) { ArrayList() }.add(TrackedBlock(startMin, durMin, col, e.id))
                    }
                    d = d.plusDays(1)
                }
            }
            map
        }
    }
    val trackedBlocksFor: (LocalDate) -> List<TrackedBlock> = { d -> trackedByDay[d.toEpochDay()] ?: emptyList() }
    // Month view: how much was tracked on a day + the day's dominant activity colour, as a thin bar.
    val trackedDayInfo: (LocalDate) -> Pair<Int, androidx.compose.ui.graphics.Color?> = info@{ d ->
        val blocks = trackedByDay[d.toEpochDay()] ?: return@info 0 to null
        val total = blocks.sumOf { it.durMin }
        val dom = blocks.groupBy { it.color }.maxByOrNull { grp -> grp.value.sumOf { it.durMin } }?.key
        total to dom
    }
    // U14: shade the rail's untracked gaps when the setting is on and Time is enabled.
    val revealUntrackedFlag = s.untrackedReveal && com.todocompanion.app.domain.Modules.isEnabled(s, com.todocompanion.app.domain.Modules.TIME)
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
            SwipeAction.MOVE -> onOpenTask(t.id)   // move picker lives in the task; open it here
            SwipeAction.NONE -> {}
        }
    }
    val prev = { onAnchor(calStep(mode, anchor, -1)) }
    val next = { onAnchor(calStep(mode, anchor, 1)) }
    // Hoisted above AnimatedContent so collapsing the month to a week survives month navigation (R23):
    // otherwise a cross-month week-swipe re-created MonthView and reset it back to the full grid.
    var monthCollapsed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    // The combined header lives in the app-bar slot (see AppRoot), so switching tabs never shifts
    // the content and the buttons line up with every other screen.
    var editTrackedId by remember { mutableStateOf<String?>(null) }   // a tracked interval tapped in the calendar
    Column(modifier.fillMaxSize()) {
        // Smooth transitions when moving between periods (swipe) and between modes (R19 #8): slide +
        // fade in the swipe direction, matching the calm feel of the month collapse. Honours reduce-motion.
        AnimatedContent(
            targetState = mode to anchor,
            transitionSpec = {
                if (s.reduceMotion || initialState.first != targetState.first && initialState.second == targetState.second) {
                    (fadeIn(tween(180)) togetherWith fadeOut(tween(150))) using SizeTransform(clip = false)
                } else {
                    val dir = if (targetState.second >= initialState.second) 1 else -1
                    (slideInHorizontally(tween(260)) { w -> dir * w } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(260)) { w -> -dir * w } + fadeOut(tween(180))) using SizeTransform(clip = false)
                }
            },
            label = "calNav",
        ) { (mode, anchor) ->
        when (mode) {
            "month" -> MonthView(anchor, selected, dueByDate, firstDow, onSelect = { onSelected(it) }, onPrev = prev, onNext = next, onOpenTask = onOpenTask, swipe = swipe, onAdd = { onAddOnDate(selected) },
                collapsed = monthCollapsed, onCollapsedChange = { monthCollapsed = it },
                habitBlocksFor = habitBlocksFor, onOpenHabit = onOpenHabit, countdownsFor = countdownsFor, trackedDayInfo = trackedDayInfo,
                eventOccForDay = eventOccForDay, onOpenEvent = openEvent, lunar = s.lunarOverlay,
                onMoveToDay = { d, id ->
                    // Preserve the task's time-of-day when dropping it on another day; default 9am.
                    val min = tasks.firstOrNull { it.id == id }?.dueDate?.let { Instant.ofEpochMilli(it).atZone(zone).let { z -> z.hour * 60 + z.minute } } ?: 540
                    vm.rescheduleToMinute(id, d, min)
                })
            "week" -> {
                val start = startOfWeek(anchor, firstDow)
                TimelineView((0..6).map { start.plusDays(it.toLong()) }, dueByDate, zone, onPrev = prev, onNext = next, onOpenTask = onOpenTask, onAddOnDate = onAddOnDate, onAddAt = onAddAt, onResize = onResize, onMoveAt = onMoveTaskTo, habitBlocksFor = habitBlocksFor, onOpenHabit = onOpenHabit, trackedBlocksFor = trackedBlocksFor, revealUntracked = revealUntrackedFlag, onOpenTracked = { editTrackedId = it }, eventBlocksFor = eventBlocksFor, onOpenEvent = openEvent)
            }
            "weekly" -> WeeklyView(startOfWeek(anchor, firstDow), dueByDate, onPrev = prev, onNext = next, onOpenTask = onOpenTask, onAddOnDate = onAddOnDate)
            "3day" -> TimelineView((0..2).map { anchor.plusDays(it.toLong()) }, dueByDate, zone, onPrev = prev, onNext = next, onOpenTask = onOpenTask, onAddOnDate = onAddOnDate, onAddAt = onAddAt, onResize = onResize, onMoveAt = onMoveTaskTo, habitBlocksFor = habitBlocksFor, onOpenHabit = onOpenHabit, trackedBlocksFor = trackedBlocksFor, revealUntracked = revealUntrackedFlag, onOpenTracked = { editTrackedId = it }, eventBlocksFor = eventBlocksFor, onOpenEvent = openEvent)
            "day" -> TimelineView(listOf(anchor), dueByDate, zone, onPrev = prev, onNext = next, onOpenTask = onOpenTask, onAddOnDate = onAddOnDate, onAddAt = onAddAt, onResize = onResize, onMoveAt = onMoveTaskTo, habitBlocksFor = habitBlocksFor, onOpenHabit = onOpenHabit, trackedBlocksFor = trackedBlocksFor, revealUntracked = revealUntrackedFlag, onOpenTracked = { editTrackedId = it }, eventBlocksFor = eventBlocksFor, onOpenEvent = openEvent)
            "year" -> YearView(anchor, dueByDate, onPrev = prev, onNext = next, onMonth = { m -> onAnchor(m.atDay(1)); onModeChange("month") }, onDay = { d -> onAnchor(d); onModeChange("day") })
            else -> AgendaView(dueByDate, onOpenTask, swipe)
        }
        }
    }
    // Tapping a tracked block opens the shared time-entry editor — adjust times, reassign, split, delete.
    editTrackedId?.let { id ->
        val entry = timeEntries.firstOrNull { it.id == id }
        if (entry != null) EditEntryDialog(entry, timeActivities.filter { !it.archived }, zone,
            onDismiss = { editTrackedId = null },
            onDelete = { vm.deleteTimeEntry(id); editTrackedId = null },
            onSplit = { at -> vm.splitTimeEntry(id, at); editTrackedId = null },
            onSave = { vm.updateTimeEntry(it); editTrackedId = null })
        else editTrackedId = null
    }

    // R39 — ensure there's a colour-coded calendar to hang events on the first time this screen opens,
    // so "New event" can always save (a fresh install has no event calendars yet).
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.ensureEventCalendar() }
    // R39 — the shared header's events menu routes its choice here; the calendar owns every event dialog.
    androidx.compose.runtime.LaunchedEffect(eventAction) {
        when (eventAction) {
            "new" -> {
                eventEditing = null
                val s0 = selected.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
                eventSeedStart = s0; eventSeedEnd = s0 + 3_600_000L; eventEditorOpen = true
            }
            "calendars" -> eventCalsOpen = true
            "gap" -> eventGapOpen = true
            "block" -> eventBlockOpen = true
            "plan" -> { plannerTab = 0; plannerOpen = true }
            "review" -> { plannerTab = 1; plannerOpen = true }
            "import" -> eventImport(arrayOf("text/calendar", "application/octet-stream", "*/*"))
            // Export: try the system file-saver; if the device has no DocumentsUI, fall back to Downloads.
            "export" -> runCatching { eventExport.launch("todocompanion-calendar.ics") }
                .onFailure { vm.exportIcsEventsToDownloads() }
        }
        if (eventAction != null) onEventActionConsumed()
    }
    if (plannerOpen) PlannerSheet(vm, zone, selected.toEpochDay(), plannerTab) { plannerOpen = false }
    if (eventEditorOpen) EventEditor(vm, zone, eventCals, eventEditing, eventSeedStart, eventSeedEnd) { eventEditorOpen = false; eventEditing = null }
    if (eventCalsOpen) CalendarsManager(vm, eventCals) { eventCalsOpen = false }
    if (eventGapOpen) GapFinder(visEvents, selected.toEpochDay(), zone, s.workStartHour, s.workEndHour,
        onDismiss = { eventGapOpen = false },
        onPick = { st, en -> eventGapOpen = false; eventEditing = null; eventSeedStart = st; eventSeedEnd = en; eventEditorOpen = true })
    if (eventBlockOpen) BlockTaskDialog(vm, selected.toEpochDay(), zone, s.workStartHour, visEvents) { eventBlockOpen = false }
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
    showCompleted: Boolean = false, onToggleShowCompleted: () -> Unit = {},
    onEventAction: (String) -> Unit = {},
) {
    var showPicker by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    var eventMenu by remember { mutableStateOf(false) }
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
            // Match the title size of every other view's top bar (Habits/Matrix/Time all use the
            // TopAppBar default, titleLarge) so the calendar's period label reads at the same weight.
            Text(label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                androidx.compose.material3.HorizontalDivider()
                // R27 #6: fold completed tasks into the grid on demand (off keeps it a plan of what's left).
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Show completed", color = if (showCompleted) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current) },
                    leadingIcon = { Icon(if (showCompleted) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, null, tint = if (showCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { onToggleShowCompleted(); typeMenu = false },
                )
            }
        }
        // R39 — events live in this one calendar now: add an event, manage calendars, find a gap, block a
        // task, or import/export .ics — all from here, no separate calendar screen.
        Box {
            // R41 — a clearly different glyph from the "Today" calendar icon beside it (they read alike).
            IconButton(onClick = { eventMenu = true }) { Icon(Icons.Filled.EditCalendar, "Events") }
            androidx.compose.material3.DropdownMenu(expanded = eventMenu, onDismissRequest = { eventMenu = false }) {
                androidx.compose.material3.DropdownMenuItem(text = { Text("New event") }, leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) }, onClick = { eventMenu = false; onEventAction("new") })
                androidx.compose.material3.DropdownMenuItem(text = { Text("Plan my day") }, leadingIcon = { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)) }, onClick = { eventMenu = false; onEventAction("plan") })
                androidx.compose.material3.DropdownMenuItem(text = { Text("Weekly review") }, leadingIcon = { Icon(Icons.Filled.Insights, null, Modifier.size(18.dp)) }, onClick = { eventMenu = false; onEventAction("review") })
                androidx.compose.material3.DropdownMenuItem(text = { Text("Block time for a task…") }, onClick = { eventMenu = false; onEventAction("block") })
                androidx.compose.material3.DropdownMenuItem(text = { Text("Find a gap…") }, onClick = { eventMenu = false; onEventAction("gap") })
                androidx.compose.material3.DropdownMenuItem(text = { Text("Calendars…") }, onClick = { eventMenu = false; onEventAction("calendars") })
                androidx.compose.material3.HorizontalDivider()
                androidx.compose.material3.DropdownMenuItem(text = { Text("Import .ics") }, onClick = { eventMenu = false; onEventAction("import") })
                androidx.compose.material3.DropdownMenuItem(text = { Text("Export .ics") }, onClick = { eventMenu = false; onEventAction("export") })
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
private fun MonthView(anchor: LocalDate, selected: LocalDate, dueByDate: Map<LocalDate, List<TaskEntity>>, firstDow: DayOfWeek, onSelect: (LocalDate) -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onOpenTask: (String) -> Unit, swipe: CalSwipe, onAdd: () -> Unit, collapsed: Boolean, onCollapsedChange: (Boolean) -> Unit, habitBlocksFor: (LocalDate) -> List<HabitBlock>, onOpenHabit: (String) -> Unit, countdownsFor: (LocalDate) -> List<com.todocompanion.app.data.entity.CountdownEntity>, trackedDayInfo: (LocalDate) -> Pair<Int, androidx.compose.ui.graphics.Color?> = { 0 to null }, eventOccForDay: (LocalDate) -> List<com.todocompanion.app.domain.calendar.CalendarEngine.Occurrence> = { emptyList() }, onOpenEvent: (String) -> Unit = {}, lunar: Boolean = false, onMoveToDay: (LocalDate, String) -> Unit) {
    val ym = YearMonth.from(anchor)
    val labels = (0..6).map { firstDow.plus(it.toLong()) }
    val first = ym.atDay(1)
    val leading = (first.dayOfWeek.value - firstDow.value + 7) % 7
    // A full grid of real dates: the leading/trailing cells are the adjacent months' days, drawn faded
    // (rather than blank), so a month that starts mid-week reads as a proper calendar (TickTick/Google style).
    val firstCell = first.minusDays(leading.toLong())
    val rowCount = (leading + ym.lengthOfMonth() + 6) / 7
    val cells: List<LocalDate> = remember(anchor, firstDow) { (0 until rowCount * 7).map { firstCell.plusDays(it.toLong()) } }
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
        // Collapsible month (R18): the whole grid is vertically draggable — pull up to shrink to just the
        // week holding the selected day (more room for the agenda), pull down to bring the month back. The
        // non-selected weeks animate open/closed so the transition is smooth, not an abrupt jump; there's
        // also a small chevron in the date row below. No separate handle strip stealing vertical space.
        val weeks = cells.chunked(7)
        val selWeek = weeks.indexOfFirst { wk -> wk.any { it == selected } }
            .let { if (it >= 0) it else weeks.indexOfFirst { wk -> wk.any { it == today } }.coerceAtLeast(0) }
            .coerceIn(0, weeks.lastIndex)
        // When collapsed to a single week, a horizontal swipe moves by WEEK (and stays collapsed); only the
        // full month grid swipes by month (R23). Crossing a month boundary also nudges the anchor so the
        // right week renders.
        val onPrevWeek = { val ns = selected.minusWeeks(1); if (YearMonth.from(ns) != ym) onPrev(); onSelect(ns) }
        val onNextWeek = { val ns = selected.plusWeeks(1); if (YearMonth.from(ns) != ym) onNext(); onSelect(ns) }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                .swipeNav(if (collapsed) onPrevWeek else onPrev, if (collapsed) onNextWeek else onNext)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dy -> if (dy < -2.5f) onCollapsedChange(true) else if (dy > 2.5f) onCollapsedChange(false) }
                },
        ) {
            weeks.forEachIndexed { wi, week ->
                androidx.compose.animation.AnimatedVisibility(visible = wi == selWeek || !collapsed) {
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { date ->
                            val isToday = date == today
                            val isSelected = date == selected
                            val isTarget = date == targetDay
                            val inMonth = date.month == anchor.month
                            // TickTick grammar: today is a solid filled circle; a selected non-today day gets a ring.
                            val ringMod = when {
                                isTarget -> Modifier.background(primary.copy(alpha = .28f), CircleShape).border(2.dp, primary, CircleShape)
                                isToday -> Modifier.background(primary, CircleShape)
                                isSelected -> Modifier.border(1.5.dp, primary, CircleShape)
                                else -> Modifier
                            }
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).padding(3.dp)
                                    .onGloballyPositioned { cellBounds[date] = it.boundsInWindow() }
                                    .clip(CircleShape).clickable { onSelect(date) }
                                    .then(ringMod),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            date.dayOfMonth.toString(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = when {
                                                isToday -> MaterialTheme.colorScheme.onPrimary
                                                !inMonth -> MaterialTheme.colorScheme.outline.copy(alpha = .55f)
                                                else -> MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                        // R42 — the local moon-phase overlay: a tiny glyph on the four principal phases.
                                        if (lunar && inMonth && com.todocompanion.app.domain.calendar.MoonPhase.isPrincipal(date.toEpochDay()))
                                            Text(com.todocompanion.app.domain.calendar.MoonPhase.glyph(date.toEpochDay()),
                                                style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, modifier = Modifier.padding(start = 1.dp))
                                    }
                                    Spacer(Modifier.size(2.dp))
                                    // A day can carry a task dot (primary) and/or a habit dot (tertiary). Adjacent-month
                                    // days stay clean (no dots) so the current month clearly stands out.
                                    val hasTask = inMonth && dueByDate.containsKey(date)
                                    val hasHabit = inMonth && habitBlocksFor(date).isNotEmpty()
                                    val hasCountdown = inMonth && countdownsFor(date).isNotEmpty()
                                    val hasEvent = inMonth && eventOccForDay(date).isNotEmpty()
                                    val eventDot = Color(0xFF7C3AED)
                                    if (hasTask || hasHabit || hasCountdown || hasEvent) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        if (hasEvent) Box(Modifier.size(5.dp).clip(CircleShape).background(if (isToday) MaterialTheme.colorScheme.onPrimary else eventDot))
                                        if (hasTask) Box(Modifier.size(5.dp).clip(CircleShape).background(if (isToday) MaterialTheme.colorScheme.onPrimary else primary))
                                        if (hasHabit) Box(Modifier.size(5.dp).clip(CircleShape).background(if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.tertiary))
                                        if (hasCountdown) Box(Modifier.size(5.dp).clip(CircleShape).background(if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary))
                                    } else Spacer(Modifier.size(5.dp))
                                    val (trkMin, trkColor) = if (inMonth) trackedDayInfo(date) else (0 to null)
                                    if (trkMin > 0) {
                                        val frac = (trkMin / 480f).coerceIn(0.12f, 1f)
                                        Box(Modifier.padding(top = 2.dp).fillMaxWidth(0.62f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                            Box(Modifier.fillMaxWidth(frac).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (isToday) MaterialTheme.colorScheme.onPrimary else (trkColor ?: MaterialTheme.colorScheme.tertiary)))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // The date row doubles as the collapse affordance — a small chevron toggles month↔week, so no
        // separate handle strip is needed (the grid itself is also drag-collapsible).
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selected == today) "TODAY" else "${selected.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()} ${selected.dayOfMonth} ${selected.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()}",
                Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.IconButton(onClick = { onCollapsedChange(!collapsed) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    if (collapsed) "Expand month" else "Collapse to week",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
                )
            }
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
                    // Done reads as a solid, saturated pill (filled = done); pending is a soft outline.
                    // No checkmark — the fill itself is the signal.
                    Row(
                        Modifier.clip(RoundedCornerShape(20.dp))
                            .background(if (hb.done) c else c.copy(alpha = .12f))
                            .then(if (hb.done) Modifier else Modifier.border(1.dp, c.copy(alpha = .45f), RoundedCornerShape(20.dp)))
                            .clickable { onOpenHabit(hb.id) }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (hb.done) Color.White else c))
                        Spacer(Modifier.size(7.dp))
                        Text(hb.label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                            fontWeight = if (hb.done) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (hb.done) Color.White else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        // Countdowns whose target lands on the selected day — a soft pill each, coloured like the countdown.
        val dayCountdowns = countdownsFor(selected)
        if (dayCountdowns.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                dayCountdowns.forEach { cd ->
                    val c = cd.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.secondary
                    Row(
                        Modifier.clip(RoundedCornerShape(20.dp)).background(c.copy(alpha = .14f))
                            .border(1.dp, c.copy(alpha = .45f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text((cd.emoji?.plus(" ") ?: "🎯 ") + cd.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        // R39 — calendar EVENTS on the selected day: a colour-coded pill each, tap to edit. Events live
        // in this one calendar now, right beside the day's tasks and habits.
        val dayEvents = eventOccForDay(selected)
        if (dayEvents.isNotEmpty()) {
            val hm = java.time.format.DateTimeFormatter.ofPattern("h:mm a")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dayEvents.forEach { o ->
                    val c = Color(o.event.colorArgb ?: 0xFF7C3AED)
                    Row(
                        Modifier.clip(RoundedCornerShape(20.dp)).background(c.copy(alpha = .14f))
                            .border(1.dp, c.copy(alpha = .45f), RoundedCornerShape(20.dp))
                            .clickable { onOpenEvent(o.event.id) }.padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(c)); Spacer(Modifier.size(7.dp))
                        val t = if (o.event.allDay) "" else Instant.ofEpochMilli(o.startMillis).atZone(java.time.ZoneId.systemDefault()).format(hm) + "  "
                        Text(t + o.event.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        val agenda = dueByDate[selected].orEmpty()
        if (agenda.isEmpty() && dayCountdowns.isEmpty() && dayEvents.isEmpty()) Text("Nothing due — enjoy the day", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (agenda.isEmpty()) Spacer(Modifier.height(4.dp))
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
        // R28 #1 — the calendar block is the task's scheduled span (durationMin). When you haven't set one,
        // fall back to your effort estimate so the two fields work together instead of both needing filling.
        val dur = (t.durationMin ?: t.estimateMin ?: 30).coerceAtLeast(20)
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TimelineView(
    days: List<LocalDate>, dueByDate: Map<LocalDate, List<TaskEntity>>, zone: ZoneId,
    onPrev: () -> Unit, onNext: () -> Unit, onOpenTask: (String) -> Unit, onAddOnDate: (LocalDate) -> Unit,
    onAddAt: (LocalDate, Int) -> Unit, onResize: (String, Int) -> Unit, onMoveAt: (LocalDate, String, Int) -> Unit,
    habitBlocksFor: (LocalDate) -> List<HabitBlock> = { emptyList() }, onOpenHabit: (String) -> Unit = {},
    trackedBlocksFor: (LocalDate) -> List<TrackedBlock> = { emptyList() },
    revealUntracked: Boolean = false, onOpenTracked: (String) -> Unit = {},
    eventBlocksFor: (LocalDate) -> List<EventBlock> = { emptyList() }, onOpenEvent: (String) -> Unit = {},
) {
    val allDayByDay = days.associateWith { d -> dueByDate[d].orEmpty().filter { it.isAllDay || !hasTime(it.dueDate!!, zone) } }
    val hasAllDay = allDayByDay.values.any { it.isNotEmpty() }
    val today = LocalDate.now()

    val scroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Pinch-to-zoom the day: two fingers scale the hour height, so the day can be stretched tall for a
    // detailed look or squeezed short for the whole-day overview — the smooth zoom Simple Time Tracker has.
    // canPan=false lets one-finger vertical scrolling keep working underneath the pinch.
    var hourZoom by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(1f) }
    val hourDp = HOUR_DP * hourZoom
    // Track the viewport height so a pinch zooms *around the middle of what you're looking at* rather than
    // pivoting at midnight (the top) — the polish that makes the zoom feel anchored and smooth (R17).
    var viewportPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    val zoomState = androidx.compose.foundation.gestures.rememberTransformableState { zoomChange, _, _ ->
        val old = hourZoom
        val next = (old * zoomChange).coerceIn(0.5f, 3.0f)
        hourZoom = next
        // Keep the hour under the viewport centre fixed: shift scroll by how much that point moved.
        if (viewportPx > 0 && next != old) {
            val centre = scroll.value + viewportPx / 2f
            scroll.dispatchRawDelta(centre * (next / old - 1f))
        }
    }
    androidx.compose.runtime.LaunchedEffect(days.firstOrNull()) {
        scroll.scrollTo(with(density) { (7 * hourDp).dp.toPx() }.toInt())
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
        // R27 #3: untimed habits (no reminder time) sit in a header band ABOVE the hour grid — not pinned to
        // a fake ~01:00 inside the timeline, where they used to stack and read as 1-o'clock events. Each day
        // column lists its own; the grid below then carries only timed habits at their real reminder time.
        val untimedByDay = days.associateWith { d -> habitBlocksFor(d).filter { it.untimed }.distinctBy { hb -> hb.id } }
        if (untimedByDay.values.any { it.isNotEmpty() }) {
            Row(Modifier.fillMaxWidth().padding(start = GUTTER_DP.dp).heightIn(max = 104.dp)) {
                days.forEach { d ->
                    Column(Modifier.weight(1f).padding(horizontal = 2.dp).verticalScroll(rememberScrollState())) {
                        untimedByDay[d].orEmpty().forEach { hb -> UntimedHabitChip(hb, onOpenHabit) }
                    }
                }
            }
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        }
        // Scrollable hour grid — pinch anywhere on it to zoom the hour height.
        Row(Modifier.fillMaxWidth().weight(1f)
            .onSizeChanged { viewportPx = it.height }
            .verticalScroll(scroll)
            .transformable(state = zoomState, canPan = { false })) {
            // Hour gutter
            Box(Modifier.width(GUTTER_DP.dp).height((hourDp * 24).dp)) {
                (1..23).forEach { h ->
                    Text("%02d:00".format(h), Modifier.offset(y = (hourDp * h - 7).dp).fillMaxWidth().padding(end = 6.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.End)
                }
            }
            days.forEach { d ->
                val timed = dueByDate[d].orEmpty().filter { !it.isAllDay && hasTime(it.dueDate!!, zone) }
                DayColumn(d, timed, zone, hourDp, onOpenTask, onAddAt, onResize, onMoveAt = { id, min -> onMoveAt(d, id, min) },
                    // Untimed habits render in the band above the grid (R27 #3); the grid gets only timed ones.
                    habitBlocks = habitBlocksFor(d).filter { !it.untimed }, onOpenHabit = onOpenHabit, trackedBlocks = trackedBlocksFor(d), revealUntracked = revealUntracked, onOpenTracked = onOpenTracked,
                    eventBlocks = eventBlocksFor(d), onOpenEvent = onOpenEvent, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DayColumn(day: LocalDate, timed: List<TaskEntity>, zone: ZoneId, hourDp: Float, onOpenTask: (String) -> Unit, onAddAt: (LocalDate, Int) -> Unit, onResize: (String, Int) -> Unit, onMoveAt: (String, Int) -> Unit,
    habitBlocks: List<HabitBlock> = emptyList(), onOpenHabit: (String) -> Unit = {}, trackedBlocks: List<TrackedBlock> = emptyList(), revealUntracked: Boolean = false, onOpenTracked: (String) -> Unit = {},
    eventBlocks: List<EventBlock> = emptyList(), onOpenEvent: (String) -> Unit = {}, modifier: Modifier) {
    val placed = remember(timed, zone) { layoutEvents(timed, zone) }
    val dens = LocalDensity.current
    val isToday = day == LocalDate.now()
    val nowMin = if (isToday) java.time.LocalTime.now().let { it.hour * 60 + it.minute } else -1
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.height((hourDp * 24).dp).pointerInput(day) {
            // Tap an empty slot to time-block a task at that half-hour.
            detectTapGestures { offset ->
                val minute = ((offset.y / size.height.toFloat()) * 1440f).toInt().coerceIn(0, 1439)
                onAddAt(day, (minute / 30) * 30)
            }
        },
    ) {
        val colW = maxWidth
        // Round 14 — the "actual" spine: a thin read-only rail on the far left showing tracked time.
        // When present it steals a few dp from the left so planned blocks sit just beside their actuals.
        // R17: the rail scales with the pinch zoom too, so zooming in fattens the tracked stripe alongside
        // the hour grid instead of leaving a hairline that's hard to read at big zoom.
        val railScale = (hourDp / HOUR_DP).coerceIn(0.85f, 2.8f)
        val railW = if (trackedBlocks.isEmpty() && !revealUntracked) 0.dp else (7.dp * railScale)
        val contentW = colW - railW
        // M1/R39: tasks keep the left; timed habits and dedicated-calendar EVENTS each take a lane on the
        // right so nothing collides and the task drag/resize math is unchanged. With both present the aux
        // strip splits in two (habits then events).
        val hasHabitLane = habitBlocks.isNotEmpty()
        val hasEventLane = eventBlocks.isNotEmpty()
        val auxCount = (if (hasHabitLane) 1 else 0) + (if (hasEventLane) 1 else 0)
        val taskAreaW = when (auxCount) { 0 -> contentW; 1 -> contentW * 0.6f; else -> contentW * 0.5f }
        val auxSliceW = if (auxCount >= 2) (contentW - taskAreaW) / 2 else (contentW - taskAreaW)
        val habitColor = MaterialTheme.colorScheme.tertiary
        // Hour gridlines
        (0..24).forEach { h ->
            Box(Modifier.fillMaxWidth().height(1.dp).offset(y = (hourDp * h).dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)))
        }
        // Right divider between day columns
        Box(Modifier.fillMaxHeight().width(1.dp).offset(x = colW - 1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)))
        // Events
        val hourPx = with(dens) { hourDp.dp.toPx() }
        placed.forEach { p ->
            val level = PriorityLevel.from(p.task.importance, p.task.urgency)
            val c = if (level == PriorityLevel.NONE) MaterialTheme.colorScheme.primary else priorityColor(level)
            val laneW = (taskAreaW - 2.dp) / p.lanes
            // Live start + duration while dragging (snapped to 15 min); reset when the saved span changes.
            var liveStart by remember(p.task.id, p.startMin) { mutableStateOf(p.startMin) }
            var liveDur by remember(p.task.id, p.endMin - p.startMin) { mutableStateOf(p.endMin - p.startMin) }
            var dragging by remember(p.task.id) { mutableStateOf(false) }
            fun snap(v: Int) = ((v / 15f).roundToInt() * 15)
            val top = (hourDp * liveStart / 60f).dp
            val h = ((hourDp * liveDur / 60f).dp).coerceAtLeast(24.dp)
            Row(
                Modifier.offset(x = railW + laneW * p.lane + 1.dp, y = top).width(laneW - 1.dp).height(h - 2.dp)
                    .clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = if (dragging) 0.30f else 0.16f))
                    // Tap opens the task; long-press then SLIDE moves the block to another time; a long-press
                    // that is released without sliding also opens the task (so a "hold" never falls through to
                    // the empty-slot time-block popup behind it). consume() keeps the grid from scrolling mid-drag.
                    .pointerInput(p.task.id, hourDp) {
                        // R27 #7: accumulate the raw drag in a float (minutes) and snap only for display, so the
                        // block follows the finger continuously. The old code re-snapped liveStart from its own
                        // snapped value each frame, truncating every sub-15-min delta to zero — the block stalled
                        // mid-drag and "lost hold". consume() keeps the grid from scrolling underneath.
                        var startLive = liveStart
                        var rawMin = liveStart.toFloat()
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = true; startLive = liveStart; rawMin = liveStart.toFloat() },
                            onDrag = { change, off ->
                                change.consume()
                                rawMin = (rawMin + off.y / hourPx * 60f).coerceIn(0f, (1440 - liveDur).toFloat())
                                liveStart = snap(rawMin.roundToInt()).coerceIn(0, 1440 - liveDur)
                            },
                            onDragEnd = { dragging = false; if (liveStart != startLive) onMoveAt(p.task.id, liveStart) else onOpenTask(p.task.id) },
                            onDragCancel = { dragging = false; liveStart = startLive },
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
                Modifier.offset(x = railW + laneW * p.lane + 1.dp, y = top + h - 18.dp).width(laneW - 1.dp).height(22.dp)
                    .pointerInput(p.task.id, hourDp) {
                        // R27 #7: same raw-float accumulation as the move drag, so resizing is smooth instead of
                        // truncating each sub-snap frame to zero. consume() keeps the grid's vertical scroll off.
                        var rawDur = liveDur.toFloat()
                        detectVerticalDragGestures(
                            onDragStart = { dragging = true; rawDur = liveDur.toFloat() },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                rawDur = (rawDur + dy / hourPx * 60f).coerceIn(15f, (24 * 60 - liveStart).toFloat())
                                liveDur = snap(rawDur.roundToInt()).coerceIn(15, 24 * 60 - liveStart)
                            },
                            onDragEnd = { dragging = false; onResize(p.task.id, liveDur) },
                            onDragCancel = { dragging = false },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.width(28.dp).height(if (dragging) 5.dp else 4.dp).clip(RoundedCornerShape(3.dp)).background(c)) }
        }
        // M1/R23: habit blocks in the right lane — read-only, tap opens the habit.
        if (habitBlocks.isNotEmpty()) {
            val habitAreaX = railW + taskAreaW + 1.dp
            val habitAreaW = auxSliceW - 2.dp
            // Untimed habits are drawn in the band above the grid now (R27 #3); the grid carries only timed ones.
            val timedH = habitBlocks.filter { !it.untimed }.sortedBy { it.startMin }
            @Composable
            fun habitChip(hb: HabitBlock, mod: Modifier) {
                val col = hb.color ?: habitColor
                Row(
                    mod.clip(RoundedCornerShape(6.dp))
                        .background(col.copy(alpha = if (hb.done) 0.26f else if (hb.partial) 0.16f else 0.09f))
                        .then(if (hb.done) Modifier else Modifier.border(1.dp, col.copy(alpha = .35f), RoundedCornerShape(6.dp)))
                        .clickable { onOpenHabit(hb.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(if (hb.done) col else col.copy(alpha = .45f)))
                    Text((if (hb.done) "✓ " else if (hb.partial) "◑ " else "") + hb.label, Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (hb.done) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (hb.done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Timed habits sit at their reminder time; lane-split so simultaneous ones never overlap.
            if (timedH.isNotEmpty()) {
                val laneEnd = ArrayList<Int>()
                val laneOf = IntArray(timedH.size)
                timedH.forEachIndexed { i, hb ->
                    val end = hb.startMin + hb.durMin.coerceAtLeast(20)
                    var placed = laneEnd.indexOfFirst { it <= hb.startMin }
                    if (placed < 0) { placed = laneEnd.size; laneEnd.add(end) } else laneEnd[placed] = end
                    laneOf[i] = placed
                }
                val lanes = maxOf(1, laneEnd.size)
                val laneW = (habitAreaW - 1.dp) / lanes
                timedH.forEachIndexed { i, hb ->
                    val top = (hourDp * hb.startMin / 60f).dp
                    val hh = ((hourDp * hb.durMin / 60f).dp).coerceAtLeast(22.dp)
                    habitChip(hb, Modifier.offset(x = habitAreaX + laneW * laneOf[i], y = top).width(laneW - 1.dp).height(hh - 2.dp))
                }
            }
        }
        // R39 — dedicated-calendar EVENTS as read-only blocks in their own right-hand lane (after habits
        // when both are present), tap to open the event editor. Lane-split so overlapping events don't stack.
        if (eventBlocks.isNotEmpty()) {
            val evAreaX = railW + taskAreaW + (if (hasHabitLane) auxSliceW else 0.dp) + 1.dp
            val sorted = eventBlocks.sortedBy { it.startMin }
            val laneEnd = ArrayList<Int>()
            val laneOf = IntArray(sorted.size)
            sorted.forEachIndexed { i, eb ->
                val end = eb.startMin + eb.durMin.coerceAtLeast(20)
                var lane = laneEnd.indexOfFirst { it <= eb.startMin }
                if (lane < 0) { lane = laneEnd.size; laneEnd.add(end) } else laneEnd[lane] = end
                laneOf[i] = lane
            }
            val lanes = maxOf(1, laneEnd.size)
            val laneW = (auxSliceW - 1.dp) / lanes
            sorted.forEachIndexed { i, eb ->
                val top = (hourDp * eb.startMin / 60f).dp
                val hh = ((hourDp * eb.durMin / 60f).dp).coerceAtLeast(22.dp)
                Row(
                    Modifier.offset(x = evAreaX + laneW * laneOf[i], y = top).width(laneW - 1.dp).height(hh - 2.dp)
                        .clip(RoundedCornerShape(6.dp)).background(eb.color.copy(alpha = 0.20f))
                        .border(1.dp, eb.color.copy(alpha = .5f), RoundedCornerShape(6.dp))
                        .clickable { onOpenEvent(eb.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(eb.color))
                    Text(eb.title, Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, maxLines = if (hh > 46.dp) 2 else 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        // Round 14 — the "actual" spine: each tracked interval as a thin colored segment on the far
        // left rail. Read-only, no gestures — it sits beside the planned blocks so the eye can compare
        // plan (task/habit blocks) against reality (what was actually tracked) at a glance.
        // U14: when revealing untracked time, paint the whole rail a faint tint first; tracked segments
        // then overlay it, so the eye reads solid = tracked, faint = uncounted.
        if (revealUntracked && railW > 0.dp) {
            Box(Modifier.offset(x = 1.dp).width(railW - 2.dp).fillMaxHeight()
                .clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .30f)))
        }
        if (trackedBlocks.isNotEmpty()) {
            val railColor = MaterialTheme.colorScheme.secondary
            trackedBlocks.forEach { tb ->
                val col = tb.color ?: railColor
                val top = (hourDp * tb.startMin / 60f).dp
                val hh = ((hourDp * tb.durMin / 60f).dp).coerceAtLeast(6.dp)
                // Tap a tracked segment to open its entry — so its start/end are visible and editable
                // right from the calendar, not just a dead line.
                Box(
                    Modifier.offset(x = 1.dp, y = top).width(railW - 2.dp).height(hh)
                        .clip(RoundedCornerShape(3.dp)).background(col.copy(alpha = 0.6f))
                        .clickable { onOpenTracked(tb.id) },
                )
            }
        }
        // Current-time line
        if (nowMin >= 0) {
            val y = (hourDp * nowMin / 60f).dp
            Box(Modifier.fillMaxWidth().offset(y = y).height(2.dp).background(MaterialTheme.colorScheme.error))
            Box(Modifier.size(7.dp).offset(y = y - 3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
        }
    }
}

/** R27 #3: one untimed habit as a compact chip in the header band above the hour grid (tap opens it). */
@Composable
private fun UntimedHabitChip(hb: HabitBlock, onOpenHabit: (String) -> Unit) {
    val col = hb.color ?: MaterialTheme.colorScheme.tertiary
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(6.dp))
            .background(col.copy(alpha = if (hb.done) 0.26f else if (hb.partial) 0.16f else 0.09f))
            .then(if (hb.done) Modifier else Modifier.border(1.dp, col.copy(alpha = .35f), RoundedCornerShape(6.dp)))
            .clickable { onOpenHabit(hb.id) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(18.dp).background(if (hb.done) col else col.copy(alpha = .45f)))
        Text((if (hb.done) "✓ " else if (hb.partial) "◑ " else "") + hb.label, Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (hb.done) FontWeight.SemiBold else FontWeight.Normal,
            color = if (hb.done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** M1: a timed habit drawn as a read-only block in the calendar's day/week grid. */
private data class HabitBlock(
    val id: String, val label: String, val color: androidx.compose.ui.graphics.Color?,
    val startMin: Int, val durMin: Int, val done: Boolean,
    val partial: Boolean = false, val untimed: Boolean = false,
)

/** Round 14: one segment of the "actual" spine — a tracked time interval clamped to the day. */
private data class TrackedBlock(
    val startMin: Int, val durMin: Int, val color: androidx.compose.ui.graphics.Color?, val id: String,
)

/** R39 — a dedicated-calendar EVENT drawn as a read-only block in the day grid (tap opens its editor). */
private data class EventBlock(
    val id: String, val title: String, val color: androidx.compose.ui.graphics.Color,
    val startMin: Int, val durMin: Int,
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
            color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f) else MaterialTheme.colorScheme.onSurface)
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
                        color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f) else MaterialTheme.colorScheme.onSurface,
                    )
                    if (task.note.isNotBlank()) Text(task.note.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // R23: match the smart-list rows — surface recurrence, flag and star alongside the time so a
                // calendar task carries the same at-a-glance detail as it does in the list views.
                if (!task.rrule.isNullOrBlank()) Icon(Icons.Filled.Repeat, "Repeats", Modifier.padding(end = 6.dp).size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                task.flagColorArgb?.let { Box(Modifier.padding(end = 6.dp).size(9.dp).clip(CircleShape).background(Color(it))) }
                if (task.star) Icon(Icons.Filled.Star, "Starred", Modifier.padding(end = 6.dp).size(15.dp), tint = Color(0xFFF5A623))
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
