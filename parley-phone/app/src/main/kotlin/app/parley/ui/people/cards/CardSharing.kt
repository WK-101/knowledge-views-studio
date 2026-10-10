package app.parley.ui.people.cards

import app.parley.common.security.Concealed
import app.parley.data.security.Concealment
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.cards.ShareMethod
import app.parley.common.cards.ShareReceipt
import app.parley.common.people.ContactRef
import app.parley.common.cards.SignedCards
import app.parley.common.people.MeCard
import app.parley.common.people.MeCards
import app.parley.data.DataContainer
import app.parley.data.people.MeCardDetails
import app.parley.ui.showMessage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sharing My card (I14, I22): the vCard every share uses, signed with the card's key so a contact's Parley can tell a
 * newer card from the same person, and the "Shared with" receipts.
 */
object CardSharing {
    /**
     * The one card every share signs (M4): My card with the phone's profile filling in what it lacks, read once the
     * profile has loaded, so the QR code, the file, "Send my card" and the swap all sign the same content.
     */
    suspend fun shareable(c: DataContainer): MeCard =
        MeCards.merge(c.people.me.card.value, runCatching { c.people.me.profile() }.getOrNull())

    /**
     * My card with [parts] as a vCard. A share of only what a signed card carries ([MeCards.isSignable]) is signed:
     * signing reads the sealed key, so it runs off the main thread, and when the key can't be read right now the card
     * goes out unsigned, as it did before. A share with more (name details, dates, relations, the note, the photo…) is
     * the whole card as Parley exports a contact, unsigned.
     */
    suspend fun vcard(c: DataContainer, parts: Set<MeCards.Part>): String = withContext(Dispatchers.IO) {
        if (!MeCards.isSignable(parts)) {
            val profile = runCatching { c.people.me.profile() }.getOrNull()
            val d = MeCardDetails.withProfile(c.people.me.details.value, profile)
            return@withContext MeCardDetails.vcard(d, parts, if (MeCards.Part.PHOTO in parts) c.people.me.photoBytes() else null)
        }
        val card = shareable(c)
        val plain = MeCards.vcard(card, parts)
        if (card.isEmpty) return@withContext plain
        runCatching { c.people.cardIdentity.sign(card, parts) }.getOrNull()?.let { SignedCards.attach(plain, it) } ?: plain
    }

    /** The signed vCard for a composable (null while it is being made; one signature per change of [parts] or the card). */
    @Composable
    fun rememberVcard(vm: AppViewModel, parts: Set<MeCards.Part>): State<String?> {
        val own by vm.c.people.me.details.collectAsStateWithLifecycle()
        return produceState<String?>(null, own, parts) { value = vcard(vm.c, parts) }
    }

    /**
     * Someone got your card: a "Shared with" receipt with the numbers it had. Nothing is recorded for an empty card.
     * A private contact's number gets a receipt that names no one (M7): its name and number stay in the vault.
     */
    fun record(c: DataContainer, name: String, number: String?, method: ShareMethod, phones: List<String>) {
        if (name.isBlank() && number.isNullOrBlank()) return
        c.scope.launch {
            runCatching {
                val private = number?.takeIf { it.isNotBlank() }?.let { n -> runCatching { c.vault.lookup(n) }.getOrNull()?.first }
                c.people.shareLedger.record(name, number, method, phones, contactKey = private?.let(ContactRef::privateKey))
            }
        }
    }

    /**
     * "Shared with" as shown: private contacts' receipts get their name and number from the vault, and are left out in
     * discreet mode or when the contact is gone (M7).
     */
    @Composable
    fun rememberShownReceipts(vm: AppViewModel): State<List<ShareReceipt>> {
        val store = vm.c.people.shareLedger
        LaunchedEffect(Unit) { store.load() }
        val receipts by store.receipts.collectAsStateWithLifecycle()
        val settings by vm.c.settings.settings.collectAsStateWithLifecycle()
        val discreet = settings.hideVault
        // I21: after a duress unlock nobody is listed (who you gave your number to can matter as much as private contacts).
        val hidden = settings.duress != null
        return produceState(if (hidden) emptyList() else receipts.filter { it.contactKey == null }, receipts, discreet, hidden) {
            value = withContext(Dispatchers.IO) { shown(vm.c, receipts, discreet) }
        }
    }

    /** [receipts] as shown (see [rememberShownReceipts]); none after a duress unlock. */
    suspend fun shown(c: DataContainer, receipts: List<ShareReceipt>, discreet: Boolean): List<ShareReceipt> =
        if (Concealment.hides(Concealed.SHARED_WITH)) emptyList() else shownInList(c, receipts, discreet)

    private suspend fun shownInList(c: DataContainer, receipts: List<ShareReceipt>, discreet: Boolean): List<ShareReceipt> = receipts.mapNotNull { r ->
        val vaultId = ContactRef.vaultIdOf(r.contactKey) ?: return@mapNotNull r.takeIf { r.contactKey == null }
        if (discreet) return@mapNotNull null
        runCatching { c.vault.summary(vaultId) }.getOrNull()?.let { s -> r.copy(name = s.name, number = s.numbers.firstOrNull()) }
    }

    /** My card's numbers as they are shared now (Parley's copy; the phone's profile only fills in what's missing). */
    fun currentPhones(c: DataContainer): List<String> = c.people.me.card.value.cleaned().phones

    // ---- QR swap: "Scan theirs" from your own code means both phones now hold each other's card.

    @Volatile private var swapSince = 0L

    /** Your code was on screen and you went on to scan theirs. */
    fun swapStarted() {
        swapSince = System.currentTimeMillis()
    }

    /** A card scanned within a few minutes of showing yours: they have yours, so it's a swap. */
    fun swapScanned(c: DataContainer, name: String, number: String?) {
        if (System.currentTimeMillis() - swapSince > SWAP_WINDOW_MS) return
        swapSince = 0L
        record(c, name, number, ShareMethod.QR_SWAP, currentPhones(c))
        markShared(c)
    }

    /** Your signed card reached someone (a swap, a file sent): its key is the one people now know (M5). */
    fun markShared(c: DataContainer) {
        c.scope.launch(Dispatchers.IO) { runCatching { c.people.cardIdentity.markShared() } }
    }

    private const val SWAP_WINDOW_MS = 10 * 60 * 1000L

    /**
     * Writes [vcard] to Parley's share folder and hands it to the app you choose. A signed card handed on counts as
     * shared ([markShared]); showing the QR code alone doesn't.
     */
    fun shareFile(c: DataContainer, context: Context, vcard: String, subject: String): Boolean = runCatching {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "my-card.vcf")
        file.writeText(vcard)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/x-vcard").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.me_share_chooser)))
        if (SignedCards.mayHold(vcard)) markShared(c)
    }.onFailure { showMessage(context, context.getString(R.string.me_share_failed)) }.isSuccess
}
