package com.cairn.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * RSVP (Rapid Serial Visual Presentation) speed-reader: flashes one word at a time, centred on an
 * Optimal Recognition Point letter so the eye never has to move. Purely on-device, driven from the
 * article's already-extracted text. A calm, focused way to get through a long piece fast.
 */
@Composable
fun RsvpReader(
    text: String,
    onClose: () -> Unit,
) {
    val words = remember(text) {
        text.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var wpm by remember { mutableFloatStateOf(350f) }

    val scheme = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(scheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            // Driver: advance one word, pausing a little longer on long or sentence-ending words.
            LaunchedEffect(playing, wpm, index, words.size) {
                if (!playing || words.isEmpty() || index >= words.size) return@LaunchedEffect
                val base = 60_000f / wpm.coerceAtLeast(60f)
                val w = words[index]
                val extra = when {
                    w.length > 8 -> base * 0.6f
                    w.endsWith('.') || w.endsWith('!') || w.endsWith('?') -> base * 0.9f
                    w.endsWith(',') || w.endsWith(';') || w.endsWith(':') -> base * 0.4f
                    else -> 0f
                }
                delay((base + extra).toLong())
                if (index < words.size - 1) index++ else playing = false
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Top row: close.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close speed reader", tint = scheme.onSurface)
                    }
                }

                Spacer(Modifier.height(48.dp))
                // The reticle: a thin guide line with the ORP-aligned word.
                Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                    val word = words.getOrNull(index).orEmpty()
                    Text(
                        text = orpAnnotated(word),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 34.sp,
                        color = scheme.onSurface,
                    )
                }
                Spacer(Modifier.height(48.dp))

                val progress = if (words.isEmpty()) 0f else (index + 1f) / words.size
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = scheme.surfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${index + 1} / ${words.size} words",
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )

                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = { index = 0; playing = true }) {
                        Icon(Icons.Outlined.Replay, contentDescription = "Restart", tint = scheme.onSurface)
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = {
                        if (index >= words.size - 1) index = 0
                        playing = !playing
                    }) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            tint = scheme.onSurface,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("${wpm.toInt()} words / min", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = wpm,
                    onValueChange = { wpm = it },
                    valueRange = 150f..800f,
                    steps = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Highlight the Optimal Recognition Point letter (Spritz-style) so the eye anchors in one spot. */
private fun orpAnnotated(word: String): AnnotatedString {
    if (word.isEmpty()) return AnnotatedString("")
    val orp = when (word.length) {
        1 -> 0
        in 2..5 -> 1
        in 6..9 -> 2
        in 10..13 -> 3
        else -> 4
    }.coerceIn(0, word.length - 1)
    return buildAnnotatedString {
        append(word.substring(0, orp))
        withStyle(SpanStyle(color = Color(0xFFEF5350), fontWeight = FontWeight.Bold)) {
            append(word[orp].toString())
        }
        if (orp + 1 <= word.length - 1) append(word.substring(orp + 1))
    }
}
