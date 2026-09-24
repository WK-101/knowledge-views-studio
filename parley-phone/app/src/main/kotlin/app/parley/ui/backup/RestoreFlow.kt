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
            title = { Text("Open backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PassField(if (useRecovery) "Recovery key" else "Backup passphrase", secret) { secret = it; error = null }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(useRecovery, { useRecovery = it; secret = "" })
                        Text("  Use recovery key instead")
                    }
                }
            },
            confirmButton = {
                TextButton({
                    val unlock = if (useRecovery) {
                        runCatching { Unlock.Recovery(RecoveryKey.parse(secret)) }.getOrElse { error = "That doesn't look like a recovery key"; return@TextButton }
                    } else {
                        Unlock.Passphrase(secret.toCharArray())
                    }
                    step = Step.Working("Decrypting and checking the backup…")
                    scope.launch {
                        step = try {
                            Step.Options(repo.open(uri, unlock))
                        } catch (_: WrongKeyException) {
                            error = if (useRecovery) "Wrong recovery key" else "Wrong passphrase"
                            Step.Unlock
                        } catch (e: Exception) {
                            error = "This file is damaged or not a Parley backup (${e.message})"
                            Step.Unlock
                        }
                    }
                }, enabled = secret.isNotBlank()) { Text("Open") }
            },
            dismissButton = { TextButton(onDone) { Text("Cancel") } },
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
                title = { Text("Backup from ${Format.fullDate(context, s.opened.createdAt)}") },
                text = {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                        Text("How to restore contacts", style = MaterialTheme.typography.titleSmall)
                        listOf(
                            RestoreMode.MERGE to "Merge (recommended): add what's missing, never delete",
                            RestoreMode.ADD_ALL to "Add everything as new contacts",
                            RestoreMode.REPLACE to "Replace: delete current contacts first (a safety backup is made automatically)",
                        ).forEach { (m, label) ->
                            Row(Modifier.fillMaxWidth().clickable { mode = m }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(mode == m, { mode = m }); Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text("What to restore", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding8())
                        fun count(k: String) = c[k]?.let { " ($it)" }.orEmpty()
                        Check("Contacts" + count("contacts"), o.contacts) { o = o.copy(contacts = it) }
                        Check("Call history" + count("calllog"), o.callLog) { o = o.copy(callLog = it) }
                        Check("Blocking rules & blocked numbers", o.blocking) { o = o.copy(blocking = it) }
                        Check("Speed dial & SIM choices", o.speedDial) { o = o.copy(speedDial = it) }
                        Check("Private contacts", o.vault) { o = o.copy(vault = it) }
                        Check("Settings (theme, blocking options…)", o.settings) { o = o.copy(settings = it) }
                    }
                },
                confirmButton = {
                    TextButton({
                        val opts = o.copy(mode = mode)
                        step = Step.Working("Comparing with your current contacts…")
                        scope.launch { step = Step.Preview(s.opened, repo.plan(s.opened, mode), opts) }
                    }) { Text("Next") }
                },
                dismissButton = { TextButton(onDone) { Text("Cancel") } },
            )
        }
        is Step.Preview -> {
            val sum = s.plan.summary
            var applyConflicts by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = onDone,
                title = { Text("Ready to restore") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (s.options.contacts) {
                            Text("${sum.new} new contacts will be added")
                            Text("${sum.enrich} existing contacts get missing details (${sum.rowsToAdd} in total)")
                            Text("${sum.identical} are already identical and stay as they are")
                            if (sum.conflict > 0) {
                                Text("${sum.conflict} look like the same person but differ (e.g. name or photo)")
                                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(applyConflicts, { applyConflicts = it }); Text("Add their missing details anyway") }
                            }
                            if (sum.toDelete > 0) Text("${sum.toDelete} current contacts will be replaced (kept in Recently deleted for 30 days)", color = MaterialTheme.colorScheme.error)
                        }
                        Text("Nothing is deleted in Merge mode, and you can undo the added contacts afterwards.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton({
                        step = Step.Working("Restoring…")
                        scope.launch {
                            if (s.plan.mode == RestoreMode.REPLACE) repo.backupNow(scheduled = false)
                            val report = repo.restore(s.opened, s.plan, s.options.copy(applyConflicts = applyConflicts))
                            step = Step.Done(report.summary())
                        }
                    }) { Text("Restore") }
                },
                dismissButton = { TextButton(onDone) { Text("Cancel") } },
            )
        }
        is Step.Done -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Restore finished") },
            text = { Text(s.text) },
            confirmButton = { TextButton(onDone) { Text("Done") } },
            dismissButton = { TextButton({ scope.launch { repo.undoLastRestore(); vm.toast("Restore undone"); onDone() } }) { Text("Undo") } },
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
