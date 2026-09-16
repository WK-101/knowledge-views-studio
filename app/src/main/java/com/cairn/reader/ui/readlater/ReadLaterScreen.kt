@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cairn.reader.ui.readlater

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.prefs.SwipeAction
import com.cairn.reader.ui.components.CollectionPickerSheet
import com.cairn.reader.ui.components.EmptyState
import com.cairn.reader.ui.components.EntryDivider
import com.cairn.reader.ui.components.FilterChipRow
import com.cairn.reader.ui.components.SectionLabel
import com.cairn.reader.ui.components.SectionLabelVariant
import com.cairn.reader.ui.components.SheetActionRow
import com.cairn.reader.ui.components.SheetHeader
import com.cairn.reader.ui.components.SwipeableItemRow

@Composable
fun ReadLaterScreen(
    padding: PaddingValues,
    onOpenItem: (String) -> Unit,
    onOpenDrawer: () -> Unit = {},
    viewModel: ReadLaterViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val typeFilter by viewModel.typeFilter.collectAsStateWithLifecycle()
    val unreadOnly by viewModel.unreadOnly.collectAsStateWithLifecycle()
    val offlineOnly by viewModel.offlineOnly.collectAsStateWithLifecycle()
    val availableTypes by viewModel.availableTypes.collectAsStateWithLifecycle()
    val picked by viewModel.picked.collectAsStateWithLifecycle()
    val swipeCfg by viewModel.swipeActions.collectAsStateWithLifecycle()
    val selecting = picked.isNotEmpty()
    val scheme = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current

    // A swipe that needs UI context (share / open original) is handled here; the rest are pure data
    // changes the ViewModel owns. Mirrors the Inbox's swipe host.
    fun onReadLaterSwipe(row: ItemListRow, action: SwipeAction) {
        when (action) {
            SwipeAction.OPEN_ORIGINAL -> runCatching {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(row.url)))
            }
            SwipeAction.SHARE -> com.cairn.reader.util.shareText(context, row.url, subject = row.title, chooser = null)
            else -> viewModel.swipe(row, action)
        }
    }

    androidx.activity.compose.BackHandler(enabled = selecting) { viewModel.clearPicks() }

    Box(Modifier.fillMaxSize()) {

    var actionRow by remember { mutableStateOf<ItemListRow?>(null) }
    var moveRow by remember { mutableStateOf<ItemListRow?>(null) }
    var showSave by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    var viewMode by remember { mutableStateOf(com.cairn.reader.data.prefs.ListViewMode.CARD) }

    val filtersActive = query.isNotBlank() || typeFilter != null || unreadOnly || offlineOnly

    Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    if (searchOpen) {
                        com.cairn.reader.ui.components.CairnSearchField(
                            value = query, onValueChange = viewModel::setQuery,
                            placeholder = "Search Read Later", autofocus = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(if (items.isEmpty()) "Read Later" else "Read Later · ${items.size}", fontWeight = FontWeight.SemiBold)
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
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.Outlined.SwapVert, contentDescription = stringResource(R.string.sort_2)) }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SectionLabel(stringResource(R.string.sort), SectionLabelVariant.Menu)
                                ReadLaterSort.entries.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(s.label, fontWeight = if (s == sort) FontWeight.SemiBold else FontWeight.Normal) },
                                        onClick = { viewModel.setSort(s); sortMenu = false },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { viewMenu = true }) { Icon(Icons.Outlined.ViewAgenda, contentDescription = stringResource(R.string.view_2)) }
                            DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                                com.cairn.reader.data.prefs.ListViewMode.entries.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m.name.lowercase().replaceFirstChar(Char::uppercase), fontWeight = if (m == viewMode) FontWeight.SemiBold else FontWeight.Normal) },
                                        onClick = { viewMode = m; viewMenu = false },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { showHelp = true }) {
                            Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = stringResource(R.string.how_to_save_newsletters_pages))
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
                    IconButton(onClick = { viewModel.markPickedRead(true) }) {
                        Icon(Icons.Outlined.MarkEmailRead, contentDescription = stringResource(R.string.mark_read))
                    }
                    IconButton(onClick = { viewModel.saveToLibraryPicked() }) {
                        Icon(Icons.AutoMirrored.Outlined.LibraryBooks, contentDescription = stringResource(R.string.save_to_library))
                    }
                    IconButton(onClick = { viewModel.removePicked() }) {
                        Icon(Icons.Outlined.BookmarkRemove, contentDescription = stringResource(R.string.remove_from_read_later))
                    }
                    Box {
                        var more by remember { mutableStateOf(false) }
                        IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_actions)) }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.mark_unread)) }, onClick = { more = false; viewModel.markPickedRead(false) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.save_offline)) }, onClick = { more = false; viewModel.savePickedOffline() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.archive)) }, onClick = { more = false; viewModel.archivePicked() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.move_to_trash)) }, onClick = { more = false; viewModel.deletePicked() })
                        }
                    }
                }
            }
            // Advanced filter chips: type + unread + offline.
            if (!selecting && (availableTypes.size >= 2 || filtersActive)) {
                FilterChipRow {
                    FilterChip(selected = typeFilter == null && !unreadOnly && !offlineOnly, onClick = {
                        viewModel.setTypeFilter(null); viewModel.setUnreadOnly(false); viewModel.setOfflineOnly(false)
                    }, label = { Text(stringResource(R.string.all)) })
                    availableTypes.forEach { t ->
                        FilterChip(selected = typeFilter == t, onClick = { viewModel.setTypeFilter(if (typeFilter == t) null else t) },
                            label = { Text(t.lowercase().replaceFirstChar(Char::uppercase)) })
                    }
                    FilterChip(selected = unreadOnly, onClick = { viewModel.setUnreadOnly(!unreadOnly) }, label = { Text(stringResource(R.string.unread)) })
                    FilterChip(selected = offlineOnly, onClick = { viewModel.setOfflineOnly(!offlineOnly) }, label = { Text(stringResource(R.string.offline)) })
                }
            }

            if (items.isEmpty()) {
                if (filtersActive) {
                    EmptyState(
                        title = stringResource(R.string.no_matches),
                        body = stringResource(R.string.nothing_here_matches_your_search_or),
                        icon = Icons.Outlined.BookmarkRemove,
                    )
                } else {
                    EmptyState(
                        title = stringResource(R.string.nothing_to_read_later),
                        body = stringResource(R.string.save_an_article_for_later_from),
                        icon = Icons.Outlined.BookmarkRemove,
                        action = {
                            TextButton(onClick = { showHelp = true }) {
                                Icon(Icons.Outlined.MailOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.how_to_save_newsletters_pages))
                            }
                        },
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = padding.calculateBottomPadding() + 96.dp),
                ) {
                    items(items, key = { it.id }) { row ->
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
                            onAction = { action -> onReadLaterSwipe(row, action) },
                            mode = viewMode,
                        )
                        if (viewMode != com.cairn.reader.data.prefs.ListViewMode.MAGAZINE) {
                            EntryDivider()
                        }
                    }
                }
            }
        }
    }

    actionRow?.let { row ->
        ModalBottomSheet(onDismissRequest = { actionRow = null }, sheetState = rememberModalBottomSheetState()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                SheetHeader(row.title)
                SheetActionRow(Icons.Outlined.Checklist, "Select", onClick = { viewModel.togglePick(row.id); actionRow = null })
                SheetActionRow(Icons.AutoMirrored.Outlined.LibraryBooks, "Save to Library…", onClick = { moveRow = row; actionRow = null })
                SheetActionRow(Icons.Outlined.Archive, "Archive", onClick = { viewModel.archive(row.id); actionRow = null })
                SheetActionRow(Icons.Outlined.BookmarkRemove, "Remove from Read Later", onClick = { viewModel.remove(row.id); actionRow = null })
            }
        }
    }

    moveRow?.let { row ->
        CollectionPickerSheet(
            collections = collections,
            currentCollectionId = null,
            title = "Save “${row.title.take(40)}” to…",
            unsortedLabel = "Favorites (no collection)",
            onPick = { collectionId -> viewModel.saveToLibrary(row.id, collectionId); moveRow = null },
            onCreate = { viewModel.createCollection(it) },
            onDismiss = { moveRow = null },
        )
    }

    if (showHelp) {
        CaptureHelpSheet(onDismiss = { showHelp = false })
    }

    if (showSave) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text(stringResource(R.string.save_a_link_for_later)) },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text("https://…") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { TextButton(onClick = { viewModel.saveLink(text); showSave = false }, enabled = text.isNotBlank()) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    // Save-a-link FAB, mirroring the Inbox's add affordance (hidden during multi-select).
    if (!selecting) {
        FloatingActionButton(
            onClick = { showSave = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.save_a_link))
        }
    }
    }
}

/** Explains how anything gets into Read Later — chiefly the system Share sheet, which captures
 *  newsletters, web pages, and selected text without accounts or an inbox connection. */
@Composable
private fun CaptureHelpSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.save_anything_to_read_later), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.cairn_is_offline_first_and_account),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            HelpItem(
                Icons.Outlined.MailOutline,
                "Newsletters & emails",
                "Open the newsletter in your email app, tap Share, and choose Save to Cairn — it lands here as a clean article. If it has a “View in browser” link, sharing that link gives the best result.",
            )
            HelpItem(
                Icons.Outlined.IosShare,
                "Any web page",
                "In your browser, tap Share → Save to Cairn. The full article is extracted on-device for offline reading.",
            )
            HelpItem(
                Icons.Outlined.FormatQuote,
                "A passage or clipping",
                "Select text anywhere, tap Share → Save to Cairn, and the excerpt is kept here to read later.",
            )
            HelpItem(
                Icons.Outlined.Link,
                "A link you already have",
                "Use the + button on this screen to paste a URL directly.",
            )
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.everything_stays_on_your_device_nothing),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HelpItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

