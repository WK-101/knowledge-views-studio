package com.todocompanion.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Two-step date → time picker. Returns the chosen instant as epoch millis (local zone).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerDialog(
    initial: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var pickedDateUtc by remember { mutableStateOf<Long?>(null) }
    val initialDt = initial?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }

    if (pickedDateUtc == null) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = { pickedDateUtc = dateState.selectedDateMillis }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) { DatePicker(state = dateState) }
    } else {
        val timeState = rememberTimePickerState(
            initialHour = initialDt?.hour ?: 9,
            initialMinute = initialDt?.minute ?: 0,
        )
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                Box(Modifier.padding(16.dp)) {
                    androidx.compose.foundation.layout.Column {
                        TimePicker(state = timeState)
                        androidx.compose.foundation.layout.Row(
                            Modifier.padding(top = 8.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                        ) {
                            TextButton(onClick = onDismiss) { Text("Cancel") }
                            TextButton(onClick = {
                                val dateUtc = pickedDateUtc ?: System.currentTimeMillis()
                                val localDate = Instant.ofEpochMilli(dateUtc).atZone(ZoneOffset.UTC).toLocalDate()
                                val dt = LocalDateTime.of(localDate, LocalTime.of(timeState.hour, timeState.minute))
                                val millis = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                onConfirm(millis)
                            }) { Text("OK") }
                        }
                    }
                }
            }
        }
    }
}
