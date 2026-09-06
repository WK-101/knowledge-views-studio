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
import coil.compose.AsyncImage
import com.cairn.reader.data.db.HighlightEntity
import com.cairn.reader.data.prefs.ReaderFont
import com.cairn.reader.data.prefs.ReaderTheme
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

@Composable
internal fun LookupSheet(
    term: String,
    onlineEnabled: Boolean,
    onDefine: suspend (String) -> Result<com.cairn.reader.domain.lookup.DictionaryEntry>,
    onEnableOnline: () -> Unit,
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
            // Online look-up is the one reader feature that sends selected text off-device, so it is
            // disclosed and opt-in. Until enabled, the sheet explains exactly what will be sent.
            if (!onlineEnabled) {
                val firstWord = remember(term) {
                    term.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.lookup_online_off_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                    )
                }
                Text(
                    if (firstWord.isNotEmpty()) stringResource(R.string.lookup_online_off_body, firstWord)
                    else stringResource(R.string.lookup_online_off_body_generic),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onEnableOnline, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.lookup_enable_online))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.lookup_online_off_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                return@Column
            }
            val entry by produceState<Result<com.cairn.reader.domain.lookup.DictionaryEntry>?>(null, term) {
                value = onDefine(term)
            }
            val result = entry
            when {
                result == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.looking_up), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
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
                        Text(stringResource(R.string.synonyms), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                        Text(e.synonyms.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    }
                    if (e.antonyms.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.antonyms), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
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
internal fun ImageLightbox(
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
                        Icon(Icons.Outlined.DownloadForOffline, contentDescription = stringResource(R.string.save_image), tint = Color.White)
                    }
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Outlined.IosShare, contentDescription = stringResource(R.string.share_image), tint = Color.White)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                }
            }
        }
    }
}

@Composable
internal fun HighlightSheet(
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
                label = { Text(stringResource(R.string.note)) },
                placeholder = { Text(stringResource(R.string.add_a_thought)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.delete))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy), modifier = Modifier.size(20.dp)) }
                IconButton(onClick = onShare) { Icon(Icons.Outlined.IosShare, contentDescription = stringResource(R.string.share), modifier = Modifier.size(20.dp)) }
                TextButton(onClick = { onSaveNote(note); onDismiss() }) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

@Composable
internal fun TypographySheet(
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
            Text(stringResource(R.string.display), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))

            // ---- Text size: preset chips — the same control (and preset scale) as Settings ›
            // Appearance, so the one setting looks identical wherever it's edited. Pinch anywhere in
            // the article for finer, off-preset sizing.
            SheetSectionLabel("TEXT SIZE")
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                listOf(0.8f to "Small", 0.9f to "Cozy", 1.0f to "Default", 1.2f to "Large", 1.5f to "Larger", 2.0f to "Huge")
                    .forEach { (value, label) ->
                        FilterChip(
                            selected = fontScale == value,
                            onClick = { onFontScale(value) },
                            label = { Text(label) },
                        )
                    }
            }
            Text(stringResource(R.string.or_pinch_anywhere_in_the_article),
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
        modifier = Modifier.semantics { heading() },
    )
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
