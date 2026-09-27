package app.parley.ui.history

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.R
import app.parley.data.history.TrashBatch
import app.parley.ui.EmptyState
import app.parley.ui.common.Format
import kotlinx.coroutines.launch

/** The calls tab of History & undo: calls deleted in Parley, restorable for 30 days, one row per deletion. */
@Composable
fun DeletedCallsList(vm: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var trash by remember { mutableStateOf<List<TrashBatch>?>(null) }
    LaunchedEffect(Unit) { trash = vm.c.history.trashBatches() }
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
                    TextButton({
                        scope.launch {
                            val n = vm.c.history.undoDelete(b.batchId)
                            vm.toast(if (n > 0) res.getQuantityString(R.plurals.hist_restored_calls, n, n) else res.getString(R.string.hist_restore_failed_default))
                            trash = vm.c.history.trashBatches()
                        }
                    }) { Text(stringResource(R.string.dc_restore), color = MaterialTheme.colorScheme.primary) }
                },
            )
        }
    }
}
