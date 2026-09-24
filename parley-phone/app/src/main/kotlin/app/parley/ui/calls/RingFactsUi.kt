package app.parley.ui.calls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.parley.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.calls.RingExplainer
import app.parley.common.calls.RingFacts
import app.parley.ui.common.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ring facts stored for [number], newest first, re-read when new ones are stored. */
@Composable
private fun rememberRingFacts(vm: AppViewModel, number: String): List<RingFacts> {
    val version by vm.c.ringFacts.version.collectAsStateWithLifecycle()
    val facts by produceState(emptyList<RingFacts>(), number, version) {
        value = withContext(Dispatchers.IO) { runCatching { vm.c.ringFacts.forNumber(number) }.getOrDefault(emptyList()) }
    }
    return facts
}

/**
 * Number history: "Why did my phone ring, or not?" (V9) for the last calls from this number: Do Not Disturb, ringer
 * mode, which ringtone, and where the call was answered. Each row opens to every fact.
 */
@Composable
fun RingFactsHistorySection(vm: AppViewModel, number: String) {
    val facts = rememberRingFacts(vm, number)
    if (facts.isEmpty()) return
    val context = LocalContext.current
    val res = LocalResources.current
    Column {
        app.parley.ui.contact.Section(stringResource(R.string.ring_section_title))
        facts.take(MAX_SHOWN).forEach { f ->
            var open by remember(f.startedAt) { mutableStateOf(false) }
            val why = RingText.whyNoRing(res, f)
            ListItem(
                modifier = Modifier.clickable(onClickLabel = stringResource(if (open) R.string.ring_hide_details else R.string.ring_show_details)) { open = !open },
                leadingContent = { Icon(if (f.audible) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff, null) },
                headlineContent = { Text(why ?: RingText.outcomeText(res, f)) },
                supportingContent = {
                    Column {
                        Text(Format.fullDate(context, f.startedAt) + if (why != null) stringResource(R.string.main_separator) + RingText.outcomeText(res, f) else "")
                        if (open) RingFactLines(f, Modifier.padding(top = 6.dp))
                    }
                },
            )
        }
    }
}

/** The facts of the call from [number] at about [time], for the blocked-log detail and "Why did this ring?". */
@Composable
fun RingFactsFor(vm: AppViewModel, number: String, time: Long, modifier: Modifier = Modifier) {
    val facts = rememberRingFacts(vm, number)
    val f = remember(facts, time) { RingExplainer.matchFor(facts, time) } ?: return
    Column(modifier) {
        Text(stringResource(R.string.ring_on_the_phone), style = MaterialTheme.typography.labelLarge)
        RingFactLines(f)
    }
}

@Composable
private fun RingFactLines(f: RingFacts, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RingText.lines(LocalResources.current, f).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private const val MAX_SHOWN = 5
