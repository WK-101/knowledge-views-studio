package app.parley.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.blocking.ScreeningPreset
import app.parley.common.ux.BasicLayout
import app.parley.common.ux.Basics
import app.parley.common.ux.BasicsChoice
import app.parley.ui.ParleyListItem
import app.parley.ui.blocking.help
import app.parley.ui.blocking.title
import kotlinx.coroutines.launch

/**
 * The first run's "Set up the basics": who can ring (the screening presets), who the phone is for (Simple mode next),
 * and how home looks. Each question starts on what is set now, says in one line where it changes later, and can be
 * left as it is; Skip changes nothing. [done] gets the answers once they are saved.
 */
@Composable
internal fun ColumnScope.BasicsStep(vm: AppViewModel, done: (BasicsChoice) -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val start = Basics.current(settings)
    var preset by rememberSaveable { mutableStateOf(start.screening?.name) }
    var someoneElse by rememberSaveable { mutableStateOf(false) }
    var layout by rememberSaveable { mutableStateOf(start.layout?.name ?: BasicLayout.TABS.name) }

    Spacer(Modifier.height(24.dp))
    Icon(Icons.Rounded.Tune, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
    Text(stringResource(R.string.basics_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.basics_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Question(stringResource(R.string.basics_ring_title), stringResource(R.string.basics_ring_line)) {
        ScreeningPreset.entries.forEach { p ->
            Choice(stringResource(p.title), stringResource(p.help), preset == p.name) { preset = p.name }
        }
    }
    Question(stringResource(R.string.basics_for_title), stringResource(R.string.basics_for_line)) {
        Choice(stringResource(R.string.basics_for_me), null, !someoneElse) { someoneElse = false }
        Choice(stringResource(R.string.basics_for_someone), stringResource(R.string.basics_for_someone_sub), someoneElse) { someoneElse = true }
    }
    Question(stringResource(R.string.basics_layout_title), stringResource(R.string.basics_layout_line)) {
        Choice(stringResource(R.string.basics_layout_tabs), stringResource(R.string.basics_layout_tabs_sub), layout == BasicLayout.TABS.name) {
            layout = BasicLayout.TABS.name
        }
        Choice(stringResource(R.string.basics_layout_combined), stringResource(R.string.basics_layout_combined_sub), layout == BasicLayout.COMBINED.name) {
            layout = BasicLayout.COMBINED.name
        }
    }

    Spacer(Modifier.weight(1f))
    Button(
        {
            val choice = BasicsChoice(
                screening = ScreeningPreset.entries.firstOrNull { it.name == preset },
                forSomeoneElse = someoneElse,
                layout = BasicLayout.entries.firstOrNull { it.name == layout },
            )
            scope.launch {
                vm.c.settings.update { Basics.apply(it, choice) }
                done(choice)
            }
        },
        Modifier.fillMaxWidth().height(56.dp),
    ) { Text(stringResource(R.string.ux_perm_continue)) }
    TextButton({ done(BasicsChoice()) }, Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.basics_skip)) }
}

/** One question: its title, the one line that says where it changes later, and its choices. */
@Composable
private fun Question(title: String, line: String, choices: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.selectableGroup()) { choices() }
    }
}

@Composable
private fun Choice(title: String, sub: String?, selected: Boolean, onSelect: () -> Unit) {
    ParleyListItem(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onSelect),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { RadioButton(selected, onClick = null) },
        headlineContent = { Text(title) },
        supportingContent = sub?.let { { Text(it) } },
    )
}
