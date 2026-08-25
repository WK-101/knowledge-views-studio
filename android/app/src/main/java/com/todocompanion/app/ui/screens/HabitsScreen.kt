package com.todocompanion.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Size
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.domain.habit.HabitStats
import com.todocompanion.app.ui.AppViewModel
import java.time.LocalDate

private val HABIT_COLORS = listOf(0xFF12A594, 0xFF3E7BFA, 0xFF8B5CF6, 0xFFE5484D, 0xFFF59E0B, 0xFFEC4899, 0xFF0EA371)
private val MILESTONES = setOf(7, 14, 30, 50, 100, 200, 365, 500, 1000)

private val HABIT_SECTIONS = listOf("Morning", "Afternoon", "Evening", "Anytime")
/** Which time-of-day section a habit belongs to, from its earliest reminder (0=Morning … 3=Anytime). */
private fun habitSectionOf(h: HabitEntity): Int {
    val first = h.reminderTimes.split(",").mapNotNull { it.trim().toIntOrNull() }.minOrNull() ?: return 3
    return when { first < 12 * 60 -> 0; first < 17 * 60 -> 1; else -> 2 }
}

/** Derived per-habit day sets used across the stats calls. */
private data class HabitDays(val done: Set<Long>, val skip: Set<Long>, val relapse: Set<Long>, val counts: Map<Long, Int>)
private fun daysFor(h: HabitEntity, checkins: List<com.todocompanion.app.data.entity.HabitCheckinEntity>): HabitDays {
    val hc = checkins.filter { it.habitId == h.id }
    return HabitDays(
        done = hc.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet(),
        skip = hc.filter { it.status == "skip" }.map { it.epochDay }.toSet(),
        relapse = hc.filter { HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet(),
        counts = hc.associate { it.epochDay to it.count },
    )
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
    HabitPreset("🚭", "No smoking", null, 0, 0xFF64748B),
    HabitPreset("🦷", "Floss", null, 1, 0xFF06B6D4),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HabitsScreen(vm: AppViewModel, modifier: Modifier = Modifier, onFocusHabit: (String) -> Unit = {}) {
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val today = LocalDate.now().toEpochDay()
    var editing by remember { mutableStateOf<HabitEntity?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    var presetOpen by remember { mutableStateOf(false) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var matrixMode by remember { mutableStateOf(false) }
    var batchOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var valueFor by remember { mutableStateOf<HabitEntity?>(null) }

    // Detail (analytics) screen takes over the whole tab when open.
    detailId?.let { id ->
        HabitDetailScreen(vm, id, onBack = { detailId = null }, onEdit = { editing = it; detailId = null })
        return
    }

    // Perfect-day: every habit that was scheduled today is now done. Celebrate on the rising edge.
    val stillDue = habits.count { h ->
        val d = daysFor(h, checkins)
        HabitStats.dueToday(h, today, d.done, d.counts[today] ?: 0)
    }
    // Only habits with a positive daily action count toward "perfect day": exclude paused and break
    // habits, so the celebration mirrors what's actually completable (and matches [stillDue]).
    val scheduledCount = habits.count {
        !it.paused && it.habitType != "break" &&
            (HabitStats.isExpectedDay(it, today) || it.freqType == HabitStats.FREQ_TIMES_WEEK || it.freqType == HabitStats.FREQ_TIMES_MONTH)
    }
    val perfectDay = scheduledCount > 0 && stillDue == 0
    var celebrated by remember { mutableStateOf(false) }
    var showConfetti by remember { mutableStateOf(false) }
    LaunchedEffect(perfectDay) {
        if (perfectDay && !celebrated) { celebrated = true; showConfetti = true }
        if (!perfectDay) celebrated = false
    }

    Box(modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        // Header: title + list/matrix toggle + overflow.
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Habits", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (habits.isNotEmpty()) {
                IconButton(onClick = { matrixMode = !matrixMode }) {
                    Icon(if (matrixMode) Icons.AutoMirrored.Filled.List else Icons.Filled.GridView, if (matrixMode) "List view" else "Matrix view")
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Batch check-in") }, onClick = { menuOpen = false; batchOpen = true })
                        val anyActive = habits.any { !it.paused }
                        DropdownMenuItem(text = { Text(if (anyActive) "Pause all habits" else "Resume all habits") }, onClick = { vm.pauseAllHabits(anyActive); menuOpen = false })
                        DropdownMenuItem(text = { Text("Starter habits") }, onClick = { menuOpen = false; presetOpen = true })
                    }
                }
            }
        }

        if (perfectDay) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text("🎉  Perfect day — every habit done!", Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
            }
        }

        if (habits.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
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
        } else if (matrixMode) {
            HabitMatrix(vm, onOpenHabit = { detailId = it.id }, modifier = Modifier.weight(1f))
        } else {
            val bySection = remember(habits) { habits.groupBy { habitSectionOf(it) } }
            val order = (0..3).filter { bySection.containsKey(it) }
            val showHeaders = order.size > 1
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 100.dp)) {
                order.forEach { sec ->
                    if (showHeaders) item(key = "sec$sec") {
                        Text(HABIT_SECTIONS[sec].uppercase(), Modifier.padding(start = 18.dp, top = 12.dp, bottom = 2.dp),
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(bySection[sec]!!, key = { it.id }) { h ->
                        HabitRow(
                            h, checkins, today,
                            onCycle = {
                                val cur = daysFor(h, checkins).counts[today] ?: 0
                                if (h.habitType == "break") {
                                    if (HabitStats.isRelapse(h, cur)) vm.clearHabitDay(h, today) else vm.setHabitValue(h, today, h.targetPerDay + 1)
                                } else vm.cycleHabit(h, today, cur)
                            },
                            onOpen = { detailId = h.id },
                            onSkip = { vm.skipHabitDay(h, today) },
                            onClear = { vm.clearHabitDay(h, today) },
                            onSetValue = { valueFor = h },
                            onPause = { vm.setHabitPaused(h, !h.paused) },
                            onEdit = { editing = h },
                            onFocus = { onFocusHabit(h.id) },
                        )
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
      }
      if (showConfetti) ConfettiOverlay(onDone = { showConfetti = false })
    }

    if (presetOpen) HabitPresetDialog(onDismiss = { presetOpen = false }, onPick = { p ->
        vm.createHabit(p.name, p.emoji, p.color, p.target, p.unit, "", ""); presetOpen = false
    })
    if (addOpen) HabitDialog(null, onDismiss = { addOpen = false }, onDelete = {},
        onSave = { h -> vm.addHabit(h); addOpen = false })
    editing?.let { h ->
        HabitDialog(h, onDismiss = { editing = null }, onDelete = { vm.deleteHabit(h.id); editing = null },
            onSave = { nh -> vm.saveHabit(nh.copy(id = h.id, createdAt = h.createdAt, sortOrder = h.sortOrder, workspaceId = h.workspaceId)); editing = null })
    }
    if (batchOpen) BatchCheckinDialog(vm, habits, checkins, today, onDismiss = { batchOpen = false })
    valueFor?.let { h ->
        NumericEntryDialog(h, daysFor(h, checkins).counts[today] ?: 0, onDismiss = { valueFor = null }) { v ->
            vm.setHabitValue(h, today, v); valueFor = null
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HabitRow(
    h: HabitEntity, checkins: List<com.todocompanion.app.data.entity.HabitCheckinEntity>, today: Long,
    onCycle: () -> Unit, onOpen: () -> Unit, onSkip: () -> Unit, onClear: () -> Unit,
    onSetValue: () -> Unit, onPause: () -> Unit, onEdit: () -> Unit, onFocus: () -> Unit,
) {
    val color = h.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val emptyCell = MaterialTheme.colorScheme.surfaceVariant
    val d = remember(checkins, h) { daysFor(h, checkins) }
    val todayCount = d.counts[today] ?: 0
    val isBreak = h.habitType == "break"
    val strength = HabitStats.strength(h, d.done, d.skip, d.relapse, today)
    val streak = HabitStats.currentStreak(h, d.done, d.skip, d.relapse, today)
    val done = if (isBreak) !HabitStats.isRelapse(h, todayCount) else HabitStats.meetsGoal(h, todayCount)
    val skippedToday = today in d.skip
    val scheduledToday = HabitStats.isExpectedDay(h, today) || h.freqType == HabitStats.FREQ_TIMES_WEEK || h.freqType == HabitStats.FREQ_TIMES_MONTH
    var rowMenu by remember { mutableStateOf(false) }

    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
    ) {
      Column(
          Modifier.fillMaxWidth()
              .combinedClickable(onClick = onOpen, onLongClick = { rowMenu = true })
              .padding(12.dp),
      ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Progress ring, tap to cycle / toggle. Break habits show a shield.
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(if (done && !isBreak) color.copy(alpha = .16f) else if (isBreak && HabitStats.isRelapse(h, todayCount)) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (scheduledToday) .5f else .25f))
                    .border(2.dp, when { isBreak && HabitStats.isRelapse(h, todayCount) -> MaterialTheme.colorScheme.error; done -> color; else -> MaterialTheme.colorScheme.outlineVariant }, CircleShape)
                    .clickable { onCycle() },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isBreak && HabitStats.isRelapse(h, todayCount) -> Text("✗", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    isBreak -> Text("🛡", style = MaterialTheme.typography.titleMedium)
                    skippedToday -> Text("–", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
                    done -> Icon(Icons.Filled.Check, null, tint = color, modifier = Modifier.size(22.dp))
                    !scheduledToday -> Text("–", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
                    h.targetPerDay > 1 -> Text("$todayCount/${h.targetPerDay}", style = MaterialTheme.typography.labelMedium, color = color)
                    h.emoji != null -> Text(h.emoji, style = MaterialTheme.typography.titleMedium)
                    else -> Box(Modifier.size(10.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((h.emoji?.plus(" ") ?: "") + h.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (h.paused) { Spacer(Modifier.width(6.dp)); Text("paused", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
                    if (streak in MILESTONES) { Spacer(Modifier.width(6.dp)); Text("🏅", style = MaterialTheme.typography.labelMedium) }
                }
                Text("${strength}% · ${HabitStats.frequencyLabel(h)}" + (h.unit?.let { " · ${h.targetPerDay} $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (streak > 0) Text((if (isBreak) "✨ " else "🔥 ") + streak, style = MaterialTheme.typography.labelLarge, color = color)
            Box {
                DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                    DropdownMenuItem(text = { Text("Open analytics") }, onClick = { rowMenu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Focus on this") }, onClick = { rowMenu = false; onFocus() })
                    if (h.unit != null || h.clickIncrement > 1 || isBreak) DropdownMenuItem(text = { Text("Set today's value…") }, onClick = { rowMenu = false; onSetValue() })
                    DropdownMenuItem(text = { Text(if (skippedToday) "Clear skip" else "Skip today") }, onClick = { rowMenu = false; if (skippedToday) onClear() else onSkip() })
                    DropdownMenuItem(text = { Text("Clear today") }, onClick = { rowMenu = false; onClear() })
                    DropdownMenuItem(text = { Text(if (h.paused) "Resume" else "Pause") }, onClick = { rowMenu = false; onPause() })
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { rowMenu = false; onEdit() })
                }
            }
        }
        // 30-day strip: connected pills for consecutive done days (streak-chaining), skip cells hollow.
        Spacer(Modifier.size(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            (29 downTo 0).forEach { back ->
                val day = today - back
                val c = d.counts[day] ?: 0
                val isDone = day in d.done
                val prevDone = (day - 1) in d.done
                val nextDone = (day + 1) in d.done && day < today
                val skipped = day in d.skip
                val scheduled = HabitStats.isExpectedDay(h, day)
                val cell = when {
                    day in d.relapse -> MaterialTheme.colorScheme.error
                    isDone -> color
                    skipped -> emptyCell.copy(alpha = .2f)
                    c > 0 -> color.copy(alpha = .4f)
                    !scheduled -> emptyCell.copy(alpha = .25f)
                    else -> emptyCell
                }
                // Chain consecutive done days by squaring the shared corners.
                val shape = if (isDone) RoundedCornerShape(
                    topStart = if (prevDone) 1.dp else 4.dp, bottomStart = if (prevDone) 1.dp else 4.dp,
                    topEnd = if (nextDone) 1.dp else 4.dp, bottomEnd = if (nextDone) 1.dp else 4.dp,
                ) else RoundedCornerShape(3.dp)
                Box(Modifier.weight(1f).height(14.dp).clip(shape).background(cell))
            }
        }
      }
    }
}

/** A short-lived confetti burst for the "perfect day" moment. */
@Composable
private fun ConfettiOverlay(onDone: () -> Unit) {
    val colors = HABIT_COLORS.map { Color(it) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(2200)); onDone() }
    val seeds = remember { (0 until 40).map { Triple(it, (it * 733 % 1000) / 1000f, ((it * 391) % 1000) / 1000f) } }
    Canvas(Modifier.fillMaxSize()) {
        val p = progress.value
        seeds.forEach { (i, x, delay) ->
            val t = ((p - delay * 0.3f) / (1f - delay * 0.3f)).coerceIn(0f, 1f)
            val cx = x * size.width + kotlin.math.sin((t * 6 + i) * 1.0f) * 18f
            val cy = t * (size.height + 40f) - 20f
            val alpha = (1f - t).coerceIn(0f, 1f)
            drawRect(colors[i % colors.size].copy(alpha = alpha), topLeft = Offset(cx, cy), size = Size(7f, 11f))
        }
    }
}

@Composable
private fun NumericEntryDialog(h: HabitEntity, current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(if (current > 0) current.toString() else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(text.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (h.habitType == "break") "Record today" else "Set today's value") },
        text = {
            Column {
                Text(if (h.habitType == "break") "How many ${h.unit ?: "times"} today? (0 = stayed clean)" else "Value for today" + (h.unit?.let { " ($it)" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(text, { text = it.filter { c -> c.isDigit() }.take(6) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            }
        },
    )
}

@Composable
private fun BatchCheckinDialog(vm: AppViewModel, habits: List<HabitEntity>, checkins: List<com.todocompanion.app.data.entity.HabitCheckinEntity>, today: Long, onDismiss: () -> Unit) {
    val due = habits.filter { h -> val d = daysFor(h, checkins); HabitStats.dueToday(h, today, d.done, d.counts[today] ?: 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Batch check-in") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (due.isEmpty()) Text("Nothing left due today — nice work. 🎉", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    Text("Everything still due today. Tap to complete.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    due.forEach { h ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            val cur = daysFor(h, checkins).counts[today] ?: 0
                            vm.setHabitValue(h, today, h.targetPerDay.coerceAtLeast(1))
                        }.padding(vertical = 10.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text((h.emoji?.plus("  ") ?: ""), style = MaterialTheme.typography.titleMedium)
                            Text(h.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Icon(Icons.Filled.Check, "Complete", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HabitDialog(existing: HabitEntity?, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (HabitEntity) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var emoji by remember { mutableStateOf(existing?.emoji ?: "") }
    var unit by remember { mutableStateOf(existing?.unit ?: "") }
    var color by remember { mutableStateOf(existing?.colorArgb ?: HABIT_COLORS.first()) }
    var target by remember { mutableStateOf(existing?.targetPerDay ?: 1) }
    var days by remember { mutableStateOf(HabitStats.parseSchedule(existing?.scheduleDays ?: "")) }
    var reminders by remember { mutableStateOf(existing?.reminderTimes.orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..1439 }.toSortedSet()) }
    var freqType by remember { mutableStateOf(existing?.freqType ?: HabitStats.FREQ_WEEKLY) }
    var freqParam by remember { mutableStateOf((existing?.freqParam ?: 3).coerceAtLeast(1)) }
    var habitType by remember { mutableStateOf(existing?.habitType ?: "build") }
    var increment by remember { mutableStateOf(existing?.clickIncrement ?: 1) }
    var extra by remember { mutableStateOf(existing?.extraTarget) }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var money by remember { mutableStateOf(existing?.moneyPerUnit?.toString() ?: "") }
    var more by remember { mutableStateOf(existing != null && (existing.habitType != "build" || existing.clickIncrement > 1 || existing.extraTarget != null || existing.description.isNotBlank())) }
    val ctx = LocalContext.current
    val isBreak = habitType == "break"

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    val base = existing ?: HabitEntity(id = "", name = "", createdAt = 0L)
                    onSave(base.copy(
                        name = name.trim(), emoji = emoji.trim().ifBlank { null }, colorArgb = color,
                        targetPerDay = if (isBreak) target.coerceAtLeast(0) else target.coerceAtLeast(1),
                        unit = unit.trim().ifBlank { null },
                        scheduleDays = if (freqType == HabitStats.FREQ_WEEKLY) days.sorted().joinToString(",") else "",
                        reminderTimes = reminders.sorted().joinToString(","),
                        habitType = habitType, targetComparison = if (isBreak) "atmost" else "atleast",
                        freqType = freqType, freqParam = freqParam,
                        clickIncrement = increment.coerceAtLeast(1), extraTarget = extra?.takeIf { it > target },
                        description = description.trim(), moneyPerUnit = money.trim().toDoubleOrNull(),
                    ))
                }
            }) { Text("Save") }
        },
        dismissButton = { if (existing != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } else TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (existing == null) "New habit" else "Habit") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(emoji, { emoji = it.take(2) }, singleLine = true, label = { Text("Emoji") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(unit, { unit = it.take(12) }, singleLine = true, label = { Text("Unit") }, modifier = Modifier.weight(1.4f))
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
                    Text((if (isBreak) "Daily limit" else "Target per day") + (unit.trim().ifBlank { null }?.let { " ($it)" } ?: ""), Modifier.weight(1f))
                    TextButton(onClick = { target = (target - 1).coerceAtLeast(if (isBreak) 0 else 1) }) { Text("−") }
                    Text(target.toString(), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { target = (target + 1).coerceAtMost(100000) }) { Text("+") }
                }

                // Frequency (progressive core).
                Spacer(Modifier.size(10.dp))
                Text("Repeat", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        HabitStats.FREQ_WEEKLY to "Weekly", HabitStats.FREQ_TIMES_WEEK to "× / week",
                        HabitStats.FREQ_TIMES_MONTH to "× / month", HabitStats.FREQ_INTERVAL to "Every N days",
                    ).forEach { (ft, label) ->
                        FilterChip(selected = freqType == ft, onClick = { freqType = ft }, label = { Text(label) })
                    }
                }
                when (freqType) {
                    HabitStats.FREQ_WEEKLY -> {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (1..7).forEach { dnum ->
                                val on = dnum in days
                                Box(Modifier.weight(1f).clip(CircleShape).background(if (on) Color(color) else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { days = if (on) days - dnum else days + dnum }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(java.time.DayOfWeek.of(dnum).getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                                        style = MaterialTheme.typography.labelMedium, color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Text(if (days.isEmpty()) "No days selected = every day" else "On selected days only",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
                    }
                    else -> {
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(when (freqType) { HabitStats.FREQ_TIMES_WEEK -> "Times per week"; HabitStats.FREQ_TIMES_MONTH -> "Times per month"; else -> "Every N days" }, Modifier.weight(1f))
                            TextButton(onClick = { freqParam = (freqParam - 1).coerceAtLeast(1) }) { Text("−") }
                            Text(freqParam.toString(), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { freqParam = (freqParam + 1).coerceAtMost(60) }) { Text("+") }
                        }
                    }
                }

                // Reminders (progressive core).
                Spacer(Modifier.size(10.dp))
                Text("Reminders", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                reminders.sorted().forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🔔  " + "%02d:%02d".format(m / 60, m % 60), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { reminders = reminders.toSortedSet().also { it.remove(m) } }) { Text("Remove") }
                    }
                }
                TextButton(onClick = {
                    val n = java.time.LocalTime.now()
                    android.app.TimePickerDialog(ctx, { _, hr, min -> reminders = reminders.toSortedSet().also { it.add(hr * 60 + min) } },
                        n.hour, n.minute, android.text.format.DateFormat.is24HourFormat(ctx)).show()
                }) { Text("＋ Add reminder time") }

                // Expert options (progressive reveal).
                Spacer(Modifier.size(6.dp))
                TextButton(onClick = { more = !more }, contentPadding = PaddingValues(0.dp)) { Text(if (more) "Fewer options ▴" else "More options ▾") }
                if (more) {
                    Text("Type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        FilterChip(selected = habitType == "build", onClick = { habitType = "build" }, label = { Text("Build") })
                        FilterChip(selected = habitType == "break", onClick = { habitType = "break" }, label = { Text("Quit (bad habit)") })
                    }
                    if (isBreak) {
                        Text("Success = staying at or under the daily limit. Streak = days since your last slip.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        OutlinedTextField(money, { money = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, singleLine = true,
                            label = { Text("Money saved per ${unit.ifBlank { "unit" }} (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text("Each tap adds", Modifier.weight(1f))
                            TextButton(onClick = { increment = (increment - 1).coerceAtLeast(1) }) { Text("−") }
                            Text(increment.toString(), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { increment = (increment + 1).coerceAtMost(1000) }) { Text("+") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Stretch goal", Modifier.weight(1f))
                            TextButton(onClick = { extra = ((extra ?: target) - 1).takeIf { it > target } }) { Text("−") }
                            Text(extra?.toString() ?: "—", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { extra = (extra ?: target) + 1 }) { Text("+") }
                        }
                    }
                    OutlinedTextField(description, { description = it }, label = { Text("Notes / reason") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
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
