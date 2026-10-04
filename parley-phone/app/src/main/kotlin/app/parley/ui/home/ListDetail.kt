package app.parley.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.StartTab
import app.parley.common.ux.PaneStack
import app.parley.common.ux.WindowLayout
import app.parley.ui.Destination
import app.parley.ui.LocalNavAnimScope
import app.parley.ui.ParleyMotion
import app.parley.ui.ParleyViewModels
import app.parley.ui.Routes
import app.parley.ui.Spacing
import app.parley.ui.contact.ContactDetailScreen
import app.parley.ui.history.NumberHistoryScreen
import kotlinx.coroutines.flow.StateFlow

/** The app window's size (split screen and desktop windows included), as Home lays it out. */
@Composable
fun rememberWindowLayout(): WindowLayout {
    val c = LocalConfiguration.current
    return remember(c.screenWidthDp, c.screenHeightDp) { WindowLayout(c.screenWidthDp, c.screenHeightDp) }
}

/**
 * What the detail pane beside a list shows, so the list marks that row as open (null: nothing open, or one column).
 * It is the item opened from the list, even when a link inside the pane has gone further.
 */
val LocalOpenDetail = compositionLocalOf<Destination?> { null }

/** The destinations a detail pane can show, as the plain strings its [PaneStack] keeps. */
internal object PaneTargets {
    fun encode(d: Destination): String? = when (d) {
        is Routes.Contact -> "c:${d.id}"
        // Old links to a private contact: the one contact page, by its negative id.
        is Routes.Vault -> "c:${-d.id}"
        is Routes.History -> "h:${d.number}"
        else -> null
    }

    fun decode(s: String): Destination? = when {
        s.startsWith("c:") -> s.drop(2).toLongOrNull()?.let { Routes.Contact(it) }
        s.startsWith("h:") -> Routes.History(s.drop(2))
        else -> null
    }
}

/**
 * Home's detail panes on a big screen: what Contacts and Recents show beside their list, one [PaneStack] per tab,
 * kept in saved state. The editor's return asks [showSaved] first, so a contact saved from Home shows in the pane
 * instead of opening as a page over both.
 */
class HomePanes(private val saved: SavedStateHandle) : ViewModel() {
    /** Whether Home draws two panes now, and which tab is open: set by Home as it draws. */
    internal var twoPanes = false
    internal var tab: StartTab = StartTab.CONTACTS

    internal fun stack(tab: StartTab): StateFlow<List<String>> = saved.getStateFlow(key(tab), emptyList())

    internal fun update(tab: StartTab, change: (PaneStack) -> PaneStack) {
        saved[key(tab)] = ArrayList(change(PaneStack(saved.get<List<String>>(key(tab)).orEmpty())).entries)
    }

    /** The saved contact [id] is shown in the open tab's pane, when Home has one: true when it took it. */
    fun showSaved(id: Long): Boolean {
        if (!twoPanes || tab !in TABS) return false
        update(tab) { it.push(PaneTargets.encode(Routes.Contact(id))!!) }
        return true
    }

    companion object {
        /** The tabs with a list and a detail pane. */
        val TABS = setOf(StartTab.CONTACTS, StartTab.RECENTS)

        private fun key(tab: StartTab) = "pane_" + tab.name
    }
}

/** The activity's [HomePanes]. */
@Composable
fun homePanes(): HomePanes {
    val owner = checkNotNull(LocalActivity.current as? ComponentActivity) { "Not inside a ComponentActivity" }
    return viewModel(owner)
}

/**
 * A home tab's list, with what it opens beside it when the window has room ([WindowLayout.listDetail]). Contacts opens
 * a contact's page; Recents opens what a tap opens on a phone (the contact's page, or the number's history). Links
 * inside the pane open there too, and Back returns through them before leaving the tab ([backEnabled]: nothing more
 * pressing, like an open search, is waiting for it). Anything else opens as its own page, as on a phone. In one
 * column the list is drawn as before; what a fold or resize left open beside it opens as its own page.
 */
@Suppress("LongParameterList") // The tab, the window, Home's navigation and the list itself.
@Composable
internal fun HomeListDetail(
    vm: AppViewModel,
    tab: StartTab,
    window: WindowLayout,
    open: (Destination) -> Unit,
    backEnabled: Boolean,
    overlay: @Composable BoxScope.() -> Unit = {},
    list: @Composable (open: (Destination) -> Unit) -> Unit,
) {
    val panes = homePanes()
    val stack by panes.stack(tab).collectAsStateWithLifecycle()
    if (!window.listDetail) {
        LaunchedEffect(stack.isEmpty()) {
            val top = stack.lastOrNull()?.let(PaneTargets::decode) ?: return@LaunchedEffect
            panes.update(tab) { PaneStack() }
            open(top)
        }
        list(open)
        return
    }
    // Contacts opens a contact's page beside the list; Recents also a number's history.
    val opensHere: (Destination) -> Boolean = { d ->
        when (d) {
            is Routes.Contact, is Routes.Vault -> true
            is Routes.History -> tab == StartTab.RECENTS
            else -> false
        }
    }
    val fromList: (Destination) -> Unit = { d -> PaneTargets.encode(d)?.takeIf { opensHere(d) }?.let { e -> panes.update(tab) { it.select(e) } } ?: open(d) }
    val fromPane: (Destination) -> Unit = { d -> PaneTargets.encode(d)?.let { e -> panes.update(tab) { it.push(e) } } ?: open(d) }
    val back: () -> Unit = { panes.update(tab) { it.back() } }
    BackHandler(enabled = backEnabled && stack.isNotEmpty(), onBack = back)
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(window.listPaneDp.dp).fillMaxHeight()) {
            CompositionLocalProvider(LocalOpenDetail provides stack.firstOrNull()?.let(PaneTargets::decode)) { list(fromList) }
            overlay()
        }
        VerticalDivider()
        // Home's scaffold already makes room for the system bars: the pane's own bars mustn't again.
        Box(Modifier.weight(1f).fillMaxHeight().consumeWindowInsets(WindowInsets.safeDrawing)) {
            val shown = stack.lastOrNull()?.let(PaneTargets::decode)
            val enter = fadeIn(ParleyMotion.effects())
            val exit = fadeOut(ParleyMotion.fastEffects())
            AnimatedContent(shown, transitionSpec = { enter togetherWith exit }, label = "pane") { d ->
                if (d == null) {
                    EmptyPane(tab)
                } else {
                    PaneViewModels(d) {
                        // No shared-element flight between the list and a page that are both on screen.
                        CompositionLocalProvider(LocalNavAnimScope provides null) { PaneScreen(vm, d, back, fromPane) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaneScreen(vm: AppViewModel, d: Destination, back: () -> Unit, open: (Destination) -> Unit) {
    when (d) {
        is Routes.Contact -> ContactDetailScreen(vm, d.id, back = back, open = open, inPane = true)
        is Routes.History -> NumberHistoryScreen(vm, d.number, back = back, open = open, inPane = true)
        else -> Unit
    }
}

/** What the pane is for, while nothing is open in it. */
@Composable
private fun EmptyPane(tab: StartTab) {
    Column(
        Modifier.fillMaxSize().padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val recents = tab == StartTab.RECENTS
        Icon(if (recents) Icons.Rounded.History else Icons.Rounded.Person, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(if (recents) R.string.home_pane_empty_recents else R.string.home_pane_empty_contacts),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

/**
 * A view model store for one pane's screen, as a navigation entry has: its view models start with it and are cleared
 * when something else opens in the pane.
 */
@Composable
private fun PaneViewModels(key: Destination, content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val owner = remember(key) { PaneOwner(app) }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
}

private class PaneOwner(app: Application) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val viewModelStore = ViewModelStore()
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory = ParleyViewModels.Factory
    override val defaultViewModelCreationExtras: CreationExtras =
        MutableCreationExtras().apply { set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, app) }
}

/**
 * A tab whose content would stretch edge to edge on a big screen (the keypad's keys, the favourites' grid): centred,
 * at most [maxDp] wide. Phones are never this wide, so nothing changes there.
 */
@Composable
internal fun Centred(maxDp: Int, content: @Composable () -> Unit) {
    if (maxDp == Int.MAX_VALUE) {
        content()
        return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxDp.dp).fillMaxHeight()) { content() }
    }
}
