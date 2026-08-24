package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.QuickAddOptions
import com.todocompanion.app.ui.components.DateTimePickerDialog
import com.todocompanion.app.ui.components.formatDue
import com.todocompanion.app.ui.components.priorityColor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lists by vm.lists.collectAsState()
    val tags by vm.tags.collectAsState()

    var text by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<Long?>(null) }
    var hasTime by remember { mutableStateOf(false) }
    var priority by remember { mutableStateOf<PriorityLevel?>(null) }
    var listId by remember { mutableStateOf<String?>(null) }
    var reminder by remember { mutableStateOf<Long?>(null) }
    var tagIds by remember { mutableStateOf<List<String>>(emptyList()) }

    var showDue by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }
    var tagMenu by remember { mutableStateOf(false) }

    val focus = remember { FocusRequester() }

    fun submit() {
        if (text.isNotBlank()) vm.submitQuickAdd(text, QuickAddOptions(due, hasTime, priority, listId, tagIds, reminder))
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 14.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text("e.g. Pay rent tomorrow 5pm !! #home") },
                trailingIcon = { IconButton(onClick = { submit() }) { Icon(Icons.Filled.Send, "Add") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )

            // selected-value chips
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                due?.let { AssistChip(onClick = { showDue = true }, label = { Text(formatDue(it)) }) }
                priority?.takeIf { it != PriorityLevel.NONE }?.let { p ->
                    AssistChip(onClick = {}, label = { Text(p.label) }, leadingIcon = { Icon(Icons.Filled.Flag, null, tint = priorityColor(p), modifier = Modifier.width(16.dp)) })
                }
                listId?.let { id -> lists.firstOrNull { it.id == id }?.let { AssistChip(onClick = { listMenu = true }, label = { Text(it.name) }) } }
                reminder?.let { AssistChip(onClick = { showReminder = true }, label = { Text("🔔 " + formatDue(it)) }) }
                tagIds.forEach { id -> tags.firstOrNull { it.id == id }?.let { AssistChip(onClick = { tagMenu = true }, label = { Text("#" + it.name) }) } }
            }

            // option toolbar (icons)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tool(Icons.Filled.CalendarMonth, "Date", due != null) { showDue = true }
                Tool(Icons.Filled.Flag, "Priority", priority != null && priority != PriorityLevel.NONE) {
                    priority = when (priority) {
                        null, PriorityLevel.NONE -> PriorityLevel.LOW
                        PriorityLevel.LOW -> PriorityLevel.MEDIUM
                        PriorityLevel.MEDIUM -> PriorityLevel.HIGH
                        PriorityLevel.HIGH -> PriorityLevel.NONE
                    }
                }
                Box {
                    Tool(Icons.Filled.Label, "Tag", tagIds.isNotEmpty()) { tagMenu = true }
                    DropdownMenu(expanded = tagMenu, onDismissRequest = { tagMenu = false }) {
                        if (tags.isEmpty()) DropdownMenuItem(text = { Text("No tags yet — type #tag in the title") }, onClick = { tagMenu = false })
                        tags.forEach { t ->
                            DropdownMenuItem(
                                text = { Text((if (t.id in tagIds) "✓ " else "") + "#" + t.name) },
                                onClick = { tagIds = if (t.id in tagIds) tagIds - t.id else tagIds + t.id },
                            )
                        }
                    }
                }
                Box {
                    Tool(Icons.Filled.FormatListBulleted, "List", listId != null) { listMenu = true }
                    DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                        lists.filter { !it.archived }.forEach { l ->
                            DropdownMenuItem(text = { Text(l.name) }, onClick = { listId = l.id; listMenu = false })
                        }
                    }
                }
                Tool(Icons.Filled.NotificationsNone, "Reminder", reminder != null) { showReminder = true }
            }
        }
    }

    if (showDue) DateTimePickerDialog(initial = due, onDismiss = { showDue = false }, onConfirm = { due = it; hasTime = true; showDue = false })
    if (showReminder) DateTimePickerDialog(initial = reminder ?: due, onDismiss = { showReminder = false }, onConfirm = { reminder = it; showReminder = false })

    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun Tool(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val bg = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = fg, modifier = Modifier.size(20.dp)) }
}
