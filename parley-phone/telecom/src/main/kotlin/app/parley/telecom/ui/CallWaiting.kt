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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.parley.telecom.R
import app.parley.ui.Bidi
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
                Text(call.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val state = when (call.state) {
                    CallState.HOLDING -> stringResource(R.string.incall_status_on_hold)
                    CallState.ACTIVE -> clockText(seconds)
                    else -> stringResource(R.string.incall_other_connecting)
                }
                Text(state, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (call.state == CallState.ACTIVE) {
                    Text(
                        stringResource(if (canHold) R.string.incall_answering_holds else R.string.incall_cannot_hold),
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
    val res = LocalResources.current
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
                            if (canHoldAnswer) add(CustomAccessibilityAction(res.getString(R.string.incall_hold_and_answer)) { CallManager.holdAndAnswer(ringing.id); true })
                            if (active != null) add(CustomAccessibilityAction(res.getString(R.string.incall_end_and_answer)) { CallManager.endAndAnswer(ringing.id); true })
                            if (active == null) add(CustomAccessibilityAction(res.getString(R.string.incall_answer)) { CallManager.answer(ringing.id); true })
                            add(CustomAccessibilityAction(res.getString(R.string.incall_decline)) { CallManager.reject(ringing.id); true })
                            if (canReply) add(CustomAccessibilityAction(res.getString(R.string.incall_reply_a11y)) { onReply(); true })
                        }
                    },
                ) {
                    Avatar(ringing.title, ringing.photoUri, 56.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(if (ringing.silenced) R.string.incall_waiting_call_silenced else R.string.incall_waiting_call), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(ringing.displayTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(ringing.label, ringing.number?.takeIf { ringing.name != null }?.let(Bidi::ltr), ringing.accountLabel, ringing.location).joinToString(stringResource(R.string.tc_separator))
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.size(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    when {
                        canHoldAnswer -> WaitingAction(Icons.Rounded.PauseCircle, stringResource(R.string.incall_hold_answer_short), stringResource(R.string.incall_hold_and_answer), CallColors.Accept) { CallManager.holdAndAnswer(ringing.id) }
                        active == null -> WaitingAction(Icons.Rounded.Call, stringResource(R.string.incall_answer), stringResource(R.string.incall_answer), CallColors.Accept) { CallManager.answer(ringing.id) }
                    }
                    if (active != null) {
                        WaitingAction(Icons.Rounded.PhoneInTalk, stringResource(R.string.incall_end_answer_short), stringResource(R.string.incall_end_and_answer), MaterialTheme.colorScheme.tertiary) { CallManager.endAndAnswer(ringing.id) }
                    }
                    WaitingAction(Icons.Rounded.CallEnd, stringResource(R.string.incall_decline), stringResource(R.string.incall_decline), CallColors.Decline) { CallManager.reject(ringing.id) }
                    if (canReply) {
                        WaitingAction(Icons.AutoMirrored.Rounded.Message, stringResource(R.string.incall_reply), stringResource(R.string.incall_reply_a11y), MaterialTheme.colorScheme.secondary, onReply)
                    }
                }
                if (!ringing.silenced) {
                    TextButton(onClick = { CallManager.ignore(ringing.id) }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.incall_ignore_waiting_tone)) }
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
