package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.todocompanion.app.domain.Goal
import com.todocompanion.app.domain.GoalMilestone
import com.todocompanion.app.domain.GoalScore
import com.todocompanion.app.domain.GoalTemplate
import com.todocompanion.app.domain.Goals
import com.todocompanion.app.domain.KeyResult
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import com.todocompanion.app.ui.components.DateOnlyPickerDialog
import com.todocompanion.app.ui.components.DoneTick
import com.todocompanion.app.ui.components.EmojiGridPicker
import com.todocompanion.app.ui.components.OptionChips
import com.todocompanion.app.ui.components.Stepper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

// ── small shared helpers ──────────────────────────────────────────────────────────────────────────
private fun goalToday() = LocalDate.now().toEpochDay()
private fun blankGoal() = Goal(id = UUID.randomUUID().toString(), name = "")
private fun epochDayToMillis(d: Long) = d * 86_400_000L
private fun millisToEpochDay(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
private fun dayLabel(d: Long): String = LocalDate.ofEpochDay(d).let { "${it.dayOfMonth} ${it.month.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }}" }
private fun fmtH(m: Int): String = if (m % 60 == 0) "${m / 60}h" else "${m / 60}h ${m % 60}m"
private fun cadenceLabel(days: Int) = when (days) { 1 -> "Daily"; 7 -> "Weekly"; 14 -> "Fortnightly"; 30 -> "Monthly"; else -> "${days}d" }

private fun templateToGoal(t: GoalTemplate) = Goal(
    id = UUID.randomUUID().toString(),
    name = t.name, emoji = t.emoji, note = t.note, identity = t.identity,
    budgetMinutes = t.budgetHours * 60,
    keyResults = t.keyResults.map { KeyResult(id = UUID.randomUUID().toString(), title = it) },
    milestones = t.milestones.map { GoalMilestone(id = UUID.randomUUID().toString(), title = it) },
)

/** A thin, rounded progress track — the one goal-progress bar idiom used across the screen. */
@Composable
private fun ProgressTrack(fraction: Float, modifier: Modifier = Modifier, height: Int = 8, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    Box(modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape((height / 2).dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape((height / 2).dp)).background(color))
    }
}

/** A compact execution sparkline (bars) for the scoreboard trend. */
@Composable
private fun Sparkline(values: List<Int>, modifier: Modifier = Modifier) {
    if (values.isEmpty()) return
    Row(modifier.height(28.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        values.forEach { v ->
            val h = (4 + (v.coerceIn(0, 100) / 100f) * 24f)
            Box(Modifier.width(7.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .35f + .5f * (v / 100f))))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════════════════════
//  The Goals surface — a dedicated screen for the expert goal system: WIGs with lead/lag measures,
//  milestones, OKR key results, GTD areas, 12-week cycles, a review cadence + scoreboard, and the
//  integrity chain. Everything is read from tasks + habits + tracked time in one store (the moat).
// ═══════════════════════════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val settings by vm.settings.collectAsState()
    val goals = remember(settings.goalsJson) { vm.goals().filter { !it.archived } }
    val reviews = remember(settings.goalReviewsJson) { vm.goalReviews() }
    val today = goalToday()

    var editing by remember { mutableStateOf<Goal?>(null) }
    var detailFor by remember { mutableStateOf<String?>(null) }
    var reviewScope by remember { mutableStateOf<String?>(null) }   // "" = portfolio, or a goal id
    var browse by remember { mutableStateOf(false) }

    val areas = remember(goals) { Goals.areasOf(goals) }
    // Group: each named area in first-seen order, then the unfiled bucket last.
    val grouped = remember(goals, areas) {
        buildList {
            areas.forEach { a -> add(a to goals.filter { it.area.trim() == a }) }
            val unfiled = goals.filter { it.area.isBlank() }
            if (unfiled.isNotEmpty()) add("" to unfiled)
        }
    }

    Scaffold(topBar = {
        TopAppBar(expandedHeight = 52.dp,
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text("Goals", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = { IconButton(onClick = { editing = blankGoal() }) { Icon(Icons.Filled.Add, "New goal") } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("A goal is one objective carried by three arms at once — the tasks that finish it, the habit that practises it, and the hours you invest. Kairo reads its health from what you actually did.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (goals.isNotEmpty()) item {
                PortfolioHeader(vm, goals, reviews, today, onReview = { reviewScope = "" })
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { editing = blankGoal() }, modifier = Modifier.weight(1f)) { Text("＋ New goal") }
                    FilledTonalButton(onClick = { browse = true }, modifier = Modifier.weight(1f)) { Text("Browse templates") }
                }
            }
            if (goals.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 34.dp, start = 8.dp, end = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎯", fontSize = 44.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("No goals yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Start from a template — run a 5K, ship a side project, learn a language — or build your own. Bind a task list, a habit and a time budget, then review it weekly.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = { browse = true }) { Text("Browse goal templates") }
                }
            }
            grouped.forEach { (area, gs) ->
                if (grouped.size > 1 || area.isNotBlank()) item(key = "area_$area") {
                    Text(if (area.isBlank()) "UNFILED" else area.uppercase(),
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
                }
                items(gs.size, key = { gs[it].id }) { i ->
                    GoalRow(vm, gs[i], reviews, today, onOpen = { detailFor = gs[i].id })
                }
            }
        }
    }

    editing?.let { g ->
        val existing = goals.any { it.id == g.id }
        GoalEditorScreen(vm, g, existing,
            onDismiss = { editing = null },
            onSave = { vm.upsertGoal(it); editing = null },
            onDelete = { vm.deleteGoal(g.id); editing = null })
    }
    detailFor?.let { id ->
        val g = vm.goals().firstOrNull { it.id == id }
        if (g == null) detailFor = null
        else GoalDetailScreen(vm, g, onBack = { detailFor = null }, onEdit = { detailFor = null; editing = g }, onReview = { reviewScope = g.id })
    }
    reviewScope?.let { scope ->
        WeeklyReviewDialog(vm, scope, goals, onDismiss = { reviewScope = null })
    }
    if (browse) GoalTemplatesDialog(onDismiss = { browse = false }, onPick = { editing = templateToGoal(it); browse = false })
}

/** 0.4 / moat #5 — the portfolio scoreboard: overall health, integrity chain, and the review nudge. */
@Composable
private fun PortfolioHeader(vm: AppViewModel, goals: List<Goal>, reviews: List<com.todocompanion.app.domain.GoalReview>, today: Long, onReview: () -> Unit) {
    // Live-key the portfolio % so a completed task / tracked minute refreshes it (matching GoalRow).
    val tasks by vm.tasks.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val timeEntries by vm.timeEntries.collectAsState()
    val overall = remember(goals, tasks, checkins, timeEntries) { if (goals.isEmpty()) 0.0 else goals.map { vm.goalHealth(it).overall }.average() }
    val chain = remember(reviews) { GoalScore.integrityChain(reviews, 7, today, "") }
    val due = remember(reviews) { GoalScore.reviewDue(reviews, 7, today, "") }
    val trend = remember(reviews) { GoalScore.executionTrend(reviews, "", 10) }
    // Contention (goals fighting for the same tracked hours) + aggregate over-commit (many goals each in
    // budget but summing past your real weekly focus) — surfaced here, on the canonical Goals surface.
    val contention = remember(goals, timeEntries) { vm.goalContention() }
    val overCommit = remember(goals, timeEntries, today) {
        // Completed-cycle goals inflate their weekly need (weeksLeft floors to ~0.5), so exclude them from the
        // aggregate — otherwise the portfolio reads "over budget" purely because a cycle finished.
        val caps = goals.filter { GoalScore.cycle(it, today)?.complete != true }.mapNotNull { vm.goalCapacity(it) }
        val need = caps.sumOf { it.weeklyNeedH }; val have = caps.firstOrNull()?.weeklyHaveH ?: 0.0
        if (have > 0.0 && need > have + 0.5) "⚠︎ Goals want ~${"%.1f".format(need)}h/wk vs ~${"%.1f".format(have)}h of real focus — the whole set may be over budget." else null
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Portfolio", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Text("${(overall * 100).toInt()}% overall", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            if (chain > 0) Column(horizontalAlignment = Alignment.End) {
                Text("🔗 $chain", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("review streak", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        ProgressTrack((overall).toFloat())
        if (trend.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Sparkline(trend, Modifier.weight(1f))
                GoalScore.avgExecution(reviews, "", 10)?.let { Text("avg $it%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (due) Text("A weekly review is due.", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            else Text("Reviewed recently — nice.", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = onReview) { Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Weekly review") }
        }
        overCommit?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        contention.forEach { Text("⚠︎ $it", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** One goal in the list — the at-a-glance card with health, lead/lag, cycle, milestones and coach. */
@Composable
private fun GoalRow(vm: AppViewModel, g: Goal, reviews: List<com.todocompanion.app.domain.GoalReview>, today: Long, onOpen: () -> Unit) {
    // Key health/capacity on the live stores so completing a task or tracking time refreshes the card
    // (keying on `g` alone left it stale until the goal JSON itself changed).
    val tasks by vm.tasks.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val timeEntries by vm.timeEntries.collectAsState()
    val h = remember(g, tasks, checkins, timeEntries) { vm.goalHealth(g) }
    val cycle = remember(g, today) { GoalScore.cycle(g, today) }
    val cap = remember(g, timeEntries) { vm.goalCapacity(g) }
    val coach = remember(g, h) { vm.goalCoaching(g) }
    val keystone = remember(checkins) { vm.keystoneHabitId() }   // the one habit whose data most predicts good days
    AppCard(modifier = Modifier.clickable { onOpen() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(g.emoji, fontSize = 24.sp, modifier = Modifier.padding(end = 10.dp))
            Column(Modifier.weight(1f)) {
                Text(g.name.ifBlank { "Untitled goal" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (g.identity.isNotBlank()) Text("Becoming ${g.identity}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${(h.overall * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(6.dp))
        ProgressTrack(h.overall.toFloat())
        Spacer(Modifier.height(6.dp))
        // Lead vs lag chips — the language of execution.
        val leadBits = buildList {
            if (g.hasHabit) add((if (g.habitId.isNotBlank() && g.habitId == keystone) "🗝️ " else "") + "↻ ${h.habitStrength}% · ${h.habitStreak}d")
            if (g.hasBudget) add("⏱ ${fmtH(h.minutesTracked)}/${fmtH(h.budgetMin)}")
        }
        val lagBits = buildList {
            if (g.hasTasks) add("✓ ${h.taskDone}/${h.taskTotal}")
            g.keyResultFraction?.let { add("◎ ${(it * 100).toInt()}% KR") }
            if (g.milestones.isNotEmpty()) add("⚑ ${g.milestonesDone}/${g.milestones.size}")
        }
        if (leadBits.isNotEmpty()) Text("Lead   " + leadBits.joinToString("   "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (lagBits.isNotEmpty()) Text("Lag    " + lagBits.joinToString("   "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Cycle badge + capacity warning.
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            cycle?.let {
                if (it.complete) {
                    Text("${it.totalWeeks}-week cycle complete · ${(h.overall * 100).toInt()}% — time to review & re-set",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                } else {
                    val on = GoalScore.onTrack(h.overall, it.elapsedFraction)
                    Text("Week ${it.weekIndex}/${it.totalWeeks} · ${it.daysLeft}d left · ${if (on) "on track" else "behind"}",
                        style = MaterialTheme.typography.labelSmall, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
            }
            h.daysLeft?.let {
                if (cycle == null) Spacer(Modifier.weight(1f))
                Text(if (it >= 0) "⌛ ${it}d" else "⌛ overdue", style = MaterialTheme.typography.labelSmall, color = if (it >= 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
        }
        // Suppress on a completed cycle — weeksLeft floors to ~0.5, inflating the weekly need into a false
        // "over budget"; the honest message then is "re-set", which the cycle badge above already shows. Matches
        // the detail card's guard so the row and detail agree.
        if (cap?.overcommitted == true && cycle?.complete != true) {
            Text("⚠︎ ~${"%.1f".format(cap.weeklyNeedH)}h/wk needed vs ~${"%.0f".format(cap.weeklyHaveH)}h of focus — this may be over budget.",
                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        if (coach != null) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🧭 ${coach.text}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            coach.startActivityId?.let { act -> TextButton(onClick = { vm.startActivityTimer(act) }) { Text("Start") } }
        }
    }
}

// ── The detail screen — the deep read + live edits (milestones, key results, review) ─────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalDetailScreen(vm: AppViewModel, g: Goal, onBack: () -> Unit, onEdit: () -> Unit, onReview: () -> Unit) {
    BackHandler(onBack = onBack)
    val today = goalToday()
    val settings by vm.settings.collectAsState()
    // Key health/capacity on the live stores (like GoalRow) so completing a task or tracking time
    // refreshes the detail card too — keying on `settings` alone left both stale.
    val tasks by vm.tasks.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    // Archived-inclusive so the lead-measure hint can tell an archived habit ("practice retired") apart from
    // a deleted one — vm.habits strips archived, which would mislabel every archived habit as "no longer exists".
    val habitsAll by vm.habitsWithArchived.collectAsState()
    val timeEntries by vm.timeEntries.collectAsState()
    val reviews = remember(settings.goalReviewsJson) { vm.goalReviews() }
    val h = remember(g, tasks, checkins, timeEntries) { vm.goalHealth(g) }
    val cycle = remember(g, today) { GoalScore.cycle(g, today) }
    val cap = remember(g, timeEntries) { vm.goalCapacity(g) }
    var krEdit by remember { mutableStateOf<KeyResult?>(null) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TopAppBar(expandedHeight = 52.dp,
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("${g.emoji} ${g.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { vm.shareGoalSnapshot(g) }) { Icon(Icons.Filled.Share, "Share progress") }
                    IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit goal") }
                })
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.height(2.dp))
                if (g.note.isNotBlank()) AppCard { Text("Why", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(g.note, style = MaterialTheme.typography.bodyMedium) }

                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (g.identity.isNotBlank()) Text("Becoming ${g.identity}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("${(h.overall * 100).toInt()}% overall health", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp)); ProgressTrack(h.overall.toFloat(), height = 10)
                    cycle?.let {
                        Spacer(Modifier.height(10.dp))
                        if (it.complete) {
                            Text("${it.totalWeeks}-week cycle complete — time to review & re-set.", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                        } else {
                            val on = GoalScore.onTrack(h.overall, it.elapsedFraction)
                            Text("${it.totalWeeks}-week cycle · week ${it.weekIndex} of ${it.totalWeeks} · ${it.daysLeft} days left", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                            Text(if (on) "On pace — health is keeping up with the calendar." else "Behind pace — health trails the time elapsed.",
                                style = MaterialTheme.typography.labelSmall, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                    }
                    // Once the cycle is complete there are 0 days left, which inflates the per-week "needed"
                    // figure — the honest message then is "re-set", not a capacity number. Suppress it.
                    if (cap != null && cycle?.complete != true) {
                        Spacer(Modifier.height(8.dp))
                        Text("Capacity: ~${"%.1f".format(cap.weeklyNeedH)}h/week needed vs ~${"%.1f".format(cap.weeklyHaveH)}h of real focus.",
                            style = MaterialTheme.typography.labelSmall, color = if (cap.overcommitted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Lead measures — the inputs you control.
                if (g.hasHabit || g.hasBudget) AppCard {
                    Text("LEAD MEASURES", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("The practice that carries it — what you control day to day.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (g.hasHabit) {
                        Spacer(Modifier.height(6.dp))
                        val isKeystone = g.habitId.isNotBlank() && g.habitId == remember(checkins) { vm.keystoneHabitId() }
                        MeasureLine((if (isKeystone) "🗝️ " else "") + "↻ Habit", "${h.habitStrength}% automaticity · ${h.habitStreak}-day streak", (h.habitStrength / 100f))
                        // If the lead habit has been archived (or deleted), its strength/streak freeze — say so,
                        // so a stalled lead measure reads as "the practice retired", not "the goal is failing".
                        val leadHabit = remember(habitsAll, g.habitId) { habitsAll.firstOrNull { it.id == g.habitId } }
                        if (leadHabit == null || leadHabit.archived) {
                            Text(if (leadHabit == null) "This lead habit no longer exists — edit the goal to relink one."
                                 else "This lead habit is archived — it's no longer part of your active practice.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                    if (g.hasBudget) { Spacer(Modifier.height(6.dp)); MeasureLine("⏱ Time budget", "${fmtH(h.minutesTracked)} of ${fmtH(h.budgetMin)} banked", if (h.budgetMin == 0) 0f else h.minutesTracked.toFloat() / h.budgetMin) }
                }

                // Lag measures — the outcomes they produce.
                if (g.hasTasks || g.keyResults.isNotEmpty()) AppCard {
                    Text("LAG MEASURES", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("The outcomes those inputs produce.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (g.hasTasks) { Spacer(Modifier.height(6.dp)); MeasureLine("✓ Tasks", "${h.taskDone} of ${h.taskTotal} done", if (h.taskTotal == 0) 0f else h.taskDone.toFloat() / h.taskTotal) }
                    g.keyResults.forEach { kr ->
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth().clickable { krEdit = kr }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                MeasureLine("◎ ${kr.title.ifBlank { "Key result" }}", "${trimNum(kr.current)}/${trimNum(kr.target)} ${kr.unit}".trim(), kr.fraction.toFloat())
                            }
                            Icon(Icons.Filled.Edit, "Update", Modifier.size(16.dp).padding(start = 6.dp), tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }

                // Milestones — tap to tick.
                if (g.milestones.isNotEmpty()) AppCard {
                    Text("MILESTONES", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    g.milestones.forEach { m ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable {
                            val flipped = m.copy(done = !m.done, doneEpochDay = if (!m.done) today else 0L)
                            vm.upsertGoal(g.copy(milestones = g.milestones.map { if (it.id == m.id) flipped else it }))
                        }, verticalAlignment = Alignment.CenterVertically) {
                            if (m.done) DoneTick(Modifier.size(20.dp)) else Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                            Spacer(Modifier.width(10.dp))
                            Text(m.title.ifBlank { "Milestone" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                color = if (m.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            if (m.targetEpochDay > 0) Text(dayLabel(m.targetEpochDay), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // Review scoreboard for this goal.
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("ACCOUNTABILITY", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            val chain = GoalScore.integrityChain(reviews, g.reviewCadenceDays, today, g.id)
                            val last = GoalScore.lastReview(reviews, g.id)
                            Text(if (last == null) "Not yet reviewed" else "Last review ${dayLabel(last.epochDay)} · 🔗 $chain kept",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // Commitments kept vs made, summed over the last few reviews (was logged but never shown).
                            val recent = reviews.filter { it.goalId == g.id }.sortedByDescending { it.epochDay }.take(6)
                            val made = recent.sumOf { it.commitmentsTotal }
                            if (made > 0) {
                                val kept = recent.sumOf { it.commitmentsKept }
                                Text("Commitments kept $kept/$made", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            // Review-due signal on the goal's own cadence.
                            if (GoalScore.reviewDue(reviews, g.reviewCadenceDays, today, g.id)) {
                                Text("⏰ Review due", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                            } else {
                                val d = GoalScore.daysUntilReview(reviews, g.reviewCadenceDays, today, g.id)
                                if (d in 1..2) Text("Next review in ${d}d", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        FilledTonalButton(onClick = onReview) { Text("Review") }
                    }
                    val trend = GoalScore.executionTrend(reviews, g.id, 10)
                    if (trend.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Sparkline(trend) }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    krEdit?.let { kr ->
        var cur by remember(kr.id) { mutableStateOf(editNum(kr.current)) }
        var tgt by remember(kr.id) { mutableStateOf(editNum(kr.target)) }
        var unit by remember(kr.id) { mutableStateOf(kr.unit) }
        AlertDialog(onDismissRequest = { krEdit = null },
            title = { Text(kr.title.ifBlank { "Key result" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(cur, { cur = cleanDecimal(it) }, label = { Text("Current${if (unit.isBlank()) "" else " ($unit)"}") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(tgt, { tgt = cleanDecimal(it) }, label = { Text("Target") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(unit, { unit = it.take(8) }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Keep the prior value when a field is left mid-edit / unparseable, rather than zeroing it.
                    val v = cur.toDoubleOrNull() ?: kr.current
                    val t = tgt.toDoubleOrNull() ?: kr.target
                    vm.upsertGoal(g.copy(keyResults = g.keyResults.map { if (it.id == kr.id) it.copy(current = v, target = t, unit = unit.trim()) else it }))
                    krEdit = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { krEdit = null }) { Text("Cancel") } })
    }
}

private fun trimNum(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else "%.1f".format(d)

/** Non-rounding numeric text for an EDITABLE field, so opening a KR editor and saving can't silently round a
 *  stored value (e.g. 5.25 → 5.3) the way the 1-dp display formatter [trimNum] would. Display keeps trimNum. */
private fun editNum(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

/** Sanitize a numeric text-field edit: digits and at most ONE decimal point, capped length. Without the
 *  single-dot guard "1.2.3" is accepted into the field but parses to null, and the edit is then silently
 *  reverted to the old value on save — the KR-editing footgun the audit flagged. */
private fun cleanDecimal(s: String): String {
    val filtered = s.filter { it.isDigit() || it == '.' }.take(9)
    val dot = filtered.indexOf('.')
    return if (dot < 0) filtered else filtered.substring(0, dot + 1) + filtered.substring(dot + 1).replace(".", "")
}

@Composable
private fun MeasureLine(label: String, detail: String, fraction: Float) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(3.dp)); ProgressTrack(fraction, height = 6)
    }
}

// ── The editor — full-screen; milestones, key results, area, cycle, cadence ──────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalEditorScreen(vm: AppViewModel, goal: Goal, existing: Boolean, onDismiss: () -> Unit, onSave: (Goal) -> Unit, onDelete: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val lists by vm.lists.collectAsState()
    val habits by vm.habits.collectAsState()
    val activities by vm.timeActivities.collectAsState()
    val liveHabits = remember(habits) { habits.filter { !it.archived } }
    val liveActs = remember(activities) { activities.filter { !it.archived } }
    val knownAreas = remember(lists) { Goals.areasOf(vm.goals()) }

    var name by remember { mutableStateOf(goal.name) }
    var emoji by remember { mutableStateOf(goal.emoji) }
    var note by remember { mutableStateOf(goal.note) }
    var identity by remember { mutableStateOf(goal.identity) }
    var area by remember { mutableStateOf(goal.area) }
    var listId by remember { mutableStateOf(goal.listId) }
    var habitId by remember { mutableStateOf(goal.habitId) }
    var activityId by remember { mutableStateOf(goal.activityId) }
    var budgetH by remember { mutableStateOf(if (goal.budgetMinutes > 0) (goal.budgetMinutes / 60).toString() else "") }
    var deadline by remember { mutableStateOf(goal.targetEpochDay) }
    var cycleOn by remember { mutableStateOf(goal.hasCycle) }
    var cycleStart by remember { mutableStateOf(if (goal.cycleStartEpochDay > 0) goal.cycleStartEpochDay else goalToday()) }
    var cycleWeeks by remember { mutableIntStateOf(if (goal.cycleWeeks > 0) goal.cycleWeeks else 12) }
    var cadence by remember { mutableIntStateOf(goal.reviewCadenceDays) }
    val milestones = remember { mutableStateListOf<GoalMilestone>().apply { addAll(goal.milestones) } }
    val keyResults = remember { mutableStateListOf<KeyResult>().apply { addAll(goal.keyResults) } }

    var pickEmoji by remember { mutableStateOf(false) }
    var pickDeadline by remember { mutableStateOf(false) }
    var pickCycleStart by remember { mutableStateOf(false) }
    var pickMilestoneDate by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val faint = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TopAppBar(expandedHeight = 52.dp,
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text(if (existing) "Edit goal" else "New goal", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = { if (existing) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) } })
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)).clickable { pickEmoji = true }, contentAlignment = Alignment.Center) {
                        Text(emoji.ifBlank { "🎯" }, fontSize = 26.sp)
                    }
                    OutlinedTextField(name, { name = it }, label = { Text("Goal name") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(note, { note = it }, label = { Text("Why — the reason that pulls you") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(identity, { identity = it }, label = { Text("Identity — “the kind of person who…”") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(area, { area = it }, label = { Text("Area of focus (GTD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (knownAreas.isNotEmpty()) OptionChips(knownAreas, area.trim().ifBlank { null }, { area = it }, wrap = true, spacing = 6) { it }

                AppCard {
                    Text("THE THREE ARMS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = faint)
                    Spacer(Modifier.height(6.dp)); Text("Task list (lag)", style = MaterialTheme.typography.labelSmall, color = faint)
                    OptionChips(listOf("") + lists.map { it.id }, listId, { listId = it }, wrap = false, spacing = 6) { id -> if (id.isBlank()) "None" else lists.firstOrNull { it.id == id }?.name ?: "" }
                    Spacer(Modifier.height(8.dp)); Text("Supporting habit (lead)", style = MaterialTheme.typography.labelSmall, color = faint)
                    OptionChips(listOf("") + liveHabits.map { it.id }, habitId, { habitId = it }, wrap = false, spacing = 6) { id -> if (id.isBlank()) "None" else liveHabits.firstOrNull { it.id == id }?.let { (it.emoji?.plus(" ") ?: "") + it.name } ?: "" }
                    Spacer(Modifier.height(8.dp)); Text("Time budget (lead)", style = MaterialTheme.typography.labelSmall, color = faint)
                    OptionChips(listOf("") + liveActs.map { it.id }, activityId, { activityId = it }, wrap = false, spacing = 6) { id -> if (id.isBlank()) "None" else liveActs.firstOrNull { it.id == id }?.let { (it.emoji?.plus(" ") ?: "") + it.name } ?: "" }
                    if (activityId.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(budgetH, { v -> budgetH = v.filter { it.isDigit() }.take(4) }, label = { Text("Budget (hours)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }

                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Deadline", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(if (deadline > 0) dayLabel(deadline) else "None", style = MaterialTheme.typography.labelSmall, color = faint)
                        }
                        if (deadline > 0) TextButton(onClick = { deadline = 0 }) { Text("Clear") }
                        FilledTonalButton(onClick = { pickDeadline = true }) { Text(if (deadline > 0) "Change" else "Set") }
                    }
                }

                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${cycleWeeks}-week cycle", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(if (cycleOn) "A sprint, not a someday — from ${dayLabel(cycleStart)}" else "Off", style = MaterialTheme.typography.labelSmall, color = faint)
                        }
                        Switch(checked = cycleOn, onCheckedChange = { cycleOn = it })
                    }
                    if (cycleOn) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Starts ${dayLabel(cycleStart)}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            FilledTonalButton(onClick = { pickCycleStart = true }) { Text("Change") }
                        }
                        Spacer(Modifier.height(4.dp))
                        Stepper(cycleWeeks, { cycleWeeks = it }, min = 1, max = 52, step = 1, label = "Weeks", display = { "$it wk" })
                    }
                }

                AppCard {
                    Text("Review cadence", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("How often you sit with this goal — the rhythm that keeps it alive.", style = MaterialTheme.typography.labelSmall, color = faint)
                    Spacer(Modifier.height(6.dp))
                    OptionChips(listOf(1, 7, 14, 30), cadence, { cadence = it }, wrap = false, spacing = 6) { cadenceLabel(it) }
                }

                HorizontalDivider()
                Text("MILESTONES", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = faint)
                milestones.forEachIndexed { i, m ->
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(m.title, { milestones[i] = m.copy(title = it) }, label = { Text("Milestone ${i + 1}") }, singleLine = true, modifier = Modifier.weight(1f))
                            IconButton(onClick = { if (i > 0) { val t = milestones[i - 1]; milestones[i - 1] = milestones[i]; milestones[i] = t } }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Up") }
                            IconButton(onClick = { milestones.removeAt(i) }) { Icon(Icons.Filled.Delete, "Delete", tint = faint) }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (m.targetEpochDay > 0) "By ${dayLabel(m.targetEpochDay)}" else "No date", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = faint)
                            if (m.targetEpochDay > 0) TextButton(onClick = { milestones[i] = m.copy(targetEpochDay = 0) }) { Text("Clear") }
                            TextButton(onClick = { pickMilestoneDate = i }) { Text("Date") }
                        }
                    }
                }
                FilledTonalButton(onClick = { milestones.add(GoalMilestone(id = UUID.randomUUID().toString(), title = "")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add milestone")
                }

                HorizontalDivider()
                Text("KEY RESULTS (OKR)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = faint)
                keyResults.forEachIndexed { i, kr ->
                    AppCard {
                        // Back each numeric field with its own raw string so a decimal point (or an empty field
                        // mid-edit) survives — binding straight to trimNum(model) would revert every keystroke.
                        var startRaw by remember(kr.id) { mutableStateOf(editNum(kr.start)) }
                        var nowRaw by remember(kr.id) { mutableStateOf(editNum(kr.current)) }
                        var targetRaw by remember(kr.id) { mutableStateOf(editNum(kr.target)) }
                        fun clean(s: String) = cleanDecimal(s)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(kr.title, { keyResults[i] = kr.copy(title = it) }, label = { Text("Result ${i + 1}") }, singleLine = true, modifier = Modifier.weight(1f))
                            IconButton(onClick = { keyResults.removeAt(i) }) { Icon(Icons.Filled.Delete, "Delete", tint = faint) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Start is the baseline the fraction measures from (a "run 2→5 km" KR is 0% at 2, not at 0).
                            OutlinedTextField(startRaw, { s -> startRaw = clean(s); keyResults[i] = kr.copy(start = startRaw.toDoubleOrNull() ?: kr.start) }, label = { Text("Start") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(nowRaw, { s -> nowRaw = clean(s); keyResults[i] = kr.copy(current = nowRaw.toDoubleOrNull() ?: kr.current) }, label = { Text("Now") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(targetRaw, { s -> targetRaw = clean(s); keyResults[i] = kr.copy(target = targetRaw.toDoubleOrNull() ?: kr.target) }, label = { Text("Target") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(kr.unit, { keyResults[i] = kr.copy(unit = it.take(8)) }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                    }
                }
                FilledTonalButton(onClick = { keyResults.add(KeyResult(id = UUID.randomUUID().toString(), title = "")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add key result")
                }
                Spacer(Modifier.height(8.dp))
            }
            Button(onClick = {
                onSave(goal.copy(
                    name = name.trim(), emoji = emoji.ifBlank { "🎯" }, note = note.trim(), identity = identity.trim(), area = area.trim(),
                    listId = listId, habitId = habitId, activityId = activityId,
                    budgetMinutes = if (activityId.isNotBlank()) (budgetH.toIntOrNull() ?: 0) * 60 else 0,
                    targetEpochDay = deadline,
                    cycleStartEpochDay = if (cycleOn) cycleStart else 0L, cycleWeeks = if (cycleOn) cycleWeeks else 0,
                    reviewCadenceDays = cadence,
                    milestones = milestones.filter { it.title.isNotBlank() }.toList(),
                    keyResults = keyResults.filter { it.title.isNotBlank() }.toList(),
                ))
            }, enabled = name.isNotBlank() && (listId.isNotBlank() || habitId.isNotBlank() || (activityId.isNotBlank() && (budgetH.toIntOrNull() ?: 0) > 0) || keyResults.any { it.title.isNotBlank() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { Text("Save goal") }
        }
    }

    if (pickEmoji) AlertDialog(onDismissRequest = { pickEmoji = false },
        title = { Text("Pick an emoji") },
        text = { EmojiGridPicker(current = emoji.ifBlank { null }, onPick = { emoji = it ?: ""; pickEmoji = false }) },
        confirmButton = { TextButton(onClick = { pickEmoji = false }) { Text("Close") } })
    if (pickDeadline) DateOnlyPickerDialog(initial = if (deadline > 0) epochDayToMillis(deadline) else null, onDismiss = { pickDeadline = false }, onConfirm = { deadline = millisToEpochDay(it); pickDeadline = false })
    if (pickCycleStart) DateOnlyPickerDialog(initial = epochDayToMillis(cycleStart), onDismiss = { pickCycleStart = false }, onConfirm = { cycleStart = millisToEpochDay(it); pickCycleStart = false })
    pickMilestoneDate?.let { i ->
        DateOnlyPickerDialog(initial = if (milestones[i].targetEpochDay > 0) epochDayToMillis(milestones[i].targetEpochDay) else null, onDismiss = { pickMilestoneDate = null }, onConfirm = { milestones[i] = milestones[i].copy(targetEpochDay = millisToEpochDay(it)); pickMilestoneDate = null })
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete goal?") },
        text = { Text("“${goal.name}” is removed. Your tasks, habit and tracked time stay — only the goal that ties them together goes. This can't be undone.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

// ── The weekly review dialog — log execution + commitments, feeding the scoreboard & integrity chain ─
@Composable
private fun WeeklyReviewDialog(vm: AppViewModel, scope: String, goals: List<Goal>, onDismiss: () -> Unit) {
    val portfolio = scope.isBlank()
    val goal = if (portfolio) null else goals.firstOrNull { it.id == scope }
    // Seed execution with the measured health so the human only nudges from an honest baseline.
    val seed = remember(scope) {
        if (portfolio) (if (goals.isEmpty()) 0 else (goals.map { vm.goalHealth(it).overall }.average() * 100).toInt())
        else goal?.let { (vm.goalHealth(it).overall * 100).toInt() } ?: 0
    }
    var execution by remember { mutableIntStateOf(seed) }
    var kept by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (portfolio) "Weekly review" else "Review · ${goal?.name ?: ""}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("How much of your plan did you actually execute this period?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Stepper(execution, { execution = it }, min = 0, max = 100, step = 5, label = "Execution", display = { "$it%" })
                Spacer(Modifier.height(8.dp))
                Text("Commitments you kept vs made — the 4DX cadence of accountability.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Stepper(kept, { kept = it.coerceAtMost(total) }, min = 0, max = 20, step = 1, label = "Kept", display = { "$it" })
                Stepper(total, { total = it; if (kept > total) kept = total }, min = 0, max = 20, step = 1, label = "Made", display = { "$it" })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(note, { note = it }, label = { Text("One line: what's the next lead measure?") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { vm.logGoalReview(scope, execution, kept, total, note); onDismiss() }) { Text("Log review") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// ── The templates browser ────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalTemplatesDialog(onDismiss: () -> Unit, onPick: (GoalTemplate) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Goal templates") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Each template pre-shapes an identity, key results and milestones. Pick one, then bind your list, habit and activity.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Goals.TEMPLATES.forEach { t ->
                    Surface(onClick = { onPick(t) }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.emoji, fontSize = 26.sp, modifier = Modifier.padding(end = 12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(t.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("~${t.budgetHours}h · ${t.keyResults.size} KRs · ${t.milestones.size} milestones", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Icon(Icons.Filled.Add, "Use", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
