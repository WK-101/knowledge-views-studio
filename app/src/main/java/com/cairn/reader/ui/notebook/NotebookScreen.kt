@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.notebook

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.cairn.reader.R

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.outlined.FormatQuote
import com.cairn.reader.data.db.HighlightWithArticle
import com.cairn.reader.ui.components.CairnSearchField
import com.cairn.reader.ui.components.EmptyState
import com.cairn.reader.ui.components.FilterChipRow
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage

@Composable
fun NotebookScreen(
    padding: PaddingValues,
    onOpenItem: (String) -> Unit,
    onOpenDrawer: () -> Unit = {},
    viewModel: NotebookViewModel = hiltViewModel(),
) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()
    val usedColors by viewModel.usedColors.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scheme = MaterialTheme.colorScheme
    var searchActive by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var shareGroup by remember { mutableStateOf<NotebookGroup?>(null) }
    var actionGroup by remember { mutableStateOf<NotebookGroup?>(null) }
    var confirmDelete by remember { mutableStateOf<NotebookGroup?>(null) }

    fun send(text: String, subject: String) {
        com.cairn.reader.util.shareText(context, text, subject = subject, chooser = null)
    }
    fun shareAll() = viewModel.exportAll { md -> send(md, "My highlights") }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.annotations), fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.open_navigation)) }
            },
            actions = {
                IconButton(onClick = { searchActive = !searchActive }) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search))
                }
                IconButton(onClick = { showOptions = true }) {
                    Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.annotation_options))
                }
                if (content.total > 0) {
                    IconButton(onClick = { shareAll() }) {
                        Icon(Icons.Outlined.IosShare, contentDescription = stringResource(R.string.export_all))
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface),
        )
        if (searchActive) {
            CairnSearchField(
                value = options.query,
                onValueChange = viewModel::setQuery,
                placeholder = stringResource(R.string.search_annotations),
                autofocus = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        when {
            content.total == 0 -> EmptyState(
                title = stringResource(if (options.query.isNotBlank()) R.string.no_results else R.string.no_annotations_yet),
                body = stringResource(if (options.query.isNotBlank()) R.string.try_a_different_search else R.string.while_reading_long_press_to_select),
                icon = Icons.Outlined.FormatQuote,
            )
            options.view == NoteView.CARDS -> LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalItemSpacing = 10.dp,
            ) {
                items(content.cards, key = { it.itemId }) { group ->
                    AnnotationCard(
                        group,
                        onClick = { onOpenItem(group.itemId) },
                        onLongClick = { actionGroup = group },
                        onShare = { shareGroup = group },
                    )
                }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
            ) {
                content.sections.forEach { section ->
                    if (section.title != null || section.color != null) {
                        item(key = "h_${section.key}") { SectionHeader(section, onOpen = { section.itemId?.let(onOpenItem) }) }
                    }
                    items(section.highlights, key = { it.id }) { h ->
                        HighlightListRow(
                            h = h,
                            onOpen = { onOpenItem(h.itemId) },
                            onSetColor = { c -> viewModel.setColor(h.id, h.itemId, c) },
                            onCopy = { clipboard.setText(AnnotatedString(h.quote.trim())) },
                            onShare = { send(viewModel.renderHighlight(h, ShareFormat.QUOTE), h.articleTitle) },
                            onDelete = { viewModel.remove(h.id, h.itemId) },
                        )
                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(start = 26.dp))
                    }
                }
            }
        }
    }

    if (showOptions) {
        AnnotationOptionsSheet(
            options = options,
            usedColors = usedColors,
            onView = viewModel::setView,
            onSort = viewModel::setSort,
            onGroup = viewModel::setGroup,
            onType = viewModel::setType,
            onColor = viewModel::setColorFilter,
            onDismiss = { showOptions = false },
        )
    }

    shareGroup?.let { group ->
        AnnotationShareSheet(
            group = group,
            onShareGroup = { fmt -> send(viewModel.renderGroup(group, fmt), group.title); shareGroup = null },
            onShareHighlight = { h, fmt -> send(viewModel.renderHighlight(h, fmt), group.title) },
            onDismiss = { shareGroup = null },
        )
    }

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
            text = { Text(pluralStringResource(R.plurals.notebook_delete_count, n, n, group.title)) },
            confirmButton = {
                TextButton(onClick = { viewModel.removeGroup(group); confirmDelete = null }) {
                    Text(stringResource(R.string.delete), color = scheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The view-options sheet: view, sort, grouping, type and colour filters — all in one place. */
@Composable
private fun AnnotationOptionsSheet(
    options: NoteOptions,
    usedColors: List<Int>,
    onView: (NoteView) -> Unit,
    onSort: (NoteSort) -> Unit,
    onGroup: (NoteGroup) -> Unit,
    onType: (NoteType) -> Unit,
    onColor: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        com.cairn.reader.ui.KeepImmersiveWhileOpen()
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            OptionLabel(stringResource(R.string.view))
            FilterChipRow {
                FilterChip(options.view == NoteView.CARDS, { onView(NoteView.CARDS) }, label = { Text(stringResource(R.string.view_cards)) })
                FilterChip(options.view == NoteView.LIST, { onView(NoteView.LIST) }, label = { Text(stringResource(R.string.view_list)) })
            }
            OptionLabel(stringResource(R.string.sort))
            FilterChipRow {
                FilterChip(options.sort == NoteSort.RECENT, { onSort(NoteSort.RECENT) }, label = { Text(stringResource(R.string.sort_recent)) })
                FilterChip(options.sort == NoteSort.OLDEST, { onSort(NoteSort.OLDEST) }, label = { Text(stringResource(R.string.sort_oldest)) })
                FilterChip(options.sort == NoteSort.TITLE, { onSort(NoteSort.TITLE) }, label = { Text(stringResource(R.string.sort_title)) })
                FilterChip(options.sort == NoteSort.COUNT, { onSort(NoteSort.COUNT) }, label = { Text(stringResource(R.string.sort_most_highlighted)) })
            }
            // Grouping only shapes the list view; cards are always one-per-article.
            if (options.view == NoteView.LIST) {
                OptionLabel(stringResource(R.string.group))
                FilterChipRow {
                    FilterChip(options.group == NoteGroup.ARTICLE, { onGroup(NoteGroup.ARTICLE) }, label = { Text(stringResource(R.string.group_by_article)) })
                    FilterChip(options.group == NoteGroup.COLOR, { onGroup(NoteGroup.COLOR) }, label = { Text(stringResource(R.string.group_by_color)) })
                    FilterChip(options.group == NoteGroup.NONE, { onGroup(NoteGroup.NONE) }, label = { Text(stringResource(R.string.group_none)) })
                }
            }
            OptionLabel(stringResource(R.string.type_label))
            FilterChipRow {
                FilterChip(options.type == NoteType.ALL, { onType(NoteType.ALL) }, label = { Text(stringResource(R.string.all)) })
                FilterChip(options.type == NoteType.ARTICLE, { onType(NoteType.ARTICLE) }, label = { Text(stringResource(R.string.type_articles)) })
                FilterChip(options.type == NoteType.TRANSCRIPT, { onType(NoteType.TRANSCRIPT) }, label = { Text(stringResource(R.string.type_transcripts)) })
            }
            if (usedColors.size > 1) {
                OptionLabel(stringResource(R.string.colour_label))
                FilterChipRow {
                    FilterChip(options.colorFilter == null, { onColor(null) }, label = { Text(stringResource(R.string.all)) })
                    usedColors.forEach { c ->
                        FilterChip(
                            selected = options.colorFilter == c,
                            onClick = { onColor(if (options.colorFilter == c) null else c) },
                            label = { Box(Modifier.size(16.dp).clip(CircleShape).background(Color(c))) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** A list-view section header — an article title (tappable), or a colour swatch, or nothing. */
@Composable
private fun SectionHeader(section: NotebookSection, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (section.itemId != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (section.color != null) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(section.color)))
        }
        Column(Modifier.weight(1f)) {
            Text(
                section.title ?: stringResource(R.string.highlights),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = section.subtitle
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            pluralStringResource(R.plurals.annotation_count, section.highlights.size, section.highlights.size),
            style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
        )
        if (section.itemId != null) Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

/** One highlight as a list row: colour bar, quote (+ note), and an actions overflow. */
@Composable
private fun HighlightListRow(
    h: HighlightWithArticle,
    onOpen: () -> Unit,
    onSetColor: (Int) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var overflow by remember { mutableStateOf(false) }
    var colorMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.padding(top = 2.dp).width(3.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(Color(h.color)))
        Column(Modifier.weight(1f)) {
            Text(
                h.quote.trim(),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = scheme.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
            h.note?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Box {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.more_options),
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp).clip(CircleShape).clickable { overflow = true },
            )
            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.open_article)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                    onClick = { overflow = false; onOpen() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.change_color)) },
                    leadingIcon = { Box(Modifier.size(18.dp).clip(CircleShape).background(Color(h.color))) },
                    onClick = { overflow = false; colorMenu = true },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy)) },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    onClick = { overflow = false; onCopy() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share)) },
                    leadingIcon = { Icon(Icons.Outlined.IosShare, contentDescription = null) },
                    onClick = { overflow = false; onShare() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = scheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = scheme.error) },
                    onClick = { overflow = false; onDelete() },
                )
            }
            DropdownMenu(expanded = colorMenu, onDismissRequest = { colorMenu = false }) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    com.cairn.reader.ui.reader.HighlightColors.all.forEach { c ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                                .border(if (c == h.color) 2.dp else 1.dp, scheme.outline, CircleShape)
                                .clickable { onSetColor(c); colorMenu = false },
                        )
                    }
                }
            }
        }
    }
}

/** Long-press actions for one annotated entry (cards view): open it, share, or delete. */
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
        com.cairn.reader.ui.KeepImmersiveWhileOpen()
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                group.title,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
            Text(
                pluralStringResource(R.plurals.annotation_count, group.highlights.size, group.highlights.size) +
                    (group.site?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(12.dp))
            NotebookActionRow(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.open_article), scheme.onSurface, onOpen)
            NotebookActionRow(Icons.Outlined.IosShare, stringResource(R.string.share_annotations), scheme.onSurface, onShare)
            NotebookActionRow(Icons.Outlined.DeleteOutline, stringResource(R.string.delete_annotations), scheme.error, onDelete)
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
    onShareHighlight: (HighlightWithArticle, ShareFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var format by remember { mutableStateOf(ShareFormat.QUOTE) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        com.cairn.reader.ui.KeepImmersiveWhileOpen()
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
                    pluralStringResource(R.plurals.share_all_annotations_count, group.highlights.size, group.highlights.size),
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
                val source = group.site ?: stringResource(R.string.highlights)
                Text(source, style = MaterialTheme.typography.labelSmall, color = scheme.primary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onShare) {
                    Icon(Icons.Outlined.IosShare, contentDescription = stringResource(R.string.share_these_annotations), tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(group.title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
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

private val COVER_TINTS = com.cairn.reader.ui.components.MonogramPalette.take(6)
