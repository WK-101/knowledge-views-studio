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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.todocompanion.app.ui.components.MiniCheck
import com.todocompanion.app.ui.components.FLAG_COLORS
import com.todocompanion.app.ui.components.FlagIcons
import com.todocompanion.app.ui.components.OptionChips
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
    // R107 — tap-to-choose pickers replacing sprawling chip rows / +− steppers.
    var showThemePack by remember { mutableStateOf(false) }
    var showBg by remember { mutableStateOf(false) }
    var showWeekStart by remember { mutableStateOf(false) }
    var showSecondaryZone by remember { mutableStateOf(false) }
    var showSnoozeCustom by remember { mutableStateOf(false) }
    var showMute by remember { mutableStateOf(false) }
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
        SettingsSectionHeader("General")
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
            // R61 — the accent is chosen through the one unified colour picker like every other colour in the
            // app: the curated accents surface as its "Suggested" row, "Dynamic" is the no-colour option, and
            // the full palette + recents + custom HSV/hex sit behind the same swatch. Clears any theme pack.
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.todocompanion.app.ui.components.AppColorPicker(
                    current = s.accentArgb.takeIf { it != 0L },
                    onPick = { vm.saveSettings(s.copy(accentArgb = it ?: 0L, themePack = "")) },
                    allowNone = true,
                    presets = ACCENTS,
                    noneLabel = "Dynamic (Material You)",
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (s.accentArgb == 0L) "Dynamic (Material You)" else "Custom accent",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(6.dp))
            NavRow("Theme pack", THEME_PACKS.firstOrNull { it.id == s.themePack }?.label ?: "Custom",
                { showThemePack = true }, subtitle = "A coordinated accent + background in one tap",
                preview = { THEME_PACKS.firstOrNull { it.id == s.themePack }?.let { ThemePackSwatch(it, 22.dp) } })
            NavRow("App background", APP_BACKGROUNDS.firstOrNull { it.first == s.appBackground }?.second ?: "None",
                { showBg = true }, preview = { BackgroundSwatch(s.appBackground, 22.dp) })

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

        SettingsSectionHeader("Planning")
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
            NavRow("Week starts on", WEEK_STARTS.firstOrNull { it.first == s.weekStart }?.second ?: "System", { showWeekStart = true })
            Spacer(Modifier.height(6.dp)); Sub("Clock")
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
            Spacer(Modifier.height(6.dp))
            TimeSettingRow("Day starts at", s.dayStartMinuteOfDay(),
                subtitle = "Tasks before this still count under Today — for night owls") { m ->
                vm.saveSettings(s.copy(dayStartHour = (m / 60).coerceIn(0, 11), dayStartMinute = m % 60))
            }

            Spacer(Modifier.height(6.dp)); Sub("Daily capacity")
            Text("How much time you can realistically commit per day — powers the workload forecast and auto-schedule.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
            val perDayOn = s.capacityByDayMin.size == 7
            if (!perDayOn) DurationSettingRow("Capacity per day", s.dailyCapacityMin) { m ->
                vm.saveSettings(s.copy(dailyCapacityMin = m.coerceIn(30, 24 * 60)))
            }
            Toggle("Different capacity per weekday", perDayOn) { on ->
                vm.saveSettings(s.copy(capacityByDayMin = if (on) List(7) { s.dailyCapacityMin } else emptyList()))
            }
            if (perDayOn) {
                java.time.DayOfWeek.values().forEach { dow ->
                    val idx = dow.value - 1
                    val cur = s.capacityByDayMin.getOrElse(idx) { s.dailyCapacityMin }
                    DurationSettingRow(dow.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()), cur) { m ->
                        vm.saveSettings(s.copy(capacityByDayMin = s.capacityByDayMin.toMutableList().also { it[idx] = m.coerceIn(0, 24 * 60) }))
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

            Spacer(Modifier.height(6.dp)); Sub("Working hours (auto-schedule)")
            TimeSettingRow("Start", s.workStartHour * 60) { m -> vm.saveSettings(s.copy(workStartHour = ((m + 30) / 60).coerceIn(0, 23))) }
            TimeSettingRow("End", (s.workEndHour % 24) * 60) { m ->
                val h = ((m + 30) / 60).let { if (it == 0) 24 else it }
                vm.saveSettings(s.copy(workEndHour = h.coerceIn(s.workStartHour + 1, 24)))
            }

            Spacer(Modifier.height(6.dp)); Sub("Second time-zone (calendar rail)")
            NavRow("Second time zone", s.secondaryZoneId.ifBlank { "Off" }, { showSecondaryZone = true },
                subtitle = "Shown beside event times in the editor")

            Spacer(Modifier.height(6.dp)); Sub("Deep-work goal")
            DurationSettingRow("Focused time I aim for daily", s.deepWorkGoalMin,
                subtitle = "Powers the Focus coach's progress and streak") { m ->
                vm.saveSettings(s.copy(deepWorkGoalMin = m.coerceIn(15, 600)))
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
                    Text("${w.name} · ${fmtHm(w.startMin)}–${fmtHm(w.endMin)}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { vm.deleteProtectedWindow(w.id) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, "Remove", Modifier.size(18.dp)) }
                }
            }
            var pwName by remember { mutableStateOf("") }
            var pwStartMin by remember { mutableIntStateOf(19 * 60) }
            var pwEndMin by remember { mutableIntStateOf(20 * 60) }
            OutlinedTextField(pwName, { pwName = it }, singleLine = true, placeholder = { Text("Window name") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            TimeSettingRow("From", pwStartMin) { pwStartMin = it }
            TimeSettingRow("To", pwEndMin) { pwEndMin = it.coerceAtLeast(pwStartMin + 15) }
            TextButton(onClick = { if (pwName.isNotBlank()) { vm.saveProtectedWindow(pwName, pwStartMin, pwEndMin, emptyList()); pwName = "" } }, enabled = pwName.isNotBlank()) { Text("Add protected window") }

            // R59 (Wave 4) — local holiday packs: add a region's public holidays as all-day events, offline.
            Spacer(Modifier.height(10.dp)); Sub("Holiday packs")
            Text("Add a region's public holidays to your calendar — generated on-device, no network.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            var holPack by remember { mutableStateOf("us") }
            val holPacks = com.todocompanion.app.domain.calendar.Holidays.PACKS
            OptionChips(holPacks, holPacks.firstOrNull { it.id == holPack }, { holPack = it.id }, spacing = 6) { "${it.emoji} ${it.name}" }
            val holYear = remember { java.time.LocalDate.now().year }
            TextButton(onClick = { vm.importHolidayPack(holPack, holYear, holYear + 1) }) { Text("＋ Import $holYear–${holYear + 1}") }

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
                Text("When a task with a start time (not an all-day task) reaches that time later today, a notification offers a one-tap “Start tracking”. Give a task a due date with a time to see it. Needs notifications + exact-alarm permission.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Toggle("Account for my whole day", s.timelineFill) { on -> vm.saveSettings(s.copy(timelineFill = on)) }
                Text("Timeline-fill mode: gaps between tracked blocks become tappable “what were you doing?” chips on the Time screen, so every part of the day is accounted for.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Toggle("Allow overlapping timers", s.multiTimer) { on -> vm.saveSettings(s.copy(multiTimer = on)) }
                Text("Multi-timer: run more than one activity at once instead of switching. Off keeps the simple single-timer.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Toggle("Reveal untracked time on the calendar", s.untrackedReveal) { on -> vm.saveSettings(s.copy(untrackedReveal = on)) }
                Text("Shade the day-column gaps between tracked intervals so uncounted time is visible.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (Modules.isEnabled(s, Modules.HABITS)) {
            SettingsSectionHeader("Habits")
            SettingsGroup(Icons.Filled.Whatshot, "Habits", open["streaks"] == true, { open["streaks"] = open["streaks"] != true }, keywords = "streak forgiving strength calm chronotype bookends companion garden wip limit rewards points routines nfc receptivity") {
                Toggle("Forgiving streaks", s.forgivingStreaks) { on -> vm.saveSettings(s.copy(forgivingStreaks = on)) }
                Text("Tolerate the odd missed day (about one a week) instead of resetting to zero — consistency over brittle chains, so one slip never wipes weeks of momentum.",
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
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0..10).forEach { n ->
                        FilterChip(selected = s.taskWipLimit == n, onClick = { vm.setTaskWipLimit(n) }, label = { Text(if (n == 0) "Off" else "$n") })
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                Toggle("Time reminders to my peak", s.receptivityTiming) { on -> vm.setReceptivityTiming(on) }
                Text("Shift the daily brief and evening review to the hour you're most likely to act, learned from when you actually finish habits and tasks. Off = use the fixed times.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                // R107 — Rewards & Routines live here now, with the rest of the habit tools, instead of as
                // standalone entries in the main settings list where they were easy to miss.
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                Sub("Rewards")
                run {
                    val rewards = com.todocompanion.app.domain.Rewards.parse(s.rewardsJson)
                    Text("Earn ⭐ points by keeping habits and finishing tasks — currently ${s.pointsBalance}. Spend them on treats you set yourself.",
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
                    Spacer(Modifier.height(6.dp))
                    Text("Your reward menu — real treats you grant yourself at milestones (points-free, self-chosen).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                if (Modules.isEnabled(s, Modules.TIME)) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    Sub("Routines")
                    val activities by vm.timeActivities.collectAsState()
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
        }

        SettingsGroup(Icons.Filled.Schedule, "Review & reflection", open["review"] == true, { open["review"] = open["review"] != true }, keywords = "review reflection gratitude three good things why streak hide density consistency cadence weekly close the day") {
            // Track 2.7 — evidence-led cadence corrections.
            Toggle("Gratitude is a weekly beat", s.gratitudeWeekly) { on -> vm.saveSettings(s.copy(gratitudeWeekly = on)) }
            Text("Surface the gratitude / “three good things” prompt once a week (on your week-start day) instead of every night — savouring works better when it isn't a daily chore.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Toggle("Ask “…and why” for good things", s.requireGoodThingWhy) { on -> vm.saveSettings(s.copy(requireGoodThingWhy = on)) }
            Text("The “three good things” entries prompt for a short reason — naming why a good thing was good makes the practice stick.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Toggle("Hide streak counters", s.hideStreaks) { on -> vm.saveSettings(s.copy(hideStreaks = on)) }
            Text("Hide the streak flame across the Day Review and The Record, leading with the density strip and consistency instead — recovery over an unbroken chain (never miss twice).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SettingsSectionHeader("Editor & notifications")
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

        SettingsGroup(Icons.Filled.Notifications, "Sounds", open["sounds"] == true, { open["sounds"] = open["sounds"] != true }, keywords = "sound tone chime beep alarm focus timer stopwatch reminder ringtone audio start completion cue") {
            val sndCtx = androidx.compose.ui.platform.LocalContext.current
            var pickingFor by remember { mutableStateOf<String?>(null) }
            val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
                if (res.resultCode == android.app.Activity.RESULT_OK) {
                    val uri = res.data?.let { androidx.core.content.IntentCompat.getParcelableExtra(it, android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI, android.net.Uri::class.java) }
                    val spec = uri?.toString() ?: (if (pickingFor == "reminder") "silent" else "none")
                    when (pickingFor) {
                        "focusStart" -> vm.saveSettings(s.copy(focusStartSound = spec))
                        "focusDone" -> vm.saveSettings(s.copy(focusDoneSound = spec))
                        "reminder" -> vm.saveSettings(s.copy(reminderSound = spec))
                    }
                }
                pickingFor = null
            }
            fun launchPicker(field: String, current: String, title: String) {
                pickingFor = field
                val intent = android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION)
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, title)
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    if (com.todocompanion.app.util.Sounds.isUri(current)) putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, runCatching { android.net.Uri.parse(current) }.getOrNull())
                }
                runCatching { ringtonePicker.launch(intent) }
            }
            // The chip's own selected styling already signals "set", so no raw "✓" glyph is needed here.
            fun customLabel(spec: String) = if (com.todocompanion.app.util.Sounds.isUri(spec)) "Custom" else "Custom…"

            Text("Tap a tone to hear it. “Custom” opens your phone's sound picker.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))

            // R107 — moved here from Appearance: completing a task can play a short cue.
            Toggle("Play a sound when I complete a task", s.completionSound) { vm.saveSettings(s.copy(completionSound = it)) }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))

            Sub("Focus & timer — start")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.todocompanion.app.util.Sounds.PRESETS.forEach { p ->
                    FilterChip(selected = s.focusStartSound == p, onClick = { vm.saveSettings(s.copy(focusStartSound = p)); com.todocompanion.app.util.Sounds.play(sndCtx, p) }, label = { Text(com.todocompanion.app.util.Sounds.label(p)) })
                }
                FilterChip(selected = com.todocompanion.app.util.Sounds.isUri(s.focusStartSound), onClick = { launchPicker("focusStart", s.focusStartSound, "Focus start sound") }, label = { Text(customLabel(s.focusStartSound)) })
            }

            Sub("Focus, timer & stopwatch — completion")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.todocompanion.app.util.Sounds.PRESETS.forEach { p ->
                    FilterChip(selected = s.focusDoneSound == p, onClick = { vm.saveSettings(s.copy(focusDoneSound = p)); com.todocompanion.app.util.Sounds.play(sndCtx, p) }, label = { Text(com.todocompanion.app.util.Sounds.label(p)) })
                }
                FilterChip(selected = com.todocompanion.app.util.Sounds.isUri(s.focusDoneSound), onClick = { launchPicker("focusDone", s.focusDoneSound, "Completion sound") }, label = { Text(customLabel(s.focusDoneSound)) })
            }

            Sub("Reminders")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("default" to "Default", "silent" to "Silent").forEach { (v, lbl) ->
                    FilterChip(selected = s.reminderSound == v, onClick = { vm.saveSettings(s.copy(reminderSound = v)) }, label = { Text(lbl) })
                }
                FilterChip(selected = com.todocompanion.app.util.Sounds.isUri(s.reminderSound), onClick = { launchPicker("reminder", s.reminderSound, "Reminder sound") }, label = { Text(customLabel(s.reminderSound)) })
            }
            Text("Reminder notifications use this sound. Custom lets you choose any sound on your phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
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
            val snoozeFixed = listOf(5, 10, 15)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                snoozeFixed.forEach { m ->
                    FilterChip(selected = s.defaultSnoozeMin == m, onClick = { vm.saveSettings(s.copy(defaultSnoozeMin = m)) }, label = { Text("${m}m") })
                }
                val isCustom = s.defaultSnoozeMin !in snoozeFixed
                FilterChip(selected = isCustom, onClick = { showSnoozeCustom = true },
                    label = { Text(if (isCustom) "Custom (${fmtDur(s.defaultSnoozeMin)})" else "Custom…") })
            }
            Text("Every notification's Snooze button uses this.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            // R59 (Wave 2) — quiet hours: hold overnight reminders and deliver them together in the morning.
            Toggle("Quiet hours", s.quietHoursEnabled) { vm.saveSettings(s.copy(quietHoursEnabled = it)) }
            if (s.quietHoursEnabled) {
                TimeSettingRow("From", s.quietStartHour * 60) { m -> vm.saveSettings(s.copy(quietStartHour = ((m + 30) / 60) % 24)) }
                TimeSettingRow("To", s.quietEndHour * 60) { m -> vm.saveSettings(s.copy(quietEndHour = ((m + 30) / 60) % 24)) }
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
            // W8: per-list/folder mute — a searchable multi-select (R108), like the "Move to" surface.
            val lists by vm.lists.collectAsState()
            val muteFolders by vm.folders.collectAsState()
            if (lists.any { !it.archived } || muteFolders.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                val activeLists = lists.filter { !it.archived }
                val mutedCount = s.mutedFolders.count { id -> muteFolders.any { it.id == id } } +
                    s.mutedLists.count { id -> activeLists.any { it.id == id } }
                NavRow(
                    "Mute reminders",
                    if (mutedCount == 0) "None" else "$mutedCount muted",
                    { showMute = true },
                    subtitle = "Silence chosen lists & folders",
                )
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
                TimeSettingRow("Brief time", s.morningBriefHour * 60) { m -> vm.setMorningBrief(true, ((m + 30) / 60).coerceIn(0, 23)) }
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
                TimeSettingRow("Reflection time", s.occasionNudgeHour * 60) { m -> vm.saveSettings(s.copy(occasionNudgeHour = ((m + 30) / 60).coerceIn(0, 23))); vm.applyOccasionNudge() }
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

        SettingsSectionHeader("Privacy & data")
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
            Toggle("Redact notes from shared exports", s.exportRedactNotes) { vm.saveSettings(s.copy(exportRedactNotes = it)) }
            Text("Leaves task notes out of the Markdown, CSV and calendar (.ics) exports you share — titles, dates and tags still export. The full JSON backup is unaffected.",
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
            ).forEach {
                // The privacy guarantees read as a modern check-list, not raw "✓" glyphs.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                    MiniCheck()
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("On this device", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            vm.deviceInventory().forEach { (label, n) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("%,d".format(n), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
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
            // R107 — the same "what's on this device" inventory shown in Privacy › Trust, so the two panels
            // always agree, plus the on-disk footprint and the measurable optimise.
            var dbBytes by remember { mutableLongStateOf(vm.databaseSizeBytes()) }
            fun humanBytes(b: Long): String = when {
                b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
                b >= 1024 -> "%.0f KB".format(b / 1024.0)
                else -> "$b B"
            }
            Text("On this device", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
            vm.deviceInventory().forEach { (label, n) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("%,d".format(n), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Text("Database file: ${humanBytes(dbBytes)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
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

        SettingsSectionHeader("About")
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
    if (showThemePack) ChoicePickerDialog("Theme pack", THEME_PACKS.map { it.id to it.label }, s.themePack,
        leading = { id -> ThemePackSwatch(THEME_PACKS.first { it.id == id }) }, onDismiss = { showThemePack = false }) { id ->
        val pack = THEME_PACKS.first { it.id == id }
        vm.saveSettings(s.copy(themePack = pack.id, accentArgb = pack.accent, appBackground = pack.background,
            dynamicColor = if (pack.id.isBlank()) s.dynamicColor else false, themeMode = pack.themeMode ?: s.themeMode))
        showThemePack = false
    }
    if (showBg) ChoicePickerDialog("App background", APP_BACKGROUNDS, s.appBackground,
        leading = { key -> BackgroundSwatch(key) }, onDismiss = { showBg = false }) { key ->
        vm.saveSettings(s.copy(appBackground = key)); showBg = false
    }
    if (showWeekStart) ChoicePickerDialog("Week starts on", WEEK_STARTS, s.weekStart, onDismiss = { showWeekStart = false }) { v ->
        vm.saveSettings(s.copy(weekStart = v)); showWeekStart = false
    }
    if (showSecondaryZone) ZonePickerDialog(current = s.secondaryZoneId, onDismiss = { showSecondaryZone = false },
        title = "Second time zone", blankLabel = "Off") { z -> vm.setSecondaryZone(z); showSecondaryZone = false }
    if (showSnoozeCustom) DurationPickerDialog(s.defaultSnoozeMin, onDismiss = { showSnoozeCustom = false }) { m ->
        vm.saveSettings(s.copy(defaultSnoozeMin = m.coerceIn(1, 720))); showSnoozeCustom = false
    }
    if (showMute) MuteTargetsDialog(
        folders = vm.folders.collectAsState().value,
        lists = vm.lists.collectAsState().value.filter { !it.archived },
        mutedFolders = s.mutedFolders,
        mutedLists = s.mutedLists,
        onToggleFolder = { vm.toggleMutedFolder(it) },
        onToggleList = { vm.toggleMutedList(it) },
        onDismiss = { showMute = false },
    )
    if (showTime) {
        com.todocompanion.app.ui.components.TimeFieldDialog(
            initialMinuteOfDay = s.dailySummaryHour * 60 + s.dailySummaryMinute,
            onDismiss = { showTime = false },
        ) { m -> vm.saveSettings(s.copy(dailySummaryHour = m / 60, dailySummaryMinute = m % 60)); showTime = false }
    }
    if (showEveningTime) {
        com.todocompanion.app.ui.components.TimeFieldDialog(
            initialMinuteOfDay = s.eveningReviewHour * 60,
            onDismiss = { showEveningTime = false },
        ) { m -> vm.saveSettings(s.copy(eveningReviewHour = m / 60)); showEveningTime = false }
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
private fun ZonePickerDialog(current: String, onDismiss: () -> Unit, title: String = "Time zone", blankLabel: String = "Device (${ZoneId.systemDefault().id})", onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember { listOf("") + ZoneId.getAvailableZoneIds().sorted() }
    val filtered = remember(query) { all.filter { query.isBlank() || it.contains(query, ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(title) },
        text = {
            Column {
                com.todocompanion.app.ui.components.AppTextField(query, { query = it }, placeholder = { Text("Search zones…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(filtered, key = { it }) { z ->
                        val label = if (z.isBlank()) blankLabel else z
                        Text(label, Modifier.fillMaxWidth().clickable { onPick(z) }.padding(vertical = 11.dp),
                            fontWeight = if (z == current) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        },
    )
}

/** R108 — a searchable multi-select for muting reminders from chosen folders & lists, mirroring the
 *  "Move to" surface: a search field over one row per target, each a folder/list icon + name (+ parent
 *  folder as subtitle) with a checkbox. Replaces the space-hungry chip grid. */
@Composable
private fun MuteTargetsDialog(
    folders: List<com.todocompanion.app.data.entity.FolderEntity>,
    lists: List<com.todocompanion.app.data.entity.ListEntity>,
    mutedFolders: Set<String>,
    mutedLists: Set<String>,
    onToggleFolder: (String) -> Unit,
    onToggleList: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    data class MuteRow(val id: String, val name: String, val sub: String?, val isFolder: Boolean, val muted: Boolean)
    val folderById = remember(folders) { folders.associateBy { it.id } }
    val all = remember(folders, lists, mutedFolders, mutedLists) {
        folders.map { MuteRow(it.id, it.name, "Folder", true, it.id in mutedFolders) } +
            lists.map { l -> MuteRow(l.id, l.name, l.folderId?.let { fid -> folderById[fid]?.name } ?: "List", false, l.id in mutedLists) }
    }
    var query by remember { mutableStateOf("") }
    val filtered = all.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Mute reminders") },
        text = {
            Column {
                Text("Muting a folder silences every list inside it.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                com.todocompanion.app.ui.components.AppTextField(query, { query = it }, placeholder = { Text("Search lists & folders…") },
                    singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(filtered, key = { (if (it.isFolder) "f:" else "l:") + it.id }) { r ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .clickable { if (r.isFolder) onToggleFolder(r.id) else onToggleList(r.id) }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (r.isFolder) Icons.Filled.Folder else Icons.AutoMirrored.Filled.List, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (r.sub != null) Text(r.sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Checkbox(checked = r.muted, onCheckedChange = { if (r.isFolder) onToggleFolder(r.id) else onToggleList(r.id) })
                        }
                    }
                    if (filtered.isEmpty()) item { Text("No match", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlagEditDialog(initial: FlagEntity?, onDismiss: () -> Unit, onSave: (String, Long, String) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableLongStateOf(initial?.colorArgb ?: FLAG_COLORS.first()) }
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

// ── R107 · the consistent selection idioms — a tappable row + a focused picker, used everywhere a setting
//    is chosen, so no more sprawling chip rows or −/+ steppers. ─────────────────────────────────────────
private fun fmtHm(minuteOfDay: Int): String = "%02d:%02d".format((minuteOfDay / 60) % 24, minuteOfDay % 60)
private fun fmtDur(min: Int): String = when {
    min <= 0 -> "0m"
    min % 60 == 0 -> "${min / 60}h"
    min < 60 -> "${min}m"
    else -> "${min / 60}h ${min % 60}m"
}

/** A clean, tappable settings row: label (+ optional subtitle) on the left, current value + chevron on the
 *  right. The single idiom for "tap to choose", replacing sprawling chip rows and steppers. */
@Composable
private fun NavRow(label: String, value: String, onClick: () -> Unit, subtitle: String? = null, preview: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (preview != null) {
            preview()
            Spacer(Modifier.width(8.dp))
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** A time-of-day setting shown as a NavRow that opens the shared themed hour+minute picker. */
@Composable
private fun TimeSettingRow(label: String, minuteOfDay: Int, subtitle: String? = null, onPick: (Int) -> Unit) {
    var show by remember { mutableStateOf(false) }
    NavRow(label, fmtHm(minuteOfDay), { show = true }, subtitle)
    if (show) com.todocompanion.app.ui.components.TimeFieldDialog(minuteOfDay, onDismiss = { show = false }) { onPick(it); show = false }
}

/** A duration setting (hours + minutes) shown as a NavRow that opens the shared duration picker. */
@Composable
private fun DurationSettingRow(label: String, minutes: Int, subtitle: String? = null, onPick: (Int) -> Unit) {
    var show by remember { mutableStateOf(false) }
    NavRow(label, fmtDur(minutes), { show = true }, subtitle)
    if (show) DurationPickerDialog(minutes, onDismiss = { show = false }) { onPick(it); show = false }
}

/** A generic single-choice picker dialog — used for theme pack, app background, week start, etc.
 *  [leading] optionally draws a preview (e.g. a colour swatch) at the start of each row. */
@Composable
private fun <T> ChoicePickerDialog(
    title: String,
    options: List<Pair<T, String>>,
    current: T?,
    leading: (@Composable (T) -> Unit)? = null,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (v, l) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(v) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (leading != null) {
                            leading(v)
                            Spacer(Modifier.width(14.dp))
                        }
                        Text(l, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                            color = if (v == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        if (v == current) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
    )
}

/** The subtle whole-app background tints, kept in sync with [appBackgroundBrush] in AppRoot so the
 *  Settings swatch previews the real thing. Returns null for "none". */
private fun appBackgroundTint(name: String): Color? = when (name) {
    "warm" -> Color(0xFFF59E0B)
    "cool" -> Color(0xFF3E7BFA)
    "mint" -> Color(0xFF12A594)
    "dusk" -> Color(0xFF8B5CF6)
    "rose" -> Color(0xFFEC4899)
    else -> null
}

/** A round preview for a whole-app background option — the tint over the surface, or an empty ring for "none". */
@Composable
private fun BackgroundSwatch(key: String, size: androidx.compose.ui.unit.Dp = 30.dp) {
    val tint = appBackgroundTint(key)
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(tint?.copy(alpha = 0.30f) ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

/** A round preview for a theme pack — the pack's background tint as the field, its accent as the inner dot. */
@Composable
private fun ThemePackSwatch(pack: ThemePack, size: androidx.compose.ui.unit.Dp = 30.dp) {
    val accent = if (pack.accent == 0L) MaterialTheme.colorScheme.primary else Color(pack.accent)
    val tint = appBackgroundTint(pack.background)
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(tint?.copy(alpha = 0.28f) ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size * 0.53f).clip(CircleShape).background(accent))
    }
}

/** R107 — a small section heading that groups the collapsible category cards, so the screen reads as a few
 *  labelled sections instead of one long list. */
@Composable
private fun SettingsSectionHeader(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 2.dp))
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

/** R107 — the subtle whole-app background tints, one place so the row + picker agree. */
private val APP_BACKGROUNDS = listOf(
    "none" to "None", "warm" to "Warm", "cool" to "Cool", "mint" to "Mint", "dusk" to "Dusk", "rose" to "Rosé",
)

/** R107 — week-start options (0 = follow the system locale). */
private val WEEK_STARTS = listOf(
    0 to "System default", 1 to "Monday", 2 to "Tuesday", 3 to "Wednesday", 4 to "Thursday", 5 to "Friday", 6 to "Saturday", 7 to "Sunday",
)

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
