package app.parley.telecom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.parley.common.ux.Tips
import app.parley.telecom.CallManager
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.R
import app.parley.telecom.TelecomGraph
import app.parley.ui.ParleyListItem
import app.parley.ui.Spacing
import app.parley.ui.rowColors

/**
 * I11: "Drive profile on", a quiet line under the caller while a car the user marked is connected, with a one-line
 * explainer the first time (P18). Not a warning: icon and text in the calm secondary colour.
 */
@Composable
internal fun DriveStatusLine(call: CallUi?, keypadOpen: Boolean) {
    // Hidden with the keypad open in a call (the caller collapses then), shown while it rings.
    if (call == null || !call.driving || !call.isLive) return
    if (keypadOpen && call.state != CallState.RINGING) return
    val firstTime = remember { runCatching { !TelecomGraph.dependencies.tipSeen(Tips.DRIVE_PROFILE) }.getOrDefault(false) }
    LaunchedEffect(Unit) { if (firstTime) runCatching { TelecomGraph.dependencies.markTipSeen(Tips.DRIVE_PROFILE) } }
    Column(Modifier.padding(top = Spacing.xs).widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.DirectionsCar, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Spacing.xs))
            Text(stringResource(R.string.drive_status), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (firstTime) {
            Text(
                stringResource(R.string.drive_status_tip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = Spacing.xxs),
            )
        }
    }
}

/** I11: the "Driving" replies, first in the reply sheet while the car is connected. */
@Composable
internal fun DrivingReplies(call: CallUi, onSent: () -> Unit) {
    if (!call.driving) return
    Text(
        stringResource(R.string.drive_replies_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s).semantics { heading() },
    )
    stringArrayResource(R.array.drive_replies).forEach { msg ->
        ParleyListItem(
            headlineContent = { Text(msg) },
            leadingContent = { Icon(Icons.Rounded.DirectionsCar, null) },
            colors = rowColors(),
            modifier = Modifier.clickable { CallManager.reject(call.id, msg); onSent() },
        )
    }
}
