@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.transcript

import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Forward30
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.R
import com.cairn.reader.domain.transcript.TranscriptProse
import com.cairn.reader.domain.transcript.TranscriptSourceKind
import com.cairn.reader.ui.components.EmptyState
import com.cairn.reader.ui.theme.Dimens
import com.cairn.reader.ui.theme.ReadingSerif

@Composable
fun TranscriptScreen(
    itemId: String,
    onBack: () -> Unit,
    viewModel: TranscriptViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val audio by viewModel.audio.collectAsStateWithLifecycle()
    val generating by viewModel.generating.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var saveSheet by remember { mutableStateOf(false) }

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
                    val hasTranscript = !state.loading && !state.unavailable && state.error == null
                    if (hasTranscript) {
                        IconButton(onClick = { saveSheet = true }) {
                            Icon(
                                imageVector = if (state.saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = stringResource(R.string.transcript_save_options),
                                tint = if (state.saved) scheme.primary else scheme.onSurfaceVariant,
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.transcript_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            val ready = state.onDeviceSupported && state.onDeviceModelReady
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(R.string.transcript_generate))
                                        if (!ready) {
                                            Text(
                                                stringResource(
                                                    if (!state.onDeviceSupported) R.string.transcript_ondevice_unsupported
                                                    else R.string.transcript_ondevice_needs_model,
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = scheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                leadingIcon = { Icon(Icons.Outlined.GraphicEq, contentDescription = null) },
                                enabled = ready && generating == null,
                                onClick = { menuOpen = false; viewModel.generateOnDevice() },
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
        Column(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
            if (generating != null) LinearProgressIndicator(progress = { generating!! }, modifier = Modifier.fillMaxWidth().height(2.dp))
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error != null -> EmptyState(title = stringResource(R.string.transcript_error), body = state.error ?: "", modifier = Modifier.fillMaxSize(), icon = Icons.Outlined.Subtitles)
                state.unavailable -> Unavailable(state, generating, viewModel::generateOnDevice, Modifier.fillMaxSize(), scheme)
                else -> TranscriptReady(
                    state = state,
                    positionMs = if (audio.active) audio.positionMs.toLong() else -1L,
                    onSeekMs = { ms ->
                        if (state.youtubeId != null) {
                            viewModel.youtubeUrlAt(ms)?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) } }
                        } else viewModel.onSeek(ms)
                    },
                    onAnnotate = viewModel::annotate,
                    onRemove = viewModel::removeAnnotation,
                    onRecolor = viewModel::recolorAnnotation,
                    onNote = viewModel::noteAnnotation,
                    modifier = Modifier.fillMaxSize(),
                    scheme = scheme,
                )
            }
        }
    }

    if (saveSheet) {
        SaveOptionsSheet(
            saved = state.saved,
            annotationCount = state.annotations.size,
            onToggleWhole = viewModel::toggleSaveWhole,
            onDismiss = { saveSheet = false },
        )
    }
}

@Composable
private fun TranscriptReady(
    state: TranscriptUiState,
    positionMs: Long,
    onSeekMs: (Long) -> Unit,
    onAnnotate: (Int, Int, Long, Long, String, Int) -> Unit,
    onRemove: (String) -> Unit,
    onRecolor: (String, Int) -> Unit,
    onNote: (String, String?) -> Unit,
    modifier: Modifier,
    scheme: androidx.compose.material3.ColorScheme,
) {
    val prose = remember(state.cues) { TranscriptProse.from(state.cues) }
    val activeRange = remember(positionMs, prose) { if (positionMs >= 0) prose.charRangeAtTime(positionMs) else null }
    val annViews = remember(state.annotations) {
        state.annotations.map { TranscriptAnnotationView(it.id, it.charStart, it.charEnd, it.color, it.quote, it.note) }
    }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var selection by remember { mutableStateOf<TranscriptSelectionInfo?>(null) }
    var manageId by remember { mutableStateOf<String?>(null) }
    val bodyStyle = TextStyle(fontFamily = ReadingSerif, fontSize = 18.sp, lineHeight = 30.sp, color = scheme.onSurface)

    LazyColumn(modifier, contentPadding = PaddingValues(bottom = Dimens.xxl)) {
        item {
            Text(
                stringResource(R.string.transcript_read_hint),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.lg, vertical = Dimens.md),
            )
        }
        transcriptBodyItem(
            prose = prose, annotations = annViews, activeRange = activeRange,
            bodyStyle = bodyStyle, justify = false, hPad = Dimens.lg, accent = scheme.primary,
            onSeekMs = onSeekMs, onSelect = { selection = it }, onManage = { manageId = it },
        )
    }

    selection?.let { sel ->
        TranscriptSelectionPill(
            yInWindow = sel.y,
            onHighlight = { onAnnotate(sel.globalStart, sel.globalEnd, sel.startMs, sel.endMs, sel.quote, it); selection = null },
            onKeep = { onAnnotate(sel.globalStart, sel.globalEnd, sel.startMs, sel.endMs, sel.quote, SavedPassageColor); selection = null },
            onCopy = { clipboard.setText(AnnotatedString(sel.quote)); selection = null },
            onShare = {
                runCatching {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, sel.quote) }, null))
                }
                selection = null
            },
            onDismiss = { selection = null },
        )
    }

    manageId?.let { id ->
        val ann = state.annotations.firstOrNull { it.id == id }
        if (ann == null) manageId = null
        else ManageAnnotationSheet(
            currentColor = ann.color, note = ann.note,
            onColor = { onRecolor(id, it) }, onNote = { onNote(id, it) },
            onRemove = { onRemove(id); manageId = null }, onDismiss = { manageId = null },
        )
    }
}

@Composable
private fun Unavailable(
    state: TranscriptUiState,
    generating: Float?,
    onGenerate: () -> Unit,
    modifier: Modifier,
    scheme: androidx.compose.material3.ColorScheme,
) {
    Column(modifier.padding(Dimens.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.Subtitles, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(Dimens.lg))
        Text(stringResource(R.string.transcript_unavailable_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Dimens.sm))
        Text(stringResource(R.string.transcript_unavailable_body), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Dimens.lg))
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
                if (state.onDeviceSupported && state.onDeviceModelReady) {
                    Spacer(Modifier.height(Dimens.md))
                    if (generating != null) {
                        Text(stringResource(R.string.transcript_generating), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { generating }, modifier = Modifier.fillMaxWidth())
                    } else {
                        Button(onClick = onGenerate, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.transcript_generate)) }
                        if (state.generateError) {
                            Spacer(Modifier.height(Dimens.sm))
                            Text(stringResource(R.string.transcript_generate_failed), style = MaterialTheme.typography.bodySmall, color = scheme.error)
                        }
                    }
                }
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
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
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
