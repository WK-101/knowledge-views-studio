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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Sync
import com.todocompanion.app.ui.components.formatDue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.School
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
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import com.todocompanion.app.domain.Modules
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
    // GetContent (ACTION_GET_CONTENT) is answered by ordinary file managers, not only the system
    // document picker — so imports still work on devices without com.android.documentsui.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importFrom(uri) { ok -> Toast.makeText(context, if (ok) "Imported" else "Import failed", Toast.LENGTH_SHORT).show() }
    }
    // Import from another app (Todoist/TickTick CSV, MLO OPML).
    val importExternalLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importExternal(uri) { ok, msg -> Toast.makeText(context, msg, if (ok) Toast.LENGTH_LONG else Toast.LENGTH_LONG).show() }
    }
    // CU3: import a calendar (.ics) back into tasks — the other half of the 2-way bridge.
    val importIcsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importIcs(uri) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }
    // Folder pickers for backup & sync — take a persistable grant so alarms can write later.
    fun persist(uri: android.net.Uri) = runCatching {
        context.contentResolver.takePersistableUriPermission(uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    val backupFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { persist(uri); vm.setAutoBackupFolder(uri.toString()) }
    }
    val syncFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { persist(uri); vm.setSyncFolder(uri.toString()) }
    }
    val exportMdLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) vm.exportMarkdownTo(uri, includeCompleted = true) { ok -> Toast.makeText(context, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show() }
    }
    val exportCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsvTo(uri, includeCompleted = true) { ok -> Toast.makeText(context, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show() }
    }
    val exportIcsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        if (uri != null) vm.exportIcsTo(uri, includeCompleted = false) { ok -> Toast.makeText(context, if (ok) "Calendar exported" else "Export failed", Toast.LENGTH_SHORT).show() }
    }
    val exportHabitsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportHabitsCsvTo(uri) { ok -> Toast.makeText(context, if (ok) "Habits exported" else "Export failed", Toast.LENGTH_SHORT).show() }
    }
    val importHabitsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importHabitsCsv(uri) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }
    // Opening the system document picker (Storage Access Framework) can throw
    // ActivityNotFoundException on devices whose Files / DocumentsUI app is missing or disabled —
    // which crashed every import/export action. Guard the launch so it shows a message instead.
    fun safePick(block: () -> Unit) {
        try { block() } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "This device has no system file picker. Use \"Export all data (JSON)\" — it saves straight to your Downloads folder.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't open the file picker.", Toast.LENGTH_LONG).show()
        }
    }
    // Exports have no third-party equivalent of the SAF "create document" picker, so when it's
    // missing we fall back to writing the file straight into Downloads (offline, no permission).
    fun safeExport(kind: String, block: () -> Unit) {
        try { block() } catch (e: android.content.ActivityNotFoundException) {
            vm.exportToDownloads(kind) { loc ->
                Toast.makeText(context, if (loc != null) "Saved to $loc" else "Export failed", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            // No document picker at all — still land the file somewhere reachable (Downloads / app storage).
            vm.exportToDownloads(kind) { loc ->
                Toast.makeText(context, if (loc != null) "Saved to $loc" else "Export failed", Toast.LENGTH_LONG).show()
            }
        }
    }
    // In-app restore/import browser (no system picker) — for devices with no DocumentsUI / file manager hook.
    // [restoreBroad] = true lists any JSON/CSV dropped into the import inbox (import a foreign file),
    // false lists only our own backups (restore).
    var restoreOpen by remember { mutableStateOf(false) }
    var restoreBroad by remember { mutableStateOf(false) }
    var savedList by remember { mutableStateOf<List<com.todocompanion.app.util.FileExport.SavedFile>?>(null) }
    fun openRestore(broad: Boolean = false) { restoreBroad = broad; savedList = null; restoreOpen = true; vm.loadSavedBackups(broad) { savedList = it } }
    // Paste-a-backup dialog — the last-resort import that needs no file, picker or permission at all.
    var showPaste by remember { mutableStateOf(false) }
    // Full filesystem browser: navigate + search real folders and pick any backup, with no system picker.
    var browseOpen by remember { mutableStateOf(false) }
    val readPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) browseOpen = true else Toast.makeText(context, "Storage permission is needed to browse files", Toast.LENGTH_LONG).show()
    }
    val manageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (vm.canBrowseStorage()) browseOpen = true else Toast.makeText(context, "Turn on “All files access” to browse for a file", Toast.LENGTH_LONG).show()
    }
    fun requestAndBrowse() {
        when {
            vm.canBrowseStorage() -> browseOpen = true
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R ->
                runCatching { manageLauncher.launch(com.todocompanion.app.util.FileExport.manageAllFilesIntent(context)) }.onFailure { openRestore(broad = true) }
            else -> readPermLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    // Import never dead-ends: if the system picker is missing, open the full filesystem browser instead.
    fun safeImport(block: () -> Unit) { try { block() } catch (e: Exception) { requestAndBrowse() } }

    // Collapsible category groups (TickTick-style compact list). All start collapsed.
    val open = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp)) {

        // T0: modular module system — pick the primary, switch any module off. Off hides it everywhere
        // (nav, drawer, capture, widgets, Momentum, Today) but never deletes its data.
        SettingsGroup(Icons.Filled.Dashboard, "Modules", open["modules"] == true, { open["modules"] = open["modules"] != true }) {
            Sub("Primary (your home + always shown)")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Modules.ALL.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = Modules.primary(s) == m,
                        onClick = { vm.setPrimaryModule(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, Modules.ALL.size),
                    ) { Text(Modules.label(m)) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Sub("Turn a module on or off")
            Modules.ALL.forEach { m ->
                val isPrimary = Modules.primary(s) == m
                Toggle(Modules.label(m) + if (isPrimary) "  (primary)" else "", Modules.isEnabled(s, m)) { on -> vm.setModuleEnabled(m, on) }
            }
            Text("The primary module can't be switched off — pick a different primary first.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

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
            // PC1: honour a preference for stillness — mute the app's own motion.
            Toggle("Reduce motion", s.reduceMotion) { vm.setReduceMotion(it) }

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
            SmartKind.entries.forEach { k ->
                SmartVisRow(k.title, s.smartListVis[k] ?: SmartVis.SHOW) { v ->
                    val next = if (v == SmartVis.SHOW) s.smartListVis - k else s.smartListVis + (k to v)
                    vm.saveSettings(s.copy(smartListVis = next))
                }
            }
            Spacer(Modifier.height(10.dp)); Sub("Entry counts")
            Text("Show a live task count next to every list, folder, tag, context and filter (smart lists always show theirs).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
            Toggle("Show entry counts", s.showEntryCounts) { vm.saveSettings(s.copy(showEntryCounts = it)) }
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
                    FilterChip(selected = s.dailyCapacityHours == h && s.capacityByDay.isEmpty(), onClick = { vm.saveSettings(s.copy(dailyCapacityHours = h)) }, label = { Text("${h}h") })
                }
            }
            val perDayOn = s.capacityByDay.size == 7
            Toggle("Different capacity per weekday", perDayOn) { on ->
                vm.saveSettings(s.copy(capacityByDay = if (on) List(7) { s.dailyCapacityHours } else emptyList()))
            }
            if (perDayOn) {
                Text("Some days you can commit more than others — set each one. Weekends are often lighter.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
                java.time.DayOfWeek.values().forEach { dow ->
                    val idx = dow.value - 1
                    val h = s.capacityByDay.getOrElse(idx) { s.dailyCapacityHours }
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(dow.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()), Modifier.weight(1f))
                        TextButton(onClick = { vm.saveSettings(s.copy(capacityByDay = s.capacityByDay.toMutableList().also { it[idx] = (h - 1).coerceAtLeast(0) })) }) { Text("−") }
                        Text("${h}h", style = MaterialTheme.typography.titleSmall, modifier = Modifier.widthIn(min = 34.dp), textAlign = TextAlign.Center)
                        TextButton(onClick = { vm.saveSettings(s.copy(capacityByDay = s.capacityByDay.toMutableList().also { it[idx] = (h + 1).coerceAtMost(24) })) }) { Text("+") }
                    }
                }
            }
            // X3 — honest capacity: use your real tracked focus-hours as the planning figure.
            Toggle("Plan against my tracked focus-hours", s.honestCapacity) { on ->
                vm.saveSettings(s.copy(honestCapacity = on))
            }
            if (s.honestCapacity) {
                val trackedH = vm.trackedCapacityHours()
                Text(
                    if (trackedH != null) "Your recent median is about ${trackedH}h of tracked focus a day — the forecast now plans against that instead of the figure above."
                    else "Not enough tracked time yet — the forecast still uses the figure above until there's a week or so of tracking.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp),
                )
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
            Spacer(Modifier.height(10.dp)); Sub("Deep-work goal")
            Text("Minutes of focused time you aim for each day. Powers the Focus coach's progress and streak.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(30, 60, 90, 120, 180).forEach { m ->
                    FilterChip(selected = s.deepWorkGoalMin == m, onClick = { vm.saveSettings(s.copy(deepWorkGoalMin = m)) }, label = { Text(if (m >= 60) "${m / 60}h${if (m % 60 != 0) " ${m % 60}m" else ""}" else "${m}m") })
                }
            }
        }

        SettingsGroup(Icons.Filled.CalendarMonth, "Calendar", open["calendar"] == true, { open["calendar"] = open["calendar"] != true }) {
            Toggle("Show habits on the calendar", s.habitCalendarBlocks) { on -> vm.saveSettings(s.copy(habitCalendarBlocks = on)) }
            Text("Draw timed habits as blocks in the day and week calendar, next to your task time-blocks. Off by default.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (Modules.isEnabled(s, Modules.TIME)) {
            SettingsGroup(Icons.Filled.Schedule, "Time tracking", open["timetrack"] == true, { open["timetrack"] = open["timetrack"] != true }) {
                Toggle("Prompt to track time-blocks", s.autoTrackPrompt) { on ->
                    vm.saveSettings(s.copy(autoTrackPrompt = on)); vm.rescheduleTrackPrompts()
                }
                Text("When a task with a start time (not an all-day task) reaches that time later today, a notification offers a one-tap “Start tracking”. Give a task a due date with a time to see it. Needs notifications + exact-alarm permission. (U2)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Toggle("Account for my whole day", s.timelineFill) { on -> vm.saveSettings(s.copy(timelineFill = on)) }
                Text("Timeline-fill mode: gaps between tracked blocks become tappable “what were you doing?” chips on the Time screen, so every part of the day is accounted for. (U5)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Toggle("Allow overlapping timers", s.multiTimer) { on -> vm.saveSettings(s.copy(multiTimer = on)) }
                Text("Multi-timer: run more than one activity at once instead of switching. Off keeps the simple single-timer. (U15)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Toggle("Reveal untracked time on the calendar", s.untrackedReveal) { on -> vm.saveSettings(s.copy(untrackedReveal = on)) }
                Text("Shade the day-column gaps between tracked intervals so uncounted time is visible. (U14)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (Modules.isEnabled(s, Modules.HABITS)) {
            SettingsGroup(Icons.Filled.Whatshot, "Streaks", open["streaks"] == true, { open["streaks"] = open["streaks"] != true }) {
                Toggle("Forgiving streaks", s.forgivingStreaks) { on -> vm.saveSettings(s.copy(forgivingStreaks = on)) }
                Text("Tolerate the odd missed day (about one a week) instead of resetting to zero — consistency over brittle chains, so one slip never wipes weeks of momentum. (U8)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                // Z8 — explain-and-opt-in migration: preview the before/after before changing scores.
                Toggle("Count partial days toward strength", s.gradedStrength) { on -> vm.setGradedStrength(on) }
                val preview = remember(s.gradedStrength) { vm.gradedStrengthPreview() }
                Text(
                    if (preview == null) "When on, a day you attempted but fell short of the goal earns partial credit toward the strength score, instead of counting as a miss."
                    else "When on, a partially-met day earns partial credit instead of a miss. On your data, average strength would move ${preview.first}% → ${preview.second}%. Your call — it only changes once you turn it on.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // V12: the self-defined rewards store.
        SettingsGroup(Icons.Filled.Star, "Rewards", open["rewards"] == true, { open["rewards"] = open["rewards"] != true }) {
            val rewards = com.todocompanion.app.domain.Rewards.parse(s.rewardsJson)
            Text("You earn ⭐ points by keeping habits and finishing tasks — currently ${s.pointsBalance}. Spend them on treats you set for yourself. Encouragement, never punishment.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            rewards.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.emoji} ${r.name} · ${r.cost} pts" + (if (r.redeemed > 0) "  (redeemed ${r.redeemed}×)" else ""), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { vm.saveRewards(rewards.filter { it.id != r.id }) }) { Icon(Icons.Filled.Delete, "Remove", modifier = Modifier.size(18.dp)) }
                }
            }
            var rName by remember { mutableStateOf("") }
            var rCost by remember { mutableStateOf("10") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                com.todocompanion.app.ui.components.AppTextField(rName, { rName = it }, label = { Text("New reward") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                com.todocompanion.app.ui.components.AppTextField(rCost, { v -> rCost = v.filter { it.isDigit() }.take(4) }, label = { Text("Cost") }, singleLine = true, modifier = Modifier.width(90.dp))
            }
            TextButton(enabled = rName.isNotBlank(), onClick = {
                vm.saveRewards(rewards + com.todocompanion.app.domain.Reward(id = java.util.UUID.randomUUID().toString(), name = rName.trim(), cost = rCost.toIntOrNull()?.coerceAtLeast(1) ?: 10))
                rName = ""; rCost = "10"
            }) { Text("＋ Add reward") }
        }

        // W6: routine tags — a named bundle launched by one NFC/QR tap or shortcut.
        if (Modules.isEnabled(s, Modules.TIME)) {
            val activities by vm.timeActivities.collectAsState()
            SettingsGroup(Icons.Filled.Bolt, "Routines", open["routines"] == true, { open["routines"] = open["routines"] != true }) {
                Text("A routine starts an activity's timer and surfaces its habit group in one tap. Fire it from a home-screen tap, or write its link — todocompanion://routine?name=NAME — to an NFC tag or QR.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                val routines = com.todocompanion.app.domain.Routines.parse(s.routinesJson)
                routines.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${r.emoji} ${r.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { vm.runRoutine(r) }) { Text("Run") }
                        IconButton(onClick = { vm.saveRoutines(routines.filter { it.id != r.id }) }) { Icon(Icons.Filled.Delete, "Remove", modifier = Modifier.size(18.dp)) }
                    }
                }
                var routName by remember { mutableStateOf("") }
                var routAct by remember { mutableStateOf<String?>(null) }
                com.todocompanion.app.ui.components.AppTextField(routName, { routName = it }, label = { Text("New routine name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    activities.filter { !it.archived }.forEach { a ->
                        FilterChip(selected = routAct == a.id, onClick = { routAct = if (routAct == a.id) null else a.id }, label = { Text((a.emoji?.plus(" ") ?: "") + a.name) })
                    }
                }
                TextButton(enabled = routName.isNotBlank(), onClick = {
                    vm.saveRoutines(routines + com.todocompanion.app.domain.Routine(id = java.util.UUID.randomUUID().toString(), name = routName.trim(), activityId = routAct ?: ""))
                    routName = ""; routAct = null
                }) { Text("＋ Add routine") }
            }
        }

        SettingsGroup(Icons.Filled.EditNote, "Task editor", open["editor"] == true, { open["editor"] = open["editor"] != true }) {
            Text("The editor shows a lean set of fields first and reveals the rest under “More fields.” Choose when each appears, or drag the order to match how you work. A field you’ve already filled always shows, whatever you pick here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            val ordered = s.editorFieldsOrdered()
            ordered.forEachIndexed { idx, f ->
                val tier = s.editorTier(f)
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = {
                            if (idx > 0) {
                                val ids = ordered.map { it.id }.toMutableList()
                                val tmp = ids[idx]; ids[idx] = ids[idx - 1]; ids[idx - 1] = tmp
                                vm.saveSettings(s.copy(editorFieldOrder = ids))
                            }
                        }, enabled = idx > 0, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.KeyboardArrowUp, "Move up", modifier = Modifier.size(20.dp)) }
                        IconButton(onClick = {
                            if (idx < ordered.lastIndex) {
                                val ids = ordered.map { it.id }.toMutableList()
                                val tmp = ids[idx]; ids[idx] = ids[idx + 1]; ids[idx + 1] = tmp
                                vm.saveSettings(s.copy(editorFieldOrder = ids))
                            }
                        }, enabled = idx < ordered.lastIndex, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.KeyboardArrowDown, "Move down", modifier = Modifier.size(20.dp)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                        listOf(
                            com.todocompanion.app.domain.AppSettings.TIER_ALWAYS to "Always",
                            com.todocompanion.app.domain.AppSettings.TIER_MORE to "Under “More”",
                            com.todocompanion.app.domain.AppSettings.TIER_HIDDEN to "Hidden",
                        ).forEach { (t, label) ->
                            FilterChip(selected = tier == t, onClick = { vm.saveSettings(s.copy(editorFieldTiers = s.editorFieldTiers + (f.id to t))) }, label = { Text(label, style = MaterialTheme.typography.labelMedium) })
                        }
                    }
                }
            }
            if (s.editorFieldTiers.isNotEmpty() || s.editorFieldOrder.isNotEmpty()) {
                TextButton(onClick = { vm.saveSettings(s.copy(editorFieldTiers = emptyMap(), editorFieldOrder = emptyList())) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                    Text("Reset to defaults")
                }
            }
        }

        SettingsGroup(Icons.Filled.Notifications, "Reminders", open["reminders"] == true, { open["reminders"] = open["reminders"] != true }) {
            Toggle("Daily summary notification", s.dailySummaryEnabled) { vm.saveSettings(s.copy(dailySummaryEnabled = it)) }
            // W8: per-list mute — silence reminders for chosen lists.
            val lists by vm.lists.collectAsState()
            if (lists.any { !it.archived }) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                Text("Mute reminders from these lists", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    lists.filter { !it.archived }.forEach { l ->
                        FilterChip(selected = l.id in s.mutedLists, onClick = { vm.toggleMutedList(l.id) }, label = { Text(l.name.take(16)) })
                    }
                }
            }
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
            // Z4 — the morning brief: one calm daily note instead of scattered pings.
            Toggle("Morning brief", s.morningBriefEnabled) { vm.setMorningBrief(it, s.morningBriefHour) }
            if (s.morningBriefEnabled) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Brief time", Modifier.weight(1f))
                    TextButton(onClick = { vm.setMorningBrief(true, (s.morningBriefHour - 1).coerceAtLeast(0)) }) { Text("−") }
                    Text("%02d:00".format(s.morningBriefHour), Modifier.widthIn(min = 52.dp), textAlign = TextAlign.Center)
                    TextButton(onClick = { vm.setMorningBrief(true, (s.morningBriefHour + 1).coerceAtMost(23)) }) { Text("+") }
                }
                Text("One note each morning: your next action, today's honest forecast, and one insight.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Text("Reminder reliability", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
            // PC6: a live self-check — surface exactly what the OS is set to throttle, in plain words.
            val health = remember(s) { vm.reminderHealth() }
            if (health.ok) {
                Text("✓ All clear — reminders will fire on time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp))
            } else {
                health.issues.forEach { Text("⚠︎ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 2.dp)) }
            }
            Action("Allow exact alarms") { openExactAlarmSettings(context) }
            Action("Ignore battery optimisation") { openBatterySettings(context) }
        }

        SettingsGroup(Icons.Filled.Lock, "Privacy", open["privacy"] == true, { open["privacy"] = open["privacy"] != true }) {
            Toggle("Require unlock to open", s.appLockEnabled) { vm.saveSettings(s.copy(appLockEnabled = it)) }
            Text("Ask for your fingerprint, face or device PIN each time the app opens (strong biometric preferred). All checks happen on-device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Toggle("Block screenshots & screen recording", s.secureScreen) { vm.saveSettings(s.copy(secureScreen = it)) }
            Text("Marks the app secure (FLAG_SECURE): screenshots, screen recorders and the recent-apps thumbnail can't capture your content.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Toggle("Hide notification content on lock screen", s.lockscreenPrivacy) { vm.saveSettings(s.copy(lockscreenPrivacy = it)) }
            Text("Reminder and summary notifications show only a generic title on a locked screen — task names stay hidden until you unlock.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            // Plan A — at-rest database encryption (SQLCipher). Desired state is local to SecureDb and
            // applies on the next launch, so this toggle is remembered locally + prompts a restart.
            Text("Encryption at rest", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 2.dp))
            var wantEnc by remember { mutableStateOf(vm.dbEncryptionDesired()) }
            val encActual = remember(wantEnc) { vm.dbEncryptionActual() }
            val encPending = wantEnc != encActual
            Toggle("Encrypt the database (SQLCipher / AES-256)", wantEnc) { on ->
                wantEnc = on; vm.setDbEncryption(on)
            }
            Text("Encrypts your on-device database with a 256-bit key held in this device's hardware key store (StrongBox when available). Protects the data if the database file is ever copied off a lost or powered-off device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (encPending) Text(
                if (wantEnc) "⟳ Restart the app to finish encrypting your data (a one-time, verified migration — a backup is made first)."
                else "⟳ Restart the app to finish removing encryption.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
            else Text(if (encActual) "● Your database is encrypted." else "○ Your database is not encrypted.",
                style = MaterialTheme.typography.bodySmall, color = if (encActual) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            vm.dbEncryptionError().takeIf { it.isNotBlank() }?.let {
                Text("Last attempt didn't complete: $it — your data is safe and unchanged. Make a backup, then try again.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp))
            }
            Text("What it does NOT protect: a rooted device while the app is installed and unlocked. And note — uninstalling the app erases the encryption key, so keep a JSON backup as your recovery copy.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            // Z7 — the trust dashboard: make the zero-permission promise something you can see.
            Text("Trust & your data", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 2.dp))
            listOf(
                "0 network permission — the app cannot reach the internet.",
                "0 location permission — it never asks where you are.",
                "Everything lives only on this device.",
            ).forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            val dc = remember(s) { vm.dataCounts() }
            Spacer(Modifier.height(6.dp))
            Text("On this device: ${dc.tasks} tasks · ${dc.habits} habits · ${dc.checkins} check-ins · ${dc.timeEntries} time entries · ${dc.activities} activities · ${dc.focus} focus sessions.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Action("Export a full copy (JSON)") {
                vm.exportToDownloads("json") { loc -> Toast.makeText(context, if (loc != null) "Saved to $loc" else "Couldn't save", Toast.LENGTH_SHORT).show() }
            }
            Text("To erase everything, export a copy first, then clear the app's storage in Android Settings or uninstall — because nothing is on a server, that removes every trace.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
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
            Action("Export all data (JSON)") { safeExport("json") { exportLauncher.launch("todo-companion-backup.json") } }
            Action("Import / restore (JSON)") { safeImport { importLauncher.launch("*/*") } }
            Action("Browse device for a file…") { requestAndBrowse() }
            Action("Restore from a saved backup…") { openRestore(broad = false) }
            Action("Import from the app inbox…") { openRestore(broad = true) }
            Action("Paste backup text…") { showPaste = true }
            Text("Complete, lossless local backup. No account, no cloud, no network. No file picker needed: exports save straight to Downloads, and either browser reads a backup back with no picker at all. To import a file from another app, copy it into ${vm.importInboxHint()} — it appears under “Import a file on device”.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Action("Export as Markdown (.md)") { safeExport("md") { exportMdLauncher.launch("todo-companion.md") } }
            Action("Export as CSV (spreadsheet)") { safeExport("csv") { exportCsvLauncher.launch("todo-companion.csv") } }
            Action("Export to calendar (.ics)") { safeExport("ics") { exportIcsLauncher.launch("todo-companion.ics") } }
            Action("Import a calendar (.ics) → tasks") { safeImport { importIcsLauncher.launch("*/*") } }
            Text("A two-way calendar bridge: export your dated tasks into any calendar, or import an .ics that a calendar exported back in as tasks. Fully on-device — no network.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            // CU4: one-tap handoff via the system share sheet — 0 permission, uses the OS's own Nearby Share/Bluetooth.
            Action("Send a copy to another device") { vm.shareBackupCopy() }
            Text("Hands a full JSON copy to Android's share sheet — beam it with Nearby Share, Bluetooth or any app you already have. Frictionless device-to-device, and we still ask for no network permission.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Action("Import from Todoist / TickTick / MLO") { safeImport { importExternalLauncher.launch("*/*") } }
            Text("Reads their CSV export (Todoist, TickTick) or OPML (MLO) on-device — no account, nothing uploaded.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Action("Export habits (CSV)") { safeExport("habits") { exportHabitsLauncher.launch("todo-companion-habits.csv") } }
            Action("Import habits (Loop / CSV)") { safeImport { importHabitsLauncher.launch("*/*") } }
            Text("Move habit check-ins in and out — reads Loop Habit Tracker's Checkmarks export or our own habit CSV.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }

        SettingsGroup(Icons.Filled.Sync, "Backup & sync (folder)", open["sync"] == true, { open["sync"] = open["sync"] != true }) {
            Text("Fully account-free: point the app at a folder (device, or a drive you already sync like Drive / Dropbox / Syncthing). Nothing goes to us.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            Toggle("Automatic backup", s.autoBackupEnabled) { on -> if (on && s.autoBackupFolder.isBlank()) safePick { backupFolderLauncher.launch(null) } else vm.setAutoBackupEnabled(on) }
            if (s.autoBackupEnabled) {
                Action(if (s.autoBackupFolder.isBlank()) "Choose backup folder…" else "Backup folder: " + folderLabel(s.autoBackupFolder)) { safePick { backupFolderLauncher.launch(null) } }
                Text("A dated JSON copy is written daily around ${"%02d:00".format(s.autoBackupHour)}.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Action("Back up now") { vm.runBackupNow { ok -> Toast.makeText(context, if (ok) "Backed up" else "Choose a folder first", Toast.LENGTH_SHORT).show() } }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Toggle("Sync across devices (shared folder)", s.syncEnabled) { on -> if (on && s.syncFolder.isBlank()) safePick { syncFolderLauncher.launch(null) } else vm.setSyncEnabled(on) }
            if (s.syncEnabled || s.syncFolder.isNotBlank()) {
                Action(if (s.syncFolder.isBlank()) "Choose sync folder…" else "Sync folder: " + folderLabel(s.syncFolder)) { safePick { syncFolderLauncher.launch(null) } }
                Action("Sync now") { vm.runSyncNow { ok, msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() } }
                // G2: last-sync change summary is more informative than just a timestamp.
                if (s.lastSyncSummary.isNotBlank()) Text(s.lastSyncSummary + (if (s.lastSyncAt > 0) " · " + formatDue(s.lastSyncAt) else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                else if (s.lastSyncAt > 0) Text("Last synced " + formatDue(s.lastSyncAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Point every device at the same folder. Merges are last-write-wins per task; each device keeps its own settings.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // V11: the honest, offline answer to cross-device sync — a documented Syncthing recipe.
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Text("Multi-device, still offline", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 2.dp))
            Text("Want your phone and tablet in sync without an account? Install Syncthing (free, open-source, peer-to-peer — no server sees your data) on both devices, share one folder between them, and point the sync folder above at it on each. Your encrypted backup files travel device-to-device over your own network. It's the private, account-free way to get real cross-device sync.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            // G1: at-rest encryption for whatever lands in the folder.
            var pass by remember(s.syncPassphrase) { mutableStateOf(s.syncPassphrase) }
            var showPass by remember { mutableStateOf(false) }
            Text("Encrypt folder files", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 2.dp))
            Text("Set a passphrase and every backup/sync file is AES-encrypted — unreadable to the drive it lands on, and to us. Keep it safe: lose it and those files can't be recovered.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            com.todocompanion.app.ui.components.AppTextField(
                value = pass, onValueChange = { pass = it },
                label = { Text("Passphrase (blank = off)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showPass) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "Hide" else "Show") } },
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = pass != s.syncPassphrase, onClick = {
                    vm.setSyncPassphrase(pass)
                    Toast.makeText(context, if (pass.isBlank()) "Encryption off" else "Encryption on — new files will be encrypted", Toast.LENGTH_SHORT).show()
                }) { Text("Save passphrase") }
            }
        }

        SettingsGroup(Icons.Filled.School, "Help & tips", open["help"] == true, { open["help"] = open["help"] != true }) {
            Text("New here, or want a refresher? Replay the guided welcome tour any time.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
            Action("Replay the welcome tour") { vm.replayOnboarding(); Toast.makeText(context, "Tour will start", Toast.LENGTH_SHORT).show() }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Text("Tip: press ✦ in the top bar to open the command palette — type or tap “All commands” to see everything the app can do (capture, track time, jump anywhere, ask your data).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    if (restoreOpen) {
        var confirming by remember { mutableStateOf<com.todocompanion.app.util.FileExport.SavedFile?>(null) }
        AlertDialog(
            onDismissRequest = { restoreOpen = false },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { restoreOpen = false }) { Text("Close") } },
            title = { Text(if (restoreBroad) "Import a file on device" else "Restore from a saved backup") },
            text = {
                val list = savedList
                when {
                    list == null -> Text("Looking for files…")
                    list.isEmpty() -> Text(
                        if (restoreBroad) "No importable files found. Copy your backup or export (JSON / CSV / OPML) into ${vm.importInboxHint()} using a file manager or USB, then reopen this."
                        else "No saved backups found. Use “Export all data (JSON)” first — it saves to your Downloads folder — then come back here.")
                    else -> androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(list, key = { (it.uri?.toString() ?: it.file?.absolutePath ?: it.name) }) { sf ->
                            Column(Modifier.fillMaxWidth().clickable { confirming = sf }.padding(vertical = 10.dp)) {
                                Text(sf.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(sf.location, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
        )
        confirming?.let { sf ->
            AlertDialog(
                onDismissRequest = { confirming = null },
                confirmButton = {
                    TextButton(onClick = {
                        val target = sf; confirming = null; restoreOpen = false
                        vm.restoreSaved(target) { ok, msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
                    }) { Text(if (sf.name.endsWith(".json", true)) "Restore" else "Import") }
                },
                dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
                title = { Text(if (sf.name.endsWith(".json", true)) "Restore this file?" else "Import this file?") },
                text = {
                    Text(if (sf.name.endsWith(".json", true))
                        "Restoring ${sf.name} replaces ALL current data with the contents of this backup. This can't be undone."
                    else "Import tasks from ${sf.name} into your current data.")
                },
            )
        }
    }
    if (showPaste) {
        var pasteText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPaste = false },
            confirmButton = {
                TextButton(enabled = pasteText.isNotBlank(), onClick = {
                    val t = pasteText; showPaste = false
                    vm.importPastedText(t) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { showPaste = false }) { Text("Cancel") } },
            title = { Text("Paste backup text") },
            text = {
                Column {
                    Text("Paste a backup you copied from another device (a JSON export restores everything; a Todoist/TickTick CSV or MLO OPML imports its tasks). Nothing leaves the device.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                    com.todocompanion.app.ui.components.AppTextField(pasteText, { pasteText = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
                        label = { Text("Backup text") })
                }
            },
        )
    }
    if (browseOpen) FileBrowser(vm, onDismiss = { browseOpen = false }, onPicked = { file ->
        browseOpen = false
        vm.importBrowsedFile(file) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    })
}

/**
 * A self-rendered filesystem browser (navigate folders + search filenames) so a backup can be picked
 * on a de-Googled phone that has no system document picker. Reads via java.io.File once storage access
 * is granted; fully offline.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FileBrowser(vm: AppViewModel, onDismiss: () -> Unit, onPicked: (java.io.File) -> Unit) {
    val roots = remember { com.todocompanion.app.util.FileExport.browseRoots() }
    var dir by remember { mutableStateOf(roots.firstOrNull() ?: java.io.File("/")) }
    var entries by remember { mutableStateOf<List<com.todocompanion.app.util.FileExport.Entry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    var confirming by remember { mutableStateOf<java.io.File?>(null) }
    androidx.compose.runtime.LaunchedEffect(dir) { vm.browseDir(dir) { entries = it } }
    androidx.compose.runtime.LaunchedEffect(query) { if (query.trim().length >= 2) vm.searchFilesystem(query) { results = it } else results = emptyList() }
    val searching = query.trim().length >= 2

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Text("Choose a backup file", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.width(56.dp))
                }
                com.todocompanion.app.ui.components.AppTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    label = { Text("Search filenames…") })
                if (!searching) {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        roots.forEach { r ->
                            FilterChip(selected = dir.absolutePath == r.absolutePath, onClick = { dir = r },
                                label = { Text(r.name.ifBlank { "Storage" }) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        val canUp = dir.parentFile != null && roots.none { it.absolutePath == dir.absolutePath }
                        TextButton(enabled = canUp, onClick = { dir.parentFile?.let { dir = it } }) { Text("↑ Up") }
                        Text(dir.absolutePath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                val rows: List<Pair<java.io.File, Boolean>> = if (searching) results.map { it to false } else entries.map { it.file to it.isDir }
                if (rows.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(if (searching) "No matching files" else "Empty folder — no importable files here", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(rows, key = { it.first.absolutePath }) { (f, isDir) ->
                        Row(Modifier.fillMaxWidth().clickable { if (isDir) { query = ""; dir = f } else confirming = f }.padding(vertical = 11.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isDir) "📁" else "📄", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (searching) Text(f.parent ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (isDir) Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    }
                }
            }
        }
    }
    confirming?.let { f ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            confirmButton = { TextButton(onClick = { onPicked(f); confirming = null }) { Text(if (f.name.endsWith(".json", true)) "Restore" else "Import") } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
            title = { Text(f.name) },
            text = { Text(if (f.name.endsWith(".json", true)) "Restoring this backup replaces ALL current data. This can't be undone." else "Import tasks/habits from this file into your current data.") },
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
                com.todocompanion.app.ui.components.AppTextField(query, { query = it }, placeholder = { Text("Search zones…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                com.todocompanion.app.ui.components.AppTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
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

/** A short human name for a SAF tree URI, e.g. "…/tree/primary:Backups" → "Backups". */
private fun folderLabel(uri: String): String = runCatching {
    val decoded = android.net.Uri.decode(uri)
    decoded.substringAfterLast(':').substringAfterLast('/').ifBlank { "folder" }
}.getOrDefault("folder")

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
