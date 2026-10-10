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

    /** Only what [parts] share; everything else blank, as [MeCards.vcard] leaves it out. */
    fun restrictedTo(parts: Set<MeCards.Part>): CardFields {
        val work = MeCards.Part.WORK in parts
        return CardFields(
            name = if (MeCards.Part.NAME in parts) name else "",
            phones = if (MeCards.Part.PHONES in parts) phones else emptyList(),
            emails = if (MeCards.Part.EMAILS in parts) emails else emptyList(),
            company = if (work) company else "",
            title = if (work) title else "",
            websites = if (MeCards.Part.WEBSITES in parts) websites else emptyList(),
            profiles = if (MeCards.Part.PROFILES in parts) profiles else emptyList(),
            address = if (MeCards.Part.ADDRESS in parts) address else "",
        )
    }

    companion object {
        private val WS = Regex("\\s+")

        /** Every part: what a card shared before parts were signed, or a link that has seen them all. */
        val ALL_PARTS: Set<MeCards.Part> = MeCards.signable

        /**
         * [mine] (what [mineParts] said) with [newer]'s values for the parts it shares ([newerParts]): what the user has
         * now seen of a card when a later share includes only some parts.
         */
        fun merge(mine: CardFields, mineParts: Set<MeCards.Part>, newer: CardFields, newerParts: Set<MeCards.Part>): CardFields {
            val keep = mine.restrictedTo(mineParts - newerParts)
            val take = newer.restrictedTo(newerParts)
            fun s(a: String, b: String) = b.ifEmpty { a }
            return CardFields(
                name = s(keep.name, take.name), phones = keep.phones + take.phones, emails = keep.emails + take.emails,
                company = s(keep.company, take.company), title = s(keep.title, take.title), websites = keep.websites + take.websites,
                profiles = keep.profiles + take.profiles, address = s(keep.address, take.address),
            ).canonical()
        }

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
 * change, [publicKey] is the raw Ed25519 key (base64url) and [signature] covers all of them with the fields and
 * [parts], the parts this share includes. A part left out says nothing about that part: it isn't a removal.
 */
data class SignedCard(
    val cardId: String,
    val version: Long,
    val publicKey: String,
    val fields: CardFields,
    val signature: String,
    val parts: Set<MeCards.Part> = CardFields.ALL_PARTS,
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
 * X-PARLEY-CARD:2;<card id>;<version>;<public key, base64url>;<parts, e.g. NAME.PHONES>
 * X-PARLEY-SIG:<Ed25519 signature, base64url>
 * ```
 *
 * The signature covers [payload]: a fixed header, the id, version, key and parts, then the card's fields one per
 * line in the order they are written. Older Parley versions and other apps read the card as before.
 *
 * The checker reads the card the strict way: a signed card must be exactly what [MeCards.vcard] writes (each
 * property once where it writes it once, no other properties or parameters, canonical escaping, within the size
 * caps). Anything else is reported as [CardCheck.Broken], so what a contacts importer saves from a card that shows
 * as signed is what the signature covers (M1).
 */
object SignedCards {
    const val PROP_CARD = "X-PARLEY-CARD"
    const val PROP_SIG = "X-PARLEY-SIG"
    private const val FORMAT = "2"
    private const val HEADER = "PARLEY-CARD 2"
    private const val PARTS_SEPARATOR = "."

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

    /** [parts] as the card property carries them ("NAME.PHONES"), in a fixed order. */
    fun encodeParts(parts: Set<MeCards.Part>): String =
        MeCards.Part.entries.filter { it in parts && it in MeCards.signable }.joinToString(PARTS_SEPARATOR) { it.name }

    /** The parts a card property names; null when one isn't a part Parley knows (the card is then not trusted). */
    fun decodeParts(s: String): Set<MeCards.Part>? = if (s.isEmpty()) {
        emptySet()
    } else {
        s.split(PARTS_SEPARATOR).map { n -> MeCards.signable.firstOrNull { it.name == n } ?: return null }.toSet()
    }

    /**
     * The next version after [stored] (M6): it never repeats, also across phones. A restored backup can be behind
     * versions the old phone shared later, so a version is at least the current time in seconds.
     */
    fun nextVersion(stored: Long, nowMs: Long): Long = maxOf(stored + 1, nowMs / 1000)

    /** The bytes that are signed. Values are escaped so a newline (or tab) in a field can't forge another line. */
    fun payload(
        cardId: String, version: Long, publicKey: String, fields: CardFields, parts: Set<MeCards.Part> = CardFields.ALL_PARTS,
    ): ByteArray = buildString {
        val f = fields.canonical().restrictedTo(parts)
        append(HEADER).append('\n')
        line("id", cardId)
        line("v", version.toString())
        line("key", publicKey)
        line("parts", encodeParts(parts))
        line("n", f.name)
        f.phones.forEach { line("tel", it) }
        f.emails.forEach { line("email", it) }
        line("org", f.company)
        line("title", f.title)
        f.websites.forEach { line("url", it) }
        // Label and link escaped each, then joined by a bare tab: ("a\tb", "c") and ("a", "b\tc") differ (L4).
        f.profiles.forEach { append("profile:").append(esc(it.label)).append('\t').append(esc(it.url)).append('\n') }
        line("adr", f.address)
    }.toByteArray(Charsets.UTF_8)

    private fun StringBuilder.line(k: String, v: String) {
        append(k).append(':').append(esc(v)).append('\n')
    }

    private fun esc(v: String) = v.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    /** Signs [fields] (the [parts] shared) as version [version] of card [cardId] with the 32-byte Ed25519 [secret]. */
    fun sign(fields: CardFields, cardId: String, version: Long, secret: ByteArray, allParts: Set<MeCards.Part> = CardFields.ALL_PARTS): SignedCard {
        // Only the parts a signed card covers are named in it (a share with others goes out unsigned).
        val parts = allParts intersect MeCards.signable
        val pub = encodeKey(Ed25519.publicKey(secret))
        val f = fields.canonical().restrictedTo(parts)
        val sig = Ed25519.sign(secret, payload(cardId, version, pub, f, parts))
        return SignedCard(cardId, version, pub, f, b64.encodeToString(sig), parts)
    }

    fun verify(card: SignedCard): Boolean = runCatching {
        Ed25519.verify(unb64.decode(card.publicKey), payload(card.cardId, card.version, card.publicKey, card.fields, card.parts), unb64.decode(card.signature))
    }.getOrDefault(false)

    /** The two properties for [card], as vCard lines. */
    fun properties(card: SignedCard): String =
        "$PROP_CARD:$FORMAT;${card.cardId};${card.version};${card.publicKey};${encodeParts(card.parts)}\r\n$PROP_SIG:${card.signature}\r\n"

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

    /** How many vCards [text] holds (signed or not), so a note can say when some of them aren't signed. */
    fun count(text: String): Int = cards(unfold(text)).size

    private fun unfold(text: String): List<String> {
        val out = ArrayList<String>()
        for (raw in text.split("\r\n", "\n", "\r")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) out[out.size - 1] = out.last() + raw.substring(1) else out += raw
        }
        return out
    }

    /** One card's lines; [damaged] when it can't be read the way an importer would (too long, or a card inside it). */
    private class Block(val lines: List<String>, val damaged: Boolean)

    private fun cards(lines: List<String>): List<Block> {
        val out = ArrayList<Block>()
        var cur: ArrayList<String>? = null
        var damaged = false
        for (l in lines) {
            val u = l.trim().uppercase()
            when {
                u == "BEGIN:VCARD" -> {
                    // A card inside a card: importers read it in their own way, so the outer one can't be trusted.
                    if (cur != null) damaged = true else { cur = ArrayList(); damaged = false }
                }
                u == "END:VCARD" -> { cur?.let { out += Block(it, damaged) }; cur = null }
                cur == null -> Unit
                // Never cut a card short quietly: an importer would read the rest (M1).
                cur.size >= MAX_LINES -> damaged = true
                else -> cur += l
            }
        }
        return out
    }

    /** A property line: (group, name, parameters, value). */
    private data class Prop(val group: String?, val name: String, val params: List<String>, val value: String)

    private fun prop(line: String): Prop? {
        val colon = line.indexOf(':').takeIf { it > 0 } ?: return null
        val head = line.substring(0, colon).split(';')
        val nameWithGroup = head.first()
        val dot = nameWithGroup.indexOf('.')
        val group = if (dot > 0) nameWithGroup.substring(0, dot).lowercase() else null
        val name = (if (dot > 0) nameWithGroup.substring(dot + 1) else nameWithGroup).uppercase()
        return Prop(group, name, head.drop(1), line.substring(colon + 1))
    }

    /** Properties [MeCards.vcard] writes once at most. */
    private val SINGLE = setOf("VERSION", "N", "FN", "ORG", "TITLE", "ADR", PROP_CARD, PROP_SIG)

    /** Properties a signed card may have; an importer reads anything else (notes, phones in X-ANDROID-CUSTOM…). */
    private val ALLOWED = SINGLE + setOf("TEL", "EMAIL", "URL", "X-ABLABEL")

    /** Properties that may carry a TYPE (the only parameter Parley writes). */
    private val TYPED = setOf("TEL", "EMAIL", "URL", "ADR")
    private val TYPE_PARAM = Regex("(?i)TYPE=[A-Z0-9,-]{1,40}")

    /** Text properties: their value must be escaped exactly as [MeCards.esc] writes it. */
    private val TEXT = setOf("FN", "TEL", "EMAIL", "ORG", "TITLE", "URL", "X-ABLABEL")

    /** Whether [props] are a card [MeCards.vcard] could have written (besides the values, which the signature covers). */
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // One rule per way an importer could read more than the checker.
    private fun strict(lines: List<String>, props: List<Prop>): Boolean {
        if (lines.any { it.isNotBlank() && prop(it) == null }) return false
        if (props.size != lines.count { it.isNotBlank() }) return false
        val names = props.groupingBy { it.name }.eachCount()
        if (names.keys.any { it !in ALLOWED }) return false
        if (SINGLE.any { (names[it] ?: 0) > 1 }) return false
        if (listOf("VERSION", "N", "FN", PROP_CARD, PROP_SIG).any { names[it] != 1 }) return false
        for (p in props) {
            if (p.value.length > MAX_VALUE) return false
            if (p.params.isNotEmpty() && (p.name !in TYPED || p.params.any { !TYPE_PARAM.matches(it) })) return false
            if (p.group != null && p.name != "URL" && p.name != "X-ABLABEL") return false
            if (p.name in TEXT && MeCards.esc(unescape(p.value)) != p.value) return false
        }
        if (props.first { it.name == "VERSION" }.value.trim() != "3.0") return false
        // A label belongs to one link, in the same group.
        val grouped = props.filter { it.group != null }.groupBy { it.group }
        if (grouped.values.any { g -> g.count { it.name == "URL" } != 1 || g.count { it.name == "X-ABLABEL" } > 1 }) return false
        if (props.any { it.name == "X-ABLABEL" && it.group == null }) return false
        // Structured values: only the parts Parley writes (family and given name; the street), escaped its way.
        val n = splitStructured(props.first { it.name == "N" }.value)
        if (props.first { it.name == "N" }.value != "${MeCards.esc(n.getOrNull(0).orEmpty())};${MeCards.esc(n.getOrNull(1).orEmpty())};;;") return false
        props.firstOrNull { it.name == "ADR" }?.let { a ->
            val street = splitStructured(a.value).getOrNull(2).orEmpty()
            if (a.value != ";;${MeCards.esc(street)};;;;") return false
        }
        return true
    }

    @Suppress("CyclomaticComplexMethod") // One branch per property Parley writes.
    private fun checkOne(block: Block): CardCheck? {
        val lines = block.lines
        val props = lines.mapNotNull(::prop)
        val head = props.firstOrNull { it.name == PROP_CARD } ?: return null
        val parts = head.value.trim().split(';')
        val cardId = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
        val version = parts.getOrNull(2)?.toLongOrNull()
        val key = parts.getOrNull(3)?.trim()
        val shared = parts.getOrNull(4)?.trim()?.let(::decodeParts)
        val sig = props.firstOrNull { it.name == PROP_SIG }?.value?.trim()
        val wellFormed = parts.size == 5 && parts.firstOrNull() == FORMAT && (version ?: -1) >= 0 && cardId != null && shared != null
        if (!wellFormed || key.isNullOrEmpty() || sig.isNullOrEmpty()) return CardCheck.Broken(cardId)
        if (block.damaged || !strict(lines, props)) return CardCheck.Broken(cardId)
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
        val name = MeCards.joinName(given, family)
        // FN is the name, or the company when there is none: an importer shows it, so it must say what N says.
        val collapsed = fn.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        val fnSaysCompany = name.isEmpty() && (fn.isEmpty() || fn == company)
        if (collapsed != name && !fnSaysCompany) return CardCheck.Broken(cardId)
        val websites = urls.filter { it.first == null || labels[it.first] == null }.map { it.second }
        val profiles = urls.mapNotNull { (g, u) -> g?.let { labels[it] }?.let { CardProfile(it, u) } }
        val fields = CardFields(name, phones, emails, company, title, websites, profiles, address).canonical()
        // Nothing outside the parts it says it shares.
        if (fields.restrictedTo(shared!!) != fields) return CardCheck.Broken(cardId)
        val card = SignedCard(cardId!!, version!!, key, fields, sig, shared)
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
