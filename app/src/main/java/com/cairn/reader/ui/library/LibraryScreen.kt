@file:OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.cairn.reader.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cairn.reader.data.db.CollectionWithCount
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.db.LibraryCounts
import com.cairn.reader.data.db.TagWithCount
import com.cairn.reader.data.prefs.LibraryViewMode
import com.cairn.reader.ui.components.CollectionPickerSheet
import com.cairn.reader.ui.components.ItemRow

@Composable
fun LibraryScreen(
    padding: PaddingValues,
    onOpenItem: (String) -> Unit,
    onOpenHighlights: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val scope by viewModel.scope.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val savedSearches by viewModel.savedSearches.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val availableTypes by viewModel.availableTypes.collectAsStateWithLifecycle()
    val typeFilter by viewModel.typeFilter.collectAsStateWithLifecycle()

    var showCreate by remember { mutableStateOf<String?>(null) } // parentId (or "" for a top-level collection)
    var renaming by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showMove by remember { mutableStateOf(false) }
    var scopeMenu by remember { mutableStateOf(false) }
    var displayMenu by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var filterSheet by remember { mutableStateOf(false) }
    var reparenting by remember { mutableStateOf<Pair<String, String>?>(null) } // (id, name) to move under a new parent
    var renamingTag by remember { mutableStateOf<Pair<String, String>?>(null) } // (path, leaf label) to rename
    var movingTag by remember { mutableStateOf<String?>(null) } // tag path to move under a new parent

    val selectionActive = selection.isNotEmpty()
    val searching = query.isNotBlank()
    val showing = if (searching) results else items

    BackHandler(enabled = selectionActive) { viewModel.clearSelection() }
    // Inside a scope, Back steps up to the browse home rather than leaving the Library.
    BackHandler(enabled = !selectionActive && !searchOpen && scope !is LibraryScope.Home) {
        viewModel.setScope(LibraryScope.Home)
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // Library's own top app bar — the same treatment as the Inbox: a nav-drawer button,
        // the scope name (or a search field when open), and Search / Filter / View actions.
        TopAppBar(
            navigationIcon = {
                // At the browse home the nav icon opens the drawer; inside any scope it steps back home.
                if (scope is LibraryScope.Home || searchOpen) {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, contentDescription = "Open navigation") }
                } else {
                    IconButton(onClick = { viewModel.setScope(LibraryScope.Home) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Library home")
                    }
                }
            },
            title = {
                if (searchOpen) {
                    com.cairn.reader.ui.components.CairnSearchField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        placeholder = "Search your library",
                        autofocus = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailing = {
                            if (query.isNotBlank()) {
                                IconButton(onClick = { viewModel.saveSearch(query) }) {
                                    Icon(Icons.Outlined.BookmarkAdd, contentDescription = "Save search")
                                }
                            }
                        },
                    )
                } else {
                    Column {
                        Text(
                            text = scopeTitle(scope),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val scopeCount = showing.size
                        if (scopeCount > 0) {
                            Text(
                                "$scopeCount item${if (scopeCount == 1) "" else "s"}${if (typeFilter != null) " · ${typeLabel(typeFilter!!)}" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            actions = {
                if (searchOpen) {
                    IconButton(onClick = { viewModel.setQuery(""); searchOpen = false }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close search")
                    }
                } else {
                    IconButton(onClick = { searchOpen = true }) { Icon(Icons.Outlined.Search, contentDescription = "Search") }
                    IconButton(onClick = { filterSheet = true }) { Icon(Icons.Outlined.FilterList, contentDescription = "Filter & collections") }
                    Box {
                        IconButton(onClick = { displayMenu = true }) { Icon(Icons.Outlined.Tune, contentDescription = "View and sort") }
                        DropdownMenu(expanded = displayMenu, onDismissRequest = { displayMenu = false }) {
                            Text("VIEW", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp))
                            LibraryViewMode.entries.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(viewModeLabel(m), fontWeight = if (m == viewMode) FontWeight.SemiBold else FontWeight.Normal) },
                                    onClick = { viewModel.setViewMode(m); displayMenu = false },
                                )
                            }
                            HorizontalDivider()
                            Text("SORT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp))
                            LibrarySort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label, fontWeight = if (s == sort) FontWeight.SemiBold else FontWeight.Normal) },
                                    onClick = { viewModel.setSort(s); displayMenu = false },
                                )
                            }
                        }
                    }
                    val current = scope
                    if (current is LibraryScope.Collection) {
                        Box {
                            IconButton(onClick = { scopeMenu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Manage collection") }
                            DropdownMenu(expanded = scopeMenu, onDismissRequest = { scopeMenu = false }) {
                                DropdownMenuItem(text = { Text("New sub-collection") }, onClick = { scopeMenu = false; showCreate = current.id })
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { scopeMenu = false; renaming = current.id to current.name })
                                DropdownMenuItem(text = { Text("Move under…") }, onClick = { scopeMenu = false; reparenting = current.id to current.name })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { scopeMenu = false; viewModel.deleteCollection(current.id) })
                            }
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )

        // A single, quiet type-filter strip only when the current scope actually mixes types.
        if (!searching && availableTypes.size >= 2) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = typeFilter == null, onClick = { viewModel.setTypeFilter(null) }, label = { Text("All") })
                availableTypes.forEach { t ->
                    FilterChip(
                        selected = typeFilter == t,
                        onClick = { viewModel.setTypeFilter(if (typeFilter == t) null else t) },
                        label = { Text(typeLabel(t)) },
                    )
                }
            }
        }

        if (selectionActive) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.clearSelection() }) { Icon(Icons.Outlined.Close, contentDescription = "Clear selection") }
                Text("${selection.size} selected", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = { showMove = true }) { Text("Move") }
                TextButton(onClick = { viewModel.archiveSelected() }) {
                    Text(if (scope is LibraryScope.Archive) "Unarchive" else "Archive")
                }
                TextButton(onClick = { viewModel.removeSelectedFromLibrary() }) { Text("Remove") }
            }
        }

        if (scope is LibraryScope.Home && !searching) {
            val smart by viewModel.smartCounts.collectAsStateWithLifecycle()
            val fold by viewModel.foldState.collectAsStateWithLifecycle()
            LibraryHome(
                counts = counts,
                smart = smart,
                collections = collections,
                tags = tags,
                bottomPad = padding.calculateBottomPadding() + 88.dp,
                fold = fold,
                onSetQuickOpen = viewModel::setQuickOpen,
                onSetCollectionsOpen = viewModel::setCollectionsOpen,
                onSetTagsOpen = viewModel::setTagsOpen,
                onToggleCollection = viewModel::setCollectionCollapsed,
                onToggleTag = viewModel::setTagCollapsed,
                onScope = { viewModel.setScope(it) },
                onOpenHighlights = onOpenHighlights,
                onNewCollection = { showCreate = "" },
            )
        } else if (showing.isEmpty()) {
            val (emptyIcon, emptyTitle, emptyBody) = when {
                searching -> Triple(Icons.Outlined.Search, "No matches", "Nothing matched that search. Try a different or shorter term.")
                scope is LibraryScope.Collection -> Triple(Icons.Outlined.Add, "This collection is empty", "Open any article's menu and choose “Move to collection” to file it here.")
                scope is LibraryScope.Tag -> Triple(Icons.Outlined.Add, "No items with this tag", "Add this tag to an article from its menu and it will show up here.")
                scope is LibraryScope.Unsorted -> Triple(Icons.Outlined.BookmarkAdd, "Nothing unsorted", "Saved items that aren't in a collection gather here, ready to file.")
                scope is LibraryScope.Favorites -> Triple(Icons.Outlined.StarBorder, "No favorites yet", "Star an article from its menu or the reader and it collects here — your best-of, always one tap away.")
                scope is LibraryScope.Archive -> Triple(Icons.Outlined.Archive, "Archive is empty", "Swipe an item to archive it, or use an article's menu. Archived items leave your lists but stay searchable here.")
                else -> Triple(Icons.Outlined.BookmarkAdd, "Your library is empty", "Save or star an article, or file it into a collection, and it lives here — offline and yours.")
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(emptyIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(14.dp))
                Text(
                    text = emptyTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = emptyBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            val bottomPad = padding.calculateBottomPadding() + 88.dp
            val effectiveMode = if (searching) LibraryViewMode.LIST else viewMode
            LibraryContent(
                items = showing,
                mode = effectiveMode,
                bottomPad = bottomPad,
                selected = selection,
                onClick = { row ->
                    if (selectionActive) viewModel.toggleSelect(row.id) else {
                        com.cairn.reader.ui.reader.ReaderQueue.set(showing.map { it.id })
                        onOpenItem(row.id)
                    }
                },
                onLongPress = { row -> viewModel.toggleSelect(row.id) },
                onToggleSave = { row -> viewModel.toggleSave(row.id, !row.isReadLater) },
            )
        }
    }

    }

    showCreate?.let { parentId ->
        val underName = collections.firstOrNull { it.id == parentId }?.name
        NameDialog(
            title = if (underName != null) "New collection in $underName" else "New collection",
            initial = "", confirmLabel = "Create",
            onConfirm = { viewModel.createCollection(it, parentId.ifBlank { null }); showCreate = null },
            onDismiss = { showCreate = null },
        )
    }
    renaming?.let { (id, name) ->
        NameDialog(title = "Rename collection", initial = name, confirmLabel = "Save", onConfirm = { viewModel.renameCollection(id, it); renaming = null }, onDismiss = { renaming = null })
    }
    reparenting?.let { (id, name) ->
        // Exclude the collection itself and every descendant, so a move can never form a cycle.
        val blocked = collectionSubtreeIds(collections, id)
        CollectionPickerSheet(
            collections = collections.filter { it.id !in blocked },
            currentCollectionId = collections.firstOrNull { it.id == id }?.parentId,
            title = "Move “$name” under…",
            unsortedLabel = "Top level (no parent)",
            onPick = { parent -> viewModel.setCollectionParent(id, parent); reparenting = null },
            onCreate = { viewModel.createCollection(it) },
            onDismiss = { reparenting = null },
        )
    }
    if (showMove) {
        CollectionPickerSheet(
            collections = collections,
            currentCollectionId = null,
            onPick = { collectionId -> viewModel.moveSelected(collectionId); showMove = false },
            onCreate = { viewModel.createCollection(it) },
            onDismiss = { showMove = false },
        )
    }
    if (filterSheet) {
        LibraryFilterSheet(
            scope = scope,
            counts = counts,
            collections = collections,
            tags = tags,
            savedSearches = savedSearches,
            onScope = { viewModel.setScope(it); filterSheet = false },
            onOpenHighlights = { filterSheet = false; onOpenHighlights() },
            onSavedSearch = { q -> viewModel.setQuery(q); searchOpen = true; filterSheet = false },
            onRemoveSavedSearch = { viewModel.removeSavedSearch(it) },
            onNewCollection = { parentId -> showCreate = parentId ?: "" },
            onRenameCollection = { id, name -> renaming = id to name },
            onDeleteCollection = { viewModel.deleteCollection(it) },
            onRenameTag = { path, label -> renamingTag = path to label },
            onMoveTag = { path -> movingTag = path },
            onDeleteTag = { viewModel.deleteTag(it) },
            onDismiss = { filterSheet = false },
        )
    }
    renamingTag?.let { (path, label) ->
        NameDialog(
            title = "Rename tag",
            initial = label,
            confirmLabel = "Save",
            onConfirm = { viewModel.renameTag(path, it); renamingTag = null },
            onDismiss = { renamingTag = null },
        )
    }
    movingTag?.let { path ->
        // Candidate parents: every existing tag path that isn't this tag or one of its descendants.
        val p = path.trim().trim('/')
        val candidates = tags.map { it.name.trim().trim('/') }
            .flatMap { name -> name.split('/').filter { it.isNotBlank() }.let { segs -> (1..segs.size).map { segs.subList(0, it).joinToString("/") } } }
            .distinct()
            .filter { it != p && !it.startsWith("$p/") }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        TagMovePicker(
            path = p,
            candidates = candidates,
            onPick = { parent -> viewModel.moveTag(path, parent); movingTag = null },
            onDismiss = { movingTag = null },
        )
    }
}

/** A bottom sheet listing candidate parent tags to move a tag (and its sub-tags) under. */
@Composable
private fun TagMovePicker(
    path: String,
    candidates: List<String>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Text(
                    "Move “#${path.substringAfterLast('/')}” under…",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(null) }.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Label, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(12.dp))
                    Text("Top level (no parent)", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                }
            }
            items(candidates, key = { "mv-$it" }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(c) }.padding(start = (24 + c.count { ch -> ch == '/' } * 16).dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Label, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(12.dp))
                    Text("#${c.substringAfterLast('/')}", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun LibraryContent(
    items: List<ItemListRow>,
    mode: LibraryViewMode,
    bottomPad: Dp,
    selected: Set<String>,
    onClick: (ItemListRow) -> Unit,
    onLongPress: (ItemListRow) -> Unit,
    onToggleSave: (ItemListRow) -> Unit,
) {
    when (mode) {
        LibraryViewMode.LIST -> LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = bottomPad)) {
            items(items, key = { it.id }) { row ->
                ItemRow(row = row, onOpen = { onClick(row) }, onToggleSave = { onToggleSave(row) }, onLongPress = { onLongPress(row) }, selected = row.id in selected)
                EntryDivider()
            }
        }
        LibraryViewMode.HEADLINES -> LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = bottomPad)) {
            items(items, key = { it.id }) { row -> HeadlineRow(row, row.id in selected, { onClick(row) }, { onLongPress(row) }); EntryDivider() }
        }
        LibraryViewMode.GRID -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomPad),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            gridItems(items, key = { it.id }) { row ->
                LibraryCoverCard(row, fixedRatio = true, selected = row.id in selected, onClick = { onClick(row) }, onLongPress = { onLongPress(row) })
            }
        }
        LibraryViewMode.MASONRY -> LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomPad),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 10.dp,
        ) {
            staggeredItems(items, key = { it.id }) { row ->
                LibraryCoverCard(row, fixedRatio = false, selected = row.id in selected, onClick = { onClick(row) }, onLongPress = { onLongPress(row) })
            }
        }
    }
}

@Composable
private fun LibraryCoverCard(row: ItemListRow, fixedRatio: Boolean, selected: Boolean, onClick: () -> Unit, onLongPress: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) scheme.secondaryContainer else scheme.surfaceContainerLow)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(bottom = 8.dp),
    ) {
        Box {
            if (row.leadImage != null) {
                AsyncImage(
                    model = row.leadImage,
                    contentDescription = null,
                    contentScale = if (fixedRatio) ContentScale.Crop else ContentScale.FillWidth,
                    modifier = if (fixedRatio) {
                        Modifier.fillMaxWidth().aspectRatio(16f / 10f)
                    } else {
                        Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 320.dp)
                    },
                )
            } else {
                // No cover image — synthesise a tinted panel so the moodboard stays full.
                MonogramCover(row, tall = !fixedRatio)
            }
            TypeBadge(row.type, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            val source = row.sourceTitle ?: row.siteName ?: "Unknown"
            Text(source, style = MaterialTheme.typography.labelSmall, color = scheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(row.title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (!row.excerpt.isNullOrBlank() && !fixedRatio) {
                Spacer(Modifier.height(4.dp))
                Text(row.excerpt, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A generated cover for items with no lead image — a tinted wash carrying the title initial. */
@Composable
private fun MonogramCover(row: ItemListRow, tall: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val base = COVER_TINTS[(row.title.hashCode() and 0x7fffffff) % COVER_TINTS.size]
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (tall) Modifier.height(120.dp) else Modifier.aspectRatio(16f / 10f))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(base.copy(alpha = 0.85f), base.copy(alpha = 0.45f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (row.sourceTitle ?: row.siteName ?: row.title).trim().take(1).uppercase(),
            style = MaterialTheme.typography.displaySmall,
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.92f),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A small pill naming the item type (Article / Link / Video / Image), Raindrop-style. */
@Composable
private fun TypeBadge(type: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Text(
        typeLabelSingular(type),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = scheme.onSurface,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(scheme.surface.copy(alpha = 0.88f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

private val COVER_TINTS = listOf(
    androidx.compose.ui.graphics.Color(0xFF3F5E7A),
    androidx.compose.ui.graphics.Color(0xFF3E8E5A),
    androidx.compose.ui.graphics.Color(0xFFB98A2E),
    androidx.compose.ui.graphics.Color(0xFFB0553F),
    androidx.compose.ui.graphics.Color(0xFF6A5A8E),
    androidx.compose.ui.graphics.Color(0xFF2E8B94),
)

private fun typeLabel(type: String): String = when (type) {
    "ARTICLE" -> "Articles"
    "LINK" -> "Links"
    "VIDEO" -> "Videos"
    "AUDIO" -> "Podcasts"
    "IMAGE" -> "Images"
    "PDF" -> "PDFs"
    else -> type.lowercase().replaceFirstChar(Char::uppercase)
}

private fun typeLabelSingular(type: String): String = when (type) {
    "ARTICLE" -> "Article"
    "LINK" -> "Link"
    "VIDEO" -> "Video"
    "AUDIO" -> "Podcast"
    "IMAGE" -> "Image"
    "PDF" -> "PDF"
    else -> type.lowercase().replaceFirstChar(Char::uppercase)
}

private fun viewModeLabel(mode: LibraryViewMode): String = when (mode) {
    LibraryViewMode.LIST -> "List"
    LibraryViewMode.GRID -> "Grid"
    LibraryViewMode.MASONRY -> "Moodboard"
    LibraryViewMode.HEADLINES -> "Headlines"
}

@Composable
private fun HeadlineRow(row: ItemListRow, selected: Boolean, onClick: () -> Unit, onLongPress: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) scheme.secondaryContainer else scheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(row.title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(row.sourceTitle ?: row.siteName ?: "", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
    }
}

/** The subtle hairline shown after each list entry. */
@Composable
private fun EntryDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        thickness = 0.6.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/**
 * The storage-first browse home (Raindrop-style): you land here on the Library, not on a flat list.
 * Quick-access buckets first, then your collections as cards, then tags — everything one tap into its
 * own scope.
 */
@Composable
private fun LibraryHome(
    counts: LibraryCounts,
    smart: LibraryViewModel.SmartCounts,
    collections: List<CollectionWithCount>,
    tags: List<TagWithCount>,
    bottomPad: Dp,
    fold: LibraryViewModel.FoldState,
    onSetQuickOpen: (Boolean) -> Unit,
    onSetCollectionsOpen: (Boolean) -> Unit,
    onSetTagsOpen: (Boolean) -> Unit,
    onToggleCollection: (String, Boolean) -> Unit,
    onToggleTag: (String, Boolean) -> Unit,
    onScope: (LibraryScope) -> Unit,
    onOpenHighlights: () -> Unit,
    onNewCollection: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // Foldable state is persisted in prefs (via the ViewModel), so it survives navigation and restarts.
    val quickOpen = fold.quickOpen
    val collectionsOpen = fold.collectionsOpen
    val tagsOpen = fold.tagsOpen
    val colRows = flattenCollections(collections, fold.collapsedCollections)
    val tagRows = buildTagTree(tags, fold.collapsedTags)

    data class Bucket(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val count: Int?, val onClick: () -> Unit)
    val buckets = listOf(
        Bucket(Icons.Outlined.CollectionsBookmark, "All items", counts.allCount) { onScope(LibraryScope.All) },
        Bucket(Icons.Outlined.Inbox, "Unsorted", counts.unsortedCount) { onScope(LibraryScope.Unsorted) },
        Bucket(Icons.Outlined.StarBorder, "Favorites", counts.favoritesCount) { onScope(LibraryScope.Favorites) },
        Bucket(Icons.Outlined.FormatQuote, "Highlights", null, onOpenHighlights),
        Bucket(Icons.Outlined.OfflinePin, "Offline", counts.offlineCount) { onScope(LibraryScope.Offline) },
        Bucket(Icons.Outlined.Archive, "Archive", counts.archiveCount) { onScope(LibraryScope.Archive) },
    )
    // Smart-view "cleanup" buckets only appear when there's something to act on, so the home stays calm.
    val smartBuckets = buildList {
        if (smart.untagged > 0) add(Bucket(Icons.Outlined.Label, "Untagged", smart.untagged) { onScope(LibraryScope.Untagged) })
        if (smart.duplicates > 0) add(Bucket(Icons.Outlined.ContentCopy, "Duplicates", smart.duplicates) { onScope(LibraryScope.Duplicates) })
        if (smart.broken > 0) add(Bucket(Icons.Outlined.LinkOff, "Broken", smart.broken) { onScope(LibraryScope.Broken) })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { FoldableSectionHeader("QUICK ACCESS", quickOpen, onToggle = { onSetQuickOpen(!quickOpen) }) }
        if (quickOpen) {
            items(buckets.chunked(2)) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { b -> HomeTile(b.icon, b.label, b.count, Modifier.weight(1f), b.onClick) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        // Cleanup buckets — only present when there's something to fix.
        if (smartBuckets.isNotEmpty()) {
            item { HomeSectionLabel("CLEANUP", Modifier.padding(top = 10.dp)) }
            items(smartBuckets.chunked(2)) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { b -> HomeTile(b.icon, b.label, b.count, Modifier.weight(1f), b.onClick) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        item {
            FoldableSectionHeader(
                "COLLECTIONS", collectionsOpen, onToggle = { onSetCollectionsOpen(!collectionsOpen) },
                trailing = {
                    IconButton(onClick = onNewCollection, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Add, contentDescription = "New collection", tint = scheme.primary)
                    }
                },
            )
        }
        if (collectionsOpen) {
            if (colRows.isEmpty()) {
                item {
                    Text(
                        "No collections yet. Tap + to create one, then file saved items into it.",
                        style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
                    )
                }
            } else {
                items(colRows, key = { it.id }) { r ->
                    val isCollapsed = r.id in fold.collapsedCollections
                    CollectionTreeItem(
                        name = r.name, count = r.count, depth = r.depth,
                        hasChildren = r.hasChildren, collapsed = isCollapsed,
                        onToggle = { onToggleCollection(r.id, !isCollapsed) },
                        onOpen = { onScope(LibraryScope.Collection(r.id, r.name)) },
                    )
                }
            }
        }

        if (tagRows.isNotEmpty()) {
            item { FoldableSectionHeader("TAGS", tagsOpen, onToggle = { onSetTagsOpen(!tagsOpen) }) }
            if (tagsOpen) {
                items(tagRows, key = { it.path }) { r ->
                    val isCollapsed = r.path in fold.collapsedTags
                    TagTreeItem(
                        label = r.label, count = r.totalCount, depth = r.depth,
                        hasChildren = r.hasChildren, collapsed = isCollapsed,
                        onToggle = { onToggleTag(r.path, !isCollapsed) },
                        onOpen = if (r.exists && r.tagId != null) ({ onScope(LibraryScope.Tag(r.tagId, r.path)) }) else null,
                    )
                }
            }
        }
    }
}

/** A collapsible section header with a chevron and an optional trailing action. */
@Composable
private fun FoldableSectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle).padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = scheme.primary, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(2.dp))
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = scheme.primary, modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

/** One row of the collections tree: indent by depth, chevron to expand, tap to open. */
@Composable
private fun CollectionTreeItem(
    name: String, count: Int, depth: Int, hasChildren: Boolean, collapsed: Boolean,
    onToggle: () -> Unit, onOpen: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen)
            .padding(start = (8 + depth * 18).dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasChildren) {
            IconButton(onClick = onToggle, modifier = Modifier.size(44.dp)) {
                Icon(
                    if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (collapsed) "Expand" else "Collapse", tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
                )
            }
        } else {
            Spacer(Modifier.size(28.dp))
        }
        Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = scheme.secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    }
}

/** One row of the nested-tag tree. [onOpen] is null for a synthesized parent (expand-only). */
@Composable
private fun TagTreeItem(
    label: String, count: Int, depth: Int, hasChildren: Boolean, collapsed: Boolean,
    onToggle: () -> Unit, onOpen: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(enabled = onOpen != null || hasChildren, onClick = { onOpen?.invoke() ?: onToggle() })
            .padding(start = (8 + depth * 18).dp, top = 9.dp, bottom = 9.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasChildren) {
            IconButton(onClick = onToggle, modifier = Modifier.size(44.dp)) {
                Icon(
                    if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (collapsed) "Expand" else "Collapse", tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
                )
            }
        } else {
            Spacer(Modifier.size(28.dp))
        }
        Icon(Icons.Outlined.Label, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (count > 0) Text("$count", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun HomeSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun HomeTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (count != null) {
                Text(if (count == 1) "1 item" else "$count items", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HomeCollectionTile(
    name: String,
    count: Int,
    subCount: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = scheme.secondary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = buildString {
                append(if (count == 1) "1 item" else "$count items")
                if (subCount > 0) append(" · $subCount sub")
            }
            Text(meta, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

private fun scopeTitle(scope: LibraryScope): String = when (scope) {
    LibraryScope.Home -> "Library"
    LibraryScope.All -> "All items"
    LibraryScope.Unsorted -> "Unsorted"
    LibraryScope.Favorites -> "Favorites"
    LibraryScope.Archive -> "Archive"
    LibraryScope.Offline -> "Offline copies"
    LibraryScope.Untagged -> "Untagged"
    LibraryScope.Broken -> "Broken links"
    LibraryScope.Duplicates -> "Duplicates"
    is LibraryScope.Collection -> scope.name
    is LibraryScope.Tag -> "#${scope.name.substringAfterLast('/')}"
}

/** The id plus every (transitive) descendant id — used to keep re-parenting acyclic. */
private fun collectionSubtreeIds(all: List<CollectionWithCount>, root: String): Set<String> {
    val childrenOf = all.groupBy { it.parentId }
    val out = HashSet<String>()
    fun walk(id: String) {
        if (!out.add(id)) return
        childrenOf[id].orEmpty().forEach { walk(it.id) }
    }
    walk(root)
    return out
}

/** One flattened row of the (nested) collection tree, honouring which parents are collapsed. */
private data class CollectionRow(val id: String, val name: String, val count: Int, val depth: Int, val hasChildren: Boolean)

private fun flattenCollections(all: List<CollectionWithCount>, collapsed: Set<String>): List<CollectionRow> {
    val ids = all.mapTo(HashSet()) { it.id }
    // Treat a collection whose parent is missing (or null) as a root, so nothing is ever hidden.
    val byParent = all.groupBy { it.parentId?.takeIf { p -> p in ids } }
    val out = ArrayList<CollectionRow>()
    fun walk(parent: String?, depth: Int) {
        byParent[parent].orEmpty().sortedBy { it.name.lowercase() }.forEach { c ->
            val kids = byParent[c.id].orEmpty()
            out += CollectionRow(c.id, c.name, c.count, depth, kids.isNotEmpty())
            if (kids.isNotEmpty() && c.id !in collapsed) walk(c.id, depth + 1)
        }
    }
    walk(null, 0)
    return out
}

/** One flattened row of the (path-)nested tag tree. [exists] is false for a synthesized parent
 *  node that has no tag row of its own (only descendants), so it can still be browsed and managed.
 *  [totalCount] rolls up the node's own items plus every descendant's. */
private data class TagTreeRow(
    val path: String,
    val label: String,
    val depth: Int,
    val ownCount: Int,
    val totalCount: Int,
    val hasChildren: Boolean,
    val exists: Boolean,
    val tagId: String?,
)

/** Build the foldable tag tree from flat, "/"-delimited tag paths, synthesizing any missing
 *  parent nodes so e.g. a lone "tech/ai" still shows a browsable "tech" above it. */
private fun buildTagTree(tags: List<TagWithCount>, collapsed: Set<String>): List<TagTreeRow> {
    // Dedupe by normalized path; keep the highest count / a real id if the same path repeats.
    val real = HashMap<String, TagWithCount>()
    tags.forEach { t ->
        val p = t.name.trim().trim('/')
        if (p.isNotBlank()) real[p] = t
    }
    // Every node path, including synthesized ancestors.
    val allPaths = HashSet<String>()
    real.keys.forEach { path ->
        val segs = path.split('/').filter { it.isNotBlank() }
        for (i in 1..segs.size) allPaths += segs.subList(0, i).joinToString("/")
    }
    // Sorting "/"-paths case-insensitively yields a correct pre-order walk, because '/' sorts
    // before letters and digits, so a parent and its whole subtree precede the next sibling.
    val sorted = allPaths.sortedWith(String.CASE_INSENSITIVE_ORDER)
    val out = ArrayList<TagTreeRow>()
    for (path in sorted) {
        val segs = path.split('/')
        val ancestors = (1 until segs.size).map { segs.subList(0, it).joinToString("/") }
        if (ancestors.any { it in collapsed }) continue // a collapsed ancestor hides this node
        val hasChildren = sorted.any { it.length > path.length && it.startsWith("$path/") }
        val total = real.entries.filter { it.key == path || it.key.startsWith("$path/") }.sumOf { it.value.count }
        val rr = real[path]
        out += TagTreeRow(path, segs.last(), segs.size - 1, rr?.count ?: 0, total, hasChildren, rr != null, rr?.id)
    }
    return out
}

/**
 * The library organiser: every scope, the full nested collection tree (foldable, with add /
 * rename / delete / re-parent per node), path-nested tags, and saved searches — all in one sheet
 * so the main screen stays a clean single-row top bar. This is Cairn's answer to Raindrop's
 * left sidebar, shaped for a phone.
 */
@Composable
private fun LibraryFilterSheet(
    scope: LibraryScope,
    counts: LibraryCounts,
    collections: List<CollectionWithCount>,
    tags: List<TagWithCount>,
    savedSearches: List<String>,
    onScope: (LibraryScope) -> Unit,
    onOpenHighlights: () -> Unit,
    onSavedSearch: (String) -> Unit,
    onRemoveSavedSearch: (String) -> Unit,
    onNewCollection: (parentId: String?) -> Unit,
    onRenameCollection: (id: String, name: String) -> Unit,
    onDeleteCollection: (String) -> Unit,
    onRenameTag: (path: String, label: String) -> Unit,
    onMoveTag: (path: String) -> Unit,
    onDeleteTag: (path: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var collapsedTags by remember { mutableStateOf(setOf<String>()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var menuForTag by remember { mutableStateOf<String?>(null) }
    val rows = flattenCollections(collections, collapsed)
    val tagRows = buildTagTree(tags, collapsedTags)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item {
                Text(
                    "Filter & organize",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
                )
            }
            // -- System scopes ----------------------------------------------------
            item { SheetScopeRow(Icons.Outlined.CollectionsBookmark, "All", counts.allCount, scope is LibraryScope.All) { onScope(LibraryScope.All) } }
            item { SheetScopeRow(Icons.Outlined.Inbox, "Unsorted", counts.unsortedCount, scope is LibraryScope.Unsorted) { onScope(LibraryScope.Unsorted) } }
            item { SheetScopeRow(Icons.Outlined.StarBorder, "Favorites", counts.favoritesCount, scope is LibraryScope.Favorites) { onScope(LibraryScope.Favorites) } }
            item { SheetScopeRow(Icons.Outlined.OfflinePin, "Offline copies", counts.offlineCount, scope is LibraryScope.Offline) { onScope(LibraryScope.Offline) } }
            item { SheetScopeRow(Icons.Outlined.Archive, "Archive", counts.archiveCount, scope is LibraryScope.Archive) { onScope(LibraryScope.Archive) } }
            item { SheetScopeRow(Icons.Outlined.FormatQuote, "Highlights", null, false) { onOpenHighlights() } }

            // -- Collections (nested) --------------------------------------------
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 14.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("COLLECTIONS", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onNewCollection(null) }) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp)); Text("New")
                    }
                }
            }
            if (rows.isEmpty()) {
                item {
                    Text(
                        "No collections yet — group saved items into collections and sub-collections.",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
            }
            items(rows, key = { it.id }) { r ->
                val selected = scope.let { it is LibraryScope.Collection && it.id == r.id }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onScope(LibraryScope.Collection(r.id, r.name)) }
                        .padding(start = (18 + r.depth * 18).dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (r.hasChildren) {
                        Icon(
                            if (r.id in collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (r.id in collapsed) "Expand" else "Collapse",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp).clickable { collapsed = if (r.id in collapsed) collapsed - r.id else collapsed + r.id },
                        )
                        Spacer(Modifier.size(4.dp))
                    } else {
                        Spacer(Modifier.size(24.dp))
                    }
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = if (selected) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(12.dp))
                    Text(
                        r.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) scheme.primary else scheme.onSurface,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (r.count > 0) {
                        Text("${r.count}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { menuFor = r.id }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Manage", modifier = Modifier.size(20.dp)) }
                        DropdownMenu(expanded = menuFor == r.id, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(text = { Text("New sub-collection") }, onClick = { menuFor = null; onNewCollection(r.id) })
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menuFor = null; onRenameCollection(r.id, r.name) })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menuFor = null; onDeleteCollection(r.id) })
                        }
                    }
                }
            }

            // -- Tags (path-nested "parent/child", foldable, with roll-up counts) -----
            if (tagRows.isNotEmpty()) {
                item {
                    Text("TAGS", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 2.dp))
                }
                items(tagRows, key = { "tag-${it.path}" }) { r ->
                    val selected = scope.let { it is LibraryScope.Tag && it.name == r.path }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onScope(LibraryScope.Tag(r.tagId ?: r.path, r.path)) }
                            .padding(start = (18 + r.depth * 18).dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (r.hasChildren) {
                            Icon(
                                if (r.path in collapsedTags) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (r.path in collapsedTags) "Expand" else "Collapse",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp).clickable { collapsedTags = if (r.path in collapsedTags) collapsedTags - r.path else collapsedTags + r.path },
                            )
                            Spacer(Modifier.size(4.dp))
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                        Icon(Icons.Outlined.Label, contentDescription = null, tint = if (selected) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(
                            "#${r.label}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) scheme.primary else if (r.exists) scheme.onSurface else scheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        if (r.totalCount > 0) Text("${r.totalCount}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        Box {
                            IconButton(onClick = { menuForTag = r.path }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Manage tag", modifier = Modifier.size(20.dp)) }
                            DropdownMenu(expanded = menuForTag == r.path, onDismissRequest = { menuForTag = null }) {
                                if (r.exists) {
                                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuForTag = null; onRenameTag(r.path, r.label) })
                                }
                                DropdownMenuItem(text = { Text("Move under…") }, onClick = { menuForTag = null; onMoveTag(r.path) })
                                DropdownMenuItem(
                                    text = { Text(if (r.hasChildren) "Delete tag & sub-tags" else "Delete", color = scheme.error) },
                                    onClick = { menuForTag = null; onDeleteTag(r.path) },
                                )
                            }
                        }
                    }
                }
            }

            // -- Saved searches --------------------------------------------------
            if (savedSearches.isNotEmpty()) {
                item {
                    Text("SAVED SEARCHES", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 2.dp))
                }
                items(savedSearches, key = { "ss-$it" }) { q ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSavedSearch(q) }.padding(start = 24.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(q, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onRemoveSavedSearch(q) }) { Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetScopeRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (selected) scheme.primary else scheme.onSurface, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (count != null && count > 0) Text("$count", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
