@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.cairn.reader.ui

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import com.cairn.reader.data.prefs.ListViewMode
import com.cairn.reader.ui.components.FeedDrawerContent
import com.cairn.reader.ui.components.SectionLabel
import com.cairn.reader.ui.components.SectionLabelVariant
import com.cairn.reader.ui.inbox.InboxFilter
import com.cairn.reader.ui.inbox.InboxScreen
import com.cairn.reader.ui.inbox.InboxViewModel
import com.cairn.reader.ui.library.LibraryScreen
import com.cairn.reader.ui.settings.SettingsScreen

/**
 * A top-level destination. Every destination is a pane rendered in place inside the one shared
 * shell (same drawer, same bottom bar, same transitions) — no destination navigates away to a
 * detached full-screen route, so they all read as one app. The canonical order here is the order
 * they appear in the bar; all are opt-in from Settings except the defaults, and the bar shows up to
 * six. (Starring is a per-entry property, not a surface: starred stories live in the Library and are
 * reachable via the drawer's Starred filter — there is no dedicated Star destination.)
 */
private enum class Destination(val label: String, val icon: ImageVector, shortLabel: String? = null) {
    Inbox("Inbox", Icons.Outlined.Inbox),
    Library("Library", Icons.AutoMirrored.Outlined.LibraryBooks),
    Discover("Discover", Icons.Outlined.Explore),
    Brief("Brief", Icons.Outlined.Newspaper),
    Triage("Triage", Icons.Outlined.Style),
    Review("Review", Icons.Outlined.School),
    ReadLater("Read Later", Icons.Outlined.Bookmark, shortLabel = "Later"),
    Highlights("Highlights", Icons.Outlined.FormatQuote, shortLabel = "Notes"),
    Feeds("Feeds", Icons.Outlined.RssFeed),
    Search("Search", Icons.Outlined.Search),
    Trash("Trash", Icons.Outlined.DeleteOutline),
    Offline("Offline", Icons.Outlined.OfflinePin),
    Rules("Rules", Icons.Outlined.Bolt),
    Insights("Insights", Icons.Outlined.Insights),
    DataForever("Your Data", Icons.Outlined.Shield, shortLabel = "Data"),
    Settings("Settings", Icons.Outlined.Settings);

    /** A compact label for the bottom nav bar, where six items must each fit on one line. */
    val short: String = shortLabel ?: label
}

/** Destinations that render their own top app bar (hamburger + their controls); the shared shell
 *  top bar steps aside for these so there's exactly one bar. Inbox and Settings use the shell bar. */
private val OWN_TOP_BAR = setOf(
    Destination.Library, Destination.Discover, Destination.ReadLater, Destination.Highlights,
    Destination.Feeds, Destination.Search, Destination.Trash, Destination.Offline, Destination.Rules, Destination.Insights,
    Destination.Brief, Destination.Triage, Destination.Review, Destination.DataForever,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CairnApp(
    onOpenItem: (String) -> Unit = {},
    onOpenWeb: (String) -> Unit = {},
    onTeach: (String) -> Unit = {},
    openBrief: Boolean = false,
    onBriefConsumed: () -> Unit = {},
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
            .ifEmpty { listOf(Destination.Inbox) }.take(6)  // matches the Settings cap of 6
    }
    var currentName by rememberSaveable { mutableStateOf(Destination.Inbox.name) }
    // A stale saved name (e.g. a tab removed in an update) falls back to the Inbox.
    val current = Destination.entries.firstOrNull { it.name == currentName } ?: Destination.Inbox

    // On wide screens (tablets, unfolded foldables) show list + reader side by side,
    // unless the user has asked to keep the single-column phone layout everywhere.
    val wide = !appPrefs.forceSingleColumn && LocalConfiguration.current.screenWidthDp >= 720
    val detailNav = rememberNavController()

    val inboxViewModel: InboxViewModel = hiltViewModel()
    // Live "due for review" count for the drawer badge (spaced-repetition recall).
    val reviewViewModel: com.cairn.reader.ui.review.ReviewViewModel = hiltViewModel()
    val dueReviewCount by reviewViewModel.dueCount.collectAsStateWithLifecycle()
    // Hoisted above the destination Crossfade so the Inbox keeps its scroll position across tab
    // switches and when returning from the reader (a fresh state inside the Crossfade would reset).
    val inboxListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val inboxViewMode by inboxViewModel.viewMode.collectAsStateWithLifecycle()
    val inboxState by inboxViewModel.state.collectAsStateWithLifecycle()
    val feeds by inboxViewModel.feeds.collectAsStateWithLifecycle()
    val allArticleCount by inboxViewModel.allCount.collectAsStateWithLifecycle()
    val selection by inboxViewModel.selection.collectAsStateWithLifecycle()
    val trashCount by inboxViewModel.trashCount.collectAsStateWithLifecycle()
    val ttsState by inboxViewModel.tts.collectAsStateWithLifecycle()
    val audioState by inboxViewModel.audio.collectAsStateWithLifecycle()
    var showViewMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var showMarkMenu by remember { mutableStateOf(false) }
    var inboxSearchOpen by remember { mutableStateOf(false) }
    // A one-shot deep-link into a Settings category (e.g. "Your data, forever" → Backup & restore).
    var pendingSettingsCategory by remember { mutableStateOf<String?>(null) }
    val inboxQuery by inboxViewModel.query.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // The single navigation primitive: switch to a pane and close the drawer.
    val goTo: (Destination) -> Unit = { dest -> currentName = dest.name; scope.launch { drawerState.close() } }
    // A daily-brief notification tap opens the Brief pane once.
    androidx.compose.runtime.LaunchedEffect(openBrief) {
        if (openBrief) { currentName = Destination.Brief.name; onBriefConsumed() }
    }
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    // System Back from any secondary pane (Discover, Feeds, Settings, …) returns to the Inbox
    // instead of falling through and exiting the app. Gated to when the drawer is closed so the
    // drawer keeps its own close-on-back; panes that register their own BackHandler (e.g. Library)
    // still take precedence because they compose deeper in the tree.
    androidx.activity.compose.BackHandler(enabled = current != Destination.Inbox && drawerState.isClosed) {
        currentName = Destination.Inbox.name
    }
    // Honour the user's chosen launch destination + default Inbox filter, once per cold start.
    var appliedStart by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(appPrefs.startDestination, appPrefs.startFilter) {
        if (appliedStart) return@LaunchedEffect
        val dest = Destination.entries.firstOrNull { it.name == appPrefs.startDestination }
        if (dest != null) currentName = dest.name
        appPrefs.startFilter.takeIf { it.isNotBlank() }
            ?.let { name -> runCatching { InboxFilter.valueOf(name) }.getOrNull() }
            ?.let { inboxViewModel.setFilter(it) }
        appliedStart = true
    }
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

    androidx.compose.runtime.CompositionLocalProvider(
        com.cairn.reader.ui.components.LocalListRowOptions provides com.cairn.reader.ui.components.ListRowOptions(
            showThumbnail = appPrefs.showThumbnail,
            showExcerpt = appPrefs.showExcerpt,
            showReadingTime = appPrefs.showReadingTime,
        ),
    ) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                FeedDrawerContent(
                    totalArticles = allArticleCount,
                    feeds = feeds,
                    selection = selection,
                    onAllArticles = { inboxViewModel.selectAll(); goTo(Destination.Inbox) },
                    onSelectFeed = { feed -> inboxViewModel.selectFeed(feed.sourceId, feed.title); goTo(Destination.Inbox) },
                    onSelectFolder = { name -> inboxViewModel.selectFolder(name); goTo(Destination.Inbox) },
                    onMarkFeedRead = { sourceId -> inboxViewModel.markFeedRead(sourceId) },
                    onMarkFolderRead = { name -> inboxViewModel.markFolderRead(name) },
                    onManageFeed = { feed -> scope.launch { drawerState.close() }; inboxViewModel.loadSource(feed.sourceId) { src -> manageFeed = src } },
                    onUnsubscribe = { feed -> inboxViewModel.unsubscribe(feed.sourceId) },
                    onSaved = { goTo(Destination.Library) },
                    onReadLater = { goTo(Destination.ReadLater) },
                    onHighlights = { goTo(Destination.Highlights) },
                    onBrief = { goTo(Destination.Brief) },
                    onTriage = { goTo(Destination.Triage) },
                    onReview = { goTo(Destination.Review) },
                    dueCount = dueReviewCount,
                    onSearch = { goTo(Destination.Search) },
                    onDiscover = { goTo(Destination.Discover) },
                    onManageFeeds = { goTo(Destination.Feeds) },
                    onTrash = { goTo(Destination.Trash) },
                    trashCount = trashCount,
                    onDataForever = { goTo(Destination.DataForever) },
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
            TopAppBar(
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
                    // When the Inbox is scoped to a single feed or folder (e.g. by tapping a source
                    // name on an entry), the nav icon becomes a Back arrow that clears the scope —
                    // otherwise there's no obvious way out of the feed view. Elsewhere it opens the drawer.
                    val scoped = current == Destination.Inbox && !inboxSearchOpen &&
                        (selection is com.cairn.reader.ui.inbox.DrawerSelection.Feed ||
                            selection is com.cairn.reader.ui.inbox.DrawerSelection.Folder)
                    if (scoped) {
                        IconButton(onClick = { inboxViewModel.selectAll() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    } else {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.open_navigation))
                        }
                    }
                },
                actions = {
                    if (current == Destination.Inbox) {
                        if (inboxSearchOpen) {
                            IconButton(onClick = { inboxViewModel.setInboxQuery(""); inboxSearchOpen = false }) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close_search))
                            }
                        } else {
                            IconButton(onClick = { inboxSearchOpen = true }) {
                                Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search_these_entries))
                            }
                        }
                        if (appPrefs.ttsEnabled && inboxState.items.isNotEmpty() && !ttsState.active) {
                            IconButton(onClick = { inboxViewModel.listenAll() }) {
                                Icon(Icons.Outlined.Headphones, contentDescription = stringResource(R.string.listen_to_all))
                            }
                        }
                        if (inboxState.unread > 0) {
                            Box {
                                IconButton(onClick = { showMarkMenu = true }) {
                                    Icon(Icons.Outlined.DoneAll, contentDescription = stringResource(R.string.mark_read))
                                }
                                DropdownMenu(expanded = showMarkMenu, onDismissRequest = { showMarkMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.mark_all_read)) },
                                        onClick = { inboxViewModel.markAllRead(); showMarkMenu = false },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.older_than_7_days)) },
                                        onClick = { inboxViewModel.markOlderThan7dRead(); showMarkMenu = false },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { showFilterMenu = true }) {
                                Icon(Icons.Outlined.FilterList, contentDescription = stringResource(R.string.filter_label, inboxState.filter.label))
                            }
                            DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                                SectionLabel(stringResource(R.string.show), SectionLabelVariant.Menu)
                                InboxFilter.entries.forEach { f ->
                                    ViewModeItem(
                                        label = if (f == InboxFilter.UNREAD && inboxState.unread > 0) stringResource(R.string.unread_count, inboxState.unread) else f.label,
                                        icon = filterIcon(f),
                                        selected = inboxState.filter == f,
                                    ) { inboxViewModel.setFilter(f); showFilterMenu = false }
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { showViewMenu = true }) {
                                Icon(Icons.Outlined.ViewAgenda, contentDescription = stringResource(R.string.view_and_sort))
                            }
                            DropdownMenu(expanded = showViewMenu, onDismissRequest = { showViewMenu = false }) {
                                SectionLabel(stringResource(R.string.view), SectionLabelVariant.Menu)
                                ViewModeItem(stringResource(R.string.view_mode_list), Icons.AutoMirrored.Outlined.ViewList, inboxViewMode == ListViewMode.LIST) {
                                    inboxViewModel.setViewMode(ListViewMode.LIST); showViewMenu = false
                                }
                                ViewModeItem(stringResource(R.string.view_mode_cards), Icons.Outlined.ViewAgenda, inboxViewMode == ListViewMode.CARD) {
                                    inboxViewModel.setViewMode(ListViewMode.CARD); showViewMenu = false
                                }
                                ViewModeItem(stringResource(R.string.view_mode_magazine), Icons.Outlined.ViewCarousel, inboxViewMode == ListViewMode.MAGAZINE) {
                                    inboxViewModel.setViewMode(ListViewMode.MAGAZINE); showViewMenu = false
                                }
                                androidx.compose.material3.HorizontalDivider()
                                SectionLabel(stringResource(R.string.sort), SectionLabelVariant.Menu)
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
                    modifier = Modifier.height(64.dp),
                ) {
                    tabs.forEach { dest ->
                        val selected = current == dest && currentName == dest.name
                        NavigationBarItem(
                            selected = selected,
                            onClick = { goTo(dest) },
                            icon = { Icon(dest.icon, contentDescription = dest.label, modifier = Modifier.size(22.dp)) },
                            label = {
                                Text(
                                    dest.short,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
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
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_feed))
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
                Destination.Rules -> com.cairn.reader.ui.rules.RulesScreen(padding, onOpenDrawer = openDrawer)
                Destination.Insights -> com.cairn.reader.ui.insights.InsightsScreen(padding, onOpenItem = open, onOpenDrawer = openDrawer)
                Destination.Brief -> com.cairn.reader.ui.brief.BriefScreen(padding, onOpenItem = open, onOpenDrawer = openDrawer)
                Destination.Triage -> com.cairn.reader.ui.triage.TriageScreen(padding, onOpenItem = open, onOpenDrawer = openDrawer)
                Destination.Review -> com.cairn.reader.ui.review.ReviewScreen(padding, onOpenDrawer = openDrawer)
                Destination.DataForever -> com.cairn.reader.ui.settings.DataForeverScreen(padding, onOpenDrawer = openDrawer, onOpenBackupSettings = { pendingSettingsCategory = com.cairn.reader.ui.settings.SettingsCategory.BACKUP.name; goTo(Destination.Settings) })
                Destination.Settings -> SettingsScreen(padding, onOpenNotebook = { goTo(Destination.Highlights) }, onOpenOffline = { goTo(Destination.Offline) }, onOpenRules = { goTo(Destination.Rules) }, onOpenInsights = { goTo(Destination.Insights) }, onOpenDataForever = { goTo(Destination.DataForever) }, initialCategory = pendingSettingsCategory, onCategoryConsumed = { pendingSettingsCategory = null })
                // Inbox and any non-pane fallthrough render the Inbox.
                else -> InboxScreen(padding, inboxViewModel, open, onOpenWeb, inboxViewMode, inboxListState)
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
                                Text(stringResource(R.string.select_an_article_to_read), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            val motionSpec = if (com.cairn.reader.ui.util.reduceMotion())
                androidx.compose.animation.core.snap<Float>()
            else androidx.compose.animation.core.tween(220)
            // Preserve each pane's scroll position and rememberSaveable UI state across tab switches
            // (a bare Crossfade disposes the outgoing pane and resets it). Each destination gets its
            // own state slot keyed by name.
            val paneStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            Crossfade(targetState = current, animationSpec = motionSpec, label = "destination") { dest ->
                paneStateHolder.SaveableStateProvider(dest.name) {
                    renderDest(dest, onOpenItem)
                }
            }
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
            onMuted = { inboxViewModel.setFeedMuted(source.id, it) },
            onSetPaused = { inboxViewModel.setFeedPaused(source.id, it) },
            onPodcast = { inboxViewModel.setFeedPodcast(source.id, it) },
            onFeedUrl = { inboxViewModel.setFeedUrl(source.id, it) },
            onOpenIn = { inboxViewModel.setFeedOpenIn(source.id, it) },
            onMaxItems = { inboxViewModel.setFeedMaxItems(source.id, it) },
            onOpenSite = { source.siteUrl?.let(onOpenWeb) },
            onVerify = { inboxViewModel.verifyFeed(source.id) },
            onRemove = { inboxViewModel.unsubscribe(source.id); manageFeed = null },
            onDismiss = { manageFeed = null },
        )
    }
}

@Composable
private fun AddFeedDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_a_feed)) },
        text = {
            Column {
                Text(stringResource(R.string.paste_a_website_or_feed_url),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.example_com)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun filterIcon(filter: InboxFilter): ImageVector = when (filter) {
    InboxFilter.UNREAD -> Icons.Outlined.Circle
    InboxFilter.STARRED -> Icons.Outlined.StarBorder
    InboxFilter.SAVED -> Icons.Outlined.Bookmark
    InboxFilter.ALL -> Icons.Outlined.Inbox
}

@Composable
private fun ViewModeItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick,
    )
}
