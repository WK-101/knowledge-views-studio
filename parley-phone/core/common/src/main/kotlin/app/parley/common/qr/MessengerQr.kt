package app.parley.common.qr

/**
 * Q3: messengers whose contact, chat and invite links a QR code can hold. [packages] are the apps (and forks) that
 * open them, the first one being the store page offered when none is installed. [scanInside]: the app mostly wants
 * its own codes scanned inside it (WeChat, KakaoTalk profiles); [legacy]: the service has closed.
 */
enum class QrApp(val label: String, val packages: List<String>, val scanInside: Boolean = false, val legacy: Boolean = false) {
    WHATSAPP("WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b")),
    SIGNAL("Signal", listOf("org.thoughtcrime.securesms", "im.molly.app")),
    TELEGRAM("Telegram", listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram", "org.telegram.plus", "org.telegram.messenger.beta")),
    WECHAT("WeChat", listOf("com.tencent.mm"), scanInside = true),
    LINE("LINE", listOf("jp.naver.line.android")),
    VIBER("Viber", listOf("com.viber.voip")),
    THREEMA("Threema", listOf("ch.threema.app", "ch.threema.app.libre", "ch.threema.app.work")),
    SKYPE("Skype", listOf("com.skype.raider"), legacy = true),
    MESSENGER("Messenger", listOf("com.facebook.orca")),
    INSTAGRAM("Instagram", listOf("com.instagram.android")),
    SNAPCHAT("Snapchat", listOf("com.snapchat.android")),
    KAKAOTALK("KakaoTalk", listOf("com.kakao.talk")),
    ZALO("Zalo", listOf("com.zing.zalo")),
    DISCORD("Discord", listOf("com.discord")),
    SESSION("Session", listOf("network.loki.messenger")),
    SIMPLEX("SimpleX Chat", listOf("chat.simplex.app")),
    MATRIX("Matrix", listOf("im.vector.app", "io.element.android.x", "de.spiritcroc.riotx", "chat.fluffy.fluffychat")),
    WIRE("Wire", listOf("com.wire")),
    BRIAR("Briar", listOf("org.briarproject.briar.android")),
    ;

    /** No link scheme opens it with the payload: Parley copies the text and opens the app (Session IDs, Briar links). */
    val pasteOnly: Boolean get() = this == SESSION || this == BRIAR
}

object MessengerQr {
    private val SESSION_ID = Regex("^05[0-9a-fA-F]{64}$")
    private val THREEMA_ID = Regex("^[0-9A-Z*][0-9A-Z]{7}$")
    private val DIGITS = Regex("^\\+?[0-9]{6,15}$")

    /** The messenger link in [text] (one line, already trimmed), or null when it isn't one. */
    fun classify(text: String): QrPayload.Messenger? {
        val t = text.trim()
        if (SESSION_ID.matches(t)) return QrPayload.Messenger(t, QrApp.SESSION, LinkKind.ID, t.lowercase(), null, t.lowercase())
        val colon = t.indexOf(':')
        if (colon <= 0) return null
        val scheme = t.substring(0, colon).lowercase()
        val rest = t.substring(colon + 1)
        return when (scheme) {
            "http", "https" -> web(t)
            "whatsapp" -> custom(t, QrApp.WHATSAPP, rest)
            "sgnl" -> web("https:" + rest.removePrefix("//").let { "//$it" })?.takeIf { it.app == QrApp.SIGNAL }?.copy(raw = t, uri = normalScheme(t))
            "tg" -> custom(t, QrApp.TELEGRAM, rest)
            "weixin" -> m(t, QrApp.WECHAT, LinkKind.LINK, null)
            "line" -> m(t, QrApp.LINE, LinkKind.LINK, null)
            "viber" -> custom(t, QrApp.VIBER, rest)
            "3mid" -> {
                val id = rest.substringBefore(',').uppercase()
                if (THREEMA_ID.matches(id)) m(t, QrApp.THREEMA, LinkKind.ID, id, uri = "https://threema.id/$id") else null
            }
            "threema" -> {
                val q = QrText.query(rest.substringAfter('?', ""))
                val id = q["id"]?.uppercase()
                if (id != null && THREEMA_ID.matches(id)) m(t, QrApp.THREEMA, LinkKind.ID, id, uri = "https://threema.id/$id") else m(t, QrApp.THREEMA, LinkKind.LINK, null)
            }
            "skype" -> m(t, QrApp.SKYPE, LinkKind.PROFILE, QrText.percentDecode(rest.removePrefix("//").substringBefore('?')).ifEmpty { null })
            "simplex" -> simplexKind(rest)?.let { m(t, QrApp.SIMPLEX, it, null) }
            "matrix" -> matrixUri(t, rest)
            "briar" -> m(t, QrApp.BRIAR, LinkKind.INVITE, null)
            else -> null
        }
    }

    private fun normalScheme(uri: String): String {
        val i = uri.indexOf(':')
        return uri.substring(0, i).lowercase() + uri.substring(i)
    }

    private fun m(raw: String, app: QrApp, kind: LinkKind, handle: String?, phone: String? = null, uri: String = normalScheme(raw)) =
        QrPayload.Messenger(raw, app, kind, handle?.let { QrText.clean(it, keepLines = false) }, phone, uri)

    /** "+491511234567" from digits with or without the plus; null when it isn't a plausible international number. */
    private fun e164(digits: String?): String? {
        val d = digits?.filter { it.isDigit() || it == '+' }?.removePrefix("+") ?: return null
        return if (d.length in 7..15 && d.all { it.isDigit() } && d[0] != '0') "+$d" else null
    }

    private fun custom(raw: String, app: QrApp, rest: String): QrPayload.Messenger {
        val action = rest.removePrefix("//").substringBefore('?').substringBefore('/').lowercase()
        val q = QrText.query(rest.substringAfter('?', ""))
        return when (app) {
            QrApp.WHATSAPP -> e164(q["phone"])?.let { m(raw, app, LinkKind.PHONE, it, it) } ?: m(raw, app, LinkKind.LINK, null)
            QrApp.TELEGRAM -> when (action) {
                "resolve" -> e164(q["phone"])?.let { m(raw, app, LinkKind.PHONE, it, it) }
                    ?: q["domain"]?.let { m(raw, app, LinkKind.PROFILE, "@$it") } ?: m(raw, app, LinkKind.LINK, null)
                "join" -> m(raw, app, LinkKind.GROUP, null)
                "contact" -> m(raw, app, LinkKind.PROFILE, null)
                else -> m(raw, app, LinkKind.LINK, null)
            }
            QrApp.VIBER -> when (action) {
                "chat", "add", "contact" -> (e164(q["number"]) ?: q["number"]?.let { n -> e164("+" + n.filter { it.isDigit() }) })
                    ?.let { m(raw, app, LinkKind.PHONE, it, it) } ?: m(raw, app, LinkKind.LINK, null)
                else -> m(raw, app, LinkKind.LINK, null)
            }
            else -> m(raw, app, LinkKind.LINK, null)
        }
    }

    /** SimpleX link kinds: full (`contact`, `invitation`) and short (`a`, `i`, `g`, `c`) forms. */
    private fun simplexKind(rest: String): LinkKind? {
        val path = rest.removePrefix("/").substringBefore('#').substringBefore('?').trim('/').lowercase()
        return when (path) {
            "contact", "a" -> LinkKind.PROFILE
            "invitation", "i" -> LinkKind.INVITE
            "g" -> LinkKind.GROUP
            "c" -> LinkKind.CHANNEL
            else -> null
        }
    }

    private fun matrixUri(raw: String, rest: String): QrPayload.Messenger? {
        val body = rest.substringBefore('?')
        val parts = body.split('/')
        if (parts.size < 2) return null
        val id = QrText.percentDecode(parts[1])
        return when (parts[0]) {
            "u" -> m(raw, QrApp.MATRIX, LinkKind.PROFILE, "@$id", uri = "https://matrix.to/#/@$id")
            "r" -> m(raw, QrApp.MATRIX, LinkKind.GROUP, "#$id", uri = "https://matrix.to/#/#$id")
            "roomid" -> m(raw, QrApp.MATRIX, LinkKind.GROUP, null)
            else -> null
        }
    }

    /** https links of known hosts. */
    private fun web(url: String): QrPayload.Messenger? {
        val mm = Regex("""^[A-Za-z]+://([^/?#]+)([^?#]*)(\?[^#]*)?(#.*)?$""").find(url) ?: return null
        val host = mm.groupValues[1].substringAfterLast('@').substringBefore(':').lowercase().removePrefix("www.")
        val path = mm.groupValues[2]
        val query = QrText.query(mm.groupValues[3].removePrefix("?"))
        val fragment = mm.groupValues[4].removePrefix("#")
        val segs = path.split('/').filter { it.isNotEmpty() }
        val first = segs.getOrNull(0)
        val uri = if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else normalScheme(url)
        fun r(app: QrApp, kind: LinkKind, handle: String?, phone: String? = null) = m(url, app, kind, handle, phone, uri)
        return when (host) {
            "wa.me" -> when {
                first == null -> null
                first == "qr" || first == "message" -> r(QrApp.WHATSAPP, LinkKind.PROFILE, null)
                first == "c" -> r(QrApp.WHATSAPP, LinkKind.PROFILE, null)
                else -> e164(first)?.let { r(QrApp.WHATSAPP, LinkKind.PHONE, it, it) } ?: r(QrApp.WHATSAPP, LinkKind.LINK, null)
            }
            "api.whatsapp.com" -> e164(query["phone"])?.let { r(QrApp.WHATSAPP, LinkKind.PHONE, it, it) } ?: r(QrApp.WHATSAPP, LinkKind.LINK, null)
            "chat.whatsapp.com" -> r(QrApp.WHATSAPP, LinkKind.GROUP, null)
            "whatsapp.com" -> if (first == "channel") r(QrApp.WHATSAPP, LinkKind.CHANNEL, null) else null
            "signal.me" -> when {
                fragment.startsWith("p/") -> e164(QrText.percentDecode(fragment.removePrefix("p/")))?.let { r(QrApp.SIGNAL, LinkKind.PHONE, it, it) }
                    ?: r(QrApp.SIGNAL, LinkKind.LINK, null)
                fragment.startsWith("eu/") -> r(QrApp.SIGNAL, LinkKind.PROFILE, null)
                else -> r(QrApp.SIGNAL, LinkKind.LINK, null)
            }
            "signal.group" -> r(QrApp.SIGNAL, LinkKind.GROUP, null)
            "t.me", "telegram.me", "telegram.dog" -> when {
                first == null -> null
                first == "joinchat" -> r(QrApp.TELEGRAM, LinkKind.GROUP, null)
                first == "contact" -> r(QrApp.TELEGRAM, LinkKind.PROFILE, null)
                first.startsWith("+") -> e164(first)?.let { r(QrApp.TELEGRAM, LinkKind.PHONE, it, it) } ?: r(QrApp.TELEGRAM, LinkKind.GROUP, null)
                first in setOf("addstickers", "addemoji", "addtheme", "proxy", "socks", "share", "setlanguage", "login", "invoice", "giftcode", "boost") ->
                    r(QrApp.TELEGRAM, LinkKind.LINK, null)
                segs.size >= 2 -> r(QrApp.TELEGRAM, LinkKind.CHANNEL, "@$first")
                else -> r(QrApp.TELEGRAM, LinkKind.PROFILE, "@$first")
            }
            "u.wechat.com", "weixin.qq.com" -> r(QrApp.WECHAT, LinkKind.PROFILE, null)
            "line.me" -> {
                val s = if (first.equals("R", true)) segs.drop(1) else segs
                when {
                    s.getOrNull(0) == "ti" && s.getOrNull(1) == "p" -> r(QrApp.LINE, LinkKind.PROFILE, s.getOrNull(2)?.let(QrText::percentDecode))
                    s.getOrNull(0) == "ti" && (s.getOrNull(1) == "g" || s.getOrNull(1) == "g2") -> r(QrApp.LINE, LinkKind.GROUP, null)
                    else -> r(QrApp.LINE, LinkKind.LINK, null)
                }
            }
            "lin.ee" -> r(QrApp.LINE, LinkKind.PROFILE, null)
            "invite.viber.com" -> r(QrApp.VIBER, LinkKind.GROUP, null)
            "threema.id" -> first?.uppercase()?.takeIf { THREEMA_ID.matches(it) }?.let { r(QrApp.THREEMA, LinkKind.ID, it) }
            "m.me" -> first?.let { r(QrApp.MESSENGER, LinkKind.PROFILE, it) }
            "instagram.com", "instagr.am" -> {
                val user = if (first == "_u") segs.getOrNull(1) else first
                when {
                    user == null -> null
                    user in setOf("p", "reel", "reels", "stories", "explore", "tv", "accounts", "direct") -> r(QrApp.INSTAGRAM, LinkKind.LINK, null)
                    else -> r(QrApp.INSTAGRAM, LinkKind.PROFILE, "@$user")
                }
            }
            "snapchat.com" -> if (first == "add" && segs.size >= 2) r(QrApp.SNAPCHAT, LinkKind.PROFILE, segs[1]) else r(QrApp.SNAPCHAT, LinkKind.LINK, null)
            "open.kakao.com" -> r(QrApp.KAKAOTALK, LinkKind.GROUP, null)
            "qr.kakao.com" -> r(QrApp.KAKAOTALK, LinkKind.PROFILE, null)
            "zalo.me" -> when {
                first == null -> null
                first == "g" -> r(QrApp.ZALO, LinkKind.GROUP, null)
                first.all { it.isDigit() } && first.length in 9..15 -> {
                    // Zalo's own links use the number as written in Vietnam ("0912…"); 84… is international.
                    val phone = if (first.startsWith("0")) "+84" + first.drop(1) else e164(first)
                    r(QrApp.ZALO, LinkKind.PHONE, phone ?: first, phone)
                }
                else -> r(QrApp.ZALO, LinkKind.PROFILE, first)
            }
            "discord.gg" -> r(QrApp.DISCORD, LinkKind.INVITE, null)
            "discord.com", "discordapp.com" -> when (first) {
                "invite" -> r(QrApp.DISCORD, LinkKind.INVITE, null)
                "users" -> r(QrApp.DISCORD, LinkKind.PROFILE, null)
                else -> null
            }
            "simplex.chat" -> simplexKind(path)?.let { r(QrApp.SIMPLEX, it, null) }
            "matrix.to" -> {
                val id = QrText.percentDecode(fragment.removePrefix("/").substringBefore('?').substringBefore('/'))
                when {
                    id.startsWith("@") -> r(QrApp.MATRIX, LinkKind.PROFILE, id)
                    id.startsWith("#") -> r(QrApp.MATRIX, LinkKind.GROUP, id)
                    id.startsWith("!") -> r(QrApp.MATRIX, LinkKind.GROUP, null)
                    else -> null
                }
            }
            "account.wire.com" -> when (first) {
                "user-profile" -> r(QrApp.WIRE, LinkKind.PROFILE, null)
                "conversation-join" -> r(QrApp.WIRE, LinkKind.GROUP, null)
                else -> null
            }
            else -> when {
                // SimpleX short links live on its relay servers (smp*.simplex.im/a#…).
                host.endsWith(".simplex.im") && fragment.isNotEmpty() -> simplexKind(path)?.let { r(QrApp.SIMPLEX, it, null) }
                else -> null
            }
        }
    }
}
