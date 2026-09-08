package com.todocompanion.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared schedule dialogs. Relocated out of TaskDetailScreen (where they were `internal`) so the shared
 * DateReminderSheet and Settings depend on the design-system layer, not on a screen — the seam the task
 * editor overhaul rebuilds around. Behaviour is unchanged.
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepeatDialog(rule: String?, hasChildren: Boolean, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    val r0 = com.todocompanion.app.domain.recurrence.Recurrence.parse(rule)
    var freq by remember { mutableStateOf(r0?.freq) }   // null = does not repeat
    var interval by remember { mutableIntStateOf(r0?.interval ?: 1) }
    var days by remember { mutableStateOf(r0?.byDays ?: emptySet<Int>()) }
    // end: 0 never, 1 until, 2 count
    var endMode by remember { mutableIntStateOf(if (r0?.untilEpochDay != null) 1 else if (r0?.count != null) 2 else 0) }
    var until by remember { mutableLongStateOf(r0?.untilEpochDay ?: java.time.LocalDate.now().plusMonths(3).toEpochDay()) }
    var count by remember { mutableIntStateOf(r0?.count ?: 10) }
    var showUntil by remember { mutableStateOf(false) }
    // Monthly mode: 0 day-of-month, 1 nth weekday, 2 first working day. + regenerate-from-completion.
    var monthMode by remember { mutableIntStateOf(if (r0?.firstWorkday == true) 2 else if (r0?.bySetPos != null && r0.byWeekday != null) 1 else 0) }
    var pos by remember { mutableIntStateOf(r0?.bySetPos ?: 1) }
    var weekday by remember { mutableIntStateOf(r0?.byWeekday ?: 1) }
    var fromCompletion by remember { mutableStateOf(r0?.fromCompletion ?: false) }
    var subtaskReset by remember { mutableStateOf(r0?.subtaskReset ?: "all") }

    fun build(): String? {
        val f = freq ?: return null
        val isMonthly = f == com.todocompanion.app.domain.recurrence.Freq.MONTHLY
        return com.todocompanion.app.domain.recurrence.Recurrence.encode(
            com.todocompanion.app.domain.recurrence.Recur(
                freq = f, interval = interval.coerceAtLeast(1),
                byDays = if (f == com.todocompanion.app.domain.recurrence.Freq.WEEKLY) days else emptySet(),
                bySetPos = if (isMonthly && monthMode == 1) pos else null,
                byWeekday = if (isMonthly && monthMode == 1) weekday else null,
                firstWorkday = isMonthly && monthMode == 2,
                fromCompletion = fromCompletion,
                subtaskReset = subtaskReset,
                untilEpochDay = if (endMode == 1) until else null,
                count = if (endMode == 2) count.coerceAtLeast(1) else null,
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(build()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Repeat") },
        text = {
            Column {
                val freqs = listOf<Pair<com.todocompanion.app.domain.recurrence.Freq?, String>>(
                    null to "None",
                    com.todocompanion.app.domain.recurrence.Freq.DAILY to "Daily",
                    com.todocompanion.app.domain.recurrence.Freq.WEEKDAYS to "Weekday",
                    com.todocompanion.app.domain.recurrence.Freq.WEEKLY to "Weekly",
                    com.todocompanion.app.domain.recurrence.Freq.MONTHLY to "Monthly",
                    com.todocompanion.app.domain.recurrence.Freq.YEARLY to "Yearly",
                )
                OptionChips(freqs.map { it.first }, freq, { freq = it }, spacing = 6) { f -> freqs.first { it.first == f }.second }
                if (freq != null && freq != com.todocompanion.app.domain.recurrence.Freq.WEEKDAYS) {
                    Spacer(Modifier.size(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Every", Modifier.padding(end = 8.dp))
                        Stepper(interval, { interval = it.coerceIn(1, 99) })
                    }
                }
                if (freq == com.todocompanion.app.domain.recurrence.Freq.WEEKLY) {
                    Spacer(Modifier.size(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 7 to "S").forEach { (d, l) ->
                            FilterChip(selected = d in days, onClick = { days = if (d in days) days - d else days + d }, label = { Text(l) })
                        }
                    }
                }
                if (freq == com.todocompanion.app.domain.recurrence.Freq.MONTHLY) {
                    Spacer(Modifier.size(8.dp))
                    OptionChips(listOf(0, 1, 2), monthMode, { monthMode = it }, spacing = 6) {
                        when (it) { 0 -> "On day of month"; 1 -> "On a weekday"; else -> "First working day" }
                    }
                    if (monthMode == 1) {
                        Spacer(Modifier.size(6.dp))
                        OptionChips(listOf(1, 2, 3, 4, -1), pos, { pos = it }, spacing = 4) {
                            when (it) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; 4 -> "4th"; else -> "Last" }
                        }
                        Spacer(Modifier.size(4.dp))
                        OptionChips(listOf(1, 2, 3, 4, 5, 6, 7), weekday, { weekday = it }, spacing = 4) {
                            when (it) { 1 -> "M"; 2 -> "T"; 3 -> "W"; 4 -> "T"; 5 -> "F"; 6 -> "S"; else -> "S" }
                        }
                    }
                }
                if (freq != null) {
                    Spacer(Modifier.size(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Repeat after completion", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = fromCompletion, onCheckedChange = { fromCompletion = it })
                    }
                }
                if (freq != null && hasChildren) {
                    Spacer(Modifier.size(10.dp)); Text("Subtasks each cycle", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OptionChips(listOf("all", "allDone", "keep"), subtaskReset, { subtaskReset = it }, spacing = 6) {
                        when (it) { "all" -> "Reset all"; "allDone" -> "Only if all done"; else -> "Keep" }
                    }
                }
                if (freq != null) {
                    Spacer(Modifier.size(12.dp)); Text("Ends", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OptionChips(listOf(0, 1, 2), endMode, { endMode = it }, spacing = 6) {
                        when (it) { 0 -> "Never"; 1 -> "On date"; else -> "After N" }
                    }
                    if (endMode == 1) TextButton(onClick = { showUntil = true }) { Text("Until " + java.time.LocalDate.ofEpochDay(until)) }
                    if (endMode == 2) Row(verticalAlignment = Alignment.CenterVertically) { Text("After", Modifier.padding(end = 8.dp)); Stepper(count, { count = it.coerceIn(1, 999) }); Text(" times", Modifier.padding(start = 6.dp)) }
                }
            }
        },
    )
    if (showUntil) {
        val z = java.time.ZoneId.systemDefault()
        DateTimePickerDialog(java.time.LocalDate.ofEpochDay(until).atStartOfDay(z).toInstant().toEpochMilli(), { showUntil = false }) { m ->
            until = java.time.Instant.ofEpochMilli(m).atZone(z).toLocalDate().toEpochDay(); showUntil = false
        }
    }
}

/** Flexible duration picker — any hours and minutes, not fixed presets. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DurationPickerDialog(initialMin: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var hours by remember { mutableIntStateOf((initialMin / 60).coerceIn(0, 99)) }
    var mins by remember { mutableIntStateOf((initialMin % 60).coerceIn(0, 59)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Duration") },
        text = {
            Column {
                Stepper(hours, { hours = it }, min = 0, max = 99, step = 1, label = "Hours", editable = true)
                Spacer(Modifier.height(8.dp))
                Stepper(mins, { mins = it }, min = 0, max = 59, step = 5, label = "Minutes", editable = true)   // ± nudges by 5; type any minute directly
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 45, 60, 90, 120, 180, 240).forEach { m ->
                        FilterChip(selected = hours * 60 + mins == m, onClick = { hours = m / 60; mins = m % 60 },
                            label = { Text(fmtDuration(m)) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("= ${fmtDuration((hours * 60 + mins).coerceAtLeast(0))}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(hours * 60 + mins) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
