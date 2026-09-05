@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.cairn.reader.ui.search

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.clickable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.ui.components.ItemRow
import com.cairn.reader.ui.util.formatAgo
import androidx.compose.runtime.LaunchedEffect

@Composable
fun SearchScreen(
    padding: PaddingValues,
    onOpenItem: (String) -> Unit,
    onOpenWeb: (String) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.results.collectAsStateWithLifecycle()
    val filterState by viewModel.state.collectAsStateWithLifecycle()
    val since by viewModel.since.collectAsStateWithLifecycle()
    val typeFilter by viewModel.type.collectAsStateWithLifecycle()
    val availableTypes by viewModel.availableTypes.collectAsStateWithLifecycle()
    val web by viewModel.web.collectAsStateWithLifecycle()
    val webBusy by viewModel.webBusy.collectAsStateWithLifecycle()
    val archiveSites by viewModel.archiveSites.collectAsStateWithLifecycle()
    val archive by viewModel.archive.collectAsStateWithLifecycle()
    val archiveBusy by viewModel.archiveBusy.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, contentDescription = "Open navigation") }
                },
                title = {
                    com.cairn.reader.ui.components.CairnSearchField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        placeholder = "Search everything you've collected",
                        autofocus = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface),
            )
        Column(Modifier.fillMaxSize()) {
            // Advanced filters (state, recency, type) — collapsed behind one compact row by default
            // so results get the vertical space; the row shows how many filters are active.
            if (state.hasSearched) {
                var filtersOpen by remember { mutableStateOf(false) }
                val activeCount = (if (filterState != SearchState.ALL) 1 else 0) +
                    (if (since != SearchSince.ANY) 1 else 0) +
                    (if (typeFilter != null) 1 else 0)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { filtersOpen = !filtersOpen }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Tune, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(
                        if (activeCount > 0) "Filters · $activeCount" else "Filters",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (activeCount > 0) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (activeCount > 0 && !filtersOpen) {
                        TextButton(onClick = { viewModel.setState(SearchState.ALL); viewModel.setSince(SearchSince.ANY); viewModel.setType(null) }) { Text("Clear") }
                    }
                    Icon(if (filtersOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = scheme.onSurfaceVariant)
                }
                if (filtersOpen) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SearchState.entries.forEach { s ->
                            FilterChip(selected = filterState == s, onClick = { viewModel.setState(s) }, label = { Text(s.label) })
                        }
                    }
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SearchSince.entries.forEach { s ->
                            FilterChip(selected = since == s, onClick = { viewModel.setSince(s) }, label = { Text(s.label) })
                        }
                    }
                    if (availableTypes.size >= 2) {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(selected = typeFilter == null, onClick = { viewModel.setType(null) }, label = { Text("Any type") })
                            availableTypes.forEach { t ->
                                FilterChip(selected = typeFilter == t, onClick = { viewModel.setType(if (typeFilter == t) null else t) }, label = { Text(typeLabel(t)) })
                            }
                        }
                    }
                }
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
            }

            when {
                !state.hasSearched -> SearchHint("Search your library", "Find any saved article, note, or feed item by title, author, or text — all on your device. Then search the whole web for anything you haven't collected yet.")
                state.searching && state.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
                ) {
                    if (state.results.isEmpty()) {
                        item {
                            Text(
                                "No stored items match “${state.query.trim()}”.",
                                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                    items(state.results, key = { it.id }) { row ->
                        ItemRow(row = row, onOpen = { com.cairn.reader.ui.reader.ReaderQueue.set(state.results.map { it.id }); onOpenItem(row.id) }, onToggleSave = { viewModel.toggleSave(row.id, !row.isReadLater) })
                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
                    }

                    // ---- Search the whole web ----------------------------------------------
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                            if (web.isEmpty()) {
                                OutlinedButton(onClick = { viewModel.searchWeb() }, enabled = !webBusy) {
                                    Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (webBusy) "Searching the web…" else "Search the whole web for “${state.query.trim()}”")
                                }
                                Text(
                                    "Goes beyond what you've stored — searches across the web (via Google News) for everything published on this topic.",
                                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            } else {
                                Text("FROM THE WEB", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    items(web, key = { "web-${it.url}" }) { hit ->
                        WebHitRow(hit, onOpen = { onOpenWeb(hit.url) }, onSave = { viewModel.saveWebHit(hit.url) })
                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
                    }

                    // ---- Search a single site's entire published archive -------------------
                    if (archiveSites.isNotEmpty()) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                                Text(
                                    if (archiveBusy) "Searching the archive…" else "SEARCH A SITE'S FULL ARCHIVE",
                                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "Finds “${state.query.trim()}” across everything a site ever published — its whole back catalogue, not just recent items. Tap a site:",
                                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    archiveSites.take(30).forEach { site ->
                                        AssistChip(
                                            onClick = { viewModel.searchArchive(site) },
                                            enabled = !archiveBusy,
                                            label = { Text(site.title, maxLines = 1) },
                                        )
                                    }
                                }
                            }
                        }
                        items(archive, key = { "arc-${it.url}" }) { hit ->
                            WebHitRow(hit, onOpen = { onOpenWeb(hit.url) }, onSave = { viewModel.saveWebHit(hit.url) })
                            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WebHitRow(hit: WebHit, onOpen: () -> Unit, onSave: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onSave).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(hit.title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            val ago = hit.publishedAt?.let { formatAgo(it) }.orEmpty()
            Text(if (ago.isNotEmpty()) "${hit.site}  ·  $ago" else hit.site, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onOpen) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open", tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
    }
}

private fun typeLabel(type: String): String = when (type) {
    "ARTICLE" -> "Articles"; "LINK" -> "Links"; "VIDEO" -> "Videos"; "AUDIO" -> "Podcasts"
    "IMAGE" -> "Images"; "PDF" -> "PDFs"; else -> type.lowercase().replaceFirstChar(Char::uppercase)
}

@Composable
private fun SearchHint(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
