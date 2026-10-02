package app.parley.ui.discover

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import app.parley.AppViewModel
import app.parley.BuildConfigInfo
import app.parley.R
import app.parley.common.ux.Capability
import app.parley.common.ux.CapabilityCatalog
import app.parley.common.ux.CapabilitySearch
import app.parley.common.ux.Job
import app.parley.ui.EmptyState
import app.parley.ui.LinkRow
import app.parley.ui.SegmentedGroup
import app.parley.ui.SegmentedGroupScope
import app.parley.ui.SettingsScaffold
import app.parley.ui.Spacing

/**
 * P8 "What Parley can do": every row of [CapabilityCatalog], grouped by the job it does, one line each; a tap opens
 * the feature. A search box filters as you type. What this release added comes first, under "New in …".
 * Reached from Tools, Settings and the What's new card (never shown by itself).
 */
@Composable
fun CapabilitiesScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    // Shown texts, so search finds what the screen says (the English reference texts keep matching too).
    val texts = remember(res) {
        CapabilityCatalog.rows.associate { c -> c.key to CapabilityText.of(c).let { (t, s) -> res.getString(t) to res.getString(s) } }
    }
    val shown = remember(query, texts) { CapabilitySearch.search(query, CapabilityCatalog.rows) { texts.getValue(it.key) } }
    val version = remember { BuildConfigInfo.versionName(context) }
    val fresh = remember(version) { CapabilityCatalog.newIn(version) }

    fun SegmentedGroupScope.row(c: Capability) = item(c.key) {
        val (title, summary) = texts.getValue(c.key)
        LinkRow(title, summary) { vm.navigate(capabilityEvent(c.target)) }
    }

    SettingsScaffold(stringResource(R.string.discover_title), back) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.discover_search)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.set_clear)) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.listInset),
        )
        if (shown.isEmpty()) {
            EmptyState(
                Icons.Rounded.SearchOff, stringResource(R.string.discover_no_match, query.trim()),
                action = stringResource(R.string.ux_empty_clear_search), onAction = { query = "" },
            )
        }
        if (query.isBlank() && fresh.isNotEmpty()) {
            SegmentedGroup(stringResource(R.string.discover_new_in, CapabilityCatalog.majorMinor(version))) { fresh.forEach { row(it) } }
        }
        Job.entries.forEach { job ->
            val rows = shown.filter { it.job == job }
            SegmentedGroup(stringResource(CapabilityText.job(job))) { rows.forEach { row(it) } }
        }
    }
}
