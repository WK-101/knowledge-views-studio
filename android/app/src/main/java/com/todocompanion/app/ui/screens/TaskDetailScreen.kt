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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val allFlags by vm.flags.collectAsState()
    val ttRefs by vm.taskTags.collectAsState()
    val tcRefs by vm.taskContexts.collectAsState()
    val reminders by vm.reminders.collectAsState()
    val checklist by vm.checklist.collectAsState()
    val lists by vm.lists.collectAsState()
    val allDeps by vm.dependencies.collectAsState()
    val allTasks by vm.tasks.collectAsState()

    var showDue by remember { mutableStateOf(false) }
    var showStart by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }
    var newContext by remember { mutableStateOf("") }
    var newCheck by remember { mutableStateOf("") }
    var listMenu by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showBlockPicker by remember { mutableStateOf(false) }
    var saveTemplate by remember { mutableStateOf(false) }
    var notePreview by remember(taskId) { mutableStateOf(true) }

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
                var menu by remember { mutableStateOf(false) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (task?.pinned == true) "Unpin" else "Pin to top") }, onClick = { task?.let { vm.togglePin(it) }; menu = false })
                    DropdownMenuItem(text = { Text(if (task?.isNote == true) "Convert to task" else "Convert to note") }, onClick = { task?.let { vm.toggleNote(it) }; menu = false })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { task?.let { vm.duplicateTask(it) }; menu = false; onBack() })
                    DropdownMenuItem(text = { Text("Save as template") }, onClick = { menu = false; saveTemplate = true })
                    if (!task?.rrule.isNullOrBlank()) DropdownMenuItem(text = { Text("Skip this occurrence") }, onClick = { task?.let { vm.skipOccurrence(it) }; menu = false; onBack() })
                    DropdownMenuItem(text = { Text(if (task?.abandoned == true) "Undo won't do" else "Won't do") }, onClick = { task?.let { vm.setAbandoned(it, !it.abandoned) }; menu = false })
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { task?.let { vm.trash(it) }; menu = false; onBack() })
                }
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
                com.todocompanion.app.ui.components.PriorityCheckbox(task.completed, plevel, onCheckedChange = {
                    update { it.copy(completed = !it.completed, completedAt = if (!it.completed) System.currentTimeMillis() else null) }
                }, onSetLevel = { lvl -> update { it.copy(importance = lvl.importance, urgency = lvl.urgency) } })
                Spacer(Modifier.width(2.dp))
                BorderlessField(
                    task.title, { v -> update { it.copy(title = v) } }, "Task title",
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface),
                    strikethrough = task.completed,
                    modifier = Modifier.weight(1f).padding(top = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(start = 42.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Notes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (task.note.isNotBlank()) {
                    IconButton(onClick = { notePreview = !notePreview }, modifier = Modifier.size(32.dp)) {
                        if (notePreview) Icon(Icons.Outlined.Edit, "Edit notes", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Icon(Icons.Outlined.Visibility, "Preview notes", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (task.note.isNotBlank() && notePreview) {
                com.todocompanion.app.ui.components.MarkdownText(
                    task.note,
                    modifier = Modifier.fillMaxWidth().padding(start = 42.dp, end = 4.dp, bottom = 4.dp)
                        .clickable { notePreview = false },
                )
            } else {
                BorderlessField(
                    task.note, { v -> update { it.copy(note = v) } }, "Notes — Markdown supported",
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().padding(start = 42.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))

            // Effort-weighted project rollup — shown for any task that has subtasks.
            val (doneW, totalW, doneN, totalN) = remember(allTasks, task.id) { projectRollup(task.id, allTasks) }
            if (totalN > 0) {
                AppCard {
                    val pct = if (totalW > 0) (doneW / totalW) else 0.0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CardLabel("Progress"); Spacer(Modifier.weight(1f))
                        Text("${(pct * 100).toInt()}% · $doneN of $totalN", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.LinearProgressIndicator(progress = { pct.toFloat() }, modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)))
                }
            }

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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = task.flagId == null,
                        onClick = { update { it.copy(flagId = null, flagColorArgb = null) } },
                        label = { Text("None") },
                    )
                    allFlags.forEach { f ->
                        FilterChip(
                            selected = task.flagId == f.id,
                            onClick = { update { it.copy(flagId = f.id, flagColorArgb = f.colorArgb) } },
                            leadingIcon = { Icon(com.todocompanion.app.ui.components.FlagIcons.vector(f.icon), null, tint = Color(f.colorArgb), modifier = Modifier.size(18.dp)) },
                            label = { Text(f.name) },
                        )
                    }
                }
            }

            AppCard {
                CardLabel("Schedule"); Spacer(Modifier.height(2.dp))
                ScheduleRow("Due", task.dueDate, onSet = { showDue = true }, onClear = { update { it.copy(dueDate = null) } })
                ScheduleRow("Start", task.startDate, onSet = { showStart = true }, onClear = { update { it.copy(startDate = null) } })
                // Duration sizes the block on the calendar timeline (only meaningful for a timed due).
                if (task.dueDate != null && !task.isAllDay && (java.time.Instant.ofEpochMilli(task.dueDate!!).atZone(java.time.ZoneId.systemDefault()).let { it.hour != 0 || it.minute != 0 })) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Duration", Modifier.weight(1f))
                        Box {
                            var durMenu by remember { mutableStateOf(false) }
                            TextButton(onClick = { durMenu = true }) { Text(task.durationMin?.let { "$it min" } ?: "30 min") }
                            DropdownMenu(expanded = durMenu, onDismissRequest = { durMenu = false }) {
                                listOf(15, 30, 45, 60, 90, 120, 180, 240).forEach { m ->
                                    DropdownMenuItem(text = { Text("$m min") }, onClick = { update { it.copy(durationMin = m) }; durMenu = false })
                                }
                            }
                        }
                    }
                }
                RepeatRow(task.rrule, allTasks.any { it.parentId == task.id && !it.trashed }) { rule -> update { it.copy(rrule = rule) } }
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
                val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    if (uri != null) vm.addAttachment(task.id, uri)
                }
                val attFlow = remember(task.id) { vm.attachmentMeta(task.id) }
                val attachments by attFlow.collectAsState(initial = emptyList())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CardLabel("Attachments"); Spacer(Modifier.weight(1f))
                    TextButton(onClick = { pickFile.launch("*/*") }) {
                        Icon(Icons.Filled.AttachFile, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Add")
                    }
                }
                if (attachments.isEmpty()) {
                    Text("Any file — images, PDF, Office docs, epub, text. Up to 25 MB per file, add as many as you like. Stored on-device and in backups.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                attachments.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.openAttachment(a.id, a.fileName, a.mime) }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (a.isImage) AttachmentThumb(vm, a.id) else {
                            val (fIcon, fTint) = attachmentGlyph(a.mime, a.fileName)
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(fTint.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                                Icon(fIcon, null, tint = fTint, modifier = Modifier.size(21.dp))
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text("${attachmentKind(a.mime, a.fileName)} · ${formatBytes(a.sizeBytes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        IconButton(onClick = { vm.removeAttachment(a.id) }) { Icon(Icons.Filled.Close, "Remove attachment") }
                    }
                }
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
                CardLabel("Blocked by"); Spacer(Modifier.height(4.dp))
                val myDeps = allDeps.filter { it.taskId == task.id }
                val byId = allTasks.associateBy { it.id }
                if (myDeps.isEmpty()) Text("Not blocked — this task can be done now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                myDeps.forEach { dep ->
                    val pred = byId[dep.dependsOnTaskId]
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (pred?.completed == true) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                            tint = if (pred?.completed == true) Color(0xFF12A594) else MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(pred?.title ?: "(deleted task)", Modifier.weight(1f), maxLines = 1)
                        IconButton(onClick = { vm.removeDependency(dep) }) { Icon(Icons.Filled.Close, "Remove", modifier = Modifier.size(18.dp)) }
                    }
                }
                // With 2+ blockers, choose whether all must finish or any one unblocks (MLO ALL/ANY).
                if (myDeps.size >= 2) {
                    val mode = myDeps.first().mode
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = mode == "AND", onClick = { vm.setDependencyMode(task.id, "AND") }, label = { Text("All must finish") })
                        FilterChip(selected = mode == "OR", onClick = { vm.setDependencyMode(task.id, "OR") }, label = { Text("Any one unblocks") })
                    }
                }
                // Delayed activation: start N days after the blocker(s) complete.
                if (myDeps.isNotEmpty()) {
                    val delay = myDeps.first().delayDays
                    Spacer(Modifier.height(4.dp))
                    Text("Start after", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "No delay", 1 to "1 day", 3 to "3 days", 7 to "1 week").forEach { (d, l) ->
                            FilterChip(selected = delay == d, onClick = { vm.setDependencyDelay(task.id, d) }, label = { Text(l) })
                        }
                    }
                }
                TextButton(onClick = { showBlockPicker = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("＋ Add a blocker") }
            }

            val hasChildren = allTasks.any { it.parentId == task.id && !it.trashed }
            AppCard {
                TextButton(onClick = { showMore = !showMore }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(if (showMore) "Show less" else "Show more — estimate · goal · project · review")
                }
                if (showMore) {
                    Dial("Estimate (min)", (task.estimateMin ?: 0).coerceIn(0, 5)) { v -> update { it.copy(estimateMin = v * 15) } }
                    SwitchRow("Mark as goal", task.isGoal) { v -> update { it.copy(isGoal = v) } }
                    SwitchRow("Mark as project", task.isProject) { v -> update { it.copy(isProject = v) } }
                    if (hasChildren) SwitchRow("Complete subtasks in order", task.completeInOrder) { v -> update { it.copy(completeInOrder = v) } }

                    // Per-item GTD review cadence.
                    Spacer(Modifier.height(6.dp)); CardLabel("Review cadence")
                    val reviewOpts = listOf(null to "Off", 1 to "Daily", 7 to "Weekly", 30 to "Monthly", 90 to "Quarterly")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        reviewOpts.forEach { (days, label) ->
                            FilterChip(selected = task.reviewEveryDays == days, onClick = { update { it.copy(reviewEveryDays = days) } }, label = { Text(label) })
                        }
                    }
                    if (task.reviewEveryDays != null) {
                        val last = task.reviewedAt ?: task.createdAt
                        val dueIn = ((last + task.reviewEveryDays!! * 86_400_000L) - System.currentTimeMillis()) / 86_400_000L
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (dueIn <= 0) "Due for review" else "Next review in ${dueIn}d", Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall, color = if (dueIn <= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { update { it.copy(reviewedAt = System.currentTimeMillis()) } }) { Text("Mark reviewed") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDue) DateTimePickerDialog(task?.dueDate, { showDue = false }) { m -> update { it.copy(dueDate = m) }; showDue = false }
    if (showStart) DateTimePickerDialog(task?.startDate, { showStart = false }) { m -> update { it.copy(startDate = m) }; showStart = false }
    if (showReminder) DateTimePickerDialog(task?.dueDate ?: System.currentTimeMillis(), { showReminder = false }) { m -> task?.let { vm.addAbsoluteReminder(it, m) }; showReminder = false }
    if (showBlockPicker && task != null) {
        val existing = allDeps.filter { it.taskId == task.id }.map { it.dependsOnTaskId }.toSet()
        val candidates = allTasks.filter { it.id != task.id && it.id !in existing && !it.trashed && it.parentId != task.id }
        BlockerPickerDialog(candidates, onDismiss = { showBlockPicker = false }) { picked ->
            vm.addDependency(task.id, picked); showBlockPicker = false
        }
    }
    if (saveTemplate && task != null) {
        var tplName by remember(task.id) { mutableStateOf(task.title) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { saveTemplate = false },
            confirmButton = { TextButton(onClick = { vm.saveAsTemplate(task.id, tplName.trim()); saveTemplate = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { saveTemplate = false }) { Text("Cancel") } },
            title = { Text("Save as template") },
            text = {
                Column {
                    Text("Saves this task and its subtree — note, priority, flag, checklist, tags, contexts, recurrence and relative dates — for reuse.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(tplName, { tplName = it }, singleLine = true, label = { Text("Template name") }, modifier = Modifier.fillMaxWidth())
                }
            },
        )
    }
}

@Composable
private fun BlockerPickerDialog(candidates: List<TaskEntity>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Blocked by which task?") },
        text = {
            if (candidates.isEmpty()) Text("No other tasks to depend on.")
            else LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(candidates, key = { it.id }) { t ->
                    Text(t.title, Modifier.fillMaxWidth().clickable { onPick(t.id) }.padding(vertical = 12.dp), maxLines = 1)
                }
            }
        },
    )
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
private fun RepeatRow(rule: String?, hasChildren: Boolean, onChange: (String?) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { show = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Repeat", Modifier.weight(1f))
        Text(com.todocompanion.app.domain.recurrence.Recurrence.label(rule) ?: "Does not repeat", color = MaterialTheme.colorScheme.primary)
    }
    if (show) RepeatDialog(rule, hasChildren, onDismiss = { show = false }) { onChange(it); show = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepeatDialog(rule: String?, hasChildren: Boolean, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    val r0 = com.todocompanion.app.domain.recurrence.Recurrence.parse(rule)
    var freq by remember { mutableStateOf(r0?.freq) }   // null = does not repeat
    var interval by remember { mutableStateOf(r0?.interval ?: 1) }
    var days by remember { mutableStateOf(r0?.byDays ?: emptySet<Int>()) }
    // end: 0 never, 1 until, 2 count
    var endMode by remember { mutableStateOf(if (r0?.untilEpochDay != null) 1 else if (r0?.count != null) 2 else 0) }
    var until by remember { mutableStateOf(r0?.untilEpochDay ?: java.time.LocalDate.now().plusMonths(3).toEpochDay()) }
    var count by remember { mutableStateOf(r0?.count ?: 10) }
    var showUntil by remember { mutableStateOf(false) }
    // Monthly mode: 0 day-of-month, 1 nth weekday, 2 first working day. + regenerate-from-completion.
    var monthMode by remember { mutableStateOf(if (r0?.firstWorkday == true) 2 else if (r0?.bySetPos != null && r0.byWeekday != null) 1 else 0) }
    var pos by remember { mutableStateOf(r0?.bySetPos ?: 1) }
    var weekday by remember { mutableStateOf(r0?.byWeekday ?: 1) }
    var fromCompletion by remember { mutableStateOf(r0?.fromCompletion ?: false) }
    var subtaskReset by remember { mutableStateOf(r0?.subtaskReset ?: "all") }

    fun build(): String? {
        val f = freq ?: return null
        val isMonthly = f == com.todocompanion.app.domain.recurrence.Freq.MONTHLY
        return com.todocompanion.app.domain.recurrence.Recurrence.encode(
            com.todocompanion.app.domain.recurrence.Recur(
                freq = f, interval = interval.coerceAtLeast(1),
                byDays = if (f == com.todocompanion.app.domain.recurrence.Freq.WEEKLY) days else emptySet(),
                bySetPos = if (isMonthly && monthMode == 1) pos else null,
                byWeekday = if (isMonthly && monthMode == 1) weekday else null,
                firstWorkday = isMonthly && monthMode == 2,
                fromCompletion = fromCompletion,
                subtaskReset = subtaskReset,
                untilEpochDay = if (endMode == 1) until else null,
                count = if (endMode == 2) count.coerceAtLeast(1) else null,
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(build()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Repeat") },
        text = {
            Column {
                val freqs = listOf<Pair<com.todocompanion.app.domain.recurrence.Freq?, String>>(
                    null to "None",
                    com.todocompanion.app.domain.recurrence.Freq.DAILY to "Daily",
                    com.todocompanion.app.domain.recurrence.Freq.WEEKDAYS to "Weekday",
                    com.todocompanion.app.domain.recurrence.Freq.WEEKLY to "Weekly",
                    com.todocompanion.app.domain.recurrence.Freq.MONTHLY to "Monthly",
                    com.todocompanion.app.domain.recurrence.Freq.YEARLY to "Yearly",
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    freqs.forEach { (f, l) -> FilterChip(selected = freq == f, onClick = { freq = f }, label = { Text(l) }) }
                }
                if (freq != null && freq != com.todocompanion.app.domain.recurrence.Freq.WEEKDAYS) {
                    Spacer(Modifier.size(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Every", Modifier.padding(end = 8.dp))
                        Stepper(interval) { interval = it.coerceIn(1, 99) }
                    }
                }
                if (freq == com.todocompanion.app.domain.recurrence.Freq.WEEKLY) {
                    Spacer(Modifier.size(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 7 to "S").forEach { (d, l) ->
                            FilterChip(selected = d in days, onClick = { days = if (d in days) days - d else days + d }, label = { Text(l) })
                        }
                    }
                }
                if (freq == com.todocompanion.app.domain.recurrence.Freq.MONTHLY) {
                    Spacer(Modifier.size(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = monthMode == 0, onClick = { monthMode = 0 }, label = { Text("On day of month") })
                        FilterChip(selected = monthMode == 1, onClick = { monthMode = 1 }, label = { Text("On a weekday") })
                        FilterChip(selected = monthMode == 2, onClick = { monthMode = 2 }, label = { Text("First working day") })
                    }
                    if (monthMode == 1) {
                        Spacer(Modifier.size(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(1 to "1st", 2 to "2nd", 3 to "3rd", 4 to "4th", -1 to "Last").forEach { (p, l) ->
                                FilterChip(selected = pos == p, onClick = { pos = p }, label = { Text(l) })
                            }
                        }
                        Spacer(Modifier.size(4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 7 to "S").forEach { (d, l) ->
                                FilterChip(selected = weekday == d, onClick = { weekday = d }, label = { Text(l) })
                            }
                        }
                    }
                }
                if (freq != null) {
                    Spacer(Modifier.size(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Repeat after completion", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = fromCompletion, onCheckedChange = { fromCompletion = it })
                    }
                }
                if (freq != null && hasChildren) {
                    Spacer(Modifier.size(10.dp)); Text("Subtasks each cycle", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("all" to "Reset all", "allDone" to "Only if all done", "keep" to "Keep").forEach { (k, l) ->
                            FilterChip(selected = subtaskReset == k, onClick = { subtaskReset = k }, label = { Text(l) })
                        }
                    }
                }
                if (freq != null) {
                    Spacer(Modifier.size(12.dp)); Text("Ends", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "Never", 1 to "On date", 2 to "After N").forEachIndexed { _, (m, l) ->
                            FilterChip(selected = endMode == m, onClick = { endMode = m }, label = { Text(l) })
                        }
                    }
                    if (endMode == 1) TextButton(onClick = { showUntil = true }) { Text("Until " + java.time.LocalDate.ofEpochDay(until)) }
                    if (endMode == 2) Row(verticalAlignment = Alignment.CenterVertically) { Text("After", Modifier.padding(end = 8.dp)); Stepper(count) { count = it.coerceIn(1, 999) }; Text(" times", Modifier.padding(start = 6.dp)) }
                }
            }
        },
    )
    if (showUntil) {
        val z = java.time.ZoneId.systemDefault()
        DateTimePickerDialog(java.time.LocalDate.ofEpochDay(until).atStartOfDay(z).toInstant().toEpochMilli(), { showUntil = false }) { m ->
            until = java.time.Instant.ofEpochMilli(m).atZone(z).toLocalDate().toEpochDay(); showUntil = false
        }
    }
}

@Composable
private fun Stepper(value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange(value - 1) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) { Text("−") }
        Text(value.toString(), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { onChange(value + 1) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) { Text("+") }
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
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
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

/** Small image preview for an attachment, decoded off the main thread and downsampled. */
@Composable
private fun AttachmentThumb(vm: AppViewModel, id: String) {
    var bmp by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(id) {
        val b64 = vm.attachmentContent(id) ?: return@LaunchedEffect
        bmp = withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 240 || bounds.outHeight / sample > 240) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** Pick an icon + accent for a non-image attachment from its MIME type / extension. */
private fun attachmentGlyph(mime: String, name: String): Pair<androidx.compose.ui.graphics.vector.ImageVector, androidx.compose.ui.graphics.Color> {
    val ext = name.substringAfterLast('.', "").lowercase()
    val m = mime.lowercase()
    return when {
        m.contains("pdf") || ext == "pdf" -> Icons.Filled.PictureAsPdf to androidx.compose.ui.graphics.Color(0xFFE5484D)
        m.contains("word") || m.contains("msword") || ext in setOf("doc", "docx", "odt", "rtf") -> Icons.AutoMirrored.Filled.Article to androidx.compose.ui.graphics.Color(0xFF2F6BFF)
        m.contains("sheet") || m.contains("excel") || ext in setOf("xls", "xlsx", "ods", "csv") -> Icons.Filled.TableChart to androidx.compose.ui.graphics.Color(0xFF0EA371)
        m.contains("presentation") || m.contains("powerpoint") || ext in setOf("ppt", "pptx", "odp") -> Icons.Filled.Slideshow to androidx.compose.ui.graphics.Color(0xFFEA580C)
        ext == "epub" || m.contains("epub") -> Icons.Filled.MenuBook to androidx.compose.ui.graphics.Color(0xFF8B5CF6)
        m.startsWith("audio") -> Icons.Filled.AudioFile to androidx.compose.ui.graphics.Color(0xFFDB2777)
        m.startsWith("video") -> Icons.Filled.VideoFile to androidx.compose.ui.graphics.Color(0xFF7C3AED)
        m.contains("zip") || m.contains("compressed") || ext in setOf("zip", "rar", "7z", "tar", "gz") -> Icons.Filled.FolderZip to androidx.compose.ui.graphics.Color(0xFFCA8A04)
        m.startsWith("text") || ext in setOf("txt", "md", "log", "json", "xml") -> Icons.AutoMirrored.Filled.TextSnippet to androidx.compose.ui.graphics.Color(0xFF64748B)
        else -> Icons.Filled.InsertDriveFile to androidx.compose.ui.graphics.Color(0xFF64748B)
    }
}

private fun attachmentKind(mime: String, name: String): String {
    val ext = name.substringAfterLast('.', "").uppercase()
    return ext.ifBlank { mime.substringAfterLast('/', "file").uppercase() }
}

private data class Rollup(val doneWeight: Double, val totalWeight: Double, val doneCount: Int, val totalCount: Int)

/** Effort-weighted completion across a task's whole subtree. Leaf tasks carry weight = their
 *  estimate (default 15 min); a heavier subtask contributes more, matching MLO's rollup. */
private fun projectRollup(taskId: String, all: List<TaskEntity>): Rollup {
    val byParent = all.filter { !it.trashed }.groupBy { it.parentId }
    var dw = 0.0; var tw = 0.0; var dc = 0; var tc = 0
    fun walk(id: String) {
        val kids = byParent[id].orEmpty()
        for (c in kids) {
            val grandkids = byParent[c.id].orEmpty()
            if (grandkids.isEmpty()) {
                val w = (c.estimateMin ?: 15).coerceAtLeast(1).toDouble()
                tw += w; tc += 1
                if (c.completed) { dw += w; dc += 1 }
            } else walk(c.id)
        }
    }
    walk(taskId)
    return Rollup(dw, tw, dc, tc)
}

private fun formatBytes(n: Long): String = when {
    n >= 1024 * 1024 -> "%.1f MB".format(n / (1024.0 * 1024))
    n >= 1024 -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}
