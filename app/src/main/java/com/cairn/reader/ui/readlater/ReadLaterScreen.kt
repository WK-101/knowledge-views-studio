@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cairn.reader.ui.readlater

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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Menu
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.ui.components.CollectionPickerSheet
import com.cairn.reader.ui.components.ItemRow

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
    val scheme = MaterialTheme.colorScheme

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
                        OutlinedTextField(
                            value = query, onValueChange = viewModel::setQuery, singleLine = true,
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            placeholder = { Text("Search Read Later") }, modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(if (items.isEmpty()) "Read Later" else "Read Later · ${items.size}", fontWeight = FontWeight.SemiBold)
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
                                ReadLaterSort.entries.forEach { s ->
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
                        IconButton(onClick = { showSave = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Save a link")
                        }
                        IconButton(onClick = { showHelp = true }) {
                            Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "How to save newsletters & pages")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface),
            )
        Column(Modifier.fillMaxSize()) {
            // Advanced filter chips: type + unread + offline.
            if (availableTypes.size >= 2 || filtersActive) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = typeFilter == null && !unreadOnly && !offlineOnly, onClick = {
                        viewModel.setTypeFilter(null); viewModel.setUnreadOnly(false); viewModel.setOfflineOnly(false)
                    }, label = { Text("All") })
                    availableTypes.forEach { t ->
                        FilterChip(selected = typeFilter == t, onClick = { viewModel.setTypeFilter(if (typeFilter == t) null else t) },
                            label = { Text(t.lowercase().replaceFirstChar(Char::uppercase)) })
                    }
                    FilterChip(selected = unreadOnly, onClick = { viewModel.setUnreadOnly(!unreadOnly) }, label = { Text("Unread") })
                    FilterChip(selected = offlineOnly, onClick = { viewModel.setOfflineOnly(!offlineOnly) }, label = { Text("Offline") })
                }
            }

            if (items.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.BookmarkRemove, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(14.dp))
                    if (filtersActive) {
                        Text("No matches", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                        Spacer(Modifier.height(8.dp))
                        Text("Nothing here matches your search or filters.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    } else {
                        Text("Nothing to read later", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Save an article for later — from the reader, an item's menu, or a swipe — and it waits here. Save it to the Library to keep it for good.",
                            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = { showHelp = true }) {
                            Icon(Icons.Outlined.MailOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("How to save newsletters & pages")
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = padding.calculateBottomPadding() + 96.dp),
                ) {
                    items(items, key = { it.id }) { row ->
                        com.cairn.reader.ui.components.FeedItemCell(
                            row = row, mode = viewMode,
                            onOpen = { onOpenItem(row.id) },
                            onLongPress = { actionRow = row },
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
                ActionRow(Icons.AutoMirrored.Outlined.LibraryBooks, "Save to Library…") { moveRow = row; actionRow = null }
                ActionRow(Icons.Outlined.Archive, "Archive") { viewModel.archive(row.id); actionRow = null }
                ActionRow(Icons.Outlined.BookmarkRemove, "Remove from Read Later") { viewModel.remove(row.id); actionRow = null }
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
            title = { Text("Save a link for later") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text("https://…") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { TextButton(onClick = { viewModel.saveLink(text); showSave = false }, enabled = text.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } },
        )
    }
}

/** Explains how anything gets into Read Later — chiefly the system Share sheet, which captures
 *  newsletters, web pages, and selected text without accounts or an inbox connection. */
@Composable
private fun CaptureHelpSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text("Save anything to Read Later", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Cairn is offline-first and account-free, so the quickest way in is your phone's Share sheet — pick “Save to Cairn” from any app.",
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
            Text(
                "Everything stays on your device — nothing is uploaded, and Cairn never connects to your inbox.",
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

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
