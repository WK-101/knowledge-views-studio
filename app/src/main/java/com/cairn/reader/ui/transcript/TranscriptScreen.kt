@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.transcript

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Forward30
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.R
import com.cairn.reader.domain.transcript.TranscriptCue
import com.cairn.reader.domain.transcript.TranscriptSourceKind
import com.cairn.reader.domain.transcript.formatTimestamp
import com.cairn.reader.ui.components.EmptyState
import com.cairn.reader.ui.theme.Dimens

/** The four highlighter colours a transcript annotation can take (ARGB), matching the reader's set. */
private val HighlightPalette = listOf(0xFFFFF176, 0xFFAED581, 0xFF80DEEA, 0xFFF48FB1).map { it.toInt() }

@Composable
fun TranscriptScreen(
    itemId: String,
    onBack: () -> Unit,
    viewModel: TranscriptViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val audio by viewModel.audio.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var colorIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(itemId) { viewModel.start(itemId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.transcript), fontWeight = FontWeight.SemiBold)
                        val label = provenanceLabel(state.provenance)
                        if (label != null && !state.loading && !state.unavailable) {
                            Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (!state.loading && !state.unavailable && state.error == null) {
                        IconButton(onClick = viewModel::toggleSaveWhole) {
                            Icon(
                                imageVector = if (state.saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = stringResource(R.string.transcript_keep_whole),
                                tint = if (state.saved) scheme.primary else scheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.isAudio && audio.active) {
                TranscriptAudioBar(audio.playing, viewModel::audioToggle, { viewModel.seekBy(-10_000) }, { viewModel.seekBy(30_000) }, scheme)
            }
        },
    ) { inner ->
        val modifier = Modifier.fillMaxSize().padding(inner)
        when {
            state.loading -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> EmptyState(
                title = stringResource(R.string.transcript_error),
                body = state.error ?: "",
                modifier = modifier,
                icon = Icons.Outlined.Subtitles,
            )
            state.unavailable -> Unavailable(state, modifier, scheme)
            else -> Ready(
                state = state,
                positionMs = if (audio.active) audio.positionMs.toLong() else -1L,
                colorIndex = colorIndex,
                onColor = { colorIndex = it },
                onCueTap = { cue ->
                    if (state.youtubeId != null) {
                        viewModel.youtubeUrlAt(cue.startMs)?.let {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) }
                        }
                    } else {
                        viewModel.onCueTap(cue)
                    }
                },
                onCueHighlight = { cue -> viewModel.toggleHighlight(cue, HighlightPalette[colorIndex]) },
                modifier = modifier,
                scheme = scheme,
            )
        }
    }
}

@Composable
private fun Ready(
    state: TranscriptUiState,
    positionMs: Long,
    colorIndex: Int,
    onColor: (Int) -> Unit,
    onCueTap: (TranscriptCue) -> Unit,
    onCueHighlight: (TranscriptCue) -> Unit,
    modifier: Modifier,
    scheme: androidx.compose.material3.ColorScheme,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = Dimens.xxl)) {
        item {
            Column(Modifier.fillMaxWidth().padding(Dimens.lg)) {
                // Keep-whole hint + save summary.
                Text(
                    stringResource(R.string.transcript_highlight_hint),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Dimens.md))
                // Highlighter colour selector — the colour the next annotation takes.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                    Text(stringResource(R.string.transcript_highlight), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    HighlightPalette.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).background(Color(c))
                                .then(if (i == colorIndex) Modifier.border(2.dp, scheme.onSurface, CircleShape) else Modifier)
                                .clickable { onColor(i) },
                        )
                    }
                }
            }
        }
        items(state.cues, key = { it.startMs }) { cue ->
            val highlighted = cue.startMs in state.highlightedStarts
            val active = positionMs in cue.startMs until (cue.endMs.coerceAtLeast(cue.startMs + 1))
            CueRow(cue, highlighted, active, onTap = { onCueTap(cue) }, onHighlight = { onCueHighlight(cue) }, scheme)
        }
    }
}

@Composable
private fun CueRow(
    cue: TranscriptCue,
    highlighted: Boolean,
    active: Boolean,
    onTap: () -> Unit,
    onHighlight: () -> Unit,
    scheme: androidx.compose.material3.ColorScheme,
) {
    val bg = if (active) scheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onTap)
            .padding(horizontal = Dimens.lg, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            formatTimestamp(cue.startMs),
            style = MaterialTheme.typography.labelMedium,
            color = if (active) scheme.primary else scheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(52.dp).padding(top = 2.dp),
        )
        Text(
            cue.text,
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f).padding(horizontal = Dimens.sm),
        )
        IconButton(onClick = onHighlight, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.Subtitles,
                contentDescription = stringResource(R.string.transcript_highlight),
                tint = if (highlighted) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun Unavailable(state: TranscriptUiState, modifier: Modifier, scheme: androidx.compose.material3.ColorScheme) {
    Column(
        modifier.padding(Dimens.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Subtitles, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(Dimens.lg))
        Text(
            stringResource(R.string.transcript_unavailable_title),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Dimens.sm))
        Text(
            stringResource(R.string.transcript_unavailable_body),
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Dimens.lg))
        // Honest on-device status: only offered when the speech engine can actually run here.
        Surface(color = scheme.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Dimens.lg)) {
                Text(stringResource(R.string.transcript_ondevice_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                Spacer(Modifier.height(6.dp))
                val body = when {
                    !state.onDeviceSupported -> stringResource(R.string.transcript_ondevice_unsupported)
                    !state.onDeviceModelReady -> stringResource(R.string.transcript_ondevice_needs_model)
                    else -> stringResource(R.string.transcript_ondevice_ready)
                }
                Text(body, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TranscriptAudioBar(
    playing: Boolean,
    onToggle: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    scheme: androidx.compose.material3.ColorScheme,
) {
    Surface(color = scheme.surfaceContainerHigh, tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.lg, vertical = Dimens.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.Replay10, contentDescription = stringResource(R.string.rewind), tint = scheme.onSurface) }
            IconButton(onClick = onToggle) {
                Icon(
                    if (playing) Icons.Outlined.PauseCircle else Icons.Outlined.PlayCircle,
                    contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                    tint = scheme.primary, modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = onForward) { Icon(Icons.Outlined.Forward30, contentDescription = stringResource(R.string.forward), tint = scheme.onSurface) }
        }
    }
}

@Composable
private fun provenanceLabel(kind: TranscriptSourceKind): String? = when (kind) {
    TranscriptSourceKind.YOUTUBE_CAPTIONS -> stringResource(R.string.transcript_source_youtube)
    TranscriptSourceKind.PODCAST_TRANSCRIPT -> stringResource(R.string.transcript_source_podcast)
    TranscriptSourceKind.CAPTION_FILE -> stringResource(R.string.transcript_source_file)
    TranscriptSourceKind.ON_DEVICE -> stringResource(R.string.transcript_source_ondevice)
    TranscriptSourceKind.UNKNOWN -> null
}
