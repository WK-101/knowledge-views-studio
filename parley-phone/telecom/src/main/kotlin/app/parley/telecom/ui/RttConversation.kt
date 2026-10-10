package app.parley.telecom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.SpeakerNotesOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.common.calls.RttBubble
import app.parley.common.calls.RttSide
import app.parley.telecom.CallRtt
import app.parley.telecom.CallUi
import app.parley.telecom.R
import app.parley.telecom.RttMode
import app.parley.telecom.RttUi
import app.parley.ui.Banner
import app.parley.ui.ParleyShapes
import app.parley.ui.ParleySheet
import app.parley.ui.SheetTitle
import app.parley.ui.Spacing

/** The call's RTT state, or the default (not supported) while unknown. */
@Composable
internal fun rttOf(callId: String?): RttUi {
    val all by CallRtt.state.collectAsStateWithLifecycle()
    return callId?.let { all[it] } ?: RttUi()
}

/**
 * Under the caller: the other person's request to switch to RTT (Switch to RTT / Not now), "Asking to switch…",
 * a request that didn't work, or, with RTT on, the way back into the conversation. [onOpen] opens the sheet; it also
 * opens by itself the first time RTT comes on for the call.
 */
@Composable
internal fun RttCallCard(call: CallUi, onOpen: () -> Unit, autoOpened: MutableSet<String>) {
    val rtt = rttOf(call.id)
    LaunchedEffect(call.id, rtt.active) {
        if (rtt.active && autoOpened.add(call.id)) onOpen()
    }
    val scheme = MaterialTheme.colorScheme
    val request = rtt.incomingRequest
    when {
        request != null -> Surface(
            color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer, shape = ParleyShapes.card,
            modifier = Modifier.widthIn(max = CallButtonSize.panelMaxWidth).fillMaxWidth().padding(top = Spacing.m)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Keyboard, null)
                    Spacer(Modifier.width(Spacing.m))
                    Text(
                        call.name?.let { stringResource(R.string.rtt_request_named, it) } ?: stringResource(R.string.rtt_request),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(stringResource(R.string.rtt_request_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.xs))
                Row(Modifier.fillMaxWidth().padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End)) {
                    TextButton({ CallRtt.respond(call.id, false) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.memory_skip)) }
                    Button({ CallRtt.respond(call.id, true) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.rtt_more_start)) }
                }
            }
        }
        rtt.failed -> Box(Modifier.padding(top = Spacing.m)) {
            Banner(
                stringResource(R.string.rtt_failed), icon = Icons.Rounded.Keyboard, onDismiss = { CallRtt.dismissFailure(call.id) },
            )
        }
        rtt.requesting -> Text(
            stringResource(R.string.rtt_requesting), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.m).semantics { liveRegion = LiveRegionMode.Polite },
        )
        rtt.active -> FilledTonalButton(onOpen, Modifier.padding(top = Spacing.m).heightIn(min = 48.dp)) {
            Icon(Icons.Rounded.Keyboard, null, Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.rtt_open))
        }
    }
}

/**
 * The RTT conversation, chat-like: their messages on the start side as they type them (an open one says it's
 * still being typed), yours on the end side. Each letter typed in the field goes out at once ([CallRtt.type]); Send
 * ends the message. Audio mode, Save to the call's note (only when tapped) and Turn off RTT. After the call ends it
 * stays readable, and savable, until closed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RttSheet(call: CallUi, onDismiss: () -> Unit) {
    val rtt = rttOf(call.id)
    val them = call.name ?: stringResource(R.string.rtt_them)
    val you = stringResource(R.string.rtt_you)
    ParleySheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(SHEET_HEIGHT).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(end = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                SheetTitle(stringResource(R.string.rtt_more_open), Modifier.weight(1f))
                if (!rtt.transcript.isEmpty) {
                    val saveLabel = stringResource(if (rtt.saved) R.string.rtt_saved else R.string.rtt_save)
                    TextButton({ CallRtt.save(call.id, them, you) }, enabled = !rtt.saved, modifier = Modifier.heightIn(min = 48.dp)) {
                        if (rtt.saved) Icon(Icons.Rounded.BookmarkAdded, null, Modifier.size(18.dp).padding(end = Spacing.xs))
                        Text(saveLabel)
                    }
                }
                if (rtt.active) {
                    IconButton({ CallRtt.stop(call.id) }) { Icon(Icons.Rounded.SpeakerNotesOff, stringResource(R.string.rtt_stop)) }
                }
            }
            if (rtt.active) ModeChips(call.id, rtt.mode)
            Transcript(rtt, them, you, Modifier.weight(1f))
            when {
                rtt.active -> TypingField(call.id, rtt)
                rtt.ended -> Note(stringResource(R.string.rtt_ended))
                else -> Note(stringResource(R.string.rtt_off))
            }
        }
    }
}

@Composable
private fun ModeChips(callId: String, mode: RttMode) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.audio_button), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RttMode.entries.forEach { m ->
            FilterChip(
                selected = m == mode, onClick = { CallRtt.setMode(callId, m) },
                label = {
                    Text(
                        stringResource(
                            when (m) {
                                RttMode.FULL -> R.string.rtt_mode_full
                                RttMode.HCO -> R.string.rtt_mode_hco
                                RttMode.VCO -> R.string.rtt_mode_vco
                            },
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun Transcript(rtt: RttUi, them: String, you: String, modifier: Modifier) {
    val bubbles = rtt.transcript.bubbles.filter { it.text.isNotEmpty() }
    val list = rememberLazyListState()
    // The newest text stays in view as it streams in.
    val size = bubbles.size
    val lastLength = bubbles.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(size, lastLength) { if (size > 0) list.animateScrollToItem(size - 1) }
    if (bubbles.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(horizontal = Spacing.xl), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.rtt_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val lastFinishedTheirs = bubbles.indexOfLast { it.side == RttSide.THEM && !it.open }
    LazyColumn(
        modifier.fillMaxWidth(), state = list,
        contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        itemsIndexed(bubbles) { i, b -> Bubble(b, if (b.side == RttSide.THEM) them else you, announce = i == lastFinishedTheirs) }
    }
}

@Composable
private fun Bubble(b: RttBubble, who: String, announce: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val mine = b.side == RttSide.ME
    val spoken = stringResource(R.string.rtt_bubble, if (b.open) stringResource(R.string.rtt_typing, who) else who, b.text)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (mine) scheme.primaryContainer else scheme.surfaceContainerHighest,
            contentColor = if (mine) scheme.onPrimaryContainer else scheme.onSurface,
            shape = ParleyShapes.card,
            modifier = Modifier.widthIn(max = BUBBLE_MAX).clearAndSetSemantics {
                contentDescription = spoken
                // Their finished message is read out once; letters being typed aren't spelled out one by one.
                if (announce) liveRegion = LiveRegionMode.Polite
            },
        ) {
            Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) {
                // Plain text only: it comes from the other phone.
                Text(b.text, style = MaterialTheme.typography.bodyLarge)
                if (b.open && !mine) {
                    Text(stringResource(R.string.rtt_typing_label), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TypingField(callId: String, rtt: RttUi) {
    var field by remember(callId) { mutableStateOf(TextFieldValue(rtt.typing, TextRange(rtt.typing.length))) }
    // Send (or the call side) cleared the message: the field follows.
    LaunchedEffect(rtt.typing) { if (rtt.typing.isEmpty() && field.text.isNotEmpty()) field = TextFieldValue("") }
    fun send() {
        if (field.text.isBlank()) return
        CallRtt.endMessage(callId)
        field = TextFieldValue("")
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = field,
            onValueChange = { v ->
                field = v
                if (v.text != rtt.typing) CallRtt.type(callId, v.text)
            },
            placeholder = { Text(stringResource(R.string.rtt_field_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            shape = ParleyShapes.control,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Spacing.s))
        FilledIconButton(::send, enabled = field.text.isNotBlank(), modifier = Modifier.size(56.dp)) {
            Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.incall_send))
        }
    }
    Spacer(Modifier.height(Spacing.s))
}

@Composable
private fun Note(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.l),
    )
}

private const val SHEET_HEIGHT = 0.92f
private val BUBBLE_MAX = 320.dp
