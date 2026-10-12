package app.parley.ui.cases

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.viewModelScope
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.cases.CaseFile
import app.parley.ui.ParleyListItem
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * "Follow up by": the day you mean to chase the case by, picked with the date picker; from that day Next up says so
 * until the case is resolved or the day cleared.
 */
@Composable
internal fun CaseFollowUpRow(vm: AppViewModel, case: CaseFile) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val day = case.followUpBy?.let(LocalDate::ofEpochDay)
    ParleyListItem(
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.case_follow_up_pick)) { picking = true },
        leadingContent = { Icon(Icons.Rounded.Event, null) },
        headlineContent = { Text(stringResource(R.string.case_follow_up)) },
        supportingContent = { Text(day?.let(::followUpDate) ?: stringResource(R.string.case_follow_up_none)) },
        trailingContent = if (day == null) {
            null
        } else {
            {
                IconButton({ vm.viewModelScope.launch { vm.cases.setFollowUp(case.id, null) } }) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.case_follow_up_clear))
                }
            }
        },
    )
    if (picking) {
        FollowUpPicker(case.followUpBy, onDismiss = { picking = false }) { picked ->
            picking = false
            vm.viewModelScope.launch { vm.cases.setFollowUp(case.id, picked) }
        }
    }
}

/** The date picker for "Follow up by"; [onPick] gets an epoch day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FollowUpPicker(day: Long?, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val initial = day?.let { LocalDate.ofEpochDay(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    val state = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({
                val picked = state.selectedDateMillis?.let { Math.floorDiv(it, DAY_MS) }
                if (picked != null) onPick(picked) else onDismiss()
            }) { Text(stringResource(R.string.dc_ok)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    ) { DatePicker(state) }
}

/** "12 Oct 2026", as the case's other dates read. */
fun followUpDate(day: LocalDate): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(day)

private const val DAY_MS = 86_400_000L
