package app.parley.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.people.FavoriteOrder
import app.parley.common.people.FavoriteSort
import app.parley.ui.Avatar
import app.parley.ui.EmptyState
import app.parley.ui.Routes

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoritesTab(vm: AppViewModel, open: (String) -> Unit, query: String = "", onClearQuery: (() -> Unit)? = null) {
    val favorites by vm.people.favorites.collectAsStateWithLifecycle()
    val frequents by vm.frequents.collectAsStateWithLifecycle()
    val ps by vm.people.settings.collectAsStateWithLifecycle()
    var reordering by remember { mutableStateOf(false) }
    if (favorites.isEmpty() && frequents.isEmpty()) {
        // U5: nothing starred yet: the way on is the contact list.
        EmptyState(
            Icons.Rounded.StarOutline, stringResource(R.string.fav_empty_title), stringResource(R.string.fav_empty_body),
            action = stringResource(R.string.ux_empty_choose_favorites), onAction = { vm.navigate(app.parley.NavEvent.Tab(app.parley.common.StartTab.CONTACTS)) },
        )
        return
    }
    // Local copy while dragging; written back (by lookup key) when the drag ends.
    var order by remember { mutableStateOf(favorites) }
    LaunchedEffect(favorites) { order = favorites }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberLazyGridState()
    // Search from the header filters favourites and frequent contacts (reordering pauses while searching).
    val q = query.trim()
    LaunchedEffect(q.isNotEmpty()) { if (q.isNotEmpty()) reordering = false }
    val shownFavorites = if (q.isEmpty()) order else order.filter { app.parley.common.TextSearch.matches(q, it.displayName, it.phones.map { p -> p.number }) }
    val shownFrequents = if (q.isEmpty()) frequents else frequents.filter { app.parley.common.TextSearch.matches(q, it.title, listOf(it.number)) }
    val cells = if (ps.favoriteColumns > 0) GridCells.Fixed(ps.favoriteColumns) else GridCells.Adaptive(104.dp)
    fun commit() = vm.people.setFavoriteOrder(order.map { it.lookupKey })

    LazyVerticalGrid(
        cells,
        state = state,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().pointerInput(ps.favoriteColumns) {
            // Pinch to change the number of columns (two fingers only; one-finger scrolling is untouched).
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var zoom = 1f
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } >= 2) {
                        zoom *= event.calculateZoom()
                        if (event.changes.any { it.positionChanged() }) event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
                val current = if (ps.favoriteColumns > 0) ps.favoriteColumns else (size.width / (112.dp.toPx())).toInt().coerceIn(2, 6)
                val next = FavoriteOrder.columnsAfterPinch(current, zoom)
                if (next != current) vm.people.update { it.copy(favoriteColumns = next) }
            }
        },
    ) {
        if (q.isNotEmpty() && shownFavorites.isEmpty() && shownFrequents.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState(
                Icons.Rounded.StarOutline, stringResource(R.string.fav_no_match, q), modifier = Modifier.padding(top = 32.dp),
                action = onClearQuery?.let { stringResource(R.string.ux_empty_clear_search) }, onAction = onClearQuery,
            )
        }
        if (q.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FavoriteSort.entries.forEach { s ->
                    FilterChip(ps.favoriteSort == s, { vm.people.update { it.copy(favoriteSort = s) }; if (s != FavoriteSort.CUSTOM) reordering = false }, label = { Text(stringResource(s.labelRes)) })
                }
                if (favorites.size > 1) TextButton({
                    if (!reordering && ps.favoriteSort != FavoriteSort.CUSTOM) vm.people.setFavoriteOrder(favorites.map { it.lookupKey })
                    reordering = !reordering
                }) { Text(stringResource(if (reordering) R.string.main_done else R.string.fav_reorder)) }
            }
        }
        if (reordering) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(stringResource(R.string.fav_reorder_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
        }
        itemsIndexed(shownFavorites, key = { _, c -> "f" + c.id }) { i, c ->
            val dragging = dragKey == c.lookupKey
            val moveEarlier = stringResource(R.string.fav_move_earlier)
            val moveLater = stringResource(R.string.fav_move_later)
            val base = Modifier.animateItem(placementSpec = if (dragging) null else androidx.compose.animation.core.spring())
            if (reordering) {
                Tile(
                    c.displayName, c.photoUri, onClick = {}, onLong = {}, reorder = true,
                    modifier = base.zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { if (dragging) { translationX = dragOffset.x; translationY = dragOffset.y; scaleX = 1.08f; scaleY = 1.08f } }
                        .semantics {
                            customActions = listOfNotNull(
                                if (i > 0) CustomAccessibilityAction(moveEarlier) { order = order.moved(i, i - 1); commit(); true } else null,
                                if (i < order.size - 1) CustomAccessibilityAction(moveLater) { order = order.moved(i, i + 1); commit(); true } else null,
                            )
                        }
                        .pointerInput(c.lookupKey) {
                            detectDragGestures(
                                onDragStart = { dragKey = c.lookupKey; dragOffset = Offset.Zero },
                                onDragEnd = { dragKey = null; dragOffset = Offset.Zero; commit() },
                                onDragCancel = { dragKey = null; dragOffset = Offset.Zero; order = favorites },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount
                                    val from = order.indexOfFirst { it.lookupKey == c.lookupKey }
                                    val me = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "f" + c.id } ?: return@detectDragGestures
                                    val center = Offset(me.offset.x + me.size.width / 2f + dragOffset.x, me.offset.y + me.size.height / 2f + dragOffset.y)
                                    val target = state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                        val k = info.key as? String ?: return@firstOrNull false
                                        k.startsWith("f") && k != "f" + c.id &&
                                            center.x >= info.offset.x && center.x <= info.offset.x + info.size.width &&
                                            center.y >= info.offset.y && center.y <= info.offset.y + info.size.height
                                    } ?: return@detectDragGestures
                                    val to = order.indexOfFirst { "f" + it.id == target.key }
                                    if (from >= 0 && to >= 0) {
                                        // Keep the tile under the finger after it moves to its new cell.
                                        dragOffset += Offset((me.offset.x - target.offset.x).toFloat(), (me.offset.y - target.offset.y).toFloat())
                                        order = order.moved(from, to)
                                    }
                                },
                            )
                        },
                )
            } else {
                Tile(c.displayName, c.photoUri, modifier = base, onClick = {
                    c.phones.firstOrNull()?.let { p -> vm.requestCall(p.number, c.displayName, source = app.parley.common.calls.CallSource.FAVORITE) } ?: open(Routes.contact(c.id))
                }, onLong = { open(Routes.contact(c.id)) })
            }
        }
        if (shownFrequents.isNotEmpty() && !reordering) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.fav_frequent), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp))
            }
            items(shownFrequents, key = { "q" + it.key }) { g ->
                Tile(g.title, g.contact?.photoUri, onClick = { vm.requestCall(g.number, g.contact?.displayName, source = app.parley.common.calls.CallSource.FAVORITE) }, onLong = {
                    g.contact?.let { open(Routes.contact(it.id)) } ?: open(Routes.history(g.number))
                })
            }
        }
    }
}

/** Chip text of a favourites order ([FavoriteSort.title] is the English original). */
private val FavoriteSort.labelRes: Int
    get() = when (this) {
        FavoriteSort.CUSTOM -> R.string.fav_sort_custom
        FavoriteSort.NAME -> R.string.fav_sort_name
        FavoriteSort.MOST_CALLED -> R.string.fav_sort_most_called
    }

private fun List<ContactSummary>.moved(from: Int, to: Int): List<ContactSummary> {
    if (from !in indices || to !in indices) return this
    return toMutableList().also { it.add(to, it.removeAt(from)) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(name: String, photo: String?, onClick: () -> Unit, onLong: () -> Unit, modifier: Modifier = Modifier, reorder: Boolean = false) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .then(if (reorder) Modifier else Modifier.combinedClickable(onClick = onClick, onLongClick = onLong, onClickLabel = stringResource(R.string.main_call), onLongClickLabel = stringResource(R.string.main_open_contact)))
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(name, photo, 72.dp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            if (reorder) Icon(Icons.Rounded.DragIndicator, null)
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}
