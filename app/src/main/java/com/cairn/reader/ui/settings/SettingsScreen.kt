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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.cairn.reader.data.prefs.ThemeMode
import com.cairn.reader.ui.components.FeedSettingsSheet

@Composable
fun SettingsScreen(
    padding: PaddingValues,
    onOpenNotebook: () -> Unit = {},
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
                Text("Cairn 1.3.0", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, fontWeight = FontWeight.SemiBold)
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
