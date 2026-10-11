package app.parley.telecom.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.common.calls.CallerLine
import app.parley.telecom.CallUi
import app.parley.telecom.R
import app.parley.ui.Spacing

/**
 * Number memory on the call screen: one quiet line under a caller who isn't a contact ("You deleted Plumber Mike in
 * March with this number"). On the lock screen it says only "Parley knows this number": names and notes appear once the
 * phone is unlocked (re-checked while the screen is up).
 */
@Composable
internal fun NumberMemoryHint(call: CallUi) {
    val text = memoryText(call) ?: return
    Row(Modifier.padding(top = Spacing.s).widthIn(max = 480.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.History, null, Modifier.padding(top = Spacing.xxs).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The quiet lines under a caller: what Parley remembers about the number, a time you promised them and a birthday
 * today, each only when [lines] keeps it (while ringing, at most three lines show).
 */
@Composable
internal fun CallerHints(call: CallUi, lines: Set<CallerLine>) {
    if (CallerLine.NUMBER_MEMORY in lines) NumberMemoryHint(call)
    // Under a safety card the promise follows it (InCallScreen's SafetyLine): the safety line comes first.
    if (!call.hasSafetyCard && CallerLine.PROMISED in lines) PromisedHint(call)
    if (CallerLine.OCCASION in lines) OccasionLine(call)
}

/**
 * "You said you'd call at 15:30": a caller you promised a time to rings first. Only while the phone is unlocked, like
 * any note about them; nothing on the lock screen.
 */
@Composable
internal fun PromisedHint(call: CallUi) {
    val at = call.promisedAt ?: return
    if (rememberKeyguardLocked()) return
    val context = LocalContext.current
    Row(Modifier.padding(top = Spacing.s).widthIn(max = 480.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.Schedule, null, Modifier.padding(top = Spacing.xxs).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.xs))
        Text(
            stringResource(R.string.promised_line, RemindTimes.whenText(context, at)), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The same line on the post-call card, with a way into Parley: the number's history, where the line offers its
 * action (Restore contact, Open note, Open snapshot). Opening it asks to unlock first.
 */
@Composable
internal fun NumberMemoryPostCall(call: CallUi, onOpen: () -> Unit) {
    val text = memoryText(call) ?: return
    Row(Modifier.fillMaxWidth().padding(top = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.History, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.s))
        Text(
            text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onOpen) { Text(stringResource(R.string.number_memory_open)) }
    }
}

@Composable
private fun memoryText(call: CallUi): String? {
    val line = call.numberMemory ?: return null
    return if (rememberKeyguardLocked()) stringResource(R.string.number_memory_locked) else line.text
}
