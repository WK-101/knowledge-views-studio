package app.parley.ui.extras

import android.content.Context
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.CallType
import app.parley.common.extras.MarkdownNotes
import app.parley.data.extras.MarkdownExport
import app.parley.ui.circle.CircleText
import app.parley.ui.common.Format
import app.parley.work.FolderSyncWorker
import kotlinx.coroutines.launch

/** C5: the worded parts of the Markdown files, in the app's language. */
object MarkdownTexts {
    fun build(context: Context): MarkdownExport.Texts {
        val res = context.resources
        return MarkdownExport.Texts(
            headings = MarkdownNotes.Headings(
                pinnedNote = res.getString(R.string.x_md_h_pinned),
                note = res.getString(R.string.x_md_h_note),
                circle = res.getString(R.string.x_md_h_circle),
                timeline = res.getString(R.string.x_md_h_timeline),
                promises = res.getString(R.string.c2_promises),
            ),
            phoneLabel = { t, l -> ContactsContract.CommonDataKinds.Phone.getTypeLabel(res, t, l).toString() },
            emailLabel = { t, l -> ContactsContract.CommonDataKinds.Email.getTypeLabel(res, t, l).toString() },
            eventLabel = { ev -> app.parley.ui.people.eventLabel(res, ev) },
            keepInTouch = { d -> res.getQuantityString(R.plurals.circle_every_days, d, d) },
            call = { type, sec ->
                val kind = res.getString(
                    when (type) {
                        CallType.INCOMING, CallType.ANSWERED_EXTERNALLY -> R.string.x_md_call_in
                        CallType.OUTGOING -> R.string.x_md_call_out
                        CallType.MISSED, CallType.REJECTED, CallType.BLOCKED -> R.string.x_md_call_missed
                        else -> R.string.x_md_call
                    },
                )
                val min = ((sec + 59) / 60).toInt()
                if (sec > 0) kind + " · " + res.getQuantityString(R.plurals.x_md_minutes, min, min) else kind // l10n-ok (separator)
            },
            interaction = { t -> CircleText.type(res, t) },
            callNote = res.getString(R.string.x_md_call_note),
        )
    }
}

/**
 * C5 on the "Sync between your phones" screen: a second, one-way folder for Markdown notes (Obsidian and other note
 * apps). It shares the folder-sync worker: "Keep it up to date" exports again every hour.
 */
@Composable
fun MarkdownExportSection(vm: AppViewModel) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val md = vm.c.markdown
    val st by md.status.collectAsStateWithLifecycle()
    var running by remember { mutableStateOf(false) }
    fun run() {
        running = true
        scope.launch {
            val n = runCatching { md.exportNow(MarkdownTexts.build(context)) }
            running = false
            vm.toast(n.getOrNull()?.let { res.getQuantityString(R.plurals.x_md_written, it, it) } ?: res.getString(R.string.x_md_failed))
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            md.setFolder(uri, uri.lastPathSegment?.substringAfterLast(':'))
            FolderSyncWorker.reschedule(context)
            run()
        }
    }
    Column {
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.x_md_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.x_md_text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }
        }
        ListItem(
            modifier = Modifier.clickable { picker.launch(null) },
            leadingContent = { Icon(Icons.Rounded.Folder, null) },
            headlineContent = { Text(stringResource(R.string.x_md_folder)) },
            supportingContent = { Text(st.folderName ?: stringResource(R.string.bkp_folder_none)) },
        )
        ListItem(
            modifier = Modifier.clickable { md.setOnlyCircle(!st.onlyCircle) },
            leadingContent = { Icon(Icons.Rounded.Groups, null) },
            headlineContent = { Text(stringResource(R.string.x_md_only_circle)) },
            supportingContent = { Text(stringResource(R.string.x_md_only_circle_body)) },
            trailingContent = { Switch(st.onlyCircle, { md.setOnlyCircle(it) }) },
        )
        ListItem(
            modifier = Modifier.clickable { md.setAuto(!st.auto); FolderSyncWorker.reschedule(context) },
            headlineContent = { Text(stringResource(R.string.x_md_auto)) },
            supportingContent = { Text(stringResource(R.string.x_md_auto_body)) },
            trailingContent = { Switch(st.auto, { md.setAuto(it); FolderSyncWorker.reschedule(context) }) },
        )
        ListItem(
            leadingContent = { Icon(Icons.Rounded.Description, null) },
            headlineContent = {
                Text(if (st.lastAt > 0) stringResource(R.string.x_md_last, Format.shortWhen(context, st.lastAt)) else stringResource(R.string.x_md_never))
            },
            supportingContent = {
                Text(
                    when (st.lastProblem) {
                        MarkdownExport.NO_PERMISSION -> stringResource(R.string.x_md_no_permission)
                        MarkdownExport.FOLDER_GONE -> stringResource(R.string.x_md_folder_gone)
                        else -> pluralStringResource(R.plurals.x_md_people, st.lastPeople, st.lastPeople)
                    },
                )
            },
        )
        Button({ run() }, enabled = st.folderUri != null && !running, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.x_md_now)) }
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        if (st.folderUri != null) TextButton({ md.setFolder(null, null); FolderSyncWorker.reschedule(context) }, Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.x_md_stop))
        }
        Text(
            stringResource(R.string.x_md_private_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}
