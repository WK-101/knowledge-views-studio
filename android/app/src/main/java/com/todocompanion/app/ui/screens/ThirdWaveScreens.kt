package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.todocompanion.app.domain.habit.HabitStats
import com.todocompanion.app.domain.habit.ThirdWave
import com.todocompanion.app.ui.AppViewModel
import java.time.LocalDate

/**
 * R35 — the third-wave screens (Causal Life Lab, values-time mirror, behavioral activation, routine
 * runner, focus lock, life heatmap, companion garden). Routed from the Life-Systems hub. Offline, no LLM.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThirdWaveScreen(vm: AppViewModel, route: String, onBack: () -> Unit, onOpenHabit: (String) -> Unit) {
    BackHandler(onBack = onBack)
    when (route) {
        "experiments" -> ExperimentsScreen(vm, onBack, onOpenHabit)
        "valuestime" -> ValuesTimeScreen(vm, onBack)
        "activation" -> ActivationScreen(vm, onBack)
        "runner" -> RoutineRunnerScreen(vm, onBack)
        "focuslock" -> FocusLockScreen(vm, onBack)
        "heatmap" -> LifeHeatmapScreen(vm, onBack)
        "companion" -> CompanionScreen(vm, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TWScaffold(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit) {
    Scaffold(topBar = {
        TopAppBar(expandedHeight = 52.dp,
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = { actions() })
    }, content = content)
}

@Composable
private fun TWEmpty(emoji: String, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp, start = 8.dp, end = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 44.sp); Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun MoodChips(label: String, value: Int, onPick: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..5).forEach { n -> FilterChip(selected = value == n, onClick = { onPick(n) }, label = { Text("$n") }) }
    }
}

// ── TW-C · Causal Life Lab (n-of-1 experiments) ───────────────────────────────────────────────────
@Composable
private fun ExperimentsScreen(vm: AppViewModel, onBack: () -> Unit, onOpenHabit: (String) -> Unit) {
    val exps by vm.experiments.collectAsState()
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val today = LocalDate.now().toEpochDay()
    var addOpen by remember { mutableStateOf(false) }
    TWScaffold("Causal Life Lab", onBack, actions = { IconButton(onClick = { addOpen = true }) { Icon(Icons.Filled.Add, "New experiment") } }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Turn a correlation into a within-person test: toggle one habit ON and OFF in alternating blocks, log the outcome, and see the effect. The causal upgrade only your own trusted ledger can run.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (exps.isEmpty()) item { TWEmpty("🔬", "No experiments yet", "Pick a habit and an outcome (mood, energy, tasks). Follow the blocks; the verdict appears here.") }
            items(exps.size) { i ->
                val e = exps[i]
                val h = habits.firstOrNull { it.id == e.habitId }
                val res = remember(e, checkins, tasks, today) { ThirdWave.analyzeExperiment(e, h ?: return@remember null, checkins, tasks, today) }
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().clickable { h?.let { onOpenHabit(it.id) } }) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${h?.emoji?.plus(" ") ?: ""}${h?.name ?: "?"} → ${e.outcome}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.deleteExperiment(e.id) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        val onBlockNow = e.onForDay(today)
                        val phase = if (today > e.endDay()) "Complete" else if (onBlockNow) "ON block — do the habit today" else "OFF block — skip it today"
                        Text("${e.blocks} × ${e.blockLenDays}-day blocks · $phase", style = MaterialTheme.typography.labelMedium, color = if (onBlockNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (res != null) {
                            val sign = if (res.effect >= 0) "+" else ""
                            Text("On “on” days, ${res.outcomeLabel} was $sign${String.format("%.1f", res.effect)} vs off (${String.format("%.1f", res.onMean)} vs ${String.format("%.1f", res.offMean)}).",
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                            Text(if (res.confident) "Enough data to trust the direction." else "Keep going — more days will firm this up. (${res.nOn}/${res.nOff} days)",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else Text("Log the outcome as the blocks run — remember to add a mood/energy tag when you check the habit off.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
    if (addOpen) {
        val builds = habits.filter { !it.archived && it.habitType != "break" }
        var habitId by remember { mutableStateOf(builds.firstOrNull()?.id ?: "") }
        var outcome by remember { mutableStateOf("mood") }
        AlertDialog(onDismissRequest = { addOpen = false },
            title = { Text("New experiment") },
            text = {
                Column {
                    Text("Manipulate which habit?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column {
                        builds.take(8).forEach { h ->
                            FilterChip(selected = habitId == h.id, onClick = { habitId = h.id }, label = { Text((h.emoji?.plus(" ") ?: "") + h.name) }, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Measure which outcome?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("mood", "energy", "tasks").forEach { o -> FilterChip(selected = outcome == o, onClick = { outcome = o }, label = { Text(o) }) }
                    }
                }
            },
            confirmButton = { TextButton(enabled = habitId.isNotBlank(), onClick = { vm.startExperiment(habitId, outcome, 3, 4); addOpen = false }) { Text("Start") } },
            dismissButton = { TextButton(onClick = { addOpen = false }) { Text("Cancel") } })
    }
}

// ── TW-C · values-time mirror ─────────────────────────────────────────────────────────────────────
@Composable
private fun ValuesTimeScreen(vm: AppViewModel, onBack: () -> Unit) {
    val values by vm.coreValues.collectAsState()
    val habits by vm.habits.collectAsState()
    val entries by vm.timeEntries.collectAsState()
    val today = LocalDate.now().toEpochDay()
    val audit = remember(values, habits, entries, today) { ThirdWave.valuesTimeAudit(values, habits, entries, today - 27) }
    val total = audit.sumOf { it.minutes }.coerceAtLeast(1)
    TWScaffold("Values-time mirror", onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("What you say matters, against where your tracked hours actually went (last 4 weeks). Link a value to a habit, and the habit to a time activity, to feed this mirror.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (values.isEmpty() || audit.all { it.minutes == 0L }) item {
                TWEmpty("⚖️", "No tracked time to reflect yet", "Attach a value to a habit and link that habit to a Time activity — then track it. The mirror fills in from there.")
            }
            items(audit.size) { i ->
                val vt = audit[i]
                val color = vt.value.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                val share = vt.minutes.toFloat() / total
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text((vt.value.emoji?.plus(" ") ?: "") + vt.value.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(if (vt.minutes >= 60) "${vt.minutes / 60}h ${vt.minutes % 60}m" else "${vt.minutes}m", style = MaterialTheme.typography.labelLarge, color = color)
                        }
                        Box(Modifier.fillMaxWidth().height(8.dp).padding(top = 6.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth(share.coerceIn(0.02f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
                        }
                    }
                }
            }
        }
    }
}

// ── TW-D · behavioral activation ──────────────────────────────────────────────────────────────────
@Composable
private fun ActivationScreen(vm: AppViewModel, onBack: () -> Unit) {
    val items by vm.activationItems.collectAsState()
    val values by vm.coreValues.collectAsState()
    val today = LocalDate.now().toEpochDay()
    var text by remember { mutableStateOf("") }
    var valueId by remember { mutableStateOf<String?>(null) }
    var rating by remember { mutableStateOf<com.todocompanion.app.data.entity.ActivationItemEntity?>(null) }
    TWScaffold("Behavioral activation", onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Schedule a small, values-linked activity for today — and act before motivation shows up. Afterwards, rate it for pleasure and mastery. Doing comes first; the mood follows.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(text, { text = it }, label = { Text("A small win (“10-min walk”, “call a friend”)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (values.isNotEmpty()) {
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            values.take(5).forEach { v -> FilterChip(selected = valueId == v.id, onClick = { valueId = if (valueId == v.id) null else v.id }, label = { Text((v.emoji?.plus(" ") ?: "") + v.name) }) }
                        }
                    }
                    FilledTonalButton(onClick = { vm.addActivation(text, valueId, today); text = "" }, enabled = text.isNotBlank(), modifier = Modifier.padding(top = 6.dp)) { Text("Schedule for today") }
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val todays = items.filter { it.plannedDay >= today - 1 }
                if (todays.isEmpty()) item { TWEmpty("🌤️", "Nothing scheduled", "Add a small, values-aligned activity above — the antidote to a low, flat day.") }
                items(todays.size) { i ->
                    val it = todays[i]
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(it.text, style = MaterialTheme.typography.bodyLarge, textDecoration = if (it.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                                if (it.done && (it.pleasure > 0 || it.mastery > 0)) Text("Pleasure ${it.pleasure}/5 · Mastery ${it.mastery}/5", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!it.done) TextButton(onClick = { rating = it }) { Text("Did it →") }
                            IconButton(onClick = { vm.deleteActivation(it.id) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
    rating?.let { item ->
        var pleasure by remember { mutableStateOf(3) }
        var mastery by remember { mutableStateOf(3) }
        AlertDialog(onDismissRequest = { rating = null },
            title = { Text("How was it?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoodChips("Pleasure — how enjoyable?", pleasure) { pleasure = it }
                MoodChips("Mastery — sense of accomplishment?", mastery) { mastery = it }
            } },
            confirmButton = { TextButton(onClick = { vm.rateActivation(item, pleasure, mastery); rating = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { rating = null }) { Text("Cancel") } })
    }
}

// ── TW-E · routine runner ─────────────────────────────────────────────────────────────────────────
@Composable
private fun RoutineRunnerScreen(vm: AppViewModel, onBack: () -> Unit) {
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val today = LocalDate.now().toEpochDay()
    // The run set: build habits due today, ordered by anchor chains then sort order.
    val due = remember(habits, checkins, today) {
        habits.filter { h ->
            if (h.paused || h.archived || h.habitType == "break") return@filter false
            val hc = checkins.filter { it.habitId == h.id }
            val done = hc.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            HabitStats.dueToday(h, today, done, hc.firstOrNull { it.epochDay == today }?.count ?: 0)
        }
    }
    var running by remember { mutableStateOf(false) }
    var idx by remember { mutableStateOf(0) }
    var secs by remember { mutableStateOf(0) }
    LaunchedEffect(running, idx) { if (running) { secs = 0; while (running) { kotlinx.coroutines.delay(1000); secs++ } } }
    TWScaffold("Routine runner", onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (due.isEmpty()) { TWEmpty("🎉", "Nothing due to run", "Every habit for today is already done — enjoy the free time."); return@Column }
            if (!running) {
                Text("Press play and move through today's habits one at a time — no re-deciding, just momentum. ${due.size} to go.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                due.forEachIndexed { i, h ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", Modifier.width(24.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text((h.emoji?.plus(" ") ?: "") + h.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = { idx = 0; running = true }, modifier = Modifier.fillMaxWidth()) { Text("▶  Run my routine") }
            } else {
                val h = due.getOrNull(idx)
                if (h == null) { running = false; return@Column }
                val color = h.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                Spacer(Modifier.height(20.dp))
                Text("Step ${idx + 1} of ${due.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                Box(Modifier.size(150.dp).clip(CircleShape).background(color.copy(alpha = .14f)).border(3.dp, color, CircleShape), contentAlignment = Alignment.Center) {
                    Text(h.emoji ?: "✓", fontSize = 56.sp)
                }
                Spacer(Modifier.height(16.dp))
                Text(h.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (h.cueContext.isNotBlank()) Text(h.cueContext, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${secs / 60}:${(secs % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.displaySmall, color = color, modifier = Modifier.padding(top = 12.dp))
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { if (idx < due.lastIndex) idx++ else running = false }) { Text("Skip") }
                    Button(onClick = {
                        vm.setHabitValue(h, today, h.targetPerDay.coerceAtLeast(1))
                        if (idx < due.lastIndex) idx++ else running = false
                    }) { Text("Done ✓  Next") }
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { running = false }) { Text("End run") }
            }
        }
    }
}

// ── TW-E · focus lock (escalating exit) ───────────────────────────────────────────────────────────
@Composable
private fun FocusLockScreen(vm: AppViewModel, onBack: () -> Unit) {
    var minutes by remember { mutableStateOf(25) }
    var running by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf(0) }
    var quitAttempts by remember { mutableStateOf(0) }
    var holding by remember { mutableStateOf(0) }        // seconds of exit-hold remaining
    LaunchedEffect(running) { if (running) { while (running && left > 0) { kotlinx.coroutines.delay(1000); left--; if (holding > 0) holding-- }; if (left <= 0) running = false } }
    TWScaffold("Focus lock", onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (!running) {
                Text("🔒", fontSize = 48.sp)
                Text("A self-imposed focus session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Text("Quitting early gets harder each time you try — a commitment device, entirely on your device. No block-list, no server.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 25, 45, 60).forEach { m -> FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("${m}m") }) }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { left = minutes * 60; quitAttempts = 0; holding = 0; running = true }) { Text("Start focus lock") }
            } else {
                Text("${left / 60}:${(left % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Stay with it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                if (holding > 0) {
                    Text("Reconsider… you can exit in ${holding}s", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                } else {
                    TextButton(onClick = {
                        quitAttempts++
                        holding = quitAttempts * 10   // escalating exit friction: 10s, 20s, 30s…
                    }) { Text("Give up", color = MaterialTheme.colorScheme.error) }
                    if (quitAttempts > 0) TextButton(onClick = { running = false }) { Text("Exit now") }
                }
            }
        }
    }
}

// ── TW-F · life heatmap ───────────────────────────────────────────────────────────────────────────
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LifeHeatmapScreen(vm: AppViewModel, onBack: () -> Unit) {
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val today = LocalDate.now().toEpochDay()
    val grid = remember(habits, checkins, today) { ThirdWave.compositeHeatmap(habits, checkins, today, 182) }
    val memory = remember(checkins, today) { ThirdWave.onThisDay(checkins, today) }
    val base = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)
    TWScaffold("Life heatmap", onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Your whole practice in one grid — every scheduled habit, every day, the last six months. A calm long view only a permanent, on-device ledger can keep.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            memory?.let { (y, n) -> item {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f)) {
                    Text("📅  On this day $y year${if (y == 1) "" else "s"} ago, you completed $n habit${if (n == 1) "" else "s"}. Still here.", Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            } }
            item {
                // 26 weeks × 7 days grid; each column a week.
                val days = grid.keys.toList()
                val weeks = days.chunked(7)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    weeks.forEach { wk ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            wk.forEach { d ->
                                val v = grid[d] ?: -1f
                                val c = when { v < 0f -> empty; v == 0f -> empty; else -> base.copy(alpha = (0.25f + v * 0.75f)) }
                                Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(c))
                            }
                        }
                    }
                }
            }
            item { Text("Lighter = fewer of the day's habits done; fuller = a complete day. Blank = nothing scheduled.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// ── TW-D · companion garden ───────────────────────────────────────────────────────────────────────
@Composable
private fun CompanionScreen(vm: AppViewModel, onBack: () -> Unit) {
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val settings by vm.settings.collectAsState()
    val today = LocalDate.now().toEpochDay()
    val c = remember(habits, checkins, today) { ThirdWave.companion(habits, checkins, today) }
    TWScaffold("Your garden", onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(c.emoji, fontSize = 96.sp)
            Spacer(Modifier.height(12.dp))
            Text(c.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("It grows from your consistency, and it's never shamed when you miss. No streaks, no points — just something alive that reflects showing up.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth((c.pct / 100f).coerceIn(0.02f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.primary))
            }
            if (!settings.companionEnabled) {
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = { vm.setCompanion(true) }) { Text("Show my garden on the habits screen") }
            }
        }
    }
}
