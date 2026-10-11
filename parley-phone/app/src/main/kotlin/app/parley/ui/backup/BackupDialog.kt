package app.parley.ui.backup

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.ui.Clipboard
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.ParleyDialog
import app.parley.ui.ConfirmDialog
import kotlinx.coroutines.CoroutineScope
import app.parley.data.backup.BackupRepository

/** The screen's dialogs, one at a time. */
internal sealed interface BackupDialog {
    /** Set the passphrase (no keys yet), or change it. */
    data class Passphrase(val change: Boolean) : BackupDialog

    /** Keys made before backups were signed: one passphrase entry lets the key vouch for this phone. */
    data object ConfirmPhone : BackupDialog

    /** The recovery key, shown once after the keys are made. */
    data class Recovery(val key: String) : BackupDialog

    /** Restore from a backup file. */
    data class Restore(val uri: Uri) : BackupDialog
}

/**
 * Shows the backup screen's [dialog]; [show] moves to the next one (the recovery key after the passphrase) and [close]
 * ends it, saying whether a restore ran (the folder's list is read again then).
 */
@Composable
internal fun BackupDialogHost(
    vm: AppViewModel,
    repo: BackupRepository,
    dialog: BackupDialog?,
    scope: CoroutineScope,
    show: (BackupDialog) -> Unit,
    close: (restored: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    when (dialog) {
        null -> Unit
        is BackupDialog.Passphrase -> PassphraseDialog(change = dialog.change, onDismiss = { close(false) }) { old, new ->
            scope.launch {
                if (dialog.change) {
                    val ok = repo.changePassphrase(old!!.toCharArray(), new.toCharArray())
                    vm.toast(res.getString(if (ok) R.string.bkp_pass_changed else R.string.bkp_pass_wrong))
                    close(false)
                } else {
                    show(BackupDialog.Recovery(repo.setupKeys(new.toCharArray()).format()))
                }
            }
        }
        BackupDialog.ConfirmPhone -> ConfirmPhoneDialog(repo, scope, vm::toast) { close(false) }
        is BackupDialog.Recovery -> ParleyDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.bkp_recovery_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.bkp_recovery_text))
                    Text(dialog.key, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 12.dp))
                }
            },
            confirmButton = { TextButton({ close(false) }) { Text(stringResource(R.string.bkp_recovery_saved)) } },
            dismissButton = { TextButton({ Clipboard.copy(context, dialog.key) }) { Text(stringResource(R.string.action_copy)) } },
        )
        is BackupDialog.Restore -> RestoreFlow(vm, dialog.uri) { close(true) }
    }
}

/** Keys made before backups were signed: the passphrase once, and the key vouches for this phone. */
@Composable
private fun ConfirmPhoneDialog(repo: BackupRepository, scope: CoroutineScope, toast: (String) -> Unit, close: () -> Unit) {
    val res = LocalResources.current
    var pass by remember { mutableStateOf("") }
    ConfirmDialog(
        title = stringResource(R.string.bkp_confirm_phone),
        text = stringResource(R.string.bkp_confirm_phone_summary),
        confirmLabel = stringResource(R.string.dc_ok),
        onConfirm = {
            scope.launch {
                val ok = repo.confirmThisPhone(pass.toCharArray())
                toast(res.getString(if (ok) R.string.bkp_confirm_phone_done else R.string.bkp_pass_wrong))
                if (ok) close()
            }
        },
        onDismiss = close,
        dismissLabel = stringResource(R.string.dc_cancel),
        confirmEnabled = pass.isNotEmpty(),
        content = { PassField(stringResource(R.string.bkp_current_pass), pass) { pass = it } },
    )
}
