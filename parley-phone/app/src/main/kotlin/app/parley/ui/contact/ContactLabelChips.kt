package app.parley.ui.contact

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.R
import app.parley.data.GroupInfo
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyListItem
import app.parley.ui.Spacing
import app.parley.ui.people.PeopleRoutes
import kotlinx.coroutines.launch

/**
 * The contact's labels, as chips under the name (the same on a phone, beside the list on a big screen, for private
 * contacts while locked, and for a contact kept in a read-only account): a tap opens the label's page, and "Add to
 * label" puts them in another one. A shared label says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContactLabelChips(ctx: ContactPageContext, modifier: Modifier = Modifier) {
    val labels by ctx.page.labels.collectAsStateWithLifecycle()
    var choices by remember { mutableStateOf<List<GroupInfo>?>(null) }
    val openLabel = stringResource(R.string.ctl_label_open)
    FlowRow(
        modifier.padding(top = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        labels.forEach { l ->
            val described = if (l.shared) stringResource(R.string.ctl_label_shared, l.title) else l.title
            AssistChip(
                onClick = { ctx.open(PeopleRoutes.label(l.title)) },
                label = { Text(l.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    Icon(if (l.shared) Icons.Rounded.Groups else Icons.AutoMirrored.Rounded.Label, null, Modifier.size(AssistChipDefaults.IconSize))
                },
                modifier = Modifier.semantics {
                    contentDescription = described
                    onClick(label = openLabel) { ctx.open(PeopleRoutes.label(l.title)); true }
                },
            )
        }
        AssistChip(
            onClick = { ctx.scope.launch { choices = ctx.page.labelChoices() } },
            label = { Text(stringResource(R.string.sel_add_to_label), maxLines = 1) },
            leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(AssistChipDefaults.IconSize)) },
        )
    }
    choices?.let { groups ->
        AddToLabelDialog(groups, onDismiss = { choices = null }) { g ->
            choices = null
            ctx.page.addToLabel(g)
        }
    }
}

/** The labels this contact can join, one per title; none when it isn't saved in an account that has labels. */
@Composable
private fun AddToLabelDialog(groups: List<GroupInfo>, onDismiss: () -> Unit, onPick: (GroupInfo) -> Unit) {
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sel_add_to_label)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (groups.isEmpty()) Text(stringResource(R.string.ctl_no_label_for_contact))
                groups.forEach { g ->
                    ParleyListItem(
                        headlineContent = { Text(g.title.trim()) },
                        leadingContent = { Icon(Icons.AutoMirrored.Rounded.Label, null) },
                        modifier = Modifier.clickable { onPick(g) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    )
}
