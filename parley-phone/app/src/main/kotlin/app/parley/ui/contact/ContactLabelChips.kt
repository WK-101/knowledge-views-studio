package app.parley.ui.contact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.R
import app.parley.ui.Spacing
import app.parley.ui.people.PeopleRoutes

/**
 * The contact's labels, as chips under the name (the same on a phone, beside the list on a big screen, for private
 * contacts while locked, and for a contact kept in a read-only account): a tap opens the label's page. Nothing shows
 * when they have none; labels are added in the editor. A shared label says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContactLabelChips(ctx: ContactPageContext, modifier: Modifier = Modifier) {
    val labels by ctx.page.labels.collectAsStateWithLifecycle()
    if (labels.isEmpty()) return
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
    }
}
