package app.parley.ui.calltime

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.telecom.CallClock
import app.parley.telecom.CallManager
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.ui.InCallActivity
import app.parley.ui.CallColors
import kotlinx.coroutines.delay

/**
 * "● On call with Ana · 03:12 · Return" above the bottom navigation whenever a call exists (A3). With a time
 * limit it counts down instead (T3). Right after dialling, before Telecom has the call, it says which SIM the
 * call goes out on (A10).
 */
@Composable
fun ReturnToCallChip(modifier: Modifier = Modifier) {
    val calls by CallManager.state.collectAsStateWithLifecycle()
    val pending by CallManager.pendingOutgoing.collectAsStateWithLifecycle()
    val timings by CallClock.timings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val live = calls.filter { it.isLive }
    val call = live.firstOrNull { it.state == CallState.ACTIVE } ?: live.firstOrNull()
    val visible = call != null || pending != null
    AnimatedVisibility(visible, modifier = modifier, enter = expandVertically(), exit = shrinkVertically()) {
        val now by produceState(SystemClock.elapsedRealtime()) {
            while (true) {
                value = SystemClock.elapsedRealtime()
                delay(500)
            }
        }
        val (text, spoken) = when {
            call != null -> describe(call, live.size, timings[call.id]?.remainingMs(now))
            else -> {
                val p = pending
                val t = p?.simLabel?.let { "Calling via $it…" } ?: "Calling…"
                t to t
            }
        }
        val color = if (call?.state == CallState.RINGING) MaterialTheme.colorScheme.tertiary else CallColors.Accept
        Surface(
            color = color,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClickLabel = "Return to call") { context.startActivity(InCallActivity.intent(context, false)) }
                .semantics(mergeDescendants = true) { contentDescription = spoken },
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                Spacer(Modifier.width(10.dp))
                Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text("Return", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun describe(call: CallUi, count: Int, remainingMs: Long?): Pair<String, String> {
    val who = call.title
    val more = if (count > 1) " +${count - 1}" else ""
    val status = when (call.state) {
        CallState.RINGING -> "Incoming call from $who"
        CallState.DIALING, CallState.CONNECTING, CallState.NEW, CallState.SELECT_ACCOUNT -> "Calling $who…"
        CallState.HOLDING -> "$who on hold"
        else -> "On call with $who"
    }
    val time = when {
        remainingMs != null -> clock(remainingMs / 1000) + " left"
        call.state == CallState.ACTIVE && call.connectTimeMillis > 0 -> clock((System.currentTimeMillis() - call.connectTimeMillis) / 1000)
        else -> null
    }
    val text = listOfNotNull(status + more, time).joinToString(" · ")
    return text to text
}

private fun clock(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}
