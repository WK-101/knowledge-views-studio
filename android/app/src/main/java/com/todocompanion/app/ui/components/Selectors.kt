package com.todocompanion.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * R61 — the ONE single-choice chip row for the whole app. Every "pick one" selector (a range, a filter, a
 * mode, a time-of-day bucket, a preset) used to be hand-laid five different ways — some wrapping (FlowRow),
 * some scrolling, some a plain Row that clipped chips off narrow screens. This is the single idiom:
 *   • [wrap] = true (default) flows onto as many lines as it needs — the calm default for editors and sheets;
 *   • [wrap] = false keeps everything on one line that scrolls — for tight top-bars where wrapping looks busy.
 * Either way a chip never clips, and every option row in the app reads and behaves the same.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> OptionChips(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    wrap: Boolean = true,
    spacing: Int = 8,
    label: (T) -> String,
) {
    if (wrap) {
        FlowRow(
            modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.dp),
        ) {
            options.forEach { opt ->
                FilterChip(selected = opt == selected, onClick = { onSelect(opt) }, label = { Text(label(opt), maxLines = 1) })
            }
        }
    } else {
        Row(
            modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.dp),
        ) {
            options.forEach { opt ->
                FilterChip(selected = opt == selected, onClick = { onSelect(opt) }, label = { Text(label(opt), maxLines = 1) })
            }
        }
    }
}

/**
 * R61 — the ONE clock-hour stepper. A −/+ pair around an "HH:00" value, used everywhere the app adjusts a
 * whole-hour time (working hours, protected windows, quiet hours, morning brief, reflection time). The caller
 * supplies clamping/wrap-around and persistence inside [onChange]; this only renders and emits hour ± 1.
 */
@Composable
fun HourStepper(hour: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange(hour - 1) }, contentPadding = PaddingValues(6.dp)) { Text("−") }
        Text(
            "%02d:00".format(hour),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 52.dp),
        )
        TextButton(onClick = { onChange(hour + 1) }, contentPadding = PaddingValues(6.dp)) { Text("+") }
    }
}
