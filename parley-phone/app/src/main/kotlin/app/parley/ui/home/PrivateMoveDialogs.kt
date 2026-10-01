package app.parley.ui.home

import android.app.Application
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.security.AppLock
import app.parley.ui.ConfirmDialog
import app.parley.ui.ParleyDialog
import app.parley.ui.Spacing

/**
 * Confirms the bulk "Move to private" for the device contacts [ids] ([names] for the outcome), then starts it in the
 * app's scope ([PrivateMoves]); the selection bar shows its progress and outcome ([PrivateMoveProgress]).
 */
@Composable
fun MoveToPrivateDialog(vm: AppViewModel, ids: List<Long>, names: Map<Long, String>, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = pluralStringResource(R.plurals.move_private_title, ids.size, ids.size),
        text = stringResource(R.string.move_private_text),
        confirmLabel = stringResource(R.string.move_private_move),
        onConfirm = {
            onDismiss()
            // The outcome may arrive after this screen is gone (a rotation, or leaving it): only the app's resources.
            val res = vm.getApplication<Application>().resources
            vm.privateMoves.start(
                ids, names,
                onFinished = { r ->
                    // The ones that couldn't move stay selected, so they can be tried again or dealt with.
                    vm.selection.value = r.failed.map { it.first }.toSet()
                    if (r.moved > 0) vm.toast(res.getQuantityString(R.plurals.move_private_done, r.moved, r.moved))
                    when {
                        r.messengerCopies -> vm.toast(res.getString(R.string.vm_messenger_copies_remain))
                        r.removedAfterSync -> vm.toast(res.getString(R.string.vm_removed_after_sync))
                    }
                },
                onError = { e -> vm.toast(res.getString(R.string.vault_move_failed, e.message.orEmpty())) },
            )
        },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.dc_cancel),
    )
}

/**
 * The bulk move's progress ("Moving 3 of 5 to private…", not dismissable: leaving never stops it) and, when some
 * couldn't be moved, the outcome with their names.
 */
@Composable
fun PrivateMoveProgress(vm: AppViewModel) {
    val state by vm.privateMoves.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? FragmentActivity
    when (val s = state) {
        is PrivateMoves.State.Moving -> MovingDialog(s.done, s.total)
        is PrivateMoves.State.NeedsUnlock -> {
            MovingDialog(s.done, s.total)
            // The vault locked partway: this screen (the one showing now, never one from before a rotation) asks for
            // its unlock, once per attempt; the move waits for the answer.
            LaunchedEffect(s.attempt, activity) {
                if (activity == null) {
                    vm.privateMoves.unlocked(s.attempt, false)
                } else {
                    AppLock.authenticateForVault(activity) { ok -> vm.privateMoves.unlocked(s.attempt, ok) }
                }
            }
        }
        is PrivateMoves.State.Done -> {
            val r = s.result
            val names = r.failed.map { it.second.ifBlank { stringResource(R.string.move_private_unnamed) } }
            ParleyDialog(
                onDismissRequest = vm.privateMoves::dismiss,
                title = { Text(pluralStringResource(R.plurals.move_private_result_title, r.moved, r.moved)) },
                text = {
                    Text(
                        pluralStringResource(R.plurals.move_private_failed, names.size, names.size, names.joinToString(", ")),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = { TextButton(vm.privateMoves::dismiss) { Text(stringResource(R.string.main_ok)) } },
            )
        }
        null -> Unit
    }
}

@Composable
private fun MovingDialog(done: Int, total: Int) {
    ParleyDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Rounded.Lock, null) },
        title = { Text(stringResource(R.string.move_private_moving)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.move_private_progress, done, total), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {},
    )
}
