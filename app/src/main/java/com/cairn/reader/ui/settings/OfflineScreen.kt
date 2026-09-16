@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cairn.reader.ui.settings

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
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
import com.cairn.reader.data.db.CacheStatus
import com.cairn.reader.data.prefs.SwipeAction
import com.cairn.reader.ui.components.EmptyState
import com.cairn.reader.ui.components.EntryDivider
import com.cairn.reader.ui.components.FilterChipRow
import com.cairn.reader.ui.components.SectionLabel
import com.cairn.reader.ui.components.SectionLabelVariant
import com.cairn.reader.ui.components.SheetActionRow
import com.cairn.reader.ui.components.SheetHeader
import com.cairn.reader.ui.components.SwipeableItemRow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

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
    val swipeCfg by viewModel.swipeActions.collectAsStateWithLifecycle()
    val selecting = picked.isNotEmpty()
    val scheme = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // A swipe that needs UI context (share / open original) is handled here; the rest are pure data
    // changes the ViewModel owns. Mirrors the Inbox's swipe host.
    fun onOfflineSwipe(row: com.cairn.reader.data.db.ItemListRow, action: SwipeAction) {
        when (action) {
            SwipeAction.OPEN_ORIGINAL -> runCatching {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(row.url)))
            }
            SwipeAction.SHARE -> com.cairn.reader.util.shareText(ctx, row.url, subject = row.title, chooser = null)
            else -> viewModel.swipe(row, action)
        }
    }

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
                        placeholder = stringResource(R.string.search_offline), autofocus = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(if (totalCount == 0) stringResource(R.string.offline) else stringResource(R.string.offline_count, totalCount), fontWeight = FontWeight.SemiBold)
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
                            android.widget.Toast.makeText(ctx, ctx.getString(R.string.preparing_offline_pack), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.prepareOfflinePack { saved ->
                                val msg = if (saved > 0) ctx.resources.getQuantityString(R.plurals.saved_articles_offline, saved, saved) else ctx.getString(R.string.everything_already_offline)
                                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                    ) {
                        if (preparing) androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.DownloadForOffline, contentDescription = stringResource(R.string.prepare_offline_pack))
                    }
                    androidx.compose.foundation.layout.Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Outlined.SwapVert, contentDescription = stringResource(R.string.sort_group)) }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            SectionLabel(stringResource(R.string.sort), SectionLabelVariant.Menu)
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
            FilterChipRow {
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
                    storage < 0 -> stringResource(R.string.measuring_storage)
                    else -> pluralStringResource(R.plurals.offline_readable_count, items.size, items.size, com.cairn.reader.util.formatBytes(ctx, storage))
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (items.isEmpty()) {
            if (filtersActive && totalCount > 0) {
                EmptyState(
                    title = stringResource(R.string.no_matches),
                    body = stringResource(R.string.nothing_offline_matches_your_search_or),
                    icon = Icons.Outlined.OfflinePin,
                    action = { TextButton(onClick = { viewModel.clearFilters() }) { Text(stringResource(R.string.clear_filters)) } },
                )
            } else {
                EmptyState(
                    title = stringResource(R.string.nothing_saved_offline_yet),
                    body = stringResource(R.string.open_an_article_to_cache_it),
                    icon = Icons.Outlined.OfflinePin,
                )
            }
        } else {
            val cell: @Composable (com.cairn.reader.data.db.ItemListRow) -> Unit = { row ->
                SwipeableItemRow(
                    row = row,
                    onOpen = {
                        if (selecting) viewModel.togglePick(row.id) else {
                            com.cairn.reader.ui.reader.ReaderQueue.set(items.map { it.id })
                            onOpenItem(row.id)
                        }
                    },
                    onLongPress = { if (selecting) viewModel.togglePick(row.id) else actionRow = row },
                    selected = row.id in picked,
                    swipeEnabled = !selecting,
                    rightHalf = swipeCfg.rightHalf,
                    rightFull = swipeCfg.rightFull,
                    leftHalf = swipeCfg.leftHalf,
                    leftFull = swipeCfg.leftFull,
                    onAction = { action -> onOfflineSwipe(row, action) },
                    mode = com.cairn.reader.data.prefs.ListViewMode.LIST,
                )
                EntryDivider()
            }
            val unknownSource = stringResource(R.string.source_unknown)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 2.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            ) {
                if (groupBySource) {
                    val groups = items.groupBy { it.sourceTitle ?: it.siteName ?: unknownSource }.toSortedMap()
                    groups.forEach { (source, rows) ->
                        item(key = "hdr-$source") {
                            SectionLabel(
                                stringResource(R.string.header_count, source, rows.size),
                                SectionLabelVariant.Group,
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
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
        val permanent = CacheStatus.isPermanent(row.cacheStatus)
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { actionRow = null }, sheetState = androidx.compose.material3.rememberModalBottomSheetState()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                SheetHeader(
                    title = row.title,
                    subtitle = if (permanent) stringResource(R.string.saved_offline_permanent_copy) else stringResource(R.string.cached_from_reading),
                )
                SheetActionRow(Icons.AutoMirrored.Outlined.Article, stringResource(R.string.open), onClick = { onOpenItem(row.id); actionRow = null })
                SheetActionRow(Icons.Outlined.Checklist, stringResource(R.string.select), onClick = { viewModel.togglePick(row.id); actionRow = null })
                if (!permanent) {
                    SheetActionRow(Icons.Outlined.OfflinePin, stringResource(R.string.save_offline_permanent), onClick = { viewModel.makePermanent(row.id); actionRow = null })
                }
                SheetActionRow(Icons.Outlined.CloudOff, stringResource(R.string.remove_download_keep_entry), onClick = { viewModel.removeCache(row.id); actionRow = null })
                SheetActionRow(Icons.Outlined.DeleteOutline, stringResource(R.string.delete_entry_2), onClick = { confirmDelete = row; actionRow = null }, destructive = true)
            }
        }
    }

    confirmDelete?.let { row ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = scheme.error) },
            title = { Text(stringResource(R.string.delete_entry)) },
            text = { Text(stringResource(R.string.delete_entry_body, row.title.take(60))) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { viewModel.deleteEntry(row.id); confirmDelete = null }) { Text(stringResource(R.string.delete), color = scheme.error) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showSettings) {
        StorageSettingsSheet(padding = padding, onDismiss = { showSettings = false })
    }
}

/**
 * The storage & sync policy, reachable as a bottom sheet from the Offline surface's top bar. It
 * renders the same shared groups that Settings → "Feeds & articles" and "Storage" use, so there is
 * a single source of truth for sync, retention and offline-copy controls.
 */
@Composable
private fun StorageSettingsSheet(
    padding: PaddingValues,
    onDismiss: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            Text(stringResource(R.string.storage_sync), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 22.dp, top = 4.dp, bottom = 4.dp))
            SyncSettingsGroup(prefs, viewModel)
            OfflineCopiesGroup(prefs, viewModel)
            RetentionSettingsGroup(prefs, viewModel)
        }
    }
}

