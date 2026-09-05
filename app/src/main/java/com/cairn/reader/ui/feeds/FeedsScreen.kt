@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.cairn.reader.ui.feeds

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.data.db.SourceEntity
import com.cairn.reader.ui.components.FeedSettingsSheet
import com.cairn.reader.ui.util.formatAgo
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun FeedsScreen(
    onBack: () -> Unit,
    onOpenWeb: (String) -> Unit,
    onTeach: (String) -> Unit = {},
    viewModel: FeedsViewModel = hiltViewModel(),
) {
    val allSources by viewModel.sources.collectAsStateWithLifecycle()
    val sources by viewModel.displayed.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val unread by viewModel.unread.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val grouped by viewModel.grouped.collectAsStateWithLifecycle()
    val failingOnly by viewModel.failingOnly.collectAsStateWithLifecycle()
    val folderFilter by viewModel.folderFilter.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val snackbar = remember { SnackbarHostState() }

    var editing by remember { mutableStateOf<SourceEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }

    val selectionActive = selection.isNotEmpty()
    BackHandler(enabled = selectionActive) { viewModel.clearSelection() }
    LaunchedEffect(Unit) { viewModel.snacks.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            if (selectionActive) {
                TopAppBar(
                    title = { Text("${selection.size} selected", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) { Icon(Icons.Outlined.Close, contentDescription = "Clear selection") }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAllVisible() }) { Icon(Icons.Outlined.DoneAll, contentDescription = "Select all") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.secondaryContainer),
                )
            } else {
                TopAppBar(
                    title = {
                        if (showSearch) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = viewModel::setQuery,
                                singleLine = true,
                                placeholder = { Text("Filter feeds") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text("Feeds · ${allSources.size}", fontWeight = FontWeight.SemiBold)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (showSearch) { showSearch = false; viewModel.setQuery("") } else onBack() }) {
                            Icon(if (showSearch) Icons.Outlined.Close else Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (showSearch) "Close search" else "Back")
                        }
                    },
                    actions = {
                        if (!showSearch) {
                            IconButton(onClick = { showSearch = true }) { Icon(Icons.Outlined.Search, contentDescription = "Filter") }
                            Box {
                                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Outlined.Sort, contentDescription = "Sort & group") }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    Text("SORT", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp))
                                    FeedSort.entries.forEach { s ->
                                        DropdownMenuItem(
                                            text = { Text(s.label, fontWeight = if (s == sort) FontWeight.SemiBold else FontWeight.Normal) },
                                            trailingIcon = { if (s == sort) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                            onClick = { viewModel.setSort(s); sortMenu = false },
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Group by folder") },
                                        trailingIcon = { if (grouped) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = { viewModel.setGrouped(!grouped); sortMenu = false },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Only failing feeds") },
                                        trailingIcon = { if (failingOnly) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = { viewModel.setFailingOnly(!failingOnly); sortMenu = false },
                                    )
                                }
                            }
                            IconButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, contentDescription = "Add feed") }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            // Category (folder) filter — a horizontal strip of chips.
            if (folders.isNotEmpty() && !selectionActive) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = folderFilter == null, onClick = { viewModel.setFolderFilter(null) }, label = { Text("All") })
                    folders.forEach { f ->
                        FilterChip(
                            selected = folderFilter == f,
                            onClick = { viewModel.setFolderFilter(if (folderFilter == f) null else f) },
                            label = { Text(f) },
                            leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
            }

            // Bulk-action bar while selecting.
            if (selectionActive) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BulkAction(Icons.AutoMirrored.Outlined.DriveFileMove, "Move") { showMove = true }
                    BulkAction(Icons.Outlined.DoneAll, "Read") { viewModel.bulkMarkRead() }
                    BulkAction(Icons.Outlined.Subject, "Full text") { viewModel.bulkSetFullText(true) }
                    BulkAction(Icons.Outlined.Notifications, "Notify") { viewModel.bulkSetNotify(true) }
                    BulkAction(Icons.Outlined.Close, "Remove") { viewModel.bulkDelete() }
                }
                HorizontalDivider()
            }

            if (allSources.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No feeds yet", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap + to add a website or feed. Cairn finds the feed — and can even follow sites that don't publish one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                val showGroups = grouped && folderFilter == null && query.isBlank() && !failingOnly
                var collapsedFolders by remember { mutableStateOf(setOf<String>()) }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                ) {
                    if (showGroups) {
                        val groups = sources.groupBy { it.folder?.takeIf { f -> f.isNotBlank() } }
                        // Loose feeds first, then each folder.
                        groups[null]?.let { loose ->
                            items(loose, key = { it.id }) { source ->
                                FeedManageRow(source, unread[source.id] ?: 0, source.id in selection, selectionActive,
                                    onClick = { if (selectionActive) viewModel.toggleSelect(source.id) else editing = source },
                                    onLongPress = { viewModel.toggleSelect(source.id) })
                            }
                        }
                        groups.filterKeys { it != null }.forEach { (folder, feeds) ->
                            val collapsed = folder in collapsedFolders
                            item(key = "hdr-$folder") {
                                FolderHeader(folder!!, feeds.size, collapsed) {
                                    collapsedFolders = if (collapsed) collapsedFolders - folder else collapsedFolders + folder
                                }
                            }
                            if (!collapsed) {
                                items(feeds, key = { it.id }) { source ->
                                    FeedManageRow(source, unread[source.id] ?: 0, source.id in selection, selectionActive,
                                        onClick = { if (selectionActive) viewModel.toggleSelect(source.id) else editing = source },
                                        onLongPress = { viewModel.toggleSelect(source.id) })
                                }
                            }
                        }
                    } else {
                        if (sources.isEmpty()) {
                            item {
                                Text(
                                    "No feeds match.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(24.dp),
                                )
                            }
                        }
                        items(sources, key = { it.id }) { source ->
                            FeedManageRow(source, unread[source.id] ?: 0, source.id in selection, selectionActive,
                                onClick = { if (selectionActive) viewModel.toggleSelect(source.id) else editing = source },
                                onLongPress = { viewModel.toggleSelect(source.id) })
                        }
                    }
                }
            }
        }
    }

    editing?.let { source ->
        FeedSettingsSheet(
            source = source,
            folders = folders,
            onRename = { viewModel.rename(source.id, it) },
            onFolder = { viewModel.setFolder(source.id, it) },
            onFullText = { viewModel.setFullText(source.id, it) },
            onNotify = { viewModel.setNotify(source.id, it) },
            onPodcast = { viewModel.setPodcast(source.id, it) },
            onFeedUrl = { viewModel.setFeedUrl(source.id, it) },
            onOpenIn = { viewModel.setOpenIn(source.id, it) },
            onMaxItems = { viewModel.setMaxItems(source.id, it) },
            onOpenSite = { source.siteUrl?.let(onOpenWeb) },
            onRemove = { viewModel.delete(source.id) },
            onDismiss = { editing = null },
        )
    }

    if (showMove) {
        MoveToFolderSheet(
            folders = folders,
            onPick = { viewModel.bulkMoveToFolder(it); showMove = false },
            onDismiss = { showMove = false },
        )
    }

    if (showAdd) {
        AddFeedSheet(
            busy = busy,
            onAdd = { viewModel.addFeed(it) },
            onGoogleNews = { viewModel.followViaGoogleNews(it) },
            onWatchPage = { viewModel.watchPage(it) },
            onTeach = { showAdd = false; onTeach(it) },
            onDismiss = { showAdd = false },
        )
    }
}

@Composable
private fun BulkAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}

@Composable
private fun FolderHeader(folder: String, count: Int, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
            contentDescription = if (collapsed) "Expand" else "Collapse",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "${folder.uppercase()} · $count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun FeedManageRow(
    source: SourceEntity,
    unread: Int,
    selected: Boolean,
    selectionActive: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val host = source.siteUrl?.toHttpUrlOrNull()?.host ?: source.feedUrl.toHttpUrlOrNull()?.host ?: source.feedUrl
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionActive) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Spacer(Modifier.width(8.dp))
        } else {
            val letter = source.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "•"
            val tint = TINTS[(source.title.hashCode() and 0x7fffffff) % TINTS.size]
            Box(Modifier.size(30.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
                Text(letter, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(source.title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (source.fullTextByDefault) { Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.Subject, contentDescription = "Full text", tint = scheme.onSurfaceVariant, modifier = Modifier.size(15.dp)) }
                if (source.notify) { Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.Notifications, contentDescription = "Notifications on", tint = scheme.onSurfaceVariant, modifier = Modifier.size(15.dp)) }
            }
            Text(host, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val failing = source.consecutiveErrors > 0
            val status = when {
                failing -> "Sync failing — tap to check"
                source.lastSyncedAt != null -> formatAgo(source.lastSyncedAt)?.takeIf { it.isNotEmpty() }?.let { "Synced $it" }
                else -> null
            }
            if (failing || status != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (failing) scheme.error else scheme.primary.copy(alpha = 0.6f)))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        status ?: "Sync failing",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (failing) scheme.error else scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (unread > 0) {
            Spacer(Modifier.width(8.dp))
            Text("$unread", style = MaterialTheme.typography.labelMedium, color = scheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Pick a folder to move the selected feeds into (or type a new one, or ungroup). */
@Composable
private fun MoveToFolderSheet(folders: List<String>, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Move to folder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Row(
                Modifier.fillMaxWidth().clickable { onPick(null) }.padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(16.dp))
                Text("No folder (ungroup)", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            folders.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(f) }.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(f, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                    placeholder = { Text("New folder…") },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { if (newName.isNotBlank()) onPick(newName.trim()) }) {
                    Icon(Icons.Filled.Check, contentDescription = "Create and move")
                }
            }
        }
    }
}

@Composable
private fun AddFeedSheet(
    busy: Boolean,
    onAdd: (String) -> Unit,
    onGoogleNews: (String) -> Unit,
    onWatchPage: (String) -> Unit,
    onTeach: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text("Add a feed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Paste a website or feed URL. Cairn finds the feed — YouTube, Reddit, Substack, and more.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onAdd(text); onDismiss() }, enabled = text.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Working…" else "Add feed")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "No RSS feed? (e.g. many magazine sites)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Cairn can still follow it through a Google News search of that site — a public RSS feed of its recent articles. No account, just a fetch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onGoogleNews(text); onDismiss() }, enabled = text.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Follow via Google News")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Just want to know when a page changes?",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Cairn can watch any page — release notes, a job board, a list — and add an item whenever its content changes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onWatchPage(text); onDismiss() }, enabled = text.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Watch this page for changes")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Nothing works? Teach Cairn by example.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Open the page and tap a headline — Cairn learns the pattern and builds a feed from every matching link.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onTeach(text) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Teach a feed by tapping a headline")
            }
        }
    }
}

private val TINTS = listOf(
    Color(0xFF3F5E7A), Color(0xFF3E8E5A), Color(0xFFB98A2E),
    Color(0xFFB0553F), Color(0xFF6A5A8E), Color(0xFF2E8B94),
)
