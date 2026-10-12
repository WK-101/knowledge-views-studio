package app.parley.ui.people

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.ui.home.BulkContactActions
import kotlinx.coroutines.launch

/**
 * "Add people" on a label's page: everyone not in it yet, a search, and a tick for each. They join the label in each
 * account it has (a private contact as Parley's own membership), with Undo. A shared label never takes a private
 * contact: it says so, and adds the others.
 */
@Composable
internal fun AddPeopleSheet(vm: AppViewModel, title: String, members: Set<Long>, onDismiss: () -> Unit) {
    val res = LocalResources.current
    val all by vm.everyone.collectAsStateWithLifecycle()
    val candidates = remember(all, members) { all.orEmpty().filter { it.id !in members } }
    PeoplePickerSheet(
        title = stringResource(R.string.action_add_to, title),
        people = candidates,
        confirm = { n -> if (n == 0) stringResource(R.string.lbl_add_people) else pluralStringResource(R.plurals.lbl_add_n, n, n) },
        onDismiss = onDismiss,
    ) { chosen ->
        val ids = chosen.map { it.id }
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
    }
}
