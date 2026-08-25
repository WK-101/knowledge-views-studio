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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.domain.habit.HabitStats
import com.todocompanion.app.ui.AppViewModel
import java.time.LocalDate

private val HABIT_COLORS = listOf(0xFF12A594, 0xFF3E7BFA, 0xFF8B5CF6, 0xFFE5484D, 0xFFF59E0B, 0xFFEC4899, 0xFF0EA371)

private val HABIT_SECTIONS = listOf("Morning", "Afternoon", "Evening", "Anytime")
/** Which time-of-day section a habit belongs to, from its earliest reminder (0=Morning … 3=Anytime). */
private fun habitSectionOf(h: HabitEntity): Int {
    val first = h.reminderTimes.split(",").mapNotNull { it.trim().toIntOrNull() }.minOrNull() ?: return 3
    return when { first < 12 * 60 -> 0; first < 17 * 60 -> 1; else -> 2 }
}

private class HabitPreset(val emoji: String, val name: String, val unit: String?, val target: Int, val color: Long)
private val HABIT_PRESETS = listOf(
    HabitPreset("💧", "Drink water", "glasses", 8, 0xFF3E7BFA),
    HabitPreset("🏃", "Exercise", null, 1, 0xFFE5484D),
    HabitPreset("📖", "Read", "pages", 20, 0xFF8B5CF6),
    HabitPreset("🧘", "Meditate", "min", 10, 0xFF12A594),
    HabitPreset("🚶", "Walk", "steps", 8000, 0xFFF59E0B),
    HabitPreset("✍️", "Journal", null, 1, 0xFFEC4899),
    HabitPreset("💊", "Vitamins", null, 1, 0xFF14B8A6),
    HabitPreset("🥗", "Eat healthy", null, 1, 0xFF0EA371),
    HabitPreset("😴", "Sleep by 11", null, 1, 0xFF6366F1),
    HabitPreset("📵", "No phone in bed", null, 1, 0xFF64748B),
    HabitPreset("🙏", "Gratitude", null, 1, 0xFFF97316),
    HabitPreset("🦷", "Floss", null, 1, 0xFF06B6D4),
)

@Composable
fun HabitsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val today = LocalDate.now().toEpochDay()
    var editing by remember { mutableStateOf<HabitEntity?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    var presetOpen by remember { mutableStateOf(false) }

    if (habits.isEmpty()) {
        Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), CircleShape), contentAlignment = Alignment.Center) {
                Text("🌱", style = MaterialTheme.typography.headlineLarge)
            }
            Spacer(Modifier.size(14.dp))
            Text("Build a habit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Track daily habits and keep your streak going.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { presetOpen = true }) { Text("✨ Starter habits") }
                TextButton(onClick = { addOpen = true }) { Text("＋ New habit") }
            }
        }
    } else {
        // Group by time-of-day section derived from each habit's earliest reminder (Anytime = no reminder).
        val bySection = remember(habits) { habits.groupBy { habitSectionOf(it) } }
        val order = (0..3).filter { bySection.containsKey(it) }
        val showHeaders = order.size > 1
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = 100.dp)) {
            order.forEach { sec ->
                if (showHeaders) item(key = "sec$sec") {
                    Text(HABIT_SECTIONS[sec].uppercase(), Modifier.padding(start = 18.dp, top = 12.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(bySection[sec]!!, key = { it.id }) { h ->
                    val hc = checkins.filter { it.habitId == h.id }
                    val todayCount = hc.firstOrNull { it.epochDay == today }?.count ?: 0
                    val doneDays = hc.filter { it.count >= h.targetPerDay }.map { it.epochDay }.toSet()
                    val countsByDay = hc.associate { it.epochDay to it.count }
                    val schedule = HabitStats.parseSchedule(h.scheduleDays)
                    HabitRow(h, todayCount, HabitStats.streak(doneDays, today, schedule), HabitStats.rate(doneDays, today, schedule),
                        countsByDay, today, schedule, HabitStats.isScheduled(today, schedule),
                        onCycle = { vm.cycleHabit(h, today, todayCount) }, onEdit = { editing = h })
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { presetOpen = true }) { Text("✨ Starter habits") }
                    TextButton(onClick = { addOpen = true }) { Text("＋ New habit") }
                }
            }
        }
    }

    if (presetOpen) HabitPresetDialog(onDismiss = { presetOpen = false }, onPick = { p ->
        vm.createHabit(p.name, p.emoji, p.color, p.target, p.unit, "", ""); presetOpen = false
    })
    if (addOpen) HabitDialog(null, onDismiss = { addOpen = false }, onDelete = {},
        onSave = { name, emoji, color, target, unit, sched, rem -> vm.createHabit(name, emoji, color, target, unit, sched, rem); addOpen = false })
    editing?.let { h ->
        HabitDialog(h, onDismiss = { editing = null }, onDelete = { vm.deleteHabit(h.id); editing = null },
            onSave = { name, emoji, color, target, unit, sched, rem -> vm.saveHabit(h.copy(name = name, emoji = emoji, colorArgb = color, targetPerDay = target, unit = unit, scheduleDays = sched, reminderTimes = rem)); editing = null })
    }
}

@Composable
private fun HabitRow(h: HabitEntity, todayCount: Int, streak: Int, rate: Float, countsByDay: Map<Long, Int>, today: Long, schedule: Set<Int>, scheduledToday: Boolean, onCycle: () -> Unit, onEdit: () -> Unit) {
    val color = h.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val done = todayCount >= h.targetPerDay
    val emptyCell = MaterialTheme.colorScheme.surfaceVariant
    val scheduleLabel = if (schedule.isEmpty()) "Every day"
        else schedule.sorted().joinToString(" ") { java.time.DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
    ) {
      Column(Modifier.fillMaxWidth().clickable { onEdit() }.padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Progress ring / emoji, tap to cycle today's progress. On an off day it reads "rest".
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(if (done) color.copy(alpha = .16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (scheduledToday) .5f else .25f))
                    .border(2.dp, if (done) color else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable { onCycle() },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    done -> Icon(Icons.Filled.Check, null, tint = color, modifier = Modifier.size(22.dp))
                    !scheduledToday -> Text("–", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
                    h.targetPerDay > 1 -> Text("$todayCount/${h.targetPerDay}", style = MaterialTheme.typography.labelMedium, color = color)
                    h.emoji != null -> Text(h.emoji, style = MaterialTheme.typography.titleMedium)
                    else -> Box(Modifier.size(10.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(h.name + (h.unit?.let { " · ${h.targetPerDay} $it" } ?: ""), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${(rate * 100).toInt()}% · $scheduleLabel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (streak > 0) Text("🔥 $streak", style = MaterialTheme.typography.labelLarge, color = color)
        }
        // 30-day completion heat strip; off-schedule days are shown hollow (skipped).
        Spacer(Modifier.size(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            (29 downTo 0).forEach { back ->
                val day = today - back
                val c = countsByDay[day] ?: 0
                val scheduled = HabitStats.isScheduled(day, schedule)
                val cell = when {
                    c >= h.targetPerDay -> color
                    c > 0 -> color.copy(alpha = .4f)
                    !scheduled -> emptyCell.copy(alpha = .25f)
                    else -> emptyCell
                }
                Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(3.dp)).background(cell))
            }
        }
      }
    }
}

@Composable
private fun HabitDialog(existing: HabitEntity?, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (String, String?, Long?, Int, String?, String, String) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var emoji by remember { mutableStateOf(existing?.emoji ?: "") }
    var unit by remember { mutableStateOf(existing?.unit ?: "") }
    var color by remember { mutableStateOf(existing?.colorArgb ?: HABIT_COLORS.first()) }
    var target by remember { mutableStateOf(existing?.targetPerDay ?: 1) }
    var days by remember { mutableStateOf(HabitStats.parseSchedule(existing?.scheduleDays ?: "")) }
    var reminders by remember { mutableStateOf(existing?.reminderTimes.orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..1439 }.toSortedSet()) }
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim(), emoji.trim().ifBlank { null }, color, target, unit.trim().ifBlank { null }, days.sorted().joinToString(","), reminders.sorted().joinToString(",")) }) { Text("Save") } },
        dismissButton = { if (existing != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } else TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (existing == null) "New habit" else "Habit") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(emoji, { emoji = it.take(2) }, singleLine = true, label = { Text("Emoji") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(unit, { unit = it.take(12) }, singleLine = true, label = { Text("Unit (e.g. glasses)") }, modifier = Modifier.weight(1.4f))
                }
                Spacer(Modifier.size(12.dp))
                Text("Colour", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    HABIT_COLORS.forEach { c ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape).clickable { color = c })
                    }
                }
                Spacer(Modifier.size(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Target per day" + (unit.trim().ifBlank { null }?.let { " ($it)" } ?: ""), Modifier.weight(1f))
                    TextButton(onClick = { target = (target - 1).coerceAtLeast(1) }) { Text("−") }
                    Text(target.toString(), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { target = (target + 1).coerceAtMost(50) }) { Text("+") }
                }
                Spacer(Modifier.size(8.dp))
                Text("Repeat", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { d ->
                        val on = d in days
                        Box(
                            Modifier.weight(1f).clip(CircleShape)
                                .background(if (on) Color(color) else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { days = if (on) days - d else days + d }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(java.time.DayOfWeek.of(d).getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(if (days.isEmpty()) "No days selected = every day" else "On selected days only",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))

                Spacer(Modifier.size(12.dp))
                Text("Reminders", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                reminders.sorted().forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🔔  " + "%02d:%02d".format(m / 60, m % 60), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { reminders = reminders.toSortedSet().also { it.remove(m) } }) { Text("Remove") }
                    }
                }
                TextButton(onClick = {
                    val n = java.time.LocalTime.now()
                    android.app.TimePickerDialog(ctx, { _, h, min -> reminders = reminders.toSortedSet().also { it.add(h * 60 + min) } },
                        n.hour, n.minute, android.text.format.DateFormat.is24HourFormat(ctx)).show()
                }) { Text("＋ Add reminder time") }
                Text("A local notification fires at each time, on scheduled days, until you complete it.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        },
    )
}

@Composable
private fun HabitPresetDialog(onDismiss: () -> Unit, onPick: (HabitPreset) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Starter habits") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Tap to add — tweak the target, schedule and reminders after.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                HABIT_PRESETS.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(p) }.padding(vertical = 8.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(p.color).copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                            Text(p.emoji, style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(p.name + (p.unit?.let { " · ${p.target} $it" } ?: ""), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Icon(Icons.Filled.Add, "Add", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        },
    )
}
