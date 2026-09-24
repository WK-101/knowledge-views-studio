package app.parley.data.messaging

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import app.parley.common.KeypadLayout
import app.parley.common.MessagedEntry
import app.parley.common.MessagedRecord
import app.parley.common.MessengerApp
import app.parley.data.PhoneEnv
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** "Last messaged via Signal · 2 days ago" for one number. [number] is null for entries kept from before F13. */
data class LastMessaged(val app: MessengerApp?, val label: String, val at: Long, val number: String? = null, val key: String = "")

/** Your details for "Send my details". */
data class MyDetails(val name: String = "", val number: String = "")

/** A chat Parley opened for a number that isn't a contact; offered as a temporary contact on return (M4). */
data class OpenedChat(val number: String, val appLabel: String, val at: Long)

/**
 * Keypad and messaging preferences, feature-scoped (SharedPreferences, private to Parley, never backed up to any
 * server): the keypad alphabet, the last messenger chosen, the WhatsApp/Business choice, your details for drafts,
 * and when you last messaged each number. Nothing here leaves the phone.
 *
 * F13: the "last messaged" record is encrypted (the vault's caller-ID key, in the Android Keystore), can be turned
 * off ("Keep a record of numbers you message"), cleared per number or at once, never holds private (vault)
 * numbers, follows call-history retention and goes with expired temporary contacts. [isPrivateNumber] tells vault
 * numbers apart (checked off the main thread).
 */
class MessagingStore(
    context: Context,
    private val scope: CoroutineScope,
    private val isPrivateNumber: suspend (String) -> Boolean = { false },
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("messaging", Context.MODE_PRIVATE)
    private val recordLock = Mutex()

    private val _keypadLayout = MutableStateFlow(storedLayout())
    /** The chosen keypad alphabet, or null for "same as the phone's language". */
    val keypadLayoutChoice: StateFlow<KeypadLayout?> = _keypadLayout.asStateFlow()

    private val _myDetails = MutableStateFlow(MyDetails(prefs.getString(K_MY_NAME, "").orEmpty(), prefs.getString(K_MY_NUMBER, "").orEmpty()))
    val myDetails: StateFlow<MyDetails> = _myDetails.asStateFlow()

    private val _recordEnabled = MutableStateFlow(prefs.getBoolean(K_RECORD_ENABLED, true))
    /** "Keep a record of numbers you message" (on by default). */
    val recordEnabled: StateFlow<Boolean> = _recordEnabled.asStateFlow()

    private val _expiryDays = MutableStateFlow(prefs.getInt(K_EXPIRY_DAYS, 0))
    /** M10: "Forget messaged numbers after" N days (0 = never; call-history retention applies as well). */
    val expiryDays: StateFlow<Int> = _expiryDays.asStateFlow()

    private var entries: List<MessagedEntry> = emptyList()
    private val _lastMessaged = MutableStateFlow<Map<String, LastMessaged>>(emptyMap())
    /** Line key → last message sent through Parley (loaded in the background). */
    val lastMessaged: StateFlow<Map<String, LastMessaged>> = _lastMessaged.asStateFlow()

    private val _openedChat = MutableStateFlow<OpenedChat?>(null)
    /** Set while a chat with an unknown number is open in a messenger (in memory only). */
    val openedChat: StateFlow<OpenedChat?> = _openedChat.asStateFlow()

    /** Completed once the record was read: changes wait for it, so none is applied to (and saved over) an empty list. */
    private val loaded = CompletableDeferred<Unit>()

    private val _recordUnreadable = MutableStateFlow(false)
    /**
     * The stored record couldn't be decrypted (a Keystore hiccup, or the key is gone). Its ciphertext is kept as it
     * is: nothing is saved over it until it reads again, except "Clear all" / turning the record off.
     */
    val recordUnreadable: StateFlow<Boolean> = _recordUnreadable.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            try {
                recordLock.withLock { publish(loadRecord()) }
            } finally {
                loaded.complete(Unit)
            }
            // M10: expired entries go as soon as the record is loaded, not only in the daily housekeeping.
            runCatching { pruneExpired() }
        }
    }

    private fun region(): String = PhoneEnv.countryIso(appContext)

    private fun storedLayout(): KeypadLayout? =
        prefs.getString(K_LAYOUT, null)?.let { v -> KeypadLayout.entries.firstOrNull { it.name == v } }

    /** The alphabet to use: the chosen one, or the one matching the phone's language. */
    fun effectiveLayout(choice: KeypadLayout? = _keypadLayout.value, locale: Locale = Locale.getDefault()): KeypadLayout =
        choice ?: KeypadLayout.forLocale(locale.language, locale.script)

    fun setKeypadLayout(layout: KeypadLayout?) {
        prefs.edit { if (layout == null) remove(K_LAYOUT) else putString(K_LAYOUT, layout.name) }
        _keypadLayout.value = layout
    }

    fun setMyDetails(d: MyDetails) {
        prefs.edit { putString(K_MY_NAME, d.name.trim()).putString(K_MY_NUMBER, d.number.trim()) }
        _myDetails.value = d.copy(name = d.name.trim(), number = d.number.trim())
    }

    /** Package of the messenger chosen last time, offered first. */
    var lastApp: String?
        get() = prefs.getString(K_LAST_APP, null)
        set(v) = prefs.edit { putString(K_LAST_APP, v) }

    /** WhatsApp or WhatsApp Business, asked once when both are installed. */
    var whatsappChoice: String?
        get() = prefs.getString(K_WA_CHOICE, null)
        set(v) = prefs.edit { putString(K_WA_CHOICE, v) }

    /** F30: the one-time "WhatsApp may ask to sync contacts" explanation was shown. */
    var whatsappSyncNoticeShown: Boolean
        get() = prefs.getBoolean(K_WA_SYNC_NOTICE, false)
        set(v) = prefs.edit { putBoolean(K_WA_SYNC_NOTICE, v) }

    /** Records that a chat was opened, for the number history note and for "Chat, then decide". */
    fun recordOpened(number: String, app: MessengerApp?, label: String, isContact: Boolean, now: Long = System.currentTimeMillis()) {
        _openedChat.value = if (isContact || app == null) null else OpenedChat(number, label, now)
        if (!_recordEnabled.value) return
        scope.launch(Dispatchers.IO) {
            // Private contacts' numbers are never written down, not even encrypted.
            if (runCatching { isPrivateNumber(number) }.getOrDefault(true)) return@launch
            update { MessagedRecord.record(it, number, app?.packageName, label, now, region()) }
        }
    }

    /** The pending "save as temporary contact?" offer, taken once. Offers older than an hour are dropped. */
    fun takeOpenedChat(now: Long = System.currentTimeMillis()): OpenedChat? {
        val c = _openedChat.value ?: return null
        _openedChat.value = null
        return c.takeIf { now - it.at in 0..OFFER_WINDOW_MS }
    }

    fun lastMessaged(number: String): LastMessaged? = MessagedRecord.find(entries, number, region())?.toUi()

    /** Per-item delete. */
    suspend fun forget(number: String) = update { MessagedRecord.forget(it, number, region()) }

    /** Per-item delete by record key (for entries kept from before F13, which have no number). */
    suspend fun forgetKey(key: String) = update { list -> list.filterNot { it.key == key } }

    /** "Clear all" (also removes a record that can't be read any more). */
    suspend fun clearAll() = update(force = true) { emptyList() }

    /** Call-history retention: drops entries older than [before]. */
    suspend fun pruneOlderThan(before: Long) = update { MessagedRecord.prune(it, before) }

    /** M10: sets "Forget messaged numbers after" and applies it right away. */
    suspend fun setExpiryDays(days: Int) {
        prefs.edit { putInt(K_EXPIRY_DAYS, days.coerceAtLeast(0)) }
        _expiryDays.value = days.coerceAtLeast(0)
        pruneExpired()
    }

    /**
     * M10: drops entries past the record's own expiry or the call-history retention ([retentionDays], 0 = keep),
     * whichever is stricter. Called on load, when the setting changes and by the daily housekeeping.
     */
    suspend fun pruneExpired(retentionDays: Int = 0, now: Long = System.currentTimeMillis()) {
        val before = MessagedRecord.cutoff(_expiryDays.value, retentionDays, now) ?: return
        pruneOlderThan(before)
    }

    /** Turning the record off also clears it. */
    suspend fun setRecordEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(K_RECORD_ENABLED, enabled) }
        _recordEnabled.value = enabled
        if (!enabled) clearAll()
    }

    /**
     * Applies [change] to the record once it has loaded. While the stored record can't be decrypted nothing is
     * written over it (it's read again first, in case the key is back); [force] ("Clear all") replaces it anyway.
     */
    private suspend fun update(force: Boolean = false, change: (List<MessagedEntry>) -> List<MessagedEntry>) = withContext(Dispatchers.IO) {
        loaded.await()
        recordLock.withLock {
            if (_recordUnreadable.value && !force) {
                val again = loadRecord()
                if (_recordUnreadable.value) return@withLock
                publish(again)
            }
            val next = change(entries)
            if (next == entries && !(force && _recordUnreadable.value)) return@withLock
            publish(next)
            if (saveRecord(next) && force) _recordUnreadable.value = false
        }
    }

    private fun publish(list: List<MessagedEntry>) {
        entries = list
        _lastMessaged.value = list.associateTo(LinkedHashMap()) { it.key to it.toUi() }
    }

    private fun MessagedEntry.toUi() = LastMessaged(appPackage?.let { MessengerApp.forPackage(it) }, label, at, number, key)

    private fun loadRecord(): List<MessagedEntry> {
        prefs.getString(K_RECORD, null)?.let { enc ->
            val read = runCatching { decode(String(VaultCrypto.openCallerId(Base64.decode(enc, Base64.NO_WRAP)))) }
            _recordUnreadable.value = read.isFailure
            return read.getOrDefault(emptyList())
        }
        _recordUnreadable.value = false
        // The plain record from before F13: convert once, then remove it.
        val plain = prefs.getString(K_LAST_MESSAGED_PLAIN, null) ?: return emptyList()
        val migrated = runCatching {
            val json = JSONObject(plain)
            json.keys().asSequence().mapNotNull { k ->
                val o = json.getJSONObject(k)
                MessagedRecord.fromLegacy(k, o.optString("p").ifEmpty { null }, o.optString("l"), o.optLong("t"))
            }.sortedBy { it.at }.toList()
        }.getOrDefault(emptyList())
        val kept = if (_recordEnabled.value) migrated else emptyList()
        if (kept.isEmpty() || saveRecord(kept)) prefs.edit { remove(K_LAST_MESSAGED_PLAIN) }
        return kept
    }

    /** Returns false when the record couldn't be encrypted (nothing is then written in the clear). */
    private fun saveRecord(list: List<MessagedEntry>): Boolean {
        if (list.isEmpty()) {
            prefs.edit { remove(K_RECORD).remove(K_LAST_MESSAGED_PLAIN) }
            return true
        }
        val sealed = runCatching { VaultCrypto.sealCallerId(encode(list).toByteArray()) }.getOrNull() ?: return false
        prefs.edit { putString(K_RECORD, Base64.encodeToString(sealed, Base64.NO_WRAP)).remove(K_LAST_MESSAGED_PLAIN) }
        return true
    }

    private fun encode(list: List<MessagedEntry>): String = JSONArray().apply {
        list.forEach { e ->
            put(JSONObject().put("k", e.key).put("n", e.number ?: "").put("p", e.appPackage ?: "").put("l", e.label).put("t", e.at))
        }
    }.toString()

    private fun decode(raw: String): List<MessagedEntry> {
        val a = JSONArray(raw)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            MessagedEntry(o.getString("k"), o.optString("n").ifEmpty { null }, o.optString("p").ifEmpty { null }, o.optString("l"), o.optLong("t"))
        }.sortedBy { it.at }
    }

    private companion object {
        const val K_LAYOUT = "keypad_layout"
        const val K_MY_NAME = "my_name"
        const val K_MY_NUMBER = "my_number"
        const val K_LAST_APP = "last_app"
        const val K_WA_CHOICE = "whatsapp_choice"
        const val K_WA_SYNC_NOTICE = "whatsapp_sync_notice"
        /** The plain record written before F13 (read once, then removed). */
        const val K_LAST_MESSAGED_PLAIN = "last_messaged"
        const val K_RECORD = "last_messaged_enc"
        const val K_RECORD_ENABLED = "record_messaged"
        const val K_EXPIRY_DAYS = "record_expiry_days"
        const val OFFER_WINDOW_MS = 60 * 60 * 1000L
    }
}
