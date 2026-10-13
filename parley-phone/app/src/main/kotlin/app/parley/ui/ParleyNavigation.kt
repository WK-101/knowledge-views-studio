package app.parley.ui

import app.parley.ui.sync.shared.sharedLabelGraph
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import app.parley.messaging.messagingGraph
import app.parley.ui.blocking.blockingGraph
import app.parley.ui.calls.callRoundGraph
import app.parley.ui.calls.toCallGraph
import app.parley.ui.contact.contactGraph
import app.parley.ui.discover.discoverGraph
import app.parley.ui.drive.driveGraph
import app.parley.ui.extras.extrasGraph
import app.parley.ui.family.familyGraph
import app.parley.ui.history.historyGraph
import app.parley.ui.people.peopleGraph
import app.parley.ui.qr.qrGraph
import app.parley.ui.settings.settingsGraph
import app.parley.ui.situations.situationGraph
import app.parley.ui.timemachine.watchGraph
import java.util.WeakHashMap

/**
 * Every feature's graph. Home is registered by the root, which owns its tab state; the screens get the app's view
 * model from [LocalAppViewModel], so the graph can be built (and tested) without one.
 */
fun NavGraphBuilder.parleyGraph(nav: NavController) {
    contactGraph(nav)
    historyGraph(nav)
    settingsGraph(nav)
    blockingGraph(nav)
    peopleGraph(nav)
    messagingGraph(nav)
    extrasGraph(nav)
    qrGraph(nav)
    toCallGraph(nav)
    callRoundGraph(nav)
    watchGraph(nav)
    familyGraph(nav)
    discoverGraph(nav)
    driveGraph(nav)
    situationGraph(nav)
    sharedLabelGraph(nav)
}

/**
 * Back from a page's top bar. A second tap while the first pop still animates would pop the page beneath too, and
 * from a first-level page that is Home, leaving an empty host: so a Back that lands on a page is ignored until that
 * page has settled (resumed), and Home itself is never popped.
 */
fun NavController.parleyBack(): () -> Unit = { backOnce() }

/** Opens a page once: a second tap on the same row while its page still slides in is dropped. */
fun NavController.parleyOpen(): (Destination) -> Unit = { to -> openOnce(to) }

/** Pops the top page unless it is Home or a Back just landed here; true when it popped. */
internal fun NavController.backOnce(): Boolean {
    val here = currentBackStackEntry ?: return false
    if (previousBackStackEntry == null) return false
    if (landedOn[this] == here.id && !here.settled()) return false
    if (!popBackStack()) return false
    currentBackStackEntry?.let { landedOn[this] = it.id }
    return true
}

/** Navigates to [to] unless that same page was just opened and is still coming in; true when it navigated. */
internal fun NavController.openOnce(to: Destination): Boolean {
    val here = currentBackStackEntry
    val comingIn = here != null && !here.settled() && lastOpened[this] == (here.id to to)
    if (comingIn) return false
    navigate(to)
    currentBackStackEntry?.let { lastOpened[this] = it.id to to }
    return true
}

/** A page is settled once its enter or pop animation ended: navigation keeps it STARTED until then. */
private fun NavBackStackEntry.settled() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

// Per controller (main thread only); weak, so a finished activity's controller is not kept.
private val landedOn = WeakHashMap<NavController, String>()
private val lastOpened = WeakHashMap<NavController, Pair<String, Destination>>()
