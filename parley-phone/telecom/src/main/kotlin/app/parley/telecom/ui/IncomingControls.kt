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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.FilledTonalButton
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

@Composable
fun IncomingControls(
    call: CallUi, gesture: AnswerGesture, hasActiveCall: Boolean, onMessage: () -> Unit, onBlockAndDecline: (() -> Unit)? = null,
    simple: Boolean = false, confirmDecline: Boolean = false,
) {
    // X4: simple mode asks before declining, so a stray tap never sends a call away.
    var askDecline by remember { mutableStateOf(false) }
    val decline = { if (confirmDecline) askDecline = true else CallManager.reject(call.id) }
    if (askDecline) DeclineQuestion(onDecline = { askDecline = false; CallManager.reject(call.id) }, onDismiss = { askDecline = false })
    if (simple) {
        SimpleAnswerButtons(call.simHint, onAnswer = { CallManager.answer(call.id) }, onDecline = decline)
        return
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!call.hidden && !call.number.isNullOrBlank()) {
            FilledTonalButton(onClick = onMessage) {
                Icon(Icons.AutoMirrored.Rounded.Message, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.incall_reply_with_message))
            }
            Spacer(Modifier.height(28.dp))
        }
        // V5: on dual-SIM phones, which SIM the call came in on ("Work · …4567"), right on the answer control.
        val sim = call.simHint
        when (gesture) {
            AnswerGesture.SWIPE -> AnswerSlider(sim, onAnswer = { CallManager.answer(call.id) }, onDecline = decline)
            AnswerGesture.TAP -> AnswerButtons(sim, onAnswer = { CallManager.answer(call.id) }, onDecline = decline)
        }
        if (!call.silenced || onBlockAndDecline != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!call.silenced) TextButton(onClick = { CallManager.ignore(call.id) }) { Text(stringResource(R.string.incall_ignore_stop_ringing)) }
                // P2: "Block & decline" sits behind ⋮, two deliberate taps, so it can't happen by accident.
                if (onBlockAndDecline != null) BlockAndDeclineMenu(onBlockAndDecline)
            }
        }
        if (hasActiveCall) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { CallManager.endAndAnswer(call.id) }) { Text(stringResource(R.string.incall_end_and_answer)) }
        }
    }
}

/** X4: "Decline this call?" (simple mode). */
@Composable
private fun DeclineQuestion(onDecline: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.x_incall_decline_q)) },
        text = { Text(stringResource(R.string.x_incall_decline_body)) },
        confirmButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.incall_decline), color = CallColors.Decline) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.x_incall_keep_ringing)) } },
    )
}

/** X4: two very large buttons, answer on top (easy to reach and hard to miss), decline below. */
@Composable
private fun SimpleAnswerButtons(sim: String?, onAnswer: () -> Unit, onDecline: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BigAction(Icons.Rounded.Call, stringResource(R.string.incall_answer), CallColors.Accept, onAnswer, height = 112, a11y = sim?.let { stringResource(R.string.incall_answer_on, it) })
        if (sim != null) SimTag(sim, Modifier.align(Alignment.CenterHorizontally))
        BigAction(Icons.Rounded.CallEnd, stringResource(R.string.incall_decline), CallColors.Decline, onDecline, height = 80)
    }
}

@Composable
private fun BigAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit, height: Int, a11y: String? = null) {
    Row(
        Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(28.dp)).background(color)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = a11y ?: label },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(40.dp))
        Spacer(Modifier.size(16.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.headlineMedium)
    }
}

/** P2: ⋮ on the incoming screen with "Block & decline". */
@Composable
private fun BlockAndDeclineMenu(onBlockAndDecline: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.incall_incoming_more)) }
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

@Composable
private fun AnswerButtons(sim: String?, onAnswer: () -> Unit, onDecline: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        RoundAction(Icons.Rounded.CallEnd, stringResource(R.string.incall_decline), CallColors.Decline, onDecline)
        RoundAction(Icons.Rounded.Call, stringResource(R.string.incall_answer), CallColors.Accept, onAnswer, sub = sim, a11y = sim?.let { stringResource(R.string.incall_answer_on, it) })
    }
}

@Composable
private fun RoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit,
    sub: String? = null, a11y: String? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(80.dp).clip(CircleShape).background(color)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = a11y ?: label },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        if (sub != null) SimTag(sub, Modifier.padding(top = 4.dp))
    }
}

/** The SIM a call came in on, as a small tag under the answer control (V5). */
@Composable
private fun SimTag(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 10.dp, vertical = 4.dp),
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
 * The track stays left to right in right-to-left languages too, matching "slide right to answer" (L3).
 */
@Composable
private fun AnswerSlider(sim: String?, onAnswer: () -> Unit, onDecline: () -> Unit) = ForceLtr {
    val scope = rememberCoroutineScope()
    val answerLabel = stringResource(R.string.incall_answer)
    val declineLabel = stringResource(R.string.incall_decline)
    val description = if (sim != null) stringResource(R.string.incall_slider_description_sim, sim) else stringResource(R.string.incall_slider_description)
    val haptics = LocalHapticFeedback.current
    val offset = remember { Animatable(0f) }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "p",
    )
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(44.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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
        val thumb = 72.dp
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
            val scale = if (abs(progress) < 0.05f) 1f + 0.12f * pulse else 1f
            Icon(Icons.Rounded.Call, null, tint = Color.White, modifier = Modifier.size((32 * scale).dp))
        }
    }
}
