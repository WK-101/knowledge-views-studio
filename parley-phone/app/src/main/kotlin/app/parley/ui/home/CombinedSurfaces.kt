package app.parley.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.RecentGroup
import app.parley.common.ContactSummary
import app.parley.common.FavoritesPlacement
import app.parley.common.calls.CallSource
import app.parley.ui.Avatar
import app.parley.ui.Routes
import kotlinx.coroutines.launch

/**
 * S1 (v3.3): the keypad docked at the foot of Recents. [expanded] is kept by the home screen for the session
 * (tab switches and rotation keep it); [idle] is what the results area shows while nothing is typed.
 */
class KeypadDock(val expanded: Boolean, val onExpandedChange: (Boolean) -> Unit, val idle: @Composable () -> Unit)

/**
 * S1: Recents with the keypad docked at the bottom ("Calls layout: Combined"). Typing replaces the calls with the
 * keypad's matches in place; the header search searches the calls as on the Recents tab (the keypad folds away).
 */
@Composable
fun CallsSurface(vm: AppViewModel, open: (String) -> Unit, searching: Boolean, expanded: Boolean, onExpandedChange: (Boolean) -> Unit) {
    if (searching) {
        RecentsTab(vm, open)
        return
    }
    KeypadTab(
        vm, open,
        dock = KeypadDock(expanded, onExpandedChange) {
            // Room under the last call for the keypad button while the keypad is folded.
            RecentsTab(vm, open, bottomPadding = if (expanded) 0.dp else 88.dp)
        },
    )
}

/** S1: the grab handle on top of the docked keypad: tap it, or swipe it down, to fold the keypad away. */
@Composable
internal fun DockHandle(label: String, onCollapse: () -> Unit) {
    val threshold = with(LocalDensity.current) { 32.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp)
            .draggable(
                rememberDraggableState { drag += it },
                Orientation.Vertical,
                onDragStarted = { drag = 0f },
                onDragStopped = { if (drag > threshold) onCollapse() },
            )
            .clickable(onClickLabel = label, role = Role.Button, onClick = onCollapse)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.padding(vertical = 10.dp).size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}

/**
 * S1: the folded keypad: a round keypad button (with the typed number, if any). Tap it or swipe it up to bring the
 * keypad back.
 */
@Composable
internal fun DockedKeypadButton(number: String?, modifier: Modifier = Modifier, onExpand: () -> Unit) {
    val threshold = with(LocalDensity.current) { 24.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    val label = stringResource(R.string.keypad_show)
    val swipe = Modifier.draggable(
        rememberDraggableState { drag += it },
        Orientation.Vertical,
        onDragStarted = { drag = 0f },
        onDragStopped = { if (drag < -threshold) onExpand() },
    )
    if (number == null) {
        FloatingActionButton(onClick = onExpand, modifier = modifier.then(swipe), shape = CircleShape) { Icon(Icons.Rounded.Dialpad, label) }
    } else {
        val spoken = stringResource(R.string.surf_show_keypad_with, number)
        ExtendedFloatingActionButton(
            onClick = onExpand,
            modifier = modifier.then(swipe).semantics { contentDescription = spoken },
            icon = { Icon(Icons.Rounded.Dialpad, null) },
            text = { Text(number, maxLines = 1) },
        )
    }
}

// ---------------------------------------------------------------- S2: favourites in Contacts

/**
 * S2 (v3.3): the favourites at the top of Contacts, as a folding section (tiles in the Favourites grid's columns)
 * or a strip of avatars, with an optional "Frequent" row. Tap calls, a long press opens the contact, as on the
 * Favourites tab. The folded state is remembered (it's the user's choice).
 */
@Composable
fun ContactsFavorites(vm: AppViewModel, open: (String) -> Unit, onReorder: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val favorites by vm.people.favorites.collectAsStateWithLifecycle()
    val frequents by vm.frequents.collectAsStateWithLifecycle()
    val ps by vm.people.settings.collectAsStateWithLifecycle()
    val surfaces = settings.surfaces
    val collapsed = surfaces.favoritesCollapsed
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun toggle() {
        scope.launch { vm.c.settings.update { it.copy(surfaces = it.surfaces.copy(favoritesCollapsed = !it.surfaces.favoritesCollapsed)) } }
    }
    fun call(c: ContactSummary) {
        c.phones.firstOrNull()?.let { p -> vm.requestCall(p.number, c.displayName, source = CallSource.FAVORITE) } ?: open(Routes.contact(c.id))
    }
    val shownFrequents = if (surfaces.frequentsRow) frequents else emptyList()

    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        SectionHeader(
            title = stringResource(R.string.tab_favorites),
            count = favorites.size,
            collapsed = collapsed,
            onToggle = ::toggle,
            onReorder = if (favorites.size > 1 && !collapsed) onReorder else null,
        )
        if (collapsed) return@Column
        if (favorites.isEmpty()) {
            Text(
                stringResource(R.string.surf_fav_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        } else if (surfaces.favorites == FavoritesPlacement.STRIP) {
            AvatarStrip(favorites.map { StripItem("f" + it.id, it.displayName, it.photoUri, { call(it) }, { open(Routes.contact(it.id)) }) })
        } else {
            FavoriteGrid(favorites, ps.favoriteColumns, onCall = ::call, onOpen = { open(Routes.contact(it.id)) })
        }
        if (shownFrequents.isNotEmpty()) {
            Text(
                stringResource(R.string.fav_frequent), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
            )
            AvatarStrip(shownFrequents.map { g -> g.stripItem(vm, open) })
        }
        HorizontalDivider(Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

private fun RecentGroup.stripItem(vm: AppViewModel, open: (String) -> Unit) = StripItem(
    "q$key", title, contact?.photoUri,
    onClick = { vm.requestCall(number, contact?.displayName, source = CallSource.FAVORITE) },
    onLong = { contact?.let { open(Routes.contact(it.id)) } ?: open(Routes.history(number)) },
)

/** The folding header: "Favourites (n)", Reorder, and a chevron. TalkBack reads it as a heading with its state. */
@Composable
private fun SectionHeader(title: String, count: Int, collapsed: Boolean, onToggle: () -> Unit, onReorder: (() -> Unit)?) {
    val state = stringResource(if (collapsed) R.string.surf_folded else R.string.surf_unfolded)
    val action = stringResource(if (collapsed) R.string.surf_unfold else R.string.surf_fold)
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClickLabel = action, onClick = onToggle)
            .semantics { heading(); stateDescription = state }
            .padding(start = 24.dp, end = 4.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (count > 0) stringResource(R.string.surf_fav_header_count, title, count) else title,
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
        )
        if (onReorder != null) TextButton(onReorder) { Text(stringResource(R.string.fav_reorder)) }
        Icon(if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, null, modifier = Modifier.padding(12.dp))
    }
}

/** The section's tiles, in the Favourites tab's number of columns (automatic: as many as fit). */
@Composable
private fun FavoriteGrid(favorites: List<ContactSummary>, columns: Int, onCall: (ContactSummary) -> Unit, onOpen: (ContactSummary) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        val n = if (columns > 0) columns else (maxWidth / 104.dp).toInt().coerceIn(2, 8)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            favorites.chunked(n).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { c -> Tile(c.displayName, c.photoUri, onClick = { onCall(c) }, onLong = { onOpen(c) }, modifier = Modifier.weight(1f)) }
                    repeat(n - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private class StripItem(val key: String, val name: String, val photo: String?, val onClick: () -> Unit, val onLong: () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AvatarStrip(items: List<StripItem>) {
    val callLabel = stringResource(R.string.main_call)
    val openLabel = stringResource(R.string.main_open_contact)
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(items, key = { it.key }) { item ->
            Column(
                Modifier.width(80.dp).clip(RoundedCornerShape(16.dp))
                    .combinedClickable(onClick = item.onClick, onLongClick = item.onLong, onClickLabel = callLabel, onLongClickLabel = openLabel)
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(item.name, item.photo, 56.dp)
                Text(
                    item.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * S2: reorder the favourites from Contacts (the section header or Contacts ⋮): drag a row by its handle, or use
 * TalkBack's "Move earlier / later". The order is the same custom order the Favourites tab uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReorderFavoritesSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val favorites by vm.people.favorites.collectAsStateWithLifecycle()
    var order by remember { mutableStateOf(favorites) }
    var dragging by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(favorites) { if (dragging == null) order = favorites }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }
    fun commit() = vm.people.setFavoriteOrder(order.map { it.lookupKey })
    val moveEarlier = stringResource(R.string.fav_move_earlier)
    val moveLater = stringResource(R.string.fav_move_later)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.surf_reorder_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onDismiss) { Text(stringResource(R.string.main_done)) }
            }
            Text(
                stringResource(R.string.fav_reorder_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                order.forEachIndexed { i, c ->
                    androidx.compose.runtime.key(c.lookupKey) {
                        val lifted = dragging == c.lookupKey
                        Row(
                            Modifier.fillMaxWidth()
                                .zIndex(if (lifted) 1f else 0f)
                                .graphicsLayer { translationY = if (lifted) dragOffset else 0f }
                                .background(if (lifted) MaterialTheme.colorScheme.surfaceContainerHighest else androidx.compose.ui.graphics.Color.Transparent)
                                .onSizeChanged { rowHeight = it.height }
                                .heightIn(min = 56.dp)
                                .semantics {
                                    customActions = listOfNotNull(
                                        if (i > 0) CustomAccessibilityAction(moveEarlier) { order = order.movedTo(i, i - 1); commit(); true } else null,
                                        if (i < order.size - 1) CustomAccessibilityAction(moveLater) { order = order.movedTo(i, i + 1); commit(); true } else null,
                                    )
                                }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp).padding(8.dp)
                                    .pointerInput(Unit) { detectTapGestures { } }
                                    .pointerInput(c.lookupKey) {
                                        detectVerticalDragGestures(
                                            onDragStart = { dragging = c.lookupKey; dragOffset = 0f },
                                            onDragEnd = { dragging = null; dragOffset = 0f; commit() },
                                            onDragCancel = { dragging = null; dragOffset = 0f; order = favorites },
                                        ) { change, dy ->
                                            change.consume()
                                            dragOffset += dy
                                            val from = order.indexOfFirst { it.lookupKey == c.lookupKey }
                                            val step = rowHeight.toFloat()
                                            if (dragOffset > step / 2 && from < order.size - 1) {
                                                order = order.movedTo(from, from + 1)
                                                dragOffset -= step
                                            } else if (dragOffset < -step / 2 && from > 0) {
                                                order = order.movedTo(from, from - 1)
                                                dragOffset += step
                                            }
                                        }
                                    },
                            )
                            Spacer(Modifier.width(8.dp))
                            Avatar(c.displayName, c.photoUri, 40.dp)
                            Spacer(Modifier.width(16.dp))
                            Text(c.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private fun List<ContactSummary>.movedTo(from: Int, to: Int): List<ContactSummary> {
    if (from !in indices || to !in indices) return this
    return toMutableList().also { it.add(to, it.removeAt(from)) }
}
