package app.parley.ui.people.cards

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import app.parley.common.PhoneIdentity
import app.parley.common.cards.CardChange
import app.parley.common.cards.CardField
import app.parley.common.cards.CardFields
import app.parley.common.cards.CardLinks
import app.parley.common.people.ContactRef
import app.parley.common.people.MeCards
import app.parley.common.people.SocialProfiles
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.PostalItem
import app.parley.ui.contact.SaveContactUseCase
import app.parley.ui.people.BackgroundChange

/** Turning a reviewed card update into an edit of the contact (I14). */
object CardUpdateApply {
    /** The contact's own fields, as a card update compares them. */
    fun fieldsOf(d: ContactDetails): CardFields = CardFields(
        name = d.composedName.ifBlank { d.displayName },
        phones = d.phones.map { it.value }.filter { it.isNotBlank() },
        emails = d.emails.map { it.value }.filter { it.isNotBlank() },
        company = d.company,
        title = d.title,
        websites = d.websites.map { it.value }.filter { it.isNotBlank() },
        address = d.addresses.firstOrNull()?.formatted.orEmpty(),
    )

    /** Every address of the contact: an update replaces only the one that is the card's previous address (M2). */
    fun addressesOf(d: ContactDetails): List<String> = d.addresses.map { it.formatted }.filter { it.isNotBlank() }

    /**
     * [d] with [changes] made: a replaced number keeps its row (type, label, default), a removed one goes, a new one is
     * added as a mobile number; the same for email addresses and websites (a profile as a website labelled with its
     * service). The name becomes first and last name as My card splits it.
     */
    @Suppress("CyclomaticComplexMethod") // One branch per field a card has.
    fun apply(d: ContactDetails, changes: List<CardChange>, region: String?): ContactDetails {
        var out = d
        for (ch in changes) {
            out = when (ch.field) {
                CardField.NAME -> ch.new?.let { n ->
                    val (given, family) = MeCards.splitName(n)
                    out.copy(prefix = "", given = given, middle = "", family = family, suffix = "")
                } ?: out
                CardField.PHONE -> out.copy(phones = items(out.phones, ch, Phone.TYPE_MOBILE) { a, b -> PhoneIdentity.same(a, b, region) })
                CardField.EMAIL -> out.copy(emails = items(out.emails, ch, Email.TYPE_HOME) { a, b -> a.equals(b, ignoreCase = true) })
                CardField.WEBSITE -> {
                    val type = if (ch.label != null) SocialProfiles.TYPE_CUSTOM else Website.TYPE_HOMEPAGE
                    val same = { a: String, b: String -> a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true) }
                    out.copy(websites = items(out.websites, ch, type, ch.label, same))
                }
                CardField.COMPANY -> out.copy(company = ch.new.orEmpty())
                CardField.TITLE -> out.copy(title = ch.new.orEmpty())
                CardField.ADDRESS -> out.copy(addresses = address(out.addresses, ch))
            }
        }
        return out
    }

    private fun items(list: List<DataItem>, ch: CardChange, type: Int, label: String? = null, same: (String, String) -> Boolean): List<DataItem> {
        val old = ch.old
        val new = ch.new
        val at = old?.let { o -> list.indexOfFirst { same(it.value, o) } } ?: -1
        return when {
            at >= 0 && new != null -> list.toMutableList().also { it[at] = it[at].copy(value = new) }
            at >= 0 -> list.filterIndexed { i, _ -> i != at }
            new != null && list.none { same(it.value, new) } -> list + DataItem(value = new, type = type, label = label)
            else -> list
        }
    }

    private fun address(list: List<PostalItem>, ch: CardChange): List<PostalItem> {
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val at = ch.old?.let { o -> list.indexOfFirst { key(it.formatted) == key(o) } } ?: -1
        val new = ch.new
        return when {
            at >= 0 && new != null -> list.toMutableList().also { it[at] = PostalItem(id = it[at].id, street = new, type = it[at].type, label = it[at].label) }
            at >= 0 -> list.filterIndexed { i, _ -> i != at }
            // Added after the user's own addresses, which keep their place.
            new != null -> list + PostalItem(street = new, type = StructuredPostal.TYPE_HOME)
            else -> list
        }
    }

    /** The editor's save request for [chosen] on [ref], read fresh; null when the contact is gone. */
    private suspend fun request(c: DataContainer, ref: ContactRef, chosen: List<CardChange>, region: String?): SaveContactUseCase.Request? = when (ref) {
        is ContactRef.Private -> c.vault.details(ref.vaultId)?.let { loaded ->
            SaveContactUseCase.Request(
                original = null, draft = apply(loaded, chosen, region), account = null, photo = null, removePhoto = false,
                toVault = true, vaultId = ref.vaultId, background = BackgroundChange.None, pickedLinks = emptyMap(), vaultLoaded = loaded,
            )
        }
        is ContactRef.Device -> c.contacts.editable(ref.contactId)?.let { original ->
            val account = original.rawContacts.firstOrNull { it.id == original.editRawId }?.account ?: AccountRef(null, null)
            SaveContactUseCase.Request(
                original = original, draft = apply(original, chosen, region), account = account, photo = null, removePhoto = false,
                toVault = false, vaultId = null, background = BackgroundChange.None, pickedLinks = emptyMap(),
            )
        }
    }

    /** The outcome of [save]. */
    enum class Outcome { SAVED, NOTHING, FAILED }

    /**
     * Applies [chosen] of the update waiting for the contact [navId] (Parley key [key]) through the editor's own save,
     * then marks the update as seen. An empty [chosen] is "Ignore": the update is only marked as seen. A private
     * contact's details need the vault open (the caller asks for the unlock first).
     */
    suspend fun save(c: DataContainer, navId: Long, key: String, chosen: List<CardChange>, region: String?): Outcome {
        val links = c.people.cardLinks
        if (chosen.isNotEmpty()) {
            val ref = ContactRef.ofNavId(navId) ?: return Outcome.FAILED
            if (request(c, ref, chosen, region)?.let { SaveContactUseCase(c)(it) } !is SaveContactUseCase.Outcome.Saved) return Outcome.FAILED
        }
        // The key may have changed with the save (a device contact's name is part of some lookup keys): settle by card id.
        val cardId = links.book.value.links[key]?.cardId
        links.update { b -> (cardId?.let { b.byCardId(it) } ?: b.links[key]?.let { key to it })?.let { (k, l) -> b.put(k, CardLinks.settle(l)) } ?: b }
        return if (chosen.isEmpty()) Outcome.NOTHING else Outcome.SAVED
    }
}
