package com.cairn.reader.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cairn.reader.ui.reader.ReaderScreen
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

/**
 * A top-level destination. Every destination is now a pane rendered in place inside the one shared
 * shell (same drawer, same bottom bar, same transitions) — no destination navigates away to a
 * detached full-screen route, so they all read as one app. Starred is the sole exception: it just
 * re-scopes the Inbox. The canonical order here is the order they appear in the bar; all are opt-in
 * from Settings except the defaults, and the bar shows up to six.
 */
private enum class Destination(val label: String, val icon: ImageVector, val isPane: Boolean = true) {
    Inbox("Inbox", Icons.Outlined.Inbox),
    Library("Library", Icons.AutoMirrored.Outlined.LibraryBooks),
    Discover("Discover", Icons.Outlined.Explore),
    Starred("Starred", Icons.Outlined.StarBorder, isPane = false),
    ReadLater("Read Later", Icons.Outlined.Bookmark),
    Highlights("Highlights", Icons.Outlined.FormatQuote),
    Feeds("Feeds", Icons.Outlined.RssFeed),
    Search("Search", Icons.Outlined.Search),
    Trash("Trash", Icons.Outlined.DeleteOutline),
    Offline("Offline", Icons.Outlined.OfflinePin),
    Settings("Settings", Icons.Outlined.Settings),
}

/** Destinations that render their own top app bar (hamburger + their controls); the shared shell
 *  top bar steps aside for these so there's exactly one bar. Inbox and Settings use the shell bar. */
private val OWN_TOP_BAR = setOf(
    Destination.Library, Destination.Discover, Destination.ReadLater, Destination.Highlights,
    Destination.Feeds, Destination.Search, Destination.Trash, Destination.Offline,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CairnApp(
    onOpenItem: (String) -> Unit = {},
    onOpenWeb: (String) -> Unit = {},
    onTeach: (String) -> Unit = {},
) {
    var showAddFeed by remember { mutableStateOf(false) }
    var manageFeed by remember { mutableStateOf<com.cairn.reader.data.db.SourceEntity?>(null) }
    val appViewModel: AppViewModel = hiltViewModel()
    val appPrefs by appViewModel.preferences.collectAsStateWithLifecycle()
    // The bar shows the user's chosen subset, in a fixed canonical order; never empty. Capped at
    // six so the bar stays legible even if the user enables everything.
    val tabs = remember(appPrefs.bottomTabs, appPrefs.bottomTabsOrder) {
        val members = appPrefs.bottomTabs
        // Honour the user's chosen order; any enabled tab missing from the order (e.g. just added)
        // falls in at the end in the app's canonical order.
        val orderedNames = appPrefs.bottomTabsOrder.filter { it in members } +
            Destination.entries.map { it.name }.filter { it in members && it !in appPrefs.bottomTabsOrder }
        orderedNames.mapNotNull { n -> Destination.entries.firstOrNull { it.name == n } }
            .ifEmpty { listOf(Destination.Inbox) }.take(6)
    }
    var currentName by rememberSaveable { mutableStateOf(Destination.Inbox.name) }
    // current is always a pane; the only non-pane (Starred) just re-scopes the Inbox.
    val current = Destination.entries.firstOrNull { it.name == currentName && it.isPane } ?: Destination.Inbox

    // On wide screens (tablets, unfolded foldables) show list + reader side by side.
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    val detailNav = rememberNavController()

    val inboxViewModel: InboxViewModel = hiltViewModel()
    val inboxViewMode by inboxViewModel.viewMode.collectAsStateWithLifecycle()
    val inboxState by inboxViewModel.state.collectAsStateWithLifecycle()
    val feeds by inboxViewModel.feeds.collectAsStateWithLifecycle()
    val selection by inboxViewModel.selection.collectAsStateWithLifecycle()
    val trashCount by inboxViewModel.trashCount.collectAsStateWithLifecycle()
    val ttsState by inboxViewModel.tts.collectAsStateWithLifecycle()
    val audioState by inboxViewModel.audio.collectAsStateWithLifecycle()
    var showViewMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var showMarkMenu by remember { mutableStateOf(false) }
    var inboxSearchOpen by remember { mutableStateOf(false) }
    val inboxQuery by inboxViewModel.query.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // The single navigation primitive: switch to a pane and close the drawer.
    val goTo: (Destination) -> Unit = { dest -> currentName = dest.name; scope.launch { drawerState.close() } }
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    LaunchedEffect(Unit) {
        inboxViewModel.snacks.collect { snack ->
            val result = snackbar.showSnackbar(
                message = snack.message,
                actionLabel = snack.actionLabel,
                withDismissAction = true,
                // Material 3 makes an action snackbar Indefinite by default (it would never
                // auto-dismiss). Force a finite duration so the Undo bar always goes away —
                // a bit longer when there's an action so there's time to tap Undo.
                duration = if (snack.actionLabel != null) androidx.compose.material3.SnackbarDuration.Long
                           else androidx.compose.material3.SnackbarDuration.Short,
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
                    onAllArticles = { inboxViewModel.selectAll(); goTo(Destination.Inbox) },
                    onStarred = { inboxViewModel.selectStarred(); goTo(Destination.Inbox) },
                    onSelectFeed = { feed -> inboxViewModel.selectFeed(feed.sourceId, feed.title); goTo(Destination.Inbox) },
                    onSelectFolder = { name -> inboxViewModel.selectFolder(name); goTo(Destination.Inbox) },
                    onMarkFeedRead = { sourceId -> inboxViewModel.markFeedRead(sourceId) },
                    onMarkFolderRead = { name -> inboxViewModel.markFolderRead(name) },
                    onManageFeed = { feed -> scope.launch { drawerState.close() }; inboxViewModel.loadSource(feed.sourceId) { src -> manageFeed = src } },
                    onUnsubscribe = { feed -> inboxViewModel.unsubscribe(feed.sourceId) },
                    onSaved = { goTo(Destination.Library) },
                    onReadLater = { goTo(Destination.ReadLater) },
                    onHighlights = { goTo(Destination.Highlights) },
                    onSearch = { goTo(Destination.Search) },
                    onDiscover = { goTo(Destination.Discover) },
                    onManageFeeds = { goTo(Destination.Feeds) },
                    onTrash = { goTo(Destination.Trash) },
                    trashCount = trashCount,
                    onSettings = { goTo(Destination.Settings) },
                )
            }
        },
    ) {
    Scaffold(
        topBar = topBar@{
            // Panes that carry their own top app bar (Library, Discover, Read Later, Highlights,
            // Feeds, Search, Trash, Offline) render it themselves; the shared bar steps aside so
            // there is exactly one. Inbox and Settings use this shared bar.
            if (current in OWN_TOP_BAR) return@topBar
            CenterAlignedTopAppBar(
                title = {
                    if (current == Destination.Inbox && inboxSearchOpen) {
                        com.cairn.reader.ui.components.CairnSearchField(
                            value = inboxQuery,
                            onValueChange = inboxViewModel::setInboxQuery,
                            placeholder = "Search these entries",
                            autofocus = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
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
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Outlined.Menu, contentDescription = "Open navigation")
                    }
                },
                actions = {
                    if (current == Destination.Inbox) {
                        if (inboxSearchOpen) {
                            IconButton(onClick = { inboxViewModel.setInboxQuery(""); inboxSearchOpen = false }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Close search")
                            }
                        } else {
                            IconButton(onClick = { inboxSearchOpen = true }) {
                                Icon(Icons.Outlined.Search, contentDescription = "Search these entries")
                            }
                        }
                        if (appPrefs.ttsEnabled && inboxState.items.isNotEmpty() && !ttsState.active) {
                            IconButton(onClick = { inboxViewModel.listenAll() }) {
                                Icon(Icons.Outlined.Headphones, contentDescription = "Listen to all")
                            }
                        }
                        if (inboxState.unread > 0) {
                            Box {
                                IconButton(onClick = { showMarkMenu = true }) {
                                    Icon(Icons.Outlined.DoneAll, contentDescription = "Mark read")
                                }
                                DropdownMenu(expanded = showMarkMenu, onDismissRequest = { showMarkMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Mark all read") },
                                        onClick = { inboxViewModel.markAllRead(); showMarkMenu = false },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Older than 7 days") },
                                        onClick = { inboxViewModel.markOlderThan7dRead(); showMarkMenu = false },
                                    )
                                }
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
                    tonalElevation = 0.dp,
                    modifier = Modifier.height(56.dp),
                ) {
                    tabs.forEach { dest ->
                        val selected = when {
                            dest.isPane -> current == dest && currentName == dest.name
                            dest == Destination.Starred ->
                                currentName == Destination.Inbox.name && inboxState.filter == InboxFilter.STARRED
                            else -> false
                        }
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (dest == Destination.Starred) { inboxViewModel.selectStarred(); goTo(Destination.Inbox) }
                                else goTo(dest)
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label, modifier = Modifier.size(22.dp)) },
                            label = { Text(dest.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                            // Always show labels so the icon never shifts up/down as selection changes
                            // (the jump when a label appears only on the selected tab looks unpolished).
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (current == Destination.Inbox) {
                FloatingActionButton(onClick = { showAddFeed = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add feed")
                }
            }
        },
    ) { padding ->
        val renderDest: @Composable (Destination, (String) -> Unit) -> Unit = { dest, open ->
            when (dest) {
                Destination.Library -> LibraryScreen(padding, open, onOpenHighlights = { goTo(Destination.Highlights) }, onOpenDrawer = openDrawer)
                Destination.Discover -> com.cairn.reader.ui.discover.DiscoverContent(padding, onOpenDrawer = openDrawer)
                Destination.ReadLater -> com.cairn.reader.ui.readlater.ReadLaterScreen(padding, onOpenItem = open, onOpenDrawer = openDrawer)
                Destination.Highlights -> com.cairn.reader.ui.notebook.NotebookScreen(padding, onOpenItem = open, onOpenDrawer = openDrawer)
                Destination.Feeds -> com.cairn.reader.ui.feeds.FeedsScreen(padding, onOpenWeb = onOpenWeb, onTeach = onTeach, onOpenDrawer = openDrawer)
                Destination.Search -> com.cairn.reader.ui.search.SearchScreen(padding, onOpenItem = open, onOpenWeb = onOpenWeb, onOpenDrawer = openDrawer)
                Destination.Trash -> com.cairn.reader.ui.trash.TrashScreen(padding, onOpenItem = open, onOpenDrawer = openDrawer)
                Destination.Offline -> com.cairn.reader.ui.settings.OfflineScreen(padding, onOpenItem = open, onOpenDrawer = openDrawer)
                Destination.Settings -> SettingsScreen(padding, onOpenNotebook = { goTo(Destination.Highlights) }, onOpenOffline = { goTo(Destination.Offline) })
                // Inbox and any non-pane fallthrough render the Inbox.
                else -> InboxScreen(padding, inboxViewModel, open, onOpenWeb, inboxViewMode)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(400.dp)) {
                    renderDest(current) { id -> detailNav.navigate("reader/$id") }
                }
                VerticalDivider()
                Box(Modifier.weight(1f)) {
                    NavHost(detailNav, startDestination = "detail_empty") {
                        composable("detail_empty") {
                            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                                Text("Select an article to read", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        composable(
                            "reader/{itemId}",
                            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
                        ) {
                            ReaderScreen(
                                onBack = { detailNav.popBackStack() },
                                onOpenWeb = onOpenWeb,
                                onOpenItem = { neighbor ->
                                    detailNav.navigate("reader/$neighbor") {
                                        popUpTo("reader/{itemId}") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                    }
                }
            }
        } else {
            Crossfade(targetState = current, label = "destination") { dest ->
                renderDest(dest, onOpenItem)
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

    manageFeed?.let { source ->
        val manageFolders by inboxViewModel.folders.collectAsStateWithLifecycle()
        com.cairn.reader.ui.components.FeedSettingsSheet(
            source = source,
            folders = manageFolders,
            onRename = { inboxViewModel.renameFeed(source.id, it) },
            onFolder = { inboxViewModel.setFeedFolder(source.id, it) },
            onFullText = { inboxViewModel.setFeedFullText(source.id, it) },
            onNotify = { inboxViewModel.setFeedNotify(source.id, it) },
            onPodcast = { inboxViewModel.setFeedPodcast(source.id, it) },
            onFeedUrl = { inboxViewModel.setFeedUrl(source.id, it) },
            onOpenIn = { inboxViewModel.setFeedOpenIn(source.id, it) },
            onMaxItems = { inboxViewModel.setFeedMaxItems(source.id, it) },
            onOpenSite = { source.siteUrl?.let(onOpenWeb) },
            onRemove = { inboxViewModel.unsubscribe(source.id); manageFeed = null },
            onDismiss = { manageFeed = null },
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
    val swipeCfg by viewModel.swipeActions.collectAsStateWithLifecycle()
    val compact by viewModel.compact.collectAsStateWithLifecycle()
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val picked by viewModel.picked.collectAsStateWithLifecycle()
    val markReadOnScroll by viewModel.markReadOnScroll.collectAsStateWithLifecycle()
    val selecting = picked.isNotEmpty()
    var sheetRow by remember { mutableStateOf<ItemListRow?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Mark-as-read-on-scroll: as items pass above the top of the list, mark them read (no undo
    // spam). LazyColumn's key-based anchoring keeps the visible content from jumping when read
    // items drop out of the Unread lens.
    if (markReadOnScroll) {
        LaunchedEffect(listState, state.items) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .collect { first ->
                    if (first > 0) {
                        val toMark = state.items.take(first).filter { !it.isRead }.map { it.id }
                        if (toMark.isNotEmpty()) viewModel.markReadSilent(toMark)
                    }
                }
        }
    }

    // A swipe action that needs UI context (share / open in browser) is handled here; the rest
    // are pure data changes the ViewModel owns.
    fun onSwipe(row: ItemListRow, action: com.cairn.reader.data.prefs.SwipeAction) {
        when (action) {
            com.cairn.reader.data.prefs.SwipeAction.OPEN_ORIGINAL -> onOpenWeb(row.url)
            com.cairn.reader.data.prefs.SwipeAction.SHARE -> {
                val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, row.url)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, row.title)
                }
                runCatching { context.startActivity(android.content.Intent.createChooser(share, null)) }
            }
            else -> viewModel.swipe(row, action)
        }
    }

    androidx.activity.compose.BackHandler(enabled = selecting) { viewModel.clearPicks() }

    // Ordered folders (with summed unread) for the quick folder switcher.
    val folders = remember(feeds) {
        feeds.filter { !it.folder.isNullOrBlank() }
            .groupBy { it.folder!! }
            .map { (name, fs) -> name to fs.sumOf { it.unread } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = padding.calculateTopPadding()),
    ) {
        if (selecting) {
            com.cairn.reader.ui.components.SelectionActionBar(
                count = picked.size,
                onClose = { viewModel.clearPicks() },
                onSelectAll = { viewModel.pickAll() },
            ) {
                IconButton(onClick = { viewModel.markPickedRead(true) }) {
                    Icon(Icons.Outlined.MarkEmailRead, contentDescription = "Mark read")
                }
                IconButton(onClick = { viewModel.starPicked(true) }) {
                    Icon(Icons.Outlined.StarBorder, contentDescription = "Star")
                }
                IconButton(onClick = { viewModel.savePicked(true) }) {
                    Icon(Icons.Outlined.Bookmark, contentDescription = "Save for later")
                }
                Box {
                    var more by remember { mutableStateOf(false) }
                    IconButton(onClick = { more = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More actions")
                    }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text("Mark unread") }, onClick = { more = false; viewModel.markPickedRead(false) })
                        DropdownMenuItem(text = { Text("Save offline") }, onClick = { more = false; viewModel.savePickedOffline() })
                        DropdownMenuItem(text = { Text("Archive") }, onClick = { more = false; viewModel.archivePicked() })
                        DropdownMenuItem(text = { Text("Move to Trash") }, onClick = { more = false; viewModel.deletePicked() })
                    }
                }
            }
        } else if (folders.isNotEmpty()) {
            val allSelected = selection is com.cairn.reader.ui.inbox.DrawerSelection.All
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = allSelected,
                    onClick = { viewModel.selectAll() },
                    label = { Text("All") },
                )
                folders.forEach { (name, unread) ->
                    val sel = selection.let { it is com.cairn.reader.ui.inbox.DrawerSelection.Folder && it.name == name }
                    FilterChip(
                        selected = sel,
                        onClick = { viewModel.selectFolder(name) },
                        label = { Text(if (unread > 0) "$name · $unread" else name) },
                    )
                }
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
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 2.dp, bottom = padding.calculateBottomPadding() + 96.dp),
                ) {
                    items(state.items, key = { it.id }) { row ->
                        SwipeableItemRow(
                            row = row,
                            onOpen = {
                                if (selecting) viewModel.togglePick(row.id) else {
                                    com.cairn.reader.ui.reader.ReaderQueue.set(state.items.map { it.id })
                                    onOpenItem(row.id)
                                }
                            },
                            onLongPress = { if (selecting) viewModel.togglePick(row.id) else sheetRow = row },
                            selected = row.id in picked,
                            swipeEnabled = !selecting,
                            rightHalf = swipeCfg.rightHalf,
                            rightFull = swipeCfg.rightFull,
                            leftHalf = swipeCfg.leftHalf,
                            leftFull = swipeCfg.leftFull,
                            onAction = { action -> onSwipe(row, action) },
                            mode = viewMode,
                            compact = compact,
                            onOpenSource = { sid -> viewModel.selectFeed(sid, row.sourceTitle ?: row.siteName ?: "Feed") },
                        )
                        if (viewMode != ListViewMode.MAGAZINE) {
                            androidx.compose.material3.HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                thickness = 0.6.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
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
            onMarkAbove = { viewModel.markAboveRead(row) },
            onMarkBelow = { viewModel.markBelowRead(row) },
            onDelete = { viewModel.delete(row.id) },
            onSelect = { viewModel.togglePick(row.id) },
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
