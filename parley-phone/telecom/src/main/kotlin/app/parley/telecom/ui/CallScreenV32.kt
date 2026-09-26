package app.parley.telecom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.telecom.AudioUi
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.DeclineBlock
import app.parley.telecom.R
import app.parley.ui.Avatar
import app.parley.ui.Bidi

/**
 * P6: an outgoing call that didn't go through: the reason ("Airplane mode is on", "No SIM was chosen", the network's
 * own text) and Retry. It stays until the user dismisses it.
 */
@Composable
internal fun FailureBanner(call: CallUi, onRetry: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.errorContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = scheme.onErrorContainer)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.call_failed_title), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = scheme.onErrorContainer,
                )
            }
            call.failureText?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onErrorContainer, modifier = Modifier.padding(top = 4.dp))
            }
            Row(Modifier.align(Alignment.End).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onDismiss) { Text(stringResource(R.string.call_failed_dismiss), color = scheme.onErrorContainer) }
                FilledTonalButton(onRetry) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.call_failed_retry))
                }
            }
        }
    }
}

/** P2: after "Block & decline": what happened, and Undo while the rule is Parley's own new one. */
@Composable
internal fun DeclineBlockCard(block: DeclineBlock, onUndo: () -> Unit, onDone: () -> Unit) {
    val number = Bidi.ltr(block.number)
    val (title, body) = when {
        block.pending -> stringResource(R.string.decline_title) to stringResource(R.string.decline_block_pending)
        block.undone -> stringResource(R.string.decline_title) to stringResource(R.string.decline_block_undone, number)
        block.ruleId == null -> stringResource(R.string.decline_title) to stringResource(R.string.decline_block_failed)
        block.ruleId == 0L -> stringResource(R.string.decline_title) to stringResource(R.string.decline_block_already)
        block.answered -> stringResource(R.string.decline_blocked_title) to stringResource(R.string.decline_blocked_answered, number)
        else -> stringResource(R.string.decline_blocked_title) to stringResource(R.string.decline_blocked_body, number)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Block, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.align(Alignment.End).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if ((block.ruleId ?: 0L) > 0L && !block.undone && !block.pending) TextButton(onUndo) { Text(stringResource(R.string.tc_undo)) }
                TextButton(onDone) { Text(stringResource(R.string.tc_done)) }
            }
        }
    }
}

/** P2: in place of the answer controls while "Block & decline" writes the rule (a second or so at most). */
@Composable
internal fun BlockingDecline() {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 64.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(Modifier.size(32.dp))
        Text(stringResource(R.string.decline_block_pending), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    }
}

/**
 * P1: the picture-in-picture window: who, the timer (or the call's status) and a Muted tag. Mute and Hang up are
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
                        Modifier.padding(top = 2.dp).clip(RoundedCornerShape(8.dp)).background(scheme.errorContainer).padding(horizontal = 6.dp, vertical = 1.dp),
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
