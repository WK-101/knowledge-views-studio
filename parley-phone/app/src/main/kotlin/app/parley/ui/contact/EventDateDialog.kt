package app.parley.ui.contact

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.parley.common.EventDate
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Date entry that supports dates without a year (birthdays people only know the day of).
 * Fields follow the locale's usual order (day-month or month-day).
 */
@Composable
fun EventDateDialog(initial: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val parsed = EventDate.parse(initial)
    var month by remember { mutableIntStateOf(parsed?.month ?: LocalDate.now().monthValue) }
    var day by remember { mutableIntStateOf(parsed?.day ?: LocalDate.now().dayOfMonth) }
    var withYear by remember { mutableStateOf(parsed?.year != null || parsed == null) }
    var year by remember { mutableStateOf((parsed?.year ?: (LocalDate.now().year - 30)).toString()) }
    val maxDay = Month.of(month).maxLength()
    if (day > maxDay) day = maxDay
    val yearValue = year.toIntOrNull()?.takeIf { it in 1800..LocalDate.now().year + 1 }
    val valid = !withYear || yearValue != null && (month != 2 || day != 29 || java.time.Year.isLeap(yearValue.toLong()))
    val dayFirst = remember {
        val pattern = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, Locale.getDefault()).let { (it as? java.text.SimpleDateFormat)?.toPattern().orEmpty() }
        pattern.indexOf('d') in 0 until pattern.indexOf('M').let { if (it < 0) Int.MAX_VALUE else it }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose date") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val dayField: @Composable (Modifier) -> Unit = { m ->
                        Picker("Day", day.toString(), (1..maxDay).map { it.toString() }, m) { day = it + 1 }
                    }
                    val monthField: @Composable (Modifier) -> Unit = { m ->
                        Picker("Month", Month.of(month).getDisplayName(TextStyle.FULL, Locale.getDefault()), Month.entries.map { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) }, m) { month = it + 1 }
                    }
                    if (dayFirst) {
                        dayField(Modifier.weight(0.35f)); monthField(Modifier.weight(0.65f))
                    } else {
                        monthField(Modifier.weight(0.65f)); dayField(Modifier.weight(0.35f))
                    }
                }
                ListItem(
                    headlineContent = { Text("Include year") },
                    trailingContent = { Switch(withYear, { withYear = it }) },
                    modifier = Modifier.clickable { withYear = !withYear },
                )
                if (withYear) {
                    OutlinedTextField(
                        year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("Year") }, singleLine = true,
                        isError = yearValue == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton({ onPick(EventDate(if (withYear) yearValue else null, month, day).format()) }, enabled = valid) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
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
