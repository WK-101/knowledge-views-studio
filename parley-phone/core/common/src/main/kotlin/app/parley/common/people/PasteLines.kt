package app.parley.common.people

import app.parley.common.NumberText
import app.parley.common.people.PasteParser.Field
import app.parley.common.people.PasteParser.Kind
import app.parley.common.people.PasteParser.Label
import app.parley.common.people.PasteParser.LineKind
import app.parley.common.people.PasteWords.Tag
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/**
 * Reads pasted text line by line into [Piece]s: each line is tidied (invisible marks, emoji, "jane [at] acme [dot]
 * com"), cut at separators ("|", "•", wide gaps, " – "), and each part gives its labelled field ("Company: …"), its
 * profiles, emails, links, phone numbers (with the label in front: "M:", "Tel.", "Fax") and a birthday after its
 * word; what is left is classified by [remainder] (name, organisation, title, address line or other).
 */
internal object PasteLines {
    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }
    private const val MAX_LINES = 300
    private const val MIN_NATIONAL = 6

    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF\\u00AD]")
    private val SPACES = Regex("[\\u00A0\\u2007\\u202F\\u3000]")
    private val OBFUSCATED_AT = Regex("(?i)([a-z0-9])\\s*[\\[({]\\s*at\\s*[\\])}]\\s*([a-z0-9])")
    private val OBFUSCATED_DOT = Regex("(?i)([a-z0-9])\\s*[\\[({]\\s*dot\\s*[\\])}]\\s*([a-z0-9])")
    private val SEPARATORS = Regex("\\s*[|•◦‖¦▪]\\s*|\\s+[·∙]\\s+|\\s{3,}|\\t+|\\s[–—-]\\s(?!\\d)")
    private val LABEL = Regex("^([\\p{L}][\\p{L} .'’/()-]{0,30}?)\\s*[:：]\\s*(.*)$")
    private val EMAIL = Regex("(?i)(?:mailto:)?([a-z0-9._%+\\-]+@[a-z0-9\\-]+(?:\\.[a-z0-9\\-]+)*\\.[a-z]{2,})")
    private val URL = Regex(
        "(?i)\\bgeo:[^\\s<>\"']+|(?:\\bhttps?://|\\bwww\\.)[^\\s<>\"']+|(?<![\\w@.\\-])(?:[a-z0-9](?:[a-z0-9\\-]*[a-z0-9])?\\.)+" +
            "(?:com|org|net|edu|gov|io|co|ai|app|dev|me|info|biz|eu|uk|de|fr|es|it|nl|be|ch|at|pk|in|us|ca|au|nz|ie|se|no|dk|fi|pl|pt|br|jp|ae|sa|" +
            "tv|xyz|tech|studio|design|agency|online|site|shop|store|blog|page|link|bio|social|cloud|global|art)(?![\\w\\-])(?:/[^\\s<>\"']*)?",
    )
    private val HANDLE_AFTER = Regex("^([A-Za-z][A-Za-z /()]{0,20}?)\\s*[-–:]?\\s*@([\\w.\\-]+(?:@[\\w\\-]+(?:\\.[\\w\\-]+)+)?)$")
    private val HANDLE_BEFORE = Regex(
        "^@([\\w.\\-]+(?:@[\\w\\-]+(?:\\.[\\w\\-]+)+)?)\\s*(?:on\\s+|\\(\\s*)([A-Za-z][A-Za-z ]{0,20}?)\\s*\\)?$",
        RegexOption.IGNORE_CASE,
    )
    private val BIRTHDAY_WORD = Regex(
        "(?i)^(?:born(?: on)?|b\\.|bday|dob|birthday|geboren(?: am)?|geb\\.|n[ée]e? le|nacid[oa] el|nat[oa] il|cumpleaños|anniversaire)\\s*[:\\-]?\\s*(.+)$",
    )
    private val AFTER_LABEL = Regex("^\\s*\\(\\s*([\\p{L}.]{1,12})\\s*\\)")
    private val LABEL_TAIL = Regex("[,;]|\\s{2,}")

    /** Emoji that say what follows: read as the label they stand for. */
    private val MARKERS = mapOf(
        0x1F4F1 to "Mobile: ", 0x1F4F2 to "Mobile: ", 0x260E to "Tel: ", 0x1F4DE to "Tel: ", 0x1F4E0 to "Fax: ", 0x1F4CD to "Address: ",
        0x1F3E0 to "Address: ", 0x1F3E2 to "Address: ", 0x1F5FA to "Address: ", 0x1F382 to "Birthday: ",
    )

    private class Out(val pieces: List<Piece>, val addressOpen: Boolean)

    fun read(src: String, region: String?, hinted: Hinted): List<Piece> {
        val out = ArrayList<Piece>()
        var addressOpen = false
        src.replace("\r\n", "\n").replace('\r', '\n').split('\n').take(MAX_LINES).forEachIndexed { i, raw ->
            val line = clean(raw)
            if (line.isBlank()) {
                addressOpen = false
                return@forEachIndexed
            }
            if (PasteWords.isJunk(line)) return@forEachIndexed
            for (seg in SEPARATORS.split(line).map { it.trim() }.filter { it.isNotEmpty() }) {
                val r = segment(i, seg, region, hinted, addressOpen)
                out += r.pieces
                addressOpen = r.addressOpen
            }
        }
        return out
    }

    private fun clean(line: String): String {
        val s = SPACES.replace(INVISIBLE.replace(line, ""), " ")
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val marker = MARKERS[cp]
            when {
                marker != null -> sb.append(" | ").append(marker)
                isEmoji(cp) -> sb.append(' ')
                else -> sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return OBFUSCATED_DOT.replace(OBFUSCATED_AT.replace(sb, "$1@$2"), "$1.$2").trim()
    }

    private fun isEmoji(cp: Int): Boolean = cp in 0xFE00..0xFE0F || cp == 0x20E3 || cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF ||
        cp in 0x2B00..0x2BFF || cp in 0xE0000..0xE007F || (cp >= 0x2190 && Character.getType(cp) == Character.OTHER_SYMBOL.toInt())

    private fun value(line: Int, f: Field) = Piece(line, Role.VALUE, f.value, f)

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One rule per field a line can hold, in order.
    private fun segment(line: Int, seg: String, region: String?, hinted: Hinted, addressOpen: Boolean): Out {
        var text = seg
        var forced: Label? = null
        var open = addressOpen
        LABEL.matchEntire(seg)?.let { m ->
            val tag = PasteWords.tag(m.groupValues[1]) ?: return@let
            val rest = m.groupValues[2].trim()
            val plain = rest.isNotEmpty() && rest.none { it.isDigit() || it == '@' } && URL.find(rest) == null
            open = false
            when (tag) {
                Tag.Name -> if (plain) return Out(listOf(Piece(line, Role.NAME, rest, name = PasteNames.parse(rest, loose = true), labeled = true)), false)
                Tag.Org -> if (plain) return Out(listOf(Piece(line, Role.ORG, rest, labeled = true)), false)
                Tag.Title -> if (plain) return Out(listOf(Piece(line, Role.TITLE, rest, labeled = true)), false)
                Tag.Address -> {
                    if (rest.isEmpty()) return Out(emptyList(), true)
                    if (rest.none { it == '@' }) return Out(listOf(Piece(line, Role.ADDRESS, rest, addr = AddrPart.HINT, labeled = true)), false)
                }
                Tag.Birthday -> PasteDates.parse(rest, region)?.let { return Out(listOf(value(line, Field(Kind.BIRTHDAY, it))), false) }
                Tag.Note -> if (rest.isNotEmpty()) return Out(listOf(Piece(line, Role.OTHER, rest, labeled = true)), false)
                is Tag.Social -> social(rest, tag.service)?.let { return Out(listOf(value(line, it)), false) }
                is Tag.Phone -> forced = tag.label
                Tag.Email, Tag.Web -> Unit
            }
            text = rest
        }
        if (text.isEmpty()) return Out(emptyList(), open)
        handle(text)?.let { return Out(listOf(value(line, it)), false) }

        val out = ArrayList<Piece>()
        val mask = StringBuilder(text)
        fun blank(from: Int, until: Int) {
            for (k in from until until) if (k in mask.indices) mask.setCharAt(k, ' ')
        }
        for (m in EMAIL.findAll(text)) {
            val g = m.groups[1] ?: continue
            // "@ana@mastodon.social" is a Mastodon profile, not an email address.
            val mastodon = g.range.first > 0 && text[g.range.first - 1] == '@'
            val f = if (mastodon) social(text.substring(g.range.first - 1, g.range.last + 1), ProfileService.MASTODON) else Field(Kind.EMAIL, g.value)
            if (f != null) out += value(line, f)
            blank(if (mastodon) g.range.first - 1 else m.range.first, m.range.last + 1)
        }
        for (m in URL.findAll(mask.toString())) {
            val raw = trimLink(m.value)
            if (raw.length < 4) continue
            out += value(line, link(raw))
            blank(m.range.first, m.range.first + raw.length)
        }
        BIRTHDAY_WORD.matchEntire(mask.toString().trim())?.let { m ->
            PasteDates.parse(m.groupValues[1], region)?.let { return Out(out + value(line, Field(Kind.BIRTHDAY, it)), false) }
        }
        out += phones(line, mask, region, forced, hinted)
        val rest = mask.toString().trim()
        if (open && out.isEmpty() && rest.isNotEmpty()) return Out(listOf(Piece(line, Role.ADDRESS, rest, addr = AddrPart.HINT)), true)
        out += remainder(line, rest, hinted)
        return Out(out, open && out.isEmpty())
    }

    /** "Instagram @ana.lima", "Twitter - @ana", "@ana on Instagram", "@ana (LinkedIn)". */
    private fun handle(text: String): Field? {
        HANDLE_AFTER.matchEntire(text.trim())?.let { m ->
            val s = (PasteWords.tag(m.groupValues[1]) as? Tag.Social)?.service ?: return@let
            return social("@" + m.groupValues[2], s)
        }
        HANDLE_BEFORE.matchEntire(text.trim())?.let { m ->
            val s = (PasteWords.tag(m.groupValues[2]) as? Tag.Social)?.service ?: return@let
            return social("@" + m.groupValues[1], s)
        }
        return null
    }

    private fun social(raw: String, service: ProfileService): Field? {
        val h = SocialProfiles.normalize(service, raw)
        if (h.isEmpty() || h.any { it.isWhitespace() } || SocialProfiles.problem(service, h) != null) return null
        val p = Profile(service, h)
        return Field(Kind.PROFILE, p.url, profile = p)
    }

    /** Sentence punctuation after a link isn't part of it; a closing bracket is, when the link opened one. */
    private fun trimLink(link: String): String {
        var l = link.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"')
        while (l.endsWith(")") && l.count { it == ')' } > l.count { it == '(' }) l = l.dropLast(1).trimEnd('.', ',', ';', ':', '!', '?')
        return l
    }

    /** A map link, a profile address, or a website. */
    private fun link(raw: String): Field {
        val url = if (raw.contains("://") || raw.startsWith("geo:", ignoreCase = true)) raw else "https://$raw"
        MapLinks.parseLink(url)?.takeIf { it.service != MapLinks.Service.OTHER }?.let { return Field(Kind.MAP_LINK, raw, place = it) }
        SocialProfiles.fromUrl(url)?.let { return Field(Kind.PROFILE, it.url, profile = it) }
        return Field(Kind.WEBSITE, raw)
    }

    /**
     * Phone numbers in [mask] (emails and links already blanked), read in [region]. A number counts when it is valid
     * there, or when the text marks it as one (a label, a leading "+", the classifier). Each found number and its
     * label are blanked in [mask].
     */
    // A single scan over the line, as phone numbers can sit anywhere in it.
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
    private fun phones(line: Int, mask: StringBuilder, region: String?, forced: Label?, hinted: Hinted): List<Piece> {
        val text = mask.toString()
        val out = ArrayList<Piece>()
        val hint = region?.uppercase(Locale.ROOT) ?: "ZZ"
        val matches = runCatching { util.findNumbers(text, hint, PhoneNumberUtil.Leniency.POSSIBLE, Long.MAX_VALUE).toList() }.getOrDefault(emptyList())
        var boundary = 0
        for (m in matches) {
            if (m.start() < boundary) continue
            val before = text.substring(boundary, m.start())
            val tail = before.split(LABEL_TAIL).last()
            val labelBefore = PasteWords.phoneLabel(tail)?.first
            val afterMatch = AFTER_LABEL.find(text.substring(m.end()))
            val labelAfter = afterMatch?.let { PasteWords.phoneLabel(it.groupValues[1])?.first }
            val number = m.number()
            val national = util.getNationalSignificantNumber(number)
            val labelled = forced != null || labelBefore != null || labelAfter != null
            val explicit = labelled || m.rawString().trimStart().startsWith("+") || hinted.isPhone(m.rawString())
            val counts = national.length >= MIN_NATIONAL && (util.isValidNumber(number) || explicit)
            if (!counts) continue
            val e164 = util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
            val ext = number.extension?.takeIf { number.hasExtension() && it.isNotEmpty() }
            val label = listOfNotNull(labelBefore, labelAfter, forced).firstOrNull { it != Label.NONE } ?: Label.NONE
            val kind = when (util.getNumberType(number)) {
                PhoneNumberUtil.PhoneNumberType.MOBILE -> LineKind.MOBILE
                PhoneNumberUtil.PhoneNumberType.FIXED_LINE -> LineKind.FIXED
                PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE -> LineKind.EITHER
                PhoneNumberUtil.PhoneNumberType.TOLL_FREE -> LineKind.TOLL_FREE
                else -> LineKind.UNKNOWN
            }
            out += value(line, Field(Kind.PHONE, if (ext != null) "$e164,$ext" else e164, label, extension = ext, line = kind))
            if (labelBefore != null) for (k in m.start() - tail.length until m.start()) mask.setCharAt(k, ' ')
            for (k in m.start() until m.end()) mask.setCharAt(k, ' ')
            if (afterMatch != null && labelAfter != null) for (k in m.end() until m.end() + afterMatch.range.last + 1) mask.setCharAt(k, ' ')
            boundary = m.end()
        }
        // What the system's classifier saw as a number that the rules didn't read (a vanity number, an odd format).
        if (out.isEmpty()) {
            for (h in hinted.phones) {
                if (!text.contains(h)) continue
                val e164 = NumberText.toE164(h, region) ?: continue
                out += value(line, Field(Kind.PHONE, e164, forced ?: Label.NONE))
                val at = mask.indexOf(h)
                if (at >= 0) for (k in at until at + h.length) mask.setCharAt(k, ' ')
            }
        }
        return out
    }

    // ---------------------------------------------------------------- what is left of a line

    private val CHUNKS = Regex("\\s{2,}")
    private val LABEL_ONLY: Set<String> = PasteWords.PHONE_LABELS.keys + setOf(
        "e", "email", "e mail", "mail", "w", "web", "website", "www", "url", "a", "address", "adresse", "x", "ext", "extension", "or", "and",
        "oder", "ou", "o", "y", "et", "und",
    )

    private fun tidy(s: String) = s.trim().trim(',', ';', ':', '|', '-', '–', '—', '/', '·', '•', '<', '>', '"', '“', '”').trim()

    private fun labelOnly(s: String): Boolean {
        val w = PasteWords.words(s)
        return w.isEmpty() || w.joinToString(" ") in LABEL_ONLY || w.all { it in LABEL_ONLY }
    }

    /** The rest of a line, cut at the gaps the found values left, each chunk classified. */
    fun remainder(line: Int, rest: String, hinted: Hinted): List<Piece> =
        rest.split(CHUNKS).map(::tidy).filter { it.isNotEmpty() && !labelOnly(it) && !PasteWords.isJunk(it) }.flatMap { classify(line, it, hinted) }

    private fun classify(line: Int, t: String, hinted: Hinted): List<Piece> {
        if (hinted.isAddress(t)) return listOf(Piece(line, Role.ADDRESS, t, addr = AddrPart.HINT))
        val part = PasteAddress.part(t)
        if (!t.contains(',')) return one(line, t, part)
        val parts = t.split(',').map(::tidy).filter { it.isNotEmpty() }
        // "Acme Ltd, 12 High Street, London": the organisation, then its address.
        val after = parts.drop(1).joinToString(", ")
        val afterPart = PasteAddress.part(after)
        val addressAfter = afterPart == AddrPart.STREET || afterPart == AddrPart.POSTCODE
        if (parts.size >= 2 && addressAfter && PasteWords.isOrg(parts[0])) {
            return listOf(Piece(line, Role.ORG, parts[0]), Piece(line, Role.ADDRESS, after, addr = afterPart))
        }
        if (part == AddrPart.STREET || part == AddrPart.POSTCODE) return listOf(Piece(line, Role.ADDRESS, t, addr = part))
        // "Jane Doe, PhD", "DOE, Jane".
        PasteNames.parse(t)?.let { n -> return listOf(Piece(line, Role.NAME, n.display, name = n)) }
        if (parts.size in 2..MAX_PARTS) {
            val each = parts.flatMap { one(line, it, PasteAddress.part(it)) }
            if (each.any { it.role != Role.OTHER }) return each
        }
        return listOf(Piece(line, Role.OTHER, t, addr = part))
    }

    private const val MAX_PARTS = 4

    private fun one(line: Int, t: String, part: AddrPart): List<Piece> {
        if (part == AddrPart.STREET || part == AddrPart.POSTCODE) return listOf(Piece(line, Role.ADDRESS, t, addr = part))
        PasteWords.titleAt(t)?.let { (title, org) -> return listOf(Piece(line, Role.TITLE, title), Piece(line, Role.ORG, org)) }
        val org = PasteWords.isOrg(t)
        val title = PasteWords.isTitle(t)
        if (org && (!title || PasteWords.isLegalOrg(t))) return listOf(Piece(line, Role.ORG, t))
        val name = PasteNames.parse(t)
        // "Prof. Jane Doe": the title word is the name's prefix.
        val prefixOnly = name != null && name.prefix.isNotEmpty() && !PasteWords.isTitle(listOf(name.given, name.middle, name.family).joinToString(" "))
        if (title && !prefixOnly) {
            return listOf(Piece(line, Role.TITLE, t))
        }
        if (name != null) return listOf(Piece(line, Role.NAME, t, name = name, addr = part))
        return listOf(Piece(line, Role.OTHER, t, addr = part))
    }
}
