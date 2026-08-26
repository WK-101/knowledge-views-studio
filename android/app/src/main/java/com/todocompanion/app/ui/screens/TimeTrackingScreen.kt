package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import com.todocompanion.app.domain.TimeInsights
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PALETTE = listOf(0xFF3E7BFAL, 0xFFE5484DL, 0xFFF59E0BL, 0xFF16A34AL, 0xFF8B5CF6L, 0xFF0EA5E9L, 0xFFEC4899L, 0xFF64748BL)
private fun fmtDur(min: Int): String = when {
    min >= 60 -> "${min / 60}h ${min % 60}m"
    else -> "${min}m"
}

/**
 * Tier S — the time tracker. One tap on an activity starts a live timer (single-timer discipline);
 * tap again to stop. A day's entries render as a timeline with per-activity totals, and past intervals
 * can be added or removed by hand. Entirely offline; every entry lands in the lossless backup.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val paused by vm.pausedTrack.collectAsState()
    // A one-second tick so the running timer counts up live.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val runningList = entries.filter { it.running }
    val running = runningList.firstOrNull()
    LaunchedEffect(runningList.size) {
        while (runningList.isNotEmpty()) { now = System.currentTimeMillis(); delay(1000) }
    }

    var day by remember { mutableStateOf(LocalDate.now(zone)) }
    val winStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val winEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val actById = activities.associateBy { it.id }

    val dayEntries = remember(entries, day, now) {
        entries.filter { TimeTracking.minutesInWindow(it.startMillis, it.endMillis, winStart, winEnd, now) > 0 }
            .sortedByDescending { it.startMillis }
    }
    val totals = remember(entries, day, now) { TimeTracking.totalsByActivity(entries, winStart, winEnd, now) }
    val dayTotalMin = totals.sumOf { it.minutes }

    var showNewActivity by remember { mutableStateOf(false) }
    var editActivity by remember { mutableStateOf<TimeActivityEntity?>(null) }
    var showManual by remember { mutableStateOf(false) }
    var editEntry by remember { mutableStateOf<TimeEntryEntity?>(null) }

    if (showNewActivity) ActivityDialog(null, onDismiss = { showNewActivity = false }) { name, emoji, color, goal ->
        vm.createTimeActivity(name, emoji, color, goal); showNewActivity = false
    }
    editActivity?.let { a ->
        ActivityDialog(
            a, onDismiss = { editActivity = null }, onDelete = { vm.deleteTimeActivity(a.id); editActivity = null },
            habitLinks = habits.filter { !it.archived }.map { it.id to it.name },
            linkedHabitId = habits.firstOrNull { it.timeActivityId == a.id }?.id,
            onLinkHabit = { hid ->
                habits.filter { it.timeActivityId == a.id }.forEach { vm.setHabitTimeActivity(it.id, null) }
                hid?.let { vm.setHabitTimeActivity(it, a.id) }
            },
        ) { name, emoji, color, goal ->
            vm.updateTimeActivity(a.copy(name = name, emoji = emoji, colorArgb = color, goalMinutesPerDay = goal)); editActivity = null
        }
    }
    if (showManual) ManualEntryDialog(activities, day, zone, onDismiss = { showManual = false }) { actId, start, end ->
        vm.addManualTimeEntry(actId, start, end); showManual = false
    }
    editEntry?.let { e ->
        EditEntryDialog(e, activities.filter { !it.archived }, zone, onDismiss = { editEntry = null },
            onDelete = { vm.deleteTimeEntry(e.id); editEntry = null },
            onSplit = { at -> vm.splitTimeEntry(e.id, at); editEntry = null },
            onSave = { updated -> vm.updateTimeEntry(updated); editEntry = null })
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Time") },
            navigationIcon = { if (!embedded) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            // Running banner(s) — U15 multi-timer aware, U3 pause/resume.
            if (runningList.isEmpty()) {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f)) {
                    Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val p = paused
                        if (p != null) {
                            val pAct = actById[p.first]
                            Column(Modifier.weight(1f)) {
                                Text("Paused", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text((pAct?.emoji?.plus(" ") ?: "") + (pAct?.name ?: "activity"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { vm.clearPaused() }) { Text("Dismiss") }
                            Spacer(Modifier.width(4.dp))
                            FilledTonalButton(onClick = { vm.resumeTracking() }) {
                                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Resume")
                            }
                        } else {
                            Text("Not tracking — tap an activity to start.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    runningList.forEach { r ->
                        val rAct = actById[r.activityId]
                        val rc = rAct?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = rc.copy(alpha = .14f)) {
                            Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(rc)); Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text((rAct?.emoji?.plus(" ") ?: "") + (rAct?.name ?: "—"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val elapsed = ((now - r.startMillis) / 1000).coerceAtLeast(0)
                                    Text("%d:%02d:%02d".format(elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                                if (runningList.size == 1) {
                                    IconButton(onClick = { vm.pauseTracking() }) { Icon(Icons.Filled.Pause, "Pause") }
                                }
                                FilledTonalButton(onClick = { vm.stopTimeEntry(r.id) }) {
                                    Icon(Icons.Filled.Stop, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Stop")
                                }
                            }
                        }
                    }
                }
            }

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

            // Activity tiles — one tap to start / stop.
            Text("Activities", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                activities.filter { !it.archived }.forEach { a ->
                    val isRun = runningList.any { it.activityId == a.id }
                    val c = a.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                    Surface(
                        onClick = { if (isRun) runningList.filter { it.activityId == a.id }.forEach { vm.stopTimeEntry(it.id) } else vm.startTimeTracking(a.id) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isRun) c.copy(alpha = .22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f),
                        border = if (isRun) androidx.compose.foundation.BorderStroke(1.5.dp, c) else null,
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(8.dp))
                            Text((a.emoji?.plus(" ") ?: "") + a.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
                Surface(onClick = { showNewActivity = true }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .3f)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, "New activity", modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New")
                    }
                }
            }
            if (activities.isEmpty()) Text("Add a few activities like “Deep work”, “Reading”, or “Exercise”.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Day navigator.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { day = day.minusDays(1) }) { Icon(Icons.Filled.ChevronLeft, "Previous day") }
                Text(
                    if (day == LocalDate.now(zone)) "Today · ${fmtDur(dayTotalMin)}" else "${day.format(DateTimeFormatter.ofPattern("EEE, MMM d"))} · ${fmtDur(dayTotalMin)}",
                    Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = { if (day < LocalDate.now(zone)) day = day.plusDays(1) }, enabled = day < LocalDate.now(zone)) { Icon(Icons.Filled.ChevronRight, "Next day") }
            }

            // Per-activity totals (bars).
            if (totals.isNotEmpty()) AppCard {
                val max = totals.maxOf { it.minutes }.coerceAtLeast(1)
                totals.forEach { t ->
                    val a = actById[t.activityId]
                    val c = a?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                    // T4: an activity with a daily goal shows progress toward it; the bar fills against the
                    // goal (else against the day's largest activity), and reads met with a ✓.
                    val goalMin = a?.goalMinutesPerDay ?: 0
                    val goalMet = goalMin in 1..t.minutes
                    val frac = if (goalMin > 0) (t.minutes / goalMin.toFloat()).coerceIn(0f, 1f) else t.minutes / max.toFloat()
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text((a?.emoji?.plus(" ") ?: "") + (a?.name ?: "—"), Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth(frac).height(14.dp).clip(RoundedCornerShape(7.dp)).background(if (goalMet) MaterialTheme.colorScheme.tertiary else c))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            fmtDur(t.minutes) + (if (goalMin > 0) " / ${fmtDur(goalMin)}" else "") + (if (goalMet) " ✓" else ""),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                            color = if (goalMet) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                        )
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
                    val byHour = remember(entries, day, now) { TimeInsights.minutesByHour(entries, winStart, winEnd, now) }
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
                    val dist = remember(entries, day) { TimeInsights.durationDistribution(entries, winStart, winEnd) }
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
                    val tagTotals = remember(entries, day, now) { TimeInsights.totalsByTag(entries, winStart, winEnd, now) }
                    if (tagTotals.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("By tag", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tagTotals.forEach { tt -> AssistChip(onClick = {}, label = { Text("#${tt.tag} · ${fmtDur(tt.minutes)}") }) }
                        }
                    }
                }
            }

            // U12 · automations — "when I start X, do Y". Fully on-device.
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

            // Timeline of the day's entries.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Timeline", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { showManual = true }, enabled = activities.isNotEmpty()) { Text("＋ Add past entry") }
            }
            if (dayEntries.isEmpty()) {
                Text("No time logged this day yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(dayEntries, key = { it.id }) { e ->
                    val a = actById[e.activityId]
                    val c = a?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                    Surface(onClick = { editEntry = e }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text((a?.emoji?.plus(" ") ?: "") + (a?.name ?: "—") + if (e.running) "  · running" else "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val startTxt = Instant.ofEpochMilli(e.startMillis).atZone(zone).format(timeFmt)
                                val endTxt = e.endMillis?.let { Instant.ofEpochMilli(it).atZone(zone).format(timeFmt) } ?: "now"
                                Text("$startTxt – $endTxt" + (e.note.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(fmtDur(e.minutes(now)), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = c)
                        }
                    }
                }
            }
        }
    }
}

/** New/edit an activity: name, optional emoji, a colour swatch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityDialog(
    existing: TimeActivityEntity?, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null,
    habitLinks: List<Pair<String, String>> = emptyList(), linkedHabitId: String? = null, onLinkHabit: (String?) -> Unit = {},
    onSave: (String, String?, Long?, Int) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var emoji by remember { mutableStateOf(existing?.emoji ?: "") }
    var color by remember { mutableStateOf(existing?.colorArgb ?: PALETTE.first()) }
    var goal by remember { mutableStateOf(existing?.goalMinutesPerDay?.takeIf { it > 0 }?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New activity" else "Edit activity") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(emoji, { emoji = it.take(2) }, label = { Text("Emoji (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                // T4: an optional daily time goal (minutes). Progress is computed from tracked intervals.
                OutlinedTextField(goal, { v -> goal = v.filter { it.isDigit() }.take(4) }, label = { Text("Daily goal (minutes, optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PALETTE.forEach { swatch ->
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(Color(swatch))
                                .clickable { color = swatch }
                                .then(if (color == swatch) Modifier.padding(2.dp) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) { if (color == swatch) Text("✓", color = Color.White) }
                    }
                }
                // T3 (I4): link this activity to a habit — tracking it then counts the habit, sharing one goal.
                if (habitLinks.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("Counts toward habit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = linkedHabitId == null, onClick = { onLinkHabit(null) }, label = { Text("None") })
                        habitLinks.forEach { (hid, hname) ->
                            FilterChip(selected = linkedHabitId == hid, onClick = { onLinkHabit(hid) }, label = { Text(hname, maxLines = 1) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), emoji.trim().ifBlank { null }, color, goal.toIntOrNull() ?: 0) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Add a past interval: pick an activity, then a start and end time on the selected day (native pickers). */
@Composable
private fun ManualEntryDialog(activities: List<TimeActivityEntity>, day: LocalDate, zone: ZoneId, onDismiss: () -> Unit, onAdd: (String, Long, Long) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var activityId by remember { mutableStateOf(activities.firstOrNull { !it.archived }?.id) }
    var startMin by remember { mutableStateOf(9 * 60) }   // minutes from midnight
    var endMin by remember { mutableStateOf(10 * 60) }
    fun pick(initial: Int, onPicked: (Int) -> Unit) {
        android.app.TimePickerDialog(ctx, { _, h, m -> onPicked(h * 60 + m) }, initial / 60, initial % 60, true).show()
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
    var action by remember { mutableStateOf(com.todocompanion.app.domain.AutomationRule.ACTION_NOTIFY) }
    var notifyText by remember { mutableStateOf("") }
    var startId by remember { mutableStateOf(activities.getOrNull(1)?.id ?: activities.firstOrNull()?.id ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New automation") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("When I start", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    activities.forEach { a ->
                        FilterChip(selected = a.id == whenId, onClick = { whenId = a.id }, label = { Text((a.emoji?.plus(" ") ?: "") + a.name) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Then", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = action == com.todocompanion.app.domain.AutomationRule.ACTION_NOTIFY, onClick = { action = com.todocompanion.app.domain.AutomationRule.ACTION_NOTIFY }, label = { Text("Notify me") })
                    FilterChip(selected = action == com.todocompanion.app.domain.AutomationRule.ACTION_START, onClick = { action = com.todocompanion.app.domain.AutomationRule.ACTION_START }, label = { Text("Start another") })
                }
                Spacer(Modifier.height(8.dp))
                if (action == com.todocompanion.app.domain.AutomationRule.ACTION_NOTIFY) {
                    OutlinedTextField(notifyText, { notifyText = it }, label = { Text("Message (e.g. Phone on silent?)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                } else {
                    Text("Requires “Allow overlapping timers” on.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        activities.filter { it.id != whenId }.forEach { a ->
                            FilterChip(selected = a.id == startId, onClick = { startId = a.id }, label = { Text((a.emoji?.plus(" ") ?: "") + a.name) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            val valid = whenId.isNotBlank() && (action == com.todocompanion.app.domain.AutomationRule.ACTION_NOTIFY && notifyText.isNotBlank() || action == com.todocompanion.app.domain.AutomationRule.ACTION_START && startId.isNotBlank())
            TextButton(enabled = valid, onClick = {
                onSave(com.todocompanion.app.domain.AutomationRule(
                    id = java.util.UUID.randomUUID().toString(), whenActivityId = whenId,
                    actionType = action, notifyText = notifyText.trim(), startActivityId = startId))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Edit a logged entry: adjust its times, reassign the activity, tag it, split it, or delete it. */
@Composable
private fun EditEntryDialog(entry: TimeEntryEntity, activities: List<TimeActivityEntity>, zone: ZoneId, onDismiss: () -> Unit, onDelete: () -> Unit, onSplit: (Long) -> Unit, onSave: (TimeEntryEntity) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var note by remember { mutableStateOf(entry.note) }
    var tags by remember { mutableStateOf(entry.tags) }
    var activityId by remember { mutableStateOf(entry.activityId) }
    var start by remember { mutableStateOf(entry.startMillis) }
    var end by remember { mutableStateOf(entry.endMillis) }
    val fmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val activity = activities.firstOrNull { it.id == activityId }
    fun pick(initial: Long, onPicked: (Long) -> Unit) {
        val z = Instant.ofEpochMilli(initial).atZone(zone)
        android.app.TimePickerDialog(ctx, { _, h, m ->
            onPicked(z.toLocalDate().atStartOfDay(zone).plusHours(h.toLong()).plusMinutes(m.toLong()).toInstant().toEpochMilli())
        }, z.hour, z.minute, true).show()
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
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                // U11: comma-separated tags.
                OutlinedTextField(tags, { tags = it }, label = { Text("Tags (comma-separated)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
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
