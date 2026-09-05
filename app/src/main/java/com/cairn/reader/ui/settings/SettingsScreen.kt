@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cairn.reader.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.data.db.SourceEntity
import com.cairn.reader.data.prefs.ReaderFont
import com.cairn.reader.data.prefs.ReaderTheme
import com.cairn.reader.data.prefs.SwipeAction
import com.cairn.reader.data.prefs.ThemeMode
import com.cairn.reader.ui.components.FeedSettingsSheet

@Composable
fun SettingsScreen(
    padding: PaddingValues,
    onOpenNotebook: () -> Unit = {},
    onOpenOffline: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val highlightCount by viewModel.highlightCount.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var feedSettings by remember { mutableStateOf<SourceEntity?>(null) }
    // Sources can be a long list; keep the section folded by default so it doesn't crowd Settings.
    var sourcesExpanded by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            if (text != null) {
                viewModel.importOpml(text) { added ->
                    Toast.makeText(context, if (added > 0) "Imported $added feeds" else "No new feeds found", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            Toast.makeText(context, "Restoring…", Toast.LENGTH_SHORT).show()
            // Auto-detects a .zip full archive (data + offline copies) vs a .json data backup.
            viewModel.importFrom(uri) { summary -> Toast.makeText(context, summary, Toast.LENGTH_LONG).show() }
        }
    }

    val archiveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            Toast.makeText(context, "Writing archive…", Toast.LENGTH_SHORT).show()
            viewModel.exportArchive(uri) { ok ->
                Toast.makeText(context, if (ok) "Full archive saved" else "Couldn't write the archive", Toast.LENGTH_LONG).show()
            }
        }
    }

    val backupFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.setBackupFolder(uri.toString())
            Toast.makeText(context, "Auto-backup on — a copy was saved", Toast.LENGTH_SHORT).show()
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            val name = displayNameFor(context, uri) ?: "Imported PDF"
            if (bytes != null && bytes.isNotEmpty()) {
                viewModel.importPdf(name, bytes) { ok ->
                    Toast.makeText(context, if (ok) "PDF added to your library" else "Couldn't import that PDF", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 32.dp,
        ),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { sourcesExpanded = !sourcesExpanded }.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (sourcesExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (sourcesExpanded) "Collapse" else "Expand",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.height(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                SectionLabel("SOURCES · ${sources.size}")
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = viewModel::syncNow) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sync now")
                }
            }
        }
        if (sourcesExpanded) {
            items(sources, key = { it.id }) { source ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { feedSettings = source }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(source.title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = source.folder?.takeIf { it.isNotBlank() }?.let { "$it · ${source.feedUrl}" } ?: source.feedUrl
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant)
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                SectionLabel("IMPORT / EXPORT")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import OPML") }
                    OutlinedButton(onClick = {
                        viewModel.exportOpml { xml ->
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/xml"
                                putExtra(Intent.EXTRA_TITLE, "cairn-subscriptions.opml")
                                putExtra(Intent.EXTRA_SUBJECT, "Cairn subscriptions")
                                putExtra(Intent.EXTRA_TEXT, xml)
                            }
                            runCatching { context.startActivity(Intent.createChooser(send, "Export OPML")) }
                        }
                    }) { Text("Export OPML") }
                }
                Text(
                    "Bring subscriptions in from Inoreader/Feedly, or take yours out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        viewModel.exportBackup { json ->
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_TITLE, "cairn-backup.json")
                                putExtra(Intent.EXTRA_SUBJECT, "Cairn backup")
                                putExtra(Intent.EXTRA_TEXT, json)
                            }
                            runCatching { context.startActivity(Intent.createChooser(send, "Back up Cairn")) }
                        }
                    }) { Text("Back up data") }
                    OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }) { Text("Restore") }
                }
                Text(
                    "A complete JSON backup — feeds and every per-feed setting, saved items, read/star/trash state, tags, collections, highlights and all your app settings. Restore accepts either a data backup or a full archive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = {
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
                    archiveLauncher.launch("cairn-archive-$stamp.zip")
                }) {
                    Icon(Icons.Outlined.Inventory2, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Full archive (.zip)")
                }
                Text(
                    "Everything above plus every offline article copy, cached image and imported PDF — one self-contained file so nothing is lost, readable offline the moment it's restored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text("Automatic backup", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                Text(
                    if (prefs.backupFolderUri == null) "Off — pick a folder and Cairn writes a dated backup there on a schedule."
                    else "On — writing a dated ${if (prefs.backupIncludeOffline) "full archive" else "data backup"} to your chosen folder ${if (prefs.backupFrequencyHours >= 168) "weekly" else "daily"}; the last few are kept.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { backupFolderLauncher.launch(null) }) {
                        Text(if (prefs.backupFolderUri == null) "Choose folder" else "Change folder")
                    }
                    if (prefs.backupFolderUri != null) {
                        FilterChip(selected = prefs.backupFrequencyHours in 1..47, onClick = { viewModel.setBackupFrequency(24) }, label = { Text("Daily") })
                        FilterChip(selected = prefs.backupFrequencyHours >= 48, onClick = { viewModel.setBackupFrequency(168) }, label = { Text("Weekly") })
                        TextButton(onClick = { viewModel.disableBackup() }) { Text("Off") }
                    }
                }
                if (prefs.backupFolderUri != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Include offline copies", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                            Text("Scheduled backups write a full .zip archive (larger, nothing lost).", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                        Switch(checked = prefs.backupIncludeOffline, onCheckedChange = { viewModel.setBackupIncludeOffline(it) })
                    }
                }

                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { pdfLauncher.launch(arrayOf("application/pdf")) }) {
                    Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import PDF")
                }
                Text(
                    "Add a PDF to your library and read it here, page by page — fully offline. Export any article to PDF from its ⋯ menu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        item {
            Column(Modifier.padding(top = 8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenNotebook)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.FormatQuote, contentDescription = null, tint = scheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Highlights & notes", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                        Text(
                            if (highlightCount == 0) "Long-press a sentence while reading to save it" else "$highlightCount saved",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenOffline)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, tint = scheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Offline & storage", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                        Text(
                            buildString {
                                append(if (prefs.syncWifiOnly) "Sync on Wi-Fi only" else "Sync on any network")
                                append(" · ")
                                append(if (prefs.maxItemsPerFeed == 0) "keep all" else "keep ${prefs.maxItemsPerFeed}/feed")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant)
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                SectionLabel("BOTTOM BAR")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Choose which destinations appear in the bottom bar — up to six show at once. Everything stays reachable from the drawer.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                // Canonical (name -> label) for every possible tab.
                val labels = linkedMapOf(
                    "Inbox" to "Inbox", "Library" to "Library", "Discover" to "Discover",
                    "Starred" to "Starred", "ReadLater" to "Read Later", "Highlights" to "Highlights",
                    "Feeds" to "Feeds", "Search" to "Search", "Trash" to "Trash",
                    "Offline" to "Offline", "Settings" to "Settings",
                )
                val enabled = prefs.bottomTabs
                val atCap = enabled.size >= 6
                // Enabled tabs shown in the user's order (with reorder arrows); then the rest.
                val orderedEnabled = (prefs.bottomTabsOrder.filter { it in enabled } +
                    labels.keys.filter { it in enabled && it !in prefs.bottomTabsOrder })
                val disabled = labels.keys.filter { it !in enabled }
                Text("In the bar — use arrows to reorder", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                orderedEnabled.forEachIndexed { index, name ->
                    val isLastOn = enabled.size <= 1
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.width(20.dp))
                        Text(labels[name] ?: name, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.moveBottomTab(name, up = true) }, enabled = index > 0) {
                            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Move up")
                        }
                        IconButton(onClick = { viewModel.moveBottomTab(name, up = false) }, enabled = index < orderedEnabled.size - 1) {
                            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Move down")
                        }
                        androidx.compose.material3.Switch(
                            checked = true,
                            onCheckedChange = { viewModel.setBottomTab(name, false) },
                            enabled = !isLastOn,
                        )
                    }
                }
                if (disabled.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Available", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                    disabled.forEach { name ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(labels[name] ?: name, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, modifier = Modifier.weight(1f))
                            androidx.compose.material3.Switch(
                                checked = false,
                                onCheckedChange = { viewModel.setBottomTab(name, true) },
                                enabled = !atCap,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Listen (text-to-speech)", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                        Text("Read articles aloud. Off hides the Listen buttons everywhere.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(checked = prefs.ttsEnabled, onCheckedChange = { viewModel.setTtsEnabled(it) })
                }
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Strip tracking from links", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                        Text("Remove utm_*, fbclid, gclid and similar tracking parameters from links Cairn stores, opens, and shares.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(checked = prefs.stripTrackingParams, onCheckedChange = { viewModel.setStripTrackingParams(it) })
                }
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mark read on scroll", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                        Text("In the Inbox, articles are marked read automatically as they scroll up out of view.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(checked = prefs.markReadOnScroll, onCheckedChange = { viewModel.setMarkReadOnScroll(it) })
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                SectionLabel("STARTUP")
                Spacer(Modifier.height(12.dp))
                val startDests = listOf(
                    "" to "Default", "Inbox" to "Inbox", "Library" to "Library",
                    "ReadLater" to "Read Later", "Discover" to "Discover", "Feeds" to "Feeds",
                )
                LabeledChips(
                    label = "Open on launch",
                    options = startDests,
                    selected = prefs.startDestination,
                    onSelect = { viewModel.setStartDestination(it) },
                )
                Spacer(Modifier.height(12.dp))
                val startFilters = listOf(
                    "" to "Default", "UNREAD" to "Unread", "STARRED" to "Starred",
                    "SAVED" to "Saved", "ALL" to "All",
                )
                LabeledChips(
                    label = "Inbox opens to",
                    options = startFilters,
                    selected = prefs.startFilter,
                    onSelect = { viewModel.setStartFilter(it) },
                )
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                SectionLabel("APPEARANCE")
                Spacer(Modifier.height(12.dp))
                LabeledChips(
                    label = "Theme",
                    options = ThemeMode.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) },
                    selected = prefs.themeMode,
                    onSelect = viewModel::setThemeMode,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dynamic color", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                        Text("Use wallpaper colors (Android 12+)", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    Switch(checked = prefs.dynamicColor, onCheckedChange = viewModel::setDynamicColor)
                }
                Spacer(Modifier.height(14.dp))
                Text("Accent", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                Text(
                    "A colour theme for the whole app. Overrides dynamic color when set.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.cairn.reader.ui.theme.AppAccent.entries.forEach { a ->
                        val selected = prefs.appAccent == a.name
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(a.swatch)
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) scheme.onSurface else scheme.outlineVariant,
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                    )
                                    .clickable { viewModel.setAppAccent(a.name) },
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                a.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pure black (AMOLED)", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                        Text("True-black backgrounds in dark mode to save power on OLED screens", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    Switch(checked = prefs.trueBlack, onCheckedChange = viewModel::setTrueBlack)
                }
                Spacer(Modifier.height(10.dp))
                LabeledChips(
                    label = "Reading font",
                    options = ReaderFont.entries.map { it to it.label },
                    selected = prefs.readerFont,
                    onSelect = viewModel::setReaderFont,
                )
                Spacer(Modifier.height(10.dp))
                LabeledChips(
                    label = "Reader theme",
                    options = listOf(
                        ReaderTheme.DEFAULT to "Default", ReaderTheme.PAPER to "Paper", ReaderTheme.SEPIA to "Sepia",
                        ReaderTheme.GRAY to "Gray", ReaderTheme.NIGHT to "Night", ReaderTheme.BLACK to "Black",
                    ),
                    selected = prefs.readerTheme,
                    onSelect = viewModel::setReaderTheme,
                )
                Spacer(Modifier.height(10.dp))
                LabeledChips(
                    label = "Text size",
                    options = listOf(0.8f to "Small", 0.9f to "Cozy", 1.0f to "Default", 1.2f to "Large", 1.5f to "Larger", 2.0f to "Huge"),
                    selected = prefs.readerFontScale,
                    onSelect = viewModel::setReaderFontScale,
                )
                Text(
                    "You can also pinch to size text while reading.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show images", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                        Text("Off gives a text-only, data-light read", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    Switch(checked = prefs.readerShowImages, onCheckedChange = viewModel::setReaderShowImages)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Immersive scroll", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                        Text("Hide the bars as you read; scroll up to bring them back", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    Switch(checked = prefs.readerImmersive, onCheckedChange = viewModel::setReaderImmersive)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Full screen", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                        Text("Use the entire display for text", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    Switch(checked = prefs.readerFullScreen, onCheckedChange = viewModel::setReaderFullScreen)
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                SectionLabel("GESTURES & LIST")
                Spacer(Modifier.height(12.dp))
                LabeledChips(
                    label = "List density",
                    options = listOf(false to "Comfortable", true to "Compact"),
                    selected = prefs.compactDensity,
                    onSelect = viewModel::setCompactDensity,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Swipe an entry a little for the half action, or all the way for the full action.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LabeledChips(
                    label = "Swipe right · half",
                    options = SwipeAction.entries.map { it to it.label },
                    selected = prefs.swipeRightHalf,
                    onSelect = viewModel::setSwipeRightHalf,
                )
                Spacer(Modifier.height(10.dp))
                LabeledChips(
                    label = "Swipe right · full",
                    options = SwipeAction.entries.map { it to it.label },
                    selected = prefs.swipeRightFull,
                    onSelect = viewModel::setSwipeRightFull,
                )
                Spacer(Modifier.height(10.dp))
                LabeledChips(
                    label = "Swipe left · half",
                    options = SwipeAction.entries.map { it to it.label },
                    selected = prefs.swipeLeftHalf,
                    onSelect = viewModel::setSwipeLeftHalf,
                )
                Spacer(Modifier.height(10.dp))
                LabeledChips(
                    label = "Swipe left · full",
                    options = SwipeAction.entries.map { it to it.label },
                    selected = prefs.swipeLeftFull,
                    onSelect = viewModel::setSwipeLeftFull,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Justify reader text", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                        Text("Straighten the right edge of articles", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    Switch(checked = prefs.readerJustify, onCheckedChange = viewModel::setReaderJustify)
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                SectionLabel("FILTERS")
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide duplicates", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                        Text("Collapse the same story across feeds", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    Switch(checked = prefs.hideDuplicates, onCheckedChange = viewModel::setHideDuplicates)
                }
                Spacer(Modifier.height(12.dp))
                Text("Muted keywords", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                Text("Hide inbox articles whose title or summary contains these", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                if (prefs.blockedKeywords.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        prefs.blockedKeywords.sorted().forEach { term ->
                            androidx.compose.material3.InputChip(
                                selected = true,
                                onClick = { viewModel.removeBlockedKeyword(term) },
                                label = { Text(term) },
                                trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.height(16.dp)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                var newTerm by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = newTerm,
                        onValueChange = { newTerm = it },
                        singleLine = true,
                        placeholder = { Text("Add a keyword to mute") },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { if (newTerm.isNotBlank()) { viewModel.addBlockedKeyword(newTerm); newTerm = "" } }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Mute keyword")
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                SectionLabel("PRIVACY")
                Spacer(Modifier.height(8.dp))
                Text(
                    "No account. No trackers. No ads. Everything you save is stored on this device and readable offline. Cairn only connects to the feeds and pages you add.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                SectionLabel("ABOUT")
                Spacer(Modifier.height(8.dp))
                Text("Cairn 3.43.0", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text("One reader for everything you read.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
    }

    feedSettings?.let { source ->
        FeedSettingsSheet(
            source = source,
            folders = folders,
            onFolder = { viewModel.setFolder(source.id, it) },
            onFullText = { viewModel.setFullText(source.id, it) },
            onNotify = { viewModel.setNotify(source.id, it) },
            onRemove = { viewModel.removeSource(source.id) },
            onDismiss = { feedSettings = null },
        )
    }
}

@Composable
private fun <T> LabeledChips(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(6.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        options.forEach { (value, text) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(text) },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** Best-effort human-readable name for a picked document (falls back to the last path segment). */
private fun displayNameFor(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
    } ?: uri.lastPathSegment
}.getOrNull()
