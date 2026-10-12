package app.parley.ui.people

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.TextSearch
import app.parley.ui.Avatar
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet
import app.parley.ui.PrivateMarked
import app.parley.ui.Spacing
import app.parley.ui.avatarSize

/**
 * Choosing several people at once: a search (names and numbers), a tick for each, and one button that says how many
 * ([confirm] of the count). [people] are the ones to choose from: the lists' own contacts (`vm.everyone`), so private
 * contacts are among them, with the lock badge, only while they show. [initial] are ticked when it opens. Used by a
 * label's "Add people" and a call round's "Choose people…" and "Add people".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeoplePickerSheet(
    title: String,
    people: List<ContactSummary>,
    confirm: @Composable (count: Int) -> String,
    onDismiss: () -> Unit,
    initial: Set<Long> = emptySet(),
    onPick: (List<ContactSummary>) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(initial.toList()) }
    val shown = remember(people, query) { people.filter { TextSearch.matches(query, it.displayName, it.phones.map { p -> p.number }) } }
    ParleySheet(onDismissRequest = onDismiss, title = title) {
        OutlinedTextField(
            query, { query = it }, placeholder = { Text(stringResource(R.string.action_search)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
        )
        LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = Spacing.s)) {
            items(shown, key = { it.id }) { c ->
                val on = c.id in picked
                ParleyListItem(
                    modifier = Modifier.toggleable(on, role = Role.Checkbox) { v -> picked = if (v) picked + c.id else picked - c.id },
                    leadingContent = { PrivateMarked(c.id < 0) { Avatar(c.displayName, c.photoUri, avatarSize()) } },
                    headlineContent = { Text(c.displayName) },
                    trailingContent = { Checkbox(on, onCheckedChange = null) },
                )
            }
        }
        Button(
            onClick = {
                val ids = picked.toSet()
                onPick(people.filter { it.id in ids })
            },
            enabled = picked.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(Spacing.l),
        ) { Text(confirm(picked.size)) }
    }
}
