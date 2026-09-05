@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.reader

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
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Translate
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
import coil.compose.AsyncImage
import com.cairn.reader.data.db.HighlightEntity
import com.cairn.reader.data.prefs.ReaderFont
import com.cairn.reader.data.prefs.ReaderTheme
import com.cairn.reader.ui.components.CollectionPickerSheet
import com.cairn.reader.ui.components.TagEditorSheet
import com.cairn.reader.ui.theme.InterFamily
import com.cairn.reader.ui.theme.ReadingSerif
import com.cairn.reader.ui.util.formatAgo
import com.cairn.reader.ui.util.formatDateTime
import java.text.BreakIterator
import java.util.Locale

private val ReaderHPad = 22.dp

private fun readerFontFamily(font: ReaderFont): FontFamily = when (font) {
    ReaderFont.SERIF -> ReadingSerif
    ReaderFont.SANS -> InterFamily
    ReaderFont.BOOK -> FontFamily.Serif
    ReaderFont.SYSTEM -> FontFamily.Default
    ReaderFont.MONO -> FontFamily.Monospace
}

private fun readerThemeLabel(theme: ReaderTheme): String = when (theme) {
    ReaderTheme.DEFAULT -> "Default"
    ReaderTheme.PAPER -> "Paper"
    ReaderTheme.SEPIA -> "Sepia"
    ReaderTheme.GRAY -> "Gray"
    ReaderTheme.NIGHT -> "Night"
    ReaderTheme.BLACK -> "Black"
}

private data class ReaderPalette(val background: Color, val text: Color, val secondary: Color)

@Composable
private fun readerPalette(theme: ReaderTheme): ReaderPalette {
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
    var pending by remember { mutableStateOf<PendingSelection?>(null) }
    var lookup by remember { mutableStateOf<String?>(null) }
    var lightbox by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    val palette = readerPalette(prefs.readerTheme)

    // Immersive / full-screen reading: the chrome auto-hides on scroll and reappears near the
    // top or when scrolling up. A shared list state lets the screen watch scroll direction.
    val listState = rememberLazyListState()
    val immersive = prefs.readerImmersive
    val fullScreen = prefs.readerFullScreen
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
    LaunchedEffect(hideSystemBars, window) {
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) } ?: return@LaunchedEffect
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hideSystemBars) controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        else controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(window) {
        onDispose {
            window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
                ?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
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
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
    }

    // Share a downloaded image/media file out via the FileProvider (also the pre-Android-10 save path).
    fun shareMediaUri(uri: android.net.Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
    }

    // Share an imported PDF's actual file out to other apps via the FileProvider.
    fun sharePdf() {
        val path = data?.pdfPath ?: return
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", java.io.File(path),
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                data?.title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, null))
        }
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
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.text) }
                },
                actions = {
                    if (data?.type != "PDF") {
                        IconButton(onClick = { showTypography = true }) {
                            Icon(Icons.Outlined.FormatSize, contentDescription = "Text options", tint = palette.text)
                        }
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
                            if (data?.type != "PDF") {
                                DropdownMenuItem(
                                    text = { Text("Display & text") },
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
                            if (data?.type != "PDF") {
                                DropdownMenuItem(
                                    text = { Text("Listen") },
                                    leadingIcon = { Icon(Icons.Outlined.Headphones, contentDescription = null) },
                                    onClick = { showMenu = false; viewModel.toggleListen() },
                                )
                            }
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
                            if (data?.type != "PDF") {
                                DropdownMenuItem(
                                    text = { Text("Open original") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                                    onClick = { showMenu = false; openOriginal() },
                                )
                                // JS-render fallback (collector P5): for single-page-app articles whose
                                // plain fetch returns an empty shell, render the page and re-extract.
                                DropdownMenuItem(
                                    text = { Text("Load with JavaScript") },
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
                                        data?.type == "PDF" -> sharePdf()
                                        highlights.isEmpty() -> shareText(data?.url.orEmpty(), data?.title)
                                        else -> viewModel.exportHighlights { md -> shareText(md, data?.title?.let { "Highlights — $it" }) }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
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
                        onShare = { if (data.type == "PDF") sharePdf() else shareText(data.url, data.title) },
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
                showImages = prefs.readerShowImages,
                listState = listState,
                rendering = rendering,
                onLoadFull = viewModel::loadFullArticle,
                onLoadWithJs = viewModel::loadWithJavaScript,
                onOpenOriginal = ::openOriginal,
                onSaveProgress = viewModel::setProgress,
                onSelectText = { b, s, e, q, y -> pending = PendingSelection(b, s, e, q, y) },
                onManageHighlight = { managed = it },
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
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Move to Trash?") },
            text = { Text("It's moved to the Trash — hidden from your feeds but kept intact. You can restore it from Trash, or empty the Trash to erase it for good.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deleteArticle(onBack) }) {
                    Text("Move to Trash", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
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
            fullScreen = prefs.readerFullScreen,
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
            onFullScreen = viewModel::setReaderFullScreen,
            onLineHeight = viewModel::setReaderLineHeight,
            onLetterSpacing = viewModel::setReaderLetterSpacing,
            onParagraphSpacing = viewModel::setReaderParagraphSpacing,
            onMeasure = viewModel::setReaderMeasure,
            onBionic = viewModel::setBionicReading,
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

    // A selection is dismissed the moment the reader scrolls, so the pill never lingers.
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) pending = null
    }

    lookup?.let { term ->
        LookupSheet(
            term = term,
            onDefine = { viewModel.define(it) },
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
}

/** A text selection awaiting an action from the contextual menu; [yInWindow] anchors the pill. */
private data class PendingSelection(val blockIndex: Int, val start: Int, val end: Int, val quote: String, val yInWindow: Float = 0f)

@Composable
private fun ArticleBody(
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
    val byBlock = remember(highlights) { highlights.groupBy { it.startSelector?.toIntOrNull() ?: -1 } }

    val progress by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total <= 1) 0f else (listState.firstVisibleItemIndex.toFloat() / (total - 1)).coerceIn(0f, 1f)
        }
    }
    val latestProgress = rememberUpdatedState(progress)
    DisposableEffect(Unit) { onDispose { onSaveProgress(latestProgress.value) } }

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
                                Text(if (rendering) "Rendering with JavaScript…" else "Fetching full article…", style = MaterialTheme.typography.labelMedium, color = palette.secondary)
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        data.extractStatus == "FAILED" -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onLoadFull) { Text("Retry") }
                                OutlinedButton(onClick = onLoadWithJs) {
                                    Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Load with JavaScript")
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Showing the summary — the full article couldn't be fetched. If it's a JavaScript-heavy site, try loading with JavaScript.", style = MaterialTheme.typography.labelSmall, color = palette.secondary)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (data.enclosureUrl != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = onPlayEpisode) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Play episode")
                            }
                            OutlinedButton(onClick = { onSaveMedia(data.enclosureUrl!!) }) {
                                Icon(Icons.Outlined.DownloadForOffline, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Save")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    if (data.type == "VIDEO") {
                        OutlinedButton(onClick = onWatch) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Watch video")
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onLoadWithJs) {
                                Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Load with JavaScript")
                            }
                            OutlinedButton(onClick = onOpenOriginal) { Text("Open original") }
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
                )
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
                                Spacer(Modifier.width(6.dp)); Text("Previous")
                            }
                            OutlinedButton(onClick = onNext, enabled = hasNext, modifier = Modifier.weight(1f)) {
                                Text("Next"); Spacer(Modifier.width(6.dp))
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
        )
        is ReaderBlock.Paragraph -> HighlightableText(
            base = if (bionic) bionicize(block.text) else block.text,
            style = bodyStyle.copy(textAlign = if (justify) TextAlign.Justify else TextAlign.Start),
            highlights = highlights,
            onSelect = { s, e, q, y -> onSelectText(blockIndex, s, e, q, y) },
            onManage = onManageHighlight,
            modifier = Modifier.padding(horizontal = ReaderHPad, vertical = paragraphSpacing.dp),
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
private fun SelectionPill(
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

/** In-app dictionary + thesaurus for the selected word. */
@Composable
private fun LookupSheet(
    term: String,
    onDefine: suspend (String) -> Result<com.cairn.reader.domain.lookup.DictionaryEntry>,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
        ) {
            val entry by produceState<Result<com.cairn.reader.domain.lookup.DictionaryEntry>?>(null, term) {
                value = onDefine(term)
            }
            val result = entry
            when {
                result == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Looking up…", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                result.isSuccess -> {
                    val e = result.getOrThrow()
                    Text(e.word, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                    e.phonetic?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(it, style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(14.dp))
                    e.senses.forEachIndexed { i, s ->
                        Row(Modifier.padding(bottom = 10.dp)) {
                            Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, color = scheme.primary, modifier = Modifier.width(24.dp))
                            Column {
                                if (s.partOfSpeech.isNotBlank()) {
                                    Text(s.partOfSpeech, style = MaterialTheme.typography.labelMedium.copy(fontStyle = FontStyle.Italic), color = scheme.onSurfaceVariant)
                                }
                                Text(s.definition, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                                s.example?.let {
                                    Text("“$it”", style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = scheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (e.synonyms.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Synonyms", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                        Text(e.synonyms.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    }
                    if (e.antonyms.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Antonyms", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                        Text(e.antonyms.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    }
                }
                else -> Text(
                    result.exceptionOrNull()?.message ?: "No definition found.",
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Full-screen image viewer: pinch/drag to zoom and pan, with Save and Share. */
@Composable
private fun ImageLightbox(
    url: String,
    canSave: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale <= 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (canSave) {
                    IconButton(onClick = onSave) {
                        Icon(Icons.Outlined.DownloadForOffline, contentDescription = "Save image", tint = Color.White)
                    }
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Outlined.IosShare, contentDescription = "Share image", tint = Color.White)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
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
    showImages: Boolean,
    immersive: Boolean,
    fullScreen: Boolean,
    lineHeight: Float,
    letterSpacing: Float,
    paragraphSpacing: Int,
    measure: Int,
    bionic: Boolean,
    onFontScale: (Float) -> Unit,
    onReaderFont: (ReaderFont) -> Unit,
    onReaderTheme: (ReaderTheme) -> Unit,
    onJustify: (Boolean) -> Unit,
    onShowImages: (Boolean) -> Unit,
    onImmersive: (Boolean) -> Unit,
    onFullScreen: (Boolean) -> Unit,
    onLineHeight: (Float) -> Unit,
    onLetterSpacing: (Float) -> Unit,
    onParagraphSpacing: (Int) -> Unit,
    onMeasure: (Int) -> Unit,
    onBionic: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Display", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))

            // ---- Text size: a proper A− / value / A+ stepper (Instapaper/Pocket style) -------
            SheetSectionLabel("TEXT SIZE")
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SizeStepButton(label = "A", fontSize = 15.sp, enabled = fontScale > 0.7f) {
                    onFontScale(((fontScale * 20).toInt() / 20f - 0.05f).coerceIn(0.7f, 2.6f))
                }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerHighest).padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${(fontScale * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                }
                SizeStepButton(label = "A", fontSize = 24.sp, enabled = fontScale < 2.6f) {
                    onFontScale(((fontScale * 20).toInt() / 20f + 0.05f).coerceIn(0.7f, 2.6f))
                }
            }
            Text(
                "Or pinch anywhere in the article to size the text.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(16.dp))
            // ---- Typeface -------------------------------------------------------------------
            SheetSectionLabel("TYPEFACE")
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

            Spacer(Modifier.height(16.dp))
            // ---- Background: live swatches that preview the actual reader palette ------------
            SheetSectionLabel("BACKGROUND")
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                ReaderTheme.entries.forEach { t ->
                    ThemeSwatch(readerThemeLabel(t), t, readerTheme == t, onReaderTheme)
                }
            }

            Spacer(Modifier.height(16.dp))
            // ---- Fine typography: line height, letter spacing, paragraph gap, measure --------
            SheetSectionLabel("SPACING & WIDTH")
            Spacer(Modifier.height(4.dp))
            SliderRow("Line height", "${(lineHeight * 100).toInt()}%", lineHeight, 0.9f..2.2f) { onLineHeight((it * 20).toInt() / 20f) }
            SliderRow("Letter spacing", "${(letterSpacing * 100).toInt() / 100f}em", letterSpacing, -0.05f..0.3f) { onLetterSpacing((it * 100).toInt() / 100f) }
            SliderRow("Paragraph gap", "${paragraphSpacing}dp", paragraphSpacing.toFloat(), 0f..40f) { onParagraphSpacing(it.toInt()) }
            SliderRow("Text width", if (measure == 0) "Full" else "${measure}dp", measure.toFloat(), 0f..900f) { onMeasure((it / 20).toInt() * 20) }

            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = scheme.outlineVariant)

            // ---- Toggles --------------------------------------------------------------------
            ToggleRow("Bionic reading", "Bold the start of each word to guide the eye", bionic, onBionic)
            ToggleRow("Justify text", null, justify, onJustify)
            ToggleRow("Show images", "Off gives a text-only, data-light read", showImages, onShowImages)
            ToggleRow("Immersive scroll", "Hide every bar as you read; scroll up to bring them back", immersive, onImmersive)
            ToggleRow("Full screen", "Use the entire display, hiding the Android bars too", fullScreen, onFullScreen)
        }
    }
}

@Composable
private fun SliderRow(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
        androidx.compose.material3.Slider(value = current.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.2.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun SizeStepButton(label: String, fontSize: androidx.compose.ui.unit.TextUnit, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainerHighest)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun ThemeSwatch(label: String, theme: ReaderTheme, selected: Boolean, onSelect: (ReaderTheme) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val palette = readerPalette(theme)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(52.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(palette.background)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) scheme.primary else scheme.outlineVariant,
                    shape = androidx.compose.foundation.shape.CircleShape,
                )
                .clickable { onSelect(theme) },
            contentAlignment = Alignment.Center,
        ) {
            Text("A", color = palette.text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) scheme.primary else scheme.onSurfaceVariant, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
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
