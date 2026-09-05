package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import kotlin.math.roundToInt
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.todocompanion.app.domain.PeriodRange
import com.todocompanion.app.domain.done.Accomplishment
import com.todocompanion.app.domain.done.LivingRecord
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.done.DoneKind
import com.todocompanion.app.domain.done.DoneRecord
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.DoneTick
import com.todocompanion.app.ui.components.PeriodSwitcher
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
    val settings by vm.settings.collectAsState()
    val zone = ZoneId.systemDefault()
    val ctx = LocalContext.current

    val listNameById = remember(lists) { lists.associate { it.id to it.name } }
    val feed = remember(tasks, habits, checkins, timeEntries) { DoneRecord.build(tasks, habits, checkins, timeEntries, zone) }
    val today = LocalDate.now()
    // Memories look across the whole history regardless of the range picker.
    val onThisDay = remember(feed) { DoneRecord.onThisDay(feed, today) }

    // Coherence Move 7 — the record is scoped by the shared period switcher (Day·Week·Month·Year·All);
    // everything below reads the ranged slice so the totals, trophy case and feed all reflect the same
    // span. ALL is the lifetime record; YEAR is the calendar year. The window comes from the one
    // [PeriodRange.window] every review surface shares (anchored at today, clamped to today).
    var range by remember { mutableStateOf(PeriodRange.ALL) }
    val bounds = range.window(today.toEpochDay(), settings.weekStart, today.toEpochDay()).range
    val rangedFeed = remember(feed, range) { feed.filter { it.epochDay in bounds } }
    val stats = remember(rangedFeed) { DoneRecord.stats(rangedFeed) }
    val wins = remember(rangedFeed) { rangedFeed.filter { it.isWin } }

    // R32 · Living Record read-side. Tag names per task power the skills roll-up.
    val taskTags by vm.taskTags.collectAsState()
    val allTags by vm.tags.collectAsState()
    val tagNamesByTask = remember(taskTags, allTags) {
        val nameById = allTags.associate { it.id to it.name }
        taskTags.groupBy { it.taskId }.mapValues { e -> e.value.mapNotNull { nameById[it.tagId] } }
    }
    val heat = remember(rangedFeed) { com.todocompanion.app.domain.done.LivingRecord.heatmap(rangedFeed) }
    val milestones = remember(rangedFeed) { com.todocompanion.app.domain.done.LivingRecord.milestones(rangedFeed, today) }
    val patternInsights = remember(rangedFeed) { com.todocompanion.app.domain.done.LivingRecord.insights(rangedFeed, today) }
    // Track 1.1 — the felt dimension for The Record: how the record's window FELT, plus 1–2 cross-stream
    // findings that relate finishing to how days felt (descriptive only). Lifetime clamps to a finite window.
    val dayLogs by vm.dayLogs.collectAsState()
    // The felt lane clamps the all-time span to a finite window (~10 years) so the mood fold stays cheap;
    // every bounded range keeps its own start/end (both are already <= today).
    val feltStart = maxOf(bounds.first, today.toEpochDay() - 3650)
    val feltEnd = bounds.last
    val recordFelt = remember(range, dayLogs) { vm.feltSummary(feltStart, feltEnd) }
    // Cross-stream insights are heavier (per-day activity minutes), so cap their window to ~2 years.
    val insightStart = maxOf(feltStart, feltEnd - 729)
    val recordFeltInsights = remember(range, dayLogs, checkins, timeEntries) { vm.reviewInsightsFor(insightStart, feltEnd).take(2) }
    // Track 3.1 — the felt × output ledger: join finishing (per-day output) to how days felt, honestly.
    val outputLedger = remember(range, dayLogs, feed) {
        com.todocompanion.app.domain.FeltOutputLedger.compute(feltStart, feltEnd, feed, dayLogs)
    }
    val skills = remember(rangedFeed, tagNamesByTask, listNameById) { com.todocompanion.app.domain.done.LivingRecord.skills(rangedFeed, tagNamesByTask, listNameById) }
    val sealedNotes by vm.sealedNotes.collectAsState()
    var writeLetter by remember { mutableStateOf(false) }
    var openLetter by remember { mutableStateOf<com.todocompanion.app.data.entity.SealedNoteEntity?>(null) }
    var showWrapped by remember { mutableStateOf(false) }

    var query by remember { mutableStateOf("") }
    var winsOnly by remember { mutableStateOf(false) }
    var showBrag by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    // R31 #7 — extensive filters over the record: by type, by list, and a newest/oldest sort.
    val typeFilter = remember { mutableStateListOf<com.todocompanion.app.domain.done.DoneKind>() } // empty = every type
    var listFilter by remember { mutableStateOf<String?>(null) }                                    // null = every list
    var oldestFirst by remember { mutableStateOf(false) }
    // Day groups are FOLDED by default (the history gets long); this holds the days the user opened.
    val expandedDays = remember { mutableStateListOf<Long>() }
    var listMenu by remember { mutableStateOf(false) }

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
    var showImpact by remember { mutableStateOf(false) }
    val impact = remember(rangedFeed, tasks) { com.todocompanion.app.domain.done.Impact.build(rangedFeed, tasks) }
    val integrity = remember(feed, settings.integritySeal) {
        com.todocompanion.app.domain.done.Integrity.status(feed, com.todocompanion.app.domain.done.Integrity.Seal.decode(settings.integritySeal))
    }
    if (showImpact) {
        // Impact map owns its own period switcher, independent of the feed's range.
        ImpactScreen(feed, tasks, today, settings.weekStart, listNameById, onOpenTask = onOpenTask, onBack = { showImpact = false })
        return
    }
    if (showCoSign) {
        CoSignScreen(vm, onBack = { showCoSign = false })
        return
    }
    if (showWrapped) {
        // Track 1 (Unify) — Wrapped reads the unified year spine (felt + achievement counts) over the ONE
        // canonical calendar-year window, so its numbers match the "Year, reviewed" screen and the drawer's
        // annual report exactly.
        val (yStart, yEnd) = com.todocompanion.app.domain.YearReviewed.calendarYearWindow(today.year, today.toEpochDay())
        val yearRecap = remember(feed, today) { vm.yearReviewed(yStart, yEnd) }
        // The Wrapped story shares as a PNG through the modular DayCard year card (professional-capable),
        // honouring the saved period-share style — the one card system every review surface now shares.
        WrappedScreen(feed, today, yearRecap, onShare = { vm.shareYearReview(yStart, yEnd) }, onBack = { showWrapped = false })
        return
    }

    // R32 Living Record #3 — search over every finished item: tokenised AND-matching across the title,
    // outcome note, its list name and its tags, so multi-word queries actually narrow the archive.
    val shown = remember(rangedFeed, query, winsOnly, listNameById, tagNamesByTask, typeFilter.toList(), listFilter) {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val types = typeFilter.toSet()
        rangedFeed.filter { a ->
            if (winsOnly && !a.isWin) return@filter false
            if (types.isNotEmpty() && a.kind !in types) return@filter false
            if (listFilter != null && a.listId != listFilter) return@filter false
            if (terms.isEmpty()) return@filter true
            val hay = buildString {
                append(a.title.lowercase()); append(' ')
                a.outcome?.let { append(it.lowercase()); append(' ') }
                a.listId?.let { listNameById[it] }?.let { append(it.lowercase()); append(' ') }
                tagNamesByTask[a.refId]?.forEach { append(it.lowercase()); append(' ') }
            }
            terms.all { hay.contains(it) }
        }
    }
    val byDay = remember(shown, oldestFirst) {
        val cmp = if (oldestFirst) compareBy<Long> { it } else compareByDescending<Long> { it }
        shown.groupBy { it.epochDay }.toSortedMap(cmp)
    }
    // Lists that actually appear in the (ranged) record, for the list filter menu.
    val feedListIds = remember(rangedFeed) { rangedFeed.mapNotNull { it.listId }.distinct() }
    val anyFilterActive = query.isNotBlank() || winsOnly || typeFilter.isNotEmpty() || listFilter != null

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
                        // Track 2.6 — the Julia-Evans-structured brag doc over the current range, shared as Markdown.
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Brag document (structured)…") }, onClick = {
                            exportMenu = false
                            val md = DoneRecord.bragDocMarkdown(feed, listNameById, bounds.first, bounds.last, today)
                            vm.exportBragDoc(md, "brag-document-structured.md") { loc -> android.widget.Toast.makeText(ctx, if (loc != null) "Brag document saved to $loc" else "Save failed", android.widget.Toast.LENGTH_LONG).show() }
                        })
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
            // The one shared period switcher — scope the whole record to a window.
            item(key = "range") {
                PeriodSwitcher(selected = range, onSelect = { range = it })
            }
            // Totals + personal bests, over the chosen range.
            item(key = "stats") { LifetimeCard(stats, recordRangeLabel(range), settings.hideStreaks) }
            // R32 #1 — the done heatmap: a year of finishes at a glance.
            item(key = "heatmap") { HeatmapCard(heat, today) }
            // R32 #5 — year-in-review story launcher.
            item(key = "wrapped") { WrappedTeaser { showWrapped = true } }
            // R32 #2 — milestone shelf: earned badges + the next target, each shareable.
            if (milestones.isNotEmpty()) item(key = "milestones") { MilestonesCard(milestones) { m -> vm.shareMilestone(m) } }
            // R32 #4 — heuristic pattern insights (best day, peak hour, focus lift).
            if (patternInsights.isNotEmpty() || recordFelt.hasData) item(key = "insights") { PatternInsightsCard(patternInsights, recordFelt, recordFeltInsights, recordRangeLabel(range)) }
            // Track 3.1 — output vs. how you felt: descriptive, non-causal findings joining the two.
            if (outputLedger.hasData) item(key = "ledger") { OutputVsFeltCard(outputLedger) }
            // R32 #8 — skills ledger: finished work rolled up into evidence-backed areas.
            if (skills.isNotEmpty()) item(key = "skills") { SkillsCard(skills) }
            // R32 #7 — sealed letters to your future self (reveal when their day comes).
            item(key = "sealed") { SealedLettersCard(sealedNotes, today, onWrite = { writeLetter = true }, onOpen = { openLetter = it }) }
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
            // R31 #7 — extensive filters: search, type, list, wins, and a sort toggle.
            item(key = "filter") {
                Column {
                    com.todocompanion.app.ui.components.AppTextField(
                        query, { query = it }, singleLine = true,
                        label = { Text("Search what you've done") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(8.dp))
                    // Type filters — toggle any subset; none selected = every type.
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val types = listOf(
                            com.todocompanion.app.domain.done.DoneKind.TASK to "Tasks",
                            com.todocompanion.app.domain.done.DoneKind.GOAL to "Goals",
                            com.todocompanion.app.domain.done.DoneKind.PROJECT to "Projects",
                            com.todocompanion.app.domain.done.DoneKind.HABIT to "Habits",
                            com.todocompanion.app.domain.done.DoneKind.FOCUS to "Focus",
                        )
                        types.forEach { (k, lbl) ->
                            val on = k in typeFilter
                            FilterChip(selected = on, onClick = { if (on) typeFilter.remove(k) else typeFilter.add(k) }, label = { Text(lbl) })
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = winsOnly, onClick = { winsOnly = !winsOnly }, label = { Text("Wins only") },
                            leadingIcon = { Icon(if (winsOnly) Icons.Filled.Star else Icons.Filled.StarBorder, null, Modifier.size(16.dp)) })
                        // List filter — pick one of the lists that appears in the record.
                        if (feedListIds.isNotEmpty()) Box {
                            FilterChip(selected = listFilter != null, onClick = { listMenu = true },
                                label = { Text(listFilter?.let { listNameById[it] ?: "List" } ?: "Any list") },
                                trailingIcon = { Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp)) })
                            androidx.compose.material3.DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                                androidx.compose.material3.DropdownMenuItem(text = { Text("Any list") }, onClick = { listFilter = null; listMenu = false })
                                feedListIds.forEach { id ->
                                    androidx.compose.material3.DropdownMenuItem(text = { Text(listNameById[id] ?: "List") }, onClick = { listFilter = id; listMenu = false })
                                }
                            }
                        }
                        // Sort order.
                        FilterChip(selected = oldestFirst, onClick = { oldestFirst = !oldestFirst },
                            label = { Text(if (oldestFirst) "Oldest first" else "Newest first") },
                            leadingIcon = { Icon(Icons.Filled.SwapVert, null, Modifier.size(16.dp)) })
                        if (anyFilterActive) androidx.compose.material3.TextButton(onClick = {
                            query = ""; winsOnly = false; typeFilter.clear(); listFilter = null
                        }) { Text("Clear") }
                    }
                    Spacer(Modifier.size(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${shown.size} of ${rangedFeed.size} shown", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        val allDays = byDay.keys.toList()
                        val allOpen = allDays.isNotEmpty() && allDays.all { it in expandedDays }
                        androidx.compose.material3.TextButton(onClick = {
                            if (allOpen) expandedDays.clear() else { expandedDays.clear(); expandedDays.addAll(allDays) }
                        }) { Text(if (allOpen) "Collapse all" else "Expand all") }
                    }
                }
            }
            // The feed, grouped by day — each day FOLDED by default (tap the header to open it).
            byDay.forEach { (epochDay, dayItems) ->
                val open = epochDay in expandedDays
                val ordered = if (oldestFirst) dayItems.sortedBy { it.whenMillis } else dayItems.sortedByDescending { it.whenMillis }
                item(key = "day-$epochDay") {
                    DayHeader(LocalDate.ofEpochDay(epochDay), today, dayItems, expanded = open,
                        onToggle = { if (open) expandedDays.remove(epochDay) else expandedDays.add(epochDay) })
                }
                if (open) items(ordered, key = { it.kind.name + it.refId + it.whenMillis }) { a ->
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

    if (writeLetter) WriteLetterDialog(today, onDismiss = { writeLetter = false }, onSeal = { title, body, reveal ->
        vm.sealLetter(title, body, reveal); writeLetter = false
    })
    openLetter?.let { n ->
        LetterRevealDialog(n, today, currentCount = feed.size, intact = vm.letterIntact(n),
            onDismiss = { openLetter = null },
            onAck = { vm.acknowledgeLetter(n); openLetter = null },
            onDelete = { vm.deleteLetter(n.id); openLetter = null })
    }
}

/** Coherence Move 7 — the label for a scoped record window. ALL keeps its "Lifetime" wording (so the
 *  totals card still reads "Lifetime record"); every other period reads as a natural phrase. The 4mo /
 *  6mo / 5y / 10y granularities the record used to offer are intentionally dropped — Year and All (the
 *  lifetime record) cover those intents under the one shared switcher. */
internal fun recordRangeLabel(range: PeriodRange): String = when (range) {
    PeriodRange.DAY -> "Today"
    PeriodRange.WEEK -> "This week"
    PeriodRange.MONTH -> "This month"
    PeriodRange.YEAR -> "This year"
    PeriodRange.ALL -> "Lifetime"
}

@Composable
private fun LifetimeCard(s: com.todocompanion.app.domain.done.DoneStats, rangeLabel: String, hideStreaks: Boolean = false) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
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
            // Track 2.7 — with streaks hidden, show consistency (active days) instead of the chain counters.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (hideStreaks) {
                    Stat("${s.activeDays}", "active days")
                } else {
                    Stat("🔥 ${s.currentStreakDays}", "active-day streak")
                    Stat("${s.longestStreakDays}", "best active-day")
                }
                Stat("${s.bestDayCount}", "best day")
                Stat("${s.totalWins}", "wins")
            }
        }
    }
}

@Composable
private fun ComebackCard(lines: List<String>) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("📈 Comeback", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(6.dp))
            lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

@Composable
private fun HonestyCard(overall: com.todocompanion.app.domain.done.DoneRecord.LedgerRow, worst: List<com.todocompanion.app.domain.done.DoneRecord.LedgerRow>) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("⚖️ Estimate vs. actual", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(4.dp))
            // Track 1.4 — verdict from the one shared Calibration engine (the unified VERDICT_TOLERANCE), presentation unchanged.
            val ratio = overall.ratio.toDouble()
            val pct = (ratio * 100).toInt()
            val over = com.todocompanion.app.domain.Calibration.percentOff(ratio)
            Text(
                when (com.todocompanion.app.domain.Calibration.classify(ratio, com.todocompanion.app.domain.Calibration.VERDICT_TOLERANCE)) {
                    com.todocompanion.app.domain.Calibration.Verdict.ON_POINT -> "Your estimates are on the money — finished work took ${pct}% of the estimate."
                    com.todocompanion.app.domain.Calibration.Verdict.OVER -> "Finished work runs long: ${pct}% of your estimate on average. Pad by ~${over}%."
                    com.todocompanion.app.domain.Calibration.Verdict.UNDER -> "You beat your estimates — work took only ${pct}% of what you planned."
                },
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
        // Two lines so the distinct "active-day streak" / "best active-day" labels wrap instead of truncating.
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun OnThisDayCard(items: List<Accomplishment>, listNameById: Map<String, String>) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
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
                        Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
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
private fun DayHeader(day: LocalDate, today: LocalDate, items: List<Accomplishment>, expanded: Boolean = true, onToggle: (() -> Unit)? = null) {
    val label = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> "${day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}, ${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}" +
            (if (day.year != today.year) " ${day.year}" else "")
    }
    val focusMin = items.filter { it.kind == DoneKind.FOCUS }.sumOf { it.durationMin }
    // R31 #7 — the whole header is a fold toggle so a long history collapses to a scannable list of days.
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).let { if (onToggle != null) it.clickable { onToggle() } else it }
        .padding(top = 6.dp, bottom = 4.dp, start = 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onToggle != null) {
            Icon(if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(4.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.weight(1f))
        Text(buildString {
            append("${items.count { it.isTaskLike }} done")
            val h = items.count { it.kind == DoneKind.HABIT }
            if (h > 0) append(" · $h habit${if (h > 1) "s" else ""}")
            if (focusMin > 0) append(" · ${focusMin}m focus")
        }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AccomplishmentRow(a: Accomplishment, listName: String?, onOpen: () -> Unit, onToggleWin: (() -> Unit)?, onShareReceipt: (() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().clickable(enabled = a.isTaskLike) { onOpen() }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                // A finished task shows the app's modern completion mark; other kinds keep their glyph.
                if (a.kind == DoneKind.TASK) DoneTick() else Text(kindGlyph(a.kind))
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
                    tint = if (a.isWin) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline)
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
                com.todocompanion.app.ui.components.OptionChips(listOf("month", "quarter", "year", "all"), range, { range = it }, wrap = false, spacing = 6) {
                    when (it) { "month" -> "This month"; "quarter" -> "Last 3 mo"; "year" -> "This year"; else -> "All time" }
                }
                Spacer(Modifier.size(10.dp))
                Text("Group by", style = MaterialTheme.typography.labelMedium)
                com.todocompanion.app.ui.components.OptionChips(listOf("list", "day", "flat"), group, { group = it }, wrap = false, spacing = 6) {
                    when (it) { "list" -> "List"; "day" -> "Day"; else -> "Flat" }
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
    feed: List<Accomplishment>, tasks: List<TaskEntity>, today: LocalDate, weekStart: Int,
    listNameById: Map<String, String>, onOpenTask: (String) -> Unit, onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // Coherence Move 7 — the Impact map scopes to its own window via the one shared period switcher.
    var range by remember { mutableStateOf(PeriodRange.ALL) }
    val bounds = range.window(today.toEpochDay(), weekStart, today.toEpochDay()).range
    val g = remember(feed, tasks, range) { com.todocompanion.app.domain.done.Impact.build(feed.filter { it.epochDay in bounds }, tasks) }
    Scaffold(topBar = {
        TopAppBar(expandedHeight = 52.dp,
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text("Impact map") })
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "impact-range") {
                PeriodSwitcher(selected = range, onSelect = { range = it })
            }
            if (g.nodes.isEmpty()) {
                item(key = "impact-empty") {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🕸️", style = MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.height(8.dp))
                        Text("No goal-linked work in ${recordRangeLabel(range).lowercase()} yet. Finish tasks under a goal or project and they'll web up here.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            } else {
                val maxMin = (g.nodes.maxOfOrNull { it.totalMinutes } ?: 0).coerceAtLeast(1)
                item(key = "impact-head") {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(recordRangeLabel(range), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

// ═══════════════════════════════════════════════════════════════════════════════════════════════
//  R32 · The Living Record — heatmap, milestones, insights, skills, sealed letters, year-in-review.
// ═══════════════════════════════════════════════════════════════════════════════════════════════

/** #1 — GitHub-style day grid: 26 weeks of finishes, darker = more done. Renders with plain boxes so it
 *  stays crisp and theme-correct without a Canvas. */
@Composable
private fun HeatmapCard(heat: Map<Long, LivingRecord.HeatCell>, today: LocalDate) {
    val weeks = 26
    val startSunday = today.minusDays(((today.dayOfWeek.value % 7) + (weeks - 1) * 7).toLong())
    val maxCount = (heat.values.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)
    val base = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Your season of finishing", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Each square is a day — darker means more done.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (w in 0 until weeks) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (d in 0 until 7) {
                        val day = startSunday.plusDays((w * 7 + d).toLong())
                        val cell = heat[day.toEpochDay()]
                        val color = when {
                            day.isAfter(today) -> Color.Transparent
                            cell == null -> empty
                            else -> base.copy(alpha = (0.28f + 0.72f * (cell.count.toFloat() / maxCount)).coerceIn(0.28f, 1f))
                        }
                        Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(color))
                    }
                }
            }
        }
    }
}

/** #5 — a soft launcher into the year-in-review story. */
@Composable
private fun WrappedTeaser(onOpen: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("✨", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Your year, wrapped", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("A private, swipe-through story of everything you did", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

/** #2 — milestone shelf: earned badges (tap to share a verifiable card) + the single next target. */
@Composable
private fun MilestonesCard(milestones: List<LivingRecord.Milestone>, onShare: (LivingRecord.Milestone) -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Milestones", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                milestones.forEach { m ->
                    val accent = if (m.reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    Column(
                        Modifier.width(128.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (m.reached) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f))
                            .then(if (m.reached) Modifier.clickable { onShare(m) } else Modifier)
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(m.emoji, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.size(6.dp))
                        Text(m.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.size(4.dp))
                        if (!m.reached) {
                            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.outlineVariant)) {
                                Box(Modifier.fillMaxWidth(m.progress).height(5.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
                            }
                            Spacer(Modifier.size(4.dp))
                        }
                        Text(if (m.reached) "Tap to share" else m.detail, style = MaterialTheme.typography.labelSmall, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** #4 — heuristic pattern insights. */
@Composable
private fun PatternInsightsCard(
    insights: List<LivingRecord.Insight>,
    felt: com.todocompanion.app.domain.FeltState.FeltSummary,
    feltInsights: List<com.todocompanion.app.domain.ReviewInsights.Insight>,
    rangeLabel: String,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What your record reveals", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            insights.forEach { i ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(i.emoji, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(10.dp))
                    Text(i.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            // Track 1.1 — the felt dimension: how the window felt, and how finishing related to it.
            if (felt.hasData && felt.ratedDays > 0) {
                val stars = felt.avgRating.roundToInt().coerceIn(1, 5)
                val emo = if (felt.dominantEmotion.isNotBlank()) " and most often felt ${felt.dominantEmotion.lowercase(java.util.Locale.getDefault())}" else ""
                Row(verticalAlignment = Alignment.Top) {
                    Text("💗", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(10.dp))
                    Text("Across ${rangeLabel.lowercase(java.util.Locale.getDefault())}, your days averaged ${"★".repeat(stars)} (${String.format(java.util.Locale.US, "%.1f", felt.avgRating)})$emo.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            feltInsights.forEach { i ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("🔍", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(10.dp))
                    Text(i.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (feltInsights.isNotEmpty()) Text("These are descriptive patterns, not cause and effect.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Computed on-device from your own history — nothing leaves the phone.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Track 3.1 — "Output vs. how you felt": honest, descriptive findings joining finishing to how days
 *  felt, each carrying its confidence, with the non-causal disclaimer front and centre. */
@Composable
private fun OutputVsFeltCard(ledger: com.todocompanion.app.domain.FeltOutputLedger.Ledger) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Output vs. how you felt", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Over ${ledger.pairedDays} days you both rated and finished things — averaging ${String.format(Locale.US, "%.1f", ledger.avgOutputPerDay)} finished a day at ${String.format(Locale.US, "%.1f", ledger.avgRating)}★.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ledger.findings.forEach { f ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("⚖️", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text("${f.confidence.label} · ${f.sampleSize} days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            Text(ledger.disclaimer, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Computed on-device from your own record — nothing leaves the phone.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** #8 — skills ledger: finished work rolled into areas, each with a count and hours as evidence. */
@Composable
private fun SkillsCard(skills: List<LivingRecord.Skill>) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Skills you're building", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Where your finished work adds up — backed by the entries behind it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            skills.take(8).forEach { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.fromTag) "🏷️" else "📂", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(10.dp))
                    Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(buildString {
                        append("${s.count}")
                        if (s.minutes >= 60) append(" · ${s.minutes / 60}h")
                    }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** #7 — sealed letters to your future self: list, reveal when due, and a write entry. */
@Composable
private fun SealedLettersCard(
    notes: List<com.todocompanion.app.data.entity.SealedNoteEntity>, today: LocalDate,
    onWrite: () -> Unit, onOpen: (com.todocompanion.app.data.entity.SealedNoteEntity) -> Unit,
) {
    val todayDay = today.toEpochDay()
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✉️", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(8.dp))
                Text("Letters to your future self", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                androidx.compose.material3.TextButton(onClick = onWrite) { Text("Write") }
            }
            if (notes.isEmpty()) {
                Text("Seal a note today; it opens on a date you choose, beside everything you got done since. Tamper-evident and fully offline.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else notes.sortedBy { it.revealEpochDay }.forEach { n ->
                val ready = todayDay >= n.revealEpochDay
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .then(if (ready) Modifier.clickable { onOpen(n) } else Modifier)
                    .background(if (ready) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))
                    .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (ready) Icons.Filled.MarkEmailUnread else Icons.Filled.Lock, null,
                        tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val revealDate = LocalDate.ofEpochDay(n.revealEpochDay)
                        Text(if (ready) "Ready to open" else "Opens ${revealDate} · ${n.revealEpochDay - todayDay} days",
                            style = MaterialTheme.typography.labelSmall, color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Write & seal a new letter. Reveal date is a simple horizon chip so there's no date-picker to fuss with. */
@Composable
private fun WriteLetterDialog(today: LocalDate, onDismiss: () -> Unit, onSeal: (String, String, Long) -> Unit) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    val horizons = listOf("6 months" to 6L, "1 year" to 12L, "2 years" to 24L, "5 years" to 60L)
    var months by remember { mutableLongStateOf(12L) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(enabled = body.isNotBlank(), onClick = {
            onSeal(title, body, today.plusMonths(months).toEpochDay())
        }) { Text("Seal it") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("A letter to future you") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                com.todocompanion.app.ui.components.AppTextField(title, { title = it }, singleLine = true, label = { Text("Title (optional)") }, modifier = Modifier.fillMaxWidth())
                com.todocompanion.app.ui.components.AppTextField(body, { body = it }, singleLine = false, label = { Text("What do you want to tell yourself?") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
                Text("Open it in…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                com.todocompanion.app.ui.components.OptionChips(horizons, horizons.firstOrNull { it.second == months }, { months = it.second }, wrap = false, spacing = 6) { it.first }
                Text("Sealed with a tamper-evident hash. It can't be edited once sealed.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/** Reveal a due letter beside a diff of what you accomplished since sealing it. */
@Composable
private fun LetterRevealDialog(
    n: com.todocompanion.app.data.entity.SealedNoteEntity, today: LocalDate, currentCount: Int, intact: Boolean,
    onDismiss: () -> Unit, onAck: () -> Unit, onDelete: () -> Unit,
) {
    val sealedOn = LocalDate.ofEpochDay(n.createdEpochDay)
    // Track 3.4 — the "what's changed since you sealed this" diff, from the pure domain helper.
    val diff = com.todocompanion.app.domain.SealedLetters.diff(n.sealedCount, currentCount, n.createdEpochDay, today.toEpochDay())
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(onClick = onAck) { Text("Keep") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        title = { Text(n.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sealed on $sealedOn", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(n.body, style = MaterialTheme.typography.bodyLarge)
                androidx.compose.material3.HorizontalDivider()
                Text("What's changed since you sealed this", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Text(diff.phrase, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                diff.paceLine?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(if (intact) "✓ Untouched since sealing (hash verified)." else "⚠ This letter's text no longer matches its seal.",
                    style = MaterialTheme.typography.labelSmall, color = if (intact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        },
    )
}

/** #5 — year-in-review story: bold, scroll-through slides generated on-device from the record. Track 1.3 —
 *  the achievement counts AND the "how your year felt" slides both read from the one [YearReviewed] spine. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun WrappedScreen(feed: List<Accomplishment>, today: LocalDate, recap: com.todocompanion.app.domain.YearReviewed.Recap, onShare: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val year = today.year
    val ofYear = remember(feed) { feed.filter { LocalDate.ofEpochDay(it.epochDay).year == year } }
    data class Slide(val emoji: String, val big: String, val cap: String, val bg: Color)
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary
    fun oneDp(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
    val slides = buildList {
        add(Slide("📖", "$year", "Your year, on the record", primary))
        add(Slide("🏁", "${recap.tasksFinished}", "tasks finished", tertiary))
        if (recap.focusMinutesDone >= 60) add(Slide("🎯", "${recap.focusHoursDone}h", "of focused time", secondary))
        if (recap.habitDaysKept > 0) add(Slide("🔁", "${recap.habitDaysKept}", "habit days kept", primary))
        if (recap.longestActiveStreakDays >= 3) add(Slide("🔥", "${recap.longestActiveStreakDays}", "longest active-day streak", tertiary))
        if (recap.biggestMonthValue in 1..12) add(Slide("📅", java.time.Month.of(recap.biggestMonthValue).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()), "was your biggest month · ${recap.biggestMonthCount} done", secondary))
        if (recap.winsMarked > 0) add(Slide("⭐", "${recap.winsMarked}", "moments you marked a win", primary))
        add(Slide("🏅", "${recap.activeDays}", "days you showed up", tertiary))
        // Track 1.3 — how your year FELT: average rating, the feeling you named most, and a standout highlight.
        if (recap.ratedDays > 0) add(Slide("💗", "${oneDp(recap.avgRating)}★", "how your year felt, on average", tertiary))
        if (recap.topEmotionWord.isNotBlank() && recap.topEmotionCount >= 3)
            add(Slide("🫧", recap.topEmotionWord, "the feeling you named most · ${recap.topEmotionCount} days", secondary))
        if (recap.highlightText.isNotBlank())
            add(Slide("✨", "A highlight", recap.highlightText, primary))
    }
    Scaffold(topBar = {
        TopAppBar(expandedHeight = 52.dp, title = { Text("$year, wrapped") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { if (ofYear.isNotEmpty()) IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share your year") } })
    }) { padding ->
        if (ofYear.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("✨", style = MaterialTheme.typography.displaySmall)
                Text("Nothing finished in $year yet — your story starts with the first thing you do.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            slides.forEach { s ->
                Surface(shape = RoundedCornerShape(26.dp), color = s.bg.copy(alpha = .16f), modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp)) {
                    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(s.emoji, style = MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.size(10.dp))
                        Text(s.big, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = s.bg, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.size(6.dp))
                        Text(s.cap, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            androidx.compose.material3.FilledTonalButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Share your year")
            }
            Text("Generated on your device from your private record — the card never leaves the phone until you pick where to send it.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}
