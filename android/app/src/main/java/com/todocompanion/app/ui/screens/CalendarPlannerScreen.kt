package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.calendar.CalendarEngine
import com.todocompanion.app.domain.calendar.CalendarPlanner
import com.todocompanion.app.domain.calendar.ThirdHorizon
import com.todocompanion.app.ui.AppViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * R41 — the Planner. One private surface that turns the four local data types (events + tasks + habits
 * + tracked time) into the scheduling brain a paid calendar charges for: a live day-budget, a greedy
 * auto-scheduler, an inferred chronotype, a habit-defend radar, and a weekly time-audit. No network,
 * no model, no permission.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlannerSheet(vm: AppViewModel, zone: ZoneId, initialDay: Long, initialTab: Int = 0, onClose: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet) {
        var tab by remember { mutableIntStateOf(initialTab) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Planner", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Text("Your events, tasks, habits and tracked time — planned together, on-device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = tab == 0, onClick = { tab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 3)) { Text("Plan") }
                SegmentedButton(selected = tab == 1, onClick = { tab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 3)) { Text("Review") }
                SegmentedButton(selected = tab == 2, onClick = { tab = 2 }, shape = SegmentedButtonDefaults.itemShape(2, 3)) { Text("Horizon") }
            }
            Spacer(Modifier.height(14.dp))
            when (tab) { 0 -> PlanTodayTab(vm, zone, initialDay); 1 -> WeeklyReviewTab(vm, zone, initialDay); else -> HorizonTab(vm, zone, initialDay) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanTodayTab(vm: AppViewModel, zone: ZoneId, day: Long) {
    val events by vm.events.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val entries by vm.timeEntries.collectAsState()
    val habits by vm.habits.collectAsState()
    val templates by vm.eventTemplates.collectAsState()
    val settings by vm.settings.collectAsState()
    val ws = settings.workStartHour; val we = settings.workEndHour
    val today = LocalDate.now(zone).toEpochDay()

    val occ = remember(events, day) { CalendarEngine.onDay(events, day, zone) }
    val budget = remember(occ, ws, we) { CalendarPlanner.dayBudget(occ, day, ws, we, zone) }
    val nowFloor = if (day == today) System.currentTimeMillis() else null
    val placements = remember(tasks, occ, day, ws, we) {
        CalendarPlanner.autoSchedule(tasks.filter { it.workspaceId == settings.activeWorkspaceId }, occ, day, ws, we, fromMillis = nowFloor, zone = zone)
    }
    val chrono = remember(tasks, entries) { CalendarPlanner.inferChronotype(tasks, entries, zone) }
    val risksAll = remember(habits, events, today) { CalendarPlanner.habitWindowRisks(habits, events, today, 7, zone = zone) }

    // ── Day budget ──
    SectionCard {
        val dow = LocalDate.ofEpochDay(day).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        Text("$dow · time budget", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        BudgetBar(budget)
        Spacer(Modifier.height(6.dp))
        val over = budget.overcommitted
        Text(
            if (over) "Overbooked by ${fmtMin(-budget.remainingMin)} — nothing more will fit."
            else "${fmtMin(budget.bookedMin)} booked · ${fmtMin(budget.remainingMin)} free of ${fmtMin(budget.availableMin)}.",
            style = MaterialTheme.typography.bodyMedium, color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        // Planned vs actual: how much you've actually tracked against today's plan.
        val dayStartMs = LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEndMs = LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val trackedToday = remember(entries, day) {
            entries.filter { it.endMillis != null && it.startMillis < dayEndMs && it.endMillis!! > dayStartMs }
                .sumOf { (((minOf(it.endMillis!!, dayEndMs) - maxOf(it.startMillis, dayStartMs)) / 60000L)).toInt().coerceAtLeast(0) }
        }
        if (trackedToday > 0) Text("Tracked so far: ${fmtMin(trackedToday)} — the plan against your actual time.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }

    // ── Auto-schedule ──
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bolt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Auto-schedule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        chrono?.let {
            Text("Your focus peaks with ${it.label()} — the solver fills your working hours; keep high-effort blocks near your peak.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (placements.isEmpty()) {
            Text("No unscheduled tasks with estimates fit today's gaps. Give a task an estimate, or free some time.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val df = DateTimeFormatter.ofPattern("h:mm").withZone(zone)
            placements.take(8).forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(df.format(Instant.ofEpochMilli(p.startMillis)), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(52.dp))
                    Text(if (p.parts > 1) "${p.task.title} (${p.part}/${p.parts})" else p.task.title,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(fmtMin(p.durationMin()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (placements.size > 8) Text("+${placements.size - 8} more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { vm.autoScheduleDay(day) }, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("Place ${placements.size} block${if (placements.size == 1) "" else "s"} into today")
            }
        }
    }

    // ── Refine the day (plan-lock, reflow, defragment, realistic pre-mortem, load) ──
    val routines by vm.dayRoutines.collectAsState()
    val locked = remember(settings.planLockedDaysCsv, day) { settings.planLockedDaysCsv.split(",").mapNotNull { it.trim().toLongOrNull() }.contains(day) }
    SectionCard {
        Text("Refine the day", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        // Realistic-day pre-mortem.
        val placementsTotal = placements.sumOf { it.durationMin() }
        val bias = remember(tasks, entries) { CalendarPlanner.estimateBias(tasks, entries)?.medianRatio ?: 1.0 }
        val overflow = CalendarPlanner.realisticOverflowMin(budget.remainingMin, placementsTotal, bias)
        Text(
            if (placementsTotal == 0) "Nothing waiting to place — your day fits."
            else if (overflow > 0) "Placing everything would run ~${fmtMin(overflow)} past your day. Defer or trim before you commit."
            else "Everything waiting still fits with ~${fmtMin(budget.remainingMin - (placementsTotal * bias).toInt())} to spare.",
            style = MaterialTheme.typography.bodySmall, color = if (overflow > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        // Cognitive-load (effort, not just hours).
        val effortById = remember(tasks) { tasks.associate { it.id to (it.energy ?: 1).coerceIn(1, 3) } }
        val load = remember(occ, effortById) { CalendarPlanner.cognitiveLoad(occ, effortById) }
        if (load >= 24) Text("Heavy day by effort, not just hours — space the high-energy blocks apart.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 6.dp))
        if (bias >= 1.15 || bias <= 0.85) Text("Auto-schedule pads to your real pace (×${"%.2f".format(bias)}).",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = false, onClick = { vm.reflowDay(day) }, label = { Text("Heal conflicts") },
                leadingIcon = { Icon(Icons.Filled.AutoFixHigh, null, Modifier.size(16.dp)) })
            FilterChip(selected = false, onClick = { vm.defragmentDay(day) }, label = { Text("Defragment") },
                leadingIcon = { Icon(Icons.Filled.Compress, null, Modifier.size(16.dp)) })
            FilterChip(selected = locked, onClick = { vm.setPlanLocked(day, !locked) },
                label = { Text(if (locked) "Plan locked" else "Lock plan") },
                leadingIcon = { Icon(if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen, null, Modifier.size(16.dp)) })
        }
    }

    // ── One-tap actual-logging (past blocks today) ──
    if (day == today) {
        val nowMs = System.currentTimeMillis()
        val pastBlocks = remember(occ, entries, nowMs) {
            occ.filter { it.event.linkedTaskId != null && !it.event.allDay && it.endMillis <= nowMs }
                .filter { o -> entries.none { it.taskId == o.event.linkedTaskId && it.endMillis != null && it.startMillis < o.endMillis && it.endMillis!! > o.startMillis } }
        }
        if (pastBlocks.isNotEmpty()) SectionCard {
            Text("Did these happen?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("One tap turns a finished block into tracked time — the plan becomes the record.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            val df = DateTimeFormatter.ofPattern("h:mm").withZone(zone)
            pastBlocks.take(6).forEach { o ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(df.format(Instant.ofEpochMilli(o.startMillis)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(52.dp))
                    Text(o.event.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    TextButton(onClick = { vm.logActualForBlock(o.event.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp, 0.dp)) { Text("Log ${fmtMin(o.durationMin().toInt())}") }
                }
            }
        }
    }

    // ── Day routines ──
    SectionCard {
        Text("Day routines", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Lay out a template day in one tap, or save today's shape to reuse.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        if (routines.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            routines.forEach { r ->
                FilterChip(selected = false, onClick = { vm.applyDayRoutine(r.id, day) }, label = { Text("${r.emoji} ${r.name}") })
            }
        }
        var routineName by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(routineName, { routineName = it }, singleLine = true,
                placeholder = { Text("Name today's routine") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { if (routineName.isNotBlank()) { vm.saveDayRoutineFromDay(routineName, day); routineName = "" } }, enabled = routineName.isNotBlank()) { Text("Save") }
        }
    }

    // ── Templates ──
    if (templates.isNotEmpty()) SectionCard {
        Text("Quick add from a template", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Drops onto the next free slot today.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            templates.forEach { t ->
                FilterChip(selected = false, onClick = {
                    val slot = CalendarPlanner.slideToFree(day, (nowFloorMin(day, zone) ?: (ws * 60)), t.durationMin, events, ws, we, zone)
                        ?: LocalDate.ofEpochDay(day).atTime(ws.coerceIn(0, 23), 0).atZone(zone).toInstant().toEpochMilli()
                    vm.applyEventTemplate(t, slot)
                }, label = { Text("${t.emoji} ${t.title} · ${fmtMin(t.durationMin)}") })
            }
        }
    }

    // ── Habit-defend radar ──
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Habit-defend radar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        if (habits.isEmpty()) {
            Text("No habits yet. Habits you add with a reminder time get a protected window here.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            if (risksAll.isEmpty()) Text("Every habit window is clear this week — nothing at risk.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Streak-aware: a habit checked in yesterday has a live streak — flag those first (moat).
            val checkins by vm.habitCheckins.collectAsState()
            val streakAlive = remember(checkins, today) {
                checkins.filter { it.epochDay == today - 1 && it.count >= 1 }.map { it.habitId }.toSet()
            }
            risksAll.groupBy { it.habit.id }.entries.sortedByDescending { it.key in streakAlive }.take(5).forEach { (hid, list) ->
                val h = list.first().habit
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${h.emoji ?: "🔁"} ${h.name}" + if (hid in streakAlive) "  🔥" else "", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        val days = list.map { LocalDate.ofEpochDay(it.day).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }.distinct()
                        Text((if (hid in streakAlive) "Streak alive — " else "") + "an event overlaps this window on ${days.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Protect a window today:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                habits.take(8).forEach { h ->
                    FilterChip(selected = false, onClick = { vm.placeHabitBlock(h, day) }, label = { Text("${h.emoji ?: "🔁"} ${h.name}") })
                }
            }
        }
    }
}

@Composable
private fun WeeklyReviewTab(vm: AppViewModel, zone: ZoneId, day: Long) {
    val settings by vm.settings.collectAsState()
    val calendars by vm.eventCalendars.collectAsState()
    val weekStart = remember(day, settings.weekStart) { startOfWeek(day, settings.weekStart) }
    val audit by produceState<CalendarPlanner.Audit?>(initialValue = null, weekStart) {
        value = vm.buildWeeklyAudit(weekStart)
    }
    val a = audit
    if (a == null) {
        Text("Reading your week…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val df = DateTimeFormatter.ofPattern("MMM d")
    val weekLabel = "${LocalDate.ofEpochDay(weekStart).format(df)} – ${LocalDate.ofEpochDay(weekStart + 6).format(df)}"

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Insights, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Week of $weekLabel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        // Booked minutes per day
        val maxDay = (a.bookedMinByDay.values.maxOrNull() ?: 0).coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            for (i in 0 until 7) {
                val d = weekStart + i
                val mins = a.bookedMinByDay[d] ?: 0
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Box(Modifier.fillMaxWidth().height((60f * mins / maxDay).coerceAtLeast(if (mins > 0) 4f else 0f).dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (d == a.busiestDay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)))
                    Spacer(Modifier.height(3.dp))
                    Text(LocalDate.ofEpochDay(d).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        StatRow("Booked (events)", fmtMin(a.totalBookedMin))
        StatRow("Tracked (actual)", fmtMin(a.trackedMin))
        a.habitAdherencePct?.let { StatRow("Habit adherence", "$it%") }
    }

    // Hours by calendar
    if (a.minutesByCalendar.isNotEmpty()) SectionCard {
        Text("Where the hours went", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        val total = a.minutesByCalendar.values.sum().coerceAtLeast(1)
        a.minutesByCalendar.entries.sortedByDescending { it.value }.forEach { (calId, mins) ->
            val c = calendars.firstOrNull { it.id == calId }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c?.colorArgb ?: 0xFF4F46E5)))
                Spacer(Modifier.width(8.dp))
                Text(c?.name ?: "Calendar", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp), maxLines = 1)
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(mins.toFloat() / total).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(c?.colorArgb ?: 0xFF4F46E5)))
                }
                Spacer(Modifier.width(8.dp))
                Text(fmtMin(mins), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    // Estimate accuracy
    a.estimateBias?.let {
        SectionCard {
            Text("Estimate accuracy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(it.sentence(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
            Text("From ${it.samples} tasks you both estimated and tracked. New estimates get a suggested figure from this.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }

    // Advice
    SectionCard {
        Text("What the week is telling you", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        a.advice.forEach { line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text("•  ", color = MaterialTheme.colorScheme.primary)
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ── R43 · The third horizon ─────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HorizonTab(vm: AppViewModel, zone: ZoneId, day: Long) {
    val events by vm.events.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val entries by vm.timeEntries.collectAsState()
    val calendars by vm.eventCalendars.collectAsState()
    val settings by vm.settings.collectAsState()
    val ws = settings.workStartHour; val we = settings.workEndHour
    val today = LocalDate.now(zone).toEpochDay()

    val occ = remember(events, day) { CalendarEngine.onDay(events, day, zone) }
    val budget = remember(occ, ws, we) { CalendarPlanner.dayBudget(occ, day, ws, we, zone) }
    val nowFloor = if (day == today) System.currentTimeMillis() else null
    val placements = remember(tasks, occ, day) {
        CalendarPlanner.autoSchedule(tasks.filter { it.workspaceId == settings.activeWorkspaceId }, occ, day, ws, we, fromMillis = nowFloor, zone = zone)
    }
    val dayStartMs = LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEndMs = LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli()

    Text("Twelve reads only a unified store can form — your events, tasks, habits and tracked time, together.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))

    // 1 · The cost of yes
    SectionCard {
        HorizonHead("⇄", "The cost of yes")
        Text("Before you accept a new block, see what it displaces.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var dur by remember { mutableIntStateOf(60) }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(30, 60, 90, 120).forEach { m ->
                FilterChip(selected = dur == m, onClick = { dur = m }, label = { Text(fmtMin(m)) })
            }
        }
        val yes = ThirdHorizon.costOfYes(placements, budget.remainingMin, dur)
        Spacer(Modifier.height(6.dp))
        if (yes.freeAfterMin >= 0)
            Text("Fits — ${fmtMin(yes.freeAfterMin)} would still be free today.", style = MaterialTheme.typography.bodyMedium)
        else
            Text("Overbooks by ${fmtMin(-yes.freeAfterMin)}. It would bump: ${yes.displacedTitles.joinToString(", ").ifBlank { "planned work" }}.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }

    // 2 · Backfill from actuals
    val gaps = remember(entries, occ, day) { ThirdHorizon.backfillCandidates(entries, occ, dayStartMs, dayEndMs) }
    if (gaps.isNotEmpty()) SectionCard {
        HorizonHead("◔", "Backfill from actuals")
        Text("Tracked time with no block behind it — turn it into a record of the day.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        val df = DateTimeFormatter.ofPattern("h:mm").withZone(zone)
        gaps.take(5).forEach { g ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(df.format(Instant.ofEpochMilli(g.startMillis)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(52.dp))
                Text("${fmtMin(g.minutes)} untracked-as-plan", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                if (g.taskId != null) TextButton(onClick = { vm.blockTaskAsEvent(g.taskId!!, g.startMillis, g.minutes) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp, 0.dp)) { Text("Block it") }
            }
        }
    }

    // 3 · Ghost week
    val weekStart = remember(day, settings.weekStart) { startOfWeek(day, settings.weekStart) }
    val bookedByDay by produceState<Map<Long, Long>?>(initialValue = null, weekStart) {
        value = vm.bookedMinutesByDay(weekStart - 35, weekStart + 6)
    }
    bookedByDay?.let { booked ->
        val ghost = remember(booked, weekStart) { ThirdHorizon.ghostWeek(booked.mapValues { it.value.toInt() }, weekStart) }
        SectionCard {
            HorizonHead("▤", "The ghost week")
            Text("Your usual week (median of the last 4) behind this one — see where today runs heavier or lighter.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val maxV = (ghost.maxOrNull() ?: 1).coerceAtLeast((0 until 7).maxOf { (booked[weekStart + it] ?: 0L).toInt() }).coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                for (i in 0 until 7) {
                    val thisWk = (booked[weekStart + i] ?: 0L).toInt()
                    val ghostV = ghost[i]
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                            // ghost median (faint, wide)
                            Box(Modifier.fillMaxWidth().height((52f * ghostV / maxV).coerceAtLeast(if (ghostV > 0) 3f else 0f).dp)
                                .clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .22f)))
                            // this week (solid, narrow)
                            Box(Modifier.fillMaxWidth(0.55f).height((52f * thisWk / maxV).coerceAtLeast(if (thisWk > 0) 3f else 0f).dp)
                                .clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(LocalDate.ofEpochDay(weekStart + i).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // 6 · Recovery buffers (needs the history too)
        val rec = remember(booked, today) { ThirdHorizon.recovery(booked.mapValues { it.value.toInt() }, today) }
        if (rec.suggestBuffer) SectionCard {
            HorizonHead("≈", "Recovery buffer")
            Text("${rec.heavyStreak} heavy days in a row with no lighter one between — an energy-debt streak. Block a short decompress before it compounds.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }

    // 4 · Deadline-aware chunking
    val chunkTask = remember(tasks, today) {
        tasks.filter { it.workspaceId == settings.activeWorkspaceId && !it.completed && !it.trashed && (it.estimateMin ?: 0) >= 60 }
            .filter { (it.deadlineDate ?: it.dueDate) != null }
            .firstOrNull { (java.time.Instant.ofEpochMilli(it.deadlineDate ?: it.dueDate!!).atZone(zone).toLocalDate().toEpochDay()) > today }
    }
    chunkTask?.let { t ->
        SectionCard {
            HorizonHead("↦", "Deadline-aware chunking")
            val dl = java.time.Instant.ofEpochMilli(t.deadlineDate ?: t.dueDate!!).atZone(zone).toLocalDate()
            Text("“${t.title}” needs ${fmtMin(t.estimateMin ?: 0)} by ${dl.format(DateTimeFormatter.ofPattern("MMM d"))}. Spread it across the days before, not just today.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { vm.applyDeadlineChunks(t.id) }, modifier = Modifier.align(Alignment.End)) { Text("Spread it out") }
        }
    }

    // 5 · Habit-stacking finder
    val stacks = remember(occ, day) { ThirdHorizon.habitStacks(occ, dayStartMs, dayEndMs) }
    if (stacks.isNotEmpty()) SectionCard {
        HorizonHead("⊕", "Habit-stacking finder")
        Text("Small gaps right after something you already do — the perfect anchor for a micro-habit.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        stacks.take(4).forEach { s ->
            Text("• ${fmtMin(s.minutes)} free right after “${s.anchorTitle}”", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
        }
    }

    // 7 · Daylight rail
    SectionCard {
        HorizonHead("☀", "Daylight rail")
        val lat = settings.daylightLatitude
        val dl = remember(lat, day) { ThirdHorizon.daylight(lat, LocalDate.ofEpochDay(day)) }
        if (dl == null) {
            Text("Enter your latitude once (e.g. 51.5 for London, 40.7 for New York, −33.9 for Sydney) — sunrise/sunset are computed on-device, no location permission.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var latText by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(latText, { latText = it }, singleLine = true, placeholder = { Text("Latitude") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { latText.toDoubleOrNull()?.let { vm.setDaylightLatitude(it) } }, enabled = latText.toDoubleOrNull() != null) { Text("Set") }
            }
        } else {
            val tf = DateTimeFormatter.ofPattern("h:mm a")
            when (dl.polar) {
                1 -> Text("Midnight sun today — the sun doesn't set at this latitude.", style = MaterialTheme.typography.bodyMedium)
                -1 -> Text("Polar night today — the sun doesn't rise at this latitude.", style = MaterialTheme.typography.bodyMedium)
                else -> Text("☀ ${dl.sunrise.format(tf)} · 🌙 ${dl.sunset.format(tf)} — ${fmtMin(dl.daylightMin)} of daylight. Plan the bright hours first.", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { vm.setDaylightLatitude(null) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Turn off") }
        }
    }

    // 8 · Focus contract
    val nextBlock = remember(occ, nowFloor) {
        occ.filter { !it.event.allDay && it.event.linkedTaskId != null && (nowFloor == null || it.endMillis > nowFloor) }.minByOrNull { it.startMillis }
    }
    nextBlock?.let { o ->
        SectionCard {
            HorizonHead("◎", "The focus contract")
            Text("Turn “${o.event.title}” into one gesture: arm the focus ring so starting it also tracks the time.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { o.event.linkedTaskId?.let { vm.armFocusForTask(it) } }, modifier = Modifier.align(Alignment.End)) { Text("Arm focus") }
        }
    }

    // 9 + 10 · What changed & North-star (both from the weekly audit)
    val audit by produceState<CalendarPlanner.Audit?>(initialValue = null, weekStart) { value = vm.buildWeeklyAudit(weekStart) }
    audit?.let { a ->
        SectionCard {
            HorizonHead("✎", "What changed")
            Text(ThirdHorizon.whatChanged(0, 0, a.totalBookedMin, a.trackedMin), style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard {
            HorizonHead("✵", "North-star allocation")
            val visibleCals = calendars.filter { it.visible }
            val drifts = remember(settings.northStarTargetsCsv, a.minutesByCalendar) { ThirdHorizon.northStarDrift(settings.northStarTargetsCsv, a.minutesByCalendar) }
            if (drifts.isEmpty()) {
                Text("Set a target share of your time per calendar; the app reads the actual split and shows the drift.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                drifts.forEach { d ->
                    val c = visibleCals.firstOrNull { it.id == d.calId }
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c?.colorArgb ?: 0xFF4F46E5)))
                        Spacer(Modifier.width(8.dp))
                        Text(c?.name ?: "Calendar", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text("${d.actualPct}% vs ${d.targetPct}%", style = MaterialTheme.typography.labelMedium,
                            color = if (kotlin.math.abs(d.delta) <= 5) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    }
                }
            }
            // Compact editor: bump a calendar's target by ±10%.
            Spacer(Modifier.height(6.dp))
            Text("Set targets:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val targets = remember(settings.northStarTargetsCsv) { ThirdHorizon.parseTargets(settings.northStarTargetsCsv) }
            visibleCals.take(6).forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    val cur = ((targets[c.id] ?: 0.0) * 100).toInt()
                    TextButton(onClick = { vm.setNorthStarTarget(c.id, ((cur - 10) / 100.0)) }, enabled = cur > 0, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp, 0.dp)) { Text("−") }
                    Text("$cur%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
                    TextButton(onClick = { vm.setNorthStarTarget(c.id, ((cur + 10) / 100.0)) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp, 0.dp)) { Text("+") }
                }
            }
        }
    }

    // 11 · Attention-residue radar
    val frag = remember(occ) { ThirdHorizon.fragmentation(occ) }
    if (frag.score >= 20) SectionCard {
        HorizonHead("⤨", "Attention-residue radar")
        Text("Today is ${frag.shortBlocks} short blocks with ${frag.switches} context switches — batching similar work would cut the residue.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }

    // 12 · Time-debt repayment
    val debts = remember(tasks, entries) { ThirdHorizon.timeDebts(tasks, entries) }
    if (debts.isNotEmpty()) SectionCard {
        HorizonHead("↺", "Time-debt repayment")
        Text("These already ran past their estimate — repay the debt by pre-booking the real figure next time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        debts.take(4).forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text("est ${fmtMin(d.estimateMin)} · took ${fmtMin(d.actualMin)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { vm.bumpTaskEstimate(d.taskId, d.actualMin) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp, 0.dp)) { Text("Update") }
            }
        }
    }
}

@Composable
private fun HorizonHead(glyph: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(glyph, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(4.dp))
}

// ── small building blocks ─────────────────────────────────────────────────────────────────────────
@Composable
private fun SectionCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun BudgetBar(budget: CalendarPlanner.Budget) {
    val frac = budget.fractionUsed
    val over = budget.overcommitted
    Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.fillMaxWidth(frac).height(14.dp).clip(RoundedCornerShape(7.dp))
            .background(if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun fmtMin(min: Int): String {
    val m = if (min < 0) 0 else min
    return when { m < 60 -> "${m}m"; m % 60 == 0 -> "${m / 60}h"; else -> "${m / 60}h ${m % 60}m" }
}

private fun startOfWeek(day: Long, weekStart: Int): Long {
    // App convention: 0 = System (locale first-day), 1..7 = Mon..Sun (ISO). Most recent start day ≤ day.
    val date = LocalDate.ofEpochDay(day)
    val target = if (weekStart in 1..7) weekStart else java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek.value
    var d = date
    var guard = 0
    while (d.dayOfWeek.value != target && guard++ < 7) d = d.minusDays(1)
    return d.toEpochDay()
}

private fun nowFloorMin(day: Long, zone: ZoneId): Int? {
    val today = LocalDate.now(zone).toEpochDay()
    if (day != today) return null
    val now = java.time.LocalTime.now(zone)
    return now.hour * 60 + now.minute
}
