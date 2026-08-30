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
        Entry("reviews", "📆", "Weekly & annual review", "A private integrity report assembled from your ledger."),
        Entry("ledger", "🗳️", "Identity ledger", "Every vote you've cast for the person you're becoming."),
        Entry("buddies", "🤝", "Buddies", "Share a progress digest, or cheer a friend — peer-to-peer, no account."),
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
                val weekActions = checkins.count { c -> c.status == "done" && c.epochDay in weekStart..today && attached.any { it.id == c.habitId } }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LS_COLORS.forEach { c ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                            .border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { color = c })
                    }
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
