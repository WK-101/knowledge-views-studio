package app.parley.telecom.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.parley.common.spam.RangeProposal
import app.parley.common.ux.Tips
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.R
import app.parley.telecom.ReputationText
import app.parley.telecom.TelecomGraph
import app.parley.ui.Bidi
import app.parley.ui.ParleyMotion
import app.parley.ui.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * I2: "Looks like a sales line (your calls)", a quiet line under the status while an unknown number rings, with "Why?"
 * opening the reasons in place. Never a warning colour: it's what your own calls suggest, not a verdict.
 */
@Composable
internal fun ReputationLine(call: CallUi, ended: Boolean, compact: Boolean) {
    if (ended || compact || call.state != CallState.RINGING) return
    val rep = call.reputation ?: return
    val res = LocalResources.current
    var open by rememberSaveable(call.id) { mutableStateOf(false) }
    // P18: one line of explanation the first time the tag shows.
    val firstTime = remember { runCatching { !TelecomGraph.dependencies.tipSeen(Tips.REPUTATION_TAG) }.getOrDefault(false) }
    LaunchedEffect(Unit) { if (firstTime) runCatching { TelecomGraph.dependencies.markTipSeen(Tips.REPUTATION_TAG) } }
    val reasons = remember(rep, res) { ReputationText.reasons(res, rep) }
    Column(Modifier.padding(top = Spacing.xs).widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Storefront, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Spacing.xs))
            Text(stringResource(R.string.rep_tag), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ open = !open }) { Text(stringResource(if (open) R.string.rep_why_hide else R.string.rep_why)) }
        }
        if (firstTime && !open) {
            Text(
                stringResource(R.string.rep_tip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        AnimatedVisibility(open, enter = ParleyMotion.expandIn(), exit = ParleyMotion.collapseOut()) {
            ReputationReasons(reasons)
        }
    }
}

/** The reasons, one per line, and where they come from. */
@Composable
private fun ReputationReasons(reasons: List<String>) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Text(stringResource(R.string.rep_why_title), style = MaterialTheme.typography.titleSmall)
        reasons.forEach { Text(stringResource(R.string.rep_reason_bullet, it), style = MaterialTheme.typography.bodyMedium) }
        Text(
            stringResource(R.string.rep_why_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

/** The offer ("Numbers starting +336123456… (3 numbers) · would have matched 7 past calls"), or what happened. */
@Composable
private fun rangeLine(p: RangeProposal, s: RangeBlock): String = when {
    s is RangeBlock.Done && s.ruleId == null -> stringResource(R.string.rep_range_failed)
    s is RangeBlock.Done && s.ruleId == 0L -> stringResource(R.string.rep_range_already)
    s is RangeBlock.Done -> stringResource(R.string.rep_range_blocked)
    s is RangeBlock.Undone -> stringResource(R.string.rep_range_undone)
    else -> stringResource(
        R.string.rep_range_text, Bidi.ltr(p.prefix),
        pluralStringResource(R.plurals.rep_range_numbers, p.numbers, p.numbers),
        pluralStringResource(R.plurals.rep_range_calls, p.calls, p.calls),
    )
}

/** What "Block range" did, for the card's own line and Undo. */
private sealed interface RangeBlock {
    data object Idle : RangeBlock
    data object Working : RangeBlock
    data class Done(val ruleId: Long?) : RangeBlock
    data object Undone : RangeBlock
}

/**
 * I2 on the post-call card, after a call that looked like a sales line: "Block this range?" with the narrowest prefix
 * covering the related numbers from your calls and how many past calls it would have matched, then Undo. Nothing shows
 * when there's no range to offer (one number alone, or someone you know in the range).
 */
@Composable
internal fun BlockRangeOffer(call: CallUi) {
    val number = call.number ?: return
    if (call.reputation == null) return
    val proposal by produceState<RangeProposal?>(null, number, call.accountId) {
        value = withContext(Dispatchers.IO) { runCatching { TelecomGraph.dependencies.rangeProposal(number, call.accountId) }.getOrNull() }
    }
    val p = proposal ?: return
    val scope = rememberCoroutineScope()
    var state by remember(p) { mutableStateOf<RangeBlock>(RangeBlock.Idle) }
    Column(Modifier.padding(top = Spacing.m).semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(stringResource(R.string.rep_range_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val s = state
        Text(rangeLine(p, s), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val undoId = (s as? RangeBlock.Done)?.ruleId?.takeIf { it > 0L }
        Row(Modifier.padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            when {
                s is RangeBlock.Idle || s is RangeBlock.Undone -> FilledTonalButton({
                    state = RangeBlock.Working
                    scope.launch { state = RangeBlock.Done(runCatching { TelecomGraph.dependencies.blockRange(p.prefix) }.getOrNull()) }
                }) {
                    Icon(Icons.Rounded.Block, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.rep_range_block))
                }
                // The same removal as "Block & decline"'s Undo: the rule just written goes.
                undoId != null -> TextButton({
                    scope.launch {
                        runCatching { TelecomGraph.dependencies.undoBlockForDecline(undoId) }
                        state = RangeBlock.Undone
                    }
                }) { Text(stringResource(R.string.tc_undo)) }
                else -> Unit
            }
        }
    }
}
