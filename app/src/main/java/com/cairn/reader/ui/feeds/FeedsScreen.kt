@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.feeds

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.data.db.SourceEntity
import com.cairn.reader.ui.components.FeedSettingsSheet
import com.cairn.reader.ui.util.formatAgo
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun FeedsScreen(
    onBack: () -> Unit,
    onOpenWeb: (String) -> Unit,
    viewModel: FeedsViewModel = hiltViewModel(),
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val unread by viewModel.unread.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val snackbar = remember { SnackbarHostState() }

    var editing by remember { mutableStateOf<SourceEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.snacks.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Feeds · ${sources.size}", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, contentDescription = "Add feed") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (sources.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No feeds yet", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap + to add a website or feed. Cairn finds the feed — and can even follow sites that don't publish one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            val grouped = sources.groupBy { it.folder?.takeIf { f -> f.isNotBlank() } }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            ) {
                grouped.forEach { (folder, feeds) ->
                    if (folder != null) {
                        item(key = "hdr-$folder") {
                            Text(
                                folder.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                                letterSpacing = 1.4.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                            )
                        }
                    }
                    items(feeds, key = { it.id }) { source ->
                        FeedManageRow(source, unread[source.id] ?: 0, onClick = { editing = source })
                    }
                }
            }
        }
    }

    editing?.let { source ->
        FeedSettingsSheet(
            source = source,
            folders = folders,
            onRename = { viewModel.rename(source.id, it) },
            onFolder = { viewModel.setFolder(source.id, it) },
            onFullText = { viewModel.setFullText(source.id, it) },
            onNotify = { viewModel.setNotify(source.id, it) },
            onPodcast = { viewModel.setPodcast(source.id, it) },
            onOpenSite = { source.siteUrl?.let(onOpenWeb) },
            onRemove = { viewModel.delete(source.id) },
            onDismiss = { editing = null },
        )
    }

    if (showAdd) {
        AddFeedSheet(
            busy = busy,
            onAdd = { viewModel.addFeed(it) },
            onGoogleNews = { viewModel.followViaGoogleNews(it) },
            onDismiss = { showAdd = false },
        )
    }
}

@Composable
private fun FeedManageRow(source: SourceEntity, unread: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val host = source.siteUrl?.toHttpUrlOrNull()?.host ?: source.feedUrl.toHttpUrlOrNull()?.host ?: source.feedUrl
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val letter = source.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "•"
        val tint = TINTS[(source.title.hashCode() and 0x7fffffff) % TINTS.size]
        Box(Modifier.size(30.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
            Text(letter, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(source.title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(host, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val failing = source.consecutiveErrors > 0
            val status = when {
                failing -> "Sync failing — tap to check"
                source.lastSyncedAt != null -> formatAgo(source.lastSyncedAt)?.takeIf { it.isNotEmpty() }?.let { "Synced $it" }
                else -> null
            }
            if (failing || status != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (failing) scheme.error else scheme.primary.copy(alpha = 0.6f)))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        status ?: "Sync failing",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (failing) scheme.error else scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (unread > 0) {
            Spacer(Modifier.width(8.dp))
            Text("$unread", style = MaterialTheme.typography.labelMedium, color = scheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AddFeedSheet(
    busy: Boolean,
    onAdd: (String) -> Unit,
    onGoogleNews: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text("Add a feed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Paste a website or feed URL. Cairn finds the feed — YouTube, Reddit, Substack, and more.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onAdd(text); onDismiss() }, enabled = text.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Working…" else "Add feed")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "No RSS feed? (e.g. many magazine sites)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Cairn can still follow it through a Google News search of that site — a public RSS feed of its recent articles. No account, just a fetch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onGoogleNews(text); onDismiss() }, enabled = text.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Follow via Google News")
            }
        }
    }
}

private val TINTS = listOf(
    Color(0xFF3F5E7A), Color(0xFF3E8E5A), Color(0xFFB98A2E),
    Color(0xFFB0553F), Color(0xFF6A5A8E), Color(0xFF2E8B94),
)
