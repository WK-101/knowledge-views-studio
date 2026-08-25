package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.QuickAddOptions
import com.todocompanion.app.ui.components.DateTimePickerDialog
import com.todocompanion.app.ui.components.formatDue
import com.todocompanion.app.ui.components.priorityColor

/**
 * TickTick-style quick capture: a title line ("What would you like to do?"), a description line,
 * then a single borderless icon row (date · priority · tag · list · reminder … send). The title
 * still runs through the natural-language parser (`tomorrow 5pm !! ~Home #bills`).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(vm: AppViewModel, initialDue: Long? = null, initialHasTime: Boolean = false, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lists by vm.lists.collectAsState()
    val tags by vm.tags.collectAsState()

    // Date-only entries use midnight (the all-day sentinel); a real time is set via the picker.
    fun dayMillis(d: java.time.LocalDate) = d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var due by remember { mutableStateOf(initialDue) }
    var hasTime by remember { mutableStateOf(initialHasTime) }
    var priority by remember { mutableStateOf<PriorityLevel?>(null) }
    var listId by remember { mutableStateOf<String?>(null) }
    var reminder by remember { mutableStateOf<Long?>(null) }
    var tagIds by remember { mutableStateOf<List<String>>(emptyList()) }

    var showDue by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }
    var tagMenu by remember { mutableStateOf(false) }
    var prioMenu by remember { mutableStateOf(false) }

    val focus = remember { FocusRequester() }

    fun submit() {
        if (text.isNotBlank()) vm.submitQuickAdd(text, QuickAddOptions(due, hasTime, priority, listId, tagIds, reminder, note.trim()))
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp)) {
            // Title — borderless, with live token highlighting.
            Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
                if (text.isEmpty()) Text("What would you like to do?",
                    color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                BasicTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    visualTransformation = QuickAddTransformation,
                )
            }
            // Description — borderless, muted.
            Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
                if (note.isEmpty()) Text("Description",
                    color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
                BasicTextField(
                    value = note, onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }

            // Selected list / tags / reminder as light chips (only when set).
            if (listId != null || tagIds.isNotEmpty() || reminder != null) {
                FlowRow(Modifier.padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listId?.let { id -> lists.firstOrNull { it.id == id }?.let { ValueChip(it.name) { listMenu = true } } }
                    tagIds.forEach { id -> tags.firstOrNull { it.id == id }?.let { ValueChip("#" + it.name) { tagMenu = true } } }
                    reminder?.let { ValueChip("🔔 " + formatDue(it)) { showReminder = true } }
                }
            }

            // The single TickTick-style icon row.
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                // Date: an inline coloured chip when set, otherwise a plain calendar glyph.
                if (due != null) {
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable { showDue = true }.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.CalendarMonth, "Date", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(formatDue(due!!), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(3.dp))
                        Icon(Icons.Filled.Close, "Clear date", tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp).clip(CircleShape).clickable { due = null; hasTime = false })
                    }
                } else {
                    IconTool(Icons.Filled.CalendarMonth, "Date", false) { showDue = true }
                }
                Box {
                    IconTool(Icons.Filled.Flag, "Priority", priority != null && priority != PriorityLevel.NONE,
                        tint = priority?.takeIf { it != PriorityLevel.NONE }?.let { priorityColor(it) }) { prioMenu = true }
                    DropdownMenu(expanded = prioMenu, onDismissRequest = { prioMenu = false }) {
                        listOf(PriorityLevel.HIGH, PriorityLevel.MEDIUM, PriorityLevel.LOW, PriorityLevel.NONE).forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.label) },
                                leadingIcon = { Icon(Icons.Filled.Flag, null, tint = priorityColor(p), modifier = Modifier.size(18.dp)) },
                                onClick = { priority = p; prioMenu = false },
                            )
                        }
                    }
                }
                Box {
                    IconTool(Icons.Filled.Label, "Tag", tagIds.isNotEmpty()) { tagMenu = true }
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
                    IconTool(Icons.AutoMirrored.Filled.FormatListBulleted, "List", listId != null) { listMenu = true }
                    DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                        lists.filter { !it.archived }.forEach { l ->
                            DropdownMenuItem(text = { Text(l.name) }, onClick = { listId = l.id; listMenu = false })
                        }
                    }
                }
                IconTool(Icons.Filled.NotificationsNone, "Reminder", reminder != null) { showReminder = true }
                Spacer(Modifier.width(0.dp).weight(1f))
                // Send.
                Box(Modifier.size(40.dp).clip(CircleShape).background(if (text.isBlank()) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary).clickable { submit() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Send, "Add", tint = if (text.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    if (showDue) DateTimePickerDialog(initial = due, onDismiss = { showDue = false }, onConfirm = { due = it; hasTime = true; showDue = false })
    if (showReminder) DateTimePickerDialog(initial = reminder ?: due, onDismiss = { showReminder = false }, onConfirm = { reminder = it; showReminder = false })

    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** A borderless icon button for the quick-add toolbar. Tinted when active. */
@Composable
private fun IconTool(icon: ImageVector, label: String, on: Boolean, tint: Color? = null, onClick: () -> Unit) {
    val fg = tint ?: if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(40.dp).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = fg, modifier = Modifier.size(22.dp))
    }
}

/** A light rounded chip showing a chosen value (list / tag / reminder), tap to change. */
@Composable
private fun ValueChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f))
            .clickable { onClick() }.padding(horizontal = 10.dp, vertical = 5.dp),
    ) { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
