package app.parley.telecom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.parley.telecom.R
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.parley.telecom.CallUi
import app.parley.telecom.TelecomGraph

/** What the user did on the post-call card (V4). */
sealed interface PostCallChoice {
    /** Touched the card: keep the call-ended screen up. */
    data object Touched : PostCallChoice
    data object Done : PostCallChoice
    data class Block(val number: String) : PostCallChoice
    data class SavePrivately(val number: String, val name: String) : PostCallChoice
    data class MessageOn(val number: String, val accountId: String?) : PostCallChoice
    data class Report(val number: String) : PostCallChoice
}

/**
 * Shown on the call-ended screen after a call with a number that isn't in your contacts (V4): block it (opens the
 * rule editor), save it privately for a week, message it on a chat app, or report it. Each opens only after the
 * phone is unlocked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PostCallCard(call: CallUi, onChoice: (PostCallChoice) -> Unit) {
    val number = call.number ?: return
    var saving by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            // Any touch keeps the screen up (it would otherwise close a moment after the call).
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        onChoice(PostCallChoice.Touched)
                    }
                }
            },
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(stringResource(R.string.incall_not_in_contacts), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(call.location, stringResource(R.string.postcall_what_to_do)).joinToString(stringResource(R.string.tc_separator)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(Icons.Rounded.Block, stringResource(R.string.postcall_block)) { onChoice(PostCallChoice.Block(number)) }
                Action(Icons.Rounded.Lock, stringResource(R.string.postcall_save_privately)) { saving = true }
                Action(Icons.AutoMirrored.Rounded.Chat, stringResource(R.string.postcall_message_on)) { onChoice(PostCallChoice.MessageOn(number, call.accountId)) }
                Action(Icons.Rounded.Flag, stringResource(R.string.postcall_report)) { onChoice(PostCallChoice.Report(number)) }
            }
            TextButton({ onChoice(PostCallChoice.Done) }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.tc_done)) }
        }
    }
    if (saving) {
        var name by remember { mutableStateOf(runCatching { TelecomGraph.dependencies.suggestedName(number) }.getOrDefault(number)) }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text(stringResource(R.string.postcall_save_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.postcall_name)) }, singleLine = true)
                    Text(
                        stringResource(R.string.postcall_save_explainer),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton({
                    saving = false
                    onChoice(PostCallChoice.SavePrivately(number, name.trim().ifEmpty { number }))
                }) { Text(stringResource(R.string.tc_save)) }
            },
            dismissButton = { TextButton({ saving = false }) { Text(stringResource(R.string.tc_cancel)) } },
        )
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(label)
    }
}
