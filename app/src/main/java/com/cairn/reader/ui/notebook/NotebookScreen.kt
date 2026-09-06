@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.notebook

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

@Composable
fun NotebookScreen(
    padding: PaddingValues,
    onOpenItem: (String) -> Unit,
    onOpenDrawer: () -> Unit = {},
    viewModel: NotebookViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val colorFilter by viewModel.colorFilter.collectAsStateWithLifecycle()
    val usedColors by viewModel.usedColors.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var shareGroup by remember { mutableStateOf<NotebookGroup?>(null) }
    var actionGroup by remember { mutableStateOf<NotebookGroup?>(null) }
    var confirmDelete by remember { mutableStateOf<NotebookGroup?>(null) }

    fun send(text: String, subject: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, null)) }
    }

    fun shareAll() = viewModel.exportAll { md -> send(md, "My highlights") }

    Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.annotations), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.open_navigation)) }
                },
                actions = {
                    if (groups.isNotEmpty()) {
                        IconButton(onClick = { shareAll() }) {
                            Icon(Icons.Outlined.IosShare, contentDescription = stringResource(R.string.export_all))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface),
            )
        // Filter-by-colour: a compact row of swatch chips, shown only when more than one colour
        // is actually in use (so single-colour notebooks stay clutter-free).
        if (usedColors.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = colorFilter == null,
                    onClick = { viewModel.setColorFilter(null) },
                    label = { Text(stringResource(R.string.all)) },
                )
                usedColors.forEach { c ->
                    FilterChip(
                        selected = colorFilter == c,
                        onClick = { viewModel.setColorFilter(if (colorFilter == c) null else c) },
                        label = {
                            Box(
                                Modifier.size(16.dp).clip(RoundedCornerShape(8.dp)).background(Color(c))
                            )
                        },
                    )
                }
            }
        }
        if (groups.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.no_annotations_yet), style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.while_reading_long_press_to_select),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp,
                    top = 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalItemSpacing = 10.dp,
            ) {
                items(groups, key = { it.itemId }) { group ->
                    AnnotationCard(
                        group,
                        onClick = { onOpenItem(group.itemId) },
                        onLongClick = { actionGroup = group },
                        onShare = { shareGroup = group },
                    )
                }
            }
        }
    }

    shareGroup?.let { group ->
        AnnotationShareSheet(
            group = group,
            onShareGroup = { fmt -> send(viewModel.renderGroup(group, fmt), group.title); shareGroup = null },
            onShareHighlight = { h, fmt -> send(viewModel.renderHighlight(h, fmt), group.title) },
            onDismiss = { shareGroup = null },
        )
    }

    // Long-press on a card → per-entry actions.
    actionGroup?.let { group ->
        NotebookEntrySheet(
            group = group,
            onOpen = { onOpenItem(group.itemId); actionGroup = null },
            onShare = { actionGroup = null; shareGroup = group },
            onDelete = { actionGroup = null; confirmDelete = group },
            onDismiss = { actionGroup = null },
        )
    }

    confirmDelete?.let { group ->
        val n = group.highlights.size
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_annotations)) },
            text = {
                Text("Remove all $n highlight${if (n == 1) "" else "s"} and note${if (n == 1) "" else "s"} from “${group.title}”. This can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.removeGroup(group); confirmDelete = null }) {
                    Text(stringResource(R.string.delete), color = scheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Long-press actions for one annotated entry: open it, share, or delete its annotations. */
@Composable
private fun NotebookEntrySheet(
    group: NotebookGroup,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                group.title,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
            Text(
                "${group.highlights.size} annotation${if (group.highlights.size == 1) "" else "s"}" +
                    (group.site?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(12.dp))
            NotebookActionRow(Icons.AutoMirrored.Outlined.OpenInNew, "Open article", scheme.onSurface, onOpen)
            NotebookActionRow(Icons.Outlined.IosShare, "Share annotations", scheme.onSurface, onShare)
            NotebookActionRow(Icons.Outlined.DeleteOutline, "Delete annotations", scheme.error, onDelete)
        }
    }
}

@Composable
private fun NotebookActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/** Per-entry share sheet: pick a format, share the whole entry, or share any single highlight. */
@Composable
private fun AnnotationShareSheet(
    group: NotebookGroup,
    onShareGroup: (ShareFormat) -> Unit,
    onShareHighlight: (com.cairn.reader.data.db.HighlightWithArticle, ShareFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var format by remember { mutableStateOf(ShareFormat.QUOTE) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(stringResource(R.string.share_annotations),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
            Text(
                group.title,
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(12.dp))
            // Format selector.
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ShareFormat.entries.forEach { f ->
                    FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clickable { onShareGroup(format) }.padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(Icons.Outlined.IosShare, contentDescription = null, tint = scheme.primary)
                Text(
                    "Share all ${group.highlights.size} annotation${if (group.highlights.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface,
                )
            }
            HorizontalDivider()
            Text(stringResource(R.string.or_share_one),
                style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
            )
            group.highlights.forEach { h ->
                Row(
                    Modifier.fillMaxWidth().clickable { onShareHighlight(h, format) }.padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.width(3.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(Color(h.color)))
                    Text(
                        h.quote.trim(),
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Outlined.IosShare, contentDescription = stringResource(R.string.share_this_highlight), tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** One card per annotated article: cover, source, title, the top highlight, and a count. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnnotationCard(group: NotebookGroup, onClick: () -> Unit, onLongClick: () -> Unit, onShare: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // Guard against an empty group so composition never throws (was a NoSuchElementException).
    val top = group.highlights.firstOrNull() ?: return
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerLow)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(bottom = 12.dp),
    ) {
        if (group.image != null) {
            AsyncImage(
                model = group.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(96.dp),
            )
        } else {
            val tint = COVER_TINTS[(group.title.hashCode() and 0x7fffffff) % COVER_TINTS.size]
            Box(
                Modifier.fillMaxWidth().height(72.dp)
                    .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.85f), tint.copy(alpha = 0.45f)))),
            )
        }
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val source = group.site ?: "Highlight"
                Text(source, style = MaterialTheme.typography.labelSmall, color = scheme.primary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.IosShare, contentDescription = stringResource(R.string.share_these_annotations), tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(group.title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            // The top highlight, with its colour as a left bar.
            Row {
                Box(Modifier.width(3.dp).height(if (top.quote.length > 60) 44.dp else 24.dp).clip(RoundedCornerShape(2.dp)).background(Color(top.color)))
                Spacer(Modifier.width(8.dp))
                Text(
                    top.quote.trim(),
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = scheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (group.highlights.size > 1) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "+${group.highlights.size - 1} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(scheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

private val COVER_TINTS = listOf(
    Color(0xFF3F5E7A), Color(0xFF3E8E5A), Color(0xFFB98A2E),
    Color(0xFFB0553F), Color(0xFF6A5A8E), Color(0xFF2E8B94),
)
