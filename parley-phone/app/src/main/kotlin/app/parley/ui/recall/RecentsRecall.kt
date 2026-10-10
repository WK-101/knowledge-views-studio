package app.parley.ui.recall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.recall.RecallSource
import app.parley.ui.Destination
import app.parley.ui.ParleyListItem

/**
 * Recall under the Recents search, as under the Contacts search: it runs on its own when no call matches the words,
 * and on request ("Search everything for …" at the end of the calls, or Tools and the launcher shortcut, which open
 * the search with it on). [shows] while it has something to show.
 */
class RecentsRecall internal constructor(
    val state: RecallUi.State,
    val everything: Boolean,
    val shows: Boolean,
    internal val expanded: Set<RecallSource>,
    internal val expand: (RecallSource) -> Unit,
)

/** Feeds the Recents search ([query]; [nothing]: its calls found nobody, null until listed) to `vm.recentsRecall`. */
@Composable
fun rememberRecentsRecall(vm: AppViewModel, query: String, nothing: Boolean?): RecentsRecall {
    val recall = vm.recentsRecall
    LaunchedEffect(query, nothing) {
        vm.recentsSearch.value = query
        vm.recentsFoundNothing.value = nothing.takeIf { query.isNotBlank() }
    }
    val active by recall.active.collectAsStateWithLifecycle()
    val state by recall.state.collectAsStateWithLifecycle()
    val everything by recall.everything.collectAsStateWithLifecycle()
    var expanded by remember(query) { mutableStateOf(emptySet<RecallSource>()) }
    val shows = active && (everything || state.result?.isEmpty == false)
    return RecentsRecall(state, everything, shows, expanded) { expanded = expanded + it }
}

/**
 * After the calls: Recall's results while it shows, else (some calls matched) the one row that widens the search to
 * everything Parley remembers.
 */
fun LazyListScope.recentsRecallSection(vm: AppViewModel, r: RecentsRecall, query: String, matchedCalls: Boolean, open: (Destination) -> Unit) {
    if (query.isBlank()) return
    if (r.shows) {
        recallSection(vm, r.state, query, fallback = !r.everything, r.expanded, r.expand, open, R.string.recall_fallback_header_calls)
    } else if (matchedCalls && !r.everything) {
        item(key = "recall-offer", contentType = "recall-offer") { SearchEverythingRow(query) { vm.recentsRecall.everything.value = true } }
    }
}

@Composable
private fun SearchEverythingRow(query: String, onClick: () -> Unit) {
    ParleyListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        leadingContent = { Icon(Icons.Rounded.ManageSearch, null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(stringResource(R.string.recall_recents_offer, query.trim()), color = MaterialTheme.colorScheme.primary) },
    )
}
