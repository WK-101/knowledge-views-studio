package app.parley.telecom.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.parley.common.calls.CallerLine
import app.parley.common.calls.RingingLines
import app.parley.common.circle.Agenda
import app.parley.common.circle.Occasion
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.R
import app.parley.ui.Spacing

/**
 * The lines under the caller that may show now ([RingingLines]): while [call] rings, at most three, safety first, then
 * who it is, then what to talk about; once answered, all of them (each line still decides for itself whether it has
 * anything to say). [locked] is whether the keyguard is up, for the lines that hide on the lock screen.
 */
internal fun callerLinesFor(call: CallUi?, ended: Boolean, locked: Boolean): Set<CallerLine> {
    if (call == null || ended || call.state != CallState.RINGING) return CallerLine.entries.toSet()
    return RingingLines.shown(ringingLines(call, locked), ringing = true)
}

/** Which lines [call] has to show while it rings, by the same rules each line applies. */
internal fun ringingLines(call: CallUi, locked: Boolean): Set<CallerLine> =
    safetyLines(call, locked) + whoLines(call, locked) + agendaLine(call, locked)

private fun safetyLines(call: CallUi, locked: Boolean): Set<CallerLine> = buildSet {
    if (call.neverCallsYouNotice) add(CallerLine.NEVER_CALLS_YOU)
    if (call.freshNumberNotice) add(CallerLine.FRESH_NUMBER)
    if (call.reputation != null) add(CallerLine.REPUTATION)
    val rang = if (locked) call.rangThrough else call.rangThroughUnlocked ?: call.rangThrough
    if (rang != null && !call.silenced) add(CallerLine.RANG_THROUGH)
}

private fun whoLines(call: CallUi, locked: Boolean): Set<CallerLine> = buildSet {
    if (call.networkNameUnderSaved != null) add(CallerLine.NETWORK_NAME)
    if (call.nativeName != null) add(CallerLine.NATIVE_NAME)
    if (call.subject != null) add(CallerLine.SUBJECT)
    if (call.promisedAt != null && !locked) add(CallerLine.PROMISED)
    if (call.occasion != null) add(CallerLine.OCCASION)
    if (call.numberMemory != null) add(CallerLine.NUMBER_MEMORY)
    if (call.subtitle != null) add(CallerLine.SUBTITLE)
    if (call.pronouns != null) add(CallerLine.PRONOUNS)
    val memory = call.memory?.takeIf { it.onLockScreen || !locked }?.lastNote?.takeIf { it.isNotBlank() }
    if (listOfNotNull(call.context, call.note, call.lastCall, memory).isNotEmpty()) add(CallerLine.CALLER_CARD)
}

private fun agendaLine(call: CallUi, locked: Boolean): Set<CallerLine> {
    val agenda = CallAgendas.calls[call.id]?.takeIf { agendaApplies(call) } ?: return emptySet()
    val shown = Agenda.shown(agenda.items.size, locked, agenda.agenda.textOnLockScreen, call.lockMasked, agenda.agenda.privateContact)
    return if (shown == Agenda.Shown.NOTHING) emptySet() else setOf(CallerLine.AGENDA)
}

/**
 * "Birthday today" or "Anniversary today": one quiet line under a contact who calls or is called on the day. The lookup
 * leaves it out on the lock screen unless notes may show there (the call's lock-screen form drops it).
 */
@Composable
internal fun OccasionLine(call: CallUi) {
    val occasion = call.occasion ?: return
    val text = stringResource(if (occasion == Occasion.BIRTHDAY) R.string.incall_birthday_today else R.string.incall_anniversary_today)
    Row(Modifier.padding(top = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Cake, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(Spacing.xs))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
