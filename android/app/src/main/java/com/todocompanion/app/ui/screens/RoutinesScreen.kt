package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.todocompanion.app.domain.Routine
import com.todocompanion.app.domain.RoutineCatalog
import com.todocompanion.app.domain.RoutineRun
import com.todocompanion.app.domain.RoutineStep
import com.todocompanion.app.domain.Routines
import com.todocompanion.app.domain.StepKind
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import com.todocompanion.app.ui.components.DoneTick
import com.todocompanion.app.ui.components.EmojiGridPicker
import com.todocompanion.app.ui.components.MiniCheck
import com.todocompanion.app.ui.components.OptionChips
import com.todocompanion.app.ui.components.Stepper
import java.time.LocalDate
import java.util.UUID

/** Round timed seconds up to whole minutes for a friendly "12 min" label. */
private fun minLabel(sec: Int): String = "${(sec + 59) / 60} min"

private fun blankRoutine() = Routine(id = UUID.randomUUID().toString(), name = "", createdAt = System.currentTimeMillis())

private fun templateToRoutine(t: RoutineCatalog.Template): Routine = Routine(
    id = UUID.randomUUID().toString(),
    name = t.name,
    emoji = t.emoji,
    steps = t.steps.map { it.copy(id = UUID.randomUUID().toString()) },
    createdAt = System.currentTimeMillis(),
)

/**
 * Routines — the list of the user's named, press-play rituals, the catalog of starters, the editor, and the
 * runner. Fully offline; routines & run history round-trip in the settings JSON backup (no new Room entity).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val settings by vm.settings.collectAsState()
    // Re-parse whenever the persisted JSON changes so add/edit/delete reflect immediately.
    val routines = remember(settings.routinesJson) { vm.routines() }
    val runs = remember(settings.routineRunsJson) { vm.routineRuns() }
    val dayLogs by vm.dayLogs.collectAsState()
    val today = vm.today()
    val onThisDay = remember(settings.routineRunsJson, settings.routinesJson, today) {
        com.todocompanion.app.domain.RoutineInsights.onThisDay(routines, runs, today)
    }

    var running by remember { mutableStateOf<Routine?>(null) }
    var editing by remember { mutableStateOf<Routine?>(null) }
    var insightsFor by remember { mutableStateOf<Routine?>(null) }
    var browseCatalog by remember { mutableStateOf(false) }

    // A reminder tap deep-links here asking to run a specific routine — start its runner once, then clear.
    val pendingRun by vm.pendingRoutineRun.collectAsState()
    LaunchedEffect(pendingRun, routines) {
        val id = pendingRun ?: return@LaunchedEffect
        val r = routines.firstOrNull { it.id == id }
        if (r != null && r.isRunnable) running = r
        vm.pendingRoutineRun.value = null
    }

    Scaffold(topBar = {
        TopAppBar(expandedHeight = 52.dp,
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text("Routines", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = { IconButton(onClick = { editing = blankRoutine() }) { Icon(Icons.Filled.Add, "New routine") } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("A named, ordered ritual you press play on — a morning primer, an evening shutdown, a deep-work start. Each step guides you; finishing ticks the linked habits and tasks.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
            }
            if (onThisDay.isNotEmpty()) item {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("📅 On this day", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        onThisDay.take(3).forEach { o ->
                            Text("${o.emoji} ${o.yearsAgo} year${if (o.yearsAgo == 1) "" else "s"} ago you ran “${o.routineName}”.",
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { editing = blankRoutine() }, modifier = Modifier.weight(1f)) { Text("＋ New routine") }
                    FilledTonalButton(onClick = { browseCatalog = true }, modifier = Modifier.weight(1f)) { Text("Browse starters") }
                }
            }
            if (routines.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 36.dp, start = 8.dp, end = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("▶️", fontSize = 44.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("No routines yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Add one from the starter catalog — a morning primer, an evening shutdown, a focus sprint — or build your own from scratch.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = { browseCatalog = true }) { Text("Browse starter routines") }
                }
            }
            items(routines.size) { i ->
                val r = routines[i]
                AppCard(modifier = Modifier.clickable { editing = r }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.emoji, fontSize = 26.sp, modifier = Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name.ifBlank { "Untitled routine" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val stepsLabel = "${r.steps.size} step${if (r.steps.size == 1) "" else "s"}"
                            val timeLabel = if (r.plannedSec > 0) " · ${minLabel(r.plannedSec)}" else ""
                            Text(stepsLabel + timeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (runs.any { it.routineId == r.id }) {
                            IconButton(onClick = { insightsFor = r }) { Icon(Icons.Filled.QueryStats, "Routine insights", tint = MaterialTheme.colorScheme.outline) }
                        }
                        if (r.isRunnable) {
                            FilledTonalButton(onClick = { running = r }) {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Run")
                            }
                        }
                    }
                }
            }
        }
    }

    running?.let { r -> RoutineRunner(vm, r, onExit = { running = null }) }
    editing?.let { r ->
        val existing = routines.any { it.id == r.id }
        RoutineEditor(vm, r, existing,
            onDismiss = { editing = null },
            onSave = { vm.upsertRoutine(it); editing = null },
            onDelete = { vm.deleteRoutine(r.id); editing = null })
    }
    if (browseCatalog) CatalogDialog(onDismiss = { browseCatalog = false }, onAdd = { vm.upsertRoutine(templateToRoutine(it)); browseCatalog = false })
    insightsFor?.let { r -> RoutineInsightsDialog(r, runs, dayLogs, today, onDismiss = { insightsFor = null }) }
}

/** Per-routine analytics (adherence, best time, drop-off step, keystone) + a "this year" summary.
 *  All derived on-device from the run history + felt-state — nothing a single-purpose runner can show. */
@Composable
private fun RoutineInsightsDialog(
    r: Routine,
    runs: List<RoutineRun>,
    dayLogs: List<com.todocompanion.app.data.entity.DayLogEntity>,
    today: Long,
    onDismiss: () -> Unit,
) {
    val stat = remember(r, runs, dayLogs, today) { com.todocompanion.app.domain.RoutineInsights.forRoutine(r, runs, dayLogs, today) }
    // Only this routine's runs — otherwise the per-routine "This year" would sum across every routine.
    val year = remember(runs, r) { com.todocompanion.app.domain.RoutineInsights.yearSummary(listOf(r), runs.filter { it.routineId == r.id }, LocalDate.ofEpochDay(today).year) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("${r.emoji} ${r.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InsightRow("Kept", "${stat.adherencePct}% · ${stat.runs30} of the last ${stat.window} days" + (if (stat.currentStreak > 1) " · ${stat.currentStreak}-day streak" else ""))
                if (stat.bestStreak > 1) InsightRow("Best streak", "${stat.bestStreak} days")
                stat.bestHour?.let { InsightRow("Usual time", "%02d:00".format(it)) }
                stat.dropOffStepTitle?.let { InsightRow("Drop-off step", it, hint = "the step you skip most") }
                if (stat.keystoneMetric.isNotBlank() && kotlin.math.abs(stat.keystoneDelta) >= 0.2) {
                    val sign = if (stat.keystoneDelta > 0) "+" else ""
                    InsightRow("Keystone", "On days you run this, your ${stat.keystoneMetric} is $sign${oneDp(stat.keystoneDelta)}",
                        hint = "vs days you don't", accent = true)
                }
                if (year.totalRuns > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    InsightRow("This year", "${year.totalRuns} runs · ${year.totalMinutes / 60}h ${year.totalMinutes % 60}m" + (if (year.bestStreak > 1) " · best ${year.bestStreak}-day streak" else ""))
                }
                if (stat.totalRuns < 4) Text("More insight arrives as you run this a few more times.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        },
    )
}

@Composable
private fun InsightRow(label: String, value: String, hint: String? = null, accent: Boolean = false) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, letterSpacing = 0.8.sp)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal,
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
    }
}

private fun oneDp(v: Double): String = "%.1f".format(v)

// ── The runner ──────────────────────────────────────────────────────────────────────────────────────
@Composable
private fun RoutineRunner(vm: AppViewModel, routine: Routine, onExit: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val today = vm.today()

    // Felt-state gating (moat #6): on a low-energy day, default to the 2-minute Lite version — never-miss-
    // twice becomes a kind recovery, not a shame event. The user can still flip it back to the full run.
    val todayEnergy = vm.dayLogs.collectAsState().value.firstOrNull { it.epochDay == today }?.energy ?: 0
    val autoLite = todayEnergy in 1..2
    var lite by remember { mutableStateOf(autoLite) }
    val steps = remember(lite, routine) { if (lite) Routines.lite(routine).steps else routine.steps }

    var started by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var idx by remember { mutableIntStateOf(0) }
    var secsLeft by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var stepEndMillis by remember { mutableLongStateOf(0L) }   // wall-clock end of the current timed step (0 = untimed/paused)
    val completed = remember { mutableStateListOf<String>() }
    val skipped = remember { mutableStateListOf<String>() }

    // Persist the in-progress run so a background/kill mid-routine doesn't lose it (durability, moat).
    fun persist() {
        if (!started || finished) return
        vm.saveActiveRoutineRun(com.todocompanion.app.domain.ActiveRoutineRun(
            routineId = routine.id, lite = lite, idx = idx, startedAtMillis = startedAt,
            stepEndMillis = if (paused) 0L else stepEndMillis, remainingSec = if (paused) secsLeft else 0,
            paused = paused, completedStepIds = completed.toList(), skippedStepIds = skipped.toList()))
    }

    // Restore a persisted run for THIS routine once, on entry — resuming the timer from the stored end time.
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(routine.id) {
        if (restored) return@LaunchedEffect
        restored = true
        val a = vm.activeRoutineRun()?.takeIf { it.routineId == routine.id } ?: return@LaunchedEffect
        lite = a.lite
        val restSteps = if (a.lite) Routines.lite(routine).steps else routine.steps
        if (a.idx !in restSteps.indices) { vm.clearActiveRoutineRun(); return@LaunchedEffect }
        completed.clear(); completed.addAll(a.completedStepIds)
        skipped.clear(); skipped.addAll(a.skippedStepIds)
        idx = a.idx; startedAt = a.startedAtMillis; paused = a.paused
        val dur = restSteps[a.idx].durationSec
        when {
            dur == null -> { secsLeft = 0; stepEndMillis = 0L }
            a.paused -> { secsLeft = a.remainingSec.coerceAtLeast(0); stepEndMillis = 0L }
            else -> { stepEndMillis = a.stepEndMillis; secsLeft = (((a.stepEndMillis - System.currentTimeMillis()) + 999) / 1000).toInt().coerceAtLeast(0) }
        }
        started = true
    }

    fun advance(complete: Boolean) {
        val step = steps.getOrNull(idx) ?: return
        if (complete) { if (step.id !in completed) completed.add(step.id) } else { if (step.id !in skipped) skipped.add(step.id) }
        if (idx < steps.lastIndex) {
            idx += 1; paused = false
            val d = steps[idx].durationSec
            secsLeft = d ?: 0
            stepEndMillis = if (d != null) System.currentTimeMillis() + d * 1000L else 0L
            persist()
        } else { finished = true; vm.clearActiveRoutineRun() }
    }

    // Entering a step whose startActivityId is set starts the time-tracker for it.
    LaunchedEffect(idx, started) {
        if (started && !finished) steps.getOrNull(idx)?.startActivityId?.takeIf { it.isNotBlank() }?.let { vm.startTimeTracking(it) }
    }
    // Wall-clock countdown for a timed step; auto-advances at the stored end time (survives doze/background,
    // resumes correctly after a kill). Untimed steps just wait for a Done tap.
    LaunchedEffect(idx, started, paused, stepEndMillis) {
        if (!started || finished || paused) return@LaunchedEffect
        if ((steps.getOrNull(idx)?.durationSec) == null || stepEndMillis <= 0L) return@LaunchedEffect
        while (System.currentTimeMillis() < stepEndMillis) {
            kotlinx.coroutines.delay(250)
            if (paused) return@LaunchedEffect
            secsLeft = (((stepEndMillis - System.currentTimeMillis()) + 999) / 1000).toInt().coerceAtLeast(0)
        }
        secsLeft = 0
        advance(complete = true)
    }
    // On finish: a haptic flourish, tick linked habits/tasks and log the run.
    LaunchedEffect(finished) {
        if (finished) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val totalSec = ((System.currentTimeMillis() - startedAt) / 1000).toInt().coerceAtLeast(0)
            vm.logRoutineRun(RoutineRun(routine.id, today, startedAt, completed.toList(), skipped.toList(), totalSec, lite, finished = true))
        }
    }

    // Back / close while a run is in progress logs a partial run (was a silent discard) and clears the resume.
    fun exitRun() {
        if (started && !finished) {
            val totalSec = ((System.currentTimeMillis() - startedAt) / 1000).toInt().coerceAtLeast(0)
            vm.logRoutineRun(RoutineRun(routine.id, today, startedAt, completed.toList(), skipped.toList(), totalSec, lite, finished = false))
            vm.clearActiveRoutineRun()
        }
        onExit()
    }

    BackHandler(onBack = { exitRun() })
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // Slim top bar with a back / close.
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { exitRun() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") }
                Text(routine.emoji + "  " + routine.name.ifBlank { "Routine" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                finished -> FinishContent(completed.size, skipped.size, lite, onDone = onExit)
                !started -> PreStart(routine, steps, lite, autoSuggested = autoLite, onToggleLite = { lite = it }, onStart = {
                    started = true; startedAt = System.currentTimeMillis(); idx = 0; paused = false; completed.clear(); skipped.clear()
                    val d0 = steps.getOrNull(0)?.durationSec
                    secsLeft = d0 ?: 0
                    stepEndMillis = if (d0 != null) System.currentTimeMillis() + d0 * 1000L else 0L
                    // Start the routine's own activity only when step 0 doesn't already carry one (avoids a
                    // double time-tracker start); habitCategory surfacing still runs via runRoutine.
                    if (steps.getOrNull(0)?.startActivityId.isNullOrBlank() && (routine.activityId.isNotBlank() || routine.habitCategory.isNotBlank())) vm.runRoutine(routine)
                    persist()
                })
                else -> steps.getOrNull(idx)?.let { step ->
                    Running(
                        step = step, idx = idx, total = steps.size, secsLeft = secsLeft, paused = paused,
                        onPauseResume = {
                            if (paused) { stepEndMillis = System.currentTimeMillis() + secsLeft * 1000L; paused = false }
                            else { paused = true; stepEndMillis = 0L }   // freeze: secsLeft holds the remainder
                            persist()
                        },
                        onSkip = { advance(complete = false) },
                        onAddMinute = { secsLeft += 60; if (!paused && step.durationSec != null) stepEndMillis += 60_000L; persist() },
                        onDone = { advance(complete = true) },
                        onEnd = { exitRun() },
                    )
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PreStart(
    routine: Routine, steps: List<RoutineStep>, lite: Boolean, autoSuggested: Boolean = false, onToggleLite: (Boolean) -> Unit, onStart: () -> Unit,
) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(routine.emoji, fontSize = 52.sp)
                Spacer(Modifier.height(6.dp))
                Text(routine.name.ifBlank { "Routine" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                val planned = steps.sumOf { it.durationSec ?: 0 }
                Text("${steps.size} step${if (steps.size == 1) "" else "s"}" + if (planned > 0) " · ${minLabel(planned)}" else "",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (routine.note.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(routine.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        steps.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", Modifier.width(24.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.emoji.isNotBlank()) { Text(s.emoji, fontSize = 18.sp); Spacer(Modifier.width(8.dp)) }
                Text(s.title.ifBlank { "Step ${i + 1}" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (s.durationSec != null) minLabel(s.durationSec!!) else "check-off", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Lite (low-energy)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("Keep only the essentials and cap timers at 2 minutes — never miss twice, kindly.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = lite, onCheckedChange = onToggleLite)
        }
        if (autoSuggested && lite) {
            Spacer(Modifier.height(6.dp))
            Text("Suggested — your energy read low today.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
    }
    Button(onClick = onStart, enabled = steps.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
        Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Start")
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Running(
    step: RoutineStep, idx: Int, total: Int, secsLeft: Int, paused: Boolean,
    onPauseResume: () -> Unit, onSkip: () -> Unit, onAddMinute: () -> Unit, onDone: () -> Unit, onEnd: () -> Unit,
) {
    val timed = step.durationSec != null
    val accent = MaterialTheme.colorScheme.primary
    // Progress: one segment per step, filled through the current step.
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        (0 until total).forEach { i ->
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(if (i <= idx) accent else MaterialTheme.colorScheme.surfaceVariant))
        }
    }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Text("Step ${idx + 1} of $total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Box(Modifier.size(150.dp).clip(CircleShape).background(accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            Text(step.emoji.ifBlank { if (timed) "⏱️" else "✓" }, fontSize = 60.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text(step.title.ifBlank { "Step ${idx + 1}" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (step.note.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(step.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
        if (timed) {
            Text("${secsLeft / 60}:${(secsLeft % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else accent)
            if (paused) Text("Paused", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Tap Done when you've finished.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
    }
    // Pinned control bar — clears the nav bar via the parent's systemBarsPadding.
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (timed) {
                OutlinedButton(onClick = onPauseResume, modifier = Modifier.weight(1f)) { Text(if (paused) "Resume" else "Pause") }
                OutlinedButton(onClick = onAddMinute, modifier = Modifier.weight(1f)) { Text("+1 min") }
            }
            OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) { Text("Skip") }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            MiniCheck(); Spacer(Modifier.width(8.dp)); Text(if (idx == total - 1) "Done · Finish" else "Done · Next")
        }
        TextButton(onClick = onEnd, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("End", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.FinishContent(done: Int, skipped: Int, lite: Boolean, onDone: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("🎉", fontSize = 72.sp)
        Spacer(Modifier.height(12.dp))
        DoneTick()
        Spacer(Modifier.height(14.dp))
        Text("Routine complete", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        val tail = if (skipped > 0) " · $skipped skipped" else ""
        Text(
            (if (lite) "A lite run still counts. " else "") + "$done step${if (done == 1) "" else "s"} done$tail. That's a vote for who you're becoming.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)) { Text("Done") }
}

// ── The editor ──────────────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutineEditor(
    vm: AppViewModel, routine: Routine, existing: Boolean,
    onDismiss: () -> Unit, onSave: (Routine) -> Unit, onDelete: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val habits by vm.habits.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val activities by vm.timeActivities.collectAsState()
    val openTasks = remember(tasks) { tasks.filter { !it.completed && !it.trashed && !it.isNote } }
    val liveActivities = remember(activities) { activities.filter { !it.archived } }

    var name by remember { mutableStateOf(routine.name) }
    var emoji by remember { mutableStateOf(routine.emoji) }
    var note by remember { mutableStateOf(routine.note) }
    var reminderOn by remember { mutableStateOf(routine.whenReminderMin != null) }
    var reminderHour by remember { mutableIntStateOf((routine.whenReminderMin ?: 7 * 60) / 60) }
    var activityId by remember { mutableStateOf(routine.activityId) }
    var habitCategory by remember { mutableStateOf(routine.habitCategory) }
    val steps = remember { mutableStateListOf<RoutineStep>().apply { addAll(routine.steps) } }

    var pickRoutineEmoji by remember { mutableStateOf(false) }
    var pickStepEmoji by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val totalMin = steps.sumOf { it.durationSec ?: 0 } / 60

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TopAppBar(expandedHeight = 52.dp,
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text(if (existing) "Edit routine" else "New routine", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    AssistChip(onClick = {}, label = { Text("Total $totalMin min") }, modifier = Modifier.padding(end = 8.dp))
                    if (existing) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                })
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)).clickable { pickRoutineEmoji = true }, contentAlignment = Alignment.Center) {
                        Text(emoji.ifBlank { "🔗" }, fontSize = 26.sp)
                    }
                    OutlinedTextField(name, { name = it }, label = { Text("Routine name") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())

                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Daily reminder", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(if (reminderOn) "Remind me each day" else "Off", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (reminderOn) com.todocompanion.app.ui.components.HourStepper(reminderHour, onChange = { reminderHour = ((it % 24) + 24) % 24 })
                        Switch(checked = reminderOn, onCheckedChange = { reminderOn = it })
                    }
                }

                AppCard {
                    Text("On start (optional)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    DropdownPicker(
                        label = "Start tracking activity",
                        currentLabel = liveActivities.firstOrNull { it.id == activityId }?.let { (it.emoji?.plus(" ") ?: "") + it.name },
                        options = liveActivities, optionLabel = { (it.emoji?.plus(" ") ?: "") + it.name },
                        onPick = { activityId = it?.id ?: "" },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(habitCategory, { habitCategory = it }, label = { Text("Surface habit group") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }

                HorizontalDivider()
                Text("STEPS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)

                steps.forEachIndexed { i, s ->
                    StepEditorCard(
                        step = s, index = i, count = steps.size,
                        habits = habits, tasks = openTasks, activities = liveActivities,
                        onChange = { steps[i] = it },
                        onPickEmoji = { pickStepEmoji = i },
                        onMoveUp = { if (i > 0) { val tmp = steps[i - 1]; steps[i - 1] = steps[i]; steps[i] = tmp } },
                        onMoveDown = { if (i < steps.lastIndex) { val tmp = steps[i + 1]; steps[i + 1] = steps[i]; steps[i] = tmp } },
                        onDelete = { steps.removeAt(i) },
                    )
                }
                FilledTonalButton(onClick = {
                    steps.add(RoutineStep(id = UUID.randomUUID().toString(), title = "", durationSec = 300, kind = StepKind.TIMER))
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add step") }
                Spacer(Modifier.height(8.dp))
            }
            Button(onClick = {
                onSave(routine.copy(
                    name = name.trim(),
                    emoji = emoji.ifBlank { "🔗" },
                    note = note.trim(),
                    whenReminderMin = if (reminderOn) reminderHour.coerceIn(0, 23) * 60 else null,
                    activityId = activityId,
                    habitCategory = habitCategory.trim(),
                    steps = steps.toList(),
                ))
            }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { Text("Save routine") }
        }
    }

    if (pickRoutineEmoji) AlertDialog(onDismissRequest = { pickRoutineEmoji = false },
        title = { Text("Pick an emoji") },
        text = { EmojiGridPicker(current = emoji.ifBlank { null }, onPick = { emoji = it ?: ""; pickRoutineEmoji = false }) },
        confirmButton = { TextButton(onClick = { pickRoutineEmoji = false }) { Text("Close") } })
    pickStepEmoji?.let { i ->
        AlertDialog(onDismissRequest = { pickStepEmoji = null },
            title = { Text("Pick an emoji") },
            text = { EmojiGridPicker(current = steps[i].emoji.ifBlank { null }, onPick = { steps[i] = steps[i].copy(emoji = it ?: ""); pickStepEmoji = null }) },
            confirmButton = { TextButton(onClick = { pickStepEmoji = null }) { Text("Close") } })
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete routine?") },
        text = { Text("“${routine.name}” and its run history stay, but the routine itself is removed. This can't be undone.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun StepEditorCard(
    step: RoutineStep, index: Int, count: Int,
    habits: List<com.todocompanion.app.data.entity.HabitEntity>,
    tasks: List<com.todocompanion.app.data.entity.TaskEntity>,
    activities: List<com.todocompanion.app.data.entity.TimeActivityEntity>,
    onChange: (RoutineStep) -> Unit, onPickEmoji: () -> Unit,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit, onDelete: () -> Unit,
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)).clickable { onPickEmoji() }, contentAlignment = Alignment.Center) {
                Text(step.emoji.ifBlank { "＋" }, fontSize = 20.sp)
            }
            OutlinedTextField(step.title, { onChange(step.copy(title = it)) }, label = { Text("Step ${index + 1}") }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(onClick = onMoveUp, enabled = index > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
            IconButton(onClick = onMoveDown, enabled = index < count - 1) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete step", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(8.dp))
        OptionChips(listOf(StepKind.TIMER, StepKind.CHECKOFF), step.kind, { k ->
            onChange(step.copy(kind = k, durationSec = if (k == StepKind.TIMER) (step.durationSec ?: 300) else null))
        }, label = { if (it == StepKind.TIMER) "Timer" else "Check-off" })
        if (step.kind == StepKind.TIMER) {
            Spacer(Modifier.height(8.dp))
            Stepper(
                value = ((step.durationSec ?: 300) / 60).coerceAtLeast(1),
                onChange = { onChange(step.copy(durationSec = it.coerceAtLeast(1) * 60)) },
                min = 1, max = 240, step = 1, label = "Minutes", display = { "$it min" },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Essential — kept on a lite day", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = step.essential, onCheckedChange = { onChange(step.copy(essential = it)) })
        }
        Spacer(Modifier.height(4.dp))
        DropdownPicker("Tick this habit on finish", habits.firstOrNull { it.id == step.linkedHabitId }?.let { (it.emoji?.plus(" ") ?: "") + it.name },
            habits, { (it.emoji?.plus(" ") ?: "") + it.name }) { onChange(step.copy(linkedHabitId = it?.id)) }
        Spacer(Modifier.height(6.dp))
        DropdownPicker("Complete this task on finish", tasks.firstOrNull { it.id == step.linkedTaskId }?.title,
            tasks, { it.title }) { onChange(step.copy(linkedTaskId = it?.id)) }
        Spacer(Modifier.height(6.dp))
        DropdownPicker("Start tracking on this step", activities.firstOrNull { it.id == step.startActivityId }?.let { (it.emoji?.plus(" ") ?: "") + it.name },
            activities, { (it.emoji?.plus(" ") ?: "") + it.name }) { onChange(step.copy(startActivityId = it?.id)) }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(step.note, { onChange(step.copy(note = it)) }, label = { Text("Cue / note (optional)") }, modifier = Modifier.fillMaxWidth())
    }
}

/** A compact "pick one (or none)" dropdown used for linked habit / task / activity selectors. */
@Composable
private fun <T> DropdownPicker(label: String, currentLabel: String?, options: List<T>, optionLabel: (T) -> String, onPick: (T?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(currentLabel ?: "None", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                DropdownMenuItem(text = { Text("None") }, onClick = { onPick(null); open = false })
                options.forEach { o ->
                    DropdownMenuItem(text = { Text(optionLabel(o), maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { onPick(o); open = false })
                }
            }
        }
    }
}

// ── The starter catalog ──────────────────────────────────────────────────────────────────────────────
@Composable
private fun CatalogDialog(onDismiss: () -> Unit, onAdd: (RoutineCatalog.Template) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Starter routines") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoutineCatalog.templates.forEach { t ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f), modifier = Modifier.fillMaxWidth().clickable { onAdd(t) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.emoji, fontSize = 24.sp, modifier = Modifier.padding(end = 12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(t.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val planned = t.steps.sumOf { it.durationSec ?: 0 }
                                Text("${t.steps.size} steps" + if (planned > 0) " · ${minLabel(planned)}" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Filled.Add, "Add", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
