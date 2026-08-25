package com.todocompanion.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.todocompanion.app.App
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.ui.theme.AppTheme
import kotlinx.coroutines.launch

/**
 * Per-widget configuration launched when the user drops an Agenda widget on the home screen (and
 * re-openable to reconfigure). Lets them choose what the widget shows (Today, Next 7 days, all
 * Scheduled, or one list), a custom title, and a light/dark/auto theme. Entirely offline.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Back-out (no Save) must leave no widget behind.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        val app = applicationContext as App
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

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Scaffold { padding ->
                        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                            Text("Agenda widget", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Choose what this widget shows.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.size(18.dp))

                            SectionLabel("Show")
                            ChoiceRow("Today & overdue", scope == "today") { scope = "today" }
                            ChoiceRow("Next 7 days", scope == "next7") { scope = "next7" }
                            ChoiceRow("All scheduled", scope == "scheduled") { scope = "scheduled" }
                            lists.forEach { l ->
                                ChoiceRow("List · ${l.name}", scope == "list:${l.id}") { scope = "list:${l.id}" }
                            }

                            Spacer(Modifier.size(18.dp))
                            SectionLabel("Title")
                            OutlinedTextField(title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(WidgetPrefs.defaultTitle(scope)) })

                            Spacer(Modifier.size(18.dp))
                            SectionLabel("Theme")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("auto" to "Auto", "light" to "Light", "dark" to "Dark").forEach { (k, l) ->
                                    val sel = theme == k
                                    Box(
                                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                            .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                            .clickable { theme = k }.padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center,
                                    ) { Text(l, color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) }
                                }
                            }

                            Spacer(Modifier.size(28.dp))
                            Button(onClick = {
                                WidgetPrefs.save(this@WidgetConfigActivity, widgetId, scope, title.trim(), theme)
                                AgendaWidget.updateOne(this@WidgetConfigActivity, widgetId)
                                setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                finish()
                            }, modifier = Modifier.fillMaxWidth()) { Text("Add widget") }
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
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
