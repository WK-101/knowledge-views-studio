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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.todocompanion.app.domain.PeriodRecap
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import java.time.LocalDate

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
    var showAll by remember { mutableStateOf(false) }
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
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("Command", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                com.todocompanion.app.ui.components.AppTextField(
                    value = text, onValueChange = { text = it; answer = null },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = { Text("track deep work · go to habits · hours on Reading this week") },
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
                // Quick examples when empty.
                if (text.isBlank() && !showAll) {
                    Spacer(Modifier.height(10.dp))
                    FlowRowCompat {
                        listOf("track deep work", "go to habits", "plan my day", "best habit").forEach { ex ->
                            AssistChip(onClick = { text = ex; answer = null }, label = { Text(ex, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                }
                // Every command, click-to-expand — so nothing the palette can do stays hidden.
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecapScreen(vm: AppViewModel, initialStartDay: Long, initialEndDay: Long, initialTitle: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    data class Preset(val title: String, val start: Long, val end: Long)
    val today = remember { LocalDate.now() }
    val td = today.toEpochDay()
    val presets = remember {
        listOf(
            Preset("This week", td - 6, td),
            Preset("Last week", td - 13, td - 7),
            Preset("This month", today.withDayOfMonth(1).toEpochDay(), td),
            Preset("Last month",
                today.withDayOfMonth(1).minusMonths(1).toEpochDay(),
                today.withDayOfMonth(1).minusDays(1).toEpochDay()),
            Preset("This year", today.withDayOfYear(1).toEpochDay(), td),
        )
    }
    var start by remember { mutableStateOf(initialStartDay) }
    var end by remember { mutableStateOf(initialEndDay) }
    var title by remember { mutableStateOf(initialTitle) }
    val recap = remember(start, end) { vm.periodRecap(start, end, title) }

    Scaffold(topBar = {
        TopAppBar(expandedHeight = 52.dp, title = { Text("Recap") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRowCompat {
                presets.forEach { p ->
                    FilterChip(selected = start == p.start && end == p.end,
                        onClick = { start = p.start; end = p.end; title = p.title }, label = { Text(p.title) })
                }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
