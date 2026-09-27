package app.parley.ui.contact

import androidx.compose.foundation.layout.padding
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
import app.parley.ui.home.ContactRow
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactPickerScreen(vm: AppViewModel, back: () -> Unit, onPick: (Long) -> Unit) {
    val all by vm.contacts.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    ParleyScaffold(topBar = {
        ParleyTopBar(stringResource(R.string.picker_add_to_contact), onBack = back)
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                OutlinedTextField(q, { q = it }, placeholder = { Text(stringResource(R.string.main_search)) }, singleLine = true, modifier = Modifier.padding(16.dp))
            }
            items(all.orEmpty().filter { TextSearch.matches(q, it.displayName) }, key = { it.id }) { c -> ContactRow(c) { onPick(c.id) } }
        }
    }
}
