package app.parley.ui.settings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import app.parley.data.DataWipe
import app.parley.security.AppLock
import app.parley.telecom.CallManager
import kotlinx.coroutines.launch

private sealed interface WipeStep {
    data object Ask : WipeStep
    data class Working(val text: String) : WipeStep
    data class Failed(val text: String) : WipeStep
}

/**
 * Settings › Privacy › Delete all Parley data: a strong confirmation (typing a word, then the app lock when it is on),
 * an optional backup first, and separate choices for Android's call log and the contacts stored on the phone. Everything
 * Parley keeps goes; the app then starts again as new.
 */
@Composable
fun DeleteAllDataDialog(vm: AppViewModel, onDismiss: () -> Unit) {
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

    fun run() {
        if (CallManager.state.value.isNotEmpty()) {
            step = WipeStep.Failed(res.getString(R.string.wipe_in_call))
            return
        }
        scope.launch {
            if (backupFirst) {
                step = WipeStep.Working(res.getString(R.string.wipe_backing_up))
                val b = vm.c.backup.backupNow(scheduled = false)
                if (!b.ok) {
                    step = WipeStep.Failed(res.getString(R.string.wipe_backup_failed, b.message))
                    return@launch
                }
            }
            step = WipeStep.Working(res.getString(R.string.wipe_deleting))
            val app = context.applicationContext
            runCatching { WorkManager.getInstance(app).cancelAllWork().result.get() }
            runCatching { app.getSystemService(NotificationManager::class.java).cancelAll() }
            runCatching { app.getSystemService(ShortcutManager::class.java).removeAllDynamicShortcuts() }
            vm.c.wipe.wipe(DataWipe.Options(callLog = callLog, phoneContacts = phoneContacts))
            restart(app)
        }
    }

    when (val s = step) {
        WipeStep.Ask -> AlertDialog(
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
                    {
                        val act = context as? FragmentActivity
                        if (vm.settings.value.appLock && act != null) AppLock.authenticate(act, res.getString(R.string.wipe_title)) { ok -> if (ok) run() } else run()
                    },
                    enabled = typed.trim().equals(word, ignoreCase = true),
                ) { Text(stringResource(R.string.wipe_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
        )
        is WipeStep.Working -> AlertDialog(
            onDismissRequest = {},
            title = { Text(s.text) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
        is WipeStep.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.wipe_title)) },
            text = { Text(s.text) },
            confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_done)) } },
        )
    }
}

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
