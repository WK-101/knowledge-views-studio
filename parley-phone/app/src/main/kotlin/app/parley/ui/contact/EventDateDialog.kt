package app.parley.ui.contact

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.AltCalendar
import app.parley.common.EventDate
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalResources
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.Month
import java.time.Year
import java.time.format.TextStyle
import java.util.Locale
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import app.parley.ui.ConfirmDialog

/**
 * Date entry that supports dates without a year (birthdays people only know the day of).
 * Fields follow the locale's usual order (day-month or month-day). With [onPickCalendar] it also asks which calendar
 * the date comes round by each year ([AltCalendar]); that needs the year, since the date is stored as the Gregorian
 * day it happened.
 */
@Suppress("CyclomaticComplexMethod") // Day, month, optional year and calendar, in the locale's order.
@Composable
fun EventDateDialog(
    initial: String,
    onDismiss: () -> Unit,
    initialCalendar: String? = null,
    onPickCalendar: ((String, String?) -> Unit)? = null,
    onPick: (String) -> Unit,
) {
    val parsed = EventDate.parse(initial)
    // The stored value as it is (another app's, or a vCard's "islamic-civil"): only the user's own pick replaces it.
    var calendar by rememberSaveable { mutableStateOf(initialCalendar) }
    var calendarPicked by rememberSaveable { mutableStateOf(false) }
    var month by rememberSaveable { mutableIntStateOf(parsed?.month ?: LocalDate.now().monthValue) }
    var day by rememberSaveable { mutableIntStateOf(parsed?.day ?: LocalDate.now().dayOfMonth) }
    var withYear by rememberSaveable { mutableStateOf(parsed?.year != null || parsed == null) }
    var year by rememberSaveable { mutableStateOf((parsed?.year ?: (LocalDate.now().year - 30)).toString()) }
    val maxDay = Month.of(month).maxLength()
    if (day > maxDay) day = maxDay
    val yearValue = year.toIntOrNull()?.takeIf { it in 1800..LocalDate.now().year + 1 }
    val valid = !withYear || yearValue != null && (month != 2 || day != 29 || Year.isLeap(yearValue.toLong()))
    val dayFirst = remember {
        val pattern = DateFormat.getDateInstance(DateFormat.SHORT, Locale.getDefault()).let { (it as? SimpleDateFormat)?.toPattern().orEmpty() }
        pattern.indexOf('d') in 0 until pattern.indexOf('M').let { if (it < 0) Int.MAX_VALUE else it }
    }

    ConfirmDialog(
        title = stringResource(R.string.date_choose),
        text = null,
        confirmLabel = stringResource(R.string.main_ok),
        onConfirm = {
            val date = EventDate(if (withYear) yearValue else null, month, day).format()
            if (onPickCalendar != null) onPickCalendar(date, if (calendarPicked) calendar.takeIf { withYear } else initialCalendar) else onPick(date)
        },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.main_cancel),
        confirmEnabled = valid,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val dayField: @Composable (Modifier) -> Unit = { m ->
                        Picker(stringResource(R.string.date_day), day.toString(), (1..maxDay).map { it.toString() }, m) { day = it + 1 }
                    }
                    val monthField: @Composable (Modifier) -> Unit = { m ->
                        Picker(stringResource(R.string.date_month), Month.of(month).getDisplayName(TextStyle.FULL, Locale.getDefault()), Month.entries.map { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) }, m) { month = it + 1 }
                    }
                    if (dayFirst) {
                        dayField(Modifier.weight(0.35f)); monthField(Modifier.weight(0.65f))
                    } else {
                        monthField(Modifier.weight(0.65f)); dayField(Modifier.weight(0.35f))
                    }
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.date_include_year)) },
                    trailingContent = { Switch(withYear, onCheckedChange = null) },
                    modifier = Modifier.toggleable(withYear, role = Role.Switch, onValueChange = { withYear = it }),
                )
                if (withYear) {
                    OutlinedTextField(
                        year, { year = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.date_year)) }, singleLine = true,
                        isError = yearValue == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (onPickCalendar != null) {
                    val res = LocalResources.current
                    val choices = listOf<AltCalendar?>(null) + AltCalendar.entries
                    if (withYear) {
                        Picker(
                            stringResource(R.string.edit_calendar), calendarName(res, AltCalendar.byKey(calendar)), choices.map { calendarName(res, it) },
                            Modifier.fillMaxWidth(),
                        ) {
                            calendar = choices[it]?.key
                            calendarPicked = true
                        }
                    } else if (calendar != null) {
                        Text(stringResource(R.string.edit_calendar_needs_year), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}

@Composable
private fun Picker(label: String, value: String, options: List<String>, modifier: Modifier, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(value, {}, readOnly = true, label = { Text(label) }, singleLine = true, trailingIcon = { Icon(Icons.Rounded.ExpandMore, null) })
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(open, { open = false }, Modifier.heightIn(max = 320.dp)) {
            options.forEachIndexed { i, o -> DropdownMenuItem({ Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
}
