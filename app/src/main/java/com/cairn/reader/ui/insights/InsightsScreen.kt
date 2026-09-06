@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.insights

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.data.repo.HygieneIssue
import com.cairn.reader.data.repo.ReadingAnalytics

@Composable
fun InsightsScreen(
    padding: PaddingValues,
    onOpenItem: (String) -> Unit,
    onOpenDrawer: () -> Unit = {},
    viewModel: InsightsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val healing by viewModel.healing.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.insights), fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.open_navigation)) } },
                actions = { IconButton(onClick = { viewModel.refresh() }) { Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.refresh)) } },
            )
        },
    ) { inner ->
        if (state.loading && state.analytics == null) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            state.analytics?.let { a ->
                item { StatGrid(a) }
                if (a.topSources.isNotEmpty()) {
                    item {
                        Column {
                            SectionLabel("YOU READ MOST FROM")
                            Spacer(Modifier.height(8.dp))
                            a.topSources.forEach { (title, count) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(title, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("$count", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            if (state.topPicks.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        SectionLabel("TOP PICKS FOR YOU")
                    }
                }
                items(state.topPicks.size) { i ->
                    val row = state.topPicks[i]
                    Column(Modifier.fillMaxWidth().clickable { onOpenItem(row.id) }.padding(vertical = 8.dp)) {
                        Text(row.title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                append(row.sourceTitle ?: row.siteName ?: "")
                                if (row.readingMinutes > 0) { if (isNotEmpty()) append(" · "); append("${row.readingMinutes} min") }
                            },
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (state.topics.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Hub, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        SectionLabel("TOPICS YOU'RE FOLLOWING")
                    }
                }
                items(state.topics.size) { i ->
                    val topic = state.topics[i]
                    Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text("${topic.label}  ·  ${topic.items.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            topic.items.take(3).forEach { it2 ->
                                Text("• ${it2.title}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                }
            }

            if (state.hygiene.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CleaningServices, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        SectionLabel("FEED HYGIENE")
                    }
                }
                items(state.hygiene.size) { i ->
                    val issue = state.hygiene[i]
                    HygieneCard(
                        issue = issue,
                        onHeal = if (issue.kind == HygieneIssue.Kind.BROKEN_LINKS) {
                            {
                                android.widget.Toast.makeText(ctx, "Searching the Wayback Machine…", android.widget.Toast.LENGTH_SHORT).show()
                                viewModel.healBrokenLinks { healed ->
                                    android.widget.Toast.makeText(ctx, if (healed > 0) "Recovered $healed article(s) from archives" else "No archived copies found", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        } else null,
                        healing = healing,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatGrid(a: ReadingAnalytics) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Articles read", a.read.toString(), Modifier.weight(1f))
        StatTile("This week", a.readThisWeek.toString(), Modifier.weight(1f))
        StatTile("Day streak", a.streakDays.toString(), Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Time reading", formatMinutes(a.readMinutes), Modifier.weight(1f))
        StatTile("Starred", a.starred.toString(), Modifier.weight(1f))
        StatTile("Saved", a.saved.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Card(modifier, colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = scheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HygieneCard(issue: HygieneIssue, onHeal: (() -> Unit)? = null, healing: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(issue.title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(issue.detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            if (onHeal != null) {
                androidx.compose.material3.TextButton(onClick = onHeal, enabled = !healing) {
                    Text(if (healing) "Healing…" else "Heal")
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
}

private fun formatMinutes(min: Int): String = when {
    min < 60 -> "${min}m"
    else -> "${min / 60}h ${min % 60}m"
}
