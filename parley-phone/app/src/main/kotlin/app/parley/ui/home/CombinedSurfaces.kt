package app.parley.ui.home

import app.parley.ui.Destination
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.RecentGroup
import app.parley.common.ContactSummary
import app.parley.common.FavoritesPlacement
import app.parley.common.StartTab
import app.parley.common.circle.FavouritesView
import app.parley.common.homeLayout
import app.parley.ui.circle.CircleRows
import app.parley.ui.circle.FavouritesCircleSwitch
import app.parley.ui.circle.rememberCircleDue
import app.parley.ui.circle.rememberFavouritesView
import app.parley.common.calls.CallSource
import app.parley.ui.Avatar
import app.parley.ui.Routes
import kotlinx.coroutines.launch
import app.parley.ui.ParleySheet
import app.parley.ui.ParleyFab
import app.parley.ui.ParleyFabDefaults
import app.parley.ui.ParleyShapes
import app.parley.ui.ListSectionHeader
import app.parley.ui.Spacing
import app.parley.ui.ReorderMath
import app.parley.ui.avatarSize
import app.parley.ui.rememberReorderState
import app.parley.ui.reorderActions
import app.parley.ui.reorderHandle
import app.parley.ui.reorderable

/**
 * The keypad docked at the foot of Recents. [expanded] is kept by the home screen for the session
 * (tab switches and rotation keep it); [idle] is what the results area shows while nothing is typed. [backFolds]:
 * Back folds the unfolded keypad (not while something more pressing, like a selection, waits for Back).
 */
class KeypadDock(
    val expanded: Boolean,
    val onExpandedChange: (Boolean) -> Unit,
    val backFolds: Boolean = true,
    val idle: @Composable () -> Unit,
)

/**
 * Recents with the keypad docked at the bottom ("Calls layout: Combined"). Typing replaces the calls with the
 * keypad's matches in place; the header search searches the calls as on the Recents tab (the keypad folds away).
 */
@Composable
fun CallsSurface(
    vm: AppViewModel, open: (Destination) -> Unit, searching: Boolean, expanded: Boolean, backFolds: Boolean, onExpandedChange: (Boolean) -> Unit,
) {
    if (searching) {
        RecentsTab(vm, open)
        return
    }
    KeypadTab(
        vm, open,
        dock = KeypadDock(expanded, onExpandedChange, backFolds) {
            // Room under the last call for the keypad button while the keypad is folded.
            RecentsTab(vm, open, bottomPadding = if (expanded) 0.dp else ParleyFabDefaults.Reserve)
        },
    )
}

/**
 * The folded keypad: a keypad button, the screen's [ParleyFab] (same size, corner and place as Contacts' add button),
 * that springs in as the keypad folds, with the typed number's last digits as a badge; tap it, or drag it up (the
 * keypad follows), to bring the keypad back.
 */
@Composable
internal fun DockedKeypadButton(
    visible: Boolean, fold: DockFoldState, number: String?, badge: String?, modifier: Modifier = Modifier, onExpand: () -> Unit,
) {
    val label = if (number == null) stringResource(R.string.keypad_show) else stringResource(R.string.home_show_keypad_with, number)
    ParleyFab(
        Icons.Rounded.Dialpad, label, onExpand,
        modifier = modifier
            // Fades as a drag up brings the keypad in.
            .graphicsLayer { alpha = 1f - (fold.value * 1.5f).coerceIn(0f, 1f) }
            .draggable(rememberDraggableState { fold.drag(it) }, Orientation.Vertical, onDragStopped = { v -> fold.release(v) }),
        visible = visible,
        badge = badge,
    )
}

// ---------------------------------------------------------------- Favourites in Contacts

/**
 * The favourites at the top of Contacts, as a folding section (tiles in the Favourites grid's columns)
 * or a strip of avatars, with an optional "Frequent" row. Tap calls, a long press opens the contact, as on the
 * Favourites tab. The folded state is remembered (it's the user's choice). While the Circle tab is hidden, a
 * Favourites · Circle switch under the header shows the Circle here instead (its one home); Reorder is the
 * favourites' own.
 */
@Composable
fun ContactsFavorites(vm: AppViewModel, open: (Destination) -> Unit, onReorder: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val favorites by vm.people.favorites.collectAsStateWithLifecycle()
    val frequents by vm.frequents.collectAsStateWithLifecycle()
    val ps by vm.people.settings.collectAsStateWithLifecycle()
    val surfaces = settings.surfaces
    val collapsed = surfaces.favoritesCollapsed
    val scope = rememberCoroutineScope()
    fun toggle() {
        scope.launch { vm.c.settings.update { it.copy(surfaces = it.surfaces.copy(favoritesCollapsed = !it.surfaces.favoritesCollapsed)) } }
    }
    val shownFrequents = if (surfaces.frequentsRow) frequents else emptyList()
    val (switchShown, view) = rememberFavouritesView(vm, settings.homeLayout.hasCircleSwitch(StartTab.CONTACTS))
    val circle = view == FavouritesView.CIRCLE

    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        SectionHeader(
            title = stringResource(if (circle) R.string.circle_section else R.string.label_favourites),
            count = if (circle) 0 else favorites.size,
            collapsed = collapsed,
            onToggle = ::toggle,
            onReorder = if (!circle && favorites.size > 1 && !collapsed) onReorder else null,
        )
        if (collapsed) return@Column
        if (switchShown) FavouritesCircleSwitch(vm, view, Modifier.padding(bottom = Spacing.s))
        if (circle) CircleRows(vm, open, "") else FavouritesBody(vm, open, favorites, surfaces.favorites, ps.favoriteColumns, shownFrequents)
        HorizontalDivider(Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/** The favourites side of the section: tiles or a strip, the Do Not Disturb row and Frequent. */
@Composable
private fun FavouritesBody(
    vm: AppViewModel, open: (Destination) -> Unit, favorites: List<ContactSummary>, placement: FavoritesPlacement, columns: Int,
    frequents: List<RecentGroup>,
) {
    fun call(c: ContactSummary) {
        c.phones.firstOrNull()?.let { p -> vm.requestCall(p.number, c.displayName, source = CallSource.FAVORITE) } ?: open(Routes.contact(c.id))
    }
    // Circle members due a catch-up get a small dot on their tile.
    val due = rememberCircleDue(vm)
    if (favorites.isEmpty()) {
        Text(
            stringResource(R.string.home_fav_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
    } else if (placement == FavoritesPlacement.STRIP) {
        AvatarStrip(favorites.map { StripItem("f" + it.id, it.displayName, it.photoUri, { call(it) }, { open(Routes.contact(it.id)) }) })
    } else {
        FavoriteGrid(favorites, columns, due, onCall = ::call, onOpen = { open(Routes.contact(it.id)) })
    }
    // Starred only for a label's Do Not Disturb: one folded row, as on the Favourites tab.
    DndStarredRow(vm, open)
    if (frequents.isNotEmpty()) {
        ListSectionHeader(stringResource(R.string.fav_frequent), inset = Spacing.xl)
        AvatarStrip(frequents.map { g -> g.stripItem(vm, open) })
    }
}

private fun RecentGroup.stripItem(vm: AppViewModel, open: (Destination) -> Unit) = StripItem(
    "q$key", title, contact?.photoUri,
    onClick = { vm.requestCall(number, contact?.displayName, source = CallSource.FAVORITE) },
    onLong = { contact?.let { open(Routes.contact(it.id)) } ?: open(Routes.history(number)) },
)

/** The folding header: "Favourites (n)", Reorder, and a chevron. TalkBack reads it as a heading with its state. */
@Composable
private fun SectionHeader(title: String, count: Int, collapsed: Boolean, onToggle: () -> Unit, onReorder: (() -> Unit)?) {
    val state = stringResource(if (collapsed) R.string.label_folded else R.string.home_unfolded)
    val action = stringResource(if (collapsed) R.string.action_unfold else R.string.action_fold)
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClickLabel = action, onClick = onToggle)
            .semantics { heading(); stateDescription = state }
            .padding(start = 24.dp, end = 4.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (count > 0) stringResource(R.string.format_text_count, title, count) else title,
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
        )
        if (onReorder != null) TextButton(onReorder) { Text(stringResource(R.string.fav_reorder)) }
        Icon(if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, null, modifier = Modifier.padding(12.dp))
    }
}

/** The section's tiles, in the Favourites tab's number of columns (automatic: as many as fit). */
@Composable
private fun FavoriteGrid(favorites: List<ContactSummary>, columns: Int, due: Set<String>, onCall: (ContactSummary) -> Unit, onOpen: (ContactSummary) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        val n = if (columns > 0) columns else (maxWidth / 104.dp).toInt().coerceIn(2, 8)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            favorites.chunked(n).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { c ->
                        Tile(
                            c.displayName, c.photoUri, onClick = { onCall(c) }, onLong = { onOpen(c) }, modifier = Modifier.weight(1f), isPrivate = c.id < 0,
                            due = c.lookupKey in due,
                        )
                    }
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
    val callLabel = stringResource(R.string.action_call)
    val openLabel = stringResource(R.string.main_open_contact)
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(items, key = { it.key }) { item ->
            Column(
                Modifier.width(80.dp).clip(ParleyShapes.tile)
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
 * Reorder the favourites from Contacts (the section header's Reorder): drag a row by its handle, or use
 * TalkBack's "Move earlier / later". The order is the same custom order the Favourites tab uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReorderFavoritesSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val favorites by vm.people.favorites.collectAsStateWithLifecycle()
    var order by remember { mutableStateOf(favorites) }
    val reorder = rememberReorderState<String>()
    LaunchedEffect(favorites) { if (reorder.lifted == null) order = favorites }
    fun commit() = vm.people.setFavoriteOrder(order.map { it.lookupKey })
    val moveEarlier = stringResource(R.string.fav_move_earlier)
    val moveLater = stringResource(R.string.fav_move_later)
    ParleySheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = Spacing.xl, end = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_reorder_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onDismiss) { Text(stringResource(R.string.dc_done)) }
            }
            Text(
                stringResource(R.string.fav_reorder_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xs),
            )
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Spacing.l)) {
                order.forEachIndexed { i, c ->
                    androidx.compose.runtime.key(c.lookupKey) {
                        Row(
                            Modifier.fillMaxWidth()
                                .reorderable(reorder, c.lookupKey)
                                .heightIn(min = 56.dp)
                                .reorderActions(moveEarlier, moveLater, i, order.size) { to -> order = ReorderMath.moved(order, i, to); commit() }
                                .padding(horizontal = Spacing.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp).padding(Spacing.s)
                                    .reorderHandle(
                                        reorder, c.lookupKey, index = { order.indexOfFirst { it.lookupKey == c.lookupKey } }, count = { order.size },
                                        move = { from, to -> order = ReorderMath.moved(order, from, to) },
                                        onDrop = ::commit,
                                        onCancel = { order = favorites },
                                    ),
                            )
                            Spacer(Modifier.width(Spacing.s))
                            // A list row: its photo follows the list density, as in Contacts.
                            Avatar(c.displayName, c.photoUri, avatarSize())
                            Spacer(Modifier.width(Spacing.l))
                            Text(c.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
