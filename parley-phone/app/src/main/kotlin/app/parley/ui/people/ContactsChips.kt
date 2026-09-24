package app.parley.ui.people

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import androidx.compose.ui.res.stringResource
import app.parley.R

/**
 * Contacts-tab filter row: All · Private · Unlabelled · labels (multi-select, AND/OR) · account, plus shortcuts
 * to the selected label's page and to label management.
 */
@Composable
fun ContactsFilterChips(vm: AppViewModel, showVault: Boolean, vaultHidden: Boolean, open: (String) -> Unit) {
    val filter by vm.people.filter.collectAsStateWithLifecycle()
    val idx by vm.people.index.collectAsStateWithLifecycle()
    val s by vm.people.settings.collectAsStateWithLifecycle()
    val accounts by vm.people.accountChoices.collectAsStateWithLifecycle()
    val labels = idx.labelCounts.keys.sortedBy { it.lowercase() }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(filter.isEmpty && !showVault, { vm.showVault.value = false; vm.people.clearFilter() }, label = { Text(stringResource(R.string.ppl_chip_all)) })
        if (!vaultHidden) {
            FilterChip(
                showVault, { vm.showVault.value = !showVault; vm.people.clearFilter() },
                label = { Text(stringResource(R.string.ppl_chip_private)) },
                leadingIcon = { Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp)) },
            )
        }
        if (accounts.size > 1) {
            var menu by remember { mutableStateOf(false) }
            Box {
                FilterChip(
                    filter.account != null && !showVault, { menu = true },
                    label = { Text(filter.account?.let { a -> stringResource(R.string.ppl_account_count, a, accounts.firstOrNull { it.first == a }?.second ?: 0) } ?: stringResource(R.string.ppl_chip_account)) },
                    leadingIcon = { Icon(Icons.Rounded.AccountCircle, null, Modifier.size(16.dp)) },
                    trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(16.dp)) },
                )
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.ppl_chip_all_accounts)) }, onClick = { menu = false; vm.people.setAccount(null) })
                    accounts.forEach { (label, n) ->
                        DropdownMenuItem({ Text(stringResource(R.string.ppl_account_count, label, n)) }, onClick = { menu = false; vm.showVault.value = false; vm.people.setAccount(label) })
                    }
                }
            }
        }
        if (labels.isNotEmpty()) {
            FilterChip(filter.unlabelled && !showVault, { vm.showVault.value = false; vm.people.setUnlabelled(!filter.unlabelled) }, label = { Text(stringResource(R.string.ppl_chip_unlabelled)) })
        }
        if (filter.labels.size >= 2 && !showVault) {
            AssistChip(
                onClick = { vm.people.update { it.copy(labelMatchAll = !it.labelMatchAll) } },
                label = { Text(if (s.labelMatchAll) stringResource(R.string.ppl_chip_match_all) else stringResource(R.string.ppl_chip_match_any)) },
            )
        }
        labels.forEach { title ->
            FilterChip(
                title in filter.labels && !showVault, { vm.showVault.value = false; vm.people.toggleLabel(title) },
                label = { Text(stringResource(R.string.ppl_account_count, title, idx.labelCounts[title] ?: 0)) },
            )
        }
        if (filter.labels.size == 1 && !showVault) {
            val only = filter.labels.single()
            AssistChip(
                onClick = { open(PeopleRoutes.label(only)) },
                label = { Text(stringResource(R.string.ppl_chip_open, only)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp)) },
            )
        }
        AssistChip(onClick = { open(PeopleRoutes.LABELS) }, label = { Text(stringResource(R.string.ppl_chip_labels)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Label, null, Modifier.size(16.dp)) })
        // Contacts that delete themselves: shown only when there are some.
        val temporary = app.parley.ui.temporary.rememberTemporaryItems(vm).size
        if (temporary > 0) {
            AssistChip(
                onClick = { open(app.parley.ui.Routes.TEMPORARY) },
                label = { Text(stringResource(R.string.ppl_chip_temporary, temporary)) },
                leadingIcon = { Icon(androidx.compose.material.icons.Icons.Rounded.AutoDelete, null, Modifier.size(16.dp)) },
            )
        }
    }
}
