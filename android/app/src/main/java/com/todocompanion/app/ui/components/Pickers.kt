package com.todocompanion.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.HourglassEmpty
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
    val startMillis: Long? = null,     // R21: optional start date, set inside the same sheet
    val startHasTime: Boolean = false,
    val deadlineMillis: Long? = null,  // R22: the hard deadline, also set inside the same sheet
    val estimateMin: Int? = null,      // R43: the effort estimate, now set beside duration in this sheet
    val estimateSet: Boolean = false,  // whether the sheet managed the estimate (so callers know to apply it)
    // Wave B (F3) — capture reminders match the editor: the offset can anchor to due / start / deadline, or
    // instead be a permission-free place reminder. Only meaningful in capture mode (showReminderAnchors).
    val reminderAnchor: String = "due",   // "due" | "start" | "deadline"
    val reminderPlace: String? = null,    // set → arm a place reminder instead of a time offset
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
    showStart: Boolean = false,
    initialStart: Long? = null,
    initialStartHasTime: Boolean = false,
    showDeadline: Boolean = false,
    initialDeadline: Long? = null,
    repeatHasChildren: Boolean = false,
    showEstimate: Boolean = false,
    initialEstimateMin: Int? = null,
    estimateHint: String? = null,
    // Wave B (F3) — when true, the simple reminder section also offers an anchor (Due/Start/Deadline) and a
    // place option, matching the editor. Used at capture (where a full reminder manager can't run yet).
    showReminderAnchors: Boolean = false,
    initialReminderAnchor: String = "due",
    initialReminderPlace: String? = null,
) {
    val zone = ZoneId.systemDefault()
    val initialDt = initialDue?.let { Instant.ofEpochMilli(it).atZone(zone) }
    var date by remember { mutableStateOf(initialDt?.toLocalDate() ?: java.time.LocalDate.now(zone)) }
    var hasDate by remember { mutableStateOf(initialDue != null) }
    var time by remember { mutableStateOf(if (initialHasTime && initialDt != null) LocalTime.of(initialDt.hour, initialDt.minute) else null) }
    var allDay by remember { mutableStateOf(initialAllDay) }
    var durationMin by remember { mutableStateOf(initialDurationMin) }
    var estimateMin by remember { mutableStateOf(initialEstimateMin) }
    var rrule by remember { mutableStateOf(initialRrule) }
    var reminder by remember { mutableStateOf(initialReminderOffsetMin) }
    var reminderAnchor by remember { mutableStateOf(initialReminderAnchor) }
    var reminderPlace by remember { mutableStateOf(initialReminderPlace) }
    var startMillis by remember { mutableStateOf(initialStart) }
    var startHasTime by remember { mutableStateOf(initialStartHasTime) }
    var deadlineMillis by remember { mutableStateOf(initialDeadline) }
    // The lightweight pickers (time, duration, estimate, reminder offset) expand inline as an accordion
    // — one open at a time, no dialog stacked over the sheet. The heavier sub-editors (repeat, start,
    // deadline) still open a focused dialog: a nested calendar/builder is clearer as its own surface.
    var openSection by remember { mutableStateOf<String?>(null) }
    fun toggle(section: String) { openSection = if (openSection == section) null else section }
    var showRepeat by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showDeadlinePicker by remember { mutableStateOf(false) }
    val hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    val dayFmt = java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d")

    val today = java.time.LocalDate.now(zone)
    fun utcOf(d: java.time.LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    // One DatePicker state drives both the calendar and the quick-date chips, so tapping a chip moves the
    // calendar selection and vice-versa — one coherent control, not two that disagree (R22).
    val dateState = rememberDatePickerState(initialSelectedDateMillis = initialDue ?: utcOf(date))
    fun pick(d: java.time.LocalDate) { dateState.selectedDateMillis = utcOf(d); date = d; hasDate = true }
    fun fmtInstant(ms: Long): String {
        val dt = Instant.ofEpochMilli(ms).atZone(zone)
        val timed = dt.hour != 0 || dt.minute != 0
        return dt.format(dayFmt) + (if (timed) " " + LocalTime.of(dt.hour, dt.minute).format(hm) else "")
    }

    fun confirm() {
        // R41 — a date with no time is all-day ONLY when no duration is set either. Time and Duration are
        // independent: a length picked without a clock time (e.g. "this will take 90m, no fixed time") must
        // survive as a duration, not silently become an all-day entry that drops the length.
        val effectiveAllDay = allDay || (hasDate && time == null && durationMin == null)
        val due = if (!hasDate) null else java.time.LocalDateTime.of(date, time ?: LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli()
        onConfirm(DateChoice(
            dueMillis = due,
            hasTime = hasDate && time != null && !effectiveAllDay,
            allDay = effectiveAllDay,
            durationMin = if (effectiveAllDay) null else durationMin,
            rrule = rrule,
            reminderOffsetMin = if (reminderSlot != null) null else reminder,
            startMillis = startMillis,
            startHasTime = startHasTime,
            deadlineMillis = deadlineMillis,
            estimateMin = estimateMin,
            estimateSet = showEstimate,
            reminderAnchor = reminderAnchor,
            reminderPlace = reminderPlace?.trim()?.ifBlank { null },
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
            // Quick-date chips — the common picks up front so most tasks never touch the calendar.
            val sat = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SATURDAY))
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = hasDate && date == today, onClick = { pick(today) }, label = { Text("Today") })
                FilterChip(selected = hasDate && date == today.plusDays(1), onClick = { pick(today.plusDays(1)) }, label = { Text("Tomorrow") })
                FilterChip(selected = hasDate && date == today.plusDays(3), onClick = { pick(today.plusDays(3)) }, label = { Text("In 3 days") })
                FilterChip(selected = hasDate && date == today.plusWeeks(1), onClick = { pick(today.plusWeeks(1)) }, label = { Text("Next week") })
                FilterChip(selected = hasDate && date == sat, onClick = { pick(sat) }, label = { Text("Weekend") })
            }
            // Month calendar (themed M3 DatePicker, no separate time step).
            DatePicker(state = dateState, showModeToggle = false, title = null,
                headline = null, colors = androidx.compose.material3.DatePickerDefaults.colors())
            androidx.compose.runtime.LaunchedEffect(dateState.selectedDateMillis) {
                dateState.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(); hasDate = true }
            }

            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            // Time & length. All-day sits on the same header line; Time and Duration are INDEPENDENT now —
            // you can set a length without first picking a time, and vice-versa (R22).
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Time & length", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("All day", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Switch(checked = allDay, onCheckedChange = { allDay = it; if (it) time = null })
            }
            if (!allDay) {
                SheetRow(icon = Icons.Filled.Schedule, label = "Time",
                    value = time?.format(hm) ?: "None",
                    onClear = if (time != null) ({ time = null; if (openSection == "time") openSection = null }) else null,
                    onClick = { toggle("time") })
                if (openSection == "time") {
                    val ts = rememberTimePickerState(initialHour = time?.hour ?: 9, initialMinute = time?.minute ?: 0)
                    androidx.compose.foundation.layout.Column(Modifier.padding(start = 34.dp, bottom = 8.dp)) {
                        TimePicker(state = ts)
                        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { openSection = null }) { Text("Cancel") }
                            TextButton(onClick = { time = LocalTime.of(ts.hour, ts.minute); allDay = false; openSection = null }) { Text("Set time") }
                        }
                    }
                }
                SheetRow(icon = Icons.Filled.Schedule, label = "Duration",
                    value = durationMin?.let { fmtDuration(it) } ?: "None",
                    onClear = if (durationMin != null) ({ durationMin = null; if (openSection == "duration") openSection = null }) else null,
                    onClick = { toggle("duration") })
                if (openSection == "duration") InlineDurationEditor(durationMin ?: 30) { durationMin = it }
            }
            // R43 — Estimate now lives here, right beside Duration, so effort and block length are chosen
            // together. Duration is the block on the grid; Estimate is how long you think it takes (feeds
            // the planner's calibration). Available even for all-day, since effort is independent of a clock.
            if (showEstimate) {
                SheetRow(icon = Icons.Filled.HourglassEmpty, label = "Estimate",
                    value = estimateMin?.let { fmtDuration(it) } ?: "None",
                    onClear = if (estimateMin != null) ({ estimateMin = null; if (openSection == "estimate") openSection = null }) else null,
                    onClick = { toggle("estimate") })
                if (openSection == "estimate") InlineDurationEditor(estimateMin ?: 30) { estimateMin = it }
                if (estimateHint != null) Text(estimateHint, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 34.dp, bottom = 2.dp))
            }
            if (showStart || showDeadline) {
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                if (showStart) SheetRow(icon = Icons.Filled.PlayArrow, label = "Starts",
                    value = startMillis?.let { fmtInstant(it) } ?: "None",
                    onClear = if (startMillis != null) ({ startMillis = null; startHasTime = false }) else null,
                    onClick = { showStartPicker = true })
                // Deadline — the hard drop-dead moment, now part of this one sheet (R22).
                if (showDeadline) SheetRow(icon = Icons.Filled.Flag, label = "Deadline",
                    value = deadlineMillis?.let { fmtInstant(it) } ?: "None",
                    onClear = if (deadlineMillis != null) ({ deadlineMillis = null }) else null,
                    onClick = { showDeadlinePicker = true })
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
                val remValue = when {
                    reminderPlace?.isNotBlank() == true -> "📍 ${reminderPlace}"
                    reminder != null && showReminderAnchors && reminderAnchor != "due" ->
                        reminderLabelOffset(reminder) + " · " + (if (reminderAnchor == "start") "start" else "deadline")
                    else -> reminderLabelOffset(reminder)
                }
                SheetRow(icon = Icons.Filled.Notifications, label = "Reminder",
                    value = remValue,
                    onClear = if (reminder != null || reminderPlace != null) ({ reminder = null; reminderPlace = null; if (openSection == "reminder") openSection = null }) else null,
                    onClick = { toggle("reminder") })
                if (openSection == "reminder") {
                    FlowRow(Modifier.padding(start = 34.dp, top = 2.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf<Pair<Int?, String>>(null to "None") + com.todocompanion.app.domain.reminders.ReminderPresets.OFFSETS.map { m -> m to (if (m == 0) "On time" else com.todocompanion.app.domain.reminders.ReminderPresets.beforeLabel(m)) }).forEach { (off, lbl) ->
                            FilterChip(selected = reminder == off && reminderPlace == null, onClick = { reminder = off; reminderPlace = null; if (!showReminderAnchors) openSection = null }, label = { Text(lbl) })
                        }
                    }
                    // F3 — capture: choose the anchor the offset counts from, plus an optional place reminder.
                    if (showReminderAnchors && reminder != null && reminderPlace == null) {
                        FlowRow(Modifier.padding(start = 34.dp, top = 0.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Before:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
                            FilterChip(selected = reminderAnchor == "due", onClick = { reminderAnchor = "due" }, label = { Text("Due") })
                            if (startMillis != null) FilterChip(selected = reminderAnchor == "start", onClick = { reminderAnchor = "start" }, label = { Text("Start") })
                            if (deadlineMillis != null) FilterChip(selected = reminderAnchor == "deadline", onClick = { reminderAnchor = "deadline" }, label = { Text("Deadline") })
                        }
                    }
                    if (showReminderAnchors) {
                        androidx.compose.material3.OutlinedTextField(
                            value = reminderPlace ?: "",
                            onValueChange = { reminderPlace = it; if (it.isNotBlank()) reminder = null },
                            label = { Text("…or when I arrive at a place") },
                            singleLine = true,
                            leadingIcon = { androidx.compose.material3.Icon(Icons.Filled.Place, null) },
                            modifier = Modifier.padding(start = 34.dp, top = 2.dp, bottom = 8.dp).fillMaxWidth(),
                        )
                    }
                }
            }
            // Repeat row (optional).
            SheetRow(icon = Icons.Filled.Repeat, label = "Repeat",
                value = repeatLabel(rrule),
                onClear = if (rrule != null) ({ rrule = null }) else null,
                onClick = { showRepeat = true })
            // Clear the date without leaving the sheet — start/deadline/repeat you've set stay put until Done.
            if (hasDate) TextButton(onClick = { hasDate = false; time = null; durationMin = null }) {
                Text("Clear date", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    // Time, duration, estimate and the reminder offset now expand inline in the sheet above (P2) — no
    // dialog stacked over the sheet for the common flow. Only the heavier sub-editors remain focused
    // dialogs: the recurrence builder and the Start/Deadline date panels, where a nested calendar or a
    // dense builder reads more clearly as its own surface than crammed inline.
    if (showRepeat) RepeatDialog(rrule, repeatHasChildren, onDismiss = { showRepeat = false }) { rrule = it; showRepeat = false }
    if (showStartPicker) DateTimeOptionalDialog(startMillis, startHasTime, onDismiss = { showStartPicker = false }, title = "Starts") { m, ht -> startMillis = m; startHasTime = ht; showStartPicker = false }
    if (showDeadlinePicker) {
        val dlTimed = deadlineMillis?.let { Instant.ofEpochMilli(it).atZone(zone).let { z -> z.hour != 0 || z.minute != 0 } } ?: false
        DateTimeOptionalDialog(deadlineMillis, dlTimed, onDismiss = { showDeadlinePicker = false }, title = "Deadline") { m, _ -> deadlineMillis = m; showDeadlinePicker = false }
    }
}

/**
 * The unified date + OPTIONAL time panel (R60). A roomy, themed dialog that mirrors the Date & Reminder
 * sheet exactly: a header, the same quick-date chips, a full month calendar, and a Time row you can leave
 * unset. Used everywhere a single date/time is chosen (Starts, Deadline, …) so every date panel in the app
 * looks and behaves identically. Returns the chosen instant (date at the time, or midnight when no time)
 * and whether a time was set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeOptionalDialog(
    initial: Long?,
    initialHasTime: Boolean,
    onDismiss: () -> Unit,
    title: String = "Select date",
    onConfirm: (Long, Boolean) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val initDt = initial?.let { Instant.ofEpochMilli(it).atZone(zone) }
    var date by remember { mutableStateOf(initDt?.toLocalDate() ?: java.time.LocalDate.now(zone)) }
    var time by remember { mutableStateOf(if (initialHasTime && initDt != null) LocalTime.of(initDt.hour, initDt.minute) else null) }
    var showTime by remember { mutableStateOf(false) }
    val hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    val today = java.time.LocalDate.now(zone)
    fun utcOf(d: java.time.LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val dateState = rememberDatePickerState(initialSelectedDateMillis = initial ?: utcOf(date))
    fun pick(d: java.time.LocalDate) { dateState.selectedDateMillis = utcOf(d); date = d }
    androidx.compose.runtime.LaunchedEffect(dateState.selectedDateMillis) {
        dateState.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    }
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            androidx.compose.foundation.layout.Column(
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp).verticalScroll(rememberScrollState()),
            ) {
                // Header: cancel · title · confirm — identical to the schedule sheet.
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.IconButton(onClick = onDismiss) { androidx.compose.material3.Icon(Icons.Filled.Close, "Cancel") }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    androidx.compose.material3.FilledTonalButton(onClick = {
                        onConfirm(java.time.LocalDateTime.of(date, time ?: LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli(), time != null)
                    }) {
                        androidx.compose.material3.Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Done")
                    }
                }
                // Quick-date chips — the same common picks as the Due sheet.
                val sat = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SATURDAY))
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = date == today, onClick = { pick(today) }, label = { Text("Today") })
                    FilterChip(selected = date == today.plusDays(1), onClick = { pick(today.plusDays(1)) }, label = { Text("Tomorrow") })
                    FilterChip(selected = date == today.plusDays(3), onClick = { pick(today.plusDays(3)) }, label = { Text("In 3 days") })
                    FilterChip(selected = date == today.plusWeeks(1), onClick = { pick(today.plusWeeks(1)) }, label = { Text("Next week") })
                    FilterChip(selected = date == sat, onClick = { pick(sat) }, label = { Text("Weekend") })
                }
                DatePicker(state = dateState, showModeToggle = false, title = null, headline = null)
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                SheetRow(icon = Icons.Filled.Schedule, label = "Time",
                    value = time?.format(hm) ?: "None",
                    onClear = if (time != null) ({ time = null }) else null,
                    onClick = { showTime = true })
            }
        }
    }
    if (showTime) {
        val ts = rememberTimePickerState(initialHour = time?.hour ?: 9, initialMinute = time?.minute ?: 0)
        Dialog(onDismissRequest = { showTime = false }) {
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                    TimePicker(state = ts)
                    androidx.compose.foundation.layout.Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showTime = false }) { Text("Cancel") }
                        TextButton(onClick = { time = LocalTime.of(ts.hour, ts.minute); showTime = false }) { Text("OK") }
                    }
                }
            }
        }
    }
}

internal fun fmtDuration(min: Int): String = when {
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

/** Inline hours/minutes steppers + preset chips for the sheet's Duration & Estimate rows (P2 — expands
 *  in place, no dialog stacked over the sheet). Applies live: every change reports the new total, or
 *  null when it lands on zero. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InlineDurationEditor(initialMin: Int, onChange: (Int?) -> Unit) {
    var hours by remember { mutableStateOf((initialMin / 60).coerceIn(0, 99)) }
    var mins by remember { mutableStateOf((initialMin % 60).coerceIn(0, 59)) }
    fun report() { onChange((hours * 60 + mins).takeIf { it > 0 }) }
    androidx.compose.foundation.layout.Column(Modifier.padding(start = 34.dp, top = 2.dp, bottom = 8.dp)) {
        Stepper(hours, { hours = it.coerceIn(0, 99); report() }, min = 0, max = 99, step = 1, label = "Hours", editable = true)
        Spacer(Modifier.height(6.dp))
        Stepper(mins, { mins = it.coerceIn(0, 59); report() }, min = 0, max = 59, step = 5, label = "Minutes", editable = true)   // ± nudges by 5; type any minute
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(15, 30, 45, 60, 90, 120, 180, 240).forEach { m ->
                FilterChip(selected = hours * 60 + mins == m, onClick = { hours = m / 60; mins = m % 60; report() }, label = { Text(fmtDuration(m)) })
            }
        }
    }
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

/** The one compact time-of-day trigger for inline use (an editor row, a From/to range): a small tappable
 *  chip showing HH:MM that opens the shared clock [TimeFieldDialog]. Settings' full-width TimeSettingRow
 *  opens the very same dialog, so the actual time picker is identical everywhere — no −/+ hour steppers.
 *  Hour-granular callers round the returned minute to the hour inside [onPick]. */
@Composable
fun TimeChip(minuteOfDay: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var show by remember { mutableStateOf(false) }
    androidx.compose.material3.AssistChip(
        onClick = { show = true },
        enabled = enabled,
        leadingIcon = { androidx.compose.material3.Icon(Icons.Filled.Schedule, null) },
        label = { Text("%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)) },
        modifier = modifier,
    )
    if (show) TimeFieldDialog(minuteOfDay, onDismiss = { show = false }) { onPick(it); show = false }
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

/**
 * R45 — a DATE-ONLY picker (no forced time step). Occasions are all-day by nature (a birthday is a
 * date, not a time), so their editor uses this instead of DateTimePickerDialog. Returns the local
 * start-of-day millis for the chosen date. R61 — [allowFuture] = false disables days after today (the
 * Time view's day navigator uses this, since you can't have tracked time in the future), so this one
 * component is the single source for every calendar in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateOnlyPickerDialog(initial: Long?, onDismiss: () -> Unit, allowFuture: Boolean = true, onConfirm: (Long) -> Unit) {
    val today = java.time.LocalDate.now(ZoneId.systemDefault())
    val endExclusive = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val selectable = if (allowFuture) androidx.compose.material3.DatePickerDefaults.AllDates else object : androidx.compose.material3.SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis < endExclusive
        override fun isSelectableYear(year: Int): Boolean = year <= today.year
    }
    val dateState = rememberDatePickerState(initialSelectedDateMillis = initial, selectableDates = selectable)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                dateState.selectedDateMillis?.let { utc ->
                    val ld = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                    onConfirm(ld.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
                } ?: onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = dateState) }
}
