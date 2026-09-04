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
import com.cairn.reader.ui.util.formatAgo

/** The canonical list row for an item, shared across Inbox, Library, and search. */
@Composable
fun ItemRow(
    row: ItemListRow,
    onOpen: () -> Unit,
    onToggleSave: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val source = row.sourceTitle ?: row.siteName ?: "Unknown"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surface)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
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
            Spacer(Modifier.height(3.dp))
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!row.excerpt.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = row.excerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.readingMinutes > 0) {
                    Text("${row.readingMinutes} min read", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (row.isReadLater) Icons.Filled.Bookmark else Icons.Outlined.Bookmark,
                    contentDescription = if (row.isReadLater) "Saved" else "Save",
                    tint = if (row.isReadLater) scheme.tertiary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).clickable(onClick = onToggleSave),
                )
            }
        }
    }
}
