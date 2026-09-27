package app.parley.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.material.icons.rounded.Lock
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
import app.parley.ui.qr.QrRoutes
import app.parley.ui.temporary.rememberTemporaryItems
import kotlinx.coroutines.launch

/**
 * Tools: the app-wide destinations that used to repeat in every tab's ⋮ menu. Reached from ⋮ › Tools on every tab
 * and from the top of Settings. Rows use the same names (and catalog keys) as their Settings rows.
 */
@Composable
fun ToolsScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val tempCount = rememberTemporaryItems(vm).size
    val tempSub = if (tempCount == 0) null else pluralStringResource(R.plurals.set_temporary_count, tempCount, tempCount)
    val snoozing = s.screening.snoozeActive(System.currentTimeMillis())
    val snoozeOn = stringResource(R.string.set_expecting_call_on)
    SettingsScaffold(stringResource(R.string.set_tools_title), back) {
        SegmentedGroup(stringResource(R.string.tools_group_contacts)) {
            linkRow("birthdays", Icons.Rounded.Cake) { open(Routes.BIRTHDAYS) }
            linkRow("temporary_contacts", Icons.Rounded.AutoDelete, sub = tempSub) { open(Routes.TEMPORARY) }
            linkRow("health", Icons.Rounded.HealthAndSafety) { open(Routes.HEALTH) }
            linkRow("scan_qr", Icons.Rounded.QrCodeScanner) { open(QrRoutes.SCAN) }
            item("import_export") {
                LinkRow(stringResource(R.string.tools_import_export), stringResource(R.string.tools_import_export_sub), Icons.Rounded.ImportExport) {
                    open(Routes.settingsPage(SettingsCategory.CONTACTS, "import_file"))
                }
            }
        }
        SegmentedGroup(stringResource(R.string.tools_group_calls)) {
            linkRow("blocking", Icons.Rounded.Block) { open(Routes.BLOCKING) }
            switchRow("expecting_call", snoozing, Icons.Rounded.HourglassTop, sub = if (snoozing) snoozeOn else null) { v ->
                if (v) BlockingDialogs.show(BlockingDialog.Snooze)
                else scope.launch { BlockingActions.snooze(vm.c, 0) }
            }
            linkRow("messaged_numbers", Icons.AutoMirrored.Rounded.Chat) { open(MessagingRoutes.MESSAGED) }
        }
        SegmentedGroup(stringResource(R.string.tools_group_data)) {
            linkRow("journal", Icons.Rounded.RestoreFromTrash) { open(Routes.journal()) }
            linkRow("backup", Icons.Rounded.Backup) { open(Routes.BACKUP) }
            linkRow("privacy_dashboard", Icons.Rounded.PrivacyTip) { open(Routes.PRIVACY) }
            // Lock Parley now, without waiting for the timeout.
            if (s.appLock) item("lock_now") {
                LinkRow(stringResource(R.string.home_lock_now), stringResource(R.string.tools_lock_now_sub), Icons.Rounded.Lock) {
                    AppLock.lockNowByUser()
                }
            }
        }
    }
}
