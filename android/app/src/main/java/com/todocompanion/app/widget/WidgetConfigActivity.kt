package com.todocompanion.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.todocompanion.app.App
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.ui.theme.AppTheme
import kotlin.math.roundToInt

/**
 * R104 — one shared configuration surface for every configurable widget, reopenable to reconfigure
 * (widgetFeatures="reconfigurable"). It shows a live preview and an Appearance block (theme, opacity,
 * font size, compact) for all widgets, plus content options specific to the widget being placed
 * (the Agenda list picks a scope + title). Entirely offline.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        val app = applicationContext as App
        // Which widget are we configuring? Drives the content section + the save/refresh routing.
        val providerClass = AppWidgetManager.getInstance(this)
            .getAppWidgetInfo(widgetId)?.provider?.className.orEmpty()
        val isAgenda = providerClass.endsWith("AgendaWidget")
        // "List" widgets expose the compact-density + text-size controls (their row factories honour both).
        // DayWidget renders through the same compact/font-aware factory, so it belongs here too.
        val isList = isAgenda || providerClass.endsWith("DoNextWidget") || providerClass.endsWith("RecordWidget") ||
            providerClass.endsWith("HabitsWidget") || providerClass.endsWith("DayWidget")
        // Widgets that render one chosen habit — they get a habit picker.
        val isSingleHabit = providerClass.endsWith("WeekRowWidget") || providerClass.endsWith("KeystoneWidget")
        val isHabitZero = providerClass.endsWith("HabitZeroWidget")
        fun suffix(name: String) = providerClass.endsWith(name)
        // Simple card widgets themed via applyCardBackground (light/dark/auto) — they honour theme but not
        // opacity (that's a list/image-card feature), so their config shows theme only.
        val isThemeOnly = listOf("TodayWidget", "StatsWidget", "MatrixWidget", "MomentumWidget", "TimeWidget",
            "Next7Widget", "CountdownWidget", "NoteWidget", "PomodoroWidget").any { suffix(it) }
        val widgetLabel = when {
            isAgenda -> "Agenda widget"
            suffix("DoNextWidget") -> "Do Next widget"
            suffix("RecordWidget") -> "The Record widget"
            suffix("HabitsWidget") -> "Habits widget"
            suffix("HabitStatsWidget") -> "Habit Ring widget"
            suffix("HabitZeroWidget") -> "Habit Zero widget"
            suffix("HabitGridWidget") -> "Habit Year widget"
            suffix("StrengthLineWidget") -> "Habit Strength widget"
            suffix("WeekRowWidget") -> "Habit Week widget"
            suffix("StreaksWidget") -> "Streaks widget"
            suffix("KeystoneWidget") -> "Keystone Habit widget"
            suffix("CorrelationWidget") -> "Habit Insight widget"
            suffix("DayWidget") -> "Day widget"
            suffix("TodayWidget") -> "Tasks Today widget"
            suffix("StatsWidget") -> "Task Stats widget"
            suffix("MatrixWidget") -> "Priority Matrix widget"
            suffix("Next7Widget") -> "Next 7 Days widget"
            suffix("MomentumWidget") -> "Momentum widget"
            suffix("TimeWidget") -> "Time Tracker widget"
            suffix("CountdownWidget") -> "Countdown widget"
            suffix("PomodoroWidget") -> "Focus Timer widget"
            suffix("QuickAddWidget") -> "Quick Add widget"
            suffix("NoteWidget") -> "New Note widget"
            suffix("QuickBarWidget") -> "Quick Actions widget"
            else -> "Widget settings"
        }

        setContent {
            val settings by androidx.compose.runtime.produceState(initialValue = com.todocompanion.app.domain.AppSettings()) {
                value = app.repository.settingsSnapshot()
            }
            AppTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, accentArgb = settings.accentArgb) {
                var lists by remember { mutableStateOf<List<ListEntity>>(emptyList()) }
                androidx.compose.runtime.LaunchedEffect(Unit) { lists = app.repository.allListsOnce().filter { !it.archived } }
                // Build habits, for the single-habit picker (Habit Week / Keystone) + Habit Zero's group list.
                var habits by remember { mutableStateOf<List<com.todocompanion.app.data.entity.HabitEntity>>(emptyList()) }
                androidx.compose.runtime.LaunchedEffect(isSingleHabit, isHabitZero) {
                    if (isSingleHabit || isHabitZero) habits = app.repository.wsHabitsOnce().filter { !it.archived && !it.paused && it.habitType != "break" }
                }
                val groups = remember(habits) { habits.map { it.category.trim() }.filter { it.isNotBlank() }.distinct().sorted() }

                var scope by remember { mutableStateOf(WidgetPrefs.scope(this, widgetId)) }
                var title by remember { mutableStateOf(WidgetPrefs.title(this, widgetId)) }
                var theme by remember { mutableStateOf(WidgetPrefs.theme(this, widgetId)) }
                var opacity by remember { mutableIntStateOf(WidgetPrefs.opacity(this, widgetId)) }
                var fontPct by remember { mutableIntStateOf((WidgetPrefs.fontScale(this, widgetId) * 100).roundToInt()) }
                var compact by remember { mutableStateOf(WidgetPrefs.compact(this, widgetId)) }
                var habitPin by remember { mutableStateOf(WidgetPrefs.habitId(this, widgetId)) }
                var groupPin by remember { mutableStateOf(WidgetPrefs.group(this, widgetId)) }
                val isMatrix = suffix("MatrixWidget")
                var mxRows by remember { mutableIntStateOf(WidgetPrefs.matrixRows(this, widgetId)) }
                val isQuickBar = suffix("QuickBarWidget")
                var qcCount by remember { mutableIntStateOf(WidgetPrefs.quickCount(this, widgetId)) }
                val qcSlots = remember { androidx.compose.runtime.mutableStateListOf<String>().also { it.addAll(WidgetPrefs.quickSlots(this, widgetId)) } }

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Scaffold { padding ->
                        Column(
                            Modifier.padding(padding).fillMaxSize()
                                .verticalScroll(rememberScrollState()).padding(20.dp)
                        ) {
                            Text(widgetLabel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Tune how this widget looks and what it shows.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.size(16.dp))

                            // Live preview — reflects the current choices AND this widget's kind.
                            val previewKind = when {
                                isQuickBar -> "cluster"
                                isMatrix -> "matrix"
                                suffix("HabitStatsWidget") || suffix("HabitZeroWidget") || suffix("WeekRowWidget") ||
                                    suffix("KeystoneWidget") || suffix("StreaksWidget") || suffix("CorrelationWidget") ||
                                    suffix("HabitGridWidget") || suffix("StrengthLineWidget") -> "ring"
                                suffix("TimeWidget") || suffix("PomodoroWidget") -> "timer"
                                suffix("StatsWidget") || suffix("CountdownWidget") || suffix("MomentumWidget") ||
                                    suffix("Next7Widget") || suffix("NoteWidget") -> "tile"
                                isList -> "list"
                                else -> "list"
                            }
                            WidgetPreview(kind = previewKind, theme = theme, opacity = opacity, fontPct = fontPct, compact = compact,
                                title = if (isAgenda) title.ifBlank { WidgetPrefs.defaultTitle(scope) } else widgetLabel.removeSuffix(" widget"))
                            Spacer(Modifier.size(20.dp))

                            if (isAgenda) {
                                SectionLabel("Show")
                                ChoiceRow("Today & overdue", scope == "today") { scope = "today" }
                                ChoiceRow("Next 7 days", scope == "next7") { scope = "next7" }
                                ChoiceRow("All scheduled", scope == "scheduled") { scope = "scheduled" }
                                lists.forEach { l -> ChoiceRow("List · ${l.name}", scope == "list:${l.id}") { scope = "list:${l.id}" } }
                                Spacer(Modifier.size(18.dp))
                                SectionLabel("Title")
                                OutlinedTextField(title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text(WidgetPrefs.defaultTitle(scope)) })
                                Spacer(Modifier.size(18.dp))
                            }

                            if (isSingleHabit) {
                                SectionLabel("Habit")
                                ChoiceRow(if (suffix("KeystoneWidget")) "Auto — your keystone habit" else "Auto — first habit", habitPin == null) { habitPin = null }
                                habits.forEach { h ->
                                    ChoiceRow((h.emoji?.plus(" ") ?: "") + h.name, habitPin == h.id) { habitPin = h.id }
                                }
                                if (habits.isEmpty()) Text("No habits yet — add one first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.size(18.dp))
                            }

                            if (isHabitZero && groups.isNotEmpty()) {
                                SectionLabel("Show habits")
                                ChoiceRow("All habits", groupPin.isBlank()) { groupPin = "" }
                                groups.forEach { g -> ChoiceRow("Group · $g", groupPin == g) { groupPin = g } }
                                Spacer(Modifier.size(18.dp))
                            }

                            if (isMatrix) {
                                SectionLabel("Tasks per quadrant")
                                SegmentRow(listOf(0 to "Auto", 3 to "3", 5 to "5", 8 to "8", 12 to "12").map { it.first.toString() to it.second }, mxRows.toString()) { mxRows = it.toInt() }
                                Spacer(Modifier.size(18.dp))
                            }

                            if (isQuickBar) {
                                SectionLabel("Buttons")
                                SegmentRow(listOf(4, 5, 6, 7).map { it.toString() to it.toString() }, qcCount.toString()) { qcCount = it.toInt() }
                                Spacer(Modifier.size(12.dp))
                                SectionLabel("Assign each button")
                                for (i in 0 until qcCount) {
                                    QuickSlotRow(index = i, current = qcSlots.getOrElse(i) { WidgetPrefs.QUICK_ACTIONS.first() }) { picked ->
                                        if (i < qcSlots.size) qcSlots[i] = picked else qcSlots.add(picked)
                                    }
                                }
                                Spacer(Modifier.size(18.dp))
                            }

                            SectionLabel("Theme")
                            SegmentRow(listOf("auto" to "Auto", "light" to "Light", "dark" to "Dark"), theme) { theme = it }
                            Spacer(Modifier.size(18.dp))

                            // Opacity applies to every widget (each has a themeable card layer).
                            SectionLabel("Opacity · $opacity%")
                            Slider(value = opacity.toFloat(), onValueChange = { opacity = it.roundToInt() }, valueRange = 0f..100f, steps = 19)
                            Spacer(Modifier.size(12.dp))

                            if (isList) {
                                SectionLabel("Text size")
                                SegmentRow(listOf(85 to "Small", 100 to "Normal", 115 to "Large").map { it.first.toString() to it.second }, fontPct.toString()) { fontPct = it.toInt() }
                                Spacer(Modifier.size(14.dp))

                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Compact", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                        Text("Denser rows — fit more at a glance", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = compact, onCheckedChange = { compact = it })
                                }
                                Spacer(Modifier.size(8.dp))
                            }

                            Spacer(Modifier.size(20.dp))
                            Button(onClick = {
                                if (isAgenda) WidgetPrefs.save(this@WidgetConfigActivity, widgetId, scope, title.trim(), theme)
                                else WidgetPrefs.saveTheme(this@WidgetConfigActivity, widgetId, theme)
                                if (isSingleHabit) WidgetPrefs.saveHabit(this@WidgetConfigActivity, widgetId, habitPin)
                                if (isHabitZero) WidgetPrefs.saveGroup(this@WidgetConfigActivity, widgetId, groupPin)
                                if (isMatrix) WidgetPrefs.saveMatrixRows(this@WidgetConfigActivity, widgetId, mxRows)
                                if (isQuickBar) WidgetPrefs.saveQuick(this@WidgetConfigActivity, widgetId, qcCount, qcSlots.toList())
                                WidgetPrefs.saveAppearance(this@WidgetConfigActivity, widgetId, opacity, fontPct, compact, true)
                                refreshWidget(providerClass, widgetId)
                                setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                finish()
                            }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
                        }
                    }
                }
            }
        }
    }

    private fun refreshWidget(providerClass: String, widgetId: Int) {
        when {
            providerClass.endsWith("AgendaWidget") -> AgendaWidget.updateOne(this, widgetId)
            providerClass.endsWith("DoNextWidget") -> DoNextWidget.updateOne(this, widgetId)
            providerClass.endsWith("RecordWidget") -> RecordWidget.refresh(this)
            providerClass.endsWith("HabitsWidget") -> HabitsWidget.updateOne(this, widgetId)
            providerClass.endsWith("HabitStatsWidget") -> HabitStatsWidget.refresh(this)
            providerClass.endsWith("HabitZeroWidget") -> HabitZeroWidget.updateOne(this, widgetId)
            providerClass.endsWith("WeekRowWidget") -> WeekRowWidget.updateOne(this, widgetId)
            providerClass.endsWith("KeystoneWidget") -> KeystoneWidget.refresh(this)
            else -> {
                // Generic: broadcast an update to that provider so it re-renders with the new prefs.
                runCatching {
                    val mgr = AppWidgetManager.getInstance(this)
                    mgr.getAppWidgetInfo(widgetId)?.provider?.let { comp ->
                        sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                            component = comp
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
                        })
                    }
                }
            }
        }
    }
}

/** One Quick-bar slot: shows the current action and opens a dropdown of all actions to reassign it. */
@Composable
private fun QuickSlotRow(index: Int, current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${index + 1}.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp))
            Text(QuickBarWidget.displayName(current), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, "Change", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            WidgetPrefs.QUICK_ACTIONS.forEach { key ->
                DropdownMenuItem(text = { Text(QuickBarWidget.displayName(key)) }, onClick = { onPick(key); open = false })
            }
        }
    }
}

/** A small, faithful preview that reflects THIS widget's kind (list / cluster / matrix / ring / timer /
 *  tile) under the chosen appearance, so the "how it looks" section matches the widget being set up. */
@Composable
private fun WidgetPreview(kind: String, theme: String, opacity: Int, fontPct: Int, compact: Boolean, title: String) {
    val dark = when (theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val surface = (if (dark) Color(0xFF1A1B26) else Color(0xFFFBFAFF)).copy(alpha = opacity / 100f)
    val textPrimary = if (dark) Color.White else Color(0xFF1A1B26)
    val textSecondary = if (dark) Color(0xFFB9B4D0) else Color(0xFF5B5870)
    val accent = if (dark) Color(0xFFB9A6EC) else Color(0xFF6D5AC4)
    val onAccent = Color.White
    val scale = fontPct / 100f
    val rowPad = if (compact) 4.dp else 8.dp
    val danger = Color(0xFFE5484D); val warn = Color(0xFFEA9A16); val info = Color(0xFF3E7BFA); val teal = Color(0xFF12A594)

    fun disc(bg: Color, glyph: String) = @Composable {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(bg), contentAlignment = Alignment.Center) {
            Text(glyph, color = onAccent, fontSize = (15 * scale).sp)
        }
    }

    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant) // ground so a low-opacity card is visible
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(surface).padding(14.dp)
        ) {
            when (kind) {
                "cluster" -> {
                    // The Quick-bar island: two balanced rows of accent discs.
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            disc(accent, "✓")(); disc(accent, "✎")(); disc(accent, "◎")()
                        }
                        Spacer(Modifier.size(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            disc(accent, "◷")(); disc(accent, "⌕")()
                        }
                    }
                }
                "matrix" -> {
                    val quads = listOf("Do first" to danger, "Schedule" to warn, "Delegate" to info, "Later" to teal)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (r in 0..1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (cIdx in 0..1) {
                                val (label, col) = quads[r * 2 + cIdx]
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(col.copy(alpha = 0.14f)).padding(8.dp)) {
                                    Text(label, color = col, fontSize = (11 * scale).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text("• Task", color = textSecondary, fontSize = (10 * scale).sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                "ring" -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(50)).background(accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(surface), contentAlignment = Alignment.Center) {
                                Text("72%", color = accent, fontWeight = FontWeight.Bold, fontSize = (14 * scale).sp)
                            }
                        }
                        Spacer(Modifier.size(14.dp))
                        Column {
                            Text(title, color = textPrimary, fontWeight = FontWeight.Bold, fontSize = (15 * scale).sp)
                            Text("5 of 7 done · 🔥 12", color = textSecondary, fontSize = (12 * scale).sp)
                        }
                    }
                }
                "timer" -> {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("25:00", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = (30 * scale).sp)
                        Spacer(Modifier.size(6.dp))
                        Box(Modifier.clip(RoundedCornerShape(50)).background(accent).padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text("▶ Start", color = onAccent, fontWeight = FontWeight.Bold, fontSize = (13 * scale).sp)
                        }
                    }
                }
                "tile" -> {
                    Column {
                        Text(title, color = textSecondary, fontSize = (12 * scale).sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.size(4.dp))
                        Text("8", color = accent, fontWeight = FontWeight.Bold, fontSize = (34 * scale).sp)
                        Text("due today", color = textSecondary, fontSize = (12 * scale).sp)
                    }
                }
                else -> { // "list"
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, color = textPrimary, fontWeight = FontWeight.Bold, fontSize = (16 * scale).sp, modifier = Modifier.weight(1f))
                        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(accent).padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text("＋", color = onAccent, fontWeight = FontWeight.Bold, fontSize = (14 * scale).sp)
                        }
                    }
                    Spacer(Modifier.size(6.dp))
                    listOf(Triple("Draft the proposal", "Today", danger), Triple("Reply to Sam", "2:30 PM", info), Triple("Plan the week", "Overdue", warn)).forEach { (t, s, col) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = rowPad), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size((16 * scale).dp).clip(RoundedCornerShape(5.dp)).background(col.copy(alpha = 0.18f)).padding(1.dp), contentAlignment = Alignment.Center) {}
                            Spacer(Modifier.size(10.dp))
                            Text(t, color = textPrimary, fontSize = (14 * scale).sp, modifier = Modifier.weight(1f))
                            Text(s, color = if (s == "Overdue") danger else textSecondary, fontSize = (12 * scale).sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(t: String) {
    Text(t.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    Spacer(Modifier.size(6.dp))
}

@Composable
private fun SegmentRow(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            val sel = selected == key
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onPick(key) }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
