package com.cairn.reader.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.ui.components.ItemActionSheet
import com.cairn.reader.ui.components.SwipeableItemRow
import com.cairn.reader.ui.inbox.InboxFilter
import com.cairn.reader.ui.inbox.InboxViewModel
import com.cairn.reader.ui.library.LibraryScreen
import com.cairn.reader.ui.settings.SettingsScreen

private enum class Destination(val label: String, val icon: ImageVector) {
    Inbox("Inbox", Icons.Outlined.Inbox),
    Library("Library", Icons.AutoMirrored.Outlined.LibraryBooks),
    Settings("Settings", Icons.Outlined.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CairnApp(onOpenItem: (String) -> Unit = {}) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var showAddFeed by remember { mutableStateOf(false) }
    val destinations = remember { Destination.entries }
    val current = destinations[selected]

    val inboxViewModel: InboxViewModel = hiltViewModel()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        inboxViewModel.messages.collect { message ->
            if (message.startsWith(InboxViewModel.ARCHIVE_UNDO_MARKER)) {
                val id = message.removePrefix(InboxViewModel.ARCHIVE_UNDO_MARKER)
                val result = snackbar.showSnackbar("Archived", actionLabel = "Undo", withDismissAction = true)
                if (result == SnackbarResult.ActionPerformed) inboxViewModel.unarchive(id)
            } else {
                snackbar.showSnackbar(message)
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(current.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                destinations.forEachIndexed { index, dest ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                        alwaysShowLabel = false,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (current == Destination.Inbox) {
                ExtendedFloatingActionButton(
                    onClick = { showAddFeed = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add feed") },
                )
            }
        },
    ) { padding ->
        Crossfade(targetState = current, label = "destination") { dest ->
            when (dest) {
                Destination.Inbox -> InboxScreen(padding, inboxViewModel, onOpenItem)
                Destination.Library -> LibraryScreen(padding, onOpenItem)
                Destination.Settings -> SettingsScreen(padding)
            }
        }
    }

    if (showAddFeed) {
        AddFeedDialog(
            onDismiss = { showAddFeed = false },
            onAdd = { url ->
                inboxViewModel.addFeed(url)
                showAddFeed = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxScreen(
    padding: PaddingValues,
    viewModel: InboxViewModel,
    onOpenItem: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var sheetRow by remember { mutableStateOf<ItemListRow?>(null) }

    fun openOriginal(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = padding.calculateTopPadding()),
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            val filters = InboxFilter.entries
            filters.forEachIndexed { index, filter ->
                SegmentedButton(
                    selected = filter == state.filter,
                    onClick = { viewModel.setFilter(filter) },
                    shape = SegmentedButtonDefaults.itemShape(index, filters.size),
                    label = { Text(if (filter == InboxFilter.UNREAD && state.unread > 0) "Unread · ${state.unread}" else filter.label) },
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (!state.loading && state.items.isEmpty()) {
                EmptyState(state.filter)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 2.dp, bottom = padding.calculateBottomPadding() + 96.dp),
                ) {
                    items(state.items, key = { it.id }) { row ->
                        SwipeableItemRow(
                            row = row,
                            onOpen = { onOpenItem(row.id) },
                            onToggleSave = { viewModel.toggleSave(row.id, !row.isReadLater) },
                            onArchive = { viewModel.archive(row.id) },
                            onLongPress = { sheetRow = row },
                        )
                    }
                }
            }
        }
    }

    sheetRow?.let { row ->
        ItemActionSheet(
            row = row,
            onMarkRead = { read -> viewModel.markRead(row.id, read) },
            onToggleStar = { starred -> viewModel.toggleStar(row.id, starred) },
            onToggleSave = { save -> viewModel.toggleSave(row.id, save) },
            onArchive = { viewModel.archive(row.id) },
            onOpenOriginal = { openOriginal(row.url) },
            onDismiss = { sheetRow = null },
        )
    }
}

@Composable
private fun AddFeedDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a feed") },
        text = {
            Column {
                Text(
                    "Paste a website or feed URL. Cairn will find the feed — including YouTube, Reddit, Substack, and more.",
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
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyState(filter: InboxFilter) {
    val (icon, title, body) = when (filter) {
        InboxFilter.UNREAD -> Triple(
            Icons.Outlined.Inbox,
            "You're all caught up",
            "New articles from your feeds land here. Tap Add feed, or share a link to Cairn, to get started.",
        )
        InboxFilter.SAVED -> Triple(
            Icons.Outlined.Bookmark,
            "Nothing saved yet",
            "Swipe a story right, or use its menu, to save it for later. Saved stories stay here until you're done.",
        )
        InboxFilter.ALL -> Triple(
            Icons.Outlined.Inbox,
            "Nothing here yet",
            "Add a feed or share a link to Cairn, and everything you collect will appear in this list.",
        )
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
