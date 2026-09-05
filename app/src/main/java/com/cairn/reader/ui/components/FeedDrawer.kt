package com.cairn.reader.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cairn.reader.data.db.FeedUnread
import com.cairn.reader.ui.inbox.DrawerSelection
import com.cairn.reader.ui.inbox.InboxFilter

/**
 * The navigation drawer, modelled on Inoreader's: a branded header, a set of fixed hubs
 * (All Articles, Starred, Saved & Library, Highlights, Search), and a collapsible feed-tree
 * where folders roll up their unread counts and expand in place to reveal their feeds.
 */
@Composable
fun FeedDrawerContent(
    totalUnread: Int,
    feeds: List<FeedUnread>,
    selection: DrawerSelection,
    filter: InboxFilter,
    onAllArticles: () -> Unit,
    onStarred: () -> Unit,
    onSelectFeed: (FeedUnread) -> Unit,
    onSelectFolder: (String) -> Unit,
    onMarkFeedRead: (String) -> Unit,
    onMarkFolderRead: (String) -> Unit,
    onManageFeed: (FeedUnread) -> Unit,
    onUnsubscribe: (FeedUnread) -> Unit,
    onSaved: () -> Unit,
    onReadLater: () -> Unit,
    onHighlights: () -> Unit,
    onBrief: () -> Unit = {},
    onSearch: () -> Unit,
    onDiscover: () -> Unit,
    onManageFeeds: () -> Unit,
    onTrash: () -> Unit,
    trashCount: Int = 0,
    onSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val itemPad = NavigationDrawerItemDefaults.ItemPadding

    // Folder expansion state, keyed by folder name; folders start expanded so counts show.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    // The whole FEEDS section folds away as one; it starts folded so the drawer opens compact.
    var feedsExpanded by remember { mutableStateOf(false) }

    // Split feeds into folders (preserving first-seen order) and loose, ungrouped feeds.
    val grouped = feeds.filter { !it.folder.isNullOrBlank() }.groupBy { it.folder!! }
    val loose = feeds.filter { it.folder.isNullOrBlank() }

    val allSelected = selection is DrawerSelection.All && filter != InboxFilter.STARRED
    val starredSelected = filter == InboxFilter.STARRED

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        // ---- Brand header -----------------------------------------------------
        Row(
            Modifier.padding(start = 28.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CairnMark(size = 28.dp)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Cairn", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                Text("Private reading", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            // Settings sits inline with the app name, always one tap from the top of the drawer.
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = scheme.onSurfaceVariant)
            }
        }

        // ---- Primary hubs -----------------------------------------------------
        NavigationDrawerItem(
            label = { Text("All Articles") },
            selected = allSelected,
            icon = { Icon(Icons.AutoMirrored.Outlined.Article, contentDescription = null) },
            badge = { if (totalUnread > 0) Text("$totalUnread") },
            onClick = onAllArticles,
            modifier = Modifier.padding(itemPad),
        )
        NavigationDrawerItem(
            label = { Text("Starred") },
            selected = starredSelected,
            icon = { Icon(Icons.Outlined.StarOutline, contentDescription = null) },
            onClick = onStarred,
            modifier = Modifier.padding(itemPad),
        )
        NavigationDrawerItem(
            label = { Text("Read Later") },
            selected = false,
            icon = { Icon(Icons.Outlined.BookmarkBorder, contentDescription = null) },
            onClick = onReadLater,
            modifier = Modifier.padding(itemPad),
        )
        NavigationDrawerItem(
            label = { Text("Library") },
            selected = false,
            icon = { Icon(Icons.AutoMirrored.Outlined.LibraryBooks, contentDescription = null) },
            onClick = onSaved,
            modifier = Modifier.padding(itemPad),
        )
        NavigationDrawerItem(
            label = { Text("Highlights") },
            selected = false,
            icon = { Icon(Icons.Outlined.FormatQuote, contentDescription = null) },
            onClick = onHighlights,
            modifier = Modifier.padding(itemPad),
        )
        NavigationDrawerItem(
            label = { Text("Daily Brief") },
            selected = false,
            icon = { Icon(Icons.Outlined.Newspaper, contentDescription = null) },
            onClick = onBrief,
            modifier = Modifier.padding(itemPad),
        )

        // ---- Feed tree --------------------------------------------------------
        if (feeds.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "No feeds yet — tap Add feed to subscribe.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            )
        } else {
            SectionHeader("FEEDS", feeds.sumOf { it.unread }, feedsExpanded) { feedsExpanded = !feedsExpanded }
            AnimatedVisibility(visible = feedsExpanded) {
                Column {
                    grouped.forEach { (folder, folderFeeds) ->
                        val isOpen = expanded[folder] ?: true
                        val folderUnread = folderFeeds.sumOf { it.unread }
                        val folderSelected = (selection as? DrawerSelection.Folder)?.name == folder
                        FolderRow(
                            name = folder,
                            unread = folderUnread,
                            expanded = isOpen,
                            selected = folderSelected,
                            onClick = { onSelectFolder(folder) },
                            onToggle = { expanded[folder] = !isOpen },
                            onMarkRead = { onMarkFolderRead(folder) },
                        )
                        AnimatedVisibility(visible = isOpen) {
                            Column {
                                folderFeeds.forEach { feed ->
                                    FeedRow(
                                        feed = feed,
                                        selected = (selection as? DrawerSelection.Feed)?.sourceId == feed.sourceId,
                                        indent = true,
                                        onClick = { onSelectFeed(feed) },
                                        onMarkRead = { onMarkFeedRead(feed.sourceId) },
                                        onManage = { onManageFeed(feed) },
                                        onUnsubscribe = { onUnsubscribe(feed) },
                                    )
                                }
                            }
                        }
                    }
                    loose.forEach { feed ->
                        FeedRow(
                            feed = feed,
                            selected = (selection as? DrawerSelection.Feed)?.sourceId == feed.sourceId,
                            indent = false,
                            onClick = { onSelectFeed(feed) },
                            onMarkRead = { onMarkFeedRead(feed.sourceId) },
                            onManage = { onManageFeed(feed) },
                            onUnsubscribe = { onUnsubscribe(feed) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(Modifier.padding(horizontal = 28.dp))
        Spacer(Modifier.height(8.dp))

        // ---- Footer hubs ------------------------------------------------------
        NavigationDrawerItem(
            label = { Text("Search") },
            selected = false,
            icon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            onClick = onSearch,
            modifier = Modifier.padding(itemPad),
        )
        NavigationDrawerItem(
            label = { Text("Discover") },
            selected = false,
            icon = { Icon(Icons.Outlined.Explore, contentDescription = null) },
            onClick = onDiscover,
            modifier = Modifier.padding(itemPad),
        )
        NavigationDrawerItem(
            label = { Text("Manage feeds") },
            selected = false,
            icon = { Icon(Icons.Outlined.RssFeed, contentDescription = null) },
            onClick = onManageFeeds,
            modifier = Modifier.padding(itemPad),
        )
        NavigationDrawerItem(
            label = { Text("Trash") },
            selected = false,
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            badge = { if (trashCount > 0) Text("$trashCount") },
            onClick = onTrash,
            modifier = Modifier.padding(itemPad),
        )
    }
}

/** A folder header: tap to view the whole folder, tap the chevron to expand/collapse,
 *  long-press for the mark-all-read menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    name: String,
    unread: Int,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onMarkRead: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "chevron")
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowActionMenu(menu, unread, onDismiss = { menu = false }, onMarkRead = { menu = false; onMarkRead() })
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            tint = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 11.dp),
        )
        if (unread > 0) {
            Text(
                "$unread",
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse $name" else "Expand $name",
                tint = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                modifier = Modifier.rotate(rotation),
            )
        }
    }
}

/** A single feed row with a monogram dot, title and unread count.
 *  Long-press opens a mark-all-read menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedRow(
    feed: FeedUnread,
    selected: Boolean,
    indent: Boolean,
    onClick: () -> Unit,
    onMarkRead: () -> Unit,
    onManage: () -> Unit,
    onUnsubscribe: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(start = if (indent) 30.dp else 16.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FeedActionMenu(
            expanded = menu,
            unread = feed.unread,
            onDismiss = { menu = false },
            onMarkRead = { menu = false; onMarkRead() },
            onManage = { menu = false; onManage() },
            onUnsubscribe = { menu = false; onUnsubscribe() },
        )
        FeedMonogram(feed.title, dim = feed.unread == 0 && !selected)
        Spacer(Modifier.size(12.dp))
        Text(
            feed.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (feed.unread > 0) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) scheme.onSecondaryContainer
            else if (feed.unread > 0) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 9.dp),
        )
        if (feed.unread > 0) {
            Text(
                "${feed.unread}",
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            )
        }
    }
}

/** The long-press menu for folder rows — just mark-all-read. */
@Composable
private fun RowActionMenu(expanded: Boolean, unread: Int, onDismiss: () -> Unit, onMarkRead: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (unread > 0) "Mark all read ($unread)" else "Mark all read") },
            enabled = unread > 0,
            onClick = onMarkRead,
        )
    }
}

/** The richer long-press menu for a single feed: mark read, manage (settings), unsubscribe. */
@Composable
private fun FeedActionMenu(
    expanded: Boolean,
    unread: Int,
    onDismiss: () -> Unit,
    onMarkRead: () -> Unit,
    onManage: () -> Unit,
    onUnsubscribe: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (unread > 0) "Mark all read ($unread)" else "Mark all read") },
            enabled = unread > 0,
            onClick = onMarkRead,
        )
        DropdownMenuItem(text = { Text("Feed settings & folder…") }, onClick = onManage)
        DropdownMenuItem(text = { Text("Unsubscribe") }, onClick = onUnsubscribe)
    }
}

/** A small tinted circle carrying the feed's initial — a lightweight stand-in for a favicon. */
@Composable
private fun FeedMonogram(title: String, dim: Boolean) {
    val letter = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "•"
    val hue = MONOGRAM_COLORS[(title.hashCode() and 0x7fffffff) % MONOGRAM_COLORS.size]
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(hue.copy(alpha = if (dim) 0.35f else 0.9f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** The stacked-stones Cairn mark, drawn to match the brand. */
@Composable
private fun CairnMark(size: androidx.compose.ui.unit.Dp) {
    val tint = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.size(size),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.5.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(width = size * 0.32f, height = size * 0.16f).clip(CircleShape).background(tint.copy(alpha = 0.5f)))
        Box(Modifier.size(width = size * 0.55f, height = size * 0.18f).clip(CircleShape).background(tint.copy(alpha = 0.7f)))
        Box(Modifier.size(width = size * 0.78f, height = size * 0.2f).clip(CircleShape).background(tint.copy(alpha = 0.9f)))
    }
}

private val MONOGRAM_COLORS = listOf(
    Color(0xFF3F5E7A), Color(0xFF3E8E5A), Color(0xFFB98A2E), Color(0xFFB0553F),
    Color(0xFF6A5A8E), Color(0xFF2E8B94), Color(0xFF8E5A6A), Color(0xFF5A7A4E),
)

/** A foldable section header (e.g. FEEDS): label + rolled-up count + a chevron; tap to fold. */
@Composable
private fun SectionHeader(text: String, unread: Int, expanded: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "sectionChevron")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 28.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (!expanded && unread > 0) {
            Text("$unread", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
        }
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse feeds" else "Expand feeds",
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).rotate(rotation),
        )
    }
}

