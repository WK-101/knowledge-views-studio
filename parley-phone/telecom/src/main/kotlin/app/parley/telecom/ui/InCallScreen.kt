package app.parley.telecom.ui

import android.annotation.SuppressLint
import android.telecom.TelecomManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.parley.telecom.R
import app.parley.ui.Bidi
import app.parley.ui.ForceLtr
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.common.AnswerGesture
import app.parley.common.Verification
import app.parley.telecom.AudioRoute
import app.parley.telecom.AudioUi
import app.parley.telecom.CallClock
import app.parley.telecom.CallManager
import app.parley.telecom.CallState
import app.parley.telecom.CallTiming
import app.parley.telecom.CallUi
import app.parley.telecom.DeclineBlock
import app.parley.telecom.live
import app.parley.common.calls.CallWaiting
import app.parley.telecom.RouteType
import app.parley.ui.Avatar
import app.parley.ui.CallColors
import app.parley.ui.keypadKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InCallScreen(
    calls: List<CallUi>,
    audio: AudioUi,
    ended: CallUi?,
    answerGesture: AnswerGesture,
    quickReplies: List<String>,
    keypadOpen: Boolean,
    onKeypad: (Boolean) -> Unit,
    onAddCall: () -> Unit,
    onOpenContact: (CallUi) -> Unit,
    /** V4: what the user did on the post-call card (touching it at all keeps the screen up). */
    onPostCall: (PostCallChoice) -> Unit = {},
    /** P6: an outgoing call that didn't go through, with its reason, until dismissed. */
    failed: CallUi? = null,
    onRetry: (CallUi) -> Unit = {},
    onDismissFailure: (CallUi) -> Unit = {},
    /** P2: the call just declined with "Block & decline" (Undo). */
    declineBlock: DeclineBlock? = null,
    onUndoBlock: () -> Unit = {},
    /** X4 simple mode: large buttons and (optionally) a question before declining. */
    simple: Boolean = false,
    confirmDecline: Boolean = false,
) {
    val live = calls.filter { it.isLive }
    // A1/P9: which call is in front and whether a second one is waiting (pure logic in core:common).
    val slots = CallWaiting.slots(live) { it.state.live() }
    val primary = slots.primary
    val others = live.filter { it.id != primary?.id }
    // P6: a call that just ended (still in Telecom, or already gone) keeps its name; never an older call's.
    val endedNow = calls.firstOrNull { !it.isLive }
    val shown = primary ?: endedNow?.let { c -> ended?.takeIf { it.id == c.id } ?: c } ?: ended ?: calls.firstOrNull()
    val timings by CallClock.timings.collectAsStateWithLifecycle()

    var routeSheet by remember { mutableStateOf(false) }
    var replyFor by remember { mutableStateOf<String?>(null) }
    var manageSheet by remember { mutableStateOf(false) }
    var moreSheet by remember { mutableStateOf(false) }
    var noteFor by remember { mutableStateOf<String?>(null) }

    // Call waiting (A1): a ringing call while another call is active or held.
    val held = slots.held
    val current = slots.current
    val waiting = slots.waiting && current != null && primary != null

    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(scheme.primaryContainer.copy(alpha = 0.55f), scheme.surface, scheme.surface))),
    ) {
        // Landscape phones and unfolded foldables: caller on the left, controls on the right (A9).
        // The caller's own call-screen picture (C14), under a theme-coloured scrim so every control stays legible.
        CallBackground(primary?.backgroundUri, scheme.surface)
        val twoPane = maxWidth > maxHeight && maxWidth >= 560.dp
        val short = maxHeight < 480.dp
        val insets = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().displayCutoutPadding()

        if (waiting && current != null && primary != null) {
            val top: @Composable ColumnScope.() -> Unit = {
                CurrentCallCard(current, canHold = current.canHold && held.isEmpty())
                held.filter { it.id != current.id }.forEach { OnHoldStrip(it, current) }
            }
            if (twoPane) {
                Row(insets.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), content = top)
                    Box(Modifier.weight(1f)) { CallWaitingSheet(primary, current, held.size) { replyFor = primary.id } }
                }
            } else {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), content = top)
                    Spacer(Modifier.weight(1f))
                    CallWaitingSheet(primary, current, held.size) { replyFor = primary.id }
                }
            }
        } else {
            val header: @Composable ColumnScope.(Dp) -> Unit = { avatar ->
                // P6: a second call that didn't go through ("Add call"), shown above the call that goes on.
                if (primary != null && failed != null) FailureBanner(failed, { onRetry(failed) }, { onDismissFailure(failed) }, Modifier.padding(top = 12.dp))
                others.forEach { other ->
                    if (other.state == CallState.HOLDING) OnHoldStrip(other, primary)
                    else OtherCallBanner(other, onSwap = { primary?.let { CallManager.swap(it.id) } })
                }
                Spacer(Modifier.height(if (others.isEmpty() && !twoPane) 48.dp else 16.dp))
                if (shown != null) {
                    CallerHeader(
                        call = shown,
                        ended = primary == null,
                        onOpenContact = onOpenContact,
                        compact = keypadOpen && primary?.state != CallState.RINGING,
                        timing = timings[shown.id],
                        avatarSize = avatar,
                        onReply = { replyFor = shown.id },
                    )
                }
            }
            val controls: @Composable ColumnScope.(Boolean) -> Unit = { scrollKeypad ->
                when {
                    primary == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val endedCall = shown ?: ended
                        val card = declineBlock != null || (failed == null && (ended?.postCallCard == true || ended?.memoryCard == true))
                        if (failed != null) {
                            // P6: the reason and Retry, until dismissed.
                            FailureBanner(failed, { onRetry(failed) }, { onDismissFailure(failed) }, Modifier.padding(bottom = if (card) 16.dp else 48.dp))
                        } else {
                            Text(
                                endedCall?.disconnectReason ?: stringResource(R.string.incall_call_ended),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = if (card) 16.dp else 64.dp),
                            )
                        }
                        when {
                            // P2: "Blocked and declined", with Undo.
                            declineBlock != null -> DeclineBlockCard(declineBlock, onUndo = onUndoBlock, onDone = { onPostCall(PostCallChoice.Done) })
                            // V4: block, save, message or report an unknown number right after the call.
                            failed == null && ended != null && ended.postCallCard -> PostCallCard(ended, onChoice = onPostCall)
                            // R8: "Anything to remember?" after a call with a contact (opt-in).
                            failed == null && ended != null && ended.memoryCard -> MemoryCard(ended, onChoice = onPostCall)
                        }
                    }
                    primary.state == CallState.RINGING -> IncomingControls(
                        call = primary,
                        gesture = answerGesture,
                        hasActiveCall = others.any { it.state == CallState.ACTIVE },
                        onMessage = { replyFor = primary.id },
                        onBlockAndDecline = if (primary.canBlockAndDecline && !simple) ({ CallManager.blockAndDecline(primary.id) }) else null,
                        simple = simple,
                        confirmDecline = confirmDecline,
                    )
                    primary.state == CallState.SELECT_ACCOUNT -> SimPicker(primary)
                    else -> {
                        AnimatedContent(keypadOpen, label = "keypad") { open ->
                            if (open) {
                                DtmfKeypad(callId = primary.id, onClose = { onKeypad(false) }, scroll = scrollKeypad)
                            } else {
                                ControlGrid(
                                    call = primary,
                                    others = others,
                                    audio = audio,
                                    onKeypad = { onKeypad(true) },
                                    onAudio = { if (audio.hasExternal) routeSheet = true else CallManager.toggleSpeaker() },
                                    onAddCall = onAddCall,
                                    onManage = { manageSheet = true },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val moreOptions = stringResource(R.string.incall_more_options)
                            TextButton(onClick = { noteFor = primary.id }) { Text(stringResource(R.string.incall_add_note)) }
                            TextButton(onClick = { moreSheet = true }, modifier = Modifier.semantics { contentDescription = moreOptions }) {
                                Icon(Icons.Rounded.MoreHoriz, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.incall_more))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        EndCallButton { CallManager.hangup(primary.id) }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
            if (twoPane) {
                Row(insets.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Pane { header(if (short) 72.dp else 112.dp) }
                    Pane { controls(false) }
                }
            } else {
                Column(insets.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    header(112.dp)
                    Spacer(Modifier.weight(1f))
                    controls(true)
                }
            }
        }
    }

    noteFor?.let { id ->
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { noteFor = null },
            title = { Text(stringResource(R.string.incall_note_title)) },
            text = { androidx.compose.material3.OutlinedTextField(text, { text = it }, minLines = 3, placeholder = { Text(stringResource(R.string.incall_note_placeholder)) }) },
            confirmButton = { TextButton({ if (text.isNotBlank()) CallManager.saveNote(id, text.trim()); noteFor = null }) { Text(stringResource(R.string.tc_save)) } },
            dismissButton = { TextButton({ noteFor = null }) { Text(stringResource(R.string.tc_cancel)) } },
        )
    }

    val postDial = primary?.postDialWait
    if (postDial != null) {
        AlertDialog(
            onDismissRequest = { CallManager.postDialContinue(primary.id, false) },
            title = { Text(stringResource(R.string.incall_send_tones_title)) },
            text = { Text(Bidi.ltr(postDial)) },
            confirmButton = { TextButton({ CallManager.postDialContinue(primary.id, true) }) { Text(stringResource(R.string.incall_send)) } },
            dismissButton = { TextButton({ CallManager.postDialContinue(primary.id, false) }) { Text(stringResource(R.string.tc_cancel)) } },
        )
    }

    if (routeSheet) AudioRouteSheet(audio) { routeSheet = false }

    if (moreSheet && primary != null) {
        CallMoreSheet(
            call = primary,
            timing = timings[primary.id],
            onDismiss = { moreSheet = false },
            onNote = { noteFor = primary.id },
            onOpenContact = if (primary.hidden) null else ({ onOpenContact(primary) }),
        )
    }

    val replyCall = live.firstOrNull { it.id == replyFor && it.state == CallState.RINGING }
    if (replyCall != null) {
        ModalBottomSheet(onDismissRequest = { replyFor = null }) {
            Text(stringResource(R.string.incall_reply_sheet_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            // The defaults live in core/common in English: while unedited, send them in the user's language.
            val replies = if (quickReplies == app.parley.common.AppSettings.DEFAULT_QUICK_REPLIES) {
                androidx.compose.ui.res.stringArrayResource(R.array.incall_default_quick_replies).toList()
            } else {
                quickReplies
            }
            replies.forEach { msg ->
                ListItem(
                    headlineContent = { Text(msg) },
                    modifier = Modifier.clickable { CallManager.reject(replyCall.id, msg); replyFor = null },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    val conference = live.firstOrNull { it.isConference }
    if (manageSheet && conference != null) {
        ModalBottomSheet(onDismissRequest = { manageSheet = false }) {
            Text(stringResource(R.string.incall_conference_call), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            conference.children.forEach { child ->
                ListItem(
                    headlineContent = { Text(child.displayTitle) },
                    supportingContent = { child.number?.takeIf { child.name != null }?.let { Text(Bidi.ltr(it)) } },
                    leadingContent = { Avatar(child.title, child.photoUri, 40.dp) },
                    trailingContent = {
                        Row {
                            if (child.canSeparate) TextButton({ CallManager.separate(child.id); manageSheet = false }) { Text(stringResource(R.string.incall_private)) }
                            if (child.canDisconnectChild) TextButton({ CallManager.hangup(child.id) }) { Text(stringResource(R.string.incall_end), color = CallColors.Decline) }
                        }
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One half of the two-pane layout: centred, and scrollable when it doesn't fit. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.Pane(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
        Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

@Composable
private fun CallerHeader(
    call: CallUi,
    ended: Boolean,
    onOpenContact: (CallUi) -> Unit,
    compact: Boolean,
    timing: CallTiming?,
    avatarSize: Dp,
    onReply: () -> Unit,
) {
    // TalkBack: while ringing, the caller's name offers answer and decline as actions (A12).
    val ringing = call.state == CallState.RINGING && !ended
    val res = LocalResources.current
    val a11y = if (!ringing) Modifier else Modifier.semantics(mergeDescendants = true) {
        customActions = buildList {
            add(CustomAccessibilityAction(res.getString(R.string.incall_answer)) { CallManager.answer(call.id); true })
            add(CustomAccessibilityAction(res.getString(R.string.incall_decline)) { CallManager.reject(call.id); true })
            if (!call.hidden && !call.number.isNullOrBlank()) add(CustomAccessibilityAction(res.getString(R.string.incall_reply_a11y)) { onReply(); true })
            if (!call.silenced) add(CustomAccessibilityAction(res.getString(R.string.incall_stop_ringing)) { CallManager.ignore(call.id); true })
            if (call.canBlockAndDecline) add(CustomAccessibilityAction(res.getString(R.string.incall_block_decline)) { CallManager.blockAndDecline(call.id); true })
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = a11y) {
        if (!compact) {
            CallTimeRing(if (ended) null else timing, avatarSize) {
                Avatar(
                    call.title, call.photoUri, size = avatarSize,
                    modifier = Modifier.clickable(enabled = !call.hidden, onClickLabel = stringResource(R.string.incall_open_contact)) { onOpenContact(call) },
                )
            }
            Spacer(Modifier.height(20.dp))
        }
        Text(
            call.displayTitle,
            style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val sub = listOfNotNull(call.label?.let { l -> if (app.parley.common.NotificationPrivacy.isVaultLabel(l)) stringResource(R.string.tc_private_label) else l }, call.number?.takeIf { call.name != null }?.let(Bidi::ltr)).joinToString(stringResource(R.string.tc_separator))
        if (sub.isNotEmpty()) {
            Text(sub, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        // I6: job/company and "who is this".
        call.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (!compact) call.context?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
        Spacer(Modifier.height(8.dp))
        StatusLine(call, ended)
        if (!ended && call.state != CallState.RINGING) RemainingLine(timing)
        // R8/R9: the last note and open promises, only while unlocked unless allowed on the lock screen.
        val memory = call.memory?.takeIf { !ended && !it.isEmpty && (it.onLockScreen || !rememberKeyguardLocked()) }
        if (!compact && (call.note != null || call.lastCall != null || memory != null)) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    call.note?.let { Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) }
                    memory?.let { MemoryLines(it) }
                    call.lastCall?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer) }
                }
            }
        }
        if (call.unknown && call.state == CallState.RINGING) {
            Text(listOfNotNull(stringResource(R.string.incall_not_in_contacts), call.location).joinToString(stringResource(R.string.tc_separator)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        // Screening verdict (B2): "Likely spam · FTC list", "Allowed by 'Plumber'".
        if (call.verdict != null && call.state == CallState.RINGING) {
            Text(
                call.verdict, style = MaterialTheme.typography.labelLarge,
                color = if (call.verdictWarn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (call.verdictWarn) FontWeight.Bold else null, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // While dialling, the SIM already shows in the status line.
            if (call.state != CallState.DIALING && call.state != CallState.CONNECTING && call.state != CallState.NEW) call.accountLabel?.let { Chip(Icons.Rounded.SimCard, it) }
            when (call.verification) {
                Verification.PASSED -> Chip(Icons.Rounded.Verified, stringResource(R.string.incall_verified_number))
                Verification.FAILED -> Chip(Icons.Rounded.Warning, stringResource(R.string.incall_possibly_spoofed), warn = true)
                Verification.NOT_VERIFIED -> Unit
            }
            if (call.isEmergency) Chip(Icons.Rounded.Warning, stringResource(R.string.incall_emergency_call), warn = true)
        }
    }
}

@Composable
private fun StatusLine(call: CallUi, ended: Boolean) {
    val text = when {
        ended -> call.disconnectReason ?: stringResource(R.string.incall_call_ended)
        call.silenced -> call.silenceReason ?: stringResource(R.string.call_silenced_rules)
        call.state == CallState.RINGING -> stringResource(R.string.notif_incoming_call)
        // The SIM the call goes out on, even before Telecom has settled on it (A10).
        call.state == CallState.DIALING || call.state == CallState.CONNECTING || call.state == CallState.NEW ->
            call.accountLabel?.let { stringResource(R.string.incall_status_calling_via, it) } ?: stringResource(R.string.incall_status_calling)
        call.state == CallState.HOLDING -> stringResource(R.string.incall_status_on_hold)
        call.state == CallState.SELECT_ACCOUNT -> stringResource(R.string.incall_status_choose_sim)
        call.state == CallState.DISCONNECTING -> stringResource(R.string.incall_status_ending)
        call.state == CallState.ACTIVE -> null
        else -> ""
    }
    if (text != null) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
    } else {
        CallTimer(call.connectTimeMillis)
    }
}

@Composable
private fun CallTimer(connectTime: Long) {
    val elapsed by rememberCallSeconds(connectTime)
    val txt = clockText(elapsed)
    val spoken = stringResource(R.string.incall_duration_description, spokenDuration(LocalResources.current, elapsed))
    Text(txt, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { contentDescription = spoken })
}

@Composable
private fun Chip(icon: ImageVector, text: String, warn: Boolean = false) {
    val color = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    Surface(color = color, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Another call that isn't on hold (being dialled, or active while this one is dialled). */
@Composable
private fun OtherCallBanner(call: CallUi, onSwap: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(20.dp)).clickable(enabled = call.state == CallState.HOLDING, onClick = onSwap),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(call.title, call.photoUri, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(call.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (call.state) {
                        CallState.ACTIVE -> stringResource(R.string.incall_other_active)
                        CallState.RINGING -> stringResource(R.string.incall_other_incoming)
                        else -> stringResource(R.string.incall_other_connecting)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ControlGrid(
    call: CallUi,
    others: List<CallUi>,
    audio: AudioUi,
    onKeypad: () -> Unit,
    onAudio: () -> Unit,
    onAddCall: () -> Unit,
    onManage: () -> Unit,
) {
    val audioBtn = audioButton(LocalResources.current, audio)
    val audioIcon = audio.current?.let { routeIcon(it) } ?: Icons.AutoMirrored.Rounded.VolumeUp
    val canSwap = others.any { it.state == CallState.HOLDING } || call.canSwap
    val held = call.state == CallState.HOLDING
    val controls = buildList {
        add(ControlSpec(Icons.Rounded.MicOff, if (audio.muted) stringResource(R.string.incall_muted) else stringResource(R.string.incall_mute), stringResource(R.string.incall_mute), toggle = true, active = audio.muted, enabled = call.canMute) { CallManager.setMuted(!audio.muted) })
        add(ControlSpec(Icons.Rounded.Dialpad, stringResource(R.string.incall_keypad), stringResource(R.string.incall_keypad), onClick = onKeypad))
        add(ControlSpec(audioIcon, audioBtn.label, audioBtn.spoken, toggle = audioBtn.isToggle, active = audioBtn.on, enabled = audio.routes.isNotEmpty(), onClick = onAudio))
        add(ControlSpec(Icons.Rounded.Pause, if (held) stringResource(R.string.incall_resume) else stringResource(R.string.incall_hold), stringResource(R.string.incall_hold), toggle = true, active = held, enabled = call.canHold) { CallManager.toggleHold(call.id) })
        when {
            call.canMerge -> add(ControlSpec(Icons.AutoMirrored.Rounded.CallMerge, stringResource(R.string.incall_merge), stringResource(R.string.incall_merge_calls)) { CallManager.merge(call.id) })
            canSwap -> add(ControlSpec(Icons.Rounded.SwapCalls, stringResource(R.string.incall_swap), stringResource(R.string.incall_swap_calls)) { CallManager.swap(call.id) })
            else -> add(ControlSpec(Icons.Rounded.Add, stringResource(R.string.incall_add_call), stringResource(R.string.incall_add_call), enabled = others.isEmpty(), onClick = onAddCall))
        }
        if (call.isConference) add(ControlSpec(Icons.Rounded.Groups, stringResource(R.string.incall_manage), stringResource(R.string.incall_manage_conference), onClick = onManage))
        else if (call.canMerge || canSwap) add(ControlSpec(Icons.Rounded.Add, stringResource(R.string.incall_add_call), stringResource(R.string.incall_add_call), onClick = onAddCall))
        else add(ControlSpec(Icons.Rounded.PhoneInTalk, stringResource(R.string.incall_contacts), stringResource(R.string.incall_contacts), onClick = onAddCall))
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        controls.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { ControlButton(it) }
            }
        }
    }
}

/**
 * One in-call button. [spoken] is the stable name TalkBack reads; toggles add their state, so TalkBack says
 * "Mute, off" rather than a label that flips between "Mute" and "Unmute" (A12).
 */
private data class ControlSpec(
    val icon: ImageVector,
    val label: String,
    val spoken: String,
    val toggle: Boolean = false,
    val active: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun ControlButton(spec: ControlSpec) {
    val scheme = MaterialTheme.colorScheme
    val on = stringResource(R.string.tc_on)
    val off = stringResource(R.string.tc_off)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        Box(
            Modifier
                .size(width = 80.dp, height = 64.dp)
                .clip(RoundedCornerShape(if (spec.active) 20.dp else 32.dp))
                .background(if (spec.active) scheme.primary else scheme.surfaceContainerHigh)
                .clickable(enabled = spec.enabled, role = if (spec.toggle) Role.Switch else Role.Button, onClick = spec.onClick)
                .semantics {
                    contentDescription = spec.spoken
                    if (spec.toggle) stateDescription = if (spec.active) on else off
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                spec.icon, null,
                tint = when {
                    !spec.enabled -> scheme.onSurface.copy(alpha = 0.38f)
                    spec.active -> scheme.onPrimary
                    else -> scheme.onSurface
                },
            )
        }
        Text(
            spec.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp),
            color = if (spec.enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EndCallButton(onClick: () -> Unit) {
    val endCall = stringResource(R.string.incall_end_call)
    Box(
        Modifier
            .size(width = 160.dp, height = 72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(CallColors.Decline)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = endCall },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.CallEnd, null, tint = Color.White, modifier = Modifier.size(32.dp))
    }
}

// Telephony calls here are covered by the default-dialer role and each one handles SecurityException.
@SuppressLint("MissingPermission")
@Composable
private fun SimPicker(call: CallUi) {
    val context = LocalContext.current
    val accounts = remember {
        try {
            val tm = context.getSystemService(TelecomManager::class.java)
            tm.callCapablePhoneAccounts.map { it.id to (tm.getPhoneAccount(it)?.label?.toString() ?: it.id) }
        } catch (_: SecurityException) {
            emptyList()
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.incall_call_with), style = MaterialTheme.typography.titleMedium)
        accounts.forEach { (id, label) ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { CallManager.selectAccount(call.id, id) },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SimCard, null)
                    Spacer(Modifier.width(12.dp))
                    Text(label, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        TextButton(onClick = { CallManager.hangup(call.id) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.tc_cancel)) }
    }
}

@Composable
private fun DtmfKeypad(callId: String, onClose: () -> Unit, scroll: Boolean = true) {
    var typed by rememberSaveable { mutableStateOf("") }
    // One running tone per key (V7): a key's release only stops its own tone.
    val tokens = remember { HashMap<Char, Long>() }
    // The keypad can close while a key is held (hidden, call ended): stop every tone it started.
    DisposableEffect(callId) {
        onDispose {
            tokens.values.forEach { CallManager.stopDtmf(callId, it) }
            tokens.clear()
        }
    }
    val res = LocalResources.current
    // In the two-pane layout the whole pane scrolls instead.
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier) {
        Text(Bidi.ltr(typed.takeLast(20)), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.height(40.dp))
        // L3: the keypad reads 1 2 3 left to right in every language.
        ForceLtr { Column(horizontalAlignment = Alignment.CenterHorizontally) { listOf("123", "456", "789", "*0#").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                row.forEach { c ->
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            // The tone plays for as long as the key is held (phone menus that want a long press).
                            .keypadKey(
                                onPress = {
                                    typed += c
                                    CallManager.startDtmf(callId, c)?.let { tokens[c] = it }
                                },
                                onToneStop = { after -> tokens.remove(c)?.let { CallManager.stopDtmf(callId, it, after) } },
                                // Inside a scrolling column: a touch that starts a scroll must not send a digit.
                                deferPress = scroll,
                            )
                            .semantics { contentDescription = dtmfName(res, c) },
                        contentAlignment = Alignment.Center,
                    ) { Text(c.toString(), style = MaterialTheme.typography.headlineSmall) }
                }
            }
        } } }
        TextButton(onClose) { Text(stringResource(R.string.incall_hide_keypad)) }
    }
}

private fun dtmfName(res: android.content.res.Resources, c: Char): String = when (c) {
    '*' -> res.getString(app.parley.ui.R.string.ui_key_star)
    '#' -> res.getString(app.parley.ui.R.string.ui_key_pound)
    else -> c.toString()
}

fun routeIcon(r: AudioRoute): ImageVector = when (r.type) {
    RouteType.BLUETOOTH -> Icons.Rounded.Bluetooth
    RouteType.WIRED -> Icons.Rounded.Headset
    RouteType.SPEAKER -> Icons.AutoMirrored.Rounded.VolumeUp
    RouteType.EARPIECE -> Icons.Rounded.PhoneInTalk
    RouteType.STREAMING -> Icons.Rounded.PhoneInTalk
}

@Composable
private fun CallBackground(uri: String?, scrim: androidx.compose.ui.graphics.Color) {
    if (uri == null) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val image by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val u = android.net.Uri.parse(uri)
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                var sample = 1
                while (opts.outWidth / (sample * 2) >= 1080 && opts.outHeight / (sample * 2) >= 1080) sample *= 2
                context.contentResolver.openInputStream(u)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                }?.asImageBitmap()
            }.getOrNull()
        }
    }
    val bmp = image ?: return
    androidx.compose.foundation.Image(bmp, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    Box(Modifier.fillMaxSize().background(scrim.copy(alpha = 0.72f)))
}

/** R8: whether the keyguard is showing, re-checked every second (the user may unlock with the call screen up). */
@Composable
private fun rememberKeyguardLocked(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val km = remember { context.getSystemService(android.app.KeyguardManager::class.java) }
    var locked by remember { mutableStateOf(km?.isKeyguardLocked ?: true) }
    androidx.compose.runtime.LaunchedEffect(km) {
        while (true) {
            locked = km?.isKeyguardLocked ?: true
            kotlinx.coroutines.delay(1000)
        }
    }
    return locked
}

/** R8/R9: "Last note: …" and up to three open promises. */
@Composable
private fun MemoryLines(m: app.parley.telecom.CallerMemory) {
    m.lastNote?.takeIf { it.isNotBlank() }?.let {
        Text(stringResource(R.string.memory_last_note, it), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    m.promises.take(3).forEach { p ->
        Text(stringResource(R.string.memory_open_promise, p), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (m.promises.size > 3) {
        val more = m.promises.size - 3
        Text(pluralStringResource(R.plurals.memory_more_promises, more, more), style = MaterialTheme.typography.bodySmall)
    }
}
