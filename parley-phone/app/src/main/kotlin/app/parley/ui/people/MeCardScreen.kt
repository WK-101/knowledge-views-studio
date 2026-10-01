package app.parley.ui.people

import app.parley.ui.Destination
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.common.people.MeCard
import app.parley.common.people.MeCards
import app.parley.ui.Avatar
import app.parley.ui.SegmentedGroup
import app.parley.ui.Spacing
import app.parley.ui.avatarSize
import app.parley.ui.qr.QrRoutes
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.DataL10n
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyTopBar
import app.parley.ui.showMessage
import app.parley.ui.ParleyListItem
import app.parley.ui.common.Intents
import app.parley.ui.contact.ActionTile
import app.parley.ui.contact.GroupDataRow
import app.parley.ui.contact.InfoRow
import app.parley.ui.contact.mePartLabel

/** Imports the old "My details" once, so the card starts with what was typed there. */
@Composable
private fun MigrateMyDetails(vm: AppViewModel) {
    LaunchedEffect(Unit) {
        val old = vm.c.messaging.myDetails.value
        vm.c.people.me.migrateFrom(old.name, old.number)
    }
}

/**
 * "My card" at the top of Contacts. The row opens the card (laid out like a contact's page); the QR button on its end
 * shows the QR code (with Share and Edit in it). An empty card has no QR button, as there is nothing to show.
 */
@Composable
fun MeCardRow(vm: AppViewModel, open: (Destination) -> Unit) {
    MigrateMyDetails(vm)
    val own by vm.c.people.me.card.collectAsStateWithLifecycle()
    val parts by vm.c.people.me.shareParts.collectAsStateWithLifecycle()
    val profile by produceState<MeCard?>(null) { value = vm.c.people.me.profile() }
    val card = remember(own, profile) { MeCards.merge(own, profile) }
    val me = stringResource(R.string.me_short)
    val myCard = stringResource(R.string.me_title)
    var showQr by rememberSaveable { mutableStateOf(false) }
    ParleyListItem(
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.me_open)) { open(PeopleRoutes.Me) },
        leadingContent = { Avatar(card.name.ifBlank { me }, null, avatarSize()) },
        headlineContent = { Text(card.name.ifBlank { myCard }) },
        supportingContent = {
            Text(
                if (card.isEmpty) stringResource(R.string.me_empty) else listOfNotNull(myCard, card.firstNumber?.let(DataL10n::ltr)).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = if (card.isEmpty) null else ({
            // Its own 48dp target, so a tap on the QR code never opens the card.
            IconButton({ showQr = true }) { Icon(Icons.Rounded.QrCode2, stringResource(R.string.me_show_qr), tint = MaterialTheme.colorScheme.primary) }
        }),
    )
    if (showQr) {
        MeQrDialog(
            card, parts, onDismiss = { showQr = false }, onEdit = { showQr = false; open(PeopleRoutes.MeEdit) },
            onScan = { showQr = false; vm.navigate(NavEvent.Route(QrRoutes.Scan)) },
        )
    }
}

/**
 * Your own card, laid out like a contact's page: a compact header (monogram, name, job) with the QR code, Share and
 * Edit tiles, then Contact info as one group, the private note, and where the details come from. Editing opens the
 * contact editor in its My card mode ([PeopleRoutes.MeEdit]), the same form as every contact. Parley keeps the card
 * (private to Parley), shows it with the phone's profile ("Me") when there is one, and shares it as a vCard or a QR
 * code with the parts chosen in the editor. Its name and first number also fill in "Send my details".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeCardScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit) {
    MigrateMyDetails(vm)
    val context = LocalContext.current
    val store = vm.c.people.me
    val own by store.card.collectAsStateWithLifecycle()
    val parts by store.shareParts.collectAsStateWithLifecycle()
    val profile by produceState<MeCard?>(null) { value = store.profile() }
    val card = remember(own, profile) { MeCards.merge(own, profile) }
    var showQr by rememberSaveable { mutableStateOf(false) }
    val edit = { open(PeopleRoutes.MeEdit) }

    ParleyScaffold(
        topBar = {
            ParleyTopBar(stringResource(R.string.me_title), onBack = back, actions = {
                IconButton(edit) { Icon(Icons.Rounded.Edit, stringResource(R.string.me_edit)) }
            })
        },
    ) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).verticalScroll(rememberScrollState()).padding(bottom = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            MeHeader(card, onQr = { showQr = true }, onShare = { shareVcard(context, card, parts) }, onEdit = edit)
            if (!card.isEmpty) MeContactInfo(card)
            if (card.note.isNotBlank()) {
                SegmentedGroup(stringResource(R.string.me_private_note)) {
                    item {
                        InfoRow(
                            leading = { Icon(Icons.AutoMirrored.Rounded.Notes, null) },
                            headline = { Text(card.note) },
                            supporting = { Text(stringResource(R.string.me_private_note_hint)) },
                        )
                    }
                }
            }
            SegmentedGroup {
                // What the QR code and the vCard include; changed in the editor.
                item {
                    val shared = MeCards.Part.entries.filter { it in parts }.map { mePartLabel(it) }
                    InfoRow(
                        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.me_edit), onClick = edit),
                        leading = { Icon(Icons.Rounded.QrCode2, null) },
                        headline = { Text(stringResource(R.string.me_share_includes)) },
                        supporting = { Text(shared.ifEmpty { listOf(stringResource(R.string.me_share_nothing)) }.joinToString(", ")) },
                    )
                }
                if (profile != null) {
                    item {
                        InfoRow(
                            leading = { Icon(Icons.Rounded.AccountCircle, null) },
                            headline = { Text(stringResource(R.string.me_profile_title)) },
                            supporting = { Text(stringResource(R.string.me_profile_text)) },
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.me_footer),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xxl),
            )
        }
    }
    // "Scan theirs" right from your own code.
    if (showQr) MeQrDialog(card, parts, onDismiss = { showQr = false }, onScan = { showQr = false; vm.navigate(NavEvent.Route(QrRoutes.Scan)) })
}

/** The card's compact header, like a contact page's: monogram, name, job line, then the QR code, Share and Edit tiles. */
@Composable
private fun MeHeader(card: MeCard, onQr: () -> Unit, onShare: () -> Unit, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(card.name.ifBlank { stringResource(R.string.me_short) }, null, 96.dp, modifier = Modifier.padding(top = Spacing.xs))
        Text(
            card.name.ifBlank { stringResource(R.string.me_your_name) }, style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = Spacing.m),
        )
        val job = listOf(card.title, card.company).filter { it.isNotBlank() }.joinToString(" · ")
        val line = if (card.isEmpty) stringResource(R.string.me_empty) else job
        if (line.isNotEmpty()) {
            Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Spacing.xxs))
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.m), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            ActionTile(Icons.Rounded.QrCode2, stringResource(R.string.me_qr), enabled = !card.isEmpty, onClick = onQr)
            ActionTile(Icons.Rounded.Share, stringResource(R.string.me_share), enabled = !card.isEmpty, onClick = onShare)
            ActionTile(Icons.Rounded.Edit, stringResource(R.string.me_edit_short), enabled = true, onClick = onEdit)
        }
    }
}

/** Numbers, emails, work, websites and the address as one "Contact info" group; a tap copies, like on a contact. */
@Composable
private fun MeContactInfo(card: MeCard) {
    val context = LocalContext.current
    SegmentedGroup(stringResource(R.string.contact_page_info)) {
        card.phones.forEachIndexed { i, n ->
            item {
                val copy = { Intents.copy(context, n, sensitive = false) }
                GroupDataRow(Icons.Rounded.Call, i == 0, n, null, onClick = copy, headline = { Text(DataL10n.ltr(n)) })
            }
        }
        card.emails.forEachIndexed { i, e ->
            item { GroupDataRow(Icons.Rounded.Email, i == 0, e, null, onClick = { Intents.copy(context, e, sensitive = false) }) }
        }
        val job = listOf(card.title, card.company).filter { it.isNotBlank() }.joinToString(" · ")
        if (job.isNotEmpty()) item { GroupDataRow(Icons.Rounded.Business, true, job, null, onClick = { Intents.copy(context, job, sensitive = false) }) }
        card.websites.forEachIndexed { i, w ->
            item { GroupDataRow(Icons.Rounded.Language, i == 0, w, null, onClick = { Intents.copy(context, w, sensitive = false) }) }
        }
        if (card.address.isNotBlank()) {
            item { GroupDataRow(Icons.Rounded.Place, true, card.address, null, onClick = { Intents.copy(context, card.address, sensitive = false) }) }
        }
    }
}

/** Writes the vCard to Parley's share folder and hands it to the app you choose. */
private fun shareVcard(context: Context, card: MeCard, parts: Set<MeCards.Part>) {
    runCatching {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "my-card.vcf")
        file.writeText(MeCards.vcard(card, parts))
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/x-vcard").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, card.name.ifBlank { context.getString(R.string.me_title) }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.me_share_chooser)))
    }.onFailure { showMessage(context, context.getString(R.string.me_share_failed)) }
}

/**
 * The card as a QR code (made on the phone). It starts with the parts chosen in the editor ([initialParts]); ticking
 * others here changes only this code. [onEdit]: an Edit button to the editor.
 */
@Composable
internal fun MeQrDialog(
    card: MeCard,
    initialParts: Set<MeCards.Part>,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onScan: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val parts = remember { mutableStateListOf<MeCards.Part>().apply { addAll(MeCards.Part.entries.filter { it in initialParts }) } }
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
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.me_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.me_qr_desc), Modifier.size(240.dp).background(Color.White).padding(8.dp)) }
                Text(stringResource(R.string.me_scan), modifier = Modifier.padding(vertical = 8.dp))
                if (onScan != null) {
                    OutlinedButton(onScan) {
                        Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp))
                        Text("  " + stringResource(R.string.qs_scan_theirs))
                    }
                }
                available.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { if (p in parts) parts.remove(p) else parts.add(p) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(p in parts, { if (it) parts.add(p) else parts.remove(p) })
                        Text(mePartLabel(p))
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_done)) } },
        dismissButton = {
            Row {
                if (onEdit != null) TextButton(onEdit) { Text(stringResource(R.string.me_edit_short)) }
                TextButton({ shareVcard(context, card, parts.toSet()) }) { Text(stringResource(R.string.me_share_file)) }
            }
        },
    )
}

private fun qr(text: String, size: Int): Bitmap? = try {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
} catch (_: Exception) {
    null
}
