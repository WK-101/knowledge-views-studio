package app.parley.ui.people

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.TextSearch
import app.parley.ui.Avatar
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet
import app.parley.ui.PrivateBadge
import app.parley.ui.Spacing
import app.parley.ui.avatarSize
import app.parley.ui.home.BulkContactActions
import kotlinx.coroutines.launch

/**
 * "Add people" on a label's page: everyone not in it yet, a search, and a tick for each. They join the label in each
 * account it has (a private contact as Parley's own membership), with Undo. A shared label never takes a private
 * contact: it says so, and adds the others.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddPeopleSheet(vm: AppViewModel, title: String, members: Set<Long>, onDismiss: () -> Unit) {
    val res = LocalResources.current
    val all by vm.everyone.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    val candidates = remember(all, members) { all.orEmpty().filter { it.id !in members } }
    val shown = remember(candidates, query) { candidates.filter { TextSearch.matches(query, it.displayName, it.phones.map { p -> p.number }) } }
    ParleySheet(onDismissRequest = onDismiss, title = stringResource(R.string.lbl_add_people_title, title)) {
        OutlinedTextField(
            query, { query = it }, placeholder = { Text(stringResource(R.string.main_search)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
        )
        LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = Spacing.s)) {
            items(shown, key = { it.id }) { c ->
                val on = c.id in picked
                ParleyListItem(
                    modifier = Modifier.toggleable(on, role = Role.Checkbox) { v -> picked = if (v) picked + c.id else picked - c.id },
                    leadingContent = {
                        Box {
                            Avatar(c.displayName, c.photoUri, avatarSize())
                            if (c.id < 0) PrivateBadge(Modifier.align(Alignment.BottomEnd))
                        }
                    },
                    headlineContent = { Text(c.displayName) },
                    trailingContent = { Checkbox(on, onCheckedChange = null) },
                )
            }
        }
        Button(
            onClick = {
                val ids = picked
                onDismiss()
                val c = vm.c
                c.scope.launch {
                    // Private contacts are never shared: a shared label refuses them, and says why.
                    c.sharedLabels.load()
                    val refused = c.sharedLabels.refusedPrivate(title, ids)
                    val joining = ids - refused
                    val bulk = BulkContactActions(c)
                    if (joining.isNotEmpty()) bulk.rejoinLabel(joining, title)
                    val text = if (refused.isNotEmpty()) {
                        res.getQuantityString(R.plurals.shl_private_refused, refused.size, refused.size)
                    } else {
                        res.getQuantityString(R.plurals.lbl_added_people, joining.size, joining.size, title)
                    }
                    if (joining.isEmpty()) vm.toast(text) else vm.offerUndo(text) { bulk.unjoinLabel(joining, title) }
                }
            },
            enabled = picked.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(Spacing.l),
        ) {
            Text(if (picked.isEmpty()) stringResource(R.string.lbl_add_people) else pluralStringResource(R.plurals.lbl_add_n, picked.size, picked.size))
        }
    }
}
