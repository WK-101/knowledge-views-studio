package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.domain.habit.HabitStats
import com.todocompanion.app.ui.AppViewModel
import java.time.LocalDate

private val HABIT_COLORS = listOf(0xFF12A594, 0xFF3E7BFA, 0xFF8B5CF6, 0xFFE5484D, 0xFFF59E0B, 0xFFEC4899, 0xFF0EA371)

@Composable
fun HabitsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val today = LocalDate.now().toEpochDay()
    var editing by remember { mutableStateOf<HabitEntity?>(null) }
    var addOpen by remember { mutableStateOf(false) }

    if (habits.isEmpty()) {
        Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), CircleShape), contentAlignment = Alignment.Center) {
                Text("🌱", style = MaterialTheme.typography.headlineLarge)
            }
            Spacer(Modifier.size(14.dp))
            Text("Build a habit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Track daily habits and keep your streak going.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(14.dp))
            TextButton(onClick = { addOpen = true }) { Text("＋ New habit") }
        }
    } else {
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = 100.dp)) {
            items(habits, key = { it.id }) { h ->
                val hc = checkins.filter { it.habitId == h.id }
                val todayCount = hc.firstOrNull { it.epochDay == today }?.count ?: 0
                val doneDays = hc.filter { it.count >= h.targetPerDay }.map { it.epochDay }.toSet()
                val countsByDay = hc.associate { it.epochDay to it.count }
                HabitRow(h, todayCount, HabitStats.streak(doneDays, today), HabitStats.rate(doneDays, today), countsByDay, today,
                    onCycle = { vm.cycleHabit(h, today, todayCount) }, onEdit = { editing = h })
            }
            item { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { TextButton(onClick = { addOpen = true }) { Text("＋ New habit") } } }
        }
    }

    if (addOpen) HabitDialog(null, onDismiss = { addOpen = false }, onDelete = {},
        onSave = { name, emoji, color, target -> vm.createHabit(name, emoji, color, target); addOpen = false })
    editing?.let { h ->
        HabitDialog(h, onDismiss = { editing = null }, onDelete = { vm.deleteHabit(h.id); editing = null },
            onSave = { name, emoji, color, target -> vm.saveHabit(h.copy(name = name, emoji = emoji, colorArgb = color, targetPerDay = target)); editing = null })
    }
}

@Composable
private fun HabitRow(h: HabitEntity, todayCount: Int, streak: Int, rate: Float, countsByDay: Map<Long, Int>, today: Long, onCycle: () -> Unit, onEdit: () -> Unit) {
    val color = h.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val done = todayCount >= h.targetPerDay
    val emptyCell = MaterialTheme.colorScheme.surfaceVariant
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
    ) {
      Column(Modifier.fillMaxWidth().clickable { onEdit() }.padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Progress ring / emoji, tap to cycle today's progress.
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(if (done) color.copy(alpha = .16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))
                    .border(2.dp, if (done) color else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable { onCycle() },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    done -> Icon(Icons.Filled.Check, null, tint = color, modifier = Modifier.size(22.dp))
                    h.emoji != null -> Text(h.emoji, style = MaterialTheme.typography.titleMedium)
                    h.targetPerDay > 1 -> Text("$todayCount/${h.targetPerDay}", style = MaterialTheme.typography.labelMedium, color = color)
                    else -> Box(Modifier.size(10.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(h.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${(rate * 100).toInt()}% · last 30 days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (streak > 0) Text("🔥 $streak", style = MaterialTheme.typography.labelLarge, color = color)
        }
        // 30-day completion heat strip.
        Spacer(Modifier.size(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            (29 downTo 0).forEach { back ->
                val day = today - back
                val c = countsByDay[day] ?: 0
                val cell = when {
                    c >= h.targetPerDay -> color
                    c > 0 -> color.copy(alpha = .4f)
                    else -> emptyCell
                }
                Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(3.dp)).background(cell))
            }
        }
      }
    }
}

@Composable
private fun HabitDialog(existing: HabitEntity?, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (String, String?, Long?, Int) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var emoji by remember { mutableStateOf(existing?.emoji ?: "") }
    var color by remember { mutableStateOf(existing?.colorArgb ?: HABIT_COLORS.first()) }
    var target by remember { mutableStateOf(existing?.targetPerDay ?: 1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim(), emoji.trim().ifBlank { null }, color, target) }) { Text("Save") } },
        dismissButton = { if (existing != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } else TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (existing == null) "New habit" else "Habit") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(emoji, { emoji = it.take(2) }, singleLine = true, label = { Text("Emoji (optional)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(12.dp))
                Text("Colour", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    HABIT_COLORS.forEach { c ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape).clickable { color = c })
                    }
                }
                Spacer(Modifier.size(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Times per day", Modifier.weight(1f))
                    TextButton(onClick = { target = (target - 1).coerceAtLeast(1) }) { Text("−") }
                    Text(target.toString(), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { target = (target + 1).coerceAtMost(20) }) { Text("+") }
                }
            }
        },
    )
}
