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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Momentum") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
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
                    Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
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
