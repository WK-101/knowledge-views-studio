@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The Offline surface: a dedicated list of everything readable without a network — explicit
 * archival "Save offline" copies (badged) and articles auto-cached when opened. Each item can have
 * just its download removed (keeping the entry) or the whole entry deleted. The storage & sync
 * policy that used to live here moves into a settings sheet reachable from the top bar.
 */
@Composable
fun OfflineScreen(
    padding: PaddingValues,
    onOpenItem: (String) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    viewModel: OfflineViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val storage by viewModel.storageBytes.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    var actionRow by remember { mutableStateOf<com.cairn.reader.data.db.ItemListRow?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<com.cairn.reader.data.db.ItemListRow?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (items.isEmpty()) "Offline" else "Offline · ${items.size}", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, contentDescription = "Open navigation") }
            },
            actions = {
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Outlined.Tune, contentDescription = "Storage & sync settings")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface),
        )
        Text(
            text = when {
                storage < 0 -> "Measuring storage…"
                else -> "${items.size} article${if (items.size == 1) "" else "s"} readable offline · ${formatBytes(storage)} on this device"
            },
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.OfflinePin, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(14.dp))
                Text("Nothing saved offline yet", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Open an article to cache it for offline reading, or choose “Save offline” from any item's menu to keep a permanent, self-contained copy — text and images — that survives even if the source changes or deletes it.",
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 2.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            ) {
                items(items, key = { it.id }) { row ->
                    com.cairn.reader.ui.components.FeedItemCell(
                        row = row,
                        mode = com.cairn.reader.data.prefs.ListViewMode.LIST,
                        onOpen = { onOpenItem(row.id) },
                        onLongPress = { actionRow = row },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.6.dp,
                        color = scheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }

    actionRow?.let { row ->
        val permanent = row.cacheStatus == "PERMANENT"
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { actionRow = null }, sheetState = androidx.compose.material3.rememberModalBottomSheetState()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
                Text(
                    if (permanent) "Saved offline (permanent copy)" else "Cached from reading — tap “Save offline” to keep it permanently",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                OfflineAction(Icons.AutoMirrored.Outlined.Article, "Open") { onOpenItem(row.id); actionRow = null }
                if (!permanent) {
                    OfflineAction(Icons.Outlined.OfflinePin, "Save offline (permanent)") { viewModel.makePermanent(row.id); actionRow = null }
                }
                OfflineAction(Icons.Outlined.CloudOff, "Remove download (keep entry)") { viewModel.removeCache(row.id); actionRow = null }
                OfflineAction(Icons.Outlined.DeleteOutline, "Delete entry", destructive = true) { confirmDelete = row; actionRow = null }
            }
        }
    }

    confirmDelete?.let { row ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = scheme.error) },
            title = { Text("Delete entry?") },
            text = { Text("“${row.title.take(60)}” moves to the Trash, along with its offline copy. You can restore it from Trash.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { viewModel.deleteEntry(row.id); confirmDelete = null }) { Text("Delete", color = scheme.error) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    if (showSettings) {
        StorageSettingsSheet(padding = padding, onDismiss = { showSettings = false })
    }
}

@Composable
private fun OfflineAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/** The storage & sync policy, now a bottom sheet reachable from the Offline surface's top bar. */
@Composable
private fun StorageSettingsSheet(
    padding: PaddingValues,
    onDismiss: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            Text("Storage & sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp))
            SectionHeader("SYNCING")
            ToggleRow(
                title = "Sync on Wi-Fi only",
                subtitle = "Automatic background refresh waits for an un-metered network. Pull-to-refresh always works.",
                checked = prefs.syncWifiOnly,
                onCheckedChange = viewModel::setSyncWifiOnly,
            )
            SectionHeader("OFFLINE COPIES")
            ToggleRow(
                title = "Keep what you read",
                subtitle = "Every article you open is cached so it stays readable offline later — text always, images per the settings below.",
                checked = prefs.cacheOnOpen,
                onCheckedChange = viewModel::setCacheOnOpen,
            )
            ToggleRow(
                title = "Download images",
                subtitle = "“Save offline” fetches every image so the article is a true self-contained copy. Off = text only.",
                checked = prefs.cacheImagesOffline,
                onCheckedChange = viewModel::setCacheImagesOffline,
            )
            ToggleRow(
                title = "Images on Wi-Fi only",
                subtitle = "On a metered network, saving offline keeps the text and skips images until you're on Wi-Fi.",
                checked = prefs.imagesWifiOnly,
                enabled = prefs.cacheImagesOffline,
                onCheckedChange = viewModel::setImagesWifiOnly,
            )
            SectionHeader("RETENTION")
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text("Keep per feed", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(
                    "“All” (the default) keeps every item forever. Pick a number to cap each feed; older items you haven't starred, saved, archived, filed, highlighted, or saved offline are pruned as new ones arrive.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    KeepOptions.forEach { n ->
                        FilterChip(selected = prefs.maxItemsPerFeed == n, onClick = { viewModel.setMaxItemsPerFeed(n) }, label = { Text(if (n == 0) "All" else n.toString()) })
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Delete older than", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text("Also drop un-engaged items past this age on sync. Kept items are never deleted.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    AgeOptions.forEach { d ->
                        FilterChip(selected = prefs.maxAgeDays == d, onClick = { viewModel.setMaxAgeDays(d) }, label = { Text(ageLabel(d)) })
                    }
                }
            }
            ToggleRow(
                title = "Never delete unread",
                subtitle = "Retention only removes articles you've already read. Unread ones stay until you read them.",
                checked = prefs.keepUnread,
                onCheckedChange = viewModel::setKeepUnread,
            )
        }
    }
}

private val KeepOptions = listOf(0, 25, 50, 100, 200, 500, 1000)
private val AgeOptions = listOf(0, 3, 7, 14, 30, 90, 180, 365)

private fun ageLabel(d: Int): String = when (d) {
    0 -> "Forever"
    7 -> "1 week"
    14 -> "2 weeks"
    30 -> "1 month"
    90 -> "3 months"
    180 -> "6 months"
    365 -> "1 year"
    else -> "$d days"
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

@Composable
private fun SectionHeader(text: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.4f),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
