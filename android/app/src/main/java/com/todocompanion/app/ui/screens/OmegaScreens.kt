package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.todocompanion.app.domain.OmegaCommand
import com.todocompanion.app.domain.PeriodRange
import com.todocompanion.app.domain.PeriodRecap
import com.todocompanion.app.domain.weekStartOf
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import com.todocompanion.app.ui.components.PeriodSwitcher
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import com.todocompanion.app.ui.components.appCardColor

/** The full command catalog, shown click-to-expand in the palette so no capability stays hidden. */
private val COMMAND_CATALOG: List<Pair<String, List<Pair<String, String>>>> = listOf(
    "Capture" to listOf(
        "Add a task or habit" to "buy milk tomorrow 5pm !!",
        "Make a habit" to "read every night",
    ),
    "Track time" to listOf(
        "Start a timer" to "track deep work",
    ),
    "Navigate" to listOf(
        "Go to a tab, list or tag" to "go to habits",
        "Open Today" to "go to today",
        "Open a smart list (Inbox, Scheduled, Trash…)" to "go to completed",
        "Open a hub (The Record, Countdowns, Attachments…)" to "open the record",
    ),
    "Settings" to listOf(
        "Jump to a setting" to "setting dark mode",
        "Open backup & export" to "settings backup",
        "Open privacy & app lock" to "settings privacy",
    ),
    "Do" to listOf(
        "Plan your day" to "plan my day",
        "Guided weekly review" to "weekly review",
        "This week's recap" to "recap this week",
        "Last week's recap" to "recap last week",
        "This month's recap" to "recap this month",
        "Open the Momentum dashboard" to "momentum",
        "Open Statistics" to "stats",
        "Your year in review (annual report)" to "year in review",
    ),
    "Ask your data" to listOf(
        "Hours on an activity" to "hours on Reading this week",
        "Tasks completed" to "tasks done last week",
        "Focus time" to "focus this month",
        "Strongest habit" to "best habit",
    ),
)

/**
 * Ω1 + Ω2 — the command palette. One line runs the whole app: capture, navigate, act, or ask a data
 * question answered on-device. The caller (AppRoot) executes navigation/actions via [onRun]; Ask is
 * answered inline so the palette stays open for another question.
 */
@Composable
fun CommandPaletteDialog(vm: AppViewModel, onDismiss: () -> Unit, onRun: (OmegaCommand.Command) -> Unit) {
    var text by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }   // catalogue folded by default; tap "All commands" to expand
    val focus = remember { FocusRequester() }
    val parsed = remember(text) { OmegaCommand.parse(text) }

    fun run() {
        val cmd = OmegaCommand.parse(text)
        if (cmd is OmegaCommand.Command.Ask) {
            answer = vm.answerQuery(cmd.question).text
        } else if (text.isNotBlank()) {
            onRun(cmd); onDismiss()
        }
    }

    val kindLabel = when (parsed) {
        is OmegaCommand.Command.Track -> "Start timer"
        is OmegaCommand.Command.Goto -> "Go to"
        is OmegaCommand.Command.Act -> "Do"
        is OmegaCommand.Command.Ask -> "Ask your data"
        is OmegaCommand.Command.Capture -> if (text.isBlank()) "" else "Add"
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = appCardColor()) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("Command", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                com.todocompanion.app.ui.components.AppTextField(
                    value = text, onValueChange = { text = it; answer = null },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = { Text("Type a command or question…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { run() }),
                    trailingIcon = { if (kindLabel.isNotEmpty()) Text(kindLabel, Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                // Inline answer for a data question.
                answer?.let {
                    Spacer(Modifier.height(10.dp))
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(it, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                // Every command, click-to-expand — so nothing the palette can do stays hidden.
                // (The old starter chips were redundant now that the full catalogue sits right below.)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().clickable { showAll = !showAll }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("All commands", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Icon(if (showAll) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.primary)
                }
                if (showAll) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        COMMAND_CATALOG.forEach { (group, rows) ->
                            Text(group.uppercase(), Modifier.padding(top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            rows.forEach { (label, ex) ->
                                Row(Modifier.fillMaxWidth().clickable { text = ex; answer = null }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(label, style = MaterialTheme.typography.bodyMedium)
                                        Text(ex, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text("try", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { run() }, enabled = text.isNotBlank()) {
                        Text(if (parsed is OmegaCommand.Command.Ask) "Ask" else "Run")
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** A tiny wrapping row so the starter chips flow onto multiple lines without pulling in extra APIs. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), content = { content() })
}

/**
 * Ω5 — the any-period recap. Pick a window and read the one cross-module story: what you finished,
 * tracked and kept, versus the window before it. Reachable from the palette ("recap last week") and
 * the drawer.
 *
 * Coherence Move 7 — the period is chosen with the shared [PeriodSwitcher] (Day·Week·Month·Year·All),
 * mapped to a window by [PeriodRange.window], with prev/next stepping over equally-long windows. The
 * recap engine already compares each window to the equally-long one just before it, so any span works.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecapScreen(vm: AppViewModel, initialStartDay: Long, initialEndDay: Long, initialTitle: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val settings by vm.settings.collectAsState()
    // Live, all-workspace task list: collecting it warms the flow AND re-runs the recap when tasks load,
    // so "Tasks done" can't read an empty snapshot (R29 #4).
    val liveTasks by vm.allTasksLive.collectAsState()
    val today = remember { LocalDate.now() }
    val td = today.toEpochDay()

    // Coherence Move 7 — the recap is driven by the shared period switcher (Day·Week·Month·Year·All) plus
    // prev/next stepping. The window comes from [PeriodRange.window] (weeks still honor the "week starts
    // on" setting); the recap engine compares each window to the equally-long one just before it, so any
    // span works. The entry period + anchor are inferred from the window the caller opened us with (the
    // palette's "recap this/last week/month", the drawer's "This week").
    val (initialPeriod, initialAnchor) = remember(initialStartDay, initialEndDay, initialTitle) {
        val t = initialTitle.lowercase(Locale.getDefault())
        when {
            "year" in t -> PeriodRange.YEAR to initialStartDay
            "month" in t -> PeriodRange.MONTH to initialStartDay
            "week" in t -> PeriodRange.WEEK to initialStartDay
            else -> when (initialEndDay - initialStartDay + 1) {
                in Long.MIN_VALUE..1L -> PeriodRange.DAY to initialStartDay
                in 2L..8L -> PeriodRange.WEEK to initialStartDay
                in 9L..31L -> PeriodRange.MONTH to initialStartDay
                else -> PeriodRange.YEAR to initialStartDay
            }
        }
    }
    var period by remember { mutableStateOf(initialPeriod) }
    var anchor by remember { mutableLongStateOf(initialAnchor) }

    val win = period.window(anchor, settings.weekStart, td)
    val start = win.startDay
    val end = win.endDay
    val anchorDate = LocalDate.ofEpochDay(anchor)

    // The title reflects the selected period — relative where natural ("This week"/"Last month"/…), else
    // the dated span — and feeds both the recap engine's label and the shared card.
    val title = when (period) {
        PeriodRange.DAY -> when (anchor) {
            td -> "Today"
            td - 1 -> "Yesterday"
            else -> anchorDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) + ", " +
                anchorDate.dayOfMonth + " " + anchorDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
        PeriodRange.WEEK -> {
            val ws = weekStartOf(anchorDate, settings.weekStart)
            val curWs = weekStartOf(today, settings.weekStart)
            when (ws) { curWs -> "This week"; curWs.minusWeeks(1) -> "Last week"; else -> weekLabel(ws, ws.plusDays(6)) }
        }
        PeriodRange.MONTH -> {
            val first = anchorDate.withDayOfMonth(1)
            val curFirst = today.withDayOfMonth(1)
            when (first) {
                curFirst -> "This month"
                curFirst.minusMonths(1) -> "Last month"
                else -> first.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + first.year
            }
        }
        PeriodRange.YEAR -> when (anchorDate.year) { today.year -> "This year"; today.year - 1 -> "Last year"; else -> anchorDate.year.toString() }
        PeriodRange.ALL -> "All-time"
    }

    // Prev/next stepping over equally-long windows (one period unit at a time); the current/latest period
    // caps "next", and the all-time span has no earlier/later period to step to.
    val prevAnchor: Long
    val nextAnchor: Long
    val canPrev: Boolean
    val canNext: Boolean
    when (period) {
        PeriodRange.DAY -> { prevAnchor = anchor - 1; nextAnchor = anchor + 1; canPrev = true; canNext = anchor < td }
        PeriodRange.WEEK -> {
            val ws = weekStartOf(anchorDate, settings.weekStart)
            prevAnchor = anchor - 7; nextAnchor = anchor + 7; canPrev = true; canNext = ws < weekStartOf(today, settings.weekStart)
        }
        PeriodRange.MONTH -> {
            val first = anchorDate.withDayOfMonth(1)
            prevAnchor = first.minusMonths(1).toEpochDay(); nextAnchor = first.plusMonths(1).toEpochDay(); canPrev = true; canNext = first < today.withDayOfMonth(1)
        }
        PeriodRange.YEAR -> {
            val first = anchorDate.withDayOfYear(1)
            prevAnchor = first.minusYears(1).toEpochDay(); nextAnchor = first.plusYears(1).toEpochDay(); canPrev = true; canNext = anchorDate.year < today.year
        }
        PeriodRange.ALL -> { prevAnchor = anchor; nextAnchor = anchor; canPrev = false; canNext = false }
    }

    val recap = remember(start, end, title, liveTasks) { vm.periodRecap(start, end, title, liveTasks) }

    // Track 1.5 — render the recap to a permission-free PNG via the shared DayCard path (FileProvider +
    // ACTION_SEND, no network), the same family the day / week / year cards already use.
    val shareCtx = androidx.compose.ui.platform.LocalContext.current
    fun shareRecap() {
        runCatching {
            val felt = vm.feltSummary(start, end)
            val rd = com.todocompanion.app.util.DayCard.RecapData(
                title = title,
                avgRating = felt.avgRating,
                lines = recap.lines.map { "${it.icon} ${it.label} · ${it.value}" },
                narrative = recap.narrative,
                accentArgb = settings.accentArgb.takeIf { it != 0L },
            )
            val bmp = com.todocompanion.app.util.DayCard.renderRecap(rd)
            val res = com.todocompanion.app.util.ProgressCard.saveAndShareUri(shareCtx, bmp, "kairo-recap-$start-$end.png")
            res.shareUri?.let { com.todocompanion.app.util.ProgressCard.share(shareCtx, it) }
        }
    }

    Scaffold(topBar = {
        TopAppBar(expandedHeight = 52.dp, title = { Text("Recap") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { if (recap.hasData) IconButton(onClick = { shareRecap() }) { Icon(Icons.Filled.Share, "Share recap") } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The one shared period switcher; switching period re-anchors to the current period.
            PeriodSwitcher(selected = period, onSelect = { period = it; anchor = td })
            // Period navigator (‹ title ›) — mirrors the Day Review roll-up's navigator.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (canPrev) anchor = prevAnchor }, enabled = canPrev) { Icon(Icons.Filled.ChevronLeft, "Previous period") }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = { if (canNext) anchor = nextAnchor }, enabled = canNext) { Icon(Icons.Filled.ChevronRight, "Next period") }
            }
            AppCard {
                Text(recap.narrative, style = MaterialTheme.typography.bodyLarge)
            }
            if (recap.hasData) AppCard {
                recap.lines.forEach { l ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(l.icon, Modifier.width(28.dp))
                        Text(l.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(l.value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (l.delta != 0) {
                            Spacer(Modifier.width(8.dp))
                            val up = l.delta > 0
                            Text((if (up) "▲ " else "▼ ") + kotlin.math.abs(l.delta) + l.deltaUnit,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (up) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            Text("Compared with the equally-long window just before.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
