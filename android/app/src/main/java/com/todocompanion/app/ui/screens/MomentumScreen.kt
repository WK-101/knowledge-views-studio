package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.todocompanion.app.domain.WeeklyDigest
import com.todocompanion.app.domain.nlp.SmartCapture
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.habit.HabitInsights
import com.todocompanion.app.domain.habit.HabitStats
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import java.time.LocalDate
import java.time.ZoneId

/**
 * Q1 — the unified "Momentum" dashboard: the one screen that reads across BOTH halves of the store —
 * habit strength, task reliability, and focus — into a single daily momentum, with the cross-module
 * correlations (Q6) that only a unified habit+task app can compute. Entirely on-device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentumScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val focus by vm.focusSessions.collectAsState()
    val reliability by vm.taskReliability.collectAsState()
    val settings by vm.settings.collectAsState()
    val timeEntries by vm.timeEntries.collectAsState()
    val habitsOn = com.todocompanion.app.domain.Modules.isEnabled(settings, com.todocompanion.app.domain.Modules.HABITS)
    val tasksOn = com.todocompanion.app.domain.Modules.isEnabled(settings, com.todocompanion.app.domain.Modules.TASKS)
    val timeOn = com.todocompanion.app.domain.Modules.isEnabled(settings, com.todocompanion.app.domain.Modules.TIME)
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now().toEpochDay()

    val shareCtx = androidx.compose.ui.platform.LocalContext.current
    var showCapture by remember { mutableStateOf(false) }
    if (showCapture) SmartCaptureDialog(vm) { showCapture = false }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Momentum") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                // R3: one capture box that sorts itself into a habit or a task.
                IconButton(onClick = { showCapture = true }) {
                    Icon(Icons.Filled.Add, "Capture a habit or task")
                }
                // R1: share the unified momentum snapshot as an on-device PNG. Offline by construction.
                IconButton(onClick = { vm.shareMomentum { loc -> if (loc != null) android.widget.Toast.makeText(shareCtx, "Saved a copy to $loc", android.widget.Toast.LENGTH_SHORT).show() } }) {
                    Icon(Icons.Filled.Share, "Share momentum")
                }
            },
        )
    }) { padding ->
        // Habit strength (avg over active build habits). I5: gated to null when the Habits module is off,
        // so a disabled module never feeds the blend or shows a tile.
        val activeHabits = habits.filter { !it.archived }
        val habitStrengthRaw = remember(habits, checkins, today) {
            val vals = activeHabits.map { h ->
                val hc = checkins.filter { it.habitId == h.id }
                val done = hc.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
                val skip = hc.filter { it.status == "skip" }.map { it.epochDay }.toSet()
                val rel = hc.filter { HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
                HabitStats.strength(h, done, skip, rel, today)
            }
            if (vals.isEmpty()) null else vals.average().toInt()
        }
        val habitStrength = if (habitsOn) habitStrengthRaw else null
        // Task reliability (avg over recurring tasks that have a score).
        val taskRel = if (tasksOn) reliability.values.map { it.score }.let { if (it.isEmpty()) null else it.average().toInt() } else null
        // Focus minutes this week.
        val weekDays = (0 until 7).map { today - it }.toSet()
        val focusWeek = focus.filter { it.epochDay in weekDays }.sumOf { it.minutes }
        // Time tracked today (I5: informational; shown as a tile when the Time module is on).
        val nowMs = System.currentTimeMillis()
        val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val timeTodayMin = if (timeOn) com.todocompanion.app.domain.TimeTracking.totalMinutes(timeEntries, dayStart, dayEnd, nowMs) else 0
        val tasksDoneWeek = tasks.count { t -> t.completedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() in weekDays } == true }

        // One blended momentum score from whatever signals exist (weights renormalise).
        val momentum = remember(habitStrength, taskRel, focusWeek) {
            val parts = buildList {
                habitStrength?.let { add(it.toDouble() to 0.5) }
                taskRel?.let { add(it.toDouble() to 0.35) }
                add((focusWeek.coerceAtMost(300) / 300.0 * 100) to 0.15)
            }
            val wsum = parts.sumOf { it.second }
            if (wsum == 0.0) 0 else (parts.sumOf { it.first * it.second } / wsum).toInt()
        }

        val correlations = remember(habits, checkins, tasks, today) {
            HabitInsights.compute(habits, checkins, tasks, today, max = 8)
                .filter { it.emoji in setOf("🔗", "⚡", "🗝️", "📉") }
        }

        // I5: when only one module is enabled, "momentum" relabels to that module's own summary rather
        // than a degenerate one-input score.
        val enabledMods = com.todocompanion.app.domain.Modules.enabled(settings)
        val ringTitle = if (enabledMods.size == 1) when (enabledMods[0]) {
            com.todocompanion.app.domain.Modules.HABITS -> "Your habits"
            com.todocompanion.app.domain.Modules.TIME -> "Your time"
            else -> "Your tasks"
        } else "Today's momentum"

        val nothing = (!habitsOn || activeHabits.isEmpty()) && (!tasksOn || reliability.isEmpty()) && (!timeOn || timeEntries.isEmpty())
        if (nothing) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Start a habit, a task, or a timer and your momentum fills in here.", Modifier.padding(32.dp),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Scaffold
        }

        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // W2 — Right Now: the single next best action, with one tap to act.
            val rn = remember(tasks, habits, checkins, timeEntries) { vm.rightNow() }
            if (rn != null) Surface(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f),
            ) {
                Row(Modifier.padding(18.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Right now", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(rn.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(rn.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(10.dp))
                    FilledTonalButton(onClick = {
                        when (rn.kind) {
                            "task" -> rn.taskId?.let { id -> tasks.firstOrNull { it.id == id }?.let { vm.startTimeTrackingForTask(it) } }
                            "habit" -> rn.habitId?.let { id -> habits.firstOrNull { it.id == id }?.let { h -> vm.cycleHabit(h, java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toEpochDay(), 0) } }
                        }
                    }) { Text(rn.actionLabel) }
                }
            }

            // W3 — Plan my day: auto-block estimated tasks + turn on track prompts (plan → do → measure).
            if (tasksOn && timeOn) AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Plan my day", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("Time-block today's tasks by your rhythm, and I'll ask to track each block.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = {
                        vm.planMyDay { n -> android.widget.Toast.makeText(shareCtx, if (n > 0) "Blocked $n task${if (n == 1) "" else "s"} — track prompts on" else "Nothing to schedule", android.widget.Toast.LENGTH_SHORT).show() }
                    }) { Text("Plan") }
                }
            }

            // The momentum ring.
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // R4: announce the score to screen readers (the ring itself is a bare Canvas).
                    Box(Modifier.size(88.dp).semantics { contentDescription = "Momentum $momentum out of 100" }, contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val stroke = 11.dp.toPx()
                            drawArc(Color(0x33888888), -90f, 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        }
                        val accent = MaterialTheme.colorScheme.primary
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val stroke = 11.dp.toPx()
                            drawArc(accent, -90f, momentum / 100f * 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        }
                        Text("$momentum", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Column {
                        Text(ringTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(when {
                            momentum >= 75 -> "Strong — you're carrying real consistency across the board."
                            momentum >= 45 -> "Steady — a few nudges away from a great week."
                            else -> "Rebuilding — small wins today move this fast."
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // The inputs — a disabled module's tile is dropped so the row only shows what's live.
            fun fmtMin(m: Int) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
            val weekStartMs = LocalDate.now(zone).minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
            val timeWeekMin = if (timeOn) com.todocompanion.app.domain.TimeTracking.totalMinutes(timeEntries, weekStartMs, dayEnd, nowMs) else 0
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (habitsOn) MTile("Habit strength", habitStrength?.let { "$it" } ?: "—", Modifier.weight(1f))
                if (tasksOn) MTile("Task reliability", taskRel?.let { "$it%" } ?: "—", Modifier.weight(1f))
                MTile("Focus (7d)", "${focusWeek}m", Modifier.weight(1f))
                if (timeOn) MTile("Time today", fmtMin(timeTodayMin), Modifier.weight(1f))
            }
            if (timeOn) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MTile("Time (7d)", fmtMin(timeWeekMin), Modifier.weight(1f))
                Spacer(Modifier.weight(2f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MTile("Habits", "${activeHabits.size}", Modifier.weight(1f))
                MTile("Tracked tasks", "${reliability.size}", Modifier.weight(1f))
                MTile("Done (7d)", "$tasksDoneWeek", Modifier.weight(1f))
            }

            // ── Tier X · the reasoning layer ─────────────────────────────────────────────────────────

            // X1 — Unified Goals: one objective across a task list + a habit + a time budget, one health bar.
            val goals = remember(settings) { vm.goals() }
            var showGoals by remember { mutableStateOf(false) }
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Goals", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("One objective across tasks, a habit and a time budget.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { showGoals = true }) { Text(if (goals.isEmpty()) "Add" else "Manage") }
                }
                goals.forEach { g ->
                    val gh = remember(g, tasks, habits, checkins, timeEntries) { vm.goalHealth(g) }
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${g.emoji} ${g.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${(gh.overall * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth(gh.overall.toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                        }
                        Spacer(Modifier.height(4.dp))
                        val bits = buildList {
                            if (g.hasTasks) add("✓ ${gh.taskDone}/${gh.taskTotal}")
                            if (g.hasHabit) add("↻ ${gh.habitStrength}% · ${gh.habitStreak}d")
                            if (g.hasBudget) add("⏱ ${fmtMin(gh.minutesTracked)}/${fmtMin(gh.budgetMin)}")
                            gh.daysLeft?.let { add(if (it >= 0) "⌛ ${it}d left" else "⌛ overdue") }
                        }
                        if (bits.isNotEmpty()) Text(bits.joinToString("    "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // Y1 — self-coaching: the goal proposes its own next move (and a session to start).
                        val coach = remember(g, gh) { vm.goalCoaching(g) }
                        if (coach != null) Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🧭 ${coach.text}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            coach.startActivityId?.let { act -> TextButton(onClick = { vm.startActivityTimer(act) }) { Text("Start") } }
                        }
                    }
                }
                // Y8 — contention: goals competing for the same tracked hours.
                val contention = remember(goals) { vm.goalContention() }
                contention.forEach { c ->
                    Text("⚠︎ $c", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (showGoals) GoalsEditorDialog(vm) { showGoals = false }

            // Y6 — anti-burnout radar: hours climbing while habit adherence falls. A caring, early signal.
            val burnout = remember(timeEntries, habits, checkins) { vm.burnoutSignal() }
            if (burnout != null) Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .55f)) {
                Column(Modifier.padding(16.dp)) {
                    Text("A gentle heads-up", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(4.dp))
                    Text(burnout, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            // Y3 — what-if capacity: does new work fit your real hours before you commit?
            if (tasksOn) {
                var extraH by remember { mutableStateOf(0) }
                val snap = remember(tasks, settings) { vm.capacitySnapshot(14) }
                AppCard {
                    Text("What if I take this on?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Next 2 weeks: ${fmtMin(snap.committedMin)} committed of ${fmtMin(snap.capacityMin)}${if (snap.tracked) " (your tracked capacity)" else ""} — ${fmtMin(snap.freeMin)} free.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Add", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { extraH = (extraH - 2).coerceAtLeast(0) }) { Text("−") }
                        Text("${extraH}h", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        TextButton(onClick = { extraH = (extraH + 2).coerceAtMost(80) }) { Text("+") }
                    }
                    if (extraH > 0) {
                        val addMin = extraH * 60
                        val over = (addMin - snap.freeMin).coerceAtLeast(0)
                        Text(
                            if (over == 0) "✓ Fits — you'd still have ${fmtMin(snap.freeMin - addMin)} free."
                            else "✗ ${fmtMin(over)} over your real capacity — something already committed would slip.",
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                            color = if (over == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // Y4 — your ideal day: a scaffold from your peak window + each habit's real rhythm.
            val ideal = remember(timeEntries, habits, checkins) { vm.idealDay() }
            if (ideal.size >= 2) AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Your ideal day", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("Shaped from your own patterns — peak focus and habit rhythm.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (tasksOn && timeOn) TextButton(onClick = { vm.planMyDay { n -> android.widget.Toast.makeText(shareCtx, if (n > 0) "Blocked $n task${if (n == 1) "" else "s"}" else "Nothing to schedule", android.widget.Toast.LENGTH_SHORT).show() } }) { Text("Use it") }
                }
                Spacer(Modifier.height(6.dp))
                ideal.take(6).forEach { b ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(HabitStats.minuteLabel(b.minute), Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(b.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // X5 — end-of-day forecast: at your real pace, how many of today's tasks actually land.
            if (tasksOn) {
                val fc = remember(tasks, timeEntries, settings) { vm.dayForecast() }
                if (fc != null) AppCard {
                    Text("Will today's tasks land?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (fc.willSlip == 0) "At your real pace, all ${fc.willFinish} remaining task${if (fc.willFinish == 1) "" else "s"} fit the time left today."
                        else "At your real pace, ${fc.willFinish} of ${fc.total} remaining tasks fit — ${fc.willSlip} likely slip${if (fc.willSlip == 1) "s" else ""}.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Needs ~${fmtMin(fc.neededMin)}${if (fc.calibrated) " at your real pace" else ""}, ${fmtMin(fc.availMin)} left in your day.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (fc.slipTitles.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        fc.slipTitles.take(3).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    if (settings.honestCapacity) vm.trackedCapacityHours()?.let { h ->
                        Spacer(Modifier.height(6.dp))
                        Text("Capacity from your tracked focus: ~${h}h/day.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // X8 — day replay: planned blocks with no tracked time, one tap to backfill from the plan.
            if (timeOn && tasksOn) {
                val replay = remember(tasks, timeEntries) { vm.dayReplay() }
                if (replay.isNotEmpty()) AppCard {
                    Text("Account for today", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Planned blocks with no tracked time yet — log them in one tap.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    replay.take(4).forEach { b ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(b.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${HabitStats.minuteLabel(b.startMin)} · ${fmtMin(b.durMin)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { vm.backfillBlock(b) }) { Text("Log") }
                        }
                    }
                }
            }

            // X7 — insights feed: the strongest cross-type pattern your data shows this week.
            val insights = remember(tasks, habits, checkins, timeEntries) { vm.insightsFeed() }
            if (insights.isNotEmpty()) AppCard {
                Text("What your data noticed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                insights.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                        Text("✨"); Spacer(Modifier.width(8.dp)); Text(s, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // R2 / T6 — the weekly "state of you" digest: this week vs last, across every live signal.
            val lastWeekStart = LocalDate.now(zone).minusDays(13).atStartOfDay(zone).toInstant().toEpochMilli()
            val timeWk = if (timeOn) com.todocompanion.app.domain.TimeTracking.totalMinutes(timeEntries, weekStartMs, dayEnd, nowMs) else 0
            val timeLastWk = if (timeOn) com.todocompanion.app.domain.TimeTracking.totalMinutes(timeEntries, lastWeekStart, weekStartMs, nowMs) else 0
            val digest = remember(habits, checkins, tasks, focus, momentum, timeWk, timeLastWk) {
                WeeklyDigest.compute(habits, checkins, tasks, focus, momentum, today, zone, timeWk, timeLastWk)
            }
            AppCard {
                Text("Your week", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(digest.headline, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    digest.metrics.forEach { m ->
                        Column(Modifier.weight(1f)) {
                            Text(m.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(m.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            val arrow = when { m.delta > 0 -> "▲ ${m.delta}${m.deltaUnit}"; m.delta < 0 -> "▼ ${-m.delta}${m.deltaUnit}"; else -> "— same" }
                            Text(arrow + if (m.delta != 0) " vs last wk" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = when { m.delta > 0 -> MaterialTheme.colorScheme.primary; m.delta < 0 -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (digest.bestHabit != null || digest.slippingHabit != null) {
                    Spacer(Modifier.height(10.dp))
                    digest.bestHabit?.let { Text("🏆 Strongest: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    digest.slippingHabit?.let { Text("🌱 Room to grow: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(8.dp))
                Text(digest.takeaway, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            }

            // Q6 / U7 — the cross-module correlations, the one thing only a unified store computes.
            val timeLinks = remember(habits, checkins, timeEntries) { if (timeOn && habitsOn) vm.momentumLinks() else emptyList() }
            AppCard {
                Text("What moves what", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                if (correlations.isEmpty() && timeLinks.isEmpty()) Text("Keep logging habits, completing tasks and tracking time — the links between them appear here as the data builds.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    correlations.forEach { ins ->
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                            Text(ins.emoji); Spacer(Modifier.width(8.dp))
                            Text(ins.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    timeLinks.forEach { t ->
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                            Text("⏱"); Spacer(Modifier.width(8.dp))
                            Text(t, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // U6 — plan vs actual (this week) + the estimate-calibration factor. The moat as a number.
            if (timeOn && tasksOn) {
                val pa = remember(tasks, timeEntries) { vm.planVsActualWeek() }
                if (pa.items.isNotEmpty()) AppCard {
                    Text("Planned vs actual", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("This week you planned ${fmtMin(pa.plannedMin)} and tracked ${fmtMin(pa.actualMin)} across ${pa.items.size} task${if (pa.items.size == 1) "" else "s"}.",
                        style = MaterialTheme.typography.bodyMedium)
                    pa.calibration?.let { cal ->
                        Spacer(Modifier.height(6.dp))
                        val pct = ((cal - 1.0) * 100).toInt()
                        val phrase = when { pct > 10 -> "You run about $pct% over your estimates — the app will pad future ones."; pct < -10 -> "You finish about ${-pct}% under your estimates."; else -> "Your estimates are well-calibrated." }
                        Text("⚖️ $phrase", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(8.dp))
                    pa.items.sortedByDescending { it.actualMin }.take(4).forEach { it2 ->
                        val over = it2.actualMin > it2.plannedMin
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text(it2.label.take(24), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${fmtMin(it2.actualMin)} / ${fmtMin(it2.plannedMin)}", style = MaterialTheme.typography.labelMedium,
                                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // U10 — your data is safe: last-backup age + one-tap export, so the local-only trade never bites.
            AppCard {
                Text("Your data is safe", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                val lastBk = settings.lastSyncAt
                val ageTxt = if (lastBk <= 0L) "No backup yet." else {
                    val days = ((nowMs - lastBk) / 86_400_000L).toInt()
                    when { days <= 0 -> "Last backup today."; days == 1 -> "Last backup yesterday."; else -> "Last backup $days days ago." }
                }
                val stale = lastBk <= 0L || (nowMs - lastBk) > 7L * 86_400_000L
                Text(ageTxt + if (stale) "  Everything lives only on this device — export a copy." else "  You're covered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = {
                    vm.exportToDownloads("json") { loc -> android.widget.Toast.makeText(shareCtx, if (loc != null) "Backup saved to $loc" else "Couldn't save backup", android.widget.Toast.LENGTH_SHORT).show() }
                }) { Icon(Icons.Filled.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Back up now") }
            }

            // V6 — cross-type tag report: hours + tasks + habit-days grouped by one tag. The zero-permission
            // answer to WHPH's app-usage dashboard, made possible by unified tags across all three modules.
            val tagReport = remember(timeEntries, tasks, habits, checkins) { vm.crossTypeTagReport(7) }
            if (tagReport.isNotEmpty()) AppCard {
                Text("Where your week went — by tag", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Last 7 days across time, tasks and habits.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                tagReport.take(6).forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("#${t.tag}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val parts = buildList {
                            if (t.minutes > 0) add("${t.minutes / 60}h ${t.minutes % 60}m")
                            if (t.tasksDone > 0) add("${t.tasksDone} task${if (t.tasksDone == 1) "" else "s"}")
                            if (t.habitDays > 0) add("${t.habitDays} habit day${if (t.habitDays == 1) "" else "s"}")
                        }
                        Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // V7 — Reality Replay: a shareable recap across all three modules, rendered on-device.
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Your week in review", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("A shareable recap — tracked time, tasks, habits, momentum.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = { vm.shareRecap { } }) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Share") }
                }
            }

            // V12 — the rewards wallet: points earned by doing the work, spent on self-chosen treats.
            val rewardsList = com.todocompanion.app.domain.Rewards.parse(settings.rewardsJson)
            if (rewardsList.isNotEmpty()) AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rewards", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("⭐ ${settings.pointsBalance} pts", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(6.dp))
                rewardsList.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${r.emoji} ${r.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(enabled = settings.pointsBalance >= r.cost, onClick = { vm.redeemReward(r) }) { Text("Redeem · ${r.cost}") }
                    }
                }
            }

            // W4 — Balance: where the week actually went, by life area (cross-type tags). A wellbeing lens.
            val balance = remember(timeEntries, tasks, habits, checkins) { vm.balanceBreakdown(7) }
            if (balance.size >= 2) AppCard {
                Text("Your balance this week", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Across tracked time, tasks and habits — by tag.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                // A single proportional bar split by area.
                Row(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp))) {
                    balance.take(6).forEachIndexed { i, sl ->
                        val hue = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary,
                            MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceVariant)[i % 6]
                        Box(Modifier.weight(sl.share.toFloat().coerceAtLeast(0.02f)).fillMaxHeight().background(hue))
                    }
                }
                Spacer(Modifier.height(8.dp))
                balance.take(5).forEach { sl ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text("#${sl.area}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${(sl.share * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // W7 — the self-writing weekly review, drafted from unified data; share or copy it.
            var reviewText by remember { mutableStateOf<String?>(null) }
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Weekly review", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("Drafted from your week across all three modules.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { reviewText = if (reviewText == null) vm.weeklyReviewText() else null }) { Text(if (reviewText == null) "Write it" else "Hide") }
                }
                reviewText?.let { txt ->
                    Spacer(Modifier.height(8.dp))
                    Text(txt, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = {
                        val cm = shareCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Weekly review", txt))
                        android.widget.Toast.makeText(shareCtx, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("Copy") }
                }
            }

            // R5 — the "how it all fits" guide, in one plain paragraph, so the numbers above are legible.
            AppCard {
                Text("How this fits together", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Three modules share one store — Tasks, Habits and Time — and any can be your primary or switched " +
                        "off in Settings ▸ Modules (nothing is deleted). Habits build strength, recurring tasks build " +
                        "reliability, Focus and the Time tracker share one timeline, and a task or habit can be timed so " +
                        "its minutes flow back in. Momentum blends whatever's on; with one module it becomes that module's " +
                        "own summary. “Your week” compares the last 7 days to the 7 before, and ＋ Capture files a line as a " +
                        "habit or a task automatically — never into a module you turned off.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun MTile(label: String, value: String, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * X1 — the Unified Goals editor: build a goal from any mix of a task list, a supporting habit, and a
 * time budget against an activity. Only this app can bind all three into one objective. Fully offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalsEditorDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val goals = remember { mutableStateOf(vm.goals()) }
    val lists by vm.lists.collectAsState()
    val habits by vm.habits.collectAsState()
    val activities by vm.timeActivities.collectAsState()
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("🎯") }
    var listId by remember { mutableStateOf("") }
    var habitId by remember { mutableStateOf("") }
    var activityId by remember { mutableStateOf("") }
    var budgetH by remember { mutableStateOf("") }
    fun persist(newList: List<com.todocompanion.app.domain.Goal>) { goals.value = newList; vm.saveGoals(newList) }
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Goals") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                goals.value.forEach { g ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${g.emoji} ${g.name}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { persist(goals.value.filterNot { it.id == g.id }) }) { Text("Remove") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("New goal", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                // Y5 — the goal library: one tap pre-shapes name, icon and budget.
                Spacer(Modifier.height(6.dp)); Text("Start from a template", style = MaterialTheme.typography.labelSmall, color = faint)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.todocompanion.app.domain.Goals.TEMPLATES.forEach { t ->
                        FilterChip(selected = false, onClick = { name = t.name; emoji = t.emoji; budgetH = t.budgetHours.toString() }, label = { Text("${t.emoji} ${t.name}") })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = emoji, onValueChange = { emoji = it.take(2) }, modifier = Modifier.width(76.dp), label = { Text("Icon") }, singleLine = true)
                    OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.weight(1f), label = { Text("Name") }, singleLine = true)
                }
                Spacer(Modifier.height(8.dp)); Text("Task list", style = MaterialTheme.typography.labelSmall, color = faint)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = listId == "", onClick = { listId = "" }, label = { Text("None") })
                    lists.forEach { l -> FilterChip(selected = listId == l.id, onClick = { listId = l.id }, label = { Text(l.name) }) }
                }
                Spacer(Modifier.height(8.dp)); Text("Supporting habit", style = MaterialTheme.typography.labelSmall, color = faint)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = habitId == "", onClick = { habitId = "" }, label = { Text("None") })
                    habits.filter { !it.archived }.forEach { h -> FilterChip(selected = habitId == h.id, onClick = { habitId = h.id }, label = { Text((h.emoji?.plus(" ") ?: "") + h.name) }) }
                }
                Spacer(Modifier.height(8.dp)); Text("Time budget", style = MaterialTheme.typography.labelSmall, color = faint)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = activityId == "", onClick = { activityId = "" }, label = { Text("None") })
                    activities.filter { !it.archived }.forEach { a -> FilterChip(selected = activityId == a.id, onClick = { activityId = a.id }, label = { Text((a.emoji?.plus(" ") ?: "") + a.name) }) }
                }
                if (activityId != "") {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = budgetH, onValueChange = { v -> budgetH = v.filter { it.isDigit() }.take(4) }, label = { Text("Budget (hours)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(
                    enabled = name.isNotBlank() && (listId != "" || habitId != "" || (activityId != "" && (budgetH.toIntOrNull() ?: 0) > 0)),
                    onClick = {
                        val g = com.todocompanion.app.domain.Goal(
                            id = java.util.UUID.randomUUID().toString(), name = name.trim(), emoji = emoji.ifBlank { "🎯" },
                            listId = listId, habitId = habitId, activityId = activityId, budgetMinutes = (budgetH.toIntOrNull() ?: 0) * 60,
                        )
                        persist(goals.value + g)
                        name = ""; emoji = "🎯"; listId = ""; habitId = ""; activityId = ""; budgetH = ""
                    },
                ) { Text("Add goal") }
            }
        },
    )
}

/**
 * R3 — the unified capture box. One line in; a live guess ("→ Habit"/"→ Task") the user can flip with a
 * tap; then it's parsed by the matching quick-add parser and created. Fully offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartCaptureDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var override by remember { mutableStateOf<SmartCapture.Kind?>(null) }
    val guess = remember(text) { SmartCapture.classify(text) }
    val kind = override ?: guess.kind
    val ctx = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Capture anything") },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("“read 20 pages every night”, “email Sam tomorrow 9am #t20 !!”, or “track deep work”") },
                    singleLine = false, minLines = 2,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = kind == SmartCapture.Kind.TASK, onClick = { override = SmartCapture.Kind.TASK }, label = { Text("✓ Task") })
                    FilterChip(selected = kind == SmartCapture.Kind.HABIT, onClick = { override = SmartCapture.Kind.HABIT }, label = { Text("↻ Habit") })
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (text.isBlank()) "I'll sort it into the right place — tap a chip to override."
                    else if (override == null) "Auto: ${guess.reason}" else "You chose ${if (kind == SmartCapture.Kind.HABIT) "Habit" else "Task"}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    // W1 omnibox: with no override, route to timer/habit/task; a chip forces task/habit.
                    if (override == null) {
                        vm.omniCapture(text) { what ->
                            val msg = when (what) { "timer" -> "Started tracking"; "habit" -> "Added as a habit"; else -> "Added as a task" }
                            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else vm.smartCapture(text, override) { k ->
                        val what = if (k == SmartCapture.Kind.HABIT) "habit" else "task"
                        android.widget.Toast.makeText(ctx, "Added as a $what", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
