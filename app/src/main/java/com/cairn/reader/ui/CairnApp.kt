package com.cairn.reader.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.ui.inbox.InboxViewModel
import com.cairn.reader.ui.util.formatAgo

private enum class Destination(val label: String, val icon: ImageVector) {
    Inbox("Inbox", Icons.Outlined.Inbox),
    Library("Library", Icons.AutoMirrored.Outlined.LibraryBooks),
    Settings("Settings", Icons.Outlined.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CairnApp() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val destinations = remember { Destination.entries }
    val current = destinations[selected]

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = current.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                destinations.forEachIndexed { index, dest ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                        alwaysShowLabel = false,
                    )
                }
            }
        },
        floatingActionButton = {
            if (current == Destination.Inbox) {
                ExtendedFloatingActionButton(
                    onClick = { /* Add feed — wired with the discovery flow next */ },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add feed") },
                )
            }
        },
    ) { padding ->
        Crossfade(targetState = current, label = "destination") { dest ->
            when (dest) {
                Destination.Inbox -> InboxScreen(padding)
                Destination.Library -> PlaceholderScreen(padding, "Your archive", "Saved articles, tags, collections, and full-text search — coming next in this build.")
                Destination.Settings -> PlaceholderScreen(padding, "Settings", "Sources, appearance, backup, and privacy controls.")
            }
        }
    }
}

@Composable
private fun InboxScreen(
    padding: PaddingValues,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (!state.loading && state.items.isEmpty()) {
        EmptyInbox(padding)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 96.dp,
        ),
    ) {
        item {
            SectionEyebrow(
                text = "UNREAD · ${state.unread}",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        items(state.items, key = { it.id }) { row ->
            ArticleRow(
                row = row,
                onOpen = { viewModel.markRead(row.id, true) },
                onToggleSave = { viewModel.toggleSave(row.id, !row.isReadLater) },
            )
        }
    }
}

@Composable
private fun ArticleRow(
    row: ItemListRow,
    onOpen: () -> Unit,
    onToggleSave: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val source = row.sourceTitle ?: row.siteName ?: "Unknown"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
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
                Text(
                    text = source.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSecondaryContainer,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!row.isRead) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(scheme.primary),
                    )
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
                    Text(
                        text = "  ·  $ago",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
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
                    Text(
                        text = "${row.readingMinutes} min read",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (row.isReadLater) Icons.Filled.Bookmark else Icons.Outlined.Bookmark,
                    contentDescription = if (row.isReadLater) "Saved" else "Save",
                    tint = if (row.isReadLater) scheme.tertiary else scheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(onClick = onToggleSave),
                )
            }
        }
    }
}

@Composable
private fun SectionEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun EmptyInbox(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Inbox,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "You're all caught up",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "New articles from your feeds land here. Add a feed or share a link to Cairn to get started.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlaceholderScreen(padding: PaddingValues, title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
