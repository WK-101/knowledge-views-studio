package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Hub
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.todocompanion.app.domain.done.Accomplishment
import com.todocompanion.app.domain.done.DoneKind
import com.todocompanion.app.domain.done.DoneRecord
import com.todocompanion.app.ui.AppViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Done Record (R27) — the reverse-chronological story of everything you've finished, cross-referenced
 * from completed tasks, habit check-ins that met their goal, finished focus sessions and achieved goals.
 * Replaces the dead "Completed" archive with a living record: a feed, a trophy case, lifetime totals,
 * on-this-day memories and a one-tap brag-document export. Everything derived on-device; 0 network.
 */
/** Frontier F5 — the proof vault: when enabled (and the whole app isn't already locked), gate The Record
 *  behind the device biometric before it renders. */
@Composable
fun DoneScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, onBack: () -> Unit) {
    val gateSettings by vm.settings.collectAsState()
    if (gateSettings.lockRecord && !gateSettings.appLockEnabled) {
        com.todocompanion.app.ui.AppLockGate(enabled = true) { DoneScreenBody(vm, onOpenTask, onBack) }
    } else DoneScreenBody(vm, onOpenTask, onBack)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DoneScreenBody(vm: AppViewModel, onOpenTask: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val tasks by vm.tasks.collectAsState()
    val habits by vm.habits.collectAsState()
    val checkins by vm.habitCheckins.collectAsState()
    val timeEntries by vm.timeEntries.collectAsState()
    val lists by vm.lists.collectAsState()
    val zone = ZoneId.systemDefault()
    val ctx = LocalContext.current

    val listNameById = remember(lists) { lists.associate { it.id to it.name } }
    val feed = remember(tasks, habits, checkins, timeEntries) { DoneRecord.build(tasks, habits, checkins, timeEntries, zone) }
    val today = LocalDate.now()
    // Memories look across the whole history regardless of the range picker.
    val onThisDay = remember(feed) { DoneRecord.onThisDay(feed, today) }

    // R28 #10 — the record is scoped to a chosen window (today … lifetime); everything below reads the
    // ranged slice so the totals, trophy case and feed all reflect the same span.
    var range by remember { mutableStateOf("all") }
    val fromDay = rangeFromDay(range, today)
    val rangedFeed = remember(feed, fromDay) { if (fromDay == Long.MIN_VALUE) feed else feed.filter { it.epochDay >= fromDay } }
    val stats = remember(rangedFeed) { DoneRecord.stats(rangedFeed) }
    val wins = remember(rangedFeed) { rangedFeed.filter { it.isWin } }

    var query by remember { mutableStateOf("") }
    var winsOnly by remember { mutableStateOf(false) }
    var showBrag by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }

    // R28 Phase 3 — minutes actually tracked per task (for the honesty ledger: estimate vs. real).
    val trackedByTask = remember(timeEntries) {
        val m = HashMap<String, Int>()
        timeEntries.forEach { e ->
            val id = e.taskId ?: return@forEach
            val end = e.endMillis ?: return@forEach
            if (end > e.startMillis) m[id] = (m[id] ?: 0) + ((end - e.startMillis) / 60_000L).toInt()
        }
        m
    }
    val honesty = remember(tasks, trackedByTask, listNameById) { DoneRecord.honesty(tasks, trackedByTask, listNameById) }
    // Momentum = evidence-based comeback lines + a private rank against your own past (frontier F4).
    val comeback = remember(rangedFeed, feed) {
        DoneRecord.comeback(rangedFeed, today) + listOfNotNull(
            com.todocompanion.app.domain.done.Percentile.bestWeekSince(feed, today),
            com.todocompanion.app.domain.done.Percentile.todayStandout(feed, today),
        )
    }
    var showCoSign by remember { mutableStateOf(false) }

    // R29 Phase 5/7 — the impact graph (finished work → the goals it served) and the verifiable timeline.
    val settings by vm.settings.collectAsState()
    var showImpact by remember { mutableStateOf(false) }
    val impact = remember(rangedFeed, tasks) { com.todocompanion.app.domain.done.Impact.build(rangedFeed, tasks) }
    val integrity = remember(feed, settings.integritySeal) {
        com.todocompanion.app.domain.done.Integrity.status(feed, com.todocompanion.app.domain.done.Integrity.Seal.decode(settings.integritySeal))
    }
    if (showImpact) {
        ImpactScreen(impact, rangeLabel(range), listNameById, onOpenTask = onOpenTask, onBack = { showImpact = false })
        return
    }
    if (showCoSign) {
        CoSignScreen(vm, onBack = { showCoSign = false })
        return
    }

    val shown = remember(rangedFeed, query, winsOnly, listNameById) {
        val q = query.trim().lowercase()
        rangedFeed.filter { a ->
            (!winsOnly || a.isWin) &&
                (q.isEmpty() || a.title.lowercase().contains(q) ||
                    (a.outcome?.lowercase()?.contains(q) == true) ||
                    (a.listId?.let { listNameById[it] }?.lowercase()?.contains(q) == true))
        }
    }
    val byDay = remember(shown) { shown.groupBy { it.epochDay }.toSortedMap(compareByDescending { it }) }

    Scaffold(topBar = {
        TopAppBar(
            expandedHeight = 52.dp,
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text("The Record") },
            actions = {
                Box {
                    IconButton(onClick = { exportMenu = true }) { Icon(Icons.Filled.EmojiEvents, "Export the record") }
                    androidx.compose.material3.DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Brag document…") }, onClick = { exportMenu = false; showBrag = true })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Résumé lines") }, onClick = {
                            exportMenu = false
                            val md = DoneRecord.resumeMarkdown(rangedFeed, listNameById)
                            vm.exportBragDoc(md, "resume-lines.md") { loc -> android.widget.Toast.makeText(ctx, if (loc != null) "Résumé lines saved to $loc" else "Save failed", android.widget.Toast.LENGTH_LONG).show() }
                        })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Living archive (everything)") }, onClick = {
                            exportMenu = false
                            val md = DoneRecord.archiveMarkdown(feed, listNameById, today)
                            vm.exportBragDoc(md, "the-record-archive.md") { loc -> android.widget.Toast.makeText(ctx, if (loc != null) "Archive saved to $loc" else "Save failed", android.widget.Toast.LENGTH_LONG).show() }
                        })
                        // F5 — redacted archive: the shape of your work, private titles hidden.
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Redacted archive") }, onClick = {
                            exportMenu = false
                            val md = DoneRecord.archiveMarkdown(feed, listNameById, today, redact = true)
                            vm.exportBragDoc(md, "the-record-redacted.md") { loc -> android.widget.Toast.makeText(ctx, if (loc != null) "Redacted archive saved to $loc" else "Save failed", android.widget.Toast.LENGTH_LONG).show() }
                        })
                        // F2 — sealed-year certificate (shareable image for the current year).
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Sealed-year certificate") }, onClick = {
                            exportMenu = false; vm.shareYearCertificate(today.year)
                        })
                    }
                }
            },
        )
    }) { padding ->
        if (feed.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🏅", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.size(10.dp))
                Text("Your record starts here", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Finish a task, keep a habit or run a focus session and it lands here — a private, offline record of everything you've done.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Range selector — scope the whole record to a window.
            item(key = "range") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RANGES.forEach { (k, l) ->
                        FilterChip(selected = range == k, onClick = { range = k }, label = { Text(l) })
                    }
                }
            }
            // Totals + personal bests, over the chosen range.
            item(key = "stats") { LifetimeCard(stats, rangeLabel(range)) }
            // Impact graph entry — always reachable; the map itself shows an empty state if nothing links yet.
            item(key = "impact") { ImpactTeaser(impact) { showImpact = true } }
            // Verifiable timeline — seal the record so back-dating is detectable.
            item(key = "integrity") { IntegrityCard(integrity, onSeal = { vm.sealRecord() }, onClear = { vm.clearSeal() }) }
            // Peer co-sign — witness a proof phone-to-phone, no cloud.
            item(key = "cosign") { CoSignTeaser { showCoSign = true } }
            // Momentum — kind, evidence-based (recovery + standout effort), not just streaks.
            if (comeback.isNotEmpty()) item(key = "comeback") { ComebackCard(comeback) }
            // The honesty ledger — estimate vs. the time actually invested.
            honesty.overall?.let { ov -> item(key = "honesty") { HonestyCard(ov, honesty.worst) } }
            // On this day.
            if (onThisDay.isNotEmpty()) item(key = "onthisday") { OnThisDayCard(onThisDay, listNameById) }
            // Trophy case — the wins, front and centre.
            if (wins.isNotEmpty()) item(key = "trophies") { TrophyCase(wins.take(12)) { onOpenTask(it) } }
            // Search + wins filter.
            item(key = "filter") {
                Column {
                    com.todocompanion.app.ui.components.AppTextField(
                        query, { query = it }, singleLine = true,
                        label = { Text("Search what you've done") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = winsOnly, onClick = { winsOnly = !winsOnly }, label = { Text("Wins only") },
                            leadingIcon = { Icon(if (winsOnly) Icons.Filled.Star else Icons.Filled.StarBorder, null, Modifier.size(16.dp)) })
                        Text("${shown.size} of ${rangedFeed.size}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
            }
            // The feed, grouped by day.
            byDay.forEach { (epochDay, dayItems) ->
                item(key = "day-$epochDay") { DayHeader(LocalDate.ofEpochDay(epochDay), today, dayItems) }
                items(dayItems, key = { it.kind.name + it.refId + it.whenMillis }) { a ->
                    AccomplishmentRow(a, listNameById[a.listId], onOpen = { if (a.isTaskLike) onOpenTask(a.refId) },
                        onToggleWin = if (a.isTaskLike) { { tasks.firstOrNull { t -> t.id == a.refId }?.let { vm.toggleWin(it) } } } else null,
                        onShareReceipt = if (a.isTaskLike) { { vm.shareReceipt(a, listNameById[a.listId]) } } else null)
                }
            }
            item(key = "footer") {
                Text("A private record, built on-device from what you already track. Nothing here leaves your phone.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 12.dp))
            }
        }
    }

    if (showBrag) BragDialog(
        feed = feed, listNameById = listNameById, zone = zone,
        onDismiss = { showBrag = false },
        onGenerate = { md ->
            vm.exportBragDoc(md) { loc ->
                android.widget.Toast.makeText(ctx, if (loc != null) "Brag document saved to $loc" else "Save failed", android.widget.Toast.LENGTH_LONG).show()
            }
            showBrag = false
        },
    )
}

/** R28 #10 — the windows the record can be scoped to. */
private val RANGES = listOf(
    "today" to "Today", "month" to "This month", "4mo" to "4 months", "6mo" to "6 months",
    "year" to "This year", "5y" to "5 years", "10y" to "10 years", "all" to "Lifetime",
)
private fun rangeLabel(k: String): String = RANGES.firstOrNull { it.first == k }?.second ?: "Lifetime"
private fun rangeFromDay(k: String, today: LocalDate): Long = when (k) {
    "today" -> today.toEpochDay()
    "month" -> today.withDayOfMonth(1).toEpochDay()
    "4mo" -> today.minusMonths(4).toEpochDay()
    "6mo" -> today.minusMonths(6).toEpochDay()
    "year" -> today.withDayOfYear(1).toEpochDay()
    "5y" -> today.minusYears(5).toEpochDay()
    "10y" -> today.minusYears(10).toEpochDay()
    else -> Long.MIN_VALUE
}

@Composable
private fun LifetimeCard(s: com.todocompanion.app.domain.done.DoneStats, rangeLabel: String) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(if (rangeLabel == "Lifetime") "Lifetime record" else "$rangeLabel · record", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("${s.totalTasks}", "tasks done")
                Stat("${s.habitCheckins}", "habit days")
                Stat("${s.focusedMinutes / 60}h", "focused")
                Stat("${s.goalsAchieved}", "goals")
            }
            Spacer(Modifier.size(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("🔥 ${s.currentStreakDays}", "day streak")
                Stat("${s.longestStreakDays}", "best streak")
                Stat("${s.bestDayCount}", "best day")
                Stat("${s.totalWins}", "wins")
            }
        }
    }
}

@Composable
private fun ComebackCard(lines: List<String>) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .35f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("📈 Momentum", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(6.dp))
            lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

@Composable
private fun HonestyCard(overall: com.todocompanion.app.domain.done.DoneRecord.LedgerRow, worst: List<com.todocompanion.app.domain.done.DoneRecord.LedgerRow>) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("⚖️ Estimate vs. actual", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(4.dp))
            val pct = (overall.ratio * 100).toInt()
            Text(
                if (pct in 90..110) "Your estimates are on the money — finished work took ${pct}% of the estimate."
                else if (pct > 110) "Finished work runs long: ${pct}% of your estimate on average. Pad by ~${pct - 100}%."
                else "You beat your estimates — work took only ${pct}% of what you planned.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (worst.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                worst.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${r.estimateMin}m → ${r.actualMin}m", style = MaterialTheme.typography.labelMedium,
                            color = if (r.ratio > 1.15f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun OnThisDayCard(items: List<Accomplishment>, listNameById: Map<String, String>) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .35f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("📅 On this day", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
            items.take(5).forEach { a ->
                val d = LocalDate.ofEpochDay(a.epochDay)
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(kindGlyph(a.kind), modifier = Modifier.width(24.dp))
                    Text(a.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${d.year}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TrophyCase(wins: List<Accomplishment>, onOpen: (String) -> Unit) {
    Column {
        Text("🏆 Trophy case", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            wins.forEach { a ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f),
                    modifier = Modifier.width(150.dp).clickable { onOpen(a.refId) }) {
                    Column(Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.Star, null, tint = Color(0xFFF5A623), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(a.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        a.outcome?.let { Spacer(Modifier.size(3.dp)); Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate, today: LocalDate, items: List<Accomplishment>) {
    val label = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> "${day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}, ${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}" +
            (if (day.year != today.year) " ${day.year}" else "")
    }
    val focusMin = items.filter { it.kind == DoneKind.FOCUS }.sumOf { it.durationMin }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.weight(1f))
        Text(buildString {
            append("${items.count { it.isTaskLike }} done")
            if (focusMin > 0) append(" · ${focusMin}m focus")
        }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AccomplishmentRow(a: Accomplishment, listName: String?, onOpen: () -> Unit, onToggleWin: (() -> Unit)?, onShareReceipt: (() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().clickable(enabled = a.isTaskLike) { onOpen() }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                Text(kindGlyph(a.kind))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(a.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface)
                val meta = buildString {
                    a.minuteOfDay?.let { append("%02d:%02d".format(it / 60, it % 60)) }
                    if (a.durationMin > 0) { if (isNotEmpty()) append(" · "); append("${a.durationMin}m") }
                    listName?.takeIf { it != "Inbox" }?.let { if (isNotEmpty()) append(" · "); append(it) }
                }
                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                a.outcome?.let { Text("→ $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                a.praise?.let { Text("“$it”", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            // Proof-of-work receipt — mint a shareable image of this finished item (task-like only).
            if (onShareReceipt != null) IconButton(onClick = onShareReceipt) {
                Icon(Icons.Filled.Share, "Share proof of work", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
            }
            if (onToggleWin != null) IconButton(onClick = onToggleWin) {
                Icon(if (a.isWin) Icons.Filled.Star else Icons.Filled.StarBorder, "Mark as a win",
                    tint = if (a.isWin) Color(0xFFF5A623) else MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private fun kindGlyph(kind: DoneKind): String = when (kind) {
    DoneKind.TASK -> "✓"
    DoneKind.GOAL -> "🎯"
    DoneKind.PROJECT -> "📦"
    DoneKind.HABIT -> "🔁"
    DoneKind.FOCUS -> "🎯"
}

/** Range + grouping picker that turns the record into a formatted, exportable brag document. */
@Composable
private fun BragDialog(
    feed: List<Accomplishment>, listNameById: Map<String, String>, zone: ZoneId,
    onDismiss: () -> Unit, onGenerate: (String) -> Unit,
) {
    val today = LocalDate.now()
    var range by remember { mutableStateOf("month") }     // month | quarter | year | all
    var group by remember { mutableStateOf("list") }      // list | day | flat
    val fromDay = when (range) {
        "month" -> today.withDayOfMonth(1).toEpochDay()
        "quarter" -> today.minusMonths(3).toEpochDay()
        "year" -> today.withDayOfYear(1).toEpochDay()
        else -> Long.MIN_VALUE
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val items = feed.filter { it.epochDay >= fromDay }
                onGenerate(buildBrag(items, listNameById, range, group, today))
            }) { Text("Generate & save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Brag document") },
        text = {
            Column {
                Text("A formatted record of what you finished — for a review, a promotion, or yourself. Saved as Markdown, entirely on-device.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp))
                Text("Range", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("month" to "This month", "quarter" to "Last 3 mo", "year" to "This year", "all" to "All time").forEach { (k, l) ->
                        FilterChip(selected = range == k, onClick = { range = k }, label = { Text(l) })
                    }
                }
                Spacer(Modifier.size(10.dp))
                Text("Group by", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("list" to "List", "day" to "Day", "flat" to "Flat").forEach { (k, l) ->
                        FilterChip(selected = group == k, onClick = { group = k }, label = { Text(l) })
                    }
                }
            }
        },
    )
}

/** Pure Markdown builder for the brag document. */
private fun buildBrag(items: List<Accomplishment>, listNameById: Map<String, String>, range: String, group: String, today: LocalDate): String {
    val sb = StringBuilder()
    val rangeLabel = when (range) { "month" -> "This month"; "quarter" -> "Last 3 months"; "year" -> today.year.toString(); else -> "All time" }
    sb.appendLine("# Brag document — $rangeLabel")
    sb.appendLine()
    val tasks = items.count { it.isTaskLike }
    val focus = items.filter { it.kind == DoneKind.FOCUS }.sumOf { it.durationMin }
    val habitDays = items.count { it.kind == DoneKind.HABIT }
    val wins = items.count { it.isWin }
    sb.appendLine("_$tasks completed · $wins wins · ${focus / 60}h focused · $habitDays habit days_")
    sb.appendLine()
    fun line(a: Accomplishment): String {
        val bits = StringBuilder("- ")
        if (a.isWin) bits.append("⭐ ")
        bits.append(a.title)
        if (a.durationMin > 0) bits.append(" _(${a.durationMin}m)_")
        a.outcome?.let { bits.append(" — $it") }
        a.praise?.let { bits.append("  \n  > “$it”") }
        return bits.toString()
    }
    when (group) {
        "list" -> {
            items.filter { it.isTaskLike }.groupBy { it.listId?.let { id -> listNameById[id] } ?: "No list" }
                .toSortedMap(compareBy { it })
                .forEach { (name, group) ->
                    sb.appendLine("## $name")
                    group.sortedByDescending { it.whenMillis }.forEach { sb.appendLine(line(it)) }
                    sb.appendLine()
                }
            val other = items.filter { !it.isTaskLike }
            if (other.isNotEmpty()) {
                sb.appendLine("## Habits & focus")
                sb.appendLine("- $habitDays habit days kept · ${focus / 60}h ${focus % 60}m focused")
                sb.appendLine()
            }
        }
        "day" -> {
            items.groupBy { it.epochDay }.toSortedMap(compareByDescending { it }).forEach { (day, group) ->
                sb.appendLine("## ${LocalDate.ofEpochDay(day)}")
                group.forEach { sb.appendLine(line(it)) }
                sb.appendLine()
            }
        }
        else -> {
            items.sortedByDescending { it.whenMillis }.forEach { sb.appendLine(line(it)) }
        }
    }
    sb.appendLine()
    sb.appendLine("_Generated on ${today} from ToDo Companion — The Done Record. Private, on-device._")
    return sb.toString()
}

// ---------- R29 Phase 5/7 — impact map & verifiable timeline ----------

@Composable
private fun ImpactTeaser(g: com.todocompanion.app.domain.done.Impact.Graph, onOpen: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Hub, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Impact map", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${g.finished} finished · ${g.goalsServed} goal${if (g.goalsServed == 1) "" else "s"} served · ${g.outcomes} outcome${if (g.outcomes == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("View →", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun IntegrityCard(status: com.todocompanion.app.domain.done.Integrity.Status, onSeal: () -> Unit, onClear: () -> Unit) {
    val unsealed = status.state == com.todocompanion.app.domain.done.Integrity.State.UNSEALED
    val (tint, label) = when (status.state) {
        com.todocompanion.app.domain.done.Integrity.State.VERIFIED -> MaterialTheme.colorScheme.primary to "Verified — untouched since you sealed it"
        com.todocompanion.app.domain.done.Integrity.State.TAMPERED -> MaterialTheme.colorScheme.error to "Changed since sealing — a sealed entry was edited, removed or back-dated"
        else -> MaterialTheme.colorScheme.onSurfaceVariant to "Not sealed yet"
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Verified, null, tint = tint)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Verifiable timeline", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(label, style = MaterialTheme.typography.bodySmall, color = tint)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("A local hash-chain over your completions. Sealing records the current head, so any later back-dating of a sealed entry is caught — no server, no account, nothing leaves the device.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("Chain head  " + status.head.take(16).uppercase().chunked(4).joinToString("-"),
                style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            status.sealedAt?.let {
                val d = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                Text("Sealed $d · ${status.sealedCount} entries" + (if (status.newSinceSeal > 0) " · ${status.newSinceSeal} new since" else ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilledTonalButton(onClick = onSeal) {
                    Text(if (unsealed) "Seal the record" else "Re-seal")
                }
                if (!unsealed) androidx.compose.material3.TextButton(onClick = onClear) { Text("Clear seal") }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ImpactScreen(
    g: com.todocompanion.app.domain.done.Impact.Graph, rangeLabel: String,
    listNameById: Map<String, String>, onOpenTask: (String) -> Unit, onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(expandedHeight = 52.dp,
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text("Impact map") })
    }) { padding ->
        if (g.nodes.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🕸️", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text("No goal-linked work in this range yet. Finish tasks under a goal or project and they'll web up here.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Scaffold
        }
        val maxMin = (g.nodes.maxOfOrNull { it.totalMinutes } ?: 0).coerceAtLeast(1)
        LazyColumn(Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "impact-head") {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(rangeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${g.finished} finished  →  ${g.goalsServed} goals  →  ${g.outcomes} outcomes",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Each finished task rolled up to the goal it served — the shape of what your work added up to.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(g.nodes, key = { it.goalId ?: "direct" }) { node -> ImpactNodeCard(node, maxMin, onOpenTask) }
        }
    }
}

@Composable
private fun ImpactNodeCard(node: com.todocompanion.app.domain.done.Impact.Node, maxMin: Int, onOpenTask: (String) -> Unit) {
    val accent = if (node.goalId == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (node.isGoalDone) "🎯" else if (node.goalId == null) "•" else "◇")
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(node.goalTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = buildString {
                        append("${node.items.size} finished")
                        if (node.totalMinutes >= 60) append(" · ${node.totalMinutes / 60}h ${node.totalMinutes % 60}m")
                        else if (node.totalMinutes > 0) append(" · ${node.totalMinutes}m")
                        if (node.isGoalDone) append(" · goal reached")
                    }
                    Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                val frac = (node.totalMinutes.toFloat() / maxMin).coerceIn(0.02f, 1f)
                Box(Modifier.fillMaxWidth(frac).height(6.dp).clip(RoundedCornerShape(3.dp)).background(accent))
            }
            Spacer(Modifier.height(8.dp))
            node.items.take(12).forEach { a ->
                Row(Modifier.fillMaxWidth().clickable { onOpenTask(a.refId) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("└─", color = accent.copy(alpha = .6f), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(8.dp))
                    Text(a.title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (a.durationMin > 0) Text("${a.durationMin}m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (node.items.size > 12) Text("+${node.items.size - 12} more",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 24.dp, top = 2.dp))
            if (node.outcomes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                node.outcomes.take(4).forEach { o ->
                    Text("→ $o", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------- R30 frontier F3 — peer co-sign ----------

@Composable
private fun CoSignTeaser(onOpen: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Handshake, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Peer co-sign", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Have a teammate witness a proof — phone to phone, no cloud.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Open →", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CoSignScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val deviceId = remember { com.todocompanion.app.domain.done.PeerSign.deviceId() }
    var inToken by remember { mutableStateOf("") }       // a proof's verify token, to co-sign
    var producedToken by remember { mutableStateOf<String?>(null) }
    var verifyToken by remember { mutableStateOf("") }   // a co-sign token, to verify
    var verifyResult by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = {
        TopAppBar(expandedHeight = 52.dp,
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text("Peer co-sign") })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This device signs and verifies with a key held only in its secure hardware. Everything here is offline — a token (or its QR) is the only thing that moves between phones.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Your signer id · $deviceId", style = MaterialTheme.typography.labelMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)

            // 1) Co-sign someone's proof.
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Co-sign a proof", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Paste the verify token from a friend's receipt (under its QR), then sign to witness it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    com.todocompanion.app.ui.components.AppTextField(inToken, { inToken = it }, label = { Text("Their proof token (TDC1|…)") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.FilledTonalButton(enabled = inToken.isNotBlank(), onClick = {
                        producedToken = com.todocompanion.app.domain.done.PeerSign.coSign(inToken.trim())
                        if (producedToken == null) android.widget.Toast.makeText(ctx, "Couldn't sign that", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("Sign it") }
                    producedToken?.let { tok ->
                        Spacer(Modifier.height(10.dp))
                        Text("Hand this back — it's your co-signature:", style = MaterialTheme.typography.labelMedium)
                        val coQr = remember(tok) { com.todocompanion.app.ui.util.ReceiptRenderer.qrBitmap(tok) }
                        coQr?.let { bmp -> Image(bmp.asImageBitmap(), "co-sign QR", Modifier.padding(vertical = 8.dp).size(180.dp)) }
                        Text(tok, style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        androidx.compose.material3.TextButton(onClick = {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, tok) }
                            runCatching { ctx.startActivity(android.content.Intent.createChooser(send, "Co-signature")) }
                        }) { Text("Share token") }
                    }
                }
            }

            // 2) Verify a co-signature.
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Verify a co-signature", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Paste a COSIGN|… token you received to check it's genuine.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    com.todocompanion.app.ui.components.AppTextField(verifyToken, { verifyToken = it }, label = { Text("Co-signature token (COSIGN|…)") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.FilledTonalButton(enabled = verifyToken.isNotBlank(), onClick = {
                        val v = com.todocompanion.app.domain.done.PeerSign.verify(verifyToken.trim())
                        verifyResult = if (v == null) "✗ Not a valid co-signature."
                        else {
                            val d = java.time.Instant.ofEpochMilli(v.at).atZone(ZoneId.systemDefault()).toLocalDate()
                            "✓ Valid — signed by ${v.signerId} on $d."
                        }
                    }) { Text("Verify") }
                    verifyResult?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium,
                            color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
