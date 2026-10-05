package app.parley.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.people.ContactFacets
import app.parley.common.people.Facet
import app.parley.common.people.FacetChoice
import app.parley.common.people.FieldFilter
import app.parley.ui.ListSectionHeader
import app.parley.ui.ParleySheet
import app.parley.ui.Spacing
import app.parley.ui.temporary.rememberTemporaryItems
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** The Filters chip and the chips of the field filters in use (each removes itself), for the Contacts filter row. */
@Composable
fun ContactFieldFilterChips(vm: AppViewModel, vaultHidden: Boolean) {
    val filter by vm.people.filter.collectAsStateWithLifecycle()
    val searchOpen by vm.people.searchOpen.collectAsStateWithLifecycle()
    val choices by vm.people.filterChoices.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(false) }
    val fields = filter.fields
    // Progressive: the Filters chip appears with the search, or while a field filter is in use.
    if (searchOpen || !fields.isEmpty) {
        FilterChip(
            selected = !fields.isEmpty,
            onClick = { sheet = true },
            label = { Text(if (fields.isEmpty) stringResource(R.string.cs_filters) else stringResource(R.string.cs_filters_count, fields.count)) },
            leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)) },
        )
    }
    fields.chosen.forEach { (facet, keys) ->
        keys.forEach { key ->
            val label = fieldValueLabel(facet, key, choices[facet], fields)
            InputChip(
                selected = true,
                onClick = { vm.people.toggleField(facet, key) },
                label = { Text(label) },
                trailingIcon = { Icon(Icons.Rounded.Close, stringResource(R.string.cs_remove_filter, label), Modifier.size(18.dp)) },
            )
        }
    }
    if (sheet) ContactFilterSheet(vm, vaultHidden) { sheet = false }
}

/** How one chosen value reads on its chip ("Portugal", "Birthday in May", "Has an email"). */
@Composable
@Suppress("CyclomaticComplexMethod") // One wording per facet.
private fun fieldValueLabel(facet: Facet, key: String, choices: List<FacetChoice>?, fields: FieldFilter): String {
    val display = choices?.firstOrNull { it.key == key }?.display ?: fields.shownAs(facet, key) ?: key
    return when (facet) {
        Facet.BIRTHDAY_MONTH -> stringResource(R.string.cs_birthday_in, monthName(key))
        Facet.CUSTOM_LABEL -> stringResource(R.string.cs_custom_value, display)
        Facet.LANGUAGE -> stringResource(R.string.cs_speaks, display)
        Facet.CITIZENSHIP -> stringResource(R.string.cs_citizen_of, display)
        Facet.HAS -> stringResource(
            when (key) {
                ContactFacets.HAS_EMAIL -> R.string.cs_has_email
                ContactFacets.HAS_ADDRESS -> R.string.cs_has_address
                ContactFacets.HAS_PHOTO -> R.string.cs_has_photo
                else -> R.string.cs_has_birthday
            },
        )
        Facet.MISSING -> stringResource(if (key == ContactFacets.NO_NAME) R.string.cs_no_name else R.string.cs_no_number)
        Facet.KEPT -> stringResource(R.string.cs_temporary)
        else -> display
    }
}

private fun monthName(key: String): String =
    key.toIntOrNull()?.takeIf { it in 1..12 }?.let { Month.of(it).getDisplayName(TextStyle.FULL, Locale.getDefault()) } ?: key

/**
 * Every filter of the Contacts list in one sheet: private and temporary, labels and account (the same filters as the
 * row's chips), and the fields (country, citizenship, city, company, birthday month, relation, language, custom field, what a
 * contact has or is missing). Only values some contact has are offered. Changes apply at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactFilterSheet(vm: AppViewModel, vaultHidden: Boolean, onDismiss: () -> Unit) {
    val filter by vm.people.filter.collectAsStateWithLifecycle()
    val choices by vm.people.filterChoices.collectAsStateWithLifecycle()
    val showVault by vm.showVault.collectAsStateWithLifecycle()
    val shown by vm.people.filtered.collectAsStateWithLifecycle()
    val fields = filter.fields

    // The value as it reads goes with it, for its chip when no listed contact offers it for a while.
    fun toggle(facet: Facet, key: String) = vm.people.toggleField(facet, key, choices[facet]?.firstOrNull { it.key == key }?.display)

    ParleySheet(onDismissRequest = onDismiss, title = stringResource(R.string.cs_filter_title)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl)) {
            Text(stringResource(R.string.cs_filter_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            KeptGroup(vm, vaultHidden)
            LabelAndAccountGroups(vm)
            ValueGroup(stringResource(R.string.cs_group_country), Facet.COUNTRY, choices, fields::has, ::toggle)
            ValueGroup(stringResource(R.string.cs_group_citizenship), Facet.CITIZENSHIP, choices, fields::has, ::toggle)
            ValueGroup(stringResource(R.string.cs_group_place), Facet.PLACE, choices, fields::has, ::toggle)
            ValueGroup(stringResource(R.string.cs_group_company), Facet.COMPANY, choices, fields::has, ::toggle)
            BirthdayGroup(choices[Facet.BIRTHDAY_MONTH].orEmpty(), fields::has, ::toggle)
            ValueGroup(stringResource(R.string.cs_group_relation), Facet.RELATION, choices, fields::has, ::toggle)
            ValueGroup(stringResource(R.string.cs_group_language), Facet.LANGUAGE, choices, fields::has, ::toggle)
            ValueGroup(stringResource(R.string.cs_group_custom), Facet.CUSTOM_LABEL, choices, fields::has, ::toggle)
            FlagGroup(
                stringResource(R.string.cs_group_has), Facet.HAS,
                listOf(
                    ContactFacets.HAS_EMAIL to R.string.cs_has_email,
                    ContactFacets.HAS_ADDRESS to R.string.cs_has_address,
                    ContactFacets.HAS_PHOTO to R.string.cs_has_photo,
                ),
                fields::has, ::toggle,
            )
            FlagGroup(
                stringResource(R.string.cs_group_missing), Facet.MISSING,
                listOf(ContactFacets.NO_NUMBER to R.string.cs_no_number, ContactFacets.NO_NAME to R.string.cs_no_name),
                fields::has, ::toggle,
            )

            Spacer(Modifier.height(Spacing.l))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        vm.people.clearFilter()
                        vm.showVault.value = false
                    },
                    enabled = !filter.isEmpty || showVault,
                ) { Text(stringResource(R.string.cs_clear)) }
                Spacer(Modifier.weight(1f))
                val n = shown?.size ?: 0
                Button(onDismiss) { Text(pluralStringResource(R.plurals.cs_show_results, n, n)) }
            }
        }
    }
}

/** Private (the list's own "Private" filter) and Temporary, when either applies. */
@Composable
private fun KeptGroup(vm: AppViewModel, vaultHidden: Boolean) {
    val filter by vm.people.filter.collectAsStateWithLifecycle()
    val showVault by vm.showVault.collectAsStateWithLifecycle()
    val temporary = rememberTemporaryItems(vm).isNotEmpty()
    if (vaultHidden && !temporary) return
    Group(stringResource(R.string.cs_group_kept)) {
        if (!vaultHidden) {
            FilterChip(
                showVault, { vm.showVault.value = !showVault },
                label = { Text(stringResource(R.string.ppl_chip_private)) },
                leadingIcon = { Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp)) },
            )
        }
        if (temporary) {
            FilterChip(
                filter.fields.has(Facet.KEPT, ContactFacets.TEMPORARY), { vm.people.toggleField(Facet.KEPT, ContactFacets.TEMPORARY) },
                label = { Text(stringResource(R.string.cs_temporary)) },
            )
        }
    }
}

/** The same label and account filters as the row's chips. */
@Composable
private fun LabelAndAccountGroups(vm: AppViewModel) {
    val filter by vm.people.filter.collectAsStateWithLifecycle()
    val idx by vm.people.index.collectAsStateWithLifecycle()
    val accounts by vm.people.accountChoices.collectAsStateWithLifecycle()
    val labels = idx.labelCounts.keys.sortedBy { it.lowercase() }
    if (labels.isNotEmpty()) {
        Group(stringResource(R.string.ppl_chip_labels)) {
            FilterChip(
                filter.unlabelled, { vm.people.setUnlabelled(!filter.unlabelled) },
                label = { Text(stringResource(R.string.ppl_chip_unlabelled)) },
            )
            labels.forEach { t ->
                FilterChip(
                    t in filter.labels, { vm.people.toggleLabel(t) },
                    label = { Text(stringResource(R.string.ppl_account_count, t, idx.labelCounts[t] ?: 0)) },
                )
            }
        }
    }
    if (accounts.size > 1) {
        Group(stringResource(R.string.ppl_chip_account)) {
            accounts.forEach { (label, n) ->
                val on = filter.account == label
                FilterChip(
                    on, { vm.people.setAccount(if (on) null else label) },
                    label = { Text(stringResource(R.string.ppl_account_count, label, n)) },
                )
            }
        }
    }
}

/** "Has a birthday" and the months birthdays fall in. */
@Composable
private fun BirthdayGroup(months: List<FacetChoice>, isOn: (Facet, String) -> Boolean, toggle: (Facet, String) -> Unit) {
    if (months.isEmpty()) return
    Group(stringResource(R.string.cs_group_birthday)) {
        FilterChip(
            isOn(Facet.HAS, ContactFacets.HAS_BIRTHDAY), { toggle(Facet.HAS, ContactFacets.HAS_BIRTHDAY) },
            label = { Text(stringResource(R.string.cs_has_birthday)) },
        )
        months.forEach { m ->
            FilterChip(
                isOn(Facet.BIRTHDAY_MONTH, m.key), { toggle(Facet.BIRTHDAY_MONTH, m.key) },
                label = { Text(stringResource(R.string.ppl_account_count, monthName(m.key), m.count)) },
            )
        }
    }
}

/** Fixed choices of one facet (Has…, Missing info). */
@Composable
private fun FlagGroup(title: String, facet: Facet, flags: List<Pair<String, Int>>, isOn: (Facet, String) -> Boolean, toggle: (Facet, String) -> Unit) {
    Group(title) {
        flags.forEach { (k, text) -> FilterChip(isOn(facet, k), { toggle(facet, k) }, label = { Text(stringResource(text)) }) }
    }
}

/** One facet's values as chips with their counts; a long list shows its most used first, the rest on request. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ValueGroup(
    title: String,
    facet: Facet,
    choices: Map<Facet, List<FacetChoice>>,
    isOn: (Facet, String) -> Boolean,
    toggle: (Facet, String) -> Unit,
) {
    val values = choices[facet].orEmpty()
    if (values.isEmpty()) return
    var all by rememberSaveable(facet) { mutableStateOf(false) }
    // Chosen values always show, even past the first few.
    val visible = if (all || values.size <= FIRST) values else values.take(FIRST) + values.drop(FIRST).filter { isOn(facet, it.key) }
    Group(title) {
        visible.forEach { v ->
            val label = if (facet == Facet.CUSTOM_LABEL) stringResource(R.string.cs_custom_value, v.display) else v.display
            FilterChip(isOn(facet, v.key), { toggle(facet, v.key) }, label = { Text(stringResource(R.string.ppl_account_count, label, v.count)) })
        }
        if (values.size > FIRST) {
            TextButton({ all = !all }) { Text(stringResource(if (all) R.string.cs_show_fewer else R.string.cs_show_all)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    ListSectionHeader(title, inset = 0.dp, top = Spacing.l)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.Center) { content() }
}

/** How many values a facet shows before "Show all". */
private const val FIRST = 12
