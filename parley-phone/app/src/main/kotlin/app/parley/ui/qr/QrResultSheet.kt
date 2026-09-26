package app.parley.ui.qr

import android.content.res.Resources
import android.provider.ContactsContract
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.R
import app.parley.common.MessengerLinks
import app.parley.common.StartTab
import app.parley.common.qr.ContactFormat
import app.parley.common.qr.LinkKind
import app.parley.common.qr.ParleyKind
import app.parley.common.qr.QrApp
import app.parley.common.qr.QrPayload
import app.parley.common.qr.QrText
import app.parley.common.qr.ScannedCard
import app.parley.common.qr.UrlSafety
import app.parley.common.qr.WifiSecurity
import app.parley.common.record.ContactRecord
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.RecordDetails
import app.parley.messaging.MessageOnSheet
import app.parley.messaging.MessengerLauncher
import app.parley.security.launchVault
import app.parley.ui.Bidi
import app.parley.ui.Routes
import kotlinx.coroutines.launch
import java.io.File

/** Q4: plain words and icons for what a code holds. */
object QrLabels {
    fun icon(p: QrPayload): ImageVector = when (p) {
        is QrPayload.Contact -> if (p.records.size > 1) Icons.Rounded.Groups else Icons.Rounded.Person
        is QrPayload.Parley -> Icons.Rounded.QrCode2
        is QrPayload.Phone -> Icons.Rounded.Call
        is QrPayload.Sms -> Icons.AutoMirrored.Rounded.Message
        is QrPayload.Email -> Icons.Rounded.Email
        is QrPayload.Geo -> Icons.Rounded.Place
        is QrPayload.Wifi -> Icons.Rounded.Wifi
        is QrPayload.Event -> Icons.Rounded.Event
        is QrPayload.Messenger -> Icons.AutoMirrored.Rounded.Chat
        is QrPayload.Url -> Icons.Rounded.Link
        is QrPayload.Text -> Icons.AutoMirrored.Rounded.Notes
    }

    fun kind(res: Resources, p: QrPayload): String = when (p) {
        is QrPayload.Contact -> if (p.records.size > 1) res.getQuantityString(R.plurals.qs_kind_contacts, p.records.size, p.records.size) else res.getString(R.string.qs_kind_contact)
        is QrPayload.Parley -> res.getString(
            when (p.kind) {
                ParleyKind.CONTACT -> R.string.qs_kind_parley_contact
                ParleyKind.SIMPLE -> R.string.qs_kind_parley_simple
                ParleyKind.TEMPLATE -> R.string.qs_kind_parley_template
            },
        )
        is QrPayload.Phone -> res.getString(if (p.isMmi) R.string.qs_kind_code else R.string.qs_kind_phone)
        is QrPayload.Sms -> res.getString(R.string.qs_kind_sms)
        is QrPayload.Email -> res.getString(R.string.qs_kind_email)
        is QrPayload.Geo -> res.getString(R.string.qs_kind_geo)
        is QrPayload.Wifi -> res.getString(R.string.qs_kind_wifi)
        is QrPayload.Event -> res.getString(R.string.qs_kind_event)
        is QrPayload.Messenger -> res.getString(
            when (p.kind) {
                LinkKind.PHONE -> R.string.qs_link_phone
                LinkKind.PROFILE -> R.string.qs_link_profile
                LinkKind.ID -> R.string.qs_link_id
                LinkKind.GROUP -> R.string.qs_link_group
                LinkKind.CHANNEL -> R.string.qs_link_channel
                LinkKind.INVITE -> R.string.qs_link_invite
                LinkKind.LINK -> R.string.qs_link_link
            },
            p.app.label,
        )
        is QrPayload.Url -> res.getString(R.string.qs_kind_url)
        is QrPayload.Text -> res.getString(R.string.qs_kind_text)
    }
}

/**
 * Q4: the result sheet. It says what the code holds in plain words, shows it in full (control and bidi-override
 * characters removed) and offers actions; nothing happens until a button is tapped. Every result can be copied and
 * shared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrResultSheet(vm: AppViewModel, payload: QrPayload, onDismiss: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(QrLabels.icon(payload), null, tint = MaterialTheme.colorScheme.primary)
                Text(QrLabels.kind(res, payload), style = MaterialTheme.typography.titleLarge)
            }
            if (QrText.hasHidden(payload.raw)) Note(stringResource(R.string.qs_hidden_removed), Icons.Rounded.Info)
            when (payload) {
                is QrPayload.Contact -> ContactResult(vm, payload, onDismiss, open)
                is QrPayload.Parley -> ParleyResult(vm, payload, onDismiss)
                is QrPayload.Phone -> PhoneResult(vm, payload, onDismiss)
                is QrPayload.Sms -> SmsResult(vm, payload, onDismiss)
                is QrPayload.Email -> EmailResult(vm, payload, onDismiss)
                is QrPayload.Geo -> GeoResult(payload)
                is QrPayload.Wifi -> WifiResult(payload)
                is QrPayload.Event -> EventResult(payload)
                is QrPayload.Messenger -> MessengerResult(vm, payload, onDismiss)
                is QrPayload.Url -> UrlResult(payload)
                is QrPayload.Text -> TextResult(payload)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Q5: a Wi-Fi code's text holds its password: kept out of clipboard previews like "Copy password".
                TextButton({ QrActions.copy(context, payload.raw, sensitive = payload is QrPayload.Wifi && !payload.password.isNullOrEmpty()) }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                    Text("  " + stringResource(R.string.qs_copy_text))
                }
                TextButton({ QrActions.share(context, payload.raw) }) {
                    Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                    Text("  " + stringResource(R.string.qs_share))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun Action(text: String, icon: ImageVector, primary: Boolean = false, onClick: () -> Unit) {
    val m = Modifier.fillMaxWidth().padding(top = 8.dp)
    val content: @Composable () -> Unit = {
        Icon(icon, null, Modifier.size(18.dp))
        Text("  $text", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (primary) Button(onClick, m) { content() } else FilledTonalButton(onClick, m) { content() }
}

@Composable
private fun Field(label: String, value: String, ltr: Boolean = false, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val shown = QrText.shown(value)
        SelectionContainer {
            Text(if (ltr) Bidi.ltr(shown) else shown, style = MaterialTheme.typography.bodyLarge, fontFamily = if (mono) FontFamily.Monospace else null)
        }
    }
}

@Composable
private fun Note(text: String, icon: ImageVector = Icons.Rounded.Info, warning: Boolean = false) {
    Card(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------------------------------------------------------------- contacts

/** Writes [vcard] to the share folder so the importer can read it (all cards, every field). */
private fun importVcard(context: android.content.Context, vm: AppViewModel, vcard: String) {
    runCatching {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "scanned-contacts.vcf")
        f.writeText(vcard)
        vm.navigate(NavEvent.ImportVcf(FileProvider.getUriForFile(context, context.packageName + ".files", f)))
    }.onFailure { vm.toast(context.getString(R.string.qs_import_failed)) }
}

@Composable
private fun ColumnScope.ContactResult(vm: AppViewModel, p: QrPayload.Contact, onDismiss: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableIntStateOf(if (p.records.size == 1) 0 else -1) }
    if (p.format != ContactFormat.VCARD) {
        Text(
            stringResource(if (p.format == ContactFormat.MECARD) R.string.qs_format_mecard else R.string.qs_format_bizcard),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Q4: favourite, voicemail, ringtone and labels from a stranger's card stay off unless ticked here.
    val asks = remember(p) { ScannedCard.flags(p.records) }
    var allowed by remember(p) { mutableStateOf(emptySet<ScannedCard.Flag>()) }
    if (asks.isNotEmpty()) CardAsks(asks, remember(p) { ScannedCard.labels(p.records) }, allowed) { allowed = it }
    val vcard = remember(p, allowed) { ScannedCard.vcardToImport(p.records, p.vcard, allowed) }
    val record = p.records.getOrNull(selected)?.let { remember(it, allowed) { ScannedCard.strip(it, allowed) } }
    if (record == null) {
        // Several cards: pick one, or import them all.
        Text(stringResource(R.string.qs_several_contacts), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        p.records.forEachIndexed { i, r ->
            val d = remember(r) { RecordDetails.toDetails(r) }
            Row(
                Modifier.fillMaxWidth().clickable { selected = i }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column {
                    Text(QrText.shown(nameOf(r, d), 80, false).ifBlank { stringResource(R.string.qs_no_name) }, style = MaterialTheme.typography.bodyLarge)
                    d.phones.firstOrNull()?.let { Text(Bidi.ltr(QrText.shown(it.value, 40, false)), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Action(pluralStringResource(R.plurals.qs_import_all, p.records.size, p.records.size), Icons.Rounded.FileDownload, primary = true) {
            onDismiss()
            importVcard(context, vm, vcard)
        }
        return
    }
    if (p.records.size > 1) TextButton({ selected = -1 }) { Text(stringResource(R.string.qs_back_to_list)) }
    ContactCard(vm, record, onDismiss, open, allCardsVcard = if (p.records.size == 1) vcard else null)
}

/** Q4: "This card also asks to: …", each off until ticked. */
@Composable
private fun CardAsks(asks: Set<ScannedCard.Flag>, labels: List<String>, allowed: Set<ScannedCard.Flag>, onChange: (Set<ScannedCard.Flag>) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(stringResource(R.string.qs_card_asks), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            ScannedCard.Flag.entries.filter { it in asks }.forEach { f ->
                val text = when (f) {
                    ScannedCard.Flag.STARRED -> stringResource(R.string.qs_card_ask_star)
                    ScannedCard.Flag.VOICEMAIL -> stringResource(R.string.qs_card_ask_voicemail)
                    ScannedCard.Flag.RINGTONE -> stringResource(R.string.qs_card_ask_ringtone)
                    ScannedCard.Flag.LABELS -> stringResource(R.string.qs_card_ask_labels, QrText.shown(labels.joinToString(", "), 200, false))
                }
                val on = f in allowed
                Row(
                    Modifier.fillMaxWidth().toggleable(on, role = androidx.compose.ui.semantics.Role.Checkbox) { onChange(if (it) allowed + f else allowed - f) }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(on, null)
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                stringResource(R.string.qs_card_asks_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

private fun nameOf(r: ContactRecord, d: ContactDetails): String = r.displayName.ifBlank { d.composedName.ifBlank { d.company } }

@Composable
private fun ColumnScope.ContactCard(vm: AppViewModel, record: ContactRecord, onDismiss: () -> Unit, open: (String) -> Unit, allCardsVcard: String?) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val details = remember(record) { RecordDetails.toDetails(record) }
    val hidden = remember(record) { RecordDetails.hasHiddenFields(record) }
    var temporary by remember { mutableStateOf(false) }
    val name = nameOf(record, details)
    Card(Modifier.fillMaxWidth().padding(top = 12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp)) {
            Text(QrText.shown(name, 120, false).ifBlank { stringResource(R.string.qs_no_name) }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
            listOf(details.title, details.company).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                Text(QrText.shown(it, 200, false), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            details.phones.forEach { Field(stringResource(R.string.qs_field_phone), it.value, ltr = true) }
            details.emails.forEach { Field(stringResource(R.string.qs_field_email), it.value) }
            details.addresses.forEach { Field(stringResource(R.string.qs_field_address), it.formatted) }
            details.websites.forEach { Field(stringResource(R.string.qs_field_website), it.value) }
            details.handles.forEach { Field(it.service.label, it.value) }
            details.events.forEach { Field(stringResource(R.string.qs_field_date), it.date, ltr = true) }
            if (details.note.isNotBlank()) Field(stringResource(R.string.qs_field_note), details.note)
        }
    }
    Action(stringResource(R.string.qs_add_contact), Icons.Rounded.PersonAdd, primary = true) {
        // The editor shows the duplicate warning when the number or name is already saved.
        onDismiss()
        vm.navigate(NavEvent.NewContact(details))
    }
    Action(stringResource(R.string.qs_add_private), Icons.Rounded.Lock) {
        scope.launchVault(context as? androidx.fragment.app.FragmentActivity, { e -> vm.toast(res.getString(R.string.edit_save_failed, e.message.orEmpty())) }) {
            val id = vm.c.vault.save(null, details)
            vm.toast(res.getString(R.string.sqr_saved_private))
            onDismiss()
            vm.navigate(NavEvent.Vault(id))
        }
    }
    details.phones.firstOrNull()?.value?.let { number ->
        Action(stringResource(R.string.qs_add_temporary), Icons.Rounded.AutoDelete) { temporary = true }
        if (temporary) {
            app.parley.ui.temporary.SaveTemporaryDialog(
                number = Bidi.ltr(number), suggestedName = QrText.shown(name, 80, false).ifBlank { number },
                onDismiss = { temporary = false },
            ) { n, days, deleteHistory, visible ->
                temporary = false
                scope.launch {
                    val saved = app.parley.ui.temporary.TemporaryContactActions.save(vm, number, n, days, deleteHistory, visible)
                    if (saved != null) {
                        vm.toast(res.getQuantityString(if (saved.private) R.plurals.caller_saved_private_days else R.plurals.caller_saved_days, days, days))
                        onDismiss()
                    } else {
                        vm.toast(res.getString(R.string.keypad_save_failed))
                    }
                }
            }
        }
    }
    Action(stringResource(R.string.qs_update_existing), Icons.Rounded.Edit) {
        onDismiss()
        vm.pendingPrefill = details
        open(Routes.pick(Routes.PREFILL_MARK))
    }
    if (hidden && allCardsVcard != null) {
        Note(stringResource(R.string.qs_hidden_fields))
        Action(stringResource(R.string.qs_import_as_is), Icons.Rounded.FileDownload) {
            onDismiss()
            importVcard(context, vm, allCardsVcard)
        }
    }
}

// ---------------------------------------------------------------- Parley, phone, SMS, e-mail

@Composable
private fun ColumnScope.ParleyResult(vm: AppViewModel, p: QrPayload.Parley, onDismiss: () -> Unit) {
    Text(
        stringResource(
            when (p.kind) {
                ParleyKind.CONTACT -> R.string.qs_parley_contact_body
                ParleyKind.SIMPLE -> R.string.qs_parley_simple_body
                ParleyKind.TEMPLATE -> R.string.qs_parley_template_body
            },
        ),
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp),
    )
    Action(stringResource(R.string.qs_open_in_parley), Icons.Rounded.QrCode2, primary = true) {
        onDismiss()
        val uri = android.net.Uri.parse(p.raw)
        when (p.kind) {
            ParleyKind.CONTACT -> vm.navigate(NavEvent.SecureQr(uri))
            ParleyKind.SIMPLE -> {
                app.parley.ui.extras.SimpleInbox.qr.value = uri
                vm.navigate(NavEvent.Route(app.parley.ui.extras.ExtrasRoutes.SIMPLE_IMPORT))
            }
            ParleyKind.TEMPLATE -> {
                app.parley.blocking.TemplateInbox.pending.value = uri
                vm.navigate(NavEvent.Route(app.parley.ui.blocking.BlockingRoutes.TEMPLATES))
            }
        }
    }
}

@Composable
private fun ColumnScope.NumberActions(vm: AppViewModel, number: String, onDismiss: () -> Unit, call: Boolean = true) {
    var messageOn by remember { mutableStateOf(false) }
    if (call) {
        Action(stringResource(R.string.qs_call), Icons.Rounded.Call, primary = true) {
            onDismiss()
            // The usual path: dial guard, confirm-before-calling, SIM choice.
            vm.requestCall(number)
        }
    }
    Action(stringResource(R.string.qs_message_on), Icons.AutoMirrored.Rounded.Chat) { messageOn = true }
    Action(stringResource(R.string.qs_add_contact), Icons.Rounded.PersonAdd) {
        onDismiss()
        vm.navigate(NavEvent.NewContact(ContactDetails(phones = listOf(DataItem(value = number, type = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)))))
    }
    if (messageOn) MessageOnSheet(number, onDismiss = { messageOn = false })
}

@Composable
private fun ColumnScope.PhoneResult(vm: AppViewModel, p: QrPayload.Phone, onDismiss: () -> Unit) {
    Text(Bidi.ltr(p.number), style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 12.dp))
    if (p.isMmi) {
        // Service codes can change phone settings (forwarding, even a reset): shown in full, never dialled from here.
        Note(stringResource(R.string.qs_code_warning), Icons.Rounded.Warning, warning = true)
        Action(stringResource(R.string.qs_put_on_keypad), Icons.Rounded.Dialpad, primary = true) {
            onDismiss()
            vm.navigate(NavEvent.Tab(StartTab.KEYPAD, dial = p.number))
        }
        return
    }
    NumberActions(vm, p.number, onDismiss)
    Action(stringResource(R.string.qs_put_on_keypad), Icons.Rounded.Dialpad) {
        onDismiss()
        vm.navigate(NavEvent.Tab(StartTab.KEYPAD, dial = p.number))
    }
}

@Composable
private fun ColumnScope.SmsResult(vm: AppViewModel, p: QrPayload.Sms, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Field(stringResource(R.string.qs_field_to), p.numbers.joinToString(", "), ltr = true)
    p.body?.let { Field(stringResource(R.string.qs_field_message), it) }
    Note(stringResource(R.string.qs_sms_note))
    Action(stringResource(R.string.qs_write_sms), Icons.AutoMirrored.Rounded.Message, primary = true) {
        val link = MessengerLinks.sms(p.number, null, p.body, MessengerLauncher.smsPackage(context))
        MessengerLauncher.open(context, link, null)?.let { vm.toast(it) } ?: onDismiss()
    }
    NumberActions(vm, p.number, onDismiss, call = false)
}

@Composable
private fun ColumnScope.EmailResult(vm: AppViewModel, p: QrPayload.Email, onDismiss: () -> Unit) {
    val context = LocalContext.current
    if (p.to.isNotEmpty()) Field(stringResource(R.string.qs_field_to), p.to.joinToString(", "))
    if (p.cc.isNotEmpty()) Field(stringResource(R.string.qs_field_cc), p.cc.joinToString(", "))
    // Every recipient the mail app will get is shown, hidden copies included.
    if (p.bcc.isNotEmpty()) {
        Field(stringResource(R.string.qs_field_bcc), p.bcc.joinToString(", "))
        Note(stringResource(R.string.qs_bcc_note), Icons.Rounded.Warning, warning = true)
    }
    p.subject?.let { Field(stringResource(R.string.qs_field_subject), it) }
    p.body?.let { Field(stringResource(R.string.qs_field_message), it) }
    Action(stringResource(R.string.qs_write_email), Icons.Rounded.Email, primary = true) { QrActions.email(context, p) }
    p.to.firstOrNull()?.let { address ->
        Action(stringResource(R.string.qs_add_contact), Icons.Rounded.PersonAdd) {
            onDismiss()
            vm.navigate(NavEvent.NewContact(ContactDetails(emails = listOf(DataItem(value = address, type = ContactsContract.CommonDataKinds.Email.TYPE_HOME)))))
        }
    }
}

// ---------------------------------------------------------------- places, Wi-Fi, events

@Composable
private fun ColumnScope.GeoResult(p: QrPayload.Geo) {
    val context = LocalContext.current
    val coords = String.format(java.util.Locale.ROOT, "%.6f, %.6f", p.lat, p.lon)
    Field(stringResource(R.string.qs_field_coordinates), coords, ltr = true, mono = true)
    p.query?.let { Field(stringResource(R.string.qs_field_place), it) }
    Action(stringResource(R.string.qs_open_maps), Icons.Rounded.Place, primary = true) { QrActions.map(context, p) }
    Action(stringResource(R.string.qs_copy_coordinates), Icons.Rounded.ContentCopy) { QrActions.copy(context, coords) }
}

@Composable
private fun ColumnScope.WifiResult(p: QrPayload.Wifi) {
    val context = LocalContext.current
    var reveal by remember { mutableStateOf(false) }
    Field(stringResource(R.string.qs_field_network), p.ssid)
    Field(
        stringResource(R.string.qs_field_security),
        stringResource(
            when (p.security) {
                WifiSecurity.OPEN -> R.string.qs_wifi_open
                WifiSecurity.WEP -> R.string.qs_wifi_wep
                WifiSecurity.WPA -> R.string.qs_wifi_wpa
                WifiSecurity.SAE -> R.string.qs_wifi_sae
                WifiSecurity.EAP -> R.string.qs_wifi_eap
            },
        ) + (p.eapMethod?.let { " · $it" } ?: ""),
    )
    p.identity?.let { Field(stringResource(R.string.qs_field_identity), it) }
    p.password?.let { pw ->
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Field(stringResource(R.string.qs_field_password), if (reveal) pw else "•".repeat(pw.length.coerceAtMost(16)), mono = true)
            }
            IconButton({ reveal = !reveal }) {
                Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, stringResource(if (reveal) R.string.qs_hide_password else R.string.qs_show_password))
            }
        }
    }
    if (p.hidden) Text(stringResource(R.string.qs_wifi_hidden), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    val canAdd = remember(p) { QrActions.canAddWifi(p) }
    Note(stringResource(if (canAdd) R.string.qs_wifi_note_add else R.string.qs_wifi_note_settings))
    if (canAdd) Action(stringResource(R.string.qs_wifi_add), Icons.Rounded.Wifi, primary = true) { QrActions.addWifi(context, p) }
    p.password?.let { pw -> Action(stringResource(R.string.qs_copy_password), Icons.Rounded.ContentCopy, primary = !canAdd) { QrActions.copy(context, pw, sensitive = true) } }
    Action(stringResource(R.string.qs_wifi_settings), Icons.Rounded.Settings) { QrActions.wifiSettings(context) }
}

@Composable
private fun ColumnScope.EventResult(p: QrPayload.Event) {
    val context = LocalContext.current
    if (p.summary.isNotBlank()) Text(QrText.shown(p.summary, 200, false), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
    val zone = java.time.ZoneId.systemDefault()
    p.start?.let { s -> s.toEpochMillis(zone)?.let { s to it } }?.let { (s, start) ->
        // An all-day event ends at the start of the next day: show the last day it covers.
        val end = p.end?.toEpochMillis(zone)?.let { if (s.allDay && it > start) it - 1 else it } ?: start
        var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_YEAR
        if (!s.allDay) flags = flags or DateUtils.FORMAT_SHOW_TIME
        Field(stringResource(R.string.qs_field_when), DateUtils.formatDateRange(context, start, maxOf(start, end), flags))
    }
    p.location?.let { Field(stringResource(R.string.qs_field_place), it) }
    p.description?.let { Field(stringResource(R.string.qs_field_note), it) }
    p.url?.let { Field(stringResource(R.string.qs_field_website), it) }
    Action(stringResource(R.string.qs_add_calendar), Icons.Rounded.Event, primary = true) { QrActions.calendar(context, p) }
}

// ---------------------------------------------------------------- messengers, web, text

@Composable
private fun ColumnScope.MessengerResult(vm: AppViewModel, p: QrPayload.Messenger, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(false) }
    val app = p.app
    p.handle?.let { Text(if (p.phone != null) Bidi.ltr(it) else it, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp)) }
    Field(stringResource(R.string.qs_field_link), p.uri)
    when {
        app.legacy -> Note(stringResource(R.string.qs_app_closed, app.label))
        app.scanInside -> Note(stringResource(R.string.qs_scan_inside, app.label))
        app.pasteOnly -> Note(stringResource(R.string.qs_paste_only, app.label))
    }
    Action(stringResource(R.string.qs_open_in, app.label), Icons.AutoMirrored.Rounded.Chat, primary = true) {
        if (QrActions.openInApp(context, p)) onDismiss() else missing = true
    }
    if (missing) {
        Note(stringResource(R.string.qs_not_installed, app.label), Icons.Rounded.Info)
        Action(stringResource(R.string.qs_copy_link), Icons.Rounded.ContentCopy) { QrActions.copy(context, p.uri) }
        if (p.hasWebPage) {
            Action(stringResource(R.string.qs_open_browser), Icons.Rounded.OpenInBrowser) { QrActions.openInBrowser(context, p.uri) }
            Text(stringResource(R.string.qs_browser_leaves), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Action(stringResource(R.string.qs_store_page, app.label), Icons.Rounded.Shop) { QrActions.storePage(context, app.packages.first()) }
    }
    // A chat link to a phone number: the number itself is useful too.
    p.phone?.let { number ->
        Action(stringResource(R.string.qs_save_contact), Icons.Rounded.PersonAdd) {
            onDismiss()
            vm.navigate(NavEvent.NewContact(ContactDetails(phones = listOf(DataItem(value = number, type = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)))))
        }
        if (app in setOf(QrApp.WHATSAPP, QrApp.SIGNAL, QrApp.TELEGRAM, QrApp.VIBER, QrApp.ZALO)) {
            Action(stringResource(R.string.qs_call), Icons.Rounded.Call) {
                onDismiss()
                vm.requestCall(number)
            }
        }
    }
}

@Composable
private fun ColumnScope.UrlResult(p: QrPayload.Url) {
    val context = LocalContext.current
    val info = p.info
    Text(stringResource(R.string.qs_domain_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
    Text(Bidi.ltr(QrText.shown(info.domain, 120, false)), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    if (info.displayHost != info.domain) Text(Bidi.ltr(QrText.shown(info.displayHost, 200, false)), style = MaterialTheme.typography.bodyMedium)
    if (info.asciiHost != info.displayHost) {
        Text(stringResource(R.string.qs_spelled_as, info.asciiHost), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
    Field(stringResource(R.string.qs_field_full_address), p.url, ltr = true)
    info.warnings.forEach { w ->
        val text = when (w) {
            UrlSafety.Warning.NOT_HTTPS -> R.string.qs_warn_http
            UrlSafety.Warning.IDN -> R.string.qs_warn_idn
            UrlSafety.Warning.MIXED_SCRIPT -> R.string.qs_warn_mixed
            UrlSafety.Warning.LOOKALIKE -> R.string.qs_warn_lookalike
            UrlSafety.Warning.SHORTENER -> R.string.qs_warn_shortener
            UrlSafety.Warning.USERINFO -> R.string.qs_warn_userinfo
            UrlSafety.Warning.IP_ADDRESS -> R.string.qs_warn_ip
        }
        val strong = w != UrlSafety.Warning.IDN && w != UrlSafety.Warning.NOT_HTTPS
        Note(stringResource(text, info.domain), if (strong) Icons.Rounded.Warning else Icons.Rounded.Info, warning = strong)
    }
    Action(stringResource(R.string.qs_open_browser), Icons.Rounded.OpenInBrowser, primary = !info.isRisky) { QrActions.openInBrowser(context, p.url) }
    Text(stringResource(R.string.qs_browser_leaves), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Action(stringResource(R.string.qs_copy_link), Icons.Rounded.ContentCopy) { QrActions.copy(context, p.url) }
}

@Composable
private fun ColumnScope.TextResult(p: QrPayload.Text) {
    Card(Modifier.fillMaxWidth().padding(top = 12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        SelectionContainer { Text(QrText.shown(p.raw), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
    }
    if (p.truncated || p.raw.length > QrText.MAX_SHOWN) Note(stringResource(R.string.qs_text_long))
}
