package app.parley.messaging

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.common.NumberText
import app.parley.container
import app.parley.data.PhoneEnv
import app.parley.data.messaging.LastMessaged
import kotlinx.coroutines.launch

/**
 * F13, for the privacy dashboard: "Keep a record of numbers you message" (on by default), how many numbers it holds,
 * each one with a delete button, and "Clear all".
 */
@Composable
fun MessagedRecordSection() {
    val context = LocalContext.current
    val store = context.container.messaging
    val enabled by store.recordEnabled.collectAsStateWithLifecycle()
    val record by store.lastMessaged.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var manage by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("Keep a record of numbers you message") },
        supportingContent = {
            Text(
                if (enabled) {
                    "${record.size} numbers. Shows “Last messaged via WhatsApp · 2 days ago” on a number's page. Encrypted, on this phone only; " +
                        "never private contacts; follows your call-history retention."
                } else {
                    "Off: Parley doesn't note which numbers you open chats with."
                },
            )
        },
        trailingContent = { Switch(enabled, onCheckedChange = { v -> scope.launch { store.setRecordEnabled(v) } }) },
    )
    if (enabled && record.isNotEmpty()) {
        ListItem(
            headlineContent = { Text("See or clear numbers you messaged") },
            modifier = Modifier.clickable { manage = true },
        )
    }
    if (manage) {
        val region = remember { PhoneEnv.countryIso(context) }
        val entries = record.values.sortedByDescending { it.at }
        AlertDialog(
            onDismissRequest = { manage = false },
            title = { Text("Numbers you messaged") },
            text = {
                Column {
                    if (entries.isEmpty()) Text("Nothing recorded.")
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(entries, key = { it.key }) { e -> RecordRow(e, region) { scope.launch { e.number?.let { store.forget(it) } ?: store.forgetKey(e.key) } } }
                    }
                }
            },
            confirmButton = { TextButton({ scope.launch { store.clearAll() }; manage = false }) { Text("Clear all") } },
            dismissButton = { TextButton({ manage = false }) { Text("Done") } },
        )
    }
}

@Composable
private fun RecordRow(e: LastMessaged, region: String, onDelete: () -> Unit) {
    val shown = e.number?.let { n -> NumberText.toE164(n, region)?.let(NumberText::formatInternational) ?: n }
        ?: "…" + e.key.takeLast(4)
    val ago = DateUtils.getRelativeTimeSpanString(e.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    ListItem(
        headlineContent = { Text(shown) },
        supportingContent = { Text("${e.label} · $ago") },
        trailingContent = { IconButton(onDelete) { Icon(Icons.Rounded.Close, "Delete") } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
