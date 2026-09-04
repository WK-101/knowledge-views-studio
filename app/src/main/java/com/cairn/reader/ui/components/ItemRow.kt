@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.cairn.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.ui.util.formatAgo

/** True when the row carries any status worth a glyph (saved / PDF / offline copy). */
internal fun ItemListRow.hasStatusGlyph(): Boolean =
    isReadLater || type == "PDF" || cacheStatus == "PERMANENT"

/**
 * Compact status glyphs shared by every list cell: a Saved bookmark, a PDF marker, and a
 * "saved offline" pin. Render inside a Row; nothing shows when the item has no status.
 */
@Composable
internal fun StatusGlyphs(row: ItemListRow, size: Dp = 14.dp) {
    val scheme = MaterialTheme.colorScheme
    if (row.isReadLater) {
        Icon(Icons.Filled.Bookmark, contentDescription = "Saved", tint = scheme.tertiary, modifier = Modifier.size(size))
    }
    if (row.type == "PDF") {
        if (row.isReadLater) Spacer(Modifier.width(6.dp))
        Icon(Icons.Outlined.PictureAsPdf, contentDescription = "PDF", tint = scheme.onSurfaceVariant, modifier = Modifier.size(size))
    }
    if (row.cacheStatus == "PERMANENT") {
        if (row.isReadLater || row.type == "PDF") Spacer(Modifier.width(6.dp))
        Icon(Icons.Outlined.OfflinePin, contentDescription = "Saved offline", tint = scheme.primary, modifier = Modifier.size(size))
    }
}

/** The canonical list row for an item, shared across Inbox, Library, and search. */
@Composable
fun ItemRow(
    row: ItemListRow,
    onOpen: () -> Unit,
    onToggleSave: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    selected: Boolean = false,
    compact: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val source = row.sourceTitle ?: row.siteName ?: "Unknown"
    val thumb = if (compact) 46.dp else 56.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) scheme.secondaryContainer else scheme.surface)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(thumb)
                .clip(RoundedCornerShape(if (compact) 10.dp else 12.dp))
                .background(scheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (row.leadImage != null) {
                AsyncImage(
                    model = row.leadImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(source.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = scheme.onSecondaryContainer)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!row.isRead) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.primary))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = source,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val ago = formatAgo(row.publishedAt ?: row.savedAt)
                if (ago.isNotEmpty()) {
                    Text("  ·  $ago", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!row.excerpt.isNullOrBlank() && !(compact && row.leadImage != null)) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = row.excerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.readingMinutes > 0 || row.hasStatusGlyph()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.readingMinutes > 0) {
                        Text("${row.readingMinutes} min read", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        if (row.hasStatusGlyph()) Spacer(Modifier.width(8.dp))
                    }
                    StatusGlyphs(row)
                }
            }
        }
    }
}
