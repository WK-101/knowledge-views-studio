package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarViewMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import com.todocompanion.app.domain.AutomationRule
import com.todocompanion.app.domain.TimeInsights
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import com.todocompanion.app.data.entity.TimeActivityEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.domain.TimeTracking
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import com.todocompanion.app.ui.components.MiniCheck
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.todocompanion.app.ui.components.appCardColor

private val PALETTE = listOf(0xFF3E7BFAL, 0xFFE5484DL, 0xFFF59E0BL, 0xFF16A34AL, 0xFF8B5CF6L, 0xFF0EA5E9L, 0xFFEC4899L, 0xFF64748BL)
private fun fmtDur(min: Int): String = when {
    min >= 60 -> "${min / 60}h ${min % 60}m"
    else -> "${min}m"
}

/** Duration of a single entry as text — shows seconds for a sub-minute entry so a real few-second
 *  entry never reads as a blank "0m" (R19 #6). */
private fun fmtEntryDur(startMillis: Long, endMillis: Long?, now: Long): String {
    val ms = ((endMillis ?: now) - startMillis).coerceAtLeast(0L)
    val min = (ms / 60_000L).toInt()
    return if (min < 1) "${(ms / 1000L).toInt()}s" else fmtDur(min)
}

/**
 * Tier S — the time tracker. One tap on an activity starts a live timer (single-timer discipline);
 * tap again to stop. A day's entries render as a timeline with per-activity totals, and past intervals
 * can be added or removed by hand. Entirely offline; every entry lands in the lossless backup.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TimeTrackingScreen(vm: AppViewModel, onBack: () -> Unit, embedded: Boolean = false) {
    // T0: as a bottom-nav tab (embedded), there is no back — the tab bar handles navigation.
    if (!embedded) BackHandler(onBack = onBack)
    val activities by vm.timeActivities.collectAsState()
    val entries by vm.timeEntries.collectAsState()
    val habits by vm.habits.collectAsState()   // T3: link an activity to a habit
    val zone = ZoneId.systemDefault()
    val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm") }

    val settings by vm.settings.collectAsState()
    // (paused/running timer UI now lives in the persistent bar; see AppRoot.RunningTimerBar)
    // A live clock so the running timer counts up AND the untracked-time / "since your last entry" gaps
    // keep advancing even when nothing is running (previously `now` froze whenever no timer was live, so
    // untracked time looked "stuck"). Ticks every second while tracking, every 20s when idle.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val runningList = entries.filter { it.running }
    val running = runningList.firstOrNull()
    LaunchedEffect(runningList.isNotEmpty()) {
        while (true) { now = System.currentTimeMillis(); delay(if (runningList.isNotEmpty()) 1000 else 20_000) }
    }

    var day by remember { mutableStateOf(LocalDate.now(zone)) }
    var showDatePicker by remember { mutableStateOf(false) }
    var actsCollapsed by rememberSaveable { mutableStateOf(false) }   // fold the activity grid to save space
    // The Time view can summarise a Day, a Week or a Month — the totals, tiles and insights all follow
    // this window (not just day-by-day). 0 = day · 1 = week (Mon-anchored) · 2 = month.
    var rangeUnit by rememberSaveable { mutableIntStateOf(0) }
    val winStartDate = when (rangeUnit) {
        1 -> day.minusDays((day.dayOfWeek.value - 1).toLong())
        2 -> day.withDayOfMonth(1)
        else -> day
    }
    val winEndDate = when (rangeUnit) { 1 -> winStartDate.plusDays(7); 2 -> winStartDate.plusMonths(1); else -> day.plusDays(1) }
    val winStart = winStartDate.atStartOfDay(zone).toInstant().toEpochMilli()
    val winEnd = winEndDate.atStartOfDay(zone).toInstant().toEpochMilli()
    val multiDay = rangeUnit != 0
    val actById = activities.associateBy { it.id }

    // List EVERY entry that overlaps the window at all — even a few-second one that rounds to 0 minutes,
    // and a just-started running timer — so nothing tracked is invisible (R19 #6).
    val dayEntries = remember(entries, day, rangeUnit, now) {
        entries.filter { TimeTracking.overlapsWindow(it.startMillis, it.endMillis, winStart, winEnd, now) }
            .sortedByDescending { it.startMillis }
    }
    val totals = remember(entries, day, rangeUnit, now) { TimeTracking.totalsByActivity(entries, winStart, winEnd, now) }
    val dayTotalMin = totals.sumOf { it.minutes }

    var showNewActivity by remember { mutableStateOf(false) }
    var editActivity by remember { mutableStateOf<TimeActivityEntity?>(null) }
    var showManual by remember { mutableStateOf(false) }
    var editEntry by remember { mutableStateOf<TimeEntryEntity?>(null) }
    var tileMenu by remember { mutableStateOf<String?>(null) }        // activity id whose long-press menu is open
    var nestFor by remember { mutableStateOf<String?>(null) }         // activity id being (re)nested from the grid
    var historyFor by remember { mutableStateOf<String?>(null) }      // activity id whose full history is shown
    var gapInit by remember { mutableStateOf<Pair<Int, Int>?>(null) } // start/end minutes to prefill the manual dialog with

    if (showNewActivity) ActivityDialog(null, onDismiss = { showNewActivity = false }) { name, emoji, color, goal ->
        vm.createTimeActivity(name, emoji, color, goal); showNewActivity = false
    }
    editActivity?.let { a ->
        ActivityDialog(
            a, onDismiss = { editActivity = null },
            onDelete = { vm.deleteTimeActivity(a.id); editActivity = null },
            onArchive = { vm.archiveTimeActivity(a.id); editActivity = null },
            habitLinks = habits.filter { !it.archived }.map { it.id to it.name },
            linkedHabitId = habits.firstOrNull { it.timeActivityId == a.id }?.id,
            onLinkHabit = { hid ->
                habits.filter { it.timeActivityId == a.id }.forEach { vm.setHabitTimeActivity(it.id, null) }
                hid?.let { vm.setHabitTimeActivity(it, a.id) }
            },
            // A parent can be any other non-archived activity that isn't already a child of this one.
            parentCandidates = activities.filter { !it.archived && it.id != a.id && settings.timeActivityParents[it.id] != a.id }.map { it.id to it.name },
            parentId = settings.timeActivityParents[a.id],
            onSetParent = { pid -> vm.setActivityParent(a.id, pid) },
        ) { name, emoji, color, goal ->
            vm.updateTimeActivity(a.copy(name = name, emoji = emoji, colorArgb = color, goalMinutesPerDay = goal)); editActivity = null
        }
    }
    nestFor?.let { cid ->
        val child = activities.firstOrNull { it.id == cid }
        val candidates = activities.filter { !it.archived && it.id != cid && settings.timeActivityParents[it.id] != cid }
        val cur = settings.timeActivityParents[cid]
        AlertDialog(
            onDismissRequest = { nestFor = null },
            title = { Text("Nest “${child?.name ?: "activity"}” under") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Surface(onClick = { vm.setActivityParent(cid, null); nestFor = null }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                        color = if (cur == null) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else Color.Transparent) {
                        Text("↥ Top level", Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                    }
                    candidates.forEach { p ->
                        Surface(onClick = { vm.setActivityParent(cid, p.id); nestFor = null }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                            color = if (cur == p.id) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else Color.Transparent) {
                            Text((p.emoji?.plus(" ") ?: "") + p.name, Modifier.padding(horizontal = 12.dp, vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { nestFor = null }) { Text("Close") } },
        )
    }
    if (showManual) ManualEntryDialog(activities, day, zone,
        initialStartMin = gapInit?.first ?: 9 * 60, initialEndMin = gapInit?.second ?: 10 * 60,
        onDismiss = { showManual = false; gapInit = null }) { actId, start, end ->
        vm.addManualTimeEntry(actId, start, end); showManual = false; gapInit = null
    }
    // Per-activity history: every tracked interval for one activity, newest first — tap to edit (R18).
    historyFor?.let { aid ->
        val act = activities.firstOrNull { it.id == aid }
        val hist = remember(entries, aid) { entries.filter { it.activityId == aid }.sortedByDescending { it.startMillis } }
        val total = hist.sumOf { it.minutes(now) }
        AlertDialog(
            onDismissRequest = { historyFor = null },
            confirmButton = { TextButton(onClick = { historyFor = null }) { Text("Close") } },
            title = { Text((act?.emoji?.plus(" ") ?: "") + (act?.name ?: "History") + " · ${fmtDur(total)}") },
            text = {
                if (hist.isEmpty()) Text("No tracked time yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Column(Modifier.verticalScroll(rememberScrollState())) {
                    hist.take(200).forEach { e ->
                        Surface(onClick = { historyFor = null; editEntry = e }, shape = RoundedCornerShape(10.dp), color = appCardColor(), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(Instant.ofEpochMilli(e.startMillis).atZone(zone).format(DateTimeFormatter.ofPattern("EEE, MMM d")), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    val st = Instant.ofEpochMilli(e.startMillis).atZone(zone).format(timeFmt)
                                    val en = e.endMillis?.let { Instant.ofEpochMilli(it).atZone(zone).format(timeFmt) } ?: "now"
                                    Text("$st – $en" + (e.note.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(fmtEntryDur(e.startMillis, e.endMillis, now), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
        )
    }
    // R42 — the period picker adapts to the selected range: a day grid for Day, a week list for Week,
    // and a month grid for Month, so it never shows a day picker when you're browsing weeks or months.
    if (showDatePicker) {
        val t = LocalDate.now(zone)
        when (rangeUnit) {
            1 -> ThemedWeekPicker(initial = day, zone = zone, onDismiss = { showDatePicker = false }) { picked ->
                day = if (picked.isAfter(t)) t else picked; showDatePicker = false
            }
            2 -> ThemedMonthPicker(initial = day, zone = zone, onDismiss = { showDatePicker = false }) { picked ->
                day = if (picked.isAfter(t)) t.withDayOfMonth(1) else picked.withDayOfMonth(1); showDatePicker = false
            }
            else -> com.todocompanion.app.ui.components.DateOnlyPickerDialog(
                initial = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
                onDismiss = { showDatePicker = false }, allowFuture = false,
            ) { millis ->
                val picked = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                day = if (picked.isAfter(t)) t else picked; showDatePicker = false
            }
        }
    }
    editEntry?.let { e ->
        EditEntryDialog(e, activities.filter { !it.archived }, zone, onDismiss = { editEntry = null },
            onDelete = { vm.deleteTimeEntry(e.id); editEntry = null },
            onSplit = { at -> vm.splitTimeEntry(e.id, at); editEntry = null },
            onSave = { updated -> vm.updateTimeEntry(updated); editEntry = null })
    }

    // One "add a time entry" action, surfaced as a floating button that matches the app's quick-add
    // FAB. As the Time tab (embedded) the FAB lives in the shared scaffold and pokes us through the
    // view-model, so the tab shows a single top header like every other tab; standalone we host both.
    val addReq by vm.addTimeEntryRequests.collectAsState()
    var lastAddReq by remember { mutableIntStateOf(addReq) }
    fun onAddEntry() { if (activities.none { !it.archived }) showNewActivity = true else showManual = true }
    LaunchedEffect(addReq) { if (addReq != lastAddReq) { lastAddReq = addReq; onAddEntry() } }

    val body: @Composable (Modifier) -> Unit = { bodyModifier ->
        Column(bodyModifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(if (embedded) 8.dp else 2.dp))
            // The running / paused timer now lives ONLY in the persistent bar above the bottom nav
            // (reassign / pause / stop there), so the Time screen no longer duplicates it with a top card.
            // Idle start is the FAB (single tap) or tapping an activity tile below — the view stays lean.

            // U1 · "forgot to track?" — planned time-blocks today with little/no tracked time.
            if (day == LocalDate.now(zone)) {
                val untracked = remember(entries, now) { vm.untrackedTodayBlocks() }
                if (untracked.isNotEmpty()) AppCard {
                    Text("Forgot to track?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("These planned blocks have no time logged. Tap to fill.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        untracked.take(6).forEach { b ->
                            AssistChip(onClick = { vm.fillTrackedBlock(b) },
                                label = { Text(b.label.take(18) + " · " + fmtDur(b.durMin), maxLines = 1) },
                                leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) })
                        }
                    }
                }
            }

            // Activity tiles — a one-tap grid (tap starts/stops instantly, no dialog; long-press a tile
            // to pin, edit or delete). Pinned activities float to the front so the ones you use most are
            // always first — the low-decision-fatigue core, à la Simple Time Tracker.
            val liveActs = activities.filter { !it.archived }
            if (liveActs.isEmpty()) {
                com.todocompanion.app.ui.components.EmptyState(
                    emoji = "⧗", title = "Track where time goes",
                    body = "Add a few activities like Deep work, Reading or Exercise, then tap one to start the timer.",
                    actionLabel = "＋ New activity", onAction = { showNewActivity = true },
                )
            } else {
                // Header row: title + a column-count control (2–5 per row, like Simple Time Tracker).
                val cols = settings.timeGridColumns.coerceIn(2, 5)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { actsCollapsed = !actsCollapsed }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (actsCollapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Activities", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!actsCollapsed) {
                        Icon(Icons.Filled.GridView, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        listOf(2, 3, 4, 5).forEach { n ->
                            val sel = n == cols
                            Box(
                                Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = .16f) else Color.Transparent)
                                    .clickable { vm.setTimeGridColumns(n) },
                                contentAlignment = Alignment.Center,
                            ) { Text("$n", style = MaterialTheme.typography.labelMedium, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                // Nested order: each top-level activity, immediately followed by its children (pinned
                // float to the front within each level). Children render with a subtle "└" so the tree reads.
                val parents = settings.timeActivityParents
                val ordered = remember(liveActs, settings.pinnedActivities, parents) {
                    val byPin = compareByDescending<TimeActivityEntity> { it.id in settings.pinnedActivities }.thenBy { it.name.lowercase() }
                    val byParent = liveActs.groupBy { parents[it.id]?.takeIf { p -> liveActs.any { a -> a.id == p } } }
                    val topLevel = (byParent[null] ?: emptyList()).sortedWith(byPin)
                    val out = ArrayList<TimeActivityEntity>()
                    val seen = HashSet<String>()
                    // Depth-first descent so every level (children, grandchildren…) appears under its
                    // parent; `seen` guards against cycles and diamonds so nothing loops or duplicates.
                    fun descend(a: TimeActivityEntity) {
                        if (!seen.add(a.id)) return
                        out.add(a)
                        (byParent[a.id] ?: emptyList()).sortedWith(byPin).forEach { descend(it) }
                    }
                    topLevel.forEach { descend(it) }
                    // Fallback: anything unreached (all-cyclic parents, orphans) is appended flat so the grid
                    // never comes up empty and no activity vanishes.
                    liveActs.sortedWith(byPin).forEach { if (seen.add(it.id)) out.add(it) }
                    out
                }
                val childIds = remember(ordered, parents) { ordered.filter { parents[it.id]?.let { p -> ordered.any { a -> a.id == p } } == true }.map { it.id }.toSet() }
                // R64 — nested activities now ROLL UP: a parent tile shows its own tracked minutes plus every
                // descendant's, so a folder-style parent no longer reads 0 while its children hold hours.
                val rolledMin = remember(totals, parents, liveActs) {
                    val flat = totals.associate { it.activityId to it.minutes }
                    val kids = HashMap<String, MutableList<String>>()
                    liveActs.forEach { a -> parents[a.id]?.let { p -> kids.getOrPut(p) { mutableListOf() }.add(a.id) } }
                    fun subtree(id: String, seen: HashSet<String>): Int {
                        if (!seen.add(id)) return 0
                        return (flat[id] ?: 0) + (kids[id]?.sumOf { subtree(it, seen) } ?: 0)
                    }
                    liveActs.associate { it.id to subtree(it.id, HashSet()) }
                }
                // A plain N-column grid (not lazy — we're inside a vertical scroll). `null` = the New tile.
                // Tiles are square-ish and centred so they read cleanly from 2 up to 5 per row.
                val tileHeight = if (cols >= 4) 84.dp else 72.dp
                if (!actsCollapsed) (ordered + listOf<TimeActivityEntity?>(null)).chunked(cols).forEach { rowItems ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { a ->
                            if (a == null) {
                                Surface(onClick = { showNewActivity = true }, Modifier.weight(1f).height(tileHeight),
                                    shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)) {
                                    Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(4.dp)); Text("New", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            } else {
                                val isRun = runningList.any { it.activityId == a.id }
                                val c = a.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                                val todayMin = rolledMin[a.id] ?: (totals.firstOrNull { it.activityId == a.id }?.minutes ?: 0)
                                val pinned = a.id in settings.pinnedActivities
                                Box(Modifier.weight(1f)) {
                                    Column(
                                        Modifier.fillMaxWidth().height(tileHeight).clip(RoundedCornerShape(16.dp))
                                            .background(if (isRun) c.copy(alpha = .22f) else c.copy(alpha = .12f))
                                            .then(if (isRun) Modifier.border(1.5.dp, c, RoundedCornerShape(16.dp)) else Modifier)
                                            .combinedClickable(
                                                onClick = { if (isRun) runningList.filter { it.activityId == a.id }.forEach { vm.stopTimeEntry(it.id) } else vm.startTimeTracking(a.id) },
                                                onLongClick = { tileMenu = a.id },
                                            ).padding(horizontal = 6.dp, vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (a.emoji != null) Text(a.emoji!!, style = MaterialTheme.typography.titleMedium)
                                            else Box(Modifier.size(12.dp).clip(CircleShape).background(c))
                                            if (pinned) { Spacer(Modifier.width(4.dp)); Text("★", style = MaterialTheme.typography.labelSmall, color = c) }
                                        }
                                        Spacer(Modifier.height(3.dp))
                                        Text((if (a.id in childIds) "↳ " else "") + a.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp))
                                        Text(if (isRun) "● running" else if (todayMin > 0) fmtDur(todayMin) else "start",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isRun) c else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                    DropdownMenu(expanded = tileMenu == a.id, onDismissRequest = { tileMenu = null }) {
                                        DropdownMenuItem(text = { Text(if (pinned) "Unpin" else "Pin to front") }, onClick = { tileMenu = null; vm.toggleActivityPin(a.id) })
                                        DropdownMenuItem(text = { Text("History") }, onClick = { tileMenu = null; historyFor = a.id })
                                        if (liveActs.size > 1) DropdownMenuItem(text = { Text("Nest under…") }, onClick = { tileMenu = null; nestFor = a.id })
                                        DropdownMenuItem(text = { Text("Edit") }, onClick = { tileMenu = null; editActivity = a })
                                    }
                                }
                            }
                        }
                        repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            // Period navigator — a compact Day/Week/Month icon-menu inline with the ‹ label › nav, so the
            // range selector no longer takes a whole row (R18). Tap the label to jump to any date.
            val today0 = LocalDate.now(zone)
            val canNext = winEndDate <= today0
            val periodLabel = when (rangeUnit) {
                1 -> "${winStartDate.format(DateTimeFormatter.ofPattern("MMM d"))} – ${winStartDate.plusDays(6).format(DateTimeFormatter.ofPattern("MMM d"))}"
                2 -> winStartDate.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                else -> if (day == today0) "Today" else day.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                var rangeMenu by remember { mutableStateOf(false) }
                Box {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { rangeMenu = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Filled.CalendarViewMonth, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                        Text(when (rangeUnit) { 1 -> "Week"; 2 -> "Month"; else -> "Day" }, style = MaterialTheme.typography.labelMedium)
                    }
                    DropdownMenu(expanded = rangeMenu, onDismissRequest = { rangeMenu = false }) {
                        listOf("Day", "Week", "Month").forEachIndexed { i, label ->
                            DropdownMenuItem(text = { Text(label) }, trailingIcon = { if (rangeUnit == i) Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }, onClick = { rangeUnit = i; rangeMenu = false })
                        }
                    }
                }
                IconButton(onClick = { day = when (rangeUnit) { 1 -> day.minusWeeks(1); 2 -> day.minusMonths(1); else -> day.minusDays(1) } }) { Icon(Icons.Filled.ChevronLeft, "Previous") }
                Text(
                    "$periodLabel · ${fmtDur(dayTotalMin)}  ▾",
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { showDatePicker = true }.padding(vertical = 6.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = { if (canNext) day = when (rangeUnit) { 1 -> day.plusWeeks(1); 2 -> day.plusMonths(1); else -> day.plusDays(1) }.let { if (it.isAfter(today0)) today0 else it } }, enabled = canNext) { Icon(Icons.Filled.ChevronRight, "Next") }
            }

            // Per-activity totals (bars).
            if (totals.isNotEmpty()) AppCard {
                val max = totals.maxOf { it.minutes }.coerceAtLeast(1)
                totals.forEach { t ->
                    val a = actById[t.activityId]
                    val c = a?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                    // T4: an activity with a daily goal shows progress toward it; the bar fills against the
                    // goal (else against the day's largest activity), and reads met with the modern mark.
                    val goalMin = a?.goalMinutesPerDay ?: 0
                    val goalMet = goalMin in 1..t.minutes
                    val frac = if (goalMin > 0) (t.minutes / goalMin.toFloat()).coerceIn(0f, 1f) else t.minutes / max.toFloat()
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text((a?.emoji?.plus(" ") ?: "") + (a?.name ?: "—"), Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth(frac).height(14.dp).clip(RoundedCornerShape(7.dp)).background(if (goalMet) MaterialTheme.colorScheme.tertiary else c))
                        }
                        Spacer(Modifier.width(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                fmtDur(t.minutes) + (if (goalMin > 0) " / ${fmtDur(goalMin)}" else ""),
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                color = if (goalMet) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                            )
                            // Goal met reads with the modern completion mark, not a trailing raw "✓".
                            if (goalMet) { Spacer(Modifier.width(4.dp)); MiniCheck() }
                        }
                    }
                }
            }

            // U9 · insights — when you worked (by hour), how sessions distribute, and per-tag totals.
            if (dayEntries.isNotEmpty()) {
                var showInsights by rememberSaveable { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clickable { showInsights = !showInsights }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Insights", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(if (showInsights) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                }
                if (showInsights) AppCard {
                    val byHour = remember(entries, day, rangeUnit, now) { TimeInsights.minutesByHour(entries, winStart, winEnd, now) }
                    val hourMax = (byHour.maxOrNull() ?: 0).coerceAtLeast(1)
                    Text("When you worked", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.Bottom) {
                        for (h in 0..23) {
                            val frac = byHour[h] / hourMax.toFloat()
                            Box(Modifier.weight(1f).fillMaxHeight(frac.coerceAtLeast(0.02f)).clip(RoundedCornerShape(2.dp))
                                .background(if (byHour[h] > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("0", "6", "12", "18", "23").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Session lengths", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val dist = remember(entries, day, rangeUnit) { TimeInsights.durationDistribution(entries, winStart, winEnd) }
                    val distMax = dist.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
                    dist.filter { it.count > 0 }.forEach { b ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(b.label, Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall)
                            Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                Box(Modifier.fillMaxWidth(b.count / distMax.toFloat()).height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.tertiary))
                            }
                            Spacer(Modifier.width(8.dp)); Text("${b.count}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    val tagTotals = remember(entries, day, rangeUnit, now) { TimeInsights.totalsByTag(entries, winStart, winEnd, now) }
                    if (tagTotals.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("By tag", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tagTotals.forEach { tt -> AssistChip(onClick = {}, label = { Text("#${tt.tag} · ${fmtDur(tt.minutes)}") }) }
                        }
                    }
                    // V10 — this week vs last: total tracked and its change.
                    Spacer(Modifier.height(10.dp))
                    Text("This week vs last", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Always the rolling 7 days ending on `day` — independent of the Day/Week/Month window,
                    // so this comparison stays "this week vs last" and doesn't borrow winEnd (audit #3).
                    val wkStart = day.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
                    val wkEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val prevStart = day.minusDays(13).atStartOfDay(zone).toInstant().toEpochMilli()
                    val thisWk = TimeTracking.totalMinutes(entries, wkStart, wkEnd, now)
                    val lastWk = TimeTracking.totalMinutes(entries, prevStart, wkStart, now)
                    val delta = thisWk - lastWk
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmtDur(thisWk), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            when { delta > 0 -> "▲ ${fmtDur(delta)} vs last week"; delta < 0 -> "▼ ${fmtDur(-delta)} vs last week"; else -> "— same as last week" },
                            style = MaterialTheme.typography.labelMedium,
                            color = when { delta > 0 -> MaterialTheme.colorScheme.primary; delta < 0 -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                        )
                    }
                    // W8 — activity-vs-activity: the week's activities compared side by side.
                    val wkByAct = remember(entries, day, now) { TimeTracking.totalsByActivity(entries, wkStart, wkEnd, now) }
                    if (wkByAct.size >= 2) {
                        Spacer(Modifier.height(10.dp))
                        Text("Activities this week", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val wkMax = wkByAct.maxOf { it.minutes }.coerceAtLeast(1)
                        wkByAct.take(6).forEach { at ->
                            val a = actById[at.activityId]
                            val c = a?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text((a?.emoji?.plus(" ") ?: "") + (a?.name ?: "—"), Modifier.width(96.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                    Box(Modifier.fillMaxWidth(at.minutes / wkMax.toFloat()).height(10.dp).clip(RoundedCornerShape(5.dp)).background(c))
                                }
                                Spacer(Modifier.width(8.dp)); Text(fmtDur(at.minutes), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // Timeline of the day's entries. Adding an entry is the ＋ floating button (shared FAB).
            Text("Timeline", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // U5 · "account for my whole day" — when Timeline-fill is on, the holes between what you did
            // log surface as tappable chips so no stretch goes unexplained. Off by default (a Settings toggle).
            if (settings.timelineFill && !multiDay) {
                // For today, surface the live trailing gap ("nothing tracked since …") up to now, too.
                val trailingTo = if (day == LocalDate.now(zone)) now else null
                val gaps = remember(entries, day, rangeUnit, now) { TimeInsights.untrackedGaps(entries, winStart, winEnd, now, trailingTo = trailingTo) }
                if (gaps.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        gaps.forEach { g ->
                            val sMin = ((g.startMillis - winStart) / 60_000L).toInt().coerceIn(0, 1439)
                            val eMin = ((g.endMillis - winStart) / 60_000L).toInt().coerceIn(0, 1440)
                            val sTxt = Instant.ofEpochMilli(g.startMillis).atZone(zone).format(timeFmt)
                            AssistChip(onClick = { gapInit = sMin to eMin; showManual = true },
                                label = { Text("$sTxt · ${fmtDur(g.minutes)} untracked", maxLines = 1) },
                                leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) })
                        }
                    }
                }
            }
            if (dayEntries.isEmpty()) {
                Text("No time logged this day yet. Tap ＋ to add one.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                dayEntries.forEach { e ->
                    val a = actById[e.activityId]
                    val c = a?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                    Surface(onClick = { editEntry = e }, shape = RoundedCornerShape(12.dp), color = appCardColor(), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text((a?.emoji?.plus(" ") ?: "") + (a?.name ?: "—") + if (e.running) "  · running" else "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val startTxt = Instant.ofEpochMilli(e.startMillis).atZone(zone).format(timeFmt)
                                val endTxt = e.endMillis?.let { Instant.ofEpochMilli(it).atZone(zone).format(timeFmt) } ?: "now"
                                // When the window spans days, prefix the calendar date so rows are unambiguous.
                                val datePrefix = if (multiDay) Instant.ofEpochMilli(e.startMillis).atZone(zone).format(DateTimeFormatter.ofPattern("MMM d")) + "  " else ""
                                Text(datePrefix + "$startTxt – $endTxt" + (e.note.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(fmtEntryDur(e.startMillis, e.endMillis, now), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = c)
                        }
                    }
                }
            }

            // Automations live at the bottom now (were floating mid-screen) — "when I start X, do Y".
            if (activities.isNotEmpty()) {
                var showAuto by rememberSaveable { mutableStateOf(false) }
                var addRule by remember { mutableStateOf(false) }
                val rules = com.todocompanion.app.domain.AutomationRules.parse(settings.automationRulesJson)
                Row(Modifier.fillMaxWidth().clickable { showAuto = !showAuto }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Automations", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (rules.isNotEmpty()) Text("${rules.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(if (showAuto) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                }
                if (showAuto) AppCard {
                    if (rules.isEmpty()) Text("No automations yet. Fire a notification or start another activity when you begin tracking one.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    rules.forEach { r ->
                        val whenA = actById[r.whenActivityId]?.name ?: "?"
                        val doTxt = if (r.actionType == com.todocompanion.app.domain.AutomationRule.ACTION_START)
                            "start ${actById[r.startActivityId]?.name ?: "?"}" else "notify “${r.notifyText}”"
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("When $whenA starts → $doTxt", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = { vm.saveAutomationRules(rules.filter { it.id != r.id }) }) { Icon(Icons.Filled.Close, "Delete", Modifier.size(18.dp)) }
                        }
                    }
                    TextButton(onClick = { addRule = true }) { Text("＋ Add automation") }
                }
                if (addRule) AutomationRuleDialog(activities.filter { !it.archived }, onDismiss = { addRule = false }) { rule ->
                    vm.saveAutomationRules(rules + rule); addRule = false
                }
            }
            Spacer(Modifier.height(96.dp))   // FAB clearance
        }
    }

    if (embedded) {
        // The Time tab: no header here — the shared scaffold already shows the "Time" app bar and
        // hosts the ＋ FAB (which pokes onAddEntry via the view-model). One header, matching layout.
        body(Modifier.fillMaxSize().padding(horizontal = 14.dp))
    } else {
        // Standalone (opened from the drawer or a widget): our own single header + matching FAB.
        Scaffold(
            topBar = {
                TopAppBar(expandedHeight = 52.dp, 
                    title = { Text("Time") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { onAddEntry() }) { Icon(Icons.Filled.Add, "Add time entry") }
            },
        ) { padding ->
            body(Modifier.padding(padding).fillMaxSize().padding(horizontal = 14.dp))
        }
    }
}

/** New/edit an activity: name, optional emoji, a colour swatch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityDialog(
    existing: TimeActivityEntity?, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null, onArchive: (() -> Unit)? = null,
    habitLinks: List<Pair<String, String>> = emptyList(), linkedHabitId: String? = null, onLinkHabit: (String?) -> Unit = {},
    parentCandidates: List<Pair<String, String>> = emptyList(), parentId: String? = null, onSetParent: (String?) -> Unit = {},
    onSave: (String, String?, Long?, Int) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var emoji by remember { mutableStateOf(existing?.emoji ?: "") }
    var color by remember { mutableLongStateOf(existing?.colorArgb ?: PALETTE.first()) }
    var goal by remember { mutableStateOf(existing?.goalMinutesPerDay?.takeIf { it > 0 }?.toString() ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New activity" else "Edit activity") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                com.todocompanion.app.ui.components.AppTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                // Full emoji picker (same one habits use), so activities get proper icon selection everywhere.
                Text("Icon", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                com.todocompanion.app.ui.components.EmojiGridPicker(current = emoji.ifBlank { null }, onPick = { emoji = it ?: "" })
                Spacer(Modifier.height(8.dp))
                // T4: an optional daily time goal (minutes). Progress is computed from tracked intervals.
                com.todocompanion.app.ui.components.AppTextField(goal, { v -> goal = v.filter { it.isDigit() }.take(4) }, label = { Text("Daily goal (minutes, optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Colour", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    com.todocompanion.app.ui.components.AppColorPicker(current = color, onPick = { color = it ?: color })
                }
                // Nested activities: put this activity under a parent (e.g. "Standup" under "Work").
                if (existing != null && parentCandidates.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("Nest under", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    com.todocompanion.app.ui.components.OptionChips(listOf<String?>(null) + parentCandidates.map { it.first }, parentId, { onSetParent(it) }, wrap = false, spacing = 8) { pid ->
                        if (pid == null) "Top level" else parentCandidates.firstOrNull { it.first == pid }?.second ?: ""
                    }
                }
                // T3 (I4): link this activity to a habit — tracking it then counts the habit, sharing one goal.
                if (habitLinks.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("Counts toward habit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    com.todocompanion.app.ui.components.OptionChips(listOf<String?>(null) + habitLinks.map { it.first }, linkedHabitId, { onLinkHabit(it) }, wrap = false, spacing = 8) { hid ->
                        if (hid == null) "None" else habitLinks.firstOrNull { it.first == hid }?.second ?: ""
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), emoji.trim().ifBlank { null }, color, goal.toIntOrNull() ?: 0) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    // Delete offers two honest choices: archive (keep the tracked time in stats) or remove everywhere.
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete “${existing?.name ?: "activity"}”?") },
        text = { Text("Archive keeps its tracked time in your statistics. Delete removes the activity and its time entries from everywhere — this can't be undone.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete?.invoke() }) { Text("Delete everywhere", color = MaterialTheme.colorScheme.error) } },
        dismissButton = {
            Row {
                if (onArchive != null) TextButton(onClick = { confirmDelete = false; onArchive.invoke() }) { Text("Archive") }
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        },
    )
}

/** Add a past interval: pick an activity, then a start and end time on the selected day (native pickers). */
@Composable
private fun ManualEntryDialog(activities: List<TimeActivityEntity>, day: LocalDate, zone: ZoneId, initialStartMin: Int = 9 * 60, initialEndMin: Int = 10 * 60, onDismiss: () -> Unit, onAdd: (String, Long, Long) -> Unit) {
    var activityId by remember { mutableStateOf(activities.firstOrNull { !it.archived }?.id) }
    var startMin by remember { mutableIntStateOf(initialStartMin) }   // minutes from midnight
    var endMin by remember { mutableIntStateOf(initialEndMin) }
    // Themed time picker (one UI everywhere) instead of the OS TimePickerDialog.
    var picking by remember { mutableStateOf<Pair<Int, (Int) -> Unit>?>(null) }
    fun pick(initial: Int, onPicked: (Int) -> Unit) { picking = initial to onPicked }
    picking?.let { (init, cb) ->
        com.todocompanion.app.ui.components.TimeFieldDialog(init, onDismiss = { picking = null }) { m -> cb(m); picking = null }
    }
    fun label(min: Int) = "%02d:%02d".format(min / 60, min % 60)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add past entry") },
        text = {
            Column {
                Text("Activity", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    activities.filter { !it.archived }.forEach { a ->
                        val sel = a.id == activityId
                        val c = a.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                        Surface(onClick = { activityId = a.id }, shape = RoundedCornerShape(12.dp), color = if (sel) c.copy(alpha = .2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)) {
                            Text((a.emoji?.plus(" ") ?: "") + a.name, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { pick(startMin) { startMin = it } }) { Text("Start ${label(startMin)}") }
                    FilledTonalButton(onClick = { pick(endMin) { endMin = it } }) { Text("End ${label(endMin)}") }
                }
                if (endMin <= startMin) Text("End must be after start.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = activityId != null && endMin > startMin, onClick = {
                val base = day.atStartOfDay(zone)
                val s = base.plusMinutes(startMin.toLong()).toInstant().toEpochMilli()
                val e = base.plusMinutes(endMin.toLong()).toInstant().toEpochMilli()
                onAdd(activityId!!, s, e)
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** U12: create an on-start automation — pick the trigger activity and either a notification or a chained start. */
@Composable
private fun AutomationRuleDialog(activities: List<TimeActivityEntity>, onDismiss: () -> Unit, onSave: (com.todocompanion.app.domain.AutomationRule) -> Unit) {
    var whenId by remember { mutableStateOf(activities.firstOrNull()?.id ?: "") }
    var action by remember { mutableStateOf(AutomationRule.ACTION_NOTIFY) }
    var notifyText by remember { mutableStateOf("") }
    var startId by remember { mutableStateOf(activities.getOrNull(1)?.id ?: activities.firstOrNull()?.id ?: "") }
    var stopId by remember { mutableStateOf("") }   // "" = every other timer
    var afterMin by remember { mutableIntStateOf(-1) }
    var beforeMin by remember { mutableIntStateOf(-1) }
    var days by remember { mutableStateOf(setOf<Int>()) }
    var pickAfter by remember { mutableStateOf(false) }
    var pickBefore by remember { mutableStateOf(false) }
    fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New automation") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("When I start", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                com.todocompanion.app.ui.components.OptionChips(activities, activities.firstOrNull { it.id == whenId }, { whenId = it.id }, wrap = false, spacing = 6) { (it.emoji?.plus(" ") ?: "") + it.name }
                Spacer(Modifier.height(12.dp))
                Text("Then", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                com.todocompanion.app.ui.components.OptionChips(listOf(AutomationRule.ACTION_NOTIFY, AutomationRule.ACTION_START, AutomationRule.ACTION_STOP), action, { action = it }, spacing = 6) {
                    when (it) { AutomationRule.ACTION_NOTIFY -> "Notify me"; AutomationRule.ACTION_START -> "Start another"; else -> "Stop another" }
                }
                Spacer(Modifier.height(8.dp))
                when (action) {
                    AutomationRule.ACTION_NOTIFY -> com.todocompanion.app.ui.components.AppTextField(notifyText, { notifyText = it }, label = { Text("Message (e.g. Phone on silent?)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    AutomationRule.ACTION_START -> {
                        Text("Requires “Allow overlapping timers” on.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val startable = activities.filter { it.id != whenId }
                        com.todocompanion.app.ui.components.OptionChips(startable, startable.firstOrNull { it.id == startId }, { startId = it.id }, wrap = false, spacing = 6) { (it.emoji?.plus(" ") ?: "") + it.name }
                    }
                    else -> com.todocompanion.app.ui.components.OptionChips(listOf("") + activities.filter { it.id != whenId }.map { it.id }, stopId, { stopId = it }, wrap = false, spacing = 6) { id ->
                        if (id.isBlank()) "All other timers" else activities.firstOrNull { it.id == id }?.let { (it.emoji?.plus(" ") ?: "") + it.name } ?: ""
                    }
                }
                // Expert guards (R23): only within a time window and/or on chosen weekdays.
                Spacer(Modifier.height(12.dp)); androidx.compose.material3.HorizontalDivider()
                Text("Only when (optional)", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = afterMin >= 0, onClick = { if (afterMin >= 0) afterMin = -1 else pickAfter = true }, label = { Text(if (afterMin >= 0) "After ${hhmm(afterMin)}" else "After…") })
                    FilterChip(selected = beforeMin >= 0, onClick = { if (beforeMin >= 0) beforeMin = -1 else pickBefore = true }, label = { Text(if (beforeMin >= 0) "Before ${hhmm(beforeMin)}" else "Before…") })
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val names = listOf(1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 7 to "S")
                    names.forEach { (d, lbl) ->
                        FilterChip(selected = d in days, onClick = { days = if (d in days) days - d else days + d }, label = { Text(lbl) })
                    }
                }
            }
        },
        confirmButton = {
            val valid = whenId.isNotBlank() && when (action) {
                AutomationRule.ACTION_NOTIFY -> notifyText.isNotBlank()
                AutomationRule.ACTION_START -> startId.isNotBlank()
                else -> true
            }
            TextButton(enabled = valid, onClick = {
                onSave(AutomationRule(
                    id = java.util.UUID.randomUUID().toString(), whenActivityId = whenId,
                    actionType = action, notifyText = notifyText.trim(), startActivityId = startId, stopActivityId = stopId,
                    afterMin = afterMin, beforeMin = beforeMin, days = days.sorted().joinToString(",")))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickAfter) com.todocompanion.app.ui.components.TimeFieldDialog(if (afterMin >= 0) afterMin else 9 * 60, onDismiss = { pickAfter = false }) { afterMin = it; pickAfter = false }
    if (pickBefore) com.todocompanion.app.ui.components.TimeFieldDialog(if (beforeMin >= 0) beforeMin else 17 * 60, onDismiss = { pickBefore = false }) { beforeMin = it; pickBefore = false }
}

/** Edit a logged entry: adjust its times, reassign the activity, tag it, split it, or delete it.
 *  Non-private so the calendar can open the same editor when a tracked block is tapped. */
@Composable
internal fun EditEntryDialog(entry: TimeEntryEntity, activities: List<TimeActivityEntity>, zone: ZoneId, onDismiss: () -> Unit, onDelete: () -> Unit, onSplit: (Long) -> Unit, onSave: (TimeEntryEntity) -> Unit) {
    var note by remember { mutableStateOf(entry.note) }
    var tags by remember { mutableStateOf(entry.tags) }
    var activityId by remember { mutableStateOf(entry.activityId) }
    var start by remember { mutableLongStateOf(entry.startMillis) }
    var end by remember { mutableStateOf(entry.endMillis) }
    val fmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val activity = activities.firstOrNull { it.id == activityId }
    // Themed time picker (matches the app), keeping the entry on its own day while changing the clock time.
    var picking by remember { mutableStateOf<Pair<Long, (Long) -> Unit>?>(null) }
    fun pick(initial: Long, onPicked: (Long) -> Unit) { picking = initial to onPicked }
    picking?.let { (init, cb) ->
        val z = Instant.ofEpochMilli(init).atZone(zone)
        com.todocompanion.app.ui.components.TimeFieldDialog(z.hour * 60 + z.minute, onDismiss = { picking = null }) { min ->
            cb(z.toLocalDate().atStartOfDay(zone).plusMinutes(min.toLong()).toInstant().toEpochMilli()); picking = null
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text((activity?.emoji?.plus(" ") ?: "") + (activity?.name ?: "Entry")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { pick(start) { start = it } }) { Text("Start ${Instant.ofEpochMilli(start).atZone(zone).format(fmt)}") }
                    if (end != null) FilledTonalButton(onClick = { pick(end!!) { end = it } }) { Text("End ${Instant.ofEpochMilli(end!!).atZone(zone).format(fmt)}") }
                    else Text("running", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (end != null && end!! <= start) Text("End must be after start.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(10.dp))
                // U4: reassign to another activity.
                Text("Activity", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    activities.forEach { a ->
                        val sel = a.id == activityId
                        val c = a.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                        Surface(onClick = { activityId = a.id }, shape = RoundedCornerShape(12.dp), color = if (sel) c.copy(alpha = .2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)) {
                            Text((a.emoji?.plus(" ") ?: "") + a.name, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                com.todocompanion.app.ui.components.AppTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                // U11: comma-separated tags.
                com.todocompanion.app.ui.components.AppTextField(tags, { tags = it }, label = { Text("Tags (comma-separated)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                // U4: split this interval at its midpoint.
                if (end != null && end!! - start > 120_000L) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { onSplit((start + end!!) / 2) }) { Text("Split in two at midpoint") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = end == null || end!! > start, onClick = {
                onSave(entry.copy(activityId = activityId, startMillis = start, endMillis = end, note = note.trim(), tags = tags.trim()))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** R42 — a proper month picker for the Time view's Month range: a year stepper + a 12-month grid. */
@Composable
private fun ThemedMonthPicker(initial: LocalDate, zone: ZoneId, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val today = LocalDate.now(zone)
    var year by remember { mutableIntStateOf(initial.year) }
    val months = java.time.Month.values()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Pick a month") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = { year-- }) { Icon(Icons.Filled.ChevronLeft, "Previous year") }
                    Text("$year", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { if (year < today.year) year++ }, enabled = year < today.year) { Icon(Icons.Filled.ChevronRight, "Next year") }
                }
                Spacer(Modifier.height(8.dp))
                for (r in 0 until 4) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (c in 0 until 3) {
                            val m = months[r * 3 + c]
                            val ym = java.time.YearMonth.of(year, m)
                            val future = ym.isAfter(java.time.YearMonth.from(today))
                            val selected = year == initial.year && m == initial.month
                            Box(
                                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (future) 0.3f else 0.6f))
                                    .clickable(enabled = !future) { onPick(ym.atDay(1)) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(m.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else if (future) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        },
    )
}

/** R42 — a proper week picker for the Time view's Week range: a scrollable list of recent weeks. */
@Composable
private fun ThemedWeekPicker(initial: LocalDate, zone: ZoneId, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val today = LocalDate.now(zone)
    val thisWeekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val selStart = initial.minusDays((initial.dayOfWeek.value - 1).toLong())
    val fmt = DateTimeFormatter.ofPattern("MMM d")
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Pick a week") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                for (i in 0 until 52) {
                    val ws = thisWeekStart.minusWeeks(i.toLong())
                    val we = ws.plusDays(6)
                    val selected = ws == selStart
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable { onPick(ws) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${ws.format(fmt)} – ${we.format(fmt)}", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        Text(if (i == 0) "This week" else if (i == 1) "Last week" else "${i}w ago",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    )
}
