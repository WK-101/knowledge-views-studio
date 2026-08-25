package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import java.time.LocalDate
import java.time.ZoneId

/** TickTick-style "Plan your day": step through overdue + today's tasks one at a time, deciding
 *  each — reschedule, complete, skip, or drop — until the day is planned. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlanYourDayScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val tasks by vm.tasks.collectAsState()
    val lists by vm.lists.collectAsState()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val endToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    fun at9(d: LocalDate) = d.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli()

    var skipped by remember { mutableStateOf(setOf<String>()) }
    val queue = tasks.filter { !it.completed && !it.trashed && !it.abandoned && it.dueDate != null && it.dueDate!! < endToday }
        .sortedBy { it.dueDate }
    val remaining = queue.filter { it.id !in skipped }
    val current = remaining.firstOrNull()
    val listName = current?.let { c -> lists.firstOrNull { it.id == c.listId }?.name }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Plan your day") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.size(12.dp))
                        Text("Your day is planned", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (queue.isEmpty()) "Nothing overdue or due today." else "Every task has a plan.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(20.dp))
                        Button(onClick = onBack) { Text("Done") }
                    }
                }
                return@Column
            }

            Text("${remaining.size} to plan", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(16.dp))
            AppCard {
                Text(current.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                current.dueDate?.let {
                    val overdue = it < today.atStartOfDay(zone).toInstant().toEpochMilli()
                    Text(if (overdue) "Overdue" else "Due today",
                        style = MaterialTheme.typography.labelMedium, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (listName != null && listName != "Inbox") Text(listName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (current.note.isNotBlank()) {
                    Spacer(Modifier.size(6.dp))
                    Text(current.note.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                }
                Spacer(Modifier.size(6.dp))
                TextButton(onClick = { onOpenTask(current.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Open task") }
            }

            Spacer(Modifier.size(20.dp))
            Text("RESCHEDULE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.save(current.copy(dueDate = at9(today))) }) { Text("Today") }
                FilledTonalButton(onClick = { vm.save(current.copy(dueDate = at9(today.plusDays(1)))) }) { Text("Tomorrow") }
                FilledTonalButton(onClick = { vm.save(current.copy(dueDate = at9(today.plusDays(7)))) }) { Text("Next week") }
                FilledTonalButton(onClick = { vm.save(current.copy(dueDate = null)) }) { Text("Someday") }
            }

            Spacer(Modifier.size(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.toggleComplete(current) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Complete")
                }
                OutlinedButton(onClick = { skipped = skipped + current.id }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.SkipNext, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Skip")
                }
            }
            Spacer(Modifier.size(10.dp))
            TextButton(onClick = { vm.setAbandoned(current, true) }) {
                Icon(Icons.Filled.Cancel, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Won't do")
            }
        }
    }
}
