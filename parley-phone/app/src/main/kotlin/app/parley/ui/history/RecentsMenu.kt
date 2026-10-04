package app.parley.ui.history

import app.parley.ui.Destination
import app.parley.ui.ParleyListItem
import app.parley.ui.activityViewModel
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.calls.RecentsLayout
import app.parley.ui.home.RecentsViewModel
import app.parley.ui.home.recentsStyleLabels
import app.parley.ui.home.showRecentsLegend
import app.parley.common.AppSettings
import app.parley.common.RecentTap
import app.parley.common.ux.RecentsStyle
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.parley.ui.settings.settingTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import app.parley.ui.ParleyDialog

// ---------------------------------------------------------------- Call-list layout

/** The names of the three layouts, in [RecentsLayout] order. */
@Composable
fun recentsLayoutLabels(): List<String> = listOf(
    stringResource(R.string.recents_layout_grouped),
    stringResource(R.string.recents_layout_chronological),
    stringResource(R.string.recents_layout_by_day),
)

private val layoutRequested = MutableStateFlow(false)
private val clearRequested = MutableStateFlow(false)

/**
 * Recents ⋮ › "Recents view…": how Recents looks, in one place (layout, style, what a tap does) with the colours'
 * legend. The same settings are on Settings › Recents & history and Layout & gestures.
 */
@Composable
fun RecentsLayoutMenuItem(closeMenu: () -> Unit) {
    DropdownMenuItem(
        { Text(stringResource(R.string.recents_view_menu)) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ViewList, null) },
        onClick = {
            closeMenu()
            layoutRequested.value = true
        },
    )
}

/** Recents ⋮ › "Clear call history…". */
@Composable
fun ClearHistoryMenuItem(closeMenu: () -> Unit) {
    DropdownMenuItem({ Text(stringResource(R.string.clear_history_menu)) }, leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) }, onClick = {
        closeMenu()
        clearRequested.value = true
    })
}

/** Shows the layout picker and "Clear call history" when asked from the Recents ⋮ menu. */
@Composable
fun RecentsMenuDialogs(vm: AppViewModel, open: (Destination) -> Unit) {
    val layout by layoutRequested.collectAsStateWithLifecycle()
    val clear by clearRequested.collectAsStateWithLifecycle()
    if (layout) RecentsLayoutDialog(vm) { layoutRequested.value = false }
    if (clear) {
        val groups by activityViewModel<RecentsViewModel>().groups.collectAsStateWithLifecycle()
        val shown = remember(groups) { groups.orEmpty().flatMap { it.calls } }
        ClearHistoryDialog(vm, shown, open) { clearRequested.value = false }
    }
}

@Composable
private fun RecentsLayoutDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val labels = recentsLayoutLabels()
    val hints = listOf(
        stringResource(R.string.recents_layout_grouped_hint),
        stringResource(R.string.recents_layout_chronological_hint),
        stringResource(R.string.recents_layout_by_day_hint),
    )
    val styles = recentsStyleLabels()
    val taps = listOf(stringResource(R.string.home_tap_details), stringResource(R.string.home_tap_call))
    val scope = rememberCoroutineScope()
    fun set(f: (AppSettings) -> AppSettings) = scope.launch { vm.c.settings.update(f) }
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recents_view_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ViewSection(settingTitle("recents_layout"))
                Column(Modifier.selectableGroup()) {
                    RecentsLayout.entries.forEachIndexed { i, l ->
                        ChoiceItem(labels[i], hints[i], s.recentsLayout == l) { set { it.copy(recentsLayout = l) } }
                    }
                }
                ViewSection(settingTitle("recents_style"))
                Column(Modifier.selectableGroup()) {
                    RecentsStyle.entries.forEachIndexed { i, st ->
                        ChoiceItem(styles[i], null, s.recentsStyle == st) { set { it.copy(recentsStyle = st) } }
                    }
                }
                ViewSection(settingTitle("recent_tap"))
                Column(Modifier.selectableGroup()) {
                    RecentTap.entries.forEachIndexed { i, t ->
                        ChoiceItem(taps[i], null, s.surfaces.recentTap == t) { set { it.copy(surfaces = it.surfaces.copy(recentTap = t)) } }
                    }
                }
                // What the shapes and colours of each style mean (it was its own ⋮ item).
                TextButton({ onDismiss(); showRecentsLegend() }) { Text(stringResource(R.string.recents_legend_menu)) }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.clear_history_close)) } },
    )
}

@Composable
private fun ViewSection(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
internal fun ChoiceItem(title: String, sub: String?, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    ParleyListItem(
        headlineContent = { Text(title) },
        supportingContent = sub?.let { { Text(it) } },
        leadingContent = { RadioButton(selected, onClick = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .then(if (enabled) Modifier else Modifier.alpha(0.6f)),
    )
}
