package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.CountdownEntity
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.DateTimePickerDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private val CD_COLORS = listOf(0xFF6C4FE0, 0xFFE5484D, 0xFFF59E0B, 0xFF12A594, 0xFF3E7BFA, 0xFFEC4899)

/** A hub of countdowns to important dates — big "N days left" cards. Offline; part of the backup. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountdownScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val items by vm.countdowns.collectAsState()
    var editing by remember { mutableStateOf<CountdownEntity?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    Scaffold(topBar = {
        TopAppBar(expandedHeight = 52.dp, 
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text("Countdowns") },
            actions = { IconButton(onClick = { addOpen = true }) { Icon(Icons.Filled.Add, "Add countdown") } },
        )
    }) { padding ->
        if (items.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⏳", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.size(10.dp))
                Text("Count down to what matters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Pin birthdays, deadlines, trips — see the days remaining at a glance.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp))
                TextButton(onClick = { addOpen = true }) { Text("＋ New countdown") }
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items.sortedBy { it.targetMillis }, key = { it.id }) { c ->
                    val d = Instant.ofEpochMilli(c.targetMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                    val days = ChronoUnit.DAYS.between(today, d)
                    val accent = c.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                    Surface(shape = RoundedCornerShape(16.dp), color = accent.copy(alpha = .12f), modifier = Modifier.fillMaxWidth().clickable { editing = c }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (c.emoji != null) Text(c.emoji, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(end = 12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${d.year}",
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (days == 0L) "TODAY" else "${kotlin.math.abs(days)}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = accent)
                                Text(when { days == 0L -> ""; days > 0 -> "days left"; else -> "days ago" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { vm.toggleCountdownPin(c) }) {
                                Icon(if (c.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, "Pin to widget", tint = if (c.pinned) accent else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (addOpen) CountdownDialog(null, onDismiss = { addOpen = false }, onDelete = {},
        onSave = { title, millis, emoji, color -> vm.saveCountdown(null, title, millis, emoji, color); addOpen = false })
    editing?.let { c ->
        CountdownDialog(c, onDismiss = { editing = null }, onDelete = { vm.deleteCountdown(c.id); editing = null },
            onSave = { title, millis, emoji, color -> vm.saveCountdown(c.id, title, millis, emoji, color); editing = null })
    }
}

@Composable
private fun CountdownDialog(existing: CountdownEntity?, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (String, Long, String?, Long?) -> Unit) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var emoji by remember { mutableStateOf(existing?.emoji ?: "") }
    var emojiOpen by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf(existing?.colorArgb ?: CD_COLORS.first()) }
    var millis by remember { mutableStateOf(existing?.targetMillis ?: LocalDate.now().plusDays(30).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()) }
    var showDate by remember { mutableStateOf(false) }
    val d = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (title.isNotBlank()) onSave(title.trim(), millis, emoji.trim().ifBlank { null }, color) }) { Text("Save") } },
        dismissButton = { if (existing != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } else TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (existing == null) "New countdown" else "Countdown") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.width(72.dp).height(56.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f))
                            .clickable { emojiOpen = !emojiOpen },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (emoji.isBlank()) Text("＋ Emoji", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Text(emoji, style = MaterialTheme.typography.headlineSmall)
                    }
                    com.todocompanion.app.ui.components.AppTextField(title, { title = it }, singleLine = true, label = { Text("Title") }, modifier = Modifier.weight(1f))
                }
                if (emojiOpen) {
                    Spacer(Modifier.size(8.dp))
                    com.todocompanion.app.ui.components.EmojiGridPicker(current = emoji.ifBlank { null }, onPick = { emoji = it ?: ""; emojiOpen = false })
                }
                Spacer(Modifier.size(10.dp))
                TextButton(onClick = { showDate = true }) { Text("Date: ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${d.year}") }
                Spacer(Modifier.size(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CD_COLORS.forEach { c ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).clickable { color = c }, contentAlignment = Alignment.Center) {
                            if (c == color) Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
                        }
                    }
                }
            }
        },
    )
    if (showDate) DateTimePickerDialog(millis, { showDate = false }) { m -> millis = m; showDate = false }
}
