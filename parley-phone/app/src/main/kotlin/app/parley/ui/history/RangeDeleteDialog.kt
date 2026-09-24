package app.parley.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.parley.AppViewModel
import app.parley.common.history.DeleteRange
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R

/**
 * K10: delete one number's calls from a point in time until now. Deleted calls are kept sealed for 30 days;
 * [onDeleted] gets the undo batch and the count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RangeDeleteDialog(vm: AppViewModel, number: String, onDeleted: (batchId: Long, count: Int) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    var range by remember { mutableStateOf(DeleteRange.ALL) }
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    var picking by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    // Matching every call's number is real work: done off the main thread, counts appear when ready.
    val all by androidx.compose.runtime.produceState<List<app.parley.common.CallEntry>?>(null, number) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { vm.c.history.callsFor(number) }
    }
    fun count(r: DeleteRange): Int? {
        if (r == DeleteRange.SINCE_DATE && picked == null) return null
        val since = r.since(now, zone, picked)
        return all?.count { it.date >= since }
    }
    val selected = count(range)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hist_range_title)) },
        text = {
            Column {
                DeleteRange.entries.forEach { r ->
                    val n = count(r)
                    val label = if (r == DeleteRange.SINCE_DATE && picked != null) {
                        stringResource(R.string.hist_range_since, picked!!.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                    } else stringResource(HistoryText.deleteRange(r))
                    ListItem(
                        headlineContent = { Text(label) },
                        supportingContent = n?.let { { Text(pluralStringResource(R.plurals.hist_n_calls, it, it)) } },
                        leadingContent = { RadioButton(range == r, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            range = r
                            if (r == DeleteRange.SINCE_DATE) picking = true
                        },
                    )
                }
                Text(
                    stringResource(R.string.hist_range_undo_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val r = range
                    val p = picked
                    onDismiss()
                    scope.launch {
                        val n = count(r) ?: 0
                        val batch = vm.c.history.deleteRange(number, r, p)
                        if (batch != null) onDeleted(batch, n)
                    }
                },
                enabled = selected != null && selected > 0,
            ) { Text(if (selected != null && selected > 0) stringResource(R.string.hist_range_delete_n, selected) else stringResource(R.string.dc_delete)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    )

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = picked?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= now
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { picked = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text(stringResource(R.string.dc_ok)) }
            },
            dismissButton = { TextButton({ picking = false }) { Text(stringResource(R.string.dc_cancel)) } },
        ) { DatePicker(state) }
    }
}
