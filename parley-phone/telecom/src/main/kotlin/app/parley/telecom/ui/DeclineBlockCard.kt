package app.parley.telecom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.parley.telecom.DeclineBlock
import app.parley.telecom.R
import app.parley.ui.Bidi
import app.parley.ui.ParleyShapes

/** After "Block & decline": what happened, and Undo while the rule is Parley's own new one. */
@Composable
internal fun DeclineBlockCard(block: DeclineBlock, onUndo: () -> Unit, onDone: () -> Unit) {
    val number = Bidi.ltr(block.number)
    val (title, body) = when {
        block.pending -> stringResource(R.string.decline_title) to stringResource(R.string.decline_block_pending)
        block.undone -> stringResource(R.string.decline_title) to stringResource(R.string.decline_block_undone, number)
        block.ruleId == null -> stringResource(R.string.decline_title) to stringResource(R.string.decline_block_failed)
        block.ruleId == 0L -> stringResource(R.string.decline_title) to stringResource(R.string.decline_block_already)
        block.answered -> stringResource(R.string.decline_blocked_title) to stringResource(R.string.decline_blocked_answered, number)
        else -> stringResource(R.string.decline_blocked_title) to stringResource(R.string.decline_blocked_body, number)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = ParleyShapes.sheet,
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Block, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.align(Alignment.End).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if ((block.ruleId ?: 0L) > 0L && !block.undone && !block.pending) TextButton(onUndo) { Text(stringResource(R.string.tc_undo)) }
                TextButton(onDone) { Text(stringResource(R.string.tc_done)) }
            }
        }
    }
}

/** In place of the answer controls while "Block & decline" writes the rule (a second or so at most). */
@Composable
internal fun BlockingDecline() {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 64.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(Modifier.size(32.dp))
        Text(stringResource(R.string.decline_block_pending), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    }
}
