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
fun IncomingControls(call: CallUi, gesture: AnswerGesture, hasActiveCall: Boolean, onMessage: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!call.hidden && !call.number.isNullOrBlank()) {
            FilledTonalButton(onClick = onMessage) {
                Icon(Icons.AutoMirrored.Rounded.Message, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Reply with message")
            }
            Spacer(Modifier.height(28.dp))
        }
        when (gesture) {
            AnswerGesture.SWIPE -> AnswerSlider(onAnswer = { CallManager.answer(call.id) }, onDecline = { CallManager.reject(call.id) })
            AnswerGesture.TAP -> AnswerButtons(onAnswer = { CallManager.answer(call.id) }, onDecline = { CallManager.reject(call.id) })
        }
        if (hasActiveCall) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { CallManager.endAndAnswer(call.id) }) { Text("End current call and answer") }
        }
    }
}

@Composable
private fun AnswerButtons(onAnswer: () -> Unit, onDecline: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        RoundAction(Icons.Rounded.CallEnd, "Decline", CallColors.Decline, onDecline)
        RoundAction(Icons.Rounded.Call, "Answer", CallColors.Accept, onAnswer)
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(80.dp).clip(CircleShape).background(color)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * Horizontal slide-to-answer: drag right to answer, left to decline. Requires a deliberate drag
 * past 55% of the track, which avoids pocket answers. TalkBack users get explicit actions.
 */
@Composable
private fun AnswerSlider(onAnswer: () -> Unit, onDecline: () -> Unit) {
    val scope = rememberCoroutineScope()
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
                contentDescription = "Incoming call. Slide right to answer, left to decline."
                customActions = listOf(
                    CustomAccessibilityAction("Answer") { onAnswer(); true },
                    CustomAccessibilityAction("Decline") { onDecline(); true },
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
            Text(
                if (progress > 0.1f) "Answer" else if (progress < -0.1f) "Decline" else "Slide to answer",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
