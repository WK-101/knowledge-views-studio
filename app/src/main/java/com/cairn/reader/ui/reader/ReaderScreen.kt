package com.cairn.reader.ui.reader

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cairn.reader.ui.theme.ReadingSerif
import com.cairn.reader.ui.util.formatAgo

private val ReaderHPad = 22.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val data = state.data

    fun openOriginal() {
        val url = data?.url ?: return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleStar) {
                        Icon(
                            imageVector = if (data?.isStarred == true) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Star",
                            tint = if (data?.isStarred == true) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = viewModel::toggleSave) {
                        Icon(
                            imageVector = if (data?.isReadLater == true) Icons.Filled.Bookmark else Icons.Outlined.Bookmark,
                            contentDescription = "Save",
                            tint = if (data?.isReadLater == true) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = ::openOriginal) {
                        Icon(Icons.Outlined.OpenInNew, contentDescription = "Open original")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        when {
            state.loading -> Centered(padding) { CircularProgressIndicator() }
            data == null -> Centered(padding) {
                Text("This article couldn't be loaded.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> ArticleBody(padding, state, onLoadFull = viewModel::loadFullArticle, onOpenOriginal = ::openOriginal)
        }
    }
}

@Composable
private fun ArticleBody(
    padding: PaddingValues,
    state: ReaderUiState,
    onLoadFull: () -> Unit,
    onOpenOriginal: () -> Unit,
) {
    val data = state.data ?: return
    val scheme = MaterialTheme.colorScheme
    val linkColor = scheme.primary
    val blocks = remember(data.html, linkColor) {
        data.html?.let { HtmlLinearizer.linearize(it, data.url, linkColor) }.orEmpty()
    }
    val bodyStyle = TextStyle(fontFamily = ReadingSerif, fontSize = 18.sp, lineHeight = 30.sp, color = scheme.onSurface)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 48.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = ReaderHPad)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = data.title,
                    style = TextStyle(fontFamily = ReadingSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
                    color = scheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                val meta = buildList {
                    data.siteName?.let { add(it) }
                    data.author?.let { add(it) }
                    if (data.readingMinutes > 0) add("${data.readingMinutes} min read")
                    formatAgo(data.publishedAt).takeIf { it.isNotEmpty() }?.let { add(it) }
                }.joinToString("  ·  ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
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
                HorizontalDivider(color = scheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
            }
        }

        if (blocks.isEmpty()) {
            item {
                Column(Modifier.padding(horizontal = ReaderHPad)) {
                    Text(
                        "No readable content was saved for this item yet.",
                        style = bodyStyle,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onOpenOriginal) { Text("Open original") }
                }
            }
        }

        items(blocks.size) { index ->
            BlockView(blocks[index], bodyStyle)
        }
    }
}

@Composable
private fun BlockView(block: ReaderBlock, bodyStyle: TextStyle) {
    val scheme = MaterialTheme.colorScheme
    when (block) {
        is ReaderBlock.Heading -> Text(
            text = block.text,
            style = TextStyle(
                fontFamily = ReadingSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = when (block.level) { 1, 2 -> 24.sp; 3 -> 21.sp; else -> 19.sp },
                lineHeight = 30.sp,
                color = scheme.onSurface,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ReaderHPad)
                    .clip(RoundedCornerShape(12.dp)),
            )
            if (!block.caption.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    block.caption,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = ReaderHPad),
                )
            }
        }
        is ReaderBlock.Quote -> Row(
            Modifier
                .padding(horizontal = ReaderHPad, vertical = 10.dp)
                .height(IntrinsicSize.Min),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(scheme.primary, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = block.text,
                style = bodyStyle.copy(fontStyle = FontStyle.Italic, color = scheme.onSurfaceVariant),
            )
        }
        is ReaderBlock.Code -> Box(
            Modifier
                .padding(horizontal = ReaderHPad, vertical = 10.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.surfaceContainerHighest)
                .horizontalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            Text(block.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = scheme.onSurface)
        }
        is ReaderBlock.BulletList -> Column(Modifier.padding(horizontal = ReaderHPad, vertical = 8.dp)) {
            block.items.forEachIndexed { i, item ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(
                        text = if (block.ordered) "${i + 1}. " else "•  ",
                        style = bodyStyle,
                        color = scheme.primary,
                    )
                    Text(text = item, style = bodyStyle)
                }
            }
        }
        ReaderBlock.Rule -> HorizontalDivider(
            color = scheme.outlineVariant,
            modifier = Modifier.padding(horizontal = ReaderHPad, vertical = 18.dp),
        )
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
