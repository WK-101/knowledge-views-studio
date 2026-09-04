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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.prefs.LibraryViewMode
import com.cairn.reader.ui.components.CollectionPickerSheet
import com.cairn.reader.ui.components.ItemRow

@Composable
fun LibraryScreen(
    padding: PaddingValues,
    onOpenItem: (String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
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

    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showMove by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var scopeMenu by remember { mutableStateOf(false) }
    var displayMenu by remember { mutableStateOf(false) }

    val selectionActive = selection.isNotEmpty()
    val searching = query.isNotBlank()
    val showing = if (searching) results else items

    BackHandler(enabled = selectionActive) { viewModel.clearSelection() }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        // Search + (for a collection scope) manage overflow
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { viewModel.saveSearch(query) }) {
                            Icon(Icons.Outlined.BookmarkAdd, contentDescription = "Save search")
                        }
                    }
                },
                placeholder = { Text("Search everything you've saved") },
                modifier = Modifier.weight(1f),
            )
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
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { scopeMenu = false; renaming = current.id to current.name })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { scopeMenu = false; viewModel.deleteCollection(current.id) })
                    }
                }
            }
        }

        if (!searching) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = scope is LibraryScope.All, onClick = { viewModel.setScope(LibraryScope.All) }, label = { Text("All") })
                FilterChip(selected = scope is LibraryScope.Unsorted, onClick = { viewModel.setScope(LibraryScope.Unsorted) }, label = { Text("Unsorted") })
                collections.forEach { c ->
                    FilterChip(
                        selected = scope.let { it is LibraryScope.Collection && it.id == c.id },
                        onClick = { viewModel.setScope(LibraryScope.Collection(c.id, c.name)) },
                        label = { Text(if (c.count > 0) "${c.name} · ${c.count}" else c.name) },
                    )
                }
                AssistChip(
                    onClick = { showCreate = true },
                    label = { Text("New") },
                    leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.padding(0.dp)) },
                )
            }
            if (availableTypes.size >= 2) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = typeFilter == null, onClick = { viewModel.setTypeFilter(null) }, label = { Text("All types") })
                    availableTypes.forEach { t ->
                        FilterChip(
                            selected = typeFilter == t,
                            onClick = { viewModel.setTypeFilter(if (typeFilter == t) null else t) },
                            label = { Text(typeLabel(t)) },
                        )
                    }
                }
            }
            if (tags.isNotEmpty()) {
                Text(
                    "TAGS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 2.dp),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEach { tag ->
                        FilterChip(
                            selected = scope.let { it is LibraryScope.Tag && it.id == tag.id },
                            onClick = { viewModel.setScope(LibraryScope.Tag(tag.id, tag.name)) },
                            label = { Text(if (tag.count > 0) "#${tag.name} · ${tag.count}" else "#${tag.name}") },
                        )
                    }
                }
            }
            if (savedSearches.isNotEmpty()) {
                Text(
                    "SAVED SEARCHES",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 2.dp),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    savedSearches.forEach { q ->
                        androidx.compose.material3.InputChip(
                            selected = false,
                            onClick = { viewModel.setQuery(q) },
                            label = { Text(q) },
                            trailingIcon = {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "Remove",
                                    modifier = Modifier.size(16.dp).clickable { viewModel.removeSavedSearch(q) },
                                )
                            },
                        )
                    }
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
                TextButton(onClick = { viewModel.removeSelectedFromLibrary() }) { Text("Remove") }
            }
        }

        if (showing.isEmpty()) {
            val (emptyIcon, emptyTitle, emptyBody) = when {
                searching -> Triple(Icons.Outlined.Search, "No matches", "Nothing matched that search. Try a different or shorter term.")
                scope is LibraryScope.Collection -> Triple(Icons.Outlined.Add, "This collection is empty", "Open any article's menu and choose “Move to collection” to file it here.")
                scope is LibraryScope.Tag -> Triple(Icons.Outlined.Add, "No items with this tag", "Add this tag to an article from its menu and it will show up here.")
                scope is LibraryScope.Unsorted -> Triple(Icons.Outlined.BookmarkAdd, "Nothing unsorted", "Saved items that aren't in a collection gather here, ready to file.")
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
                onClick = { row -> if (selectionActive) viewModel.toggleSelect(row.id) else onOpenItem(row.id) },
                onLongPress = { row -> viewModel.toggleSelect(row.id) },
                onToggleSave = { row -> viewModel.toggleSave(row.id, !row.isReadLater) },
            )
        }
    }

    if (!selectionActive) {
        androidx.compose.material3.ExtendedFloatingActionButton(
            onClick = { showSave = true },
            icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
            text = { Text("Save link") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = padding.calculateBottomPadding() + 20.dp),
        )
    }
    }

    if (showCreate) {
        NameDialog(title = "New collection", initial = "", confirmLabel = "Create", onConfirm = { viewModel.createCollection(it); showCreate = false }, onDismiss = { showCreate = false })
    }
    renaming?.let { (id, name) ->
        NameDialog(title = "Rename collection", initial = name, confirmLabel = "Save", onConfirm = { viewModel.renameCollection(id, it); renaming = null }, onDismiss = { renaming = null })
    }
    if (showSave) {
        NameDialog(title = "Save a link", initial = "", confirmLabel = "Save", onConfirm = { viewModel.saveLink(it); showSave = false }, onDismiss = { showSave = false })
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
            }
        }
        LibraryViewMode.HEADLINES -> LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = bottomPad)) {
            items(items, key = { it.id }) { row -> HeadlineRow(row, row.id in selected, { onClick(row) }, { onLongPress(row) }) }
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
    else -> type.lowercase().replaceFirstChar(Char::uppercase)
}

private fun typeLabelSingular(type: String): String = when (type) {
    "ARTICLE" -> "Article"
    "LINK" -> "Link"
    "VIDEO" -> "Video"
    "AUDIO" -> "Podcast"
    "IMAGE" -> "Image"
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
