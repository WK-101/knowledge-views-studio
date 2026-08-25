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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * M5: the trends & correlations dashboard. Promotes the on-device insight engine to a full screen —
 * strength by habit, the best weekday, month-over-month strength, and the cross-module correlations
 * (habit↔habit, habit↔task) that only this app's unified store can compute. Entirely offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitTrendsScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val today = LocalDate.now().toEpochDay()
    val active = habits.filter { !it.archived }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Trends & correlations") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        if (active.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Add a few habits to see trends.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        // Per-habit day sets + strength.
        val perHabit = remember(habits, checkins, today) {
            active.map { h ->
                val hc = checkins.filter { it.habitId == h.id }
                val done = hc.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
                val skip = hc.filter { it.status == "skip" }.map { it.epochDay }.toSet()
                val rel = hc.filter { HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
                HabitTrend(h, done, skip, rel,
                    strength = HabitStats.strength(h, done, skip, rel, today),
                    streak = HabitStats.currentStreak(h, done, skip, rel, today))
            }.sortedByDescending { it.strength }
        }
        val avgStrength = perHabit.map { it.strength }.average().toInt()
        val monthStart = today - 29
        val checkinsThisMonth = checkins.count { it.epochDay in monthStart..today && it.status == "done" }
        val bestStreakOverall = perHabit.maxOfOrNull { it.streak } ?: 0

        // Weekday aggregate (average completion rate across build habits).
        val weekday = remember(perHabit) {
            val sums = FloatArray(7); val counts = IntArray(7)
            perHabit.filter { it.habit.habitType != "break" }.forEach { t ->
                val r = HabitStats.weekdayRates(t.done, t.skip, today, 180)
                for (i in 0..6) if (r[i] > 0f) { sums[i] += r[i]; counts[i]++ }
            }
            FloatArray(7) { if (counts[it] > 0) sums[it] / counts[it] else 0f }
        }

        // Month-over-month overall strength (avg across habits at four points back).
        val mom = remember(perHabit) {
            listOf(0, 30, 60, 90).map { back ->
                val d = today - back
                back to perHabit.map { HabitStats.strength(it.habit, it.done, it.skip, it.relapse, d) }.average().toInt()
            }
        }

        val correlations = remember(habits, checkins, tasks, today) {
            HabitInsights.compute(habits, checkins, tasks, today, max = 8)
                .filter { it.emoji in setOf("🔗", "⚡", "🗝️", "📉") }
        }

        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Overview tiles.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Habits", active.size.toString(), Modifier.weight(1f))
                StatTile("Avg strength", "$avgStrength", Modifier.weight(1f))
                StatTile("Done (30d)", "$checkinsThisMonth", Modifier.weight(1f))
                StatTile("Best streak", "$bestStreakOverall", Modifier.weight(1f))
            }

            // Strength by habit.
            AppCard {
                Text("Strength by habit", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                perHabit.forEach { t ->
                    val color = t.habit.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text((t.habit.emoji?.plus(" ") ?: "") + t.habit.name, Modifier.width(120.dp),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth(t.strength / 100f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(color))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("${t.strength}", Modifier.width(34.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Best weekday.
            AppCard {
                Text("Which weekday you're strongest", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                val maxV = (weekday.maxOrNull() ?: 1f).coerceAtLeast(0.01f)
                val bestIdx = weekday.indices.maxByOrNull { weekday[it] } ?: 0
                Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    for (i in 0..6) {
                        val frac = (weekday[i] / maxV).coerceIn(0f, 1f)
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            Box(Modifier.fillMaxWidth().height((6 + frac * 70).dp).clip(RoundedCornerShape(4.dp))
                                .background(if (i == bestIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = .35f)))
                            Spacer(Modifier.height(4.dp))
                            Text(DayOfWeek.of(i + 1).getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (weekday[bestIdx] > 0f)
                    Text("Best on ${DayOfWeek.of(bestIdx + 1).getDisplayName(TextStyle.FULL, Locale.getDefault())}s.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }

            // Month-over-month overall strength.
            AppCard {
                Text("Overall strength over time", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                    mom.reversed().forEach { (back, v) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            Text("$v", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Box(Modifier.fillMaxWidth().height((6 + v / 100f * 56).dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.tertiary))
                            Spacer(Modifier.height(4.dp))
                            Text(if (back == 0) "now" else "-${back}d", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Correlations from the insight engine.
            AppCard {
                Text("Correlations the coach found", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                if (correlations.isEmpty()) Text("Keep logging — links between habits and your productivity appear here as the data builds.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else correlations.forEach { ins ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                        Text(ins.emoji)
                        Spacer(Modifier.width(8.dp))
                        Text(ins.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

private class HabitTrend(
    val habit: com.todocompanion.app.data.entity.HabitEntity,
    val done: Set<Long>, val skip: Set<Long>, val relapse: Set<Long>,
    val strength: Int, val streak: Int,
)

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
