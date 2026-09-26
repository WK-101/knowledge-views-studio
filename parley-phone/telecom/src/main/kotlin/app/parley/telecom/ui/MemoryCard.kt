package app.parley.telecom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.parley.common.circle.Promises
import app.parley.telecom.CallUi
import app.parley.telecom.R

/**
 * R8: "Anything to remember?" on the call-ended screen after a call with a contact (opt-in): a note, chips that
 * start a line ("Their news: ", a promise "[ ] ") and a follow-up reminder in a week or a month. Saved as a call
 * note, so it shows on the contact's timeline; promises can be ticked off there later (R9).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryCard(call: CallUi, onChoice: (PostCallChoice) -> Unit) {
    val number = call.number ?: return
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var followUp by remember { mutableStateOf<Int?>(null) }
    val news = stringResource(R.string.memory_news_prefix)
    fun startLine(prefix: String) {
        val t = value.text
        val out = if (t.isEmpty() || t.endsWith("\n")) t + prefix else t + "\n" + prefix
        value = TextFieldValue(out, TextRange(out.length))
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            // Any touch keeps the screen up, like the post-call card.
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
            Text(stringResource(R.string.memory_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value, { value = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2, maxLines = 5,
                placeholder = { Text(stringResource(R.string.memory_placeholder)) },
            )
            Text(
                stringResource(R.string.memory_promise_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip({ startLine(news) }, label = { Text(stringResource(R.string.memory_chip_news)) })
                AssistChip(
                    {
                        val (text, cursor) = Promises.insertBox(value.text, value.text.length)
                        value = TextFieldValue(text, TextRange(cursor))
                    },
                    label = { Text(stringResource(R.string.memory_chip_promised)) },
                    leadingIcon = { Icon(Icons.Rounded.CheckBoxOutlineBlank, null) },
                )
                FilterChip(followUp == 7, { followUp = if (followUp == 7) null else 7 }, label = { Text(stringResource(R.string.memory_chip_week)) })
                FilterChip(followUp == 30, { followUp = if (followUp == 30) null else 30 }, label = { Text(stringResource(R.string.memory_chip_month)) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ onChoice(PostCallChoice.Done) }) { Text(stringResource(R.string.memory_skip)) }
                val note = value.text.trim().takeIf { it.isNotEmpty() && it != news.trim() && it != Promises.OPEN.trim() }
                TextButton(
                    { onChoice(PostCallChoice.Remember(number, call.connectTimeMillis, note, followUp)) },
                    enabled = note != null || followUp != null,
                ) { Text(stringResource(R.string.tc_save)) }
            }
        }
    }
}
