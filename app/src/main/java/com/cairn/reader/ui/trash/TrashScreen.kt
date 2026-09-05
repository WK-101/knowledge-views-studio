@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cairn.reader.ui.trash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.ViewAgenda
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.data.db.ItemListRow

@Composable
fun TrashScreen(
    padding: PaddingValues,
    onOpenItem: (String) -> Unit,
    onOpenDrawer: () -> Unit = {},
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val typeFilter by viewModel.typeFilter.collectAsStateWithLifecycle()
    val sourceFilter by viewModel.sourceFilter.collectAsStateWithLifecycle()
    val readState by viewModel.readState.collectAsStateWithLifecycle()
    val offlineOnly by viewModel.offlineOnly.collectAsStateWithLifecycle()
    val starredOnly by viewModel.starredOnly.collectAsStateWithLifecycle()
    val availableTypes by viewModel.availableTypes.collectAsStateWithLifecycle()
    val availableSources by viewModel.availableSources.collectAsStateWithLifecycle()
    val picked by viewModel.picked.collectAsStateWithLifecycle()
    val selecting = picked.isNotEmpty()
    val scheme = MaterialTheme.colorScheme

    androidx.activity.compose.BackHandler(enabled = selecting) { viewModel.clearPicks() }

    var actionRow by remember { mutableStateOf<ItemListRow?>(null) }
    var confirmForeverBulk by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var showGrace by remember { mutableStateOf(false) }
    val retentionDays by viewModel.retentionDays.collectAsStateWithLifecycle()
    var confirmForever by remember { mutableStateOf<ItemListRow?>(null) }
    var viewMode by remember { mutableStateOf(com.cairn.reader.data.prefs.ListViewMode.CARD) }

    val filtersActive = query.isNotBlank() || typeFilter != null || sourceFilter != null ||
        readState != TrashReadState.ANY || offlineOnly || starredOnly

    Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    if (searchOpen) {
                        com.cairn.reader.ui.components.CairnSearchField(
                            value = query, onValueChange = viewModel::setQuery,
                            placeholder = "Search Trash", autofocus = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(if (totalCount == 0) "Trash" else "Trash · $totalCount", fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, contentDescription = "Open navigation") }
                },
                actions = {
                    if (searchOpen) {
                        IconButton(onClick = { viewModel.setQuery(""); searchOpen = false }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close search")
                        }
                    } else {
                        IconButton(onClick = { searchOpen = true }) { Icon(Icons.Outlined.Search, contentDescription = "Search") }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.Outlined.SwapVert, contentDescription = "Sort") }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                Text("SORT", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp))
                                TrashSort.entries.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(s.label, fontWeight = if (s == sort) FontWeight.SemiBold else FontWeight.Normal) },
                                        onClick = { viewModel.setSort(s); sortMenu = false },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { viewMenu = true }) { Icon(Icons.Outlined.ViewAgenda, contentDescription = "View") }
                            DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                                com.cairn.reader.data.prefs.ListViewMode.entries.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m.name.lowercase().replaceFirstChar(Char::uppercase), fontWeight = if (m == viewMode) FontWeight.SemiBold else FontWeight.Normal) },
                                        onClick = { viewMode = m; viewMenu = false },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { overflowMenu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (filtersActive) "Restore shown (${items.size})" else "Restore all") },
                                    leadingIcon = { Icon(Icons.Outlined.RestoreFromTrash, contentDescription = null) },
                                    enabled = items.isNotEmpty(),
                                    onClick = { viewModel.restoreVisible(); overflowMenu = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("Empty Trash", color = scheme.error) },
                                    leadingIcon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = scheme.error) },
                                    enabled = totalCount > 0,
                                    onClick = { overflowMenu = false; confirmEmpty = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (retentionDays > 0) "Auto-clear: ${retentionDays}d" else "Auto-clear: off") },
                                    onClick = { overflowMenu = false; showGrace = true },
                                )
                                if (filtersActive) {
                                    DropdownMenuItem(
                                        text = { Text("Clear filters") },
                                        onClick = { viewModel.clearFilters(); overflowMenu = false },
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface),
            )
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                com.cairn.reader.ui.components.SelectionActionBar(
                    count = picked.size,
                    onClose = { viewModel.clearPicks() },
                    onSelectAll = { viewModel.pickAll() },
                ) {
                    TextButton(onClick = { viewModel.restorePicked() }) { Text("Restore") }
                    TextButton(onClick = { confirmForeverBulk = true }) { Text("Delete", color = scheme.error) }
                }
            }
            // Advanced filter chips: type + read-state + offline + starred + source.
            if (!selecting && totalCount > 0) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = !filtersActive, onClick = { viewModel.clearFilters() }, label = { Text("All") })
                    availableTypes.forEach { t ->
                        FilterChip(
                            selected = typeFilter == t,
                            onClick = { viewModel.setTypeFilter(if (typeFilter == t) null else t) },
                            label = { Text(t.lowercase().replaceFirstChar(Char::uppercase)) },
                        )
                    }
                    FilterChip(selected = readState == TrashReadState.UNREAD, onClick = {
                        viewModel.setReadState(if (readState == TrashReadState.UNREAD) TrashReadState.ANY else TrashReadState.UNREAD)
                    }, label = { Text("Unread") })
                    FilterChip(selected = readState == TrashReadState.READ, onClick = {
                        viewModel.setReadState(if (readState == TrashReadState.READ) TrashReadState.ANY else TrashReadState.READ)
                    }, label = { Text("Read") })
                    FilterChip(selected = offlineOnly, onClick = { viewModel.setOfflineOnly(!offlineOnly) }, label = { Text("Offline") })
                    FilterChip(selected = starredOnly, onClick = { viewModel.setStarredOnly(!starredOnly) }, label = { Text("Starred") })
                    if (availableSources.size >= 2) {
                        Box {
                            FilterChip(
                                selected = sourceFilter != null,
                                onClick = { sourceMenu = true },
                                leadingIcon = { Icon(Icons.Outlined.Source, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                label = { Text(sourceFilter ?: "Source") },
                            )
                            DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                                DropdownMenuItem(text = { Text("Any source") }, onClick = { viewModel.setSourceFilter(null); sourceMenu = false })
                                availableSources.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(s, fontWeight = if (s == sourceFilter) FontWeight.SemiBold else FontWeight.Normal) },
                                        onClick = { viewModel.setSourceFilter(s); sourceMenu = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (items.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(14.dp))
                    if (filtersActive && totalCount > 0) {
                        Text("No matches", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                        Spacer(Modifier.height(8.dp))
                        Text("Nothing in the Trash matches your search or filters.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { viewModel.clearFilters() }) { Text("Clear filters") }
                    } else {
                        Text("Trash is empty", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (retentionDays > 0) "Deleted articles land here — hidden from your feeds and Library but kept intact. Restore anything you want back, or empty the Trash to erase it for good. Items auto-clear after $retentionDays days."
                            else "Deleted articles land here — hidden from your feeds and Library but kept intact. Restore anything you want back, or empty the Trash to erase it for good. Auto-clear is off — items stay until you empty the Trash.",
                            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                ) {
                    items(items, key = { it.id }) { row ->
                        com.cairn.reader.ui.components.FeedItemCell(
                            row = row, mode = viewMode,
                            onOpen = { if (selecting) viewModel.togglePick(row.id) else onOpenItem(row.id) },
                            onLongPress = { if (selecting) viewModel.togglePick(row.id) else actionRow = row },
                            selected = row.id in picked,
                        )
                        if (viewMode != com.cairn.reader.data.prefs.ListViewMode.MAGAZINE) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                thickness = 0.6.dp,
                                color = scheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }

    actionRow?.let { row ->
        ModalBottomSheet(onDismissRequest = { actionRow = null }, sheetState = rememberModalBottomSheetState()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                ActionRow(Icons.Outlined.Checklist, "Select") { viewModel.togglePick(row.id); actionRow = null }
                ActionRow(Icons.Outlined.RestoreFromTrash, "Restore") { viewModel.restore(row.id); actionRow = null }
                ActionRow(Icons.Outlined.DeleteForever, "Delete forever", destructive = true) { confirmForever = row; actionRow = null }
            }
        }
    }

    if (confirmForeverBulk) {
        val n = picked.size
        AlertDialog(
            onDismissRequest = { confirmForeverBulk = false },
            icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = scheme.error) },
            title = { Text("Delete forever?") },
            text = { Text("$n item${if (n == 1) "" else "s"} will be erased permanently, along with their offline copies. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmForeverBulk = false; viewModel.deletePickedForever() }) {
                    Text("Delete forever", color = scheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmForeverBulk = false }) { Text("Cancel") } },
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = scheme.error) },
            title = { Text("Empty Trash?") },
            text = { Text("This permanently erases all $totalCount item${if (totalCount == 1) "" else "s"} in the Trash, along with their offline copies. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmEmpty = false; viewModel.emptyTrash() }) {
                    Text("Empty Trash", color = scheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
        )
    }

    confirmForever?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmForever = null },
            icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = scheme.error) },
            title = { Text("Delete forever?") },
            text = { Text("“${row.title.take(60)}” is erased permanently, along with its offline copy. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteForever(row.id); confirmForever = null }) {
                    Text("Delete forever", color = scheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmForever = null }) { Text("Cancel") } },
        )
    }

    if (showGrace) {
        val options = listOf(0, 7, 14, 30, 90, 180, 365)
        AlertDialog(
            onDismissRequest = { showGrace = false },
            title = { Text("Auto-clear Trash after") },
            text = {
                Column {
                    Text("Trashed items are permanently erased on sync once they're older than this. Kept items are never touched.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    options.forEach { d ->
                        Row(
                            Modifier.fillMaxWidth().clickable { viewModel.setRetentionDays(d); showGrace = false }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(selected = retentionDays == d, onClick = { viewModel.setRetentionDays(d); showGrace = false })
                            Spacer(Modifier.width(8.dp))
                            Text(if (d == 0) "Never (keep until emptied)" else "$d days", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showGrace = false }) { Text("Done") } },
        )
    }
}

@Composable
private fun ActionRow(
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
