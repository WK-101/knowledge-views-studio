package app.parley.telecom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import app.parley.telecom.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.telecom.CallManager
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.ui.Avatar
import app.parley.ui.CallColors

/**
 * "Ana on hold · 02:10" with Swap, Merge and End inline (A2). Tapping the strip swaps, only when the call in front
 * can be held (or swapped as a conference); otherwise the strip offers Merge and End and tapping does nothing.
 * When the call in front ends, [CallManager] resumes the held call by itself.
 */
@Composable
internal fun OnHoldStrip(held: CallUi, front: CallUi?, modifier: Modifier = Modifier) {
    val now by rememberElapsedNow()
    val heldFor = if (held.heldSinceElapsed > 0) (now - held.heldSinceElapsed) / 1000 else 0
    // While another call is being dialled, switching would disturb it: only End is offered then. Resuming the held
    // call makes Telecom hold the active one, and end it if it can't be held: swap only when that's possible.
    val frontActive = front == null || front.state == CallState.ACTIVE
    val canSwap = front == null || (front.state == CallState.ACTIVE && (front.canHold || front.canSwap))
    val swap = { if (front != null) CallManager.swap(front.id) else CallManager.toggleHold(held.id) }
    val canMerge = frontActive && front?.canMerge == true
    val res = LocalResources.current
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = canSwap, role = Role.Button, onClickLabel = res.getString(R.string.incall_switch_to_call), onClick = swap)
            .semantics(mergeDescendants = true) {
                contentDescription = res.getString(R.string.incall_held_description, held.title, spokenDuration(res, heldFor))
                customActions = buildList {
                    if (canSwap) add(CustomAccessibilityAction(res.getString(R.string.incall_swap_calls)) { swap(); true })
                    if (canMerge) add(CustomAccessibilityAction(res.getString(R.string.incall_merge_calls)) { CallManager.merge(front.id); true })
                    add(CustomAccessibilityAction(res.getString(R.string.incall_end_held_call)) { CallManager.hangup(held.id); true })
                }
            },
    ) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(held.title, held.photoUri, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(held.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(res.getString(R.string.incall_on_hold_for, clockText(heldFor)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            if (canSwap) FilledTonalIconButton(onClick = swap) { Icon(Icons.Rounded.SwapCalls, res.getString(R.string.incall_swap_calls)) }
            if (canMerge) {
                FilledTonalIconButton(onClick = { CallManager.merge(front.id) }) { Icon(Icons.AutoMirrored.Rounded.CallMerge, res.getString(R.string.incall_merge_calls)) }
            }
            FilledTonalIconButton(
                onClick = { CallManager.hangup(held.id) },
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = CallColors.Decline, contentColor = Color.White),
            ) { Icon(Icons.Rounded.CallEnd, res.getString(R.string.incall_end_held_call)) }
        }
    }
}
