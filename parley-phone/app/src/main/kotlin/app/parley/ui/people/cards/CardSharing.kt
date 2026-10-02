package app.parley.ui.people.cards

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.core.content.FileProvider
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.cards.ShareMethod
import app.parley.common.cards.SignedCards
import app.parley.common.people.MeCard
import app.parley.common.people.MeCards
import app.parley.data.DataContainer
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
     * [card] with [parts] as a vCard, signed. Signing reads the sealed key, so it runs off the main thread; when the key
     * can't be read right now the card goes out unsigned, as it did before.
     */
    suspend fun vcard(c: DataContainer, card: MeCard, parts: Set<MeCards.Part>): String = withContext(Dispatchers.IO) {
        val plain = MeCards.vcard(card, parts)
        if (card.isEmpty) return@withContext plain
        runCatching { c.people.cardIdentity.sign(card, parts) }.getOrNull()?.let { SignedCards.attach(plain, it) } ?: plain
    }

    /** The signed vCard for a composable (null while it is being made). */
    @Composable
    fun rememberVcard(vm: AppViewModel, card: MeCard, parts: Set<MeCards.Part>): State<String?> =
        produceState<String?>(null, card, parts) { value = vcard(vm.c, card, parts) }

    /** Someone got your card: a "Shared with" receipt with the numbers it had. Nothing is recorded for an empty card. */
    fun record(c: DataContainer, name: String, number: String?, method: ShareMethod, phones: List<String>) {
        if (name.isBlank() && number.isNullOrBlank()) return
        c.scope.launch { runCatching { c.people.shareLedger.record(name, number, method, phones) } }
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
    }

    private const val SWAP_WINDOW_MS = 10 * 60 * 1000L

    /** Writes [vcard] to Parley's share folder and hands it to the app you choose. */
    fun shareFile(context: Context, vcard: String, subject: String): Boolean = runCatching {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "my-card.vcf")
        file.writeText(vcard)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/x-vcard").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.me_share_chooser)))
    }.onFailure { showMessage(context, context.getString(R.string.me_share_failed)) }.isSuccess
}
