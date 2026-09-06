@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cairn.reader.ui.settings

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    val picked by viewModel.picked.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val typeFilter by viewModel.typeFilter.collectAsStateWithLifecycle()
    val kind by viewModel.kind.collectAsStateWithLifecycle()
    val groupBySource by viewModel.groupBySource.collectAsStateWithLifecycle()
    val availableTypes by viewModel.availableTypes.collectAsStateWithLifecycle()
    val preparing by viewModel.preparing.collectAsStateWithLifecycle()
    val selecting = picked.isNotEmpty()
    val scheme = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var actionRow by remember { mutableStateOf<com.cairn.reader.data.db.ItemListRow?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<com.cairn.reader.data.db.ItemListRow?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val filtersActive = query.isNotBlank() || typeFilter != null || kind != OfflineKind.ALL

    androidx.activity.compose.BackHandler(enabled = selecting) { viewModel.clearPicks() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (searchOpen) {
                    com.cairn.reader.ui.components.CairnSearchField(
                        value = query, onValueChange = viewModel::setQuery,
                        placeholder = "Search offline", autofocus = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(if (totalCount == 0) "Offline" else "Offline · $totalCount", fontWeight = FontWeight.SemiBold)
                }
            },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.open_navigation)) }
            },
            actions = {
                if (searchOpen) {
                    IconButton(onClick = { viewModel.setQuery(""); searchOpen = false }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close_search))
                    }
                } else {
                    IconButton(onClick = { searchOpen = true }) { Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search)) }
                    IconButton(
                        enabled = !preparing,
                        onClick = {
                            android.widget.Toast.makeText(ctx, "Preparing offline pack…", android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.prepareOfflinePack { saved ->
                                android.widget.Toast.makeText(ctx, if (saved > 0) "Saved $saved articles for offline" else "Everything's already offline", android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                    ) {
                        if (preparing) androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.DownloadForOffline, contentDescription = stringResource(R.string.prepare_offline_pack))
                    }
                    androidx.compose.foundation.layout.Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Outlined.SwapVert, contentDescription = stringResource(R.string.sort_group)) }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            Text(stringResource(R.string.sort), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp))
                            OfflineSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label, fontWeight = if (s == sort) FontWeight.SemiBold else FontWeight.Normal) },
                                    onClick = { viewModel.setSort(s); sortMenu = false },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.group_by_source)) },
                                trailingIcon = { if (groupBySource) Icon(Icons.Filled.Check, contentDescription = null) },
                                onClick = { viewModel.setGroupBySource(!groupBySource); sortMenu = false },
                            )
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.storage_sync_settings))
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface),
        )
        // Filter chips: kind (all / permanent / cached) + item types.
        if (!selecting && totalCount > 0) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = !filtersActive, onClick = { viewModel.clearFilters() }, label = { Text(stringResource(R.string.all)) })
                FilterChip(selected = kind == OfflineKind.PERMANENT, onClick = { viewModel.setKind(if (kind == OfflineKind.PERMANENT) OfflineKind.ALL else OfflineKind.PERMANENT) }, label = { Text(stringResource(R.string.permanent)) })
                FilterChip(selected = kind == OfflineKind.CACHED, onClick = { viewModel.setKind(if (kind == OfflineKind.CACHED) OfflineKind.ALL else OfflineKind.CACHED) }, label = { Text(stringResource(R.string.cached)) })
                availableTypes.forEach { t ->
                    FilterChip(
                        selected = typeFilter == t,
                        onClick = { viewModel.setTypeFilter(if (typeFilter == t) null else t) },
                        label = { Text(t.lowercase().replaceFirstChar(Char::uppercase)) },
                    )
                }
            }
        }
        if (selecting) {
            com.cairn.reader.ui.components.SelectionActionBar(
                count = picked.size,
                onClose = { viewModel.clearPicks() },
                onSelectAll = { viewModel.pickAll() },
            ) {
                TextButton(onClick = { viewModel.makePermanentPicked() }) { Text(stringResource(R.string.save_offline)) }
                TextButton(onClick = { viewModel.removeCachePicked() }) { Text(stringResource(R.string.remove_2)) }
                TextButton(onClick = { viewModel.deleteEntriesPicked() }) { Text(stringResource(R.string.delete), color = scheme.error) }
            }
        } else {
            Text(
                text = when {
                    storage < 0 -> "Measuring storage…"
                    else -> "${items.size} article${if (items.size == 1) "" else "s"} readable offline · ${formatBytes(storage)} on this device"
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.OfflinePin, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(14.dp))
                if (filtersActive && totalCount > 0) {
                    Text(stringResource(R.string.no_matches), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.nothing_offline_matches_your_search_or), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { viewModel.clearFilters() }) { Text(stringResource(R.string.clear_filters)) }
                } else {
                    Text(stringResource(R.string.nothing_saved_offline_yet), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.open_an_article_to_cache_it),
                        style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        } else {
            val cell: @Composable (com.cairn.reader.data.db.ItemListRow) -> Unit = { row ->
                com.cairn.reader.ui.components.FeedItemCell(
                    row = row,
                    mode = com.cairn.reader.data.prefs.ListViewMode.LIST,
                    onOpen = {
                        if (selecting) viewModel.togglePick(row.id) else {
                            com.cairn.reader.ui.reader.ReaderQueue.set(items.map { it.id })
                            onOpenItem(row.id)
                        }
                    },
                    onLongPress = { if (selecting) viewModel.togglePick(row.id) else actionRow = row },
                    selected = row.id in picked,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    thickness = 0.6.dp,
                    color = scheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 2.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            ) {
                if (groupBySource) {
                    val groups = items.groupBy { it.sourceTitle ?: it.siteName ?: "Unknown" }.toSortedMap()
                    groups.forEach { (source, rows) ->
                        item(key = "hdr-$source") {
                            Text(
                                "$source · ${rows.size}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.primary,
                                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                            )
                        }
                        items(rows, key = { it.id }) { row -> cell(row) }
                    }
                } else {
                    items(items, key = { it.id }) { row -> cell(row) }
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
                OfflineAction(Icons.Outlined.Checklist, "Select") { viewModel.togglePick(row.id); actionRow = null }
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
            title = { Text(stringResource(R.string.delete_entry)) },
            text = { Text("“${row.title.take(60)}” moves to the Trash, along with its offline copy. You can restore it from Trash.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { viewModel.deleteEntry(row.id); confirmDelete = null }) { Text(stringResource(R.string.delete), color = scheme.error) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
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
            Text(stringResource(R.string.storage_sync), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp))
            SectionHeader("SYNCING")
            ToggleRow(
                title = "Sync on Wi-Fi only",
                subtitle = "Automatic background refresh waits for an un-metered network. Pull-to-refresh always works.",
                checked = prefs.syncWifiOnly,
                onCheckedChange = viewModel::setSyncWifiOnly,
            )
            ToggleRow(
                title = "Sync only while charging",
                subtitle = "Background refresh waits until the device is plugged in — easiest on the battery.",
                checked = prefs.syncChargingOnly,
                onCheckedChange = viewModel::setSyncChargingOnly,
            )
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text(stringResource(R.string.sync_every), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.how_often_cairn_refreshes_in_the), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    SyncIntervalOptions.forEach { m ->
                        FilterChip(selected = prefs.syncIntervalMinutes == m, onClick = { viewModel.setSyncIntervalMinutes(m) }, label = { Text(syncIntervalLabel(m)) })
                    }
                }
            }
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
                Text(stringResource(R.string.keep_per_feed), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.all_the_default_keeps_every_item),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    KeepOptions.forEach { n ->
                        FilterChip(selected = prefs.maxItemsPerFeed == n, onClick = { viewModel.setMaxItemsPerFeed(n) }, label = { Text(if (n == 0) "All" else n.toString()) })
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.delete_older_than), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.also_drop_un_engaged_items_past), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
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
private val SyncIntervalOptions = listOf(0, 15, 30, 60, 180, 360, 720, 1440)

private fun syncIntervalLabel(m: Int): String = when (m) {
    0 -> "Default"
    15 -> "15 min"
    30 -> "30 min"
    60 -> "1 hour"
    180 -> "3 hours"
    360 -> "6 hours"
    720 -> "12 hours"
    1440 -> "Daily"
    else -> "$m min"
}

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
