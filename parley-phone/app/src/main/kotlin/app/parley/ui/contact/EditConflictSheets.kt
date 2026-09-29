package app.parley.ui.contact

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MergeType
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.parley.R
import app.parley.common.people.ThreeWayMerge.Side
import app.parley.data.ContactEditRebase.Field
import app.parley.ui.ChoiceRow
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet
import app.parley.ui.Spacing
import app.parley.ui.kitStrings
import app.parley.ui.rowColors

/**
 * "Changed elsewhere": a save found the contact changed by another app or a sync since the editor opened it. The user
 * picks what happens; nothing was written yet. "Merge field by field" opens [MergeFieldsDialog] when both sides
 * changed the same field, and merges straight away when they didn't.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangedElsewhereSheet(
    conflict: EditConflict,
    onTheirs: () -> Unit,
    onMine: () -> Unit,
    onMerge: (Map<Field, Side>) -> Unit,
    onDismiss: () -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    if (choosing && conflict.conflicts.isNotEmpty()) {
        MergeFieldsDialog(conflict, onMerge = onMerge, onDismiss = { choosing = false })
        return
    }
    val gone = conflict.theirs == null
    ParleySheet(onDismissRequest = onDismiss, title = stringResource(R.string.edit_changed_title)) {
        Text(
            stringResource(if (gone) R.string.edit_changed_gone_body else R.string.edit_changed_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s),
        )
        if (!gone) {
            Choice(Icons.Rounded.History, stringResource(R.string.edit_changed_theirs), stringResource(R.string.edit_changed_theirs_sub), onTheirs)
            Choice(Icons.AutoMirrored.Rounded.MergeType, stringResource(R.string.edit_changed_merge), stringResource(R.string.edit_changed_merge_sub)) {
                if (conflict.conflicts.isEmpty()) onMerge(emptyMap()) else choosing = true
            }
        }
        Choice(
            Icons.Rounded.Edit, stringResource(R.string.edit_changed_mine),
            stringResource(if (gone) R.string.edit_changed_mine_new_sub else R.string.edit_changed_mine_sub), onMine,
        )
    }
}

@Composable
private fun Choice(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    ParleyListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(sub) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = rowColors(),
    )
}

@Composable
private fun fieldLabel(f: Field): String = stringResource(
    when (f) {
        Field.NAME -> R.string.edit_name
        Field.NICKNAME -> R.string.edit_nickname
        Field.COMPANY -> R.string.edit_company
        Field.NOTE -> R.string.edit_notes
        Field.PHONES -> R.string.edit_field_phones
        Field.EMAILS -> R.string.edit_field_emails
        Field.WEBSITES -> R.string.edit_field_websites
        Field.RELATIONS -> R.string.edit_relations
        Field.ADDRESSES -> R.string.edit_field_addresses
        Field.EVENTS -> R.string.edit_important_dates
        Field.HANDLES -> R.string.edit_handles
        Field.LABELS -> R.string.edit_field_labels
        Field.PRONOUNS -> R.string.edit_pronouns
    },
)

/** One choice per field both sides changed (theirs or mine); the rest is merged already. */
@Composable
private fun MergeFieldsDialog(conflict: EditConflict, onMerge: (Map<Field, Side>) -> Unit, onDismiss: () -> Unit) {
    // Picks survive rotation: stored as the names of the fields that take "theirs".
    var theirs by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val empty = stringResource(R.string.edit_merge_empty)
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_merge_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.edit_merge_body), style = MaterialTheme.typography.bodyMedium)
                conflict.conflicts.forEach { c ->
                    val takeTheirs = c.field.name in theirs
                    ChoiceRow(
                        fieldLabel(c.field),
                        listOf(stringResource(R.string.edit_merge_theirs), stringResource(R.string.edit_merge_mine)),
                        if (takeTheirs) 0 else 1,
                    ) { i -> theirs = if (i == 0) theirs + c.field.name else theirs - c.field.name }
                    Text(
                        stringResource(R.string.edit_merge_value_theirs, c.theirs.ifBlank { empty }),
                        style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = Spacing.l),
                    )
                    Text(
                        stringResource(R.string.edit_merge_value_mine, c.mine.ifBlank { empty }),
                        style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = Spacing.l),
                    )
                }
            }
        },
        confirmButton = {
            TextButton({
                onMerge(conflict.conflicts.associate { c -> c.field to if (c.field.name in theirs) Side.THEIRS else Side.MINE })
            }) { Text(stringResource(R.string.edit_merge_apply)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(kitStrings().cancel) } },
    )
}
