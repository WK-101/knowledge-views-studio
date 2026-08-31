package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import java.time.LocalDate
import java.time.ZoneId

/** TickTick-style "Plan your day": step through overdue + today's tasks one at a time, deciding
 *  each — reschedule, complete, skip, or drop — until the day is planned. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlanYourDayScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val tasks by vm.tasks.collectAsState()
    val lists by vm.lists.collectAsState()
    val allHabits by vm.habits.collectAsState()
    val allCheckins by vm.habitCheckins.collectAsState()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val endToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    fun at9(d: LocalDate) = d.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli()

    var skipped by remember { mutableStateOf(setOf<String>()) }
    val queue = tasks.filter { !it.completed && !it.trashed && !it.abandoned && it.dueDate != null && it.dueDate!! < endToday }
        .sortedBy { it.dueDate }
    val remaining = queue.filter { it.id !in skipped }
    val current = remaining.firstOrNull()
    val listName = current?.let { c -> lists.firstOrNull { it.id == c.listId }?.name }

    Scaffold(
        topBar = {
            TopAppBar(expandedHeight = 52.dp, 
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Plan your day") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // M3: the daily coach brief — the flagship. One proactive card at the start of the day:
            // where today stands, your keystone, the streak most at risk, and the next best move.
            val brief = remember(allHabits, allCheckins, tasks) {
                com.todocompanion.app.domain.habit.HabitInsights.dailyBrief(allHabits, allCheckins, tasks, today.toEpochDay(), zone)
            }
            brief?.let { b ->
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🌅", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.size(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.headline, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(b.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    b.moves.forEach { mv ->
                        val openId = (mv.action as? com.todocompanion.app.domain.habit.InsightAction.Open)?.habitId
                            ?: (mv.action as? com.todocompanion.app.domain.habit.InsightAction.Stack)?.childId
                        Row(
                            Modifier.fillMaxWidth().padding(top = 10.dp)
                                .then(if (openId != null) Modifier.clickable { vm.habitDetailId.value = openId } else Modifier),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(mv.emoji, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.size(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(mv.text, style = MaterialTheme.typography.bodyMedium)
                                val act = mv.action
                                if (act is com.todocompanion.app.domain.habit.InsightAction.Stack) {
                                    TextButton(onClick = {
                                        vm.habits.value.firstOrNull { it.id == act.childId }?.let { vm.saveHabit(it.copy(anchorHabitId = act.anchorId)) }
                                    }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                                        Text("Stack ‘${act.childName}’ after ‘${act.anchorName}’")
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.size(14.dp))
            }

            // F4: auto-schedule now PREVIEWS — compute a plan, show it for review/editing, apply on confirm.
            var autoMsg by remember { mutableStateOf<String?>(null) }
            var plan by remember { mutableStateOf<AppViewModel.SchedulePlan?>(null) }
            val accepted = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
            OutlinedButton(
                onClick = {
                    val p = vm.computeAutoSchedule()
                    if (p.proposals.isEmpty()) autoMsg = "Nothing to auto-schedule right now"
                    else { accepted.clear(); p.proposals.forEach { accepted[it.task.id] = true }; plan = p; autoMsg = null }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Auto-schedule my day")
            }
            autoMsg?.let { Spacer(Modifier.size(6.dp)); Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center) }
            Spacer(Modifier.size(14.dp))

            plan?.let { p ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { plan = null },
                    confirmButton = {
                        val keep = p.proposals.filter { accepted[it.task.id] == true }
                        TextButton(enabled = keep.isNotEmpty(), onClick = {
                            vm.applyAutoSchedule(keep) { n ->
                                autoMsg = "Scheduled $n task${if (n == 1) "" else "s"}" + if (p.didNotFit > 0) " · ${p.didNotFit} didn't fit" else ""
                            }
                            plan = null
                        }) { Text("Apply ${keep.size}") }
                    },
                    dismissButton = { TextButton(onClick = { plan = null }) { Text("Cancel") } },
                    title = { Text("Review your day") },
                    text = {
                        Column {
                            Text("Tap to keep or drop each suggested time. Nothing changes until you Apply.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.size(8.dp))
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                p.proposals.forEach { pr ->
                                    val on = accepted[pr.task.id] == true
                                    val t = java.time.Instant.ofEpochMilli(pr.newDueMillis).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                            .clickable { accepted[pr.task.id] = !on }.padding(vertical = 8.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        androidx.compose.material3.Checkbox(checked = on, onCheckedChange = { accepted[pr.task.id] = it })
                                        Spacer(Modifier.size(6.dp))
                                        Text("%02d:%02d".format(t.hour, t.minute), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(52.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(pr.task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${pr.durationMin} min", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                if (p.didNotFit > 0) Text("${p.didNotFit} task${if (p.didNotFit == 1) "" else "s"} didn't fit in your work hours today.",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    },
                )
            }

            // L5: one timeline for today — timed habits and time-blocked tasks, in order.
            run {
                data class Ev(val min: Int, val label: String, val isHabit: Boolean, val color: Color)
                val evs = ArrayList<Ev>()
                allHabits.filter { !it.paused && !it.archived }.forEach { h ->
                    val expected = com.todocompanion.app.domain.habit.HabitStats.isExpectedDay(h, today.toEpochDay()) ||
                        h.freqType == com.todocompanion.app.domain.habit.HabitStats.FREQ_TIMES_WEEK || h.freqType == com.todocompanion.app.domain.habit.HabitStats.FREQ_TIMES_MONTH
                    if (expected) h.reminderTimes.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..1439 }.forEach { m ->
                        evs += Ev(m, (h.emoji?.plus(" ") ?: "") + h.name, true, h.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.tertiary)
                    }
                }
                tasks.filter { !it.completed && !it.trashed && !it.abandoned && it.dueDate != null && it.dueDate!! in today.atStartOfDay(zone).toInstant().toEpochMilli() until endToday && !it.isAllDay }.forEach { t ->
                    val z = java.time.Instant.ofEpochMilli(t.dueDate!!).atZone(zone)
                    if (z.hour != 0 || z.minute != 0) evs += Ev(z.hour * 60 + z.minute, t.title, false, MaterialTheme.colorScheme.primary)
                }
                if (evs.isNotEmpty()) {
                    AppCard {
                        Text("Today's timeline", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.size(6.dp))
                        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                            evs.sortedBy { it.min }.forEach { e ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("%02d:%02d".format(e.min / 60, e.min % 60), Modifier.width(52.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(e.color))
                                    Spacer(Modifier.size(8.dp))
                                    Text(e.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (e.isHabit) "habit" else "task", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.size(14.dp))
                }
            }

            // Deadline-risk radar (G3): the week's committed work vs the time you actually have.
            val risk = remember(tasks) { vm.deadlineRisk(7) }
            if (risk.atRisk > 0) {
                val over = risk.overCommitted
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (over) Icons.Filled.Warning else Icons.Filled.CheckCircle, null,
                            tint = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (over) "Over-committed this week" else "This week fits", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("${risk.atRisk} deadline${if (risk.atRisk == 1) "" else "s"} need ~${"%.0f".format(risk.neededH)}h · about ${"%.0f".format(risk.freeH)}h free",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (over) {
                        Spacer(Modifier.size(6.dp))
                        Text("You're short ~${"%.0f".format(risk.neededH - risk.freeH)}h. Move, shrink, or drop something before it slips.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.size(14.dp))
            }

            // ---------- Fusion F4: habits in the evening review ----------
            run {
                val todayEpoch = today.toEpochDay()
                val tomorrowEpoch = today.plusDays(1).toEpochDay()
                fun dueOn(h: com.todocompanion.app.data.entity.HabitEntity, day: Long): Boolean {
                    val hc = allCheckins.filter { it.habitId == h.id }
                    val doneDays = hc.filter { it.status == "done" && com.todocompanion.app.domain.habit.HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
                    return com.todocompanion.app.domain.habit.HabitStats.dueToday(h, day, doneDays, hc.firstOrNull { it.epochDay == day }?.count ?: 0)
                }
                val active = allHabits.filter { !it.paused }
                val scheduledToday = active.filter { com.todocompanion.app.domain.habit.HabitStats.isExpectedDay(it, todayEpoch) || it.freqType == com.todocompanion.app.domain.habit.HabitStats.FREQ_TIMES_WEEK || it.freqType == com.todocompanion.app.domain.habit.HabitStats.FREQ_TIMES_MONTH }
                val stillDue = scheduledToday.filter { dueOn(it, todayEpoch) }
                val tomorrowCount = active.count { com.todocompanion.app.domain.habit.HabitStats.isExpectedDay(it, tomorrowEpoch) || it.freqType == com.todocompanion.app.domain.habit.HabitStats.FREQ_TIMES_WEEK || it.freqType == com.todocompanion.app.domain.habit.HabitStats.FREQ_TIMES_MONTH }
                if (scheduledToday.isNotEmpty() || tomorrowCount > 0) {
                    AppCard {
                        val doneN = scheduledToday.size - stillDue.size
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (stillDue.isEmpty()) "🎉 Habits done for today" else "Habits · $doneN/${scheduledToday.size} done today",
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (tomorrowCount > 0) Text("$tomorrowCount tomorrow", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (stillDue.isNotEmpty()) {
                            Spacer(Modifier.size(8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                stillDue.forEach { h ->
                                    val color = h.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                                    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = .12f))
                                        .clickable { vm.setHabitValue(h, todayEpoch, h.targetPerDay.coerceAtLeast(1)) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text((h.emoji?.plus(" ") ?: "") + h.name, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.size(14.dp))
                }
            }

            // ---------- Pick N for today (B1): commit a few backlog tasks to today ----------
            var showPick by remember { mutableStateOf(false) }
            val committedToday = queue.size
            OutlinedButton(onClick = { showPick = !showPick }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PlaylistAddCheck, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(8.dp))
                Text(if (showPick) "Hide backlog picks" else "Pick tasks for today")
            }
            if (showPick) {
                val candidates = remember(tasks) { vm.pickTodayCandidates(15) }
                Spacer(Modifier.size(8.dp))
                if (candidates.isEmpty()) {
                    Text("No backlog tasks to pull in — you're all caught up.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                } else {
                    Text("$committedToday on today's plate · tap to add more", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(6.dp))
                    Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        candidates.forEach { t ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(t.title.ifBlank { "Untitled" }, Modifier.weight(1f).clickable { onOpenTask(t.id) },
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                FilledTonalButton(onClick = { vm.commitToToday(t) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)); Text("Today")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.size(14.dp))
            }

            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.size(12.dp))
                        Text("Your day is planned", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (queue.isEmpty()) "Nothing overdue or due today." else "Every task has a plan.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(20.dp))
                        Button(onClick = onBack) { Text("Done") }
                    }
                }
                return@Column
            }

            Text("${remaining.size} to plan", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(16.dp))
            AppCard {
                Text(current.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                current.dueDate?.let {
                    val overdue = it < today.atStartOfDay(zone).toInstant().toEpochMilli()
                    Text(if (overdue) "Overdue" else "Due today",
                        style = MaterialTheme.typography.labelMedium, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (listName != null && listName != "Inbox") Text(listName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (current.note.isNotBlank()) {
                    Spacer(Modifier.size(6.dp))
                    Text(current.note.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                }
                Spacer(Modifier.size(6.dp))
                TextButton(onClick = { onOpenTask(current.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Open task") }
            }

            Spacer(Modifier.size(20.dp))
            Text("RESCHEDULE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.save(current.copy(dueDate = at9(today))) }) { Text("Today") }
                FilledTonalButton(onClick = { vm.save(current.copy(dueDate = at9(today.plusDays(1)))) }) { Text("Tomorrow") }
                FilledTonalButton(onClick = { vm.save(current.copy(dueDate = at9(today.plusDays(7)))) }) { Text("Next week") }
                FilledTonalButton(onClick = { vm.setSomeday(current, true) }) { Text("Someday") }
            }

            Spacer(Modifier.size(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.toggleComplete(current) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Complete")
                }
                OutlinedButton(onClick = { skipped = skipped + current.id }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.SkipNext, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Skip")
                }
            }
            Spacer(Modifier.size(10.dp))
            TextButton(onClick = { vm.setAbandoned(current, true) }) {
                Icon(Icons.Filled.Cancel, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Won't do")
            }
        }
    }
}
