package app.parley.ui.sync.shared

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.people.ContactRef
import app.parley.common.security.Bounded
import app.parley.common.security.PassphraseStrength
import app.parley.common.sync.shared.Invitation
import app.parley.common.sync.shared.LabelMember
import app.parley.common.sync.shared.SharedLabelInvites
import app.parley.common.sync.shared.SharedLabelMembership
import app.parley.data.sync.shared.SharedLabelEngine
import app.parley.data.sync.shared.SharedLabels
import app.parley.ui.Banner
import app.parley.ui.BannerTone
import app.parley.ui.Bidi
import app.parley.ui.ConfirmDialog
import app.parley.ui.Destination
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyListItem
import app.parley.ui.SegmentedGroup
import app.parley.ui.SettingsScaffold
import app.parley.ui.Spacing
import app.parley.ui.StrengthMeter
import app.parley.ui.backup.PassField
import app.parley.ui.contact.SecureQr
import app.parley.ui.qr.QrRoutes
import app.parley.ui.rowColors
import app.parley.ui.sync.strengthName
import app.parley.work.FolderSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A folder's name as the picker returns it ("Syncthing/Family" from its tree id). */
private fun folderName(uri: Uri): String = uri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: uri.toString()

/** A new passphrase typed twice; usable once it is "Strong" on the backup's estimator and both match. */
@Composable
private fun NewPassphrase(label: String, a: String, b: String, onA: (String) -> Unit, onB: (String) -> Unit) {
    PassField(label, a, onA)
    PassField(stringResource(R.string.shl_pass_again), b, onB)
    if (a.isNotEmpty()) {
        val e = remember(a) { PassphraseStrength.estimate(a) }
        StrengthMeter(e.score, strengthName(e.score), modifier = Modifier.padding(top = Spacing.s))
    }
}

private fun passReady(a: String, b: String) = a == b && PassphraseStrength.acceptableForBackup(a)

// ---------------------------------------------------------------- Share this label…

private fun createdProblem(r: SharedLabels.Created): Int? = when (r) {
    SharedLabels.Created.READY -> null
    SharedLabels.Created.SAME_AS_SYNC -> R.string.shl_err_same_as_sync
    SharedLabels.Created.FOLDER_IN_USE -> R.string.shl_err_in_use
    SharedLabels.Created.FOLDER_UNAVAILABLE -> R.string.shl_err_folder
    SharedLabels.Created.CANT_SIGN -> R.string.shl_status_cant_sign
    SharedLabels.Created.NO_PERMISSION -> R.string.shl_err_permission
    SharedLabels.Created.FAILED -> R.string.shl_err_failed
}

/** "Share this label…": an empty folder, the label's own passphrase, your name; then invitations on the next screen. */
@Composable
fun ShareLabelScreen(vm: AppViewModel, title: String, back: () -> Unit, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    // Passphrases are never put in saved state.
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var privateCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(title) { privateCount = vm.c.people.labels.members(title).count { ContactRef.ofNavId(it) is ContactRef.Private } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            folder = uri.toString()
            error = null
        }
    }
    SettingsScaffold(stringResource(R.string.shl_share_title, title), back) {
        Text(
            stringResource(R.string.shl_share_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl),
        )
        // In discreet mode nothing may hint that private contacts exist.
        val discreet = vm.settings.collectAsStateWithLifecycle().value.hideVault
        if (privateCount > 0 && !discreet) Banner(pluralStringResource(R.plurals.shl_private_left, privateCount, privateCount))
        SegmentedGroup {
            item("folder") {
                ParleyListItem(
                    modifier = Modifier.clickable { picker.launch(null) },
                    leadingContent = { Icon(Icons.Rounded.Folder, null) },
                    headlineContent = { Text(stringResource(R.string.shl_share_folder)) },
                    supportingContent = { Text(folder?.let { folderName(Uri.parse(it)) } ?: stringResource(R.string.shl_share_folder_none)) },
                    colors = rowColors(),
                )
            }
        }
        Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(R.string.shl_share_pass_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NewPassphrase(stringResource(R.string.shl_share_pass), pass, again, { pass = it; error = null }, { again = it; error = null })
            OutlinedTextField(
                name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.shl_your_name)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(
                {
                    val f = folder ?: return@Button
                    busy = true
                    scope.launch {
                        val r = vm.c.sharedLabels.create(title, Uri.parse(f), folderName(Uri.parse(f)), pass.toCharArray(), name.trim())
                        busy = false
                        error = createdProblem(r)?.let(res::getString)
                        if (r == SharedLabels.Created.READY) {
                            FolderSyncWorker.reschedule(context)
                            vm.toast(res.getString(R.string.shl_share_done, title))
                            val id = vm.c.sharedLabels.forTitle(title)?.labelId
                            back()
                            if (id != null) open(SharedLabelRoutes.Manage(id))
                        }
                    }
                },
                enabled = !busy && folder != null && passReady(pass, again) && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.shl_share_start)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

// ---------------------------------------------------------------- Members & invitations

/** One shared label: invitations, members with their fingerprints, every change, removing someone and leaving. */
@Composable
fun ManageSharedLabelScreen(vm: AppViewModel, id: String, back: () -> Unit) {
    val res = LocalResources.current
    val shared = vm.c.sharedLabels
    LaunchedEffect(Unit) { shared.load() }
    val states by shared.states.collectAsStateWithLifecycle()
    val s = states.firstOrNull { it.labelId == id }
    val me = rememberMyHex(vm)
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<LabelMember?>(null) }
    LaunchedEffect(s == null, states.isNotEmpty()) { if (s == null && states.isNotEmpty()) back() }
    if (s == null) return
    val active = SharedLabelMembership.syncs(s.membership)
    SettingsScaffold(stringResource(R.string.shl_manage_title, s.title), back) {
        SharedLabelTexts.problem(res, s)?.let { Banner(it, tone = BannerTone.WARNING) }
        Text(
            SharedLabelTexts.status(res, s) + stringResource(R.string.main_separator) + s.folderName, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.xl),
        )
        if (active) InviteRows(vm, id, s.title)
        SegmentedGroup(stringResource(R.string.shl_members)) {
            s.members.forEach { m ->
                item(m.keyHex) { MemberRow(m, me, s.members, canRemove = active && m.keyHex != me) { removing = m } }
            }
        }
        Column { HistoryList(s, me, filter, onFilter = { filter = it }) }
        LeaveButton(vm, id, s.title, back)
    }
    removing?.let { m -> RemoveMemberDialog(vm, s.labelId, m) { removing = null } }
}

/** Invite with a QR code (shown here) or a file (sealed with the label's passphrase, saved where you choose). */
@Composable
private fun InviteRows(vm: AppViewModel, id: String, title: String) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var showQr by remember { mutableStateOf(false) }
    var askFilePass by remember { mutableStateOf(false) }
    // Never in saved state: kept only while this screen lives.
    var filePass by remember { mutableStateOf<CharArray?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val p = filePass
        filePass = null
        if (uri != null && p != null) {
            scope.launch {
                val r = vm.c.sharedLabels.inviteFile(id, p) { bytes -> context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null }
                p.fill(' ')
                vm.toast(res.getString(inviteFileText(r)))
            }
        }
    }
    SegmentedGroup {
        item("qr") {
            ParleyListItem(
                modifier = Modifier.clickable { showQr = true },
                leadingContent = { Icon(Icons.Rounded.QrCode2, null) },
                headlineContent = { Text(stringResource(R.string.shl_invite_qr)) },
                supportingContent = { Text(stringResource(R.string.shl_invite_qr_sub)) },
                colors = rowColors(),
            )
        }
        item("file") {
            ParleyListItem(
                modifier = Modifier.clickable { askFilePass = true },
                leadingContent = { Icon(Icons.Rounded.Share, null) },
                headlineContent = { Text(stringResource(R.string.shl_invite_file)) },
                supportingContent = { Text(stringResource(R.string.shl_invite_file_sub)) },
                colors = rowColors(),
            )
        }
    }
    if (showQr) InviteQrDialog(vm, id) { showQr = false }
    if (askFilePass) {
        PassphraseDialog(stringResource(R.string.shl_invite_file), stringResource(R.string.shl_invite_file_pass), onDismiss = { askFilePass = false }) { p ->
            askFilePass = false
            filePass = p
            saver.launch(title.filter { it.isLetterOrDigit() || it == ' ' }.trim().ifEmpty { "label" } + SharedLabelInvites.FILE_EXTENSION)
        }
    }
}

private fun inviteFileText(r: SharedLabels.InviteFile): Int = when (r) {
    SharedLabels.InviteFile.READY -> R.string.shl_invite_file_saved
    SharedLabels.InviteFile.WRONG_PASSPHRASE -> R.string.shl_invite_wrong_pass
    SharedLabels.InviteFile.FAILED -> R.string.shl_invite_failed
}

/** "Leave this label", after a question. */
@Composable
private fun LeaveButton(vm: AppViewModel, id: String, title: String, back: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    OutlinedButton({ leaving = true }, Modifier.padding(horizontal = Spacing.l)) {
        Icon(Icons.AutoMirrored.Rounded.Logout, null, Modifier.size(18.dp))
        Text("  " + stringResource(R.string.shl_leave))
    }
    if (leaving) {
        ConfirmDialog(
            title = stringResource(R.string.shl_leave_title, title),
            text = stringResource(R.string.shl_leave_text),
            confirmLabel = stringResource(R.string.shl_leave),
            destructive = true,
            onConfirm = {
                leaving = false
                scope.launch {
                    vm.c.sharedLabels.leave(id)
                    FolderSyncWorker.reschedule(context)
                    vm.toast(res.getString(R.string.shl_left, title))
                    back()
                }
            },
            onDismiss = { leaving = false },
        )
    }
}

@Composable
private fun MemberRow(m: LabelMember, me: String?, all: List<LabelMember>, canRemove: Boolean, onRemove: () -> Unit) {
    val name = if (m.keyHex == me) stringResource(R.string.shl_member_you, m.name) else m.name
    val role = when {
        m.awaitingKey -> stringResource(R.string.shl_member_needs_invite)
        m.anchor -> stringResource(R.string.shl_member_started)
        m.invitedBy != null -> all.firstOrNull { it.keyHex == m.invitedBy }?.let { by ->
            stringResource(R.string.shl_member_invited_by, if (by.keyHex == me) stringResource(R.string.shl_you) else by.name)
        }
        else -> null
    }
    ParleyListItem(
        leadingContent = { Icon(Icons.Rounded.Person, null) },
        headlineContent = { Text(name) },
        supportingContent = {
            Column {
                role?.let { Text(it) }
                Text(
                    stringResource(R.string.shl_fingerprint, Bidi.ltr(m.fingerprint)),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        trailingContent = {
            if (canRemove) {
                val desc = stringResource(R.string.shl_remove_desc, m.name)
                TextButton(onRemove, modifier = Modifier.semanticsLabel(desc)) { Text(stringResource(R.string.shl_remove)) }
            }
        },
        colors = rowColors(),
    )
}

private fun Modifier.semanticsLabel(text: String) = semantics { contentDescription = text }

/** A new key for the label: typed twice, then everyone but [m] needs a new invitation. */
@Composable
private fun RemoveMemberDialog(vm: AppViewModel, id: String, m: LabelMember, onDone: () -> Unit) {
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    ConfirmDialog(
        title = stringResource(R.string.shl_remove_title, m.name),
        text = stringResource(R.string.shl_remove_text, m.name),
        confirmLabel = stringResource(R.string.shl_remove),
        destructive = true,
        confirmEnabled = !busy && passReady(a, b),
        onConfirm = {
            busy = true
            scope.launch {
                val ok = vm.c.sharedLabels.removeMembers(id, setOf(m.keyHex), a.toCharArray())
                vm.toast(res.getString(if (ok) R.string.shl_remove_done else R.string.shl_remove_failed, m.name))
                onDone()
            }
        },
        onDismiss = onDone,
        content = {
            Column {
                NewPassphrase(stringResource(R.string.shl_remove_new_pass), a, b, { a = it }, { b = it })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Spacing.s))
            }
        },
    )
}

/** Asks for the label's passphrase (for an invitation file). */
@Composable
private fun PassphraseDialog(title: String, text: String, onDismiss: () -> Unit, onDone: (CharArray) -> Unit) {
    var p by remember { mutableStateOf("") }
    ConfirmDialog(
        title = title,
        text = text,
        confirmLabel = stringResource(R.string.main_ok),
        confirmEnabled = p.isNotEmpty(),
        onConfirm = { onDone(p.toCharArray()) },
        onDismiss = onDismiss,
        content = { PassField(stringResource(R.string.shl_join_pass), p) { p = it } },
    )
}

/** The invitation as a `parley://label` QR code, with a one-time code to read out. */
@Composable
private fun InviteQrDialog(vm: AppViewModel, id: String, onDismiss: () -> Unit) {
    val res = LocalResources.current
    val passcode = remember { SecureQr.newPasscode() }
    val bitmap by produceState<Bitmap?>(null, id) {
        val link = vm.c.sharedLabels.inviteLink(id, passcode)
        if (link == null) {
            vm.toast(res.getString(R.string.shl_invite_failed))
            onDismiss()
        } else {
            value = withContext(Dispatchers.Default) { SecureQr.qr(link) }
        }
    }
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shl_invite_qr)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                bitmap?.let {
                    Image(it.asImageBitmap(), stringResource(R.string.shl_invite_qr_desc), Modifier.size(260.dp).background(Color.White).padding(8.dp))
                }
                    ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.shl_invite_code), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                Text(Bidi.ltr(passcode), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.shl_invite_qr_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_done)) } },
    )
}

// ---------------------------------------------------------------- Settings › Shared labels

/** Every shared label on this phone, and joining one from a file or a QR code. */
@Composable
fun SharedLabelsScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit) {
    val res = LocalResources.current
    val shared = vm.c.sharedLabels
    LaunchedEffect(Unit) { shared.load() }
    val states by shared.states.collectAsStateWithLifecycle()
    val loader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            SharedLabelInbox.link.value = null
            SharedLabelInbox.file.value = uri
            open(SharedLabelRoutes.Join)
        }
    }
    SettingsScaffold(stringResource(R.string.set_shared_labels_title), back) {
        Text(
            stringResource(R.string.set_shared_labels_summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl),
        )
        if (states.isEmpty()) {
            Text(stringResource(R.string.shl_list_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Spacing.xl))
        } else {
            SegmentedGroup {
                states.forEach { s ->
                    item(s.labelId) {
                        ParleyListItem(
                            modifier = Modifier.clickable { open(SharedLabelRoutes.Manage(s.labelId)) },
                            leadingContent = { Icon(Icons.AutoMirrored.Rounded.Label, null) },
                            headlineContent = { Text(s.title) },
                            supportingContent = { Text(SharedLabelTexts.problem(res, s) ?: SharedLabelTexts.status(res, s)) },
                            colors = rowColors(),
                        )
                    }
                }
            }
        }
        SegmentedGroup(stringResource(R.string.set_shared_labels_join_title)) {
            item("file") {
                ParleyListItem(
                    modifier = Modifier.clickable { loader.launch(arrayOf("*/*")) },
                    leadingContent = { Icon(Icons.Rounded.FileUpload, null) },
                    headlineContent = { Text(stringResource(R.string.shl_join_file)) },
                    supportingContent = { Text(stringResource(R.string.shl_join_file_sub)) },
                    colors = rowColors(),
                )
            }
            item("qr") {
                ParleyListItem(
                    modifier = Modifier.clickable { open(QrRoutes.Scan) },
                    leadingContent = { Icon(Icons.Rounded.QrCodeScanner, null) },
                    headlineContent = { Text(stringResource(R.string.shl_join_scan)) },
                    supportingContent = { Text(stringResource(R.string.shl_join_scan_sub)) },
                    colors = rowColors(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Joining

/**
 * Opening an invitation from [SharedLabelInbox]: its code or passphrase, then who invited you (with their key's
 * fingerprint) and the folder hint, then the folder, the members found there, your name, and Join.
 */
@Composable
fun JoinSharedLabelScreen(vm: AppViewModel, back: () -> Unit) {
    val link by SharedLabelInbox.link.collectAsStateWithLifecycle()
    val file by SharedLabelInbox.file.collectAsStateWithLifecycle()
    // The opened invitation holds the label's key: kept only while this screen lives, never in saved state.
    var invitation by remember { mutableStateOf<Invitation?>(null) }
    LaunchedEffect(link, file) { if (link == null && file == null && invitation == null) back() }
    fun done() {
        SharedLabelInbox.link.value = null
        SharedLabelInbox.file.value = null
        back()
    }
    SettingsScaffold(stringResource(R.string.shl_join_title), ::done) {
        val i = invitation
        if (i == null) OpenInvitation(link, file) { invitation = it } else JoinInvitation(vm, i, onJoined = { invitation = null; done() })
    }
}

/** The code under the QR code, or the label's passphrase for a file. */
@Composable
private fun OpenInvitation(link: String?, file: Uri?, onOpened: (Invitation) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    // A code or passphrase is never put in saved state.
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val qr = link != null
    Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(stringResource(if (qr) R.string.shl_join_passcode else R.string.shl_join_passphrase))
        OutlinedTextField(
            code, { code = it; error = null }, singleLine = true, isError = error != null,
            label = { Text(stringResource(if (qr) R.string.shl_invite_code else R.string.shl_join_pass)) },
            supportingText = error?.let { e -> { Text(e) } },
            visualTransformation = if (qr) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(capitalization = if (qr) KeyboardCapitalization.Characters else KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth(),
        )
        Button({
            busy = true
            scope.launch {
                val opened = withContext(Dispatchers.IO) {
                    runCatching {
                        if (link != null) {
                            SharedLabelInvites.fromLink(link, code)
                        } else {
                            val bytes = context.contentResolver.openInputStream(file!!)!!
                                .use { Bounded.readBytes(it, Bounded.Caps.SETUP_FILE, "invitation") }
                            SharedLabelInvites.fromFile(bytes, code.toCharArray())
                        }
                    }.getOrNull()
                }
                busy = false
                if (opened == null) error = res.getString(R.string.shl_join_wrong) else onOpened(opened)
            }
        }, enabled = code.isNotEmpty() && !busy) { Text(stringResource(R.string.shl_join_open)) }
    }
}

/** Who invited you and their key, the folder, the members found there, your name, and Join. */
@Composable
private fun JoinInvitation(vm: AppViewModel, i: Invitation, onJoined: () -> Unit) {
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<SharedLabelEngine.Preview?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            folder = uri
            preview = null
            scope.launch { preview = vm.c.sharedLabels.preview(i, uri) }
        }
    }
    Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(stringResource(R.string.shl_join_invited, i.inviterName, i.title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.shl_join_key, Bidi.ltr(i.inviterFingerprint)), style = MaterialTheme.typography.bodyMedium)
        if (i.folderHint.isNotBlank()) Text(stringResource(R.string.shl_join_folder_hint, i.folderHint), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton({ picker.launch(null) }) {
            Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp))
            Text("  " + (folder?.let { folderName(it) } ?: stringResource(R.string.shl_join_pick)))
        }
    }
    val f = folder ?: return
    when (val p = preview) {
        null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Spacing.l))
        SharedLabelEngine.Preview.WrongFolder -> Banner(stringResource(R.string.shl_join_wrong_folder), tone = BannerTone.WARNING)
        SharedLabelEngine.Preview.OldInvitation -> Banner(stringResource(R.string.shl_join_old), tone = BannerTone.WARNING)
        is SharedLabelEngine.Preview.Unavailable -> Banner(stringResource(R.string.shl_err_folder), tone = BannerTone.WARNING)
        is SharedLabelEngine.Preview.Ready -> JoinAs(vm, i, f, p.members, onJoined)
    }
}

/** The members found in the folder (with their keys), your name, and Join. */
@Composable
private fun JoinAs(vm: AppViewModel, i: Invitation, folder: Uri, members: List<LabelMember>, onJoined: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    SegmentedGroup(stringResource(R.string.shl_members)) {
        members.forEach { m -> item(m.keyHex) { MemberRow(m, null, members, canRemove = false) {} } }
    }
    Column(Modifier.padding(horizontal = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        OutlinedTextField(
            name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.shl_your_name)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth(),
        )
        Button({
            busy = true
            scope.launch {
                val ok = vm.c.sharedLabels.join(i, folder, folderName(folder), name.trim())
                busy = false
                if (ok) {
                    FolderSyncWorker.reschedule(context)
                    vm.toast(res.getString(R.string.shl_join_done, i.title))
                    onJoined()
                } else {
                    vm.toast(res.getString(R.string.shl_join_failed))
                }
            }
        }, enabled = name.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.shl_join_button)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
