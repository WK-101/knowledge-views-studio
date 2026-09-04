@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cairn.reader.data.db.ItemListRow

/**
 * A list row that can be swiped: right to save/unsave, left to archive.
 * Archiving dismisses the row (the reactive query drops it); saving snaps back
 * and lets the bookmark state update in place.
 */
@Composable
fun SwipeableItemRow(
    row: ItemListRow,
    onOpen: () -> Unit,
    onToggleSave: () -> Unit,
    onArchive: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onToggleSave(); false }
                SwipeToDismissBoxValue.EndToStart -> { onArchive(); true }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = { SwipeBackground(dismissState.dismissDirection, row.isReadLater) },
    ) {
        ItemRow(row = row, onOpen = onOpen, onToggleSave = onToggleSave, onLongPress = onLongPress)
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, isSaved: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val (color, icon, alignment) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd ->
            Triple(scheme.tertiaryContainer, if (isSaved) Icons.Outlined.BookmarkRemove else Icons.Outlined.Bookmark, Alignment.CenterStart)
        SwipeToDismissBoxValue.EndToStart ->
            Triple(scheme.secondaryContainer, Icons.Outlined.Archive, Alignment.CenterEnd)
        SwipeToDismissBoxValue.Settled ->
            Triple(Color.Transparent, Icons.Outlined.Archive, Alignment.Center)
    }
    Box(
        Modifier.fillMaxSize().background(color).padding(horizontal = 28.dp),
        contentAlignment = alignment,
    ) {
        if (direction != SwipeToDismissBoxValue.Settled) {
            Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant)
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

            ActionItem(
                icon = if (row.isStarred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                label = if (row.isStarred) "Remove star" else "Star",
            ) { onToggleStar(!row.isStarred); onDismiss() }

            ActionItem(
                icon = if (row.isReadLater) Icons.Filled.Bookmark else Icons.Outlined.Bookmark,
                label = if (row.isReadLater) "Remove from Saved" else "Save for later",
            ) { onToggleSave(!row.isReadLater); onDismiss() }

            ActionItem(icon = Icons.Filled.Archive, label = "Archive") { onArchive(); onDismiss() }

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
        }
    }
}

@Composable
private fun ActionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
