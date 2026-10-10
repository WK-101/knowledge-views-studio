package app.parley.ui.calls

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SignalCellularConnectedNoInternet0Bar
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.ux.DefaultAppFeature
import app.parley.R
import app.parley.common.calls.CallQualityDiary
import app.parley.common.calls.CallQualityFacts
import app.parley.common.calls.NumberQuality
import app.parley.common.calls.DropKind
import app.parley.ui.ParleyListItem
import app.parley.ui.Section
import app.parley.ui.common.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Number history: the facts of recent calls worth knowing: the subject the caller sent, a call the network
 * dropped and why, Wi-Fi calling and HD voice, the SIM. Plain calls with nothing to say aren't listed. Above them, one
 * quality line for the number once it has two connected calls.
 */
@Composable
fun CallFactsHistorySection(vm: AppViewModel, number: String) {
    val version by vm.c.callQuality.version.collectAsStateWithLifecycle()
    val facts by produceState(emptyList<CallQualityFacts>(), number, version) {
        value = withContext(Dispatchers.IO) { runCatching { vm.c.callQuality.forNumber(number) }.getOrDefault(emptyList()) }
    }
    val shown = facts.filter { it.subject != null || it.drop != null || it.wifi || it.hd }.take(MAX_SHOWN)
    // One quality line for the number ("7 calls in 60 days, 2 dropped, all on Work").
    val quality = remember(facts) { CallQualityDiary.numberLine(facts) }
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    // Without the phone-app role nothing is noted: the section says so, with the way to change it.
    if (shown.isEmpty() && quality == null && isDefault) return
    Column {
        Section(stringResource(R.string.callfacts_section_title))
        DefaultAppNote(vm, DefaultAppFeature.CALL_FACTS)
        quality?.let { q ->
            ParleyListItem(
                leadingContent = { Icon(Icons.Rounded.SignalCellularAlt, null) },
                headlineContent = { Text(qualityLine(q)) },
            )
        }
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

/** "7 calls in 60 days, 2 dropped, all on Work · 3 over Wi-Fi calling · 5 in HD voice". */
@Composable
private fun qualityLine(q: NumberQuality): String {
    val calls = pluralStringResource(R.plurals.quality_line_calls, q.rate.calls, q.rate.calls)
    val sim = q.dropSim
    val main = when {
        q.rate.drops == 0 -> stringResource(R.string.quality_line_none, calls)
        sim != null -> stringResource(R.string.quality_line_dropped_sim, calls, q.rate.drops, sim)
        else -> stringResource(R.string.quality_line_dropped, calls, q.rate.drops)
    }
    val parts = buildList {
        add(main)
        if (q.wifiCalls > 0) add(pluralStringResource(R.plurals.quality_line_wifi, q.wifiCalls, q.wifiCalls))
        if (q.hdCalls > 0) add(pluralStringResource(R.plurals.quality_line_hd, q.hdCalls, q.hdCalls))
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
