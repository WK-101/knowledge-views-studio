package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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

// M2: themed starter routines — one tap creates a whole coherent set (each tagged with the routine's
// category so they land in their own section). A gentle first-run on-ramp for the whole app.
private class RoutineHabit(val emoji: String, val name: String, val unit: String?, val target: Int, val color: Long, val reminderMin: Int? = null, val type: String = "build")
private class HabitRoutine(val emoji: String, val name: String, val blurb: String, val habits: List<RoutineHabit>)
private fun RoutineHabit.toEntity(category: String) = HabitEntity(
    id = "", name = name, emoji = emoji, colorArgb = color, targetPerDay = target, unit = unit,
    reminderTimes = reminderMin?.toString() ?: "", createdAt = 0L,
    habitType = type, targetComparison = if (type == "break") "atmost" else "atleast", category = category,
)
private val HABIT_ROUTINES = listOf(
    HabitRoutine("🌅", "Morning routine", "Start the day with intention", listOf(
        RoutineHabit("💧", "Drink water", "glasses", 2, 0xFF3E7BFA, 7 * 60),
        RoutineHabit("🧘", "Meditate", "min", 10, 0xFF12A594, 7 * 60 + 15),
        RoutineHabit("✍️", "Journal", null, 1, 0xFFEC4899, 7 * 60 + 30),
        RoutineHabit("🛏️", "Make the bed", null, 1, 0xFF8B5CF6, 7 * 60),
    )),
    HabitRoutine("💪", "Fitness", "Move every day", listOf(
        RoutineHabit("🏃", "Exercise", null, 1, 0xFFE5484D, 18 * 60),
        RoutineHabit("🚶", "Walk", "steps", 8000, 0xFFF59E0B),
        RoutineHabit("💧", "Drink water", "glasses", 8, 0xFF3E7BFA),
        RoutineHabit("🥗", "Eat healthy", null, 1, 0xFF0EA371),
    )),
    HabitRoutine("🎯", "Deep focus", "Protect your attention", listOf(
        RoutineHabit("📵", "No phone first hour", null, 1, 0xFF64748B, 8 * 60),
        RoutineHabit("🍅", "One focus session", null, 1, 0xFF6650A4, 9 * 60),
        RoutineHabit("📖", "Read", "pages", 20, 0xFF8B5CF6),
    )),
    HabitRoutine("🌙", "Better sleep", "Wind down well", listOf(
        RoutineHabit("😴", "Sleep by 11", null, 1, 0xFF6366F1, 22 * 60 + 30),
        RoutineHabit("📵", "No phone in bed", null, 1, 0xFF64748B, 22 * 60),
        RoutineHabit("📴", "No screens after 10", null, 1, 0xFF475569, 22 * 60),
    )),
    HabitRoutine("🧠", "Mindfulness", "A calmer mind", listOf(
        RoutineHabit("🧘", "Meditate", "min", 10, 0xFF12A594, 8 * 60),
        RoutineHabit("🙏", "Gratitude — 3 things", null, 3, 0xFFEC4899, 21 * 60),
        RoutineHabit("🌬️", "Breathing break", null, 1, 0xFF06B6D4),
    )),
    HabitRoutine("🚭", "Quit bad habits", "Break what holds you back", listOf(
        RoutineHabit("🚭", "No smoking", null, 0, 0xFF64748B, type = "break"),
        RoutineHabit("🍭", "Limit sugar", null, 1, 0xFFF59E0B, type = "break"),
        RoutineHabit("📱", "Limit social media", "min", 30, 0xFF64748B, type = "break"),
    )),
)
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
    val appSettings by vm.settings.collectAsState()
    val today = LocalDate.now().toEpochDay()
    // View-state now lives in the ViewModel so the app's single top bar drives it (see HabitsHeader);
    // the detail screen and the editor are full-screen overlays rendered by AppRoot.
    val matrixMode by vm.habitMatrixMode.collectAsState()
    val density by vm.habitDensity.collectAsState()
    val batchOpen by vm.habitBatchOpen.collectAsState()
    val presetOpen by vm.habitPresetOpen.collectAsState()
    val quickAddOpen by vm.habitQuickAddOpen.collectAsState()
    val tasks by vm.tasks.collectAsState()
    var valueFor by remember { mutableStateOf<HabitEntity?>(null) }
    // K1: on-device insights over the shared habit/task store.
    val insights = remember(habits, checkins, tasks, today) {
        com.todocompanion.app.domain.habit.HabitInsights.compute(habits, checkins, tasks, today)
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
    // N2: a reward-unlock celebration when a habit reaches its self-chosen reward streak.
    val reward by vm.rewardCelebration.collectAsState()
    val rewardCtx = LocalContext.current
    LaunchedEffect(reward) {
        reward?.let { r ->
            showConfetti = true
            android.widget.Toast.makeText(rewardCtx, "🎁 Reward unlocked: $r", android.widget.Toast.LENGTH_LONG).show()
            vm.rewardCelebration.value = null
        }
    }

    Box(modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
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
                    TextButton(onClick = { vm.habitPresetOpen.value = true }) { Text("✨ Starter habits") }
                    TextButton(onClick = { vm.habitEditor.value = com.todocompanion.app.ui.HabitEditRequest() }) { Text("＋ New habit") }
                }
            }
        } else if (matrixMode) {
            HabitMatrix(vm, density, onOpenHabit = { vm.habitDetailId.value = it.id }, modifier = Modifier.weight(1f))
        } else {
            // M6: when the user has given habits a category, section by that (their explicit grouping);
            // otherwise fall back to the time-of-day sections derived from reminder times.
            val sections = remember(habits) {
                val useCategory = habits.any { it.category.isNotBlank() }
                if (useCategory) habits.groupBy { it.category.trim().ifBlank { "Other" } }
                    .entries.sortedWith(compareBy({ it.key == "Other" }, { it.key.lowercase() })).map { it.key to it.value }
                else (0..3).mapNotNull { sec -> habits.filter { habitSectionOf(it) == sec }.takeIf { it.isNotEmpty() }?.let { HABIT_SECTIONS[sec] to it } }
            }
            // Show the group header whenever the user set explicit groups — even a single named group
            // like "Morning habits" gets its title (F2), not just when there are 2+ sections.
            val showHeaders = sections.size > 1 || habits.any { it.category.isNotBlank() }
            // Drag-to-reorder persists a global habit order; a drag rearranges within its section, then the
            // whole order is saved (so categories/stacks stay grouped — "Morning habits" together, etc.).
            fun persistSection(sectionHabits: List<HabitEntity>, newIds: List<String>) {
                val global = sections.flatMap { (_, hs) -> if (hs === sectionHabits) newIds else hs.map { it.id } }
                vm.setHabitOrder(global)
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 100.dp)) {
                if (insights.isNotEmpty()) item(key = "insights") { InsightsCard(insights, vm) }
                sections.forEach { (title, secHabits) ->
                    if (showHeaders) item(key = "sec-$title") {
                        Text(title.uppercase(), Modifier.padding(start = 18.dp, top = 12.dp, bottom = 2.dp),
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item(key = "seccol-$title") {
                        HabitDraggableColumn(secHabits, onReorder = { persistSection(secHabits, it) }) { h ->
                            HabitRow(
                                h, checkins, today, allHabits = habits, forgiving = appSettings.forgivingStreaks, graded = appSettings.gradedStrength,
                                onCycle = {
                                    val cur = daysFor(h, checkins).counts[today] ?: 0
                                    if (h.habitType == "break") {
                                        if (HabitStats.isRelapse(h, cur)) vm.clearHabitDay(h, today) else vm.setHabitValue(h, today, h.targetPerDay + 1)
                                    } else vm.cycleHabit(h, today, cur)
                                },
                                onOpen = { vm.habitDetailId.value = h.id },
                                onSkip = { vm.skipHabitDay(h, today) },
                                onClear = { vm.clearHabitDay(h, today) },
                                onSetValue = { valueFor = h },
                                onPause = { vm.setHabitPaused(h, !h.paused) },
                                onEdit = { vm.habitEditor.value = com.todocompanion.app.ui.HabitEditRequest(h) },
                                onFocus = { onFocusHabit(h.id) },
                                onAddValue = { delta ->
                                    val cur = daysFor(h, checkins).counts[today] ?: 0
                                    vm.setHabitValue(h, today, (cur + delta).coerceAtLeast(0))
                                },
                            )
                        }
                    }
                }
                // New-habit and starter actions live in the top app-bar (＋ and ✨), so no redundant
                // buttons under the list.
            }
        }
      }
      if (showConfetti) ConfettiOverlay(onDone = { showConfetti = false })
    }

    if (quickAddOpen) HabitQuickAddDialog(
        onDismiss = { vm.habitQuickAddOpen.value = false },
        onAdd = { draft -> vm.addHabit(draft); vm.habitQuickAddOpen.value = false },
        onAdvanced = { draft -> vm.habitQuickAddOpen.value = false; vm.habitEditor.value = com.todocompanion.app.ui.HabitEditRequest(draft.takeIf { it.name.isNotBlank() }) },
    )
    if (presetOpen) HabitPresetDialog(
        onDismiss = { vm.habitPresetOpen.value = false },
        onPick = { p -> vm.createHabit(p.name, p.emoji, p.color, p.target, p.unit, "", ""); vm.habitPresetOpen.value = false },
        onAddRoutine = { r -> vm.addHabits(r.habits.map { it.toEntity(r.name) }) },
    )
    if (batchOpen) BatchCheckinDialog(vm, habits, checkins, today, onDismiss = { vm.habitBatchOpen.value = false })
    valueFor?.let { h ->
        NumericEntryDialog(h, daysFor(h, checkins).counts[today] ?: 0, onDismiss = { valueFor = null }) { v ->
            vm.setHabitValue(h, today, v); valueFor = null
        }
    }
}

/**
 * The Habits tab's app-bar — rendered in the shared top-bar slot so the tab shows ONE header like
 * every other view. Carries the list/matrix toggle, the matrix density menu, a new-habit action, and
 * the overflow (batch check-in, pause all, starters). All state lives in the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitsHeader(vm: AppViewModel, onOpenDrawer: () -> Unit) {
    val matrixMode by vm.habitMatrixMode.collectAsState()
    val density by vm.habitDensity.collectAsState()
    val habits by vm.habits.collectAsState()
    TopAppBar(
        windowInsets = TopAppBarDefaults.windowInsets,
        expandedHeight = 52.dp,
        title = { Text("Habits", maxLines = 1) },
        navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, "Menu") } },
        actions = {
            if (habits.isNotEmpty()) {
                IconButton(onClick = { vm.habitMatrixMode.value = !matrixMode }) {
                    Icon(if (matrixMode) Icons.AutoMirrored.Filled.List else Icons.Filled.GridView,
                        if (matrixMode) "List view" else "Matrix view",
                        tint = if (matrixMode) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current)
                }
                if (matrixMode) {
                    var dMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { dMenu = true }) { Icon(Icons.Filled.Tune, "Density") }
                        DropdownMenu(expanded = dMenu, onDismissRequest = { dMenu = false }) {
                            listOf("Compact", "Medium", "Large").forEachIndexed { i, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    leadingIcon = { if (density == i) Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) else Spacer(Modifier.width(18.dp)) },
                                    onClick = { vm.habitDensity.value = i; dMenu = false },
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = { vm.habitPresetOpen.value = true }) { Icon(Icons.Filled.AutoAwesome, "Starter habits") }
                IconButton(onClick = { vm.habitQuickAddOpen.value = true }) { Icon(Icons.Filled.Add, "New habit") }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Trends & correlations") }, onClick = { menu = false; vm.habitTrendsOpen.value = true })
                        DropdownMenuItem(text = { Text("Batch check-in") }, onClick = { menu = false; vm.habitBatchOpen.value = true })
                        val anyActive = habits.any { !it.paused }
                        DropdownMenuItem(text = { Text(if (anyActive) "Pause all habits" else "Resume all habits") }, onClick = { vm.pauseAllHabits(anyActive); menu = false })
                    }
                }
            } else {
                IconButton(onClick = { vm.habitPresetOpen.value = true }) { Icon(Icons.Filled.AutoAwesome, "Starter habits") }
                IconButton(onClick = { vm.habitQuickAddOpen.value = true }) { Icon(Icons.Filled.Add, "New habit") }
            }
        },
    )
}

/** K1/L1: the on-device coach card — plain-language patterns from the shared habit/task store, each
 *  with a one-tap action (open the habit, or stack two habits). */
@Composable
private fun InsightsCard(insights: List<com.todocompanion.app.domain.habit.Insight>, vm: AppViewModel) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .55f),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✨", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(6.dp))
                Text("Insights", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            insights.forEach { ins ->
                val action = ins.action
                val openId = (action as? com.todocompanion.app.domain.habit.InsightAction.Open)?.habitId
                    ?: (action as? com.todocompanion.app.domain.habit.InsightAction.Stack)?.childId
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp)
                        .then(if (openId != null) Modifier.clickable { vm.habitDetailId.value = openId } else Modifier),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(ins.emoji, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ins.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        if (action is com.todocompanion.app.domain.habit.InsightAction.Stack) {
                            TextButton(onClick = {
                                vm.habits.value.firstOrNull { it.id == action.childId }?.let { vm.saveHabit(it.copy(anchorHabitId = action.anchorId)) }
                            }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                                Text("Stack ‘${action.childName}’ after ‘${action.anchorName}’")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A column of habits reorderable by *holding* a habit and dragging it (persists a manual order).
 * G1: no dedicated drag handle — the whole habit is the grab target via long-press, so the row keeps
 * its full width for content. A short lift while dragging signals the grab.
 */
@Composable
private fun HabitDraggableColumn(habits: List<HabitEntity>, onReorder: (List<String>) -> Unit, row: @Composable (HabitEntity) -> Unit) {
    var order by remember(habits) { mutableStateOf(habits) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableStateOf(0f) }
    val rowPx = with(LocalDensity.current) { 68.dp.toPx() }
    Column {
        order.forEach { h ->
            val dragging = h.id == draggingId
            Box(
                Modifier
                    .fillMaxWidth()
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragging) dragY else 0f
                        if (dragging) { scaleX = 1.02f; scaleY = 1.02f }
                    }
                    .pointerInput(h.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { draggingId = h.id; dragY = 0f },
                            onDrag = { ch, off ->
                                ch.consume(); dragY += off.y
                                val cur = order.indexOfFirst { it.id == h.id }
                                if (cur >= 0) {
                                    val target = (cur + (dragY / rowPx).roundToInt()).coerceIn(0, order.lastIndex)
                                    if (target != cur) { order = order.toMutableList().also { it.add(target, it.removeAt(cur)) }; dragY -= (target - cur) * rowPx }
                                }
                            },
                            onDragEnd = { draggingId = null; dragY = 0f; onReorder(order.map { it.id }) },
                            onDragCancel = { draggingId = null; dragY = 0f },
                        )
                    },
            ) { row(h) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HabitRow(
    h: HabitEntity, checkins: List<com.todocompanion.app.data.entity.HabitCheckinEntity>, today: Long,
    allHabits: List<HabitEntity> = emptyList(), forgiving: Boolean = false, graded: Boolean = false,
    onCycle: () -> Unit, onOpen: () -> Unit, onSkip: () -> Unit, onClear: () -> Unit,
    onSetValue: () -> Unit, onPause: () -> Unit, onEdit: () -> Unit, onFocus: () -> Unit,
    onAddValue: (Int) -> Unit = {},
) {
    val color = h.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val emptyCell = MaterialTheme.colorScheme.surfaceVariant
    val d = remember(checkins, h) { daysFor(h, checkins) }
    val todayCount = d.counts[today] ?: 0
    val isBreak = h.habitType == "break"
    // Z8 correction: honour the graded-strength opt-in here too, so the list badge matches the detail.
    val gradedCredit = if (graded && !isBreak) remember(checkins, h) {
        val target = h.targetPerDay.coerceAtLeast(1)
        checkins.filter { it.habitId == h.id && it.status == "done" && !HabitStats.meetsGoal(h, it.count) && it.count > 0 }
            .associate { it.epochDay to (it.count.toDouble() / target).coerceIn(0.0, 0.99) }
    } else emptyMap()
    val strength = HabitStats.strength(h, d.done, d.skip, d.relapse, today, gradedCredit = gradedCredit)
    val streak = HabitStats.displayStreak(h, d.done, d.skip, d.relapse, today, forgiving)
    val done = if (isBreak) !HabitStats.isRelapse(h, todayCount) else HabitStats.meetsGoal(h, todayCount)
    val skippedToday = today in d.skip
    val scheduledToday = HabitStats.isExpectedDay(h, today) || h.freqType == HabitStats.FREQ_TIMES_WEEK || h.freqType == HabitStats.FREQ_TIMES_MONTH
    var rowMenu by remember { mutableStateOf(false) }
    // K4: habit-stacking anchor — surface "after <anchor>" and highlight once the anchor is done today.
    val anchor = h.anchorHabitId?.let { aid -> allHabits.firstOrNull { it.id == aid } }
    val anchorDoneToday = anchor?.let { a -> val ad = daysFor(a, checkins); HabitStats.meetsGoal(a, ad.counts[today] ?: 0) } ?: false

    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
    ) {
      Column(
          // G1: tap opens analytics; long-press is reserved for hold-and-drag reorder (parent column),
          // so the row menu now lives on an explicit ⋮ button below.
          Modifier.fillMaxWidth()
              .clickable(onClick = onOpen)
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
                if (anchor != null && !done) {
                    Text("▸ after ${anchor.name}" + if (anchorDoneToday) " · now's the time" else "",
                        style = MaterialTheme.typography.labelSmall, fontWeight = if (anchorDoneToday) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (anchorDoneToday) color else MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // E2: friction-free numeric entry — an inline −/+ stepper right on the row, no digging into
            // a menu. + adds the habit's per-tap step (so 10 000 steps takes a few taps, not 10 000);
            // tap the number to type an exact value. Falls back to the streak flame for yes/no habits.
            val isNumeric = !isBreak && (h.targetPerDay > 1 || h.unit != null || h.clickIncrement > 1)
            if (isNumeric && scheduledToday && !skippedToday) {
                val step = h.clickIncrement.coerceAtLeast(1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepBtn("−", enabled = todayCount > 0) { onAddValue(-step) }
                    Text(
                        if (h.targetPerDay > 1) "$todayCount/${h.targetPerDay}" else "$todayCount",
                        Modifier.clip(RoundedCornerShape(6.dp)).clickable { onSetValue() }.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        color = if (done) color else MaterialTheme.colorScheme.onSurface,
                    )
                    StepBtn("+", enabled = true) { onAddValue(step) }
                }
            } else if (streak > 0) Text((if (isBreak) "✨ " else "🔥 ") + streak, style = MaterialTheme.typography.labelLarge, color = color)
            Box {
                // R4: full 48dp touch target for accessibility; the icon stays visually compact.
                IconButton(onClick = { rowMenu = true }) {
                    Icon(Icons.Filled.MoreVert, "More options for ${h.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                    DropdownMenuItem(text = { Text("Open analytics") }, onClick = { rowMenu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Focus on this") }, onClick = { rowMenu = false; onFocus() })
                    if (h.targetPerDay > 1 || h.unit != null || h.clickIncrement > 1 || isBreak) DropdownMenuItem(text = { Text("Set today's value…") }, onClick = { rowMenu = false; onSetValue() })
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

/** E2: a compact round −/+ button for inline numeric habit entry. */
@Composable
private fun StepBtn(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(CircleShape)
            .background(if (enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .3f))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
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
                com.todocompanion.app.ui.components.AppTextField(text, { text = it.filter { c -> c.isDigit() }.take(7) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HabitEditorScreen(vm: AppViewModel, existing: HabitEntity?, onClose: () -> Unit) {
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
    // Tier K editor fields.
    var identity by remember { mutableStateOf(existing?.identity ?: "") }
    var anchorId by remember { mutableStateOf(existing?.anchorHabitId) }
    var rewardText by remember { mutableStateOf(existing?.rewardText ?: "") }
    var rewardAt by remember { mutableStateOf(existing?.rewardAtStreak ?: 0) }
    // Tier V4: user-written encouragements (one per line). V3: how linked tracked time credits the habit.
    var encouragements by remember { mutableStateOf(existing?.encouragements ?: "") }
    var linkMode by remember { mutableStateOf(existing?.linkMode ?: "minutes") }
    // M6: model tidy-ups — a grouping category and a user-editable start date (defaults to creation).
    var category by remember { mutableStateOf(existing?.category ?: "") }
    var startDate by remember { mutableStateOf(existing?.startDate) }
    var showStartPicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // T3: which time-tracking activity this habit is linked to (tracking it credits the habit).
    var timeActivityId by remember { mutableStateOf(existing?.timeActivityId) }
    // E6: keep the default editor as light as quick-add — advanced sections fold away, auto-opening
    // only when editing a habit that already uses one of them.
    var advancedOpen by remember { mutableStateOf(
        existing != null && (existing.identity.isNotBlank() || existing.anchorHabitId != null ||
            existing.rewardText.isNotBlank() || existing.category.isNotBlank() || existing.habitType == "break" ||
            existing.moneyPerUnit != null || existing.startDate != null || existing.clickIncrement > 1 || existing.extraTarget != null)
    ) }
    val ctx = LocalContext.current
    val isBreak = habitType == "break"
    val allHabits by vm.habits.collectAsState()
    val timeActivities by vm.timeActivities.collectAsState()
    val editorSettings by vm.settings.collectAsState()
    val timeOn = com.todocompanion.app.domain.Modules.isEnabled(editorSettings, com.todocompanion.app.domain.Modules.TIME)

    fun buildHabit(): HabitEntity {
        val base = existing ?: HabitEntity(id = "", name = "", createdAt = 0L)
        return base.copy(
            name = name.trim(), emoji = emoji.trim().ifBlank { null }, colorArgb = color,
            targetPerDay = if (isBreak) target.coerceAtLeast(0) else target.coerceAtLeast(1),
            unit = unit.trim().ifBlank { null },
            scheduleDays = if (freqType == HabitStats.FREQ_WEEKLY) days.sorted().joinToString(",") else "",
            reminderTimes = reminders.sorted().joinToString(","),
            habitType = habitType, targetComparison = if (isBreak) "atmost" else "atleast",
            freqType = freqType, freqParam = freqParam,
            clickIncrement = increment.coerceAtLeast(1), extraTarget = extra?.takeIf { it > target },
            description = description.trim(), moneyPerUnit = money.trim().toDoubleOrNull(),
            identity = identity.trim(), anchorHabitId = anchorId,
            rewardText = rewardText.trim(), rewardAtStreak = rewardAt,
            category = category.trim(), startDate = startDate,
            encouragements = encouragements.trim(), linkMode = linkMode,
            timeActivityId = timeActivityId,
        )
    }
    fun save() {
        if (name.isBlank()) return
        val h = buildHabit()
        if (existing == null) vm.addHabit(h)
        else vm.saveHabit(h.copy(id = existing.id, createdAt = existing.createdAt, sortOrder = existing.sortOrder, workspaceId = existing.workspaceId))
        onClose()
    }
    BackHandler { onClose() }

    Scaffold(topBar = {
        TopAppBar(
            windowInsets = TopAppBarDefaults.windowInsets,
            expandedHeight = 52.dp,
            title = { Text(if (existing == null) "New habit" else "Edit habit", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (existing != null) IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { save() }, enabled = name.isNotBlank()) { Text("Save", fontWeight = FontWeight.SemiBold) }
            },
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Identity
            EditorCard {
                com.todocompanion.app.ui.components.AppTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.todocompanion.app.ui.components.AppTextField(emoji, { emoji = it.take(2) }, singleLine = true, label = { Text("Emoji") }, modifier = Modifier.weight(1f))
                    com.todocompanion.app.ui.components.AppTextField(unit, { unit = it.take(12) }, singleLine = true, label = { Text("Unit") }, modifier = Modifier.weight(1.4f))
                }
                Spacer(Modifier.size(10.dp))
                // F2: group habits into named sections (e.g. "Morning", "Fitness"). Front-and-centre now,
                // not buried in Advanced, with quick suggestions so a stack is one tap to create.
                com.todocompanion.app.ui.components.AppTextField(category, { category = it.take(30) }, singleLine = true,
                    label = { Text("Group (e.g. Morning, Fitness) — optional") }, modifier = Modifier.fillMaxWidth())
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    val existingGroups = allHabits.mapNotNull { it.category.trim().ifBlank { null } }.distinct().take(6)
                    (existingGroups + listOf("Morning", "Afternoon", "Evening").filter { it !in existingGroups }).take(6).forEach { g ->
                        FilterChip(selected = category.trim().equals(g, true), onClick = { category = if (category.trim().equals(g, true)) "" else g }, label = { Text(g) })
                    }
                }
                Spacer(Modifier.size(12.dp))
                // Compact colour picker: one swatch shown; tap it to reveal the palette (space-optimised).
                var colorsOpen by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Colour", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color(color)).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).clickable { colorsOpen = !colorsOpen })
                }
                if (colorsOpen) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    HABIT_COLORS.forEach { c ->
                        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c)).border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape).clickable { color = c; colorsOpen = false })
                    }
                }
            }

            // 2. Target — typed, so a 10000-step goal doesn't take 10000 taps to set. +/- kept as nudges.
            EditorCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.todocompanion.app.ui.components.AppTextField(
                        value = target.toString(),
                        onValueChange = { v -> target = (v.filter { it.isDigit() }.take(7).toIntOrNull() ?: 0).coerceIn(if (isBreak) 0 else 1, 9_999_999) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        label = { Text((if (isBreak) "Daily limit" else "Target per day") + (unit.trim().ifBlank { null }?.let { " ($it)" } ?: "")) },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { target = (target - 1).coerceAtLeast(if (isBreak) 0 else 1) }) { Icon(Icons.Filled.Remove, "Less") }
                    IconButton(onClick = { target = (target + 1).coerceAtMost(9_999_999) }) { Icon(Icons.Filled.Add, "More") }
                }
            }

            // 3. Repeat
            EditorCard {
                Text("Repeat", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    listOf(
                        HabitStats.FREQ_WEEKLY to "Weekly", HabitStats.FREQ_TIMES_WEEK to "× / week",
                        HabitStats.FREQ_TIMES_MONTH to "× / month", HabitStats.FREQ_INTERVAL to "Every N days",
                    ).forEach { (ft, label) -> FilterChip(selected = freqType == ft, onClick = { freqType = ft }, label = { Text(label) }) }
                }
                when (freqType) {
                    HabitStats.FREQ_WEEKLY -> {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp))
                    }
                    else -> StepperRow(
                        when (freqType) { HabitStats.FREQ_TIMES_WEEK -> "Times per week"; HabitStats.FREQ_TIMES_MONTH -> "Times per month"; else -> "Every N days" },
                        freqParam.toString(), onMinus = { freqParam = (freqParam - 1).coerceAtLeast(1) }, onPlus = { freqParam = (freqParam + 1).coerceAtMost(60) },
                        modifier = Modifier.padding(top = 8.dp))
                }
            }

            // 4. Reminders
            EditorCard {
                Text("Reminders", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                reminders.sorted().forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🔔  " + "%02d:%02d".format(m / 60, m % 60), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { reminders = reminders.toSortedSet().also { it.remove(m) } }) { Text("Remove") }
                    }
                }
                TextButton(onClick = {
                    val n = java.time.LocalTime.now()
                    android.app.TimePickerDialog(ctx, { _, hr, min -> reminders = reminders.toSortedSet().also { it.add(hr * 60 + min) } },
                        n.hour, n.minute, android.text.format.DateFormat.is24HourFormat(ctx)).show()
                }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) { Text("＋ Add reminder time") }
            }

            // E6: everything below folds behind one tap so a new habit stays as simple as quick-add.
            Surface(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { advancedOpen = !advancedOpen },
                shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Advanced — type, identity, stacking, reward, organise", Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(if (advancedOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (advancedOpen) {
            // 5. Type & advanced
            EditorCard {
                Text("Type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    FilterChip(selected = habitType == "build", onClick = { habitType = "build" }, label = { Text("Build") })
                    FilterChip(selected = habitType == "break", onClick = { habitType = "break" }, label = { Text("Quit (bad habit)") })
                }
                if (isBreak) {
                    Text("Success = staying at or under the daily limit. Streak = days since your last slip.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp))
                    com.todocompanion.app.ui.components.AppTextField(money, { money = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, singleLine = true,
                        label = { Text("Money saved per ${unit.ifBlank { "unit" }} (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                // "Each tap adds" as a typed field, not a +/- stepper — so +250 steps / +1000 doesn't need
                // 250 taps to configure. Available for build AND break habits (a slip counter can step too).
                com.todocompanion.app.ui.components.AppTextField(
                    value = if (increment <= 0) "" else increment.toString(),
                    onValueChange = { v -> increment = (v.filter { it.isDigit() }.take(6).toIntOrNull() ?: 1).coerceAtLeast(1) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    label = { Text("Each tap adds" + (unit.ifBlank { "" }.let { if (it.isBlank()) "" else " ($it)" })) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (!isBreak) {
                    StepperRow("Stretch goal", extra?.toString() ?: "—", onMinus = { extra = ((extra ?: target) - 1).takeIf { it > target } }, onPlus = { extra = (extra ?: target) + 1 })
                }
                com.todocompanion.app.ui.components.AppTextField(description, { description = it }, label = { Text("Why — your motivation (shown when you're about to slip)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                // V4: user-written encouragements, one shown at random on each check-off.
                com.todocompanion.app.ui.components.AppTextField(encouragements, { encouragements = it }, label = { Text("Encouragements (one per line, shown on check-off)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                // T3/V3: link this habit to a time-tracking activity — tracking that activity then credits
                // the habit. (Fixes: no way to link an activity to a habit from the habit side.)
                if (timeOn) {
                    Text("Track with activity", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                    Text("Tracking this activity's time counts toward the habit.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    var actMenu by remember { mutableStateOf(false) }
                    val linkedName = timeActivities.firstOrNull { it.id == timeActivityId }?.let { (it.emoji?.plus(" ") ?: "") + it.name }
                    Box(Modifier.padding(top = 6.dp)) {
                        Surface(Modifier.fillMaxWidth().clickable { actMenu = true }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(linkedName ?: "Not linked", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                    color = if (linkedName != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (timeActivityId != null) TextButton(onClick = { timeActivityId = null }) { Text("Clear") }
                                Icon(Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        DropdownMenu(expanded = actMenu, onDismissRequest = { actMenu = false }) {
                            if (timeActivities.none { !it.archived }) DropdownMenuItem(text = { Text("No activities yet — add one in Time") }, onClick = { actMenu = false })
                            timeActivities.filter { !it.archived }.forEach { a ->
                                DropdownMenuItem(text = { Text((a.emoji?.plus(" ") ?: "") + a.name) }, onClick = { timeActivityId = a.id; actMenu = false })
                            }
                        }
                    }
                }
                // V3: how tracked time on a linked activity credits this habit.
                if (timeOn && timeActivityId != null) {
                    Text("When timed, count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("minutes" to "Minutes", "sessions" to "Sessions", "off" to "Off").forEach { (v, lbl) ->
                            FilterChip(selected = linkMode == v, onClick = { linkMode = v }, label = { Text(lbl) })
                        }
                    }
                }
            }

            // 6. Motivation & extras (Tier K)
            EditorCard {
                Text("Identity & stacking", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                com.todocompanion.app.ui.components.AppTextField(identity, { identity = it.take(60) }, singleLine = true,
                    label = { Text("I'm becoming… (e.g. “a writer”)") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                // Anchor picker (K4).
                var anchorMenu by remember { mutableStateOf(false) }
                val candidates = allHabits.filter { it.id != existing?.id }
                val anchorName = candidates.firstOrNull { it.id == anchorId }?.name
                Box(Modifier.padding(top = 10.dp)) {
                    Surface(Modifier.fillMaxWidth().clickable(enabled = candidates.isNotEmpty()) { anchorMenu = true }, shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (anchorName != null) "After I: $anchorName" else if (candidates.isEmpty()) "Add another habit to stack after" else "Stack after another habit (optional)",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                color = if (anchorName != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (anchorId != null) TextButton(onClick = { anchorId = null }) { Text("Clear") }
                        }
                    }
                    DropdownMenu(expanded = anchorMenu, onDismissRequest = { anchorMenu = false }) {
                        candidates.forEach { c ->
                            DropdownMenuItem(text = { Text((c.emoji?.plus(" ") ?: "") + c.name) }, onClick = { anchorId = c.id; anchorMenu = false })
                        }
                    }
                }

                // Self-reward (K5, light).
                Text("Reward", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
                com.todocompanion.app.ui.components.AppTextField(rewardText, { rewardText = it.take(80) }, singleLine = true,
                    label = { Text("Treat yourself when you hit a streak (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                if (rewardText.isNotBlank()) StepperRow("…at a streak of", if (rewardAt <= 0) "30" else rewardAt.toString(),
                    onMinus = { rewardAt = ((if (rewardAt <= 0) 30 else rewardAt) - 5).coerceAtLeast(5) },
                    onPlus = { rewardAt = ((if (rewardAt <= 0) 30 else rewardAt) + 5).coerceAtMost(1000) }, modifier = Modifier.padding(top = 6.dp))
            }

            // 7. Organise (M6): a user-editable start date. (Grouping moved up to the main card — F2.)
            EditorCard {
                Text("Start date", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val startLabel = startDate?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")) }
                    Text("Start date", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (startDate != null) TextButton(onClick = { startDate = null }) { Text("Reset") }
                    TextButton(onClick = { showStartPicker = true }) { Text(startLabel ?: "When created") }
                }
                Text("Days before the start date aren't counted as misses.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            } // advancedOpen
            Spacer(Modifier.height(40.dp))
        }
    }
    if (confirmDelete && existing != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        icon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Delete habit?") },
        text = { Text("“${existing.name}” and all its check-in history will be permanently deleted. This can't be undone.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteHabit(existing.id); onClose() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    if (showStartPicker) {
        val initial = startDate ?: existing?.createdAt ?: System.currentTimeMillis()
        val pickerState = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = initial)
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = { TextButton(onClick = { startDate = pickerState.selectedDateMillis; showStartPicker = false }) { Text("Set") } },
            dismissButton = { TextButton(onClick = { showStartPicker = false }) { Text("Cancel") } },
        ) { androidx.compose.material3.DatePicker(state = pickerState) }
    }
}

/** A rounded surface section for the habit editor, matching the app's card language. */
@Composable
private fun EditorCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
    }
}

/** A label + −/value/+ stepper row, used throughout the editor. */
@Composable
private fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = onMinus) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        TextButton(onClick = onPlus) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

/**
 * L6: type a habit in plain language ("meditate 10 min every morning") — parsed live into a draft.
 * Presented as a bottom sheet with a borderless title field and a send button, so new-habit capture
 * feels identical to the task quick-add sheet (one consistent capture language across the app).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitQuickAddDialog(onDismiss: () -> Unit, onAdd: (HabitEntity) -> Unit, onAdvanced: (HabitEntity) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    val draft = remember(text) { com.todocompanion.app.domain.habit.HabitQuickParser.parse(text) }
    val focus = remember { FocusRequester() }
    fun submit() { if (draft.name.isNotBlank()) onAdd(draft) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp)) {
            Text("New habit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)) {
                if (text.isEmpty()) Text("meditate 10 min every morning",
                    color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                BasicTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
            }
            if (text.isNotBlank()) {
                Text("→ ${draft.name.ifBlank { "?" }} · ${HabitStats.frequencyLabel(draft)}" +
                    (draft.unit?.let { " · ${draft.targetPerDay} $it" } ?: "") +
                    (draft.reminderTimes.split(",").firstOrNull { it.isNotBlank() }?.toIntOrNull()?.let { " · 🔔 %02d:%02d".format(it / 60, it % 60) } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            } else {
                Text("Try “gym 3x a week”, “read 20 pages daily”, “journal every evening at 9pm”.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onAdvanced(draft) }) { Text("Advanced…") }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(if (draft.name.isBlank()) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary)
                        .clickable { submit() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Send, "Add habit",
                        tint = if (draft.name.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun HabitPresetDialog(onDismiss: () -> Unit, onPick: (HabitPreset) -> Unit, onAddRoutine: (HabitRoutine) -> Unit) {
    var addedRoutine by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Starter gallery") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Themed routines: one tap builds a whole coherent set (M2).
                Text("ROUTINES", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Add a whole set at once — each lands in its own section.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                HABIT_ROUTINES.forEach { r ->
                    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.emoji, style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(r.blurb + " · " + r.habits.joinToString(" ") { it.emoji }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (addedRoutine == r.name) Text("Added ✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            else FilledTonalButton(onClick = { onAddRoutine(r); addedRoutine = r.name }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("Add ${r.habits.size}") }
                        }
                    }
                }
                Spacer(Modifier.size(14.dp))
                Text("SINGLE HABITS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Tap to add — tweak the target, schedule and reminders after.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(4.dp))
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
