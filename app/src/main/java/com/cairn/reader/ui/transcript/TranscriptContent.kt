@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.res.stringResource
import com.cairn.reader.R
import com.cairn.reader.domain.transcript.TranscriptProse
import com.cairn.reader.domain.transcript.formatTimestamp
import com.cairn.reader.ui.reader.HighlightColors
import com.cairn.reader.ui.theme.Dimens

/** A neutral tint for a "kept passage" — distinct from the vivid emphasis highlight colours. */
internal val SavedPassageColor = 0xFFB0BEC5.toInt()

/** A live text selection in the transcript, resolved to a media time range + a window anchor. */
internal data class TranscriptSelectionInfo(
    val globalStart: Int,
    val globalEnd: Int,
    val quote: String,
    val startMs: Long,
    val endMs: Long,
    val y: Float,
)

internal data class TranscriptPaintSpan(val localStart: Int, val localEnd: Int, val color: Int, val id: String)

/**
 * Emit the transcript prose as reader paragraphs into a [LazyListScope], styled by the caller's
 * [bodyStyle]/[justify]/[paragraphSpacing] — so wherever it's shown (the standalone screen or inline
 * in the article reader) it inherits that surface's font, size, line height, colour and theme. Each
 * paragraph carries a subtle, tappable timecode; selection reports GLOBAL prose offsets.
 */
internal fun LazyListScope.transcriptParagraphItems(
    prose: TranscriptProse,
    annotations: List<TranscriptAnnotationView>,
    activeRange: IntRange?,
    bodyStyle: TextStyle,
    justify: Boolean,
    paragraphSpacing: Int,
    hPad: Dp,
    timeColor: Color,
    selColor: Color,
    onSeekMs: (Long) -> Unit,
    onSelect: (TranscriptSelectionInfo) -> Unit,
    onManage: (String) -> Unit,
) {
    items(prose.paragraphs, key = { "tp_" + it.charStart }) { para ->
        val paraText = remember(para) { prose.text.substring(para.charStart, para.charEnd) }
        val paints = remember(para, annotations) { paintSpansFor(paraText, para.charStart, annotations) }
        val activeLocal = activeRange?.let { r ->
            val s = (r.first - para.charStart); val e = (r.last + 1 - para.charStart)
            if (e > 0 && s < paraText.length) s.coerceIn(0, paraText.length) until e.coerceIn(0, paraText.length) else null
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = hPad, vertical = paragraphSpacing.dp)) {
            Text(
                formatTimestamp(para.startMs),
                style = MaterialTheme.typography.labelSmall,
                color = timeColor,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onSeekMs(para.startMs) }.padding(vertical = 2.dp),
            )
            Spacer(Modifier.height(4.dp))
            HighlightableProse(
                localText = paraText,
                baseChar = para.charStart,
                style = bodyStyle.copy(textAlign = if (justify) TextAlign.Justify else TextAlign.Start),
                paints = paints,
                activeLocal = activeLocal,
                selColor = selColor,
                onSelect = { gs, ge, q, y ->
                    onSelect(
                        TranscriptSelectionInfo(
                            gs, ge, q,
                            prose.startMsForRange(gs, ge), prose.endMsForRange(gs, ge), y,
                        ),
                    )
                },
                onSeekChar = { global -> onSeekMs(prose.timeAt(global)) },
                onManage = onManage,
            )
        }
    }
}

/** A minimal projection of a saved annotation for painting/managing, decoupled from the ViewModel. */
internal data class TranscriptAnnotationView(
    val id: String,
    val charStart: Int,
    val charEnd: Int,
    val color: Int,
    val quote: String,
    val note: String?,
)

@Composable
internal fun HighlightableProse(
    localText: String,
    baseChar: Int,
    style: TextStyle,
    paints: List<TranscriptPaintSpan>,
    activeLocal: IntRange?,
    selColor: Color,
    onSelect: (Int, Int, String, Float) -> Unit,
    onSeekChar: (Int) -> Unit,
    onManage: (String) -> Unit,
) {
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val currentSelect by rememberUpdatedState(onSelect)
    val currentSeek by rememberUpdatedState(onSeekChar)
    val currentManage by rememberUpdatedState(onManage)
    val currentPaints by rememberUpdatedState(paints)
    var topInWindow by remember { mutableStateOf(0f) }
    var anchor by remember { mutableStateOf<Int?>(null) }
    var focus by remember { mutableStateOf<Int?>(null) }

    val rendered = remember(localText, paints, activeLocal, anchor, focus, selColor) {
        buildAnnotatedString {
            append(localText)
            activeLocal?.let { r ->
                val s = r.first.coerceIn(0, localText.length); val e = (r.last + 1).coerceIn(s, localText.length)
                if (e > s) addStyle(SpanStyle(background = selColor.copy(alpha = 0.16f)), s, e)
            }
            paints.forEach { p ->
                val s = p.localStart.coerceIn(0, localText.length); val e = p.localEnd.coerceIn(s, localText.length)
                if (e > s) addStyle(SpanStyle(background = Color(p.color).copy(alpha = 0.42f)), s, e)
            }
            val a = anchor; val f = focus
            if (a != null && f != null && a != f) addStyle(SpanStyle(background = selColor.copy(alpha = 0.30f)), minOf(a, f), maxOf(a, f))
        }
    }

    fun offsetAt(pos: androidx.compose.ui.geometry.Offset): Int =
        (layout.value?.getOffsetForPosition(pos) ?: 0).coerceIn(0, localText.length)

    Text(
        text = rendered,
        style = style,
        onTextLayout = { layout.value = it },
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { topInWindow = it.positionInWindow().y }
            .pointerInput(localText) {
                detectTapGestures { pos ->
                    val o = offsetAt(pos)
                    val hit = currentPaints.firstOrNull { o >= it.localStart && o < it.localEnd }
                    if (hit != null) currentManage(hit.id) else currentSeek(baseChar + o)
                }
            }
            .pointerInput(localText) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        val o = offsetAt(pos)
                        val w = wordRangeAt(localText, o)
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
                        while (e > s && localText[e - 1].isWhitespace()) e--
                        while (s < e && localText[s].isWhitespace()) s++
                        if (e > s) {
                            val boxTop = runCatching { layout.value?.getBoundingBox(s)?.top ?: 0f }.getOrDefault(0f)
                            currentSelect(baseChar + s, baseChar + e, localText.substring(s, e), topInWindow + boxTop)
                        }
                    },
                )
            },
    )
}

@Composable
internal fun TranscriptSelectionPill(
    yInWindow: Float,
    onHighlight: (Int) -> Unit,
    onKeep: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val above = with(density) { 56.dp.toPx() }
    val minY = with(density) { 96.dp.toPx() }
    val rawAbove = yInWindow - above
    val y = if (rawAbove < minY) yInWindow + with(density) { 40.dp.toPx() } else rawAbove
    val yPx = y.toInt().coerceAtLeast(with(density) { 8.dp.toPx() }.toInt())

    Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, yPx), onDismissRequest = onDismiss, properties = PopupProperties(focusable = false)) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, tonalElevation = 3.dp, shadowElevation = 8.dp) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HighlightColors.all.forEach { c ->
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(Color(c))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { onHighlight(c) },
                    )
                }
                Box(Modifier.padding(horizontal = 2.dp).size(width = 1.dp, height = 24.dp).background(MaterialTheme.colorScheme.outlineVariant))
                PillAction(Icons.Outlined.BookmarkAdd, stringResource(R.string.transcript_keep_passage), onKeep)
                PillAction(Icons.Outlined.ContentCopy, stringResource(R.string.transcript_copy), onCopy)
                PillAction(Icons.Outlined.IosShare, stringResource(R.string.transcript_share), onShare)
            }
        }
    }
}

@Composable
private fun PillAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).clickable(onClick = onClick).padding(8.dp)) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun ManageAnnotationSheet(
    currentColor: Int,
    note: String?,
    onColor: (Int) -> Unit,
    onNote: (String?) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var noteText by remember { mutableStateOf(note.orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.lg).padding(bottom = Dimens.xl)) {
            Text(stringResource(R.string.transcript_manage_annotation), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Dimens.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm), verticalAlignment = Alignment.CenterVertically) {
                (HighlightColors.all + SavedPassageColor).forEach { c ->
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                            .then(if (c == currentColor) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .clickable { onColor(c) },
                    )
                }
            }
            Spacer(Modifier.height(Dimens.md))
            OutlinedTextField(value = noteText, onValueChange = { noteText = it }, label = { Text(stringResource(R.string.transcript_note_hint)) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Dimens.md))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onRemove) { Text(stringResource(R.string.transcript_remove_highlight), color = MaterialTheme.colorScheme.error) }
                Button(onClick = { onNote(noteText.ifBlank { null }); onDismiss() }) { Text(stringResource(R.string.done)) }
            }
        }
    }
}

@Composable
internal fun SaveOptionsSheet(
    saved: Boolean,
    annotationCount: Int,
    onToggleWhole: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.lg).padding(bottom = Dimens.xl)) {
            Text(stringResource(R.string.transcript_save_sheet_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Dimens.md))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = Dimens.md)) {
                    Text(stringResource(R.string.transcript_keep_whole), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.transcript_keep_whole_desc), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                Switch(checked = saved, onCheckedChange = { onToggleWhole() })
            }
            Spacer(Modifier.height(Dimens.md))
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(Dimens.md))
            Text(stringResource(R.string.transcript_keep_highlights_note, annotationCount), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

// -- helpers ------------------------------------------------------------------

internal fun paintSpansFor(paraText: String, paraStart: Int, annotations: List<TranscriptAnnotationView>): List<TranscriptPaintSpan> =
    annotations.mapNotNull { a ->
        val ls0 = a.charStart - paraStart
        val le0 = a.charEnd - paraStart
        if (le0 <= 0 || ls0 >= paraText.length) return@mapNotNull null
        var ls = ls0.coerceIn(0, paraText.length)
        var le = le0.coerceIn(ls, paraText.length)
        if (le <= ls) return@mapNotNull null
        if (a.quote.isNotBlank() && runCatching { paraText.substring(ls, le) }.getOrNull() != a.quote) {
            val idx = paraText.indexOf(a.quote)
            if (idx >= 0) { ls = idx; le = idx + a.quote.length }
        }
        TranscriptPaintSpan(ls, le, a.color, a.id)
    }

internal fun wordRangeAt(text: String, offset: Int): IntRange? {
    if (text.isBlank()) return null
    val probe = offset.coerceIn(0, text.length - 1)
    if (!text[probe].isLetterOrDigit()) return null
    var s = probe
    while (s > 0 && text[s - 1].isLetterOrDigit()) s--
    var e = probe
    while (e < text.length && text[e].isLetterOrDigit()) e++
    return if (e > s) s..(e - 1) else null
}
