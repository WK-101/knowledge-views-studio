@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.reader

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cairn.reader.data.db.HighlightEntity
import com.cairn.reader.data.prefs.ReaderFont
import com.cairn.reader.data.prefs.ReaderTheme
import com.cairn.reader.ui.components.CollectionPickerSheet
import com.cairn.reader.ui.components.TagEditorSheet
import com.cairn.reader.ui.theme.InterFamily
import com.cairn.reader.ui.theme.ReadingSerif
import com.cairn.reader.ui.util.formatAgo
import java.text.BreakIterator
import java.util.Locale

private val ReaderHPad = 22.dp

private fun readerFontFamily(font: ReaderFont): FontFamily = when (font) {
    ReaderFont.SERIF -> ReadingSerif
    ReaderFont.SANS -> InterFamily
    ReaderFont.SYSTEM -> FontFamily.Serif
    ReaderFont.MONO -> FontFamily.Monospace
}

private data class ReaderPalette(val background: Color, val text: Color, val secondary: Color)

@Composable
private fun readerPalette(theme: ReaderTheme): ReaderPalette {
    val scheme = MaterialTheme.colorScheme
    return when (theme) {
        ReaderTheme.DEFAULT -> ReaderPalette(scheme.surface, scheme.onSurface, scheme.onSurfaceVariant)
        ReaderTheme.SEPIA -> ReaderPalette(Color(0xFFF4ECD8), Color(0xFF463A28), Color(0xFF7C6C52))
        ReaderTheme.BLACK -> ReaderPalette(Color(0xFF000000), Color(0xFFE6E6E6), Color(0xFF9C9C9C))
    }
}

@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    onOpenWeb: (String) -> Unit = {},
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    val ttsState by viewModel.tts.collectAsStateWithLifecycle()
    val audioState by viewModel.audio.collectAsStateWithLifecycle()
    val savingOffline by viewModel.savingOffline.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val itemTags by viewModel.itemTags.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val data = state.data
    var showTypography by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showCollections by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    var managed by remember { mutableStateOf<HighlightEntity?>(null) }
    var pending by remember { mutableStateOf<PendingSelection?>(null) }
    val clipboard = LocalClipboardManager.current

    val palette = readerPalette(prefs.readerTheme)

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun openOriginal() {
        val url = data?.url ?: return
        onOpenWeb(url)
    }

    fun shareText(text: String, subject: String?) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
    }

    // Search / Define stay inside Cairn's own WebView (a normal fetch of a public
    // search page) — no app switch, nothing about the query leaves the device
    // beyond the search itself, which the reader deliberately asked for.
    fun webLookup(quote: String, define: Boolean) {
        val q = quote.trim().take(300)
        if (q.isEmpty()) return
        val term = if (define) "define $q" else q
        val url = "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(term, "UTF-8")
        onOpenWeb(url)
    }

    Scaffold(
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.text) }
                },
                actions = {
                    IconButton(onClick = { showTypography = true }) {
                        Icon(Icons.Outlined.FormatSize, contentDescription = "Text options", tint = palette.text)
                    }
                    IconButton(onClick = viewModel::toggleStar) {
                        Icon(
                            imageVector = if (data?.isStarred == true) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Star",
                            tint = if (data?.isStarred == true) MaterialTheme.colorScheme.tertiary else palette.text,
                        )
                    }
                    IconButton(onClick = viewModel::toggleSave) {
                        Icon(
                            imageVector = if (data?.isReadLater == true) Icons.Filled.Bookmark else Icons.Outlined.Bookmark,
                            contentDescription = "Save",
                            tint = if (data?.isReadLater == true) MaterialTheme.colorScheme.tertiary else palette.text,
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "More", tint = palette.text)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (data?.collectionId != null) "Move to collection" else "Save to collection") },
                                leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                                onClick = { showMenu = false; showCollections = true },
                            )
                            DropdownMenuItem(
                                text = { Text(if (itemTags.isEmpty()) "Add tags" else "Tags · ${itemTags.size}") },
                                leadingIcon = { Icon(Icons.Outlined.Label, contentDescription = null) },
                                onClick = { showMenu = false; showTags = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Listen") },
                                leadingIcon = { Icon(Icons.Outlined.Headphones, contentDescription = null) },
                                onClick = { showMenu = false; viewModel.toggleListen() },
                            )
                            val permanent = data?.cacheStatus == "PERMANENT"
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when {
                                            savingOffline -> "Saving offline…"
                                            permanent -> "Saved offline ✓"
                                            else -> "Save offline"
                                        },
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (permanent) Icons.Outlined.OfflinePin else Icons.Outlined.DownloadForOffline,
                                        contentDescription = null,
                                    )
                                },
                                enabled = !savingOffline && !permanent,
                                onClick = { showMenu = false; viewModel.saveOffline() },
                            )
                            DropdownMenuItem(
                                text = { Text(if (data?.isArchived == true) "Unarchive" else "Archive") },
                                leadingIcon = {
                                    Icon(
                                        if (data?.isArchived == true) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                                        contentDescription = null,
                                    )
                                },
                                onClick = { showMenu = false; viewModel.toggleArchive() },
                            )
                            if (data?.type != "PDF") {
                                DropdownMenuItem(
                                    text = { Text("Export as PDF") },
                                    leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null) },
                                    onClick = {
                                        showMenu = false
                                        com.cairn.reader.ui.util.PdfExport.printArticle(context, data?.title.orEmpty(), data?.html)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Open original") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                                onClick = { showMenu = false; openOriginal() },
                            )
                            DropdownMenuItem(
                                text = { Text(if (highlights.isEmpty()) "Share article" else "Export highlights") },
                                leadingIcon = { Icon(Icons.Outlined.IosShare, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    if (highlights.isEmpty()) shareText(data?.url.orEmpty(), data?.title)
                                    else viewModel.exportHighlights { md -> shareText(md, data?.title?.let { "Highlights — $it" }) }
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.background),
            )
        },
        bottomBar = {
            Column {
                if (ttsState.active) {
                    com.cairn.reader.ui.components.ListenBar(
                        state = ttsState,
                        onPlayPause = viewModel::toggleListen,
                        onStop = viewModel::stopListen,
                        onSpeed = viewModel::setListenSpeed,
                        onPrev = viewModel::listenPrev,
                        onNext = viewModel::listenNext,
                    )
                }
                if (audioState.active) {
                    com.cairn.reader.ui.components.AudioBar(
                        state = audioState,
                        onPlayPause = viewModel::audioToggle,
                        onBack = { viewModel.audioSeek(-15_000) },
                        onForward = { viewModel.audioSeek(30_000) },
                        onStop = viewModel::audioStop,
                    )
                }
                if (data != null) {
                    ReaderActionBar(
                        isStarred = data.isStarred,
                        onShare = { shareText(data.url, data.title) },
                        onUnread = { viewModel.markUnread(); onBack() },
                        onStar = viewModel::toggleStar,
                        onTag = { showTags = true },
                        onMore = { showMenu = true },
                    )
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> Centered(padding) { CircularProgressIndicator() }
            data == null -> Centered(padding) { Text("This article couldn't be loaded.", color = palette.secondary) }
            data.type == "PDF" -> PdfView(padding = padding, path = data.pdfPath, background = palette.background)
            else -> ArticleBody(
                padding = padding,
                state = state,
                palette = palette,
                highlights = highlights,
                fontFamily = readerFontFamily(prefs.readerFont),
                scale = prefs.readerFontScale,
                justify = prefs.readerJustify,
                onLoadFull = viewModel::loadFullArticle,
                onOpenOriginal = ::openOriginal,
                onSaveProgress = viewModel::setProgress,
                onSelectText = { b, s, e, q -> pending = PendingSelection(b, s, e, q) },
                onManageHighlight = { managed = it },
                onPlayEpisode = viewModel::playEpisode,
            )
        }
    }

    if (showTypography && data != null) {
        TypographySheet(
            fontScale = prefs.readerFontScale,
            readerFont = prefs.readerFont,
            readerTheme = prefs.readerTheme,
            justify = prefs.readerJustify,
            onFontScale = viewModel::setFontScale,
            onReaderFont = viewModel::setReaderFont,
            onReaderTheme = viewModel::setReaderTheme,
            onJustify = viewModel::setReaderJustify,
            onDismiss = { showTypography = false },
        )
    }

    if (showCollections && data != null) {
        CollectionPickerSheet(
            collections = collections,
            currentCollectionId = data.collectionId,
            onPick = { collectionId -> viewModel.moveToCollection(collectionId); showCollections = false },
            onCreate = { name -> viewModel.createCollection(name) {} },
            onDismiss = { showCollections = false },
        )
    }

    if (showTags && data != null) {
        TagEditorSheet(
            current = itemTags,
            all = allTags,
            onAdd = viewModel::addTag,
            onRemove = viewModel::removeTag,
            onDismiss = { showTags = false },
        )
    }

    managed?.let { highlight ->
        HighlightSheet(
            highlight = highlight,
            onColor = { viewModel.setHighlightColor(highlight.id, it) },
            onSaveNote = { viewModel.setHighlightNote(highlight.id, it) },
            onCopy = { clipboard.setText(AnnotatedString(highlight.quote.trim())) },
            onShare = { shareText(highlight.quote.trim(), data?.title) },
            onDelete = { viewModel.removeHighlight(highlight.id); managed = null },
            onDismiss = { managed = null },
        )
    }

    pending?.let { sel ->
        SelectionSheet(
            quote = sel.quote,
            onHighlight = { color ->
                viewModel.addHighlight(sel.blockIndex, sel.start, sel.end, sel.quote, color)
                pending = null
            },
            onSearch = { webLookup(sel.quote, define = false); pending = null },
            onDefine = { webLookup(sel.quote, define = true); pending = null },
            onCopy = { clipboard.setText(AnnotatedString(sel.quote.trim())); pending = null },
            onShare = { shareText(sel.quote.trim(), data?.title); pending = null },
            onDismiss = { pending = null },
        )
    }
}

/** A text selection awaiting an action from the contextual menu. */
private data class PendingSelection(val blockIndex: Int, val start: Int, val end: Int, val quote: String)

@Composable
private fun ArticleBody(
    padding: PaddingValues,
    state: ReaderUiState,
    palette: ReaderPalette,
    highlights: List<HighlightEntity>,
    fontFamily: FontFamily,
    scale: Float,
    justify: Boolean,
    onLoadFull: () -> Unit,
    onOpenOriginal: () -> Unit,
    onSaveProgress: (Float) -> Unit,
    onSelectText: (blockIndex: Int, start: Int, end: Int, quote: String) -> Unit,
    onManageHighlight: (HighlightEntity) -> Unit,
    onPlayEpisode: () -> Unit,
) {
    val data = state.data ?: return
    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = remember(data.html, linkColor) {
        data.html?.let { HtmlLinearizer.linearize(it, data.url, linkColor) }.orEmpty()
    }
    val bodyStyle = TextStyle(fontFamily = fontFamily, fontSize = (18 * scale).sp, lineHeight = (30 * scale).sp, color = palette.text)
    val byBlock = remember(highlights) { highlights.groupBy { it.startSelector?.toIntOrNull() ?: -1 } }

    val listState = rememberLazyListState()
    val progress by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total <= 1) 0f else (listState.firstVisibleItemIndex.toFloat() / (total - 1)).coerceIn(0f, 1f)
        }
    }
    val latestProgress = rememberUpdatedState(progress)
    DisposableEffect(Unit) { onDispose { onSaveProgress(latestProgress.value) } }

    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 48.dp),
        ) {
            item {
                if (data.leadImage != null) {
                    AsyncImage(
                        model = data.leadImage,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                    )
                }
                Column(Modifier.padding(horizontal = ReaderHPad)) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = data.title,
                        style = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = (28 * scale).sp, lineHeight = (34 * scale).sp),
                        color = palette.text,
                    )
                    Spacer(Modifier.height(10.dp))
                    val meta = buildList {
                        data.siteName?.let { add(it) }
                        data.author?.let { add(it) }
                        if (data.readingMinutes > 0) add("${data.readingMinutes} min read")
                        formatAgo(data.publishedAt).takeIf { it.isNotEmpty() }?.let { add(it) }
                        if (data.cacheStatus == "PERMANENT") add("Saved offline")
                        if (data.isArchived) add("Archived")
                    }.joinToString("  ·  ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelMedium, color = palette.secondary)
                    Spacer(Modifier.height(16.dp))
                    when {
                        state.extracting -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(16.dp).width(16.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("Fetching full article…", style = MaterialTheme.typography.labelMedium, color = palette.secondary)
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        data.extractStatus == "FAILED" -> {
                            OutlinedButton(onClick = onLoadFull) { Text("Retry full article") }
                            Spacer(Modifier.height(4.dp))
                            Text("Showing the summary — the full article couldn't be fetched.", style = MaterialTheme.typography.labelSmall, color = palette.secondary)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (data.enclosureUrl != null) {
                        OutlinedButton(onClick = onPlayEpisode) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play episode")
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    HorizontalDivider(color = palette.secondary.copy(alpha = 0.25f))
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (blocks.isEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = ReaderHPad)) {
                        Text("No readable content was saved for this item yet.", style = bodyStyle, color = palette.secondary)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = onOpenOriginal) { Text("Open original") }
                    }
                }
            }

            items(blocks.size) { index ->
                BlockView(
                    block = blocks[index],
                    blockIndex = index,
                    bodyStyle = bodyStyle,
                    palette = palette,
                    justify = justify,
                    highlights = byBlock[index].orEmpty(),
                    onSelectText = onSelectText,
                    onManageHighlight = onManageHighlight,
                )
            }
        }
    }
}

@Composable
private fun BlockView(
    block: ReaderBlock,
    blockIndex: Int,
    bodyStyle: TextStyle,
    palette: ReaderPalette,
    justify: Boolean,
    highlights: List<HighlightEntity>,
    onSelectText: (blockIndex: Int, start: Int, end: Int, quote: String) -> Unit,
    onManageHighlight: (HighlightEntity) -> Unit,
) {
    when (block) {
        is ReaderBlock.Heading -> HighlightableText(
            base = block.text,
            style = bodyStyle.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = bodyStyle.fontSize * when (block.level) { 1, 2 -> 1.35f; 3 -> 1.2f; else -> 1.08f },
                lineHeight = bodyStyle.lineHeight * 1.05f,
            ),
            highlights = highlights,
            onSelect = { s, e, q -> onSelectText(blockIndex, s, e, q) },
            onManage = onManageHighlight,
            modifier = Modifier.padding(horizontal = ReaderHPad, vertical = 10.dp),
        )
        is ReaderBlock.Paragraph -> HighlightableText(
            base = block.text,
            style = bodyStyle.copy(textAlign = if (justify) TextAlign.Justify else TextAlign.Start),
            highlights = highlights,
            onSelect = { s, e, q -> onSelectText(blockIndex, s, e, q) },
            onManage = onManageHighlight,
            modifier = Modifier.padding(horizontal = ReaderHPad, vertical = 9.dp),
        )
        is ReaderBlock.Image -> Column(Modifier.padding(vertical = 10.dp)) {
            AsyncImage(
                model = block.url,
                contentDescription = block.caption,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().padding(horizontal = ReaderHPad).clip(RoundedCornerShape(12.dp)),
            )
            if (!block.caption.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(block.caption, style = MaterialTheme.typography.labelMedium, color = palette.secondary, modifier = Modifier.fillMaxWidth().padding(horizontal = ReaderHPad))
            }
        }
        is ReaderBlock.Quote -> Row(
            Modifier.padding(horizontal = ReaderHPad, vertical = 10.dp).height(IntrinsicSize.Min),
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(14.dp))
            HighlightableText(
                base = block.text,
                style = bodyStyle.copy(fontStyle = FontStyle.Italic, color = palette.secondary),
                highlights = highlights,
                onSelect = { s, e, q -> onSelectText(blockIndex, s, e, q) },
                onManage = onManageHighlight,
            )
        }
        is ReaderBlock.Code -> Box(
            Modifier.padding(horizontal = ReaderHPad, vertical = 10.dp).fillMaxWidth()
                .clip(RoundedCornerShape(10.dp)).background(palette.text.copy(alpha = 0.06f))
                .horizontalScroll(rememberScrollState()).padding(14.dp),
        ) {
            Text(block.text, style = bodyStyle.copy(fontFamily = FontFamily.Monospace, fontSize = bodyStyle.fontSize * 0.82f))
        }
        is ReaderBlock.BulletList -> Column(Modifier.padding(horizontal = ReaderHPad, vertical = 8.dp)) {
            block.items.forEachIndexed { i, item ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(if (block.ordered) "${i + 1}. " else "•  ", style = bodyStyle, color = MaterialTheme.colorScheme.primary)
                    Text(text = item, style = bodyStyle)
                }
            }
        }
        ReaderBlock.Rule -> HorizontalDivider(color = palette.secondary.copy(alpha = 0.25f), modifier = Modifier.padding(horizontal = ReaderHPad, vertical = 18.dp))
    }
}

/**
 * Body text that can be highlighted. Long-press a sentence to open the selection menu
 * (highlight in a colour, copy, share); long-press an existing highlight to manage it.
 * Links keep working because they are rendered as their own interactive regions.
 */
@Composable
private fun HighlightableText(
    base: AnnotatedString,
    style: TextStyle,
    highlights: List<HighlightEntity>,
    onSelect: (start: Int, end: Int, quote: String) -> Unit,
    onManage: (HighlightEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val plain = base.text
    val layoutState = remember { mutableStateOf<TextLayoutResult?>(null) }
    val currentSelect by rememberUpdatedState(onSelect)
    val currentManage by rememberUpdatedState(onManage)
    val currentHighlights by rememberUpdatedState(highlights)
    val selColor = MaterialTheme.colorScheme.primary

    // Live drag selection: long-press anchors on a word, drag extends across words.
    var anchor by remember { mutableStateOf<Int?>(null) }
    var focus by remember { mutableStateOf<Int?>(null) }

    val rendered = remember(base, highlights, anchor, focus) {
        val withHl = applyHighlights(base, highlights)
        val a = anchor; val f = focus
        if (a != null && f != null && a != f) {
            buildAnnotatedString {
                append(withHl)
                addStyle(SpanStyle(background = selColor.copy(alpha = 0.28f)), minOf(a, f), maxOf(a, f))
            }
        } else withHl
    }

    fun offsetAt(pos: androidx.compose.ui.geometry.Offset): Int =
        (layoutState.value?.getOffsetForPosition(pos) ?: 0).coerceIn(0, plain.length)

    Text(
        text = rendered,
        style = style,
        onTextLayout = { layoutState.value = it },
        modifier = modifier.pointerInput(plain) {
            detectDragGesturesAfterLongPress(
                onDragStart = { pos ->
                    val o = offsetAt(pos)
                    // Anchor to the whole word first — a plain long-press then selects one word.
                    val w = wordRangeAt(plain, o)
                    anchor = w?.first ?: o
                    focus = w?.let { it.last + 1 } ?: o
                },
                onDrag = { change, _ -> focus = offsetAt(change.position) },
                onDragCancel = { anchor = null; focus = null },
                onDragEnd = {
                    val a = anchor; val f = focus
                    anchor = null; focus = null
                    if (a == null || f == null) return@detectDragGesturesAfterLongPress
                    var s = minOf(a, f); var e = maxOf(a, f)
                    // A tap that landed on an existing highlight (no real drag) manages it.
                    val hit = currentHighlights.firstOrNull { s >= it.startOffset && s < it.endOffset }
                    if (hit != null && e - s <= (hit.endOffset - hit.startOffset)) { currentManage(hit); return@detectDragGesturesAfterLongPress }
                    if (e <= s) return@detectDragGesturesAfterLongPress
                    // trim surrounding whitespace
                    while (e > s && plain[e - 1].isWhitespace()) e--
                    while (s < e && plain[s].isWhitespace()) s++
                    if (e > s) currentSelect(s, e, plain.substring(s, e))
                },
            )
        },
    )
}

/** Shown when the reader long-presses unhighlighted text — the contextual menu:
 *  Search / Define / Copy / Share, plus a row of highlight colours. */
@Composable
private fun SelectionSheet(
    quote: String,
    onHighlight: (Int) -> Unit,
    onSearch: () -> Unit,
    onDefine: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text(
                text = "“${quote.trim()}”",
                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionAction(Icons.Outlined.Search, "Search", onSearch)
                SelectionAction(Icons.Outlined.MenuBook, "Define", onDefine)
                SelectionAction(Icons.Outlined.ContentCopy, "Copy", onCopy)
                SelectionAction(Icons.Outlined.IosShare, "Share", onShare)
            }
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(16.dp))
            Text("Highlight", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                HighlightColors.all.forEach { c ->
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { onHighlight(c) },
                    )
                }
            }
        }
    }
}

/** One compact icon+label action in the selection menu. */
@Composable
private fun SelectionAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HighlightSheet(
    highlight: HighlightEntity,
    onColor: (Int) -> Unit,
    onSaveNote: (String?) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var note by remember(highlight.id) { mutableStateOf(highlight.note.orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(
                text = "“${highlight.quote.trim()}”",
                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                HighlightColors.all.forEach { c ->
                    val selected = c == highlight.color
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            )
                            .clickable { onColor(c) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note") },
                placeholder = { Text("Add a thought…") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(20.dp)) }
                IconButton(onClick = onShare) { Icon(Icons.Outlined.IosShare, contentDescription = "Share", modifier = Modifier.size(20.dp)) }
                TextButton(onClick = { onSaveNote(note); onDismiss() }) { Text("Save") }
            }
        }
    }
}

@Composable
private fun TypographySheet(
    fontScale: Float,
    readerFont: ReaderFont,
    readerTheme: ReaderTheme,
    justify: Boolean,
    onFontScale: (Float) -> Unit,
    onReaderFont: (ReaderFont) -> Unit,
    onReaderTheme: (ReaderTheme) -> Unit,
    onJustify: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("Text size", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = fontScale,
                    onValueChange = onFontScale,
                    valueRange = 0.8f..1.8f,
                    steps = 4,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text("A", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(8.dp))
            Text("Font", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                ReaderFont.entries.forEach { font ->
                    FilterChip(
                        selected = readerFont == font,
                        onClick = { onReaderFont(font) },
                        label = { Text(font.label, fontFamily = readerFontFamily(font)) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Theme", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = readerTheme == ReaderTheme.DEFAULT, onClick = { onReaderTheme(ReaderTheme.DEFAULT) }, label = { Text("Default") })
                FilterChip(selected = readerTheme == ReaderTheme.SEPIA, onClick = { onReaderTheme(ReaderTheme.SEPIA) }, label = { Text("Sepia") })
                FilterChip(selected = readerTheme == ReaderTheme.BLACK, onClick = { onReaderTheme(ReaderTheme.BLACK) }, label = { Text("Black") })
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Justify text", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = justify, onCheckedChange = onJustify)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val ListenSpeeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

private fun nextSpeed(current: Float): Float {
    val i = ListenSpeeds.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return if (i == -1) 1.0f else ListenSpeeds[(i + 1) % ListenSpeeds.size]
}

private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "$speed×"

@Composable
private fun MiniPlayer(
    state: com.cairn.reader.audio.TtsReader.State,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSpeed: (Float) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.playing) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text("Listening", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                val fraction = if (state.total > 0) (state.index + 1f) / state.total else 0f
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
            TextButton(onClick = { onSpeed(nextSpeed(state.speed)) }) { Text(speedLabel(state.speed)) }
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Close, contentDescription = "Stop", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The persistent reader triage bar — Inoreader-style: Share · Unread · Star · Tag · More. */
@Composable
private fun ReaderActionBar(
    isStarred: Boolean,
    onShare: () -> Unit,
    onUnread: () -> Unit,
    onStar: () -> Unit,
    onTag: () -> Unit,
    onMore: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp, shadowElevation = 6.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderAction(Icons.Outlined.IosShare, "Share", onShare)
            ReaderAction(Icons.Outlined.Circle, "Unread", onUnread)
            ReaderAction(
                if (isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                "Star",
                onStar,
                tint = if (isStarred) MaterialTheme.colorScheme.tertiary else null,
            )
            ReaderAction(Icons.Outlined.Label, "Tag", onTag)
            ReaderAction(Icons.Outlined.MoreVert, "More", onMore)
        }
    }
}

@Composable
private fun ReaderAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, tint: Color? = null) {
    val color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun Centered(padding: PaddingValues, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

// -- Highlight helpers --------------------------------------------------------

private fun applyHighlights(base: AnnotatedString, highlights: List<HighlightEntity>): AnnotatedString {
    if (highlights.isEmpty()) return base
    return buildAnnotatedString {
        append(base)
        highlights.forEach { h ->
            val s = h.startOffset.coerceIn(0, base.length)
            val e = h.endOffset.coerceIn(s, base.length)
            if (e > s) addStyle(SpanStyle(background = Color(h.color).copy(alpha = 0.42f)), s, e)
        }
    }
}

/** The character range of the word containing [offset] (letters/digits), or null on whitespace. */
private fun wordRangeAt(text: String, offset: Int): IntRange? {
    if (text.isBlank()) return null
    val probe = offset.coerceIn(0, text.length - 1)
    if (!text[probe].isLetterOrDigit()) return null
    var s = probe
    while (s > 0 && text[s - 1].isLetterOrDigit()) s--
    var e = probe
    while (e < text.length && text[e].isLetterOrDigit()) e++
    return if (e > s) s..(e - 1) else null
}

/** The character range of the sentence containing [offset], trimmed of surrounding space. */
private fun sentenceRangeAt(text: String, offset: Int): IntRange? {
    if (text.isBlank()) return null
    val iterator = BreakIterator.getSentenceInstance(Locale.getDefault())
    iterator.setText(text)
    val probe = offset.coerceIn(0, text.length - 1)
    val end = iterator.following(probe).let { if (it == BreakIterator.DONE) text.length else it }
    val start = iterator.previous().let { if (it == BreakIterator.DONE) 0 else it }
    var e = end
    while (e > start && text[e - 1].isWhitespace()) e--
    var s = start
    while (s < e && text[s].isWhitespace()) s++
    return if (e > s) s..(e - 1) else null
}
