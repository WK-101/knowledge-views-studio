package app.parley.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.data.GroupInfo
import app.parley.ui.Avatar
import app.parley.ui.shared
import app.parley.ui.EmptyState
import app.parley.ui.Routes
import app.parley.ui.avatarSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

fun sectionOf(name: String): String {
    val c = name.firstOrNull { it.isLetterOrDigit() } ?: return "#"
    if (c.isDigit()) return "#"
    val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).first().uppercaseChar()
    return base.toString()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactsTab(vm: AppViewModel, open: (String) -> Unit) {
    val list by vm.people.filtered.collectAsStateWithLifecycle()
    val query by vm.contactQuery.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val secondLines by vm.people.secondLines.collectAsStateWithLifecycle()
    val filter by vm.people.filter.collectAsStateWithLifecycle()

    val showVault by vm.showVault.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val vaultList by vm.c.vault.contacts.collectAsStateWithLifecycle()
    val chips: @Composable () -> Unit = { app.parley.ui.people.ContactsFilterChips(vm, showVault, settings.hideVault, open) }
    val peopleSettings by vm.people.settings.collectAsStateWithLifecycle()
    val hints by vm.people.searchHints.collectAsStateWithLifecycle()
    val index by vm.people.index.collectAsStateWithLifecycle()
    // M7: the row's message button and a "Message" swipe use each person's usual way to message.
    val (quick, quickHost) = app.parley.ui.contact.rememberQuickMessenger(vm)
    if (showVault && !settings.hideVault) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { chips() }
            val shown = vaultList.filter { app.parley.common.TextSearch.matches(query, it.name, it.numbers) }
            if (shown.isEmpty()) {
                item {
                    EmptyState(
                        androidx.compose.material.icons.Icons.Rounded.Lock, stringResource(R.string.contacts_no_private),
                        stringResource(R.string.contacts_no_private_body),
                        Modifier.padding(top = 32.dp),
                    )
                }
            }
            shown.forEach { v ->
                item(key = "v" + v.id) {
                    ListItem(
                        modifier = Modifier.clickable { open(Routes.vault(v.id)) },
                        leadingContent = { Avatar(v.name, remember(v.id, v.updatedAt) { vm.c.vault.photoUri(v.id) }, avatarSize()) },
                        headlineContent = { Text(v.name) },
                        supportingContent = v.numbers.firstOrNull()?.let { n -> { Text(app.parley.ui.common.Format.number(n, vm.countryIso)) } },
                    )
                }
            }
        }
        return
    }

    val contacts = list
    if (contacts == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Build (index of first item for each section) for the fast-scroll rail.
    // I2: "My card" leads the list when nothing is being searched or filtered.
    val showMe = query.isBlank() && filter.isEmpty && selection.isEmpty()
    val sections = remember(contacts, showMe) {
        val map = LinkedHashMap<String, Int>()
        var idx = if (showMe) 2 else 1 // item 0 is the group chips row, then "My card"
        contacts.forEachIndexed { i, c ->
            val s = sectionOf(c.displayName)
            if (s !in map) {
                map[s] = idx
                idx++
            }
            idx++
        }
        map
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            item(key = "groups") { chips() }
            if (showMe) item(key = "me") { app.parley.ui.people.MeCardRow(vm, open) }
            if (contacts.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        Icons.Rounded.People,
                        when {
                            query.isNotBlank() -> stringResource(R.string.contacts_no_matches, query)
                            !filter.isEmpty -> stringResource(R.string.contacts_no_filter_match)
                            else -> stringResource(R.string.contacts_none)
                        },
                        modifier = Modifier.padding(top = 48.dp),
                    )
                }
            }
            var last: String? = null
            contacts.forEach { c ->
                val s = sectionOf(c.displayName)
                if (s != last) {
                    last = s
                    stickyHeader(key = "s$s") {
                        Text(
                            s, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 24.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }
                }
                item(key = c.id) {
                    val number = (c.phones.firstOrNull { it.isPrimary } ?: c.phones.firstOrNull())?.number
                    // U4: opt-in swipe actions (never while selecting).
                    app.parley.ui.people.SwipeActionRow(
                        if (selection.isEmpty()) peopleSettings.swipe else peopleSettings.swipe.copy(enabled = false),
                        hasNumber = number != null, canDelete = true,
                        onAction = { a ->
                            when (a) {
                                app.parley.common.people.SwipeAction.CALL -> number?.let { vm.requestCall(it, c.displayName) }
                                app.parley.common.people.SwipeAction.MESSAGE -> quick.message(c)
                                app.parley.common.people.SwipeAction.MESSAGE_ON -> quick.message(c, ask = true)
                                app.parley.common.people.SwipeAction.BLOCK -> c.phones.forEach { vm.blockNumber(it.number) }
                                app.parley.common.people.SwipeAction.DELETE -> vm.deleteContacts(listOf(c.id))
                                app.parley.common.people.SwipeAction.NONE -> Unit
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
                            onLongClick = { vm.toggleSelection(c.id) },
                            onMessage = { n -> quick.message(c, n) },
                            isCompany = index.extras[c.id]?.let { e -> e.company.isNotBlank() && e.company.trim().equals(c.displayName.trim(), ignoreCase = true) } == true,
                        ) { if (selection.isNotEmpty()) vm.toggleSelection(c.id) else open(Routes.contact(c.id)) }
                    }
                }
            }
        }
        if (query.isBlank() && contacts.size > 30) {
            FastScroller(sections.keys.toList(), Modifier.align(Alignment.CenterEnd)) { s ->
                sections[s]?.let { scope.launch { state.scrollToItem(it) } }
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
    /** M7: the message button; by default a text message to the number. */
    onMessage: ((String) -> Unit)? = null,
    /** U6: a contact that is only a company gets a building in lists too. */
    isCompany: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.recents_select)),
        colors = if (selected) androidx.compose.material3.ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else androidx.compose.material3.ListItemDefaults.colors(),
        leadingContent = {
            if (selectionMode) {
                Box(
                    Modifier.size(avatarSize()).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Check, stringResource(R.string.contacts_selected), tint = MaterialTheme.colorScheme.onPrimary)
                }
            } else {
                Avatar(c.displayName, c.photoUri, avatarSize(), Modifier.shared("avatar-${c.id}"), isCompany = isCompany)
            }
        },
        headlineContent = { Text(c.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.shared("name-${c.id}", bounds = true)) },
        supportingContent = secondLine?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = if (actions && c.phones.isNotEmpty()) ({
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val n = (c.phones.firstOrNull { it.isPrimary } ?: c.phones.first()).number
            Row {
                androidx.compose.material3.IconButton({ if (onMessage != null) onMessage(n) else app.parley.ui.common.Intents.sms(ctx, n) }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.Message, stringResource(R.string.main_message_who, c.displayName))
                }
                androidx.compose.material3.IconButton({ onCall(n) }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Call, stringResource(R.string.main_call_who, c.displayName), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }) else null,
    )
}

@Composable
private fun FastScroller(letters: List<String>, modifier: Modifier, onLetter: (String) -> Unit) {
    var height by remember { mutableStateOf(1) }
    // U7: letters as large as fit (8 to 13 sp), so a short alphabet isn't tiny and a long one doesn't overlap.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val letterSp = with(density) { (height / letters.size.coerceAtLeast(1) * 0.62f).toSp().value }.coerceIn(8f, 13f)
    val indexLabel = stringResource(R.string.contacts_alphabet_index)
    Column(
        modifier
            .fillMaxHeight()
            .width(28.dp)
            .padding(vertical = 8.dp)
            .onSizeChanged { height = it.height }
            .semantics { contentDescription = indexLabel }
            .pointerInput(letters) {
                fun pick(y: Float) {
                    val i = ((y / height) * letters.size).toInt().coerceIn(0, letters.size - 1)
                    onLetter(letters[i])
                }
                detectVerticalDragGestures(onDragStart = { pick(it.y) }) { change, _ -> pick(change.position.y) }
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { l ->
            Text(l, fontSize = letterSp.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable { onLetter(l) })
        }
    }
}
