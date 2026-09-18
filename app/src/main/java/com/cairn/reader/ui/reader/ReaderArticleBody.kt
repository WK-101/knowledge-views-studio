@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.reader

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cairn.reader.data.db.HighlightEntity
import com.cairn.reader.data.prefs.ReaderFont
import com.cairn.reader.data.prefs.ReaderTheme
import com.cairn.reader.domain.transcript.TranscriptProse
import com.cairn.reader.ui.transcript.TranscriptAnnotationView
import com.cairn.reader.ui.transcript.TranscriptSelectionInfo
import com.cairn.reader.ui.transcript.transcriptBodyItem
import com.cairn.reader.ui.components.CollectionMembershipSheet
import com.cairn.reader.ui.components.TagEditorSheet
import com.cairn.reader.ui.theme.InterFamily
import com.cairn.reader.ui.theme.ReadingSerif
import com.cairn.reader.ui.util.formatAgo
import com.cairn.reader.ui.util.formatDateTime
import com.cairn.reader.ui.util.nextSpeed
import com.cairn.reader.ui.util.speedLabel
import com.cairn.reader.data.db.CacheStatus
import com.cairn.reader.data.db.ExtractStatus
import com.cairn.reader.data.db.ItemType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

@Composable
internal fun ArticleBody(
    padding: PaddingValues,
    state: ReaderUiState,
    palette: ReaderPalette,
    highlights: List<HighlightEntity>,
    fontFamily: FontFamily,
    scale: Float,
    justify: Boolean,
    rendering: Boolean = false,
    onLoadFull: () -> Unit,
    onLoadWithJs: () -> Unit,
    onOpenOriginal: () -> Unit,
    onSaveProgress: (Float) -> Unit,
    onSelectText: (blockIndex: Int, start: Int, end: Int, quote: String, yInWindow: Float) -> Unit,
    onManageHighlight: (HighlightEntity) -> Unit,
    onPlayEpisode: () -> Unit,
    onWatch: () -> Unit = {},
    showImages: Boolean = true,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    onScaleCommit: (Float) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onSaveMedia: (String) -> Unit = {},
    hasPrev: Boolean = false,
    hasNext: Boolean = false,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    lineHeightMult: Float = 1f,
    letterSpacing: Float = 0f,
    paragraphSpacing: Int = 8,
    measure: Int = 0,
    bionic: Boolean = false,
    tapZonePaging: Boolean = false,
    volumeKeyPaging: Boolean = false,
    resumeProgress: Float = 0f,
    inlineTranscript: InlineTranscriptUi? = null,
    // Highlights box: every highlight on the page (article + transcript), shown foldable at the top.
    boxHighlights: List<HighlightEntity> = emptyList(),
    highlightsBoxDefaultExpanded: Boolean = true,
    onCopyHighlight: (HighlightEntity) -> Unit = {},
    onShareHighlight: (HighlightEntity) -> Unit = {},
    onDeleteHighlight: (HighlightEntity) -> Unit = {},
    onSetHighlightColor: (HighlightEntity, Int) -> Unit = { _, _ -> },
    // The selection still awaiting a pill action, so the chosen words stay tinted while it's open.
    activeSelBlock: Int? = null,
    activeSelStart: Int = 0,
    activeSelEnd: Int = 0,
) {
    val data = state.data ?: return
    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = remember(data.html, linkColor, showImages) {
        val all = data.html?.let { HtmlLinearizer.linearize(it, data.url, linkColor) }.orEmpty()
        if (showImages) all else all.filterNot { it is ReaderBlock.Image }
    }
    // Pinch-to-zoom drives a live scale seeded from the saved preference; commit on release.
    var liveScale by remember(scale) { androidx.compose.runtime.mutableFloatStateOf(scale) }
    var zooming by remember { androidx.compose.runtime.mutableStateOf(false) }
    val bodyStyle = TextStyle(
        fontFamily = fontFamily,
        fontSize = (18 * liveScale).sp,
        lineHeight = (30 * liveScale * lineHeightMult).sp,
        letterSpacing = letterSpacing.em,
        color = palette.text,
    )
    // Re-anchor highlights to the current blocks so a re-extracted / re-linearized article still
    // paints them over the right words (stored block/offset anchors drift; the quote is the anchor).
    val byBlock = remember(highlights, blocks) {
        HighlightAnchoring.reanchor(blocks.map { it.highlightText() }, highlights)
    }

    val progress by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total <= 1) 0f else (listState.firstVisibleItemIndex.toFloat() / (total - 1)).coerceIn(0f, 1f)
        }
    }
    val latestProgress = rememberUpdatedState(progress)
    DisposableEffect(Unit) { onDispose { onSaveProgress(latestProgress.value) } }

    // Resume where you left off: once the article's blocks are laid out, jump to the saved
    // position (skip when unstarted or essentially finished, so re-reads start at the top).
    androidx.compose.runtime.LaunchedEffect(data.id, blocks.size) {
        if (resumeProgress in 0.02f..0.97f && blocks.size > 1) {
            val target = (resumeProgress * (blocks.size - 1)).toInt().coerceIn(0, blocks.size - 1)
            runCatching { listState.scrollToItem(target) }
        }
    }

    // Page-turn helpers (opt-in): scroll ~85% of the viewport up or down.
    val pageScope = rememberCoroutineScope()
    fun pageBy(down: Boolean) {
        val vp = (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset)
        val delta = (vp * 0.85f).coerceAtLeast(1f)
        pageScope.launch { listState.animateScrollBy(if (down) delta else -delta) }
    }
    // Register the volume-key handler with the Activity only while volume paging is on and this
    // reader is composed; clear it on dispose so the volume keys behave normally elsewhere.
    if (volumeKeyPaging) {
        DisposableEffect(Unit) {
            ReaderPaging.handler = { down -> pageBy(down); true }
            onDispose { ReaderPaging.handler = null }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .then(if (measure > 0) Modifier.fillMaxHeight().widthIn(max = measure.dp).align(Alignment.CenterHorizontally) else Modifier.fillMaxSize())
                // Pinch-to-zoom text: only two-finger gestures are consumed, so ordinary
                // single-finger scrolling passes straight through. Zoom is accumulated and the
                // real font size only steps in 5% increments once the pinch ratio crosses a
                // threshold — so the text reflows a handful of times, not every frame (smooth),
                // and a floating badge shows the live size. The size commits on release.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var pinched = false
                        var accum = 1f
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.size >= 2) {
                                pinched = true
                                zooming = true
                                accum *= event.calculateZoom()
                                while (accum >= 1.06f && liveScale < 2.6f) {
                                    liveScale = ((liveScale * 20).toInt() / 20f + 0.05f).coerceIn(0.7f, 2.6f); accum /= 1.06f
                                }
                                while (accum <= 0.94f && liveScale > 0.7f) {
                                    liveScale = ((liveScale * 20).toInt() / 20f - 0.05f).coerceIn(0.7f, 2.6f); accum /= 0.94f
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        zooming = false
                        if (pinched) onScaleCommit(liveScale)
                    }
                },
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 48.dp),
        ) {
            item {
                if (showImages && data.leadImage != null) {
                    AsyncImage(
                        model = data.leadImage,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(220.dp).clickable { onImageClick(data.leadImage!!) },
                    )
                }
                Column(Modifier.padding(horizontal = ReaderHPad)) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = data.title,
                        style = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = (28 * liveScale).sp, lineHeight = (34 * liveScale).sp),
                        color = palette.text,
                    )
                    Spacer(Modifier.height(10.dp))
                    val readerCtx = LocalContext.current
                    val meta = buildList {
                        data.siteName?.let { add(it) }
                        data.author?.let { add(it) }
                        if (data.readingMinutes > 0) add("${data.readingMinutes} min read")
                        // Absolute published date + time, honoring the device's 12/24-hour clock.
                        formatDateTime(readerCtx, data.publishedAt).takeIf { it.isNotEmpty() }?.let { add(it) }
                        if (CacheStatus.isPermanent(data.cacheStatus)) add("Saved offline")
                        if (data.isArchived) add("Archived")
                    }.joinToString("  ·  ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelMedium, color = palette.secondary)
                    Spacer(Modifier.height(16.dp))
                    when {
                        state.extracting -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(16.dp).width(16.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(if (rendering) "Rendering with JavaScript…" else "Fetching full article…", style = MaterialTheme.typography.labelMedium, color = palette.secondary)
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        data.extractStatus == ExtractStatus.FAILED.raw -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onLoadFull) { Text(stringResource(R.string.retry)) }
                                OutlinedButton(onClick = onLoadWithJs) {
                                    Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.load_with_javascript))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.showing_the_summary_the_full_article), style = MaterialTheme.typography.labelSmall, color = palette.secondary)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (data.enclosureUrl != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = onPlayEpisode) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.play_episode))
                            }
                            OutlinedButton(onClick = { onSaveMedia(data.enclosureUrl!!) }) {
                                Icon(Icons.Outlined.DownloadForOffline, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.save))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    if (data.type == ItemType.VIDEO.name) {
                        OutlinedButton(onClick = onWatch) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.watch_video))
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    HorizontalDivider(color = palette.secondary.copy(alpha = 0.25f))
                    Spacer(Modifier.height(12.dp))
                }
            }

            // Highlights on this page — foldable, only when there are any (article + transcript).
            if (boxHighlights.isNotEmpty()) {
                item(key = "highlights_box") {
                    HighlightsBox(
                        highlights = boxHighlights,
                        palette = palette,
                        defaultExpanded = highlightsBoxDefaultExpanded,
                        onManage = onManageHighlight,
                        onSetColor = onSetHighlightColor,
                        onCopy = onCopyHighlight,
                        onShare = onShareHighlight,
                        onDelete = onDeleteHighlight,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            if (blocks.isEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = ReaderHPad)) {
                        Text(stringResource(R.string.no_readable_content_was_saved_for), style = bodyStyle, color = palette.secondary)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onLoadWithJs) {
                                Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.load_with_javascript))
                            }
                            OutlinedButton(onClick = onOpenOriginal) { Text(stringResource(R.string.open_original)) }
                        }
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
                    onImageClick = onImageClick,
                    paragraphSpacing = paragraphSpacing,
                    bionic = bionic,
                    selStart = if (activeSelBlock == index) activeSelStart else 0,
                    selEnd = if (activeSelBlock == index) activeSelEnd else 0,
                )
            }
            // Inline transcript — rendered right here in the article pane so it uses the very same
            // typography (font, size, line height, theme/background, justify) as the article above.
            if (inlineTranscript != null) {
                inlineTranscriptSection(inlineTranscript, bodyStyle, palette, justify, paragraphSpacing)
            }
            // Flow to the next/previous article without going back to the list.
            if (hasPrev || hasNext) {
                item {
                    Column(Modifier.padding(horizontal = ReaderHPad, vertical = 28.dp)) {
                        HorizontalDivider(color = palette.secondary.copy(alpha = 0.25f))
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onPrev, enabled = hasPrev, modifier = Modifier.weight(1f)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.previous))
                            }
                            OutlinedButton(onClick = onNext, enabled = hasNext, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.next)); Spacer(Modifier.width(6.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
        // Tap-zone paging (opt-in): narrow strips at the left/right edges page up/down on tap.
        if (tapZonePaging) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(44.dp)
                    .pointerInput(Unit) { detectTapGestures { pageBy(down = false) } },
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(44.dp)
                    .pointerInput(Unit) { detectTapGestures { pageBy(down = true) } },
            )
        }
        // A floating badge shows the live text size while pinching, then fades out.
        androidx.compose.animation.AnimatedVisibility(
            visible = zooming,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Surface(color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f), shape = RoundedCornerShape(18.dp)) {
                Text(
                    "${(liveScale * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** Bold the leading ~40% of every word — a bionic-reading aid that guides the eye. Preserves the
 *  source string's existing spans (links, emphasis) and overlays bold on word prefixes. */
private fun bionicize(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.AnnotatedString {
    val s = text.text
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        var i = 0
        while (i < s.length) {
            while (i < s.length && !s[i].isLetter()) i++
            val start = i
            while (i < s.length && s[i].isLetter()) i++
            if (i > start) {
                val boldLen = kotlin.math.ceil((i - start) * 0.4).toInt().coerceAtLeast(1)
                addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold), start, start + boldLen)
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
    onSelectText: (blockIndex: Int, start: Int, end: Int, quote: String, yInWindow: Float) -> Unit,
    onManageHighlight: (HighlightEntity) -> Unit,
    onImageClick: (String) -> Unit = {},
    paragraphSpacing: Int = 9,
    bionic: Boolean = false,
    selStart: Int = 0,
    selEnd: Int = 0,
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
            onSelect = { s, e, q, y -> onSelectText(blockIndex, s, e, q, y) },
            onManage = onManageHighlight,
            modifier = Modifier.padding(horizontal = ReaderHPad, vertical = 10.dp),
            selStart = selStart,
            selEnd = selEnd,
        )
        is ReaderBlock.Paragraph -> HighlightableText(
            base = if (bionic) bionicize(block.text) else block.text,
            style = bodyStyle.copy(textAlign = if (justify) TextAlign.Justify else TextAlign.Start),
            highlights = highlights,
            onSelect = { s, e, q, y -> onSelectText(blockIndex, s, e, q, y) },
            onManage = onManageHighlight,
            modifier = Modifier.padding(horizontal = ReaderHPad, vertical = paragraphSpacing.dp),
            selStart = selStart,
            selEnd = selEnd,
        )
        is ReaderBlock.Image -> Column(Modifier.padding(vertical = 10.dp)) {
            AsyncImage(
                model = block.url,
                contentDescription = block.caption,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().padding(horizontal = ReaderHPad).clip(RoundedCornerShape(12.dp))
                    .clickable { onImageClick(block.url) },
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
                onSelect = { s, e, q, y -> onSelectText(blockIndex, s, e, q, y) },
                onManage = onManageHighlight,
                selStart = selStart,
                selEnd = selEnd,
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
    onSelect: (start: Int, end: Int, quote: String, yInWindow: Float) -> Unit,
    onManage: (HighlightEntity) -> Unit,
    modifier: Modifier = Modifier,
    // The still-pending selection for this block (0..0 = none). Drives the tint so the selected
    // words stay visibly highlighted while the action pill is open, until the pill is dismissed.
    selStart: Int = 0,
    selEnd: Int = 0,
) {
    val plain = base.text
    val layoutState = remember { mutableStateOf<TextLayoutResult?>(null) }
    val currentSelect by rememberUpdatedState(onSelect)
    val currentManage by rememberUpdatedState(onManage)
    val currentHighlights by rememberUpdatedState(highlights)
    val selColor = MaterialTheme.colorScheme.primary
    // Where this text block sits in the window, so the selection toolbar can float beside the words.
    var topInWindow by remember { mutableStateOf(0f) }

    // Live drag selection: long-press anchors on a word, drag extends across words.
    var anchor by remember { mutableStateOf<Int?>(null) }
    var focus by remember { mutableStateOf<Int?>(null) }

    val rendered = remember(base, highlights, anchor, focus, selStart, selEnd) {
        val withHl = applyHighlights(base, highlights)
        val a = anchor; val f = focus
        when {
            // Live drag in progress — track the finger.
            a != null && f != null && a != f -> buildAnnotatedString {
                append(withHl)
                addStyle(SpanStyle(background = selColor.copy(alpha = 0.28f)), minOf(a, f), maxOf(a, f))
            }
            // Drag ended but the pill is still open — keep the committed selection tinted.
            selStart in 0 until selEnd && selEnd <= withHl.length -> buildAnnotatedString {
                append(withHl)
                addStyle(SpanStyle(background = selColor.copy(alpha = 0.28f)), selStart, selEnd)
            }
            else -> withHl
        }
    }

    fun offsetAt(pos: androidx.compose.ui.geometry.Offset): Int =
        (layoutState.value?.getOffsetForPosition(pos) ?: 0).coerceIn(0, plain.length)

    Text(
        text = rendered,
        style = style,
        onTextLayout = { layoutState.value = it },
        modifier = modifier
            .onGloballyPositioned { topInWindow = it.positionInWindow().y }
            .pointerInput(plain) {
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
                        if (e > s) {
                            val boxTop = runCatching { layoutState.value?.getBoundingBox(s)?.top ?: 0f }.getOrDefault(0f)
                            currentSelect(s, e, plain.substring(s, e), topInWindow + boxTop)
                        }
                    },
                )
            },
    )
}

/**
 * The text-selection toolbar as a compact floating pill anchored just above the selection —
 * the pattern modern reading apps use (Apple Books, Medium, Matter): highlight colour dots then
 * Copy / Define / Search / Share. Rendered in a non-focusable [Popup] so it floats over the
 * article without pulling the reader out of full-screen (a modal sheet's own window did).
 */
@Composable
internal fun SelectionPill(
    yInWindow: Float,
    onHighlight: (Int) -> Unit,
    onSearch: () -> Unit,
    onDefine: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val above = with(density) { 56.dp.toPx() }
    val minY = with(density) { 96.dp.toPx() }
    val rawAbove = yInWindow - above
    // Above the selection normally; if that would tuck under the top bar, drop just below it.
    val y = (if (rawAbove < minY) yInWindow + with(density) { 40.dp.toPx() } else rawAbove)
    val yPx = y.toInt().coerceAtLeast(with(density) { 8.dp.toPx() }.toInt())

    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, yPx),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HighlightColors.all.forEach { c ->
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { onHighlight(c) },
                    )
                }
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .size(width = 1.dp, height = 24.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                PillAction(Icons.Outlined.ContentCopy, "Copy", onCopy)
                PillAction(Icons.Outlined.MenuBook, "Define", onDefine)
                PillAction(Icons.Outlined.Search, "Search", onSearch)
                PillAction(Icons.Outlined.IosShare, "Share", onShare)
            }
        }
    }
}

/** One compact icon button in the selection pill. */
@Composable
private fun PillAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
    }
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

// -- Inline transcript section ------------------------------------------------

/** Everything the reader needs to render the transcript inline in the article pane. */
internal class InlineTranscriptUi(
    val state: ReaderViewModel.TranscriptState,
    val generating: Float?,
    val prose: TranscriptProse,
    val annotations: List<TranscriptAnnotationView>,
    val activeRange: IntRange?,
    val saved: Boolean,
    val accent: Color,
    val onSeekMs: (Long) -> Unit,
    val onSelect: (TranscriptSelectionInfo) -> Unit,
    val onManage: (String) -> Unit,
    val onGenerate: () -> Unit,
    val onOpenSave: () -> Unit,
    // The still-pending transcript selection (global char offsets; 0..0 = none), kept tinted while
    // the action pill is open.
    val selStart: Int = 0,
    val selEnd: Int = 0,
)

/** Emit the transcript as a section of the article's LazyColumn, styled with the reader's own
 *  [bodyStyle]/[justify]/[paragraphSpacing] so it inherits every reading setting. */
private fun LazyListScope.inlineTranscriptSection(
    t: InlineTranscriptUi,
    bodyStyle: TextStyle,
    palette: ReaderPalette,
    justify: Boolean,
    paragraphSpacing: Int,
) {
    item {
        Column(Modifier.padding(horizontal = ReaderHPad)) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = palette.secondary.copy(alpha = 0.25f))
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.transcript),
                    style = bodyStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = bodyStyle.fontSize * 1.3f),
                    color = palette.text,
                    modifier = Modifier.weight(1f),
                )
                val ready = t.state.onDeviceSupported && t.state.onDeviceModelReady
                if (ready && t.generating == null && !t.state.loading) {
                    IconButton(onClick = t.onGenerate) {
                        Icon(Icons.Outlined.GraphicEq, contentDescription = stringResource(R.string.transcript_generate), tint = palette.secondary)
                    }
                }
                if (t.state.loaded && !t.state.unavailable) {
                    IconButton(onClick = t.onOpenSave) {
                        Icon(
                            if (t.saved) Icons.Filled.Bookmark else Icons.Outlined.Bookmark,
                            contentDescription = stringResource(R.string.transcript_save_options),
                            tint = if (t.saved) t.accent else palette.secondary,
                        )
                    }
                }
            }
            val prov = when (t.state.provenance) {
                com.cairn.reader.domain.transcript.TranscriptSourceKind.YOUTUBE_CAPTIONS -> stringResource(R.string.transcript_source_youtube)
                com.cairn.reader.domain.transcript.TranscriptSourceKind.PODCAST_TRANSCRIPT -> stringResource(R.string.transcript_source_podcast)
                com.cairn.reader.domain.transcript.TranscriptSourceKind.CAPTION_FILE -> stringResource(R.string.transcript_source_file)
                com.cairn.reader.domain.transcript.TranscriptSourceKind.ON_DEVICE -> stringResource(R.string.transcript_source_ondevice)
                com.cairn.reader.domain.transcript.TranscriptSourceKind.UNKNOWN -> null
            }
            if (prov != null && t.state.loaded && !t.state.unavailable) {
                Text(prov, style = MaterialTheme.typography.labelMedium, color = palette.secondary)
            }
            if (t.generating != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { t.generating }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    when {
        t.state.loading -> item {
            Row(Modifier.fillMaxWidth().padding(horizontal = ReaderHPad, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.transcript), style = MaterialTheme.typography.labelMedium, color = palette.secondary)
            }
        }
        t.state.unavailable -> item {
            Column(Modifier.padding(horizontal = ReaderHPad, vertical = 8.dp)) {
                Text(stringResource(R.string.transcript_unavailable_body), style = bodyStyle.copy(fontSize = bodyStyle.fontSize * 0.9f), color = palette.secondary)
                Spacer(Modifier.height(10.dp))
                val body = when {
                    !t.state.onDeviceSupported -> stringResource(R.string.transcript_ondevice_unsupported)
                    !t.state.onDeviceModelReady -> stringResource(R.string.transcript_ondevice_needs_model)
                    else -> stringResource(R.string.transcript_ondevice_ready)
                }
                Text(body, style = MaterialTheme.typography.bodySmall, color = palette.secondary)
                if (t.state.onDeviceSupported && t.state.onDeviceModelReady && t.generating == null) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = t.onGenerate) {
                        Icon(Icons.Outlined.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.transcript_generate))
                    }
                }
                if (t.state.generateError) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.transcript_generate_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        else -> {
            item {
                Text(
                    stringResource(R.string.transcript_read_hint),
                    style = MaterialTheme.typography.bodySmall, color = palette.secondary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = ReaderHPad, vertical = 4.dp),
                )
            }
            transcriptBodyItem(
                prose = t.prose, annotations = t.annotations, activeRange = t.activeRange,
                bodyStyle = bodyStyle, justify = justify, hPad = ReaderHPad, accent = t.accent,
                onSeekMs = t.onSeekMs, onSelect = t.onSelect, onManage = t.onManage,
                selStart = t.selStart, selEnd = t.selEnd,
            )
        }
    }
}
