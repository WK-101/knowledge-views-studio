package app.parley.ui.cases

import android.content.Context
import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.cases.CaseTimeline
import app.parley.common.cases.QueueMemories
import app.parley.common.cases.QueueMemory
import app.parley.common.cases.QueueTime
import app.parley.ui.Spacing
import app.parley.ui.calls.remindWithUndo
import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar

/** Queue memory of [timeline] in this phone's time zone; null with fewer than three timed calls of yours. */
@Composable
internal fun rememberQueueMemory(timeline: CaseTimeline?): QueueMemory? =
    remember(timeline) { timeline?.let { QueueMemories.of(it.entries, ZoneId.systemDefault()) } }

/** "6–25 min" (or "about 12 min"), in whole minutes. */
internal fun waitRange(res: Resources, q: QueueMemory): String {
    val low = minutes(q.shortestSec)
    val high = minutes(q.longestSec)
    return if (low == high) res.getString(R.string.queue_about, low) else res.getString(R.string.queue_range, low, high)
}

/** "Your last 4 calls waited 6–25 min. Shortest before 9:30 on weekdays." as one or two sentences. */
internal fun queueLines(context: Context, res: Resources, q: QueueMemory): List<String> = listOfNotNull(
    res.getQuantityString(R.plurals.queue_memory_waited, q.calls, q.calls, waitRange(res, q)),
    q.best?.let { b ->
        val at = clock(context, b.minuteOfDay)
        res.getString(
            when {
                b.before && b.weekdays -> R.string.queue_best_before_weekdays
                b.before -> R.string.queue_best_before
                b.weekdays -> R.string.queue_best_after_weekdays
                else -> R.string.queue_best_after
            },
            at,
        )
    },
)

/**
 * The case file's queue memory: the waits and the better time, with "Remind me at 9:00" (a To call item for the
 * case's number at the next such time). Nothing under three timed calls: a small sample isn't a pattern.
 */
@Composable
internal fun QueueMemorySection(vm: AppViewModel, number: String?, q: QueueMemory, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val res = LocalResources.current
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        Text(res.getString(R.string.case_queue_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        queueLines(context, res, q).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        val best = q.best
        if (best != null && number != null) {
            val at = clock(context, QueueMemories.reminderMinute(best))
            FilledTonalButton(onClick = { remind(vm, number, best) }, modifier = Modifier.padding(top = Spacing.s)) {
                Icon(Icons.Rounded.Alarm, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(res.getString(R.string.queue_remind, at))
            }
        }
    }
}

/** The same To call reminder as Remind me to call, with its Undo. */
private fun remind(vm: AppViewModel, number: String, best: QueueTime) =
    remindWithUndo(vm, number, accountId = null, at = QueueMemories.remindAt(best, System.currentTimeMillis(), ZoneId.systemDefault()))

private fun minutes(sec: Long): Int = ((sec + 30) / 60).toInt().coerceAtLeast(1)

/** A minute of the day as the phone shows times ("9:30" or "9:30 AM"). */
private fun clock(context: Context, minuteOfDay: Int): String {
    val t = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, t.hour)
        set(Calendar.MINUTE, t.minute)
    }
    return DateFormat.getTimeFormat(context).format(cal.time)
}
