package app.parley.ui.blocking

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.R
import app.parley.blocking.BlockFlow
import app.parley.blocking.BlockingActions
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.blocking.BlockPlan
import app.parley.common.suspendRunCatching
import app.parley.common.sync.shared.SharedLabelMembership
import app.parley.common.ux.DefaultAppFeature
import app.parley.ui.Bidi
import app.parley.ui.ConfirmDialog
import app.parley.ui.ParleyDialog
import app.parley.ui.calls.DefaultAppNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every "Block" in the app: asks once (what will happen), then blocks with Undo. [name] is how the question names a
 * single number (a contact's name); [note] goes on any Parley rule written (a suggestion's reason).
 */
fun askToBlock(numbers: List<String>, name: String? = null, note: String? = null) {
    val list = numbers.filter { it.isNotBlank() }.distinct()
    if (list.isNotEmpty()) BlockingDialogs.show(BlockingDialog.Block(list, name, note))
}

/** Blocks now (the question was asked, or a swipe or selection bar asked its own), with Undo on the snackbar. */
fun blockWithUndo(vm: AppViewModel, numbers: List<String>, name: String? = null, note: String? = null, share: Boolean = true) {
    val res = vm.getApplication<Application>().resources
    vm.viewModelScope.launch {
        val done = suspendRunCatching { BlockFlow.block(vm.c, numbers, note, share = share) }.getOrNull()
        // What else happened, said with the result: emergency numbers left open, "Always allow" rules taken away.
        val notes = listOfNotNull(
            done?.emergency?.size?.takeIf { it > 0 && done.numbers.isNotEmpty() }?.let { res.getQuantityString(R.plurals.blockflow_emergency_left, it, it) },
            res.getString(R.string.blockflow_allow_removed).takeIf { (done?.liftedAllows ?: 0) > 0 },
        )
        fun said(text: String) = (listOf(text) + notes).joinToString(". ")
        when {
            done == null -> vm.toast(res.getString(R.string.vm_couldnt_block))
            done.numbers.isEmpty() && done.emergency.isNotEmpty() -> vm.toast(res.getString(R.string.blockflow_emergency_text))
            done.numbers.isEmpty() -> vm.toast(res.getString(R.string.contacts_swipe_already_blocked))
            done.numbers.size == 1 -> vm.offerUndo(said(res.getString(R.string.vm_blocked, name ?: Bidi.ltr(done.numbers[0]))), done.undo)
            else -> vm.offerUndo(said(res.getQuantityString(R.plurals.blk_blocked_numbers, done.numbers.size, done.numbers.size)), done.undo)
        }
    }
}

/** Every "Unblock": no question (nothing is lost), Undo on the snackbar. */
fun unblockWithUndo(vm: AppViewModel, numbers: List<String>, name: String? = null) {
    val res = vm.getApplication<Application>().resources
    vm.viewModelScope.launch {
        val done = suspendRunCatching { BlockFlow.unblock(vm.c, numbers) }.getOrNull() ?: return@launch
        when (done.numbers.size) {
            0 -> Unit
            1 -> vm.offerUndo(res.getString(R.string.vm_unblocked, name ?: Bidi.ltr(done.numbers[0])), done.undo)
            else -> vm.offerUndo(res.getQuantityString(R.plurals.blockflow_unblocked_many, done.numbers.size, done.numbers.size), done.undo)
        }
    }
}

/** "Always allow" or "Allow for 24 hours" with Undo, which puts back the allowance there was before (or none). */
fun allowWithUndo(vm: AppViewModel, number: String, hours: Int?, text: String) {
    vm.viewModelScope.launch {
        val iso = vm.countryIso
        val before = BlockPlan.exactRules(vm.c.blocks.allRules(), number, iso, RuleKind.ALLOW)
        suspendRunCatching { BlockingActions.allowNumber(vm.c, number, hours) }.onFailure { return@launch }
        vm.offerUndo(text) {
            val now = BlockPlan.exactRules(vm.c.blocks.allRules(), number, iso, RuleKind.ALLOW)
            now.filter { r -> before.none { it.id == r.id } }.forEach { vm.c.blocks.deleteRule(it.id) }
            before.forEach { vm.c.blocks.saveRule(it) }
        }
    }
}

/** Whether every one of [numbers] is an emergency number here: then nothing offers Block or Unblock for them. */
@Composable
fun rememberEmergency(vm: AppViewModel, numbers: List<String>): Boolean {
    val emergency by produceState(false, numbers) {
        value = withContext(Dispatchers.IO) { numbers.any { it.isNotBlank() } && numbers.filter { it.isNotBlank() }.all { BlockFlow.isEmergency(vm.c, it) } }
    }
    return emergency
}

/**
 * Whether any of [numbers] is blocked now (on Android's list or by its own exact rule), kept current as either
 * changes, so each place can offer Unblock instead of Block.
 */
@Composable
fun rememberBlocked(vm: AppViewModel, numbers: List<String>): Boolean {
    val system by vm.c.blocks.systemList.collectAsStateWithLifecycle()
    val rules by vm.c.blocks.rules.collectAsStateWithLifecycle()
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    val blocked by produceState(false, numbers, system, rules, isDefault) {
        value = withContext(Dispatchers.Default) {
            val listed = if (isDefault) system.map { it.number } else emptyList()
            numbers.any { it.isNotBlank() && BlockPlan.now(it, listed, rules, vm.countryIso).blocked }
        }
    }
    return blocked
}

/** The question behind [askToBlock]: what blocking does here, and (for one number) the rule editor for more. */
@Composable
internal fun BlockConfirmDialog(vm: AppViewModel, x: BlockingDialog.Block, dismiss: () -> Unit) {
    // What blocking would change, and whether every number is an emergency number (read together, so the dialog
    // never shows "Already blocked" for one first).
    val loaded by produceState<Pair<List<BlockPlan.Block>, Boolean>?>(null, x) {
        val plans = suspendRunCatching { BlockFlow.plans(vm.c, x.numbers) }.getOrDefault(emptyList())
        value = plans to withContext(Dispatchers.IO) { x.numbers.isNotEmpty() && x.numbers.all { BlockFlow.isEmergency(vm.c, it) } }
    }
    // The labels whose family spam shield shares numbers blocked one by one (a Parley rule, not Android's list).
    val labelStates by vm.c.sharedLabels.states.collectAsStateWithLifecycle()
    var share by rememberSaveable(x) { mutableStateOf(true) }
    val (p, emergencyOnly) = loaded ?: return
    val who = x.name ?: x.numbers.singleOrNull()?.let { Bidi.ltr(it) }
    if (p.isEmpty()) return NothingToBlockDialog(vm, x, who, emergencyOnly, dismiss)
    val count = p.size
    val onSystem = p.all { it.where == BlockPlan.Where.SYSTEM_LIST }
    val sharedWith = if (onSystem) emptyList() else labelStates.filter { it.shieldOn && SharedLabelMembership.syncs(it.membership) }.map { it.title }
    ConfirmDialog(
        title = if (count == 1 && who != null) {
            stringResource(R.string.blockflow_title_one, who)
        } else {
            pluralStringResource(R.plurals.blockflow_title_many, count, count)
        },
        text = null,
        confirmLabel = stringResource(R.string.blk_block),
        onConfirm = { dismiss(); blockWithUndo(vm, p.map { it.number }, x.name, x.note, share = share || sharedWith.isEmpty()) },
        onDismiss = dismiss,
        dismissLabel = stringResource(R.string.main_cancel),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pluralStringResource(if (onSystem) R.plurals.blockflow_system_list else R.plurals.blockflow_parley_rule, count))
                if (p.any { it.liftAllows.isNotEmpty() }) {
                    Text(stringResource(R.string.blockflow_lifts_allow), style = MaterialTheme.typography.bodySmall)
                }
                if (!onSystem) DefaultAppNote(vm, DefaultAppFeature.BLOCKING, inset = false)
                // Said at the moment of blocking, with a way out for this block only (no setting).
                if (sharedWith.isNotEmpty()) {
                    Text(pluralStringResource(R.plurals.blockflow_also_shared, count, sharedWith.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
                    Row(
                        Modifier.fillMaxWidth().toggleable(!share, role = Role.Checkbox) { share = !it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(!share, onCheckedChange = null)
                        Text(stringResource(R.string.blockflow_dont_share), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                x.numbers.singleOrNull()?.let { n ->
                    TextButton({
                        dismiss()
                        vm.navigate(NavEvent.Route(BlockingRoutes.rule(0, RuleKind.BLOCK, RuleType.EXACT, n)))
                    }) { Text(stringResource(R.string.blockflow_more_ways)) }
                }
            }
        },
    )
}

/**
 * Nothing of [x] can be blocked: emergency numbers, which never are (said calmly, nothing to choose), or numbers blocked
 * already (a notification or the post-call card can't know), with the way back instead.
 */
@Composable
private fun NothingToBlockDialog(vm: AppViewModel, x: BlockingDialog.Block, who: String?, emergency: Boolean, dismiss: () -> Unit) {
    if (emergency) {
        ParleyDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.blockflow_emergency_title)) },
            text = { Text(stringResource(R.string.blockflow_emergency_text)) },
            confirmButton = { TextButton(dismiss) { Text(stringResource(R.string.main_ok)) } },
        )
        return
    }
    ConfirmDialog(
        title = stringResource(R.string.blockflow_already_title),
        text = who?.let { stringResource(R.string.blockflow_already_text, it) },
        confirmLabel = stringResource(R.string.blk_unblock),
        onConfirm = { dismiss(); unblockWithUndo(vm, x.numbers, x.name) },
        onDismiss = dismiss,
        dismissLabel = stringResource(R.string.main_cancel),
    )
}
