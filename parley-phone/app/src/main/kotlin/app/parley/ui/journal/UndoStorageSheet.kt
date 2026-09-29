package app.parley.ui.journal

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.backup.SnapshotClearing
import app.parley.common.backup.SnapshotKeep
import app.parley.data.UndoStorage
import app.parley.security.AppLock
import app.parley.ui.ConfirmDialog
import app.parley.ui.InfoRow
import app.parley.ui.ParleySheet
import app.parley.ui.Spacing
import kotlinx.coroutines.launch

/** One of History & undo's stores. */
private enum class UndoStore { CONTACTS, CALLS, SNAPSHOTS }

/**
 * History & undo › ⋮ › Clear history & undo: how much each undo store holds, and a Clear for each. Every clear is
 * confirmed with what goes and that it can't be undone, then asks for the app lock when it is on (like "Delete all
 * Parley data": someone holding the unlocked phone shouldn't be able to wipe the trail quietly). [onCleared] lets the
 * tabs reload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UndoStorageSheet(vm: AppViewModel, onDismiss: () -> Unit, onCleared: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var round by remember { mutableIntStateOf(0) }
    val usage by produceState<UndoStorage.Usage?>(null, round) { value = runCatching { vm.c.undoStorage.usage() }.getOrNull() }
    var asking by remember { mutableStateOf<UndoStore?>(null) }

    fun clear(store: UndoStore, keep: SnapshotKeep) = scope.launch {
        val message = runCatching {
            when (store) {
                UndoStore.CONTACTS -> vm.c.undoStorage.clearContactChanges().let { res.getQuantityString(R.plurals.jr_cleared_contacts, it, it) }
                UndoStore.CALLS -> vm.c.undoStorage.clearDeletedCalls().let { res.getQuantityString(R.plurals.jr_cleared_calls, it, it) }
                UndoStore.SNAPSHOTS -> vm.c.undoStorage.clearSnapshots(keep).let { res.getQuantityString(R.plurals.jr_cleared_snapshots, it, it) }
            }
        }.getOrElse { res.getString(R.string.jr_clear_failed) }
        vm.toast(message)
        round++
        onCleared()
    }

    ParleySheet(onDismissRequest = onDismiss, title = stringResource(R.string.jr_storage_title)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Spacing.l)) {
            Text(
                stringResource(R.string.jr_storage_intro),
                Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.s),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StoreRows(usage) { asking = it }
        }
    }
    asking?.let { store ->
        ClearDialog(store, usage, onDismiss = { asking = null }) { keep ->
            asking = null
            authorize(context, vm) { clear(store, keep) }
        }
    }
}

/** One row per store: what it holds (count · size), or "Nothing kept", and its Clear button. */
@Composable
private fun StoreRows(u: UndoStorage.Usage?, onClear: (UndoStore) -> Unit) {
    val context = LocalContext.current
    val contacts = u?.contactChanges ?: 0
    val calls = u?.deletedCalls ?: 0
    val first = u?.snapshotTimes?.firstOrNull()
    StoreRow(
        Icons.Rounded.Person, stringResource(R.string.jr_storage_contacts),
        u?.takeIf { contacts > 0 }?.let { pluralStringResource(R.plurals.jr_storage_contacts_sub, contacts, contacts, size(context, it.contactBytes)) },
    ) { onClear(UndoStore.CONTACTS) }
    StoreRow(
        Icons.Rounded.Call, stringResource(R.string.jr_storage_calls),
        u?.takeIf { calls > 0 }?.let { pluralStringResource(R.plurals.jr_storage_calls_sub, calls, calls, size(context, it.callBytes)) },
    ) { onClear(UndoStore.CALLS) }
    StoreRow(
        Icons.Rounded.History, stringResource(R.string.jr_storage_snapshots),
        u?.takeIf { first != null }?.let {
            pluralStringResource(R.plurals.jr_storage_snapshots_sub, it.snapshots, it.snapshots, day(context, first ?: 0), size(context, it.snapshotBytes))
        },
    ) { onClear(UndoStore.SNAPSHOTS) }
}

/** The confirmation for clearing [store]: exactly what goes, and that it can't be undone. */
@Composable
private fun ClearDialog(store: UndoStore, u: UndoStorage.Usage?, onDismiss: () -> Unit, onConfirm: (SnapshotKeep) -> Unit) {
    val contacts = u?.contactChanges ?: 0
    val calls = u?.deletedCalls ?: 0
    when (store) {
        UndoStore.CONTACTS -> ConfirmDialog(
            title = stringResource(R.string.jr_clear_contacts_title),
            text = pluralStringResource(R.plurals.jr_clear_contacts_text, contacts, contacts),
            confirmLabel = stringResource(R.string.jr_storage_clear), destructive = true,
            onConfirm = { onConfirm(SnapshotKeep.NONE) }, onDismiss = onDismiss,
        )
        UndoStore.CALLS -> ConfirmDialog(
            title = stringResource(R.string.jr_clear_calls_title),
            text = pluralStringResource(R.plurals.jr_clear_calls_text, calls, calls),
            confirmLabel = stringResource(R.string.jr_storage_clear), destructive = true,
            onConfirm = { onConfirm(SnapshotKeep.NONE) }, onDismiss = onDismiss,
        )
        UndoStore.SNAPSHOTS -> SnapshotClearDialog(u?.snapshotTimes.orEmpty(), onConfirm, onDismiss)
    }
}

@Composable
private fun StoreRow(icon: ImageVector, title: String, sub: String?, onClear: () -> Unit) {
    // A store with nothing in it has no summary and nothing to clear.
    val enabled = sub != null
    InfoRow(title, sub ?: stringResource(R.string.jr_storage_empty), icon) {
        TextButton(onClear, enabled = enabled) {
            Text(stringResource(R.string.jr_storage_clear), color = if (enabled) MaterialTheme.colorScheme.error else Color.Unspecified)
        }
    }
}

/** Which snapshots to clear: older than a month (the default: recent undo stays), all but the latest, or all. */
@Composable
private fun SnapshotClearDialog(times: List<Long>, onConfirm: (SnapshotKeep) -> Unit, onDismiss: () -> Unit) {
    val now = remember { System.currentTimeMillis() }
    val counts = SnapshotKeep.entries.associateWith { SnapshotClearing.toDrop(times, it, now).size }
    var keep by remember { mutableStateOf(SnapshotKeep.entries.firstOrNull { (counts[it] ?: 0) > 0 } ?: SnapshotKeep.NONE) }
    val days = SnapshotClearing.RECENT_DAYS.toInt()
    ConfirmDialog(
        title = stringResource(R.string.jr_clear_snapshots_title),
        text = stringResource(R.string.jr_clear_snapshots_text),
        confirmLabel = stringResource(R.string.jr_storage_clear), destructive = true,
        confirmEnabled = (counts[keep] ?: 0) > 0,
        onConfirm = { onConfirm(keep) },
        onDismiss = onDismiss,
    ) {
        Column(Modifier.padding(top = Spacing.m).selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            SnapshotKeep.entries.forEach { option ->
                val n = counts[option] ?: 0
                val label = when (option) {
                    SnapshotKeep.RECENT -> pluralStringResource(R.plurals.jr_keep_recent, days, days)
                    SnapshotKeep.LATEST -> stringResource(R.string.jr_keep_latest)
                    SnapshotKeep.NONE -> stringResource(R.string.jr_keep_none)
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(keep == option, enabled = n > 0, role = Role.RadioButton) { keep = option },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(keep == option, onClick = null, enabled = n > 0)
                    Column(Modifier.padding(start = Spacing.m)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            pluralStringResource(R.plurals.jr_snapshots_would_go, n, n),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Runs [then] after the app lock when it is on; without the lock, right away. */
private fun authorize(context: Context, vm: AppViewModel, then: () -> Unit) {
    if (!vm.settings.value.appLock) return then()
    val act = context as? FragmentActivity ?: return
    AppLock.authenticate(act, context.getString(R.string.jr_storage_title)) { ok -> if (ok) then() }
}

private fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

private fun day(context: Context, millis: Long): String =
    DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
