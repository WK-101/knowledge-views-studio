package app.parley.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.R
import app.parley.common.ux.ComingFrom
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleyMotion
import app.parley.ui.SegmentedGroup
import app.parley.ui.SettingsScaffold
import app.parley.ui.Spacing
import app.parley.ui.rowColors

/** "Coming from another phone?" on its own (in Tools): the same list as onboarding's last step. */
@Composable
fun ComingFromScreen(vm: AppViewModel, back: () -> Unit) {
    SettingsScaffold(stringResource(R.string.discover_coming_from_title), back) {
        Text(
            stringResource(R.string.coming_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl),
        )
        ComingFromGroups { importer -> vm.navigate(NavEvent.Route(importerRoute(importer))) }
    }
}

/**
 * The sources, grouped by what they bring (contacts, call history, block lists). A tap unfolds where to export on the
 * old phone and one button that opens Parley's own importer for that file.
 */
@Composable
fun ComingFromGroups(onImport: (ComingFrom.Importer) -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    ComingFrom.grouped().forEach { (importer, sources) ->
        SegmentedGroup(stringResource(importer.groupTitle)) {
            sources.forEach { s ->
                item(s.name) {
                    SourceRow(s, expanded = open == s.name, onToggle = { open = if (open == s.name) null else s.name }) { onImport(importer) }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(s: ComingFrom.Source, expanded: Boolean, onToggle: () -> Unit, onImport: () -> Unit) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f, ParleyMotion.fastSpatial(), label = "expand")
    val stateText = stringResource(if (expanded) R.string.blk_expanded else R.string.blk_collapsed)
    Column {
        ParleyListItem(
            modifier = Modifier.clickable(role = Role.Button, onClick = onToggle).semantics { stateDescription = stateText },
            leadingContent = { Icon(s.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            headlineContent = { Text(stringResource(s.title)) },
            trailingContent = { Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(turn)) },
            colors = rowColors(),
        )
        AnimatedVisibility(expanded, enter = ParleyMotion.expandIn(), exit = ParleyMotion.collapseOut()) {
            Column(
                Modifier.padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Text(stringResource(s.howTo), style = MaterialTheme.typography.bodyMedium)
                s.note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                FilledTonalButton(onImport) {
                    Icon(Icons.AutoMirrored.Rounded.Login, null)
                    Text(stringResource(s.importer.action), Modifier.padding(start = Spacing.s))
                }
            }
        }
    }
}

private val ComingFrom.Importer.groupTitle: Int
    get() = when (this) {
        ComingFrom.Importer.PARLEY_BACKUP -> R.string.coming_group_parley
        ComingFrom.Importer.CONTACTS_FILE -> R.string.rst_contacts
        ComingFrom.Importer.CALL_HISTORY_CSV -> R.string.hist_settings_title
        ComingFrom.Importer.BLOCK_LIST -> R.string.coming_group_blocking
    }

private val ComingFrom.Importer.action: Int
    get() = when (this) {
        ComingFrom.Importer.PARLEY_BACKUP -> R.string.coming_open_parley
        ComingFrom.Importer.CONTACTS_FILE -> R.string.coming_open_contacts
        ComingFrom.Importer.CALL_HISTORY_CSV -> R.string.hist_import_title
        ComingFrom.Importer.BLOCK_LIST -> R.string.blk_transfer_import_title
    }

private val ComingFrom.Source.title: Int
    get() = when (this) {
        ComingFrom.Source.PARLEY -> R.string.coming_source_parley
        ComingFrom.Source.GOOGLE -> R.string.csv_layout_google
        ComingFrom.Source.IPHONE -> R.string.coming_source_iphone
        ComingFrom.Source.SAMSUNG -> R.string.coming_source_samsung
        ComingFrom.Source.CALL_LOG_CSV -> R.string.coming_source_call_log
        ComingFrom.Source.CALL_BLOCKER -> R.string.coming_source_call_blocker
        ComingFrom.Source.YACB -> R.string.coming_source_yacb
        ComingFrom.Source.NO_PHONE_SPAM -> R.string.coming_source_nps
    }

private val ComingFrom.Source.howTo: Int
    get() = when (this) {
        ComingFrom.Source.PARLEY -> R.string.coming_source_parley_how
        ComingFrom.Source.GOOGLE -> R.string.coming_source_google_how
        ComingFrom.Source.IPHONE -> R.string.coming_source_iphone_how
        ComingFrom.Source.SAMSUNG -> R.string.coming_source_samsung_how
        ComingFrom.Source.CALL_LOG_CSV -> R.string.coming_source_call_log_how
        ComingFrom.Source.CALL_BLOCKER -> R.string.coming_source_call_blocker_how
        ComingFrom.Source.YACB -> R.string.coming_source_yacb_how
        ComingFrom.Source.NO_PHONE_SPAM -> R.string.coming_source_nps_how
    }

/**
 * Google contacts may need no import at all: say so before anyone exports a file for nothing. A Parley restore puts
 * the old phone's settings back: say so before anyone sets things up twice.
 */
private val ComingFrom.Source.note: Int?
    get() = when (this) {
        ComingFrom.Source.GOOGLE -> R.string.coming_source_google_note
        ComingFrom.Source.PARLEY -> R.string.coming_source_parley_note
        else -> null
    }

private val ComingFrom.Source.icon: ImageVector
    get() = when (this) {
        ComingFrom.Source.PARLEY -> Icons.Rounded.SettingsBackupRestore
        ComingFrom.Source.GOOGLE -> Icons.Rounded.AccountCircle
        ComingFrom.Source.IPHONE -> Icons.Rounded.PhoneIphone
        ComingFrom.Source.SAMSUNG -> Icons.Rounded.PhoneAndroid
        ComingFrom.Source.CALL_LOG_CSV -> Icons.Rounded.History
        ComingFrom.Source.CALL_BLOCKER, ComingFrom.Source.YACB, ComingFrom.Source.NO_PHONE_SPAM -> Icons.Rounded.Block
    }
