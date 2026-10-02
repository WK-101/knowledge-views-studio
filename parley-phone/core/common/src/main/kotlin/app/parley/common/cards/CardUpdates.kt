package app.parley.common.cards

import app.parley.common.PhoneNumbers
import kotlinx.serialization.Serializable

/** A newer card that arrived and waits for the user: "Ana sent an updated card". */
@Serializable
data class PendingCard(val version: Long, val fields: CardFields, val receivedAt: Long)

/**
 * A contact linked to someone's signed card, kept under the contact's Parley key: the card id, the key that signs it
 * (base64url; its fingerprint is what people compare) and the newest version the user has seen, with its fields, so
 * the next one can say what changed. [pending] is a newer card not applied or ignored yet.
 */
@Serializable
data class CardLink(
    val cardId: String,
    val publicKey: String,
    val version: Long,
    val fields: CardFields,
    val linkedAt: Long,
    val pending: PendingCard? = null,
) {
    val fingerprint: String get() = SignedCards.fingerprint(publicKey)
}

/** What an arriving signed card means for the contact it belongs to. */
enum class CardArrival {
    /** No contact has this card yet. */
    NEW,

    /** A newer version from the same key: offered as an update, never applied by itself. */
    UPDATE,

    /** The version the contact already has (or one already waiting). */
    SAME,

    /** An older version than the one the contact has: ignored. */
    OLDER,

    /** The card id the contact has, signed by another key: someone else's card claiming to be theirs. */
    DIFFERENT_SIGNER,
}

/** I14's rules for linking contacts to signed cards and taking in newer versions. */
object CardLinks {
    fun arrival(link: CardLink?, card: SignedCard): CardArrival = when {
        link == null || link.cardId != card.cardId -> CardArrival.NEW
        link.publicKey != card.publicKey -> CardArrival.DIFFERENT_SIGNER
        card.version <= link.version -> if (card.version == link.version) CardArrival.SAME else CardArrival.OLDER
        (link.pending?.version ?: Long.MIN_VALUE) >= card.version -> CardArrival.SAME
        else -> CardArrival.UPDATE
    }

    /** A contact linked to [card] as it is now (the version the user saved). */
    fun first(card: SignedCard, now: Long): CardLink = CardLink(card.cardId, card.publicKey, card.version, card.fields, now)

    /** [link] with [card] waiting as its update, when [arrival] says it is one; otherwise [link] unchanged. */
    fun receive(link: CardLink, card: SignedCard, now: Long): CardLink =
        if (arrival(link, card) == CardArrival.UPDATE) link.copy(pending = PendingCard(card.version, card.fields, now)) else link

    /**
     * The update was applied (all of it, some of it) or ignored: either way that version is now the one the user has
     * seen, so the next update is compared with it and the same one isn't offered again.
     */
    fun settle(link: CardLink): CardLink {
        val p = link.pending ?: return link
        return link.copy(version = p.version, fields = p.fields, pending = null)
    }
}

/** A field of a card, as the update dialog names it. */
enum class CardField { NAME, PHONE, EMAIL, COMPANY, TITLE, WEBSITE, ADDRESS }

/**
 * One change an update offers for the contact: [old] → [new]. Added has no [old], removed no [new]. [label] is a
 * profile's service ("Instagram") for a website row.
 */
data class CardChange(val field: CardField, val old: String?, val new: String?, val label: String? = null) {
    val added: Boolean get() = old == null && new != null
    val removed: Boolean get() = new == null && old != null
}

/**
 * What an updated card changes for the contact. It compares the card the user had ([before], null for a contact just
 * linked) with the new one ([after]), and only offers what the contact itself doesn't reflect yet ([contact], the
 * contact's own fields): a number the card dropped is removed only if the contact still has it, a new one is added
 * only if it's missing, and a number that replaced another shows as one change. Numbers and fields the user added to
 * the contact themselves are never touched.
 */
object CardDiff {
    fun changes(before: CardFields?, after: CardFields, contact: CardFields, region: String? = null): List<CardChange> {
        val a = after.canonical()
        val b = before?.canonical()
        val out = ArrayList<CardChange>()
        scalar(out, CardField.NAME, b?.name, a.name, contact.name, removable = false)
        out += list(CardField.PHONE, b?.phones, a.phones, contact.phones) { x, y -> PhoneNumbers.same(x, y, region) }
        out += list(CardField.EMAIL, b?.emails, a.emails, contact.emails) { x, y -> x.equals(y, ignoreCase = true) }
        scalar(out, CardField.COMPANY, b?.company, a.company, contact.company)
        scalar(out, CardField.TITLE, b?.title, a.title, contact.title)
        val sameUrl = { x: String, y: String -> url(x) == url(y) }
        out += list(CardField.WEBSITE, b?.websites, a.websites, contact.websites, sameUrl)
        val labels = a.profiles.associate { it.url to it.label } + b?.profiles.orEmpty().associate { it.url to it.label }
        out += list(CardField.WEBSITE, b?.profiles?.map { it.url }, a.profiles.map { it.url }, contact.websites, sameUrl)
            .map { c -> c.copy(label = labels[c.new ?: c.old]) }
        scalar(out, CardField.ADDRESS, b?.address, a.address, contact.address, same = { x, y -> letters(x) == letters(y) })
        return out
    }

    private fun scalar(
        out: MutableList<CardChange>, field: CardField, before: String?, after: String, contact: String,
        removable: Boolean = true, same: (String, String) -> Boolean = { x, y -> x == y },
    ) {
        if (before != null && same(before, after)) return
        when {
            after.isNotEmpty() && !same(contact.trim(), after) -> out += CardChange(field, contact.trim().ifEmpty { null }, after)
            // Taken off the card: offered only while the contact still shows what the card said.
            after.isEmpty() && removable && !before.isNullOrEmpty() && same(contact.trim(), before) -> out += CardChange(field, before, null)
        }
    }

    private fun list(field: CardField, before: List<String>?, after: List<String>, contact: List<String>, same: (String, String) -> Boolean): List<CardChange> {
        fun List<String>.has(v: String) = any { same(it, v) }
        val removed = before.orEmpty().filter { !after.has(it) && contact.has(it) }
        val added = after.filter { (before == null || !before.has(it)) && !contact.has(it) }
        // A number that replaced another reads as one change ("+44 7700 900123 → +44 7700 900456").
        val n = minOf(removed.size, added.size)
        return (0 until n).map { CardChange(field, removed[it], added[it]) } +
            removed.drop(n).map { CardChange(field, it, null) } + added.drop(n).map { CardChange(field, null, it) }
    }

    private fun url(s: String) = s.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    private fun letters(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The fields the update touches, in the order the banner names them ("new number", "new email"…). */
    fun fields(changes: List<CardChange>): List<CardField> = CardField.entries.filter { f -> changes.any { it.field == f } }
}

/** A signed card received for nobody yet: linked to the contact it is saved as, once that contact's page opens. */
@Serializable
data class HeldCard(val cardId: String, val publicKey: String, val version: Long, val fields: CardFields, val receivedAt: Long)

/**
 * Every link of this phone, by Parley key, and the cards held for linking: one small document, sealed where it is
 * stored. Pure operations; the store writes the result.
 */
@Serializable
data class CardLinkBook(val links: Map<String, CardLink> = emptyMap(), val held: List<HeldCard> = emptyList()) {
    /** The contact (its Parley key) linked to [cardId], if any. */
    fun byCardId(cardId: String): Pair<String, CardLink>? = links.entries.firstOrNull { it.value.cardId == cardId }?.toPair()

    fun put(key: String, link: CardLink): CardLinkBook =
        copy(links = links.filterValues { it.cardId != link.cardId } + (key to link), held = held.filterNot { it.cardId == link.cardId })

    fun forget(key: String): CardLinkBook = if (key in links) copy(links = links - key) else this

    /** The contact's key changed (a sync, Make private, Make visible): its link follows, the newer one wins. */
    fun rekey(from: String, to: String): CardLinkBook {
        val moving = links[from] ?: return this
        val there = links[to]
        val kept = if (there != null && there.cardId == moving.cardId && there.version >= moving.version) there else moving
        return copy(links = links - from + (to to kept))
    }

    /** Holds [card] until a contact for it is saved; the newest version of a card wins, old and excess ones go. */
    fun hold(card: SignedCard, now: Long): CardLinkBook {
        val existing = held.firstOrNull { it.cardId == card.cardId }
        if (existing != null && existing.version >= card.version) return this
        val h = HeldCard(card.cardId, card.publicKey, card.version, card.fields, now)
        val kept = (listOf(h) + held.filterNot { it.cardId == card.cardId }).filter { now - it.receivedAt < HOLD_MS }.take(MAX_HELD)
        return copy(held = kept)
    }

    /** The held card [matches] picks for a contact, linked to [key] now; null when none matches. */
    fun linkHeld(key: String, now: Long, matches: (CardFields) -> Boolean): CardLinkBook? {
        if (key in links) return null
        val h = held.firstOrNull { now - it.receivedAt < HOLD_MS && matches(it.fields) } ?: return null
        return put(key, CardLink(h.cardId, h.publicKey, h.version, h.fields, now))
    }

    companion object {
        /** A card waits this long for its contact to be saved and opened. */
        const val HOLD_MS = 90L * 24 * 60 * 60 * 1000
        const val MAX_HELD = 50
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun encode(b: CardLinkBook): String = json.encodeToString(serializer(), b)

        fun decode(text: String?): CardLinkBook = if (text.isNullOrBlank()) CardLinkBook() else json.decodeFromString(serializer(), text)
    }
}
