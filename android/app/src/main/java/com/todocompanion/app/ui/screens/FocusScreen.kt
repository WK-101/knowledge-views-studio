package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

private const val POMO_SECONDS = 25 * 60

@Composable
fun FocusScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val sessions by vm.focusSessions.collectAsState()
    val today = LocalDate.now().toEpochDay()
    val todayMinutes = sessions.filter { it.epochDay == today }.sumOf { it.minutes }
    val todayCount = sessions.count { it.epochDay == today }
    val context = LocalContext.current

    var pomo by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    // Wall-clock model so the timer stays accurate across backgrounding / process death:
    // baseElapsed = seconds banked before the current run segment; segmentStart = its wall-clock start.
    var baseElapsed by remember { mutableIntStateOf(0) }
    var segmentStart by remember { mutableLongStateOf(0L) }
    var startMillis by remember { mutableLongStateOf(0L) }
    var tick by remember { mutableIntStateOf(0) }

    fun elapsedNow(): Int = baseElapsed + if (running) ((System.currentTimeMillis() - segmentStart) / 1000L).toInt() else 0

    fun finish() {
        val e = elapsedNow()
        val mins = if (pomo) (minOf(e, POMO_SECONDS) / 60) else (e / 60)
        if (mins >= 1) vm.recordFocus(startMillis, mins, if (pomo) "pomo" else "stopwatch")
        running = false; baseElapsed = 0
        AlarmScheduler.cancelFocusDone(context)
    }

    // Tick once a second to recompute from the wall clock; on returning from the background this
    // re-syncs immediately, and a Pomodoro that elapsed while away auto-completes.
    LaunchedEffect(running) {
        while (running) {
            delay(1000)
            tick++
            if (pomo && elapsedNow() >= POMO_SECONDS) { finish(); break }
        }
    }

    tick // read so recomposition tracks the tick
    val elapsed = elapsedNow()
    val display = if (pomo) (POMO_SECONDS - elapsed).coerceAtLeast(0) else elapsed
    val mm = display / 60; val ss = display % 60
    val accent = MaterialTheme.colorScheme.primary

    Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(selected = pomo, onClick = { if (!running) { pomo = true; baseElapsed = 0 } }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Pomodoro") }
            SegmentedButton(selected = !pomo, onClick = { if (!running) { pomo = false; baseElapsed = 0 } }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Stopwatch") }
        }
        Spacer(Modifier.size(36.dp))
        Box(
            Modifier.size(260.dp).clip(CircleShape)
                .background(accent.copy(alpha = if (running) 0.10f else 0.05f))
                .border(3.dp, if (running) accent else MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%02d:%02d".format(mm, ss), fontSize = 62.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(if (pomo) "Focus" else "Elapsed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.size(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                if (running) {
                    // Pause: bank the elapsed time and cancel the pending completion alarm.
                    baseElapsed = elapsedNow(); running = false; AlarmScheduler.cancelFocusDone(context)
                } else {
                    if (baseElapsed == 0) startMillis = System.currentTimeMillis()
                    segmentStart = System.currentTimeMillis(); running = true
                    if (pomo) AlarmScheduler.scheduleFocusDone(context, System.currentTimeMillis() + (POMO_SECONDS - baseElapsed) * 1000L)
                }
            }) {
                Text(if (running) "Pause" else if (elapsed == 0) "Start" else "Resume")
            }
            if (elapsed > 0) OutlinedButton(onClick = { finish() }) { Text("Finish") }
            if (elapsed > 0 && !running) OutlinedButton(onClick = { baseElapsed = 0 }) { Text("Reset") }
        }
        Spacer(Modifier.size(30.dp))
        Text("Today: ${todayMinutes} min · ${todayCount} session${if (todayCount == 1) "" else "s"}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Keeps time in the background; you'll get a notification when a Pomodoro ends.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}
