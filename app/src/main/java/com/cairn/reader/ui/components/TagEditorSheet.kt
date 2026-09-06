@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cairn.reader.ui.components

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cairn.reader.data.db.TagEntity
import com.cairn.reader.data.db.TagWithCount

/** Edit the tags on one item: remove current tags, type a new one, or tap a suggestion. */
@Composable
fun TagEditorSheet(
    current: List<TagEntity>,
    all: List<TagWithCount>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val currentIds = current.map { it.id }.toSet()
    val suggestions = all.filter { it.id !in currentIds }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.tags), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            if (current.isEmpty()) {
                Text(stringResource(R.string.no_tags_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    current.forEach { tag ->
                        InputChip(
                            selected = true,
                            onClick = { onRemove(tag.id) },
                            label = { Text(tag.name) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.remove_2), modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.add_a_tag)) },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { if (text.isNotBlank()) { onAdd(text.trim()); text = "" } }) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.add_tag))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.tip_use_to_nest_e_g),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.existing_tags), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.take(24).forEach { tag ->
                        AssistChip(onClick = { onAdd(tag.name) }, label = { Text(if (tag.count > 0) "${tag.name} · ${tag.count}" else tag.name) })
                    }
                }
            }
        }
    }
}
