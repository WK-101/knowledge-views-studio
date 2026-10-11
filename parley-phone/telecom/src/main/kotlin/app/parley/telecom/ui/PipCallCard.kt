package app.parley.telecom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.common.ux.CallScreenBackground
import app.parley.telecom.AudioUi
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.R
import app.parley.ui.Avatar
import app.parley.ui.ParleyShapes
import app.parley.ui.tabular

/**
 * The picture-in-picture window: who, the timer (or the call's status, or hold mode's wait) and a Muted tag. Mute
 * and Hang up are the window's own actions, with "They're back" first in hold mode.
 */
@Composable
internal fun PipCallCard(calls: List<CallUi>, audio: AudioUi, ended: CallUi?, background: CallScreenBackground = CallScreenBackground.CALLER_COLOUR) {
    val live = calls.filter { it.isLive }
    val call = live.firstOrNull { it.state == CallState.ACTIVE } ?: live.firstOrNull() ?: ended ?: calls.firstOrNull()
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(scheme.surface)) {
        // The same caller tint as the full call screen, so the small window reads as the same call.
        CallBackground(call, background, picture = false)
        PipContent(call, live.size, audio)
    }
}

@Composable
private fun PipContent(call: CallUi?, liveCount: Int, audio: AudioUi) {
    val scheme = MaterialTheme.colorScheme
    val live = liveCount
    Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.CenterStart) {
        if (call == null) return@Box
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(call.title, call.photoUri, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    call.displayTitle + if (live > 1) " +${live - 1}" else "",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val status = pipStatus(call)
                if (status != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // On hold reads at a glance in the small window too.
                        if (call.isLive && (call.state == CallState.HOLDING || call.holdModeSince > 0)) {
                            Icon(Icons.Rounded.Pause, null, Modifier.size(12.dp), tint = scheme.primary)
                            Spacer(Modifier.width(2.dp))
                        }
                        Text(status, style = MaterialTheme.typography.labelMedium, color = scheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    val elapsed by rememberCallSeconds(call.connectTimeMillis)
                    Text(clockText(elapsed), style = MaterialTheme.typography.labelMedium.tabular(), color = scheme.primary, maxLines = 1)
                }
                if (audio.muted && call.isLive) {
                    Row(
                        Modifier.padding(top = 2.dp).clip(ParleyShapes.tag).background(scheme.errorContainer).padding(horizontal = 6.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.MicOff, null, Modifier.size(12.dp), tint = scheme.onErrorContainer)
                        Spacer(Modifier.width(3.dp))
                        Text(stringResource(R.string.incall_muted), style = MaterialTheme.typography.labelSmall, color = scheme.onErrorContainer, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The window's status line: why it ended, hold mode's wait (so the app can be used meanwhile), or null for the timer. */
@Composable
private fun pipStatus(call: CallUi): String? {
    val holdNow = if (call.isLive && call.holdModeSince > 0) rememberElapsedNow().value else 0L
    return when {
        !call.isLive -> call.failureText ?: call.dropText?.let { stringResource(R.string.call_drop_title) } ?: call.disconnectReason
            ?: stringResource(R.string.incall_call_ended)
        holdNow > 0 -> stringResource(R.string.holdmode_pip, clockText(holdModeSeconds(call, holdNow)))
        call.state == CallState.HOLDING -> stringResource(R.string.holdmode_title)
        call.state == CallState.ACTIVE -> null
        else -> stringResource(R.string.incall_status_calling)
    }
}
