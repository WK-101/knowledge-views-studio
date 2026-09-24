package app.parley.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.animation.togetherWith
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.RecentFilter
import app.parley.common.StartTab
import app.parley.ui.Routes

private data class TabSpec(val tab: StartTab, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabSpec(StartTab.FAVORITES, "Favorites", Icons.Rounded.Star),
    TabSpec(StartTab.RECENTS, "Recents", Icons.Rounded.AccessTime),
    TabSpec(StartTab.CONTACTS, "Contacts", Icons.Rounded.People),
    TabSpec(StartTab.KEYPAD, "Keypad", Icons.Rounded.Dialpad),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: AppViewModel,
    tabRequest: NavEvent.Tab?,
    onTabRequestHandled: () -> Unit,
    initialTab: StartTab,
    open: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // Tablets, foldables and landscape: navigation rail instead of a bottom bar.
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    var searching by rememberSaveable { mutableStateOf(false) }
    val missed by vm.missedCount.collectAsStateWithLifecycle()

    LaunchedEffect(tabRequest) {
        val r = tabRequest ?: return@LaunchedEffect
        tab = r.tab
        r.dial?.let { vm.dialInput.value = it }
        if (r.missedOnly) vm.recentFilter.value = RecentFilter.MISSED
        onTabRequestHandled()
    }
    LaunchedEffect(tab) {
        if (tab == StartTab.RECENTS && missed > 0) vm.markMissedSeen()
        if (tab != StartTab.CONTACTS && tab != StartTab.RECENTS) searching = false
    }
    val selection by vm.selection.collectAsStateWithLifecycle()
    BackHandler(enabled = selection.isNotEmpty()) { vm.selection.value = emptySet() }
    LaunchedEffect(tab) { if (tab != StartTab.CONTACTS) vm.selection.value = emptySet() }
    BackHandler(enabled = searching) {
        searching = false
        vm.contactQuery.value = ""
        vm.recentQuery.value = ""
    }

    Scaffold(
        topBar = {
            if (tab == StartTab.CONTACTS && selection.isNotEmpty()) {
                SelectionBar(vm)
            } else if (tab != StartTab.KEYPAD) {
                HomeTopBar(
                    title = tabs.first { it.tab == tab }.label,
                    searchable = tab == StartTab.CONTACTS || tab == StartTab.RECENTS,
                    searching = searching,
                    query = if (tab == StartTab.CONTACTS) vm.contactQuery.collectAsStateWithLifecycle().value else vm.recentQuery.collectAsStateWithLifecycle().value,
                    onQuery = { if (tab == StartTab.CONTACTS) vm.contactQuery.value = it else vm.recentQuery.value = it },
                    onSearch = { searching = it; if (!it) { vm.contactQuery.value = ""; vm.recentQuery.value = "" } },
                    open = open,
                    actions = { if (tab == StartTab.RECENTS) app.parley.ui.history.RecentsInsightsAction(open) },
                    menuItems = { close -> if (tab == StartTab.RECENTS) app.parley.ui.history.RecentsExportMenuItem(close) },
                )
            }
        },
        bottomBar = {
            Column {
                app.parley.ui.calltime.NotificationHealthBanner(vm)
                app.parley.ui.calltime.ReturnToCallChip()
                if (!wide) NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t.tab,
                            onClick = { tab = t.tab },
                            icon = {
                                if (t.tab == StartTab.RECENTS && missed > 0) {
                                    BadgedBox(badge = { Badge { Text(missed.toString()) } }) { Icon(t.icon, null) }
                                } else {
                                    Icon(t.icon, null)
                                }
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            androidx.compose.animation.AnimatedVisibility(
                tab == StartTab.CONTACTS && selection.isEmpty(),
                enter = androidx.compose.animation.scaleIn(),
                exit = androidx.compose.animation.scaleOut(),
            ) {
                FloatingActionButton(onClick = { open(if (vm.showVault.value) Routes.edit(vault = 0) else Routes.edit()) }) { Icon(Icons.Rounded.PersonAdd, "Create contact") }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (wide) {
                androidx.compose.material3.NavigationRail {
                    Spacer(Modifier.weight(1f))
                    tabs.forEach { t ->
                        androidx.compose.material3.NavigationRailItem(
                            selected = tab == t.tab,
                            onClick = { tab = t.tab },
                            icon = {
                                if (t.tab == StartTab.RECENTS && missed > 0) BadgedBox(badge = { Badge { Text(missed.toString()) } }) { Icon(t.icon, null) }
                                else Icon(t.icon, null)
                            },
                            label = { Text(t.label) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                androidx.compose.animation.AnimatedContent(
                    tab,
                    transitionSpec = { androidx.compose.animation.fadeIn() togetherWith androidx.compose.animation.fadeOut() },
                    label = "tab",
                ) { t ->
                    when (t) {
                        StartTab.FAVORITES -> FavoritesTab(vm, open)
                        StartTab.RECENTS -> RecentsTab(vm, open)
                        StartTab.CONTACTS -> ContactsTab(vm, open)
                        StartTab.KEYPAD -> KeypadTab(vm, open)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    title: String,
    searchable: Boolean,
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onSearch: (Boolean) -> Unit,
    open: (String) -> Unit,
    actions: @Composable () -> Unit = {},
    menuItems: @Composable (close: () -> Unit) -> Unit = {},
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).padding(horizontal = 8.dp).then(Modifier.padding(top = 0.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
            Box(Modifier.weight(1f).padding(vertical = 4.dp)) {
                if (searchable) {
                    TextField(
                        value = query,
                        onValueChange = { onQuery(it); if (!searching) onSearch(true) },
                        placeholder = { Text("Search ${title.lowercase()}") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = {
                            if (searching || query.isNotEmpty()) IconButton({ onSearch(false) }) { Icon(Icons.Rounded.Close, "Clear search") }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)),
                    )
                } else {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                }
            }
            actions()
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More options") }
                DropdownMenu(menu, { menu = false }) {
                    menuItems { menu = false }
                    DropdownMenuItem({ Text("Birthdays & dates") }, leadingIcon = { Icon(Icons.Rounded.Cake, null) }, onClick = { menu = false; open(Routes.BIRTHDAYS) })
                    DropdownMenuItem({ Text("Recently deleted") }, leadingIcon = { Icon(Icons.Rounded.History, null) }, onClick = { menu = false; open(Routes.JOURNAL) })
                    DropdownMenuItem({ Text("Tidy up contacts") }, leadingIcon = { Icon(Icons.Rounded.HealthAndSafety, null) }, onClick = { menu = false; open(Routes.HEALTH) })
                    DropdownMenuItem({ Text("Blocked numbers") }, leadingIcon = { Icon(Icons.Rounded.Block, null) }, onClick = { menu = false; open(Routes.BLOCKING) })
                    app.parley.ui.blocking.ExpectingCallMenuItem { menu = false }
                    DropdownMenuItem({ Text("Settings") }, leadingIcon = { Icon(Icons.Rounded.Settings, null) }, onClick = { menu = false; open(Routes.SETTINGS) })
                }
            }
        }
    }
}
