package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.PeriodRange
import com.todocompanion.app.domain.PeriodicNotes
import com.todocompanion.app.domain.weekStartOf
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import com.todocompanion.app.ui.components.appCardColor
import com.todocompanion.app.ui.components.KairoScreenScaffold
import com.todocompanion.app.ui.theme.LocalKairoColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Journal hub — Periodic Notes across day · week · month · year, each linked to that period's own
 * review/recap. Improvised from Obsidian's Periodic Notes: instead of a filename date-format we resolve
 * a note by period-window, and instead of a blank page each note is bound to Kairo's matching review
 * (the moat). Navigate by the shared period switcher + prev/next, drill through the time-tree
 * (contains / rolls up to), and — for the day — a month calendar with a dot on every journalled day.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PeriodicNotesScreen(
    vm: AppViewModel,
    initialPeriod: PeriodRange,
    initialAnchor: Long,
    onOpenNote: (String) -> Unit,
    onOpenReview: (PeriodRange, Long) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val settings by vm.settings.collectAsState()
    val notesList by vm.notes.collectAsState()   // recompose dots/status when notes change
    val kairo = LocalKairoColors.current
    val today = remember { LocalDate.now().toEpochDay() }
    val weekStart = settings.weekStart

    var period by remember { mutableStateOf(if (initialPeriod == PeriodRange.ALL) PeriodRange.DAY else initialPeriod) }
    var anchor by remember { mutableLongStateOf(if (initialAnchor <= 0L) today else initialAnchor) }

    val win = remember(period, anchor, weekStart, today) { period.window(anchor, weekStart, today) }
    val title = remember(period, win.startDay) { PeriodicNotes.titleFor(period, win.startDay) }
    val noteId = remember(notesList, period, anchor, weekStart) { vm.periodicNoteId(period, anchor) }
    val existingNote = remember(notesList, noteId) { notesList.firstOrNull { it.id == noteId } }
    val streak = remember(notesList) { vm.journalStreak() }
    // "Next" exists only while this isn't the current/latest period (its window end is clamped to today).
    val canNext = win.endDay < today

    fun openThis() = vm.openPeriodicNote(period, anchor) { onOpenNote(it) }

    KairoScreenScaffold(
        title = "Journal",
        onBack = onBack,
        actions = { TextButton(onClick = { anchor = today }) { Text(nowLabel(period)) } },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
        // ── Granularity switcher (Day · Week · Month · Year) ──
        com.todocompanion.app.ui.components.PeriodSwitcher(
            selected = period,
            onSelect = { period = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            periods = PeriodicNotes.GRANULARITIES,
        )

        // ── Period stepper: ◀  title  ▶ ──
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { anchor = PeriodicNotes.step(period, anchor, forward = false) }) {
                Icon(Icons.Filled.ChevronLeft, "Previous ${PeriodicNotes.noun(period)}")
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val rel = relativeLabel(period, win.startDay, today, weekStart)
                if (rel != null) Text(rel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { if (canNext) anchor = minOf(PeriodicNotes.step(period, anchor, forward = true), today) }, enabled = canNext) {
                Icon(Icons.Filled.ChevronRight, "Next ${PeriodicNotes.noun(period)}", tint = if (canNext) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant)
            }
        }

        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.size(2.dp))
            // ── Hero: the note + its review ──
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                        Text(PeriodicNotes.emojiFor(period), style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(PeriodicNotes.noun(period).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        val sub = existingNote?.let { n ->
                            n.body.lineSequence().map { it.trim().removePrefix("#").trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("<!--") }
                                ?.takeIf { it.isNotBlank() } ?: "Started — nothing written yet"
                        } ?: "No note yet for this ${periodWord(period)}"
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { openThis() }, modifier = Modifier.weight(1f)) {
                        Text(if (noteId != null) "Open note" else "Create ${PeriodicNotes.noun(period)}")
                    }
                    OutlinedButton(onClick = { onOpenReview(period, anchor) }) { Text(reviewLabel(period)) }
                }
                if (settings.periodicRecapEmbed) {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        "This ${periodWord(period)}'s recap is folded into the note automatically.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TextButton(onClick = { vm.savePeriodRecapToNote(period, anchor) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text("Add this ${periodWord(period)}'s recap to the note")
                    }
                }
            }

            // ── Journaling streak (gentle, shame-free) ──
            if (streak > 0) {
                AppCard(onClick = { period = PeriodRange.DAY; anchor = today; openThis() }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔥", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(10.dp))
                        Text("$streak-day journaling streak", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("Write today →", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // ── Day: a month calendar with a dot on every journalled day ──
            if (period == PeriodRange.DAY) {
                MonthDotGrid(vm, anchorDay = anchor, today = today, weekStart = weekStart, onOpenDay = { d ->
                    anchor = d; vm.openPeriodicNote(PeriodRange.DAY, d) { onOpenNote(it) }
                })
            }

            // ── The time-tree: rolls up to (parent) + contains (children) ──
            val parent = PeriodicNotes.parentOf(period)
            if (parent != null) {
                val pWin = parent.window(anchor, weekStart, today)
                val pHas = vm.periodicNoteId(parent, anchor) != null
                com.todocompanion.app.ui.components.CardLabel("ROLLS UP TO")
                PeriodChip(
                    emoji = PeriodicNotes.emojiFor(parent),
                    label = PeriodicNotes.titleFor(parent, pWin.startDay),
                    hasNote = pHas, kairo = kairo,
                ) { period = parent }
            }

            val child = PeriodicNotes.childOf(period)
            if (child != null && period != PeriodRange.DAY) {
                com.todocompanion.app.ui.components.CardLabel("CONTAINS")
                val kids = remember(period, anchor, weekStart) { childAnchors(period, win.startDay, weekStart) }
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    kids.forEach { kAnchor ->
                        val kWin = child.window(kAnchor, weekStart, today)
                        val kHas = vm.periodicNoteId(child, kAnchor) != null
                        val future = kWin.startDay > today
                        PeriodChip(
                            emoji = PeriodicNotes.emojiFor(child),
                            label = childLabel(child, kWin.startDay),
                            hasNote = kHas, kairo = kairo, dim = future,
                        ) { if (!future) { period = child; anchor = kAnchor } }
                    }
                }
            }
            Spacer(Modifier.size(24.dp))
        }
        }
    }
}

/** A small pill: emoji + label with a filled dot when a note exists for that period. */
@Composable
private fun PeriodChip(
    emoji: String,
    label: String,
    hasNote: Boolean,
    kairo: com.todocompanion.app.ui.theme.KairoColors,
    dim: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (hasNote) kairo.good.copy(alpha = 0.14f) else appCardColor(),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, color = if (dim) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
            if (hasNote) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(7.dp).clip(CircleShape).background(kairo.good))
            }
        }
    }
}

/** A compact month grid (weeks × 7). A dot marks any day that already has a daily note; tap opens/creates. */
@Composable
private fun MonthDotGrid(vm: AppViewModel, anchorDay: Long, today: Long, weekStart: Int, onOpenDay: (Long) -> Unit) {
    val kairo = LocalKairoColors.current
    val anchorDate = remember(anchorDay) { LocalDate.ofEpochDay(anchorDay) }
    val first = anchorDate.withDayOfMonth(1)
    val gridStart = weekStartOf(first, weekStart)
    val cells = remember(anchorDay, weekStart) { (0 until 42).map { gridStart.plusDays(it.toLong()) } }
    val dows = remember(weekStart) { (0 until 7).map { gridStart.plusDays(it.toLong()).dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()) } }
    AppCard(padding = 10.dp) {
        Row(Modifier.fillMaxWidth()) {
            dows.forEach { d -> Text(d, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.size(4.dp))
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { d ->
                    val ed = d.toEpochDay()
                    val inMonth = d.month == first.month
                    val isToday = ed == today
                    val future = ed > today
                    val has = inMonth && vm.periodicNoteId(PeriodRange.DAY, ed) != null
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).padding(2.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable(enabled = inMonth && !future) { onOpenDay(ed) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                d.dayOfMonth.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    !inMonth || future -> MaterialTheme.colorScheme.outlineVariant
                                    isToday -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            )
                            Box(Modifier.size(5.dp).clip(CircleShape).background(if (has) kairo.good else androidx.compose.ui.graphics.Color.Transparent))
                        }
                    }
                }
            }
        }
    }
}

// ── labels ──
private fun periodWord(p: PeriodRange) = when (p) { PeriodRange.DAY -> "day"; PeriodRange.WEEK -> "week"; PeriodRange.MONTH -> "month"; PeriodRange.YEAR -> "year"; PeriodRange.ALL -> "period" }
private fun nowLabel(p: PeriodRange) = when (p) { PeriodRange.DAY -> "Today"; PeriodRange.WEEK -> "This week"; PeriodRange.MONTH -> "This month"; PeriodRange.YEAR -> "This year"; PeriodRange.ALL -> "Now" }
private fun reviewLabel(p: PeriodRange) = when (p) { PeriodRange.DAY -> "Day review"; PeriodRange.WEEK -> "Weekly review"; PeriodRange.MONTH -> "Month recap"; PeriodRange.YEAR -> "Year recap"; PeriodRange.ALL -> "Review" }

private fun relativeLabel(p: PeriodRange, startDay: Long, today: Long, weekStart: Int): String? {
    return when (p) {
        PeriodRange.DAY -> when (startDay) { today -> "Today"; today - 1 -> "Yesterday"; today + 1 -> "Tomorrow"; else -> null }
        PeriodRange.WEEK -> {
            val cur = weekStartOf(LocalDate.ofEpochDay(today), weekStart).toEpochDay()
            when (startDay) { cur -> "This week"; cur - 7 -> "Last week"; else -> null }
        }
        PeriodRange.MONTH -> {
            val d = LocalDate.ofEpochDay(startDay); val t = LocalDate.ofEpochDay(today)
            when { d.year == t.year && d.month == t.month -> "This month"; d == t.minusMonths(1).withDayOfMonth(1) -> "Last month"; else -> null }
        }
        PeriodRange.YEAR -> {
            val y = LocalDate.ofEpochDay(startDay).year; val ty = LocalDate.ofEpochDay(today).year
            when (y) { ty -> "This year"; ty - 1 -> "Last year"; else -> null }
        }
        PeriodRange.ALL -> null
    }
}

private fun childLabel(child: PeriodRange, startDay: Long): String = runCatching {
    val d = LocalDate.ofEpochDay(startDay)
    when (child) {
        PeriodRange.DAY -> d.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()))
        PeriodRange.WEEK -> "w/" + d.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        PeriodRange.MONTH -> d.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))
        else -> d.toString()
    }
}.getOrDefault("")

/** Child anchors for a period's "contains" row: a week's days, a month's weeks, a year's months. */
private fun childAnchors(period: PeriodRange, startDay: Long, weekStart: Int): List<Long> = runCatching {
    when (period) {
        PeriodRange.WEEK -> (0L..6L).map { startDay + it }
        PeriodRange.MONTH -> {
            val first = LocalDate.ofEpochDay(startDay).withDayOfMonth(1)
            val last = first.withDayOfMonth(first.lengthOfMonth())
            val out = ArrayList<Long>()
            var ws = weekStartOf(first, weekStart)
            while (!ws.isAfter(last)) { out.add(ws.toEpochDay()); ws = ws.plusWeeks(1) }
            out
        }
        PeriodRange.YEAR -> {
            val year = LocalDate.ofEpochDay(startDay).year
            (1..12).map { LocalDate.of(year, it, 1).toEpochDay() }
        }
        else -> emptyList()
    }
}.getOrDefault(emptyList())
