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
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledTonalButton
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
import app.parley.telecom.CallUi
import app.parley.telecom.R
import app.parley.ui.ParleyShapes

/**
 * An outgoing call that didn't go through: the reason ("Airplane mode is on", "No SIM was chosen", the network's
 * own text) and Retry. It stays until the user dismisses it. [onShowNumberAgain] (non-null when the call was placed
 * with the number hidden) adds the guess "Some people don't answer hidden numbers" and "Call again showing your number".
 */
@Composable
internal fun FailureBanner(
    call: CallUi,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onShowNumberAgain: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.errorContainer,
        shape = ParleyShapes.panel,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = scheme.onErrorContainer)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.call_failed_title), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = scheme.onErrorContainer,
                )
            }
            call.failureText?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onErrorContainer, modifier = Modifier.padding(top = 4.dp))
            }
            if (onShowNumberAgain != null) {
                Text(
                    stringResource(R.string.call_show_again_guess), style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onErrorContainer, modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(Modifier.align(Alignment.End).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onDismiss) { Text(stringResource(R.string.call_failed_dismiss), color = scheme.onErrorContainer) }
                if (onShowNumberAgain != null) {
                    TextButton(onShowNumberAgain) { Text(stringResource(R.string.call_show_again_action), color = scheme.onErrorContainer) }
                }
                FilledTonalButton(onRetry) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.call_failed_retry))
                }
            }
        }
    }
}

/**
 * The call-ended offer after a hidden outgoing call the other side seems to have turned away (a REMOTE/REJECTED end,
 * where no failure banner shows): worded as a guess, with "Call again showing your number" and a dismiss.
 */
@Composable
internal fun ShowNumberAgainCard(onCallAgain: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceVariant,
        shape = ParleyShapes.panel,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                stringResource(R.string.call_show_again_guess), style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            Row(Modifier.align(Alignment.End).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onDismiss) { Text(stringResource(R.string.call_failed_dismiss)) }
                FilledTonalButton(onCallAgain) { Text(stringResource(R.string.call_show_again_action)) }
            }
        }
    }
}
