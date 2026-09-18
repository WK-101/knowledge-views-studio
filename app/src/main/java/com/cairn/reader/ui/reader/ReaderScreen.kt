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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Subtitles
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

internal val ReaderHPad = 22.dp

internal fun readerFontFamily(font: ReaderFont): FontFamily = when (font) {
    ReaderFont.SERIF -> ReadingSerif
    ReaderFont.SANS -> InterFamily
    ReaderFont.BOOK -> FontFamily.Serif
    ReaderFont.SYSTEM -> FontFamily.Default
    ReaderFont.MONO -> FontFamily.Monospace
}

internal fun readerThemeLabel(theme: ReaderTheme): String = when (theme) {
    ReaderTheme.DEFAULT -> "Default"
    ReaderTheme.PAPER -> "Paper"
    ReaderTheme.SEPIA -> "Sepia"
    ReaderTheme.GRAY -> "Gray"
    ReaderTheme.NIGHT -> "Night"
    ReaderTheme.BLACK -> "Black"
}

internal data class ReaderPalette(val background: Color, val text: Color, val secondary: Color)

@Composable
internal fun readerPalette(theme: ReaderTheme): ReaderPalette {
    val scheme = MaterialTheme.colorScheme
    return when (theme) {
        ReaderTheme.DEFAULT -> ReaderPalette(scheme.surface, scheme.onSurface, scheme.onSurfaceVariant)
        ReaderTheme.PAPER -> ReaderPalette(Color(0xFFFBF7EF), Color(0xFF2A2620), Color(0xFF6B6357))
        ReaderTheme.SEPIA -> ReaderPalette(Color(0xFFF4ECD8), Color(0xFF463A28), Color(0xFF7C6C52))
        ReaderTheme.GRAY -> ReaderPalette(Color(0xFF202124), Color(0xFFE3E3E3), Color(0xFF9AA0A6))
        ReaderTheme.NIGHT -> ReaderPalette(Color(0xFF12161C), Color(0xFFCAD3E0), Color(0xFF8595A8))
        ReaderTheme.BLACK -> ReaderPalette(Color(0xFF000000), Color(0xFFE6E6E6), Color(0xFF9C9C9C))
    }
}

@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    onOpenWeb: (String) -> Unit = {},
    onOpenItem: (String) -> Unit = {},
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    val ttsState by viewModel.tts.collectAsStateWithLifecycle()
    val audioState by viewModel.audio.collectAsStateWithLifecycle()
    val savingOffline by viewModel.savingOffline.collectAsStateWithLifecycle()
    val rendering by viewModel.rendering.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val memberCollections by viewModel.memberCollections.collectAsStateWithLifecycle()
    val itemTags by viewModel.itemTags.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val data = state.data
    var showTypography by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showCollections by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    var managed by remember { mutableStateOf<HighlightEntity?>(null) }
    var showRsvp by remember { mutableStateOf(false) }
    var showRelated by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingSelection?>(null) }
    var lookup by remember { mutableStateOf<String?>(null) }
    var lightbox by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    // Inline transcript, shown right in this reading pane so it inherits the reader's typography.
    val transcript by viewModel.transcript.collectAsStateWithLifecycle()
    val transcriptGenerating by viewModel.transcriptGenerating.collectAsStateWithLifecycle()
    val transcriptSaved by viewModel.transcriptSaved.collectAsStateWithLifecycle()
    var transcriptPending by remember { mutableStateOf<TranscriptSelectionInfo?>(null) }
    var transcriptManageId by remember { mutableStateOf<String?>(null) }
    var showTranscriptSave by remember { mutableStateOf(false) }
    // Article highlights are block-anchored; transcript annotations are time-anchored ("t:" selector).
    // Keep them apart so neither paints over the other's surface.
    val articleHighlights = remember(highlights) { highlights.filterNot { it.startSelector?.startsWith("t:") == true } }
    val transcriptAnnotations = remember(highlights) {
        highlights.filter { it.startSelector?.startsWith("t:") == true }
            .map { TranscriptAnnotationView(it.id, it.startOffset, it.endOffset, it.color, it.quote, it.note) }
    }
    val transcriptProse = remember(transcript.cues) { TranscriptProse.from(transcript.cues) }

    val palette = readerPalette(prefs.readerTheme)
    val transcriptAccent = MaterialTheme.colorScheme.primary

    // Immersive / full-screen reading: the chrome auto-hides on scroll and reappears near the
    // top or when scrolling up. A shared list state lets the screen watch scroll direction.
    val listState = rememberLazyListState()
    val immersive = prefs.readerImmersive
    // App-wide full screen (shared by the whole app; CairnRoot keeps it applied off-reader too).
    val fullScreen = prefs.appFullScreen
    var barsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(fullScreen) { barsVisible = !fullScreen }
    LaunchedEffect(listState, immersive, fullScreen) {
        if (!immersive && !fullScreen) { barsVisible = true; return@LaunchedEffect }
        var li = listState.firstVisibleItemIndex
        var lo = listState.firstVisibleItemScrollOffset
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (i, o) ->
                when {
                    i == 0 && o < 16 -> barsVisible = true
                    i > li || (i == li && o > lo + 8) -> barsVisible = false
                    i < li || (i == li && o < lo - 8) -> barsVisible = true
                }
                li = i; lo = o
            }
    }
    // System-bar hiding: full-screen hides the Android status/nav bars for the whole read;
    // immersive hides them in step with the app chrome, so scrolling down gives the entire
    // display to the text and scrolling back up brings everything back. Restore on leave.
    val window = (context as? android.app.Activity)?.window
    val hideSystemBars = fullScreen || (immersive && !barsVisible)
    // A ModalBottomSheet / Dialog opens in its own window that shows the system bars; when it
    // dismisses the reader must reclaim full-screen. Re-run whenever such an overlay opens or
    // closes so immersive mode is re-asserted and doesn't get stuck "out of full screen".
    val overlayOpen = lookup != null || pending != null || showTypography || showCollections || showTags || managed != null || lightbox != null || showRsvp || showRelated || showSummary
    LaunchedEffect(hideSystemBars, window, overlayOpen) {
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) } ?: return@LaunchedEffect
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hideSystemBars) controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        else controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
    // Leaving the reader restores the bars — UNLESS app-wide full screen is on, in which case the
    // whole app stays full-screen and CairnRoot keeps the bars hidden.
    DisposableEffect(window, fullScreen) {
        onDispose {
            if (!fullScreen) {
                window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
                    ?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun openOriginal() {
        val url = data?.url ?: return
        onOpenWeb(url)
    }

    // Video items (e.g. YouTube) open in whatever app handles the watch URL, not the reader WebView.
    fun watchVideo() {
        val url = data?.url ?: return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    fun shareText(text: String, subject: String?) {
        com.cairn.reader.util.shareText(context, text, subject = subject, chooser = null)
    }

    // Share a downloaded image/media file out via the FileProvider (also the pre-Android-10 save path).
    fun shareMediaUri(uri: android.net.Uri, mime: String) {
        com.cairn.reader.util.shareStream(context, uri, mime, chooser = null)
    }

    // Share an exported file (EPUB / HTML snapshot) out via the FileProvider.
    fun shareFile(file: java.io.File, mime: String) {
        com.cairn.reader.util.shareFile(context, file, mime, subject = data?.title, chooser = null)
    }

    // Share an imported PDF's actual file out to other apps via the FileProvider.
    fun sharePdf() {
        val path = data?.pdfPath ?: return
        com.cairn.reader.util.shareFile(context, java.io.File(path), "application/pdf", subject = data?.title, chooser = null)
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
          androidx.compose.animation.AnimatedVisibility(
              visible = barsVisible,
              enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
              exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
          ) {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = palette.text) }
                },
                actions = {
                    if (data?.type != ItemType.PDF.name) {
                        IconButton(onClick = { showTypography = true }) {
                            Icon(Icons.Outlined.FormatSize, contentDescription = stringResource(R.string.text_options), tint = palette.text)
                        }
                    }
                    IconButton(onClick = viewModel::toggleStar) {
                        Icon(
                            imageVector = if (data?.isStarred == true) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            // Announce the resulting state so TalkBack reads e.g. "Starred" vs "Not starred".
                            contentDescription = if (data?.isStarred == true) "Starred" else "Not starred",
                            tint = if (data?.isStarred == true) MaterialTheme.colorScheme.tertiary else palette.text,
                        )
                    }
                    IconButton(onClick = viewModel::toggleSave) {
                        Icon(
                            imageVector = if (data?.isReadLater == true) Icons.Filled.Bookmark else Icons.Outlined.Bookmark,
                            contentDescription = if (data?.isReadLater == true) "Saved for later" else "Save for later",
                            tint = if (data?.isReadLater == true) MaterialTheme.colorScheme.tertiary else palette.text,
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more), tint = palette.text)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            if (data?.type != ItemType.PDF.name) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.display_text)) },
                                    leadingIcon = { Icon(Icons.Outlined.FormatSize, contentDescription = null) },
                                    onClick = { showMenu = false; showTypography = true },
                                )
                            }
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
                            if (data?.type != ItemType.PDF.name) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.listen)) },
                                    leadingIcon = { Icon(Icons.Outlined.Headphones, contentDescription = null) },
                                    onClick = { showMenu = false; viewModel.toggleListen() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.speed_read)) },
                                    leadingIcon = { Icon(Icons.Outlined.Bolt, contentDescription = null) },
                                    onClick = { showMenu = false; showRsvp = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.summarize)) },
                                    leadingIcon = { Icon(Icons.Outlined.Notes, contentDescription = null) },
                                    onClick = { showMenu = false; viewModel.loadSummary(); showSummary = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.related_articles)) },
                                    leadingIcon = { Icon(Icons.Outlined.Hub, contentDescription = null) },
                                    onClick = { showMenu = false; viewModel.loadRelated(); showRelated = true },
                                )
                            }
                            // Transcript: only for playable media (podcasts, videos) where captions
                            // or on-device speech-to-text can produce a timed, highlightable transcript.
                            if (data?.type == ItemType.AUDIO.name || data?.type == ItemType.VIDEO.name) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.transcript)) },
                                    leadingIcon = { Icon(Icons.Outlined.Subtitles, contentDescription = null) },
                                    trailingIcon = { if (transcript.visible) Icon(Icons.Outlined.Check, contentDescription = null) },
                                    onClick = { showMenu = false; viewModel.toggleTranscript() },
                                )
                                // On-device speech-to-text — reachable here whether or not captions loaded.
                                if (transcript.visible) {
                                    val odReady = transcript.onDeviceSupported && transcript.onDeviceModelReady
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(stringResource(R.string.transcript_generate))
                                                if (!odReady) Text(
                                                    stringResource(if (!transcript.onDeviceSupported) R.string.transcript_ondevice_unsupported else R.string.transcript_ondevice_needs_model),
                                                    style = MaterialTheme.typography.labelSmall, color = palette.secondary,
                                                )
                                            }
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.GraphicEq, contentDescription = null) },
                                        enabled = odReady && transcriptGenerating == null,
                                        onClick = { showMenu = false; viewModel.generateTranscriptOnDevice() },
                                    )
                                }
                            }
                            val permanent = CacheStatus.isPermanent(data?.cacheStatus)
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
                            if (data?.type != ItemType.PDF.name) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_as_pdf)) },
                                    leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null) },
                                    onClick = {
                                        showMenu = false
                                        com.cairn.reader.ui.util.PdfExport.printArticle(context, data?.title.orEmpty(), data?.html)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_as_markdown)) },
                                    leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.exportMarkdown { md -> shareText(md, data?.title) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.send_to_kindle_epub)) },
                                    leadingIcon = { Icon(Icons.Outlined.MenuBook, contentDescription = null) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.exportEpub { file -> shareFile(file, "application/epub+zip") }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.save_full_page_snapshot)) },
                                    leadingIcon = { Icon(Icons.Outlined.Save, contentDescription = null) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.exportSnapshot { file -> shareFile(file, "text/html") }
                                    },
                                )
                            }
                            data?.commentsUrl?.takeIf { it.isNotBlank() }?.let { commentsUrl ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.open_comments)) },
                                    leadingIcon = { Icon(Icons.Outlined.Forum, contentDescription = null) },
                                    onClick = { showMenu = false; onOpenWeb(commentsUrl) },
                                )
                            }
                            if (data?.type != ItemType.PDF.name) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.open_original)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                                    onClick = { showMenu = false; openOriginal() },
                                )
                                // JS-render fallback (collector P5): for single-page-app articles whose
                                // plain fetch returns an empty shell, render the page and re-extract.
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.load_with_javascript)) },
                                    leadingIcon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                                    onClick = { showMenu = false; viewModel.loadWithJavaScript() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (highlights.isEmpty()) "Share article" else "Export highlights") },
                                leadingIcon = { Icon(Icons.Outlined.IosShare, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    when {
                                        data?.type == ItemType.PDF.name -> sharePdf()
                                        highlights.isEmpty() -> shareText(data?.url.orEmpty(), data?.title)
                                        else -> viewModel.exportHighlights { md -> shareText(md, data?.title?.let { "Highlights — $it" }) }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { showMenu = false; confirmDelete = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.background),
            )
          }
        },
        bottomBar = {
          androidx.compose.animation.AnimatedVisibility(
              visible = barsVisible,
              enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
              exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
          ) {
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
                        onShare = { if (data.type == ItemType.PDF.name) sharePdf() else shareText(data.url, data.title) },
                        onUnread = { viewModel.markUnread(); onBack() },
                        onStar = viewModel::toggleStar,
                        onTag = { showTags = true },
                        onMore = { showMenu = true },
                    )
                }
            }
          }
        },
    ) { padding ->
        when {
            state.loading -> Centered(padding) { CircularProgressIndicator() }
            data == null -> Centered(padding) { Text(stringResource(R.string.this_article_couldn_t_be_loaded), color = palette.secondary) }
            data.type == ItemType.PDF.name -> PdfView(padding = padding, path = data.pdfPath, background = palette.background)
            else -> ArticleBody(
                padding = padding,
                state = state,
                palette = palette,
                highlights = articleHighlights,
                fontFamily = readerFontFamily(prefs.readerFont),
                scale = prefs.readerFontScale,
                justify = prefs.readerJustify,
                showImages = prefs.readerShowImages,
                listState = listState,
                rendering = rendering,
                onLoadFull = viewModel::loadFullArticle,
                onLoadWithJs = viewModel::loadWithJavaScript,
                onOpenOriginal = ::openOriginal,
                onSaveProgress = viewModel::setProgress,
                onSelectText = { b, s, e, q, y -> pending = PendingSelection(b, s, e, q, y) },
                // Keep the selected passage tinted while the pill is open (cleared when pending clears).
                activeSelBlock = pending?.blockIndex,
                activeSelStart = pending?.start ?: 0,
                activeSelEnd = pending?.end ?: 0,
                // Route to the right manage sheet: transcript excerpts ("t:") vs article passages.
                onManageHighlight = { h -> if (h.startSelector?.startsWith("t:") == true) transcriptManageId = h.id else managed = h },
                onPlayEpisode = viewModel::playEpisode,
                onWatch = ::watchVideo,
                onScaleCommit = viewModel::setFontScale,
                onImageClick = { url -> lightbox = url },
                onSaveMedia = { url -> if (viewModel.canSaveMediaDirectly) viewModel.saveMedia(url) else viewModel.shareMedia(url) { uri, mime -> shareMediaUri(uri, mime) } },
                hasPrev = viewModel.prevId != null,
                hasNext = viewModel.nextId != null,
                onPrev = { viewModel.prevId?.let(onOpenItem) },
                onNext = { viewModel.nextId?.let(onOpenItem) },
                lineHeightMult = prefs.readerLineHeight,
                letterSpacing = prefs.readerLetterSpacing,
                paragraphSpacing = prefs.readerParagraphSpacing,
                measure = prefs.readerMeasure,
                bionic = prefs.bionicReading,
                tapZonePaging = prefs.tapZonePaging,
                volumeKeyPaging = prefs.volumeKeyPaging,
                resumeProgress = data.readProgress,
                inlineTranscript = if (transcript.visible) InlineTranscriptUi(
                    state = transcript,
                    generating = transcriptGenerating,
                    prose = transcriptProse,
                    annotations = transcriptAnnotations,
                    activeRange = if (audioState.active && transcript.isAudio) transcriptProse.charRangeAtTime(audioState.positionMs.toLong()) else null,
                    saved = transcriptSaved,
                    accent = transcriptAccent,
                    selStart = transcriptPending?.globalStart ?: 0,
                    selEnd = transcriptPending?.globalEnd ?: 0,
                    onSeekMs = { ms ->
                        val yt = transcript.youtubeId
                        if (yt != null) runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$yt&t=${ms / 1000}s")))
                        } else viewModel.seekTranscript(ms)
                    },
                    onSelect = { transcriptPending = it },
                    onManage = { transcriptManageId = it },
                    onGenerate = viewModel::generateTranscriptOnDevice,
                    onOpenSave = { showTranscriptSave = true },
                ) else null,
                // Highlights box — every highlight on the page (article passages + transcript excerpts).
                boxHighlights = highlights,
                highlightsBoxDefaultExpanded = prefs.highlightsBoxExpanded,
                onCopyHighlight = { clipboard.setText(AnnotatedString(it.quote.trim())) },
                onShareHighlight = { shareText(it.quote.trim(), data?.title) },
                onDeleteHighlight = { viewModel.removeHighlight(it.id) },
                onSetHighlightColor = { h, c -> viewModel.setHighlightColor(h.id, c) },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.move_to_trash_2)) },
            text = { Text(stringResource(R.string.it_s_moved_to_the_trash)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deleteArticle(onBack) }) {
                    Text(stringResource(R.string.move_to_trash), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showTypography && data != null) {
        TypographySheet(
            fontScale = prefs.readerFontScale,
            readerFont = prefs.readerFont,
            readerTheme = prefs.readerTheme,
            justify = prefs.readerJustify,
            showImages = prefs.readerShowImages,
            immersive = prefs.readerImmersive,
            fullScreen = prefs.appFullScreen,
            lineHeight = prefs.readerLineHeight,
            letterSpacing = prefs.readerLetterSpacing,
            paragraphSpacing = prefs.readerParagraphSpacing,
            measure = prefs.readerMeasure,
            bionic = prefs.bionicReading,
            onFontScale = viewModel::setFontScale,
            onReaderFont = viewModel::setReaderFont,
            onReaderTheme = viewModel::setReaderTheme,
            onJustify = viewModel::setReaderJustify,
            onShowImages = viewModel::setReaderShowImages,
            onImmersive = viewModel::setReaderImmersive,
            onFullScreen = viewModel::setAppFullScreen,
            onLineHeight = viewModel::setReaderLineHeight,
            onLetterSpacing = viewModel::setReaderLetterSpacing,
            onParagraphSpacing = viewModel::setReaderParagraphSpacing,
            onMeasure = viewModel::setReaderMeasure,
            onBionic = viewModel::setBionicReading,
            onDismiss = { showTypography = false },
        )
    }

    if (showSummary) {
        val summary by viewModel.summary.collectAsStateWithLifecycle()
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showSummary = false }) {
            com.cairn.reader.ui.KeepImmersiveWhileOpen(hideSystemBars)
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
                Text(stringResource(R.string.key_points),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                when {
                    summary == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    summary!!.isEmpty() -> Text(stringResource(R.string.not_enough_article_text_to_summarize), style = MaterialTheme.typography.bodyMedium, color = palette.secondary)
                    else -> summary!!.forEach { s ->
                        androidx.compose.foundation.layout.Row(Modifier.padding(vertical = 6.dp)) {
                            Text("•  ", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                            Text(s, style = MaterialTheme.typography.bodyLarge, color = palette.text)
                        }
                    }
                }
                if (summary?.isNotEmpty() == true) {
                    Text(stringResource(R.string.extracted_on_device_from_the_article),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.secondary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }

    if (showRelated) {
        val related by viewModel.related.collectAsStateWithLifecycle()
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showRelated = false }) {
            com.cairn.reader.ui.KeepImmersiveWhileOpen(hideSystemBars)
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(stringResource(R.string.related_articles),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                when {
                    related == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    related!!.isEmpty() -> Text(stringResource(R.string.nothing_closely_related_found_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.secondary,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    else -> related!!.forEach { r ->
                        androidx.compose.foundation.layout.Column(
                            Modifier.fillMaxWidth().clickable { showRelated = false; onOpenItem(r.id) }.padding(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            Text(r.title, style = MaterialTheme.typography.bodyLarge, color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            r.sourceTitle?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = palette.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRsvp && data != null) {
        val plain = remember(data.html) {
            data.html?.let { runCatching { org.jsoup.Jsoup.parse(it).text() }.getOrNull() }?.takeIf { it.isNotBlank() }
                ?: data.title
        }
        RsvpReader(text = plain, onClose = { showRsvp = false })
    }

    if (showCollections && data != null) {
        CollectionMembershipSheet(
            collections = collections,
            membership = memberCollections,
            onToggle = { collectionId, inIt -> viewModel.setInCollection(collectionId, inIt) },
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
            // Editing a highlight must not drop the reader out of full-screen either.
            keepImmersive = hideSystemBars,
            onColor = { viewModel.setHighlightColor(highlight.id, it) },
            onSaveNote = { viewModel.setHighlightNote(highlight.id, it) },
            onCopy = { clipboard.setText(AnnotatedString(highlight.quote.trim())) },
            onShare = { shareText(highlight.quote.trim(), data?.title) },
            onDelete = { viewModel.removeHighlight(highlight.id); managed = null },
            onDismiss = { managed = null },
        )
    }

    // A selection is dismissed the moment the reader scrolls, so the pill never lingers.
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) { pending = null; transcriptPending = null }
    }

    lookup?.let { term ->
        val onlineEnabled by viewModel.dictionaryOnline.collectAsStateWithLifecycle()
        LookupSheet(
            term = term,
            onlineEnabled = onlineEnabled,
            // Keep the reader's full-screen while the definition sheet is open — the sheet's own
            // window would otherwise re-show the system bars (and shunt the article) on focus.
            keepImmersive = hideSystemBars,
            onDefine = { viewModel.define(it) },
            onEnableOnline = { viewModel.setDictionaryOnline(true) },
            onDismiss = { lookup = null },
        )
    }

    lightbox?.let { url ->
        ImageLightbox(
            url = url,
            canSave = viewModel.canSaveMediaDirectly,
            onSave = { viewModel.saveImage(url) },
            onShare = { viewModel.shareMedia(url) { uri, mime -> shareMediaUri(uri, mime) } },
            onDismiss = { lightbox = null },
        )
    }

    pending?.let { sel ->
        SelectionPill(
            yInWindow = sel.yInWindow,
            onHighlight = { color ->
                viewModel.addHighlight(sel.blockIndex, sel.start, sel.end, sel.quote, color)
                pending = null
            },
            onSearch = { webLookup(sel.quote, define = false); pending = null },
            onDefine = { lookup = sel.quote; pending = null },
            onCopy = { clipboard.setText(AnnotatedString(sel.quote.trim())); pending = null },
            onShare = { shareText(sel.quote.trim(), data?.title); pending = null },
            onDismiss = { pending = null },
        )
    }

    // Inline-transcript selection + annotation management + save options (hoisted to the root so the
    // pill/sheets float over the reader like the article's own selection pill).
    // The transcript uses the *same* selection pill as the article body, for a uniform experience.
    transcriptPending?.let { sel ->
        SelectionPill(
            yInWindow = sel.y,
            onHighlight = { color -> viewModel.annotateTranscript(sel.globalStart, sel.globalEnd, sel.startMs, sel.endMs, sel.quote, color); transcriptPending = null },
            onSearch = { webLookup(sel.quote, define = false); transcriptPending = null },
            onDefine = { lookup = sel.quote; transcriptPending = null },
            onCopy = { clipboard.setText(AnnotatedString(sel.quote.trim())); transcriptPending = null },
            onShare = { shareText(sel.quote.trim(), data?.title); transcriptPending = null },
            onDismiss = { transcriptPending = null },
        )
    }

    transcriptManageId?.let { id ->
        val ann = transcriptAnnotations.firstOrNull { it.id == id }
        if (ann == null) transcriptManageId = null
        else com.cairn.reader.ui.transcript.ManageAnnotationSheet(
            currentColor = ann.color, note = ann.note,
            onColor = { viewModel.recolorTranscriptAnnotation(id, it) },
            onNote = { viewModel.noteTranscriptAnnotation(id, it) },
            onRemove = { viewModel.removeTranscriptAnnotation(id); transcriptManageId = null },
            onDismiss = { transcriptManageId = null },
            keepImmersive = hideSystemBars,
        )
    }

    if (showTranscriptSave) {
        com.cairn.reader.ui.transcript.SaveOptionsSheet(
            saved = transcriptSaved,
            annotationCount = transcriptAnnotations.size,
            onToggleWhole = viewModel::toggleSaveTranscriptWhole,
            onDismiss = { showTranscriptSave = false },
            keepImmersive = hideSystemBars,
        )
    }
}

/** A text selection awaiting an action from the contextual menu; [yInWindow] anchors the pill. */
private data class PendingSelection(val blockIndex: Int, val start: Int, val end: Int, val quote: String, val yInWindow: Float = 0f)

/** In-app dictionary + thesaurus for the selected word. */
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
                Text(stringResource(R.string.listening), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                val fraction = if (state.total > 0) (state.index + 1f) / state.total else 0f
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
            TextButton(onClick = { onSpeed(nextSpeed(state.speed)) }) { Text(speedLabel(state.speed)) }
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.stop), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
