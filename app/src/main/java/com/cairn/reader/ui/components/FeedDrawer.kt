package com.cairn.reader.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cairn.reader.data.db.FeedUnread

/** Contents of the navigation drawer: All Articles, the feed tree with unread counts,
 *  and the saved / highlights / settings destinations. */
@Composable
fun FeedDrawerContent(
    totalUnread: Int,
    feeds: List<FeedUnread>,
    selectedSource: String?,
    onAllArticles: () -> Unit,
    onSelectFeed: (String) -> Unit,
    onSaved: () -> Unit,
    onHighlights: () -> Unit,
    onSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        Row(Modifier.padding(horizontal = 28.dp, vertical = 12.dp)) {
            Text("Cairn", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
        }

        NavigationDrawerItem(
            label = { Text("All Articles") },
            selected = selectedSource == null,
            icon = { Icon(Icons.AutoMirrored.Outlined.Article, contentDescription = null) },
            badge = { if (totalUnread > 0) Text("$totalUnread") },
            onClick = onAllArticles,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )

        DrawerLabel("FEEDS")
        // Group by folder; feeds without a folder fall under a plain list.
        val groups = feeds.groupBy { it.folder }
        groups.forEach { (folder, groupFeeds) ->
            if (folder != null) DrawerLabel(folder.uppercase(), top = 6.dp)
            groupFeeds.forEach { feed ->
                NavigationDrawerItem(
                    label = { Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = selectedSource == feed.sourceId,
                    icon = { Icon(Icons.Outlined.RssFeed, contentDescription = null) },
                    badge = { if (feed.unread > 0) Text("${feed.unread}") },
                    onClick = { onSelectFeed(feed.sourceId) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        }
        if (feeds.isEmpty()) {
            Text(
                "No feeds yet — add one from the Inbox.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(Modifier.padding(horizontal = 28.dp))
        Spacer(Modifier.height(8.dp))

        NavigationDrawerItem(
            label = { Text("Saved") },
            selected = false,
            icon = { Icon(Icons.Outlined.Bookmark, contentDescription = null) },
            onClick = onSaved,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        NavigationDrawerItem(
            label = { Text("Highlights") },
            selected = false,
            icon = { Icon(Icons.Outlined.FormatQuote, contentDescription = null) },
            onClick = onHighlights,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        NavigationDrawerItem(
            label = { Text("Settings") },
            selected = false,
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            onClick = onSettings,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
    }
}

@Composable
private fun DrawerLabel(text: String, top: androidx.compose.ui.unit.Dp = 12.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.4.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = top, bottom = 4.dp),
    )
}
