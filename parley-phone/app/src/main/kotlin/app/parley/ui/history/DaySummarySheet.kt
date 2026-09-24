package app.parley.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.history.CallTotals
import app.parley.common.history.Period
import java.time.Instant
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.util.Locale

/** H3: tap a Recents day header → that day's made / received / missed / rejected calls and talk time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaySummarySheet(vm: AppViewModel, dayMillis: Long, title: String, onDismiss: () -> Unit) {
    val index by vm.c.history.index.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            val idx = index
            if (idx == null) {
                CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
                return@Column
            }
            val date = remember(dayMillis, idx) { Instant.ofEpochMilli(dayMillis).atZone(idx.zone).toLocalDate() }
            val t = remember(date, idx) { idx.day(date) }
            val people = remember(date, idx) { idx.perPerson(Period.day(date, idx.zone)).size }
            Text(
                if (t.isEmpty) stringResource(R.string.hist_no_calls)
                else pluralStringResource(R.plurals.hist_day_calls_with, t.total, t.total, pluralStringResource(R.plurals.hist_people_count, people, people)),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp),
            )
            StatGrid(t)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.hist_talk_time, HistoryFormat.talk(t.talkSec)), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.hist_talk_split, HistoryFormat.talk(t.talkOutSec), HistoryFormat.talk(t.talkInSec)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Made / received / missed / rejected (and blocked when there are any) as tiles. */
@Composable
fun StatGrid(t: CallTotals) {
    val tiles = buildList {
        add(stringResource(R.string.hist_stat_made) to t.outgoing)
        add(stringResource(R.string.hist_stat_received) to t.incoming)
        add(stringResource(R.string.hist_stat_missed) to t.missed)
        add(stringResource(R.string.hist_stat_rejected) to t.rejected)
        if (t.blocked > 0) add(stringResource(R.string.hist_stat_blocked) to t.blocked)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEach { (label, n) ->
            Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(n.toString(), style = MaterialTheme.typography.headlineSmall)
                    Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

object HistoryFormat {
    /** "0 min", "45 s", "12 min", "2 h 5 min", in the current locale's short unit style. */
    fun talk(sec: Long): String {
        val f = MeasureFormat.getInstance(Locale.getDefault(), MeasureFormat.FormatWidth.SHORT)
        return when {
            sec <= 0 -> f.format(Measure(0, MeasureUnit.MINUTE))
            sec < 60 -> f.format(Measure(sec, MeasureUnit.SECOND))
            sec < 3600 -> f.format(Measure(sec / 60, MeasureUnit.MINUTE))
            else -> f.formatMeasures(Measure(sec / 3600, MeasureUnit.HOUR), Measure((sec % 3600) / 60, MeasureUnit.MINUTE))
        }
    }
}
