package app.parley.telecom.ui

import androidx.annotation.StringRes
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
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.parley.common.people.FreshNumber
import app.parley.common.people.NumberOrigin
import app.parley.common.people.NumberPassport
import app.parley.telecom.R
import app.parley.ui.ParleyShapes
import app.parley.ui.Spacing

/**
 * The fresh-number line: a saved contact calls from a number that was added or changed recently, and not by you
 * ("save our new number" is a common scam step, and the scammer's calls then show a trusted name). It says when and
 * from where, never that the call is a scam, and offers their older number ([onCallOlder], dialled after the unlock
 * and never shown here) or, when there is none, Check it's really them ([onVerify]). In the secondary colours, like
 * "This number never calls you": most new numbers are genuine.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FreshNumberCard(fresh: FreshNumber, onCallOlder: (() -> Unit)?, onVerify: (() -> Unit)?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val days = NumberPassport.daysAgo(fresh.since, System.currentTimeMillis())
    val title = if (days == 0) stringResource(R.string.fresh_number_title_today) else pluralStringResource(R.plurals.fresh_number_title, days, days)
    val from = stringResource(originText(fresh.origin))
    val body = stringResource(if (onCallOlder != null) R.string.fresh_number_body_older else R.string.fresh_number_body_check, from)
    Surface(
        color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer, shape = ParleyShapes.card,
        // TalkBack reads it once when it appears while the call rings.
        modifier = modifier.widthIn(max = 480.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.m, bottom = Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.NewReleases, null, Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.m))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.xs))
            FlowRow(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End)) {
                when {
                    onCallOlder != null -> FilledTonalButton(onCallOlder) {
                        Icon(Icons.Rounded.History, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.fresh_number_call_older))
                    }
                    onVerify != null -> FilledTonalButton(onVerify) {
                        Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.verify_title))
                    }
                }
            }
        }
    }
}

/** Where the number came from, in a few words (only the origins that warn reach the call screen). */
@StringRes
internal fun originText(origin: NumberOrigin): Int = when (origin) {
    NumberOrigin.PASTED -> R.string.fresh_number_from_pasted
    NumberOrigin.SCANNED -> R.string.fresh_number_from_scanned
    NumberOrigin.IMPORTED -> R.string.fresh_number_from_imported
    NumberOrigin.HANDED_OVER -> R.string.fresh_number_from_handed_over
    NumberOrigin.SHARED_LABEL -> R.string.fresh_number_from_shared_label
    NumberOrigin.SYNC -> R.string.fresh_number_from_sync
    else -> R.string.fresh_number_from_other_app
}
