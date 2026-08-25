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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import com.todocompanion.app.data.entity.FlagEntity
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import com.todocompanion.app.ui.components.FLAG_COLORS
import com.todocompanion.app.ui.components.FlagIcons
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val s by vm.settings.collectAsState()
    val flags by vm.flags.collectAsState()
    val context = LocalContext.current
    var showZone by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var showEveningTime by remember { mutableStateOf(false) }
    var editFlag by remember { mutableStateOf<FlagEntity?>(null) }
    var addingFlag by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportTo(uri) { ok -> Toast.makeText(context, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show() }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFrom(uri) { ok -> Toast.makeText(context, if (ok) "Imported" else "Import failed", Toast.LENGTH_SHORT).show() }
    }
    val exportMdLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) vm.exportMarkdownTo(uri, includeCompleted = true) { ok -> Toast.makeText(context, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show() }
    }
    val exportCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsvTo(uri, includeCompleted = true) { ok -> Toast.makeText(context, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show() }
    }

    // Collapsible category groups (TickTick-style compact list). All start collapsed.
    val open = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp)) {

        SettingsGroup(Icons.Filled.Palette, "Appearance", open["appearance"] == true, { open["appearance"] = open["appearance"] != true }) {
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

            Spacer(Modifier.height(12.dp)); Sub("Theme pack")
            Text("One tap sets a coordinated accent + background.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                THEME_PACKS.forEach { pack ->
                    FilterChip(
                        selected = s.themePack == pack.id,
                        onClick = {
                            vm.saveSettings(s.copy(themePack = pack.id, accentArgb = pack.accent, appBackground = pack.background,
                                dynamicColor = if (pack.id.isBlank()) s.dynamicColor else false,
                                themeMode = pack.themeMode ?: s.themeMode))
                        },
                        label = { Text(pack.label) },
                        leadingIcon = { Box(Modifier.size(14.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (pack.accent == 0L) MaterialTheme.colorScheme.primary else Color(pack.accent))) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp)); Sub("App background")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("none" to "None", "warm" to "Warm", "cool" to "Cool", "mint" to "Mint", "dusk" to "Dusk", "rose" to "Rose").forEach { (key, label) ->
                    FilterChip(selected = s.appBackground == key, onClick = { vm.saveSettings(s.copy(appBackground = key)) }, label = { Text(label) })
                }
            }

            Spacer(Modifier.height(10.dp))
            Toggle("Completion sound", s.completionSound) { vm.saveSettings(s.copy(completionSound = it)) }

            Spacer(Modifier.height(12.dp)); Sub("Task density")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Density.entries.forEachIndexed { i, d ->
                    SegmentedButton(selected = s.density == d, onClick = { vm.saveSettings(s.copy(density = d)) },
                        shape = SegmentedButtonDefaults.itemShape(i, Density.entries.size)) {
                        Text(d.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }

            Spacer(Modifier.height(12.dp)); Sub("Add button position")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("start" to "Left", "center" to "Center", "end" to "Right").forEachIndexed { i, (key, label) ->
                    SegmentedButton(selected = s.fabPosition == key, onClick = { vm.saveSettings(s.copy(fabPosition = key)) },
                        shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                }
            }
            Text("Long-press the add button for quick actions (plan, focus, review).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))

            Spacer(Modifier.height(12.dp)); Sub("Swipe actions")
            SwipeRow("Swipe right", s.swipeRight) { vm.saveSettings(s.copy(swipeRight = it)) }
            SwipeRow("Swipe right — full", s.swipeRightFar) { vm.saveSettings(s.copy(swipeRightFar = it)) }
            SwipeRow("Swipe left", s.swipeLeft) { vm.saveSettings(s.copy(swipeLeft = it)) }
            SwipeRow("Swipe left — full", s.swipeLeftFar) { vm.saveSettings(s.copy(swipeLeftFar = it)) }
            Text("A short swipe runs the first action; a longer swipe runs the “full” action.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SettingsGroup(Icons.Filled.Tune, "Do-Next priority", open["priority"] == true, { open["priority"] = open["priority"] != true }) {
            Toggle("Use computed priority", s.priorityComputed) { vm.saveSettings(s.copy(priorityComputed = it)) }
            Text(if (s.priorityComputed) "MLO-style score ranks the Do-Next list — importance & urgency compound down the outline, plus a date term."
                 else "Computed score off. Do-Next orders by star, then importance/urgency, then your manual order.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            if (s.priorityComputed) {
                Spacer(Modifier.height(8.dp)); Sub("Weigh by")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val modes = listOf("importance" to "Importance", "urgency" to "Urgency", "both" to "Both")
                    modes.forEachIndexed { i, (k, label) ->
                        SegmentedButton(selected = s.priorityMode == k, onClick = { vm.saveSettings(s.copy(priorityMode = k)) },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(label) }
                    }
                }
                Spacer(Modifier.height(8.dp)); Sub("Influence of each factor")
                WeightRow("Due-date weight", s.priorityDueWeight) { vm.saveSettings(s.copy(priorityDueWeight = it)) }
                WeightRow("Start-date weight", s.priorityStartWeight) { vm.saveSettings(s.copy(priorityStartWeight = it)) }
                WeightRow("Weekly-goal weight", s.priorityGoalWeight) { vm.saveSettings(s.copy(priorityGoalWeight = it)) }
                FactorRow("Star boost", s.priorityStarBoost, 1.0f, 3.0f, 7) { vm.saveSettings(s.copy(priorityStarBoost = it)) }
                FactorRow("Level curve", s.priorityCurveBase, 1.1f, 2.5f, 13) { vm.saveSettings(s.copy(priorityCurveBase = it)) }
                Text("Level curve sets how sharply each importance/urgency step raises the score.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Toggle("Boost overdue tasks", s.priorityOverdueBoost) { vm.saveSettings(s.copy(priorityOverdueBoost = it)) }
            }
        }

        SettingsGroup(Icons.Filled.RocketLaunch, "Startup", open["startup"] == true, { open["startup"] = open["startup"] != true }) {
            Toggle("Resume where I left off", s.resumeLastView) { vm.saveSettings(s.copy(resumeLastView = it)) }
            Text("Reopen the last view you had open. Overrides the default view below.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp)); Sub("Default view")
            val startupChoices = listOf(
                "" to "Today", "smart:INBOX" to "Inbox", "smart:DO_NEXT" to "Do Next",
                "smart:NEXT7" to "Next 7 Days", "smart:SCHEDULED" to "Scheduled", "smart:ALL" to "All", "smart:GOALS" to "Goals",
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                startupChoices.forEach { (ref, label) ->
                    FilterChip(selected = s.defaultViewRef == ref, onClick = { vm.saveSettings(s.copy(defaultViewRef = ref)) }, label = { Text(label) })
                }
            }
        }

        SettingsGroup(Icons.Filled.ViewSidebar, "Sidebar & tabs", open["sidebar"] == true, { open["sidebar"] = open["sidebar"] != true }) {
            Sub("Smart lists")
            Text("Choose which smart lists appear in the navigation drawer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            SMART_KINDS.forEach { k ->
                SmartVisRow(k.title, s.smartListVis[k] ?: SmartVis.SHOW) { v ->
                    val next = if (v == SmartVis.SHOW) s.smartListVis - k else s.smartListVis + (k to v)
                    vm.saveSettings(s.copy(smartListVis = next))
                }
            }
            Spacer(Modifier.height(10.dp)); Sub("Bottom bar")
            Text("Tasks always shows. Hidden tabs stay reachable from the drawer menu.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
            listOf("CALENDAR" to "Calendar", "TIMELINE" to "Timeline", "MATRIX" to "Matrix", "HABITS" to "Habits", "FOCUS" to "Focus", "SEARCH" to "Search", "SETTINGS" to "Settings").forEach { (key, label) ->
                Toggle(label, key !in s.bottomTabsHidden) { on ->
                    val next = if (on) s.bottomTabsHidden - key else s.bottomTabsHidden + key
                    vm.saveSettings(s.copy(bottomTabsHidden = next))
                }
            }
            Spacer(Modifier.height(10.dp)); Sub("Drawer sections")
            Text("Show or hide whole sections of the navigation drawer. Settings always stays visible.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
            listOf(
                "fav" to "Favourites", "smart" to "Smart lists", "lists" to "Lists", "tags" to "Tags",
                "filters" to "Filters", "contexts" to "Contexts", "views" to "Views", "more" to "More (Templates, Attachments…)",
            ).forEach { (key, label) ->
                Toggle(label, key !in s.sidebarHidden) { on ->
                    vm.setSidebarSectionHidden(key, !on)
                }
            }
        }

        SettingsGroup(Icons.Filled.Schedule, "Date & time", open["datetime"] == true, { open["datetime"] = open["datetime"] != true }) {
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
            Spacer(Modifier.height(10.dp)); Sub("Day starts at")
            Text("Tasks before this hour still count under Today — handy for night owls.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..6).forEach { h ->
                    FilterChip(selected = s.dayStartHour == h, onClick = { vm.saveSettings(s.copy(dayStartHour = h)) },
                        label = { Text(if (h == 0) "Midnight" else "%d:00".format(h)) })
                }
            }

            Spacer(Modifier.height(12.dp)); Sub("Daily capacity")
            Text("How many hours you can realistically commit per day — powers the workload forecast and auto-schedule.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2, 4, 6, 8, 10, 12).forEach { h ->
                    FilterChip(selected = s.dailyCapacityHours == h, onClick = { vm.saveSettings(s.copy(dailyCapacityHours = h)) }, label = { Text("${h}h") })
                }
            }
            Spacer(Modifier.height(10.dp)); Sub("Working hours (auto-schedule)")
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Start", Modifier.weight(1f))
                TextButton(onClick = { vm.saveSettings(s.copy(workStartHour = ((s.workStartHour - 1 + 24) % 24))) }) { Text("−") }
                Text("%02d:00".format(s.workStartHour), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { vm.saveSettings(s.copy(workStartHour = ((s.workStartHour + 1) % 24))) }) { Text("+") }
                Spacer(Modifier.width(16.dp))
                Text("End", Modifier.weight(1f))
                TextButton(onClick = { vm.saveSettings(s.copy(workEndHour = (s.workEndHour - 1).coerceAtLeast(s.workStartHour + 1))) }) { Text("−") }
                Text("%02d:00".format(s.workEndHour), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { vm.saveSettings(s.copy(workEndHour = (s.workEndHour + 1).coerceAtMost(24))) }) { Text("+") }
            }
        }

        SettingsGroup(Icons.Filled.Notifications, "Reminders", open["reminders"] == true, { open["reminders"] = open["reminders"] != true }) {
            Toggle("Daily summary notification", s.dailySummaryEnabled) { vm.saveSettings(s.copy(dailySummaryEnabled = it)) }
            if (s.dailySummaryEnabled) {
                Row(Modifier.fillMaxWidth().clickable { showTime = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Summary time", Modifier.weight(1f))
                    Text("%02d:%02d".format(s.dailySummaryHour, s.dailySummaryMinute), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Toggle("Evening review nudge", s.eveningReviewEnabled) { vm.saveSettings(s.copy(eveningReviewEnabled = it)) }
            if (s.eveningReviewEnabled) {
                Row(Modifier.fillMaxWidth().clickable { showEveningTime = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Review time", Modifier.weight(1f))
                    Text("%02d:00".format(s.eveningReviewHour), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("An end-of-day tap to line up tomorrow before you clock off.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Text("Reminder reliability", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
            Text("Android may delay or drop alarms to save battery. Grant these once so reminders fire on time.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            Action("Allow exact alarms") { openExactAlarmSettings(context) }
            Action("Ignore battery optimisation") { openBatterySettings(context) }
        }

        SettingsGroup(Icons.Filled.Lock, "Privacy", open["privacy"] == true, { open["privacy"] = open["privacy"] != true }) {
            Toggle("Require unlock to open", s.appLockEnabled) { vm.saveSettings(s.copy(appLockEnabled = it)) }
            Text("Ask for your fingerprint, face or device PIN each time the app opens. All checks happen on-device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SettingsGroup(Icons.Filled.Flag, "Flags", open["flags"] == true, { open["flags"] = open["flags"] != true }) {
            Text("Named, coloured markers you can sort and group by. A flag stays on a task; the star is a separate ‘focus now’ toggle.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            flags.forEachIndexed { i, f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(FlagIcons.vector(f.icon), null, tint = Color(f.colorArgb), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { vm.moveFlag(f, -1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                    IconButton(onClick = { vm.moveFlag(f, +1) }, enabled = i < flags.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                    IconButton(onClick = { editFlag = f }) { Icon(Icons.Filled.Edit, "Edit") }
                    IconButton(onClick = { vm.deleteFlag(f.id) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                }
            }
            Row(Modifier.fillMaxWidth().clickable { addingFlag = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Add flag", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
        }

        SettingsGroup(Icons.Filled.CloudSync, "Backup", open["backup"] == true, { open["backup"] = open["backup"] != true }) {
            Action("Export all data (JSON)") { exportLauncher.launch("todo-companion-backup.json") }
            Action("Import / restore (JSON)") { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }
            Text("Complete, lossless local backup. No account, no cloud, no network.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Action("Export as Markdown (.md)") { exportMdLauncher.launch("todo-companion.md") }
            Action("Export as CSV (spreadsheet)") { exportCsvLauncher.launch("todo-companion.csv") }
            Text("Readable, portable snapshots for sharing or archiving. (Restore uses JSON.)",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.height(20.dp))
        Text("ToDo Companion · Phase 1a · offline & private by construction (no network permission).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (addingFlag) FlagEditDialog(null, onDismiss = { addingFlag = false }) { name, color, icon ->
        vm.createFlag(name, color, icon); addingFlag = false
    }
    editFlag?.let { f ->
        FlagEditDialog(f, onDismiss = { editFlag = null }) { name, color, icon ->
            vm.updateFlag(f.copy(name = name, colorArgb = color, icon = icon)); editFlag = null
        }
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
    if (showEveningTime) {
        val ts = rememberTimePickerState(initialHour = s.eveningReviewHour, initialMinute = 0)
        AlertDialog(
            onDismissRequest = { showEveningTime = false },
            confirmButton = { TextButton(onClick = { vm.saveSettings(s.copy(eveningReviewHour = ts.hour)); showEveningTime = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showEveningTime = false }) { Text("Cancel") } },
            title = { Text("Evening review time") },
            text = { TimePicker(state = ts) },
        )
    }
}

/** Open the OS screen where the user can grant exact-alarm scheduling (Android 12+). */
private fun openExactAlarmSettings(context: android.content.Context) {
    runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                android.net.Uri.parse("package:" + context.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + context.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Ask the OS to exempt the app from battery optimisation so alarms aren't deferred. */
private fun openBatterySettings(context: android.content.Context) {
    runCatching {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:" + context.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        runCatching {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlagEditDialog(initial: FlagEntity?, onDismiss: () -> Unit, onSave: (String, Long, String) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableStateOf(initial?.colorArgb ?: FLAG_COLORS.first()) }
    var icon by remember { mutableStateOf(initial?.icon ?: "flag") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(name.trim().ifBlank { "Flag" }, color, icon) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (initial == null) "New flag" else "Edit flag") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp)); Sub("Colour")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FLAG_COLORS.forEach { c ->
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                                .border(width = if (c == color) 3.dp else 0.dp, color = MaterialTheme.colorScheme.primary, shape = CircleShape)
                                .clickable { color = c },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp)); Sub("Icon")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlagIcons.keys.forEach { key ->
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(if (key == icon) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable { icon = key },
                            contentAlignment = Alignment.Center,
                        ) { Icon(FlagIcons.vector(key), key, tint = Color(color), modifier = Modifier.size(22.dp)) }
                    }
                }
            }
        },
    )
}

/** A collapsible, iconized settings category (TickTick-style): a tidy header row that expands
 *  its controls inline, so the screen reads as a compact list instead of one long lump. */
@Composable
private fun SettingsGroup(icon: ImageVector, title: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { onToggle() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Icon(if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) { content() }
            }
        }
    }
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
private fun WeightRow(label: String, value: Double, onChange: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label  ${"%.1f".format(value)}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        androidx.compose.material3.Slider(
            value = value.toFloat(), onValueChange = { onChange((Math.round(it * 2f) / 2.0)) },
            valueRange = 0f..10f, steps = 19, modifier = Modifier.width(150.dp),
        )
    }
}

@Composable
private fun FactorRow(label: String, value: Double, min: Float, max: Float, steps: Int, onChange: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label  ${"%.2f×".format(value)}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        androidx.compose.material3.Slider(
            value = value.toFloat().coerceIn(min, max), onValueChange = { onChange(((it * 100).toInt() / 100.0)) },
            valueRange = min..max, steps = steps, modifier = Modifier.width(150.dp),
        )
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

/** A curated theme: coordinated accent colour + subtle app background (+ optional forced mode). */
private data class ThemePack(val id: String, val label: String, val accent: Long, val background: String, val themeMode: ThemeMode? = null)
private val THEME_PACKS = listOf(
    ThemePack("", "Dynamic", 0L, "none"),
    ThemePack("sunset", "Sunset", 0xFFEA580C, "warm"),
    ThemePack("ocean", "Ocean", 0xFF0EA5E9, "cool"),
    ThemePack("forest", "Forest", 0xFF0EA371, "mint"),
    ThemePack("grape", "Grape", 0xFF7C3AED, "dusk"),
    ThemePack("rose", "Rosé", 0xFFDB2777, "rose"),
    ThemePack("midnight", "Midnight", 0xFF2F6BFF, "none", ThemeMode.AMOLED),
    ThemePack("slate", "Slate", 0xFF64748B, "cool"),
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
