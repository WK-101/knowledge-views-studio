package app.parley.ui.people

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PhoneForwarded
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.R
import app.parley.common.cards.ShareLedger
import app.parley.common.people.ContactRef
import app.parley.common.people.MeCard
import app.parley.common.people.MeCards
import app.parley.common.people.RelationLinks
import app.parley.common.people.RelationshipStatus
import app.parley.common.ux.Tips
import app.parley.data.ContactDetails
import app.parley.data.people.MeCardDetails
import app.parley.data.people.PeopleContainer
import app.parley.data.people.RelationMirrors
import app.parley.ui.Avatar
import app.parley.ui.Banner
import app.parley.ui.DataL10n
import app.parley.ui.Destination
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleyScaffold
import app.parley.ui.ParleyTopBar
import app.parley.ui.SegmentedGroup
import app.parley.ui.Spacing
import app.parley.ui.avatarSize
import app.parley.ui.common.CoachMark
import app.parley.ui.common.ImageActionButtons
import app.parley.ui.common.generatedImage
import app.parley.ui.common.rememberImageActions
import app.parley.ui.contact.ActionTile
import app.parley.ui.contact.HeaderFacts
import app.parley.ui.contact.HeaderLink
import app.parley.ui.contact.InfoRow
import app.parley.ui.contact.NativeNameLine
import app.parley.ui.contact.mePartLabel
import app.parley.ui.people.cards.CardSharing
import app.parley.ui.qr.QrRoutes
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * My card as My card shows it: Parley's copy, completed with the phone's profile ("Me"). The old "My details" was
 * folded into it ([app.parley.data.people.PeopleContainer.me]).
 */
@Composable
fun rememberMyCard(people: PeopleContainer): MeCard {
    val own by people.me.card.collectAsStateWithLifecycle()
    val profile by produceState<MeCard?>(null) { value = people.me.profile() }
    return remember(own, profile) { MeCards.merge(own, profile) }
}

/** My card as a whole contact, as My card shows it: Parley's copy completed with the phone's profile. */
@Composable
fun rememberMyDetails(people: PeopleContainer): ContactDetails {
    val own by people.me.details.collectAsStateWithLifecycle()
    val profile by produceState<MeCard?>(null) { value = people.me.profile() }
    return remember(own, profile) { MeCardDetails.withProfile(own, profile) }
}

/**
 * What "Send my details" and Introduce myself send, and what their name-and-number edit starts from
 * ([MeCards.forSending]): Parley's own name and number, so clearing the number there really leaves it out; the phone's
 * profile only while Parley's card has neither.
 */
@Composable
fun rememberCardForSending(people: PeopleContainer): MeCard {
    val own by people.me.card.collectAsStateWithLifecycle()
    val profile by produceState<MeCard?>(null) { value = people.me.profile() }
    return remember(own, profile) { MeCards.forSending(own, profile) }
}

/**
 * "My card" at the top of Contacts. The row opens the card (laid out like a contact's page); the QR button on its end
 * shows the QR code (with Share and Edit in it). An empty card has no QR button, as there is nothing to show.
 */
@Composable
fun MeCardRow(vm: AppViewModel, open: (Destination) -> Unit) {
    val parts by vm.c.people.me.shareParts.collectAsStateWithLifecycle()
    val card = rememberMyDetails(vm.c.people)
    val name = MeCardDetails.nameOf(card)
    val empty = MeCardDetails.isEmpty(card)
    val number = MeCardDetails.toCard(card).firstNumber
    val me = stringResource(R.string.basics_for_me)
    val myCard = stringResource(R.string.me_title)
    var showQr by rememberSaveable { mutableStateOf(false) }
    ParleyListItem(
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.me_open)) { open(PeopleRoutes.Me) },
        leadingContent = { Avatar(name.ifBlank { me }, card.photoUri, avatarSize()) },
        headlineContent = { Text(name.ifBlank { myCard }) },
        supportingContent = {
            Text(
                if (empty) stringResource(R.string.me_empty) else listOfNotNull(myCard, number?.let(DataL10n::ltr)).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = if (empty) null else ({
            // Its own 48dp target, so a tap on the QR code never opens the card.
            IconButton({ showQr = true }) { Icon(Icons.Rounded.QrCode2, stringResource(R.string.me_show_qr), tint = MaterialTheme.colorScheme.primary) }
        }),
    )
    if (showQr) {
        MeQrDialog(
            vm, card, parts, onDismiss = { showQr = false }, onEdit = { showQr = false; open(PeopleRoutes.MeEdit) },
            onScan = { showQr = false; vm.navigate(NavEvent.Route(QrRoutes.Scan)) },
        )
    }
}

/**
 * Your own card, laid out like a contact's page: a compact header (photo, name, pronouns, nickname, job and who you
 * are married to) with the QR code, Share and Edit tiles, then every detail with the contact page's own rows
 * ([MeCardDetailsSections]), and where the details come from. Editing opens the contact editor in its My card mode
 * ([PeopleRoutes.MeEdit]), the same form and the same fields as every contact. Parley keeps the card (private to
 * Parley), shows it with the phone's profile ("Me") when there is one, and shares it as a vCard or a QR code with the
 * parts chosen in the editor. Its name and first number also fill in "Send my details".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeCardScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val store = vm.c.people.me
    val own by store.card.collectAsStateWithLifecycle()
    val parts by store.shareParts.collectAsStateWithLifecycle()
    val links by store.links.collectAsStateWithLifecycle()
    val privacy by vm.privacy.collectAsStateWithLifecycle()
    val profile by produceState<MeCard?>(null) { value = store.profile() }
    val card = rememberMyDetails(vm.c.people)
    val empty = MeCardDetails.isEmpty(card)
    var showQr by rememberSaveable { mutableStateOf(false) }
    val edit = { open(PeopleRoutes.MeEdit) }
    val scope = rememberCoroutineScope()
    val subject = MeCardDetails.nameOf(card).ifBlank { stringResource(R.string.me_title) }
    val share = {
        scope.launch { CardSharing.shareFile(vm.c, context, CardSharing.vcard(vm.c, parts), subject) }
        Unit
    }

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
            MeHeader(card, links, privacy.privateHidden, onQr = { showQr = true }, onShare = share, onEdit = edit, open = open)
            // "Changed my number": offered while people you shared with still have an old number.
            NewNumberBanner(vm, own, open)
            // A restored backup brought your earlier card key while this phone's was already shared.
            CardKeyChoiceBanner(vm)
            if (!empty) MeCardDetailsSections(vm, card, links, privacy.privateHidden, open)
            // Signed cards and "Shared with", explained once.
            if (!empty) CoachMark(Tips.SIGNED_CARD, stringResource(R.string.card_signed_tip))
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
                // Who got your card, when and how.
                item { SharedWithRow(vm, open) }
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
            // Android's emergency information and an ICE label (the lock screen's Emergency button stays Android's).
            EmergencyInfoGroup(vm)
            Text(
                stringResource(R.string.me_footer),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xxl),
            )
        }
    }
    // "Scan theirs" right from your own code.
    if (showQr) MeQrDialog(vm, card, parts, onDismiss = { showQr = false }, onScan = { showQr = false; vm.navigate(NavEvent.Route(QrRoutes.Scan)) })
}

/**
 * The card's compact header, like a contact page's: photo or monogram, name, the name in your own language, then
 * pronouns, nickname, job and who you are married to or the partner of (from your relations; a tap opens them), then
 * the QR code, Share and Edit tiles.
 */
@Composable
private fun MeHeader(
    card: ContactDetails,
    links: Map<String, RelationLinks.Link>,
    hidePrivate: Boolean,
    onQr: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    open: (Destination) -> Unit,
) {
    val res = LocalResources.current
    val empty = MeCardDetails.isEmpty(card)
    val name = MeCardDetails.nameOf(card)
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(name.ifBlank { stringResource(R.string.basics_for_me) }, card.photoUri, 96.dp, modifier = Modifier.padding(top = Spacing.xs))
        Text(
            name.ifBlank { stringResource(R.string.me_your_name) }, style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = Spacing.m),
        )
        if (!card.nativeName.isBlank) NativeNameLine(card.nativeName)
        if (empty) {
            Text(
                stringResource(R.string.me_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
        } else {
            val work = listOf(card.title, card.department, card.company).filter { it.isNotBlank() }.joinToString(", ")
            val shown = card.relations.filterNot { r -> hidePrivate && links[RelationLinks.nameKey(r.value)]?.lookupKey?.let(ContactRef::isPrivateKey) == true }
            val status = RelationshipStatus.header(shown) { RelationMirrors.rowOf(it) }.map { (kind, rel) ->
                val format = if (kind == RelationshipStatus.Kind.MARRIED) R.string.detail_married_to else R.string.detail_partner_of
                val text = res.getString(format, rel.value.trim())
                HeaderLink(text) { open(PeopleRoutes.Me) }
            }
            HeaderFacts(listOf(card.pronouns.trim(), card.nickname.trim(), work), stringResource(R.string.main_separator), status)
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.m), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            ActionTile(Icons.Rounded.QrCode2, stringResource(R.string.blk_qr_code), enabled = !empty, onClick = onQr)
            ActionTile(Icons.Rounded.Share, stringResource(R.string.diag_share), enabled = !empty, onClick = onShare)
            ActionTile(Icons.Rounded.Edit, stringResource(R.string.me_edit_short), enabled = true, onClick = onEdit)
        }
    }
}

/**
 * The card as a QR code (made on the phone). It starts with the parts chosen in the editor ([initialParts]); ticking
 * others here changes only this code. The photo never fits a QR code: it goes only into the shared file. [onEdit]: an
 * Edit button to the editor.
 */
@Suppress("CyclomaticComplexMethod") // One check per part the card can share.
@Composable
internal fun MeQrDialog(
    vm: AppViewModel,
    card: ContactDetails,
    initialParts: Set<MeCards.Part>,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onScan: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val parts = remember { mutableStateListOf<MeCards.Part>().apply { addAll(MeCards.Part.entries.filter { it in initialParts }) } }
    // Only the parts the card holds something for.
    val nothing = MeCardDetails.restrict(card, emptySet())
    val available = MeCards.Part.entries.filter { p ->
        if (p == MeCards.Part.PHOTO) card.photoUri != null else MeCardDetails.restrict(card, setOf(p)) != nothing
    }
    // Signed when only what a signed card carries is ticked: a contact's Parley can tell a later card from you;
    // camera apps read it like any vCard. One signature per change: the card is read with the profile inside.
    val text by CardSharing.rememberVcard(vm, parts.toSet() - MeCards.Part.PHOTO)
    val bitmap = remember(text) { text?.let { qr(it, 720) } }
    val fileName = stringResource(R.string.img_name_my_card_qr)
    val actions = rememberImageActions(vm, bitmap?.let { generatedImage(fileName, it) })
    val scope = rememberCoroutineScope()
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.me_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.me_qr_desc), Modifier.size(240.dp).background(Color.White).padding(8.dp)) }
                if (text != null && bitmap == null) Text(stringResource(R.string.me_qr_too_big), color = MaterialTheme.colorScheme.error)
                actions?.let { ImageActionButtons(it, Modifier.padding(top = 8.dp)) }
                Text(stringResource(R.string.me_scan), modifier = Modifier.padding(vertical = 8.dp))
                if (onScan != null) {
                    OutlinedButton({ CardSharing.swapStarted(); onScan() }) {
                        Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp))
                        Text("  " + stringResource(R.string.qs_scan_theirs))
                    }
                }
                available.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { if (p in parts) parts.remove(p) else parts.add(p) }, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(p in parts, { if (it) parts.add(p) else parts.remove(p) })
                        Text(mePartLabel(p))
                    }
                }
                val notes = listOfNotNull(
                    stringResource(R.string.me_qr_no_photo).takeIf { MeCards.Part.PHOTO in parts },
                    stringResource(R.string.me_share_unsigned).takeIf { !MeCards.isSignable(parts.toSet() - MeCards.Part.PHOTO) },
                )
                notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_done)) } },
        dismissButton = {
            Row {
                if (onEdit != null) TextButton(onEdit) { Text(stringResource(R.string.me_edit_short)) }
                val subject = MeCardDetails.nameOf(card).ifBlank { stringResource(R.string.me_title) }
                // The file takes every part ticked, the photo too.
                TextButton({ scope.launch { CardSharing.shareFile(vm.c, context, CardSharing.vcard(vm.c, parts.toSet()), subject) } }, enabled = text != null) {
                    Text(stringResource(R.string.me_share_file))
                }
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

/**
 * "Use your earlier card key?" after a restore, when this phone had already shared a card with its own key. Either
 * answer is a choice; until then this phone keeps signing with its own key.
 */
@Composable
private fun CardKeyChoiceBanner(vm: AppViewModel) {
    val identity = vm.c.people.cardIdentity
    val asking by identity.restoreChoice.collectAsStateWithLifecycle()
    if (!asking) return
    val scope = rememberCoroutineScope()
    Banner(
        stringResource(R.string.card_key_restore_question),
        icon = Icons.Rounded.Key,
        action = stringResource(R.string.card_key_restore_use_earlier),
        onAction = { scope.launch(Dispatchers.IO) { runCatching { identity.usePrevious() } } },
        onDismiss = { scope.launch(Dispatchers.IO) { runCatching { identity.keepThis() } } },
        dismissLabel = stringResource(R.string.card_key_restore_keep_this),
    )
}

/** My card › "Shared with": how many people have your card; opens the list. */
@Composable
private fun SharedWithRow(vm: AppViewModel, open: (Destination) -> Unit) {
    val receipts by CardSharing.rememberShownReceipts(vm)
    val people = remember(receipts) { ShareLedger.people(receipts, vm.countryIso).size }
    InfoRow(
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.card_shared_open)) { open(PeopleRoutes.SharedWith) },
        leading = { Icon(Icons.Rounded.People, null) },
        headline = { Text(stringResource(R.string.card_shared_title)) },
        supporting = {
            Text(
                if (people == 0) stringResource(R.string.card_shared_none)
                else pluralStringResource(R.plurals.card_shared_people, people, people),
            )
        },
    )
}

/**
 * After a number change on My card: "You have a new number. Tell the 3 people who have the old one?" until they've
 * been told or the offer is dismissed for these numbers.
 */
@Composable
private fun NewNumberBanner(vm: AppViewModel, own: MeCard, open: (Destination) -> Unit) {
    val store = vm.c.people.shareLedger
    val receipts by CardSharing.rememberShownReceipts(vm)
    val dismissed by store.dismissedNumbers.collectAsStateWithLifecycle()
    val phones = own.cleaned().phones
    val key = ShareLedger.numbersKey(phones)
    val outdated = remember(receipts, phones) { ShareLedger.outdated(receipts, phones, vm.countryIso).size }
    if (outdated == 0 || dismissed == key) return
    Banner(
        pluralStringResource(R.plurals.card_new_number_banner, outdated, outdated),
        icon = Icons.Rounded.PhoneForwarded,
        action = stringResource(R.string.card_new_number_tell), onAction = { open(PeopleRoutes.NewNumber) },
        onDismiss = { store.dismissNumbers(key) }, dismissLabel = stringResource(R.string.circle_not_now),
    )
}
