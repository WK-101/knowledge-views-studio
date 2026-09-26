package app.parley.ui.extras

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.TextSearch
import app.parley.common.extras.SimpleConfig
import app.parley.common.extras.SimplePerson
import app.parley.common.extras.SimpleSetup
import app.parley.ui.Avatar
import app.parley.ui.Bidi
import app.parley.ui.Routes
import app.parley.ui.SegmentedGroup
import app.parley.ui.contact.SecureQr
import app.parley.ui.settings.SettingsScaffold
import app.parley.ui.settings.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val clearRow @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

/**
 * X4: Settings › Appearance › Simple mode. Choose up to nine people, the options for the incoming screen, and turn
 * it on here or hand the setup to another phone as an encrypted file or QR code (and import one).
 */
@Composable
fun SimpleSetupScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val store = vm.c.extras
    val cfg by store.simple.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val resolved = remember(cfg, contacts) { SimpleSetup.resolve(cfg.people, contacts.orEmpty()) }
    var picking by remember { mutableStateOf(false) }
    var askFilePass by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var filePass by remember { mutableStateOf<CharArray?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pass = filePass
        filePass = null
        if (uri != null && pass != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(SimpleTransfer.encryptFile(cfg, pass)) } }.isSuccess
            }
            pass.fill(' ')
            vm.toast(res.getString(if (ok) R.string.x_simple_file_saved else R.string.x_simple_file_failed))
        }
    }
    val loader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            SimpleInbox.file.value = uri
            open(ExtrasRoutes.SIMPLE_IMPORT)
        }
    }

    SettingsScaffold(stringResource(R.string.x_set_simple_title), back) {
        Text(
            stringResource(R.string.x_simple_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        SegmentedGroup(pluralStringResource(R.plurals.x_simple_people_n, cfg.people.size, cfg.people.size, SimpleConfig.MAX_PEOPLE)) {
            resolved.forEachIndexed { i, r ->
                item("p$i") {
                    ListItem(
                        colors = clearRow,
                        leadingContent = { Avatar(r.person.name, r.contact?.photoUri, 40.dp) },
                        headlineContent = { Text(r.person.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(Bidi.ltr(r.person.number)) },
                        trailingContent = {
                            IconButton({ store.updateSimple { c -> c.copy(people = c.people.filterIndexed { j, _ -> j != i }) } }) {
                                Icon(Icons.Rounded.RemoveCircle, stringResource(R.string.x_simple_remove, r.person.name), tint = MaterialTheme.colorScheme.error)
                            }
                        },
                    )
                }
            }
            if (cfg.people.size < SimpleConfig.MAX_PEOPLE) item("add") {
                ListItem(
                    modifier = Modifier.clickable { picking = true },
                    colors = clearRow,
                    leadingContent = { Icon(Icons.Rounded.PersonAdd, null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(stringResource(R.string.x_simple_add)) },
                )
            }
        }
        SegmentedGroup(stringResource(R.string.x_simple_options)) {
            item("keypad") { SwitchRow(stringResource(R.string.x_simple_keypad), stringResource(R.string.x_simple_keypad_body), cfg.showKeypad, Icons.Rounded.Dialpad) { v -> store.updateSimple { it.copy(showKeypad = v) } } }
            item("decline") { SwitchRow(stringResource(R.string.x_simple_confirm_decline), stringResource(R.string.x_simple_confirm_decline_body), cfg.confirmDecline, Icons.Rounded.HelpOutline) { v -> store.updateSimple { it.copy(confirmDecline = v) } } }
            item("speak") { SwitchRow(stringResource(R.string.x_simple_speak), stringResource(R.string.x_simple_speak_body), cfg.speakName, Icons.Rounded.RecordVoiceOver) { v -> store.updateSimple { it.copy(speakName = v) } } }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ store.updateSimple { it.copy(enabled = true) } }, enabled = cfg.people.isNotEmpty() || cfg.showKeypad, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.x_simple_turn_on))
            }
            Text(stringResource(R.string.x_simple_exit_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SegmentedGroup(stringResource(R.string.x_simple_share)) {
            item("file") {
                ListItem(
                    modifier = Modifier.clickable(enabled = cfg.people.isNotEmpty()) { askFilePass = true }, colors = clearRow,
                    leadingContent = { Icon(Icons.Rounded.FileDownload, null) },
                    headlineContent = { Text(stringResource(R.string.x_simple_save_file)) },
                    supportingContent = { Text(stringResource(R.string.x_simple_save_file_body)) },
                )
            }
            item("qr") {
                ListItem(
                    modifier = Modifier.clickable(enabled = cfg.people.isNotEmpty()) { showQr = true }, colors = clearRow,
                    leadingContent = { Icon(Icons.Rounded.QrCode2, null) },
                    headlineContent = { Text(stringResource(R.string.x_simple_show_qr)) },
                    supportingContent = { Text(stringResource(R.string.x_simple_show_qr_body)) },
                )
            }
            item("import") {
                ListItem(
                    modifier = Modifier.clickable { loader.launch(arrayOf("*/*")) }, colors = clearRow,
                    leadingContent = { Icon(Icons.Rounded.FileUpload, null) },
                    headlineContent = { Text(stringResource(R.string.x_simple_import_file)) },
                    supportingContent = { Text(stringResource(R.string.x_simple_import_file_body)) },
                )
            }
        }
    }

    if (picking) SimplePersonPicker(contacts.orEmpty(), taken = cfg.people.map { it.number }.toSet(), onDismiss = { picking = false }) { c, number ->
        picking = false
        store.updateSimple { it.copy(people = it.people + SimplePerson(c.displayName, number, c.lookupKey)) }
    }
    if (askFilePass) PassphraseDialog(
        title = stringResource(R.string.x_simple_save_file), confirm = true, onDismiss = { askFilePass = false },
    ) { pass ->
        askFilePass = false
        filePass = pass
        saver.launch("parley-simple-mode.parleysimple")
    }
    if (showQr) SimpleQrDialog(cfg) { showQr = false }
}

/** Contacts with a number, searchable; a contact with several numbers asks which one. */
@Composable
private fun SimplePersonPicker(contacts: List<ContactSummary>, taken: Set<String>, onDismiss: () -> Unit, onPick: (ContactSummary, String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var numbersOf by remember { mutableStateOf<ContactSummary?>(null) }
    val shown = remember(q, contacts) { contacts.filter { c -> c.phones.any { SimpleSetup.dialable(it.number) != null } && TextSearch.matches(q, c.displayName, c.phones.map { p -> p.number }) }.take(200) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.x_simple_add)) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text(stringResource(R.string.home_search_contacts)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.id }) { c ->
                        ListItem(
                            modifier = Modifier.clickable {
                                // X4: only plain numbers can go on a tile (no codes, pauses or extensions).
                                val numbers = c.phones.map { it.number }.filter { SimpleSetup.dialable(it) != null }.distinct()
                                if (numbers.size == 1) onPick(c, numbers.first()) else numbersOf = c
                            },
                            colors = clearRow,
                            leadingContent = { Avatar(c.displayName, c.photoUri, 36.dp) },
                            headlineContent = { Text(c.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    )
    numbersOf?.let { c ->
        AlertDialog(
            onDismissRequest = { numbersOf = null },
            title = { Text(c.displayName) },
            text = {
                Column {
                    c.phones.map { it.number }.filter { SimpleSetup.dialable(it) != null }.distinct().forEach { n ->
                        ListItem(
                            modifier = Modifier.clickable { numbersOf = null; onPick(c, n) }, colors = clearRow,
                            headlineContent = { Text(Bidi.ltr(n)) },
                            trailingContent = { if (SimpleSetup.dialable(n) in taken) Icon(Icons.Rounded.CheckCircle, null) },
                        )
                    }
                }
            },
            confirmButton = { TextButton({ numbersOf = null }) { Text(stringResource(R.string.dc_cancel)) } },
        )
    }
}

/** Asks for a passphrase ([confirm]: typed twice, at least 8 characters). */
@Composable
private fun PassphraseDialog(title: String, confirm: Boolean, onDismiss: () -> Unit, onDone: (CharArray) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val ok = if (confirm) a.length >= 8 && a == b else a.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (confirm) R.string.x_simple_pass_new else R.string.x_simple_pass_enter))
                OutlinedTextField(
                    a, { a = it }, singleLine = true, label = { Text(stringResource(R.string.x_simple_pass)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (confirm) OutlinedTextField(
                    b, { b = it }, singleLine = true, label = { Text(stringResource(R.string.x_simple_pass_again)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = b.isNotEmpty() && a != b,
                )
            }
        },
        confirmButton = { TextButton({ onDone(a.toCharArray()) }, enabled = ok) { Text(stringResource(R.string.main_ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    )
}

/** The setup as a `parley://simple` QR code with a one-time passcode to read out. */
@Composable
private fun SimpleQrDialog(cfg: SimpleConfig, onDismiss: () -> Unit) {
    val passcode = remember { SecureQr.newPasscode() }
    val bitmap by produceState<Bitmap?>(null, cfg) { value = withContext(Dispatchers.Default) { SecureQr.qr(SimpleTransfer.qrLink(cfg, passcode)) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.x_simple_show_qr)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.x_simple_qr_desc), Modifier.size(260.dp).background(Color.White).padding(8.dp)) }
                Text(stringResource(R.string.sqr_passcode), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                Text(Bidi.ltr(passcode), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.x_simple_qr_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_done)) } },
    )
}

/**
 * X4: importing a setup (from a file or a scanned QR code): unlock it, see who it found in this phone's contacts,
 * create the missing ones, then use it and turn simple mode on.
 */
@Composable
fun SimpleImportScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val qr by SimpleInbox.qr.collectAsStateWithLifecycle()
    val file by SimpleInbox.file.collectAsStateWithLifecycle()
    val source = qr ?: file
    var imported by remember { mutableStateOf<SimpleSetup.Imported?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    LaunchedEffect(source) { if (source == null && imported == null) back() }
    SettingsScaffold(stringResource(R.string.x_simple_import_title), back) {
        val cfg = imported?.config
        if (cfg == null) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var code by remember { mutableStateOf("") }
                Text(stringResource(if (qr != null) R.string.x_simple_enter_passcode else R.string.x_simple_pass_enter))
                OutlinedTextField(
                    code, { code = it; error = null }, singleLine = true, isError = error != null,
                    label = { Text(stringResource(if (qr != null) R.string.sqr_passcode else R.string.x_simple_pass)) },
                    supportingText = error?.let { e -> { Text(e) } },
                    visualTransformation = if (qr != null) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(capitalization = if (qr != null) KeyboardCapitalization.Characters else KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button({
                    scope.launch {
                        val q = qr
                        val f = file
                        imported = withContext(Dispatchers.IO) {
                            runCatching {
                                if (q != null) SimpleTransfer.fromQr(q, code)
                                else SimpleTransfer.decryptFile(context.contentResolver.openInputStream(f!!)!!.use { it.readBytes() }, code.toCharArray())
                            }.getOrNull()
                        }
                        if (imported == null) error = res.getString(R.string.x_simple_wrong_pass)
                    }
                }, enabled = code.isNotEmpty()) { Text(stringResource(R.string.msg_open)) }
            }
        } else {
            val resolved = SimpleSetup.resolve(cfg.people, contacts.orEmpty())
            val skipped = imported?.skipped ?: 0
            // X4: people whose "number" was a code, a pause or not a phone number at all were left out; say so.
            if (skipped > 0) Text(
                pluralStringResource(R.plurals.x_simple_skipped, skipped, skipped), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            SegmentedGroup(pluralStringResource(R.plurals.x_simple_people_n, cfg.people.size, cfg.people.size, SimpleConfig.MAX_PEOPLE)) {
                resolved.forEachIndexed { i, r ->
                    item("r$i") {
                        ListItem(
                            colors = clearRow,
                            leadingContent = { Avatar(r.person.name, r.contact?.photoUri, 40.dp) },
                            headlineContent = { Text(r.person.name) },
                            // X4: the number this tile will call is always shown; a contact is "found" only when it has that number.
                            supportingContent = {
                                Column {
                                    Text(Bidi.ltr(r.person.number))
                                    Text(r.contact?.let { stringResource(R.string.x_simple_matched, it.displayName) } ?: stringResource(R.string.x_simple_not_in_contacts))
                                }
                            },
                            trailingContent = {
                                if (r.contact == null) TextButton({ open(Routes.edit(name = r.person.name, phone = r.person.number)) }) { Text(stringResource(R.string.x_simple_create)) }
                                else Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            },
                        )
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.x_simple_import_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button({
                    // Keys of the contacts found here, so the tiles follow renames on this phone.
                    val people = SimpleSetup.resolve(cfg.people, vm.contacts.value.orEmpty()).map { r -> r.person.copy(lookupKey = r.contact?.lookupKey) }
                    vm.c.extras.updateSimple { cfg.copy(people = people, enabled = true) }
                    SimpleInbox.qr.value = null
                    SimpleInbox.file.value = null
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.x_simple_use_on)) }
                OutlinedButton({
                    val people = SimpleSetup.resolve(cfg.people, vm.contacts.value.orEmpty()).map { r -> r.person.copy(lookupKey = r.contact?.lookupKey) }
                    vm.c.extras.updateSimple { cfg.copy(people = people, enabled = false) }
                    vm.toast(res.getString(R.string.x_simple_saved))
                    SimpleInbox.qr.value = null
                    SimpleInbox.file.value = null
                    back()
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.x_simple_use_later)) }
            }
        }
    }
}
