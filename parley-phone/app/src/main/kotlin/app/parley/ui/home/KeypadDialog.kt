package app.parley.ui.home

import app.parley.ui.Destination
import app.parley.ui.temporary.TemporaryContactActions
import app.parley.security.VaultUnlockDeclined
import androidx.activity.ComponentActivity
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.menus.CallReasonFlow
import app.parley.ui.menus.ReasonTarget
import app.parley.messaging.TemporaryContact
import app.parley.ui.temporary.SaveTemporaryDialog
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import app.parley.AppViewModel
import app.parley.messaging.ReachSheet
import app.parley.messaging.ReachTarget
import app.parley.ui.Routes
import app.parley.ui.common.Format
import app.parley.ui.ConfirmDialog
import kotlinx.coroutines.CoroutineScope

/** The keypad's dialogs, one at a time. */
sealed interface KeypadDialog {
    /** A long-press on a speed-dial key with no number set up yet. */
    data class SpeedDialEmpty(val key: Int) : KeypadDialog

    /** "Call with a reason…" from a long-press on the Call pill. */
    data class Reason(val target: ReasonTarget) : KeypadDialog

    /** Message or call the typed number in an app. */
    data class Message(val number: String) : KeypadDialog

    /** Save the typed number for a while. */
    data class SaveTemporary(val number: String) : KeypadDialog

    /** `*#06#` typed: where to find the IMEI. */
    data object Imei : KeypadDialog
}

/** Shows [dialog]; [close] ends it. Saving for a while runs in the keypad's own [scope]. */
@Composable
internal fun KeypadDialogHost(
    vm: AppViewModel,
    keypad: KeypadViewModel,
    dialog: KeypadDialog?,
    field: TextFieldState,
    scope: CoroutineScope,
    open: (Destination) -> Unit,
    close: () -> Unit,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    when (dialog) {
        null -> Unit
        is KeypadDialog.SpeedDialEmpty -> ConfirmDialog(
            title = stringResource(R.string.keypad_speed_empty_title, dialog.key),
            text = stringResource(R.string.keypad_speed_empty_body, dialog.key),
            confirmLabel = stringResource(R.string.action_set_up),
            onConfirm = { close(); open(Routes.SpeedDial) },
            onDismiss = close,
            dismissLabel = stringResource(R.string.dc_cancel),
        )
        is KeypadDialog.Reason -> CallReasonFlow(vm, dialog.target) { close() }
        is KeypadDialog.Message -> ReachSheet(ReachTarget.Number(dialog.number), onDismiss = close, onCall = { num -> vm.requestCall(num) })
        is KeypadDialog.SaveTemporary -> SaveTemporaryDialog(
            number = Format.number(dialog.number, vm.countryIso),
            suggestedName = TemporaryContact.suggestedName(dialog.number, null, vm.countryIso.uppercase()),
            onDismiss = close,
        ) { name, days, deleteHistory, visible, clock ->
            val n = dialog.number
            scope.launch {
                // Private contacts locked: their unlock first; cancelled, the dialog stays with what was typed.
                val saved = try {
                    TemporaryContactActions.saveUnlocking(context as? ComponentActivity) { keypad.saveTemporaryNow(n, name, days, deleteHistory, visible, clock) }
                } catch (_: VaultUnlockDeclined) {
                    return@launch
                }
                close()
                if (saved != null) {
                    field.clearText()
                    vm.toast(res.getQuantityString(if (saved.private) R.plurals.caller_saved_private_days else R.plurals.caller_saved_days, days, days))
                } else {
                    vm.toast(res.getString(R.string.keypad_save_failed))
                }
            }
        }
        KeypadDialog.Imei -> ImeiSheet(onDismiss = {
            close()
            if (field.text.toString() == IMEI_CODE) field.clearText()
        })
    }
}
