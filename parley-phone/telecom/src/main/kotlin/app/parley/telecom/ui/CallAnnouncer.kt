package app.parley.telecom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.parley.common.calls.CallAnnouncements
import app.parley.common.calls.CallAnnouncements.Phase
import app.parley.common.calls.CallAnnouncements.Say
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.R

/**
 * A live region that speaks "Call connected", "On hold", "Call resumed" and "Call ended" (or why it ended) as the
 * call changes ([CallAnnouncements]). Invisible and empty until the first change, so opening the screen and the
 * ticking timer stay quiet; it starts over for another call.
 */
@Composable
internal fun CallStateAnnouncer(call: CallUi, ended: Boolean) {
    val res = LocalResources.current
    val phase = phaseOf(call, ended)
    var last by remember(call.id) { mutableStateOf<Phase?>(null) }
    var spoken by remember(call.id) { mutableStateOf("") }
    LaunchedEffect(call.id, phase) {
        when (CallAnnouncements.on(last, phase)) {
            Say.CONNECTED -> spoken = res.getString(R.string.incall_announce_connected)
            Say.ON_HOLD -> spoken = res.getString(R.string.incall_status_on_hold)
            Say.RESUMED -> spoken = res.getString(R.string.incall_announce_resumed)
            Say.ENDED -> spoken = call.disconnectReason ?: res.getString(R.string.incall_call_ended)
            null -> Unit
        }
        last = phase
    }
    if (spoken.isNotEmpty()) {
        Box(
            Modifier.size(1.dp).semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = spoken
            },
        )
    }
}

private fun phaseOf(call: CallUi, ended: Boolean): Phase = when {
    ended || call.state == CallState.DISCONNECTED -> Phase.ENDED
    call.state == CallState.RINGING -> Phase.RINGING
    call.state == CallState.DIALING || call.state == CallState.CONNECTING || call.state == CallState.NEW -> Phase.DIALLING
    call.state == CallState.ACTIVE -> Phase.ACTIVE
    call.state == CallState.HOLDING -> Phase.HOLDING
    else -> Phase.OTHER
}
