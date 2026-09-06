@file:OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.cairn.reader.ui.library

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

@Composable
internal fun LibraryFilterSheet(
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
                Text(stringResource(R.string.filter_organize),
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
                    Text(stringResource(R.string.collections), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onNewCollection(null) }) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp)); Text(stringResource(R.string.new_kw))
                    }
                }
            }
            if (rows.isEmpty()) {
                item {
                    Text(stringResource(R.string.no_collections_yet_group_saved_items),
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
                        IconButton(onClick = { menuFor = r.id }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.manage), modifier = Modifier.size(20.dp)) }
                        DropdownMenu(expanded = menuFor == r.id, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.new_sub_collection)) }, onClick = { menuFor = null; onNewCollection(r.id) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menuFor = null; onRenameCollection(r.id, r.name) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menuFor = null; onDeleteCollection(r.id) })
                        }
                    }
                }
            }

            // -- Tags (path-nested "parent/child", foldable, with roll-up counts) -----
            if (tagRows.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.tags_2), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 2.dp))
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
                            IconButton(onClick = { menuForTag = r.path }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.manage_tag), modifier = Modifier.size(20.dp)) }
                            DropdownMenu(expanded = menuForTag == r.path, onDismissRequest = { menuForTag = null }) {
                                if (r.exists) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menuForTag = null; onRenameTag(r.path, r.label) })
                                }
                                DropdownMenuItem(text = { Text(stringResource(R.string.move_under)) }, onClick = { menuForTag = null; onMoveTag(r.path) })
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
                    Text(stringResource(R.string.saved_searches), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 2.dp))
                }
                items(savedSearches, key = { "ss-$it" }) { q ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSavedSearch(q) }.padding(start = 24.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(q, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onRemoveSavedSearch(q) }) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.remove_2), modifier = Modifier.size(18.dp)) }
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
internal fun NameDialog(title: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
