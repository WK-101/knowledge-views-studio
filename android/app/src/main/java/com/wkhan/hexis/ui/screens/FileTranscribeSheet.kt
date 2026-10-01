package com.wkhan.hexis.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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

import com.wkhan.hexis.addon.OpenTranscribeClient
import com.wkhan.hexis.ui.AppViewModel

/**
 * Phase 3 surface: transcribe a picked audio file via an installed Open Transcribe provider (Scrib,
 * our addon, …). Chooser → progress → editable transcript the user can add as a task. The core holds
 * no microphone permission; the transcriber does the work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileTranscribeSheet(vm: AppViewModel) {
    val ui by vm.transcribeUi.collectAsStateWithLifecycle()
    if (ui.status == AppViewModel.TranscribeStatus.IDLE) return

    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = { vm.cancelFileTranscription() }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            when (ui.status) {
                AppViewModel.TranscribeStatus.CHOOSING -> ChoosingContent(vm, ui.providers)
                AppViewModel.TranscribeStatus.TRANSCRIBING -> TranscribingContent(ui.result)
                AppViewModel.TranscribeStatus.RESULT -> TranscribeResultContent(vm, ui.result)
                AppViewModel.TranscribeStatus.ERROR -> TranscribeErrorContent(vm, ui.error)
                AppViewModel.TranscribeStatus.IDLE -> Unit
            }
        }
    }
}

@Composable
private fun ChoosingContent(vm: AppViewModel, providers: List<OpenTranscribeClient.Provider>) {
    Text("Choose a transcriber", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    providers.forEach { p ->
        Text(
            p.label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vm.chooseTranscriber(p) }
                .padding(vertical = 12.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { vm.cancelFileTranscription() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

@Composable
private fun TranscribingContent(progress: String) {
    Text("Transcribing…", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    Text(
        progress.ifBlank { "Working on it — this can take a moment for longer recordings." },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TranscribeResultContent(vm: AppViewModel, result: String) {
    var text by remember(result) { mutableStateOf(result) }
    Text("Transcript", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp),
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { vm.commitTranscript(text) },
        enabled = text.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Add as task") }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { vm.cancelFileTranscription() }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
}

@Composable
private fun TranscribeErrorContent(vm: AppViewModel, error: String?) {
    Text("Transcription", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    Text(error ?: "Something went wrong.", color = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(20.dp))
    Button(onClick = { vm.cancelFileTranscription() }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
}
