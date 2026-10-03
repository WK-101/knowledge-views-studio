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
import androidx.compose.material3.OutlinedButton
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
import com.wkhan.hexis.domain.voice.VoiceIntent
import com.wkhan.hexis.ui.AppViewModel

/**
 * The push-to-talk surface, hosted globally (the mic FAB fires from any tab). It shows the recording /
 * transcribing state, then an intent-routed review: the transcript (editable) plus the actions the app
 * can take with it — add task, add note, search, command palette, start/stop a timer — with the best
 * guess offered first. Nothing is written until the user picks an action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCaptureSheet(vm: AppViewModel) {
    val ui by vm.voiceUi.collectAsStateWithLifecycle()
    if (ui.status == AppViewModel.VoiceStatus.IDLE) return

    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = { vm.cancelVoiceCapture() }, sheetState = sheetState) {
        VoiceCapturePanel(vm)
    }
}

/**
 * The capture + intent-router content, free of any sheet chrome, so it can be hosted either in the
 * global [VoiceCaptureSheet] (a ModalBottomSheet) or drawn directly inside the translucent widget
 * popup activity (where a ModalBottomSheet can't be used).
 */
@Composable
fun VoiceCapturePanel(vm: AppViewModel) {
    val ui by vm.voiceUi.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        when (ui.status) {
            AppViewModel.VoiceStatus.LISTENING -> ListeningContent(vm, ui.partial)
            AppViewModel.VoiceStatus.TRANSCRIBING -> TranscribingContent()
            AppViewModel.VoiceStatus.REVIEW -> ReviewContent(vm, ui.partial, ui.draftText, ui.intent)
            AppViewModel.VoiceStatus.ERROR -> ErrorContent(vm, ui.error)
            AppViewModel.VoiceStatus.IDLE -> Unit
        }
    }
}

@Composable
private fun ListeningContent(vm: AppViewModel, partial: String) {
    Text("Listening…", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    Text(
        partial.ifBlank { "Speak, then tap Stop — e.g. “buy milk tomorrow”, “note call the dentist”, “search budget”, “start timer for deep work”." },
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(20.dp))
    Button(onClick = { vm.stopVoiceListening() }, modifier = Modifier.fillMaxWidth()) { Text("Stop & transcribe") }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { vm.cancelVoiceCapture() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

@Composable
private fun TranscribingContent() {
    Text("Transcribing…", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    Text(
        "Turning your recording into text on-device — a moment…",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
}

private class VoiceAction(val label: String, val run: () -> Unit)

/** The routed actions, best guess first. Each acts on the (possibly edited) [text]. */
private fun voiceActions(vm: AppViewModel, text: String, intent: VoiceIntent): List<VoiceAction> {
    val task = VoiceAction("Add task") { vm.commitVoiceCapture(text) }
    val note = VoiceAction("Add note") { vm.commitVoiceNote(text) }
    val search = VoiceAction("Search the app") { vm.commitVoiceSearch(text) }
    val timer = VoiceAction("Start timer") { vm.commitVoiceStartTimer(text) }
    val command = VoiceAction("Command palette") { vm.commitVoiceCommand(text) }
    val speak = VoiceAction("Speak the answer") { vm.commitVoiceQuery(text) }
    return when (intent) {
        VoiceIntent.STOP_TIMER ->
            listOf(VoiceAction("Stop timer") { vm.commitVoiceStopTimer() }, task, note, search, command)
        VoiceIntent.START_TIMER -> listOf(timer, task, note, search, command)
        VoiceIntent.ADD_NOTE -> listOf(note, task, search, timer, command)
        VoiceIntent.SEARCH -> listOf(search, task, note, command, timer)
        VoiceIntent.QUERY -> listOf(speak, search, command, task, note)
        else -> listOf(task, note, search, timer, command) // ADD_TASK / UNKNOWN
    }
}

@Composable
private fun ReviewContent(vm: AppViewModel, heard: String, draft: String, intent: VoiceIntent) {
    var text by remember(draft) { mutableStateOf(draft) }
    Text("What should I do?", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Heard: “$heard”",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (intent != VoiceIntent.STOP_TIMER) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Text") },
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
    }
    Spacer(Modifier.height(16.dp))
    val actions = voiceActions(vm, text.trim(), intent)
    actions.forEachIndexed { i, action ->
        val enabled = intent == VoiceIntent.STOP_TIMER || text.isNotBlank()
        if (i == 0) {
            Button(onClick = action.run, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(action.label) }
        } else {
            OutlinedButton(onClick = action.run, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(action.label) }
        }
        Spacer(Modifier.height(8.dp))
    }
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
