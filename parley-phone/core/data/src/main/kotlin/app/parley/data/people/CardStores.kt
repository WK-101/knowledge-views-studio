package app.parley.data.people

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import app.parley.common.cards.CardFields
import app.parley.common.cards.CardLinkBook
import app.parley.common.cards.HeldCard
import app.parley.common.cards.ShareLedger
import app.parley.common.cards.ShareMethod
import app.parley.common.cards.ShareReceipt
import app.parley.common.cards.SignedCard
import app.parley.common.cards.SignedCards
import app.parley.common.people.MeCard
import app.parley.common.people.MeCards
import app.parley.common.spam.Ed25519
import app.parley.data.security.RecordCrypto
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * I14: My card's identity. A stable card id, an Ed25519 key that signs every card you share, and the card's version,
 * which grows whenever what the card says changes.
 *
 * The Android Keystore can't hold an Ed25519 key on most phones (and a key it held could never move to your next
 * phone, so your contacts would see a different signer after a phone change). The 32-byte secret is therefore kept
 * sealed with the small-records key ([RecordCrypto], wrapped by a Keystore key) and travels only inside the encrypted
 * backup. Signing uses the platform's Ed25519 where there is one ([Ed25519]).
 */
class MyCardIdentity(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val records by lazy { RecordCrypto.get(app) }

    /** The secret, created on first use; null while a sealed one can't be opened (nothing is signed then). */
    @Synchronized
    private fun secret(): ByteArray? {
        val stored = prefs.getString(K_SECRET, null)
        if (stored == null) {
            val s = Ed25519.newSecret()
            prefs.edit(commit = true) {
                putString(K_SECRET, records.sealText(Base64.encodeToString(s, Base64.NO_WRAP)))
                putString(K_ID, SignedCards.newCardId())
            }
            return s
        }
        val plain = records.openText(stored) ?: return null
        // Kept plain when the Keystore was briefly unavailable: sealed now.
        if (!records.isSealed(stored)) prefs.edit { putString(K_SECRET, records.sealText(plain)) }
        return runCatching { Base64.decode(plain, Base64.NO_WRAP) }.getOrNull()?.takeIf { it.size == 32 }
    }

    /**
     * [card] as shared with [parts], signed. The version moves on when anything the card says changed since the last
     * signature (whatever parts that one shared), so a receiver can tell newer from older. Null when the key can't be
     * read right now: the card is then shared unsigned, as before.
     */
    @Synchronized
    fun sign(card: MeCard, parts: Set<MeCards.Part>): SignedCard? {
        val secret = secret() ?: return null
        val id = prefs.getString(K_ID, null) ?: SignedCards.newCardId().also { prefs.edit { putString(K_ID, it) } }
        val full = CardFields.of(card, MeCards.Part.entries.toSet())
        val hash = digest(SignedCards.payload(id, 0, "", full))
        var version = prefs.getLong(K_VERSION, 0)
        if (hash != prefs.getString(K_HASH, null)) {
            version++
            prefs.edit(commit = true) { putLong(K_VERSION, version).putString(K_HASH, hash).putBoolean(K_USED, true) }
        }
        return SignedCards.sign(CardFields.of(card, parts), id, version, secret)
    }

    /** The version your contacts have of your card at most (0: never shared signed). */
    val version: Long get() = prefs.getLong(K_VERSION, 0)

    /** "3F2A 9C1B 0D7E 44A2", for "Shared with" (people can compare it with what their Parley shows). */
    fun fingerprint(): String? = secret()?.let { Ed25519.fingerprint(Ed25519.publicKey(it)) }

    /** For the encrypted backup: the id, the secret and the version, so your next phone signs as you. */
    fun exportJson(): String? {
        if (!prefs.getBoolean(K_USED, false)) return null
        val secret = secret() ?: return null
        return JSONObject().put("id", prefs.getString(K_ID, "")).put("key", Base64.encodeToString(secret, Base64.NO_WRAP))
            .put("v", prefs.getLong(K_VERSION, 0)).put("h", prefs.getString(K_HASH, "")).toString()
    }

    /** Restores a backup's identity, unless this phone already shared a signed card of its own. */
    @Synchronized
    fun importJson(json: String) {
        if (prefs.getBoolean(K_USED, false)) return
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val key = runCatching { Base64.decode(o.optString("key"), Base64.NO_WRAP) }.getOrNull()?.takeIf { it.size == 32 } ?: return
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return
        prefs.edit(commit = true) {
            putString(K_ID, id).putString(K_SECRET, records.sealText(Base64.encodeToString(key, Base64.NO_WRAP)))
                .putLong(K_VERSION, o.optLong("v")).putString(K_HASH, o.optString("h")).putBoolean(K_USED, true)
        }
    }

    private fun digest(b: ByteArray) = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(b), Base64.NO_WRAP)

    private companion object {
        const val FILE = "my_card_identity"
        const val K_ID = "card_id"
        const val K_SECRET = "secret"
        const val K_VERSION = "version"
        const val K_HASH = "content"
        const val K_USED = "used"
    }
}

/**
 * I22: "Shared with", the private ledger of who got your card. One document sealed with the small-records key;
 * a document that can't be opened right now is kept as it is and nothing is added until it can.
 */
class ShareLedgerStore(context: Context) {
    private val app = context.applicationContext
    private val prefs by lazy { app.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    private val records by lazy { RecordCrypto.get(app) }
    private val mutex = Mutex()

    @Volatile private var loaded = false

    private val _receipts = MutableStateFlow<List<ShareReceipt>>(emptyList())

    /** Newest first. Empty until [load] ran. */
    val receipts: StateFlow<List<ShareReceipt>> = _receipts.asStateFlow()

    private val _dismissed = MutableStateFlow<String?>(null)

    /** The numbers ([ShareLedger.numbersKey]) a "Changed my number" offer was dismissed for. */
    val dismissedNumbers: StateFlow<String?> = _dismissed.asStateFlow()

    suspend fun load(): Boolean = withContext(Dispatchers.IO) { mutex.withLock { loadLocked() } }

    private fun loadLocked(): Boolean {
        if (loaded) return true
        val stored = prefs.getString(K_DOC, null)
        val list = try {
            ShareLedger.decode(records.openTextOrThrow(stored))
        } catch (_: Exception) {
            return false
        }
        _receipts.value = list
        _dismissed.value = prefs.getString(K_DISMISSED, null)
        loaded = true
        return true
    }

    private suspend fun write(f: (List<ShareReceipt>) -> List<ShareReceipt>): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loadLocked()) return@withLock false
            val next = f(_receipts.value)
            prefs.edit(commit = true) { putString(K_DOC, records.sealText(ShareLedger.encode(next))) }
            _receipts.value = next
            true
        }
    }

    /** Someone got your card ([phones]: the numbers it had). */
    suspend fun record(name: String, number: String?, method: ShareMethod, phones: List<String>, at: Long = System.currentTimeMillis()): Boolean =
        write { ShareLedger.add(it, ShareReceipt(UUID.randomUUID().toString(), name.trim(), number?.trim()?.ifEmpty { null }, method, at, phones)) }

    suspend fun remove(ids: Set<String>) = write { list -> list.filterNot { it.id in ids } }

    suspend fun clear() = write { emptyList() }

    fun dismissNumbers(key: String) {
        prefs.edit { putString(K_DISMISSED, key) }
        _dismissed.value = key
    }

    /** For the encrypted backup (the plain document inside it). */
    suspend fun exportJson(): String? {
        if (!load()) return null
        return _receipts.value.takeIf { it.isNotEmpty() }?.let(ShareLedger::encode)
    }

    /** Merges a backup's receipts with this phone's (by id). */
    suspend fun importJson(json: String) {
        val restored = runCatching { ShareLedger.decode(json) }.getOrNull() ?: return
        write { list -> (list + restored.filter { r -> list.none { it.id == r.id } }).sortedByDescending { it.at }.take(ShareLedger.MAX) }
    }

    private companion object {
        const val FILE = "card_sharing"
        const val K_DOC = "ledger"
        const val K_DISMISSED = "dismissed_numbers"
    }
}

/**
 * I14: which contacts are linked to which signed cards, by the contact's Parley key (a private contact's
 * `parley-private:<id>` too), with the newest version seen and an update waiting; and the cards received for nobody yet.
 * One document sealed with the small-records key. Keys follow the contact through [app.parley.data.people.ContactKeys].
 */
class CardLinkStore(context: Context) {
    private val app = context.applicationContext
    private val prefs by lazy { app.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    private val records by lazy { RecordCrypto.get(app) }
    private val mutex = Mutex()

    @Volatile private var loaded = false

    private val _book = MutableStateFlow(CardLinkBook())
    val book: StateFlow<CardLinkBook> = _book.asStateFlow()

    suspend fun load(): Boolean = withContext(Dispatchers.IO) { mutex.withLock { loadLocked() } }

    private fun loadLocked(): Boolean {
        if (loaded) return true
        _book.value = try {
            CardLinkBook.decode(records.openTextOrThrow(prefs.getString(K_DOC, null)))
        } catch (_: Exception) {
            return false
        }
        loaded = true
        return true
    }

    /** Applies [f] and stores the result; false when the document can't be opened right now. */
    suspend fun update(f: (CardLinkBook) -> CardLinkBook): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loadLocked()) return@withLock false
            val next = f(_book.value)
            if (next != _book.value) {
                prefs.edit(commit = true) { putString(K_DOC, records.sealText(CardLinkBook.encode(next))) }
                _book.value = next
            }
            true
        }
    }

    /** Keys with a link, for the key sweep. */
    fun keys(): Set<String> = _book.value.links.keys

    suspend fun rekey(from: String, to: String) { update { it.rekey(from, to) } }

    suspend fun forget(key: String) { update { it.forget(key) } }

    /** One contact's link for the backup's private-contacts section. */
    fun exportOne(key: String): String? = _book.value.links[key]?.let { CardLinkBook.encode(CardLinkBook(mapOf(key to it))) }

    suspend fun importOne(key: String, json: String) {
        val link = runCatching { CardLinkBook.decode(json).links.values.firstOrNull() }.getOrNull() ?: return
        update { b -> if (b.byCardId(link.cardId) != null) b else b.put(key, link) }
    }

    /** Device contacts' links (by lookup key) for the backup; matched back by the card's numbers on restore. */
    suspend fun exportDevice(isPrivate: (String) -> Boolean): String? {
        if (!load()) return null
        val b = _book.value
        val device = CardLinkBook(b.links.filterKeys { !isPrivate(it) }, b.held)
        return device.takeIf { it.links.isNotEmpty() || it.held.isNotEmpty() }?.let(CardLinkBook::encode)
    }

    /**
     * A backup's device links: each goes back under the lookup key [keyFor] finds for it (the same key, or the
     * contact with one of the card's numbers); the others wait as held cards, linked when their contact opens.
     */
    suspend fun importDevice(json: String, keyFor: suspend (String, CardFields) -> String?) {
        val restored = runCatching { CardLinkBook.decode(json) }.getOrNull() ?: return
        val placed = restored.links.mapNotNull { (k, l) -> keyFor(k, l.fields)?.let { it to l } }
        val now = System.currentTimeMillis()
        update { b ->
            var next = b
            placed.forEach { (k, l) -> if (next.byCardId(l.cardId) == null && k !in next.links) next = next.put(k, l) }
            val waiting = restored.links.values.filter { l -> placed.none { it.second.cardId == l.cardId } }
            val held = waiting.map { l -> HeldCard(l.cardId, l.publicKey, l.version, l.fields, now) } + restored.held
            held.forEach { h ->
                val known = next.byCardId(h.cardId) != null || next.held.any { it.cardId == h.cardId }
                if (!known) next = next.copy(held = (next.held + h).take(CardLinkBook.MAX_HELD))
            }
            next
        }
    }

    private companion object {
        const val FILE = "card_links"
        const val K_DOC = "book"
    }
}
