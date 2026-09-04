@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.reader

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cairn.reader.data.db.HighlightEntity
import com.cairn.reader.data.prefs.ReaderFont
import com.cairn.reader.data.prefs.ReaderTheme
import com.cairn.reader.ui.theme.InterFamily
import com.cairn.reader.ui.theme.ReadingSerif
import com.cairn.reader.ui.util.formatAgo
import java.text.BreakIterator
import java.util.Locale

private val ReaderHPad = 22.dp

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
    val context = LocalContext.current
    val data = state.data
    var showTypography by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var managed by remember { mutableStateOf<HighlightEntity?>(null) }

    val palette = readerPalette(prefs.readerTheme)

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
                                text = { Text("Listen") },
                                leadingIcon = { Icon(Icons.Outlined.Headphones, contentDescription = null) },
                                onClick = { showMenu = false; viewModel.toggleListen() },
                            )
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
            if (ttsState.active) {
                MiniPlayer(
                    state = ttsState,
                    onPlayPause = viewModel::toggleListen,
                    onStop = viewModel::stopListen,
                    onSpeed = viewModel::setListenSpeed,
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> Centered(padding) { CircularProgressIndicator() }
            data == null -> Centered(padding) { Text("This article couldn't be loaded.", color = palette.secondary) }
            else -> ArticleBody(
                padding = padding,
                state = state,
                palette = palette,
                highlights = highlights,
                fontFamily = if (prefs.readerFont == ReaderFont.SERIF) ReadingSerif else InterFamily,
                scale = prefs.readerFontScale,
                onLoadFull = viewModel::loadFullArticle,
                onOpenOriginal = ::openOriginal,
                onSaveProgress = viewModel::setProgress,
                onAddHighlight = viewModel::addHighlight,
                onManageHighlight = { managed = it },
            )
        }
    }

    if (showTypography && data != null) {
        TypographySheet(
            fontScale = prefs.readerFontScale,
            readerFont = prefs.readerFont,
            readerTheme = prefs.readerTheme,
            onFontScale = viewModel::setFontScale,
            onReaderFont = viewModel::setReaderFont,
            onReaderTheme = viewModel::setReaderTheme,
            onDismiss = { showTypography = false },
        )
    }

    managed?.let { highlight ->
        HighlightSheet(
            highlight = highlight,
            onColor = { viewModel.setHighlightColor(highlight.id, it) },
            onSaveNote = { viewModel.setHighlightNote(highlight.id, it) },
            onDelete = { viewModel.removeHighlight(highlight.id); managed = null },
            onDismiss = { managed = null },
        )
    }
}

@Composable
private fun ArticleBody(
    padding: PaddingValues,
    state: ReaderUiState,
    palette: ReaderPalette,
    highlights: List<HighlightEntity>,
    fontFamily: FontFamily,
    scale: Float,
    onLoadFull: () -> Unit,
    onOpenOriginal: () -> Unit,
    onSaveProgress: (Float) -> Unit,
    onAddHighlight: (blockIndex: Int, start: Int, end: Int, quote: String) -> Unit,
    onManageHighlight: (HighlightEntity) -> Unit,
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
                    highlights = byBlock[index].orEmpty(),
                    onAddHighlight = onAddHighlight,
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
    highlights: List<HighlightEntity>,
    onAddHighlight: (blockIndex: Int, start: Int, end: Int, quote: String) -> Unit,
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
            onAdd = { s, e, q -> onAddHighlight(blockIndex, s, e, q) },
            onManage = onManageHighlight,
            modifier = Modifier.padding(horizontal = ReaderHPad, vertical = 10.dp),
        )
        is ReaderBlock.Paragraph -> HighlightableText(
            base = block.text,
            style = bodyStyle,
            highlights = highlights,
            onAdd = { s, e, q -> onAddHighlight(blockIndex, s, e, q) },
            onManage = onManageHighlight,
            modifier = Modifier.padding(horizontal = ReaderHPad, vertical = 8.dp),
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
                onAdd = { s, e, q -> onAddHighlight(blockIndex, s, e, q) },
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
 * Body text that can be highlighted. Long-press a sentence to highlight it; long-press
 * an existing highlight to manage it. Links keep working because they are rendered as
 * their own interactive regions inside the text.
 */
@Composable
private fun HighlightableText(
    base: AnnotatedString,
    style: TextStyle,
    highlights: List<HighlightEntity>,
    onAdd: (start: Int, end: Int, quote: String) -> Unit,
    onManage: (HighlightEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val plain = base.text
    val rendered = remember(base, highlights) { applyHighlights(base, highlights) }
    val layoutState = remember { mutableStateOf<TextLayoutResult?>(null) }
    val currentAdd by rememberUpdatedState(onAdd)
    val currentManage by rememberUpdatedState(onManage)
    val currentHighlights by rememberUpdatedState(highlights)
    Text(
        text = rendered,
        style = style,
        onTextLayout = { layoutState.value = it },
        modifier = modifier.pointerInput(plain) {
            detectTapGestures(
                onLongPress = { pos ->
                    val layout = layoutState.value ?: return@detectTapGestures
                    val offset = layout.getOffsetForPosition(pos).coerceIn(0, plain.length)
                    val hit = currentHighlights.firstOrNull { offset >= it.startOffset && offset < it.endOffset }
                    if (hit != null) {
                        currentManage(hit)
                    } else {
                        sentenceRangeAt(plain, offset)?.let { r ->
                            currentAdd(r.first, r.last + 1, plain.substring(r.first, r.last + 1))
                        }
                    }
                },
            )
        },
    )
}

@Composable
private fun HighlightSheet(
    highlight: HighlightEntity,
    onColor: (Int) -> Unit,
    onSaveNote: (String?) -> Unit,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
                Spacer(Modifier.weight(1f))
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
    onFontScale: (Float) -> Unit,
    onReaderFont: (ReaderFont) -> Unit,
    onReaderTheme: (ReaderTheme) -> Unit,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = readerFont == ReaderFont.SERIF, onClick = { onReaderFont(ReaderFont.SERIF) }, label = { Text("Serif") })
                FilterChip(selected = readerFont == ReaderFont.SANS, onClick = { onReaderFont(ReaderFont.SANS) }, label = { Text("Sans") })
            }
            Spacer(Modifier.height(12.dp))
            Text("Theme", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = readerTheme == ReaderTheme.DEFAULT, onClick = { onReaderTheme(ReaderTheme.DEFAULT) }, label = { Text("Default") })
                FilterChip(selected = readerTheme == ReaderTheme.SEPIA, onClick = { onReaderTheme(ReaderTheme.SEPIA) }, label = { Text("Sepia") })
                FilterChip(selected = readerTheme == ReaderTheme.BLACK, onClick = { onReaderTheme(ReaderTheme.BLACK) }, label = { Text("Black") })
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
