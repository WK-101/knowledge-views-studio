package app.parley.telecom.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.telecom.TelecomManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.common.AnswerGesture
import app.parley.common.AppSettings
import app.parley.common.calls.CallControl
import app.parley.common.calls.CallControls
import app.parley.common.calls.CallWaiting
import app.parley.common.ux.CallScreenBackground
import app.parley.common.ux.CallBackdrop
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableFloatStateOf
import app.parley.common.ux.Tips
import app.parley.telecom.AudioRoute
import app.parley.telecom.AudioUi
import app.parley.telecom.CallClock
import app.parley.telecom.CallManager
import app.parley.telecom.CallRtt
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.DeclineBlock
import app.parley.telecom.HelperCalls
import app.parley.common.calls.NameReply
import app.parley.common.calls.SafeWords
import app.parley.common.calls.ScamCheck
import app.parley.telecom.R
import app.parley.telecom.RouteType
import app.parley.telecom.RttUi
import app.parley.telecom.TelecomGraph
import app.parley.telecom.live
import app.parley.telecom.shownFor
import app.parley.ui.Avatar
import app.parley.ui.Bidi
import app.parley.ui.CallColors
import app.parley.ui.Clipboard
import app.parley.ui.ConfirmDialog
import app.parley.ui.ForceLtr
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleyMotion
import app.parley.ui.ParleySheet
import app.parley.ui.ParleyShapes
import app.parley.ui.ParleyType
import app.parley.ui.Spacing
import app.parley.ui.keypadKey
import app.parley.ui.rowColors

/** Which of the screen's sheets and dialogs is open. */
private class InCallSheets {
    var route by mutableStateOf(false)
    var replyFor by mutableStateOf<String?>(null)
    var manage by mutableStateOf(false)
    var more by mutableStateOf(false)
    var noteFor by mutableStateOf<String?>(null)

    /** "Check it's really them" for this call (live, or just ended from the post-call card). */
    var verifyFor by mutableStateOf<CallUi?>(null)

    /** Family safety: the safe-word card and "Add my helper" (WP-8). */
    val family = FamilyCallState()

    /** "Send to another number" for this ringing call. */
    var handOff by mutableStateOf<String?>(null)

    /** "Is this a scam?" for this call (live, or just ended from the post-call card). */
    var scamFor by mutableStateOf<CallUi?>(null)

    /** L3: the RTT conversation sheet for this call, and the calls whose sheet already opened by itself once. */
    var rttFor by mutableStateOf<String?>(null)
    val rttOpened = mutableSetOf<String>()
}

/**
 * The call screen (docs/CALL_SCREEN_DESIGN.md): the caller at the top over a background tinted with their colour (or
 * their call-screen picture), then the controls within thumb reach: the incoming answer controls, or a 3 × 2 grid
 * of morphing buttons with the red End call pill under it. Landscape phones, foldables and tablets put the caller
 * on one side and the controls on the other.
 */
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
    /** What the user did on the post-call card (touching it at all keeps the screen up). */
    onPostCall: (PostCallChoice) -> Unit = {},
    /** An outgoing call that didn't go through, with its reason, until dismissed. */
    failed: CallUi? = null,
    onRetry: (CallUi) -> Unit = {},
    onDismissFailure: (CallUi) -> Unit = {},
    /** The call just declined with "Block & decline" (Undo). */
    declineBlock: DeclineBlock? = null,
    onUndoBlock: () -> Unit = {},
    /** Simple mode: large buttons and (optionally) a question before declining. */
    simple: Boolean = false,
    confirmDecline: Boolean = false,
    /** A call declined from the notification while "Confirm before declining" is on: ask first. */
    askDeclineFor: String? = null,
    onAskDeclineDone: () -> Unit = {},
    /** Settings › Calls › "Call screen background". */
    background: CallScreenBackground = CallScreenBackground.CALLER_COLOUR,
    /** A connected call dropped: "Call again" (true) or dismissing the card (false). */
    onDrop: (CallUi, Boolean) -> Unit = { _, _ -> },
    /** Runs the block once the phone is unlocked (saved numbers never show on the lock screen). */
    onUnlock: (() -> Unit) -> Unit = { it() },
) {
    val live = calls.filter { it.isLive }
    // Which call is in front and whether a second one is waiting (pure logic in core:common).
    val slots = CallWaiting.slots(live) { it.state.live() }
    val primary = slots.primary
    // A call that just ended (still in Telecom, or already gone) keeps its name; never an older call's.
    val endedNow = calls.firstOrNull { !it.isLive }
    val shown = primary ?: endedNow?.let { c -> ended?.takeIf { it.id == c.id } ?: c } ?: ended ?: calls.firstOrNull()
    val sheets = remember { InCallSheets() }
    LoadFamilyCallState(primary, sheets.family)
    val screen = ScreenState(
        live = live, primary = primary, shown = shown, ended = ended, failed = failed, declineBlock = declineBlock, audio = audio,
        keypadOpen = keypadOpen, incoming = IncomingPrefs(answerGesture, simple, confirmDecline),
    )
    // Where the caller's text starts, for a poster's clear picture above it (window pixels, read only while drawing).
    val screenTop = remember { mutableFloatStateOf(0f) }
    val callerTop = remember { mutableFloatStateOf(-1f) }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).onGloballyPositioned { screenTop.floatValue = it.positionInRoot().y },
    ) {
        val twoPane = maxWidth > maxHeight && maxWidth >= 560.dp
        val short = maxHeight < 480.dp
        val backdropCall = primary ?: shown
        // The call-screen picture, decoded here: the layout follows whether it can be shown, not only whether it is set.
        val picture = rememberCallPicture(backdropCall?.backgroundUri?.takeIf { callBackdropPlan(backdropCall, background).picture })
        // Settings › Calls › Poster, for a caller with a call-screen picture that shows, in the one-column layout.
        val poster = posterLayout(backdropCall, background, slots, twoPane, short, keypadOpen, pictureShown = picture != null)
        CallBackground(
            backdropCall, background, image = picture, poster = poster,
            textTop = { textTop(callerTop.floatValue, screenTop.floatValue) },
        )
        val insets = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().displayCutoutPadding()
        val actions = ScreenActions(
            onKeypad = onKeypad, onAddCall = onAddCall, onOpenContact = onOpenContact,
            onPostCall = withVerify(onPostCall, shown, sheets, onUnlock),
            onRetry = onRetry, onDismissFailure = onDismissFailure, onUndoBlock = onUndoBlock, onDrop = onDrop, onUnlock = onUnlock,
        )
        if (slots.waiting && slots.current != null && primary != null) {
            CallWaitingLayout(primary, slots.current!!, slots.held, twoPane, confirmDecline, insets) { sheets.replyFor = primary.id }
        } else if (twoPane) {
            Row(insets.padding(horizontal = Spacing.xl), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                Pane { CallerSection(screen, sheets, actions, if (short) 88.dp else 120.dp, twoPane = true) }
                Pane { ControlsSection(screen, sheets, actions, scrollKeypad = false) }
            }
        } else {
            // While it rings, the caller sits in the upper part of the free space rather than against the top, so the
            // screen reads as one composition (Phone by Google and iOS place the name a little above the middle).
            // A poster keeps the caller low, just above the controls, so the picture shows above the name.
            val bias = rememberCallerBias(primary, short, poster)
            Column(insets.padding(horizontal = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                // The caller scrolls on small screens and at large font sizes; the controls never move.
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = BiasAlignment(0f, bias)) {
                    Column(
                        Modifier.onGloballyPositioned { callerTop.floatValue = it.positionInRoot().y }.verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CallerSection(screen, sheets, actions, if (short) 88.dp else 128.dp, twoPane = false, poster = poster)
                    }
                }
                ControlsSection(screen, sheets, actions, scrollKeypad = true)
            }
        }
    }
    InCallDialogs(screen, sheets, quickReplies, onOpenContact, onAddCall, askDeclineFor, onAskDeclineDone, onUnlock)
    ScamCheckDialog(screen, sheets, onAddCall, onUnlock, withVerify(onPostCall, shown, sheets, onUnlock))
}

/** Whether the caller is laid out as a poster (see [CallBackdrop.posterLayout]). */
@Suppress("LongParameterList")
private fun posterLayout(
    call: CallUi?,
    background: CallScreenBackground,
    slots: CallWaiting.Slots<CallUi>,
    twoPane: Boolean,
    short: Boolean,
    keypadOpen: Boolean,
    pictureShown: Boolean,
): Boolean {
    val primary = slots.primary
    return CallBackdrop.posterLayout(
        callBackdropPlan(call, background), twoPane = twoPane, short = short,
        keypadOpen = keypadOpen && primary?.state != CallState.RINGING, callWaiting = slots.waiting && slots.current != null && primary != null,
        pictureShown = pictureShown,
    )
}

/** The caller's top edge from the top of the screen, or -1 while it hasn't been measured. */
private fun textTop(callerTop: Float, screenTop: Float): Float = if (callerTop >= 0f) callerTop - screenTop else -1f

/** "Call a saved number" on the post-call card opens the same sheet as during the call, once unlocked. */
private fun withVerify(
    onPostCall: (PostCallChoice) -> Unit,
    shown: CallUi?,
    sheets: InCallSheets,
    onUnlock: (() -> Unit) -> Unit,
): (PostCallChoice) -> Unit = { choice ->
    onPostCall(choice)
    if (choice is PostCallChoice.Verify && shown != null) onUnlock { sheets.verifyFor = shown }
    // Nothing in it names the caller, so it opens over the lock screen like the card itself.
    if (choice is PostCallChoice.ScamCheck && shown != null) sheets.scamFor = shown
}

/** Where the ringing caller sits in the space above the controls (-1 top, 0 middle). */
private const val RINGING_BIAS = -0.45f

@Composable
private fun rememberCallerBias(primary: CallUi?, short: Boolean, poster: Boolean): Float {
    val ringing = primary?.state == CallState.RINGING && !short
    val target = when {
        poster -> 1f
        ringing -> RINGING_BIAS
        else -> -1f
    }
    val bias by animateFloatAsState(target, ParleyMotion.spatial(), label = "bias")
    return bias
}

/** Everything the two halves of the screen read. */
private class ScreenState(
    val live: List<CallUi>,
    val primary: CallUi?,
    val shown: CallUi?,
    val ended: CallUi?,
    val failed: CallUi?,
    val declineBlock: DeclineBlock?,
    val audio: AudioUi,
    val keypadOpen: Boolean,
    val incoming: IncomingPrefs,
) {
    val others: List<CallUi> get() = live.filter { it.id != primary?.id }
}

/** How a ringing call is answered and declined (Settings › Calls, Simple mode). */
private class IncomingPrefs(val answerGesture: AnswerGesture, val simple: Boolean, val confirmDecline: Boolean)

private class ScreenActions(
    val onKeypad: (Boolean) -> Unit,
    val onAddCall: () -> Unit,
    val onOpenContact: (CallUi) -> Unit,
    val onPostCall: (PostCallChoice) -> Unit,
    val onRetry: (CallUi) -> Unit,
    val onDismissFailure: (CallUi) -> Unit,
    val onUndoBlock: () -> Unit,
    val onDrop: (CallUi, Boolean) -> Unit,
    val onUnlock: (() -> Unit) -> Unit = { it() },
)

/** A ringing call while another call is going: the current call(s) at the top, the waiting call as a sheet. */
@Composable
private fun CallWaitingLayout(
    ringing: CallUi,
    current: CallUi,
    held: List<CallUi>,
    twoPane: Boolean,
    confirmDecline: Boolean,
    insets: Modifier,
    onReply: () -> Unit,
) {
    val top: @Composable ColumnScope.() -> Unit = {
        CurrentCallCard(current, canHold = current.canHold && held.isEmpty())
        held.filter { it.id != current.id }.forEach { OnHoldStrip(it, current) }
    }
    if (twoPane) {
        Row(insets.padding(horizontal = Spacing.xl), horizontalArrangement = Arrangement.spacedBy(Spacing.xl), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), content = top)
            Box(Modifier.weight(1f)) { CallWaitingSheet(ringing, current, held.size, confirmDecline, onReply) }
        }
    } else {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.l), content = top)
            Spacer(Modifier.weight(1f))
            CallWaitingSheet(ringing, current, held.size, confirmDecline, onReply)
        }
    }
}

/** The top half: other calls (on hold, being dialled), a failed second call or Undo, then the caller. */
@Composable
private fun CallerSection(s: ScreenState, sheets: InCallSheets, a: ScreenActions, avatar: Dp, twoPane: Boolean, poster: Boolean = false) {
    val timings by CallClock.timings.collectAsStateWithLifecycle()
    val primary = s.primary
    // A second call that didn't go through ("Add call"), shown above the call that goes on.
    if (primary != null && s.failed != null) {
        FailureBanner(s.failed, { a.onRetry(s.failed) }, { a.onDismissFailure(s.failed) }, Modifier.padding(top = Spacing.m))
    }
    // A call just declined with "Block & decline" while another call goes on: Undo stays at hand.
    if (primary != null && s.declineBlock != null) {
        Spacer(Modifier.height(Spacing.m))
        DeclineBlockCard(s.declineBlock, onUndo = a.onUndoBlock, onDone = { CallManager.dismissDeclineBlock() })
    }
    s.others.forEach { other ->
        if (other.state == CallState.HOLDING) OnHoldStrip(other, primary) else OtherCallBanner(other)
    }
    Spacer(Modifier.height(if (s.others.isEmpty() && !twoPane) Spacing.xxl else Spacing.l))
    val shown = s.shown ?: return
    CallerHeader(
        call = shown,
        ended = primary == null,
        onOpenContact = a.onOpenContact,
        compact = s.keypadOpen && primary?.state != CallState.RINGING,
        timing = timings[shown.id]?.shownFor(shown),
        avatarSize = avatar,
        onReply = { sheets.replyFor = shown.id },
        poster = poster,
    )
    // Auto-answer's countdown with Cancel, between the caller and the answer controls (an overlay of its own).
    if (shown.state == CallState.RINGING) AutoAnswerCountdown(shown)
    // I11: "Drive profile on" while the marked car is connected.
    DriveStatusLine(primary, keypadOpen = s.keypadOpen)
    // WP-8: the helper being brought in, and "Claims to be family? Ask: …".
    if (primary != null && primary.state != CallState.RINGING) {
        FamilySafetyCards(primary, s.live, sheets.family, a.onUnlock)
        // L3: an RTT request to answer, or the way back into the RTT conversation.
        RttCallCard(primary, onOpen = { sheets.rttFor = primary.id }, sheets.rttOpened)
    }
    Spacer(Modifier.height(Spacing.l))
}

/** The bottom half: what can be done now. */
@Composable
private fun ColumnScope.ControlsSection(s: ScreenState, sheets: InCallSheets, a: ScreenActions, scrollKeypad: Boolean) {
    val primary = s.primary
    when {
        primary == null -> EndedCards(s, a)
        // "Block & decline" is under way: nothing left to answer.
        primary.state == CallState.RINGING && primary.blockingDecline -> BlockingDecline()
        primary.state == CallState.RINGING -> IncomingControls(
            call = primary,
            gesture = s.incoming.answerGesture,
            hasActiveCall = s.others.any { it.state == CallState.ACTIVE },
            onMessage = { sheets.replyFor = primary.id },
            onBlockAndDecline = if (primary.canBlockAndDecline && !s.incoming.simple) ({ CallManager.blockAndDecline(primary.id) }) else null,
            // Saved numbers are listed only once the phone is unlocked.
            onDeflect = if (primary.canDeflect && !s.incoming.simple) ({ a.onUnlock { sheets.handOff = primary.id } }) else null,
            simple = s.incoming.simple,
            confirmDecline = s.incoming.confirmDecline,
        )
        primary.state == CallState.SELECT_ACCOUNT -> SimPicker(primary)
        else -> OngoingControls(primary, s, sheets, a, scrollKeypad)
    }
}

/** After the call: the reason it didn't go through (Retry), "Blocked · Undo", the post-call or memory card. */
@Composable
private fun EndedCards(s: ScreenState, a: ScreenActions) {
    val ended = s.ended
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 560.dp).padding(bottom = Spacing.l)) {
        when {
            s.failed != null -> FailureBanner(s.failed, { a.onRetry(s.failed) }, { a.onDismissFailure(s.failed) }, Modifier.padding(bottom = Spacing.xl))
            // A connected call the network dropped: why, and a big Call again.
            ended != null && ended.drop != null ->
                DropCard(ended, onCallAgain = { a.onDrop(ended, true) }, onDismiss = { a.onDrop(ended, false) }, Modifier.padding(bottom = Spacing.xl))
            // "Blocked and declined", with Undo.
            s.declineBlock != null -> DeclineBlockCard(s.declineBlock, onUndo = a.onUndoBlock, onDone = { a.onPostCall(PostCallChoice.Done) })
            // Block, save, message or report an unknown number right after the call.
            ended != null && ended.postCallCard -> PostCallCard(ended, onChoice = a.onPostCall)
            // "Anything to remember?" after a call with a contact (opt-in).
            ended != null && ended.memoryCard -> MemoryCard(ended, onChoice = a.onPostCall)
            else -> Spacer(Modifier.height(Spacing.xxl * 2))
        }
    }
}

/** The grid (or the keypad in its place), then End call. */
@Composable
private fun OngoingControls(call: CallUi, s: ScreenState, sheets: InCallSheets, a: ScreenActions, scrollKeypad: Boolean) {
    val fast = ParleyMotion.fastEffects<Float>()
    val spatial = ParleyMotion.spatial<Float>()
    AnimatedContent(
        s.keypadOpen,
        transitionSpec = { (fadeIn(fast) + scaleIn(spatial, initialScale = 0.92f)) togetherWith fadeOut(fast) },
        contentAlignment = Alignment.BottomCenter,
        label = "keypad",
    ) { open ->
        when {
            open -> DtmfKeypad(call, scroll = scrollKeypad)
            // I10: "I'm on hold" shows the waiting time and the way out instead of the grid.
            call.holdModeSince > 0 -> HoldModePanel(call, onKeypad = { a.onKeypad(true) })
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Simple mode: "Add my helper" as one big button (I5).
                if (s.incoming.simple) SimpleHelper(call, s, sheets)
                AudioRoutesTip(call, s.audio)
                ControlGrid(
                    call = call,
                    others = s.others,
                    audio = s.audio,
                    onKeypad = { a.onKeypad(true) },
                    onAudio = { if (s.audio.hasExternal) sheets.route = true else CallManager.toggleSpeaker() },
                    // P6: press and hold opens the list of outputs straight away, headset or not.
                    onAudioList = {
                        markTipSeen(Tips.CALL_AUDIO_ROUTES)
                        sheets.route = true
                    },
                    onAddCall = a.onAddCall,
                    onManage = { sheets.manage = true },
                    onMore = { sheets.more = true },
                )
            }
        }
    }
    Spacer(Modifier.height(Spacing.xl))
    // End call centred; with the keypad open, "Hide keypad" sits beside it where the thumb already is.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f))
        EndCallButton({ CallManager.hangup(call.id) })
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (s.keypadOpen) {
                FilledTonalIconButton(onClick = { a.onKeypad(false) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.KeyboardHide, stringResource(R.string.incall_hide_keypad))
                }
            }
        }
    }
    Spacer(Modifier.height(Spacing.xl))
}

/** Tips seen during this call, so a dismissed tip goes at once (the stored flag is read from memory by the app). */
private val tipsDismissedHere = mutableStateListOf<String>()

private fun markTipSeen(id: String) {
    if (id !in tipsDismissedHere) tipsDismissedHere += id
    runCatching { TelecomGraph.dependencies.markTipSeen(id) }
}

/** P6's tip, once: press and hold Speaker for the list of outputs (only while connected, with somewhere to choose). */
@Composable
private fun AudioRoutesTip(call: CallUi, audio: AudioUi) {
    if (call.state != CallState.ACTIVE || audio.routes.size < 2 || Tips.CALL_AUDIO_ROUTES in tipsDismissedHere) return
    val seen = remember { runCatching { TelecomGraph.dependencies.tipSeen(Tips.CALL_AUDIO_ROUTES) }.getOrDefault(true) }
    if (seen) return
    CallTip(stringResource(R.string.call_tip_audio_routes)) { markTipSeen(Tips.CALL_AUDIO_ROUTES) }
}

/** One half of the two-pane layout: centred, and scrollable when it doesn't fit. */
@Composable
private fun RowScope.Pane(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
        Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/** Another call that isn't on hold (being dialled, or active while this one is dialled). */
@Composable
private fun OtherCallBanner(call: CallUi) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = ParleyShapes.card,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
    ) {
        Row(Modifier.padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Avatar(call.title, call.photoUri, 36.dp)
            Spacer(Modifier.width(Spacing.m))
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

/** The 3 × 2 grid; which buttons it holds is decided by [CallControls] (core:common). */
@Composable
private fun ControlGrid(
    call: CallUi,
    others: List<CallUi>,
    audio: AudioUi,
    onKeypad: () -> Unit,
    onAudio: () -> Unit,
    onAudioList: () -> Unit,
    onAddCall: () -> Unit,
    onManage: () -> Unit,
    onMore: () -> Unit,
) {
    val layout = CallControls.layout(controlCaps(call, others, audio))
    val res = LocalResources.current
    val specs = layout.grid.map { slot ->
        val spec = controlSpec(res, slot.control, slot.enabled, call, audio) {
            when (slot.control) {
                CallControl.KEYPAD -> onKeypad()
                CallControl.AUDIO -> onAudio()
                CallControl.ADD_CALL -> onAddCall()
                CallControl.MANAGE -> onManage()
                CallControl.MORE -> onMore()
                else -> runControl(slot.control, call, audio)
            }
        }
        if (slot.control == CallControl.AUDIO && audio.routes.size >= 2) {
            spec.copy(onLongClick = onAudioList, longClickLabel = res.getString(R.string.incall_choose_audio_output))
        } else {
            spec
        }
    }
    ControlRows(specs, CallControls.COLUMNS)
}

internal fun controlCaps(call: CallUi, others: List<CallUi>, audio: AudioUi) = CallControls.Caps(
    canMute = call.canMute,
    canHold = call.canHold,
    canMerge = call.canMerge,
    canSwap = others.any { it.state == CallState.HOLDING } || call.canSwap,
    isConference = call.isConference,
    otherCalls = others.size,
    hasAudioRoutes = audio.routes.isNotEmpty(),
)

/** The calls a button makes by itself (the others open something on the screen). */
private fun runControl(control: CallControl, call: CallUi, audio: AudioUi) {
    when (control) {
        CallControl.MUTE -> CallManager.setMuted(!audio.muted)
        CallControl.HOLD -> CallManager.toggleHold(call.id)
        CallControl.MERGE -> CallManager.merge(call.id)
        CallControl.SWAP -> CallManager.swap(call.id)
        else -> Unit
    }
}

/** A control's icon, label, spoken name and state; toggles change icon too, not only colour. */
private fun controlSpec(res: Resources, control: CallControl, enabled: Boolean, call: CallUi, audio: AudioUi, onClick: () -> Unit): ControlSpec {
    fun plain(icon: ImageVector, label: Int, spoken: Int) =
        ControlSpec(icon, res.getString(label), res.getString(spoken), enabled = enabled, onClick = onClick)
    return when (control) {
        CallControl.MUTE -> toggleSpec(
            res, audio.muted, Icons.Rounded.MicOff to Icons.Rounded.Mic, R.string.incall_muted to R.string.incall_mute, enabled, onClick,
        )
        CallControl.KEYPAD -> plain(Icons.Rounded.Dialpad, R.string.incall_keypad, R.string.incall_keypad)
        CallControl.AUDIO -> audioSpec(res, audio, enabled, onClick)
        CallControl.HOLD -> toggleSpec(
            res, call.state == CallState.HOLDING, Icons.Rounded.PlayArrow to Icons.Rounded.Pause,
            R.string.incall_resume to R.string.incall_hold, enabled, onClick,
        )
        CallControl.MERGE -> plain(Icons.AutoMirrored.Rounded.CallMerge, R.string.incall_merge, R.string.incall_merge_calls)
        CallControl.SWAP -> plain(Icons.Rounded.SwapCalls, R.string.incall_swap, R.string.incall_swap_calls)
        CallControl.MANAGE -> plain(Icons.Rounded.Groups, R.string.incall_manage, R.string.incall_manage_conference)
        CallControl.ADD_CALL -> plain(Icons.Rounded.Add, R.string.incall_add_call, R.string.incall_add_call)
        CallControl.MORE -> plain(Icons.Rounded.MoreHoriz, R.string.incall_more, R.string.incall_more_options)
    }
}

/** Mute and Hold: (on, off) icons and labels; TalkBack hears the "off" label as the name, with the state after it. */
private fun toggleSpec(
    res: Resources,
    on: Boolean,
    icons: Pair<ImageVector, ImageVector>,
    labels: Pair<Int, Int>,
    enabled: Boolean,
    onClick: () -> Unit,
) = ControlSpec(
    if (on) icons.first else icons.second, res.getString(if (on) labels.first else labels.second), res.getString(labels.second),
    toggle = true, active = on, enabled = enabled, onClick = onClick,
)

/** Speaker (a toggle) without a headset; with one, the current route's name, opening the route sheet. */
private fun audioSpec(res: Resources, audio: AudioUi, enabled: Boolean, onClick: () -> Unit): ControlSpec {
    val b = audioButton(res, audio)
    return ControlSpec(
        audio.current?.let { routeIcon(it) } ?: Icons.AutoMirrored.Rounded.VolumeUp, b.label, b.spoken,
        toggle = b.isToggle, active = b.on, enabled = enabled, onClick = onClick,
    )
}

/** The More sheet's rows for the call controls that didn't fit the grid, same icons and names. */
@Composable
private fun overflowRows(call: CallUi, others: List<CallUi>, audio: AudioUi, onAddCall: () -> Unit, onManage: () -> Unit): List<ControlSpec> {
    val res = LocalResources.current
    return CallControls.layout(controlCaps(call, others, audio)).more.map { slot ->
        controlSpec(res, slot.control, slot.enabled, call, audio) {
            when (slot.control) {
                CallControl.ADD_CALL -> onAddCall()
                CallControl.MANAGE -> onManage()
                else -> runControl(slot.control, call, audio)
            }
        }
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
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Text(stringResource(R.string.incall_call_with), style = MaterialTheme.typography.titleMedium)
        accounts.forEach { (id, label) ->
            Surface(
                shape = ParleyShapes.card,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth().clip(ParleyShapes.card).clickable { CallManager.selectAccount(call.id, id) },
            ) {
                Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SimCard, null)
                    Spacer(Modifier.width(Spacing.m))
                    Text(label, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        TextButton(onClick = { CallManager.hangup(call.id) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.tc_cancel))
        }
    }
}

/**
 * Tones during the call, in the same key shape as the grid. The tones sent so far stay above the keys in one line
 * (the start trimmed with "…" once it no longer fits), so a menu choice or an account number can be checked.
 */
@Composable
private fun DtmfKeypad(call: CallUi, scroll: Boolean = true) {
    val callId = call.id
    var typed by rememberSaveable { mutableStateOf("") }
    // Replayed menu digits join the line of tones sent, as if typed.
    val replay by CallManager.menuReplay.collectAsState()
    var replayShown by remember { mutableStateOf(0L to 0) }
    LaunchedEffect(replay) {
        val r = replay?.takeIf { it.callId == callId } ?: return@LaunchedEffect
        var shown = if (replayShown.first == r.token) replayShown.second else 0
        while (shown < r.sent) typed += r.steps[shown++].tone
        replayShown = r.token to shown
    }
    // One running tone per key: a key's release only stops its own tone.
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
        // I6: "Last time: 2 › 1 › 4" with Replay, for a number Parley remembers menu digits for.
        MenuMemoryRow(call)
        Text(
            Bidi.ltr(typed), style = ParleyType.typedDigits, maxLines = 1,
            overflow = TextOverflow.StartEllipsis, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = CallButtonSize.panelMaxWidth).padding(horizontal = Spacing.l).height(44.dp),
        )
        Spacer(Modifier.height(Spacing.s))
        // The keypad reads 1 2 3 left to right in every language.
        ForceLtr {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                listOf("123", "456", "789", "*0#").forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                        row.forEach { c ->
                            Box(
                                Modifier.size(width = DTMF_KEY_WIDTH, height = DTMF_KEY_HEIGHT).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    // The tone plays for as long as the key is held (phone menus that want a long press).
                                    .keypadKey(
                                        onPress = {
                                            typed += c
                                            // A key pressed by hand takes over from a replay.
                                            if (CallManager.menuReplay.value?.callId == callId) CallManager.stopMenuReplay()
                                            CallManager.startDtmf(callId, c)?.let { tokens[c] = it }
                                        },
                                        onToneStop = { after -> tokens.remove(c)?.let { CallManager.stopDtmf(callId, it, after) } },
                                        // Inside a scrolling column: a touch that starts a scroll must not send a digit.
                                        deferPress = scroll,
                                    )
                                    .semantics { contentDescription = dtmfName(res, c) },
                                contentAlignment = Alignment.Center,
                            ) { Text(c.toString(), style = ParleyType.typedDigits, color = MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                }
            }
        }
    }
}

/** In-call keys: a little larger than 4.1's, for a thumb held away from the face. */
private val DTMF_KEY_WIDTH = 88.dp
private val DTMF_KEY_HEIGHT = 64.dp

private fun dtmfName(res: Resources, c: Char): String = when (c) {
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

/** The screen's sheets and dialogs: note, tones to send, audio route, More, decline question, replies, conference. */
@Composable
private fun InCallDialogs(
    s: ScreenState,
    sheets: InCallSheets,
    quickReplies: List<String>,
    onOpenContact: (CallUi) -> Unit,
    onAddCall: () -> Unit,
    askDeclineFor: String?,
    onAskDeclineDone: () -> Unit,
    onUnlock: (() -> Unit) -> Unit,
) {
    val primary = s.primary
    sheets.noteFor?.let { id -> NoteDialog(id) { sheets.noteFor = null } }
    val postDial = primary?.postDialWait
    if (postDial != null) {
        ConfirmDialog(
            title = stringResource(R.string.incall_send_tones_title),
            text = Bidi.ltr(postDial),
            confirmLabel = stringResource(R.string.incall_send),
            onConfirm = { CallManager.postDialContinue(primary.id, true) },
            onDismiss = { CallManager.postDialContinue(primary.id, false) },
            dismissLabel = stringResource(R.string.tc_cancel),
        )
    }
    if (sheets.route) AudioRouteSheet(s.audio) { sheets.route = false }
    if (sheets.more && primary != null) MoreSheet(primary, s, sheets, onOpenContact, onAddCall, onUnlock)
    HelperPick(primary, s, sheets)
    RttDialog(s, sheets)
    VerifyDialog(s, sheets)
    HandOffDialog(s, sheets)
    // Decline tapped in the notification, with "Confirm before declining" on.
    val askCall = s.live.firstOrNull { it.id == askDeclineFor && it.state == CallState.RINGING }
    if (askCall != null) {
        DeclineQuestion(onDecline = { CallManager.reject(askCall.id); onAskDeclineDone() }, onDismiss = onAskDeclineDone)
    } else if (askDeclineFor != null) {
        DisposableEffect(askDeclineFor) { onAskDeclineDone(); onDispose { } }
    }
    s.live.firstOrNull { it.id == sheets.replyFor && it.state == CallState.RINGING }?.let { ReplySheet(it, quickReplies) { sheets.replyFor = null } }
    val conference = s.live.firstOrNull { it.isConference }
    if (sheets.manage && conference != null) ConferenceSheet(conference) { sheets.manage = false }
}

/** L3: the RTT conversation; it stays open (and can still be saved) when the call ends under it. */
@Composable
private fun RttDialog(s: ScreenState, sheets: InCallSheets) {
    val id = sheets.rttFor ?: return
    val call = s.live.firstOrNull { it.id == id } ?: s.shown?.takeIf { it.id == id } ?: return
    RttSheet(call) { sheets.rttFor = null }
}

/** "Check it's really them": for the live call while it lasts, or for the call that just ended. */
@Composable
private fun VerifyDialog(s: ScreenState, sheets: InCallSheets) {
    val v = sheets.verifyFor ?: return
    val live = s.live.firstOrNull { it.id == v.id }
    if (live != null || s.primary == null) {
        VerifySheet(live ?: v, live = live != null) { sheets.verifyFor = null }
    } else {
        // That call ended while another goes on: nothing left to check.
        LaunchedEffect(v.id) { sheets.verifyFor = null }
    }
}

/** More: the controls that didn't fit the grid, notes, Open contact, Copy number and the call's time. */
@Composable
private fun MoreSheet(
    primary: CallUi,
    s: ScreenState,
    sheets: InCallSheets,
    onOpenContact: (CallUi) -> Unit,
    onAddCall: () -> Unit,
    onUnlock: (() -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val timings by CallClock.timings.collectAsStateWithLifecycle()
    val rtt = rttOf(primary.id)
    CallMoreSheet(
        call = primary,
        timing = timings[primary.id]?.shownFor(primary),
        controls = overflowRows(primary, s.others, s.audio, onAddCall = onAddCall, onManage = { sheets.manage = true }),
        onDismiss = { sheets.more = false },
        onNote = { sheets.noteFor = primary.id },
        onOpenContact = if (primary.hidden) null else ({ onOpenContact(primary) }),
        // A call masked on the lock screen copies its number only once the phone is unlocked.
        onCopyNumber = primary.number?.takeIf { !primary.hidden && it.isNotBlank() }?.let { n ->
            { if (primary.lockMasked) onUnlock { copyNumber(context, n) } else copyNumber(context, n) }
        },
        onHoldMode = if (primary.canHoldMode) ({ CallManager.startHoldMode(primary.id) }) else null,
        onVerify = if (primary.canVerify) ({ onUnlock { sheets.verifyFor = primary } }) else null,
        onClaimsFamily = claimsFamily(primary, sheets.family),
        onAddHelper = addHelper(context, primary, s, sheets.family),
        onRtt = rttAction(primary, rtt, sheets),
        rttActive = rtt.active,
        onScamCheck = if (primary.scamCheckOffered) ({ sheets.scamFor = primary }) else null,
    )
}

/** "Send to another number", while the call still rings and can be sent on. */
@Composable
private fun HandOffDialog(s: ScreenState, sheets: InCallSheets) {
    val id = sheets.handOff ?: return
    val call = s.live.firstOrNull { it.id == id }?.takeIf { it.canDeflect }
    if (call != null) {
        HandOffSheet(call) { sheets.handOff = null }
    } else {
        LaunchedEffect(id) { sheets.handOff = null }
    }
}

/**
 * "Is this a scam?": during the call (safe word, Check it's really them, the official number, hang up), or from
 * the post-call card (Call a saved number, Block, Report).
 */
@Composable
private fun ScamCheckDialog(
    s: ScreenState,
    sheets: InCallSheets,
    onAddCall: () -> Unit,
    onUnlock: (() -> Unit) -> Unit,
    onPostCall: (PostCallChoice) -> Unit,
) {
    val v = sheets.scamFor ?: return
    val live = s.live.firstOrNull { it.id == v.id }
    val close = { sheets.scamFor = null }
    when {
        live != null -> {
            val safeWord = ScamCheck.safeWordReminder(sheets.family.promptsFor(live).isNotEmpty(), live.isEmergency)
            val actions = ScamCheckActions(
                onVerify = if (live.canVerify) ({ onUnlock { sheets.verifyFor = live } }) else null,
                onSafeWord = if (safeWord) ({ sheets.family.claimed[live.id] = true }) else null,
                onCallOfficial = { CallManager.hangup(live.id); onAddCall() },
                onHangUp = { CallManager.hangup(live.id) },
                blockReportNext = !live.hidden && !live.number.isNullOrBlank(),
            )
            ScamCheckSheet(live = true, actions, close)
        }
        s.primary == null && v.postCallCard -> {
            val number = v.number.orEmpty()
            val actions = ScamCheckActions(
                onVerify = { onPostCall(PostCallChoice.Verify(number)) },
                onBlock = { onPostCall(PostCallChoice.Block(number)) },
                onReport = { onPostCall(PostCallChoice.Report(number)) },
            )
            ScamCheckSheet(live = false, actions, close)
        }
        // That call ended while another goes on: nothing left to check.
        else -> LaunchedEffect(v.id) { sheets.scamFor = null }
    }
}

/** L3: More › "Switch to RTT" where the call's SIM supports it, or "RTT conversation" once it's on. */
private fun rttAction(call: CallUi, rtt: RttUi, sheets: InCallSheets): (() -> Unit)? = when {
    rtt.active -> ({ sheets.rttFor = call.id })
    rtt.supported && !rtt.requesting && call.state == CallState.ACTIVE -> ({ CallRtt.request(call.id) })
    else -> null
}

/** I4: More › "Says they're family", while the safe-word card isn't up yet for this call. */
private fun claimsFamily(call: CallUi, family: FamilyCallState): (() -> Unit)? {
    val seconds = if (call.connectTimeMillis > 0) (System.currentTimeMillis() - call.connectTimeMillis) / 1000 else 0
    if (!SafeWords.claimOffered(family.facts(call, seconds))) return null
    return { family.claimed[call.id] = true }
}

/** I5: More › "Add my helper": calls the one helper at once, or lists them. */
private fun addHelper(context: Context, call: CallUi, s: ScreenState, family: FamilyCallState): (() -> Unit)? {
    val helpers = family.helpersFor(call, s.others, joining = HelperCalls.join.value != null)
    if (helpers.isEmpty()) return null
    return { if (helpers.size == 1) startHelper(context, call, helpers.first()) else family.pickHelper = true }
}

/** "Add my helper" with several helpers: the list to choose from (I5), while asked for. */
@Composable
private fun HelperPick(primary: CallUi?, s: ScreenState, sheets: InCallSheets) {
    if (!sheets.family.pickHelper || primary == null) return
    val context = LocalContext.current
    val helpers = sheets.family.helpersFor(primary, s.others, joining = false)
    HelperSheet(helpers, onPick = { startHelper(context, primary, it) }) { sheets.family.pickHelper = false }
}

/** Simple mode's big "Add my helper" (I5), when there's someone to add. */
@Composable
private fun SimpleHelper(call: CallUi, s: ScreenState, sheets: InCallSheets) {
    val context = LocalContext.current
    val join by HelperCalls.join.collectAsStateWithLifecycle()
    val helpers = sheets.family.helpersFor(call, s.others, joining = join != null)
    SimpleHelperButton(helpers) { addHelper(context, call, s, sheets.family)?.invoke() }
}

@Composable
private fun NoteDialog(callId: String, onDone: () -> Unit) {
    var text by remember { mutableStateOf("") }
    ConfirmDialog(
        title = stringResource(R.string.incall_note_title),
        text = null,
        confirmLabel = stringResource(R.string.tc_save),
        onConfirm = {
            if (text.isNotBlank()) CallManager.saveNote(callId, text.trim())
            onDone()
        },
        onDismiss = onDone,
        dismissLabel = stringResource(R.string.tc_cancel),
        content = { OutlinedTextField(text, { text = it }, minLines = 3, placeholder = { Text(stringResource(R.string.incall_note_placeholder)) }) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplySheet(call: CallUi, quickReplies: List<String>, onDismiss: () -> Unit) {
    ParleySheet(onDismissRequest = onDismiss, title = stringResource(R.string.incall_reply_sheet_title)) {
        // The defaults live in core/common in English: while unedited, send them in the user's language.
        val replies = if (quickReplies == AppSettings.DEFAULT_QUICK_REPLIES) {
            stringArrayResource(R.array.incall_default_quick_replies).toList()
        } else {
            quickReplies
        }
        // I11: in the car, the driving replies come first.
        DrivingReplies(call, onDismiss)
        // "Text me your name" first for a number that isn't saved, with what it's for under it.
        val nameReply = nameReplyFor(call)
        if (nameReply != null) {
            ParleyListItem(
                headlineContent = { Text(nameReply) },
                supportingContent = { Text(stringResource(R.string.name_reply_tag)) },
                colors = rowColors(),
                modifier = Modifier.clickable { CallManager.reject(call.id, nameReply); onDismiss() },
            )
        }
        NameReply.replies(replies, null).filter { it != nameReply }.forEach { msg ->
            ParleyListItem(
                headlineContent = { Text(msg) },
                colors = rowColors(),
                modifier = Modifier.clickable { CallManager.reject(call.id, msg); onDismiss() },
            )
        }
        Spacer(Modifier.height(Spacing.xl))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConferenceSheet(conference: CallUi, onDismiss: () -> Unit) {
    ParleySheet(onDismissRequest = onDismiss, title = stringResource(R.string.incall_conference_call)) {
        conference.children.forEach { child ->
            ParleyListItem(
                headlineContent = { Text(child.displayTitle) },
                supportingContent = child.number?.takeIf { child.name != null && !child.lockMasked }?.let { n -> { Text(Bidi.ltr(n)) } },
                leadingContent = { Avatar(child.title, child.photoUri, 40.dp) },
                colors = rowColors(),
                trailingContent = {
                    Row {
                        if (child.canSeparate) {
                            TextButton({ CallManager.separate(child.id); onDismiss() }) { Text(stringResource(R.string.incall_private)) }
                        }
                        if (child.canDisconnectChild) {
                            TextButton({ CallManager.hangup(child.id) }) { Text(stringResource(R.string.incall_end), color = CallColors.Decline) }
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(Spacing.xl))
    }
}

/** The caller's number, sensitive like every copy in Parley. */
private fun copyNumber(context: Context, number: String) = Clipboard.copy(context, number, confirm = context.getString(R.string.incall_copied))
