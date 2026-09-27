package app.parley.common

import app.parley.common.qr.QrApp

/**
 * The one registry of messenger apps Parley knows. Every other list is derived from it, so a new flavour or fork is
 * added in one place:
 * - [MessengerApp]: the installed apps that open a chat for a phone number ("Message or call on…" for any number);
 * - [ReachApp] (this enum): the apps whose contacts rows Parley recognises, by account type and mimetype;
 * - [QrApp.packages]: the apps that open a scanned chat link;
 * - [app.parley.common.people.Handles]: the apps that open a typed-in handle;
 * - [app.parley.common.record.Messengers]: the messenger accounts backups and exports leave out;
 * - the app manifest's `<queries>`, checked against [ALL_PACKAGES] by a unit test.
 *
 * Package lists start with the main app (its store page is offered when none is installed), then flavours and forks.
 */
enum class MessengerCatalog(
    val label: String,
    val packages: List<String>,
    /** Account types of the raw contacts the app's sync adapter writes; empty when it writes none. */
    val accountTypes: List<String> = emptyList(),
    /** What the app's own contacts mimetypes start with, after `vnd.android.cursor.item/`. */
    val mimePrefixes: List<String> = emptyList(),
    /** Exact data mimetypes (after `vnd.android.cursor.item/`) the app is known to write, and what opening them does. */
    val mimes: Map<String, ReachKind> = emptyMap(),
    /** How a chat opens for a phone number that isn't saved ([MessengerLinks.build]); null when the app has no such link. */
    val chat: Messenger? = null,
    /** The family of scanned links this app opens; null when Parley reads none of its codes. */
    val qr: QrApp? = null,
    /** URL schemes of the app's own links (QR codes, handles). */
    val schemes: List<String> = emptyList(),
    /** Web hosts of the app's own links. */
    val hosts: List<String> = emptyList(),
) {
    WHATSAPP(
        "WhatsApp", listOf("com.whatsapp"), listOf("com.whatsapp"), listOf("vnd.com.whatsapp."),
        whatsAppMimes("com.whatsapp"), Messenger.WHATSAPP, QrApp.WHATSAPP, listOf("whatsapp"),
        listOf("wa.me", "api.whatsapp.com", "chat.whatsapp.com", "whatsapp.com"),
    ),
    WHATSAPP_BUSINESS(
        "WhatsApp Business", listOf("com.whatsapp.w4b"), listOf("com.whatsapp.w4b"), listOf("vnd.com.whatsapp.w4b."),
        whatsAppMimes("com.whatsapp.w4b"), Messenger.WHATSAPP, QrApp.WHATSAPP,
    ),
    SIGNAL(
        "Signal", listOf("org.thoughtcrime.securesms"), listOf("org.thoughtcrime.securesms"), listOf("vnd.org.thoughtcrime.securesms."),
        // SyncSystemContactLinksJob / contactsformat.xml; Molly writes the same ones.
        mapOf(
            "vnd.org.thoughtcrime.securesms.contact" to ReachKind.MESSAGE,
            "vnd.org.thoughtcrime.securesms.call" to ReachKind.VOICE,
            "vnd.org.thoughtcrime.securesms.videocall" to ReachKind.VIDEO,
        ),
        Messenger.SIGNAL, QrApp.SIGNAL, listOf("sgnl"), listOf("signal.me", "signal.group"),
    ),
    /** A Signal fork: it writes Signal's mimetypes, so it is told apart by its account type only. */
    MOLLY("Molly", listOf("im.molly.app", "im.molly.app.unifiedpush"), listOf("im.molly.app", "im.molly.app.unifiedpush"), chat = Messenger.SIGNAL, qr = QrApp.SIGNAL),
    TELEGRAM(
        "Telegram", listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta", "org.telegram.plus"),
        listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta", "org.telegram.plus"),
        listOf("vnd.org.telegram.messenger."),
        // res/xml/contacts.xml
        mapOf(
            "vnd.org.telegram.messenger.android.profile" to ReachKind.MESSAGE,
            "vnd.org.telegram.messenger.android.call" to ReachKind.VOICE,
            "vnd.org.telegram.messenger.android.call.video" to ReachKind.VIDEO,
        ),
        Messenger.TELEGRAM, QrApp.TELEGRAM, listOf("tg"), listOf("t.me", "telegram.me", "telegram.dog"),
    ),
    TELEGRAM_X(
        "Telegram X", listOf("org.thunderdog.challegram"), listOf("org.thunderdog.challegram"), listOf("vnd.org.thunderdog.challegram."),
        chat = Messenger.TELEGRAM, qr = QrApp.TELEGRAM,
    ),
    VIBER(
        "Viber", listOf("com.viber.voip"), listOf("com.viber.voip"), listOf("vnd.com.viber.voip."),
        // Free calls and chats; Viber Out rows call the phone network and are billed.
        mapOf(
            "vnd.com.viber.voip.viber_number_message" to ReachKind.MESSAGE,
            "vnd.com.viber.voip.viber_number_call" to ReachKind.VOICE,
            "vnd.com.viber.voip.viber_out_call_viber" to ReachKind.PAID_CALL,
            "vnd.com.viber.voip.viber_out_call_none" to ReachKind.PAID_CALL,
        ),
        Messenger.VIBER, QrApp.VIBER, listOf("viber"), listOf("invite.viber.com"),
    ),
    THREEMA(
        "Threema", THREEMA_PACKAGES, THREEMA_PACKAGES, listOf("vnd.ch.threema.app."),
        qr = QrApp.THREEMA, schemes = listOf("threema", "3mid"), hosts = listOf("threema.id"),
    ),
    LINE(
        "LINE", listOf("jp.naver.line.android"), listOf("jp.naver.line.android"), listOf("vnd.jp.naver.line.android."),
        qr = QrApp.LINE, schemes = listOf("line"), hosts = listOf("line.me", "lin.ee"),
    ),
    IMO("imo", listOf("com.imo.android.imoim"), listOf("com.imo.android.imoim"), listOf("vnd.com.imo.android.imoim.")),
    BOTIM("BOTIM", listOf("im.thebot.messenger"), listOf("im.thebot.messenger"), listOf("vnd.im.thebot.messenger.")),
    /** Google Meet (formerly Duo). */
    MEET(
        "Google Meet", listOf("com.google.android.apps.tachyon"), listOf("com.google.android.apps.tachyon"),
        listOf("com.google.android.apps.tachyon.", "vnd.com.google.android.apps.tachyon."),
        mapOf("com.google.android.apps.tachyon.phone" to ReachKind.VIDEO, "com.google.android.apps.tachyon.phone.audio" to ReachKind.VOICE),
    ),
    SKYPE(
        "Skype", listOf("com.skype.raider"), listOf("com.skype.raider"), listOf("vnd.com.skype.raider.", "com.skype.android."),
        qr = QrApp.SKYPE, schemes = listOf("skype"),
    ),
    WIRE("Wire", listOf("com.wire"), listOf("com.wire"), listOf("vnd.com.wire."), qr = QrApp.WIRE, hosts = listOf("account.wire.com")),
    /** Matrix: Element writes contacts rows; Element X, SchildiChat and FluffyChat open the same links. */
    ELEMENT(
        "Element", listOf("im.vector.app", "io.element.android.x", "de.spiritcroc.riotx", "chat.fluffy.fluffychat"), listOf("im.vector.app"),
        listOf("vnd.im.vector.app."), qr = QrApp.MATRIX, schemes = listOf("matrix"), hosts = listOf("matrix.to"),
    ),
    BRIAR(
        "Briar", listOf("org.briarproject.briar.android"), listOf("org.briarproject.briar.android"), listOf("vnd.org.briarproject.briar.android."),
        qr = QrApp.BRIAR, schemes = listOf("briar"),
    ),
    SIMPLEX(
        "SimpleX", listOf("chat.simplex.app"), listOf("chat.simplex.app"), listOf("vnd.chat.simplex.app."),
        qr = QrApp.SIMPLEX, schemes = listOf("simplex"), hosts = listOf("simplex.chat", "simplex.im"),
    ),
    WECHAT("WeChat", listOf("com.tencent.mm"), qr = QrApp.WECHAT, schemes = listOf("weixin"), hosts = listOf("u.wechat.com", "weixin.qq.com")),
    FACEBOOK_MESSENGER("Messenger", listOf("com.facebook.orca"), listOf("com.facebook.orca"), qr = QrApp.MESSENGER, hosts = listOf("m.me")),
    INSTAGRAM("Instagram", listOf("com.instagram.android"), qr = QrApp.INSTAGRAM, hosts = listOf("instagram.com", "instagr.am")),
    SNAPCHAT("Snapchat", listOf("com.snapchat.android"), qr = QrApp.SNAPCHAT, hosts = listOf("snapchat.com")),
    KAKAOTALK("KakaoTalk", listOf("com.kakao.talk"), qr = QrApp.KAKAOTALK, hosts = listOf("open.kakao.com", "qr.kakao.com")),
    ZALO("Zalo", listOf("com.zing.zalo"), qr = QrApp.ZALO, hosts = listOf("zalo.me")),
    DISCORD("Discord", listOf("com.discord"), listOf("com.discord"), qr = QrApp.DISCORD, hosts = listOf("discord.gg", "discord.com", "discordapp.com")),
    SESSION("Session", listOf("network.loki.messenger"), qr = QrApp.SESSION),
    /** XMPP clients (handles only). */
    XMPP("XMPP", listOf("eu.siacs.conversations", "im.quicksy.client", "org.monocles.chat"), schemes = listOf("xmpp")),
    /** Closed service: its raw contacts can still sit on old phones, and stay read-only. */
    KIK("Kik", emptyList(), listOf("kik.android")),
    ;

    /** The app's main package, or its account type when it has no package Parley opens. */
    val packageName: String get() = packages.firstOrNull() ?: accountTypes.first()

    companion object {
        /** Every package any entry names: the manifest's `<queries>` must list each (see the manifest test). */
        val ALL_PACKAGES: Set<String> = entries.flatMap { it.packages }.toSet()

        /** Every account type Parley knows as a messenger's (read-only, owned by the app's sync adapter). */
        val ACCOUNT_TYPES: Set<String> = entries.flatMap { it.accountTypes }.toSet()

        fun forPackage(pkg: String?): MessengerCatalog? = pkg?.let { p -> entries.firstOrNull { p in it.packages } }

        fun forAccountType(type: String?): MessengerCatalog? = type?.let { t -> entries.firstOrNull { t in it.accountTypes } }

        /** The app whose mimetype namespace [mime] is in, longest prefix first (Business before WhatsApp). */
        fun forMime(mime: String): MessengerCatalog? {
            val sub = mime.substringAfter('/', "")
            return byPrefix.firstOrNull { sub.startsWith(it.first) }?.second
        }

        /** The entry for an installed chat app, so a chat app and its contacts rows are one entry. */
        fun forMessengerApp(app: MessengerApp): MessengerCatalog = app.entry

        /** The entry whose links use [scheme] ("tg", "sgnl"…). */
        fun forScheme(scheme: String): MessengerCatalog? = scheme.lowercase().let { s -> entries.firstOrNull { s in it.schemes } }

        private val byPrefix: List<Pair<String, MessengerCatalog>> =
            entries.flatMap { app -> app.mimePrefixes.map { it to app } }.sortedByDescending { it.first.length }
    }
}

/** The name the contacts-row code has always used for a catalog entry. */
typealias ReachApp = MessengerCatalog

private val THREEMA_PACKAGES = listOf("ch.threema.app", "ch.threema.app.libre", "ch.threema.app.work", "ch.threema.app.green", "ch.threema.app.onprem")

private fun whatsAppMimes(pkg: String): Map<String, ReachKind> = mapOf(
    "vnd.$pkg.profile" to ReachKind.MESSAGE,
    "vnd.$pkg.voip.call" to ReachKind.VOICE,
    "vnd.$pkg.video.call" to ReachKind.VIDEO,
)

/**
 * One installable app that opens a chat for a phone number: a package of a [MessengerCatalog] entry with a [chat]
 * link. Signal and Molly both open Signal chats; Telegram's flavours share one entry.
 */
data class MessengerApp(val entry: MessengerCatalog, val packageName: String) {
    val messenger: Messenger = requireNotNull(entry.chat) { "${entry.name} has no chat link" }
    val label: String get() = entry.label

    /** Whether this app can pre-fill a message. */
    val takesText: Boolean get() = messenger == Messenger.WHATSAPP || messenger == Messenger.TELEGRAM

    companion object {
        /** Every package that opens chats by number, main apps before their flavours. */
        val entries: List<MessengerApp> = MessengerCatalog.entries.filter { it.chat != null }.flatMap { e -> e.packages.map { MessengerApp(e, it) } }

        fun forPackage(pkg: String?): MessengerApp? = entries.firstOrNull { it.packageName == pkg }

        /** The main package of [entry] (for tests and fixed choices). */
        fun of(entry: MessengerCatalog): MessengerApp = entries.first { it.entry == entry }

        /** One app per catalog entry among [installed] (the main app when several flavours are there), in catalog order. */
        fun onePerApp(installed: List<MessengerApp>): List<MessengerApp> =
            installed.sortedWith(compareBy({ it.entry.ordinal }, { it.entry.packages.indexOf(it.packageName) })).distinctBy { it.entry }
    }
}
