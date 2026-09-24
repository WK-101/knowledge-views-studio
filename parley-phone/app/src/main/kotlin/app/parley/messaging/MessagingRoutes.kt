package app.parley.messaging

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.parley.AppViewModel
import app.parley.common.messaging.IntroQueue
import app.parley.data.AccountRef

/** Screens of the messaging round (M10–M13) and the contact CSV mapping (M12). */
object MessagingRoutes {
    const val MESSAGED = "messaging/messaged"
    const val BULK_ADD = "messaging/bulk"
    const val INTRODUCE = "messaging/introduce"
    const val CSV_MAPPING = "import/csv-columns"
}

/** A contact CSV waiting for its columns to be mapped (M12). */
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

fun NavGraphBuilder.messagingRoutes(vm: AppViewModel, nav: NavController) {
    composable(MessagingRoutes.MESSAGED) { MessagedNumbersScreen(vm, back = { nav.popBackStack() }) }
    composable(MessagingRoutes.BULK_ADD) {
        BulkAddScreen(vm, back = { nav.popBackStack() }, open = { r -> nav.navigate(r) })
    }
    composable(MessagingRoutes.INTRODUCE) { IntroduceScreen(vm, back = { nav.popBackStack() }) }
    composable(MessagingRoutes.CSV_MAPPING) { app.parley.ui.people.CsvMappingScreen(vm, back = { nav.popBackStack() }) }
}
