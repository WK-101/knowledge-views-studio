package app.parley.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.ui.Avatar
import app.parley.ui.Destination
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet
import app.parley.ui.Routes
import app.parley.ui.Spacing
import app.parley.ui.people.PeopleRoutes

/**
 * Under the favourites: one folded row for the people Parley starred only so a label lets them ring through Do Not
 * Disturb ("Also starred for Do Not Disturb: 4 (Family)"). Opening it lists them, each with **Make a favourite**, and
 * the labels with **Open**. Nothing shows when there are none. Android's stars don't change.
 */
@Composable
fun DndStarredRow(vm: AppViewModel, open: (Destination) -> Unit, modifier: Modifier = Modifier) {
    val split by vm.people.dndStarred.collectAsStateWithLifecycle()
    if (split.forDnd.isEmpty()) return
    var shown by rememberSaveable { mutableStateOf(false) }
    val labels = split.labels.joinToString(", ")
    ParleyListItem(
        headlineContent = {
            Text(
                pluralStringResource(R.plurals.fav_dnd_row, split.forDnd.size, split.forDnd.size, labels),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { Icon(Icons.Rounded.DoNotDisturbOn, null) },
        trailingContent = { Icon(Icons.Rounded.ExpandMore, null) },
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { shown = true },
    )
    if (shown) DndStarredSheet(vm, open) { shown = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DndStarredSheet(vm: AppViewModel, open: (Destination) -> Unit, onDismiss: () -> Unit) {
    val split by vm.people.dndStarred.collectAsStateWithLifecycle()
    val people = split.forDnd
    ParleySheet(onDismissRequest = onDismiss, title = stringResource(R.string.fav_dnd_title)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text(
                stringResource(R.string.fav_dnd_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xs),
            )
            people.forEach { c ->
                val makeLabel = stringResource(R.string.fav_dnd_make)
                val made = stringResource(R.string.fav_dnd_made, c.displayName)
                // TalkBack says whose star it is: the button's own text is the same on every row.
                val makeSpoken = stringResource(R.string.fav_dnd_make_a11y, c.displayName)
                ParleyListItem(
                    headlineContent = { Text(c.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Avatar(c.displayName, c.photoUri, 40.dp) },
                    trailingContent = {
                        TextButton(
                            {
                                val undo = vm.people.makeFavourite(c)
                                vm.offerUndo(made) { undo() }
                            },
                            Modifier.semantics { contentDescription = makeSpoken },
                        ) { Text(makeLabel) }
                    },
                    modifier = Modifier.clickable(role = Role.Button) { onDismiss(); open(Routes.contact(c.id)) },
                )
            }
            split.labels.forEach { title ->
                TextButton({ onDismiss(); open(PeopleRoutes.Label(title)) }, Modifier.padding(horizontal = Spacing.m)) {
                    Icon(Icons.AutoMirrored.Rounded.Label, null)
                    Spacer(Modifier.padding(start = Spacing.xs))
                    Text(stringResource(R.string.fav_dnd_open_label, title))
                }
            }
            Spacer(Modifier.padding(bottom = Spacing.l))
        }
    }
    // Everyone made a favourite: nothing left to list.
    LaunchedEffect(people.isEmpty()) { if (people.isEmpty()) onDismiss() }
}
