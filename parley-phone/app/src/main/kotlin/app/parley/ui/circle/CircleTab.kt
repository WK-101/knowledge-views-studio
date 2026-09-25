package app.parley.ui.circle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.TextSearch
import app.parley.common.calls.CallSource
import app.parley.common.circle.CircleStatus
import app.parley.ui.Avatar
import app.parley.ui.EmptyState
import app.parley.ui.Routes
import app.parley.ui.SegmentedGroup
import app.parley.ui.contact.QuickMessenger
import app.parley.ui.contact.rememberQuickMessenger
import kotlinx.coroutines.launch

/**
 * R1: the Circle tab. People with keep-in-touch set, most urgent first, each with a status chip, when you were last
 * in touch and one-tap Call / Message. An empty Circle offers "Suggested from your calls"; a search with no match
 * says so (it never claims the Circle is empty).
 */
@Composable
fun CircleTab(vm: AppViewModel, open: (String) -> Unit, query: String) {
    val rows by vm.circle.rows.collectAsStateWithLifecycle()
    val suggestions by vm.circle.suggestions.collectAsStateWithLifecycle()
    val config by vm.c.circle.config.collectAsStateWithLifecycle()
    val (quick, quickHost) = rememberQuickMessenger(vm)
    quickHost()
    val all = rows ?: return
    val q = query.trim()
    val shown = if (q.isEmpty()) all else all.filter { TextSearch.matches(q, it.contact.displayName, it.contact.phones.map { p -> p.number }) }
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            all.isEmpty() -> item(key = "empty") {
                EmptyState(Icons.Rounded.Groups, stringResource(R.string.circle_empty_title), stringResource(R.string.circle_empty_body), modifier = Modifier.padding(top = 8.dp))
            }
            shown.isEmpty() -> item(key = "nomatch") {
                EmptyState(Icons.Rounded.SearchOff, stringResource(R.string.circle_no_match, q), modifier = Modifier.padding(top = 32.dp))
            }
            else -> item(key = "rows") {
                SegmentedGroup { shown.forEach { r -> item(r.contact.lookupKey) { CircleRowItem(vm, r, quick, open) } } }
            }
        }
        // Suggestions: always offered while the Circle is empty; afterwards until dismissed.
        if (q.isEmpty() && suggestions.isNotEmpty() && (all.isEmpty() || !config.suggestionsDismissed)) item(key = "suggest") {
            SuggestionsGroup(vm, suggestions, canDismiss = all.isNotEmpty())
        }
        if (all.isEmpty() && suggestions.isEmpty()) item(key = "howto") {
            Text(
                stringResource(R.string.circle_how_to_add), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

/**
 * R1: the Circle as a folding section at the top of Favourites, used while the Circle tab is hidden. Shows nothing
 * for an empty Circle without suggestions, so Favourites stays as it was for people who don't use it.
 */
@Composable
fun CircleFavoritesSection(vm: AppViewModel, open: (String) -> Unit, query: String) {
    val rows by vm.circle.rows.collectAsStateWithLifecycle()
    val suggestions by vm.circle.suggestions.collectAsStateWithLifecycle()
    val config by vm.c.circle.config.collectAsStateWithLifecycle()
    val all = rows ?: return
    val q = query.trim()
    val shown = if (q.isEmpty()) all else all.filter { TextSearch.matches(q, it.contact.displayName, it.contact.phones.map { p -> p.number }) }
    val offerSuggestions = q.isEmpty() && suggestions.isNotEmpty() && !config.suggestionsDismissed
    if (shown.isEmpty() && !offerSuggestions) return
    val (quick, quickHost) = rememberQuickMessenger(vm)
    quickHost()
    val collapsed = config.favoritesSectionCollapsed && q.isEmpty()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { vm.c.circle.updateConfig { it.copy(favoritesSectionCollapsed = !it.favoritesSectionCollapsed) } }.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.circle_section), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Icon(
                if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                stringResource(if (collapsed) R.string.circle_expand else R.string.circle_collapse),
                modifier = Modifier.padding(12.dp),
            )
        }
        AnimatedVisibility(!collapsed) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (shown.isNotEmpty()) SegmentedGroup(modifier = Modifier.padding(horizontal = 0.dp)) { shown.forEach { r -> item(r.contact.lookupKey) { CircleRowItem(vm, r, quick, open) } } }
                if (offerSuggestions) SuggestionsGroup(vm, suggestions, canDismiss = true)
            }
        }
    }
}

@Composable
private fun CircleRowItem(vm: AppViewModel, r: CircleRow, quick: QuickMessenger, open: (String) -> Unit) {
    val res = LocalResources.current
    val phone = r.contact.phones.firstOrNull { it.isPrimary } ?: r.contact.phones.firstOrNull()
    ListItem(
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.main_open_contact)) { open(Routes.contact(r.contact.id)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Avatar(r.contact.displayName, r.contact.photoUri, 40.dp) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.contact.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                StatusChip(r.status)
            }
        },
        supportingContent = { Text(CircleText.last(res, r.last), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            if (phone != null) Row {
                IconButton({ vm.requestCall(phone.number, r.contact.displayName, source = CallSource.CONTACT) }) {
                    Icon(Icons.Rounded.Call, stringResource(R.string.circle_call_who, r.contact.displayName))
                }
                IconButton({ quick.message(r.contact) }) { Icon(Icons.AutoMirrored.Rounded.Message, stringResource(R.string.circle_message_who, r.contact.displayName)) }
            }
        },
    )
}

/** Due / Soon / Fine, in calm colours (no red: it's a hint, not a warning). */
@Composable
fun StatusChip(s: CircleStatus) {
    val res = LocalResources.current
    val (bg, fg) = when (s) {
        CircleStatus.DUE -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        CircleStatus.SOON -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        CircleStatus.FINE -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(8.dp)) {
        Text(CircleText.status(res, s), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun SuggestionsGroup(vm: AppViewModel, suggestions: List<CircleSuggestion>, canDismiss: Boolean) {
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    Column {
        SegmentedGroup(stringResource(R.string.circle_suggested)) {
            suggestions.forEach { s ->
                item("s:" + s.contact.lookupKey) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Avatar(s.contact.displayName, s.contact.photoUri, 40.dp) },
                        headlineContent = { Text(s.contact.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(pluralStringResource(R.plurals.circle_suggest_calls, s.calls, s.calls) + stringResource(R.string.main_separator) + pluralStringResource(R.plurals.circle_every_days, s.days, s.days))
                        },
                        trailingContent = {
                            FilledTonalButton({
                                scope.launch {
                                    vm.c.circle.setRhythm(s.contact.lookupKey, s.contact.id, s.days)
                                    CircleSnacks.show(CircleSnack(res.getString(R.string.circle_added, s.contact.displayName)) { vm.c.circle.setRhythm(s.contact.lookupKey, s.contact.id, null) })
                                }
                            }) {
                                Icon(Icons.Rounded.PersonAdd, null, modifier = Modifier.padding(end = 6.dp))
                                Text(stringResource(R.string.circle_add))
                            }
                        },
                    )
                }
            }
        }
        if (canDismiss) TextButton({ vm.c.circle.updateConfig { it.copy(suggestionsDismissed = true) } }, Modifier.padding(start = 16.dp)) {
            Text(stringResource(R.string.circle_suggest_dismiss))
        }
    }
}
