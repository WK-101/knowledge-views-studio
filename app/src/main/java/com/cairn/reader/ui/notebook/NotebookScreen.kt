@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.notebook

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit,
    viewModel: NotebookViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    fun shareAll() = viewModel.exportAll { md ->
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, md)
            putExtra(Intent.EXTRA_SUBJECT, "My highlights")
        }
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Annotations", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (groups.isNotEmpty()) {
                        IconButton(onClick = { shareAll() }) {
                            Icon(Icons.Outlined.IosShare, contentDescription = "Export all")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (groups.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No annotations yet", style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "While reading, long-press to select text, then Highlight it. Your highlights and notes collect here as cards, ready to export.",
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
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalItemSpacing = 10.dp,
            ) {
                items(groups, key = { it.itemId }) { group ->
                    AnnotationCard(group, onClick = { onOpenItem(group.itemId) })
                }
            }
        }
    }
}

/** One card per annotated article: cover, source, title, the top highlight, and a count. */
@Composable
private fun AnnotationCard(group: NotebookGroup, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val top = group.highlights.first()
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerLow)
            .clickable(onClick = onClick)
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
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            val source = group.site ?: "Highlight"
            Text(source, style = MaterialTheme.typography.labelSmall, color = scheme.primary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
