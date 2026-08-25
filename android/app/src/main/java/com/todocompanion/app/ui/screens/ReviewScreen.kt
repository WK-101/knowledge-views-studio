package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A guided GTD-style weekly review (an MLO staple). Surfaces the piles that need
 * attention — unfiled inbox, overdue, gone stale, never scheduled — with one-tap
 * actions, then celebrates what got done. All computed on-device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    val allTasks by vm.tasks.collectAsState()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val now = System.currentTimeMillis()
    val staleCutoff = now - 14L * 24 * 3600 * 1000

    fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    fun todayAt9() = today.atStartOfDay(zone).toInstant().toEpochMilli()

    val active = allTasks.filter { !it.trashed && !it.completed && !it.abandoned }
    val startOfTomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    fun tomorrowAt9() = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val dueForReview = active.filter { it.reviewEveryDays != null && (it.reviewedAt ?: it.createdAt) + it.reviewEveryDays!! * 86_400_000L <= now }
    val dueToday = active.filter { it.dueDate != null && it.dueDate in startOfToday until startOfTomorrow }.sortedBy { it.dueDate }
    val inbox = active.filter { it.listId == ListEntity.INBOX_ID && it.parentId == null }
    val overdue = active.filter { it.dueDate != null && it.dueDate < startOfToday }.sortedBy { it.dueDate }
    val stale = active.filter { it.updatedAt < staleCutoff && it.dueDate == null && it.listId != ListEntity.INBOX_ID }
    val unscheduled = active.filter { it.dueDate == null && !it.isNote && it.listId != ListEntity.INBOX_ID && it.parentId == null && it.updatedAt >= staleCutoff }

    val doneThisWeek = allTasks.count { it.completed && it.completedAt != null && !dayOf(it.completedAt!!).isBefore(today.minusDays(6)) }
    val openCount = active.count { !it.isNote }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Weekly review") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            AppCard {
                Text("Take five minutes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("$doneThisWeek done in the last 7 days · $openCount still open", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))

            // Procrastination breaker (E2): tasks pushed 3+ times — decide instead of deferring again.
            val moveCounts by androidx.compose.runtime.produceState(initialValue = emptyMap<String, Int>()) { value = vm.rescheduleCounts() }
            val slipping = active.filter { (moveCounts[it.id] ?: 0) >= 3 }.sortedByDescending { moveCounts[it.id] ?: 0 }
            ReviewSection("Keeps slipping — break it down or drop it", slipping, "Nothing's been pushed over and over.", onOpenTask,
                action = "Won't do" to { t: TaskEntity -> vm.setAbandoned(t, true) })
            // Two-minute rule (E4): if it takes ≤2 minutes, do it now rather than tracking it.
            val twoMin = active.filter { !it.isNote && (it.estimateMin ?: it.estimateMax)?.let { e -> e in 1..2 } == true }
            ReviewSection("Two-minute rule — just do these now", twoMin, "No two-minute quick wins waiting.", onOpenTask,
                action = "Done" to { t: TaskEntity -> vm.toggleComplete(t) })

            ReviewSection("Plan today — due today", dueToday, "Nothing due today.", onOpenTask,
                action = "Tomorrow" to { t: TaskEntity -> vm.save(t.copy(dueDate = tomorrowAt9())) })
            ReviewSection("Due for review", dueForReview, "Nothing is due for a review.", onOpenTask,
                action = "Reviewed" to { t: TaskEntity -> vm.markReviewed(t) })
            ReviewSection("Process your inbox", inbox, "Nothing waiting in the inbox.", onOpenTask)
            ReviewSection("Overdue — reschedule or drop", overdue, "No overdue tasks. Nice.", onOpenTask,
                action = "Today" to { t: TaskEntity -> vm.save(t.copy(dueDate = todayAt9())) })
            ReviewSection("Gone stale (2+ weeks untouched)", stale, "Nothing has gone stale.", onOpenTask)
            ReviewSection("Never scheduled", unscheduled, "Everything actionable has a date.", onOpenTask,
                action = "Today" to { t: TaskEntity -> vm.save(t.copy(dueDate = todayAt9())) })

            Spacer(Modifier.height(8.dp))
            Text("Review runs entirely on-device from your data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun ReviewSection(
    title: String,
    tasks: List<TaskEntity>,
    emptyText: String,
    onOpenTask: (String) -> Unit,
    action: Pair<String, (TaskEntity) -> Unit>? = null,
) {
    var open by remember(title) { mutableStateOf(tasks.isNotEmpty()) }
    AppCard {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            if (tasks.isEmpty()) {
                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            } else {
                Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.size(6.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (tasks.isNotEmpty()) Text(tasks.size.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        if (tasks.isEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp))
        }
        AnimatedVisibility(visible = open && tasks.isNotEmpty()) {
            Column {
                Spacer(Modifier.height(4.dp))
                tasks.take(12).forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenTask(t.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.title.ifBlank { "(untitled)" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (action != null) TextButton(onClick = { action.second(t) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                            Text(action.first, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (tasks.size > 12) Text("+${tasks.size - 12} more", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp, start = 4.dp))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}
