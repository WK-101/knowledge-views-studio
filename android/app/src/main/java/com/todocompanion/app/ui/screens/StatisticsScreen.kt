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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.habit.HabitStats
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val tasks by vm.tasks.collectAsState()
    val focus by vm.focusSessions.collectAsState()
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val todayEpoch = today.toEpochDay()

    fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val completed = tasks.filter { it.completed && it.completedAt != null }
    val last7 = completed.count { !dayOf(it.completedAt!!).isBefore(today.minusDays(6)) }
    val last30 = completed.count { !dayOf(it.completedAt!!).isBefore(today.minusDays(29)) }
    val perDay = (0..6).map { i -> val d = today.minusDays((6 - i).toLong()); d to completed.count { dayOf(it.completedAt!!) == d } }

    val focus7 = focus.filter { it.epochDay >= todayEpoch - 6 }
    val focusMin = focus7.sumOf { it.minutes }
    val focusSessions = focus7.size

    val habitRates = habits.map { h ->
        val done = checkins.filter { it.habitId == h.id && it.count >= h.targetPerDay }.map { it.epochDay }.toSet()
        HabitStats.rate(done, todayEpoch)
    }
    val avgHabit = if (habitRates.isEmpty()) 0f else habitRates.average().toFloat()

    // ---- Gamification (all on-device) ----
    val totalDone = completed.size
    val totalFocusMin = focus.sumOf { it.minutes }
    val doneDays = completed.map { dayOf(it.completedAt!!).toEpochDay() }.toSet()
    val streak = HabitStats.streak(doneDays, todayEpoch)
    // Achievement score: tasks + focus + streak, in the spirit of TickTick's Achievement Score.
    val score = totalDone * 10 + totalFocusMin / 6 + streak * 15
    val level = 1 + score / 500
    val intoLevel = (score % 500) / 500f
    val levelTitle = when (level) {
        1 -> "Getting started"; 2 -> "Finding rhythm"; 3 -> "In the flow"; 4 -> "Consistent"
        5 -> "Focused"; 6 -> "Productive"; 7 -> "Relentless"; else -> "Master"
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Statistics") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            AchievementsCard(score, level, levelTitle, intoLevel, streak, totalDone, totalFocusMin)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Done · 7 days", last7.toString(), Modifier.weight(1f))
                StatTile("Done · 30 days", last30.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            AppCard {
                Text("Completed per day", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                val max = (perDay.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
                Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    perDay.forEach { (d, n) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            Text(if (n > 0) n.toString() else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(Modifier.fillMaxWidth().height((6 + 90 * n / max).dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = if (n > 0) 1f else .25f)))
                            Spacer(Modifier.height(4.dp))
                            Text(d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Focus · 7 days", "${focusMin}m", Modifier.weight(1f), sub = "$focusSessions sessions")
                StatTile("Habit rate", "${(avgHabit * 100).toInt()}%", Modifier.weight(1f), sub = "${habits.size} habits")
            }
            Spacer(Modifier.height(16.dp))
            Text("All stats are computed on-device from your data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AchievementsCard(score: Int, level: Int, levelTitle: String, intoLevel: Float, streak: Int, totalDone: Int, totalFocusMin: Int) {
    data class Badge(val emoji: String, val label: String, val unlocked: Boolean)
    val badges = listOf(
        Badge("🌱", "First step", totalDone >= 1),
        Badge("✅", "10 done", totalDone >= 10),
        Badge("🏅", "50 done", totalDone >= 50),
        Badge("🏆", "100 done", totalDone >= 100),
        Badge("🔥", "7-day streak", streak >= 7),
        Badge("⏳", "10h focus", totalFocusMin >= 600),
    )
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text(level.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Level $level · $levelTitle", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("$score pts" + if (streak > 0) " · 🔥 $streak-day streak" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.LinearProgressIndicator(
            progress = { intoLevel },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        )
        Text("${(intoLevel * 500).toInt()} / 500 to level ${level + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            badges.forEach { b ->
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (b.unlocked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(b.emoji, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(6.dp))
                    Text(b.label, style = MaterialTheme.typography.labelMedium,
                        color = if (b.unlocked) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null) {
    AppCard(modifier) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}
