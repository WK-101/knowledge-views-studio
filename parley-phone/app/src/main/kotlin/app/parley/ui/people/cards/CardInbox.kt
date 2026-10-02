package app.parley.ui.people.cards

import app.parley.common.PhoneNumbers
import app.parley.common.cards.CardArrival
import app.parley.common.cards.CardCheck
import app.parley.common.cards.CardFields
import app.parley.common.cards.CardLinks
import app.parley.common.cards.SignedCard
import app.parley.common.cards.SignedCards
import app.parley.common.people.ContactRef
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Signed cards arriving from outside (I14): a scanned QR code, a shared or opened .vcf file, pasted text. Each one is
 * checked and matched to the contact it belongs to by its card id; a newer version from the same key waits on that
 * contact's page as an update the user reviews. Nothing about the contact changes here.
 */
object CardInbox {
    /** What happened to one signed card, for the screen that received it. */
    sealed interface Result {
        /** A newer card for [name]: their page offers it. */
        data class Update(val navId: Long, val name: String) : Result

        /** [name] already has this card (or a newer one). */
        data class Current(val navId: Long, val name: String) : Result

        /** First card from [name], linked now: their future updates will be offered. */
        data class Linked(val navId: Long, val name: String) : Result

        /** Nobody has this card yet: it links to the contact once that contact is saved and opened. */
        data object Held : Result

        /** [name]'s card id, signed by another key: not offered. */
        data class DifferentSigner(val navId: Long, val name: String, val fingerprint: String) : Result

        /** The card was changed after it was signed. */
        data object Broken : Result

        /** About a private contact while discreet mode hides them: nothing is said. */
        data object Quiet : Result
    }

    /** Checks every signed card in [text] (cheap when there is none) and files it; empty when [text] holds none. */
    suspend fun receive(c: DataContainer, text: String, region: String?): List<Result> = withContext(Dispatchers.IO) {
        if (!SignedCards.mayHold(text)) return@withContext emptyList()
        val store = c.people.cardLinks
        if (!store.load()) return@withContext emptyList()
        SignedCards.check(text).map { check ->
            when (check) {
                is CardCheck.Broken -> Result.Broken
                is CardCheck.Signed -> runCatching { receiveOne(c, check.card, region) }.getOrDefault(Result.Held)
            }
        }
    }

    private suspend fun receiveOne(c: DataContainer, card: SignedCard, region: String?): Result {
        val store = c.people.cardLinks
        val now = System.currentTimeMillis()
        val discreet = c.settings.settings.value.hideVault
        val found = store.book.value.byCardId(card.cardId)
        if (found != null) {
            val (key, link) = found
            val who = resolve(c, key)
            if (who == null) {
                // The contact is gone: its link goes, and the card waits for a new one.
                store.update { it.forget(key).hold(card, now) }
                return Result.Held
            }
            val quiet = discreet && ContactRef.isPrivateKey(key)
            return when (CardLinks.arrival(link, card)) {
                CardArrival.UPDATE -> {
                    store.update { b -> b.links[key]?.let { b.put(key, CardLinks.receive(it, card, now)) } ?: b }
                    if (quiet) Result.Quiet else Result.Update(who.first, who.second)
                }
                CardArrival.DIFFERENT_SIGNER -> if (quiet) Result.Quiet else Result.DifferentSigner(who.first, who.second, card.fingerprint)
                else -> if (quiet) Result.Quiet else Result.Current(who.first, who.second)
            }
        }
        // A first card: linked to the one contact that has one of its numbers, else held for the contact about to be saved.
        val match = matchByNumbers(c, card.fields, region, includePrivate = !discreet)
        if (match != null) {
            val (key, who) = match
            store.update { it.put(key, CardLinks.first(card, now)) }
            return Result.Linked(who.first, who.second)
        }
        store.update { it.hold(card, now) }
        return Result.Held
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
     * A contact's page opened: a held card with one of its numbers or email addresses (the contact just saved from
     * that card) is linked to it now.
     */
    suspend fun linkHeld(c: DataContainer, key: String, d: ContactDetails, region: String?) = withContext(Dispatchers.IO) {
        if (key.isEmpty()) return@withContext
        val store = c.people.cardLinks
        if (!store.load() || store.book.value.held.isEmpty() || key in store.book.value.links) return@withContext
        val phones = d.phones.map { it.value }
        val emails = d.emails.map { it.value.lowercase() }
        store.update { b ->
            b.linkHeld(key, System.currentTimeMillis()) { f ->
                f.phones.any { p -> phones.any { PhoneNumbers.same(it, p, region) } } || f.emails.any { it.lowercase() in emails }
            } ?: b
        }
    }
}
