@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cairn.reader.ui.discover

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Standalone Discover (its own top bar + back), for the drawer route / deep link. */
@Composable
fun DiscoverScreen(
    onBack: () -> Unit,
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    val snackbar = remember { SnackbarHostState() }
    val query by viewModel.query.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.snacks.collect { snackbar.showSnackbar(it) } }
    Scaffold(
        topBar = {
            DiscoverTopBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                query = query,
                onQuery = viewModel::setQuery,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding -> DiscoverBody(padding, viewModel) }
}

/** Embedded Discover, for use as a bottom-nav tab inside the app's own Scaffold. Renders its own
 *  top app bar (with the search icon) — like the Library tab — so the shared bar steps aside. */
@Composable
fun DiscoverContent(
    padding: PaddingValues,
    onOpenDrawer: () -> Unit = {},
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val query by viewModel.query.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.snacks.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    Column(Modifier.fillMaxSize()) {
        DiscoverTopBar(
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, contentDescription = "Open navigation") }
            },
            query = query,
            onQuery = viewModel::setQuery,
        )
        DiscoverBody(padding, viewModel)
    }
}

/** Discover's top app bar: title, or an inline search field when the search icon is tapped. */
@Composable
private fun DiscoverTopBar(
    navigationIcon: @Composable () -> Unit,
    query: String,
    onQuery: (String) -> Unit,
) {
    var searchOpen by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = navigationIcon,
        title = {
            if (searchOpen) {
                com.cairn.reader.ui.components.CairnSearchField(
                    value = query,
                    onValueChange = onQuery,
                    placeholder = "Search feeds, or paste a site / URL",
                    autofocus = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("Discover", fontWeight = FontWeight.SemiBold)
            }
        },
        actions = {
            if (searchOpen) {
                IconButton(onClick = { onQuery(""); searchOpen = false }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close search")
                }
            } else {
                IconButton(onClick = { searchOpen = true }) {
                    Icon(Icons.Outlined.Search, contentDescription = "Search")
                }
            }
        },
    )
}

@Composable
private fun DiscoverBody(padding: PaddingValues, viewModel: DiscoverViewModel) {
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val subscribed by viewModel.subscribed.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val addable by viewModel.queryIsAddable.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    var platformSheet by remember { mutableStateOf<PlatformFeed?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        // The top bar (in the parent Column) already handles the top inset — don't add it again,
        // which was leaving a blank gap between the bar and the first section.
        contentPadding = PaddingValues(top = 4.dp, bottom = padding.calculateBottomPadding() + 28.dp),
    ) {
        if (addable) item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Add “${query.trim()}”", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.addTypedQuery() }, enabled = !busy) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(4.dp)); Text("Add")
                }
            }
        }
        if (query.isBlank()) item {
            Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                SectionLabel("ADD FROM A SITE")
                Spacer(Modifier.height(10.dp))
                // Compact single row of platform icons (Reddit, YouTube, Substack, …) — tap one to
                // follow. Scrolls horizontally so it stays one tidy row rather than a wrapped grid.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlatformFeed.entries.forEach { p ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FilledTonalIconButton(onClick = { platformSheet = p }) {
                                Icon(platformIcon(p), contentDescription = p.label)
                            }
                            Text(p.label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }
        catalog.forEach { category ->
            item(key = "hdr-${category.name}") {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 2.dp)) {
                    SectionLabel(category.name.uppercase())
                }
            }
            items(category.feeds, key = { it.url }) { feed ->
                val added = feed.url.trimEnd('/') in subscribed
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(feed.title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(feed.site, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (added) {
                        Icon(Icons.Filled.Check, contentDescription = "Subscribed", tint = scheme.tertiary)
                    } else {
                        TextButton(onClick = { viewModel.addCatalogFeed(feed) }, enabled = !busy) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.height(18.dp))
                            Spacer(Modifier.width(4.dp)); Text("Add")
                        }
                    }
                }
            }
        }
    }

    platformSheet?.let { platform ->
        PlatformSheet(
            platform = platform,
            busy = busy,
            onAdd = { viewModel.addFromPlatform(platform, it) },
            onDismiss = { platformSheet = null },
        )
    }
}

@Composable
private fun PlatformSheet(platform: PlatformFeed, busy: Boolean, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text("Follow on ${platform.label}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Enter a ${platform.hint}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(platform.hint) },
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onAdd(text); onDismiss() }, enabled = text.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Working…" else "Add feed")
            }
        }
    }
}

/** A representative Material glyph for each add-from-a-site platform (no brand logos available). */
private fun platformIcon(p: PlatformFeed): androidx.compose.ui.graphics.vector.ImageVector = when (p) {
    PlatformFeed.REDDIT -> Icons.Outlined.Forum
    PlatformFeed.YOUTUBE -> Icons.Outlined.SmartDisplay
    PlatformFeed.SUBSTACK -> Icons.Outlined.MailOutline
    PlatformFeed.MEDIUM -> Icons.AutoMirrored.Outlined.Article
    PlatformFeed.TUMBLR -> Icons.Outlined.Tag
    PlatformFeed.WEBSITE -> Icons.Outlined.Language
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.4.sp,
        fontWeight = FontWeight.Medium,
    )
}
