// The feature's destinations and its graph live together, named for the graph.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.blocking

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.ui.Destination
import app.parley.ui.Routes
import app.parley.ui.appVm
import kotlinx.serialization.Serializable

/** Blocking sub-screens. */
object BlockingRoutes {
    @Serializable data object Lists : Destination

    @Serializable data object Transfer : Destination

    @Serializable data object DryRun : Destination

    @Serializable data object Templates : Destination

    /** A rule to edit ([id] > 0) or a new one of [kind] and [type] for [pattern]. */
    @Serializable
    data class Rule(val id: Long, val kind: String = RuleKind.BLOCK.name, val type: String = RuleType.PREFIX.name, val pattern: String = "") : Destination

    fun rule(id: Long, kind: RuleKind = RuleKind.BLOCK, type: RuleType = RuleType.PREFIX, pattern: String = ""): Destination =
        Rule(id, kind.name, type.name, pattern)
}

/** Blocking & screening and its sub-screens. */
fun NavGraphBuilder.blockingGraph(nav: NavController) {
    val back: () -> Unit = { nav.popBackStack() }
    composable<Routes.Blocking> { BlockingScreen(appVm(), back = back, open = { r -> nav.navigate(r) }) }
    composable<BlockingRoutes.Lists> { SpamListsScreen(appVm(), back) }
    composable<BlockingRoutes.Transfer> { TransferScreen(appVm(), back) }
    composable<BlockingRoutes.DryRun> { DryRunScreen(appVm(), back) }
    composable<BlockingRoutes.Templates> { TemplatesScreen(appVm(), back) }
    composable<BlockingRoutes.Rule> {
        val a = it.toRoute<BlockingRoutes.Rule>()
        val kind = runCatching { RuleKind.valueOf(a.kind) }.getOrDefault(RuleKind.BLOCK)
        val type = runCatching { RuleType.valueOf(a.type) }.getOrDefault(RuleType.PREFIX)
        RuleEditorScreen(appVm(), a.id, newRule(kind, type, a.pattern), back)
    }
}
