package app.parley.common.people

import app.parley.common.CountryCodes
import app.parley.common.EventDate
import app.parley.common.PhoneIdentity
import app.parley.common.TextSearch
import app.parley.common.record.Col
import app.parley.common.record.Mime
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Contacts search: every field of a contact, for address-book and private contacts alike. A query's words must all
 * be found (in any field each: "ana lisbon" finds Ana who lives in Lisbon), accent- and case-insensitively; a query
 * that is mostly digits is also looked for in the numbers, written nationally or internationally. [match] says which
 * field explained the match, so the row can say "Matched: address".
 *
 * Each contact is prepared once into a [Doc] (texts folded, numbers in their digit forms), so a keystroke only folds
 * the query and scans prepared strings. Docs live in memory only; a private contact's is made from its opened details
 * and never written anywhere.
 */
object ContactSearch {
    /** Which field matched, in the order a hint prefers them. */
    enum class Field {
        NAME,
        NUMBER,
        PHONETIC,
        NICKNAME,
        EMAIL,
        COMPANY,
        ADDRESS,
        RELATION,
        DATE,
        NOTE,

        /** A social or professional profile's handle ("@ana.lima" on Instagram). */
        PROFILE,
        WEBSITE,

        /** A messenger handle or SIP address. */
        HANDLE,

        /** A custom field's label or value ("Shoe size: 38"). */
        CUSTOM,
        LABEL,
        PRONOUNS,
        LANGUAGE,
        ACCOUNT,
    }

    /** True when the row should say which field matched ("Matched: address"); false for the name or number. */
    fun explains(field: Field?): Boolean = field != null && field != Field.NAME && field != Field.NUMBER

    /**
     * Folds text for matching: lower case, accents dropped, and the letters that don't decompose (ø, æ, ß, ł…) spelled
     * out, so "Ålesund", "Straße" and "Łódź" are found as typed on any keyboard.
     */
    fun fold(s: String): String {
        val n = TextSearch.normalize(s)
        if (n.none { it in FOLDS }) return n
        val sb = StringBuilder(n.length + 4)
        n.forEach { c -> sb.append(FOLDS[c] ?: c) }
        return sb.toString()
    }

    private val FOLDS: Map<Char, String> = mapOf(
        'ø' to "o", 'æ' to "ae", 'œ' to "oe", 'ß' to "ss", 'ł' to "l", 'đ' to "d", 'ð' to "d", 'þ' to "th", 'ı' to "i",
        '’' to "'", '‘' to "'",
    )

    /** A query, folded once for many docs. */
    class Query(text: String) {
        val folded: String = fold(text.trim())
        val isEmpty: Boolean get() = folded.isEmpty()
        internal val words: List<String> = folded.split(' ', '\t', '\n', ',').filter { it.isNotEmpty() }

        /** Each word's digits when it is a number ("912", "+351"), else null: looked for in the numbers too. */
        internal val wordDigits: List<List<String>?> = words.map { w ->
            if (w.length >= 2 && w.all { it in '0'..'9' || it == '+' }) listOf(PhoneIdentity.digits(w)) else null
        }
        private val digits = PhoneIdentity.digits(folded)

        /** Mostly digits: the whole query is also searched in the numbers. */
        internal val byNumber = digits.length >= 2 && digits.length * 2 >= folded.count { !it.isWhitespace() }

        /** The query's digits as typed, and without an international prefix ("0044…" → "44…"). */
        internal val digitForms: List<String> = if (!byNumber) emptyList() else listOfNotNull(
            digits,
            digits.removePrefix("00").takeIf { digits.startsWith("00") && it.length >= 2 },
        )
    }

    /** One contact prepared for search: folded texts with their fields, numbers in their digit forms, and its facets. */
    class Doc internal constructor(
        val id: Long,
        private val fields: Array<Field>,
        private val texts: Array<String>,
        private val numbers: Array<String>,
        val facets: ContactFacets,
    ) {
        /** Every text, one per line: a word not in it is in no field, found with one scan. */
        private val all: String = texts.joinToString("\n")

        /** The first field holding [word]: fields are kept in [Field] order, so it is the one a hint prefers. */
        internal fun fieldOf(word: String): Field? {
            if (!all.contains(word)) return null
            for (i in texts.indices) if (texts[i].contains(word)) return fields[i]
            return null
        }

        internal fun hasNumber(forms: List<String>): Boolean = numbers.any { n -> forms.any { n.contains(it) } }
    }

    /**
     * Which field explains [doc] matching [q], or null for no match. A blank query matches everything by name. The
     * name and number win; otherwise the first field (in [Field] order) that one of the words needed. [name] is the
     * name the list shows, already [fold]ed, searched as the name too (it can come from elsewhere than the doc).
     */
    fun match(q: Query, doc: Doc, name: String = ""): Field? {
        if (q.isEmpty) return Field.NAME
        if (q.byNumber && doc.hasNumber(q.digitForms)) return Field.NUMBER
        var hint: Field? = null
        var number = false
        for (i in q.words.indices) {
            val f = wordField(q, i, doc, name) ?: return null
            when {
                f == Field.NUMBER -> number = true
                explains(f) -> if (hint == null || f.ordinal < hint.ordinal) hint = f
            }
        }
        return hint ?: if (number) Field.NUMBER else Field.NAME
    }

    /** Where word [i] of [q] is: the shown name, a field, or (a number word) the numbers; null when nowhere. */
    private fun wordField(q: Query, i: Int, doc: Doc, name: String): Field? {
        val w = q.words[i]
        if (name.contains(w)) return Field.NAME
        val digits = q.wordDigits[i]
        return doc.fieldOf(w) ?: if (digits != null && doc.hasNumber(digits)) Field.NUMBER else null
    }

    fun match(query: String, doc: Doc, name: String = ""): Field? = match(Query(query), doc, name)

    /** Digit forms of a stored number: as written, international without '+', national with its trunk prefix, and bare. */
    fun numberForms(raw: String, region: String?): List<String> {
        val d = PhoneIdentity.digits(raw)
        if (d.isEmpty()) return emptyList()
        val e164 = PhoneIdentity.e164(raw, region) ?: return listOf(d)
        val cc = CountryCodes.callingCodeOf(e164) ?: return listOf(d, e164.substring(1)).distinct()
        val national = e164.substring(1 + cc.length)
        val trunk = (region?.takeIf { CountryCodes.callingCode(it) == cc } ?: CountryCodes.regionsFor(cc).firstOrNull())
            ?.let { CountryCodes.trunkPrefix(it) }.orEmpty()
        return listOf(d, e164.substring(1), trunk + national, national).filter { it.isNotEmpty() }.distinct()
    }

    /**
     * Builds a [Doc]. Address-book contacts are fed their data rows ([row]); private contacts their opened details,
     * field by field. Blank values are skipped.
     */
    class Builder(private val id: Long, private val region: String? = null) {
        private val fields = ArrayList<Field>()
        private val texts = ArrayList<String>()
        private val numbers = LinkedHashSet<String>()
        private val facets = ContactFacets.Builder()

        private fun add(field: Field, vararg values: String?) = add(field, values.asList())

        private fun add(field: Field, values: List<String?>) {
            val text = values.mapNotNull { v -> v?.trim()?.takeIf { it.isNotEmpty() } }.joinToString(" ")
            if (text.isEmpty()) return
            fields += field
            texts += fold(text)
        }

        fun name(vararg parts: String?) {
            if (parts.any { !it.isNullOrBlank() }) facets.named = true
            add(Field.NAME, parts.asList())
        }

        /** The name a list shows, which may be a number or an email when the contact has no name: searched, not counted as one. */
        fun shownName(vararg names: String?) = add(Field.NAME, names.asList())

        fun phonetic(vararg parts: String?) = add(Field.PHONETIC, parts.asList())

        fun nickname(s: String?) {
            if (!s.isNullOrBlank()) facets.named = true
            add(Field.NICKNAME, s)
        }

        fun number(raw: String?) {
            if (raw.isNullOrBlank()) return
            val forms = numberForms(raw, region)
            if (forms.isEmpty()) return
            numbers += forms
            facets.flag(ContactFacets.HAS_NUMBER)
        }

        fun email(s: String?) {
            if (s.isNullOrBlank()) return
            add(Field.EMAIL, s)
            facets.flag(ContactFacets.HAS_EMAIL)
        }

        /** An address, every part searched; [parts] is RFC 9554's stored form ([AddressParts]). */
        @Suppress("LongParameterList") // An address's own parts.
        fun address(
            street: String?, poBox: String?, neighborhood: String?, city: String?, region: String?, postcode: String?, country: String?,
            parts: String? = null, formatted: String? = null,
        ) {
            val before = texts.size
            add(Field.ADDRESS, listOf(street, poBox, neighborhood, city, region, postcode, country) + AddressParts.decode(parts).values)
            // A row with only its formatted form (some sync adapters write nothing else) still says where.
            if (texts.size == before) add(Field.ADDRESS, formatted)
            if (texts.size == before) return
            facets.flag(ContactFacets.HAS_ADDRESS)
            country?.let { c -> Countries.canonical(c).takeIf { it.isNotEmpty() }?.let { facets.add(Facet.COUNTRY, it) } }
            city?.trim()?.takeIf { it.isNotEmpty() }?.let { facets.add(Facet.PLACE, it) }
            region?.trim()?.takeIf { it.isNotEmpty() }?.let { facets.add(Facet.PLACE, it) }
        }

        fun work(company: String?, title: String?, department: String? = null, office: String? = null, description: String? = null) {
            add(Field.COMPANY, company, title, department, office, description)
            company?.trim()?.takeIf { it.isNotEmpty() }?.let {
                facets.named = true
                facets.add(Facet.COMPANY, it)
            }
        }

        fun website(url: String?, type: Int? = null, label: String? = null) {
            if (url.isNullOrBlank()) return
            val profile = SocialProfiles.fromWebsite(url, type, label)
            if (profile != null) add(Field.PROFILE, SocialProfiles.searchTerms(profile) + profile.service.label)
            add(Field.WEBSITE, url)
        }

        /** A messenger handle or a SIP address, with the service's name ("Signal") when known. */
        fun handle(value: String?, service: String? = null) {
            if (value.isNullOrBlank()) return
            add(Field.HANDLE, value, service)
        }

        /** A relation: the person's name and the relation's type ("Sister") or own label. */
        fun relation(name: String?, type: Int, label: String?) {
            val kind = RelationTypes.fromAndroid(type, label)?.label ?: label?.trim()?.takeIf { it.isNotEmpty() }
            add(Field.RELATION, name, kind)
            kind?.let { facets.add(Facet.RELATION, it) }
        }

        /** A date ("1990-05-14" or "--05-14"), found by year, month name, "14 may" or its kind ("birthday"). */
        fun event(date: String?, type: Int, label: String?) {
            val d = EventDate.parse(date) ?: return add(Field.DATE, date, label)
            val month = Month.of(d.month).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            val kind = when (type) {
                TYPE_BIRTHDAY -> "birthday"
                TYPE_ANNIVERSARY -> "anniversary"
                else -> label
            }
            add(Field.DATE, d.format(), d.year?.toString(), "${d.day} $month", "$month ${d.day}", kind)
            if (type == TYPE_BIRTHDAY) {
                facets.flag(ContactFacets.HAS_BIRTHDAY)
                facets.add(Facet.BIRTHDAY_MONTH, d.month.toString())
            }
        }

        fun note(s: String?) = add(Field.NOTE, s)

        fun custom(label: String?, value: String?) {
            add(Field.CUSTOM, label, value)
            label?.trim()?.takeIf { it.isNotEmpty() }?.let { facets.add(Facet.CUSTOM_LABEL, it) }
        }

        fun label(title: String?) = add(Field.LABEL, title)

        fun pronouns(s: String?) = add(Field.PRONOUNS, s)

        /** The language as stored (a BCP 47 tag or the name as typed): found by tag and by its English name. */
        fun language(stored: String?) {
            if (stored.isNullOrBlank()) return
            val name = Languages.display(stored, Locale.ENGLISH)
            add(Field.LANGUAGE, stored, name.takeIf { it != stored })
            facets.add(Facet.LANGUAGE, name)
        }

        fun account(label: String?) = add(Field.ACCOUNT, label)

        /** One address-book data row, by its kind; [get] reads a column ("data1"…). Unknown kinds are skipped. */
        @Suppress("CyclomaticComplexMethod") // One branch per kind of row.
        fun row(mime: String, get: (String) -> String?) {
            fun int(col: String) = get(col)?.toIntOrNull() ?: 0
            when (mime) {
                Mime.NAME -> {
                    // Prefix, given, middle, family, suffix; the display name too (it may be the only part).
                    name(get(Col.D1), get(Col.D4), get(Col.D2), get(Col.D5), get(Col.D3), get(Col.D6))
                    phonetic(get(Col.D7), get(Col.D8), get(Col.D9))
                }
                Mime.NAME_PARTS -> name(get(Col.D1), get(Col.D2))
                Mime.NICKNAME -> nickname(get(Col.D1))
                Mime.PHONE -> number(get(Col.D1))
                Mime.EMAIL -> email(get(Col.D1))
                Mime.POSTAL -> address(
                    get(Col.D4), get(Col.D5), get(Col.D6), get(Col.D7), get(Col.D8), get(Col.D9), get(Col.D10),
                    parts = get(AddressParts.COLUMN), formatted = get(Col.D1),
                )
                // Company, title, department, job description, office location.
                Mime.ORG -> work(get(Col.D1), get(Col.D4), get(Col.D5), get(Col.D9), get(Col.D6))
                Mime.WEBSITE -> website(get(Col.D1), int(Col.D2), get(Col.D3))
                Mime.IM -> handle(get(Col.D1), get(Col.D6))
                Mime.SIP -> handle(get(Col.D1))
                Mime.RELATION -> relation(get(Col.D1), int(Col.D2), get(Col.D3))
                Mime.EVENT -> event(get(Col.D1), int(Col.D2), get(Col.D3))
                Mime.NOTE -> note(get(Col.D1))
                Mime.CUSTOM_FIELD, Mime.GOOGLE_CUSTOM_FIELD -> custom(get(Col.D1), get(Col.D2))
                Mime.PRONOUNS -> pronouns(get(Col.D1))
                Mime.LANGUAGE -> language(get(Col.D1))
            }
        }

        fun build(): Doc {
            val order = fields.indices.sortedBy { fields[it].ordinal }
            return Doc(id, Array(order.size) { fields[order[it]] }, Array(order.size) { texts[order[it]] }, numbers.toTypedArray(), facets.build())
        }
    }

    /** ContactsContract's Event.TYPE_BIRTHDAY and TYPE_ANNIVERSARY. */
    const val TYPE_BIRTHDAY = 3
    const val TYPE_ANNIVERSARY = 1

    /** The Data kinds [Builder.row] reads (for the address book's query). */
    val ROW_KINDS: List<String> = listOf(
        Mime.NAME, Mime.NAME_PARTS, Mime.NICKNAME, Mime.PHONE, Mime.EMAIL, Mime.POSTAL, Mime.ORG, Mime.WEBSITE, Mime.IM, Mime.SIP,
        Mime.RELATION, Mime.EVENT, Mime.NOTE, Mime.CUSTOM_FIELD, Mime.GOOGLE_CUSTOM_FIELD, Mime.PRONOUNS, Mime.LANGUAGE,
    )
}

/**
 * Which docs the Contacts search uses. A private contact's sealed details (its addresses, notes, dates…) are searched
 * only while they may be read: the vault is open (its details opened in memory after the unlock), discreet mode is
 * off and Parley's app lock isn't engaged. Otherwise it is found by what its listing shows (name and numbers), like
 * before it was opened.
 */
object SearchDocs {
    fun privateDetailsSearchable(vaultOpen: Boolean, discreet: Boolean, appLocked: Boolean): Boolean = vaultOpen && !discreet && !appLocked

    /**
     * [device] docs by list id, with [privateDetails] (by list id, negative) laid over them only when [searchable];
     * in [discreet] mode no private doc at all.
     */
    fun combine(
        device: Map<Long, ContactSearch.Doc>,
        privateDetails: Map<Long, ContactSearch.Doc>,
        searchable: Boolean,
        discreet: Boolean,
    ): Map<Long, ContactSearch.Doc> {
        if (discreet) return device.filterKeys { it >= 0 }
        if (!searchable || privateDetails.isEmpty()) return device
        return HashMap<Long, ContactSearch.Doc>(device.size + privateDetails.size).apply {
            putAll(device)
            privateDetails.forEach { (id, d) -> if (id < 0) put(id, d) }
        }
    }
}
