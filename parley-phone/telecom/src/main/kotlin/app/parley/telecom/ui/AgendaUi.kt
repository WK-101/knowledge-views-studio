package app.parley.telecom.ui

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.common.catching
import app.parley.common.circle.Agenda
import app.parley.telecom.AgendaAdded
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.CallerAgenda
import app.parley.telecom.R
import app.parley.telecom.TelecomGraph
import app.parley.ui.ConfirmDialog
import app.parley.ui.ParleyMotion
import app.parley.ui.ParleyShapes
import app.parley.ui.Spacing
import app.parley.ui.showMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ticks and additions outlive the card that started them (the call may end meanwhile). */
private val agendaScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

/**
 * A call the agenda applies to: a number that isn't hidden, never an emergency call, a conference or a rescue call
 * (which reads, ticks and adds nothing).
 */
internal fun agendaApplies(call: CallUi): Boolean =
    !call.hidden && !call.isEmergency && !call.isConference && !call.simulated && !call.number.isNullOrBlank()

/**
 * The agenda of each call on the screen, from when it was read to after the call ends ("Did you cover these?").
 * In memory only, and only the last few calls.
 */
internal object CallAgendas {
    class State(val agenda: CallerAgenda, items: List<String>) {
        /** The items shown during this call (open when read), in order. */
        var items by mutableStateOf(items)

        /** The items ticked off from the call screen. */
        var ticked by mutableStateOf(emptySet<String>())

        /** What the call screen last showed of it. */
        var shown by mutableStateOf(Agenda.Shown.NOTHING)

        /** "Did you cover these?" was answered. */
        var answered by mutableStateOf(false)
    }

    val calls = mutableStateMapOf<String, State>()

    /** Keeps what was ticked when the agenda is read again (after an unlock). */
    fun loaded(callId: String, agenda: CallerAgenda?) {
        val old = calls[callId]
        if (agenda == null) {
            if (old == null) return
            // Nothing readable now (locked again): what was shown stays for the end of the call, hidden meanwhile.
            old.shown = Agenda.Shown.NOTHING
            return
        }
        val next = State(agenda, ((old?.items ?: emptyList()) + agenda.items).distinct())
        if (old != null) {
            next.ticked = old.ticked
            next.answered = old.answered
        }
        calls[callId] = next
        // Only the last few calls are kept, oldest out first.
        order.remove(callId)
        order.addLast(callId)
        while (order.size > KEPT) calls.remove(order.removeFirst())
    }

    /** The calls in [calls], oldest first. */
    private val order = ArrayDeque<String>()

    @VisibleForTesting
    fun forgetForTest() {
        calls.clear()
        order.clear()
    }

    /** The items "Did you cover these?" asks about after [call]. */
    fun toAsk(call: CallUi): List<String> {
        val s = calls[call.id] ?: return emptyList()
        return Agenda.toAskAfter(s.items, s.ticked, s.items.filterNot { it in s.ticked }, connected = call.connectTimeMillis > 0)
    }

    /** "Did you cover these?" shows after [call]: items to ask about, shown in full during the call, not answered yet. */
    fun asksAfter(call: CallUi?): Boolean {
        val s = call?.let { calls[it.id] } ?: return false
        return !s.answered && s.shown == Agenda.Shown.ITEMS && agendaApplies(call) && toAsk(call).isNotEmpty()
    }

    private const val KEPT = 4
}

/** Ticks [text] off (or opens it again) for [call], at once on screen; back as it was when it can't be saved. */
private fun toggle(context: Context, call: CallUi, s: CallAgendas.State, text: String, done: Boolean, failed: String) {
    s.ticked = if (done) s.ticked + text else s.ticked - text
    val number = call.number ?: return
    agendaScope.launch {
        val ok = catching { withContext(Dispatchers.IO) { TelecomGraph.dependencies.setAgendaDone(number, call.accountId, text, done) } }.getOrDefault(false)
        if (!ok) {
            s.ticked = if (done) s.ticked - text else s.ticked + text
            catching { showMessage(context, failed) }
        }
    }
}

/**
 * "To talk about" under the caller: the open items of the person on the line, when you call them or they call you.
 * Compact (the first two, "Show 3 more"), each ticked off with one tap. Unlocked: the items. On the lock screen: the
 * items only when notes may show there, else how many; nothing for a private contact or a masked caller
 * ([Agenda.shown]). Read once the screen is up and again when the phone is unlocked; never slows the ringing.
 */
@Composable
internal fun AgendaCard(call: CallUi, modifier: Modifier = Modifier) {
    val applies = agendaApplies(call) && call.isLive
    val locked = rememberKeyguardLocked()
    LaunchedEffect(call.id, applies, locked) {
        if (!applies) return@LaunchedEffect
        val number = call.number.orEmpty()
        val agenda = catching { withContext(Dispatchers.IO) { TelecomGraph.dependencies.agendaFor(number, call.accountId) } }.getOrNull()
        CallAgendas.loaded(call.id, agenda)
    }
    val s = CallAgendas.calls[call.id] ?: return
    val open = s.items.filterNot { it in s.ticked }
    val shown = Agenda.shown(s.items.size, locked, s.agenda.textOnLockScreen, call.lockMasked, s.agenda.privateContact)
    LaunchedEffect(s, shown) { s.shown = shown }
    if (!applies || shown == Agenda.Shown.NOTHING) return
    var expanded by rememberSaveable(call.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val failed = stringResource(R.string.incall_agenda_tick_failed)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = ParleyShapes.card,
        modifier = modifier.widthIn(max = 480.dp).fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m).animateContentSize(ParleyMotion.spatial()),
        ) {
            val count = pluralStringResource(R.plurals.incall_agenda_count, open.size, open.size)
            AgendaHeading(if (shown == Agenda.Shown.COUNT) count else stringResource(R.string.incall_agenda_title))
            if (shown == Agenda.Shown.COUNT) {
                Text(
                    stringResource(R.string.incall_agenda_unlock_to_see), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = ICON_INSET),
                )
                return@Column
            }
            // Ringing: only the first two (the answer controls need the room).
            val compact = !expanded || call.state == CallState.RINGING
            Agenda.visible(s.items, !compact).forEach { item ->
                AgendaRow(item, checked = item in s.ticked) { done -> toggle(context, call, s, item, done, failed) }
            }
            val more = Agenda.moreThanShown(s.items, !compact)
            if (call.state != CallState.RINGING && (more > 0 || expanded)) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.heightIn(min = 48.dp).padding(start = ICON_INSET - Spacing.m),
                ) {
                    Text(if (expanded) stringResource(R.string.incall_agenda_show_less) else pluralStringResource(R.plurals.incall_agenda_show_all, more, more))
                }
            }
        }
    }
}

@Composable
private fun AgendaHeading(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 32.dp)) {
        Icon(Icons.Rounded.Checklist, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.m))
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
    }
}

/** One item: the whole row ticks it off (or back on), at least 48dp tall. */
@Composable
private fun AgendaRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(end = Spacing.m))
        Text(
            text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textDecoration = if (checked) TextDecoration.LineThrough else null,
            color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * "Did you cover these?" on the call-ended screen: the items that were shown during the call and weren't ticked off.
 * Tick what was talked about; **Keep for next time** leaves the rest open, **All covered** ticks them all. Shown only
 * after a call that connected, and only where the items could show during it. [onAnswered] runs once it's answered.
 */
@Composable
internal fun AgendaAfterCallCard(call: CallUi, onTouched: () -> Unit, onAnswered: () -> Unit) {
    val s = CallAgendas.calls[call.id] ?: return
    // The list is fixed when the card appears, so a ticked item stays (ticked) instead of jumping away.
    val asked = remember(call.id) { CallAgendas.toAsk(call) }
    val locked = rememberKeyguardLocked()
    if (asked.isEmpty() || Agenda.shown(asked.size, locked, s.agenda.textOnLockScreen, call.lockMasked, s.agenda.privateContact) != Agenda.Shown.ITEMS) {
        return
    }
    val failed = stringResource(R.string.incall_agenda_tick_failed)
    val context = LocalContext.current
    fun answer() {
        s.answered = true
        onAnswered()
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = ParleyShapes.sheet,
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            // Any touch keeps the screen up, like the post-call card.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        onTouched()
                    }
                }
            },
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                stringResource(R.string.incall_agenda_after_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.incall_agenda_after_body), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            asked.forEach { item -> AgendaRow(item, checked = item in s.ticked) { done -> toggle(context, call, s, item, done, failed) } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({
                    asked.filterNot { it in s.ticked }.forEach { toggle(context, call, s, it, true, failed) }
                    answer()
                }) { Text(stringResource(R.string.incall_agenda_after_all)) }
                TextButton(::answer) { Text(stringResource(R.string.incall_agenda_after_keep)) }
            }
        }
    }
}

/**
 * More › "Add something to talk about": one line for next time with the person on the line. Saved without unlocking
 * for a contact or a number (like a note during the call); a private contact's agenda needs private contacts unlocked.
 */
@Composable
internal fun AgendaAddDialog(call: CallUi, onDone: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    val said = mapOf(
        AgendaAdded.ADDED to stringResource(R.string.incall_agenda_added),
        AgendaAdded.ALREADY to stringResource(R.string.incall_agenda_already),
        AgendaAdded.LOCKED to stringResource(R.string.incall_agenda_locked),
        AgendaAdded.FAILED to stringResource(R.string.incall_agenda_add_failed),
    )
    ConfirmDialog(
        title = stringResource(R.string.incall_agenda_add_title),
        text = null,
        confirmLabel = stringResource(R.string.incall_agenda_add_save),
        confirmEnabled = Agenda.clean(text) != null,
        onConfirm = {
            val number = call.number
            val item = text
            onDone()
            if (number != null) {
                agendaScope.launch {
                    val result = catching { withContext(Dispatchers.IO) { TelecomGraph.dependencies.addAgendaItem(number, call.accountId, item) } }
                        .getOrDefault(AgendaAdded.FAILED)
                    catching { showMessage(context, said.getValue(result)) }
                }
            }
        },
        onDismiss = onDone,
        content = {
            OutlinedTextField(
                text, { text = it.take(Agenda.MAX_LENGTH) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.incall_agenda_add_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/** Where an item's text starts: past the heading's icon. */
private val ICON_INSET = 18.dp + Spacing.m
