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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Sync
import com.todocompanion.app.ui.components.formatDue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Bookmark
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
import androidx.compose.runtime.LaunchedEffect
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

/** R28 #7 — the live settings-search query, read by every [SettingsGroup] so it can hide/expand itself. */
private val LocalSettingsQuery = androidx.compose.runtime.compositionLocalOf { "" }

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

    // R45 — every picker goes through SystemPicker → MainActivity's classic startActivityForResult
    // (no ActivityOptions bundle, the thing the ROM rejected). Each launcher below is now just a lambda
    // that opens the right route. Imports = OPEN_DOCUMENT; exports = CREATE_DOCUMENT; folders = tree.
    val err: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    val exportLauncher: (String) -> Unit = { name -> com.todocompanion.app.util.SystemPicker.createFile("application/json", name, onError = err) { uri -> vm.exportTo(uri) { ok -> Toast.makeText(context, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show() } } }
    val importLauncher: (Array<String>) -> Unit = { types -> com.todocompanion.app.util.SystemPicker.openFile(types, onError = err) { uri -> vm.importFrom(uri) { ok -> Toast.makeText(context, if (ok) "Imported" else "Import failed", Toast.LENGTH_SHORT).show() } } }
    val importExternalLauncher: (Array<String>) -> Unit = { types -> com.todocompanion.app.util.SystemPicker.openFile(types, onError = err) { uri -> vm.importExternal(uri) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() } } }
    val importIcsLauncher: (Array<String>) -> Unit = { types -> com.todocompanion.app.util.SystemPicker.openFile(types, onError = err) { uri -> vm.importIcs(uri) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() } } }
    fun persist(uri: android.net.Uri) = runCatching {
        context.contentResolver.takePersistableUriPermission(uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    val backupFolderLauncher: () -> Unit = { com.todocompanion.app.util.SystemPicker.openTree(onError = err) { uri -> persist(uri); vm.setAutoBackupFolder(uri.toString()) } }
    val syncFolderLauncher: () -> Unit = { com.todocompanion.app.util.SystemPicker.openTree(onError = err) { uri -> persist(uri); vm.setSyncFolder(uri.toString()) } }
    val exportMdLauncher: (String) -> Unit = { name -> com.todocompanion.app.util.SystemPicker.createFile("text/markdown", name, onError = err) { uri -> vm.exportMarkdownTo(uri, includeCompleted = true) { ok -> Toast.makeText(context, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show() } } }
    val exportCsvLauncher: (String) -> Unit = { name -> com.todocompanion.app.util.SystemPicker.createFile("text/csv", name, onError = err) { uri -> vm.exportCsvTo(uri, includeCompleted = true) { ok -> Toast.makeText(context, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show() } } }
    val exportIcsLauncher: (String) -> Unit = { name -> com.todocompanion.app.util.SystemPicker.createFile("text/calendar", name, onError = err) { uri -> vm.exportIcsTo(uri, includeCompleted = false) { ok -> Toast.makeText(context, if (ok) "Calendar exported" else "Export failed", Toast.LENGTH_SHORT).show() } } }
    val exportHabitsLauncher: (String) -> Unit = { name -> com.todocompanion.app.util.SystemPicker.createFile("text/csv", name, onError = err) { uri -> vm.exportHabitsCsvTo(uri) { ok -> Toast.makeText(context, if (ok) "Habits exported" else "Export failed", Toast.LENGTH_SHORT).show() } } }
    val importHabitsLauncher: (Array<String>) -> Unit = { types -> com.todocompanion.app.util.SystemPicker.openFile(types, onError = err) { uri -> vm.importHabitsCsv(uri) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() } } }
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
    // R28 #11 — one "Export as…" and one "Import from another app…" chooser instead of a long flat list.
    var showExportChooser by remember { mutableStateOf(false) }
    var showImportChooser by remember { mutableStateOf(false) }
    // R40 — no in-app file browser and no storage permission. Every import goes through the SYSTEM picker
    // (SAF, shows all files). If a device has no picker at all, fall back to the permissionless saved-backups
    // list (app-reachable backups + the import inbox) — never to a folder browser.
    fun safeImport(block: () -> Unit) { try { block() } catch (e: Exception) { openRestore(broad = true) } }

    // Collapsible category groups (TickTick-style compact list). All start collapsed.
    val open = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    // R28 #7 — settings search. The query is provided to every group via a CompositionLocal; a match from
    // the command palette (#5) pre-fills it through vm.settingsSearchQuery.
    var settingsQuery by remember { mutableStateOf("") }
    val seededQuery by vm.settingsSearchQuery.collectAsState()
    LaunchedEffect(seededQuery) { if (seededQuery.isNotBlank()) { settingsQuery = seededQuery; vm.settingsSearchQuery.value = "" } }

    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsQuery provides settingsQuery.trim()) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp)) {
        com.todocompanion.app.ui.components.AppTextField(
            settingsQuery, { settingsQuery = it }, singleLine = true,
            label = { Text("Search settings") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (settingsQuery.isNotEmpty()) IconButton(onClick = { settingsQuery = "" }) { Icon(Icons.Filled.Close, "Clear") } },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )

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

        SettingsGroup(Icons.Filled.Palette, "Appearance", open["appearance"] == true, { open["appearance"] = open["appearance"] != true }, keywords = "theme dark light mode dynamic color accent palette theme pack background tint density compact spacing fab position swipe actions gestures") {
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentSwatch(0L, s.accentArgb) { vm.saveSettings(s.copy(accentArgb = 0L, themePack = "")) }
                ACCENTS.forEach { c -> AccentSwatch(c, s.accentArgb) { vm.saveSettings(s.copy(accentArgb = c, themePack = "")) } }
                // R59 (Wave 1) — fold a fully custom accent into the app's unified colour picker (palette +
                // recents + HSV/hex), sitting right beside the curated presets. Clears any curated theme pack.
                com.todocompanion.app.ui.components.AppColorPicker(
                    current = s.accentArgb.takeIf { it != 0L },
                    onPick = { vm.saveSettings(s.copy(accentArgb = it ?: 0L, themePack = "")) },
                    allowNone = true,
                )
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

        SettingsGroup(Icons.Filled.Tune, "Do-Next priority", open["priority"] == true, { open["priority"] = open["priority"] != true }, keywords = "computed priority weights importance urgency due start goal overdue boost score") {
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

        SettingsGroup(Icons.Filled.RocketLaunch, "Startup", open["startup"] == true, { open["startup"] = open["startup"] != true }, keywords = "resume last place default view open launch") {
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

            // R30 #5 — long-press the first bottom-bar tab jumps to this view (default Inbox).
            Spacer(Modifier.height(14.dp)); Sub("Home shortcut")
            Text("Long-press the first bottom-bar tab to jump straight to this view.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            val navLists by vm.lists.collectAsState()
            val navFolders by vm.folders.collectAsState()
            val navTags by vm.tags.collectAsState()
            val navContexts by vm.contexts.collectAsState()
            val navFilters by vm.filters.collectAsState()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "smart:INBOX" to "Inbox", "smart:TODAY" to "Today", "smart:DO_NEXT" to "Do Next",
                    "smart:NEXT7" to "Next 7", "smart:SCHEDULED" to "Scheduled", "smart:ALL" to "All",
                ).forEach { (ref, label) ->
                    FilterChip(selected = s.navShortcutRef == ref, onClick = { vm.saveSettings(s.copy(navShortcutRef = ref)) }, label = { Text(label) })
                }
            }
            var navPick by remember { mutableStateOf(false) }
            val curLabel = run {
                val ref = s.navShortcutRef; val id = ref.substringAfter(':', "")
                when (ref.substringBefore(':')) {
                    "smart" -> id.lowercase().replaceFirstChar { c -> c.titlecase() }
                    "list" -> navLists.firstOrNull { it.id == id }?.name ?: "List"
                    "folder" -> "📁 " + (navFolders.firstOrNull { it.id == id }?.name ?: "Folder")
                    "tag" -> "#" + (navTags.firstOrNull { it.id == id }?.name ?: "tag")
                    "context" -> "@" + (navContexts.firstOrNull { it.id == id }?.name ?: "context")
                    "filter" -> navFilters.firstOrNull { it.id == id }?.name ?: "Filter"
                    else -> "Inbox"
                }
            }
            Box {
                androidx.compose.material3.TextButton(onClick = { navPick = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                    Text("Or pick a list / folder / tag / context / filter…")
                }
                DropdownMenu(expanded = navPick, onDismissRequest = { navPick = false }) {
                    navLists.forEach { l -> DropdownMenuItem(text = { Text(l.name) }, onClick = { vm.saveSettings(s.copy(navShortcutRef = "list:${l.id}")); navPick = false }) }
                    navFolders.forEach { f -> DropdownMenuItem(text = { Text("📁 ${f.name}") }, onClick = { vm.saveSettings(s.copy(navShortcutRef = "folder:${f.id}")); navPick = false }) }
                    navTags.forEach { t -> DropdownMenuItem(text = { Text("#${t.name}") }, onClick = { vm.saveSettings(s.copy(navShortcutRef = "tag:${t.id}")); navPick = false }) }
                    navContexts.forEach { c -> DropdownMenuItem(text = { Text("@${c.name}") }, onClick = { vm.saveSettings(s.copy(navShortcutRef = "context:${c.id}")); navPick = false }) }
                    navFilters.forEach { fl -> DropdownMenuItem(text = { Text("⚑ ${fl.name}") }, onClick = { vm.saveSettings(s.copy(navShortcutRef = "filter:${fl.id}")); navPick = false }) }
                }
            }
            Text("Now: $curLabel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }

        SettingsGroup(Icons.Filled.ViewSidebar, "Sidebar & tabs", open["sidebar"] == true, { open["sidebar"] = open["sidebar"] != true }, keywords = "smart lists entry counts bottom bar tabs drawer sections show hide navigation") {
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

        SettingsGroup(Icons.Filled.Schedule, "Date & time", open["datetime"] == true, { open["datetime"] = open["datetime"] != true }, keywords = "week start clock 24 hour day start rollover timezone capacity working hours deep work goal") {
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
            // R41 — a pinned secondary time-zone shown beside event times (a floating event keeps its wall clock).
            Spacer(Modifier.height(10.dp)); Sub("Second time-zone (calendar rail)")
            Text("Shown alongside event times in the editor. Off uses only your device zone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val zones = listOf("" to "Off", "UTC" to "UTC", "America/New_York" to "New York", "America/Los_Angeles" to "LA",
                    "Europe/London" to "London", "Asia/Dubai" to "Dubai", "Asia/Kolkata" to "India", "Asia/Tokyo" to "Tokyo")
                zones.forEach { (id, label) ->
                    FilterChip(selected = s.secondaryZoneId == id, onClick = { vm.setSecondaryZone(id) }, label = { Text(label) })
                }
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

        SettingsGroup(Icons.Filled.CalendarMonth, "Calendar & planner", open["calendar"] == true, { open["calendar"] = open["calendar"] != true }, keywords = "calendar habits blocks lunar moon phase protected window context mode routine planner defragment reflow") {
            Toggle("Show habits on the calendar", s.habitCalendarBlocks) { on -> vm.saveSettings(s.copy(habitCalendarBlocks = on)) }
            Text("Draw timed habits as blocks in the day and week calendar, next to your task time-blocks. Off by default.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Toggle("Moon-phase overlay", s.lunarOverlay) { on -> vm.setLunarOverlay(on) }
            Text("Marks the new, first-quarter, full and last-quarter moons on the month grid. Computed locally.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Protected windows — inviolable life-blocks the auto-scheduler treats as walls.
            val protectedWindows by vm.protectedWindows.collectAsState()
            Spacer(Modifier.height(10.dp)); Sub("Protected windows")
            Text("The planner never schedules into these. e.g. family dinner 19:00–20:00.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            protectedWindows.forEach { w ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${w.name} · %02d:00–%02d:00".format(w.startMin / 60, w.endMin / 60), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { vm.deleteProtectedWindow(w.id) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, "Remove", Modifier.size(18.dp)) }
                }
            }
            var pwName by remember { mutableStateOf("") }
            var pwStart by remember { mutableStateOf(19) }
            var pwEnd by remember { mutableStateOf(20) }
            OutlinedTextField(pwName, { pwName = it }, singleLine = true, placeholder = { Text("Window name") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("From", Modifier.weight(1f)); TextButton(onClick = { pwStart = (pwStart - 1 + 24) % 24 }) { Text("−") }; Text("%02d:00".format(pwStart)); TextButton(onClick = { pwStart = (pwStart + 1) % 24 }) { Text("+") }
                Spacer(Modifier.width(10.dp)); Text("To", Modifier.weight(1f)); TextButton(onClick = { pwEnd = (pwEnd - 1).coerceAtLeast(pwStart + 1) }) { Text("−") }; Text("%02d:00".format(pwEnd)); TextButton(onClick = { pwEnd = (pwEnd + 1).coerceAtMost(24) }) { Text("+") }
            }
            TextButton(onClick = { if (pwName.isNotBlank()) { vm.saveProtectedWindow(pwName, pwStart * 60, pwEnd * 60, emptyList()); pwName = "" } }, enabled = pwName.isNotBlank()) { Text("Add protected window") }

            // Context modes — a saved set of calendars to show; the rest hide.
            val contexts by vm.calContexts.collectAsState()
            val eCals by vm.eventCalendars.collectAsState()
            Spacer(Modifier.height(10.dp)); Sub("Context modes")
            Text("Save the calendars currently shown as a mode (Work / Personal). Activating one hides the rest.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            contexts.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${c.emoji} ${c.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (c.id == s.activeContextId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    TextButton(onClick = { vm.activateContext(c.id) }) { Text(if (c.id == s.activeContextId) "Active" else "Activate") }
                    IconButton(onClick = { vm.deleteContext(c.id) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, "Remove", Modifier.size(18.dp)) }
                }
            }
            if (s.activeContextId.isNotBlank()) TextButton(onClick = { vm.activateContext("") }) { Text("Show all calendars") }
            var ctxName by remember { mutableStateOf("") }
            OutlinedTextField(ctxName, { ctxName = it }, singleLine = true, placeholder = { Text("Name this context") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            TextButton(onClick = { if (ctxName.isNotBlank()) { vm.saveContext(ctxName, eCals.filter { it.visible }.map { it.id }); ctxName = "" } }, enabled = ctxName.isNotBlank()) { Text("Save shown calendars as “${ctxName.ifBlank { "…" }}”") }

            // Day routines — created in the Planner; managed here.
            val routines by vm.dayRoutines.collectAsState()
            if (routines.isNotEmpty()) {
                Spacer(Modifier.height(10.dp)); Sub("Day routines")
                routines.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${r.emoji} ${r.name} · ${r.blocks.size} block${if (r.blocks.size == 1) "" else "s"}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { vm.deleteDayRoutine(r.id) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, "Remove", Modifier.size(18.dp)) }
                    }
                }
            }
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
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                // R34 · calm mode — hide points/streaks/celebration to protect intrinsic motivation (SDT).
                Toggle("Calm mode", s.calmMode) { on -> vm.setCalmMode(on) }
                Text("Hide points, streak flames and celebrations across habits — a quiet tracker for when visible rewards would crowd out the real motivation.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                // R34 · chronotype — the coach flags habits scheduled against your low-energy window.
                Text("Your chronotype", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("When set, the habit coach nudges habits scheduled against your natural low-energy window.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Neutral", "Morning lark", "Night owl").forEachIndexed { i, lbl ->
                        FilterChip(selected = s.chronotype == i, onClick = { vm.setChronotype(i) }, label = { Text(lbl) })
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                // R35 · third-wave toggles.
                Toggle("Strength meter", s.strengthMeter) { on -> vm.setStrengthMeter(on) }
                Text("Show the forgiving strength % as the headline habit metric instead of the streak flame — every rep counts, a few misses barely dent it (Loop-style).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                Toggle("Daily AM/PM bookends", s.bookendsEnabled) { on -> vm.setBookends(on) }
                Text("A morning-intention card before noon and an evening-review card after 5pm, on the Today list — the daily reflection loop between your weekly reviews.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                Toggle("Companion garden", s.companionEnabled) { on -> vm.setCompanion(on) }
                Text("A plant that grows from your consistency, shown on the habits screen — never shamed on a miss. A calm alternative to numbers (open it from Life systems › Your garden).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                // R36 · fourth-wave: new-habit WIP limiter.
                Text("New-habit focus limit", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("Cap how many habits can be forming at once. When you're over the cap, the Habits screen gently suggests finishing one before starting another. Attention is the scarce resource.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 2, 3, 4, 5).forEach { n ->
                        FilterChip(selected = s.habitWipLimit == n, onClick = { vm.setHabitWipLimit(n) }, label = { Text(if (n == 0) "Off" else "$n") })
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                // R37 · task ports.
                Text("Task focus limit (personal kanban)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("Cap how many tasks can be in progress (started, not done) at once. Over the cap, Today nudges you to finish one before starting another.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 3, 5, 7, 10).forEach { n ->
                        FilterChip(selected = s.taskWipLimit == n, onClick = { vm.setTaskWipLimit(n) }, label = { Text(if (n == 0) "Off" else "$n") })
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                Toggle("Time reminders to my peak", s.receptivityTiming) { on -> vm.setReceptivityTiming(on) }
                Text("Shift the daily brief and evening review to the hour you're most likely to act, learned from when you actually finish habits and tasks. Off = use the fixed times.",
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
            // R34 · the intrinsic reward menu — real treats YOU choose to grant yourself at milestones.
            // The app never invents the reward (avoids overjustification); it just holds your own list.
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Text("Your reward menu", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("A list of real rewards you grant yourself at milestones — points-free, self-chosen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.rewardMenu.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🎁 $r", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { vm.removeReward(r) }) { Icon(Icons.Filled.Delete, "Remove", modifier = Modifier.size(18.dp)) }
                }
            }
            var mName by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                com.todocompanion.app.ui.components.AppTextField(mName, { mName = it }, label = { Text("A reward you'd grant yourself") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                TextButton(enabled = mName.isNotBlank(), onClick = { vm.addReward(mName); mName = "" }) { Text("Add") }
            }
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

        SettingsGroup(Icons.Filled.EditNote, "Task editor", open["editor"] == true, { open["editor"] = open["editor"] != true }, keywords = "fields tier always more hidden reorder reflection estimate energy flag attachments") {
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

        SettingsGroup(Icons.Filled.Notifications, "Reminders", open["reminders"] == true, { open["reminders"] = open["reminders"] != true }, keywords = "notification daily summary evening review morning brief exact alarm battery optimization intensity gentle persistent insistent snooze duration escalate") {
            // R59 (Wave 1) — the default intensity for new task reminders + the snooze duration every
            // notification's Snooze action uses.
            Text("Default reminder intensity", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                com.todocompanion.app.domain.reminders.ReminderPresets.TIER_LABELS.forEachIndexed { i, label ->
                    FilterChip(selected = s.defaultReminderTier == i, onClick = { vm.saveSettings(s.copy(defaultReminderTier = i)) }, label = { Text(label) })
                }
            }
            Text(com.todocompanion.app.domain.reminders.ReminderPresets.TIER_BLURBS[s.defaultReminderTier.coerceIn(0, 2)],
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            Text("Snooze duration", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                com.todocompanion.app.domain.reminders.ReminderPresets.SNOOZE.forEach { m ->
                    FilterChip(selected = s.defaultSnoozeMin == m, onClick = { vm.saveSettings(s.copy(defaultSnoozeMin = m)) }, label = { Text(com.todocompanion.app.domain.reminders.ReminderPresets.snoozeLabel(m)) })
                }
            }
            Text("Every notification's Snooze button uses this.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            // R59 (Wave 2) — quiet hours: hold overnight reminders and deliver them together in the morning.
            Toggle("Quiet hours", s.quietHoursEnabled) { vm.saveSettings(s.copy(quietHoursEnabled = it)) }
            if (s.quietHoursEnabled) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("From", Modifier.weight(1f))
                    TextButton(onClick = { vm.saveSettings(s.copy(quietStartHour = (s.quietStartHour + 23) % 24)) }) { Text("−") }
                    Text("%02d:00".format(s.quietStartHour), Modifier.widthIn(min = 52.dp), textAlign = TextAlign.Center)
                    TextButton(onClick = { vm.saveSettings(s.copy(quietStartHour = (s.quietStartHour + 1) % 24)) }) { Text("+") }
                    Spacer(Modifier.width(12.dp))
                    Text("to", Modifier.weight(1f))
                    TextButton(onClick = { vm.saveSettings(s.copy(quietEndHour = (s.quietEndHour + 23) % 24)) }) { Text("−") }
                    Text("%02d:00".format(s.quietEndHour), Modifier.widthIn(min = 52.dp), textAlign = TextAlign.Center)
                    TextButton(onClick = { vm.saveSettings(s.copy(quietEndHour = (s.quietEndHour + 1) % 24)) }) { Text("+") }
                }
                Text("Reminders due in this window are held and arrive together when it ends — a calm morning digest.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            // R59 (Wave 3) — focus-block DND: silence notifications while a Focus session runs.
            val dndCtx = androidx.compose.ui.platform.LocalContext.current
            Toggle("Silence notifications during Focus", s.focusDnd) { on ->
                vm.saveSettings(s.copy(focusDnd = on))
                if (on && !com.todocompanion.app.reminders.FocusDnd.hasAccess(dndCtx))
                    runCatching { dndCtx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            Text("Puts your phone on Do Not Disturb while a Focus session runs. Needs a one-time Do-Not-Disturb access grant (tap the toggle to open it).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
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
            // R46 Occasions — an ongoing "next occasion" notification, and a gentle daily reflection.
            Toggle("Pin next occasion to notifications", s.occasionLiveNotif) { vm.saveSettings(s.copy(occasionLiveNotif = it)); vm.refreshOccasionNotification() }
            Text("A quiet, ongoing note showing the soonest birthday, anniversary or countdown.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Toggle("Daily reflection", s.occasionNudge) { vm.saveSettings(s.copy(occasionNudge = it)); vm.applyOccasionNudge() }
            if (s.occasionNudge) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Reflection time", Modifier.weight(1f))
                    TextButton(onClick = { vm.saveSettings(s.copy(occasionNudgeHour = (s.occasionNudgeHour - 1).coerceAtLeast(0))); vm.applyOccasionNudge() }) { Text("−") }
                    Text("%02d:00".format(s.occasionNudgeHour), Modifier.widthIn(min = 52.dp), textAlign = TextAlign.Center)
                    TextButton(onClick = { vm.saveSettings(s.copy(occasionNudgeHour = (s.occasionNudgeHour + 1).coerceAtMost(23))); vm.applyOccasionNudge() }) { Text("+") }
                }
                Text("One gentle, finite-time thought a day, paired with a this-day-in-history note. No cloud.",
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

        SettingsGroup(Icons.Filled.Lock, "Privacy", open["privacy"] == true, { open["privacy"] = open["privacy"] != true }, keywords = "app lock biometric fingerprint secure screen screenshot lockscreen encrypt database sqlcipher security trust") {
            Toggle("Require unlock to open", s.appLockEnabled) { vm.saveSettings(s.copy(appLockEnabled = it)) }
            Text("Ask for your fingerprint, face or device PIN each time the app opens (strong biometric preferred). All checks happen on-device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!s.appLockEnabled) {
                Toggle("Lock The Record (proof vault)", s.lockRecord) { vm.saveSettings(s.copy(lockRecord = it)) }
                Text("Gate just your accomplishment record behind the device biometric, even when the whole app isn't locked.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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

        SettingsGroup(Icons.Filled.Bookmark, "Flags", open["flags"] == true, { open["flags"] = open["flags"] != true }) {
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

        SettingsGroup(Icons.Filled.CloudSync, "Backup", open["backup"] == true, { open["backup"] = open["backup"] != true }, keywords = "export import restore backup json csv markdown ics calendar todoist ticktick mlo habits share copy data") {
            // R28 #11 — one clear back-up-and-restore flow, then two choosers for everything else, instead of
            // 15 flat rows. Restore opens the in-app browser first (no system picker needed).
            Sub("Back up & restore")
            Action("Back up everything") { safeExport("json") { exportLauncher("todo-companion-backup.json") } }
            // Restore uses the SYSTEM file picker (ACTION_GET_CONTENT) first — no storage permission, and it
            // shows your files straight away. The in-app browser is only the fallback (offered below, and
            // reached automatically if the device has no system picker at all).
            Action("Restore a backup…") { safeImport { importLauncher(arrayOf("*/*")) } }
            Action("Send a copy to another device") { vm.shareBackupCopy() }
            // A permissionless alternative: a list of backups the app can already reach (its own saved
            // copies in Downloads + anything dropped into the import inbox). No picker, no permission.
            Action("Restore from a saved backup…") { openRestore(false) }
            Text("Your complete, lossless backup — no account, no cloud, no network. Restore opens your device’s file picker (no permission needed). You can also drop a file into ${vm.importInboxHint()}, or paste the backup text below.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Sub("More formats")
            Action("Export as…") { showExportChooser = true }
            Action("Import from another app…") { showImportChooser = true }
            Action("Paste backup text…") { showPaste = true }
            Text("Export a copy as Markdown, a spreadsheet (CSV), a calendar (.ics) or a habits CSV — or import from Todoist, TickTick, MLO, a calendar, or a habits CSV. Everything on-device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Sub("Maintenance")
            // R54 — live storage footprint, so "built for a decade" is visible (and the optimise is measurable).
            val allTasks by vm.tasks.collectAsState()
            val allEvents by vm.events.collectAsState()
            val allOccasions by vm.countdowns.collectAsState()
            var dbBytes by remember { mutableStateOf(vm.databaseSizeBytes()) }
            fun humanBytes(b: Long): String = when {
                b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
                b >= 1024 -> "%.0f KB".format(b / 1024.0)
                else -> "$b B"
            }
            Text("${allTasks.size} tasks · ${allEvents.size} events · ${allOccasions.size} occasions · database ${humanBytes(dbBytes)}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 2.dp))
            // R56 (Wave B / R1) — a database-health breakdown computed by COUNT aggregates in the database
            // itself (not by scanning in-memory lists), so it stays instant as the store grows.
            val rowCounts by androidx.compose.runtime.produceState(initialValue = emptyMap<String, Long>(), dbBytes) { value = vm.databaseRowCounts() }
            if (rowCounts.isNotEmpty()) {
                Text("Database health", style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                rowCounts.forEach { (label, n) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("%,d".format(n), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Action("Optimise storage now") { vm.optimizeStorage { dbBytes = vm.databaseSizeBytes() } }
            Text("Compacts and defragments the on-device database and rebuilds the full-text search index. Built for years of data — search stays fast into the hundred-thousands, and this keeps the file small. Safe and offline.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }

        SettingsGroup(Icons.Filled.Sync, "Backup & sync (folder)", open["sync"] == true, { open["sync"] = open["sync"] != true }, keywords = "automatic backup folder sync across devices shared folder passphrase encryption schedule") {
            Text("Fully account-free: point the app at a folder (device, or a drive you already sync like Drive / Dropbox / Syncthing). Nothing goes to us.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            // Defaults to the device's Downloads folder (no file picker, works on de-Googled devices);
            // the folder action below lets you point it at a synced folder instead.
            Toggle("Automatic backup", s.autoBackupEnabled) { on -> if (on && s.autoBackupFolder.isBlank()) vm.setAutoBackupFolder(com.todocompanion.app.data.sync.SyncEngine.DOWNLOADS_FOLDER) else vm.setAutoBackupEnabled(on) }
            if (s.autoBackupEnabled) {
                Action("Backup folder: " + folderLabel(s.autoBackupFolder.ifBlank { com.todocompanion.app.data.sync.SyncEngine.DOWNLOADS_FOLDER }) + " · change…") { safePick { backupFolderLauncher() } }
                if (s.autoBackupFolder == com.todocompanion.app.data.sync.SyncEngine.DOWNLOADS_FOLDER)
                    Action("Use Device Downloads (no picker)") { vm.setAutoBackupFolder(com.todocompanion.app.data.sync.SyncEngine.DOWNLOADS_FOLDER) }
                else Action("Reset to Device Downloads (no picker)") { vm.setAutoBackupFolder(com.todocompanion.app.data.sync.SyncEngine.DOWNLOADS_FOLDER) }
                Text("A dated JSON copy is written daily around ${"%02d:00".format(s.autoBackupHour)}. Choose a synced folder (Drive / Dropbox / Syncthing) to keep copies off-device.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Action("Back up now") { vm.runBackupNow { ok -> Toast.makeText(context, if (ok) "Backed up" else "Choose a folder first", Toast.LENGTH_SHORT).show() } }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Toggle("Sync across devices (shared folder)", s.syncEnabled) { on -> if (on && s.syncFolder.isBlank()) safePick { syncFolderLauncher() } else vm.setSyncEnabled(on) }
            if (s.syncEnabled || s.syncFolder.isNotBlank()) {
                Action(if (s.syncFolder.isBlank()) "Choose sync folder…" else "Sync folder: " + folderLabel(s.syncFolder)) { safePick { syncFolderLauncher() } }
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
        // R31 #2 — the same maker's mark that closes the sidebar.
        com.todocompanion.app.ui.components.AppSignature()
        Spacer(Modifier.height(8.dp))
    }
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
    // R28 #11 — the "Export as…" chooser (formats that used to be four separate rows).
    if (showExportChooser) AlertDialog(
        onDismissRequest = { showExportChooser = false },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { showExportChooser = false }) { Text("Close") } },
        title = { Text("Export a copy as…") },
        text = {
            Column {
                ChooserRow("Markdown (.md)", "A readable outline of your lists and tasks") { showExportChooser = false; safeExport("md") { exportMdLauncher("todo-companion.md") } }
                ChooserRow("Spreadsheet (CSV)", "Open in any spreadsheet app") { showExportChooser = false; safeExport("csv") { exportCsvLauncher("todo-companion.csv") } }
                ChooserRow("Calendar (.ics)", "Your dated tasks, for any calendar app") { showExportChooser = false; safeExport("ics") { exportIcsLauncher("todo-companion.ics") } }
                ChooserRow("Habits (CSV)", "Habit check-ins as a spreadsheet") { showExportChooser = false; safeExport("habits") { exportHabitsLauncher("todo-companion-habits.csv") } }
            }
        },
    )
    // The "Import from another app…" chooser.
    if (showImportChooser) AlertDialog(
        onDismissRequest = { showImportChooser = false },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { showImportChooser = false }) { Text("Close") } },
        title = { Text("Import from another app…") },
        text = {
            Column {
                ChooserRow("Todoist / TickTick / MLO", "Their CSV export, or MLO's OPML") { showImportChooser = false; safeImport { importExternalLauncher(arrayOf("*/*")) } }
                ChooserRow("Calendar (.ics) → tasks", "Turn a calendar export into tasks") { showImportChooser = false; safeImport { importIcsLauncher(arrayOf("*/*")) } }
                ChooserRow("Habits (Loop / CSV)", "Loop Habit Tracker's Checkmarks, or our CSV") { showImportChooser = false; safeImport { importHabitsLauncher(arrayOf("*/*")) } }
            }
        },
    )
}

/** One tappable row inside an export/import chooser dialog. */
@Composable
private fun ChooserRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp)) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
    var icon by remember { mutableStateOf(initial?.icon ?: "bookmark") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(name.trim().ifBlank { "Flag" }, color, icon) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (initial == null) "New flag" else "Edit flag") },
        text = {
            Column {
                com.todocompanion.app.ui.components.AppTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp)); Sub("Colour")
                com.todocompanion.app.ui.components.AppColorPicker(current = color, onPick = { color = it ?: color })
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
private fun SettingsGroup(icon: ImageVector, title: String, expanded: Boolean, onToggle: () -> Unit, keywords: String = "", content: @Composable ColumnScope.() -> Unit) {
    // R28 #7 — settings search: when a query is active, hide non-matching groups and force-expand the rest,
    // matching against the group title + its keyword hints. Filtering here keeps every group call unchanged.
    val query = LocalSettingsQuery.current
    if (query.isNotBlank() && !"$title $keywords".contains(query, ignoreCase = true)) return
    val effExpanded = expanded || query.isNotBlank()
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { onToggle() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Icon(if (effExpanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(visible = effExpanded) {
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
private fun folderLabel(uri: String): String {
    if (uri == com.todocompanion.app.data.sync.SyncEngine.DOWNLOADS_FOLDER) return "Device Downloads"
    return runCatching {
        val decoded = android.net.Uri.decode(uri)
        decoded.substringAfterLast(':').substringAfterLast('/').ifBlank { "folder" }
    }.getOrDefault("folder")
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
