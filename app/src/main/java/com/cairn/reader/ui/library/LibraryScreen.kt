@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cairn.reader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.ui.components.CollectionPickerSheet
import com.cairn.reader.ui.components.ItemRow

@Composable
fun LibraryScreen(
    padding: PaddingValues,
    onOpenItem: (String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val scope by viewModel.scope.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()

    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Pair<String, String>?>(null) }
    var moving by remember { mutableStateOf<ItemListRow?>(null) }
    var scopeMenu by remember { mutableStateOf(false) }

    val searching = query.isNotBlank()
    val showing = if (searching) results else items

    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        // Search + (for a collection scope) manage overflow
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Search everything you've saved") },
                modifier = Modifier.weight(1f),
            )
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
        }

        if (showing.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (searching) "No matches" else "Nothing here yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (searching) "Try a different search." else "Save or star an article, or file it into a collection, and it lives here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = padding.calculateBottomPadding() + 24.dp)) {
                items(showing, key = { it.id }) { row ->
                    ItemRow(
                        row = row,
                        onOpen = { onOpenItem(row.id) },
                        onToggleSave = { viewModel.toggleSave(row.id, !row.isReadLater) },
                        onLongPress = { moving = row },
                    )
                }
            }
        }
    }

    if (showCreate) {
        NameDialog(title = "New collection", initial = "", confirmLabel = "Create", onConfirm = { viewModel.createCollection(it); showCreate = false }, onDismiss = { showCreate = false })
    }
    renaming?.let { (id, name) ->
        NameDialog(title = "Rename collection", initial = name, confirmLabel = "Save", onConfirm = { viewModel.renameCollection(id, it); renaming = null }, onDismiss = { renaming = null })
    }
    moving?.let { row ->
        CollectionPickerSheet(
            collections = collections,
            currentCollectionId = null,
            onPick = { collectionId -> viewModel.moveItem(row.id, collectionId); moving = null },
            onCreate = { viewModel.createCollection(it) },
            onDismiss = { moving = null },
        )
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
