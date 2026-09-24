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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.ui.Avatar
import app.parley.ui.EmptyState
import app.parley.ui.Routes
import app.parley.ui.common.Format
import kotlinx.coroutines.launch
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import app.parley.R

@StringRes private fun actionText(a: String): Int? = when (a) {
    "DELETE" -> R.string.jr_deleted
    "EDIT" -> R.string.jr_edited
    "MERGE" -> R.string.jr_merged
    "SEPARATE" -> R.string.jr_separated
    else -> null
}

/** "Recently deleted & changed": 30 days of undo for anything Parley changed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val entries by vm.c.journal.recent().collectAsStateWithLifecycle(emptyList())
    // U7: scroll-linked top-bar tint.
    val barTint = androidx.compose.material3.TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(barTint.nestedScrollConnection), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.jr_title)) }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) } }, scrollBehavior = barTint)
    }) { p ->
        if (entries.isEmpty()) {
            EmptyState(Icons.Rounded.History, stringResource(R.string.jr_empty_title), stringResource(R.string.jr_empty_text), Modifier.padding(p))
            return@Scaffold
        }
        LazyColumn(Modifier.padding(p)) {
            items(entries, key = { it.id }) { e ->
                ListItem(
                    leadingContent = { Avatar(e.displayName, null) },
                    headlineContent = { Text(e.displayName) },
                    supportingContent = {
                        val line = "${actionText(e.action)?.let { stringResource(it) } ?: e.action.lowercase()} · ${Format.fullDate(context, e.time)}"
                        Text(if (e.restored) stringResource(R.string.jr_restored_suffix, line) else line)
                    },
                    trailingContent = {
                        TextButton(onClick = {
                            scope.launch {
                                val id = vm.c.journal.restore(e.id)
                                if (id != null) {
                                    vm.toast(if (e.action == "DELETE") res.getString(R.string.jr_restored_name, e.displayName) else res.getString(R.string.jr_restored_copy))
                                    open(Routes.contact(id))
                                } else {
                                    vm.toast(res.getString(R.string.jr_restore_failed))
                                }
                            }
                        }) { Text(if (e.action == "DELETE") stringResource(R.string.dc_restore) else stringResource(R.string.jr_restore_copy), color = MaterialTheme.colorScheme.primary) }
                    },
                )
            }
        }
    }
}
