package com.cairn.reader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cairn.reader.audio.TtsReader

private val ListenSpeeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

private fun nextSpeed(current: Float): Float {
    val i = ListenSpeeds.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return if (i == -1) 1.0f else ListenSpeeds[(i + 1) % ListenSpeeds.size]
}

private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "$speed×"

/** The shared read-aloud bar: previous / play-pause / next, a title + progress, speed and stop.
 *  Previous & next are shown only when a multi-article queue is playing. */
@Composable
fun ListenBar(
    state: TtsReader.State,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSpeed: (Float) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val hasQueue = state.trackCount > 1
    Surface(color = scheme.surfaceContainerHigh, tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasQueue) {
                IconButton(onClick = onPrev) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = scheme.onSurface)
                }
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.playing) "Pause" else "Play",
                    tint = scheme.onSurface,
                )
            }
            if (hasQueue) {
                IconButton(onClick = onNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = scheme.onSurface)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                val title = state.trackTitle.ifBlank { "Listening" }
                val label = if (hasQueue) "${state.trackIndex + 1} of ${state.trackCount}  ·  $title" else title
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                val fraction = if (state.total > 0) (state.index + 1f) / state.total else 0f
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
            TextButton(onClick = { onSpeed(nextSpeed(state.speed)) }) { Text(speedLabel(state.speed)) }
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Close, contentDescription = "Stop", tint = scheme.onSurfaceVariant)
            }
        }
    }
}
