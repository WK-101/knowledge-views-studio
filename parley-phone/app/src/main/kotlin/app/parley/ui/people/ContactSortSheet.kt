package app.parley.ui.people

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.people.ContactSort
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet
import app.parley.ui.Spacing

/** The name of an order, as the sheet and the chip say it. */
@Composable
private fun ContactSort.label(): String = stringResource(
    when (this) {
        ContactSort.NAME -> R.string.agenda_share_search
        ContactSort.RECENTLY_ADDED -> R.string.cs_sort_recent
        ContactSort.MOST_CALLED -> R.string.cs_sort_most_called
        ContactSort.COMPANY -> R.string.cs_sort_company
    },
)

@Composable
private fun ContactSort.help(): String = stringResource(
    when (this) {
        ContactSort.NAME -> R.string.cs_sort_name_help
        ContactSort.RECENTLY_ADDED -> R.string.cs_sort_recent_help
        ContactSort.MOST_CALLED -> R.string.cs_sort_most_called_help
        ContactSort.COMPANY -> R.string.cs_sort_company_help
    },
)

/**
 * Contacts ⋮ › Sort by: by name, recently added, most called or company. Kept in the list itself (not a Settings row)
 * and remembered; private contacts are ordered with everyone else. The A–Z rail shows only in name order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactSortSheet(vm: AppViewModel) {
    val open by vm.people.sortSheet.collectAsStateWithLifecycle()
    if (!open) return
    val current by vm.people.sort.collectAsStateWithLifecycle()
    val close = { vm.people.sortSheet.value = false }
    ParleySheet(onDismissRequest = close, title = stringResource(R.string.cs_sort_title)) {
        Column(Modifier.selectableGroup().padding(bottom = Spacing.xl)) {
            ContactSort.entries.forEach { s ->
                ParleyListItem(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(s == current, role = Role.RadioButton) {
                        vm.people.setSort(s)
                        close()
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { RadioButton(s == current, onClick = null) },
                    headlineContent = { Text(s.label()) },
                    supportingContent = { Text(s.help()) },
                )
            }
        }
    }
}

/** In the Contacts chips while the list isn't in name order: says which order, and opens the sheet to change it. */
@Composable
fun ContactSortChip(vm: AppViewModel) {
    val sort by vm.people.sort.collectAsStateWithLifecycle()
    if (sort == ContactSort.NAME) return
    FilterChip(
        selected = true,
        onClick = { vm.people.sortSheet.value = true },
        label = { Text(sort.label()) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.cs_sort_title), Modifier.size(18.dp)) },
    )
}
