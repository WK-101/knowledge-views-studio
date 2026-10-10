package app.parley.ui.home

import android.app.Activity
import android.content.Intent
import android.content.res.Resources
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.LabelOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.SimAccount
import app.parley.common.people.BulkEdit
import app.parley.common.people.BulkEdits
import app.parley.data.AccountRef
import app.parley.common.ux.BackupNudge
import app.parley.ui.ConfirmDialog
import app.parley.ui.ParleyDialog
import app.parley.ui.backup.rememberBackupFirst
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet
import app.parley.ui.Spacing
import app.parley.ui.circle.CircleSnack
import app.parley.ui.circle.CircleSnacks
import app.parley.ui.people.accountLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the edit sheet asks next, after a row of it was chosen. */
private sealed interface EditStep {
    data class RemoveLabel(val labels: List<Pair<String, Int>>) : EditStep
    data class Sim(val sims: List<SimAccount>) : EditStep
    data class Account(val accounts: List<AccountRef>) : EditStep

    /** "Move N contacts to X?", asked before anything moves. */
    data class ConfirmMove(val account: AccountRef) : EditStep
}

/**
 * The selection bar's Edit…: add to or remove from a label, a ringtone, the SIM for calls, and (address-book contacts
 * only) another account, for every selected contact at once, private ones too. Each change says what it did with
 * Undo; device contacts get a copy in History & undo before they change. A move is confirmed first (and offers a
 * backup when the last one is old), since it re-creates each contact. [onAddToLabel] opens the bar's own label picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BulkEditSheet(vm: AppViewModel, chosen: List<ContactSummary>, onAddToLabel: () -> Unit, onDismiss: () -> Unit) {
    val res = LocalResources.current
    val edits = remember(vm, chosen) { BulkEditRunner(vm, res, chosen) }
    val ids = chosen.map { it.id }
    var step by remember { mutableStateOf<EditStep?>(null) }
    var sims by remember { mutableStateOf<List<SimAccount>>(emptyList()) }
    var accounts by remember { mutableStateOf<List<AccountRef>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { vm.c.sims.accounts() to vm.c.contacts.accounts() }.let { (s, a) -> sims = s; accounts = a }
    }
    val index by vm.people.index.collectAsStateWithLifecycle()
    val removable = remember(ids, index) { BulkEdits.removableLabels(ids) { index.extras[it]?.labels.orEmpty() } }
    val movePlan = BulkEdits.plan(BulkEdit.MOVE_ACCOUNT, ids)

    val tonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        @Suppress("DEPRECATION") // The ringtone picker returns its pick in an untyped extra.
        val picked = r.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        onDismiss()
        // "Default ringtone" means the phone's own: no ringtone of their own.
        edits.ringtone(picked?.takeIf { it != Settings.System.DEFAULT_RINGTONE_URI }?.toString())
    }

    ParleySheet(onDismissRequest = onDismiss, title = pluralStringResource(R.plurals.be_title, ids.size, ids.size)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Spacing.xl)) {
            EditRow(Icons.AutoMirrored.Rounded.Label, stringResource(R.string.sel_add_to_label)) { onDismiss(); onAddToLabel() }
            if (removable.isNotEmpty()) {
                EditRow(Icons.AutoMirrored.Rounded.LabelOff, stringResource(R.string.be_remove_label)) { step = EditStep.RemoveLabel(removable) }
            }
            EditRow(Icons.Rounded.MusicNote, stringResource(R.string.be_ringtone), stringResource(R.string.be_ringtone_sub)) {
                tonePicker.launch(
                    Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false),
                )
            }
            // Only with a choice to make: two SIMs, two accounts and an address-book contact among them.
            if (sims.size > 1) {
                EditRow(Icons.Rounded.SimCard, stringResource(R.string.be_sim), stringResource(R.string.be_sim_sub)) { step = EditStep.Sim(sims) }
            }
            if (accounts.size > 1 && movePlan.ids.isNotEmpty()) {
                val n = movePlan.skippedPrivate
                val sub = if (n > 0) pluralStringResource(R.plurals.sel_private_skipped, n, n) else null
                EditRow(Icons.AutoMirrored.Rounded.DriveFileMove, stringResource(R.string.be_move), sub) { step = EditStep.Account(accounts) }
            }
        }
    }
    // Asked "Back up first?" before a move of many, like Merge and Delete.
    val backupFirst = rememberBackupFirst(vm)
    step?.let { s ->
        EditStepPicker(
            vm, s, edits, movePlan.ids.size, close = { step = null }, onDismiss = onDismiss,
            onPickAccount = { step = EditStep.ConfirmMove(it) },
            onConfirmMove = { a ->
                step = null
                backupFirst.ask(movePlan.ids.size, BackupNudge.LARGE_DELETE) {
                    onDismiss()
                    edits.move(a)
                }
            },
        )
    }
}

/** The short list a row of the sheet asks from: which label, which SIM, which account. */
@Composable
@Suppress("LongParameterList") // The sheet's steps and what each one leads to.
private fun EditStepPicker(
    vm: AppViewModel,
    s: EditStep,
    edits: BulkEditRunner,
    moving: Int,
    close: () -> Unit,
    onDismiss: () -> Unit,
    onPickAccount: (AccountRef) -> Unit,
    onConfirmMove: (AccountRef) -> Unit,
) {
    fun picked(then: () -> Unit) {
        close()
        onDismiss()
        then()
    }
    when (s) {
        is EditStep.RemoveLabel -> {
            val choices = s.labels.map { (t, n) -> t to pluralStringResource(R.plurals.be_in_label, n, n) }
            Picker(stringResource(R.string.be_remove_label), choices, close) { i -> picked { edits.leaveLabel(s.labels[i].first) } }
        }
        is EditStep.Sim -> {
            val choices = listOf(stringResource(R.string.be_sim_ask) to null) + s.sims.map { it.label to it.subtitle }
            Picker(stringResource(R.string.be_sim), choices, close) { i -> picked { edits.sim(s.sims.getOrNull(i - 1)) } }
        }
        is EditStep.Account -> Picker(stringResource(R.string.be_move), s.accounts.map { vm.accountLabel(it) to null }, close) { i ->
            onPickAccount(s.accounts[i])
        }
        is EditStep.ConfirmMove -> {
            val into = vm.accountLabel(s.account)
            ConfirmDialog(
                title = pluralStringResource(R.plurals.be_move_confirm_title, moving, moving, into),
                text = stringResource(R.string.be_move_confirm_body, into),
                confirmLabel = stringResource(R.string.be_move_confirm),
                onConfirm = { onConfirmMove(s.account) },
                onDismiss = close,
                dismissLabel = stringResource(R.string.main_cancel),
            )
        }
    }
}

/**
 * Runs the sheet's edits in the view model's scope (they outlive the sheet and the selection) and says what each did,
 * with Undo where it can be undone.
 */
private class BulkEditRunner(private val vm: AppViewModel, private val res: Resources, private val chosen: List<ContactSummary>) {
    private val bulk = BulkContactActions(vm.c)
    private val ids = chosen.map { it.id }

    private fun run(block: suspend () -> Unit) {
        vm.viewModelScope.launch { block() }
    }

    private fun said(changed: Int, text: () -> String, undo: suspend () -> Unit) {
        if (changed == 0) vm.toast(res.getString(R.string.be_nothing_changed)) else CircleSnacks.show(CircleSnack(text(), undo))
    }

    fun ringtone(tone: String?) = run {
        val before = bulk.setRingtone(ids, tone)
        said(before.size, { res.getQuantityString(R.plurals.be_ringtone_set, before.size, before.size) }) { bulk.restoreRingtones(before) }
    }

    fun leaveLabel(title: String) = run {
        val left = bulk.leaveLabel(ids, title)
        said(left.size, { res.getQuantityString(R.plurals.be_label_removed, left.size, left.size, title) }) { bulk.rejoinLabel(left, title) }
    }

    fun sim(sim: SimAccount?) = run {
        val numbers = chosen.flatMap { c -> c.phones.map { it.number } }
        if (numbers.isEmpty()) {
            vm.toast(res.getString(R.string.sel_no_numbers))
            return@run
        }
        val before = bulk.setSim(numbers, sim?.id)
        val name = sim?.label ?: res.getString(R.string.be_sim_ask)
        said(before.size, { res.getQuantityString(R.plurals.be_sim_set, before.size, before.size, name) }) { bulk.restoreSims(before) }
    }

    /**
     * Says what the move did, contact by contact kind, with Undo when something moved (each moved contact goes back
     * into the accounts it was in; History & undo keeps them too).
     */
    fun move(target: AccountRef) {
        vm.toast(res.getString(R.string.be_moving))
        run {
            val r = bulk.moveToAccount(ids, target, chosen.associate { it.id to it.displayName })
            vm.selection.value = emptySet()
            val into = vm.accountLabel(r.redirectedTo ?: target)
            val text = listOfNotNull(
                if (r.moved > 0) res.getQuantityString(R.plurals.be_moved, r.moved, r.moved, into) else null,
                if (r.unchanged > 0) res.getQuantityString(R.plurals.be_move_already, r.unchanged, r.unchanged, vm.accountLabel(target)) else null,
                if (r.keptReadOnly > 0) res.getQuantityString(R.plurals.be_move_kept_copies, r.keptReadOnly, r.keptReadOnly) else null,
                if (r.notMovable.isNotEmpty()) {
                    res.getQuantityString(R.plurals.be_move_not_movable, r.notMovable.size, r.notMovable.size, r.notMovable.first())
                } else {
                    null
                },
                if (r.failed.isNotEmpty()) res.getQuantityString(R.plurals.be_move_failed, r.failed.size, r.failed.size, r.failed.first()) else null,
                if (r.skippedPrivate > 0) res.getQuantityString(R.plurals.sel_private_skipped, r.skippedPrivate, r.skippedPrivate) else null,
            ).joinToString(". ").ifEmpty { res.getString(R.string.be_nothing_changed) }
            if (r.undo.isNotEmpty()) CircleSnacks.show(CircleSnack(text) { bulk.undoMove(r.undo) }) else vm.toast(text)
        }
    }
}

@Composable
private fun EditRow(icon: ImageVector, title: String, sub: String? = null, onClick: () -> Unit) {
    ParleyListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = sub?.let { { Text(it) } },
    )
}

/** One choice from a short list ([choices]: title and an optional line under it). */
@Composable
private fun Picker(title: String, choices: List<Pair<String, String?>>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                choices.forEachIndexed { i, (t, sub) ->
                    ParleyListItem(
                        modifier = Modifier.clickable { onPick(i) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(t) },
                        supportingContent = sub?.let { { Text(it) } },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
}
