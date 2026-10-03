package app.parley.ui.people.cards

import app.parley.common.PhoneIdentity
import app.parley.common.cards.CardCheck
import app.parley.common.cards.CardFields
import app.parley.common.cards.CardIntake
import app.parley.common.cards.CardLinkBook
import app.parley.common.cards.CardLinks
import app.parley.common.cards.HeldCard
import app.parley.common.cards.HeldOffer
import app.parley.common.cards.SignedCard
import app.parley.common.cards.SignedCards
import app.parley.common.people.ContactRef
import app.parley.common.suspendRunCatching
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Signed cards arriving from outside (I14): a scanned QR code, a shared or opened .vcf file, pasted text. Each one is
 * checked and matched to the contact it belongs to by its card id; a newer version from the same key waits on that
 * contact's page as an update the user reviews. Nothing about the contact changes here, and no card is linked to a
 * contact without the user saying so (H1): trust on first explicit link, the key pinned after that.
 */
object CardInbox {
    /** What happened to one signed card, for the screen that received it. */
    sealed interface Result {
        /** A newer card for [name]: their page offers it. */
        data class Update(val navId: Long, val name: String) : Result

        /** [name] already has this card (or a newer one). */
        data class Current(val navId: Long, val name: String) : Result

        /** The user linked the card to [name]: their future updates will be offered. */
        data class Linked(val navId: Long, val name: String) : Result

        /** "This card says it's [name]. Link it?" The contact (Parley [key]) has no card linked yet. */
        data class Offer(val navId: Long, val name: String, val key: String, val card: SignedCard) : Result

        /** Nobody has this card yet: the page of the contact it is saved as will ask to link it. */
        data object Held : Result

        /**
         * [name] (Parley [key]) is linked to another card or key ([linked]'s fingerprint): this one ([card]) offers
         * nothing; "Trust the new card" is the user's explicit way to switch.
         */
        data class DifferentSigner(val navId: Long, val name: String, val key: String, val linked: String, val card: SignedCard) : Result

        /** The card was changed after it was signed. */
        data object Broken : Result

        /** The signature couldn't be checked right now (L2): neither "verified" nor "changed". */
        data object Unchecked : Result

        /** About a private contact while discreet mode hides them: nothing is said. */
        data object Quiet : Result
    }

    /**
     * Checks every signed card in [text] (cheap when there is none) and files it; empty when [text] holds none. A
     * broken card is always reported; a signed one that can't be filed right now is "couldn't check", never verified.
     */
    suspend fun receive(c: DataContainer, text: String, region: String?): List<Result> = withContext(Dispatchers.IO) {
        if (!SignedCards.mayHold(text)) return@withContext emptyList()
        val checks = SignedCards.check(text)
        val loaded = suspendRunCatching { c.people.cardLinks.load() }.getOrDefault(false)
        checks.map { check ->
            when (check) {
                is CardCheck.Broken -> Result.Broken
                is CardCheck.Signed ->
                    if (!loaded) Result.Unchecked else suspendRunCatching { receiveOne(c, check.card, region) }.getOrDefault(Result.Unchecked)
            }
        }
    }

    private suspend fun receiveOne(c: DataContainer, card: SignedCard, region: String?): Result {
        val store = c.people.cardLinks
        val now = System.currentTimeMillis()
        val discreet = c.settings.settings.value.hideVault
        val book = store.book.value
        // Who is still there, for a card already linked; who has one of its numbers, for a card that isn't.
        val linkedKey = book.byCardId(card.cardId)?.first
        val linkedWho = linkedKey?.let { resolve(c, it) }
        val match = if (linkedKey == null) matchByNumbers(c, card.fields, region, includePrivate = !discreet) else null
        var outcome: CardIntake.Outcome? = null
        if (!store.update { b -> CardIntake.receive(b, card, match?.first, now) { it == linkedKey && linkedWho != null }.also { outcome = it }.book }) {
            return Result.Unchecked
        }
        val o = outcome ?: return Result.Unchecked
        val key = o.key ?: return Result.Held
        val who = (if (key == linkedKey) linkedWho else match?.second) ?: return Result.Held
        if (discreet && ContactRef.isPrivateKey(key)) return Result.Quiet
        return resultOf(o, key, who, card)
    }

    private fun resultOf(o: CardIntake.Outcome, key: String, who: Pair<Long, String>, card: SignedCard): Result =
        when (o.kind) {
            CardIntake.Kind.UPDATE -> Result.Update(who.first, who.second)
            CardIntake.Kind.CURRENT -> Result.Current(who.first, who.second)
            CardIntake.Kind.OFFER -> Result.Offer(who.first, who.second, key, card)
            CardIntake.Kind.DIFFERENT_SIGNER -> Result.DifferentSigner(who.first, who.second, key, o.book.links[key]?.fingerprint.orEmpty(), card)
            CardIntake.Kind.HELD -> Result.Held
        }

    /**
     * The user chose to link [card] to the contact under [key] ("Link it", or "Trust the new card" over another card):
     * the one place a link is made or replaced (H1). False when it couldn't be stored right now.
     */
    suspend fun link(c: DataContainer, key: String, card: SignedCard): Boolean = withContext(Dispatchers.IO) {
        if (key.isEmpty()) return@withContext false
        c.people.cardLinks.update { it.link(key, card, System.currentTimeMillis()) }
    }

    /** The user linked the held [card] to [key] from the contact's page. */
    suspend fun linkHeld(c: DataContainer, key: String, card: HeldCard): Boolean = link(c, key, card.toCard())

    /** "Don't link": the held card is forgotten. */
    suspend fun dropHeld(c: DataContainer, card: HeldCard) = withContext(Dispatchers.IO) {
        c.people.cardLinks.update { it.dropHeld(card.cardId, card.publicKey) }
    }

    /** (navigation id, name) of the contact under Parley key [key], or null when it no longer exists. */
    private suspend fun resolve(c: DataContainer, key: String): Pair<Long, String>? {
        val vaultId = ContactRef.vaultIdOf(key)
        if (vaultId != null) return c.vault.summary(vaultId)?.let { ContactRef.Private(vaultId).navId to it.name }
        val id = c.contacts.currentOf(key, null)?.first ?: return null
        return id to c.contacts.details(id)?.displayName.orEmpty()
    }

    /** The single contact (device or private) holding one of [fields]' numbers, as (Parley key, (navId, name)). */
    private suspend fun matchByNumbers(c: DataContainer, fields: CardFields, region: String?, includePrivate: Boolean): Pair<String, Pair<Long, String>>? {
        val found = LinkedHashMap<String, Pair<Long, String>>()
        for (n in fields.phones) {
            c.contacts.lookup(n)?.takeIf { !it.work }?.let { info ->
                val key = info.lookupKey ?: c.contacts.lookupKeyOf(info.contactId)
                if (!key.isNullOrEmpty()) found[key] = info.contactId to info.name
            }
            if (includePrivate) c.vault.lookup(n, region)?.let { (id, info) -> found[ContactRef.privateKey(id)] = ContactRef.Private(id).navId to info.name }
        }
        return found.entries.singleOrNull()?.toPair()
    }

    /**
     * What a held card means for the contact whose page is open ([key], [d]): a card with one of its numbers or email
     * addresses (or its link's card id) is offered to link, or shown as a different signer. Nothing is linked here.
     */
    fun heldOffer(book: CardLinkBook, key: String, d: ContactDetails, region: String?): HeldOffer? {
        if (key.isEmpty() || book.held.isEmpty()) return null
        val phones = d.phones.map { it.value }.filter { it.isNotBlank() }
        val emails = d.emails.map { it.value.lowercase() }.filter { it.isNotBlank() }
        return CardLinks.offer(book.links[key], book.held, System.currentTimeMillis()) { f ->
            f.phones.any { p -> phones.any { PhoneIdentity.same(it, p, region) } } || f.emails.any { it.lowercase() in emails }
        }
    }
}
