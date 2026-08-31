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
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.unit.sp
import com.todocompanion.app.data.entity.CoreValueEntity
import com.todocompanion.app.data.entity.ScorecardItemEntity
import com.todocompanion.app.domain.habit.FourthWave
import com.todocompanion.app.domain.habit.HabitStats
import com.todocompanion.app.domain.habit.LifeSystems
import com.todocompanion.app.ui.AppViewModel
import java.time.LocalDate

private val LS_COLORS = listOf(0xFF46618C, 0xFFC15B4A, 0xFF5E8C6A, 0xFF6C4FE0, 0xFFF59E0B, 0xFFEC4899, 0xFF12A594)

/**
 * R34 — the Life-Systems hub and its screens. One overlay driven by [AppViewModel.lifeSystemsRoute];
 * each route is a private, on-device view over the owned cross-module ledger. Fully offline, no LLM.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeSystemsScreen(vm: AppViewModel, route: String, onBack: () -> Unit, onOpenHabit: (String) -> Unit) {
    BackHandler(onBack = onBack)
    when (route) {
        "values" -> ValuesScreen(vm, onBack)
        "scorecard" -> ScorecardScreen(vm, onBack)
        "correlations" -> CorrelationsScreen(vm, onBack, onOpenHabit)
        "reviews" -> ReviewsScreen(vm, onBack)
        "ledger" -> IdentityLedgerScreen(vm, onBack)
        "buddies" -> BuddiesScreen(vm, onBack)
        "experiments", "activation", "heatmap", "valuestime", "runner", "companion", "focuslock" ->
            ThirdWaveScreen(vm, route, onBack, onOpenHabit)
        "loadbalancer" -> LoadBalancerScreen(vm, onBack)
        "causal" -> CausalGraphScreen(vm, onBack, onOpenHabit)
        "receptivity" -> ReceptivityScreen(vm, onBack)
        "nudgelab" -> NudgeLabScreen(vm, onBack)
        "escrow" -> EscrowScreen(vm, onBack)
        "grounding" -> GroundingScreen(vm, onBack)
        "freshstart" -> FreshStartScreen(vm, onBack)
        else -> HubScreen(vm, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LSScaffold(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit) {
    Scaffold(topBar = {
        TopAppBar(expandedHeight = 52.dp,
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = { actions() })
    }, content = content)
}

// ── Hub ───────────────────────────────────────────────────────────────────────────────────────────
@Composable
private fun HubScreen(vm: AppViewModel, onBack: () -> Unit) {
    data class Entry(val route: String, val emoji: String, val title: String, val blurb: String)
    val entries = listOf(
        Entry("values", "🧭", "Values → systems → habits", "Name what you stand for; see how the week's actions cash out each value."),
        Entry("scorecard", "📋", "Habits scorecard", "Audit a typical day — tag each behaviour good, neutral or bad."),
        Entry("correlations", "🔗", "Correlation engine", "What your own data reveals: “on days you do X, mood is +N.”"),
        Entry("experiments", "🔬", "Causal Life Lab", "Run an n-of-1 experiment — prove which habit actually moves your mood."),
        Entry("valuestime", "⚖️", "Values-time mirror", "Stated vs revealed: where your tracked hours really went."),
        Entry("activation", "🌤️", "Behavioral activation", "Schedule small, values-linked wins; rate pleasure & mastery. Act first."),
        Entry("runner", "▶️", "Routine runner", "Press play on a stack of habits and ride the momentum, step by step."),
        Entry("focuslock", "🔒", "Focus lock", "A self-imposed focus session with rising exit friction — no server."),
        Entry("heatmap", "🟩", "Life heatmap", "Your whole practice in one year-in-pixels grid, plus a memory from years past."),
        Entry("companion", "🌳", "Your garden", "A plant that grows from consistency — never shamed. A calm alternative to numbers."),
        Entry("reviews", "📆", "Weekly & annual review", "A private integrity report assembled from your ledger."),
        Entry("ledger", "🗳️", "Identity ledger", "Every vote you've cast for the person you're becoming."),
        Entry("buddies", "🤝", "Buddies", "Share a progress digest, or cheer a friend — peer-to-peer, no account."),
        // R36 · Fourth wave.
        Entry("loadbalancer", "⚖️", "Life-load balancer", "Next week's committed minutes vs your capacity — from your own tasks & habits. No external calendar."),
        Entry("causal", "🕸️", "Causal trigger graph", "Which habit, done today, most often precedes a good day tomorrow — your own lagged patterns."),
        Entry("receptivity", "📡", "Receptivity model", "When you actually act — across habits and tasks — so nudges land at your peak, not at random."),
        Entry("nudgelab", "🎲", "Nudge lab (MRT)", "A micro-randomised trial of nudge wordings on you — see which message actually gets you moving."),
        Entry("escrow", "🔐", "Self-escrow", "Pre-commit a reward or a stake, released only when you hit a real milestone. Contingency contracts, offline."),
        Entry("grounding", "🧯", "Grounding library", "A calm, offline toolkit for panic and hard urges — 5-4-3-2-1, box breathing, and more."),
        Entry("freshstart", "🌅", "Fresh-start windows", "Temporal landmarks and life transitions — the moments a reset actually sticks."),
    )
    LSScaffold("Life systems", onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("From tracking habits to engineering a life — private, permanent, and entirely on your device.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            }
            items(entries.size) { i ->
                val e = entries[i]
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().clickable { vm.lifeSystemsRoute.value = e.route }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e.emoji, fontSize = 26.sp, modifier = Modifier.padding(end = 14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(e.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ── LS5 · Values ────────────────────────────────────────────────────────────────────────────────
@Composable
private fun ValuesScreen(vm: AppViewModel, onBack: () -> Unit) {
    val values by vm.coreValues.collectAsState()
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val tasks by vm.allTasksLive.collectAsState()   // R37 · Port 9 — real work counts toward values too
    val zone = java.time.ZoneId.systemDefault()
    val today = LocalDate.now().toEpochDay()
    val weekStart = today - 6
    var editing by remember { mutableStateOf<CoreValueEntity?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    LSScaffold("Values", onBack, actions = { IconButton(onClick = { addOpen = true }) { Icon(Icons.Filled.Add, "Add value") } }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (values.isEmpty()) item {
                EmptyBlock("🧭", "Anchor habits to values", "Autonomous, self-endorsed habits are the ones that stick (SDT). Name 3–5 core values, then attach habits to them in each habit's editor.") { addOpen = true }
            }
            items(values.size) { i ->
                val v = values[i]
                val color = v.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                val attached = habits.filter { it.valueId == v.id && !it.archived }
                val habitActions = checkins.count { c -> c.status == "done" && c.epochDay in weekStart..today && attached.any { it.id == c.habitId } }
                val taskActions = tasks.count { t -> t.valueId == v.id && t.completed && t.completedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() in weekStart..today } == true }
                val weekActions = habitActions + taskActions
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().clickable { editing = v }) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
                            Spacer(Modifier.width(10.dp))
                            Text((v.emoji?.plus(" ") ?: "") + v.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("$weekActions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
                        }
                        if (v.statement.isNotBlank()) Text("“${v.statement}”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        Text(
                            if (attached.isEmpty()) "No habits attached yet — pick this value in a habit's editor."
                            else "$weekActions action${if (weekActions == 1) "" else "s"} this week toward it · ${attached.joinToString(", ") { it.name }}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
    if (addOpen) ValueEditor(null, onDismiss = { addOpen = false }, onSave = { n, e, c, s -> vm.saveValue(null, n, e, c, s); addOpen = false })
    editing?.let { v ->
        ValueEditor(v, onDismiss = { editing = null },
            onSave = { n, e, c, s -> vm.saveValue(v.id, n, e, c, s); editing = null },
            onDelete = { vm.deleteValue(v.id); editing = null })
    }
}

@Composable
private fun ValueEditor(v: CoreValueEntity?, onDismiss: () -> Unit, onSave: (String, String?, Long?, String) -> Unit, onDelete: (() -> Unit)? = null) {
    var name by remember { mutableStateOf(v?.name ?: "") }
    var emoji by remember { mutableStateOf(v?.emoji ?: "") }
    var statement by remember { mutableStateOf(v?.statement ?: "") }
    var color by remember { mutableStateOf(v?.colorArgb ?: LS_COLORS.first()) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (v == null) "New value" else "Edit value") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name (Health, Craft, Family…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(emoji, { emoji = it.takeLast(2) }, label = { Text("Emoji (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(statement, { statement = it }, label = { Text("“I am someone who…”") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Colour", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    com.todocompanion.app.ui.components.AppColorPicker(current = color, onPick = { color = it ?: color })
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name, emoji.ifBlank { null }, color, statement) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        })
}

// ── Habit scorecard ───────────────────────────────────────────────────────────────────────────────
@Composable
private fun ScorecardScreen(vm: AppViewModel, onBack: () -> Unit) {
    val items by vm.scorecardItems.collectAsState()
    var text by remember { mutableStateOf("") }
    LSScaffold("Habits scorecard", onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("List a typical day's behaviours — no judgement yet. Then tag each good (＋), neutral (=) or bad (－). You can't change what you're not aware of.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(text, { text = it }, label = { Text("A behaviour (“scroll in bed”, “morning walk”)") }, singleLine = true, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(onClick = { vm.addScorecardItem(text, 0); text = "" }, enabled = text.isNotBlank()) { Text("Add") }
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) item { EmptyBlock("📋", "Your day, honestly", "Add the things you actually do — brushing teeth, checking the phone, a walk. Awareness is the precondition for change.", null) }
                items(items.size) { i ->
                    val it = items[i]
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(it.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                IconButton(onClick = { vm.deleteScorecardItem(it.id) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SignChip("＋ good", it.sign == 1, Color(0xFF5E8C6A)) { vm.setScorecardSign(it, 1) }
                                SignChip("= neutral", it.sign == 0, MaterialTheme.colorScheme.outline) { vm.setScorecardSign(it, 0) }
                                SignChip("－ bad", it.sign == -1, Color(0xFFC15B4A)) { vm.setScorecardSign(it, -1) }
                                if (it.sign != 0) {
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { vm.scorecardToHabit(it) }) { Text(if (it.sign > 0) "Build →" else "Break →") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) },
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = color.copy(alpha = .22f), selectedLabelColor = color))
}

// ── LS8 · Correlation engine ────────────────────────────────────────────────────────────────────
@Composable
private fun CorrelationsScreen(vm: AppViewModel, onBack: () -> Unit, onOpenHabit: (String) -> Unit) {
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val today = LocalDate.now().toEpochDay()
    val corr = remember(habits, checkins, tasks, today) { LifeSystems.correlations(habits, checkins, tasks, today) }
    val keystone = remember(corr) { LifeSystems.keystone(corr) }
    LSScaffold("Correlation engine", onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Rule-based, min-sample-guarded links across your own habits, mood, energy and tasks. Correlation, not proof — but it's yours, and no one else could compute it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (keystone != null) item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().clickable { onOpenHabit(keystone.id) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("KEYSTONE HABIT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text((keystone.emoji?.plus(" ") ?: "") + keystone.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Its good days lift the most across everything else. Protect this one.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            if (corr.isEmpty()) item {
                EmptyBlock("🔗", "Not enough data yet", "Keep logging — and add an energy/mood tag when you check a habit off (on the habit's page). Once there's enough signal, the links appear here.", null)
            }
            items(corr.size) { i ->
                val c = corr[i]
                val color = if (c.positive) Color(0xFF5E8C6A) else Color(0xFFC15B4A)
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().clickable { onOpenHabit(c.habit.id) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text("On days you do ${c.habit.emoji?.plus(" ") ?: ""}${c.habit.name}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("${c.signal} is ${if (c.positive) "+" else ""}${String.format("%.1f", c.delta)} ${unitFor(c.signal)}",
                            style = MaterialTheme.typography.bodyLarge, color = color, fontWeight = FontWeight.SemiBold)
                        Text("${c.onValue.let { String.format("%.1f", it) }} on vs ${c.offValue.let { String.format("%.1f", it) }} off · ${c.nOn}/${c.nOff} days",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun unitFor(signal: String) = when (signal) { "mood", "energy" -> "/5"; else -> "" }

// ── LS6 · Weekly & annual review ──────────────────────────────────────────────────────────────────
@Composable
private fun ReviewsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val values by vm.coreValues.collectAsState()
    val saved by vm.integrityReviews.collectAsState()
    val today = LocalDate.now()
    val td = today.toEpochDay()
    var kind by remember { mutableStateOf("weekly") }
    val (startDay, label, periodKey) = remember(kind, td) {
        if (kind == "weekly") Triple(td - 6, "This week", "${today.year}-W${today.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())}")
        else Triple(today.withDayOfYear(1).toEpochDay(), "${today.year} in review", "${today.year}")
    }
    val review = remember(kind, habits, checkins, tasks, values, td) {
        LifeSystems.review(kind, label, startDay, td, habits, checkins, tasks, values)
    }
    var note by remember(kind) { mutableStateOf("") }
    LSScaffold("Integrity review", onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == "weekly", { kind = "weekly" }, { Text("Weekly") })
                    FilterChip(kind == "annual", { kind = "annual" }, { Text("Annual") })
                }
            }
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                    Column(Modifier.padding(16.dp)) {
                        Text(review.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        StatRow("Completions", "${review.completions}")
                        StatRow("Habits kept active", "${review.activeHabits}")
                        if (review.bestStreakName != null) StatRow("Best streak", "${review.bestStreak} · ${review.bestStreakName}")
                        if (review.keystoneName != null) StatRow("Keystone", review.keystoneName!!)
                        if (review.automaticityGainName != null) StatRow("Most repetitions", "${review.automaticityGain} · ${review.automaticityGainName}")
                        if (review.values.any { it.actions > 0 }) {
                            Spacer(Modifier.height(6.dp)); HorizontalDivider(); Spacer(Modifier.height(6.dp))
                            Text("LIVING YOUR VALUES", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            review.values.filter { it.actions > 0 }.forEach { StatRow("${it.emoji?.plus(" ") ?: ""}${it.name}", "${it.actions}") }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(note, { note = it }, label = { Text("Reflection — what worked, what's next?") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Button(onClick = { vm.saveIntegrityReview(kind, periodKey, note, "") ; note = "" }, enabled = note.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save to my ledger") }
            }
            if (saved.isNotEmpty()) {
                item { Text("PAST REVIEWS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
                items(saved.size) { i ->
                    val r = saved[i]
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${r.kind.replaceFirstChar { it.uppercase() }} · ${r.periodKey}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodyMedium)
                            }
                            IconButton(onClick = { vm.deleteIntegrityReview(r.id) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

// ── LS3 · Identity ledger ─────────────────────────────────────────────────────────────────────────
@Composable
private fun IdentityLedgerScreen(vm: AppViewModel, onBack: () -> Unit) {
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val ledger = remember(habits, checkins) { LifeSystems.identityLedger(habits, checkins) }
    LSScaffold("Identity ledger", onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Every completion is a vote for the person you're becoming. This is your permanent, append-only tally — years of evidence that only a private, on-device ledger can keep.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ledger.isEmpty()) item {
                EmptyBlock("🗳️", "No votes cast yet", "Give a habit an identity (“I'm a runner”) in its editor. Each time you do it, a vote lands here.", null)
            }
            items(ledger.size) { i ->
                val t = ledger[i]
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("“${t.identity}”", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("${t.votes}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Text("votes" + (t.sinceDay?.let { " since ${LocalDate.ofEpochDay(it)}" } ?: "") + " · " + t.habitNames.joinToString(", "),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ── Buddies ───────────────────────────────────────────────────────────────────────────────────────
@Composable
private fun BuddiesScreen(vm: AppViewModel, onBack: () -> Unit) {
    val buddies by vm.buddies.collectAsState()
    val ctx = LocalContext.current
    var importOpen by remember { mutableStateOf(false) }
    LSScaffold("Buddies", onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Accountability without an account. Share a compact digest of your streaks; import a friend's to cheer them on. Nothing leaves except the file you choose to send.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val json = vm.exportBuddyDigest("Me")
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, json)
                        }
                        runCatching { ctx.startActivity(android.content.Intent.createChooser(send, "Share my progress digest")) }
                    }, modifier = Modifier.weight(1f)) { Text("Share my digest") }
                    FilledTonalButton(onClick = { importOpen = true }, modifier = Modifier.weight(1f)) { Text("Import a buddy") }
                }
            }
            if (buddies.isEmpty()) item { EmptyBlock("🤝", "No buddies yet", "Import a friend's shared digest to see their streaks here — a quiet, private cheer-squad.", null) }
            items(buddies.size) { i ->
                val b = buddies[i]
                val digest = remember(b.payloadJson) { runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(LifeSystems.BuddyDigest.serializer(), b.payloadJson) }.getOrNull() }
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🤝 ${b.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.deleteBuddy(b.id) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        digest?.habits?.take(8)?.forEach { h ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text((h.emoji?.plus(" ") ?: "") + h.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("🔥${h.streak} · ${h.strength}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    if (importOpen) {
        var pasted by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { importOpen = false },
            title = { Text("Import a buddy digest") },
            text = { Column { Text("Paste the digest text your friend shared:", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(8.dp)); OutlinedTextField(pasted, { pasted = it }, modifier = Modifier.fillMaxWidth().height(140.dp)) } },
            confirmButton = { TextButton(onClick = { vm.importBuddyDigest(pasted); importOpen = false }) { Text("Import") } },
            dismissButton = { TextButton(onClick = { importOpen = false }) { Text("Cancel") } })
    }
}

@Composable
private fun EmptyBlock(emoji: String, title: String, body: String, onAdd: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp, start = 8.dp, end = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 44.sp)
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        if (onAdd != null) { Spacer(Modifier.height(10.dp)); FilledTonalButton(onClick = onAdd) { Text("Get started") } }
    }
}

// ══════════════════════════════ R36 · Fourth-wave screens ══════════════════════════════

@Composable
private fun FWCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun FWBar(fraction: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(9.dp).clip(RoundedCornerShape(5.dp)).background(color))
    }
}

private fun hrsMin(min: Int): String = if (min < 60) "${min}m" else "${min / 60}h${if (min % 60 == 0) "" else " ${min % 60}m"}"

// FW-15 · Life-load balancer.
@Composable
private fun LoadBalancerScreen(vm: AppViewModel, onBack: () -> Unit) {
    val tasks by vm.tasks.collectAsState()
    val habits by vm.habits.collectAsState()
    val settings by vm.settings.collectAsState()
    val today = LocalDate.now().toEpochDay()
    val forecast = remember(tasks, habits, settings) { FourthWave.lifeLoadForecast(tasks, habits, settings, today, 7) }
    LSScaffold("Life-load balancer", onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                FWCard {
                    Text("Next 7 days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(forecast.advice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    Text("Load is your own task estimates + scheduled habit minutes — no external calendar is ever read.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            items(forecast.days.size) { i ->
                val dl = forecast.days[i]
                val date = LocalDate.ofEpochDay(dl.day)
                val cap = settings.capacityHoursFor(date.dayOfWeek) * 60
                val over = dl.day in forecast.overloaded
                val color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                FWCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) + " " + date.dayOfMonth,
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${hrsMin(dl.total)} / ${hrsMin(cap)}", style = MaterialTheme.typography.labelLarge, color = color)
                    }
                    Spacer(Modifier.height(6.dp))
                    FWBar(if (cap == 0) 0f else dl.total.toFloat() / cap, color)
                    if (dl.taskMin > 0 || dl.habitMin > 0) Text(
                        "Tasks ${hrsMin(dl.taskMin)} · Habits ${hrsMin(dl.habitMin)}" + if (over) " · over capacity" else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

// FW-13 · Causal trigger graph.
@Composable
private fun CausalGraphScreen(vm: AppViewModel, onBack: () -> Unit, onOpenHabit: (String) -> Unit) {
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val tasks by vm.allTasksLive.collectAsState()
    val today = LocalDate.now().toEpochDay()
    val edges = remember(habits, checkins) { FourthWave.causalPrecursors(habits, checkins, today) }
    val outEdges = remember(habits, checkins, tasks) { FourthWave.causalOutput(habits, checkins, tasks, today) }
    LSScaffold("Causal trigger graph", onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (edges.isEmpty()) item {
                EmptyBlock("🕸️", "Not enough signal yet", "Log a daily mood on your check-ins for a couple of weeks. Then this shows which habit, done today, most often precedes a good day tomorrow — your own lagged patterns. Correlation, not proof.", null)
            } else item {
                Text("On days after you did these, your next-day mood was better than usual. Suggestive, not proof — a lead to test in the Causal Life Lab.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            items(edges.size) { i ->
                val e = edges[i]
                val pctMore = ((e.lift - 1.0) * 100).toInt()
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().clickable { onOpenHabit(e.habitId) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e.emoji, fontSize = 24.sp, modifier = Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.habitName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("→ a good day follows ${pctMore}% more often (n=${e.nWith})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("×${"%.2f".format(e.lift)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            // R37 · Port 7 — the same lift, but the outcome is a high-OUTPUT day (task completions).
            if (outEdges.isNotEmpty()) {
                item {
                    Text("→ Days you get more done", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                    Text("On days after these, you finished more tasks than your median. Same caveat — a lead, not proof.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                }
                items(outEdges.size) { i ->
                    val e = outEdges[i]
                    val pctMore = ((e.lift - 1.0) * 100).toInt()
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().clickable { onOpenHabit(e.habitId) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(e.emoji, fontSize = 24.sp, modifier = Modifier.padding(end = 12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.habitName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("→ a high-output day follows ${pctMore}% more often (n=${e.nWith})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("×${"%.2f".format(e.lift)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }
    }
}

// FW-16 · Cross-domain receptivity model.
@Composable
private fun ReceptivityScreen(vm: AppViewModel, onBack: () -> Unit) {
    val checkins by vm.habitCheckins.collectAsState()
    val tasks by vm.allTasksLive.collectAsState()
    val rec = remember(checkins, tasks) { FourthWave.receptivity(checkins, tasks) }
    LSScaffold("Receptivity model", onBack) { pad ->
        if (rec == null) {
            Column(Modifier.padding(pad).fillMaxSize()) {
                EmptyBlock("📡", "Learning your rhythm", "Once you've checked in habits and finished tasks a handful of times, this learns the hours and weekdays you actually act — so reminders can aim at your peak, not a random time.", null)
            }
            return@LSScaffold
        }
        val maxB = (rec.byBucket.maxOrNull() ?: 1).coerceAtLeast(1)
        val maxD = ((1..7).maxOf { rec.byDow[it] }).coerceAtLeast(1)
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                FWCard {
                    Text("You're most receptive around", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${rec.bucketLabel(rec.bestBucket)} · ${java.time.DayOfWeek.of(rec.bestDow).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())}s",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Learned from ${rec.n} completed habits & tasks. Aim your hardest habit and any nudges at this window.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item {
                FWCard {
                    Text("By time of day", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    for (b in 0..7) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                            Text(rec.bucketLabel(b), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(78.dp))
                            Box(Modifier.weight(1f)) { FWBar(rec.byBucket[b].toFloat() / maxB, if (b == rec.bestBucket) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary) }
                            Text(" ${rec.byBucket[b]}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(28.dp))
                        }
                    }
                }
            }
            item {
                FWCard {
                    Text("By weekday", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    for (d in 1..7) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                            Text(rec.dowLabel(d), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(78.dp))
                            Box(Modifier.weight(1f)) { FWBar(rec.byDow[d].toFloat() / maxD, if (d == rec.bestDow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary) }
                            Text(" ${rec.byDow[d]}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(28.dp))
                        }
                    }
                }
            }
        }
    }
}

// FW-14 · Nudge lab (Personal Nudge MRT).
@Composable
private fun NudgeLabScreen(vm: AppViewModel, onBack: () -> Unit) {
    val events by vm.nudgeEvents.collectAsState()
    val readout = remember(events) { FourthWave.nudgeMrtReadout(events) }
    LSScaffold("Nudge lab", onBack) { pad ->
        if (readout == null) {
            Column(Modifier.padding(pad).fillMaxSize()) {
                EmptyBlock("🎲", "No trials yet", "When a habit is due at your usual time, Today shows an opportunity nudge with a randomly-chosen wording. Over time this reads out which message actually gets you to act — a single-case randomised trial, run only on you.", null)
            }
            return@LSScaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("${readout.totalShown} nudge${if (readout.totalShown == 1) "" else "s"} shown so far, across habit prompts and task reminders. Each was a random pick between these wordings — here's how often you acted after each.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            items(readout.variants.size) { i ->
                val v = readout.variants[i]
                val best = v.variant == readout.bestVariant && v.shown >= 3
                FWCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("“${v.label}”", style = MaterialTheme.typography.bodyMedium, fontWeight = if (best) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                        if (best) Text("★ best", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(8.dp))
                    FWBar(v.rate / 100f, if (best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                    Text(if (v.shown == 0) "not shown yet" else "${v.rate}% acted · ${v.acted}/${v.shown}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

// FW-9 · Self-escrow.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EscrowScreen(vm: AppViewModel, onBack: () -> Unit) {
    val escrows by vm.escrows.collectAsState()
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val today = LocalDate.now().toEpochDay()
    var addOpen by remember { mutableStateOf(false) }
    LSScaffold("Self-escrow", onBack, actions = { IconButton(onClick = { addOpen = true }) { Icon(Icons.Filled.Add, "New escrow") } }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (escrows.isEmpty()) item {
                EmptyBlock("🔐", "Pre-commit to a milestone", "Lock a reward you'll only unlock at a real target (a 30-day streak, 90 clean days, 80% automatic) — or a stake you forfeit if you don't. Commitment devices beat willpower. Nothing leaves your device.") { addOpen = true }
            }
            items(escrows.size) { i ->
                val e = escrows[i]
                val st = FourthWave.escrowStatus(e, habits, checkins, today)
                val habitName = habits.firstOrNull { it.id == e.habitId }?.name
                val kindLabel = when (e.milestoneKind) { "streak" -> "day streak"; "cleandays" -> "clean days"; else -> "% automatic" }
                FWCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (e.kind == "stake") "🎯" else "🎁", fontSize = 22.sp, modifier = Modifier.padding(end = 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.description, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text((if (e.kind == "stake") "Stake · " else "Reward · ") + "at ${e.milestoneValue} $kindLabel" + (habitName?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.deleteEscrow(e.id) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (!e.released) {
                        Spacer(Modifier.height(8.dp))
                        FWBar(st.pct / 100f, if (st.reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${st.current} / ${st.target} — ${st.pct}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            if (st.reached) {
                                if (e.kind == "stake") {
                                    TextButton(onClick = { vm.releaseEscrow(e, false) }) { Text("I made it") }
                                } else {
                                    TextButton(onClick = { vm.releaseEscrow(e, false) }) { Text("Bank") }
                                    Button(onClick = { vm.releaseEscrow(e, true) }) { Text("Claim") }
                                }
                            }
                        }
                        if (st.reached && e.kind == "stake") TextButton(onClick = { vm.releaseEscrow(e, true) }) { Text("Missed — pay the stake", color = MaterialTheme.colorScheme.error) }
                    } else {
                        Text(when {
                            e.kind == "stake" && e.redeemed -> "Stake paid."
                            e.kind == "stake" -> "Cleared — you made it. 🎉"
                            e.redeemed -> "Claimed. 🎉"
                            else -> "Banked."
                        }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
    if (addOpen) EscrowAddDialog(vm, habits, onClose = { addOpen = false })
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EscrowAddDialog(vm: AppViewModel, habits: List<com.todocompanion.app.data.entity.HabitEntity>, onClose: () -> Unit) {
    var desc by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("reward") }
    var mKind by remember { mutableStateOf("streak") }
    var value by remember { mutableStateOf("30") }
    var habitId by remember { mutableStateOf<String?>(null) }
    val buildHabits = habits.filter { !it.archived && it.habitType != "break" }
    val breakHabits = habits.filter { !it.archived && it.habitType == "break" }
    AlertDialog(onDismissRequest = onClose,
        title = { Text("New escrow") },
        text = {
            Column {
                OutlinedTextField(desc, { desc = it }, label = { Text("Reward or stake") }, placeholder = { Text("e.g. new headphones") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(10.dp))
                Text("Type", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == "reward", { kind = "reward" }, label = { Text("Reward") })
                    FilterChip(kind == "stake", { kind = "stake" }, label = { Text("Stake") })
                }
                Spacer(Modifier.height(10.dp))
                Text("Milestone", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(mKind == "streak", { mKind = "streak"; value = "30" }, label = { Text("Streak") })
                    FilterChip(mKind == "cleandays", { mKind = "cleandays"; value = "90" }, label = { Text("Clean days") })
                    FilterChip(mKind == "automaticity", { mKind = "automaticity"; value = "80" }, label = { Text("Automatic %") })
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value, { v -> value = v.filter { it.isDigit() }.take(4) }, label = { Text("Target") }, modifier = Modifier.width(140.dp), singleLine = true)
                val linkable = if (mKind == "cleandays") breakHabits else buildHabits
                if (linkable.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Track on habit (optional)", style = MaterialTheme.typography.labelMedium)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(habitId == null, { habitId = null }, label = { Text("None") })
                        linkable.take(8).forEach { h -> FilterChip(habitId == h.id, { habitId = h.id }, label = { Text(h.name, maxLines = 1) }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = desc.isNotBlank(), onClick = {
            vm.addEscrow(habitId, desc, kind, mKind, value.toIntOrNull() ?: 1); onClose()
        }) { Text("Lock it") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

// FW-10 · Grounding library.
@Composable
private fun GroundingScreen(vm: AppViewModel, onBack: () -> Unit) {
    val techniques = remember { FourthWave.groundingTechniques() }
    LSScaffold("Grounding library", onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("For the hard moments — panic, a spike of craving, overwhelm. Pick any one and take it slowly. All offline, always here.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            items(techniques.size) { i ->
                val g = techniques[i]
                FWCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g.emoji, fontSize = 24.sp, modifier = Modifier.padding(end = 12.dp))
                        Text(g.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Text(g.steps, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

// FW-11/FW-12 · Fresh-start windows (temporal landmarks + transition detector).
@Composable
private fun FreshStartScreen(vm: AppViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val today = LocalDate.now().toEpochDay()
    val landmark = remember(today) { FourthWave.temporalLandmark(today) }
    val transition = remember(settings, today) { FourthWave.transitionWindow(settings, today) }
    var declareOpen by remember { mutableStateOf(false) }
    LSScaffold("Fresh-start windows", onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("People re-commit more readily at boundaries — a new week, a new month, a life change. These windows are when a reset actually sticks.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            landmark?.let { lm -> item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(lm.emoji, fontSize = 26.sp, modifier = Modifier.padding(end = 12.dp))
                        Text(lm.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            } }
            item {
                FWCard {
                    Text("Life transition", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (transition != null) {
                        Text(transition.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        Spacer(Modifier.height(8.dp))
                        FWBar(transition.dayOfWindow.toFloat() / transition.windowDays, MaterialTheme.colorScheme.primary)
                        Text("Day ${transition.dayOfWindow} of a ${transition.windowDays}-day reset window", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.padding(top = 6.dp)) {
                            TextButton(onClick = { declareOpen = true }) { Text("Edit") }
                            TextButton(onClick = { vm.clearTransition() }) { Text("Clear") }
                        }
                    } else if (settings.transitionLabel.isNotBlank()) {
                        Text("“${settings.transitionLabel}” — its reset window has passed. Habits chosen during it are the ones to keep.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.padding(top = 6.dp)) {
                            TextButton(onClick = { declareOpen = true }) { Text("Declare a new one") }
                            TextButton(onClick = { vm.clearTransition() }) { Text("Clear") }
                        }
                    } else {
                        Text("Starting a new job, a move, a term, becoming a parent? Declaring it opens a 3-week window where re-choosing routines is far more likely to hold.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = { declareOpen = true }) { Text("Declare a transition") }
                    }
                }
            }
        }
    }
    if (declareOpen) {
        var label by remember { mutableStateOf(settings.transitionLabel) }
        AlertDialog(onDismissRequest = { declareOpen = false },
            title = { Text("Declare a transition") },
            text = {
                Column {
                    Text("Name the change. A 3-week fresh-start window opens from today.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(label, { label = it }, placeholder = { Text("e.g. New job, Moved cities") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            },
            confirmButton = { TextButton(enabled = label.isNotBlank(), onClick = { vm.setTransition(label, today); declareOpen = false }) { Text("Open window") } },
            dismissButton = { TextButton(onClick = { declareOpen = false }) { Text("Cancel") } })
    }
}
