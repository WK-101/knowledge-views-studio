package com.todocompanion.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.DateTimePickerDialog
import com.todocompanion.app.ui.components.formatDue
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskDetailScreen(vm: AppViewModel, taskId: String, onBack: () -> Unit) {
    val loaded by vm.observeTask(taskId).collectAsState(initial = null)
    var draft by remember(taskId) { mutableStateOf<TaskEntity?>(null) }
    // Seed the editable draft once the task first loads.
    if (draft == null && loaded != null) draft = loaded

    val settings by vm.settings.collectAsState()
    val allTags by vm.tags.collectAsState()
    val allContexts by vm.contexts.collectAsState()
    val taskTagRefs by vm.taskTags.collectAsState()
    val taskCtxRefs by vm.taskContexts.collectAsState()
    val reminders by vm.reminders.collectAsState()

    var showDuePicker by remember { mutableStateOf(false) }
    var showReminderPicker by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }
    var newContext by remember { mutableStateOf("") }

    fun update(block: (TaskEntity) -> TaskEntity) {
        val d = draft ?: return
        val nd = block(d)
        draft = nd
        vm.save(nd)
    }

    val task = draft
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Task") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { update { it.copy(star = !it.star) } }) {
                        Icon(if (task?.star == true) Icons.Filled.Star else Icons.Filled.StarBorder, "Star")
                    }
                    IconButton(onClick = { draft?.let { vm.delete(it) }; onBack() }) {
                        Icon(Icons.Filled.Delete, "Delete")
                    }
                },
            )
        },
    ) { padding ->
        if (task == null) {
            Column(Modifier.padding(padding).fillMaxSize()) {}
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = task.title,
                onValueChange = { v -> update { it.copy(title = v) } },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = task.note,
                onValueChange = { v -> update { it.copy(note = v) } },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )

            Spacer(Modifier.height(16.dp))
            Label("Priority")
            val level = PriorityLevel.from(task.importance, task.urgency)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PriorityLevel.entries.forEachIndexed { i, lvl ->
                    SegmentedButton(
                        selected = level == lvl,
                        onClick = { update { it.copy(importance = lvl.importance, urgency = lvl.urgency) } },
                        shape = SegmentedButtonDefaults.itemShape(i, PriorityLevel.entries.size),
                    ) { Text(lvl.label) }
                }
            }
            if (settings.advancedPriority) {
                Spacer(Modifier.height(8.dp))
                DialRow("Importance", task.importance) { v -> update { it.copy(importance = v) } }
                DialRow("Urgency", task.urgency) { v -> update { it.copy(urgency = v) } }
            }

            Spacer(Modifier.height(16.dp))
            Label("Schedule")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Due", Modifier.weight(1f))
                task.dueDate?.let {
                    AssistChip(onClick = { showDuePicker = true }, label = { Text(formatDue(it)) })
                    IconButton(onClick = { update { t -> t.copy(dueDate = null) } }) { Icon(Icons.Filled.Close, "Clear due") }
                } ?: TextButton(onClick = { showDuePicker = true }) { Text("Set") }
            }

            Spacer(Modifier.height(8.dp))
            Label("Reminders")
            reminders.filter { it.taskId == task.id }.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.atTime?.let { formatDue(it) } ?: r.type, Modifier.weight(1f))
                    IconButton(onClick = { vm.deleteReminder(r, task) }) { Icon(Icons.Filled.Close, "Remove reminder") }
                }
            }
            OutlinedButton(onClick = { showReminderPicker = true }) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Add reminder")
            }

            Spacer(Modifier.height(16.dp))
            Label("Tags")
            val assignedTags = taskTagRefs.filter { it.taskId == task.id }.map { it.tagId }.toSet()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                allTags.forEach { tag ->
                    FilterChip(
                        selected = tag.id in assignedTags,
                        onClick = {
                            val next = if (tag.id in assignedTags) assignedTags - tag.id else assignedTags + tag.id
                            vm.setTags(task.id, next.toList())
                        },
                        label = { Text(tag.name) },
                    )
                }
            }
            AddInline(newTag, { newTag = it }, "New tag") { if (it.isNotBlank()) { vm.createTag(it.trim()); newTag = "" } }

            Spacer(Modifier.height(16.dp))
            Label("Contexts")
            val assignedCtx = taskCtxRefs.filter { it.taskId == task.id }.map { it.contextId }.toSet()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                allContexts.forEach { c ->
                    FilterChip(
                        selected = c.id in assignedCtx,
                        onClick = {
                            val next = if (c.id in assignedCtx) assignedCtx - c.id else assignedCtx + c.id
                            vm.setContexts(task.id, next.toList())
                        },
                        label = { Text("@${c.name}") },
                    )
                }
            }
            AddInline(newContext, { newContext = it }, "New context") { if (it.isNotBlank()) { vm.createContext(it.trim()); newContext = "" } }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDuePicker) {
        DateTimePickerDialog(
            initial = task?.dueDate,
            onDismiss = { showDuePicker = false },
            onConfirm = { millis -> update { it.copy(dueDate = millis) }; showDuePicker = false },
        )
    }
    if (showReminderPicker) {
        DateTimePickerDialog(
            initial = task?.dueDate ?: System.currentTimeMillis(),
            onDismiss = { showReminderPicker = false },
            onConfirm = { millis -> task?.let { vm.addAbsoluteReminder(it, millis) }; showReminderPicker = false },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun DialRow(name: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$name: $value", Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(1, 5)) },
            valueRange = 1f..5f,
            steps = 3,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddInline(value: String, onValueChange: (String) -> Unit, placeholder: String, onAdd: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onAdd(value) }) { Text("Add") }
    }
}
