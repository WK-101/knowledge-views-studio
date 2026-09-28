package app.parley.telecom.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.parley.telecom.R
import app.parley.ui.ForceLtr
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.parley.common.AnswerGesture
import app.parley.telecom.CallManager
import app.parley.telecom.CallUi
import app.parley.ui.CallColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyShapes
import app.parley.ui.ParleyMotion
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import app.parley.ui.Spacing

/**
 * The incoming screen's controls (docs/CALL_SCREEN_DESIGN.md): a quiet row of secondary actions (Reply, Silence,
 * ⋮ with Block & decline), then answer and decline as the chosen gesture (a slider or two round buttons), then
 * "End current call and answer" while another call is going.
 */
@Composable
fun IncomingControls(
    call: CallUi, gesture: AnswerGesture, hasActiveCall: Boolean, onMessage: () -> Unit, onBlockAndDecline: (() -> Unit)? = null,
    simple: Boolean = false, confirmDecline: Boolean = false,
) {
    // Simple mode asks before declining, so a stray tap never sends a call away.
    var askDecline by remember { mutableStateOf(false) }
    val decline = { if (confirmDecline) askDecline = true else CallManager.reject(call.id) }
    if (askDecline) DeclineQuestion(onDecline = { askDecline = false; CallManager.reject(call.id) }, onDismiss = { askDecline = false })
    if (simple) {
        SimpleAnswerButtons(call.simHint, onAnswer = { CallManager.answer(call.id) }, onDecline = decline)
        return
    }
    Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(bottom = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        IncomingSecondaryRow(call, onMessage, onBlockAndDecline)
        Spacer(Modifier.height(Spacing.xl))
        // On dual-SIM phones, which SIM the call came in on ("Work · …4567"), right on the answer control.
        val sim = call.simHint
        when (gesture) {
            AnswerGesture.SWIPE -> AnswerSlider(sim, onAnswer = { CallManager.answer(call.id) }, onDecline = decline)
            AnswerGesture.TAP -> AnswerButtons(sim, onAnswer = { CallManager.answer(call.id) }, onDecline = decline)
        }
        if (hasActiveCall) {
            Spacer(Modifier.height(Spacing.m))
            TextButton(onClick = { CallManager.endAndAnswer(call.id) }) { Text(stringResource(R.string.incall_end_and_answer)) }
        }
    }
}

/** Reply with a message, Silence (stop ringing) and ⋮ (Block & decline), all the same quiet pill. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IncomingSecondaryRow(call: CallUi, onMessage: () -> Unit, onBlockAndDecline: (() -> Unit)?) {
    val canReply = !call.hidden && !call.number.isNullOrBlank()
    if (!canReply && call.silenced && onBlockAndDecline == null) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (canReply) {
            SecondaryAction(
                Icons.AutoMirrored.Rounded.Message, stringResource(R.string.incall_reply), onMessage, spoken = stringResource(R.string.incall_reply_a11y),
            )
        }
        if (!call.silenced) SecondaryAction(Icons.Rounded.NotificationsOff, stringResource(R.string.incall_silence), { CallManager.ignore(call.id) })
        // "Block & decline" sits behind ⋮, two deliberate taps, so it can't happen by accident.
        if (onBlockAndDecline != null) BlockAndDeclineMenu(onBlockAndDecline)
    }
}

/** "Decline this call?" (simple mode). */
@Composable
internal fun DeclineQuestion(onDecline: () -> Unit, onDismiss: () -> Unit) {
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.incall_decline_q)) },
        text = { Text(stringResource(R.string.incall_decline_body)) },
        confirmButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.incall_decline), color = CallColors.Decline) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.incall_keep_ringing)) } },
    )
}

/** Two very large buttons, answer on top (easy to reach and hard to miss), decline below. */
@Composable
private fun SimpleAnswerButtons(sim: String?, onAnswer: () -> Unit, onDecline: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BigAction(Icons.Rounded.Call, stringResource(R.string.incall_answer), CallColors.Accept, onAnswer, height = 112, a11y = sim?.let { stringResource(R.string.incall_answer_on, it) })
        if (sim != null) SimTag(sim, Modifier.align(Alignment.CenterHorizontally))
        BigAction(Icons.Rounded.CallEnd, stringResource(R.string.incall_decline), CallColors.Decline, onDecline, height = 80)
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

/** ⋮ on the incoming screen with "Block & decline". */
@Composable
private fun BlockAndDeclineMenu(onBlockAndDecline: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalIconButton(
            onClick = { open = true },
            modifier = Modifier.size(CallButtonSize.secondaryHeight),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        ) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.incall_incoming_more)) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
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

/** Tap to answer: Decline and Answer as two 80 dp circles, Answer with a slow halo while it rings. */
@Composable
private fun AnswerButtons(sim: String?, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val still = ParleyMotion.reducedMotion()
    // Read only while drawing, so the halo redraws without recomposing the buttons.
    val halo = if (still) {
        null
    } else {
        rememberInfiniteTransition(label = "halo").animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "t")
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
        CallActionButton(Icons.Rounded.CallEnd, stringResource(R.string.incall_decline), CallColors.Decline, onDecline)
        CallActionButton(
            Icons.Rounded.Call, stringResource(R.string.incall_answer), CallColors.Accept, onAnswer,
            spoken = sim?.let { stringResource(R.string.incall_answer_on, it) } ?: stringResource(R.string.incall_answer),
            halo = halo?.let { h -> { h.value } },
            below = sim?.let { { SimTag(it, Modifier.padding(top = Spacing.xs)) } },
        )
    }
}

/** The SIM a call came in on, as a small tag under the answer control. */
@Composable
private fun SimTag(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(ParleyShapes.control).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.SimCard, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Spacer(Modifier.size(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
    }
}

/**
 * Horizontal slide-to-answer: drag right to answer, left to decline. Requires a deliberate drag
 * past 55% of the track, which avoids pocket answers. TalkBack users get explicit actions.
 * The track stays left to right in right-to-left languages too, matching "slide right to answer".
 */
@Composable
private fun AnswerSlider(sim: String?, onAnswer: () -> Unit, onDecline: () -> Unit) = ForceLtr {
    val scope = rememberCoroutineScope()
    val answerLabel = stringResource(R.string.incall_answer)
    val declineLabel = stringResource(R.string.incall_decline)
    val description = if (sim != null) stringResource(R.string.incall_slider_description_sim, sim) else stringResource(R.string.incall_slider_description)
    val haptics = LocalHapticFeedback.current
    val offset = remember { Animatable(0f) }
    val still = ParleyMotion.reducedMotion()
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "p",
    )
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(ParleyShapes.pill)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .semantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(answerLabel) { onAnswer(); true },
                    CustomAccessibilityAction(declineLabel) { onDecline(); true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val thumb = 64.dp
        val maxPx = with(density) { ((maxWidth - thumb) / 2 - 8.dp).toPx() }
        val progress = (offset.value / maxPx).coerceIn(-1f, 1f)

        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CallEnd, null, tint = CallColors.Decline.copy(alpha = 0.5f + 0.5f * (-progress).coerceAtLeast(0f)))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (progress > 0.1f) answerLabel else if (progress < -0.1f) declineLabel else stringResource(R.string.incall_slide_to_answer),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sim != null && abs(progress) <= 0.1f) {
                    Text(
                        stringResource(R.string.incall_on_sim, sim), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1,
                    )
                }
            }
            Icon(Icons.Rounded.Call, null, tint = CallColors.Accept.copy(alpha = 0.5f + 0.5f * progress.coerceAtLeast(0f)))
        }
        val color = when {
            progress > 0 -> lerp(MaterialTheme.colorScheme.primary, CallColors.Accept, progress)
            else -> lerp(MaterialTheme.colorScheme.primary, CallColors.Decline, -progress)
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .size(thumb)
                .clip(CircleShape)
                .background(color)
                .pointerInput(maxPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val p = offset.value / maxPx
                            scope.launch {
                                when {
                                    p > 0.55f -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); offset.animateTo(maxPx); onAnswer() }
                                    p < -0.55f -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); offset.animateTo(-maxPx); onDecline() }
                                    else -> offset.animateTo(0f)
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { change, drag ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + drag).coerceIn(-maxPx, maxPx)) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // With animations removed (Accessibility settings) the thumb stays still.
            val scale = if (!still && abs(progress) < 0.05f) 1f + 0.12f * pulse else 1f
            Icon(Icons.Rounded.Call, null, tint = Color.White, modifier = Modifier.size((32 * scale).dp))
        }
    }
}
