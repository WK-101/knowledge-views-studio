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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
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
        // Habit strength (avg over active build habits).
        val activeHabits = habits.filter { !it.archived }
        val habitStrength = remember(habits, checkins, today) {
            val vals = activeHabits.map { h ->
                val hc = checkins.filter { it.habitId == h.id }
                val done = hc.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
                val skip = hc.filter { it.status == "skip" }.map { it.epochDay }.toSet()
                val rel = hc.filter { HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
                HabitStats.strength(h, done, skip, rel, today)
            }
            if (vals.isEmpty()) null else vals.average().toInt()
        }
        // Task reliability (avg over recurring tasks that have a score).
        val taskRel = reliability.values.map { it.score }.let { if (it.isEmpty()) null else it.average().toInt() }
        // Focus minutes this week.
        val weekDays = (0 until 7).map { today - it }.toSet()
        val focusWeek = focus.filter { it.epochDay in weekDays }.sumOf { it.minutes }
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

        if (activeHabits.isEmpty() && reliability.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Add a few habits or recurring tasks to see your momentum.", Modifier.padding(32.dp),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Scaffold
        }

        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
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
                        Text("Today's momentum", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(when {
                            momentum >= 75 -> "Strong — you're carrying real consistency across the board."
                            momentum >= 45 -> "Steady — a few nudges away from a great week."
                            else -> "Rebuilding — small wins today move this fast."
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // The three inputs.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MTile("Habit strength", habitStrength?.let { "$it" } ?: "—", Modifier.weight(1f))
                MTile("Task reliability", taskRel?.let { "$it%" } ?: "—", Modifier.weight(1f))
                MTile("Focus (7d)", "${focusWeek}m", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MTile("Habits", "${activeHabits.size}", Modifier.weight(1f))
                MTile("Tracked tasks", "${reliability.size}", Modifier.weight(1f))
                MTile("Done (7d)", "$tasksDoneWeek", Modifier.weight(1f))
            }

            // R2 — the weekly "state of you" digest: this week vs last, across all three signals.
            val digest = remember(habits, checkins, tasks, focus, momentum) {
                WeeklyDigest.compute(habits, checkins, tasks, focus, momentum, today, zone)
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

            // Q6 — the cross-module correlations, the one thing only a unified store computes.
            AppCard {
                Text("What moves what", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                if (correlations.isEmpty()) Text("Keep logging habits and completing tasks — the links between them appear here as the data builds.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else correlations.forEach { ins ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                        Text(ins.emoji); Spacer(Modifier.width(8.dp))
                        Text(ins.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // R5 — the "how it all fits" guide, in one plain paragraph, so the numbers above are legible.
            AppCard {
                Text("How this fits together", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Habits build strength, recurring tasks build reliability, and Focus sessions track deep work. " +
                        "Momentum blends all three into one score. “Your week” compares your last 7 days to the 7 before. " +
                        "The ＋ Capture box files whatever you type as a habit or a task automatically — tap the chip to override.",
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
                    placeholder = { Text("“read 20 pages every night” or “email Sam tomorrow 9am”") },
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
                    vm.smartCapture(text, override) { k ->
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
