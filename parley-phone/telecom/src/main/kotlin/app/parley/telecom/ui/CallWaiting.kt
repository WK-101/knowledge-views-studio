package app.parley.telecom.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.telecom.CallManager
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.ui.Avatar
import app.parley.ui.CallColors

/**
 * The call you're on, dimmed at the top while another call is waiting (A1). It stays readable: the name and
 * the running time, and what answering will do to it.
 */
@Composable
internal fun CurrentCallCard(call: CallUi, canHold: Boolean, modifier: Modifier = Modifier) {
    val seconds by rememberCallSeconds(call.connectTimeMillis)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth().alpha(0.72f),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(call.title, call.photoUri, 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(call.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val state = when (call.state) {
                    CallState.HOLDING -> "On hold"
                    CallState.ACTIVE -> clockText(seconds)
                    else -> "Connecting…"
                }
                Text(state, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (call.state == CallState.ACTIVE) {
                    Text(
                        if (canHold) "Answering puts this call on hold" else "This call can't be put on hold",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The waiting call, sliding up as a sheet with Hold & answer · End & answer · Decline · Reply (A1). It can't be
 * swiped away: the call keeps ringing until one of the choices is made.
 */
@Composable
internal fun CallWaitingSheet(ringing: CallUi, current: CallUi?, heldCount: Int, onReply: () -> Unit) {
    val active = current?.takeIf { it.state == CallState.ACTIVE }
    val canHoldAnswer = active != null && active.canHold && heldCount == 0
    val canReply = !ringing.hidden && !ringing.number.isNullOrBlank()
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visible, enter = slideInVertically { it } + fadeIn()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(width = 32.dp, height = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
                Spacer(Modifier.size(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                        customActions = buildList {
                            if (canHoldAnswer) add(CustomAccessibilityAction("Hold current call and answer") { CallManager.holdAndAnswer(ringing.id); true })
                            if (active != null) add(CustomAccessibilityAction("End current call and answer") { CallManager.endAndAnswer(ringing.id); true })
                            if (active == null) add(CustomAccessibilityAction("Answer") { CallManager.answer(ringing.id); true })
                            add(CustomAccessibilityAction("Decline") { CallManager.reject(ringing.id); true })
                            if (canReply) add(CustomAccessibilityAction("Reply with a message") { onReply(); true })
                        }
                    },
                ) {
                    Avatar(ringing.title, ringing.photoUri, 56.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (ringing.silenced) "Waiting call · silenced" else "Waiting call", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(ringing.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(ringing.label, ringing.number?.takeIf { ringing.name != null }, ringing.accountLabel, ringing.location).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.size(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    when {
                        canHoldAnswer -> WaitingAction(Icons.Rounded.PauseCircle, "Hold &\nanswer", "Hold current call and answer", CallColors.Accept) { CallManager.holdAndAnswer(ringing.id) }
                        active == null -> WaitingAction(Icons.Rounded.Call, "Answer", "Answer", CallColors.Accept) { CallManager.answer(ringing.id) }
                    }
                    if (active != null) {
                        WaitingAction(Icons.Rounded.PhoneInTalk, "End &\nanswer", "End current call and answer", MaterialTheme.colorScheme.tertiary) { CallManager.endAndAnswer(ringing.id) }
                    }
                    WaitingAction(Icons.Rounded.CallEnd, "Decline", "Decline", CallColors.Decline) { CallManager.reject(ringing.id) }
                    if (canReply) {
                        WaitingAction(Icons.AutoMirrored.Rounded.Message, "Reply", "Reply with a message", MaterialTheme.colorScheme.secondary, onReply)
                    }
                }
                if (!ringing.silenced) {
                    TextButton(onClick = { CallManager.ignore(ringing.id) }, modifier = Modifier.padding(top = 8.dp)) { Text("Ignore — stop the waiting tone") }
                }
            }
        }
    }
}

@Composable
private fun WaitingAction(icon: ImageVector, label: String, spoken: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(80.dp)) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = spoken },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp), maxLines = 2)
    }
}
