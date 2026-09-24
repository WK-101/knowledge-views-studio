package app.parley.common.vcard

import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import java.io.Reader

/**
 * Structured CSV for spreadsheets: fixed, human-readable columns (name parts, nickname, organisation, title,
 * numbered phone/e-mail/address groups, birthday, notes, labels). It is an interchange format, not a backup:
 * only the fields above are included, and several notes are joined into one cell.
 *
 * Cells that a spreadsheet would run as a formula (starting with `=`, `+`, `-`, `@`, tab or CR) are written
 * with a leading apostrophe, which [read] removes again. Plain phone numbers such as `+1 555 0100` are left
 * alone; they cannot form a formula.
 */
object ContactCsv {
    const val GROUP_SEPARATOR = " ::: "
    private const val NOTE_SEPARATOR = "\n\n"

    private val BASE = listOf("Prefix", "Given", "Middle", "Family", "Suffix", "Nickname", "Organization", "Title")
    private val TAIL = listOf("Birthday", "Notes", "Groups")
    private val ADDRESS_PARTS = listOf(
        "Street" to Col.D4, "PO Box" to Col.D5, "Neighborhood" to Col.D6, "City" to Col.D7,
        "Region" to Col.D8, "Postcode" to Col.D9, "Country" to Col.D10,
    )

    private val PHONE_TYPES = mapOf(
        1 to "Home", 2 to "Mobile", 3 to "Work", 4 to "Work fax", 5 to "Home fax", 6 to "Pager", 7 to "Other",
        8 to "Callback", 9 to "Car", 10 to "Company main", 11 to "ISDN", 12 to "Main", 13 to "Other fax", 14 to "Radio",
        15 to "Telex", 16 to "TTY/TDD", 17 to "Work mobile", 18 to "Work pager", 19 to "Assistant", 20 to "MMS",
    )
    private val EMAIL_TYPES = mapOf(1 to "Home", 2 to "Work", 3 to "Other", 4 to "Mobile")
    private val POSTAL_TYPES = mapOf(1 to "Home", 2 to "Work", 3 to "Other")

    private val SAFE_NUMBER = Regex("""^\+?[0-9 ()./\-]+$""")

    /** How many phone, e-mail and address column groups a set of records needs (at least one each). */
    data class Slots(val phones: Int, val emails: Int, val addresses: Int)

    fun slotsFor(records: Iterable<ContactRecord>): Slots {
        var p = 1; var e = 1; var a = 1
        for (r in records) {
            val rows = r.raws.flatMap { it.rows }
            p = maxOf(p, rows.count { it.mimeType == Mime.PHONE })
            e = maxOf(e, rows.count { it.mimeType == Mime.EMAIL })
            a = maxOf(a, rows.count { it.mimeType == Mime.POSTAL })
        }
        return Slots(p, e, a)
    }

    fun header(slots: Slots): List<String> = buildList {
        addAll(BASE)
        for (i in 1..slots.phones) { add("Phone $i Type"); add("Phone $i Value") }
        for (i in 1..slots.emails) { add("Email $i Type"); add("Email $i Value") }
        for (i in 1..slots.addresses) { add("Address $i Type"); ADDRESS_PARTS.forEach { (n, _) -> add("Address $i $n") } }
        addAll(TAIL)
    }

    /** Writes a header line and one line per record. The UTF-8 BOM helps spreadsheet apps detect the encoding. */
    fun write(records: List<ContactRecord>, out: Appendable, groupTitles: Map<Long, String> = emptyMap(), withBom: Boolean = true) {
        val slots = slotsFor(records.map { VCardMapper.canonical(it, groupTitles) })
        if (withBom) out.append('\uFEFF')
        writeLine(out, header(slots))
        records.forEach { writeLine(out, row(it, slots, groupTitles)) }
    }

    /** The cells of one record, in [header] order (unescaped). */
    fun row(record: ContactRecord, slots: Slots, groupTitles: Map<Long, String> = emptyMap()): List<String> {
        val rows = VCardMapper.canonical(record, groupTitles).raws.single().rows
        fun first(mime: String) = rows.firstOrNull { it.mimeType == mime }
        val name = first(Mime.NAME)
        val org = first(Mime.ORG)
        return buildList {
            add(name?.get(Col.D4).orEmpty()); add(name?.get(Col.D2).orEmpty()); add(name?.get(Col.D5).orEmpty())
            add(name?.get(Col.D3).orEmpty()); add(name?.get(Col.D6).orEmpty())
            add(rows.filter { it.mimeType == Mime.NICKNAME }.mapNotNull { it[Col.D1] }.joinToString(", "))
            add(org?.get(Col.D1).orEmpty()); add(org?.get(Col.D4).orEmpty())
            val phones = rows.filter { it.mimeType == Mime.PHONE }
            for (i in 0 until slots.phones) { val p = phones.getOrNull(i); add(p?.let { typeName(it, PHONE_TYPES) }.orEmpty()); add(p?.get(Col.D1).orEmpty()) }
            val emails = rows.filter { it.mimeType == Mime.EMAIL }
            for (i in 0 until slots.emails) { val e = emails.getOrNull(i); add(e?.let { typeName(it, EMAIL_TYPES) }.orEmpty()); add(e?.get(Col.D1).orEmpty()) }
            val addrs = rows.filter { it.mimeType == Mime.POSTAL }
            for (i in 0 until slots.addresses) {
                val a = addrs.getOrNull(i)
                add(a?.let { typeName(it, POSTAL_TYPES) }.orEmpty())
                ADDRESS_PARTS.forEach { (_, col) ->
                    // A free-form address (no parts) goes in Street so it is not lost.
                    val v = a?.get(col) ?: if (col == Col.D4 && a != null && ADDRESS_PARTS.all { a[it.second] == null }) a[Col.D1] else null
                    add(v.orEmpty())
                }
            }
            add(rows.firstOrNull { it.mimeType == Mime.EVENT && it[Col.D2] == "3" }?.get(Col.D1).orEmpty())
            add(rows.filter { it.mimeType == Mime.NOTE }.mapNotNull { it[Col.D1] }.joinToString(NOTE_SEPARATOR))
            add(rows.filter { it.mimeType == Mime.GROUP }.mapNotNull { it[Col.GROUP_TITLE] }.joinToString(GROUP_SEPARATOR))
        }
    }

    private fun typeName(r: DataRow, names: Map<Int, String>): String {
        val t = r[Col.D2]
        if (t == "0") return r[Col.D3].orEmpty()
        return t?.toIntOrNull()?.let { names[it] } ?: r[Col.D3].orEmpty()
    }

    private fun typeCode(name: String, names: Map<Int, String>, default: String): Pair<String, String?> {
        val s = name.trim()
        if (s.isEmpty()) return default to null
        names.entries.firstOrNull { it.value.equals(s, ignoreCase = true) }?.let { return it.key.toString() to null }
        return "0" to s
    }

    // ---- CSV text ----

    fun writeLine(out: Appendable, cells: List<String>) {
        cells.forEachIndexed { i, c ->
            if (i > 0) out.append(',')
            out.append(quote(escapeFormula(c)))
        }
        out.append("\r\n")
    }

    /** Neutralises spreadsheet formulas (CSV injection) with a leading apostrophe. */
    fun escapeFormula(v: String): String {
        if (v.isEmpty()) return v
        val c = v[0]
        val risky = c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r' || c == '\''
        return if (risky && !SAFE_NUMBER.matches(v)) "'$v" else v
    }

    fun unescapeFormula(v: String): String {
        if (v.length < 2 || v[0] != '\'') return v
        val c = v[1]
        return if (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r' || c == '\'') v.substring(1) else v
    }

    private fun quote(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || v.startsWith(' ') || v.endsWith(' ')) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /**
     * The cell separator of a CSV file from its first line: comma, semicolon (spreadsheets in locales with a decimal
     * comma) or tab, whichever occurs most outside quotes; comma when none occurs.
     */
    fun detectDelimiter(firstLine: String): Char {
        val counts = HashMap<Char, Int>()
        var quoted = false
        for (c in firstLine) {
            if (c == '"') quoted = !quoted
            else if (!quoted && c in DELIMITERS) counts[c] = (counts[c] ?: 0) + 1
        }
        return DELIMITERS.maxByOrNull { counts[it] ?: 0 }?.takeIf { (counts[it] ?: 0) > 0 } ?: ','
    }

    private val DELIMITERS = listOf(',', ';', '\t')
    private val NUMBER_CELL = Regex("""^\+?[0-9 ()./\-]{5,}$""")

    /** A cell that is a phone number and nothing else. */
    fun isPhoneNumber(cell: String): Boolean {
        val t = unescapeFormula(cell.trim().trim('"')).trim()
        return NUMBER_CELL.matches(t) && t.count(Char::isDigit) >= 5
    }

    /**
     * Whether sampled [lines] form a plain list of phone numbers, one per line (optionally under a one-cell header
     * such as "Phone"): no separator anywhere and at least one number.
     */
    fun looksLikeNumberList(lines: List<String>): Boolean {
        val l = lines.map { it.trim().trimStart('﻿') }.filter { it.isNotEmpty() }
        if (l.isEmpty() || l.any { line -> line.any { it in DELIMITERS } }) return false
        val body = if (isPhoneNumber(l.first())) l else l.drop(1)
        return body.isNotEmpty() && body.all(::isPhoneNumber)
    }

    /** Reads a one-number-per-line list: each number becomes a contact that shows the number as its name. */
    fun readNumberList(input: Reader, report: ImportReportBuilder, onRecord: (ParsedCard) -> Unit) {
        val r = if (input is java.io.BufferedReader) input else java.io.BufferedReader(input)
        var line = 0
        r.lineSequence().forEach { raw ->
            line++
            val cell = unescapeFormula(raw.trim().trimStart('﻿').trim('"')).trim()
            if (cell.isEmpty()) return@forEach
            if (!isPhoneNumber(cell)) {
                if (line > 1) report.fail(line, "Not a phone number", raw)
                return@forEach
            }
            val row = DataRow(Mime.PHONE, mapOf(Col.D1 to cell, Col.D2 to "2"))
            val record = ContactRecord(key = "", displayName = "", raws = listOf(RawRecord(null, null, rows = listOf(row))))
            report.cardsParsed++
            onRecord(ParsedCard(line, VCardMapper.canonical(record), raw))
        }
    }

    /**
     * Looks at the start of [input] and says how to read it: the separator, and whether it is a plain list of
     * numbers. The reader is reset to where it was.
     */
    fun sniff(input: java.io.BufferedReader): Pair<Char, Boolean> {
        input.mark(SNIFF_CHARS)
        val buf = CharArray(SNIFF_CHARS - 1)
        var n = 0
        while (n < buf.size) {
            val k = input.read(buf, n, buf.size - n)
            if (k < 0) break
            n += k
        }
        input.reset()
        val head = String(buf, 0, n).trimStart('﻿')
        var lines = head.split('\n').map { it.trimEnd('\r') }
        if (n == buf.size && lines.size > 1) lines = lines.dropLast(1) // the last line may be cut off
        lines = lines.take(50)
        return detectDelimiter(lines.firstOrNull().orEmpty()) to looksLikeNumberList(lines)
    }

    private const val SNIFF_CHARS = 16 * 1024

    /** RFC 4180 parser: quoted cells may hold [delimiter]s, quotes and line breaks. Returns rows of cells. */
    fun parse(input: Reader, delimiter: Char = ','): Sequence<List<String>> = sequence {
        val r = if (input is java.io.BufferedReader) input else java.io.BufferedReader(input)
        val row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var any = false
        var first = true
        while (true) {
            var ch = r.read()
            if (first) { first = false; if (ch == 0xFEFF) ch = r.read() }
            if (ch < 0) break
            val c = ch.toChar()
            any = true
            if (quoted) {
                if (c == '"') {
                    r.mark(1)
                    if (r.read() == '"'.code) cell.append('"') else { r.reset(); quoted = false }
                } else cell.append(c)
                continue
            }
            when (c) {
                '"' -> if (cell.isEmpty()) quoted = true else cell.append(c)
                delimiter -> { row += cell.toString(); cell.setLength(0) }
                '\r', '\n' -> {
                    if (c == '\r') { r.mark(1); if (r.read() != '\n'.code) r.reset() }
                    row += cell.toString(); cell.setLength(0)
                    if (!(row.size == 1 && row[0].isEmpty())) yield(row.toList())
                    row.clear()
                    any = false
                }
                else -> cell.append(c)
            }
        }
        if (any || row.isNotEmpty()) {
            row += cell.toString()
            if (!(row.size == 1 && row[0].isEmpty())) yield(row.toList())
        }
    }

    /**
     * Reads a CSV in this format (columns are found by header name, so reordered or missing columns are fine).
     * Unknown columns are counted in [report] as unmapped; each non-empty line becomes one record.
     */
    fun read(input: Reader, report: ImportReportBuilder, onRecord: (ParsedCard) -> Unit) {
        val buffered = if (input is java.io.BufferedReader) input else java.io.BufferedReader(input)
        val (delimiter, numberList) = sniff(buffered)
        if (numberList) return readNumberList(buffered, report, onRecord)
        val lines = parse(buffered, delimiter).iterator()
        if (!lines.hasNext()) return
        val header = lines.next().map { it.trim() }
        val idx = HashMap<String, Int>()
        header.forEachIndexed { i, h -> idx.putIfAbsent(h.lowercase(), i) }
        val known = header(Slots(99, 99, 99)).map { it.lowercase() }.toSet()
        val unknownCols = header.filter { it.isNotEmpty() && it.lowercase() !in known }
        var line = 1
        while (lines.hasNext()) {
            val cells = lines.next()
            line++
            val raw = cells.joinToString(delimiter.toString())
            try {
                fun cell(name: String): String = idx[name.lowercase()]?.let { cells.getOrNull(it) }?.let(::unescapeFormula)?.trim().orEmpty()
                unknownCols.forEach { c -> if (cell(c).isNotEmpty()) report.unmapped("CSV column “$c”") }
                val rows = ArrayList<DataRow>()
                fun put(mime: String, vararg pairs: Pair<String, String?>) {
                    val v = linkedMapOf<String, String>()
                    pairs.forEach { (k, value) -> if (!value.isNullOrEmpty()) v[k] = value }
                    rows += DataRow(mime, v)
                }
                val nameParts = listOf(Col.D4 to cell("Prefix"), Col.D2 to cell("Given"), Col.D5 to cell("Middle"), Col.D3 to cell("Family"), Col.D6 to cell("Suffix"))
                if (nameParts.any { it.second.isNotEmpty() }) put(Mime.NAME, *nameParts.toTypedArray())
                cell("Nickname").split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(Mime.NICKNAME, Col.D1 to it) }
                if (cell("Organization").isNotEmpty() || cell("Title").isNotEmpty()) put(Mime.ORG, Col.D1 to cell("Organization"), Col.D4 to cell("Title"))
                var i = 1
                while (idx.containsKey("phone $i value")) {
                    val v = cell("Phone $i Value")
                    if (v.isNotEmpty()) { val (t, l) = typeCode(cell("Phone $i Type"), PHONE_TYPES, "7"); put(Mime.PHONE, Col.D1 to v, Col.D2 to t, Col.D3 to l) }
                    i++
                }
                i = 1
                while (idx.containsKey("email $i value")) {
                    val v = cell("Email $i Value")
                    if (v.isNotEmpty()) { val (t, l) = typeCode(cell("Email $i Type"), EMAIL_TYPES, "3"); put(Mime.EMAIL, Col.D1 to v, Col.D2 to t, Col.D3 to l) }
                    i++
                }
                i = 1
                while (ADDRESS_PARTS.any { idx.containsKey("address $i ${it.first}".lowercase()) }) {
                    val parts = ADDRESS_PARTS.map { (n, col) -> col to cell("Address $i $n") }
                    if (parts.any { it.second.isNotEmpty() }) {
                        val (t, l) = typeCode(cell("Address $i Type"), POSTAL_TYPES, "3")
                        put(Mime.POSTAL, *(parts + listOf(Col.D2 to t, Col.D3 to l)).toTypedArray())
                    }
                    i++
                }
                cell("Birthday").takeIf { it.isNotEmpty() }?.let { put(Mime.EVENT, Col.D1 to VCardMapper.normalizeDate(it), Col.D2 to "3") }
                cell("Notes").takeIf { it.isNotEmpty() }?.let { put(Mime.NOTE, Col.D1 to it) }
                cell("Groups").split(GROUP_SEPARATOR.trim()).map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(Mime.GROUP, Col.GROUP_TITLE to it) }
                if (rows.isEmpty()) continue
                val record = ContactRecord(key = "", displayName = "", raws = listOf(RawRecord(null, null, rows = rows)))
                val canonical = VCardMapper.canonical(record)
                report.cardsParsed++
                onRecord(ParsedCard(line, canonical, raw))
            } catch (e: Exception) {
                report.fail(line, "Could not read this line: ${e.message ?: e.javaClass.simpleName}", raw)
            }
        }
    }

    fun readAll(text: String): Pair<List<ContactRecord>, ImportReport> {
        val report = ImportReportBuilder()
        val out = ArrayList<ContactRecord>()
        read(text.reader(), report) { out += it.record }
        return out to report.build()
    }

    fun writeAll(records: List<ContactRecord>, groupTitles: Map<Long, String> = emptyMap()): String =
        StringBuilder().also { write(records, it, groupTitles) }.toString()
}
