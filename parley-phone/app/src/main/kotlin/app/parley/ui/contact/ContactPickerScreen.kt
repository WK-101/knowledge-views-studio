package app.parley.ui.contact

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.TextSearch
import app.parley.common.people.AlphabetIndex
import app.parley.ui.AlphabetIndexDefaults
import app.parley.ui.AlphabetIndexRail
import app.parley.ui.home.ContactRow
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactPickerScreen(vm: AppViewModel, back: () -> Unit, onPick: (Long) -> Unit) {
    val all by vm.contacts.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    ParleyScaffold(topBar = {
        ParleyTopBar(stringResource(R.string.hist_action_add_to_contact), onBack = back)
    }) { p ->
        val shown = all.orEmpty().filter { TextSearch.matches(q, it.displayName) }
        // The A–Z index while nothing is typed, from the first contact on (the search field is row 0).
        val indexed = q.isBlank() && shown.size > AlphabetIndex.MIN_ITEMS
        val entries = remember(shown, indexed) {
            if (indexed) AlphabetIndex.entries(AlphabetIndex.sectionsOf(shown.map { it.sortName }, offset = 1)) else emptyList()
        }
        val state = rememberLazyListState()
        Box(Modifier.padding(p)) {
            LazyColumn(state = state) {
                item {
                    OutlinedTextField(q, { q = it }, placeholder = { Text(stringResource(R.string.blk_search)) }, singleLine = true, modifier = Modifier.padding(16.dp))
                }
                items(shown, key = { it.id }) { c ->
                    Box(Modifier.padding(end = if (indexed) AlphabetIndexDefaults.RowEndPadding else 0.dp)) { ContactRow(c) { onPick(c.id) } }
                }
            }
            if (indexed) AlphabetIndexRail(state, entries, start = 1, headers = false)
        }
    }
}
