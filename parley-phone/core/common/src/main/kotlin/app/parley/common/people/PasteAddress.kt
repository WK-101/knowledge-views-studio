package app.parley.common.people

import app.parley.common.people.PasteParser.Address

/**
 * Postal addresses in pasted text: which lines belong to one ([part]: a street with its number, a postcode line, a
 * country), the place names between them, and how a run of such lines splits into street, city, region, postcode and
 * country ([structure]). Written for the common European, British, North American and South Asian forms; anything it
 * can't split stays whole in the street, where the person can still correct it.
 */
internal object PasteAddress {
    private const val MAX_LENGTH = 160
    private const val MAX_POSTCODE_WORDS = 7
    private const val MAX_PLACE_WORDS = 3

    private val STREET_WORDS = setOf(
        "street", "st", "road", "rd", "avenue", "ave", "av", "lane", "ln", "drive", "dr", "boulevard", "blvd", "way", "place", "pl", "square",
        "sq", "court", "ct", "terrace", "close", "crescent", "parkway", "pkwy", "highway", "hwy", "row", "mews", "gardens", "hill", "walk",
        "strasse", "str", "gasse", "weg", "platz", "allee", "ring", "damm", "ufer", "chaussee", "rue", "bd", "chemin", "impasse", "quai",
        "cours", "via", "viale", "piazza", "corso", "largo", "vicolo", "calle", "avenida", "avda", "plaza", "paseo", "carrera", "camino", "rua",
        "travessa", "praca", "straat", "laan", "plein", "gracht", "kade", "singel", "gata", "gatan", "vej", "gade", "vagen", "veien",
        "ulica", "ul", "sector", "block", "phase", "floor", "fl", "building", "bldg", "tower", "suite", "ste", "unit", "level", "apt",
        "apartment", "flat", "house", "plot", "office", "po", "postfach", "box", "bp", "cp", "apartado", "شارع", "طريق", "רחוב",
    )
    private val STREET_ENDINGS = listOf(
        "strasse", "str", "gasse", "weg", "platz", "allee", "ring", "damm", "ufer", "gracht", "straat", "laan", "plein", "kade", "singel",
        "gatan", "vej", "gade", "vagen", "veien",
    )

    private val UK = Regex("""\b[A-Z]{1,2}\d[A-Z\d]?\s?\d[A-Z]{2}\b""")
    private val US = Regex("""\b[A-Z]{2}\s+\d{5}(?:-\d{4})?\b""")
    private val CA = Regex("""\b[A-Z]\d[A-Z]\s?\d[A-Z]\d\b""")
    private val NL = Regex("""^\d{4}\s?[A-Z]{2}\s+\p{L}""")
    private val CODE_CITY = Regex("""^(?:[A-Z]{1,2}-)?\d{3,5}(?:-\d{3})?\s+\p{L}""")
    private val CITY_CODE = Regex("""^\p{L}[\p{L} .'-]*,?\s+\d{4,6}$""")
    private val NUMBER_FIRST = Regex("""^\d+[A-Za-z]?(?:[-/]\d+[A-Za-z]?)?,?\s+\p{L}""")
    private val NUMBER_LAST = Regex("""^\p{L}[\p{L} .'-]{2,},?\s+\d+[A-Za-z]?(?:[-/]\d+[A-Za-z]?)?$""")

    /** How [text] looks as an address line. */
    fun part(text: String): AddrPart {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LENGTH || t.contains('@')) return AddrPart.NONE
        if (PasteWords.isCountry(t)) return AddrPart.COUNTRY
        val digits = t.any { it.isDigit() }
        if (digits && hasStreetWord(t)) return AddrPart.STREET
        if (isPostcodeLine(t)) return AddrPart.POSTCODE
        val words = t.split(' ').size
        if (words <= MAX_PLACE_WORDS + 3 && (NUMBER_FIRST.containsMatchIn(t) || NUMBER_LAST.matches(t))) return AddrPart.WEAK
        return AddrPart.NONE
    }

    private fun hasStreetWord(t: String): Boolean = PasteWords.words(t).any { w ->
        w in STREET_WORDS || STREET_ENDINGS.any { e -> w.length > e.length + 2 && w.endsWith(e) }
    }

    fun isPostcodeLine(t: String): Boolean {
        if (t.split(' ').size > MAX_POSTCODE_WORDS) return false
        return UK.containsMatchIn(t) || US.containsMatchIn(t) || CA.containsMatchIn(t) || NL.containsMatchIn(t) ||
            CODE_CITY.containsMatchIn(t) || CITY_CODE.matches(t)
    }

    /** A short place name ("London", "New York"), which belongs to an address between two of its lines. */
    private fun isPlace(t: String): Boolean {
        val w = t.trim().split(' ').filter { it.isNotEmpty() }
        return w.size in 1..MAX_PLACE_WORDS && t.none { it.isDigit() || it == '@' } && w.all { x -> x.first().let { it.isUpperCase() || !it.isLowerCase() } }
    }

    /**
     * Gathers the address lines: a weak line or a country next to an address line joins it, so does a place name
     * between two of its lines (or after its street), and each run of adjacent address lines becomes one address.
     */
    @Suppress("CyclomaticComplexMethod") // One rule per way addresses run across pieces.
    fun group(pieces: List<Piece>): List<Piece> {
        val p = pieces.toMutableList()
        fun addr(i: Int) = p.getOrNull(i)?.takeIf { it.role == Role.ADDRESS }
        fun near(a: Piece?, b: Piece) = a != null && kotlin.math.abs(a.line - b.line) <= 1
        repeat(2) {
            for (i in p.indices) {
                val x = p[i]
                if (x.role == Role.ADDRESS || x.role == Role.VALUE || x.labeled) continue
                val prev = addr(i - 1)?.takeIf { near(it, x) }
                val next = addr(i + 1)?.takeIf { near(it, x) }
                val joins = when {
                    x.addr == AddrPart.WEAK || x.addr == AddrPart.COUNTRY -> prev != null || next != null
                    x.role != Role.NAME && x.role != Role.OTHER || !isPlace(x.text) -> false
                    prev != null && next != null -> true
                    prev?.addr == AddrPart.STREET -> p.getOrNull(i + 1)?.let { it.role == Role.VALUE || !near(x, it) } ?: true
                    else -> false
                }
                if (joins) p[i] = x.copy(role = Role.ADDRESS, addr = if (x.addr == AddrPart.NONE) AddrPart.CITY else x.addr)
            }
        }
        val out = ArrayList<Piece>()
        var run = ArrayList<Piece>()
        fun flush() {
            if (run.isEmpty()) return
            val a = structure(run.map { it.line to it.text })
            // Shown in the preview as written (the parts go into the address's fields).
            val written = run.joinToString(", ") { it.text.trim().trimEnd(',') }
            out += Piece(run.first().line, Role.VALUE, written, PasteParser.Field(PasteParser.Kind.ADDRESS, written, address = a))
            run = ArrayList()
        }
        for (x in p) {
            if (x.role == Role.ADDRESS) {
                val last = run.lastOrNull()
                // A gap, or a new "Address:" label, starts the next address.
                val apart = last != null && x.line - last.line > 1
                val relabelled = last != null && x.labeled && x.line != last.line
                if (apart || relabelled) flush()
                run += x
            } else {
                flush()
                out += x
            }
        }
        flush()
        return out
    }

    private val STATE_AT_END = Regex("""(?:^|\s)([A-Z]{2,3})$""")

    /** Splits address lines (line number, text) into its parts. */
    @Suppress("CyclomaticComplexMethod") // One rule per address part.
    fun structure(lines: List<Pair<Int, String>>): Address {
        // Each comma part on its own, remembering its line, so street lines keep their line breaks.
        val parts = lines.flatMap { (line, text) -> text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { line to it } }.toMutableList()
        if (parts.isEmpty()) return Address()
        var country = ""
        if (parts.size > 1 && PasteWords.isCountry(parts.last().second)) country = parts.removeAt(parts.lastIndex).second
        var city = ""
        var region = ""
        var postcode = ""
        val at = parts.indexOfLast { isPostcodeLine(it.second) && !(it.second.any(Char::isDigit) && hasStreetWord(it.second) && parts.size > 1) }
        var streetEnd = parts.size
        if (at >= 0) {
            val t = parts[at].second
            streetEnd = at
            US.find(t)?.takeIf { it.range.last == t.length - 1 }?.let { m ->
                region = m.value.take(2)
                postcode = m.value.drop(2).trim()
                city = t.substring(0, m.range.first).trim().trimEnd(',')
            } ?: run {
                val code = (UK.find(t) ?: CA.find(t))?.takeIf { it.range.first == 0 || it.range.last == t.length - 1 }
                    ?: Regex("""^(?:[A-Z]{1,2}-)?\d{3,5}(?:-\d{3})?(?:\s?[A-Z]{2}(?=\s))?""").find(t)
                    ?: Regex("""\d{4,6}$""").find(t)
                if (code != null) {
                    postcode = code.value.trim()
                    city = t.removeRange(code.range).trim().trim(',').trim()
                } else {
                    city = t
                }
            }
            // "Sydney NSW 2000", "ON M5H 2N2": a state or province code before the postcode.
            STATE_AT_END.find(city)?.let { m ->
                if (region.isEmpty()) region = m.groupValues[1]
                city = city.substring(0, m.range.first).trim().trimEnd(',')
            }
            // "Springfield" before "IL 62704", "London" before "SW1A 1AA": the city on its own part.
            val before = parts.getOrNull(at - 1)?.second
            val placeBefore = before != null && before.none { it.isDigit() } && !hasStreetWord(before)
            if (city.isEmpty() && placeBefore) {
                city = parts[at - 1].second
                streetEnd = at - 1
            }
            // A part after the postcode (a state, a province) that isn't the country.
            if (at + 1 < parts.size) {
                val after = parts.subList(at + 1, parts.size).joinToString(", ") { it.second }
                region = listOf(region, after).filter { it.isNotEmpty() }.joinToString(" ")
            }
        } else if (parts.size >= 2 && parts.last().second.none { it.isDigit() }) {
            city = parts.last().second
            streetEnd = parts.size - 1
        }
        val street = StringBuilder()
        var lastLine = -1
        for ((line, text) in parts.subList(0, streetEnd)) {
            if (street.isNotEmpty()) street.append(if (line == lastLine) ", " else "\n")
            street.append(text)
            lastLine = line
        }
        return Address(street.toString(), city, region, postcode, country)
    }
}
