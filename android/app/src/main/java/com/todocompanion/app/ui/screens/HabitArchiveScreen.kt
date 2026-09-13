package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.appCardColor

/**
 * Archived habits + Trash management. Archived habits are kept out of the active list & every analysis
 * surface but never deleted; trashed habits are recoverable soft-deletes. A habit is only ever erased
 * permanently from here (Delete forever / Empty Trash), always behind a confirm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitArchiveScreen(vm: AppViewModel, onClose: () -> Unit) {
    BackHandler { onClose() }
    var tab by remember { mutableIntStateOf(0) }   // 0 = Archived, 1 = Trash
    val withArchived by vm.habitsWithArchived.collectAsState()
    val trashed by vm.trashedHabits.collectAsState()
    val archived = withArchived.filter { it.archived }
    var confirmForever by remember { mutableStateOf<HabitEntity?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archived & Trash", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (tab == 1 && trashed.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) {
                        Text("Empty", color = MaterialTheme.colorScheme.error)
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                SegmentedButton(selected = tab == 0, onClick = { tab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text("Archived" + if (archived.isNotEmpty()) " (${archived.size})" else "")
                }
                SegmentedButton(selected = tab == 1, onClick = { tab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text("Trash" + if (trashed.isNotEmpty()) " (${trashed.size})" else "")
                }
            }
            val list = if (tab == 0) archived else trashed
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (tab == 0) "No archived habits.\nArchive a habit to hide it from the list and all stats without deleting it."
                        else "Trash is empty.\nDeleted habits land here and can be restored — or removed forever.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(list, key = { it.id }) { h ->
                        HabitArchiveRow(
                            h = h, inTrash = tab == 1,
                            onUnarchive = { vm.setHabitArchived(h, false) },
                            onTrash = { vm.trashHabit(h) },
                            onRestore = { vm.restoreHabit(h.id) },
                            onDeleteForever = { confirmForever = h },
                        )
                    }
                }
            }
        }
    }

    confirmForever?.let { h ->
        AlertDialog(
            onDismissRequest = { confirmForever = null },
            icon = { Icon(Icons.Filled.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete forever?") },
            text = { Text("“${h.name}” and all its check-in history will be permanently erased. This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.deleteHabit(h.id); confirmForever = null }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmForever = null }) { Text("Cancel") } },
        )
    }
    if (confirmEmpty) AlertDialog(
        onDismissRequest = { confirmEmpty = false },
        icon = { Icon(Icons.Filled.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Empty Trash?") },
        text = { Text("Permanently erase all ${trashed.size} habit(s) in the Trash and their history. This can't be undone.") },
        confirmButton = { TextButton(onClick = { vm.emptyHabitTrash(); confirmEmpty = false }) { Text("Empty Trash", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
    )
}

@Composable
private fun HabitArchiveRow(
    h: HabitEntity, inTrash: Boolean,
    onUnarchive: () -> Unit, onTrash: () -> Unit, onRestore: () -> Unit, onDeleteForever: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(16.dp), color = appCardColor(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(h.emoji ?: (if (h.habitType == "break") "🚫" else "🌱"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(h.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    (if (h.habitType == "break") "Quit habit" else "Build habit") + if (inTrash) " · in Trash" else " · archived",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (inTrash) {
                IconButton(onClick = onRestore) { Icon(Icons.Filled.Restore, "Restore", tint = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = onDeleteForever) { Icon(Icons.Filled.DeleteForever, "Delete forever", tint = MaterialTheme.colorScheme.error) }
            } else {
                IconButton(onClick = onUnarchive) { Icon(Icons.Filled.Unarchive, "Unarchive", tint = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = onTrash) { Icon(Icons.Filled.DeleteForever, "Move to Trash", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
