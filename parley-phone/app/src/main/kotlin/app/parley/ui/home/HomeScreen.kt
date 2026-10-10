package app.parley.ui.home

import app.parley.ui.people.ContactsLockButton
import app.parley.ui.Destination
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.R
import app.parley.RecentFilter
import app.parley.common.SettingsCategory
import app.parley.ui.discover.DiscoverRoutes
import app.parley.common.StartTab
import app.parley.common.homeLayout
import app.parley.common.ux.Tips
import app.parley.messaging.MessagingRoutes
import app.parley.ui.Routes
import app.parley.ui.activityViewModel
import app.parley.ui.calltime.NotificationHealthBanner
import app.parley.ui.situations.SituationChip
import app.parley.ui.calltime.ReturnToCallChip
import app.parley.ui.circle.CircleTab
import app.parley.ui.common.CoachMarkAnchor
import app.parley.ui.history.ClearHistoryMenuItem
import app.parley.ui.history.RecentsExportMenuItem
import app.parley.ui.history.HistoryRoutes
import app.parley.ui.history.RecentsLayoutMenuItem
import app.parley.ui.people.PeopleRoutes
import app.parley.ui.qr.QrRoutes
import app.parley.ui.ParleyScaffold
import app.parley.ui.Spacing
import androidx.compose.material.icons.automirrored.rounded.MergeType

/**
 * Home: one tab at a time under a shared header ([HomeHeader]), with a bottom bar on phones and a navigation rail on
 * wide screens. The bar's order and visible tabs come from Settings › Appearance › Navigation bar; a tab hidden
 * there still opens from links (ACTION_DIAL, missed calls…) and then shows in the bar until you leave it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: AppViewModel,
    tabRequest: NavEvent.Tab?,
    onTabRequestHandled: () -> Unit,
    initialTab: StartTab,
    open: (Destination) -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // Tablets, foldables and landscape: navigation rail instead of a bottom bar; with room, list and detail side by side.
    val window = rememberWindowLayout()
    val wide = window.rail
    val panes = homePanes()
    SideEffect {
        panes.twoPanes = window.listDetail
        panes.tab = tab
    }
    var searching by rememberSaveable { mutableStateOf(false) }
    var favoriteQuery by rememberSaveable { mutableStateOf("") }
    var keypadQuery by rememberSaveable { mutableStateOf("") }
    var circleQuery by rememberSaveable { mutableStateOf("") }
    val missed by vm.missedCount.collectAsStateWithLifecycle()
    val recents: RecentsViewModel = activityViewModel()
    val keypad: KeypadViewModel = activityViewModel()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    // Which tabs the bar shows and where the keypad and the favourites live.
    val layout = settings.homeLayout
    // The docked keypad's folded state, kept for the session (tab switches, rotation). It starts unfolded when
    // the keypad is where you asked Parley to open.
    var dockOpen by rememberSaveable { mutableStateOf(settings.startTab == StartTab.KEYPAD) }
    var reorderFavorites by remember { mutableStateOf(false) }

    fun closeSearch() {
        searching = false
        vm.contactQuery.value = ""
        vm.recall.everything.value = false
        recents.query.value = ""
        favoriteQuery = ""
        keypadQuery = ""
        circleQuery = ""
    }

    // The Contacts search's Filters chip shows while its search is open.
    LaunchedEffect(searching, tab) { vm.people.searchOpen.value = searching && tab == StartTab.CONTACTS }

    LaunchedEffect(tabRequest) {
        val r = tabRequest ?: return@LaunchedEffect
        // A request for a tab that another surface hosts opens that surface (tel:, ACTION_DIAL, shortcuts
        // and the headset's Call button open the docked keypad, unfolded).
        tab = layout.hostOf(r.tab)
        if (layout.opensDockedKeypad(r.tab)) {
            // The tab doesn't change, so an open Recents search would keep hiding the keypad and the number.
            if (searching) closeSearch()
            dockOpen = true
        }
        r.dial?.let { keypad.input.value = it }
        if (r.missedOnly) recents.filter.value = RecentFilter.MISSED
        onTabRequestHandled()
    }
    var lastTab by rememberSaveable { mutableStateOf(tab) }
    LaunchedEffect(tab) {
        if (tab == StartTab.RECENTS && missed > 0) vm.markMissedSeen()
        // A new tab starts without the previous tab's search (but rotation keeps an open search).
        if (tab != lastTab) {
            lastTab = tab
            if (searching) closeSearch()
        }
        if (tab != StartTab.CONTACTS) vm.selection.value = emptySet()
        scroll.state.contentOffset = 0f
    }
    // Switching an option on while its tab is open moves to the surface that now hosts it.
    LaunchedEffect(layout.absorbed) { layout.hostOf(tab).let { if (it != tab) tab = it } }
    BackHandler(enabled = selection.isNotEmpty()) { vm.selection.value = emptySet() }
    // Back closes the page beside a list only once nothing more pressing (a selection, a search) is waiting for it.
    val paneBack = selection.isEmpty() && !searching
    // Back folds the docked keypad first.
    BackHandler(enabled = tab == StartTab.RECENTS && layout.keypadDocked && dockOpen && !searching) { dockOpen = false }
    BackHandler(enabled = searching) { closeSearch() }

    val barTabs = layout.barTabs(tab)
    // With a single tab left there is nothing to switch between: no bar and no rail.
    val showBar = layout.showBar(tab)

    // The bulk Move to private's progress, unlock and outcome: on whichever tab, even once the selection is gone.
    PrivateMoveProgress(vm)
    ParleyScaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            if (tab == StartTab.CONTACTS && selection.isNotEmpty()) {
                SelectionBar(vm)
            } else {
                val query = when (tab) {
                    StartTab.CONTACTS -> vm.contactQuery.collectAsStateWithLifecycle().value
                    StartTab.RECENTS -> recents.query.collectAsStateWithLifecycle().value
                    StartTab.FAVORITES -> favoriteQuery
                    StartTab.KEYPAD -> keypadQuery
                    StartTab.CIRCLE -> circleQuery
                }
                HomeHeader(
                    title = tab.label,
                    searching = searching,
                    query = query,
                    searchHint = when (tab) {
                        StartTab.FAVORITES -> stringResource(R.string.home_search_favorites)
                        StartTab.RECENTS -> stringResource(R.string.home_search_recents)
                        StartTab.CONTACTS -> stringResource(R.string.home_search_contacts)
                        StartTab.KEYPAD -> stringResource(R.string.home_search_keypad)
                        StartTab.CIRCLE -> stringResource(R.string.circle_search)
                    },
                    onQuery = { q ->
                        when (tab) {
                            StartTab.CONTACTS -> vm.contactQuery.value = q
                            StartTab.RECENTS -> recents.query.value = q
                            StartTab.FAVORITES -> favoriteQuery = q
                            StartTab.KEYPAD -> keypadQuery = q
                            StartTab.CIRCLE -> circleQuery = q
                        }
                    },
                    onSearch = { on -> if (on) searching = true else closeSearch() },
                    scrollBehavior = scroll,
                    actions = { TabActions(vm, tab, settings.appLock, open) },
                    menu = { close -> TabMenu(vm, tab, settings.appLock, open, close, onReorderFavorites = { reorderFavorites = true }) },
                )
            }
        },
        bottomBar = {
            Column(if (wide) Modifier.navigationBarsPadding() else Modifier) {
                NotificationHealthBanner(vm)
                // The Situation on now, with one-tap Turn off.
                SituationChip(vm, open)
                ReturnToCallChip()
                if (!wide && showBar) NavigationBar {
                    barTabs.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { TabIcon(t, missed) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
        // Beside a detail pane the add button belongs to the list, not the page next to it.
        floatingActionButton = { if (!window.listDetail) AddButton(vm, open, tab == StartTab.CONTACTS && selection.isEmpty() && !searching) },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (wide && showBar) {
                // The Scaffold already pads for the system bars and the header.
                NavigationRail(windowInsets = WindowInsets(0)) {
                    Spacer(Modifier.weight(1f))
                    barTabs.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { TabIcon(t, missed) },
                            label = { Text(t.label) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            Column(Modifier.weight(1f).fillMaxSize()) {
                // "What's new" once after an update, as a card above the tab (the layout itself never changes).
                if (!searching && selection.isEmpty()) WhatsNewCard(vm, open)
                Box(Modifier.weight(1f).fillMaxSize()) {
                    AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                        when (t) {
                            StartTab.FAVORITES -> Centred(window.contentMaxDp) { FavoritesTab(vm, open, favoriteQuery, onClearQuery = { favoriteQuery = "" }) }
                            StartTab.RECENTS -> HomeListDetail(vm, t, window, open, backEnabled = paneBack && !(layout.keypadDocked && dockOpen)) { o ->
                                if (layout.keypadDocked) CallsSurface(vm, o, searching, dockOpen) { dockOpen = it } else RecentsTab(vm, o)
                            }
                            StartTab.CONTACTS -> HomeListDetail(
                                vm, t, window, open, backEnabled = paneBack,
                                overlay = { Box(Modifier.align(Alignment.BottomEnd).padding(Spacing.l)) { AddButton(vm, open, paneBack) } },
                            ) { o -> ContactsTab(vm, o, onReorderFavorites = { reorderFavorites = true }) }
                            StartTab.KEYPAD -> Centred(window.keypadMaxDp) { KeypadTab(vm, open, keypadQuery.takeIf { searching }) }
                            StartTab.CIRCLE -> CircleTab(vm, open, circleQuery)
                        }
                    }
                }
            }
        }
    }
    if (reorderFavorites) ReorderFavoritesSheet(vm) { reorderFavorites = false }
}

@Composable
private fun TabIcon(t: StartTab, missed: Int) {
    if (t == StartTab.RECENTS && missed > 0) {
        BadgedBox(badge = { Badge { Text(missed.toString()) } }) { Icon(t.icon, pluralStringResource(R.plurals.missed_title_many, missed, missed)) }
    } else {
        Icon(t.icon, null)
    }
}

/** Icons next to the search icon: the tab's most used actions. */
@Composable
private fun TabActions(vm: AppViewModel, tab: StartTab, appLock: Boolean, open: (Destination) -> Unit) {
    when (tab) {
        // Call insights is in ⋮; with the keypad docked here, its Speed dial comes along.
        StartTab.RECENTS -> if (vm.settings.collectAsStateWithLifecycle().value.homeLayout.keypadDocked) {
            IconButton({ open(Routes.SpeedDial) }) { Icon(Icons.Rounded.Speed, stringResource(R.string.home_speed_dial)) }
        }
        StartTab.CONTACTS -> {
            // Scan QR is in the add button's menu; Labels is the chip row's. One lock: private contacts, Parley, or a
            // small menu with both.
            ContactsLockButton(vm, appLock)
        }
        StartTab.KEYPAD -> IconButton({ open(Routes.SpeedDial) }) { Icon(Icons.Rounded.Speed, stringResource(R.string.home_speed_dial)) }
        StartTab.FAVORITES, StartTab.CIRCLE -> Unit
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem({ Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

/**
 * "More options": at most seven items, the tab's own first, then Tools (the one hub: everything Parley does, by what
 * you want done) and Settings. Settings pages aren't repeated here.
 */
@Composable
private fun ColumnScope.TabMenu(vm: AppViewModel, tab: StartTab, appLock: Boolean, open: (Destination) -> Unit, close: () -> Unit, onReorderFavorites: () -> Unit = {}) {
    fun go(route: Destination) { close(); open(route) }
    val settings = vm.settings.collectAsStateWithLifecycle().value
    val layout = settings.homeLayout
    when (tab) {
        StartTab.RECENTS -> {
            // Right after a spam call: the rules that decide which calls ring.
            MenuItem(stringResource(R.string.set_blocking_title), Icons.Rounded.Block) { go(Routes.Blocking) }
            MenuItem(stringResource(R.string.hist_insights_action), Icons.Rounded.Insights) { go(HistoryRoutes.Insights()) }
            // Layout, style, what a tap does and the colours' legend, in one dialog.
            RecentsLayoutMenuItem(close)
            RecentsExportMenuItem(close)
            ClearHistoryMenuItem(close)
        }
        StartTab.CONTACTS -> {
            MenuItem(stringResource(R.string.home_select_all), Icons.Rounded.SelectAll) {
                close()
                vm.selection.value = vm.people.filtered.value.orEmpty().map { it.id }.toSet()
            }
            // Name, recently added, most called or company: kept in the list, remembered. ("Add several" is on the add button.)
            MenuItem(stringResource(R.string.cs_sort_menu), Icons.AutoMirrored.Rounded.Sort) { close(); vm.people.sortSheet.value = true }
            MenuItem(stringResource(R.string.home_duplicates), Icons.AutoMirrored.Rounded.MergeType) { go(Routes.Duplicates) }
            // Favourites shown in Contacts are reordered from here too.
            if (layout.favoritesInContacts) MenuItem(stringResource(R.string.home_reorder_title), Icons.Rounded.Star) { close(); onReorderFavorites() }
            // Archived contacts are out of the list: this is where they are, once there are some.
            val archived = vm.c.archive.cards.collectAsStateWithLifecycle().value
            // Archived private contacts count only while private contacts may show.
            val privateArchived = app.parley.ui.people.archive.privateArchived(vm)
            if (archived.isNotEmpty() || privateArchived.isNotEmpty()) {
                MenuItem(stringResource(R.string.archive_title_screen), Icons.Rounded.Archive) { go(PeopleRoutes.Archived) }
            }
        }
        StartTab.KEYPAD -> Unit
        StartTab.CIRCLE -> {
            // Contacts › Circle: its own setting, and a link to how keep-in-touch reminders arrive (on Reminders).
            // Lands on the Circle's own group of Settings › Contacts (its "Log this?" row, not folded under Advanced).
            MenuItem(stringResource(R.string.circle_settings), Icons.Rounded.Tune) { go(Routes.settingsPage(SettingsCategory.CONTACTS, "log_prompts")) }
        }
        // "Who's in…" is the Contacts search's city chip now, not an item of these menus.
        StartTab.FAVORITES -> Unit
    }
    if (tab == StartTab.RECENTS || tab == StartTab.CONTACTS || tab == StartTab.CIRCLE) {
        HorizontalDivider()
    }
    MenuItem(stringResource(R.string.home_tools), Icons.Rounded.Handyman) { go(DiscoverRoutes.Capabilities) }
    MenuItem(stringResource(R.string.home_settings), Icons.Rounded.Settings) { go(Routes.Settings) }
}

/**
 * Contacts' add button: a tap opens its menu (New contact, Scan QR code, Add several numbers), the ways to add
 * someone in one place. New contact is private while only private contacts show.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddContactFab(vm: AppViewModel, open: (Destination) -> Unit, visible: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Hidden for a selection or a search: it comes back closed.
    LaunchedEffect(visible) { if (!visible) expanded = false }
    BackHandler(expanded) { expanded = false }
    fun go(d: Destination) { expanded = false; open(d) }
    val addLabel = stringResource(R.string.home_add_contact_menu)
    val state = stringResource(if (expanded) R.string.home_add_menu_open else R.string.home_add_menu_closed)
    val action = stringResource(if (expanded) R.string.home_add_menu_hide else R.string.home_add_menu_show)
    FloatingActionButtonMenu(
        expanded = expanded,
        button = {
            CoachMarkAnchor(Tips.CONTACTS_ADD_MENU, stringResource(R.string.qs_tip_contacts)) {
                ToggleFloatingActionButton(
                    checked = expanded, onCheckedChange = { expanded = it },
                    // A button that opens a menu, not an on/off switch: TalkBack says "Add a contact, menu closed".
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = addLabel
                        stateDescription = state
                        role = Role.Button
                        onClick(label = action) { expanded = !expanded; true }
                    },
                ) {
                    Icon(if (checkedProgress > 0.5f) Icons.Rounded.Close else Icons.Rounded.PersonAdd, null)
                }
            }
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = { go(if (vm.showVault.value) Routes.edit(vault = 0) else Routes.edit()) },
            text = { Text(stringResource(R.string.home_create_contact)) }, icon = { Icon(Icons.Rounded.PersonAdd, null) },
        )
        FloatingActionButtonMenuItem(
            onClick = { go(QrRoutes.Scan) }, text = { Text(stringResource(R.string.qs_menu)) }, icon = { Icon(Icons.Rounded.QrCodeScanner, null) },
        )
        FloatingActionButtonMenuItem(
            onClick = { go(MessagingRoutes.BulkAdd) }, text = { Text(stringResource(R.string.home_add_several)) },
            icon = { Icon(Icons.Rounded.GroupAdd, null) },
        )
    }
}

/** Contacts' add button while it can be used ([visible]: Contacts is open, with no selection or search). */
@Composable
private fun AddButton(vm: AppViewModel, open: (Destination) -> Unit, visible: Boolean) {
    AnimatedVisibility(visible, enter = scaleIn(), exit = scaleOut()) {
        AddContactFab(vm, open, visible = visible)
    }
}
