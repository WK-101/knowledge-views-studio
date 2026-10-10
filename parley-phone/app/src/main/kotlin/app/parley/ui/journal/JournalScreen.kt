package app.parley.ui.journal

import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.people.ContactRef
import app.parley.data.vault.PrivateTrash
import app.parley.security.AppLock
import app.parley.ui.ConfirmDialog
import app.parley.ui.Destination
import app.parley.ui.EmptyState
import app.parley.ui.ParleyListItem
import app.parley.ui.PersonRow
import app.parley.ui.Routes
import app.parley.ui.common.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@StringRes private fun actionText(a: String): Int? = when (a) {
    "DELETE" -> R.string.jr_deleted
    "EDIT" -> R.string.jr_edited
    "MERGE" -> R.string.jr_merged
    "SEPARATE" -> R.string.jr_separated
    "MOVE" -> R.string.jr_moved
    else -> null
}

/**
 * The contacts tab of History & undo: 30 days of undo for any contact Parley deleted, edited, merged or separated.
 * A row can also be removed for good (its saved copy is deleted). Deleted private contacts are kept apart, sealed
 * ([PrivateTrash]), and listed only after the vault's own unlock; their edits keep no copy (docs/CONTACT_MODEL.md).
 */
@Composable
fun JournalList(vm: AppViewModel, open: (Destination) -> Unit, onShowSnapshots: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val entries by vm.c.journal.recent().collectAsStateWithLifecycle(emptyList())
    // The row asked about: its journal id and name.
    var removing by remember { mutableStateOf<Pair<Long, String>?>(null) }
    // Deleted private contacts: counted without opening anything; listed only after the vault's own unlock.
    val trash = rememberPrivateTrash(vm, open)
    val privateCount = trash.count
    if (entries.isEmpty() && privateCount == 0) {
        // Nothing to undo yet; the daily snapshots are the other way back.
        EmptyState(
            Icons.Rounded.History, stringResource(R.string.jr_empty_title), stringResource(R.string.jr_empty_text), modifier,
            action = stringResource(R.string.ux_empty_what_changed), onAction = onShowSnapshots,
        )
        return
    }
    LazyColumn(modifier) {
        if (privateCount > 0) privateTrashItems(trash)
        items(entries, key = { it.id }) { e ->
            PersonRow(
                e.displayName, null,
                supportingContent = {
                    val line = "${actionText(e.action)?.let { stringResource(it) } ?: e.action.lowercase()} · ${Format.fullDate(context, e.time)}"
                    Text(if (e.restored) stringResource(R.string.jr_restored_suffix, line) else line)
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ removing = e.id to e.displayName }) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.jr_remove_item)) }
                        TextButton(onClick = {
                            scope.launch {
                                val id = vm.c.journal.restore(e.id)
                                if (id != null) {
                                    vm.toast(
                                        if (e.action == "DELETE") res.getString(
                                            R.string.tm_restored_name, e.displayName,
                                        ) else res.getString(R.string.jr_restored_copy),
                                    )
                                    open(Routes.contact(id))
                                } else {
                                    vm.toast(res.getString(R.string.jr_restore_failed))
                                }
                            }
                        }) { Text(if (e.action == "DELETE") stringResource(R.string.dc_restore) else stringResource(R.string.jr_restore_copy), color = MaterialTheme.colorScheme.primary) }
                    }
                },
            )
        }
    }
    trash.RemoveDialog()
    removing?.let { (id, name) ->
        ConfirmDialog(
            title = stringResource(R.string.jr_remove_title),
            text = stringResource(R.string.jr_remove_change_text, name),
            confirmLabel = stringResource(R.string.jr_remove), destructive = true,
            onConfirm = {
                removing = null
                scope.launch { if (vm.c.undoStorage.forgetContactChange(id)) vm.toast(res.getString(R.string.jr_removed)) }
            },
            onDismiss = { removing = null },
        )
    }
}

/**
 * "Deleted private contacts" in the Contacts tab: counted without opening anything (none in discreet mode), listed
 * only after the vault's own unlock ([unlock]), each restorable or removable for good.
 */
private class PrivateTrashUi(
    private val vm: AppViewModel,
    private val scope: CoroutineScope,
    private val activity: ComponentActivity?,
    private val open: (Destination) -> Unit,
) {
    var count by mutableIntStateOf(0)
    var kept by mutableStateOf<List<PrivateTrash.Kept>?>(null)
    var removing by mutableStateOf<PrivateTrash.Kept?>(null)
    var round by mutableIntStateOf(0)

    fun reload() {
        round++
        if (kept != null) scope.launch { kept = vm.c.privateTrash.list() }
    }

    fun unlock() {
        val a = activity ?: return
        AppLock.authenticateForVault(a) { ok -> if (ok) scope.launch { kept = vm.c.privateTrash.list() } }
    }

    fun restore(k: PrivateTrash.Kept, res: Resources) = scope.launch {
        val id = runCatching { vm.c.privateTrash.restore(k.file) }.getOrNull()
        reload()
        if (id == null) return@launch vm.toast(res.getString(R.string.jr_restore_failed))
        vm.toast(res.getString(R.string.tm_restored_name, k.name))
        open(Routes.contact(ContactRef.Private(id).navId))
    }

    @Composable
    fun RemoveDialog() {
        val k = removing ?: return
        val res = LocalResources.current
        ConfirmDialog(
            title = stringResource(R.string.jr_remove_title),
            text = stringResource(R.string.jr_private_remove_text, k.name),
            confirmLabel = stringResource(R.string.jr_remove), destructive = true,
            onConfirm = {
                removing = null
                scope.launch {
                    vm.c.privateTrash.remove(k.file)
                    reload()
                    vm.toast(res.getString(R.string.jr_removed))
                }
            },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun rememberPrivateTrash(vm: AppViewModel, open: (Destination) -> Unit): PrivateTrashUi {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui = remember(vm) { PrivateTrashUi(vm, scope, context as? ComponentActivity, open) }
    // Discreet mode ("Hide private contacts") hides that there are any, here too.
    val privacy by vm.privacy.collectAsStateWithLifecycle()
    LaunchedEffect(ui.round, privacy.privateHidden) {
        ui.count = if (privacy.privateHidden) 0 else withContext(Dispatchers.IO) { vm.c.privateTrash.count() }
    }
    return ui
}

private fun LazyListScope.privateTrashItems(ui: PrivateTrashUi) {
    item(key = "private") {
        val kept = ui.kept
        val unlock = if (kept == null) Modifier.clickable(onClickLabel = stringResource(R.string.cs_private_unlock)) { ui.unlock() } else Modifier
        ParleyListItem(
            modifier = unlock,
            leadingContent = { Icon(Icons.Rounded.Lock, null) },
            headlineContent = { Text(stringResource(R.string.jr_storage_private)) },
            supportingContent = {
                Text(if (kept == null) pluralStringResource(R.plurals.jr_private_locked, ui.count, ui.count) else stringResource(R.string.jr_private_open))
            },
        )
    }
    items(ui.kept.orEmpty(), key = { "p:" + it.file }) { k ->
        val context = LocalContext.current
        val res = LocalResources.current
        PersonRow(
            k.name, null,
            supportingContent = { Text("${stringResource(R.string.jr_deleted)} · ${Format.fullDate(context, k.deletedAt)}") },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ ui.removing = k }) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.jr_remove_item)) }
                    TextButton({ ui.restore(k, res) }) { Text(stringResource(R.string.dc_restore), color = MaterialTheme.colorScheme.primary) }
                }
            },
        )
    }
}
