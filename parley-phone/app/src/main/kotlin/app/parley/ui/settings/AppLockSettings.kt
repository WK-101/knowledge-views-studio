// The page and its destination live together.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.security.PinProblem
import app.parley.data.security.AppPinStore
import app.parley.security.AppLock
import app.parley.security.NewPinDialog
import app.parley.ui.ConfirmDialog
import app.parley.ui.Destination
import app.parley.ui.InfoRow
import app.parley.ui.LinkRow
import app.parley.ui.ParleyDialog
import app.parley.ui.SegmentedGroup
import app.parley.ui.SettingsScaffold
import app.parley.ui.Spacing
import androidx.compose.material3.TextButton
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Settings › Privacy & security › App lock › Unlock with (I21). */
object AppLockRoutes {
    @Serializable data object UnlockWith : Destination
}

/** What "Unlock with" says under its title on the Privacy page. */
@Composable
internal fun unlockWithSummary(vm: AppViewModel): String {
    val pin by vm.c.appPin.summary.collectAsStateWithLifecycle()
    val shownOff by vm.c.appPin.sessionShownOff.collectAsStateWithLifecycle()
    return stringResource(if (pin?.pinSet == true && !shownOff) R.string.app_lock_method_pin else R.string.app_lock_method_device)
}

private enum class PinDialog { SET_PIN, PIN_OFF, DURESS_ABOUT, DURESS_SET, DURESS_MENU, DURESS_OFF, DURESS_INFO }

/**
 * The Parley PIN, and the duress PIN with its option. During a duress session ([app.parley.common.AppSettings.duress])
 * the page looks exactly like one where no duress PIN was ever set (M7): the same rows, the duress PIN "Off", and
 * setting one there works for the session's screens only. A new PIN becomes the duress PIN and turning the PIN off
 * lasts until the next lock (see [AppPinStore]). Every change asks for the fingerprint or screen lock first, like
 * turning the app lock on or off.
 */
@Composable
internal fun UnlockWithScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val store = vm.c.appPin
    val settings by vm.settings.collectAsStateWithLifecycle()
    // In a duress session, the session's view: the duress PIN off unless set in the session (M7).
    val summary by store.shown.collectAsStateWithLifecycle()
    val shownOff by store.sessionShownOff.collectAsStateWithLifecycle()
    val inDuress = settings.duress != null
    val s = summary ?: AppPinStore.Summary()
    val pinOn = s.pinSet && !shownOff
    var dialog by rememberSaveable { mutableStateOf<PinDialog?>(null) }
    val confirmed: Confirm = { why, then ->
        (context as? FragmentActivity)?.let { act -> AppLock.confirm(act, res.getString(why)) { ok -> if (ok) then() } }
    }

    SettingsScaffold(settingTitle("app_lock_method"), back) {
        Text(
            stringResource(R.string.app_lock_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s),
        )
        SegmentedGroup {
            switchRow("parley_pin", pinOn, Icons.Rounded.Dialpad) { on ->
                if (on) confirmed(R.string.pin_set_title) { dialog = PinDialog.SET_PIN } else dialog = PinDialog.PIN_OFF
            }
            if (pinOn) item("pin_change") {
                LinkRow(stringResource(R.string.pin_change), null, Icons.Rounded.Password) { confirmed(R.string.pin_change) { dialog = PinDialog.SET_PIN } }
            }
        }
        // The same in a duress session, where it shows the session's view: nothing may hint that a second PIN exists.
        if (s.pinSet) DuressGroup(vm, s, inDuress, confirmed) { dialog = it }
    }
    PinDialogs(vm, dialog, s, inDuress, confirmed) { dialog = it }
}

/** Asks for the fingerprint or screen lock with a reason (a string id), then runs the action. */
private typealias Confirm = (Int, () -> Unit) -> Unit

@Composable
private fun DuressGroup(vm: AppViewModel, s: AppPinStore.Summary, inDuress: Boolean, confirmed: Confirm, show: (PinDialog) -> Unit) {
    val scope = rememberCoroutineScope()
    val duressSub = stringResource(if (s.duressSet) R.string.duress_on_summary else R.string.set_off)
    SegmentedGroup(stringResource(R.string.duress_group)) {
        item("duress_explainer") { InfoRow(stringResource(R.string.duress_explainer), null, Icons.Rounded.Shield) }
        linkRow("duress_pin", Icons.Rounded.Password, sub = duressSub) { show(if (s.duressSet) PinDialog.DURESS_MENU else PinDialog.DURESS_ABOUT) }
        if (s.duressSet) {
            switchRow("duress_lock_vault", s.lockVaultOnDuress, Icons.Rounded.EnhancedEncryption) { on ->
                confirmed(R.string.duress_change) { scope.launch { vm.c.appPin.setLockVaultOnDuress(on, duressSession = inDuress) } }
            }
        }
        item("duress_info") { LinkRow(stringResource(R.string.duress_what_it_hides), null, Icons.Rounded.Info) { show(PinDialog.DURESS_INFO) } }
    }
}

/** The Parley PIN's dialogs; the duress PIN's are in [DuressDialogs]. */
@Composable
private fun PinDialogs(
    vm: AppViewModel,
    dialog: PinDialog?,
    s: AppPinStore.Summary,
    inDuress: Boolean,
    confirmed: Confirm,
    show: (PinDialog?) -> Unit,
) {
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val store = vm.c.appPin
    when (dialog) {
        PinDialog.SET_PIN -> NewPinDialog(
            title = stringResource(R.string.pin_set_title),
            body = stringResource(R.string.pin_set_body),
            check = { pin ->
                // M5: changes wait after a few, the same for any PIN in any session. The duress PIN as this page shows
                // it can't also be the Parley PIN (in a session: one set there; never compared with the real PIN).
                val wait = store.changeWait()
                when {
                    wait > 0 -> res.getString(R.string.pin_change_wait, DateUtils.formatElapsedTime((wait + 999) / 1000))
                    store.isShownDuress(pin, inDuress) -> res.getString(R.string.pin_same_as_duress)
                    else -> null
                }
            },
            save = { pin -> store.setPin(pin, duressSession = inDuress) },
            onDismiss = { show(null) },
        )
        PinDialog.PIN_OFF -> ConfirmDialog(
            title = stringResource(R.string.pin_off_title),
            text = stringResource(if (s.duressSet) R.string.pin_off_body_duress else R.string.pin_off_body),
            confirmLabel = stringResource(R.string.pin_off_confirm),
            onConfirm = {
                show(null)
                confirmed(R.string.pin_off_title) { scope.launch { store.removePin(duressSession = inDuress) } }
            },
            onDismiss = { show(null) },
        )
        null -> Unit
        else -> DuressDialogs(vm, dialog, inDuress, confirmed, show)
    }
}

@Composable
private fun DuressDialogs(vm: AppViewModel, dialog: PinDialog, inDuress: Boolean, confirmed: Confirm, show: (PinDialog?) -> Unit) {
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val store = vm.c.appPin
    when (dialog) {
        PinDialog.DURESS_ABOUT -> DuressAboutDialog(stringResource(R.string.duress_about_continue), onDismiss = { show(null) }) {
            show(null)
            confirmed(R.string.duress_set_title) { show(PinDialog.DURESS_SET) }
        }
        PinDialog.DURESS_INFO -> DuressAboutDialog(null, onDismiss = { show(null) }) { show(null) }
        PinDialog.DURESS_SET -> NewPinDialog(
            title = stringResource(R.string.duress_set_title),
            body = stringResource(R.string.duress_set_body),
            check = { pin -> if (store.duressProblem(pin, inDuress) == PinProblem.SAME_AS_PIN) res.getString(R.string.pin_same_as_pin) else null },
            save = { pin -> store.setDuress(pin, duressSession = inDuress) },
            onDismiss = { show(null) },
        )
        PinDialog.DURESS_MENU -> ParleyDialog(
            onDismissRequest = { show(null) },
            title = { Text(settingTitle("duress_pin")) },
            text = { Text(stringResource(R.string.duress_on_summary)) },
            confirmButton = {
                TextButton({
                    show(null)
                    confirmed(R.string.duress_change) { show(PinDialog.DURESS_SET) }
                }) { Text(stringResource(R.string.duress_change)) }
            },
            dismissButton = { TextButton({ show(PinDialog.DURESS_OFF) }) { Text(stringResource(R.string.duress_off)) } },
        )
        PinDialog.DURESS_OFF -> ConfirmDialog(
            title = stringResource(R.string.duress_off_title),
            text = stringResource(R.string.duress_off_body),
            confirmLabel = stringResource(R.string.pin_off_confirm),
            onConfirm = {
                show(null)
                confirmed(R.string.duress_off_title) { scope.launch { store.setDuress(null, duressSession = inDuress) } }
            },
            onDismiss = { show(null) },
        )
        else -> Unit
    }
}

/** What a duress PIN hides, what it can't do, and the one thing to remember (the Parley PIN can't be recovered). */
@Composable
private fun DuressAboutDialog(continueLabel: String?, onDismiss: () -> Unit, onContinue: () -> Unit) {
    ParleyDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Shield, null) },
        title = { Text(stringResource(R.string.duress_about_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                listOf(R.string.duress_about_hides, R.string.duress_about_until, R.string.duress_about_only_pin, R.string.duress_about_limits).forEach { id ->
                    Text(stringResource(id), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = Spacing.s))
                }
            }
        },
        confirmButton = { TextButton(onContinue) { Text(continueLabel ?: stringResource(R.string.set_ok)) } },
        dismissButton = continueLabel?.let { { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } } },
    )
}
