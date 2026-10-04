package app.parley.ui.sync.shared

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.security.Bounded
import app.parley.common.sync.shared.SharedLabelInvites
import app.parley.common.sync.shared.SharedLabelUpdates
import app.parley.data.sync.shared.SharedLabelEngine.UpdateResult
import app.parley.data.sync.shared.SharedLabelState
import app.parley.data.sync.shared.SharedLabels
import app.parley.ui.Banner
import app.parley.ui.BannerTone
import app.parley.ui.Destination
import app.parley.ui.ParleyListItem
import app.parley.ui.SegmentedGroup
import app.parley.ui.SettingsScaffold
import app.parley.ui.Spacing
import app.parley.ui.rowColors
import app.parley.ui.startOrSay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Update files on their way out and in (docs/SHARED_LABELS.md, "Sharing by file"). */
internal object LabelUpdateFiles {
    private const val DIR = "label_updates"
    private const val INVITE_MAGIC = "PARLEYB1"

    /** Writes [bytes] as `<label>-<date>.parleyupdate` in the share folder (only the newest stays) and opens the share sheet. */
    fun share(context: Context, title: String, bytes: ByteArray): Boolean = runCatching {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val day = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date())
        val base = title.filter { it.isLetterOrDigit() || it == ' ' }.trim().replace(' ', '-').ifEmpty { "label" }
        val file = File(dir, "$base-$day${SharedLabelUpdates.FILE_EXTENSION}").apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType(SharedLabelUpdates.MIME).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        context.startOrSay(Intent.createChooser(send, context.getString(R.string.shl_send_chooser)), context.getString(R.string.main_no_app))
    }.getOrDefault(false)

    /** What a file handed to Parley is: an update, an invitation (sealed like a backup, named so or unnamed), or neither. */
    enum class Kind { UPDATE, INVITATION, OTHER }

    fun kindOf(bytes: ByteArray, name: String?): Kind = when {
        SharedLabelUpdates.looksLikeUpdate(bytes) -> Kind.UPDATE
        bytes.size > INVITE_MAGIC.length && String(bytes.copyOf(INVITE_MAGIC.length), Charsets.US_ASCII) == INVITE_MAGIC &&
            (name == null || name.endsWith(SharedLabelInvites.FILE_EXTENSION, ignoreCase = true)) -> Kind.INVITATION
        else -> Kind.OTHER
    }

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    fun read(context: Context, uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { Bounded.readBytes(it, SharedLabelUpdates.MAX_SEALED, "label update") }
    }.getOrNull()

    /** Why an update wasn't opened, in the user's words; null when it was merged. */
    fun problem(res: Resources, r: UpdateResult): String? = when (r) {
        UpdateResult.MERGED -> null
        UpdateResult.NOT_AN_UPDATE -> res.getString(R.string.shl_open_not_update)
        UpdateResult.OTHER_LABEL -> res.getString(R.string.shl_open_other_label)
        UpdateResult.WRONG_KEY -> res.getString(R.string.shl_open_wrong_key)
        UpdateResult.OLDER_KEY -> res.getString(R.string.shl_open_older_key)
        UpdateResult.NEWER_KEY -> res.getString(R.string.shl_open_newer_key)
        UpdateResult.DAMAGED -> res.getString(R.string.shl_open_damaged)
        UpdateResult.ALREADY_OPENED -> res.getString(R.string.shl_open_already)
        UpdateResult.OWN -> res.getString(R.string.shl_open_own)
        UpdateResult.UNAVAILABLE -> res.getString(R.string.shl_open_unavailable)
    }

    /** "Last update from them 2 days ago" for a member, "You last sent an update …" for this phone; null for a folder label without updates. */
    fun memberLine(res: Resources, s: SharedLabelState, memberHex: String, me: String?): String? {
        if (memberHex == me) return s.lastSentAt.takeIf { it > 0 }?.let { res.getString(R.string.shl_last_sent, SharedLabelTexts.ago(it)) }
        val at = s.exchanged[memberHex]
        return when {
            at != null -> res.getString(R.string.shl_member_last_update, SharedLabelTexts.ago(at))
            s.byFile -> res.getString(R.string.shl_member_no_update)
            else -> null
        }
    }
}

/** "Send an update" and "Open an update" for one shared label (any label: one shared by folder can reach someone without it). */
@Composable
internal fun UpdateRows(vm: AppViewModel, s: SharedLabelState, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            SharedLabelInbox.update.value = uri
            open(SharedLabelRoutes.OpenFile)
        }
    }
    SegmentedGroup(stringResource(R.string.shl_updates)) {
        item("send") {
            ParleyListItem(
                modifier = Modifier.clickable(enabled = !busy) {
                    busy = true
                    scope.launch {
                        val u: SharedLabels.Update? = vm.c.sharedLabels.sendUpdate(s.labelId)
                        busy = false
                        if (u == null || !LabelUpdateFiles.share(context, s.title, u.bytes)) vm.toast(res.getString(R.string.shl_send_failed))
                    }
                },
                leadingContent = { Icon(Icons.AutoMirrored.Rounded.Send, null) },
                headlineContent = { Text(stringResource(R.string.shl_send_update)) },
                supportingContent = {
                    Text(
                        if (s.lastSentAt > 0) res.getString(R.string.shl_last_sent, SharedLabelTexts.ago(s.lastSentAt)) else res.getString(R.string.shl_send_update_sub),
                    )
                },
                colors = rowColors(),
            )
        }
        item("open") {
            ParleyListItem(
                modifier = Modifier.clickable { picker.launch(arrayOf("*/*")) },
                leadingContent = { Icon(Icons.Rounded.FileOpen, null) },
                headlineContent = { Text(stringResource(R.string.shl_open_update)) },
                supportingContent = { Text(stringResource(R.string.shl_open_update_sub)) },
                colors = rowColors(),
            )
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Spacing.l))
}

/**
 * An update or invitation file from [SharedLabelInbox] (picked, or sent to Parley from another app): an update is
 * merged into its label and the result said; an invitation goes on to Join; anything else is named as not one.
 */
@Composable
fun OpenLabelFileScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val uri by SharedLabelInbox.update.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var tone by remember { mutableStateOf(BannerTone.INFO) }
    var label by remember { mutableStateOf<SharedLabelState?>(null) }
    fun done() {
        SharedLabelInbox.update.value = null
        back()
    }
    LaunchedEffect(uri) {
        val u = uri ?: return@LaunchedEffect
        val (bytes, name) = withContext(Dispatchers.IO) { LabelUpdateFiles.read(context, u) to LabelUpdateFiles.displayName(context, u) }
        val kind = bytes?.let { LabelUpdateFiles.kindOf(it, name) }
        when {
            bytes == null -> message = res.getString(R.string.shl_open_failed).also { tone = BannerTone.WARNING }
            kind == LabelUpdateFiles.Kind.INVITATION -> {
                SharedLabelInbox.update.value = null
                SharedLabelInbox.link.value = null
                SharedLabelInbox.file.value = u
                back()
                open(SharedLabelRoutes.Join)
            }
            kind == LabelUpdateFiles.Kind.OTHER -> message = res.getString(R.string.shl_open_not_update).also { tone = BannerTone.WARNING }
            else -> {
                val out = vm.c.sharedLabels.openUpdate(bytes)
                label = out.state
                val problem = LabelUpdateFiles.problem(res, out.result)
                tone = if (problem == null) BannerTone.INFO else BannerTone.WARNING
                message = problem ?: mergedText(res, out)
            }
        }
    }
    LaunchedEffect(uri) { if (uri == null && message == null) back() }
    SettingsScaffold(stringResource(R.string.shl_open_title), ::done) {
        val m = message
        if (m == null) {
            Text(stringResource(R.string.shl_open_reading), modifier = Modifier.padding(horizontal = Spacing.xl))
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Spacing.l))
        } else {
            Banner(m, tone = tone)
        }
        val l = label
        if (l != null && m != null) {
            Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Button({
                    SharedLabelInbox.update.value = null
                    back()
                    open(SharedLabelRoutes.Manage(l.labelId))
                }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.shl_open_show_label)) }
            }
        }
        Text(
            stringResource(R.string.shl_open_update_sub), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.xl),
        )
    }
}

private fun mergedText(res: Resources, out: SharedLabels.Opened): String {
    val title = out.state?.title.orEmpty()
    val head = if (out.fromName.isNotBlank()) res.getString(R.string.shl_open_merged, out.fromName, title) else res.getString(R.string.shl_open_merged_someone, title)
    val r = out.report
    val changed = r.applied + r.imported + r.linked + r.deleted
    val tail = if (changed > 0) res.getQuantityString(R.plurals.shl_open_changed, changed, changed) else res.getString(R.string.shl_open_nothing_new)
    val problem = out.state?.let { SharedLabelTexts.problem(res, it) }
    return listOfNotNull(head, tail, problem).joinToString(" ")
}
