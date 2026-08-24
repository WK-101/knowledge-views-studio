package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.todocompanion.app.ui.components.FLAG_COLORS
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
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import com.todocompanion.app.ui.components.AppCard
import com.todocompanion.app.ui.components.CardLabel
import com.todocompanion.app.ui.components.DateTimePickerDialog
import com.todocompanion.app.ui.components.formatDue
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskDetailScreen(vm: AppViewModel, taskId: String, onBack: () -> Unit) {
    val loaded by vm.observeTask(taskId).collectAsState(initial = null)
    var draft by remember(taskId) { mutableStateOf<TaskEntity?>(null) }
    if (draft == null && loaded != null) draft = loaded

    val settings by vm.settings.collectAsState()
    val allTags by vm.tags.collectAsState()
    val allContexts by vm.contexts.collectAsState()
    val ttRefs by vm.taskTags.collectAsState()
    val tcRefs by vm.taskContexts.collectAsState()
    val reminders by vm.reminders.collectAsState()
    val checklist by vm.checklist.collectAsState()
    val lists by vm.lists.collectAsState()

    var showDue by remember { mutableStateOf(false) }
    var showStart by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }
    var newContext by remember { mutableStateOf("") }
    var newCheck by remember { mutableStateOf("") }
    var listMenu by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }

    fun update(block: (TaskEntity) -> TaskEntity) {
        val d = draft ?: return; val nd = block(d); draft = nd; vm.save(nd)
    }

    BackHandler { onBack() }

    val task = draft
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Task") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { update { it.copy(star = !it.star) } }) {
                    Icon(if (task?.star == true) Icons.Filled.Star else Icons.Filled.StarBorder, "Star")
                }
                IconButton(onClick = { task?.let { vm.setAbandoned(it, !it.abandoned) } }) {
                    Icon(Icons.Filled.Cancel, "Won't do", tint = if (task?.abandoned == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { task?.let { vm.trash(it) }; onBack() }) { Icon(Icons.Filled.Delete, "Trash") }
                // Changes auto-save as you type; Done just confirms and closes.
                TextButton(onClick = onBack) { Text("Done", fontWeight = FontWeight.SemiBold) }
            },
        )
    }) { padding ->
        if (task == null) { Column(Modifier.padding(padding).fillMaxSize()) {}; return@Scaffold }
        val plevel = PriorityLevel.from(task.importance, task.urgency)
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Borderless title with an inline completion checkbox (TickTick-style).
            Row(verticalAlignment = Alignment.Top) {
                com.todocompanion.app.ui.components.PriorityCheckbox(task.completed, plevel) {
                    update { it.copy(completed = !it.completed, completedAt = if (!it.completed) System.currentTimeMillis() else null) }
                }
                Spacer(Modifier.width(2.dp))
                BorderlessField(
                    task.title, { v -> update { it.copy(title = v) } }, "Task title",
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface),
                    strikethrough = task.completed,
                    modifier = Modifier.weight(1f).padding(top = 8.dp),
                )
            }
            BorderlessField(
                task.note, { v -> update { it.copy(note = v) } }, "Notes",
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                singleLine = false,
                modifier = Modifier.fillMaxWidth().padding(start = 42.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))

            AppCard {
                CardLabel("Organize"); Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("List", Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { listMenu = true }) { Text(lists.firstOrNull { it.id == task.listId }?.name ?: "Inbox") }
                        DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                            lists.filter { !it.archived }.forEach { l ->
                                DropdownMenuItem(text = { Text(l.name) }, onClick = { vm.moveToList(task, l.id); draft = task.copy(listId = l.id); listMenu = false })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val level = PriorityLevel.from(task.importance, task.urgency)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PriorityLevel.entries.forEachIndexed { i, lvl ->
                        SegmentedButton(selected = level == lvl, onClick = { update { it.copy(importance = lvl.importance, urgency = lvl.urgency) } },
                            shape = SegmentedButtonDefaults.itemShape(i, PriorityLevel.entries.size)) { Text(lvl.label) }
                    }
                }
                if (settings.advancedPriority) {
                    Dial("Importance", task.importance) { v -> update { it.copy(importance = v) } }
                    Dial("Urgency", task.urgency) { v -> update { it.copy(urgency = v) } }
                }
                Spacer(Modifier.height(10.dp)); CardLabel("Flag"); Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlagSwatch(null, task.flagColorArgb) { update { it.copy(flagColorArgb = null) } }
                    FLAG_COLORS.forEach { c -> FlagSwatch(c, task.flagColorArgb) { update { it.copy(flagColorArgb = c) } } }
                }
            }

            AppCard {
                CardLabel("Schedule"); Spacer(Modifier.height(2.dp))
                ScheduleRow("Due", task.dueDate, onSet = { showDue = true }, onClear = { update { it.copy(dueDate = null) } })
                ScheduleRow("Start", task.startDate, onSet = { showStart = true }, onClear = { update { it.copy(startDate = null) } })
                RepeatRow(task.rrule) { rule -> update { it.copy(rrule = rule) } }
                Spacer(Modifier.height(8.dp)); CardLabel("Reminders")
                reminders.filter { it.taskId == task.id }.forEach { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.atTime?.let { formatDue(it) } ?: r.type, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { vm.deleteReminder(r, task) }) { Icon(Icons.Filled.Close, "Remove") }
                    }
                }
                TextButton(onClick = { showReminder = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Add reminder") }
            }

            AppCard {
                CardLabel("Checklist"); Spacer(Modifier.height(2.dp))
                checklist.filter { it.taskId == task.id }.sortedBy { it.sortOrder }.forEach { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.checked, onCheckedChange = { vm.toggleChecklist(item) })
                        Text(item.text, Modifier.weight(1f), color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        IconButton(onClick = { vm.deleteChecklistItem(item.id) }) { Icon(Icons.Filled.Close, "Remove") }
                    }
                }
                AddInline(newCheck, { newCheck = it }, "Add checklist item") { if (it.isNotBlank()) { vm.addChecklistItem(task.id, it.trim()); newCheck = "" } }
            }

            AppCard {
                CardLabel("Tags"); Spacer(Modifier.height(6.dp))
                val assignedTags = ttRefs.filter { it.taskId == task.id }.map { it.tagId }.toSet()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    allTags.forEach { tag ->
                        FilterChip(selected = tag.id in assignedTags, onClick = {
                            val next = if (tag.id in assignedTags) assignedTags - tag.id else assignedTags + tag.id
                            vm.setTags(task.id, next.toList())
                        }, label = { Text(tag.name) })
                    }
                }
                AddInline(newTag, { newTag = it }, "New tag") { if (it.isNotBlank()) { vm.createTag(it.trim()); newTag = "" } }
                Spacer(Modifier.height(8.dp)); CardLabel("Contexts"); Spacer(Modifier.height(6.dp))
                val assignedCtx = tcRefs.filter { it.taskId == task.id }.map { it.contextId }.toSet()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    allContexts.forEach { c ->
                        FilterChip(selected = c.id in assignedCtx, onClick = {
                            val next = if (c.id in assignedCtx) assignedCtx - c.id else assignedCtx + c.id
                            vm.setContexts(task.id, next.toList())
                        }, label = { Text("@" + c.name) })
                    }
                }
                AddInline(newContext, { newContext = it }, "New context") { if (it.isNotBlank()) { vm.createContext(it.trim()); newContext = "" } }
            }

            AppCard {
                TextButton(onClick = { showMore = !showMore }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(if (showMore) "Show less" else "Show more — estimate · goal")
                }
                if (showMore) {
                    Dial("Estimate (min)", (task.estimateMin ?: 0).coerceIn(0, 5)) { v -> update { it.copy(estimateMin = v * 15) } }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Mark as goal", Modifier.weight(1f))
                        Checkbox(checked = task.isGoal, onCheckedChange = { v -> update { it.copy(isGoal = v) } })
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDue) DateTimePickerDialog(task?.dueDate, { showDue = false }) { m -> update { it.copy(dueDate = m) }; showDue = false }
    if (showStart) DateTimePickerDialog(task?.startDate, { showStart = false }) { m -> update { it.copy(startDate = m) }; showStart = false }
    if (showReminder) DateTimePickerDialog(task?.dueDate ?: System.currentTimeMillis(), { showReminder = false }) { m -> task?.let { vm.addAbsoluteReminder(it, m) }; showReminder = false }
}

@Composable
private fun BorderlessField(
    value: String, onValueChange: (String) -> Unit, placeholder: String,
    textStyle: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier,
    singleLine: Boolean = true, strikethrough: Boolean = false,
) {
    Box(modifier) {
        if (value.isEmpty()) Text(placeholder, style = textStyle.copy(color = MaterialTheme.colorScheme.outline))
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onValueChange,
            textStyle = textStyle.copy(textDecoration = if (strikethrough) androidx.compose.ui.text.style.TextDecoration.LineThrough else androidx.compose.ui.text.style.TextDecoration.None),
            singleLine = singleLine,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FlagSwatch(color: Long?, current: Long?, onClick: () -> Unit) {
    val selected = color == current
    Box(
        Modifier.size(28.dp).clip(CircleShape)
            .background(color?.let { Color(it) } ?: Color.Transparent)
            .border(
                width = if (selected) 3.dp else 2.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable { onClick() },
    )
}

@Composable
private fun RepeatRow(rule: String?, onChange: (String?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Repeat", Modifier.weight(1f))
        Box {
            TextButton(onClick = { menu = true }) {
                Text(com.todocompanion.app.domain.recurrence.Recurrence.label(rule) ?: "Does not repeat")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                com.todocompanion.app.domain.recurrence.Recurrence.PRESETS.forEach { (r, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onChange(r); menu = false })
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(name: String, value: Long?, onSet: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f))
        if (value != null) {
            AssistChip(onClick = onSet, label = { Text(formatDue(value)) })
            IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Clear") }
        } else TextButton(onClick = onSet) { Text("Set") }
    }
}

@Composable
private fun Dial(name: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$name: $value", Modifier.width(130.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt().coerceIn(1, 5)) }, valueRange = 1f..5f, steps = 3, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddInline(value: String, onValueChange: (String) -> Unit, placeholder: String, onAdd: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        BorderlessField(value, onValueChange, placeholder, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface), modifier = Modifier.weight(1f))
        if (value.isNotBlank()) TextButton(onClick = { onAdd(value) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { Text("Add") }
    }
}
