package app.parley.ui.home

import app.parley.ui.Destination
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import app.parley.common.recall.RecallSource
import app.parley.ui.recall.recallSection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.StartTab
import app.parley.common.homeLayout
import app.parley.common.people.ContactsFooter
import app.parley.common.people.AlphabetIndex
import app.parley.ui.AlphabetIndexDefaults
import app.parley.ui.AlphabetIndexRail
import app.parley.common.people.SwipeAction
import app.parley.ui.Avatar
import app.parley.ui.circle.CircleFavoritesSection
import app.parley.ui.common.Intents
import app.parley.ui.contact.rememberQuickMessenger
import app.parley.ui.people.ContactsFilterChips
import app.parley.ui.people.MeCardRow
import app.parley.ui.people.SwipeActionRow
import app.parley.ui.people.blockWithUndo
import app.parley.ui.people.rememberWorkResults
import app.parley.ui.people.workResultsSection
import app.parley.ui.shared
import app.parley.ui.EmptyState
import app.parley.ui.Routes
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import app.parley.ui.PrivateBadge
import app.parley.ui.avatarSize
import app.parley.common.ux.ListSections
import app.parley.common.people.ContactSort
import app.parley.ui.people.ContactSortSheet
import app.parley.ui.ListSectionHeader
import app.parley.ui.Spacing
import app.parley.ui.ParleyListItem
import app.parley.ui.Banner
import app.parley.security.AppLock
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.material.icons.rounded.Lock

fun sectionOf(name: String): String = ListSections.letterOf(name)

private const val CONTENT_LETTER = "letter"
private const val CONTENT_CONTACT = "contact"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactsTab(vm: AppViewModel, open: (Destination) -> Unit, onReorderFavorites: () -> Unit = {}) {
    // The rows with their letter headers, worked out once per list change (PeopleUi.listing).
    val listing by vm.people.listing.collectAsStateWithLifecycle()
    val query by vm.contactQuery.collectAsStateWithLifecycle()
    val selectionState = vm.selection.collectAsStateWithLifecycle()
    // Only entering or leaving selection changes the screen above the rows; ticking another contact doesn't.
    val selectingAny by remember { derivedStateOf { selectionState.value.isNotEmpty() } }
    // Read inside each row (not by the list's builder), so a new second line or hint recomposes the rows, not the list.
    val secondLinesState = vm.people.secondLines.collectAsStateWithLifecycle()
    val filter by vm.people.filter.collectAsStateWithLifecycle()

    val showVault by vm.showVault.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // The "Private" chip is a filter of the one list (private contacts are listed with everyone else).
    val privateOnly = showVault && !settings.hideVault
    val chips: @Composable () -> Unit = { ContactsFilterChips(vm, showVault, settings.hideVault, open) }
    val peopleSettings by vm.people.settings.collectAsStateWithLifecycle()
    val hintsState = vm.people.searchHints.collectAsStateWithLifecycle()
    val privateLocked = vm.people.privateSearch.locked.collectAsStateWithLifecycle().value && !settings.hideVault
    val indexState = vm.people.index.collectAsStateWithLifecycle()
    // The row's message button and a "Message" swipe use each person's usual way to message.
    val (quick, quickHost) = rememberQuickMessenger(vm)
    // The work profile's matches, read-only, under the search results (not while filtering the list).
    val work = rememberWorkResults(if (filter.isEmpty && !privateOnly) query else "")
    // Recall: everything Parley remembers, with the "Search everything" chip or when the contacts give nothing.
    val recallActive by vm.recall.active.collectAsStateWithLifecycle()
    val recallState by vm.recall.state.collectAsStateWithLifecycle()
    val everything by vm.recall.everything.collectAsStateWithLifecycle()
    var recallExpanded by remember(query) { mutableStateOf(emptySet<RecallSource>()) }
    val recallShows = recallActive && (everything || recallState.result?.isEmpty == false)

    val rows = listing
    if (rows == null) {
        // A cold start with a large address book: the rows shown last time, until the list has loaded.
        val head by vm.people.listHead.collectAsStateWithLifecycle()
        head?.let { ListHeadPreview(it, open) } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val state = rememberLazyListState()
    // Build (index of first item for each section) for the A–Z index.
    // "My card" leads the list when nothing is being searched or filtered.
    val showMe = query.isBlank() && filter.isEmpty && !selectingAny
    // The favourites (and the Circle, when it moved with them) under "My card", while not searching.
    val layout = settings.homeLayout
    val showFavorites = showMe && layout.favoritesInContacts
    val showCircle = showFavorites && layout.circleHost == StartTab.CONTACTS
    val leading = listOf(showMe, showFavorites, showCircle).count { it }
    // Item 0 is the group chips row, then "My card", the favourites and the Circle.
    val sections = remember(rows, leading) { ListSections.firstRows(rows, 1 + leading) }
    val count = remember(rows) { rows.count { it is ListSections.Row.Item } }
    // Private contacts listed among the others have negative ids.
    val privateShown = remember(rows) { rows.count { it is ListSections.Row.Item && it.item.id < 0 } }
    // One items block per letter rather than one item per contact.
    val runs = remember(rows) { ListSections.runs(rows) }
    val rowActions = settings.contactRowActions
    val swipe = peopleSettings.swipe
    // The A–Z index belongs to the name order only, beside the alphabetical part (never over the chips, My card, the
    // favourites or the Circle); "★" leads it when the favourites are in Contacts and jumps to them.
    val byName = peopleSettings.contactSort == ContactSort.NAME
    val indexed = query.isBlank() && count > AlphabetIndex.MIN_ITEMS && byName
    val favIndex = 1 + (if (showMe) 1 else 0)
    val indexEntries = remember(sections, showFavorites, favIndex) {
        AlphabetIndex.entries(sections.entries.map { it.key to it.value }, favouritesAt = favIndex.takeIf { showFavorites })
    }
    val indexStart = sections.values.firstOrNull() ?: -1
    // The index's own lane: the rows beside it end where it starts, so it never covers their call and message buttons.
    val laneEnd = if (indexed) AlphabetIndexDefaults.RowEndPadding else 0.dp

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            item(key = "groups") { chips() }
            // Searching or filtering while private contacts' details are locked: say so, with the unlock.
            if (privateLocked && (query.isNotBlank() || !filter.fields.isEmpty)) item(key = "private-locked") { PrivateSearchLocked(vm) }
            if (showMe) item(key = "me") { MeCardRow(vm, open) }
            if (showFavorites) item(key = "favorites") { ContactsFavorites(vm, open, onReorder = onReorderFavorites) }
            if (showCircle) item(key = "circle") { CircleFavoritesSection(vm, open, "") }
            if (count == 0 && work.isEmpty() && !recallShows) {
                item(key = "empty") {
                    EmptyState(
                        Icons.Rounded.People,
                        when {
                            query.isNotBlank() -> stringResource(R.string.contacts_no_matches, query)
                            !filter.isEmpty -> stringResource(R.string.contacts_no_filter_match)
                            // The "Private" filter with no private contacts yet.
                            privateOnly -> stringResource(R.string.contacts_no_private)
                            else -> stringResource(R.string.contacts_none)
                        },
                        modifier = Modifier.padding(top = 48.dp),
                        // One way on for each case.
                        action = stringResource(
                            when {
                                query.isNotBlank() -> R.string.ux_empty_clear_search
                                !filter.isEmpty -> R.string.ux_empty_clear_filter
                                privateOnly -> R.string.ux_empty_add_private
                                else -> R.string.ux_empty_add_contact
                            },
                        ),
                        onAction = {
                            when {
                                query.isNotBlank() -> vm.contactQuery.value = ""
                                !filter.isEmpty -> vm.people.clearFilter()
                                privateOnly -> open(Routes.edit(vault = 0))
                                else -> open(Routes.edit())
                            }
                        },
                    )
                }
            }
            runs.forEach { run ->
                run.section?.let { s ->
                    stickyHeader(key = "s$s", contentType = CONTENT_LETTER) {
                        ListSectionHeader(s, sticky = true, inset = Spacing.xl)
                    }
                }
                items(run.items, key = { it.id }, contentType = { CONTENT_CONTACT }) { c ->
                    // Each row follows only its own part of the shared state: ticking one contact recomposes that row.
                    val selected by remember(c.id) { derivedStateOf { c.id in selectionState.value } }
                    val selecting by remember { derivedStateOf { selectionState.value.isNotEmpty() } }
                    val secondLine by remember(c.id) { derivedStateOf { hintsState.value[c.id] ?: secondLinesState.value[c.id] } }
                    val isCompany by remember(c) {
                        derivedStateOf {
                            val company = indexState.value.extras[c.id]?.company.orEmpty()
                            company.isNotBlank() && company.trim().equals(c.displayName.trim(), ignoreCase = true)
                        }
                    }
                    val number = remember(c) { (c.phones.firstOrNull { it.isPrimary } ?: c.phones.firstOrNull())?.number }
                    // Its own lane beside the A–Z index.
                    Box(Modifier.padding(end = laneEnd)) {
                        // Opt-in swipe actions (never while selecting).
                        SwipeActionRow(
                            if (!selecting) swipe else swipe.copy(enabled = false),
                            hasNumber = number != null, canDelete = true, listState = state,
                            onAction = { a ->
                                when (a) {
                                    SwipeAction.CALL -> number?.let { vm.requestCall(it, c.displayName) }
                                    SwipeAction.MESSAGE -> quick.message(c)
                                    SwipeAction.MESSAGE_ON -> quick.message(c, ask = true)
                                    SwipeAction.BLOCK -> blockWithUndo(vm, c.phones.map { it.number })
                                    SwipeAction.DELETE -> vm.deleteContacts(listOf(c.id))
                                    SwipeAction.NONE -> Unit
                                }
                            },
                        ) {
                            ContactRow(
                                c,
                                secondLine = secondLine,
                                actions = rowActions && !selecting,
                                onCall = { n -> vm.requestCall(n, c.displayName) },
                                selected = selected,
                                selectionMode = selecting,
                                // Private contacts are selected like any (SelectionBar leaves them out of what would copy them out).
                                onLongClick = { vm.toggleSelection(c.id) },
                                onMessage = { n -> quick.message(c, n) },
                                isCompany = isCompany,
                            ) { if (selectionState.value.isNotEmpty()) vm.toggleSelection(c.id) else open(Routes.contact(c.id)) }
                        }
                    }
                }
            }
            if (recallShows) {
                recallSection(vm, recallState, query, fallback = !everything, recallExpanded, { recallExpanded = recallExpanded + it }, open)
            }
            workResultsSection(work) { n, name -> vm.requestCall(n, name) }
            // How many are shown, at the very end.
            ContactsFooter.line(count, query, filter, private = privateShown, privateList = privateOnly)
                ?.let { line -> item(key = "count") { ContactsCountFooter(line) } }
        }
        if (indexed) AlphabetIndexRail(state, indexEntries, indexStart)
        quickHost()
    }
    ContactSortSheet(vm)
}

/** The first screenful kept from last time ([app.parley.common.people.ListHead]): plain rows that open the contact. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListHeadPreview(rows: List<ListSections.Row<String, ContactSummary>>, open: (Destination) -> Unit) {
    val runs = remember(rows) { ListSections.runs(rows) }
    LazyColumn(Modifier.fillMaxSize()) {
        runs.forEach { run ->
            run.section?.let { s -> stickyHeader(key = "s$s", contentType = CONTENT_LETTER) { ListSectionHeader(s, sticky = true, inset = Spacing.xl) } }
            items(run.items, key = { it.id }, contentType = { CONTENT_CONTACT }) { c -> ContactRow(c) { open(Routes.contact(c.id)) } }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactRow(
    c: ContactSummary,
    secondLine: String? = null,
    actions: Boolean = false,
    onCall: (String) -> Unit = {},
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    /** The message button; by default a text message to the number. */
    onMessage: ((String) -> Unit)? = null,
    /** A contact that is only a company gets a building in lists too. */
    isCompany: Boolean = false,
    /** A trailing ⋮ with the row's own actions, where the list has no selection (a long-press selects elsewhere). */
    menu: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shown = LocalOpenDetail.current == Routes.Contact(c.id)
    ParleyListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.recents_select))
            // Open beside the list on a big screen: marked like a selected row, and said so.
            .then(if (shown) Modifier.semantics { this.selected = true } else Modifier),
        colors = if (selected || shown) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
        leadingContent = {
            if (selectionMode) {
                Box(
                    Modifier.size(avatarSize()).clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Rounded.Check, stringResource(R.string.contacts_selected), tint = MaterialTheme.colorScheme.onPrimary)
                }
            } else {
                Box {
                    Avatar(c.displayName, c.photoUri, avatarSize(), Modifier.shared("avatar-${c.id}"), isCompany = isCompany)
                    // A private contact: kept only in Parley, hidden from other apps.
                    if (c.id < 0) PrivateBadge(Modifier.align(Alignment.BottomEnd))
                }
            }
        },
        headlineContent = { Text(c.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.shared("name-${c.id}", bounds = true)) },
        supportingContent = secondLine?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = if (actions && c.phones.isNotEmpty()) ({
            val ctx = LocalContext.current
            val n = (c.phones.firstOrNull { it.isPrimary } ?: c.phones.first()).number
            Row {
                IconButton({ if (onMessage != null) onMessage(n) else Intents.sms(ctx, n) }) {
                    Icon(Icons.AutoMirrored.Rounded.Message, stringResource(R.string.main_message_who, c.displayName))
                }
                IconButton({ onCall(n) }) {
                    Icon(Icons.Rounded.Call, stringResource(R.string.main_call_who, c.displayName), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }) else menu,
    )
}

/** Private contacts' details are locked, so the search finds them by name and number only: one tap unlocks. */
@Composable
private fun PrivateSearchLocked(vm: AppViewModel) {
    val activity = LocalActivity.current as? ComponentActivity
    Banner(
        stringResource(R.string.cs_private_locked),
        icon = Icons.Rounded.Lock,
        action = stringResource(R.string.cs_private_unlock).takeIf { activity != null },
        onAction = { activity?.let { a -> AppLock.authenticateForVault(a) { ok -> if (ok) vm.people.privateSearch.retry() } } },
    )
}
