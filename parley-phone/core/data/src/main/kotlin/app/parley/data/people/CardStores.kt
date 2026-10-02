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
import app.parley.common.people.ContactRef
import app.parley.data.security.RecordCrypto
import app.parley.data.security.RecordSealing
import app.parley.data.vault.VaultCrypto
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
 * backup. It is never stored plain: while sealing fails, nothing is signed (L1). Signing uses the platform's Ed25519
 * where there is one ([Ed25519]).
 */
class MyCardIdentity(context: Context) : RecordSealing.Resealable {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val records by lazy { RecordCrypto.get(app) }

    private val _restoreChoice = MutableStateFlow(prefs.contains(K_PENDING))

    /** A restored backup brought another card key while this phone's own was already shared: the user picks one (M5). */
    val restoreChoice: StateFlow<Boolean> = _restoreChoice.asStateFlow()

    /** The secret, created on first use; null while a sealed one can't be opened or a new one can't be sealed. */
    @Synchronized
    private fun secret(): ByteArray? {
        val stored = prefs.getString(K_SECRET, null)
        if (stored == null) {
            val s = Ed25519.newSecret()
            val sealed = records.sealText(Base64.encodeToString(s, Base64.NO_WRAP))
            // Never kept plain (L1): without the Keystore nothing is signed, and the next share tries again.
            if (!records.isSealed(sealed)) return null
            prefs.edit(commit = true) {
                putString(K_SECRET, sealed)
                putString(K_ID, SignedCards.newCardId())
            }
            return s
        }
        val plain = records.openText(stored) ?: return null
        // Written plain by an older version when the Keystore was briefly unavailable: sealed now.
        if (!records.isSealed(stored)) records.sealText(plain)?.takeIf(records::isSealed)?.let { prefs.edit(commit = true) { putString(K_SECRET, it) } }
        return runCatching { Base64.decode(plain, Base64.NO_WRAP) }.getOrNull()?.takeIf { it.size == 32 }
    }

    override suspend fun resealPlain(): Boolean = withContext(Dispatchers.IO) {
        synchronized(this@MyCardIdentity) {
            val stored = prefs.getString(K_SECRET, null) ?: return@synchronized true
            if (!records.isSealed(stored)) secret()
            records.isSealed(prefs.getString(K_SECRET, null))
        }
    }

    /**
     * [card] (one source for every share: My card with the phone's profile filled in) as shared with [parts], signed.
     * The version moves on only when what the full card says changed since the last signature (M4), whatever parts
     * this share includes (they are signed with it, M3), and never repeats (M6). Showing or signing a card doesn't
     * count as sharing it ([markShared] does). Null when the key can't be read right now: the card is then shared
     * unsigned, as before.
     */
    @Synchronized
    fun sign(card: MeCard, parts: Set<MeCards.Part>, now: Long = System.currentTimeMillis()): SignedCard? {
        val secret = secret() ?: return null
        val id = prefs.getString(K_ID, null) ?: SignedCards.newCardId().also { prefs.edit(commit = true) { putString(K_ID, it) } }
        val hash = contentHash(card)
        var version = prefs.getLong(K_VERSION, 0)
        if (hash != prefs.getString(K_HASH, null)) {
            version = SignedCards.nextVersion(version, now)
            prefs.edit(commit = true) { putLong(K_VERSION, version).putString(K_HASH, hash) }
        }
        return SignedCards.sign(CardFields.of(card, parts), id, version, secret, parts)
    }

    /** A signed card actually left the phone (a file shared, a swap): this key is now the one people know (M5). */
    fun markShared() {
        if (!prefs.getBoolean(K_USED, false)) prefs.edit(commit = true) { putBoolean(K_USED, true) }
    }

    /** The version your contacts have of your card at most (0: never signed). */
    val version: Long get() = prefs.getLong(K_VERSION, 0)

    /** The public key, or null while the secret can't be read. */
    fun publicKey(): ByteArray? = secret()?.let { Ed25519.publicKey(it) }

    /**
     * Signs [message] with the card key, for shared labels: each member signs their changes with the key their
     * cards carry. Every caller's message starts with its own fixed header, so it can never pass for a card signature.
     */
    fun signMessage(message: ByteArray): ByteArray? = secret()?.let { Ed25519.sign(it, message) }

    /** "3F2A 9C1B 0D7E 44A2", for "Shared with" (people can compare it with what their Parley shows). */
    fun fingerprint(): String? = secret()?.let { Ed25519.fingerprint(Ed25519.publicKey(it)) }

    /** For the encrypted backup: the id, the secret and the version, so your next phone signs as you. */
    fun exportJson(): String? {
        if (!prefs.getBoolean(K_USED, false) && prefs.getLong(K_VERSION, 0) == 0L) return null
        val secret = secret() ?: return null
        return JSONObject().put("id", prefs.getString(K_ID, "")).put("key", Base64.encodeToString(secret, Base64.NO_WRAP))
            .put("v", prefs.getLong(K_VERSION, 0)).put("h", prefs.getString(K_HASH, "")).toString()
    }

    /**
     * Restores a backup's identity. A phone whose own card never left it takes it at once; one whose card was already
     * shared keeps it until the user chooses ([restoreChoice], [usePrevious], [keepThis]) instead of skipping the
     * backup's key silently (M5). Versions only grow across the restore (M6).
     */
    @Synchronized
    fun importJson(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val key = runCatching { Base64.decode(o.optString("key"), Base64.NO_WRAP) }.getOrNull()?.takeIf { it.size == 32 } ?: return
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return
        val mine = prefs.getString(K_SECRET, null)?.let { records.openText(it) }?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
        when {
            mine != null && mine.contentEquals(key) && prefs.getString(K_ID, null) == id ->
                prefs.edit(commit = true) { putLong(K_VERSION, maxOf(prefs.getLong(K_VERSION, 0), o.optLong("v"))) }
            !prefs.getBoolean(K_USED, false) -> adopt(o, key, id)
            else -> {
                val sealed = records.sealText(json)?.takeIf(records::isSealed) ?: return
                prefs.edit(commit = true) { putString(K_PENDING, sealed) }
                _restoreChoice.value = true
            }
        }
    }

    private fun adopt(o: JSONObject, key: ByteArray, id: String) {
        val sealed = records.sealText(Base64.encodeToString(key, Base64.NO_WRAP))?.takeIf(records::isSealed) ?: return
        val version = maxOf(prefs.getLong(K_VERSION, 0), o.optLong("v"))
        prefs.edit(commit = true) {
            putString(K_ID, id).putString(K_SECRET, sealed).putLong(K_VERSION, version).putString(K_HASH, o.optString("h"))
                .putBoolean(K_USED, true).remove(K_PENDING)
        }
        _restoreChoice.value = false
    }

    /** "Use my earlier card key": the backup's identity replaces this phone's. */
    @Synchronized
    fun usePrevious(): Boolean {
        val json = prefs.getString(K_PENDING, null)?.let { records.openText(it) } ?: return false
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return false
        val key = runCatching { Base64.decode(o.optString("key"), Base64.NO_WRAP) }.getOrNull()?.takeIf { it.size == 32 } ?: return false
        adopt(o, key, o.optString("id").takeIf { it.isNotEmpty() } ?: return false)
        return true
    }

    /** "Keep this phone's key": the backup's is dropped. */
    @Synchronized
    fun keepThis() {
        prefs.edit(commit = true) { remove(K_PENDING) }
        _restoreChoice.value = false
    }

    private fun contentHash(card: MeCard): String =
        digest(SignedCards.payload("", 0, "", CardFields.of(card, CardFields.ALL_PARTS)))

    private fun digest(b: ByteArray) = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(b), Base64.NO_WRAP)

    private companion object {
        const val FILE = "my_card_identity"
        const val K_ID = "card_id"
        const val K_SECRET = "secret"
        const val K_VERSION = "version"
        const val K_HASH = "content"
        const val K_USED = "used"
        const val K_PENDING = "restored_identity"
    }
}

/**
 * I22: "Shared with", the private ledger of who got your card. One document sealed with the small-records key; a
 * document that can't be opened right now is kept as it is and nothing is added until it can. Receipts for private
 * contacts (M7) keep no name or number, are sealed with the vault's key in a document of their own, and are left
 * out of [exportJson] (they travel with the private contacts, [exportFor]).
 */
class ShareLedgerStore(context: Context) : RecordSealing.Resealable {
    private val app = context.applicationContext
    private val prefs by lazy { app.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    private val records by lazy { RecordCrypto.get(app) }
    private val mutex = Mutex()

    @Volatile private var loaded = false

    private val _receipts = MutableStateFlow<List<ShareReceipt>>(emptyList())

    /** Newest first. Empty until [load] ran. Private contacts' receipts have their [ShareReceipt.contactKey] only. */
    val receipts: StateFlow<List<ShareReceipt>> = _receipts.asStateFlow()

    private val _dismissed = MutableStateFlow<String?>(null)

    /** The numbers ([ShareLedger.numbersKey]) a "Changed my number" offer was dismissed for. */
    val dismissedNumbers: StateFlow<String?> = _dismissed.asStateFlow()

    suspend fun load(): Boolean = withContext(Dispatchers.IO) { mutex.withLock { loadLocked() } }

    private fun loadLocked(): Boolean {
        if (loaded) return true
        val list = try {
            ShareLedger.decode(records.openTextOrThrow(prefs.getString(K_DOC, null))) + ShareLedger.decode(PrivateSeal.open(prefs.getString(K_PRIVATE, null)))
        } catch (_: Exception) {
            return false
        }
        _receipts.value = list.sortedByDescending { it.at }
        _dismissed.value = prefs.getString(K_DISMISSED, null)
        loaded = true
        return true
    }

    private suspend fun write(f: (List<ShareReceipt>) -> List<ShareReceipt>): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loadLocked()) return@withLock false
            val next = f(_receipts.value)
            if (!storeLocked(next)) return@withLock false
            _receipts.value = next
            true
        }
    }

    /** Writes [list]: private contacts' receipts sealed with the vault key, the rest with the small-records key. */
    private fun storeLocked(list: List<ShareReceipt>): Boolean {
        val (private, shared) = list.partition { ContactRef.isPrivateKey(it.contactKey) }
        val sealedPrivate = if (private.isEmpty()) null else PrivateSeal.seal(ShareLedger.encode(private)) ?: return false
        prefs.edit(commit = true) {
            putString(K_DOC, records.sealText(ShareLedger.encode(shared)))
            if (sealedPrivate == null) remove(K_PRIVATE) else putString(K_PRIVATE, sealedPrivate)
        }
        return true
    }

    override suspend fun resealPlain(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stored = prefs.getString(K_DOC, null) ?: return@withLock true
            if (records.isSealed(stored)) return@withLock true
            if (!loadLocked()) return@withLock false
            storeLocked(_receipts.value) && records.isSealed(prefs.getString(K_DOC, null))
        }
    }

    /**
     * Someone got your card ([phones]: the numbers it had). For a private contact ([contactKey]) no name or number is
     * kept: they are read from the vault when shown.
     */
    suspend fun record(
        name: String, number: String?, method: ShareMethod, phones: List<String>, at: Long = System.currentTimeMillis(), contactKey: String? = null,
    ): Boolean {
        val private = ContactRef.isPrivateKey(contactKey)
        val r = ShareReceipt(
            UUID.randomUUID().toString(), if (private) "" else name.trim(), if (private) null else number?.trim()?.ifEmpty { null },
            method, at, phones, contactKey.takeIf { private },
        )
        return write { ShareLedger.add(it, r) }
    }

    suspend fun remove(ids: Set<String>) = write { list -> list.filterNot { it.id in ids } }

    suspend fun clear() = write { emptyList() }

    fun dismissNumbers(key: String) {
        prefs.edit { putString(K_DISMISSED, key) }
        _dismissed.value = key
    }

    /** For the encrypted backup's general section (the plain document inside it): never private contacts' receipts. */
    suspend fun exportJson(): String? {
        if (!load()) return null
        return _receipts.value.filterNot { ContactRef.isPrivateKey(it.contactKey) }.takeIf { it.isNotEmpty() }?.let(ShareLedger::encode)
    }

    /** Merges a backup's receipts with this phone's (by id); private ones only come through [importFor]. */
    suspend fun importJson(json: String) {
        val restored = runCatching { ShareLedger.decode(json) }.getOrNull()?.filterNot { ContactRef.isPrivateKey(it.contactKey) } ?: return
        merge(restored)
    }

    /** Private contact [key]'s receipts, for the private-contacts part of a backup. */
    fun exportFor(key: String): String? = _receipts.value.filter { it.contactKey == key }.takeIf { it.isNotEmpty() }?.let(ShareLedger::encode)

    /** A backup's receipts for a private contact, now [key] (a restore gives it a new id). */
    suspend fun importFor(key: String, json: String) {
        val restored = runCatching { ShareLedger.decode(json) }.getOrNull() ?: return
        merge(restored.map { it.copy(name = "", number = null, contactKey = key) })
    }

    private suspend fun merge(restored: List<ShareReceipt>) {
        write { list -> (list + restored.filter { r -> list.none { it.id == r.id } }).sortedByDescending { it.at }.take(ShareLedger.MAX) }
    }

    /** A private contact's key changed (Make private moved it in, a restore): its receipts follow. */
    suspend fun rekey(from: String, to: String) {
        if (!ContactRef.isPrivateKey(from) || !ContactRef.isPrivateKey(to)) return
        write { list -> if (list.none { it.contactKey == from }) list else list.map { if (it.contactKey == from) it.copy(contactKey = to) else it } }
    }

    private companion object {
        const val FILE = "card_sharing"
        const val K_DOC = "ledger"
        const val K_PRIVATE = "ledger_private"
        const val K_DISMISSED = "dismissed_numbers"
    }
}

/** Text sealed with the vault's caller-ID key (no unlock needed), for what names a private contact (M7). */
internal object PrivateSeal {
    fun seal(text: String): String? = runCatching {
        Base64.encodeToString(VaultCrypto.sealCallerId(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }.getOrNull()

    /** The plain text, null for nothing stored; throws when it can't be opened right now (the caller keeps it). */
    fun open(stored: String?): String? = stored?.let { String(VaultCrypto.openCallerId(Base64.decode(it, Base64.NO_WRAP)), Charsets.UTF_8) }
}

/**
 * I14: which contacts are linked to which signed cards, by the contact's Parley key (a private contact's
 * `parley-private:<id>` too), with the newest version seen and an update waiting; and the cards received and not
 * linked yet. Device contacts' links and held cards are one document sealed with the small-records key; private
 * contacts' links are a second one sealed with the vault's key (M7). Keys follow the contact through
 * [app.parley.data.people.ContactKeys].
 */
class CardLinkStore(context: Context) : RecordSealing.Resealable {
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
            val device = CardLinkBook.decode(records.openTextOrThrow(prefs.getString(K_DOC, null)))
            val private = CardLinkBook.decode(PrivateSeal.open(prefs.getString(K_PRIVATE, null)))
            device.copy(links = device.links + private.links)
        } catch (_: Exception) {
            return false
        }
        loaded = true
        return true
    }

    /** Writes [b]: private contacts' links sealed with the vault key, the rest with the small-records key. */
    private fun storeLocked(b: CardLinkBook): Boolean {
        val private = b.links.filterKeys(ContactRef::isPrivateKey)
        val sealedPrivate = if (private.isEmpty()) null else PrivateSeal.seal(CardLinkBook.encode(CardLinkBook(private))) ?: return false
        prefs.edit(commit = true) {
            putString(K_DOC, records.sealText(CardLinkBook.encode(b.copy(links = b.links - private.keys))))
            if (sealedPrivate == null) remove(K_PRIVATE) else putString(K_PRIVATE, sealedPrivate)
        }
        return true
    }

    /** Applies [f] and stores the result; false when the documents can't be opened (or sealed) right now. */
    suspend fun update(f: (CardLinkBook) -> CardLinkBook): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loadLocked()) return@withLock false
            val next = f(_book.value)
            if (next != _book.value) {
                if (!storeLocked(next)) return@withLock false
                _book.value = next
            }
            true
        }
    }

    override suspend fun resealPlain(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stored = prefs.getString(K_DOC, null) ?: return@withLock true
            if (records.isSealed(stored)) return@withLock true
            if (!loadLocked()) return@withLock false
            storeLocked(_book.value) && records.isSealed(prefs.getString(K_DOC, null))
        }
    }

    /** Keys with a link, for the key sweep. */
    fun keys(): Set<String> = _book.value.links.keys

    suspend fun rekey(from: String, to: String) { update { it.rekey(from, to) } }

    suspend fun forget(key: String) { update { it.forget(key) } }

    /** One contact's link for the backup's private-contacts section. */
    fun exportOne(key: String): String? = _book.value.links[key]?.let { CardLinkBook.encode(CardLinkBook(mapOf(key to it))) }

    /** A backup's link for [key]: never over a link the contact already has, or another contact's (H1). */
    suspend fun importOne(key: String, json: String) {
        val link = runCatching { CardLinkBook.decode(json).links.values.firstOrNull() }.getOrNull() ?: return
        update { b -> if (b.byCardId(link.cardId) != null || key in b.links) b else b.put(key, link) }
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
            // Not placed: held, so the contact's page asks before linking it again.
            val held = waiting.map { l -> HeldCard(l.cardId, l.publicKey, l.version, l.fields, now, l.parts) } + restored.held
            held.forEach { h ->
                val known = next.byCardId(h.cardId) != null || next.held.any { it.cardId == h.cardId && it.publicKey == h.publicKey }
                if (!known) next = next.copy(held = (next.held + h).take(CardLinkBook.MAX_HELD))
            }
            next
        }
    }

    private companion object {
        const val FILE = "card_links"
        const val K_DOC = "book"
        const val K_PRIVATE = "book_private"
    }
}
