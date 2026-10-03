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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import app.parley.common.people.FastScroll
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
import app.parley.ui.contact.PrivateBadge
import app.parley.ui.avatarSize
import kotlinx.coroutines.launch
import app.parley.common.ux.ListSections
import app.parley.ui.ListSectionHeader
import app.parley.ui.Spacing
import app.parley.ui.ParleyListItem
import app.parley.ui.Banner
import app.parley.security.AppLock
import androidx.fragment.app.FragmentActivity
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
    val selection by vm.selection.collectAsStateWithLifecycle()
    val secondLines by vm.people.secondLines.collectAsStateWithLifecycle()
    val filter by vm.people.filter.collectAsStateWithLifecycle()

    val showVault by vm.showVault.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // The "Private" chip is a filter of the one list (private contacts are listed with everyone else).
    val privateOnly = showVault && !settings.hideVault
    val chips: @Composable () -> Unit = { ContactsFilterChips(vm, showVault, settings.hideVault, open) }
    val peopleSettings by vm.people.settings.collectAsStateWithLifecycle()
    val hints by vm.people.searchHints.collectAsStateWithLifecycle()
    val privateLocked = vm.people.privateSearch.locked.collectAsStateWithLifecycle().value && !settings.hideVault
    val index by vm.people.index.collectAsStateWithLifecycle()
    // The row's message button and a "Message" swipe use each person's usual way to message.
    val (quick, quickHost) = rememberQuickMessenger(vm)
    // The work profile's matches, read-only, under the search results (not while filtering the list).
    val work = rememberWorkResults(if (filter.isEmpty && !privateOnly) query else "")

    val rows = listing
    if (rows == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Build (index of first item for each section) for the fast-scroll rail.
    // "My card" leads the list when nothing is being searched or filtered.
    val showMe = query.isBlank() && filter.isEmpty && selection.isEmpty()
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

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            item(key = "groups") { chips() }
            // Searching or filtering while private contacts' details are locked: say so, with the unlock.
            if (privateLocked && (query.isNotBlank() || !filter.fields.isEmpty)) item(key = "private-locked") { PrivateSearchLocked(vm) }
            if (showMe) item(key = "me") { MeCardRow(vm, open) }
            if (showFavorites) item(key = "favorites") { ContactsFavorites(vm, open, onReorder = onReorderFavorites) }
            if (showCircle) item(key = "circle") { CircleFavoritesSection(vm, open, "") }
            if (count == 0 && work.isEmpty()) {
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
            rows.forEach { row ->
                if (row is ListSections.Row.Header) {
                    val s = row.section
                    stickyHeader(key = "s$s", contentType = CONTENT_LETTER) {
                        ListSectionHeader(s, sticky = true, inset = Spacing.xl)
                    }
                    return@forEach
                }
                val c = (row as ListSections.Row.Item).item
                item(key = c.id, contentType = CONTENT_CONTACT) {
                    val number = (c.phones.firstOrNull { it.isPrimary } ?: c.phones.firstOrNull())?.number
                    // Opt-in swipe actions (never while selecting).
                    SwipeActionRow(
                        if (selection.isEmpty()) peopleSettings.swipe else peopleSettings.swipe.copy(enabled = false),
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
                            secondLine = hints[c.id] ?: secondLines[c.id],
                            actions = settings.contactRowActions && selection.isEmpty(),
                            onCall = { n -> vm.requestCall(n, c.displayName) },
                            selected = c.id in selection,
                            selectionMode = selection.isNotEmpty(),
                            // Private contacts are selected like any (SelectionBar leaves them out of what would copy them out).
                            onLongClick = { vm.toggleSelection(c.id) },
                            onMessage = { n -> quick.message(c, n) },
                            isCompany = index.extras[c.id]?.let { e -> e.company.isNotBlank() && e.company.trim().equals(c.displayName.trim(), ignoreCase = true) } == true,
                        ) { if (selection.isNotEmpty()) vm.toggleSelection(c.id) else open(Routes.contact(c.id)) }
                    }
                }
            }
            workResultsSection(work) { n, name -> vm.requestCall(n, name) }
            // How many are shown, at the very end.
            ContactsFooter.line(count, query, filter, private = privateShown, privateList = privateOnly)
                ?.let { line -> item(key = "count") { ContactsCountFooter(line) } }
        }
        if (query.isBlank() && count > 30) {
            // "★" jumps to the favourites when they're at the top of Contacts.
            val favIndex = 1 + (if (showMe) 1 else 0)
            val letters = remember(sections, showFavorites) { (if (showFavorites) listOf(FAVOURITES_MARK) else emptyList()) + sections.keys }
            val starts = remember(sections, showFavorites, favIndex) { (if (showFavorites) listOf(favIndex) else emptyList()) + sections.values }
            val atTop by remember(starts) { derivedStateOf { FastScroll.sectionAt(state.firstVisibleItemIndex, starts).coerceAtLeast(0) } }
            FastScrollRail(letters, atTop, Modifier.align(Alignment.CenterEnd)) { i ->
                starts.getOrNull(i)?.let { scope.launch { state.scrollToItem(it) } }
            }
        }
        quickHost()
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
    onClick: () -> Unit,
) {
    ParleyListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.recents_select)),
        colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
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
        }) else null,
    )
}

/** Private contacts' details are locked, so the search finds them by name and number only: one tap unlocks. */
@Composable
private fun PrivateSearchLocked(vm: AppViewModel) {
    val activity = LocalActivity.current as? FragmentActivity
    Banner(
        stringResource(R.string.cs_private_locked),
        icon = Icons.Rounded.Lock,
        action = stringResource(R.string.cs_private_unlock).takeIf { activity != null },
        onAction = { activity?.let { a -> AppLock.authenticateForVault(a) { ok -> if (ok) vm.people.privateSearch.retry() } } },
    )
}

/** The rail entry for the favourites section. */
private const val FAVOURITES_MARK = "\u2605"
