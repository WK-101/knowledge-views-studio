@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.cairn.reader.ui.components

import androidx.compose.foundation.background
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
    onToggleSave: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    when (mode) {
        ListViewMode.CARD -> ItemRow(row, onOpen, onToggleSave, modifier, onLongPress)
        ListViewMode.LIST -> CompactCell(row, onOpen, onToggleSave, onLongPress, modifier)
        ListViewMode.MAGAZINE -> MagazineCell(row, onOpen, onToggleSave, onLongPress, modifier)
    }
}

@Composable
private fun CompactCell(
    row: ItemListRow,
    onOpen: () -> Unit,
    onToggleSave: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val source = row.sourceTitle ?: row.siteName ?: "Unknown"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surface)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!row.isRead) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.primary))
        } else {
            Spacer(Modifier.width(7.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (row.isRead) scheme.onSurfaceVariant else scheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val ago = formatAgo(row.publishedAt ?: row.savedAt)
            Text(
                text = if (ago.isNotEmpty()) "$source  ·  $ago" else source,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.leadImage != null) {
            AsyncImage(
                model = row.leadImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(9.dp)).background(scheme.secondaryContainer),
            )
        }
        Icon(
            imageVector = if (row.isReadLater) Icons.Filled.Bookmark else Icons.Outlined.Bookmark,
            contentDescription = if (row.isReadLater) "Saved" else "Save",
            tint = if (row.isReadLater) scheme.tertiary else scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp).combinedClickable(onClick = onToggleSave),
        )
    }
}

@Composable
private fun MagazineCell(
    row: ItemListRow,
    onOpen: () -> Unit,
    onToggleSave: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val source = row.sourceTitle ?: row.siteName ?: "Unknown"
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
            val ago = formatAgo(row.publishedAt ?: row.savedAt)
            Text(
                text = if (ago.isNotEmpty()) "$source  ·  $ago" else source,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.readingMinutes > 0) {
                Text("${row.readingMinutes} min read", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = if (row.isReadLater) Icons.Filled.Bookmark else Icons.Outlined.Bookmark,
                contentDescription = if (row.isReadLater) "Saved" else "Save",
                tint = if (row.isReadLater) scheme.tertiary else scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).combinedClickable(onClick = onToggleSave),
            )
        }
    }
}
