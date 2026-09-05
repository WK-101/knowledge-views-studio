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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.prefs.ListViewMode
import com.cairn.reader.ui.util.formatAgo

/** Renders one feed item in the chosen list view mode. */
@Composable
fun FeedItemCell(
    row: ItemListRow,
    mode: ListViewMode,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onOpenSource: ((String) -> Unit)? = null,
) {
    when (mode) {
        ListViewMode.CARD -> ItemRow(row, onOpen, {}, modifier, onLongPress, compact = compact, onOpenSource = onOpenSource)
        ListViewMode.LIST -> CompactCell(row, onOpen, onLongPress, modifier, onOpenSource)
        ListViewMode.MAGAZINE -> MagazineCell(row, onOpen, onLongPress, modifier, onOpenSource)
    }
}

/** The source label as a tappable chip that opens that source's page, when a handler is given. */
@Composable
private fun SourceMeta(
    row: ItemListRow,
    color: androidx.compose.ui.graphics.Color,
    weight: FontWeight,
    onOpenSource: ((String) -> Unit)?,
    style: androidx.compose.ui.text.TextStyle,
) {
    val source = row.sourceTitle ?: row.siteName ?: "Unknown"
    val ago = formatAgo(row.publishedAt ?: row.savedAt)
    val sid = row.sourceId
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = source,
            style = style,
            color = color,
            fontWeight = weight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (sid != null && onOpenSource != null) {
                Modifier.clip(RoundedCornerShape(4.dp)).clickable { onOpenSource(sid) }
            } else Modifier,
        )
        if (ago.isNotEmpty()) {
            Text("  ·  $ago", style = style, color = color, fontWeight = weight, maxLines = 1)
        }
    }
}

@Composable
private fun CompactCell(
    row: ItemListRow,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier,
    onOpenSource: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surface)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Text runs flush to the left margin; the unread state is carried by the title's weight
        // and colour plus a small dot on the meta line — no blank left gutter on read rows.
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (row.isRead) scheme.onSurfaceVariant else scheme.onSurface,
                fontWeight = if (row.isRead) FontWeight.Medium else FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!row.isRead) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(scheme.primary))
                    Spacer(Modifier.width(6.dp))
                }
                SourceMeta(
                    row = row,
                    color = if (row.isRead) scheme.onSurfaceVariant else scheme.primary,
                    weight = if (row.isRead) FontWeight.Normal else FontWeight.Medium,
                    onOpenSource = onOpenSource,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (row.hasStatusGlyph()) {
            Row(verticalAlignment = Alignment.CenterVertically) { StatusGlyphs(row, size = 15.dp) }
        }
        if (row.leadImage != null) {
            AsyncImage(
                model = row.leadImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(scheme.secondaryContainer),
            )
        }
    }
}

@Composable
private fun MagazineCell(
    row: ItemListRow,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier,
    onOpenSource: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surface)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        if (row.leadImage != null) {
            AsyncImage(
                model = row.leadImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.secondaryContainer),
            )
            Spacer(Modifier.height(10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!row.isRead) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.primary))
                Spacer(Modifier.width(8.dp))
            }
            SourceMeta(
                row = row,
                color = scheme.primary,
                weight = FontWeight.SemiBold,
                onOpenSource = onOpenSource,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = row.title,
            style = MaterialTheme.typography.headlineSmall,
            color = if (row.isRead) scheme.onSurfaceVariant else scheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (!row.excerpt.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = row.excerpt,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.readingMinutes > 0 || row.hasStatusGlyph()) {
            Spacer(Modifier.height(8.dp))
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
