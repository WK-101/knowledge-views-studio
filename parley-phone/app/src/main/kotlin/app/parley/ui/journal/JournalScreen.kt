package app.parley.ui.journal

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.ui.Avatar
import app.parley.ui.EmptyState
import app.parley.ui.Routes
import app.parley.ui.common.Format
import kotlinx.coroutines.launch

private fun actionText(a: String) = when (a) {
    "DELETE" -> "Deleted"
    "EDIT" -> "Edited (version before the change)"
    "MERGE" -> "Merged (version before merging)"
    "SEPARATE" -> "Separated"
    else -> a.lowercase()
}

/** "Recently deleted & changed": 30 days of undo for anything Parley changed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by vm.c.journal.recent().collectAsStateWithLifecycle(emptyList())
    Scaffold(topBar = {
        TopAppBar(title = { Text("Recently deleted & changed") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { p ->
        if (entries.isEmpty()) {
            EmptyState(Icons.Rounded.History, "Nothing here", "Contacts you delete, edit or merge in Parley are kept here for 30 days, so you can undo.", Modifier.padding(p))
            return@Scaffold
        }
        LazyColumn(Modifier.padding(p)) {
            items(entries, key = { it.id }) { e ->
                ListItem(
                    leadingContent = { Avatar(e.displayName, null) },
                    headlineContent = { Text(e.displayName) },
                    supportingContent = { Text("${actionText(e.action)} · ${Format.fullDate(context, e.time)}" + if (e.restored) " · restored" else "") },
                    trailingContent = {
                        TextButton(onClick = {
                            scope.launch {
                                val id = vm.c.journal.restore(e.id)
                                if (id != null) {
                                    vm.toast(if (e.action == "DELETE") "Restored ${e.displayName}" else "Restored the earlier version as a separate contact")
                                    open(Routes.contact(id))
                                } else {
                                    vm.toast("Couldn't restore")
                                }
                            }
                        }) { Text(if (e.action == "DELETE") "Restore" else "Restore copy", color = MaterialTheme.colorScheme.primary) }
                    },
                )
            }
        }
    }
}
