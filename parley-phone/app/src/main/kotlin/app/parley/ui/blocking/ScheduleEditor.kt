package app.parley.ui.blocking

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.blocking.BlockingText
import app.parley.common.Schedule
import app.parley.ui.settings.bidiLtr
import app.parley.ui.settings.bidiLtrIfNumber

/** Day letters and names from the app's locale, Monday first (the order of [Schedule.days] bits). */
@Composable
private fun dayNames(style: java.time.format.TextStyle): List<String> {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales.get(0) ?: java.util.Locale.getDefault()
    return java.time.DayOfWeek.entries.map { it.getDisplayName(style, locale) }
}

/**
 * "Active: always / on a schedule" (B17). [value] null = always. Presets cover the common cases;
 * a window whose end is earlier than its start runs past midnight.
 */
@Composable
fun ScheduleField(value: Schedule?, onChange: (Schedule?) -> Unit, alwaysLabel: String = stringResource(R.string.blk_always)) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(value == null, { onChange(null) }, label = { Text(alwaysLabel) })
            val nights = Schedule(Schedule.ALL_DAYS, 22 * 60, 7 * 60)
            val work = Schedule(Schedule.WEEKDAYS, 9 * 60, 17 * 60)
            val weekend = Schedule(Schedule.WEEKEND, 0, 0)
            FilterChip(value == nights, { onChange(nights) }, label = { Text(stringResource(R.string.blk_sched_nights)) })
            FilterChip(value == work, { onChange(work) }, label = { Text(stringResource(R.string.blk_sched_work)) })
            FilterChip(value == weekend, { onChange(weekend) }, label = { Text(stringResource(R.string.blk_sched_weekends)) })
            FilterChip(value != null && value != nights && value != work && value != weekend, { onChange(value ?: Schedule(Schedule.ALL_DAYS, 20 * 60, 8 * 60)) }, label = { Text(stringResource(R.string.blk_sched_custom)) })
        }
        if (value != null) ScheduleDetails(value, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDetails(value: Schedule, onChange: (Schedule) -> Unit) {
    var editing by remember { mutableStateOf<Int?>(null) } // 0 = start, 1 = end
    val letters = dayNames(java.time.format.TextStyle.NARROW)
    val names = dayNames(java.time.format.TextStyle.FULL)
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        letters.forEachIndexed { i, d ->
            val on = value.days and (1 shl i) != 0
            val cd = stringResource(if (on) R.string.blk_day_on else R.string.blk_day_off, names[i])
            FilterChip(
                on, { onChange(value.copy(days = value.days xor (1 shl i))) }, label = { Text(d) },
                modifier = Modifier.semantics { contentDescription = cd },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ editing = 0 }) { Text(stringResource(R.string.blk_sched_from, Schedule.hm(value.startMinute))) }
        OutlinedButton({ editing = 1 }) { Text(stringResource(R.string.blk_sched_to, Schedule.hm(value.endMinute))) }
    }
    Text(
        BlockingText.schedule(context, value) + if (value.endMinute < value.startMinute) " " + stringResource(R.string.blk_sched_next_morning) else "",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    editing?.let { which ->
        val initial = if (which == 0) value.startMinute else value.endMinute
        val state = rememberTimePickerState(initial / 60, initial % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(if (which == 0) R.string.blk_sched_starts_at else R.string.blk_sched_ends_at)) },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton({
                    val m = state.hour * 60 + state.minute
                    onChange(if (which == 0) value.copy(startMinute = m) else value.copy(endMinute = m))
                    editing = null
                }) { Text(stringResource(R.string.set_ok)) }
            },
            dismissButton = { TextButton({ editing = null }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }
}
