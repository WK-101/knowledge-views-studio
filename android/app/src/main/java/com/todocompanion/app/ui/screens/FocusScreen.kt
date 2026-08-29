package com.todocompanion.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.todocompanion.app.ui.AppViewModel
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * Focus is a MODE of time tracking, not a separate stopwatch. Pressing Start creates a *running*
 * kind="focus" interval on the one timeline through [AppViewModel.startFocusSession], so a focus block is
 * tracked time the instant it begins — it shows in the running-timer bar and the calendar, and every focus
 * statistic is derived from those same intervals. The ring below is just a live lens over that running
 * interval; Pause banks the elapsed segment and finalizes it, Resume opens a fresh one, and Finish closes
 * the current one (crediting any linked habit through the normal finalize path). No second data store.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FocusScreen(vm: AppViewModel, onOpenStats: () -> Unit = {}, modifier: Modifier = Modifier) {
    val tasks by vm.tasks.collectAsState()
    val habits by vm.habits.collectAsState()
    val timeEntries by vm.timeEntries.collectAsState()
    val runningEntry by vm.runningFocus.collectAsState()
    val today = LocalDate.now().toEpochDay()

    // A running focus interval is the single source of truth for "am I focusing right now".
    val running = runningEntry != null
    val segmentStartMs = runningEntry?.startMillis ?: 0L

    // The one-timeline view of focus, so "today" here matches the Time reports exactly.
    val focusList = remember(timeEntries) { vm.focusViews() }
    val todayMinutes = focusList.filter { it.epochDay == today }.sumOf { it.minutes }
    val todayCount = focusList.count { it.epochDay == today }

    var pomo by remember { mutableStateOf(true) }
    var pomoMin by remember { mutableIntStateOf(25) }              // configurable Pomodoro length
    var showCustomPomo by remember { mutableStateOf(false) }       // custom (any HH:MM) focus length
    var focusTaskId by remember { mutableStateOf<String?>(null) }  // the task this session is spent on
    var focusHabitId by remember { mutableStateOf<String?>(null) } // a habit this session credits
    // Seconds banked from earlier (paused) segments of THIS session; the live segment adds on top.
    var bankedSec by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    val pomoSeconds = pomoMin * 60

    fun elapsedNow(): Int = bankedSec + if (running && segmentStartMs > 0) ((System.currentTimeMillis() - segmentStartMs) / 1000L).toInt() else 0

    // Returning to the screen with a session already live (config change / re-nav): adopt its task/habit so
    // the ring shows what's actually running rather than a blank picker.
    LaunchedEffect(runningEntry?.id) {
        val r = runningEntry
        if (r != null && focusTaskId == null && focusHabitId == null) { focusTaskId = r.taskId; focusHabitId = r.habitId }
    }

    fun start(fresh: Boolean) {
        if (fresh) bankedSec = 0
        val target = if (pomo) pomoMin else 0
        val remaining = if (pomo) (pomoSeconds - bankedSec).coerceAtLeast(1) else 0
        vm.startFocusSession(activityId = null, targetMin = target, remainingSec = remaining, taskId = focusTaskId, habitId = focusHabitId)
    }
    fun pause() { bankedSec = elapsedNow(); vm.stopFocus() }
    fun finish() { if (running) vm.stopFocus(); bankedSec = 0 }

    // "Just start" hand-off: a task pre-selected from its detail screen lands here and auto-starts a session.
    val pendingFocus by vm.pendingFocusTaskId.collectAsState()
    LaunchedEffect(pendingFocus) {
        pendingFocus?.let { id ->
            focusTaskId = id; focusHabitId = null
            vm.pendingFocusTaskId.value = null
            if (!running) { bankedSec = 0; start(true) }
        }
    }
    // A habit pre-selected to Focus on — auto-starts, its minutes auto-log on finish via the linked activity.
    val pendingHabit by vm.pendingFocusHabitId.collectAsState()
    LaunchedEffect(pendingHabit) {
        pendingHabit?.let { id ->
            focusHabitId = id; focusTaskId = null
            vm.pendingFocusHabitId.value = null
            habits.firstOrNull { it.id == id }?.let { h -> if (h.unit?.startsWith("min") == true) pomoMin = h.targetPerDay.coerceIn(5, 90) }
            if (!running) { bankedSec = 0; start(true) }
        }
    }

    // Deep-work coach: one-tap start of a Pomodoro of [min] minutes on a chosen task.
    fun startCoach(taskId: String?, min: Int) {
        focusTaskId = taskId; focusHabitId = null
        pomo = true; pomoMin = min.coerceIn(10, 90); bankedSec = 0
        start(true)
    }

    // Tick each second to recompute the display from the wall clock; a Pomodoro that elapsed while away
    // auto-completes (and the background alarm chimes independently).
    LaunchedEffect(running, bankedSec) {
        while (running) {
            delay(1000)
            tick++
            if (pomo && elapsedNow() >= pomoSeconds) { finish(); break }
        }
    }

    tick // read so recomposition tracks the tick
    val elapsed = elapsedNow()
    val display = if (pomo) (pomoSeconds - elapsed).coerceAtLeast(0) else elapsed
    val mm = display / 60; val ss = display % 60
    val accent = MaterialTheme.colorScheme.primary
    val focusTitle = focusTaskId?.let { id -> tasks.firstOrNull { it.id == id }?.title }
    var taskMenu by remember { mutableStateOf(false) }

    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val progress = if (pomo) (elapsed.toFloat() / pomoSeconds).coerceIn(0f, 1f) else ((elapsed % 60) / 60f)

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Top controls: mode + Pomodoro length + task + stats shortcut.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(48.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(selected = pomo, onClick = { if (!running && bankedSec == 0) { pomo = true } }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Pomodoro") }
                    SegmentedButton(selected = !pomo, onClick = { if (!running && bankedSec == 0) { pomo = false } }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Stopwatch") }
                }
            }
            androidx.compose.material3.IconButton(onClick = onOpenStats) { androidx.compose.material3.Icon(Icons.Filled.BarChart, "Statistics") }
        }
        // ---- Deep-work coach: today's focused minutes vs goal, streak, and a one-tap next block ----
        val dwSettings by vm.settings.collectAsState()
        val coach = remember(timeEntries, dwSettings, tasks) { vm.deepWorkStatus() }
        if (!running && bankedSec == 0) {
            Spacer(Modifier.size(10.dp))
            androidx.compose.material3.Surface(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Deep work today", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (coach.streakDays > 0) Text("🔥 ${coach.streakDays}-day streak", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.size(8.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { (coach.todayMin.toFloat() / coach.goalMin).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (coach.todayMin >= coach.goalMin) "Goal met — ${coach.todayMin} min focused today 🎉"
                        else "${coach.todayMin} / ${coach.goalMin} min · ${coach.goalMin - coach.todayMin} to go",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val best = coach.best
                    if (best != null) {
                        Spacer(Modifier.size(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Next block", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(best.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            androidx.compose.material3.Button(onClick = { startCoach(best.id, coach.bestBlockMin) }) {
                                androidx.compose.material3.Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("${coach.bestBlockMin}m")
                            }
                        }
                    }
                }
            }
        }
        if (pomo && !running && bankedSec == 0) {
            Spacer(Modifier.size(10.dp))
            // All lengths + Custom on a single line; scrolls horizontally if the screen is too narrow to fit.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val presets = listOf(15, 25, 45, 60)
                presets.forEach { m ->
                    androidx.compose.material3.FilterChip(selected = pomoMin == m, onClick = { pomoMin = m }, label = { Text("$m") })
                }
                // Custom length: any hours:minutes via the shared time picker, so the presets aren't a cap.
                val isCustom = pomoMin !in presets
                androidx.compose.material3.FilterChip(
                    selected = isCustom,
                    onClick = { showCustomPomo = true },
                    label = { Text(if (isCustom) "$pomoMin" else "Custom") },
                    leadingIcon = { androidx.compose.material3.Icon(Icons.Filled.Tune, null, modifier = Modifier.size(16.dp)) },
                )
            }
        }
        if (showCustomPomo) com.todocompanion.app.ui.components.TimeFieldDialog(
            initialMinuteOfDay = pomoMin.coerceIn(1, 1439),
            onDismiss = { showCustomPomo = false },
            onConfirm = { mins -> pomoMin = mins.coerceIn(1, 1439); showCustomPomo = false },
        )
        Spacer(Modifier.size(12.dp))
        // Task (or habit) the session is spent on.
        val habitTitle = focusHabitId?.let { id -> habits.firstOrNull { it.id == id }?.let { (it.emoji?.plus(" ") ?: "") + it.name } }
        Box {
            androidx.compose.material3.OutlinedButton(onClick = { taskMenu = true }) {
                androidx.compose.material3.Icon(Icons.Filled.Adjust, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(habitTitle ?: focusTitle ?: "Focus on a task…", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            androidx.compose.material3.DropdownMenu(expanded = taskMenu, onDismissRequest = { taskMenu = false }) {
                // Let the priority engine pick — the top actionable task right now (availability-aware).
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("✨ Suggest for me") },
                    leadingIcon = { androidx.compose.material3.Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(18.dp)) },
                    onClick = { vm.topDoNext()?.let { focusTaskId = it.id; focusHabitId = null }; taskMenu = false },
                )
                androidx.compose.material3.HorizontalDivider()
                androidx.compose.material3.DropdownMenuItem(text = { Text("No task") }, onClick = { focusTaskId = null; focusHabitId = null; taskMenu = false })
                tasks.filter { !it.completed && !it.trashed && !it.abandoned }.take(50).forEach { t ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(t.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, onClick = { focusTaskId = t.id; focusHabitId = null; taskMenu = false })
                }
            }
        }
        // Estimate vs. actual for the linked task: focused minutes (from the one timeline) against its estimate.
        focusTaskId?.let { id ->
            val t = tasks.firstOrNull { it.id == id }
            val estimate = t?.estimateMin
            val logged = focusList.filter { it.taskId == id }.sumOf { it.minutes }
            if (estimate != null && estimate > 0) {
                Spacer(Modifier.size(6.dp))
                Column(Modifier.fillMaxWidth(0.8f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Estimated ${estimate}m", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text("$logged m logged", style = MaterialTheme.typography.labelMedium, color = if (logged > estimate) MaterialTheme.colorScheme.error else accent)
                    }
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { (logged.toFloat() / estimate).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(5.dp).clip(CircleShape),
                    )
                }
            } else if (logged > 0) {
                Spacer(Modifier.size(4.dp))
                Text("$logged m logged on this task", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // The ring grows to fill all the space between the controls above and below.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val ring = minOf(minOf(maxWidth, maxHeight) - 16.dp, 360.dp)
            Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val sw = 16.dp.toPx()
                    val inset = sw / 2
                    val arc = Size(size.width - sw, size.height - sw)
                    val off = Offset(inset, inset)
                    drawArc(color = trackColor, startAngle = -90f, sweepAngle = 360f, useCenter = false, topLeft = off, size = arc, style = Stroke(sw, cap = StrokeCap.Round))
                    drawArc(color = accent, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false, topLeft = off, size = arc, style = Stroke(sw, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%02d:%02d".format(mm, ss), fontSize = 68.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(if (pomo) (if (running) "Focusing" else "Focus") else "Elapsed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    focusTitle?.let {
                        Spacer(Modifier.size(6.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = accent, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { if (running) pause() else start(bankedSec == 0) }) {
                Text(if (running) "Pause" else if (bankedSec == 0) "Start" else "Resume")
            }
            if (elapsed > 0) OutlinedButton(onClick = { finish() }) { Text("Finish") }
            if (elapsed > 0 && !running) OutlinedButton(onClick = { bankedSec = 0 }) { Text("Reset") }
        }
        Spacer(Modifier.size(18.dp))
        Text("Today: ${todayMinutes} min · ${todayCount} session${if (todayCount == 1) "" else "s"}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Focus is tracked time — it shows on your timeline and counts once. Keeps running in the background; you'll get a notification when a Pomodoro ends.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
    }
}
