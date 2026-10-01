package com.wkhan.hexis.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.wkhan.hexis.domain.nlp.QuickAddParser
import com.wkhan.hexis.ui.AppViewModel

/**
 * Phase 2 push-to-talk surface. Shows the live transcript while the addon listens, then an editable
 * "plan card" (a quick-add string prefilled from the voice, with a chip preview) that commits through
 * the same funnel as typed capture. Nothing is written until the user taps Add task.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCaptureSheet(vm: AppViewModel) {
    val ui by vm.voiceUi.collectAsStateWithLifecycle()
    if (ui.status == AppViewModel.VoiceStatus.IDLE) return

    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = { vm.cancelVoiceCapture() }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            when (ui.status) {
                AppViewModel.VoiceStatus.LISTENING -> ListeningContent(vm, ui.partial)
                AppViewModel.VoiceStatus.REVIEW -> ReviewContent(vm, ui.partial, ui.draftText)
                AppViewModel.VoiceStatus.ERROR -> ErrorContent(vm, ui.error)
                AppViewModel.VoiceStatus.IDLE -> Unit
            }
        }
    }
}

@Composable
private fun ListeningContent(vm: AppViewModel, partial: String) {
    Text("Listening…", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    Text(
        partial.ifBlank { "Speak a task — e.g. “buy milk tomorrow at 5pm”" },
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(20.dp))
    Button(onClick = { vm.stopVoiceListening() }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { vm.cancelVoiceCapture() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

@Composable
private fun ReviewContent(vm: AppViewModel, heard: String, draft: String) {
    var text by remember(draft) { mutableStateOf(draft) }
    Text("Review", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Heard: “$heard”",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Task") },
        modifier = Modifier.fillMaxWidth(),
    )
    val chips = remember(text) { QuickAddParser.parse(text).chips() }
    if (chips.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            chips.joinToString("   ") { it.text },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = { vm.commitVoiceCapture(text) },
        enabled = text.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Add task") }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { vm.cancelVoiceCapture() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

@Composable
private fun ErrorContent(vm: AppViewModel, error: String?) {
    Text("Voice capture", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    Text(error ?: "Something went wrong.", color = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(20.dp))
    Button(onClick = { vm.cancelVoiceCapture() }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
}
