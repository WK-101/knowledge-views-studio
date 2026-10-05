package app.parley.common.people

import app.parley.common.Codecs
import app.parley.common.PhoneIdentity
import kotlinx.serialization.Serializable

/**
 * An archived contact as lists and the call path see it, without opening its whole record: out of the address book
 * (so out of every list, picker, widget and other app), kept by Parley, and still named when it calls.
 *
 * [accounts]: where its copies were kept (type and name; both null for the phone), so Unarchive can put it back there.
 * [originalKey]: its lookup key before it was archived, for matching a backup to what is here.
 */
@Serializable
data class ArchivedCard(
    val id: Long,
    val name: String,
    val numbers: List<String> = emptyList(),
    val archivedAt: Long = 0,
    val accounts: List<ArchivedAccount> = emptyList(),
    val originalKey: String = "",
    val company: String = "",
) {
    /** The Parley key what Parley keeps about this person waits under while archived. */
    val parleyKey: String get() = ContactRef.archivedKey(id)
}

/** An account a copy of an archived contact was kept in. */
@Serializable
data class ArchivedAccount(val type: String? = null, val name: String? = null)

/** The rules of archiving: where Unarchive puts a contact, and how the call path finds an archived number. */
object Archive {
    /** Where Unarchive puts a contact back. */
    sealed interface Target {
        /** Into the accounts it came from. */
        data object Original : Target

        /** One of them can't be written now (signed out, removed): the user picks an account. */
        data class Ask(val missing: List<ArchivedAccount>) : Target
    }

    /**
     * [accounts] are the archived copies' accounts and [writable] those the phone can write to now. The phone itself
     * (no account, or a phone account: [alwaysThere]) is always there; messenger accounts are never written back, so
     * they don't count either.
     */
    fun target(accounts: List<ArchivedAccount>, writable: Set<ArchivedAccount>, alwaysThere: (String?) -> Boolean = { it == null }): Target {
        val missing = accounts.distinct().filter { a -> !alwaysThere(a.type) && a !in writable }
        return if (missing.isEmpty()) Target.Original else Target.Ask(missing)
    }

    /** Number → archived contact by line ([PhoneIdentity]), for naming calls; the first card with a number wins. */
    fun index(cards: List<ArchivedCard>, region: String?): PhoneIdentity.LineMap<ArchivedCard> =
        PhoneIdentity.LineMap<ArchivedCard>(region).also { m -> cards.forEach { c -> c.numbers.forEach { n -> m.putIfAbsent(n, c) } } }

    /** Whether [a] and [b] are the same archived person: the same key before archiving, or the same name and numbers. */
    fun sameOne(a: ArchivedCard, b: ArchivedCard): Boolean =
        (a.originalKey.isNotEmpty() && a.originalKey == b.originalKey) || (a.name == b.name && a.numbers.toSet() == b.numbers.toSet())

    /**
     * A backup's archived keys ([ArchivedCard.parleyKey] of its [backup] cards) → the key of the same person among the
     * archived contacts [here]. A restore gives an archived contact a new id, so what the backup keeps under the old
     * key (the note for calls and its agenda, the Circle, logged moments, call time) follows this map, never the bare
     * id, which may name someone else here. A backup card with no match here is left out: its data matches nobody.
     */
    fun restoredKeys(backup: List<ArchivedCard>, here: List<ArchivedCard>): Map<String, String> =
        backup.mapNotNull { b -> here.firstOrNull { sameOne(it, b) }?.let { b.parleyKey to it.parleyKey } }.toMap()

    private val json = Codecs.stored

    fun encode(card: ArchivedCard): String = json.encodeToString(ArchivedCard.serializer(), card)

    fun decode(text: String?): ArchivedCard? =
        text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(ArchivedCard.serializer(), it) }.getOrNull() }
}
