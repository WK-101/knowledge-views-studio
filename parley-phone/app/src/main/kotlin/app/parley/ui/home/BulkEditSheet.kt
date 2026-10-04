package app.parley.ui.home

import android.app.Activity
import android.content.Intent
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
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.SimAccount
import app.parley.common.people.BulkEdit
import app.parley.common.people.BulkEdits
import app.parley.data.AccountRef
import app.parley.ui.ParleyDialog
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
}

/**
 * The selection bar's Edit…: add to or remove from a label, a ringtone, the SIM for calls, and (address-book contacts
 * only) another account, for every selected contact at once, private ones too. Each change says what it did with
 * Undo (a move points to History & undo instead, which keeps the old copies); device contacts get a copy in History &
 * undo before they change. [onAddToLabel] opens the bar's own label picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BulkEditSheet(vm: AppViewModel, chosen: List<ContactSummary>, onAddToLabel: () -> Unit, onDismiss: () -> Unit) {
    val res = LocalResources.current
    val bulk = remember(vm) { BulkContactActions(vm.c) }
    val ids = chosen.map { it.id }
    var step by remember { mutableStateOf<EditStep?>(null) }
    var sims by remember { mutableStateOf<List<SimAccount>>(emptyList()) }
    var accounts by remember { mutableStateOf<List<AccountRef>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { vm.c.sims.accounts() to vm.c.contacts.accounts() }.let { (s, a) -> sims = s; accounts = a }
    }
    val index = vm.people.index.value
    val removable = remember(ids) { BulkEdits.removableLabels(ids) { index.extras[it]?.labels.orEmpty() } }
    val movePlan = BulkEdits.plan(BulkEdit.MOVE_ACCOUNT, ids)
    // Work that outlives the sheet (and the selection): the view model's scope.
    fun run(block: suspend () -> Unit) {
        vm.viewModelScope.launch { block() }
    }
    fun done(text: String, undo: (suspend () -> Unit)?) = CircleSnacks.show(CircleSnack(text, undo))

    val tonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        @Suppress("DEPRECATION")
        val picked = r.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        // "Default ringtone" means the phone's own: no ringtone of their own.
        val tone = picked?.takeIf { it != Settings.System.DEFAULT_RINGTONE_URI }?.toString()
        onDismiss()
        run {
            val before = bulk.setRingtone(ids, tone)
            if (before.isEmpty()) vm.toast(res.getString(R.string.be_nothing_changed))
            else done(res.getQuantityString(R.plurals.be_ringtone_set, before.size, before.size)) { bulk.restoreRingtones(before) }
        }
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
            if (sims.size > 1) EditRow(Icons.Rounded.SimCard, stringResource(R.string.be_sim), stringResource(R.string.be_sim_sub)) { step = EditStep.Sim(sims) }
            if (accounts.size > 1 && movePlan.ids.isNotEmpty()) {
                val sub = if (movePlan.skippedPrivate > 0) res.getQuantityString(R.plurals.sel_private_skipped, movePlan.skippedPrivate, movePlan.skippedPrivate) else null
                EditRow(Icons.AutoMirrored.Rounded.DriveFileMove, stringResource(R.string.be_move), sub) { step = EditStep.Account(accounts) }
            }
        }
    }

    when (val s = step) {
        is EditStep.RemoveLabel -> Picker(stringResource(R.string.be_remove_label), s.labels.map { (t, n) -> t to pluralStringResource(R.plurals.be_in_label, n, n) }, { step = null }) { i ->
            val title = s.labels[i].first
            step = null
            onDismiss()
            run {
                val left = bulk.leaveLabel(ids, title)
                if (left.isEmpty()) vm.toast(res.getString(R.string.be_nothing_changed))
                else done(res.getQuantityString(R.plurals.be_label_removed, left.size, left.size, title)) { bulk.rejoinLabel(left, title) }
            }
        }
        is EditStep.Sim -> {
            val choices = listOf(stringResource(R.string.be_sim_ask) to null) + s.sims.map { it.label to it.subtitle }
            Picker(stringResource(R.string.be_sim), choices, { step = null }) { i ->
                val sim = s.sims.getOrNull(i - 1)
                step = null
                onDismiss()
                run {
                    val numbers = chosen.flatMap { c -> c.phones.map { it.number } }
                    if (numbers.isEmpty()) {
                        vm.toast(res.getString(R.string.sel_no_numbers))
                        return@run
                    }
                    val before = bulk.setSim(numbers, sim?.id)
                    if (before.isEmpty()) vm.toast(res.getString(R.string.be_nothing_changed))
                    else done(res.getQuantityString(R.plurals.be_sim_set, before.size, before.size, sim?.label ?: res.getString(R.string.be_sim_ask))) { bulk.restoreSims(before) }
                }
            }
        }
        is EditStep.Account -> Picker(stringResource(R.string.be_move), s.accounts.map { vm.accountLabel(it) to null }, { step = null }) { i ->
            val target = s.accounts[i]
            step = null
            onDismiss()
            val names = chosen.associate { it.id to it.displayName }
            vm.toast(res.getString(R.string.be_moving))
            run {
                val r = bulk.moveToAccount(ids, target, names)
                vm.selection.value = emptySet()
                vm.toast(
                    listOfNotNull(
                        if (r.moved > 0) res.getQuantityString(R.plurals.be_moved, r.moved, r.moved, vm.accountLabel(r.redirectedTo ?: target)) else null,
                        if (r.moved == 0 && r.failed.isEmpty()) res.getString(R.string.be_nothing_changed) else null,
                        if (r.failed.isNotEmpty()) res.getQuantityString(R.plurals.be_move_failed, r.failed.size, r.failed.size, r.failed.first()) else null,
                        if (r.skippedPrivate > 0) res.getQuantityString(R.plurals.sel_private_skipped, r.skippedPrivate, r.skippedPrivate) else null,
                    ).joinToString(". "),
                )
            }
        }
        null -> Unit
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
