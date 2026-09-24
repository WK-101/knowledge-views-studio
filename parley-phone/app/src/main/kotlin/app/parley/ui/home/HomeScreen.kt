package app.parley.ui.home

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
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.ManageHistory
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.R
import app.parley.RecentFilter
import app.parley.common.SettingsCategory
import app.parley.common.StartTab
import app.parley.ui.Routes

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
    open: (String) -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // Tablets, foldables and landscape: navigation rail instead of a bottom bar.
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    var searching by rememberSaveable { mutableStateOf(false) }
    var favoriteQuery by rememberSaveable { mutableStateOf("") }
    var keypadQuery by rememberSaveable { mutableStateOf("") }
    val missed by vm.missedCount.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    fun closeSearch() {
        searching = false
        vm.contactQuery.value = ""
        vm.recentQuery.value = ""
        favoriteQuery = ""
        keypadQuery = ""
    }

    LaunchedEffect(tabRequest) {
        val r = tabRequest ?: return@LaunchedEffect
        tab = r.tab
        r.dial?.let { vm.dialInput.value = it }
        if (r.missedOnly) vm.recentFilter.value = RecentFilter.MISSED
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
    BackHandler(enabled = selection.isNotEmpty()) { vm.selection.value = emptySet() }
    BackHandler(enabled = searching) { closeSearch() }

    val barTabs = settings.navTabs.barTabs(tab)

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            if (tab == StartTab.CONTACTS && selection.isNotEmpty()) {
                SelectionBar(vm)
            } else {
                val query = when (tab) {
                    StartTab.CONTACTS -> vm.contactQuery.collectAsStateWithLifecycle().value
                    StartTab.RECENTS -> vm.recentQuery.collectAsStateWithLifecycle().value
                    StartTab.FAVORITES -> favoriteQuery
                    StartTab.KEYPAD -> keypadQuery
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
                    },
                    onQuery = { q ->
                        when (tab) {
                            StartTab.CONTACTS -> vm.contactQuery.value = q
                            StartTab.RECENTS -> vm.recentQuery.value = q
                            StartTab.FAVORITES -> favoriteQuery = q
                            StartTab.KEYPAD -> keypadQuery = q
                        }
                    },
                    onSearch = { on -> if (on) searching = true else closeSearch() },
                    scrollBehavior = scroll,
                    actions = { TabActions(vm, tab, settings.appLock, open) },
                    menu = { close -> TabMenu(vm, tab, settings.appLock, open, close) },
                )
            }
        },
        bottomBar = {
            Column(if (wide) Modifier.navigationBarsPadding() else Modifier) {
                app.parley.ui.calltime.NotificationHealthBanner(vm)
                app.parley.ui.calltime.ReturnToCallChip()
                if (!wide) NavigationBar {
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
        floatingActionButton = {
            AnimatedVisibility(tab == StartTab.CONTACTS && selection.isEmpty() && !searching, enter = scaleIn(), exit = scaleOut()) {
                FloatingActionButton(onClick = { open(if (vm.showVault.value) Routes.edit(vault = 0) else Routes.edit()) }) { Icon(Icons.Rounded.PersonAdd, stringResource(R.string.home_create_contact)) }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (wide) {
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
            Box(Modifier.weight(1f).fillMaxSize()) {
                AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                    when (t) {
                        StartTab.FAVORITES -> FavoritesTab(vm, open, favoriteQuery)
                        StartTab.RECENTS -> RecentsTab(vm, open)
                        StartTab.CONTACTS -> ContactsTab(vm, open)
                        StartTab.KEYPAD -> KeypadTab(vm, open, keypadQuery.takeIf { searching })
                    }
                }
            }
        }
    }
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
private fun TabActions(vm: AppViewModel, tab: StartTab, appLock: Boolean, open: (String) -> Unit) {
    when (tab) {
        StartTab.RECENTS -> app.parley.ui.history.RecentsInsightsAction(open)
        StartTab.CONTACTS -> {
            IconButton({ open(app.parley.ui.people.PeopleRoutes.LABELS) }) { Icon(Icons.AutoMirrored.Rounded.Label, stringResource(R.string.home_labels)) }
            // U8: lock Parley now, without waiting for the timeout.
            if (appLock) IconButton({ app.parley.security.AppLock.lockNowByUser() }) { Icon(Icons.Rounded.Lock, stringResource(R.string.home_lock_now)) }
        }
        StartTab.KEYPAD -> IconButton({ open(Routes.SPEED_DIAL) }) { Icon(Icons.Rounded.Speed, stringResource(R.string.home_speed_dial)) }
        StartTab.FAVORITES -> Unit
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem({ Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

/** "More options": the tab's own items first, then the ones every tab shares. */
@Composable
private fun ColumnScope.TabMenu(vm: AppViewModel, tab: StartTab, appLock: Boolean, open: (String) -> Unit, close: () -> Unit) {
    fun go(route: String) { close(); open(route) }
    when (tab) {
        StartTab.RECENTS -> {
            app.parley.ui.history.RecentsExportMenuItem(close)
            MenuItem(stringResource(R.string.home_messaged_numbers), Icons.AutoMirrored.Rounded.Chat) { go(app.parley.messaging.MessagingRoutes.MESSAGED) }
            MenuItem(stringResource(R.string.home_history_settings), Icons.Rounded.ManageHistory) { go(Routes.settingsPage(SettingsCategory.HISTORY)) }
        }
        StartTab.CONTACTS -> {
            MenuItem(stringResource(R.string.home_select_all), Icons.Rounded.SelectAll) {
                close()
                vm.selection.value = vm.people.filtered.value.orEmpty().map { it.id }.toSet()
            }
            MenuItem(stringResource(R.string.home_add_several), Icons.Rounded.GroupAdd) { go(app.parley.messaging.MessagingRoutes.BULK_ADD) }
            MenuItem(stringResource(R.string.home_duplicates), Icons.AutoMirrored.Rounded.CallMerge) { go(Routes.DUPLICATES) }
            MenuItem(stringResource(R.string.home_contacts_settings), Icons.Rounded.Tune) { go(Routes.settingsPage(SettingsCategory.CONTACTS)) }
        }
        StartTab.KEYPAD -> {
            MenuItem(stringResource(R.string.home_speed_dial), Icons.Rounded.Speed) { go(Routes.SPEED_DIAL) }
            MenuItem(stringResource(R.string.home_sims), Icons.Rounded.SimCard) { go(app.parley.ui.history.HistoryRoutes.SIMS) }
            MenuItem(stringResource(R.string.home_keypad_settings), Icons.Rounded.Tune) { go(Routes.settingsPage(SettingsCategory.KEYPAD)) }
        }
        StartTab.FAVORITES -> Unit
    }
    if (tab != StartTab.FAVORITES) HorizontalDivider()
    MenuItem(stringResource(R.string.home_birthdays), Icons.Rounded.Cake) { go(Routes.BIRTHDAYS) }
    MenuItem(stringResource(R.string.home_temporary), Icons.Rounded.AutoDelete) { go(Routes.TEMPORARY) }
    MenuItem(stringResource(R.string.home_recently_deleted), Icons.Rounded.History) { go(Routes.JOURNAL) }
    MenuItem(stringResource(R.string.home_tidy_up), Icons.Rounded.HealthAndSafety) { go(Routes.HEALTH) }
    MenuItem(stringResource(R.string.home_blocked_numbers), Icons.Rounded.Block) { go(Routes.BLOCKING) }
    app.parley.ui.blocking.ExpectingCallMenuItem(close)
    if (appLock) MenuItem(stringResource(R.string.home_lock_now), Icons.Rounded.Lock) { close(); app.parley.security.AppLock.lockNowByUser() }
    MenuItem(stringResource(R.string.home_settings), Icons.Rounded.Settings) { go(Routes.SETTINGS) }
}
