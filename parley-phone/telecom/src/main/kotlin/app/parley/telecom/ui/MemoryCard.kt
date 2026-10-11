package app.parley.telecom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.parley.common.calls.CatchUp
import app.parley.common.calls.CatchUpChoice
import app.parley.common.circle.Promises
import app.parley.telecom.CallUi
import app.parley.telecom.R
import app.parley.ui.ParleyShapes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "Anything to remember?" on the call-ended screen after a call with a contact (opt-in): a note, chips that
 * start a line ("Their news: ", a promise "[ ] ") and a follow-up reminder in a week or a month. Saved as a call
 * note, so it shows on the contact's timeline; promises can be ticked off there later. For a Circle member the
 * follow-up is their next catch-up ([NextCatchUpRow]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryCard(call: CallUi, onChoice: (PostCallChoice) -> Unit) {
    val number = call.number ?: return
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var followUp by remember { mutableStateOf<Int?>(null) }
    var catchUp by remember { mutableStateOf(CatchUpChoice.USUAL) }
    var catchUpDate by remember { mutableStateOf<LocalDate?>(null) }
    val news = stringResource(R.string.memory_news_prefix)
    fun startLine(prefix: String) {
        val t = value.text
        val out = if (t.isEmpty() || t.endsWith("\n")) t + prefix else t + "\n" + prefix
        value = TextFieldValue(out, TextRange(out.length))
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = ParleyShapes.sheet,
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            // Any touch keeps the screen up, like the post-call card.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        onChoice(PostCallChoice.Touched)
                    }
                }
            },
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(stringResource(R.string.memory_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value, { value = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2, maxLines = 5,
                placeholder = { Text(stringResource(R.string.memory_placeholder)) },
            )
            Text(
                stringResource(R.string.memory_promise_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip({ startLine(news) }, label = { Text(stringResource(R.string.memory_chip_news)) })
                AssistChip(
                    {
                        val (text, cursor) = Promises.insertBox(value.text, value.text.length)
                        value = TextFieldValue(text, TextRange(cursor))
                    },
                    label = { Text(stringResource(R.string.memory_chip_promised)) },
                    leadingIcon = { Icon(Icons.Rounded.CheckBoxOutlineBlank, null) },
                )
                // A Circle member gets "Next catch-up" instead: the same reminder, kept as their next catch-up.
                if (!call.inCircle) {
                    FilterChip(followUp == 7, { followUp = if (followUp == 7) null else 7 }, label = { Text(stringResource(R.string.memory_chip_week)) })
                    FilterChip(followUp == 30, { followUp = if (followUp == 30) null else 30 }, label = { Text(stringResource(R.string.memory_chip_month)) })
                }
            }
            if (call.inCircle) NextCatchUpRow(catchUp, catchUpDate) { choice, date -> catchUp = choice; catchUpDate = date }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ onChoice(PostCallChoice.Done) }) { Text(stringResource(R.string.memory_skip)) }
                val note = value.text.trim().takeIf { it.isNotEmpty() && it != news.trim() && it != Promises.OPEN.trim() }
                val catchUpAt = CatchUp.at(catchUp, catchUpDate, System.currentTimeMillis(), ZoneId.systemDefault())
                TextButton(
                    { onChoice(PostCallChoice.Remember(number, call.connectTimeMillis, note, followUp.takeIf { !call.inCircle }, catchUpAt)) },
                    enabled = note != null || followUp != null || catchUpAt != null,
                ) { Text(stringResource(R.string.tc_save)) }
            }
        }
    }
}

/**
 * "Next catch-up: Usual rhythm · In a week · On a date" for a Circle member. "On a date" opens a calendar (tomorrow or
 * later) and then names the day picked. Usual rhythm sets nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NextCatchUpRow(choice: CatchUpChoice, date: LocalDate?, onChange: (CatchUpChoice, LocalDate?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Text(
        stringResource(R.string.memory_catch_up_title), style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            choice == CatchUpChoice.USUAL, { onChange(CatchUpChoice.USUAL, null) },
            label = { Text(stringResource(R.string.memory_catch_up_usual)) },
        )
        FilterChip(
            choice == CatchUpChoice.IN_A_WEEK, { onChange(CatchUpChoice.IN_A_WEEK, null) },
            label = { Text(stringResource(R.string.memory_catch_up_week)) },
        )
        val dated = date?.takeIf { choice == CatchUpChoice.ON_A_DATE }
        FilterChip(
            dated != null, { picking = true },
            label = {
                Text(
                    dated?.let { stringResource(R.string.memory_catch_up_on, it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))) }
                        ?: stringResource(R.string.memory_catch_up_date),
                )
            },
        )
    }
    if (picking) {
        CatchUpDatePicker(date, onDismiss = { picking = false }) { picked ->
            picking = false
            onChange(CatchUpChoice.ON_A_DATE, picked)
        }
    }
}

/** A day after today. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatchUpDatePicker(day: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val tomorrow = LocalDate.now().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = day?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= tomorrow
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss() },
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(R.string.memory_catch_up_set)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(app.parley.ui.R.string.ui_cancel)) } },
    ) { DatePicker(state) }
}
