package app.parley.ui.backup

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.common.backup.MergePlan
import app.parley.common.backup.RecoveryKey
import app.parley.common.backup.RestoreMode
import app.parley.common.backup.Unlock
import app.parley.common.backup.WrongKeyException
import app.parley.data.backup.OpenedBackup
import app.parley.data.backup.RestoreOptions
import app.parley.ui.common.Format
import kotlinx.coroutines.launch
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R

private sealed interface Step {
    data object Unlock : Step
    data class Working(val text: String) : Step
    data class Options(val opened: OpenedBackup) : Step
    data class Preview(val opened: OpenedBackup, val plan: MergePlan, val options: RestoreOptions) : Step
    data class Done(val text: String) : Step
}

/** Unlock → choose what to restore → preview (new / updated / identical / conflicts) → restore → report. */
@Composable
fun RestoreFlow(vm: AppViewModel, uri: Uri, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step>(Step.Unlock) }
    var secret by remember { mutableStateOf("") }
    var useRecovery by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val repo = vm.c.backup

    when (val s = step) {
        Step.Unlock -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text(stringResource(R.string.rst_open_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PassField(if (useRecovery) stringResource(R.string.rst_recovery_key) else stringResource(R.string.bkp_pass_title), secret) { secret = it; error = null }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(useRecovery, { useRecovery = it; secret = "" })
                        Text("  " + stringResource(R.string.rst_use_recovery))
                    }
                }
            },
            confirmButton = {
                TextButton({
                    val unlock = if (useRecovery) {
                        runCatching { Unlock.Recovery(RecoveryKey.parse(secret)) }.getOrElse { error = context.getString(R.string.rst_not_recovery); return@TextButton }
                    } else {
                        Unlock.Passphrase(secret.toCharArray())
                    }
                    step = Step.Working(context.getString(R.string.rst_decrypting))
                    scope.launch {
                        step = try {
                            Step.Options(repo.open(uri, unlock))
                        } catch (_: WrongKeyException) {
                            error = context.getString(if (useRecovery) R.string.rst_wrong_recovery else R.string.rst_wrong_pass)
                            Step.Unlock
                        } catch (e: Exception) {
                            error = context.getString(R.string.rst_damaged, e.message.orEmpty())
                            Step.Unlock
                        }
                    }
                }, enabled = secret.isNotBlank()) { Text(stringResource(R.string.rst_open)) }
            },
            dismissButton = { TextButton(onDone) { Text(stringResource(R.string.dc_cancel)) } },
        )
        is Step.Working -> AlertDialog(
            onDismissRequest = {},
            title = { Text(s.text) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
        is Step.Options -> {
            var mode by remember { mutableStateOf(RestoreMode.MERGE) }
            var o by remember { mutableStateOf(RestoreOptions()) }
            val c = s.opened.counts
            AlertDialog(
                onDismissRequest = onDone,
                title = { Text(stringResource(R.string.rst_backup_from, Format.fullDate(context, s.opened.createdAt))) },
                text = {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.rst_how), style = MaterialTheme.typography.titleSmall)
                        listOf(
                            RestoreMode.MERGE to stringResource(R.string.rst_mode_merge),
                            RestoreMode.ADD_ALL to stringResource(R.string.rst_mode_add_all),
                            RestoreMode.REPLACE to stringResource(R.string.rst_mode_replace),
                        ).forEach { (m, label) ->
                            Row(Modifier.fillMaxWidth().clickable { mode = m }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(mode == m, { mode = m }); Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text(stringResource(R.string.rst_what), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding8())
                        fun count(label: String, k: String) = c[k]?.let { context.getString(R.string.rst_with_count, label, it.toInt()) } ?: label
                        Check(count(stringResource(R.string.rst_contacts), "contacts"), o.contacts) { o = o.copy(contacts = it) }
                        Check(count(stringResource(R.string.rst_call_history), "calllog"), o.callLog) { o = o.copy(callLog = it) }
                        Check(stringResource(R.string.rst_blocking), o.blocking) { o = o.copy(blocking = it) }
                        Check(stringResource(R.string.rst_speed_dial), o.speedDial) { o = o.copy(speedDial = it) }
                        Check(stringResource(R.string.rst_private), o.vault) { o = o.copy(vault = it) }
                        Check(stringResource(R.string.rst_settings), o.settings) { o = o.copy(settings = it) }
                    }
                },
                confirmButton = {
                    TextButton({
                        val opts = o.copy(mode = mode)
                        step = Step.Working(context.getString(R.string.rst_comparing))
                        scope.launch { step = Step.Preview(s.opened, repo.plan(s.opened, mode), opts) }
                    }) { Text(stringResource(R.string.dc_next)) }
                },
                dismissButton = { TextButton(onDone) { Text(stringResource(R.string.dc_cancel)) } },
            )
        }
        is Step.Preview -> {
            val sum = s.plan.summary
            var applyConflicts by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = onDone,
                title = { Text(stringResource(R.string.rst_ready)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (s.options.contacts) {
                            Text(pluralStringResource(R.plurals.rst_new, sum.new, sum.new))
                            Text(pluralStringResource(R.plurals.rst_enrich, sum.enrich, sum.enrich, sum.rowsToAdd))
                            Text(pluralStringResource(R.plurals.rst_identical, sum.identical, sum.identical))
                            if (sum.conflict > 0) {
                                Text(pluralStringResource(R.plurals.rst_conflict, sum.conflict, sum.conflict))
                                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(applyConflicts, { applyConflicts = it }); Text(stringResource(R.string.rst_apply_conflicts)) }
                            }
                            if (sum.toDelete > 0) Text(pluralStringResource(R.plurals.rst_to_delete, sum.toDelete, sum.toDelete), color = MaterialTheme.colorScheme.error)
                        }
                        Text(stringResource(R.string.rst_merge_note), style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton({
                        step = Step.Working(context.getString(R.string.rst_restoring))
                        scope.launch {
                            val report = repo.restore(s.opened, s.plan, s.options.copy(applyConflicts = applyConflicts))
                            step = Step.Done(report.summary(context.resources))
                        }
                    }) { Text(stringResource(R.string.dc_restore)) }
                },
                dismissButton = { TextButton(onDone) { Text(stringResource(R.string.dc_cancel)) } },
            )
        }
        is Step.Done -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text(stringResource(R.string.rst_finished)) },
            text = { Text(s.text) },
            confirmButton = { TextButton(onDone) { Text(stringResource(R.string.dc_done)) } },
            dismissButton = { TextButton({ scope.launch { repo.undoLastRestore(); vm.toast(context.getString(R.string.rst_undone)); onDone() } }) { Text(stringResource(R.string.dc_undo)) } },
        )
    }
}

private fun Modifier.padding8() = this.then(Modifier.padding(top = 12.dp))

@Composable
private fun Check(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, onChange); Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
