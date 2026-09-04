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
import androidx.compose.material3.Button
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
        val isList = isAgenda || providerClass.endsWith("DoNextWidget") || providerClass.endsWith("RecordWidget")
        val widgetLabel = when {
            isAgenda -> "Agenda widget"
            providerClass.endsWith("DoNextWidget") -> "Do-Next widget"
            providerClass.endsWith("RecordWidget") -> "Record widget"
            else -> "Widget settings"
        }

        setContent {
            val settings by androidx.compose.runtime.produceState(initialValue = com.todocompanion.app.domain.AppSettings()) {
                value = app.repository.settingsSnapshot()
            }
            AppTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, accentArgb = settings.accentArgb) {
                var lists by remember { mutableStateOf<List<ListEntity>>(emptyList()) }
                androidx.compose.runtime.LaunchedEffect(Unit) { lists = app.repository.allListsOnce().filter { !it.archived } }

                var scope by remember { mutableStateOf(WidgetPrefs.scope(this, widgetId)) }
                var title by remember { mutableStateOf(WidgetPrefs.title(this, widgetId)) }
                var theme by remember { mutableStateOf(WidgetPrefs.theme(this, widgetId)) }
                var opacity by remember { mutableIntStateOf(WidgetPrefs.opacity(this, widgetId)) }
                var fontPct by remember { mutableIntStateOf((WidgetPrefs.fontScale(this, widgetId) * 100).roundToInt()) }
                var compact by remember { mutableStateOf(WidgetPrefs.compact(this, widgetId)) }

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Scaffold { padding ->
                        Column(
                            Modifier.padding(padding).fillMaxSize()
                                .verticalScroll(rememberScrollState()).padding(20.dp)
                        ) {
                            Text(widgetLabel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Tune how this widget looks and what it shows.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.size(16.dp))

                            // Live preview — reflects the current choices, like Todoist's config.
                            WidgetPreview(theme = theme, opacity = opacity, fontPct = fontPct, compact = compact,
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

                            SectionLabel("Theme")
                            SegmentRow(listOf("auto" to "Auto", "light" to "Light", "dark" to "Dark"), theme) { theme = it }
                            Spacer(Modifier.size(18.dp))

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

/** A small, faithful preview card of a list widget under the chosen appearance. */
@Composable
private fun WidgetPreview(theme: String, opacity: Int, fontPct: Int, compact: Boolean, title: String) {
    val dark = when (theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val surface = (if (dark) Color(0xFF1A1B26) else Color(0xFFFBFAFF)).copy(alpha = opacity / 100f)
    val textPrimary = if (dark) Color.White else Color(0xFF1A1B26)
    val textSecondary = if (dark) Color(0xFFB9B4D0) else Color(0xFF5B5870)
    val accent = if (dark) Color(0xFFB9A6EC) else Color(0xFF6D5AC4)
    val scale = fontPct / 100f
    val rowPad = if (compact) 4.dp else 8.dp
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant) // ground so a low-opacity card is visible
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(surface).padding(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = textPrimary, fontWeight = FontWeight.Bold, fontSize = (16 * scale).sp, modifier = Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(accent).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("＋", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (14 * scale).sp)
                }
            }
            Spacer(Modifier.size(6.dp))
            listOf("Draft the proposal" to "Today", "Reply to Sam" to "2:30 PM", "Plan the week" to "Overdue").forEach { (t, s) ->
                Row(Modifier.fillMaxWidth().padding(vertical = rowPad), verticalAlignment = Alignment.CenterVertically) {
                    Text("○", color = accent, fontSize = (17 * scale).sp, modifier = Modifier.padding(end = 10.dp))
                    Text(t, color = textPrimary, fontSize = (14 * scale).sp, modifier = Modifier.weight(1f))
                    Text(s, color = textSecondary, fontSize = (12 * scale).sp)
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
