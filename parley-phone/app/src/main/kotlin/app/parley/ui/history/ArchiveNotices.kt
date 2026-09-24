package app.parley.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import kotlinx.coroutines.launch

/**
 * One-time notes about Parley's call archive, shown at the top of Recents:
 * - when the archive first holds calls: calls cleared in other apps stay in Parley (with a link to its settings);
 * - when the archive's key was lost and a new archive started (the old one is kept aside, not deleted).
 */
@Composable
fun ArchiveNotices(vm: AppViewModel, open: (String) -> Unit) {
    val prefs by vm.c.history.prefs.state.collectAsStateWithLifecycle()
    val archive by vm.c.history.archive.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    when {
        !prefs.archiveResetSeen && prefs.archiveResetAt > 0 -> Note(
            "Parley's call archive was reset",
            "Its encryption key was no longer available on this phone (this can happen after a system update or a " +
                "security change), so a new archive was started. The old one was set aside, not deleted.",
            onSettings = {
                scope.launch { vm.c.history.prefs.setArchiveResetSeen() }
                open(HistoryRoutes.SETTINGS)
            },
            onDismiss = { scope.launch { vm.c.history.prefs.setArchiveResetSeen() } },
        )
        prefs.archiveEnabled && !prefs.archiveIntroSeen && !archive.isNullOrEmpty() -> Note(
            "Parley keeps its own copy of your calls",
            "Android may drop old calls, so Parley keeps an encrypted copy on this phone. Calls you clear in " +
                "another app stay in Parley; delete them here, or turn the archive off in its settings.",
            onSettings = {
                scope.launch { vm.c.history.prefs.setArchiveIntroSeen() }
                open(HistoryRoutes.SETTINGS)
            },
            onDismiss = { scope.launch { vm.c.history.prefs.setArchiveIntroSeen() } },
        )
    }
}

@Composable
private fun Note(title: String, text: String, onSettings: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onSettings) { Text("Archive settings") }
                TextButton(onDismiss) { Text("Got it") }
            }
        }
    }
}
