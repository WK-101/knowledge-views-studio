// The update banner and dialog live with the words they use.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.people.cards

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.R
import app.parley.common.cards.CardChange
import app.parley.common.cards.CardDiff
import app.parley.common.cards.CardField
import app.parley.common.cards.HeldOffer
import app.parley.common.cards.SignedCard
import app.parley.common.cards.SignedCards
import app.parley.common.security.Bounded
import android.net.Uri
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.parley.data.ContactDetails
import app.parley.security.launchVault
import app.parley.ui.Banner
import app.parley.ui.BannerTone
import app.parley.ui.ConfirmDialog
import app.parley.ui.Bidi
import app.parley.ui.ParleyDialog
import app.parley.ui.Spacing
import kotlinx.coroutines.launch

/** Words for card updates. */
object CardUpdateText {
    /** "new number, new email" for the banner. */
    fun kinds(res: Resources, changes: List<CardChange>): String = CardDiff.fields(changes).map { f ->
        res.getString(
            when (f) {
                CardField.NAME -> R.string.card_kind_name
                CardField.PHONE -> R.string.card_kind_number
                CardField.EMAIL -> R.string.card_kind_email
                CardField.COMPANY, CardField.TITLE -> R.string.card_kind_work
                CardField.WEBSITE -> R.string.card_kind_link
                CardField.ADDRESS -> R.string.card_kind_address
            },
        )
    }.distinct().joinToString(res.getString(R.string.dc_list_separator))

    fun fieldLabel(res: Resources, c: CardChange): String = c.label ?: res.getString(
        when (c.field) {
            CardField.NAME -> R.string.me_name
            CardField.PHONE -> R.string.me_number
            CardField.EMAIL -> R.string.me_email
            CardField.COMPANY -> R.string.me_company
            CardField.TITLE -> R.string.me_job_title
            CardField.WEBSITE -> R.string.me_website
            CardField.ADDRESS -> R.string.me_address
        },
    )

    /** "+44 7700 900123 → +44 7700 900456", "Add …", "Remove …". Numbers read left to right in every language. */
    fun change(res: Resources, c: CardChange): String {
        fun v(s: String) = if (c.field == CardField.PHONE) Bidi.ltr(s) else s
        return when {
            c.old != null && c.new != null -> res.getString(R.string.card_change_replace, v(c.old!!), v(c.new!!))
            c.new != null -> res.getString(R.string.card_change_add, v(c.new!!))
            else -> res.getString(R.string.card_change_remove, v(c.old.orEmpty()))
        }
    }
}

/**
 * On a contact's page (I14): a held card about this contact is offered to link ("Link it?") or shown as a different
 * signer, never linked by itself (H1); and when a newer card from the linked key waits, "Ana sent an updated card: new
 * number" with Review. Nothing is applied until the user chooses. [key] is the contact's Parley key; [navId] its page id.
 */
@Composable
fun CardUpdateBanner(vm: AppViewModel, navId: Long, key: String, details: ContactDetails) {
    val res = LocalResources.current
    val book by vm.c.people.cardLinks.book.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { runCatching { vm.c.people.cardLinks.load() } }
    val link = book.links[key]
    val offer = remember(book, key, details) { CardInbox.heldOffer(book, key, details, vm.countryIso) }
    if (offer != null) HeldCardBanner(vm, key, details.displayName, link?.fingerprint, offer)
    val pending = link?.pending ?: return
    val changes = remember(link, details) {
        CardDiff.changes(
            link.fields, pending.fields, CardUpdateApply.fieldsOf(details), vm.countryIso,
            afterParts = pending.parts, beforeParts = link.parts, contactAddresses = CardUpdateApply.addressesOf(details),
        )
    }
    // Everything the new card says, the contact already shows: nothing to ask, the update is simply taken as seen.
    LaunchedEffect(link, changes.isEmpty()) {
        if (changes.isEmpty()) runCatching { CardUpdateApply.save(vm.c, navId, key, emptyList(), vm.countryIso) }
    }
    if (changes.isEmpty()) return
    var hidden by rememberSaveable(pending.version) { mutableStateOf(false) }
    var review by rememberSaveable(pending.version) { mutableStateOf(false) }
    if (!hidden) {
        Banner(
            res.getString(R.string.card_update_banner, details.displayName, CardUpdateText.kinds(res, changes)),
            icon = Icons.Rounded.Verified,
            action = stringResource(R.string.card_update_review), onAction = { review = true },
            onDismiss = { hidden = true }, dismissLabel = stringResource(R.string.card_update_not_now),
        )
    }
    if (review) CardUpdateDialog(vm, navId, key, details.displayName, link.fingerprint, changes) { review = false }
}

/**
 * A held card about the contact on screen: "Link it?" for a contact without a link, the different-signer warning with
 * "Trust the new card" for one linked to another card or key (H1, L3).
 */
@Composable
private fun HeldCardBanner(vm: AppViewModel, key: String, name: String, linkedFingerprint: String?, offer: HeldOffer) {
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val held = offer.card
    var trust by remember(held) { mutableStateOf(false) }
    fun linkIt() = scope.launch {
        if (!CardInbox.linkHeld(vm.c, key, held)) vm.toast(res.getString(R.string.card_link_failed))
    }
    when (offer.kind) {
        HeldOffer.Kind.LINK -> Banner(
            res.getString(R.string.card_held_offer, name, Bidi.ltr(held.fingerprint)), icon = Icons.Rounded.Badge,
            action = stringResource(R.string.card_link), onAction = { linkIt() },
            onDismiss = { scope.launch { CardInbox.dropHeld(vm.c, held) } }, dismissLabel = stringResource(R.string.card_held_dont_link),
        )
        HeldOffer.Kind.DIFFERENT_SIGNER -> Banner(
            res.getString(R.string.card_held_different, name, Bidi.ltr(held.fingerprint)), icon = Icons.Rounded.GppMaybe, tone = BannerTone.WARNING,
            action = stringResource(R.string.card_trust_new), onAction = { trust = true },
            onDismiss = { scope.launch { CardInbox.dropHeld(vm.c, held) } }, dismissLabel = stringResource(R.string.card_update_ignore),
        )
    }
    if (trust) TrustNewCardDialog(name, linkedFingerprint, held.fingerprint, onConfirm = { trust = false; linkIt() }, onDismiss = { trust = false })
}

/** "Trust the new card for Ana?" with both keys, so the user can compare them with Ana (H1, L3). */
@Composable
private fun TrustNewCardDialog(name: String, linked: String?, new: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.card_trust_title, name),
        text = stringResource(R.string.card_trust_body, name, Bidi.ltr(linked.orEmpty().ifEmpty { "-" }), Bidi.ltr(new)),
        confirmLabel = stringResource(R.string.card_trust_new),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        icon = Icons.Rounded.GppMaybe,
    )
}

/**
 * The changes, each with its tick: what the card itself changed is ticked; the name, removals and replacements of a
 * value the user wrote themselves never are (H1, M2). Apply or Ignore.
 */
@Composable
private fun CardUpdateDialog(vm: AppViewModel, navId: Long, key: String, name: String, fingerprint: String, changes: List<CardChange>, onDone: () -> Unit) {
    val res = LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chosen = remember(changes) { mutableStateListOf<CardChange>().apply { addAll(changes.filter { it.preselected }) } }
    var busy by remember { mutableStateOf(false) }
    fun run(list: List<CardChange>) {
        busy = true
        scope.launchVault(context as? FragmentActivity, { busy = false; vm.toast(res.getString(R.string.card_update_failed)) }) {
            val out = CardUpdateApply.save(vm.c, navId, key, list, vm.countryIso)
            busy = false
            when (out) {
                CardUpdateApply.Outcome.SAVED -> vm.toast(res.getString(R.string.card_update_applied))
                CardUpdateApply.Outcome.FAILED -> vm.toast(res.getString(R.string.card_update_failed))
                CardUpdateApply.Outcome.NOTHING -> Unit
            }
            onDone()
        }
    }
    ParleyDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text(stringResource(R.string.card_update_title, name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.card_update_body, Bidi.ltr(fingerprint)), style = MaterialTheme.typography.bodyMedium)
                changes.forEach { c ->
                    val on = c in chosen
                    Row(
                        Modifier.fillMaxWidth().toggleable(on, enabled = !busy, role = Role.Checkbox) { if (it) chosen.add(c) else chosen.remove(c) }
                            .padding(vertical = Spacing.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(on, null)
                        Column(Modifier.padding(start = Spacing.s)) {
                            Text(
                                CardUpdateText.fieldLabel(res, c), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(CardUpdateText.change(res, c), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ run(changes.filter { it in chosen }) }, enabled = !busy && chosen.isNotEmpty()) {
                Text(stringResource(if (chosen.size == changes.size) R.string.card_update_apply_all else R.string.card_update_apply))
            }
        },
        dismissButton = { TextButton({ run(emptyList()) }, enabled = !busy) { Text(stringResource(R.string.card_update_ignore)) } },
    )
}

/**
 * What the signed cards in something just received mean (a scanned code, an opened file, pasted text): one line each,
 * with "Open" for an update waiting on a contact's page, "Link" for a first card (H1) and "Trust the new card" for a
 * different signer. A note says when the same text also holds cards without a signature (M1). Nothing shows for an
 * ordinary vCard.
 */
@Suppress("CyclomaticComplexMethod") // One line per kind of result.
@Composable
fun CardArrivalNotes(vm: AppViewModel, text: String?, onOpen: (() -> Unit)? = null) {
    val res = LocalResources.current
    val results = remember(text) { mutableStateListOf<CardInbox.Result>() }
    var unsigned by remember(text) { mutableStateOf(0) }
    LaunchedEffect(text) {
        if (text == null) return@LaunchedEffect
        val got = runCatching { CardInbox.receive(vm.c, text, vm.countryIso) }.getOrDefault(emptyList())
        unsigned = if (got.isEmpty()) 0 else withContext(Dispatchers.Default) { (SignedCards.count(text) - got.size).coerceAtLeast(0) }
        results.clear()
        results.addAll(got)
    }
    val scope = rememberCoroutineScope()
    var trusting by remember(text) { mutableStateOf<CardInbox.Result.DifferentSigner?>(null) }
    fun linkAt(i: Int, key: String, card: SignedCard, navId: Long, name: String) = scope.launch {
        if (CardInbox.link(vm.c, key, card)) {
            if (i < results.size) results[i] = CardInbox.Result.Linked(navId, name)
        } else {
            vm.toast(res.getString(R.string.card_link_failed))
        }
    }
    results.forEachIndexed { i, r ->
        fun open(id: Long) {
            onOpen?.invoke()
            scope.launch { vm.navigate(NavEvent.Contact(id)) }
        }
        when (r) {
            is CardInbox.Result.Update -> Banner(
                res.getString(R.string.card_in_update, r.name), icon = Icons.Rounded.Verified,
                action = stringResource(R.string.card_in_open), onAction = { open(r.navId) },
            )
            is CardInbox.Result.Current -> Banner(res.getString(R.string.card_in_current, r.name), icon = Icons.Rounded.Verified)
            is CardInbox.Result.Linked -> Banner(res.getString(R.string.card_in_linked, r.name), icon = Icons.Rounded.Verified)
            is CardInbox.Result.Offer -> Banner(
                res.getString(R.string.card_in_offer, r.name, Bidi.ltr(r.card.fingerprint)), icon = Icons.Rounded.Badge,
                action = stringResource(R.string.card_link), onAction = { linkAt(i, r.key, r.card, r.navId, r.name) },
            )
            CardInbox.Result.Held -> Banner(res.getString(R.string.card_in_held), icon = Icons.Rounded.Badge)
            is CardInbox.Result.DifferentSigner -> Banner(
                res.getString(R.string.card_in_different, r.name, Bidi.ltr(r.card.fingerprint)), icon = Icons.Rounded.GppMaybe, tone = BannerTone.WARNING,
                action = stringResource(R.string.card_trust_new), onAction = { trusting = r },
            )
            CardInbox.Result.Broken -> Banner(res.getString(R.string.card_in_broken), icon = Icons.Rounded.GppMaybe, tone = BannerTone.WARNING)
            CardInbox.Result.Unchecked -> Banner(res.getString(R.string.card_in_unchecked), icon = Icons.Rounded.Info)
            CardInbox.Result.Quiet -> Unit
        }
    }
    if (unsigned > 0) Banner(pluralStringResource(R.plurals.card_in_unsigned, unsigned, unsigned), icon = Icons.Rounded.Info)
    trusting?.let { r ->
        TrustNewCardDialog(r.name, r.linked, r.card.fingerprint, onConfirm = {
            trusting = null
            val i = results.indexOf(r)
            if (i >= 0) linkAt(i, r.key, r.card, r.navId, r.name)
        }, onDismiss = { trusting = null })
    }
}

/** Signed cards are a few kB; larger files (whole address books) are imported without the check. */
private const val SIGNED_CHECK_MAX = 2L shl 20

/** The text of the file at [uri] when it may hold a signed card (I14), for [CardArrivalNotes]; null otherwise. */
@Composable
fun rememberSignedCardText(vm: AppViewModel, uri: Uri): State<String?> = produceState<String?>(null, uri) {
    value = withContext(Dispatchers.IO) {
        runCatching {
            vm.c.appContext.contentResolver.openInputStream(uri)?.use { String(Bounded.readBytes(it, SIGNED_CHECK_MAX, "vCard"), Charsets.UTF_8) }
        }.getOrNull()?.takeIf { SignedCards.mayHold(it) }
    }
}
