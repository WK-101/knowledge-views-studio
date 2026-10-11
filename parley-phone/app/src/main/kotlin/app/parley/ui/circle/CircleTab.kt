package app.parley.ui.circle

import androidx.compose.foundation.layout.Box
import app.parley.ui.PersonRow
import app.parley.ui.PrivateBadge
import app.parley.ui.Destination
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import app.parley.common.circle.FavouritesView
import app.parley.ui.Spacing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.parley.ui.history.HistoryRoutes
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
import app.parley.common.ux.Tips
import app.parley.ui.common.tipPending
import app.parley.ui.common.CoachMark
import app.parley.common.TextSearch
import app.parley.common.calls.CallSource
import app.parley.common.circle.CircleGlance
import app.parley.common.circle.CircleStatus
import app.parley.common.circle.CircleSuggestions
import app.parley.ui.Avatar
import app.parley.ui.EmptyState
import app.parley.ui.Routes
import app.parley.ui.SegmentedGroup
import app.parley.ui.contact.QuickMessenger
import app.parley.ui.contact.rememberQuickMessenger
import kotlinx.coroutines.launch
import app.parley.ui.ParleyShapes
import app.parley.ui.ParleyListItem
import app.parley.ui.avatarSize

/** What the Circle tab lists: the [shown] rows, the status [filter] in force and the [header] over them. */
private class CircleView(val shown: List<CircleRow>, val filter: CircleStatus?, val header: CircleGlance.Glance?)

/**
 * The rows matching [q], narrowed to the status [only] chosen in the header. The header and its filter are for the whole
 * Circle, so neither applies while searching; the filter lets go by itself once nobody has that status any more.
 */
private fun circleView(all: List<CircleRow>, q: String, only: CircleStatus?, glance: CircleGlance.Glance?): CircleView {
    val searched = if (q.isEmpty()) all else all.filter { TextSearch.matches(q, it.contact.displayName, it.contact.phones.map { p -> p.number }) }
    val header = glance.takeIf { q.isEmpty() }
    val filter = only.takeIf { s -> header != null && searched.any { it.status == s } }
    return CircleView(if (filter == null) searched else searched.filter { it.status == filter }, filter, header)
}

/** The Circle's explainer shows over people, not over the empty state (which explains it) or a search. */
private fun showCircleTip(pending: Boolean, empty: Boolean, query: String) = pending && !empty && query.isEmpty()

/**
 * The Circle tab. People with keep-in-touch set, most urgent first, each with a status chip, when you were last
 * in touch and one-tap Call / Message. An empty Circle offers "Suggested for your Circle"; a search with no match
 * says so (it never claims the Circle is empty).
 */
@Composable
fun CircleTab(vm: AppViewModel, open: (Destination) -> Unit, query: String) {
    val rows by vm.circle.rows.collectAsStateWithLifecycle()
    val suggestions by vm.circle.suggestions.collectAsStateWithLifecycle()
    val config by vm.c.circle.config.collectAsStateWithLifecycle()
    val (quick, quickHost) = rememberQuickMessenger(vm)
    quickHost()
    val all = rows ?: return
    val q = query.trim()
    val glance by vm.circle.glance.collectAsStateWithLifecycle()
    var only by rememberSaveable { mutableStateOf<CircleStatus?>(null) }
    val view = circleView(all, q, only, glance)
    val shown = view.shown
    val circleTip = showCircleTip(tipPending(Tips.CONCEPT_CIRCLE), all.isEmpty(), q)
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // What the Circle is, once it has people in it (the empty state explains it before that).
        if (circleTip) item(key = "tip") {
            CoachMark(Tips.CONCEPT_CIRCLE, stringResource(R.string.tip_concept_circle))
        }
        view.header?.let { g ->
            item(key = "glance") { CircleGlanceHeader(g, view.filter, { only = it }) { open(HistoryRoutes.Insights()) } }
        }
        when {
            all.isEmpty() -> item(key = "empty") {
                EmptyState(
                    Icons.Rounded.Groups,
                    stringResource(R.string.circle_empty_title),
                    stringResource(R.string.circle_empty_body),
                    modifier = Modifier.padding(top = 8.dp),
                )
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
 * The Circle under the Favourites · Circle switch at the top of Contacts: its rows (most urgent first), then the
 * suggestions while they're offered. The Favourites tab shows the whole [CircleTab] instead.
 */
@Composable
fun CircleRows(vm: AppViewModel, open: (Destination) -> Unit, query: String) {
    val rows by vm.circle.rows.collectAsStateWithLifecycle()
    val suggestions by vm.circle.suggestions.collectAsStateWithLifecycle()
    val config by vm.c.circle.config.collectAsStateWithLifecycle()
    val all = rows ?: return
    val q = query.trim()
    val shown = if (q.isEmpty()) all else all.filter { TextSearch.matches(q, it.contact.displayName, it.contact.phones.map { p -> p.number }) }
    val offerSuggestions = CircleSuggestions.offerInSection(all.size, suggestions.size, config.suggestionsDismissed, searching = q.isNotEmpty())
    val (quick, quickHost) = rememberQuickMessenger(vm)
    quickHost()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (shown.isNotEmpty()) SegmentedGroup { shown.forEach { r -> item(r.contact.lookupKey) { CircleRowItem(vm, r, quick, open) } } }
        if (offerSuggestions) SuggestionsGroup(vm, suggestions, canDismiss = true)
    }
}

/**
 * Whether the Favourites · Circle switch shows here ([hasSwitch]: the layout puts it on this surface) and which
 * side is shown: the last choice, kept in the Circle's settings, once the Circle has people.
 */
@Composable
fun rememberFavouritesView(vm: AppViewModel, hasSwitch: Boolean): Pair<Boolean, FavouritesView> {
    val members = vm.circle.rows.collectAsStateWithLifecycle().value?.size ?: 0
    val chosen = vm.c.circle.config.collectAsStateWithLifecycle().value.circleChosen
    return FavouritesView.switchShown(hasSwitch, members) to FavouritesView.of(hasSwitch, members, chosen)
}

/** The lookup keys of the Circle's people due a catch-up now (their favourite tiles show a small dot). */
@Composable
fun rememberCircleDue(vm: AppViewModel): Set<String> {
    val rows by vm.circle.rows.collectAsStateWithLifecycle()
    return remember(rows) { rows.orEmpty().filter { it.status == CircleStatus.DUE }.mapTo(HashSet()) { it.contact.lookupKey } }
}

/** The Due dot on a favourite's photo: the Due chip's colour, ringed by the surface so it reads on any photo. */
@Composable
fun DueDot(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.circle_due_dot)
    Box(
        modifier.size(14.dp).clip(ParleyShapes.pill).background(MaterialTheme.colorScheme.surface).padding(2.dp)
            .clip(ParleyShapes.pill).background(MaterialTheme.colorScheme.tertiary)
            .semantics { contentDescription = label },
    )
}

/**
 * The Favourites · Circle switch: two connected buttons, the one shown selected. The Circle's one home while its tab
 * is hidden (the reorder is the favourites' own, so it never shows on the Circle side).
 */
@Composable
fun FavouritesCircleSwitch(vm: AppViewModel, view: FavouritesView, modifier: Modifier = Modifier) {
    fun choose(circle: Boolean) {
        if ((view == FavouritesView.CIRCLE) != circle) vm.c.circle.updateConfig { it.copy(circleChosen = circle) }
    }
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs)) {
        SegmentedButton(
            view == FavouritesView.FAVOURITES, { choose(false) }, SegmentedButtonDefaults.itemShape(0, 2),
            icon = { Icon(Icons.Rounded.Star, null, Modifier.size(18.dp)) },
        ) { Text(stringResource(R.string.label_favourites), maxLines = 1) }
        SegmentedButton(
            view == FavouritesView.CIRCLE, { choose(true) }, SegmentedButtonDefaults.itemShape(1, 2),
            icon = { Icon(Icons.Rounded.Groups, null, Modifier.size(18.dp)) },
        ) { Text(stringResource(R.string.circle_section), maxLines = 1) }
    }
}

@Composable
private fun CircleRowItem(vm: AppViewModel, r: CircleRow, quick: QuickMessenger, open: (Destination) -> Unit) {
    val res = LocalResources.current
    val phone = r.contact.primaryPhone
    ParleyListItem(
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.main_open_contact)) { open(Routes.contact(r.contact.id)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box {
                Avatar(r.contact.displayName, r.contact.photoUri, avatarSize())
                // A private contact in the Circle: the same lock as in Contacts.
                if (r.contact.id < 0) PrivateBadge(Modifier.align(Alignment.BottomEnd))
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.contact.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                // A favourite too: the star, as on their tile.
                if (r.contact.starred) {
                    Icon(Icons.Rounded.Star, stringResource(R.string.circle_also_favourite), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
                StatusChip(r.status)
            }
        },
        supportingContent = { Text(CircleText.last(res, r.last), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            if (phone != null) Row {
                IconButton({ vm.requestCall(phone.number, r.contact.displayName, source = CallSource.CONTACT) }) {
                    Icon(Icons.Rounded.Call, stringResource(R.string.action_call_named, r.contact.displayName))
                }
                IconButton({ quick.message(r.contact) }) {
                    Icon(Icons.AutoMirrored.Rounded.Message, stringResource(R.string.action_message_named, r.contact.displayName))
                }
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
    Surface(color = bg, contentColor = fg, shape = ParleyShapes.tag) {
        Text(CircleText.status(res, s), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun SuggestionsGroup(vm: AppViewModel, suggestions: List<CircleSuggestion>, canDismiss: Boolean) {
    // Each suggestion says how often you two called: a relationship insight, none while a duress unlock hides them.
    val privacy by vm.privacy.collectAsStateWithLifecycle()
    if (!privacy.insightsShown) return
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    Column {
        SegmentedGroup(stringResource(R.string.circle_suggested)) {
            suggestions.forEach { s ->
                item("s:" + s.contact.lookupKey) {
                    PersonRow(
                        s.contact.displayName, s.contact.photoUri,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        supportingContent = {
                            Text(
                                pluralStringResource(
                                    R.plurals.circle_suggest_calls, s.calls, s.calls,
                                ) + stringResource(R.string.main_separator) + pluralStringResource(R.plurals.circle_every_days, s.days, s.days),
                            )
                        },
                        trailingContent = {
                            FilledTonalButton({
                                scope.launch {
                                    vm.c.circle.setRhythm(s.contact.lookupKey, s.contact.id, s.days)
                                    CircleSnacks.show(
                                        CircleSnack(
                                            res.getString(R.string.circle_added, s.contact.displayName),
                                        ) { vm.c.circle.setRhythm(s.contact.lookupKey, s.contact.id, null) },
                                    )
                                }
                            }) {
                                Icon(Icons.Rounded.PersonAdd, null, modifier = Modifier.padding(end = 6.dp))
                                Text(stringResource(R.string.action_add))
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
