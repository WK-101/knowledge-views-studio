package app.parley.common.people

/**
 * "Paste details": reads an email signature, a business profile, a web "Contact us" block, an event badge or any
 * other pasted or shared text into a contact's fields (name, organisation, job title, phones, emails, addresses with
 * their map links, websites, profiles, a birthday when the text says so, and a note for what is left), entirely on
 * the phone. The editor shows the result field by field to be ticked before anything fills the form; nothing here
 * saves or stores the text.
 *
 * The rules are deterministic and offline: libphonenumber reads the numbers with the SIM's country as the hint
 * ([parse]'s `region`), [MapLinks] the map links, [SocialProfiles] the profile links. The system's on-device text
 * classifier can add [Hint]s (where it saw an address, a phone number, an email or a link), which the rules use
 * where theirs say nothing, never to override them.
 */
object PasteParser {
    /** Longer texts are cut: a signature or a business card is never that long. */
    const val MAX_TEXT = 10_000

    enum class Kind { NAME, ORGANISATION, JOB_TITLE, PHONE, EMAIL, ADDRESS, MAP_LINK, WEBSITE, PROFILE, BIRTHDAY, NOTE }

    /** The type a phone, email, address or website gets in the contact (NONE: the kind's usual one). */
    enum class Label { NONE, MOBILE, WORK, HOME, MAIN, FAX, OTHER }

    data class Name(
        val prefix: String = "",
        val given: String = "",
        val middle: String = "",
        val family: String = "",
        val suffix: String = "",
    ) {
        val display: String
            get() = listOf(prefix, given, middle, family).filter { it.isNotEmpty() }.joinToString(" ") +
                (if (suffix.isNotEmpty()) ", $suffix" else "")

        /** Two words or more ("Ana Lima"), not a lone first name. */
        val full: Boolean get() = given.isNotEmpty() && family.isNotEmpty()
    }

    data class Address(
        /** Street lines, one per line as written. */
        val street: String = "",
        val city: String = "",
        val region: String = "",
        val postcode: String = "",
        val country: String = "",
    ) {
        val display: String
            get() = listOf(
                street.replace('\n', ',').replace(",", ", ").replace(Regex(" {2,}"), " ").trim(),
                listOf(postcode, city).filter { it.isNotEmpty() }.joinToString(" "),
                region, country,
            ).filter { it.isNotBlank() }.joinToString(", ")
    }

    /**
     * One value found in the text. [value] is what the contact keeps: a phone in international form (an extension
     * after a pause, "+441234567890,123"), an email, the address as one line, a link, a birthday as `yyyy-MM-dd` or
     * `--MM-dd`. [suggested]: ticked in the preview at first.
     */
    data class Field(
        val kind: Kind,
        val value: String,
        val label: Label = Label.NONE,
        val name: Name? = null,
        val address: Address? = null,
        val profile: Profile? = null,
        val place: MapLinks.Place? = null,
        /** A phone's extension ("123"), also kept in [value] after a pause. */
        val extension: String? = null,
        val suggested: Boolean = true,
        /** What libphonenumber says about a phone number (decides its type when the text doesn't). */
        val line: LineKind = LineKind.UNKNOWN,
    )

    enum class LineKind { UNKNOWN, MOBILE, FIXED, EITHER, TOLL_FREE }

    /** One person (or one organisation) found in the text, its fields in the editor's order. */
    data class Card(val fields: List<Field>) {
        fun all(kind: Kind): List<Field> = fields.filter { it.kind == kind }
        fun first(kind: Kind): Field? = fields.firstOrNull { it.kind == kind }

        /** What the card is called among several: the name, else the organisation, else its first value. */
        val title: String get() = (first(Kind.NAME) ?: first(Kind.ORGANISATION) ?: fields.firstOrNull())?.value.orEmpty()
    }

    enum class HintType { PHONE, EMAIL, ADDRESS, URL }

    /** Where the system's text classifier found something in the text ([start] inclusive, [end] exclusive). */
    data class Hint(val start: Int, val end: Int, val type: HintType)

    /**
     * The people in [text], in order (usually one). [region] is the two-letter country national numbers are read
     * in (the SIM's, then the network's); null reads only numbers written with their country code.
     */
    fun parse(text: String, region: String?, hints: List<Hint> = emptyList()): List<Card> {
        val src = text.take(MAX_TEXT)
        if (src.isBlank()) return emptyList()
        val hinted = Hinted.of(src, hints)
        val pieces = PasteAddress.group(PasteLines.read(src, region?.takeIf { it.length == 2 }, hinted))
        return people(pieces).map(PasteCards::build).filter { it.fields.isNotEmpty() }
    }

    /**
     * Whether shared text is worth offering as a new contact: it names someone (or an organisation, an email, an
     * address) together with something else, so it is more than a lone number or link.
     */
    fun worthOffering(text: String, region: String?): Boolean {
        if (text.trim().length < MIN_OFFER) return false
        val card = parse(text, region).firstOrNull() ?: return false
        val kinds = card.fields.map { it.kind }.filter { it != Kind.NOTE }.toSet()
        return kinds.size >= 2 && kinds.any { it in OFFER_KINDS }
    }

    private const val MIN_OFFER = 8
    private val OFFER_KINDS = setOf(Kind.NAME, Kind.ORGANISATION, Kind.EMAIL, Kind.ADDRESS)

    /**
     * Splits the pieces into people: a new full name after someone who already has a name and a phone or email starts
     * the next person. A "person" without any phone or email isn't one (a city, a slogan): its lines go back to the
     * one before.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun people(pieces: List<Piece>): List<List<Piece>> {
        val out = ArrayList<MutableList<Piece>>()
        var cur = ArrayList<Piece>()
        var hasName = false
        var hasContact = false
        for (p in pieces) {
            val strong = p.role == Role.NAME && (p.labeled || p.name?.full == true)
            if (strong && hasName && hasContact) {
                out += cur
                cur = ArrayList()
                hasContact = false
            }
            cur += p
            if (strong) hasName = true
            if (p.field?.kind == Kind.PHONE || p.field?.kind == Kind.EMAIL) hasContact = true
        }
        out += cur
        val merged = ArrayList<MutableList<Piece>>()
        for (person in out) {
            val contact = person.any { it.field?.kind == Kind.PHONE || it.field?.kind == Kind.EMAIL }
            val prev = merged.lastOrNull()
            if (!contact && prev != null) {
                prev += person.map { if (it.role == Role.NAME) it.copy(role = Role.OTHER) else it }
            } else {
                merged += person
            }
        }
        return merged
    }
}

/** What a piece of the text is, before the pieces are put together into people. */
internal enum class Role { VALUE, NAME, ORG, TITLE, ADDRESS, OTHER }

/** How a piece looks as part of an address. WEAK: a number and a word, an address only next to one. */
internal enum class AddrPart { NONE, STREET, WEAK, POSTCODE, COUNTRY, CITY, HINT }

/**
 * One piece of the text: a found value ([Role.VALUE] with its [field]) or a line (part) still to be placed. [line] is
 * its line number (blank lines counted, so pieces of one block are at most one apart). [labeled]: the text said what
 * it is ("Name: …").
 */
internal data class Piece(
    val line: Int,
    val role: Role,
    val text: String,
    val field: PasteParser.Field? = null,
    val addr: AddrPart = AddrPart.NONE,
    val name: PasteParser.Name? = null,
    val labeled: Boolean = false,
)

/** The classifier's findings as text, matched against the rules' pieces by content. */
internal class Hinted(
    private val addresses: List<String>,
    val phones: List<String>,
    val urls: List<String>,
    val emails: List<String>,
) {
    fun isAddress(text: String): Boolean {
        val t = squash(text)
        return t.length >= MIN_ADDRESS && addresses.any { it.contains(t, ignoreCase = true) }
    }

    fun isPhone(raw: String): Boolean {
        val t = squash(raw)
        return t.isNotEmpty() && phones.any { it.contains(t) || t.contains(it) }
    }

    companion object {
        private const val MIN_ADDRESS = 4
        private val SPACE = Regex("\\s+")

        fun squash(s: String): String = s.replace(SPACE, " ").trim()

        fun of(src: String, hints: List<PasteParser.Hint>): Hinted {
            fun of(type: PasteParser.HintType) = hints.filter { it.type == type && it.start >= 0 && it.end <= src.length && it.start < it.end }
                .map { squash(src.substring(it.start, it.end)) }.filter { it.isNotEmpty() }
            return Hinted(
                of(PasteParser.HintType.ADDRESS), of(PasteParser.HintType.PHONE), of(PasteParser.HintType.URL), of(PasteParser.HintType.EMAIL),
            )
        }
    }
}
