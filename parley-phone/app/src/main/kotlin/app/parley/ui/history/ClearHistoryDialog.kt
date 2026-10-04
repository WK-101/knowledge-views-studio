package app.parley.ui.history

import app.parley.ui.Destination
import androidx.compose.ui.platform.LocalResources
import app.parley.common.PhoneIdentity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.jobs.UserErrorText
import app.parley.ui.ParleyListItem
import kotlinx.coroutines.CancellationException
import app.parley.common.CallEntry
import app.parley.common.calls.ClearHistory
import app.parley.common.calls.ClearScope
import app.parley.common.history.ExportFormat
import app.parley.ui.Routes
import app.parley.ui.LinkRow
import app.parley.ui.settings.settingSummary
import app.parley.ui.settings.settingTitle
import kotlinx.coroutines.launch
import app.parley.ui.ParleyDialog

/** Settings › Recents & history › Clear call history. */
@Composable
fun ClearHistoryRow(vm: AppViewModel, open: (Destination) -> Unit, icon: ImageVector? = null) {
    var show by remember { mutableStateOf(false) }
    LinkRow(settingTitle("clear_history"), settingSummary("clear_history"), icon) { show = true }
    if (show) ClearHistoryDialog(vm, shown = null, open = open) { show = false }
}

private enum class ClearStep { SCOPE, EXPORT, CONFIRM }

/**
 * Which calls (all, unknown numbers, missed, or what Recents shows now), then "Export first?" (a CSV file, or an
 * encrypted backup), then a confirmation. Deleted calls stay in Recently deleted for 30 days, with Undo.
 * Calls with private contacts live in the vault and are never touched here.
 */
@Composable
fun ClearHistoryDialog(vm: AppViewModel, shown: List<CallEntry>?, open: (Destination) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val all by vm.c.history.calls.collectAsStateWithLifecycle()
    val vault by vm.c.vault.contacts.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val contactsAllowed by vm.hasContactsPermission.collectAsStateWithLifecycle()
    val iso = vm.countryIso
    val vaultKeys = remember(vault) { PhoneIdentity.KnownSet(vault.flatMap { v -> v.numbers }, iso) }
    // "Unknown numbers" only once the contacts are really there: before they load, or without the permission, every
    // number would look unknown (and calls with family and friends would go).
    val contactsReady = contactsAllowed && !contacts.isNullOrEmpty()
    val known = remember(contacts) {
        PhoneIdentity.KnownSet(contacts.orEmpty().flatMap { ct -> ct.phones.map { it.number } }.filter { PhoneIdentity.digits(it).length >= 3 }, iso)
    }
    val isKnown = { n: String -> n in known }
    // Private contacts' calls are never cleared here, not even before the vault has moved them out of the system log.
    val isPrivate = { n: String -> n in vaultKeys }
    val shownIds = remember(shown) { shown?.map { it.id }?.toSet().orEmpty() }
    val scopes = buildList {
        // "What Recents shows now" only when filters or a search narrow it down.
        if (shown != null && shown.size < all.orEmpty().size) add(ClearScope.SHOWN)
        add(ClearScope.UNKNOWN_NUMBERS)
        add(ClearScope.MISSED)
        add(ClearScope.ALL)
    }
    val counts = remember(all, shownIds, vaultKeys, known, contactsReady) {
        ClearScope.entries.associateWith { ClearHistory.select(all.orEmpty(), it, isKnown, shownIds, isPrivate, contactsReady).size }
    }
    var picked by remember { mutableStateOf(scopes.first { ClearHistory.available(it, contactsReady) }) }
    var step by remember { mutableStateOf(ClearStep.SCOPE) }
    // The contacts went away while the dialog was open: "Unknown numbers" can't be picked any more.
    LaunchedEffect(contactsReady) {
        if (!ClearHistory.available(picked, contactsReady)) picked = scopes.first { ClearHistory.available(it, contactsReady) }
    }
    var busy by remember { mutableStateOf(false) }
    // The calls are fixed when the user moves on from the scope, so what's exported is what's deleted.
    var chosen by remember { mutableStateOf<List<CallEntry>>(emptyList()) }
    fun selected() = ClearHistory.select(all.orEmpty(), picked, isKnown, shownIds, isPrivate, contactsReady)
    val count = if (step == ClearStep.SCOPE) counts[picked] ?: 0 else chosen.size

    ParleyDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                when (step) {
                    ClearStep.SCOPE -> stringResource(R.string.clear_history_title)
                    ClearStep.EXPORT -> stringResource(R.string.clear_history_export_title)
                    ClearStep.CONFIRM -> pluralStringResource(R.plurals.clear_history_confirm_title, count, count)
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (step) {
                    ClearStep.SCOPE -> Column(Modifier.selectableGroup()) {
                        if (all == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        scopes.forEach { sc ->
                            val n = counts[sc] ?: 0
                            if (ClearHistory.available(sc, contactsReady)) {
                                ChoiceItem(scopeLabel(sc), pluralStringResource(R.plurals.clear_history_calls, n, n), picked == sc) { picked = sc }
                            } else {
                                ChoiceItem(scopeLabel(sc), stringResource(R.string.clear_history_unknown_needs_contacts), selected = false, enabled = false) {}
                            }
                        }
                    }
                    ClearStep.EXPORT -> {
                        Text(stringResource(R.string.clear_history_export_body), style = MaterialTheme.typography.bodyMedium)
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                        ParleyListItem(
                            headlineContent = { Text(stringResource(R.string.hist_export_csv)) },
                            supportingContent = { Text(stringResource(R.string.clear_history_export_csv_sub)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable(enabled = !busy) {
                                busy = true
                                scope.launch {
                                    try {
                                        val list = chosen
                                        val rows = ExportFiles.rows(context, list) { e -> ExportFiles.nameFor(e) { n -> vm.contactFor(n)?.displayName } }
                                        ExportFiles.share(context, ExportFiles.write(context, rows, null, ExportFormat.CSV), ExportFormat.CSV)
                                        step = ClearStep.CONFIRM
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        vm.toast(res.getString(R.string.hist_export_failed, UserErrorText.of(context, e)))
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                        )
                        ParleyListItem(
                            headlineContent = { Text(stringResource(R.string.clear_history_export_backup)) },
                            supportingContent = { Text(stringResource(R.string.clear_history_export_backup_sub)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable(enabled = !busy) {
                                onDismiss()
                                open(Routes.Backup)
                            },
                        )
                    }
                    ClearStep.CONFIRM -> Text(stringResource(R.string.clear_history_confirm_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            when (step) {
                ClearStep.SCOPE -> TextButton({
                    chosen = selected()
                    step = ClearStep.EXPORT
                }, enabled = count > 0) { Text(stringResource(R.string.clear_history_next)) }
                ClearStep.EXPORT -> TextButton({ step = ClearStep.CONFIRM }, enabled = !busy) { Text(stringResource(R.string.clear_history_skip_export)) }
                ClearStep.CONFIRM -> TextButton({
                    vm.deleteCallsWithUndo(chosen, keepPrivate = true)
                    onDismiss()
                }, enabled = count > 0) { Text(stringResource(R.string.clear_history_delete), color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton({ onDismiss() }, enabled = !busy) { Text(stringResource(R.string.clear_history_cancel)) } },
    )
}

@Composable
private fun scopeLabel(s: ClearScope): String = stringResource(
    when (s) {
        ClearScope.ALL -> R.string.clear_history_scope_all
        ClearScope.UNKNOWN_NUMBERS -> R.string.clear_history_scope_unknown
        ClearScope.MISSED -> R.string.clear_history_scope_missed
        ClearScope.SHOWN -> R.string.clear_history_scope_shown
    },
)
