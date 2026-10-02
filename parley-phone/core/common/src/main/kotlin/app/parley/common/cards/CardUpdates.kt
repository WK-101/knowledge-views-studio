package app.parley.common.cards

import app.parley.common.PhoneNumbers
import app.parley.common.people.MeCards
import kotlinx.serialization.Serializable

/** A newer card that arrived and waits for the user: "Ana sent an updated card". [parts]: what that share includes. */
@Serializable
data class PendingCard(
    val version: Long,
    val fields: CardFields,
    val receivedAt: Long,
    val parts: Set<MeCards.Part> = CardFields.ALL_PARTS,
)

/**
 * A contact linked to someone's signed card, kept under the contact's Parley key: the card id, the key that signs it
 * (base64url; its fingerprint is what people compare) and the newest version the user has seen, with its fields, so
 * the next one can say what changed. [parts] are the parts of the card the user has seen (a share may include only
 * some); [pending] is a newer card not applied or ignored yet.
 *
 * Trust (H1): a link is made only by the user ("Link it to Ana", "Trust the new card"); after that the key is pinned,
 * and only cards signed by it are offered as updates.
 */
@Serializable
data class CardLink(
    val cardId: String,
    val publicKey: String,
    val version: Long,
    val fields: CardFields,
    val linkedAt: Long,
    val pending: PendingCard? = null,
    val parts: Set<MeCards.Part> = CardFields.ALL_PARTS,
) {
    val fingerprint: String get() = SignedCards.fingerprint(publicKey)
}

/** What an arriving signed card means for the contact it belongs to. */
enum class CardArrival {
    /** This contact isn't linked to this card (no link, or a link to another card): nothing is offered by itself. */
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
    fun arrival(link: CardLink?, card: SignedCard): CardArrival {
        val pending = link?.pending
        return when {
            link == null || link.cardId != card.cardId -> CardArrival.NEW
            link.publicKey != card.publicKey -> CardArrival.DIFFERENT_SIGNER
            !newer(card.version, card.parts, link.version, link.parts) ->
                if (card.version < link.version) CardArrival.OLDER else CardArrival.SAME
            pending != null && !newer(card.version, card.parts, pending.version, pending.parts) -> CardArrival.SAME
            else -> CardArrival.UPDATE
        }
    }

    /** A later version, or the same version sharing parts not seen yet (M3: a wider share isn't "the same card"). */
    private fun newer(version: Long, parts: Set<MeCards.Part>, seen: Long, seenParts: Set<MeCards.Part>): Boolean =
        version > seen || (version == seen && !seenParts.containsAll(parts))

    /** A contact linked to [card] as it is now (the version the user chose to link). */
    fun first(card: SignedCard, now: Long): CardLink = CardLink(card.cardId, card.publicKey, card.version, card.fields, now, parts = card.parts)

    /** [link] with [card] waiting as its update, when [arrival] says it is one; otherwise [link] unchanged. */
    fun receive(link: CardLink, card: SignedCard, now: Long): CardLink =
        if (arrival(link, card) == CardArrival.UPDATE) link.copy(pending = PendingCard(card.version, card.fields, now, card.parts)) else link

    /**
     * The update was applied (all of it, some of it) or ignored: either way that version is now the one the user has
     * seen, so the next update is compared with it and the same one isn't offered again. Parts the update didn't
     * share keep what the user saw before.
     */
    fun settle(link: CardLink): CardLink {
        val p = link.pending ?: return link
        return link.copy(
            version = maxOf(link.version, p.version),
            fields = CardFields.merge(link.fields, link.parts, p.fields, p.parts),
            parts = link.parts + p.parts,
            pending = null,
        )
    }

    /**
     * What a held card (H1) is for the contact whose page is open: [heldCard]s matching it ([matches]: one of its
     * numbers or email addresses, or the card id of its link) are offered one at a time, newest first. A contact
     * without a link is asked "Link it?"; a contact linked to another card or key gets the different-signer warning.
     * Nothing is linked here.
     */
    fun offer(link: CardLink?, held: List<HeldCard>, now: Long, matches: (CardFields) -> Boolean): HeldOffer? {
        val h = held.filter { now - it.receivedAt < CardLinkBook.HOLD_MS }.sortedByDescending { it.receivedAt }
            .firstOrNull { h ->
                val sameCard = link != null && link.cardId == h.cardId
                // The linked card's own newer versions are updates, never held offers.
                if (sameCard && link?.publicKey == h.publicKey) false else sameCard || matches(h.fields)
            } ?: return null
        return HeldOffer(h, if (link == null) HeldOffer.Kind.LINK else HeldOffer.Kind.DIFFERENT_SIGNER)
    }
}

/** A held card offered on a contact's page: "Link it?" or "A different card claims to be them". */
data class HeldOffer(val card: HeldCard, val kind: Kind) {
    enum class Kind { LINK, DIFFERENT_SIGNER }
}

/** A field of a card, as the update dialog names it. */
enum class CardField { NAME, PHONE, EMAIL, COMPANY, TITLE, WEBSITE, ADDRESS }

/**
 * One change an update offers for the contact: [old] → [new]. Added has no [old], removed no [new]. [label] is a
 * profile's service ("Instagram") for a website row. [preselected]: ticked when the dialog opens. Removals, names and
 * replacements of a value the user wrote themselves never are (H1, M2).
 */
data class CardChange(
    val field: CardField,
    val old: String?,
    val new: String?,
    val label: String? = null,
    val preselected: Boolean = new != null && old == null,
) {
    val added: Boolean get() = old == null && new != null
    val removed: Boolean get() = new == null && old != null
}

/**
 * What an updated card changes for the contact. It compares the card the user had ([before], null for a contact just
 * linked) with the new one ([after]), and only offers what the contact itself doesn't reflect yet ([contact], the
 * contact's own fields): a number the card dropped is removed only if the contact still has it, a new one is added
 * only if it's missing, and a number that replaced another shows as one change. Numbers and fields the user added to
 * the contact themselves are never touched.
 *
 * Parts (M3): a part the new card doesn't share ([afterParts]) is unknown, never "removed"; a part the earlier card
 * didn't share ([beforeParts]) is compared as on a first link (only what is missing is added). Addresses (M2) are
 * matched by the card's previous address among all of the contact's ([contactAddresses]), never "the first one".
 */
object CardDiff {
    @Suppress("LongParameterList") // The two cards with their parts, and the contact.
    fun changes(
        before: CardFields?,
        after: CardFields,
        contact: CardFields,
        region: String? = null,
        afterParts: Set<MeCards.Part> = CardFields.ALL_PARTS,
        beforeParts: Set<MeCards.Part> = CardFields.ALL_PARTS,
        contactAddresses: List<String>? = null,
    ): List<CardChange> {
        val a = after.canonical()
        val b = before?.canonical()
        fun known(p: MeCards.Part) = b?.takeIf { p in beforeParts }
        val out = ArrayList<CardChange>()
        if (MeCards.Part.NAME in afterParts) scalar(out, CardField.NAME, known(MeCards.Part.NAME)?.name, a.name, contact.name, removable = false)
        if (MeCards.Part.PHONES in afterParts) {
            out += list(CardField.PHONE, known(MeCards.Part.PHONES)?.phones, a.phones, contact.phones) { x, y -> PhoneNumbers.same(x, y, region) }
        }
        if (MeCards.Part.EMAILS in afterParts) {
            out += list(CardField.EMAIL, known(MeCards.Part.EMAILS)?.emails, a.emails, contact.emails) { x, y -> x.equals(y, ignoreCase = true) }
        }
        if (MeCards.Part.WORK in afterParts) {
            scalar(out, CardField.COMPANY, known(MeCards.Part.WORK)?.company, a.company, contact.company)
            scalar(out, CardField.TITLE, known(MeCards.Part.WORK)?.title, a.title, contact.title)
        }
        val sameUrl = { x: String, y: String -> url(x) == url(y) }
        if (MeCards.Part.WEBSITES in afterParts) out += list(CardField.WEBSITE, known(MeCards.Part.WEBSITES)?.websites, a.websites, contact.websites, sameUrl)
        if (MeCards.Part.PROFILES in afterParts) {
            val bp = known(MeCards.Part.PROFILES)?.profiles
            val labels = a.profiles.associate { it.url to it.label } + bp.orEmpty().associate { it.url to it.label }
            out += list(CardField.WEBSITE, bp?.map { it.url }, a.profiles.map { it.url }, contact.websites, sameUrl)
                .map { c -> c.copy(label = labels[c.new ?: c.old]) }
        }
        if (MeCards.Part.ADDRESS in afterParts) {
            address(out, known(MeCards.Part.ADDRESS)?.address, a.address, contactAddresses ?: listOf(contact.address).filter { it.isNotBlank() })
        }
        return out
    }

    @Suppress("LongParameterList")
    private fun scalar(
        out: MutableList<CardChange>, field: CardField, before: String?, after: String, contact: String,
        removable: Boolean = true, same: (String, String) -> Boolean = { x, y -> x == y },
    ) {
        if (before != null && same(before, after)) return
        val mine = contact.trim()
        when {
            after.isNotEmpty() && !same(mine, after) -> {
                // Ticked only when the contact is empty or still shows what the card said; a value the user wrote
                // themselves (it differs from the card's old one) is offered unticked, and the name never is.
                val tick = field != CardField.NAME && (mine.isEmpty() || (before != null && same(mine, before)))
                out += CardChange(field, mine.ifEmpty { null }, after, preselected = tick)
            }
            // Taken off the card: offered (unticked) only while the contact still shows what the card said.
            after.isEmpty() && removable && !before.isNullOrEmpty() && same(mine, before) -> out += CardChange(field, before, null, preselected = false)
        }
    }

    /** One address among the contact's: the card's previous one is replaced where the contact still has it (M2). */
    private fun address(out: MutableList<CardChange>, before: String?, after: String, contact: List<String>) {
        val same = { x: String, y: String -> letters(x) == letters(y) }
        if (before != null && same(before, after)) return
        val previous = before?.takeIf { it.isNotEmpty() }?.let { b -> contact.firstOrNull { same(it, b) } }
        when {
            after.isNotEmpty() && contact.any { same(it, after) } -> Unit
            after.isNotEmpty() && previous != null -> out += CardChange(CardField.ADDRESS, previous, after, preselected = true)
            after.isNotEmpty() -> out += CardChange(CardField.ADDRESS, null, after)
            previous != null -> out += CardChange(CardField.ADDRESS, previous, null, preselected = false)
        }
    }

    private fun list(field: CardField, before: List<String>?, after: List<String>, contact: List<String>, same: (String, String) -> Boolean): List<CardChange> {
        fun List<String>.has(v: String) = any { same(it, v) }
        val removed = before.orEmpty().filter { !after.has(it) && contact.has(it) }
        val added = after.filter { (before == null || !before.has(it)) && !contact.has(it) }
        // A number that replaced another reads as one change ("+44 7700 900123 → +44 7700 900456"); the old one is
        // the card's own earlier value, so it is ticked. A plain removal never is.
        val n = minOf(removed.size, added.size)
        return (0 until n).map { CardChange(field, removed[it], added[it], preselected = true) } +
            removed.drop(n).map { CardChange(field, it, null, preselected = false) } + added.drop(n).map { CardChange(field, null, it) }
    }

    private fun url(s: String) = s.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    private fun letters(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The fields the update touches, in the order the banner names them ("new number", "new email"…). */
    fun fields(changes: List<CardChange>): List<CardField> = CardField.entries.filter { f -> changes.any { it.field == f } }
}

/**
 * A signed card the user received and hasn't linked (H1): kept so the page of the contact it is about can ask "Link
 * it?" (or warn that a different card claims to be that contact). Holding never links anything.
 */
@Serializable
data class HeldCard(
    val cardId: String,
    val publicKey: String,
    val version: Long,
    val fields: CardFields,
    val receivedAt: Long,
    val parts: Set<MeCards.Part> = CardFields.ALL_PARTS,
) {
    val fingerprint: String get() = SignedCards.fingerprint(publicKey)

    fun toCard(): SignedCard = SignedCard(cardId, version, publicKey, fields, "", parts)

    companion object {
        fun of(card: SignedCard, now: Long) = HeldCard(card.cardId, card.publicKey, card.version, card.fields, now, card.parts)
    }
}

/**
 * Every link of this phone, by Parley key, and the cards held for linking: one small document, sealed where it is
 * stored. Pure operations; the store writes the result.
 *
 * Trust on first explicit link, pinned key after (H1): only [link] makes or replaces a link, and only the user calls it
 * ("Link it to Ana", "Trust the new card"). Cards that arrive are never linked by themselves: [put] only updates the
 * link a contact already has, [hold] keeps cards apart by card id and key.
 */
@Serializable
data class CardLinkBook(val links: Map<String, CardLink> = emptyMap(), val held: List<HeldCard> = emptyList()) {
    /** The contact (its Parley key) linked to [cardId], if any. */
    fun byCardId(cardId: String): Pair<String, CardLink>? = links.entries.firstOrNull { it.value.cardId == cardId }?.toPair()

    /** [key]'s own link, rewritten (an update waiting, settled, re-keyed); another contact with that card id lets go. */
    fun put(key: String, link: CardLink): CardLinkBook =
        copy(
            links = links.filterValues { it.cardId != link.cardId } + (key to link),
            held = held.filterNot { it.cardId == link.cardId && it.publicKey == link.publicKey },
        )

    /**
     * The user chose to link [key] to [card] (a first link, or "Trust the new card" over the one it had): from now on
     * only [card]'s key is trusted for [key].
     */
    fun link(key: String, card: SignedCard, now: Long): CardLinkBook = put(key, CardLinks.first(card, now))

    fun forget(key: String): CardLinkBook = if (key in links) copy(links = links - key) else this

    /** The contact's key changed (a sync, Make private, Make visible): its link follows, the newer one wins. */
    fun rekey(from: String, to: String): CardLinkBook {
        val moving = links[from] ?: return this
        val there = links[to]
        val kept = if (there != null && there.cardId == moving.cardId && there.version >= moving.version) there else moving
        return copy(links = links - from + (to to kept))
    }

    /**
     * Holds [card] for a contact page to offer. Held cards are told apart by card id **and** key, so a card signed by
     * another key never replaces one held (H1); a newer version from the same key does. Old and excess ones go.
     */
    fun hold(card: SignedCard, now: Long): CardLinkBook {
        fun same(h: HeldCard) = h.cardId == card.cardId && h.publicKey == card.publicKey
        val existing = held.firstOrNull(::same)
        if (existing != null && existing.version >= card.version && existing.parts.containsAll(card.parts)) return this
        val kept = (listOf(HeldCard.of(card, now)) + held.filterNot(::same)).filter { now - it.receivedAt < HOLD_MS }.take(MAX_HELD)
        return copy(held = kept)
    }

    /** The held card ([cardId], [publicKey]) the user chose to link to [key]; null when it isn't held (any more). */
    fun linkHeld(key: String, cardId: String, publicKey: String, now: Long): CardLinkBook? {
        val h = held.firstOrNull { it.cardId == cardId && it.publicKey == publicKey && now - it.receivedAt < HOLD_MS } ?: return null
        return link(key, h.toCard(), now)
    }

    /** "Don't link": the held card goes. */
    fun dropHeld(cardId: String, publicKey: String): CardLinkBook = copy(held = held.filterNot { it.cardId == cardId && it.publicKey == publicKey })

    companion object {
        /** A card waits this long for its contact's page to be opened. */
        const val HOLD_MS = 90L * 24 * 60 * 60 * 1000
        const val MAX_HELD = 50
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun encode(b: CardLinkBook): String = json.encodeToString(serializer(), b)

        fun decode(text: String?): CardLinkBook = if (text.isNullOrBlank()) CardLinkBook() else json.decodeFromString(serializer(), text)
    }
}

/**
 * What an arriving signed card does to the [CardLinkBook] (H1): pure, so every path, and every way to try a takeover,
 * is tested. A card is never linked here: a newer version from a linked card's own key waits as an update, anything
 * else is only held for the user to decide on.
 */
object CardIntake {
    enum class Kind {
        /** A newer card for the contact linked to it: waiting on their page. */
        UPDATE,

        /** The contact already has this version (or a newer one). */
        CURRENT,

        /** The contact [Outcome.key] has no link, and this card has one of its numbers: ask "Link it?". */
        OFFER,

        /** The contact [Outcome.key] is linked to another card or key: warn, offer nothing ("Trust the new card"). */
        DIFFERENT_SIGNER,

        /** No contact for it: held until the user links it from a contact's page. */
        HELD,
    }

    data class Outcome(val kind: Kind, val key: String?, val book: CardLinkBook)

    /**
     * [card] arriving in [book]. [numberMatch] is the one contact (Parley key) holding one of the card's numbers, if
     * any; [exists] says whether a linked contact is still there.
     */
    fun receive(book: CardLinkBook, card: SignedCard, numberMatch: String?, now: Long, exists: (String) -> Boolean): Outcome {
        val found = book.byCardId(card.cardId)
        if (found != null) {
            val (key, link) = found
            // The contact is gone: its link goes, and the card waits like any other.
            if (!exists(key)) return Outcome(Kind.HELD, null, book.forget(key).hold(card, now))
            return when (CardLinks.arrival(link, card)) {
                CardArrival.UPDATE -> Outcome(Kind.UPDATE, key, book.put(key, CardLinks.receive(link, card, now)))
                // Same card id, another key: held apart (never replacing the real one), shown as a warning.
                CardArrival.DIFFERENT_SIGNER -> Outcome(Kind.DIFFERENT_SIGNER, key, book.hold(card, now))
                else -> Outcome(Kind.CURRENT, key, book)
            }
        }
        if (numberMatch == null) return Outcome(Kind.HELD, null, book.hold(card, now))
        val kind = if (book.links[numberMatch] == null) Kind.OFFER else Kind.DIFFERENT_SIGNER
        return Outcome(kind, numberMatch, book.hold(card, now))
    }
}
