@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.components

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cairn.reader.data.db.CollectionWithCount

/** Pick the collection an item belongs to (or Unsorted), and create new ones inline. */
@Composable
fun CollectionPickerSheet(
    collections: List<CollectionWithCount>,
    currentCollectionId: String?,
    onPick: (String?) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Move to collection",
    unsortedLabel: String = "Unsorted",
) {
    var newName by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                PickRow(Icons.Outlined.Inbox, unsortedLabel, null, currentCollectionId == null) { onPick(null) }
                collections.forEach { c ->
                    PickRow(Icons.Outlined.FolderOpen, c.name, c.count, c.id == currentCollectionId) { onPick(c.id) }
                }
            }
            CreateRow(newName, { newName = it }) {
                if (newName.isNotBlank()) { onCreate(newName.trim()); newName = "" }
            }
        }
    }
}

/**
 * Multi-select membership picker: an item can live in several collections at once (Raindrop-style).
 * Each row is a checkbox reflecting current membership; toggling adds/removes without closing the sheet.
 */
@Composable
fun CollectionMembershipSheet(
    collections: List<CollectionWithCount>,
    membership: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Collections",
) {
    var newName by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            }
            if (collections.isEmpty()) {
                Text(stringResource(R.string.no_collections_yet_create_one_below),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                collections.forEach { c ->
                    val checked = c.id in membership
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onToggle(c.id, !checked) }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (checked) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                            contentDescription = if (checked) "In ${c.name}" else "Not in ${c.name}",
                            modifier = Modifier.size(22.dp),
                            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(16.dp))
                        Text(
                            c.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (c.count > 0) {
                            Text("${c.count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            CreateRow(newName, { newName = it }) {
                if (newName.isNotBlank()) { onCreate(newName.trim()); newName = "" }
            }
        }
    }
}

@Composable
private fun CreateRow(value: String, onValueChange: (String) -> Unit, onCreate: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.new_collection)) },
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCreate) {
            Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.create_collection))
        }
    }
}

@Composable
private fun PickRow(icon: ImageVector, label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (count != null && count > 0) {
            Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(10.dp))
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.current), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}
