package app.parley.data.messaging

import android.content.Context
import app.parley.common.KeypadLayout
import app.parley.common.MessengerApp
import app.parley.common.PhoneNumbers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Locale

/** "Last messaged via Signal · 2 days ago" for one number. */
data class LastMessaged(val app: MessengerApp?, val label: String, val at: Long)

/** Your details for "Send my details". */
data class MyDetails(val name: String = "", val number: String = "")

/** A chat Parley opened for a number that isn't a contact; offered as a temporary contact on return (M4). */
data class OpenedChat(val number: String, val appLabel: String, val at: Long)

/**
 * Keypad and messaging preferences, feature-scoped (SharedPreferences, private to Parley, never backed up to any
 * server): the keypad alphabet, the last messenger chosen, the WhatsApp/Business choice, your details for drafts,
 * and when you last messaged each number. Nothing here leaves the phone.
 */
class MessagingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("messaging", Context.MODE_PRIVATE)

    private val _keypadLayout = MutableStateFlow(storedLayout())
    /** The chosen keypad alphabet, or null for "same as the phone's language". */
    val keypadLayoutChoice: StateFlow<KeypadLayout?> = _keypadLayout.asStateFlow()

    private val _myDetails = MutableStateFlow(MyDetails(prefs.getString(K_MY_NAME, "").orEmpty(), prefs.getString(K_MY_NUMBER, "").orEmpty()))
    val myDetails: StateFlow<MyDetails> = _myDetails.asStateFlow()

    private val _lastMessaged = MutableStateFlow(loadLastMessaged())
    /** Number match key → last message sent through Parley. */
    val lastMessaged: StateFlow<Map<String, LastMessaged>> = _lastMessaged.asStateFlow()

    private val _openedChat = MutableStateFlow<OpenedChat?>(null)
    /** Set while a chat with an unknown number is open in a messenger (in memory only). */
    val openedChat: StateFlow<OpenedChat?> = _openedChat.asStateFlow()

    private fun storedLayout(): KeypadLayout? =
        prefs.getString(K_LAYOUT, null)?.let { v -> KeypadLayout.entries.firstOrNull { it.name == v } }

    /** The alphabet to use: the chosen one, or the one matching the phone's language. */
    fun effectiveLayout(choice: KeypadLayout? = _keypadLayout.value, locale: Locale = Locale.getDefault()): KeypadLayout =
        choice ?: KeypadLayout.forLocale(locale.language, locale.script)

    fun setKeypadLayout(layout: KeypadLayout?) {
        prefs.edit().apply { if (layout == null) remove(K_LAYOUT) else putString(K_LAYOUT, layout.name) }.apply()
        _keypadLayout.value = layout
    }

    fun setMyDetails(d: MyDetails) {
        prefs.edit().putString(K_MY_NAME, d.name.trim()).putString(K_MY_NUMBER, d.number.trim()).apply()
        _myDetails.value = d.copy(name = d.name.trim(), number = d.number.trim())
    }

    /** Package of the messenger chosen last time, offered first. */
    var lastApp: String?
        get() = prefs.getString(K_LAST_APP, null)
        set(v) = prefs.edit().putString(K_LAST_APP, v).apply()

    /** WhatsApp or WhatsApp Business, asked once when both are installed. */
    var whatsappChoice: String?
        get() = prefs.getString(K_WA_CHOICE, null)
        set(v) = prefs.edit().putString(K_WA_CHOICE, v).apply()

    /** Records that a chat was opened, for the number history note and for "Chat, then decide". */
    fun recordOpened(number: String, app: MessengerApp?, label: String, isContact: Boolean, now: Long = System.currentTimeMillis()) {
        val key = PhoneNumbers.matchKey(number)
        if (key.isEmpty()) return
        val next = LinkedHashMap(_lastMessaged.value)
        next.remove(key)
        next[key] = LastMessaged(app, label, now)
        while (next.size > MAX_ENTRIES) next.remove(next.keys.first())
        _lastMessaged.value = next
        saveLastMessaged(next)
        _openedChat.value = if (isContact || app == null) null else OpenedChat(number, label, now)
    }

    /** The pending "save as temporary contact?" offer, taken once. Offers older than an hour are dropped. */
    fun takeOpenedChat(now: Long = System.currentTimeMillis()): OpenedChat? {
        val c = _openedChat.value ?: return null
        _openedChat.value = null
        return c.takeIf { now - it.at in 0..OFFER_WINDOW_MS }
    }

    fun lastMessaged(number: String): LastMessaged? = _lastMessaged.value[PhoneNumbers.matchKey(number)]

    private fun loadLastMessaged(): Map<String, LastMessaged> {
        val raw = prefs.getString(K_LAST_MESSAGED, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            val out = LinkedHashMap<String, LastMessaged>()
            json.keys().forEach { k ->
                val o = json.getJSONObject(k)
                out[k] = LastMessaged(MessengerApp.forPackage(o.optString("p")), o.optString("l"), o.optLong("t"))
            }
            out.entries.sortedBy { it.value.at }.associateTo(LinkedHashMap()) { it.key to it.value }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveLastMessaged(map: Map<String, LastMessaged>) {
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k, JSONObject().put("p", v.app?.packageName.orEmpty()).put("l", v.label).put("t", v.at)) }
        prefs.edit().putString(K_LAST_MESSAGED, json.toString()).apply()
    }

    private companion object {
        const val K_LAYOUT = "keypad_layout"
        const val K_MY_NAME = "my_name"
        const val K_MY_NUMBER = "my_number"
        const val K_LAST_APP = "last_app"
        const val K_WA_CHOICE = "whatsapp_choice"
        const val K_LAST_MESSAGED = "last_messaged"
        const val MAX_ENTRIES = 500
        const val OFFER_WINDOW_MS = 60 * 60 * 1000L
    }
}
