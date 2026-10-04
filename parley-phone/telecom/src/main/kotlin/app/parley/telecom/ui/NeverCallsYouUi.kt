package app.parley.telecom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.parley.telecom.R
import app.parley.ui.ParleyShapes
import app.parley.ui.Spacing

/**
 * "This number never calls you": a calm card under the caller while a saved organisation calls on a number you have
 * only ever called (caller ID can be faked, and a faked bank number shows the bank's saved name). It says what your
 * calls show, never that the call is a scam, and offers Check it's really them ([onVerify], null when the call can't be
 * checked) and the scam sheet ([onScamCheck]). In the secondary colours, not the error ones: most such calls are real.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NeverCallsYouCard(onVerify: (() -> Unit)?, onScamCheck: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer, shape = ParleyShapes.card,
        // TalkBack reads it once when it appears while the call rings.
        modifier = modifier.widthIn(max = 480.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.m, bottom = Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.CallReceived, null, Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.m))
                Text(
                    stringResource(R.string.never_calls_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                stringResource(R.string.never_calls_body), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            FlowRow(
                Modifier.fillMaxWidth().padding(top = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End),
            ) {
                TextButton(onScamCheck) {
                    Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.scam_title))
                }
                if (onVerify != null) {
                    FilledTonalButton(onVerify) {
                        Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.verify_title))
                    }
                }
            }
        }
    }
}
