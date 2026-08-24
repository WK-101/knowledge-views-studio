package com.todocompanion.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.todocompanion.app.domain.Density
import com.todocompanion.app.domain.SmartVis
import com.todocompanion.app.domain.SwipeAction
import com.todocompanion.app.domain.view.SmartKind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.domain.ThemeMode
import com.todocompanion.app.domain.TimeFormat
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val s by vm.settings.collectAsState()
    val context = LocalContext.current
    var showZone by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportTo(uri) { ok -> Toast.makeText(context, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show() }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFrom(uri) { ok -> Toast.makeText(context, if (ok) "Imported" else "Import failed", Toast.LENGTH_SHORT).show() }
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {

        Section("Appearance")
        AppCard {
            Sub("Theme")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(selected = s.themeMode == m, onClick = { vm.saveSettings(s.copy(themeMode = m)) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) {
                        Text(when (m) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark"; ThemeMode.AMOLED -> "AMOLED" }, maxLines = 1)
                    }
                }
            }
            Toggle("Dynamic color (Material You)", s.dynamicColor) { vm.saveSettings(s.copy(dynamicColor = it)) }
            Toggle("Advanced priority (importance + urgency)", s.advancedPriority) { vm.saveSettings(s.copy(advancedPriority = it)) }

            Spacer(Modifier.height(10.dp)); Sub("Accent colour")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccentSwatch(0L, s.accentArgb) { vm.saveSettings(s.copy(accentArgb = 0L)) }
                ACCENTS.forEach { c -> AccentSwatch(c, s.accentArgb) { vm.saveSettings(s.copy(accentArgb = c)) } }
            }

            Spacer(Modifier.height(12.dp)); Sub("Task density")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Density.entries.forEachIndexed { i, d ->
                    SegmentedButton(selected = s.density == d, onClick = { vm.saveSettings(s.copy(density = d)) },
                        shape = SegmentedButtonDefaults.itemShape(i, Density.entries.size)) {
                        Text(d.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }

            Spacer(Modifier.height(12.dp)); Sub("Swipe actions")
            SwipeRow("Swipe right", s.swipeRight) { vm.saveSettings(s.copy(swipeRight = it)) }
            SwipeRow("Swipe right — full", s.swipeRightFar) { vm.saveSettings(s.copy(swipeRightFar = it)) }
            SwipeRow("Swipe left", s.swipeLeft) { vm.saveSettings(s.copy(swipeLeft = it)) }
            SwipeRow("Swipe left — full", s.swipeLeftFar) { vm.saveSettings(s.copy(swipeLeftFar = it)) }
            Text("A short swipe runs the first action; a longer swipe runs the “full” action.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(18.dp))
        Section("Sidebar")
        AppCard {
            Sub("Smart lists")
            Text("Choose which smart lists appear in the navigation drawer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            SMART_KINDS.forEach { k ->
                SmartVisRow(k.title, s.smartListVis[k] ?: SmartVis.SHOW) { v ->
                    val next = if (v == SmartVis.SHOW) s.smartListVis - k else s.smartListVis + (k to v)
                    vm.saveSettings(s.copy(smartListVis = next))
                }
            }
            Spacer(Modifier.height(10.dp)); Sub("Bottom bar")
            Text("Tasks always shows. Hidden tabs stay reachable from the menu.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
            listOf("CALENDAR" to "Calendar", "MATRIX" to "Matrix", "SEARCH" to "Search", "SETTINGS" to "Settings").forEach { (key, label) ->
                Toggle(label, key !in s.bottomTabsHidden) { on ->
                    val next = if (on) s.bottomTabsHidden - key else s.bottomTabsHidden + key
                    vm.saveSettings(s.copy(bottomTabsHidden = next))
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Section("Date & time")
        AppCard {
            Sub("Week starts on")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val labels = listOf("System" to 0, "Mon" to 1, "Tue" to 2, "Wed" to 3, "Thu" to 4, "Fri" to 5, "Sat" to 6, "Sun" to 7)
                labels.forEach { (label, v) ->
                    FilterChip(selected = s.weekStart == v, onClick = { vm.saveSettings(s.copy(weekStart = v)) }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(10.dp)); Sub("Clock")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TimeFormat.entries.forEachIndexed { i, f ->
                    SegmentedButton(selected = s.timeFormat == f, onClick = { vm.saveSettings(s.copy(timeFormat = f)) },
                        shape = SegmentedButtonDefaults.itemShape(i, TimeFormat.entries.size)) {
                        Text(when (f) { TimeFormat.SYSTEM -> "System"; TimeFormat.H12 -> "12-hour"; TimeFormat.H24 -> "24-hour" })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().clickable { showZone = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Time zone", Modifier.weight(1f))
                Text(s.timeZone.ifBlank { "Device (${ZoneId.systemDefault().id})" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(18.dp))
        Section("Reminders")
        AppCard {
            Toggle("Daily summary notification", s.dailySummaryEnabled) { vm.saveSettings(s.copy(dailySummaryEnabled = it)) }
            if (s.dailySummaryEnabled) {
                Row(Modifier.fillMaxWidth().clickable { showTime = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Summary time", Modifier.weight(1f))
                    Text("%02d:%02d".format(s.dailySummaryHour, s.dailySummaryMinute), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Section("Backup")
        AppCard {
            Action("Export all data (JSON)") { exportLauncher.launch("todo-companion-backup.json") }
            Action("Import / restore (JSON)") { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }
            Text("Complete, lossless local backup. No account, no cloud, no network.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.height(20.dp))
        Text("ToDo Companion · Phase 1a · offline & private by construction (no network permission).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (showZone) ZonePickerDialog(current = s.timeZone, onDismiss = { showZone = false }) { z ->
        vm.saveSettings(s.copy(timeZone = z)); showZone = false
    }
    if (showTime) {
        val ts = rememberTimePickerState(initialHour = s.dailySummaryHour, initialMinute = s.dailySummaryMinute)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = { TextButton(onClick = { vm.saveSettings(s.copy(dailySummaryHour = ts.hour, dailySummaryMinute = ts.minute)); showTime = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
            title = { Text("Summary time") },
            text = { TimePicker(state = ts) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZonePickerDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember { listOf("") + ZoneId.getAvailableZoneIds().sorted() }
    val filtered = remember(query) { all.filter { query.isBlank() || it.contains(query, ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Time zone") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search zones…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(filtered, key = { it }) { z ->
                        val label = if (z.isBlank()) "Device (${ZoneId.systemDefault().id})" else z
                        Text(label, Modifier.fillMaxWidth().clickable { onPick(z) }.padding(vertical = 11.dp),
                            fontWeight = if (z == current) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        },
    )
}

@Composable private fun Section(t: String) { Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(8.dp)) }
@Composable private fun Sub(t: String) { Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(6.dp)) }

@Composable
private fun Toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Action(title: String, onClick: () -> Unit) {
    Text(title, Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
}

private val ACCENTS = listOf(
    0xFF5B57D9, 0xFF2F6BFF, 0xFF0EA5E9, 0xFF06B6D4, 0xFF12A594, 0xFF0EA371, 0xFF65A30D, 0xFFCA8A04,
    0xFFF59E0B, 0xFFEA580C, 0xFFE5484D, 0xFFEC4899, 0xFFDB2777, 0xFF8B5CF6, 0xFF7C3AED, 0xFF64748B,
)

@Composable
private fun AccentSwatch(color: Long, current: Long, onClick: () -> Unit) {
    val selected = color == current
    Box(
        Modifier.size(30.dp).clip(CircleShape)
            .background(if (color == 0L) MaterialTheme.colorScheme.surfaceVariant else Color(color))
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { if (color == 0L) Text("A", style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun SwipeRow(label: String, action: SwipeAction, onChange: (SwipeAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Box {
            TextButton(onClick = { menu = true }) { Text(action.label) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                SwipeAction.entries.forEach { a -> DropdownMenuItem(text = { Text(a.label) }, onClick = { onChange(a); menu = false }) }
            }
        }
    }
}

private val SMART_KINDS = listOf(
    SmartKind.INBOX, SmartKind.TODAY, SmartKind.TOMORROW, SmartKind.NEXT7, SmartKind.DO_NEXT,
    SmartKind.SCHEDULED, SmartKind.FLAGGED, SmartKind.ALL, SmartKind.COMPLETED, SmartKind.WONT_DO, SmartKind.TRASH,
)

@Composable
private fun SmartVisRow(label: String, vis: SmartVis, onChange: (SmartVis) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Box {
            TextButton(onClick = { menu = true }) { Text(vis.label) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                SmartVis.entries.forEach { v -> DropdownMenuItem(text = { Text(v.label) }, onClick = { onChange(v); menu = false }) }
            }
        }
    }
}
