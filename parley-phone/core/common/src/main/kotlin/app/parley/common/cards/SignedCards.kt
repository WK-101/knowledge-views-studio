package app.parley.common.cards

import app.parley.common.people.MeCard
import app.parley.common.people.MeCards
import app.parley.common.spam.Ed25519
import java.util.Base64
import kotlinx.serialization.Serializable

/** A profile on a shared card: the service's label ("Instagram") and the link. */
@Serializable
data class CardProfile(val label: String, val url: String)

/**
 * What a shared card says, in the form that is signed: exactly the fields of the vCard Parley writes for My card
 * ([MeCards.vcard]), so the receiver rebuilds the same fields from the vCard it got and checks the signature over them.
 * The private note is never part of it.
 */
@Serializable
data class CardFields(
    val name: String = "",
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val company: String = "",
    val title: String = "",
    val websites: List<String> = emptyList(),
    val profiles: List<CardProfile> = emptyList(),
    val address: String = "",
) {
    val isEmpty: Boolean
        get() = name.isEmpty() && phones.isEmpty() && emails.isEmpty() && company.isEmpty() && title.isEmpty() &&
            websites.isEmpty() && profiles.isEmpty() && address.isEmpty()

    /**
     * Whitespace collapsed in the name (the vCard splits it into given and family names and the receiver joins them
     * again), everything trimmed, blanks and repeats dropped, so both sides sign and check the same text.
     */
    fun canonical(): CardFields = CardFields(
        name = name.split(WS).filter { it.isNotEmpty() }.joinToString(" "),
        phones = phones.map(::norm).filter { it.isNotEmpty() }.distinct(),
        emails = emails.map(::norm).filter { it.isNotEmpty() }.distinct(),
        company = norm(company),
        title = norm(title),
        websites = websites.map(::norm).filter { it.isNotEmpty() }.distinct(),
        profiles = profiles.map { CardProfile(norm(it.label), norm(it.url)) }.filter { it.url.isNotEmpty() }.distinct(),
        address = norm(address),
    )

    companion object {
        private val WS = Regex("\\s+")

        /** Line breaks as the vCard carries them (it writes every one as `\\n`), then trimmed. */
        private fun norm(s: String) = s.replace("\r\n", "\n").replace('\r', '\n').trim()

        /** The fields of [card] that [MeCards.vcard] writes with [parts]. */
        fun of(card: MeCard, parts: Set<MeCards.Part>): CardFields {
            val c = card.cleaned()
            val work = MeCards.Part.WORK in parts
            return CardFields(
                name = if (MeCards.Part.NAME in parts) c.name else "",
                phones = if (MeCards.Part.PHONES in parts) c.phones else emptyList(),
                emails = if (MeCards.Part.EMAILS in parts) c.emails else emptyList(),
                company = if (work) c.company else "",
                title = if (work) c.title else "",
                websites = if (MeCards.Part.WEBSITES in parts) c.websites else emptyList(),
                profiles = if (MeCards.Part.PROFILES in parts) {
                    c.profiles.filter { it.url.isNotEmpty() }.map { CardProfile(it.service.label, it.url) }
                } else {
                    emptyList()
                },
                address = if (MeCards.Part.ADDRESS in parts) c.address else "",
            ).canonical()
        }
    }
}

/**
 * A card signed by its owner's key: [cardId] stays the same for the life of the card, [version] grows with every
 * change, [publicKey] is the raw Ed25519 key (base64url) and [signature] covers all of them with the fields.
 */
data class SignedCard(
    val cardId: String,
    val version: Long,
    val publicKey: String,
    val fields: CardFields,
    val signature: String,
) {
    /** The short fingerprint people can compare ("3F2A 9C1B 0D7E 44A2"). */
    val fingerprint: String get() = SignedCards.fingerprint(publicKey)
}

/** What a received vCard's Parley signature says. */
sealed interface CardCheck {
    /** Signed by [card]'s key, and nothing changed since. */
    data class Signed(val card: SignedCard) : CardCheck

    /** It carries a Parley signature that doesn't match its fields: changed after signing, or damaged. */
    data class Broken(val cardId: String?) : CardCheck
}

/**
 * I14: signed card updates. My card is shared as a plain vCard 3.0 with two extra properties, which other apps keep
 * or ignore like any `X-` property and plain QR scanners never show:
 *
 * ```
 * X-PARLEY-CARD:1;<card id>;<version>;<public key, base64url>
 * X-PARLEY-SIG:<Ed25519 signature, base64url>
 * ```
 *
 * The signature covers [payload]: a fixed header, the id, version and key, then the card's fields one per line in
 * the order they are written. Older Parley versions and other apps read the card as before.
 */
object SignedCards {
    const val PROP_CARD = "X-PARLEY-CARD"
    const val PROP_SIG = "X-PARLEY-SIG"
    private const val FORMAT = "1"
    private const val HEADER = "PARLEY-CARD 1"

    /** Caps for what is read from outside: a card with more lines or longer values isn't one Parley wrote. */
    private const val MAX_LINES = 400
    private const val MAX_VALUE = 2_000
    private const val MAX_CARDS = 50

    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()

    fun encodeKey(raw: ByteArray): String = b64.encodeToString(raw)

    fun fingerprint(publicKey: String): String = runCatching { Ed25519.fingerprint(unb64.decode(publicKey)) }.getOrDefault("")

    /** A new random card id (128 bits, base64url). */
    fun newCardId(random: java.util.Random = java.security.SecureRandom()): String = ByteArray(16).also { random.nextBytes(it) }.let(b64::encodeToString)

    /** The bytes that are signed. Values are escaped so a newline in a field can't forge another line. */
    fun payload(cardId: String, version: Long, publicKey: String, fields: CardFields): ByteArray = buildString {
        val f = fields.canonical()
        append(HEADER).append('\n')
        line("id", cardId)
        line("v", version.toString())
        line("key", publicKey)
        line("n", f.name)
        f.phones.forEach { line("tel", it) }
        f.emails.forEach { line("email", it) }
        line("org", f.company)
        line("title", f.title)
        f.websites.forEach { line("url", it) }
        f.profiles.forEach { line("profile", it.label + "\t" + it.url) }
        line("adr", f.address)
    }.toByteArray(Charsets.UTF_8)

    private fun StringBuilder.line(k: String, v: String) {
        append(k).append(':').append(v.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")).append('\n')
    }

    /** Signs [fields] as version [version] of card [cardId] with the 32-byte Ed25519 [secret]. */
    fun sign(fields: CardFields, cardId: String, version: Long, secret: ByteArray): SignedCard {
        val pub = encodeKey(Ed25519.publicKey(secret))
        val f = fields.canonical()
        val sig = Ed25519.sign(secret, payload(cardId, version, pub, f))
        return SignedCard(cardId, version, pub, f, b64.encodeToString(sig))
    }

    fun verify(card: SignedCard): Boolean = runCatching {
        Ed25519.verify(unb64.decode(card.publicKey), payload(card.cardId, card.version, card.publicKey, card.fields), unb64.decode(card.signature))
    }.getOrDefault(false)

    /** The two properties for [card], as vCard lines. */
    fun properties(card: SignedCard): String =
        "$PROP_CARD:$FORMAT;${card.cardId};${card.version};${card.publicKey}\r\n$PROP_SIG:${card.signature}\r\n"

    /** [vcard] (one card, as [MeCards.vcard] writes it) with [card]'s properties added before its end. */
    fun attach(vcard: String, card: SignedCard): String {
        val end = vcard.lastIndexOf("END:VCARD")
        return if (end < 0) vcard else vcard.substring(0, end) + properties(card) + vcard.substring(end)
    }

    /** Whether [text] may hold a signed card (cheap; [check] does the real work). */
    fun mayHold(text: String?): Boolean = text != null && text.contains(PROP_CARD, ignoreCase = true)

    /**
     * Every card in [text] (a vCard file, a QR code, pasted text) that carries a Parley signature, checked. Cards
     * without one are left out: they are ordinary vCards.
     */
    fun check(text: String): List<CardCheck> {
        if (!mayHold(text)) return emptyList()
        return cards(unfold(text)).take(MAX_CARDS).mapNotNull(::checkOne)
    }

    private fun unfold(text: String): List<String> {
        val out = ArrayList<String>()
        for (raw in text.split("\r\n", "\n", "\r")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) out[out.size - 1] = out.last() + raw.substring(1) else out += raw
        }
        return out
    }

    private fun cards(lines: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        var cur: ArrayList<String>? = null
        for (l in lines) {
            val u = l.trim().uppercase()
            when {
                u == "BEGIN:VCARD" -> cur = ArrayList()
                u == "END:VCARD" -> { cur?.let { out += it }; cur = null }
                cur != null && cur.size < MAX_LINES -> cur += l
            }
        }
        return out
    }

    /** A property line: (group, name, value); parameters are dropped (Parley's own lines only use TYPE). */
    private data class Prop(val group: String?, val name: String, val value: String)

    private fun prop(line: String): Prop? {
        val colon = line.indexOf(':').takeIf { it > 0 } ?: return null
        val head = line.substring(0, colon)
        val nameWithGroup = head.substringBefore(';')
        val dot = nameWithGroup.indexOf('.')
        val group = if (dot > 0) nameWithGroup.substring(0, dot).lowercase() else null
        val name = (if (dot > 0) nameWithGroup.substring(dot + 1) else nameWithGroup).uppercase()
        return Prop(group, name, line.substring(colon + 1).take(MAX_VALUE))
    }

    @Suppress("CyclomaticComplexMethod") // One branch per property Parley writes.
    private fun checkOne(lines: List<String>): CardCheck? {
        val props = lines.mapNotNull(::prop)
        val head = props.firstOrNull { it.name == PROP_CARD } ?: return null
        val parts = head.value.trim().split(';')
        val cardId = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
        val version = parts.getOrNull(2)?.toLongOrNull()
        val key = parts.getOrNull(3)?.trim()
        val sig = props.firstOrNull { it.name == PROP_SIG }?.value?.trim()
        val wellFormed = parts.firstOrNull() == FORMAT && (version ?: -1) >= 0 && cardId != null
        if (!wellFormed || key.isNullOrEmpty() || sig.isNullOrEmpty()) return CardCheck.Broken(cardId)
        var given = ""
        var family = ""
        var fn = ""
        val phones = ArrayList<String>()
        val emails = ArrayList<String>()
        var company = ""
        var title = ""
        val urls = ArrayList<Pair<String?, String>>()
        val labels = HashMap<String, String>()
        var address = ""
        for (p in props) {
            when (p.name) {
                "N" -> splitStructured(p.value).let { family = it.getOrNull(0).orEmpty(); given = it.getOrNull(1).orEmpty() }
                "FN" -> fn = unescape(p.value)
                "TEL" -> phones += unescape(p.value)
                "EMAIL" -> emails += unescape(p.value)
                "ORG" -> company = unescape(p.value)
                "TITLE" -> title = unescape(p.value)
                "URL" -> urls += p.group to unescape(p.value)
                "X-ABLABEL" -> if (p.group != null) labels[p.group] = unescape(p.value)
                "ADR" -> address = splitStructured(p.value).getOrNull(2).orEmpty()
            }
        }
        // The name is the given and family names joined back (the card's FN may hold the company instead).
        val name = MeCards.joinName(given, family).ifEmpty { if (fn == company) "" else fn }
        val websites = urls.filter { it.first == null || labels[it.first] == null }.map { it.second }
        val profiles = urls.mapNotNull { (g, u) -> g?.let { labels[it] }?.let { CardProfile(it, u) } }
        val card = SignedCard(cardId!!, version!!, key, CardFields(name, phones, emails, company, title, websites, profiles, address).canonical(), sig)
        return if (verify(card)) CardCheck.Signed(card) else CardCheck.Broken(cardId)
    }

    /** A structured value split at unescaped semicolons, each part unescaped. */
    private fun splitStructured(v: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                sb.append(c).append(v[i + 1])
                i += 2
                continue
            }
            if (c == ';') { out += unescape(sb.toString()); sb.clear() } else sb.append(c)
            i++
        }
        out += unescape(sb.toString())
        return out
    }

    /** vCard 3.0 text unescaping, the reverse of [MeCards.esc]. */
    fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
