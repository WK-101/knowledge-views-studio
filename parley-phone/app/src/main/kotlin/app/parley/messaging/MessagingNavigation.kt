package app.parley.messaging

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.parley.common.messaging.IntroQueue
import app.parley.data.AccountRef
import app.parley.ui.Destination
import app.parley.ui.appVm
import app.parley.ui.people.CsvMappingScreen
import kotlinx.serialization.Serializable

/** Screens of the messaging round and the contact CSV mapping. */
object MessagingRoutes {
    @Serializable data object Messaged : Destination

    @Serializable data object BulkAdd : Destination

    @Serializable data object Introduce : Destination

    @Serializable data object CsvMapping : Destination
}

/** A contact CSV waiting for its columns to be mapped. */
data class CsvImportRequest(val uri: Uri, val account: AccountRef, val skipDuplicates: Boolean)

/**
 * In-memory hand-offs between screens and activities: text to add numbers from, the people to introduce yourself
 * to, a CSV to map. Nothing here is written to disk; after process death the screens simply start empty.
 */
object MessagingInbox {
    @Volatile var bulkText: String? = null
    @Volatile var introTargets: List<IntroQueue.Target> = emptyList()
    @Volatile var csvImport: CsvImportRequest? = null
}

/** Messaged numbers, "Add several numbers", "Introduce myself" and the CSV column mapping. */
fun NavGraphBuilder.messagingGraph(nav: NavController) {
    val back: () -> Unit = { nav.popBackStack() }
    composable<MessagingRoutes.Messaged> { MessagedNumbersScreen(appVm(), back = back) }
    composable<MessagingRoutes.BulkAdd> { BulkAddScreen(appVm(), back = back, open = { r -> nav.navigate(r) }) }
    composable<MessagingRoutes.Introduce> { IntroduceScreen(appVm(), back = back) }
    composable<MessagingRoutes.CsvMapping> { CsvMappingScreen(appVm(), back = back) }
}
