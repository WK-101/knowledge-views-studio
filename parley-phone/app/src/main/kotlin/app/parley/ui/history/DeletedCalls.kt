package app.parley.ui.history

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.AppViewModel
import app.parley.R
import app.parley.data.history.TrashBatch
import app.parley.ui.ConfirmDialog
import app.parley.ui.EmptyState
import app.parley.ui.common.Format
import kotlinx.coroutines.launch

/**
 * The calls tab of History & undo: calls deleted in Parley, restorable for 30 days, one row per deletion. Each row can
 * also be removed for good; [reload] changes after the ⋮ menu cleared them.
 */
@Composable
fun DeletedCallsList(vm: AppViewModel, modifier: Modifier = Modifier, reload: Int = 0) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var trash by remember { mutableStateOf<List<TrashBatch>?>(null) }
    var removing by remember { mutableStateOf<TrashBatch?>(null) }
    LaunchedEffect(reload) { trash = vm.c.history.trashBatches() }
    val list = trash ?: return
    if (list.isEmpty()) {
        EmptyState(Icons.Rounded.History, stringResource(R.string.hist_recently_deleted), stringResource(R.string.hist_recently_deleted_empty), modifier)
        return
    }
    LazyColumn(modifier) {
        items(list, key = { it.batchId }) { b ->
            ListItem(
                leadingContent = { Icon(Icons.Rounded.History, null) },
                headlineContent = { Text(pluralStringResource(R.plurals.hist_calls_deleted, b.count, b.count)) },
                supportingContent = { Text(Format.fullDate(context, b.deletedAt)) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ removing = b }) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.jr_remove_item)) }
                        TextButton({
                            scope.launch {
                                val n = vm.c.history.undoDelete(b.batchId)
                                vm.toast(
                                    if (n > 0) res.getQuantityString(
                                        R.plurals.hist_restored_calls, n, n,
                                    ) else res.getString(R.string.hist_restore_failed_default),
                                )
                                trash = vm.c.history.trashBatches()
                            }
                        }) { Text(stringResource(R.string.dc_restore), color = MaterialTheme.colorScheme.primary) }
                    }
                },
            )
        }
    }
    removing?.let { b ->
        ConfirmDialog(
            title = stringResource(R.string.jr_remove_title),
            text = pluralStringResource(R.plurals.jr_remove_calls_text, b.count, b.count),
            confirmLabel = stringResource(R.string.jr_remove), destructive = true,
            onConfirm = {
                removing = null
                scope.launch {
                    vm.c.undoStorage.forgetDeletedCalls(b.batchId)
                    vm.toast(res.getString(R.string.jr_removed))
                    trash = vm.c.history.trashBatches()
                }
            },
            onDismiss = { removing = null },
        )
    }
}
