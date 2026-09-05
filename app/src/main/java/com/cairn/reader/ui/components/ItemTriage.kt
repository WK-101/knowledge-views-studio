@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.prefs.ListViewMode
import com.cairn.reader.data.prefs.SwipeAction
import kotlinx.coroutines.launch

/**
 * A list row with two-stage, user-configurable swipe actions per direction: a short (half)
 * swipe fires the "half" action, a long (full) swipe fires the "full" action. The row is
 * dragged directly (not M3 SwipeToDismiss) so the two thresholds and their live icon/colour
 * feedback are precise. The row always springs back; every action carries Undo via the snackbar.
 */
@Composable
fun SwipeableItemRow(
    row: ItemListRow,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    rightHalf: SwipeAction,
    rightFull: SwipeAction,
    leftHalf: SwipeAction,
    leftFull: SwipeAction,
    onAction: (SwipeAction) -> Unit,
    modifier: Modifier = Modifier,
    mode: ListViewMode = ListViewMode.CARD,
    compact: Boolean = false,
    selected: Boolean = false,
    swipeEnabled: Boolean = true,
    onOpenSource: ((String) -> Unit)? = null,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val halfPx = with(density) { 76.dp.toPx() }
    val fullPx = with(density) { 200.dp.toPx() }
    val offset = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val rightEnabled = swipeEnabled && (rightHalf != SwipeAction.NONE || rightFull != SwipeAction.NONE)
    val leftEnabled = swipeEnabled && (leftHalf != SwipeAction.NONE || leftFull != SwipeAction.NONE)
    val maxRight = if (rightEnabled) fullPx * 1.1f else 0f
    val minLeft = if (leftEnabled) -fullPx * 1.1f else 0f

    fun pick(primary: SwipeAction, fallback: SwipeAction) = if (primary != SwipeAction.NONE) primary else fallback

    Box(modifier.fillMaxWidth()) {
        // matchParentSize makes the coloured reveal span the full row (its size follows the
        // foreground cell), instead of collapsing to the icon's height in a LazyColumn item.
        SwipeBackground(Modifier.matchParentSize(), offset.value, halfPx, fullPx, row, rightHalf, rightFull, leftHalf, leftFull)
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(offset.value.toInt(), 0) }
                .androidxDraggable(offset, minLeft, maxRight) {
                    val o = offset.value
                    val action = when {
                        o >= fullPx -> pick(rightFull, rightHalf)
                        o >= halfPx -> pick(rightHalf, rightFull)
                        o <= -fullPx -> pick(leftFull, leftHalf)
                        o <= -halfPx -> pick(leftHalf, leftFull)
                        else -> SwipeAction.NONE
                    }
                    offset.animateTo(0f, androidx.compose.animation.core.tween(220))
                    if (action != SwipeAction.NONE) onAction(action)
                },
        ) {
            FeedItemCell(row = row, mode = mode, onOpen = onOpen, onLongPress = onLongPress, compact = compact, selected = selected, onOpenSource = onOpenSource)
        }
    }
}

/** Horizontal drag wired to an [Animatable], clamped to the enabled directions. */
private fun Modifier.androidxDraggable(
    offset: androidx.compose.animation.core.Animatable<Float, *>,
    minLeft: Float,
    maxRight: Float,
    onStopped: suspend (Float) -> Unit,
): Modifier = composed {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    draggable(
        state = rememberDraggableState { delta ->
            scope.launch { offset.snapTo((offset.value + delta).coerceIn(minLeft, maxRight)) }
        },
        orientation = Orientation.Horizontal,
        onDragStopped = { velocity -> onStopped(velocity) },
    )
}

@Composable
private fun swipeIcon(action: SwipeAction, row: ItemListRow): ImageVector = when (action) {
    SwipeAction.MARK_READ -> if (row.isRead) Icons.Outlined.MarkEmailUnread else Icons.Outlined.MarkEmailRead
    SwipeAction.SAVE -> if (row.isReadLater) Icons.Outlined.BookmarkRemove else Icons.Outlined.Bookmark
    SwipeAction.STAR -> if (row.isStarred) Icons.Filled.Star else Icons.Outlined.Star
    SwipeAction.ARCHIVE -> Icons.Outlined.Archive
    SwipeAction.DELETE -> Icons.Outlined.DeleteOutline
    SwipeAction.SAVE_OFFLINE -> Icons.Outlined.DownloadForOffline
    SwipeAction.LIBRARY -> Icons.AutoMirrored.Outlined.LibraryBooks
    SwipeAction.OPEN_ORIGINAL -> Icons.AutoMirrored.Outlined.OpenInNew
    SwipeAction.SHARE -> Icons.Outlined.Share
    SwipeAction.NONE -> Icons.Outlined.Archive
}

@Composable
private fun SwipeBackground(
    modifier: Modifier,
    offset: Float,
    halfPx: Float,
    fullPx: Float,
    row: ItemListRow,
    rightHalf: SwipeAction,
    rightFull: SwipeAction,
    leftHalf: SwipeAction,
    leftFull: SwipeAction,
) {
    if (offset == 0f) return
    val scheme = MaterialTheme.colorScheme
    val toRight = offset > 0
    val mag = kotlin.math.abs(offset)
    val past = mag >= fullPx
    val action = when {
        toRight && past -> if (rightFull != SwipeAction.NONE) rightFull else rightHalf
        toRight -> if (rightHalf != SwipeAction.NONE) rightHalf else rightFull
        past -> if (leftFull != SwipeAction.NONE) leftFull else leftHalf
        else -> if (leftHalf != SwipeAction.NONE) leftHalf else leftFull
    }
    // The colour deepens once the full threshold is crossed, so the two stages read distinctly.
    // A destructive delete always reads in the error palette so it can't be confused with a save.
    val destructive = action == SwipeAction.DELETE
    val base = when {
        destructive -> scheme.errorContainer
        toRight -> scheme.tertiaryContainer
        else -> scheme.secondaryContainer
    }
    val deep = when {
        destructive -> scheme.error
        toRight -> scheme.tertiary
        else -> scheme.secondary
    }
    val bg = if (past) deep else base
    val fg = when {
        destructive && past -> scheme.onError
        destructive -> scheme.onErrorContainer
        past -> if (toRight) scheme.onTertiary else scheme.onSecondary
        else -> scheme.onSurfaceVariant
    }
    Box(
        modifier.background(bg).padding(horizontal = 24.dp),
        contentAlignment = if (toRight) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        if (mag > 12f) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(swipeIcon(action, row), contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
                if (mag >= halfPx) Text(action.label, style = MaterialTheme.typography.labelLarge, color = fg, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Bottom sheet of every triage action for a single item, opened by long-press. */
@Composable
fun ItemActionSheet(
    row: ItemListRow,
    onMarkRead: (Boolean) -> Unit,
    onToggleStar: (Boolean) -> Unit,
    onToggleSave: (Boolean) -> Unit,
    onArchive: () -> Unit,
    onOpenOriginal: () -> Unit,
    onDismiss: () -> Unit,
    onSaveOffline: (() -> Unit)? = null,
    onMarkAbove: (() -> Unit)? = null,
    onMarkBelow: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onSelect: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    // Build every applicable action as data, then lay them out as compact cards, four per row,
    // so the whole sheet stays short instead of a long column of full-width rows.
    val actions = buildList {
        if (onSelect != null) add(SheetAction(Icons.Outlined.Checklist, "Select") { onSelect(); onDismiss() })
        add(SheetAction(
            if (row.isRead) Icons.Outlined.MarkEmailUnread else Icons.Outlined.MarkEmailRead,
            if (row.isRead) "Unread" else "Read",
        ) { onMarkRead(!row.isRead); onDismiss() })
        add(SheetAction(
            if (row.isStarred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
            if (row.isStarred) "Unstar" else "Star",
        ) { onToggleStar(!row.isStarred); onDismiss() })
        add(SheetAction(
            if (row.isReadLater) Icons.Filled.Bookmark else Icons.Outlined.Bookmark,
            if (row.isReadLater) "Unsave" else "Save",
        ) { onToggleSave(!row.isReadLater); onDismiss() })
        add(SheetAction(Icons.Filled.Archive, "Archive") { onArchive(); onDismiss() })
        if (onSaveOffline != null && row.type != "PDF") {
            val permanent = row.cacheStatus == "PERMANENT"
            add(SheetAction(
                if (permanent) Icons.Outlined.OfflinePin else Icons.Outlined.DownloadForOffline,
                if (permanent) "Offline ✓" else "Offline",
            ) { if (!permanent) onSaveOffline(); onDismiss() })
        }
        if (onMarkAbove != null) add(SheetAction(Icons.Outlined.KeyboardArrowUp, "Read up") { onMarkAbove(); onDismiss() })
        if (onMarkBelow != null) add(SheetAction(Icons.Outlined.KeyboardArrowDown, "Read down") { onMarkBelow(); onDismiss() })
        add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "Original") { onOpenOriginal(); onDismiss() })
        add(SheetAction(Icons.Outlined.Share, "Share") {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, row.url); putExtra(Intent.EXTRA_SUBJECT, row.title)
            }
            runCatching { context.startActivity(Intent.createChooser(share, null)) }
            onDismiss()
        })
        if (onDelete != null) add(SheetAction(Icons.Outlined.DeleteOutline, "Trash", destructive = true) { onDelete(); onDismiss() })
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            (row.sourceTitle ?: row.siteName)?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
            // Four-column grid of action cards.
            actions.chunked(4).forEach { rowActions ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowActions.forEach { a ->
                        ActionCard(a, Modifier.weight(1f))
                    }
                    // Pad the final row so cards keep a consistent width.
                    repeat(4 - rowActions.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private class SheetAction(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun ActionCard(action: SheetAction, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val content = if (action.destructive) scheme.error else scheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerHighest)
            .clickable(onClick = action.onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(action.icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = content)
        Text(
            action.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (action.destructive) scheme.error else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
