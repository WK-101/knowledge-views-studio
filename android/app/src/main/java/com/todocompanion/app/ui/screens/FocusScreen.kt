package com.todocompanion.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
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
import androidx.compose.runtime.mutableLongStateOf
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
import com.todocompanion.app.reminders.AlarmScheduler
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.time.LocalDate

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FocusScreen(vm: AppViewModel, onOpenStats: () -> Unit = {}, modifier: Modifier = Modifier) {
    val sessions by vm.focusSessions.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val today = LocalDate.now().toEpochDay()
    val todayMinutes = sessions.filter { it.epochDay == today }.sumOf { it.minutes }
    val todayCount = sessions.count { it.epochDay == today }
    val context = LocalContext.current

    var pomo by remember { mutableStateOf(true) }
    var pomoMin by remember { mutableIntStateOf(25) }          // configurable Pomodoro length
    var focusTaskId by remember { mutableStateOf<String?>(null) } // the task this session is spent on
    var running by remember { mutableStateOf(false) }
    // Wall-clock model so the timer stays accurate across backgrounding / process death:
    // baseElapsed = seconds banked before the current run segment; segmentStart = its wall-clock start.
    var baseElapsed by remember { mutableIntStateOf(0) }
    var segmentStart by remember { mutableLongStateOf(0L) }
    var startMillis by remember { mutableLongStateOf(0L) }
    var tick by remember { mutableIntStateOf(0) }
    val pomoSeconds = pomoMin * 60

    fun elapsedNow(): Int = baseElapsed + if (running) ((System.currentTimeMillis() - segmentStart) / 1000L).toInt() else 0

    fun finish() {
        val e = elapsedNow()
        val mins = if (pomo) (minOf(e, pomoSeconds) / 60) else (e / 60)
        if (mins >= 1) vm.recordFocus(startMillis, mins, if (pomo) "pomo" else "stopwatch", focusTaskId)
        running = false; baseElapsed = 0
        AlarmScheduler.cancelFocusDone(context)
    }

    // Tick once a second to recompute from the wall clock; on returning from the background this
    // re-syncs immediately, and a Pomodoro that elapsed while away auto-completes.
    LaunchedEffect(running) {
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

    val track = MaterialTheme.colorScheme.outlineVariant
    val progress = if (pomo) (elapsed.toFloat() / pomoSeconds).coerceIn(0f, 1f) else ((elapsed % 60) / 60f)

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Top controls: mode + Pomodoro length + task + stats shortcut.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(48.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(selected = pomo, onClick = { if (!running) { pomo = true; baseElapsed = 0 } }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Pomodoro") }
                    SegmentedButton(selected = !pomo, onClick = { if (!running) { pomo = false; baseElapsed = 0 } }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Stopwatch") }
                }
            }
            androidx.compose.material3.IconButton(onClick = onOpenStats) { androidx.compose.material3.Icon(Icons.Filled.BarChart, "Statistics") }
        }
        if (pomo && !running && elapsed == 0) {
            Spacer(Modifier.size(10.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 25, 45, 60).forEach { m ->
                    androidx.compose.material3.FilterChip(selected = pomoMin == m, onClick = { pomoMin = m }, label = { Text("$m min") })
                }
            }
        }
        Spacer(Modifier.size(12.dp))
        // Task the session is spent on.
        Box {
            androidx.compose.material3.OutlinedButton(onClick = { taskMenu = true }) {
                androidx.compose.material3.Icon(Icons.Filled.Adjust, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(focusTitle ?: "Focus on a task…", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            androidx.compose.material3.DropdownMenu(expanded = taskMenu, onDismissRequest = { taskMenu = false }) {
                androidx.compose.material3.DropdownMenuItem(text = { Text("No task") }, onClick = { focusTaskId = null; taskMenu = false })
                tasks.filter { !it.completed && !it.trashed && !it.abandoned }.take(50).forEach { t ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(t.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, onClick = { focusTaskId = t.id; taskMenu = false })
                }
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
                    drawArc(color = track, startAngle = -90f, sweepAngle = 360f, useCenter = false, topLeft = off, size = arc, style = Stroke(sw, cap = StrokeCap.Round))
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
            Button(onClick = {
                if (running) {
                    baseElapsed = elapsedNow(); running = false; AlarmScheduler.cancelFocusDone(context)
                } else {
                    if (baseElapsed == 0) startMillis = System.currentTimeMillis()
                    segmentStart = System.currentTimeMillis(); running = true
                    if (pomo) AlarmScheduler.scheduleFocusDone(context, System.currentTimeMillis() + (pomoSeconds - baseElapsed) * 1000L)
                }
            }) {
                Text(if (running) "Pause" else if (elapsed == 0) "Start" else "Resume")
            }
            if (elapsed > 0) OutlinedButton(onClick = { finish() }) { Text("Finish") }
            if (elapsed > 0 && !running) OutlinedButton(onClick = { baseElapsed = 0 }) { Text("Reset") }
        }
        Spacer(Modifier.size(18.dp))
        Text("Today: ${todayMinutes} min · ${todayCount} session${if (todayCount == 1) "" else "s"}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Keeps time in the background; you'll get a notification when a Pomodoro ends.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
    }
}
