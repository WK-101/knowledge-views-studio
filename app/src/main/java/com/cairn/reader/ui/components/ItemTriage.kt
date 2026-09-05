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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkRemove
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
    onOpenSource: ((String) -> Unit)? = null,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val halfPx = with(density) { 76.dp.toPx() }
    val fullPx = with(density) { 200.dp.toPx() }
    val offset = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val rightEnabled = rightHalf != SwipeAction.NONE || rightFull != SwipeAction.NONE
    val leftEnabled = leftHalf != SwipeAction.NONE || leftFull != SwipeAction.NONE
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
            FeedItemCell(row = row, mode = mode, onOpen = onOpen, onLongPress = onLongPress, compact = compact, onOpenSource = onOpenSource)
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
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            (row.sourceTitle ?: row.siteName)?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            ActionItem(
                icon = if (row.isRead) Icons.Outlined.MarkEmailUnread else Icons.Outlined.MarkEmailRead,
                label = if (row.isRead) "Mark as unread" else "Mark as read",
            ) { onMarkRead(!row.isRead); onDismiss() }

            if (onMarkAbove != null) {
                ActionItem(icon = Icons.Outlined.KeyboardArrowUp, label = "Mark newer as read") { onMarkAbove(); onDismiss() }
            }
            if (onMarkBelow != null) {
                ActionItem(icon = Icons.Outlined.KeyboardArrowDown, label = "Mark older as read") { onMarkBelow(); onDismiss() }
            }

            ActionItem(
                icon = if (row.isStarred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                label = if (row.isStarred) "Remove star" else "Star",
            ) { onToggleStar(!row.isStarred); onDismiss() }

            ActionItem(
                icon = if (row.isReadLater) Icons.Filled.Bookmark else Icons.Outlined.Bookmark,
                label = if (row.isReadLater) "Remove from Saved" else "Save for later",
            ) { onToggleSave(!row.isReadLater); onDismiss() }

            ActionItem(icon = Icons.Filled.Archive, label = "Archive") { onArchive(); onDismiss() }

            if (onSaveOffline != null && row.type != "PDF") {
                val permanent = row.cacheStatus == "PERMANENT"
                ActionItem(
                    icon = if (permanent) Icons.Outlined.OfflinePin else Icons.Outlined.DownloadForOffline,
                    label = if (permanent) "Saved offline" else "Save offline",
                ) { if (!permanent) onSaveOffline(); onDismiss() }
            }

            ActionItem(icon = Icons.AutoMirrored.Outlined.OpenInNew, label = "Open original") { onOpenOriginal(); onDismiss() }

            ActionItem(icon = Icons.Outlined.Share, label = "Share link") {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, row.url)
                    putExtra(Intent.EXTRA_SUBJECT, row.title)
                }
                runCatching { context.startActivity(Intent.createChooser(share, null)) }
                onDismiss()
            }

            if (onDelete != null) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ActionItem(
                    icon = Icons.Outlined.DeleteOutline,
                    label = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    textColor = MaterialTheme.colorScheme.error,
                ) { onDelete(); onDismiss() }
            }
        }
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = textColor)
    }
}
