package app.parley.common.people

import app.parley.common.MessengerApp

/**
 * M7: a contact's preferred ways to reach them, stored in `contact_meta.preferredMessenger` (for private contacts in
 * their encrypted record).
 *
 * Compatible with what older versions stored there: a bare account type such as `com.whatsapp` meant "call with
 * this app". That is still read (as [call]) and still written when nothing else is set, so a backup made now
 * restores into an older Parley unchanged. Otherwise the value is `call=…;msg=…;video=…;num=…`.
 *
 * - [call]: account type of the messenger whose call row "Call" uses.
 * - [message]: [SMS], or a messenger package. The messenger opens by its data row when it has linked the person,
 *   otherwise by a link built from the number (so it works when WhatsApp can't see your contacts).
 * - [video]: account type of the messenger whose video row "Video" uses.
 * - [number]: the number to message (null: the default number).
 */
data class MessengerPrefs(
    val call: String? = null,
    val message: String? = null,
    val video: String? = null,
    val number: String? = null,
) {
    val isEmpty: Boolean get() = call == null && message == null && video == null && number == null

    fun encode(): String? = when {
        isEmpty -> null
        message == null && video == null && number == null -> call // the legacy form
        else -> listOfNotNull(
            call?.let { "call=" + esc(it) },
            message?.let { "msg=" + esc(it) },
            video?.let { "video=" + esc(it) },
            number?.let { "num=" + esc(it) },
        ).joinToString(";")
    }

    companion object {
        const val SMS = "sms"

        fun decode(raw: String?): MessengerPrefs {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return MessengerPrefs()
            if (!s.contains('=')) return MessengerPrefs(call = s)
            val m = s.split(';').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to unesc(part.substring(i + 1)).ifEmpty { null }
            }.toMap()
            return MessengerPrefs(m["call"], m["msg"], m["video"], m["num"])
        }

        private fun esc(s: String) = s.replace("%", "%25").replace(";", "%3B").replace("=", "%3D")
        private fun unesc(s: String) = s.replace("%3D", "=").replace("%3B", ";").replace("%25", "%")
    }
}

/** What the Message button does for one contact. */
sealed interface MessageRoute {
    /** Text message to [number]. */
    data class Sms(val number: String) : MessageRoute
    /** Open the messenger's own data row for this person (the app has linked them). */
    data class MessengerRow(val accountType: String) : MessageRoute
    /** Open a chat by number with an explicit package (works when the app hasn't linked the person). */
    data class MessengerLink(val app: MessengerApp, val number: String) : MessageRoute
    /** No usable preference: show the "Message on…" sheet. */
    data object Ask : MessageRoute
}

object MessageRoutes {
    /**
     * Resolves [prefs] against what's there now: [linked] = account types of messengers with a message row for this
     * person, [installed] = installed messenger packages, [numbers] = the contact's numbers, [defaultNumber] = the
     * one to use when no number was chosen. A preferred app that went away falls back to asking.
     */
    fun plan(prefs: MessengerPrefs, linked: Set<String>, installed: Set<String>, numbers: List<String>, defaultNumber: String?): MessageRoute {
        val number = prefs.number?.takeIf { n -> numbers.any { it.filter(Char::isDigit) == n.filter(Char::isDigit) } } ?: defaultNumber
        val pref = prefs.message ?: return MessageRoute.Ask
        if (pref == MessengerPrefs.SMS) return number?.let { MessageRoute.Sms(it) } ?: MessageRoute.Ask
        if (pref in linked) return MessageRoute.MessengerRow(pref)
        val app = MessengerApp.forPackage(pref)
        if (app != null && pref in installed && number != null) return MessageRoute.MessengerLink(app, number)
        return MessageRoute.Ask
    }

    /** "WhatsApp can't see your contacts…" is worth saying when the app is installed but hasn't linked this person. */
    fun showUnlinkedHint(pkg: String, linked: Set<String>, installed: Set<String>): Boolean =
        pkg in installed && pkg !in linked && (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b")
}
