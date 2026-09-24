package app.parley.common

/** A chat app Parley can open for a number you haven't saved. */
enum class Messenger(val label: String) {
    WHATSAPP("WhatsApp"),
    SIGNAL("Signal"),
    TELEGRAM("Telegram"),
    VIBER("Viber"),
}

/** One installable app for a [Messenger] (Signal and Molly both open Signal chats). */
enum class MessengerApp(val messenger: Messenger, val packageName: String, val label: String) {
    WHATSAPP(Messenger.WHATSAPP, "com.whatsapp", "WhatsApp"),
    WHATSAPP_BUSINESS(Messenger.WHATSAPP, "com.whatsapp.w4b", "WhatsApp Business"),
    SIGNAL(Messenger.SIGNAL, "org.thoughtcrime.securesms", "Signal"),
    MOLLY(Messenger.SIGNAL, "im.molly.app", "Molly"),
    TELEGRAM(Messenger.TELEGRAM, "org.telegram.messenger", "Telegram"),
    TELEGRAM_WEB(Messenger.TELEGRAM, "org.telegram.messenger.web", "Telegram"),
    TELEGRAM_X(Messenger.TELEGRAM, "org.thunderdog.challegram", "Telegram X"),
    VIBER(Messenger.VIBER, "com.viber.voip", "Viber"),
    ;

    /** Whether this app can pre-fill a message. */
    val takesText: Boolean get() = messenger == Messenger.WHATSAPP || messenger == Messenger.TELEGRAM

    companion object {
        fun forPackage(pkg: String?): MessengerApp? = entries.firstOrNull { it.packageName == pkg }
    }
}

/** What to start: an intent action, its data URI, the app it must go to, and extras. */
data class MessengerLink(
    val action: String,
    val uri: String,
    /** Always set for messengers. Null only for SMS when no default SMS app is known. */
    val packageName: String?,
    val extras: Map<String, String> = emptyMap(),
) {
    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SENDTO = "android.intent.action.SENDTO"
        const val EXTRA_SMS_BODY = "sms_body"
    }
}

/**
 * Links that open a chat with a phone number directly in the messenger app. Every link goes to an explicit package:
 * a missing or disabled app is reported ("Install or enable WhatsApp"), never handed to a browser, so no website
 * learns the number.
 *
 * - WhatsApp: `https://wa.me/<digits>?text=` (WhatsApp's own verified link, opened in the app).
 * - Signal / Molly: `sgnl://signal.me/#p/+E164` (no pre-filled text).
 * - Telegram: `tg://resolve?phone=<digits>&text=`. The other person's privacy settings may hide them.
 * - Viber: `viber://chat?number=%2B<digits>`.
 * - SMS: `smsto:+E164` with `sms_body`.
 */
object MessengerLinks {
    /**
     * @param e164 the number in international form ("+923001234567"), see [NumberText.toE164].
     * @return null when [e164] isn't an international number.
     */
    fun build(app: MessengerApp, e164: String, text: String? = null): MessengerLink? {
        val digits = internationalDigits(e164) ?: return null
        val body = text?.trim()?.takeIf { it.isNotEmpty() }
        val uri = when (app.messenger) {
            Messenger.WHATSAPP -> "https://wa.me/$digits" + (body?.let { "?text=" + encode(it) } ?: "")
            Messenger.SIGNAL -> "sgnl://signal.me/#p/+$digits"
            Messenger.TELEGRAM -> "tg://resolve?phone=$digits" + (body?.let { "&text=" + encode(it) } ?: "")
            Messenger.VIBER -> "viber://chat?number=%2B$digits"
        }
        return MessengerLink(MessengerLink.ACTION_VIEW, uri, app.packageName)
    }

    /**
     * SMS to [number]: the international form when known (so it works abroad), otherwise the number as given
     * (short codes). [smsPackage] is the default SMS app.
     */
    fun sms(number: String, e164: String?, text: String?, smsPackage: String?): MessengerLink {
        val to = e164 ?: PhoneNumbers.clean(number)
        val body = text?.trim()?.takeIf { it.isNotEmpty() }
        return MessengerLink(
            MessengerLink.ACTION_SENDTO,
            "smsto:" + encode(to, keep = "+"),
            smsPackage,
            if (body != null) mapOf(MessengerLink.EXTRA_SMS_BODY to body) else emptyMap(),
        )
    }

    /**
     * F19: why a chat link can't be built for this number, shown on the disabled row; null when it can. [e164] is the
     * international form, or null when none could be worked out.
     */
    fun unavailableReason(e164: String?): String? = when {
        e164 == null -> "Needs the number with its country code"
        internationalDigits(e164) == null -> "Not a complete international number (7 to 15 digits)"
        else -> null
    }

    /** "Install or enable WhatsApp" when the app is missing or turned off. */
    fun unavailableMessage(app: MessengerApp): String = "Install or enable ${app.label}"

    /** Digits after "+", or null when [e164] isn't a plausible international number. */
    internal fun internationalDigits(e164: String): String? {
        val t = e164.trim()
        if (!t.startsWith("+")) return null
        val d = t.substring(1)
        return d.takeIf { it.length in 7..15 && it.all { c -> c in '0'..'9' } && it[0] != '0' }
    }

    /** Percent-encodes everything except RFC 3986 unreserved characters (spaces become %20, not "+"). */
    fun encode(s: String, keep: String = ""): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~" || c in keep) {
                sb.append(c)
            } else {
                sb.append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
            }
        }
        return sb.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}

/** Ready-made message texts. */
object MessageDrafts {
    /** "Send my details": introduces you to someone who doesn't have your number saved. */
    fun myDetails(name: String?, number: String?): String? {
        val n = name?.trim()?.takeIf { it.isNotEmpty() }
        val num = number?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            n != null && num != null -> "Hi, this is $n. My number is $num."
            n != null -> "Hi, this is $n."
            num != null -> "Hi, my number is $num."
            else -> null
        }
    }
}
