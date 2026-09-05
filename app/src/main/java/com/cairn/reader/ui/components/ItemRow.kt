@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.cairn.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.staticCompositionLocalOf
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

/** Per-element list-row visibility, provided app-wide so every surface honours the user's density
 *  choices (Settings → List). Defaults show everything. */
data class ListRowOptions(
    val showThumbnail: Boolean = true,
    val showExcerpt: Boolean = true,
    val showReadingTime: Boolean = true,
)

val LocalListRowOptions = staticCompositionLocalOf { ListRowOptions() }

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

/** The canonical list row for an item, shared across Inbox, Library, and search.
 *
 *  Inoreader-style card: the text runs flush to the left margin and the thumbnail sits on the
 *  right, stretched to the card's full height so image and content always share one height —
 *  no letter-avatar box, so text-only feeds read as a clean column with no dead space. */
@Composable
fun ItemRow(
    row: ItemListRow,
    onOpen: () -> Unit,
    onToggleSave: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    selected: Boolean = false,
    compact: Boolean = false,
    onOpenSource: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val opts = LocalListRowOptions.current
    val source = row.sourceTitle ?: row.siteName ?: "Unknown"
    val thumbW = if (compact) 84.dp else 104.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) scheme.secondaryContainer else scheme.surface)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .height(IntrinsicSize.Min)
            .padding(start = 16.dp, end = 16.dp, top = if (compact) 9.dp else 12.dp, bottom = if (compact) 9.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!row.isRead) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.primary))
                    Spacer(Modifier.width(8.dp))
                }
                val sid = row.sourceId
                Text(
                    text = source,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .then(
                            if (sid != null && onOpenSource != null) {
                                Modifier.clip(RoundedCornerShape(4.dp)).clickable { onOpenSource(sid) }
                            } else Modifier,
                        ),
                )
                val ago = formatAgo(row.publishedAt ?: row.savedAt)
                if (ago.isNotEmpty()) {
                    Text("  ·  $ago", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (row.isRead) scheme.onSurfaceVariant else scheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (opts.showExcerpt && !row.excerpt.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = row.excerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val showTime = opts.showReadingTime && row.readingMinutes > 0
            if (showTime || row.hasStatusGlyph()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showTime) {
                        Text("${row.readingMinutes} min read", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        if (row.hasStatusGlyph()) Spacer(Modifier.width(8.dp))
                    }
                    StatusGlyphs(row)
                }
            }
        }
        if (opts.showThumbnail && row.leadImage != null) {
            AsyncImage(
                model = row.leadImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(thumbW)
                    .fillMaxHeight()
                    .heightIn(min = if (compact) 60.dp else 74.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.secondaryContainer),
            )
        }
    }
}
