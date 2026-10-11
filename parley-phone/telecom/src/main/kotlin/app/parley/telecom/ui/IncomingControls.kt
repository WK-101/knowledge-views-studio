package app.parley.telecom.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.automirrored.rounded.PhoneForwarded
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.parley.common.AnswerGesture
import app.parley.common.calls.AnswerSlide
import app.parley.telecom.CallManager
import app.parley.telecom.CallUi
import app.parley.telecom.R
import app.parley.ui.CallColors
import app.parley.ui.ForceLtr
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyMotion
import app.parley.ui.ParleyShapes
import app.parley.ui.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The incoming screen's controls (docs/CALL_SCREEN_DESIGN.md, "4.2 revisions"), one column no wider than the grid:
 * a quiet row of round actions (Reply, Silence, More with Block & decline), the line the call came in on, then the
 * answer control (the slide track, or Decline and Answer circles), with "End current call and answer" under it
 * while another call is going. The outer actions sit right above the answer control's two ends.
 */
@Composable
fun IncomingControls(
    call: CallUi, gesture: AnswerGesture, hasActiveCall: Boolean, onMessage: () -> Unit, onBlockAndDecline: (() -> Unit)? = null,
    simple: Boolean = false, confirmDecline: Boolean = false,
    /** "Send to another number" (deflect), where the network supports it. */
    onDeflect: (() -> Unit)? = null,
) {
    // Simple mode asks before declining, so a stray tap never sends a call away.
    var askDecline by remember { mutableStateOf(false) }
    val decline = { if (confirmDecline) askDecline = true else CallManager.reject(call.id) }
    if (askDecline) DeclineQuestion(onDecline = { askDecline = false; CallManager.reject(call.id) }, onDismiss = { askDecline = false })
    if (simple) {
        SimpleAnswerButtons(call.simHint, onAnswer = { CallManager.answer(call.id) }, onDecline = decline)
        return
    }
    Column(
        Modifier.widthIn(max = CallButtonSize.panelMaxWidth).fillMaxWidth().padding(bottom = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IncomingSecondaryRow(call, onMessage, onBlockAndDecline, onDeflect)
        Spacer(Modifier.height(Spacing.xxl))
        // On dual-SIM phones, which SIM the call came in on, as a label above the control (never inside the track).
        val sim = call.simHint
        if (sim != null) {
            LineLabel(sim)
            Spacer(Modifier.height(Spacing.m))
        }
        when (gesture) {
            AnswerGesture.SWIPE -> AnswerSlider(sim, onAnswer = { CallManager.answer(call.id) }, onDecline = decline)
            AnswerGesture.TAP -> AnswerButtons(sim, onAnswer = { CallManager.answer(call.id) }, onDecline = decline)
        }
        if (hasActiveCall) {
            Spacer(Modifier.height(Spacing.s))
            TextButton(onClick = { CallManager.endAndAnswer(call.id) }) { Text(stringResource(R.string.incall_end_and_answer)) }
        }
    }
}

/**
 * Reply, Silence and More as three fixed columns: the outer two line up with the answer control's ends, and a column
 * whose action doesn't apply (no Reply for a hidden number) stays empty, so nothing shifts. Silence becomes a quiet
 * "Silenced" once the ringer is off.
 */
@Composable
private fun IncomingSecondaryRow(call: CallUi, onMessage: () -> Unit, onBlockAndDecline: (() -> Unit)?, onDeflect: (() -> Unit)?) {
    val canReply = !call.hidden && !call.number.isNullOrBlank()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Slot {
            if (canReply) {
                QuietAction(
                    Icons.AutoMirrored.Rounded.Message, stringResource(R.string.incall_reply), onMessage, spoken = stringResource(R.string.incall_reply_a11y),
                )
            }
        }
        Slot {
            if (call.silenced) {
                QuietAction(Icons.Rounded.NotificationsOff, stringResource(R.string.call_silenced), onClick = null, selected = true)
            } else {
                QuietAction(
                    Icons.Rounded.NotificationsOff, stringResource(R.string.incall_silence), { CallManager.ignore(call.id) },
                    spoken = stringResource(R.string.incall_stop_ringing),
                )
            }
        }
        // "Block & decline" sits behind More, two deliberate taps, so it can't happen by accident; so do the
        // follow-ups ("Decline & remind", "Decline & message or call on…").
        Slot { if (onBlockAndDecline != null || onDeflect != null || offersDeclineFollowUp(call)) BlockAndDeclineMenu(call, onBlockAndDecline, onDeflect) }
    }
}

@Composable
private fun Slot(content: @Composable () -> Unit) {
    Box(Modifier.width(CallButtonSize.slot), contentAlignment = Alignment.TopCenter) { content() }
}

/** "Incoming on Work · …4567": a small label above the answer control. TalkBack hears it with the control itself. */
@Composable
private fun LineLabel(sim: String) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.SimCard, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.xs))
        Text(
            stringResource(R.string.incall_incoming_on, sim), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Decline this call?" (simple mode). */
@Composable
internal fun DeclineQuestion(onDecline: () -> Unit, onDismiss: () -> Unit) {
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.incall_decline_q)) },
        text = { Text(stringResource(R.string.incall_decline_body)) },
        confirmButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.notif_decline), color = CallColors.Decline) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.incall_keep_ringing)) } },
    )
}

/** Two very large buttons, answer on top (easy to reach and hard to miss), decline below. */
@Composable
private fun SimpleAnswerButtons(sim: String?, onAnswer: () -> Unit, onDecline: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (sim != null) Box(Modifier.align(Alignment.CenterHorizontally)) { LineLabel(sim) }
        BigAction(Icons.Rounded.Call, stringResource(R.string.notif_answer), CallColors.Accept, onAnswer, height = 112, a11y = sim?.let { stringResource(R.string.incall_answer_on, it) })
        BigAction(Icons.Rounded.CallEnd, stringResource(R.string.notif_decline), CallColors.Decline, onDecline, height = 80)
    }
}

@Composable
private fun BigAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit, height: Int, a11y: String? = null) {
    Row(
        Modifier.fillMaxWidth().height(height.dp).clip(ParleyShapes.sheet).background(color)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = a11y ?: label },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(40.dp))
        Spacer(Modifier.size(16.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.headlineMedium)
    }
}

/** More on the incoming screen, with the follow-ups ([DeclineFollowUpItems]), "Send to another number" and "Block & decline". */
@Composable
private fun BlockAndDeclineMenu(call: CallUi, onBlockAndDecline: (() -> Unit)?, onDeflect: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        QuietAction(Icons.Rounded.MoreVert, stringResource(R.string.incall_more), { open = true }, spoken = stringResource(R.string.incall_incoming_more))
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DeclineFollowUpItems(call) { open = false }
            if (onDeflect != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.handoff_deflect)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PhoneForwarded, null) },
                onClick = {
                    open = false
                    onDeflect()
                },
            )
            if (onBlockAndDecline != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.incall_block_decline)) },
                leadingIcon = { Icon(Icons.Rounded.Block, null, tint = CallColors.Decline) },
                onClick = {
                    open = false
                    onBlockAndDecline()
                },
            )
        }
    }
}

/**
 * Tap to answer: Decline and Answer circles at the two ends, where the slide control has its ends, Answer with a slow
 * halo while it rings. Decline stays on the left and Answer on the right in every language, like the slide control.
 */
@Composable
private fun AnswerButtons(sim: String?, onAnswer: () -> Unit, onDecline: () -> Unit) = ForceLtr {
    val still = ParleyMotion.reducedMotion()
    // Read only while drawing, so the halo redraws without recomposing the buttons.
    val halo = if (still) {
        null
    } else {
        rememberInfiniteTransition(label = "halo").animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "t")
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        CallActionButton(Icons.Rounded.CallEnd, stringResource(R.string.notif_decline), CallColors.Decline, onDecline, Modifier.width(CallButtonSize.slot))
        CallActionButton(
            Icons.Rounded.Call, stringResource(R.string.notif_answer), CallColors.Accept, onAnswer, Modifier.width(CallButtonSize.slot),
            spoken = sim?.let { stringResource(R.string.incall_answer_on, it) } ?: stringResource(R.string.notif_answer),
            halo = halo?.let { h -> { h.value } },
        )
    }
}

/**
 * Slide to answer. The knob rests in the middle of the track, with Decline as a red target at the left end and
 * Answer as a green one at the right; nothing is written inside the track, so the knob never covers words. Faint
 * chevrons shimmer outwards and the knob gives a small nudge towards Answer every few seconds (both still when
 * animations are off). The hint under the track fades as the knob moves and turns into "Release to answer" once
 * letting go would answer, with a haptic tick on crossing that point both ways. The rules (how far, how fast) are
 * [AnswerSlide] in core:common. Only a drag that starts on the knob moves it. TalkBack users get Answer and Decline
 * actions. Left to right in every language, matching "slide right to answer".
 */
@Composable
private fun AnswerSlider(sim: String?, onAnswer: () -> Unit, onDecline: () -> Unit) = ForceLtr {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val answerLabel = stringResource(R.string.notif_answer)
    val declineLabel = stringResource(R.string.notif_decline)
    val description = if (sim != null) stringResource(R.string.incall_slider_description_sim, sim) else stringResource(R.string.incall_slider_description)
    val settleSpec = ParleyMotion.fastSpatial<Float>()
    val state = remember { SlideState() }
    var widthPx by remember { mutableIntStateOf(0) }
    val travel = (widthPx / 2f - with(density) { (CallButtonSize.slot / 2).toPx() }).coerceAtLeast(0f)
    val release = { outcome: AnswerSlide.Outcome ->
        when (outcome) {
            AnswerSlide.Outcome.ANSWER -> state.finish(scope, settleSpec, 1, haptics, onAnswer)
            AnswerSlide.Outcome.DECLINE -> state.finish(scope, settleSpec, -1, haptics, onDecline)
            AnswerSlide.Outcome.SPRING_BACK -> state.settleTo(scope, settleSpec, 0f)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(TRACK)
                .onSizeChanged { widthPx = it.width }
                .clip(ParleyShapes.pill)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = TRACK_VEIL))
                .semantics {
                    contentDescription = description
                    customActions = listOf(
                        CustomAccessibilityAction(answerLabel) { onAnswer(); true },
                        CustomAccessibilityAction(declineLabel) { onDecline(); true },
                    )
                }
                .slideGesture(state, travel, with(density) { (KNOB / 2 + Spacing.s).toPx() }, onTick = { tick ->
                    when (tick) {
                        AnswerSlide.Tick.ARMED -> haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        AnswerSlide.Tick.DISARMED -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        AnswerSlide.Tick.NONE -> Unit
                    }
                }, onRelease = release),
            contentAlignment = Alignment.Center,
        ) {
            SlideTrackDecor(state.pos, answerLabel, declineLabel, onAnswer, onDecline)
            SlideKnob(state, travel)
        }
        SlideHint(state.pos)
    }
}

/** The knob's place and what it is doing; see [AnswerSlide] for the rules. */
private class SlideState {
    /** -1 (decline end) .. 1 (answer end). */
    var pos by mutableFloatStateOf(0f)
    var dragging by mutableStateOf(false)

    /** Answered or declined: the knob stays at its end and ignores touches for a moment. */
    var done by mutableStateOf(false)
    private var settle: Job? = null

    fun stop() {
        settle?.cancel()
    }

    fun settleTo(scope: CoroutineScope, spec: AnimationSpec<Float>, target: Float) {
        settle?.cancel()
        settle = scope.launch { animate(pos, target, animationSpec = spec) { v, _ -> pos = v } }
    }

    fun finish(scope: CoroutineScope, spec: AnimationSpec<Float>, side: Int, haptics: HapticFeedback, action: () -> Unit) {
        done = true
        haptics.performHapticFeedback(if (side > 0) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
        settle?.cancel()
        settle = scope.launch {
            animate(pos, side.toFloat(), animationSpec = spec) { v, _ -> pos = v }
            action()
            // Still here after a moment (declining asked first, or the network was slow): back to the middle.
            delay(RESET_MS)
            done = false
            animate(pos, 0f, animationSpec = spec) { v, _ -> pos = v }
        }
    }
}

/**
 * Drags that start on the knob (within [grabPx] of its centre) move it along [travel]; [onTick] reports crossing the
 * threshold, and [onRelease] what letting go (with the finger's speed) means.
 */
private fun Modifier.slideGesture(
    state: SlideState,
    travel: Float,
    grabPx: Float,
    onTick: (AnswerSlide.Tick) -> Unit,
    onRelease: (AnswerSlide.Outcome) -> Unit,
): Modifier = pointerInput(travel, grabPx) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (state.done || travel <= 0f || abs(down.position.x - (size.width / 2f + state.pos * travel)) > grabPx) return@awaitEachGesture
        state.stop()
        state.dragging = true
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var offset = state.pos * travel
        val move = { dx: Float ->
            val before = state.pos
            offset = (offset + dx).coerceIn(-travel, travel)
            state.pos = AnswerSlide.position(offset, travel)
            onTick(AnswerSlide.tick(before, state.pos))
        }
        val started = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            change.consume()
            tracker.addPointerInputChange(change)
            move(over)
        }
        if (started != null) {
            horizontalDrag(started.id) { change ->
                tracker.addPointerInputChange(change)
                move(change.positionChange().x)
                change.consume()
            }
        }
        state.dragging = false
        onRelease(AnswerSlide.release(state.pos, tracker.calculateVelocity().x.toDp().value))
    }
}

/**
 * The two ends and the shimmering chevrons (still when animations are off), fading as the knob moves. The ends are
 * named buttons for accessibility services only (Voice Access "tap Answer", Switch Access, TalkBack); a finger still
 * has to slide, so a pocket can't answer by touching an end.
 */
@Composable
private fun BoxScope.SlideTrackDecor(pos: Float, answerLabel: String, declineLabel: String, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val shimmer = if (ParleyMotion.reducedMotion()) {
        null
    } else {
        rememberInfiniteTransition(label = "shimmer").animateFloat(0f, 1f, infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing)), label = "s")
    }
    val phase = shimmer?.let { s -> { s.value } }
    val hint = AnswerSlide.hintAlpha(pos)
    val declineEnd = Modifier.align(Alignment.CenterStart).serviceButton(declineLabel, onDecline)
    val answerEnd = Modifier.align(Alignment.CenterEnd).serviceButton(answerLabel, onAnswer)
    SlideTarget(Icons.Rounded.CallEnd, CallColors.Decline, AnswerSlide.targetFill(pos, -1), declineEnd)
    SlideTarget(Icons.Rounded.Call, CallColors.Accept, AnswerSlide.targetFill(pos, 1), answerEnd)
    Chevrons(Icons.Rounded.ChevronLeft, hint, phase, Modifier.align(Alignment.CenterStart), outward = -1)
    Chevrons(Icons.Rounded.ChevronRight, hint, phase, Modifier.align(Alignment.CenterEnd), outward = 1)
}

/**
 * The knob: primary at rest, turning green or red as it moves, its handset tipping over to the hang-up angle towards
 * Decline. At rest it gives a small nudge towards Answer every few seconds (still when animations are off).
 */
@Composable
private fun SlideKnob(state: SlideState, travel: Float) {
    val scheme = MaterialTheme.colorScheme
    val nudgePx = with(LocalDensity.current) { NUDGE.toPx() }
    val nudge = if (ParleyMotion.reducedMotion()) {
        null
    } else {
        // Start and end differ so the transition always runs; the keyframes pin both ends to rest.
        rememberInfiniteTransition(label = "nudge").animateFloat(
            0f, 1f,
            infiniteRepeatable(
                keyframes {
                    durationMillis = NUDGE_MS
                    0f at 0
                    0f at NUDGE_MS - 900
                    1f at NUDGE_MS - 680 using FastOutSlowInEasing
                    0f at NUDGE_MS - 380 using FastOutSlowInEasing
                    0.4f at NUDGE_MS - 220 using FastOutSlowInEasing
                    0f at NUDGE_MS using FastOutSlowInEasing
                },
            ),
            label = "n",
        )
    }
    val grow by animateFloatAsState(if (state.dragging) 1.06f else 1f, ParleyMotion.fastSpatial(), label = "grow")
    val pos = state.pos
    val fill = (abs(pos) / AnswerSlide.COMMIT).coerceAtMost(1f)
    Box(
        Modifier
            .offset {
                val idle = if (!state.dragging && !state.done && state.pos == 0f) (nudge?.value ?: 0f) * nudgePx else 0f
                IntOffset((state.pos * travel + idle).roundToInt(), 0)
            }
            .graphicsLayer {
                scaleX = grow
                scaleY = grow
            }
            .size(KNOB)
            .clip(CircleShape)
            .background(lerp(scheme.primary, if (pos >= 0f) CallColors.Accept else CallColors.Decline, fill)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Call, null, tint = lerp(scheme.onPrimary, Color.White, fill),
            modifier = Modifier.size(30.dp).graphicsLayer { rotationZ = if (pos < 0f) HANG_UP_TURN * fill else 0f },
        )
    }
}

/**
 * The hint under the track, so nothing ever hides it: it fades as the knob moves and says "Release to answer" (or
 * decline) once letting go would act. TalkBack already hears the track's description.
 */
@Composable
private fun SlideHint(pos: Float) {
    val armed = AnswerSlide.armed(pos)
    Text(
        when {
            armed > 0 -> stringResource(R.string.incall_release_to_answer)
            armed < 0 -> stringResource(R.string.incall_release_to_decline)
            else -> stringResource(R.string.incall_slide_hint)
        },
        style = MaterialTheme.typography.labelLarge,
        color = when {
            armed > 0 -> CallColors.Accept
            armed < 0 -> CallColors.Decline
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier
            .padding(top = Spacing.m)
            .heightIn(min = 20.dp)
            .alpha(if (armed != 0) 1f else AnswerSlide.hintAlpha(pos))
            .clearAndSetSemantics { },
    )
}

/** A button only accessibility services can press: a name, a role and a click action, but no touch handling. */
private fun Modifier.serviceButton(label: String, action: () -> Unit): Modifier = semantics {
    contentDescription = label
    role = Role.Button
    onClick(label) { action(); true }
}

/** An end of the slide track: a tinted circle that fills with its colour as the knob comes near ([fill] 0..1). */
@Composable
private fun SlideTarget(icon: ImageVector, color: Color, fill: Float, modifier: Modifier) {
    Box(Modifier.then(modifier).width(CallButtonSize.slot), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = 1f + TARGET_GROW * fill
                    scaleY = 1f + TARGET_GROW * fill
                }
                .size(TARGET)
                .clip(CircleShape)
                .background(lerp(color.copy(alpha = TARGET_REST_ALPHA), color, fill)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = lerp(color, Color.White, fill), modifier = Modifier.size(26.dp)) }
    }
}

/**
 * Two chevrons between the knob and an end, pointing at it. [phase] (0..1, repeating) runs a soft highlight
 * outwards; without it (animations off) they rest at a steady strength. [visible] fades them as the knob moves.
 */
@Composable
private fun Chevrons(icon: ImageVector, visible: Float, phase: (() -> Float)?, modifier: Modifier, outward: Int) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .padding(horizontal = CallButtonSize.slot)
            .alpha(visible),
    ) {
        // Innermost first: the highlight travels from the knob towards the end.
        val order = if (outward > 0) listOf(0, 1) else listOf(1, 0)
        order.forEach { i ->
            Icon(
                icon, null,
                modifier = Modifier.size(20.dp).graphicsLayer {
                    val p = phase?.invoke()
                    alpha = if (p == null) CHEVRON_REST else CHEVRON_LOW + (1f - CHEVRON_LOW) * wave(p - i * CHEVRON_STAGGER)
                },
                tint = tint,
            )
        }
    }
}

/** A soft pulse (0..1) that peaks once per cycle of [t]. */
private fun wave(t: Float): Float {
    val x = t - kotlin.math.floor(t)
    return if (x < 0.5f) sin(x * 2f * Math.PI.toFloat()).coerceAtLeast(0f) else 0f
}

private val TRACK = 80.dp
private val KNOB = 64.dp
private val TARGET = 56.dp
private val NUDGE = 12.dp
private const val TRACK_VEIL = 0.08f
private const val TARGET_REST_ALPHA = 0.18f
private const val TARGET_GROW = 0.1f
private const val HANG_UP_TURN = 135f
private const val CHEVRON_REST = 0.6f
private const val CHEVRON_LOW = 0.25f
private const val CHEVRON_STAGGER = 0.2f
private const val NUDGE_MS = 2600
private const val SHIMMER_MS = 1600
private const val RESET_MS = 1500L
