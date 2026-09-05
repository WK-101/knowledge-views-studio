package com.cairn.reader.ui

import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
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
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.prefs.ListViewMode
import com.cairn.reader.ui.components.FeedDrawerContent
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
fun CairnApp(
    onOpenItem: (String) -> Unit = {},
    onOpenNotebook: () -> Unit = {},
    onOpenWeb: (String) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenFeeds: () -> Unit = {},
    onOpenOffline: () -> Unit = {},
    onOpenDiscover: () -> Unit = {},
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var showAddFeed by remember { mutableStateOf(false) }
    val destinations = remember { Destination.entries }
    val current = destinations[selected]

    val inboxViewModel: InboxViewModel = hiltViewModel()
    val inboxViewMode by inboxViewModel.viewMode.collectAsStateWithLifecycle()
    val inboxState by inboxViewModel.state.collectAsStateWithLifecycle()
    val feeds by inboxViewModel.feeds.collectAsStateWithLifecycle()
    val selection by inboxViewModel.selection.collectAsStateWithLifecycle()
    val ttsState by inboxViewModel.tts.collectAsStateWithLifecycle()
    val audioState by inboxViewModel.audio.collectAsStateWithLifecycle()
    var showViewMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        inboxViewModel.snacks.collect { snack ->
            val result = snackbar.showSnackbar(
                message = snack.message,
                actionLabel = snack.actionLabel,
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) snack.onAction?.invoke()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                FeedDrawerContent(
                    totalUnread = inboxState.unread,
                    feeds = feeds,
                    selection = selection,
                    filter = inboxState.filter,
                    onAllArticles = { inboxViewModel.selectAll(); selected = 0; scope.launch { drawerState.close() } },
                    onStarred = { inboxViewModel.selectStarred(); selected = 0; scope.launch { drawerState.close() } },
                    onSelectFeed = { feed -> inboxViewModel.selectFeed(feed.sourceId, feed.title); selected = 0; scope.launch { drawerState.close() } },
                    onSelectFolder = { name -> inboxViewModel.selectFolder(name); selected = 0; scope.launch { drawerState.close() } },
                    onMarkFeedRead = { sourceId -> inboxViewModel.markFeedRead(sourceId) },
                    onMarkFolderRead = { name -> inboxViewModel.markFolderRead(name) },
                    onSaved = { selected = 1; scope.launch { drawerState.close() } },
                    onHighlights = { scope.launch { drawerState.close() }; onOpenNotebook() },
                    onSearch = { scope.launch { drawerState.close() }; onOpenSearch() },
                    onDiscover = { scope.launch { drawerState.close() }; onOpenDiscover() },
                    onManageFeeds = { scope.launch { drawerState.close() }; onOpenFeeds() },
                    onSettings = { selected = 2; scope.launch { drawerState.close() } },
                )
            }
        },
    ) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    val title = when {
                        current != Destination.Inbox -> current.label
                        inboxState.filter == InboxFilter.STARRED -> "Starred"
                        selection is com.cairn.reader.ui.inbox.DrawerSelection.Feed ->
                            (selection as com.cairn.reader.ui.inbox.DrawerSelection.Feed).title
                        selection is com.cairn.reader.ui.inbox.DrawerSelection.Folder ->
                            (selection as com.cairn.reader.ui.inbox.DrawerSelection.Folder).name
                        else -> "All Articles"
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Outlined.Menu, contentDescription = "Open navigation")
                    }
                },
                actions = {
                    if (current == Destination.Inbox) {
                        if (inboxState.items.isNotEmpty() && !ttsState.active) {
                            IconButton(onClick = { inboxViewModel.listenAll() }) {
                                Icon(Icons.Outlined.Headphones, contentDescription = "Listen to all")
                            }
                        }
                        if (inboxState.unread > 0) {
                            IconButton(onClick = { inboxViewModel.markAllRead() }) {
                                Icon(Icons.Outlined.DoneAll, contentDescription = "Mark all read")
                            }
                        }
                        Box {
                            IconButton(onClick = { showFilterMenu = true }) {
                                Icon(Icons.Outlined.FilterList, contentDescription = "Filter: ${inboxState.filter.label}")
                            }
                            DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                                MenuSectionLabel("SHOW")
                                InboxFilter.entries.forEach { f ->
                                    ViewModeItem(
                                        label = if (f == InboxFilter.UNREAD && inboxState.unread > 0) "Unread · ${inboxState.unread}" else f.label,
                                        icon = filterIcon(f),
                                        selected = inboxState.filter == f,
                                    ) { inboxViewModel.setFilter(f); showFilterMenu = false }
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { showViewMenu = true }) {
                                Icon(Icons.Outlined.ViewAgenda, contentDescription = "View and sort")
                            }
                            DropdownMenu(expanded = showViewMenu, onDismissRequest = { showViewMenu = false }) {
                                MenuSectionLabel("VIEW")
                                ViewModeItem("List", Icons.AutoMirrored.Outlined.ViewList, inboxViewMode == ListViewMode.LIST) {
                                    inboxViewModel.setViewMode(ListViewMode.LIST); showViewMenu = false
                                }
                                ViewModeItem("Cards", Icons.Outlined.ViewAgenda, inboxViewMode == ListViewMode.CARD) {
                                    inboxViewModel.setViewMode(ListViewMode.CARD); showViewMenu = false
                                }
                                ViewModeItem("Magazine", Icons.Outlined.ViewCarousel, inboxViewMode == ListViewMode.MAGAZINE) {
                                    inboxViewModel.setViewMode(ListViewMode.MAGAZINE); showViewMenu = false
                                }
                                androidx.compose.material3.HorizontalDivider()
                                MenuSectionLabel("SORT")
                                com.cairn.reader.ui.inbox.InboxSort.entries.forEach { s ->
                                    ViewModeItem(s.label, Icons.Outlined.SwapVert, inboxState.sort == s) {
                                        inboxViewModel.setSort(s); showViewMenu = false
                                    }
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Column {
                if (ttsState.active) {
                    com.cairn.reader.ui.components.ListenBar(
                        state = ttsState,
                        onPlayPause = inboxViewModel::listenToggle,
                        onStop = inboxViewModel::listenStop,
                        onSpeed = inboxViewModel::listenSpeed,
                        onPrev = inboxViewModel::listenPrev,
                        onNext = inboxViewModel::listenNext,
                    )
                }
                if (audioState.active) {
                    com.cairn.reader.ui.components.AudioBar(
                        state = audioState,
                        onPlayPause = inboxViewModel::audioToggle,
                        onBack = { inboxViewModel.audioSeek(-15_000) },
                        onForward = { inboxViewModel.audioSeek(30_000) },
                        onStop = inboxViewModel::audioStop,
                    )
                }
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.height(64.dp),
                ) {
                    destinations.forEachIndexed { index, dest ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                            alwaysShowLabel = false,
                        )
                    }
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
                Destination.Inbox -> InboxScreen(padding, inboxViewModel, onOpenItem, onOpenWeb, inboxViewMode)
                Destination.Library -> LibraryScreen(padding, onOpenItem, onOpenHighlights = onOpenNotebook)
                Destination.Settings -> SettingsScreen(padding, onOpenNotebook = onOpenNotebook, onOpenOffline = onOpenOffline)
            }
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
    onOpenWeb: (String) -> Unit,
    viewMode: ListViewMode,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val (rightAction, leftAction) = viewModel.swipeActions.collectAsStateWithLifecycle().value
    val compact by viewModel.compact.collectAsStateWithLifecycle()
    var sheetRow by remember { mutableStateOf<ItemListRow?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = padding.calculateTopPadding()),
    ) {
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
                            onLongPress = { sheetRow = row },
                            rightAction = rightAction,
                            leftAction = leftAction,
                            onAction = { action -> viewModel.swipe(row, action) },
                            mode = viewMode,
                            compact = compact,
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
            onOpenOriginal = { onOpenWeb(row.url) },
            onDismiss = { sheetRow = null },
            onSaveOffline = { viewModel.saveOffline(row.id) },
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

private fun filterIcon(filter: InboxFilter): ImageVector = when (filter) {
    InboxFilter.UNREAD -> Icons.Outlined.Circle
    InboxFilter.STARRED -> Icons.Outlined.StarBorder
    InboxFilter.SAVED -> Icons.Outlined.Bookmark
    InboxFilter.ALL -> Icons.Outlined.Inbox
}

@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun ViewModeItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick,
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
        InboxFilter.STARRED -> Triple(
            Icons.Outlined.Bookmark,
            "No starred stories",
            "Star a story from its menu to keep it here. Starred stories stay put no matter how you triage the rest.",
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
