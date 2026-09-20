package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.NoteGarden
import com.todocompanion.app.domain.NoteProperties
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import com.todocompanion.app.ui.components.CardLabel
import com.todocompanion.app.ui.components.EmptyState

/**
 * Wave 2 · The Note-Garden review — a weekly ten-minute tending surface that only a local app with the
 * whole picture can build. It surfaces entropy in your second brain — notes due for a spaced re-read,
 * orphans adrift in the graph, stale notes, dropped intentions (a checkbox that never became a task), and
 * near-duplicate pairs — and lets you act on each in place. Everything is computed on-device from
 * projections the ViewModel assembles ([NoteGarden] + the MinHash similarity in NoteSemantic); nothing
 * leaves the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteGardenScreen(vm: AppViewModel, onOpenNote: (String) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val report by vm.noteGarden.collectAsState()
    val loading by vm.noteGardenLoading.collectAsState()
    val due by vm.notesDueForReview.collectAsState()

    LaunchedEffect(Unit) { vm.refreshNoteGarden() }

    val empty = report.orphans.isEmpty() && report.stale.isEmpty() && report.dropped.isEmpty() &&
        report.duplicates.isEmpty() && due.isEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 52.dp,
                title = { Text("Note garden" + if (report.scanned > 0) "  ·  ${report.scanned}" else "") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (loading) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { vm.refreshNoteGarden() }) { Icon(Icons.Filled.Refresh, "Re-scan") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (empty && !loading) {
                EmptyState(emoji = "🌱", title = "A tidy garden", body = "Nothing to tend right now — no orphans, stale notes, dropped intentions or near-duplicates. Come back after you've written more.")
                return@Column
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "A ten-minute weekly tending keeps a second brain alive. Each finding is computed on your device.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }

                // ── Due for review — Evergreen Resurfacing falling due right now ──
                if (due.isNotEmpty()) {
                    item { GardenSectionHeader("♻️ Due for review", due.size) }
                    items(due, key = { "due:${it.id}" }) { note ->
                        GardenRow(
                            title = note.title.ifBlank { "Untitled" },
                            sub = NoteProperties.strip(note.body).take(120),
                            onOpen = { onOpenNote(note.id) },
                            action = "Reviewed" to { vm.markNoteReviewed(note.id) },
                        )
                    }
                }

                // ── Dropped intentions — a checkbox line with no matching task ──
                if (report.dropped.isNotEmpty()) {
                    item { GardenSectionHeader("🎯 Dropped intentions", report.dropped.size) }
                    items(report.dropped, key = { "drop:${it.first.id}:${it.second.hashCode()}" }) { (note, phrase) ->
                        GardenRow(
                            title = phrase,
                            sub = "in “${note.title.ifBlank { "Untitled" }}”",
                            onOpen = { onOpenNote(note.id) },
                            action = "Make task" to { vm.createTaskFromIntention(phrase) },
                        )
                    }
                }

                // ── Near-duplicates — high MinHash similarity ──
                if (report.duplicates.isNotEmpty()) {
                    item { GardenSectionHeader("👯 Near-duplicates", report.duplicates.size) }
                    items(report.duplicates, key = { "dup:${it.a.id}:${it.b.id}" }) { pair ->
                        DupeRow(pair, onOpenNote)
                    }
                }

                // ── Orphans — no tags, no links in or out ──
                if (report.orphans.isNotEmpty()) {
                    item { GardenSectionHeader("🌫️ Orphans", report.orphans.size) }
                    items(report.orphans, key = { "orphan:${it.id}" }) { note ->
                        GardenRow(
                            title = note.title.ifBlank { "Untitled" },
                            sub = "No tags or links — adrift in the graph",
                            onOpen = { onOpenNote(note.id) },
                            action = null,
                        )
                    }
                }

                // ── Stale — untouched for 90+ days ──
                if (report.stale.isNotEmpty()) {
                    item { GardenSectionHeader("🕰️ Stale", report.stale.size) }
                    items(report.stale, key = { "stale:${it.id}" }) { note ->
                        GardenRow(
                            title = note.title.ifBlank { "Untitled" },
                            sub = "Last touched " + relDays(note.updatedAt) + " ago",
                            onOpen = { onOpenNote(note.id) },
                            action = null,
                        )
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun GardenSectionHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GardenRow(title: String, sub: String, onOpen: () -> Unit, action: Pair<String, () -> Unit>?) {
    AppCard(onClick = onOpen, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (action != null) {
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = action.second) { Text(action.first) }
            }
        }
    }
}

@Composable
private fun DupeRow(pair: NoteGarden.DupePair, onOpenNote: (String) -> Unit) {
    AppCard(padding = 12.dp) {
        CardLabel("${(pair.score * 100).toInt()}% alike")
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { onOpenNote(pair.a.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(pair.a.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("↔", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { onOpenNote(pair.b.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(pair.b.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun relDays(updatedAt: Long): String {
    val days = ((System.currentTimeMillis() - updatedAt) / (24L * 60 * 60 * 1000)).toInt().coerceAtLeast(0)
    return when {
        days >= 365 -> "${days / 365}y"
        days >= 30 -> "${days / 30}mo"
        else -> "${days}d"
    }
}
