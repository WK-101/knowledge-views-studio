package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.FirstView
import com.todocompanion.app.domain.priority.PriorityEngine
import com.todocompanion.app.ui.AppViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(
    vm: AppViewModel,
    onOpenTask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by vm.settings.collectAsState()
    var view by remember(settings.firstView) { mutableStateOf(settings.firstView) }

    Column(modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            SegmentedButton(
                selected = view == FirstView.MATRIX,
                onClick = { view = FirstView.MATRIX },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Matrix") }
            SegmentedButton(
                selected = view == FirstView.CALENDAR,
                onClick = { view = FirstView.CALENDAR },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Calendar") }
        }
        Box(Modifier.weight(1f).fillMaxSize()) {
            when (view) {
                FirstView.MATRIX -> MatrixView(vm, onOpenTask)
                FirstView.CALENDAR -> CalendarView(vm, onOpenTask)
            }
        }
    }
}

@Composable
private fun MatrixView(vm: AppViewModel, onOpenTask: (String) -> Unit) {
    val tasks by vm.tasks.collectAsState()
    val active = tasks.filter { !it.completed }
    val byQuadrant = active.groupBy { PriorityEngine.quadrant(it) }
    val quadrants = listOf(
        Triple(0, "Do first", Color(0xFFE53935)),
        Triple(1, "Schedule", Color(0xFF1E88E5)),
        Triple(2, "Delegate", Color(0xFFFB8C00)),
        Triple(3, "Later", Color(0xFF757575)),
    )
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        for (rowIdx in 0..1) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (colIdx in 0..1) {
                    val q = quadrants[rowIdx * 2 + colIdx]
                    QuadrantCard(
                        title = q.second,
                        color = q.third,
                        tasks = byQuadrant[q.first].orEmpty(),
                        onOpenTask = onOpenTask,
                        modifier = Modifier.weight(1f).fillMaxSize().padding(4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuadrantCard(
    title: String,
    color: Color,
    tasks: List<TaskEntity>,
    onOpenTask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Text(
                "  $title  (${tasks.size})",
                style = MaterialTheme.typography.labelLarge,
                color = color,
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(tasks, key = { it.id }) { t ->
                Text(
                    t.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenTask(t.id) }
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CalendarView(vm: AppViewModel, onOpenTask: (String) -> Unit) {
    val tasks by vm.tasks.collectAsState()
    val zone = ZoneId.systemDefault()
    val dueByDate = remember(tasks) {
        tasks.filter { it.dueDate != null && !it.completed }
            .groupBy { Instant.ofEpochMilli(it.dueDate!!).atZone(zone).toLocalDate() }
    }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { month = month.minusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            Text(
                "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}",
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = { month = month.plusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
            }
        }
        // Weekday header (Mon..Sun)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            for (d in listOf("M", "T", "W", "T", "F", "S", "S")) {
                Text(
                    d,
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Weeks
        val first = month.atDay(1)
        val leading = (first.dayOfWeek.value + 6) % 7 // Mon=0
        val daysInMonth = month.lengthOfMonth()
        val cells = buildList<LocalDate?> {
            repeat(leading) { add(null) }
            for (day in 1..daysInMonth) add(month.atDay(day))
            while (size % 7 != 0) add(null)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        DayCell(
                            date = date,
                            selected = date == selected,
                            hasTasks = date != null && dueByDate.containsKey(date),
                            onClick = { date?.let { selected = it } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        // Agenda for selected day
        Text(
            "Due on ${selected.dayOfMonth} ${selected.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(12.dp),
        )
        val agenda = dueByDate[selected].orEmpty()
        if (agenda.isEmpty()) {
            Text(
                "No tasks due",
                Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(agenda, key = { it.id }) { t ->
                    Text(
                        t.title,
                        Modifier.fillMaxWidth().clickable { onOpenTask(t.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate?,
    selected: Boolean,
    hasTasks: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    Box(
        modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.primary
                    date == today -> MaterialTheme.colorScheme.primaryContainer
                    else -> Color.Transparent
                }
            )
            .clickable(enabled = date != null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (date != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
                if (hasTasks) {
                    Box(
                        Modifier.size(5.dp).clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}
