package app.parley.ui.settings

import app.parley.ui.Destination
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.blocking.BlockingActions
import app.parley.common.SettingsCategory
import app.parley.messaging.MessagingRoutes
import app.parley.security.AppLock
import app.parley.ui.Routes
import app.parley.ui.SegmentedGroup
import app.parley.ui.blocking.BlockingDialog
import app.parley.ui.blocking.BlockingDialogs
import app.parley.ui.discover.DiscoverRoutes
import app.parley.ui.qr.QrRoutes
import app.parley.ui.temporary.rememberTemporaryItems
import kotlinx.coroutines.launch
import app.parley.ui.LinkRow
import app.parley.ui.SettingsScaffold

/**
 * Tools: the app-wide destinations that used to repeat in every tab's ⋮ menu. Reached from ⋮ › Tools on every tab
 * and from the top of Settings. Rows use the same names (and catalog keys) as their Settings rows.
 */
@Composable
fun ToolsScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val tempCount = rememberTemporaryItems(vm).size
    val tempSub = if (tempCount == 0) null else pluralStringResource(R.plurals.set_temporary_count, tempCount, tempCount)
    val snoozing = s.screening.snoozeActive(System.currentTimeMillis())
    val snoozeOn = stringResource(R.string.set_expecting_call_on)
    SettingsScaffold(stringResource(R.string.set_tools_title), back) {
        // P8: the way into everything else, grouped by what people want done.
        SegmentedGroup {
            linkRow("what_parley_can_do", Icons.Rounded.Explore) { open(DiscoverRoutes.Capabilities) }
        }
        SegmentedGroup(stringResource(R.string.tools_group_contacts)) {
            linkRow("birthdays", Icons.Rounded.Cake) { open(Routes.Birthdays) }
            linkRow("temporary_contacts", Icons.Rounded.AutoDelete, sub = tempSub) { open(Routes.Temporary) }
            linkRow("health", Icons.Rounded.HealthAndSafety) { open(Routes.Health) }
            linkRow("scan_qr", Icons.Rounded.QrCodeScanner) { open(QrRoutes.Scan) }
            item("import_export") {
                LinkRow(stringResource(R.string.tools_import_export), stringResource(R.string.tools_import_export_sub), Icons.Rounded.ImportExport) {
                    open(Routes.settingsPage(SettingsCategory.CONTACTS, "import_file"))
                }
            }
            // P7: the same "Coming from…" list as onboarding's last step.
            linkRow("coming_from", Icons.Rounded.MoveToInbox) { open(DiscoverRoutes.ComingFrom) }
        }
        SegmentedGroup(stringResource(R.string.tools_group_calls)) {
            linkRow("blocking", Icons.Rounded.Block) { open(Routes.Blocking) }
            switchRow("expecting_call", snoozing, Icons.Rounded.HourglassTop, sub = if (snoozing) snoozeOn else null) { v ->
                if (v) BlockingDialogs.show(BlockingDialog.Snooze)
                else scope.launch { BlockingActions.snooze(vm.c, 0) }
            }
            linkRow("messaged_numbers", Icons.AutoMirrored.Rounded.Chat) { open(MessagingRoutes.Messaged) }
        }
        SegmentedGroup(stringResource(R.string.tools_group_data)) {
            linkRow("journal", Icons.Rounded.RestoreFromTrash) { open(Routes.journal()) }
            linkRow("backup", Icons.Rounded.Backup) { open(Routes.Backup) }
            linkRow("privacy_dashboard", Icons.Rounded.PrivacyTip) { open(Routes.Privacy) }
            // Lock Parley now, without waiting for the timeout.
            if (s.appLock) item("lock_now") {
                LinkRow(stringResource(R.string.home_lock_now), stringResource(R.string.tools_lock_now_sub), Icons.Rounded.Lock) {
                    AppLock.lockNowByUser()
                }
            }
        }
    }
}
