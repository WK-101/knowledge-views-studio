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

    // A one-second tick so the running timer counts up live.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val running = entries.firstOrNull { it.running }
    LaunchedEffect(running?.id) {
        while (running != null) { now = System.currentTimeMillis(); delay(1000) }
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
        EditEntryDialog(e, actById[e.activityId], zone, onDismiss = { editEntry = null },
            onDelete = { vm.deleteTimeEntry(e.id); editEntry = null },
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
            // Running banner.
            val runAct = running?.let { actById[it.activityId] }
            Surface(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                color = if (running != null) (runAct?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary).copy(alpha = .14f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f),
            ) {
                Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (running != null && runAct != null) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(runAct.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text((runAct.emoji?.plus(" ") ?: "") + runAct.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val elapsed = ((now - running.startMillis) / 1000).coerceAtLeast(0)
                            Text("%d:%02d:%02d".format(elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        FilledTonalButton(onClick = { vm.stopTimeTracking() }) {
                            Icon(Icons.Filled.Stop, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Stop")
                        }
                    } else {
                        Text("Not tracking — tap an activity to start.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Activity tiles — one tap to start / stop.
            Text("Activities", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                activities.filter { !it.archived }.forEach { a ->
                    val isRun = running?.activityId == a.id
                    val c = a.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                    Surface(
                        onClick = { if (isRun) vm.stopTimeTracking() else vm.startTimeTracking(a.id) },
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

/** Edit a logged entry: adjust its times, note, or delete it. */
@Composable
private fun EditEntryDialog(entry: TimeEntryEntity, activity: TimeActivityEntity?, zone: ZoneId, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (TimeEntryEntity) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var note by remember { mutableStateOf(entry.note) }
    var start by remember { mutableStateOf(entry.startMillis) }
    var end by remember { mutableStateOf(entry.endMillis) }
    val fmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
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
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { pick(start) { start = it } }) { Text("Start ${Instant.ofEpochMilli(start).atZone(zone).format(fmt)}") }
                    if (end != null) FilledTonalButton(onClick = { pick(end!!) { end = it } }) { Text("End ${Instant.ofEpochMilli(end!!).atZone(zone).format(fmt)}") }
                    else Text("running", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (end != null && end!! <= start) Text("End must be after start.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = end == null || end!! > start, onClick = { onSave(entry.copy(startMillis = start, endMillis = end, note = note.trim())) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
