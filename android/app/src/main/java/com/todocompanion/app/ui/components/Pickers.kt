package com.todocompanion.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The full result of the unified Date & Reminder sheet — everything a task's schedule needs, set in one
 *  place. A null [dueMillis] means "clear the date". Time is optional: [hasTime] false = an all-day date. */
data class DateChoice(
    val dueMillis: Long?,
    val hasTime: Boolean,
    val allDay: Boolean,
    val durationMin: Int?,
    val rrule: String?,
    val reminderOffsetMin: Int?,   // null = no reminder; 0 = on time; N = minutes before due
)

/**
 * TickTick-style unified Date & Reminder bottom sheet — the SINGLE place for a task's whole schedule:
 * date, an OPTIONAL time (adding a date no longer forces a time), all-day, duration, reminder and repeat.
 * Replaces the scattered task-editor controls entirely. When [reminderSlot] is supplied the full reminder
 * manager renders inside the sheet (so reminders live here too, not in a separate section outside). Fully
 * offline & theme-aware.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DateReminderSheet(
    initialDue: Long?,
    initialHasTime: Boolean,
    initialAllDay: Boolean,
    initialDurationMin: Int?,
    initialRrule: String?,
    initialReminderOffsetMin: Int?,
    onDismiss: () -> Unit,
    onConfirm: (DateChoice) -> Unit,
    reminderSlot: (@Composable () -> Unit)? = null,
) {
    val zone = ZoneId.systemDefault()
    val initialDt = initialDue?.let { Instant.ofEpochMilli(it).atZone(zone) }
    var date by remember { mutableStateOf(initialDt?.toLocalDate() ?: java.time.LocalDate.now(zone)) }
    var hasDate by remember { mutableStateOf(initialDue != null) }
    var time by remember { mutableStateOf(if (initialHasTime && initialDt != null) LocalTime.of(initialDt.hour, initialDt.minute) else null) }
    var allDay by remember { mutableStateOf(initialAllDay) }
    var durationMin by remember { mutableStateOf(initialDurationMin) }
    var rrule by remember { mutableStateOf(initialRrule) }
    var reminder by remember { mutableStateOf(initialReminderOffsetMin) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showRepeat by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    val hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

    fun confirm() {
        val effectiveAllDay = allDay || (hasDate && time == null)
        val due = if (!hasDate) null else java.time.LocalDateTime.of(date, time ?: LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli()
        onConfirm(DateChoice(
            dueMillis = due,
            hasTime = hasDate && time != null && !effectiveAllDay,
            allDay = effectiveAllDay,
            durationMin = if (!effectiveAllDay) durationMin else null,
            rrule = rrule,
            reminderOffsetMin = if (reminderSlot != null) null else reminder,
        ))
    }

    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Header: cancel · title · confirm — one calm row.
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.IconButton(onClick = onDismiss) { androidx.compose.material3.Icon(Icons.Filled.Close, "Cancel") }
                Text("Schedule", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                androidx.compose.material3.FilledTonalButton(onClick = { confirm() }) {
                    androidx.compose.material3.Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Done")
                }
            }
            // Month calendar (themed M3 DatePicker, no separate time step).
            val dateState = rememberDatePickerState(initialSelectedDateMillis = initialDue ?: date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePicker(state = dateState, showModeToggle = false, title = null,
                headline = null, colors = androidx.compose.material3.DatePickerDefaults.colors())
            androidx.compose.runtime.LaunchedEffect(dateState.selectedDateMillis) {
                dateState.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(); hasDate = true }
            }

            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            // All-day is top-level (not tab-gated): turning it on clears the time and duration.
            SheetToggleRow("All day", allDay) { allDay = it; if (it) { time = null; durationMin = null } }
            if (!allDay) {
                // Time — OPTIONAL. Tap to add/change; clear with ✕. (The "don't force a time" behaviour.)
                SheetRow(icon = Icons.Filled.Schedule, label = "Time",
                    value = time?.format(hm) ?: "None",
                    onClear = if (time != null) ({ time = null; durationMin = null }) else null,
                    onClick = { showTimePicker = true })
                // Duration only means something once there's a start time (end = start + duration).
                if (time != null) {
                    Text("Duration", Modifier.padding(start = 34.dp, top = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(Modifier.padding(start = 34.dp, top = 4.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf<Pair<Int?, String>>(null to "None", 15 to "15m", 30 to "30m", 45 to "45m", 60 to "1h", 90 to "1½h", 120 to "2h", 240 to "4h").forEach { (m, l) ->
                            FilterChip(selected = durationMin == m, onClick = { durationMin = m }, label = { Text(l) })
                        }
                    }
                }
            }
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            // Reminders: the full manager renders here (via the slot) so reminders live inside the sheet.
            if (reminderSlot != null) {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(Icons.Filled.Notifications, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp)); Text("Reminders", style = MaterialTheme.typography.bodyLarge)
                }
                Box(Modifier.padding(start = 34.dp, bottom = 4.dp)) { reminderSlot() }
            } else {
                SheetRow(icon = Icons.Filled.Notifications, label = "Reminder",
                    value = reminderLabelOffset(reminder),
                    onClear = if (reminder != null) ({ reminder = null }) else null,
                    onClick = { showReminder = true })
            }
            // Repeat row (optional).
            SheetRow(icon = Icons.Filled.Repeat, label = "Repeat",
                value = repeatLabel(rrule),
                onClear = if (rrule != null) ({ rrule = null }) else null,
                onClick = { showRepeat = true })
            if (hasDate) TextButton(onClick = { hasDate = false; time = null; onConfirm(DateChoice(null, false, false, null, rrule, if (reminderSlot != null) null else reminder)) }) {
                Text("Clear date", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showTimePicker) {
        val ts = rememberTimePickerState(initialHour = time?.hour ?: 9, initialMinute = time?.minute ?: 0)
        Dialog(onDismissRequest = { showTimePicker = false }) {
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                    TimePicker(state = ts)
                    androidx.compose.foundation.layout.Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
                        TextButton(onClick = { time = LocalTime.of(ts.hour, ts.minute); allDay = false; showTimePicker = false }) { Text("OK") }
                    }
                }
            }
        }
    }
    if (showReminder) PickListDialog("Reminder", listOf<Pair<Int?, String>>(null to "None", 0 to "On time", 5 to "5 min before", 15 to "15 min before", 30 to "30 min before", 60 to "1 hour before", 1440 to "1 day before"), onDismiss = { showReminder = false }) { reminder = it; showReminder = false }
    if (showRepeat) PickListDialog("Repeat", listOf<Pair<String?, String>>(null to "None", "FREQ=DAILY" to "Daily", "FREQ=WEEKLY" to "Weekly", "FREQ=MONTHLY" to "Monthly", "FREQ=YEARLY" to "Yearly", "FREQ=WEEKLY;INTERVAL=2" to "Every 2 weeks", "FREQ=DAILY;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR" to "Weekdays"), onDismiss = { showRepeat = false }) { rrule = it; showRepeat = false }
}

private fun fmtDuration(min: Int): String = when {
    min < 60 -> "${min}m"
    min % 60 == 0 -> "${min / 60}h"
    else -> "${min / 60}h ${min % 60}m"
}
private fun reminderLabelOffset(off: Int?): String = when (off) {
    null -> "None"; 0 -> "On time"; 1440 -> "1 day before"; 60 -> "1 hour before"
    else -> "$off min before"
}
private fun repeatLabel(rrule: String?): String = when {
    rrule == null -> "None"
    rrule.contains("BYDAY=MO,TU,WE,TH,FR") -> "Weekdays"
    rrule.contains("INTERVAL=2") && rrule.contains("WEEKLY") -> "Every 2 weeks"
    rrule.contains("DAILY") -> "Daily"
    rrule.contains("WEEKLY") -> "Weekly"
    rrule.contains("MONTHLY") -> "Monthly"
    rrule.contains("YEARLY") -> "Yearly"
    else -> "Custom"
}

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, onClear: (() -> Unit)?, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (value == "None") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
        if (onClear != null) androidx.compose.material3.IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) { androidx.compose.material3.Icon(Icons.Filled.Close, "Clear", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun SheetToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> PickListDialog(title: String, options: List<Pair<T, String>>, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column {
                options.forEach { (v, l) ->
                    Text(l, Modifier.fillMaxWidth().clickable { onPick(v) }.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
    )
}

/** A themed Material 3 time picker in a dialog, returning the chosen minute-of-day. Used wherever the app
 *  needs a time (past-entry start/end, edit entry) so one UI is used everywhere, not the OS dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeFieldDialog(initialMinuteOfDay: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val ts = rememberTimePickerState(initialHour = (initialMinuteOfDay / 60).coerceIn(0, 23), initialMinute = (initialMinuteOfDay % 60).coerceIn(0, 59), is24Hour = true)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
            androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                TimePicker(state = ts)
                androidx.compose.foundation.layout.Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onConfirm(ts.hour * 60 + ts.minute) }) { Text("OK") }
                }
            }
        }
    }
}

/**
 * Two-step date → time picker. Returns the chosen instant as epoch millis (local zone).
 * When [onDuration] is supplied, the time step also offers an optional block duration.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DateTimePickerDialog(
    initial: Long?,
    onDismiss: () -> Unit,
    initialDurationMin: Int? = null,
    onDuration: ((Int?) -> Unit)? = null,
    onConfirm: (Long) -> Unit,
) {
    var pickedDateUtc by remember { mutableStateOf<Long?>(null) }
    var duration by remember { mutableStateOf(initialDurationMin) }
    val initialDt = initial?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }

    if (pickedDateUtc == null) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = { pickedDateUtc = dateState.selectedDateMillis }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) { DatePicker(state = dateState) }
    } else {
        val timeState = rememberTimePickerState(
            initialHour = initialDt?.hour ?: 9,
            initialMinute = initialDt?.minute ?: 0,
        )
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                Box(Modifier.padding(16.dp)) {
                    androidx.compose.foundation.layout.Column {
                        TimePicker(state = timeState)
                        // Optional duration (TickTick-style time block): turns a single instant into a
                        // start→end span the calendar can lay out. Only shown when the caller opts in.
                        if (onDuration != null) {
                            Text("Duration", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf<Pair<Int?, String>>(null to "None", 15 to "15m", 30 to "30m", 45 to "45m", 60 to "1h", 90 to "1½h", 120 to "2h", 240 to "4h").forEach { (mins, label) ->
                                    FilterChip(selected = duration == mins, onClick = { duration = mins }, label = { Text(label) })
                                }
                            }
                        }
                        androidx.compose.foundation.layout.Row(
                            Modifier.padding(top = 8.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                        ) {
                            TextButton(onClick = onDismiss) { Text("Cancel") }
                            TextButton(onClick = {
                                val dateUtc = pickedDateUtc ?: System.currentTimeMillis()
                                val localDate = Instant.ofEpochMilli(dateUtc).atZone(ZoneOffset.UTC).toLocalDate()
                                val dt = LocalDateTime.of(localDate, LocalTime.of(timeState.hour, timeState.minute))
                                val millis = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                onDuration?.invoke(duration)
                                onConfirm(millis)
                            }) { Text("OK") }
                        }
                    }
                }
            }
        }
    }
}
