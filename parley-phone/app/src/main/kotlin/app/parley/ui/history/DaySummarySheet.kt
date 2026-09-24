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
                if (t.isEmpty) "No calls" else "${t.total} calls with $people ${if (people == 1) "person" else "people"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp),
            )
            StatGrid(t)
            Spacer(Modifier.height(12.dp))
            Text("Talk time ${HistoryFormat.talk(t.talkSec)}", style = MaterialTheme.typography.titleMedium)
            Text(
                "${HistoryFormat.talk(t.talkOutSec)} on calls you made · ${HistoryFormat.talk(t.talkInSec)} on calls you received",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Made / received / missed / rejected (and blocked when there are any) as tiles. */
@Composable
fun StatGrid(t: CallTotals) {
    val tiles = buildList {
        add("Made" to t.outgoing)
        add("Received" to t.incoming)
        add("Missed" to t.missed)
        add("Rejected" to t.rejected)
        if (t.blocked > 0) add("Blocked" to t.blocked)
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
    /** "0 min", "45 s", "12 min", "2 h 05 min". */
    fun talk(sec: Long): String = when {
        sec <= 0 -> "0 min"
        sec < 60 -> "$sec s"
        sec < 3600 -> "${sec / 60} min"
        else -> "${sec / 3600} h %02d min".format((sec % 3600) / 60)
    }
}
