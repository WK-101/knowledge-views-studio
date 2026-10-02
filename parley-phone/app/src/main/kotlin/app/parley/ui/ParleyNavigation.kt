package app.parley.ui

import app.parley.ui.sync.shared.sharedLabelGraph
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import app.parley.messaging.messagingGraph
import app.parley.ui.blocking.blockingGraph
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
import app.parley.ui.timemachine.watchGraph

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
    watchGraph(nav)
    familyGraph(nav)
    discoverGraph(nav)
    driveGraph(nav)
    sharedLabelGraph(nav)
}
