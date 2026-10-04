package app.parley.common.cards

import app.parley.common.Codecs
import app.parley.common.PhoneIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** How My card reached someone. */
@Serializable
enum class ShareMethod {
    /** Swapped QR codes: they showed theirs, you showed yours. */
    QR_SWAP,

    /** "Send my details" in a chat or SMS. */
    SEND_DETAILS,

    /** "Introduce myself" to a list. */
    INTRODUCE,

    /** The card as a file, sent to someone Parley knew. */
    CARD_FILE,

    /** The "Changed my number" message. */
    NEW_NUMBER,
}

/**
 * I22: one entry of "Shared with": who got your card, when, how, and which numbers it had then ([phones], so the
 * "Changed my number" helper knows who still has an old one). [number] is theirs, when known; [name] may be empty.
 *
 * [contactKey] is set for a private contact (`parley-private:<id>`, M7): such a receipt keeps no name or number of
 * theirs (they are read from the vault when the list is shown, and hidden in discreet mode), is stored sealed with
 * the vault's key, and travels only in the private-contacts part of a backup.
 */
@Serializable
data class ShareReceipt(
    val id: String,
    val name: String,
    val number: String? = null,
    val method: ShareMethod,
    val at: Long,
    val phones: List<String> = emptyList(),
    val contactKey: String? = null,
)

/** One person in "Shared with": their latest receipt and every receipt of theirs, newest first. */
data class SharedPerson(val latest: ShareReceipt, val receipts: List<ShareReceipt>) {
    val name: String get() = receipts.firstOrNull { it.name.isNotBlank() }?.name.orEmpty()
    val number: String? get() = receipts.firstNotNullOfOrNull { it.number?.takeIf(String::isNotBlank) }
}

/** The private ledger of who has your card (I22), kept sealed on the phone and in backups. */
object ShareLedger {
    /** Enough for years of swaps; the oldest go first. */
    const val MAX = 500

    private val json = Codecs.stored
    private val serializer = ListSerializer(ShareReceipt.serializer())

    fun encode(list: List<ShareReceipt>): String = json.encodeToString(serializer, list)

    /** A stored ledger; unknown methods or damage throw, so the store keeps what it can't read. */
    fun decode(text: String?): List<ShareReceipt> = if (text.isNullOrBlank()) emptyList() else json.decodeFromString(serializer, text)

    /** [list] with [r] first, capped at [MAX]. */
    fun add(list: List<ShareReceipt>, r: ShareReceipt): List<ShareReceipt> = (listOf(r) + list.filterNot { it.id == r.id }).take(MAX)

    /**
     * Receipts grouped by person, most recent first: the same number (as [PhoneIdentity.same] reads it) is one person;
     * receipts without a number are grouped by name.
     */
    fun people(list: List<ShareReceipt>, region: String? = null): List<SharedPerson> {
        val groups = ArrayList<MutableList<ShareReceipt>>()
        for (r in list.sortedByDescending { it.at }) {
            val g = groups.firstOrNull { g ->
                val n = r.number
                if (!n.isNullOrBlank()) g.any { !it.number.isNullOrBlank() && PhoneIdentity.same(it.number, n, region) }
                else g.all { it.number.isNullOrBlank() } && r.name.isNotBlank() && g.any { it.name.equals(r.name, ignoreCase = true) }
            }
            if (g != null) g += r else groups += mutableListOf(r)
        }
        return groups.map { SharedPerson(it.first(), it) }
    }

    /**
     * The people to tell about a new number: those with a number of their own whose latest card from you had a number
     * you no longer have ([current], My card's numbers now), or didn't have your first number yet. Nobody when My card
     * has no number.
     */
    fun outdated(list: List<ShareReceipt>, current: List<String>, region: String? = null): List<SharedPerson> {
        val first = current.firstOrNull { it.isNotBlank() } ?: return emptyList()
        fun has(xs: List<String>, n: String) = xs.any { PhoneIdentity.same(it, n, region) }
        return people(list, region).filter { p ->
            val got = p.latest.phones
            p.number != null && got.isNotEmpty() && (got.any { !has(current, it) } || !has(got, first))
        }
    }

    /**
     * A short fingerprint of My card's numbers, to remember which numbers a "Changed my number" offer was already
     * dismissed for (it comes back when the numbers change again).
     */
    fun numbersKey(phones: List<String>): String =
        phones.map { PhoneIdentity.legacyKey(it) }.filter { it.isNotEmpty() }.sorted().joinToString(",")
}
