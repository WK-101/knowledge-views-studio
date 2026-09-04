@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.reader

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cairn.reader.data.prefs.ReaderFont
import com.cairn.reader.data.prefs.ReaderTheme
import com.cairn.reader.ui.theme.InterFamily
import com.cairn.reader.ui.theme.ReadingSerif
import com.cairn.reader.ui.util.formatAgo

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
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val data = state.data
    var showTypography by remember { mutableStateOf(false) }

    val palette = readerPalette(prefs.readerTheme)

    fun openOriginal() {
        val url = data?.url ?: return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
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
                    IconButton(onClick = ::openOriginal) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open original", tint = palette.text) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.background),
            )
        },
    ) { padding ->
        when {
            state.loading -> Centered(padding) { CircularProgressIndicator() }
            data == null -> Centered(padding) { Text("This article couldn't be loaded.", color = palette.secondary) }
            else -> ArticleBody(
                padding = padding,
                state = state,
                palette = palette,
                fontFamily = if (prefs.readerFont == ReaderFont.SERIF) ReadingSerif else InterFamily,
                scale = prefs.readerFontScale,
                onLoadFull = viewModel::loadFullArticle,
                onOpenOriginal = ::openOriginal,
                onSaveProgress = viewModel::setProgress,
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
}

@Composable
private fun ArticleBody(
    padding: PaddingValues,
    state: ReaderUiState,
    palette: ReaderPalette,
    fontFamily: FontFamily,
    scale: Float,
    onLoadFull: () -> Unit,
    onOpenOriginal: () -> Unit,
    onSaveProgress: (Float) -> Unit,
) {
    val data = state.data ?: return
    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = remember(data.html, linkColor) {
        data.html?.let { HtmlLinearizer.linearize(it, data.url, linkColor) }.orEmpty()
    }
    val bodyStyle = TextStyle(fontFamily = fontFamily, fontSize = (18 * scale).sp, lineHeight = (30 * scale).sp, color = palette.text)

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
                    if (data.extractStatus != "OK") {
                        OutlinedButton(onClick = onLoadFull, enabled = !state.extracting) {
                            if (state.extracting) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(16.dp).width(16.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (state.extracting) "Fetching…" else "Read full article")
                        }
                        Spacer(Modifier.height(8.dp))
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
                BlockView(blocks[index], bodyStyle, palette)
            }
        }
    }
}

@Composable
private fun BlockView(block: ReaderBlock, bodyStyle: TextStyle, palette: ReaderPalette) {
    when (block) {
        is ReaderBlock.Heading -> Text(
            text = block.text,
            style = bodyStyle.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = bodyStyle.fontSize * when (block.level) { 1, 2 -> 1.35f; 3 -> 1.2f; else -> 1.08f },
                lineHeight = bodyStyle.lineHeight * 1.05f,
            ),
            modifier = Modifier.padding(horizontal = ReaderHPad, vertical = 10.dp),
        )
        is ReaderBlock.Paragraph -> Text(
            text = block.text,
            style = bodyStyle,
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
            Text(text = block.text, style = bodyStyle.copy(fontStyle = FontStyle.Italic, color = palette.secondary))
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

@Composable
private fun Centered(padding: PaddingValues, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
