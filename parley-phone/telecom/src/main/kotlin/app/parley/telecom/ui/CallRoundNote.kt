package app.parley.telecom.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.parley.common.catching
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.R
import app.parley.telecom.TelecomGraph
import app.parley.ui.ParleyShapes
import app.parley.ui.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A call the round's note may show on: a number you can see, under way, never an emergency call or a rescue call. */
internal fun roundNoteApplies(call: CallUi): Boolean =
    agendaApplies(call) && call.isLive && call.state != CallState.RINGING

/**
 * "Call round: tell them …" under the caller, while you call someone in the round you are working through: the one
 * thing you set out to say. Never on the lock screen (not even a count: a round is often about something sad or
 * private), never while the call rings, and nothing when the app says it may not show (a duress unlock).
 */
@Composable
internal fun CallRoundNote(call: CallUi, modifier: Modifier = Modifier) {
    val applies = roundNoteApplies(call) && !call.lockMasked
    val locked = rememberKeyguardLocked()
    val note by produceState<String?>(null, call.id, applies, locked) {
        value = if (!applies || locked) {
            null
        } else {
            val number = call.number.orEmpty()
            catching { withContext(Dispatchers.IO) { TelecomGraph.dependencies.roundNoteFor(number, call.accountId) } }.getOrNull()
        }
    }
    val text = note?.takeIf { applies && !locked } ?: return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = ParleyShapes.card,
        modifier = modifier.widthIn(max = 480.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 32.dp)) {
                Icon(Icons.AutoMirrored.Rounded.Chat, null, Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.m))
                Text(
                    stringResource(R.string.incall_round_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 18.dp + Spacing.m))
        }
    }
}
