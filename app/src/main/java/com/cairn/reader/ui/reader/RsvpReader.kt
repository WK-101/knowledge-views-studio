package com.cairn.reader.ui.reader

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R

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
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close_speed_reader), tint = scheme.onSurface)
                    }
                }

                Spacer(Modifier.height(48.dp))
                // The reticle: the ORP letter is PINNED to the fixed centre column (two guide ticks
                // mark it) so the eye never has to move between words — the whole point of RSVP. The
                // text before/after the pivot grows out to each side around that fixed point.
                val word = words.getOrNull(index).orEmpty()
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.width(2.dp).height(10.dp).background(scheme.primary.copy(alpha = 0.5f)))
                    Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                        val orp = orpIndex(word)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(140.dp), contentAlignment = Alignment.CenterEnd) {
                                Text(
                                    text = if (word.isNotEmpty()) word.substring(0, orp) else "",
                                    fontFamily = FontFamily.Monospace, fontSize = 34.sp, color = scheme.onSurface,
                                    maxLines = 1,
                                )
                            }
                            Text(
                                text = if (word.isNotEmpty()) word[orp].toString() else "",
                                fontFamily = FontFamily.Monospace, fontSize = 34.sp,
                                color = Color(0xFFEF5350), fontWeight = FontWeight.Bold,
                            )
                            Box(Modifier.width(140.dp), contentAlignment = Alignment.CenterStart) {
                                Text(
                                    text = if (word.isNotEmpty() && orp + 1 <= word.length - 1) word.substring(orp + 1) else "",
                                    fontFamily = FontFamily.Monospace, fontSize = 34.sp, color = scheme.onSurface,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Box(Modifier.width(2.dp).height(10.dp).background(scheme.primary.copy(alpha = 0.5f)))
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
                        Icon(Icons.Outlined.Replay, contentDescription = stringResource(R.string.restart), tint = scheme.onSurface)
                    }
                    Spacer(Modifier.width(8.dp))
                    // Regression: step back a word to re-read (RSVP's usual weakness — you can't glance
                    // back). Stepping pauses so you can dwell.
                    IconButton(onClick = { playing = false; index = (index - 1).coerceAtLeast(0) }, enabled = index > 0) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous word", tint = scheme.onSurface)
                    }
                    Spacer(Modifier.width(8.dp))
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
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { playing = false; index = (index + 1).coerceAtMost(words.size - 1) }, enabled = index < words.size - 1) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next word", tint = scheme.onSurface)
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

/** The Optimal Recognition Point letter index (Spritz-style) the word is pinned around. */
private fun orpIndex(word: String): Int = when (word.length) {
    0, 1 -> 0
    in 2..5 -> 1
    in 6..9 -> 2
    in 10..13 -> 3
    else -> 4
}.coerceIn(0, (word.length - 1).coerceAtLeast(0))
