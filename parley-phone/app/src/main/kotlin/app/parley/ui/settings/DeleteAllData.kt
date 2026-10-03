package app.parley.ui.settings

import app.parley.security.SensitiveScreen
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.content.pm.ShortcutManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import app.parley.AppViewModel
import app.parley.R
import app.parley.data.DataContainer
import app.parley.data.DataWipe
import app.parley.data.vault.VaultCrypto
import app.parley.security.AppLock
import app.parley.telecom.CallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import app.parley.data.security.Concealment
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.parley.ui.ParleyDialog
import app.parley.ui.ConfirmDialog
import app.parley.ui.InfoDialog

private sealed interface WipeStep {
    data object Ask : WipeStep
    data class Working(val text: String) : WipeStep
    data class Failed(val text: String) : WipeStep

    /** Private contacts exist but the backup couldn't include them (their key was locked). */
    data object VaultLocked : WipeStep
}

/**
 * Settings › Privacy › Delete all Parley data: a strong confirmation (typing a word, then the app lock when it is on,
 * or the screen lock while supervised call-time limits are set), an optional backup first, and separate choices for
 * Android's call log and the contacts stored on the phone. Everything Parley keeps goes; the app then starts again as new.
 *
 * The backup and the wipe run in the app's scope, not the dialog's: a rotation or theme change mid-way must neither
 * cancel the restart after a wipe (old in-memory state would be written back) nor leave a wipe half-reported.
 */
@Composable
fun DeleteAllDataDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    SensitiveScreen()
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val backupState by vm.c.backup.prefs.state.collectAsStateWithLifecycle()
    val canBackUp = backupState.hasKeys && backupState.folderUri != null
    var step by remember { mutableStateOf<WipeStep>(WipeStep.Ask) }
    var backupFirst by remember { mutableStateOf(canBackUp) }
    var callLog by remember { mutableStateOf(false) }
    var phoneContacts by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val word = stringResource(R.string.wipe_confirm_word)

    /** Backs up (when asked), checks again that no call started meanwhile, wipes and restarts. */
    fun proceed(withoutPrivate: Boolean) {
        val app = context.applicationContext
        val job = WipeJob(vm.c, app, res, backupFirst, withoutPrivate, DataWipe.Options(callLog = callLog, phoneContacts = phoneContacts))
        vm.c.scope.launch(Dispatchers.Main) { job.run { step = it } }
    }

    when (val s = step) {
        WipeStep.Ask -> ParleyDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.wipe_title)) },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.wipe_body))
                    Text(stringResource(R.string.wipe_outside), style = MaterialTheme.typography.bodySmall)
                    if (canBackUp) WipeCheck(stringResource(R.string.wipe_backup_first), backupFirst) { backupFirst = it }
                    else Text(stringResource(R.string.wipe_no_backup), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    WipeCheck(stringResource(R.string.wipe_call_log), callLog) { callLog = it }
                    WipeCheck(stringResource(R.string.wipe_phone_contacts), phoneContacts) { phoneContacts = it }
                    OutlinedTextField(
                        typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.wipe_type_to_confirm, word)) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    { confirmWipe(context, vm, scope, backupFirst, { step = it }, ::proceed) },
                    enabled = typed.trim().equals(word, ignoreCase = true),
                ) { Text(stringResource(R.string.wipe_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
        )
        is WipeStep.Working -> ParleyDialog(
            onDismissRequest = {},
            title = { Text(s.text) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
        is WipeStep.Failed -> InfoDialog(
            title = stringResource(R.string.wipe_title),
            text = s.text,
            onDismiss = onDismiss,
            closeLabel = stringResource(R.string.dc_done),
        )
        WipeStep.VaultLocked -> VaultLockedDialog(
            onUnlock = {
                val act = context as? FragmentActivity
                if (act != null) AppLock.authenticateForVault(act) { ok -> if (ok) proceed(withoutPrivate = false) }
            },
            onWithout = { proceed(withoutPrivate = true) },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun VaultLockedDialog(onUnlock: () -> Unit, onWithout: () -> Unit, onDismiss: () -> Unit) = ConfirmDialog(
    title = stringResource(R.string.wipe_title),
    text = null,
    confirmLabel = stringResource(R.string.wipe_unlock_private),
    onConfirm = onUnlock,
    onDismiss = onDismiss,
    dismissLabel = stringResource(R.string.dc_cancel),
    content = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.wipe_private_locked))
            TextButton(onWithout) { Text(stringResource(R.string.wipe_without_private), color = MaterialTheme.colorScheme.error) }
        }
    },
)

/**
 * After the typed word: the app lock (or the screen lock while supervised call-time limits are set, which the wipe
 * removes too, even with the app lock off), then, for a backup, the private contacts' unlock so they go into it.
 */
private fun confirmWipe(
    context: Context, vm: AppViewModel, scope: CoroutineScope, backupFirst: Boolean,
    show: (WipeStep) -> Unit, proceed: (withoutPrivate: Boolean) -> Unit,
) {
    val act = context as? FragmentActivity
    fun start() {
        if (inCall()) return show(WipeStep.Failed(context.getString(R.string.wipe_in_call)))
        // While a duress unlock hides things, private contacts don't exist as far as this screen can tell: no unlock is
        // asked for and nothing says they were left out (the backup leaves them out silently, see WipeJob).
        if (!backupFirst || Concealment.hiding) return proceed(false)
        scope.launch {
            val hasPrivate = runCatching { vm.c.vault.summariesNow().isNotEmpty() }.getOrDefault(true)
            when {
                !hasPrivate || !VaultCrypto.detailNeedsUnlock() -> proceed(false)
                act == null -> show(WipeStep.VaultLocked)
                else -> AppLock.authenticateForVault(act) { ok -> if (ok) proceed(false) else show(WipeStep.VaultLocked) }
            }
        }
    }
    val needsAuth = vm.settings.value.appLock || vm.c.calling.config.value.supervised
    when {
        !needsAuth -> start()
        act != null -> AppLock.confirm(act, context.getString(R.string.wipe_title)) { ok -> if (ok) start() }
    }
}

private fun inCall() = CallManager.state.value.isNotEmpty() || CallManager.pendingOutgoing.value != null

/**
 * The backup (when asked) and the wipe, run in the app's scope. A backup counts only when it is complete and, if
 * there are private contacts, holds them too (unless the user chose to go on without them).
 */
private class WipeJob(
    private val c: DataContainer,
    private val app: Context,
    private val res: Resources,
    private val backup: Boolean,
    private val withoutPrivate: Boolean,
    private val options: DataWipe.Options,
) {
    suspend fun run(show: (WipeStep) -> Unit) {
        if (backup) backUp(show)?.let { return show(it) }
        // A call may have started during the backup: closing the database or exiting would break it.
        if (inCall()) return show(WipeStep.Failed(res.getString(R.string.wipe_in_call)))
        show(WipeStep.Working(res.getString(R.string.wipe_deleting)))
        // While a duress unlock hides things nothing is deleted: the wipe would destroy what is hidden (SECURITY_MODEL,
        // "Everything here hides; nothing destroys"). It ends the way a wipe that can't start ends, after a while.
        if (Concealment.hiding) {
            delay(DECOY_MS)
            return show(WipeStep.Failed(res.getString(R.string.wipe_not_now)))
        }
        val stopped = withContext(Dispatchers.IO + NonCancellable) {
            // Background work must be stopped first, or it would write the old data back; if it can't be, nothing goes.
            runCatching { WorkManager.getInstance(app).cancelAllWork().result.get() }.isSuccess
        }
        if (!stopped) return show(WipeStep.Failed(res.getString(R.string.wipe_not_now)))
        withContext(Dispatchers.IO + NonCancellable) {
            runCatching { app.getSystemService(NotificationManager::class.java).cancelAll() }
            runCatching { app.getSystemService(ShortcutManager::class.java).removeAllDynamicShortcuts() }
            c.wipe.wipe(options)
        }
        restart(app)
    }

    /** Null when the backup is good enough to wipe after, otherwise the step that says why not. */
    private suspend fun backUp(show: (WipeStep) -> Unit): WipeStep? {
        show(WipeStep.Working(res.getString(R.string.wipe_backing_up)))
        val b = c.backup.backupNow(scheduled = false)
        return when {
            !b.ok -> WipeStep.Failed(res.getString(R.string.wipe_backup_failed, b.message))
            b.failedSections.isNotEmpty() -> WipeStep.Failed(res.getString(R.string.wipe_backup_incomplete, b.message))
            // While hiding, the backup leaves private contacts out without a word (as every backup made then does).
            !b.vaultIncluded && !withoutPrivate && !Concealment.hiding && runCatching { c.vault.summariesNow().isNotEmpty() }.getOrDefault(true) ->
                WipeStep.VaultLocked
            else -> null
        }
    }
}

/** How long the "Deleting…" step shows before a wipe refused while hiding says it couldn't be done. */
private const val DECOY_MS = 1_500L

@Composable
private fun WipeCheck(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Starts Parley again from scratch: nothing in this process may write the old state back. */
private fun restart(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component
    if (launch != null) context.startActivity(Intent.makeRestartActivityTask(launch))
    Runtime.getRuntime().exit(0)
}
