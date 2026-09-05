@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cairn.reader.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.PictureAsPdf
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
            val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            if (text != null) {
                viewModel.importBackup(text) { summary -> Toast.makeText(context, summary, Toast.LENGTH_LONG).show() }
            } else {
                Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show()
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("SOURCES · ${sources.size}")
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = viewModel::syncNow) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sync now")
                }
            }
        }
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
                    }) { Text("Back up") }
                    OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }) { Text("Restore") }
                }
                Text(
                    "A full JSON backup of your feeds, saved items, tags, collections and highlights — yours to keep.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text("Automatic backup", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                Text(
                    if (prefs.backupFolderUri == null) "Off — pick a folder and Cairn writes a dated backup there on a schedule."
                    else "On — writing to your chosen folder ${if (prefs.backupFrequencyHours >= 168) "weekly" else "daily"}; the last few are kept.",
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
                    "Choose which tabs appear in the bottom bar. Everything stays reachable from the drawer.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                val tabs = listOf("Inbox", "Library", "Discover", "Settings")
                val enabled = prefs.bottomTabs
                tabs.forEach { name ->
                    val isOn = name in enabled
                    val isLastOn = isOn && enabled.size <= 1
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(
                            checked = isOn,
                            onCheckedChange = { viewModel.setBottomTab(name, it) },
                            enabled = !isLastOn,
                        )
                    }
                }
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
                    options = listOf(ReaderTheme.DEFAULT to "Default", ReaderTheme.SEPIA to "Sepia", ReaderTheme.BLACK to "Black"),
                    selected = prefs.readerTheme,
                    onSelect = viewModel::setReaderTheme,
                )
                Spacer(Modifier.height(10.dp))
                LabeledChips(
                    label = "Text size",
                    options = listOf(0.9f to "Small", 1.0f to "Default", 1.2f to "Large", 1.5f to "Larger"),
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
                Text("Cairn 3.17.0", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, fontWeight = FontWeight.SemiBold)
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
