package app.parley.common.people

import app.parley.common.record.Mime

/** ContactsContract Im.PROTOCOL_* values (duplicated to keep this module pure JVM). */
object ImProtocol {
    const val CUSTOM = -1
    const val AIM_PROTOCOL = 0
    const val MSN_PROTOCOL = 1
    const val YAHOO_PROTOCOL = 2
    const val SKYPE_PROTOCOL = 3
    const val QQ_PROTOCOL = 4
    const val GOOGLE_TALK_PROTOCOL = 5
    const val ICQ_PROTOCOL = 6
    const val JABBER = 7
    const val NETMEETING_PROTOCOL = 8
    /** Not an Im protocol: SIP handles are SipAddress rows. */
    const val SIP_PROTOCOL = -100
}

/**
 * I1: messenger handles (Matrix, Threema ID, Telegram and Signal usernames, Discord, XMPP, SIP…) as Android stores
 * them: `Im` rows (DATA1 handle, DATA5 protocol, DATA6 custom protocol name) and `SipAddress` rows (DATA1).
 *
 * The services Android predefines keep their protocol number; the others are written as PROTOCOL_CUSTOM with the
 * service's English name as the custom protocol, which is also how Google Contacts and most CardDAV servers write
 * them, so they round-trip. Anything unrecognised stays exactly as found ([HandleService.OTHER] keeps its custom
 * protocol name).
 */
enum class HandleService(
    val key: String,
    val label: String,
    /** ContactsContract Im.PROTOCOL_* value, or [CUSTOM] with [customName]. */
    val protocol: Int,
    val customName: String?,
    /** What to type, shown under the field. */
    val hint: String,
    val placeholder: String,
) {
    MATRIX("matrix", "Matrix", ImProtocol.CUSTOM, "Matrix", "Full Matrix ID with the server", "@name:matrix.org"),
    SIGNAL("signal", "Signal username", ImProtocol.CUSTOM, "Signal", "Username with its number, e.g. name.01", "name.01"),
    TELEGRAM("telegram", "Telegram", ImProtocol.CUSTOM, "Telegram", "Username without @ (5 to 32 letters, digits or _)", "username"),
    THREEMA("threema", "Threema ID", ImProtocol.CUSTOM, "Threema", "The 8-character Threema ID", "ABCD1234"),
    DISCORD("discord", "Discord", ImProtocol.CUSTOM, "Discord", "Username (or the numeric user ID to open the profile)", "name"),
    XMPP("xmpp", "XMPP / Jabber", ImProtocol.JABBER, null, "Address like name@server", "name@conversations.im"),
    SIP("sip", "SIP address", ImProtocol.SIP_PROTOCOL, null, "Internet-calling address like name@provider", "name@sip.example.com"),
    SKYPE("skype", "Skype", ImProtocol.SKYPE_PROTOCOL, null, "Skype name", "live:name"),
    WIRE("wire", "Wire", ImProtocol.CUSTOM, "Wire", "Username without @", "name"),
    SESSION("session", "Session", ImProtocol.CUSTOM, "Session", "66-character Session ID", "05…"),
    SIMPLEX("simplex", "SimpleX", ImProtocol.CUSTOM, "SimpleX", "Paste the contact link", "https://simplex.chat/contact#…"),
    MASTODON("mastodon", "Mastodon", ImProtocol.CUSTOM, "Mastodon", "Handle like @name@server", "@name@mastodon.social"),
    GOOGLE_TALK("gtalk", "Google Talk", ImProtocol.GOOGLE_TALK_PROTOCOL, null, "Address", "name@gmail.com"),
    AIM("aim", "AIM", ImProtocol.AIM_PROTOCOL, null, "Screen name", "name"),
    MSN("msn", "Windows Live", ImProtocol.MSN_PROTOCOL, null, "Address", "name@outlook.com"),
    YAHOO("yahoo", "Yahoo", ImProtocol.YAHOO_PROTOCOL, null, "Yahoo ID", "name"),
    QQ("qq", "QQ", ImProtocol.QQ_PROTOCOL, null, "QQ number", "12345678"),
    ICQ("icq", "ICQ", ImProtocol.ICQ_PROTOCOL, null, "ICQ number", "12345678"),
    NETMEETING("netmeeting", "NetMeeting", ImProtocol.NETMEETING_PROTOCOL, null, "Address", ""),
    OTHER("other", "Other", ImProtocol.CUSTOM, null, "Any handle", ""),
    ;

    val isSip: Boolean get() = this == SIP

    companion object {
        fun byKey(key: String?): HandleService? = entries.firstOrNull { it.key == key }

        /** Services offered first in the editor's "Add handle" menu. */
        val common = listOf(SIGNAL, MATRIX, TELEGRAM, THREEMA, XMPP, SIP, DISCORD, WIRE, SESSION, SIMPLEX, MASTODON, SKYPE)
    }
}

/** One handle as shown and edited. [customProtocol] keeps an unknown service's own name ("Jami", "Briar"…). */
data class Handle(
    val service: HandleService,
    val value: String,
    val customProtocol: String? = null,
) {
    /** "Matrix", or the unknown service's own name. */
    val serviceLabel: String get() = if (service == HandleService.OTHER) customProtocol?.takeIf { it.isNotBlank() } ?: "Handle" else service.label
}

/** What opening a handle does: an app link (explicit package when known) or a web link that needs confirmation. */
data class HandleLink(
    val uri: String,
    /** Apps that open this link directly, most likely first. Empty: any app may (the user chooses). */
    val packages: List<String> = emptyList(),
    /** An https link: never opened in a browser without asking first. */
    val isWeb: Boolean = false,
    /** A call rather than a chat (SIP). */
    val isCall: Boolean = false,
)

object Handles {
    /** The service of an `Im` row. */
    fun fromIm(protocol: Int?, customProtocol: String?): HandleService {
        if (protocol != null && protocol != ImProtocol.CUSTOM) {
            return HandleService.entries.firstOrNull { it.protocol == protocol && it.customName == null && it != HandleService.SIP } ?: HandleService.OTHER
        }
        val name = customProtocol?.trim()?.lowercase().orEmpty()
        if (name.isEmpty()) return HandleService.OTHER
        return HandleService.entries.firstOrNull { s ->
            s.customName?.lowercase() == name || s.key == name || s.label.lowercase() == name
        } ?: when {
            name.contains("matrix") || name == "element" -> HandleService.MATRIX
            name.contains("threema") -> HandleService.THREEMA
            name.contains("telegram") -> HandleService.TELEGRAM
            name.contains("signal") -> HandleService.SIGNAL
            name.contains("discord") -> HandleService.DISCORD
            name.contains("xmpp") || name.contains("jabber") -> HandleService.XMPP
            else -> HandleService.OTHER
        }
    }

    /** Reads one data row (mimetype + data1…); null for rows that aren't handles. */
    fun fromRow(mime: String, data1: String?, protocol: String?, customProtocol: String?): Handle? {
        val v = data1?.trim().orEmpty()
        return when (mime) {
            Mime.SIP -> Handle(HandleService.SIP, v)
            Mime.IM -> {
                val service = fromIm(protocol?.trim()?.toIntOrNull(), customProtocol)
                Handle(service, v, customProtocol?.takeIf { service == HandleService.OTHER && it.isNotBlank() })
            }
            else -> null
        }
    }

    /** Mimetype and columns to write for [h] (Im: data1, data5 protocol, data6 custom protocol; SIP: data1). */
    fun toColumns(h: Handle): Pair<String, Map<String, String?>> {
        val value = normalize(h.service, h.value)
        if (h.service.isSip) return Mime.SIP to mapOf("data1" to value)
        val custom = h.service.protocol == ImProtocol.CUSTOM
        return Mime.IM to mapOf(
            "data1" to value,
            "data5" to h.service.protocol.toString(),
            "data6" to if (custom) (h.service.customName ?: h.customProtocol?.trim()?.ifEmpty { null } ?: "Other") else null,
        )
    }

    /** Tidies what was typed: strips the scheme or "@" the service doesn't store. Never changes the meaning. */
    fun normalize(service: HandleService, raw: String): String {
        val s = raw.trim()
        return when (service) {
            HandleService.TELEGRAM -> s.removePrefix("https://t.me/").removePrefix("t.me/").removePrefix("@")
            HandleService.THREEMA -> s.removePrefix("https://threema.id/").uppercase()
            HandleService.XMPP -> s.removePrefix("xmpp:")
            HandleService.SIP -> s.removePrefix("sip:").removePrefix("sips:")
            HandleService.WIRE -> s.removePrefix("@")
            HandleService.MATRIX -> s.removePrefix("https://matrix.to/#/")
            else -> s
        }
    }

    private val telegramName = Regex("[A-Za-z][A-Za-z0-9_]{4,31}")
    private val threemaId = Regex("[A-Z0-9*][A-Z0-9]{7}")
    private val matrixId = Regex("[@!#][^:\\s]+:[A-Za-z0-9.\\-]+(:\\d+)?")
    private val address = Regex("[^@\\s]+@[A-Za-z0-9.\\-]+")
    private val signalName = Regex("[A-Za-z_][A-Za-z0-9_]{2,31}\\.\\d{2,9}")

    /** Why [value] doesn't look right for [service] (shown under the field), or null. Saving is never blocked. */
    fun problem(service: HandleService, value: String): String? {
        val v = normalize(service, value)
        if (v.isEmpty()) return null
        return when (service) {
            HandleService.TELEGRAM -> if (telegramName.matches(v)) null else "Telegram usernames have 5 to 32 letters, digits or _"
            HandleService.THREEMA -> if (threemaId.matches(v)) null else "A Threema ID has 8 letters and digits"
            HandleService.MATRIX -> if (matrixId.matches(v)) null else "Needs the server too, like @name:matrix.org"
            HandleService.XMPP, HandleService.SIP -> if (address.matches(v)) null else "Needs the server too, like name@server"
            HandleService.SIGNAL -> if (signalName.matches(v)) null else "Signal usernames end with a dot and digits, like name.01"
            else -> null
        }
    }

    /**
     * How to open [h], or null when the service has no link (the handle can still be copied). App links carry the
     * packages that handle them; https links are marked [HandleLink.isWeb] so the caller asks before a browser opens.
     */
    fun link(h: Handle): HandleLink? {
        val v = normalize(h.service, h.value)
        if (v.isEmpty()) return null
        return when (h.service) {
            HandleService.MATRIX -> if (matrixId.matches(v)) {
                HandleLink("https://matrix.to/#/" + v, MATRIX_APPS, isWeb = true)
            } else null
            HandleService.THREEMA -> if (threemaId.matches(v)) {
                HandleLink("threema://compose?id=" + v, listOf("ch.threema.app", "ch.threema.app.libre", "ch.threema.app.work"))
            } else null
            HandleService.TELEGRAM -> if (telegramName.matches(v)) {
                HandleLink("tg://resolve?domain=" + v, TELEGRAM_APPS)
            } else null
            HandleService.SIGNAL -> HandleLink("sgnl://signal.me/#u/" + encode(v), listOf("org.thoughtcrime.securesms", "im.molly.app"))
            HandleService.XMPP -> if (address.matches(v)) HandleLink("xmpp:" + v, XMPP_APPS) else null
            HandleService.SIP -> if (address.matches(v)) HandleLink("sip:" + v, isCall = true) else null
            HandleService.SKYPE -> HandleLink("skype:" + encode(v) + "?chat", listOf("com.skype.raider"))
            HandleService.DISCORD -> if (v.all { it.isDigit() } && v.length in 15..21) {
                HandleLink("https://discord.com/users/" + v, listOf("com.discord"), isWeb = true)
            } else null
            HandleService.SIMPLEX -> if (v.startsWith("https://simplex.chat/") || v.startsWith("simplex:")) {
                HandleLink(v, listOf("chat.simplex.app"), isWeb = v.startsWith("https://"))
            } else null
            else -> null
        }
    }

    /** Packages the app should be able to see (manifest `<queries>`), for [link] targets. */
    val MATRIX_APPS = listOf("im.vector.app", "de.spiritcroc.riotx", "chat.fluffy.fluffychat")
    val TELEGRAM_APPS = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram", "org.telegram.plus", "org.telegram.messenger.beta")
    val XMPP_APPS = listOf("eu.siacs.conversations", "im.quicksy.client", "org.monocles.chat")

    private fun encode(s: String): String = app.parley.common.MessengerLinks.encode(s, keep = "@:")
}
