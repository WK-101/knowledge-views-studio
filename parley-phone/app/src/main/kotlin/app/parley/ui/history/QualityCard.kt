package app.parley.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.calls.CallNetwork
import app.parley.common.calls.CallQualityDiary
import app.parley.common.calls.DayPart
import app.parley.common.calls.DiaryCall
import app.parley.common.calls.DropRate
import app.parley.common.calls.QualityAdvice
import app.parley.common.calls.QualityPattern
import app.parley.common.calls.QualityReport
import app.parley.common.history.CallLogIndex
import app.parley.data.vault.PrivateCall
import app.parley.ui.Destination
import app.parley.ui.ParleyListItem
import app.parley.ui.PersonRow
import app.parley.ui.Routes
import app.parley.ui.Spacing
import app.parley.ui.common.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Who a diary row was with, as the card names them: a contact, a private contact (outside discreet mode) or a number. */
private data class DiaryPerson(val title: String, val number: String, val contactNav: Long?)

private data class QualityData(val report: QualityReport, val people: Map<String, DiaryPerson>)

/**
 * The Call insights "Quality" card: drop rates (overall, per SIM, Wi-Fi calling against the mobile network),
 * patterns worth acting on ("Calls with Mum often drop on SIM 2 in the evening · Try Wi-Fi calling") and the recent
 * dropped calls with Call again (same number, same SIM). From the quality facts kept per call for 60 days
 * ([app.parley.data.calls.CallQualityStore]); those are keyed by a fingerprint of the line, so people are matched by
 * the numbers in the call history (and private contacts' calls, never in discreet mode). Shows nothing until there
 * are a few connected calls.
 */
@Composable
fun QualityCard(vm: AppViewModel, idx: CallLogIndex, open: (Destination) -> Unit) {
    val version by vm.c.callQuality.version.collectAsStateWithLifecycle()
    val privacy by vm.privacy.collectAsStateWithLifecycle()
    val hideVault = privacy.privateHidden
    val priv by vm.c.vault.privateCalls.collectAsStateWithLifecycle()
    val data by produceState<QualityData?>(null, idx, version, hideVault, priv) {
        value = withContext(Dispatchers.IO) { runCatching { qualityData(vm, idx, if (hideVault) emptyList() else priv) }.getOrNull() }
    }
    val d = data ?: return
    val r = d.report
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(top = Spacing.s), color = MaterialTheme.colorScheme.surfaceContainerHigh)
        Text(
            stringResource(R.string.quality_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = Spacing.l, top = Spacing.m, end = Spacing.l).semantics { heading() },
        )
        Text(
            stringResource(R.string.quality_explainer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
        )
        RateRows(r)
        if (r.patterns.isNotEmpty()) SubHeader(stringResource(R.string.quality_patterns))
        r.patterns.forEach { p ->
            ParleyListItem(
                leadingContent = { Icon(Icons.Rounded.Lightbulb, null, tint = MaterialTheme.colorScheme.tertiary) },
                headlineContent = { Text(patternText(p, d.people[p.who]?.title)) },
                supportingContent = { Text(droppedOf(p.rate) + stringResource(R.string.main_separator) + adviceText(p)) },
            )
        }
        if (r.recentDrops.isNotEmpty()) SubHeader(stringResource(R.string.quality_recent))
        r.recentDrops.forEach { call -> RecentDrop(vm, call, d.people[call.who] ?: return@forEach, open) }
    }
}

/** Overall, per SIM and per network. */
@Composable
private fun RateRows(r: QualityReport) {
    val none = r.overall.drops == 0
    ParleyListItem(
        leadingContent = { Icon(Icons.Rounded.SignalCellularAlt, null) },
        headlineContent = { Text(if (none) stringResource(R.string.quality_none_dropped) else droppedOf(r.overall)) },
        supportingContent = { Text(if (none) droppedOf(r.overall) else stringResource(R.string.quality_percent, r.overall.percent)) },
    )
    r.bySim.entries.sortedByDescending { it.value.calls }.forEach { (sim, rate) ->
        ParleyListItem(
            leadingContent = { Icon(Icons.Rounded.SimCard, null) },
            headlineContent = { Text(sim) },
            supportingContent = { Text(droppedOf(rate)) },
        )
    }
    CallNetwork.entries.forEach { n ->
        val rate = r.byNetwork[n] ?: return@forEach
        if (rate.calls == 0) return@forEach
        ParleyListItem(
            leadingContent = { Icon(if (n == CallNetwork.WIFI) Icons.Rounded.Wifi else Icons.Rounded.NetworkCheck, null) },
            headlineContent = { Text(stringResource(if (n == CallNetwork.WIFI) R.string.callfacts_wifi else R.string.quality_mobile)) },
            supportingContent = { Text(droppedOf(rate)) },
        )
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.l, top = Spacing.m, bottom = Spacing.xs).semantics { heading() },
    )
}

/** A recent drop: who, when and why, and Call again on the same SIM. */
@Composable
private fun RecentDrop(vm: AppViewModel, call: DiaryCall, who: DiaryPerson, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val sims by vm.sims.collectAsStateWithLifecycle()
    val f = call.facts
    val details = listOfNotNull(Format.shortWhen(context, f.startedAt), Format.duration(f.durationSec).ifBlank { null }, f.sim)
        .joinToString(stringResource(R.string.main_separator))
    val again = stringResource(R.string.quality_call_again_desc, who.title)
    PersonRow(
        who.title, null,
        modifier = Modifier.clickable { open(who.contactNav?.let { Routes.contact(it) } ?: Routes.history(who.number)) },
        supportingContent = { Text(details) },
        trailingContent = {
            FilledTonalButton(
                onClick = { vm.requestCall(who.number, who.title, simId = sims.firstOrNull { it.label == f.sim }?.id) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = again },
            ) {
                Icon(Icons.Rounded.Call, null)
                Text(stringResource(R.string.quality_call_again), Modifier.padding(start = Spacing.s))
            }
        },
    )
}

/** "3 of 4 calls dropped". */
@Composable
internal fun droppedOf(rate: DropRate): String = pluralStringResource(R.plurals.quality_dropped_of, rate.calls, rate.drops, rate.calls)

/** "Calls with Mum often drop on SIM 2 in the evening". */
@Composable
private fun patternText(p: QualityPattern, name: String?): String {
    val subject = name?.let { stringResource(R.string.quality_subject_with, it) } ?: stringResource(R.string.quality_subject_all)
    val parts = buildList {
        p.sim?.let { add(stringResource(R.string.quality_on_sim, it)) }
        when (p.network) {
            CallNetwork.WIFI -> add(stringResource(R.string.quality_over_wifi))
            CallNetwork.MOBILE -> add(stringResource(R.string.quality_on_mobile))
            null -> Unit
        }
        when (p.dayPart) {
            DayPart.MORNING -> add(stringResource(R.string.quality_morning))
            DayPart.AFTERNOON -> add(stringResource(R.string.quality_afternoon))
            DayPart.EVENING -> add(stringResource(R.string.quality_evening))
            DayPart.NIGHT -> add(stringResource(R.string.quality_night))
            null -> Unit
        }
    }
    return if (parts.isEmpty()) stringResource(R.string.quality_pattern_plain, subject)
    else stringResource(R.string.quality_pattern, subject, parts.joinToString(" "))
}

@Composable
private fun adviceText(p: QualityPattern): String = when (p.advice) {
    QualityAdvice.TRY_WIFI_CALLING -> stringResource(R.string.quality_advice_wifi)
    QualityAdvice.TRY_MOBILE_NETWORK -> stringResource(R.string.quality_advice_mobile)
    QualityAdvice.TRY_OTHER_SIM -> stringResource(R.string.quality_advice_sim, p.otherSim.orEmpty())
}

/**
 * The diary's calls with who they were with. The quality store keys rows by a fingerprint of the line, so the numbers
 * in the call history (and the private calls passed in) are fingerprinted the same way to find them; one person's
 * numbers share their key. Rows nobody matches still count in the rates, unnamed and without Call again.
 */
private fun qualityData(vm: AppViewModel, idx: CallLogIndex, privateCalls: List<PrivateCall>): QualityData? {
    val store = vm.c.callQuality
    val now = System.currentTimeMillis()
    val since = now - WINDOW_DAYS * CallLogIndex.DAY
    val people = HashMap<String, DiaryPerson>()
    val byLine = HashMap<String, String>()
    idx.calls.asSequence()
        .filter { it.date >= since && !it.call.presentationHidden && it.call.number.isNotBlank() }
        .distinctBy { it.call.number }
        .forEach { c ->
            val line = store.keyOf(c.call.number) ?: return@forEach
            val p = idx.people[c.personKey]
            val who = "p:" + c.personKey
            byLine.putIfAbsent(line, who)
            people.putIfAbsent(who, DiaryPerson(p?.name ?: Format.number(c.call.number, vm.countryIso), c.call.number, p?.contactId))
        }
    // A private contact's own calls (kept apart from the phone's history) name them as the page does.
    privateCalls.asSequence().filter { it.date >= since && it.number.isNotBlank() }.distinctBy { it.number }.forEach { pc ->
        val line = store.keyOf(pc.number) ?: return@forEach
        val who = "v:" + pc.vaultId
        byLine[line] = who
        people[who] = DiaryPerson(pc.name, pc.number, -pc.vaultId)
    }
    val calls = store.all().map { (line, facts) -> DiaryCall(byLine[line], facts) }
    val report = CallQualityDiary.report(calls, ZoneId.systemDefault(), now, WINDOW_DAYS) ?: return null
    return QualityData(report, people)
}

private const val WINDOW_DAYS = 60
