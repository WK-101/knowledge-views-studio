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
import app.parley.telecom.AudioUi
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.R
import app.parley.ui.Avatar
import app.parley.ui.ParleyShapes

/**
 * The picture-in-picture window: who, the timer (or the call's status) and a Muted tag. Mute and Hang up are
 * the window's own actions.
 */
@Composable
internal fun PipCallCard(calls: List<CallUi>, audio: AudioUi, ended: CallUi?) {
    val live = calls.filter { it.isLive }
    val call = live.firstOrNull { it.state == CallState.ACTIVE } ?: live.firstOrNull() ?: ended ?: calls.firstOrNull()
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(scheme.surface).padding(10.dp), contentAlignment = Alignment.CenterStart) {
        if (call == null) return@Box
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(call.title, call.photoUri, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    call.displayTitle + if (live.size > 1) " +${live.size - 1}" else "",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val status = when {
                    !call.isLive -> call.failureText ?: call.disconnectReason ?: stringResource(R.string.incall_call_ended)
                    call.state == CallState.HOLDING -> stringResource(R.string.incall_status_on_hold)
                    call.state == CallState.ACTIVE -> null
                    else -> stringResource(R.string.incall_status_calling)
                }
                if (status != null) {
                    Text(status, style = MaterialTheme.typography.labelMedium, color = scheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    val elapsed by rememberCallSeconds(call.connectTimeMillis)
                    Text(clockText(elapsed), style = MaterialTheme.typography.labelMedium, color = scheme.primary, maxLines = 1)
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
