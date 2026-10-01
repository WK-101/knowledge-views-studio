package app.parley.ui.calls

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.SignalCellularConnectedNoInternet0Bar
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.calls.CallQualityFacts
import app.parley.common.calls.DropKind
import app.parley.ui.ParleyListItem
import app.parley.ui.common.Format
import app.parley.ui.contact.Section
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Number history: the facts of recent calls worth knowing (L2, L10): the subject the caller sent, a call the network
 * dropped and why, Wi-Fi calling and HD voice, the SIM. Plain calls with nothing to say aren't listed.
 */
@Composable
fun CallFactsHistorySection(vm: AppViewModel, number: String) {
    val version by vm.c.callQuality.version.collectAsStateWithLifecycle()
    val facts by produceState(emptyList<CallQualityFacts>(), number, version) {
        value = withContext(Dispatchers.IO) { runCatching { vm.c.callQuality.forNumber(number) }.getOrDefault(emptyList()) }
    }
    val shown = facts.filter { it.subject != null || it.drop != null || it.wifi || it.hd }.take(MAX_SHOWN)
    if (shown.isEmpty()) return
    Column {
        Section(stringResource(R.string.callfacts_section_title))
        shown.forEach { f ->
            ParleyListItem(
                leadingContent = { Icon(factsIcon(f), null) },
                headlineContent = { Text(headline(f)) },
                supportingContent = { Text(details(f)) },
            )
        }
    }
}

private fun factsIcon(f: CallQualityFacts): ImageVector = when {
    f.drop != null -> Icons.Rounded.SignalCellularConnectedNoInternet0Bar
    f.subject != null -> Icons.AutoMirrored.Rounded.Subject
    f.wifi -> Icons.Rounded.Wifi
    else -> Icons.Rounded.Hd
}

/** The caller's subject leads; else "Call dropped", else the direction. */
@Composable
private fun headline(f: CallQualityFacts): String {
    val subject = f.subject
    return when {
        subject != null -> stringResource(R.string.callfacts_subject, subject)
        f.drop != null -> stringResource(R.string.callfacts_dropped)
        f.incoming -> stringResource(R.string.callfacts_incoming)
        else -> stringResource(R.string.callfacts_outgoing)
    }
}

/** "3 Sep, 14:02 · 4 min · Dropped: lost signal · Wi-Fi calling · HD voice · Work". */
@Composable
private fun details(f: CallQualityFacts): String {
    val context = LocalContext.current
    val parts = buildList {
        add(Format.fullDate(context, f.startedAt))
        if (f.durationSec > 0) add(Format.duration(f.durationSec))
        // The headline already says "Call dropped" unless a subject leads.
        f.drop?.takeIf { f.subject != null || it != DropKind.NETWORK }?.let { add(dropText(it)) }
        if (f.wifi) add(stringResource(R.string.callfacts_wifi))
        if (f.hd) add(stringResource(R.string.callfacts_hd))
        f.sim?.let(::add)
    }
    return parts.joinToString(stringResource(R.string.main_separator))
}

@Composable
private fun dropText(kind: DropKind): String = when (kind) {
    DropKind.LOST_SIGNAL -> stringResource(R.string.callfacts_drop_lost_signal)
    DropKind.WIFI_LOST -> stringResource(R.string.callfacts_drop_wifi_lost)
    DropKind.NO_SERVICE -> stringResource(R.string.callfacts_drop_no_service)
    DropKind.NETWORK -> stringResource(R.string.callfacts_dropped)
}

private const val MAX_SHOWN = 5
