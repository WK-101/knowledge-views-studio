package app.parley.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
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
    val list by vm.filteredContacts.collectAsStateWithLifecycle()
    val query by vm.contactQuery.collectAsStateWithLifecycle()
    val selectedGroup by vm.selectedGroup.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    var groups by remember { mutableStateOf<List<GroupInfo>>(emptyList()) }
    val all by vm.contacts.collectAsStateWithLifecycle()
    LaunchedEffect(all?.size) { groups = withContext(Dispatchers.IO) { vm.c.contacts.groups() } }

    val showVault by vm.showVault.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val vaultList by vm.c.vault.contacts.collectAsStateWithLifecycle()
    val chips: @Composable () -> Unit = {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selectedGroup == null && !showVault, { vm.showVault.value = false; vm.selectGroup(null) }, label = { Text("All") })
            if (!settings.hideVault) {
                FilterChip(
                    showVault, { vm.showVault.value = !showVault; vm.selectGroup(null) },
                    label = { Text("Private") },
                    leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Lock, null, Modifier.size(16.dp)) },
                )
            }
            groups.distinctBy { it.title }.forEach { g ->
                FilterChip(selectedGroup == g.id && !showVault, { vm.showVault.value = false; vm.selectGroup(if (selectedGroup == g.id) null else g.id) }, label = { Text(g.title) })
            }
        }
    }
    if (showVault && !settings.hideVault) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { chips() }
            val shown = vaultList.filter { app.parley.common.TextSearch.matches(query, it.name, it.numbers) }
            if (shown.isEmpty()) {
                item {
                    EmptyState(
                        androidx.compose.material.icons.Icons.Rounded.Lock, "No private contacts",
                        "Private contacts are encrypted and only visible in Parley — other apps (messengers, keyboards) can't read them. Calls from them still show their name.",
                        Modifier.padding(top = 32.dp),
                    )
                }
            }
            shown.forEach { v ->
                item(key = "v" + v.id) {
                    ListItem(
                        modifier = Modifier.clickable { open(Routes.vault(v.id)) },
                        leadingContent = { Avatar(v.name, null, avatarSize()) },
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
    val sections = remember(contacts) {
        val map = LinkedHashMap<String, Int>()
        var idx = 1 // item 0 is the group chips row
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
            if (contacts.isEmpty()) {
                item(key = "empty") {
                    EmptyState(Icons.Rounded.People, if (query.isBlank()) "No contacts" else "No matches for “$query”", modifier = Modifier.padding(top = 48.dp))
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
                    ContactRow(
                        c,
                        selected = c.id in selection,
                        selectionMode = selection.isNotEmpty(),
                        onLongClick = { vm.toggleSelection(c.id) },
                    ) { if (selection.isNotEmpty()) vm.toggleSelection(c.id) else open(Routes.contact(c.id)) }
                }
            }
        }
        if (query.isBlank() && contacts.size > 30) {
            FastScroller(sections.keys.toList(), Modifier.align(Alignment.CenterEnd)) { s ->
                sections[s]?.let { scope.launch { state.scrollToItem(it) } }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactRow(
    c: ContactSummary,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select"),
        colors = if (selected) androidx.compose.material3.ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else androidx.compose.material3.ListItemDefaults.colors(),
        leadingContent = {
            if (selectionMode) {
                Box(
                    Modifier.size(avatarSize()).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.onPrimary)
                }
            } else {
                Avatar(c.displayName, c.photoUri, avatarSize(), Modifier.shared("avatar-${c.id}"))
            }
        },
        headlineContent = { Text(c.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.shared("name-${c.id}", bounds = true)) },
    )
}

@Composable
private fun FastScroller(letters: List<String>, modifier: Modifier, onLetter: (String) -> Unit) {
    var height by remember { mutableStateOf(1) }
    Column(
        modifier
            .fillMaxHeight()
            .width(28.dp)
            .padding(vertical = 8.dp)
            .onSizeChanged { height = it.height }
            .semantics { contentDescription = "Alphabet index" }
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
            Text(l, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable { onLetter(l) })
        }
    }
}
