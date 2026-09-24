package app.parley.ui.people

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.people.MeCard
import app.parley.common.people.MeCards
import app.parley.data.messaging.MyDetails
import app.parley.ui.Avatar
import app.parley.ui.CallColors
import app.parley.ui.SegmentedGroup
import app.parley.ui.settings.SettingsScaffold
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

/** Imports the old "My details" once, so the card starts with what was typed there (I2). */
@Composable
private fun MigrateMyDetails(vm: AppViewModel) {
    LaunchedEffect(Unit) {
        val old = vm.c.messaging.myDetails.value
        vm.c.people.me.migrateFrom(old.name, old.number)
    }
}

/** I2: "My card" at the top of Contacts. */
@Composable
fun MeCardRow(vm: AppViewModel, open: (String) -> Unit) {
    MigrateMyDetails(vm)
    val own by vm.c.people.me.card.collectAsStateWithLifecycle()
    val profile by produceState<MeCard?>(null) { value = vm.c.people.me.profile() }
    val card = remember(own, profile) { MeCards.merge(own, profile) }
    ListItem(
        modifier = Modifier.clickable(onClickLabel = "Open my card") { open(PeopleRoutes.ME) },
        leadingContent = { Avatar(card.name.ifBlank { "Me" }, null, app.parley.ui.avatarSize()) },
        headlineContent = { Text(card.name.ifBlank { "My card" }) },
        supportingContent = {
            Text(
                if (card.isEmpty) "Add your details to share them as a QR code" else listOfNotNull("My card", card.firstNumber).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { Icon(Icons.Rounded.QrCode2, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

/**
 * I2: your own card. Parley keeps it (private to Parley, like "My details" before), shows it with the phone's
 * profile ("Me") when there is one, and shares it as a vCard or a QR code with only the parts you choose. Its name
 * and first number also fill in "Send my details".
 */
@Composable
fun MeCardScreen(vm: AppViewModel, back: () -> Unit) {
    MigrateMyDetails(vm)
    val context = LocalContext.current
    val store = vm.c.people.me
    val own by store.card.collectAsStateWithLifecycle()
    val profile by produceState<MeCard?>(null) { value = store.profile() }
    var draft by remember(own) { mutableStateOf(own) }
    var showQr by remember { mutableStateOf(false) }
    val merged = remember(draft, profile) { MeCards.merge(draft, profile) }
    val dirty = draft.cleaned() != own

    fun save() {
        val c = draft.cleaned()
        store.save(c)
        // "Send my details" keeps working from the card.
        vm.c.messaging.setMyDetails(MyDetails(c.name, c.firstNumber.orEmpty()))
        vm.toast("Card saved")
    }

    SettingsScaffold("My card", back, actions = {
        TextButton(::save, enabled = dirty) { Text("Save") }
    }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(merged.name.ifBlank { "Me" }, null, 96.dp)
            Text(merged.name.ifBlank { "Your name" }, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
            merged.firstNumber?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ showQr = true }, enabled = !merged.isEmpty) {
                    Icon(Icons.Rounded.QrCode2, null, Modifier.size(18.dp))
                    Text("  QR code")
                }
                OutlinedButton({ shareVcard(context, merged, MeCards.Part.entries.toSet()) }, enabled = !merged.isEmpty) {
                    Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                    Text("  Share")
                }
            }
        }
        if (profile != null) {
            SegmentedGroup {
                item {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Icon(Icons.Rounded.AccountCircle, null) },
                        headlineContent = { Text("Also shows your phone's profile") },
                        supportingContent = {
                            Text(
                                "Details from Android's “Me” contact fill in what's empty here. Parley doesn't change that profile: " +
                                    "anything in it can be read by every app with contacts access, so your card stays in Parley.",
                            )
                        },
                    )
                }
            }
        }
        SegmentedGroup("You") {
            item { Box16 { OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)) } }
            item { Box16 { OutlinedTextField(draft.company, { draft = draft.copy(company = it) }, label = { Text("Company") }, singleLine = true, modifier = Modifier.fillMaxWidth()) } }
            item { Box16 { OutlinedTextField(draft.title, { draft = draft.copy(title = it) }, label = { Text("Job title") }, singleLine = true, modifier = Modifier.fillMaxWidth()) } }
        }
        ListEditor("Numbers", "Number", draft.phones, KeyboardType.Phone, suggest = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.c.sims.ownNumbers().firstOrNull() } }) { draft = draft.copy(phones = it) }
        ListEditor("E-mail", "E-mail address", draft.emails, KeyboardType.Email) { draft = draft.copy(emails = it) }
        ListEditor("Websites", "Website", draft.websites, KeyboardType.Uri) { draft = draft.copy(websites = it) }
        SegmentedGroup("More") {
            item { Box16 { OutlinedTextField(draft.address, { draft = draft.copy(address = it) }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth(), minLines = 2) } }
            item {
                Box16 {
                    OutlinedTextField(
                        draft.note, { draft = draft.copy(note = it) }, label = { Text("Private note") }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                        supportingText = { Text("Only for you: never shared") },
                    )
                }
            }
        }
        Text(
            "Your card is stored on this phone only, inside Parley. “Send my details” uses its name and first number.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
    if (showQr) MeQrDialog(merged) { showQr = false }
}

@Composable
private fun Box16(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { content() }
}

@Composable
private fun ListEditor(title: String, label: String, values: List<String>, keyboard: KeyboardType, suggest: (suspend () -> String?)? = null, onChange: (List<String>) -> Unit) {
    val rows = values.ifEmpty { listOf("") }
    LaunchedEffect(Unit) {
        if (suggest != null && values.isEmpty()) runCatching { suggest() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { onChange(listOf(it)) }
    }
    SegmentedGroup(title) {
        rows.forEachIndexed { i, v ->
            item {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        v, { n -> onChange(rows.toMutableList().also { it[i] = n }) }, label = { Text(label) }, singleLine = true,
                        modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                    )
                    IconButton({ onChange(rows.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Rounded.RemoveCircle, "Remove", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item {
            TextButton({ onChange(rows + "") }, Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Rounded.AddCircle, null, Modifier.size(20.dp), tint = CallColors.Accept)
                Text("  Add ${label.lowercase()}")
            }
        }
    }
}

/** Writes the vCard to Parley's share folder and hands it to the app you choose. */
private fun shareVcard(context: android.content.Context, card: MeCard, parts: Set<MeCards.Part>) {
    runCatching {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "my-card.vcf")
        file.writeText(MeCards.vcard(card, parts))
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/x-vcard").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, card.name.ifBlank { "My card" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share my card"))
    }.onFailure { android.widget.Toast.makeText(context, "Couldn't share the card", android.widget.Toast.LENGTH_SHORT).show() }
}

/** The card as a QR code (made on the phone), with the parts to include. */
@Composable
private fun MeQrDialog(card: MeCard, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val parts = remember { mutableStateListOf(MeCards.Part.NAME, MeCards.Part.PHONES) }
    val available = MeCards.Part.entries.filter { p ->
        when (p) {
            MeCards.Part.NAME -> card.name.isNotBlank()
            MeCards.Part.PHONES -> card.phones.isNotEmpty()
            MeCards.Part.EMAILS -> card.emails.isNotEmpty()
            MeCards.Part.WORK -> card.company.isNotBlank() || card.title.isNotBlank()
            MeCards.Part.WEBSITES -> card.websites.isNotEmpty()
            MeCards.Part.ADDRESS -> card.address.isNotBlank()
        }
    }
    val text = MeCards.vcard(card, parts.toSet())
    val bitmap = remember(text) { qr(text, 720) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My card") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                bitmap?.let { Image(it.asImageBitmap(), "QR code of my card", Modifier.size(240.dp).background(Color.White).padding(8.dp)) }
                Text("Scan with any camera app. Include:", modifier = Modifier.padding(vertical = 8.dp))
                available.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { if (p in parts) parts.remove(p) else parts.add(p) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(p in parts, { if (it) parts.add(p) else parts.remove(p) })
                        Text(
                            when (p) {
                                MeCards.Part.NAME -> "Name"
                                MeCards.Part.PHONES -> "Numbers"
                                MeCards.Part.EMAILS -> "E-mail"
                                MeCards.Part.WORK -> "Company and job"
                                MeCards.Part.WEBSITES -> "Websites"
                                MeCards.Part.ADDRESS -> "Address"
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } },
        dismissButton = { TextButton({ shareVcard(context, card, parts.toSet()) }) { Text("Share as file") } },
    )
}

private fun qr(text: String, size: Int): Bitmap? = try {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
} catch (_: Exception) {
    null
}
