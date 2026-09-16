@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.cairn.reader.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.cairn.reader.R
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.prefs.ListViewMode
import com.cairn.reader.ui.components.EmptyState
import com.cairn.reader.ui.components.EntryDivider
import com.cairn.reader.ui.components.ItemActionSheet
import com.cairn.reader.ui.components.SwipeableItemRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InboxScreen(
    padding: PaddingValues,
    viewModel: InboxViewModel,
    onOpenItem: (String) -> Unit,
    onOpenWeb: (String) -> Unit,
    viewMode: ListViewMode,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val swipeCfg by viewModel.swipeActions.collectAsStateWithLifecycle()
    val compact by viewModel.compact.collectAsStateWithLifecycle()
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val picked by viewModel.picked.collectAsStateWithLifecycle()
    val markReadOnScroll by viewModel.markReadOnScroll.collectAsStateWithLifecycle()
    val stickyDates by viewModel.stickyDateHeaders.collectAsStateWithLifecycle()
    val openModes by viewModel.openModes.collectAsStateWithLifecycle()
    val openInWebDefault by viewModel.openInWebDefault.collectAsStateWithLifecycle()
    val selecting = picked.isNotEmpty()
    var sheetRow by remember { mutableStateOf<ItemListRow?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Route a tapped item per its feed's chosen open mode (Reader / in-app Browser / External).
    fun openRow(row: ItemListRow) {
        when (openModes[row.sourceId]) {
            "BROWSER" -> onOpenWeb(row.url)
            "EXTERNAL" -> runCatching {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(row.url)))
            }.onFailure { onOpenWeb(row.url) }
            // Reader / Default: honor the global "open as web page" default when the user turned it on.
            else -> if (openInWebDefault) onOpenWeb(row.url) else {
                com.cairn.reader.ui.reader.ReaderQueue.set(state.items.map { it.id })
                onOpenItem(row.id)
            }
        }
    }
    val listScope = rememberCoroutineScope()

    // Mark-as-read-on-scroll: as items pass above the top of the list, mark them read (no undo
    // spam). LazyColumn's key-based anchoring keeps the visible content from jumping when read
    // items drop out of the Unread lens.
    if (markReadOnScroll) {
        LaunchedEffect(listState, state.items) {
            // Key off the first visible item's id (not the raw index) so it stays correct even
            // when sticky date headers are interleaved into the list.
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key }
                .collect { key ->
                    val idx = state.items.indexOfFirst { it.id == key }
                    if (idx > 0) {
                        val toMark = state.items.take(idx).filter { !it.isRead }.map { it.id }
                        if (toMark.isNotEmpty()) viewModel.markReadSilent(toMark)
                    }
                }
        }
    }

    // A swipe action that needs UI context (share / open in browser) is handled here; the rest
    // are pure data changes the ViewModel owns.
    fun onSwipe(row: ItemListRow, action: com.cairn.reader.data.prefs.SwipeAction) {
        when (action) {
            com.cairn.reader.data.prefs.SwipeAction.OPEN_ORIGINAL -> onOpenWeb(row.url)
            com.cairn.reader.data.prefs.SwipeAction.SHARE ->
                com.cairn.reader.util.shareText(context, row.url, subject = row.title, chooser = null)
            else -> viewModel.swipe(row, action)
        }
    }

    androidx.activity.compose.BackHandler(enabled = selecting) { viewModel.clearPicks() }

    // Ordered folders (with summed unread) for the quick folder switcher.
    val folders = remember(feeds) {
        feeds.filter { !it.folder.isNullOrBlank() }
            .groupBy { it.folder!! }
            .map { (name, fs) -> name to fs.sumOf { it.unread } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = padding.calculateTopPadding()),
    ) {
        if (selecting) {
            com.cairn.reader.ui.components.SelectionActionBar(
                count = picked.size,
                onClose = { viewModel.clearPicks() },
                onSelectAll = { viewModel.pickAll() },
            ) {
                IconButton(onClick = { viewModel.markPickedRead(true) }) {
                    Icon(Icons.Outlined.MarkEmailRead, contentDescription = stringResource(R.string.mark_read))
                }
                IconButton(onClick = { viewModel.starPicked(true) }) {
                    Icon(Icons.Outlined.StarBorder, contentDescription = stringResource(R.string.star))
                }
                IconButton(onClick = { viewModel.savePicked(true) }) {
                    Icon(Icons.Outlined.Bookmark, contentDescription = stringResource(R.string.save_for_later))
                }
                Box {
                    var more by remember { mutableStateOf(false) }
                    IconButton(onClick = { more = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_actions))
                    }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.mark_unread)) }, onClick = { more = false; viewModel.markPickedRead(false) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.save_offline)) }, onClick = { more = false; viewModel.savePickedOffline() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.archive)) }, onClick = { more = false; viewModel.archivePicked() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.move_to_trash)) }, onClick = { more = false; viewModel.deletePicked() })
                    }
                }
            }
        } else if (folders.isNotEmpty()) {
            val allSelected = selection is com.cairn.reader.ui.inbox.DrawerSelection.All
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = allSelected,
                    onClick = { viewModel.selectAll() },
                    label = { Text(stringResource(R.string.all)) },
                )
                folders.forEach { (name, unread) ->
                    val sel = selection.let { it is com.cairn.reader.ui.inbox.DrawerSelection.Folder && it.name == name }
                    FilterChip(
                        selected = sel,
                        onClick = { viewModel.selectFolder(name) },
                        label = { Text(if (unread > 0) "$name · $unread" else name) },
                    )
                }
            }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (!state.loading && state.items.isEmpty()) {
                InboxEmptyState(state.filter)
            } else {
                val inboxRow: @Composable (ItemListRow) -> Unit = { row ->
                    SwipeableItemRow(
                        row = row,
                        onOpen = { if (selecting) viewModel.togglePick(row.id) else openRow(row) },
                        onLongPress = { if (selecting) viewModel.togglePick(row.id) else sheetRow = row },
                        selected = row.id in picked,
                        swipeEnabled = !selecting,
                        rightHalf = swipeCfg.rightHalf,
                        rightFull = swipeCfg.rightFull,
                        leftHalf = swipeCfg.leftHalf,
                        leftFull = swipeCfg.leftFull,
                        onAction = { action -> onSwipe(row, action) },
                        mode = viewMode,
                        compact = compact,
                        onOpenSource = { sid -> viewModel.selectFeed(sid, row.sourceTitle ?: row.siteName ?: "Feed") },
                    )
                    if (viewMode != ListViewMode.MAGAZINE) {
                        EntryDivider()
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 2.dp, bottom = padding.calculateBottomPadding() + 96.dp),
                ) {
                    if (stickyDates) {
                        // Group the (already time-sorted) list by day; LinkedHashMap keeps order.
                        val groups = state.items.groupBy { inboxDateLabel(it.publishedAt ?: it.savedAt) }
                        groups.forEach { (label, rows) ->
                            stickyHeader(key = "date-$label") { InboxDateHeader(label) }
                            items(rows, key = { it.id }) { row -> inboxRow(row) }
                        }
                    } else {
                        items(state.items, key = { it.id }) { row -> inboxRow(row) }
                    }
                }
            }
            // Scroll-to-top FAB: appears once the list is scrolled a few rows down, above the "+" FAB.
            val showScrollTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 4 } }
            if (showScrollTop) {
                Box(Modifier.fillMaxSize()) {
                    SmallFloatingActionButton(
                        onClick = { listScope.launch { listState.animateScrollToItem(0) } },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 4.dp, bottom = 80.dp),
                    ) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.scroll_to_top))
                    }
                }
            }
        }
    }

    sheetRow?.let { row ->
        ItemActionSheet(
            row = row,
            onMarkRead = { read -> viewModel.markRead(row.id, read) },
            onToggleStar = { starred -> viewModel.toggleStar(row.id, starred) },
            onToggleSave = { save -> viewModel.toggleSave(row.id, save) },
            onArchive = { viewModel.archive(row.id) },
            onOpenOriginal = { onOpenWeb(row.url) },
            onDismiss = { sheetRow = null },
            onSaveOffline = { viewModel.saveOffline(row.id) },
            onMarkAbove = { viewModel.markAboveRead(row) },
            onMarkBelow = { viewModel.markBelowRead(row) },
            onDelete = { viewModel.delete(row.id) },
            onSelect = { viewModel.togglePick(row.id) },
        )
    }
}

/** A human day label ("Today", "Yesterday", or a date) for the Inbox sticky headers. */
private fun inboxDateLabel(millis: Long): String {
    if (millis <= 0L) return "Earlier"
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    fun dayKey(c: java.util.Calendar) = c.get(java.util.Calendar.YEAR) * 1000 + c.get(java.util.Calendar.DAY_OF_YEAR)
    val diff = dayKey(now) - dayKey(then)
    return when {
        diff == 0 -> "Today"
        diff == 1 -> "Yesterday"
        now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) ->
            java.text.SimpleDateFormat("EEEE, MMM d", java.util.Locale.getDefault()).format(java.util.Date(millis))
        else -> java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(millis))
    }
}

@Composable
private fun InboxDateHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
    )
}

@Composable
private fun InboxEmptyState(filter: InboxFilter) {
    val (icon, title, body) = when (filter) {
        InboxFilter.UNREAD -> Triple(
            Icons.Outlined.Inbox,
            "You're all caught up",
            "New articles from your feeds land here. Tap Add feed, or share a link to Cairn, to get started.",
        )
        InboxFilter.STARRED -> Triple(
            Icons.Outlined.Bookmark,
            "No starred stories",
            "Star a story from its menu to keep it here. Starred stories stay put no matter how you triage the rest.",
        )
        InboxFilter.SAVED -> Triple(
            Icons.Outlined.Bookmark,
            "Nothing saved yet",
            "Swipe a story right, or use its menu, to save it for later. Saved stories stay here until you're done.",
        )
        InboxFilter.ALL -> Triple(
            Icons.Outlined.Inbox,
            "Nothing here yet",
            "Add a feed or share a link to Cairn, and everything you collect will appear in this list.",
        )
    }
    EmptyState(title = title, body = body, icon = icon)
}
