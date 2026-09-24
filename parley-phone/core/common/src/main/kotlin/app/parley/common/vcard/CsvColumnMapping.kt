package app.parley.common.vcard

import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import java.io.Reader

/** What a CSV column holds (M12). */
enum class CsvField(val label: String) {
    IGNORE("Don't import"),
    FULL_NAME("Full name"),
    PREFIX("Name prefix"),
    GIVEN("First name"),
    MIDDLE("Middle name"),
    FAMILY("Last name"),
    SUFFIX("Name suffix"),
    NICKNAME("Nickname"),
    PHONE("Phone"),
    PHONE_LABEL("Phone type (for the phone next to it)"),
    EMAIL("E-mail"),
    EMAIL_LABEL("E-mail type (for the e-mail next to it)"),
    ORG("Company"),
    TITLE("Job title"),
    ADDRESS("Address"),
    WEBSITE("Website"),
    BIRTHDAY("Birthday"),
    NOTES("Notes"),
    LABELS("Labels"),
}

/** A column's meaning: the [field] and, for phones and e-mails, the type (ContactsContract `TYPE_*`; null = from a type column, else mobile / other). */
data class ColumnTarget(val field: CsvField, val type: Int? = null) {
    val label: String
        get() = when {
            this.field == CsvField.PHONE && type != null -> "Phone (${(CsvColumnMapping.PHONE_TYPES[type] ?: "other").lowercase()})"
            this.field == CsvField.EMAIL && type != null -> "E-mail (${(CsvColumnMapping.EMAIL_TYPES[type] ?: "other").lowercase()})"
            else -> this.field.label
        }

    companion object {
        val IGNORED = ColumnTarget(CsvField.IGNORE)
    }
}

/**
 * M12: contact CSVs that aren't Parley's own format (Google, Outlook, "Name,Phone", any headers, semicolon or tab
 * separated, a single column). [guess] proposes what each column holds, the user corrects it on the mapping screen,
 * and [read] turns each line into a contact with that mapping. Pure: the file is read by the caller.
 */
object CsvColumnMapping {
    val PHONE_TYPES = linkedMapOf(
        2 to "Mobile", 1 to "Home", 3 to "Work", 12 to "Main", 7 to "Other", 17 to "Work mobile", 4 to "Work fax", 5 to "Home fax",
        6 to "Pager", 10 to "Company main", 9 to "Car", 8 to "Callback", 19 to "Assistant", 13 to "Other fax", 14 to "Radio",
        11 to "ISDN", 15 to "Telex", 16 to "TTY/TDD", 18 to "Work pager", 20 to "MMS",
    )
    val EMAIL_TYPES = linkedMapOf(1 to "Home", 2 to "Work", 3 to "Other", 4 to "Mobile")

    /** The choices offered for a column, in menu order. */
    val OPTIONS: List<ColumnTarget> = buildList {
        add(ColumnTarget.IGNORED)
        listOf(CsvField.FULL_NAME, CsvField.GIVEN, CsvField.MIDDLE, CsvField.FAMILY, CsvField.PREFIX, CsvField.SUFFIX, CsvField.NICKNAME).forEach { add(ColumnTarget(it)) }
        add(ColumnTarget(CsvField.PHONE))
        listOf(2, 1, 3, 12, 7, 4, 5, 6).forEach { add(ColumnTarget(CsvField.PHONE, it)) }
        add(ColumnTarget(CsvField.PHONE_LABEL))
        add(ColumnTarget(CsvField.EMAIL))
        listOf(1, 2, 3).forEach { add(ColumnTarget(CsvField.EMAIL, it)) }
        add(ColumnTarget(CsvField.EMAIL_LABEL))
        listOf(CsvField.ORG, CsvField.TITLE, CsvField.ADDRESS, CsvField.WEBSITE, CsvField.BIRTHDAY, CsvField.NOTES, CsvField.LABELS).forEach { add(ColumnTarget(it)) }
    }

    /** Which well-known layout a header comes from, for the screen's "Looks like a Google export" line. */
    enum class Layout(val label: String) { PARLEY("Parley"), GOOGLE("Google Contacts"), OUTLOOK("Outlook"), OTHER("a spreadsheet") }

    private fun norm(h: String): String = h.trim().trimStart('﻿').lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** Parley's own export ([ContactCsv]): every named column is one of its columns, and "Given" is among them. */
    fun isParleyHeader(header: List<String>): Boolean {
        val known = ContactCsv.header(ContactCsv.Slots(99, 99, 99)).map { it.lowercase() }.toSet()
        val named = header.map { it.trim().trimStart('﻿').lowercase() }.filter { it.isNotEmpty() }
        return named.size >= 3 && "given" in named && named.all { it in known }
    }

    fun layout(header: List<String>): Layout {
        if (isParleyHeader(header)) return Layout.PARLEY
        val n = header.map(::norm).toSet()
        return when {
            ("givenname" in n || "firstname" in n) && n.any { it.startsWith("phone1") } -> Layout.GOOGLE
            "mobilephone" in n || "businessphone" in n || "emailaddress" in n || "homephone" in n -> Layout.OUTLOOK
            else -> Layout.OTHER
        }
    }

    /**
     * Whether the first line is a header: it is data when one of its cells is a phone number or an e-mail address.
     * A single line of words is a header.
     */
    fun hasHeader(firstRow: List<String>): Boolean =
        firstRow.none { c -> ContactCsv.isPhoneNumber(c) || looksLikeEmail(c) }

    private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
    fun looksLikeEmail(cell: String): Boolean = EMAIL.matches(cell.trim())

    /**
     * Proposes a target per column from its header (Google, Outlook and common names in several languages) and,
     * when the name says nothing, from [sample] rows (a column of numbers is a phone, of addresses an e-mail). With
     * [header] null (no header line) the first text column becomes the full name.
     */
    fun guess(header: List<String>?, sample: List<List<String>>): List<ColumnTarget> {
        val width = maxOf(header?.size ?: 0, sample.maxOfOrNull { it.size } ?: 0)
        val names = header?.map(::norm).orEmpty()
        val outlookTitle = "jobtitle" in names
        val out = MutableList(width) { i -> names.getOrNull(i)?.let { byName(it, outlookTitle) } }
        for (i in 0 until width) {
            if (out[i] != null) continue
            val values = sample.mapNotNull { it.getOrNull(i)?.trim()?.takeIf { v -> v.isNotEmpty() } }
            out[i] = when {
                values.isEmpty() -> null
                values.all { v -> v.split(":::").all { ContactCsv.isPhoneNumber(it) } } -> ColumnTarget(CsvField.PHONE)
                values.all { looksLikeEmail(it) } -> ColumnTarget(CsvField.EMAIL)
                else -> null
            }
        }
        // Without a recognised name column, the first other column holding words is the full name.
        if (out.none { it != null && it.field in NAME_FIELDS }) {
            (0 until width).firstOrNull { i -> out[i] == null && sample.any { r -> r.getOrNull(i)?.any(Char::isLetter) == true } }
                ?.let { out[it] = ColumnTarget(CsvField.FULL_NAME) }
        }
        return out.map { it ?: ColumnTarget.IGNORED }
    }

    private val NAME_FIELDS = setOf(CsvField.FULL_NAME, CsvField.GIVEN, CsvField.FAMILY, CsvField.MIDDLE)

    private fun byName(n: String, outlookTitle: Boolean): ColumnTarget? {
        fun t(f: CsvField, type: Int? = null) = ColumnTarget(f, type)
        // Phonetic and furigana name columns ("Phonetic First Name", "Yomi…") are not phones or names.
        if (n.startsWith("phonetic") || n.contains("yomi")) return t(CsvField.IGNORE)
        // Google: "Phone 1 - Type" / "Phone 1 - Label" / "Phone 1 - Value", "E-mail 2 - Value"…
        Regex("^phone\\d+(type|label)$").find(n)?.let { return t(CsvField.PHONE_LABEL) }
        Regex("^phone\\d+value$").find(n)?.let { return t(CsvField.PHONE) }
        Regex("^e?mail\\d+(type|label)$").find(n)?.let { return t(CsvField.EMAIL_LABEL) }
        Regex("^e?mail\\d+value$").find(n)?.let { return t(CsvField.EMAIL) }
        Regex("^(website|web)\\d+value$").find(n)?.let { return t(CsvField.WEBSITE) }
        if (Regex("^(website|web|address|relation|event|im|organization|customfield|externalid|location)\\d*(type|label|\\d)").containsMatchIn(n) &&
            !n.startsWith("organization1name") && !n.startsWith("organization1title") && !n.startsWith("address1formatted")
        ) return t(CsvField.IGNORE)
        return when (n) {
            "name", "fullname", "displayname", "contactname", "contact", "names", "nomcomplet", "nombre", "nome", "naam", "имя", "фио" -> t(CsvField.FULL_NAME)
            "givenname", "firstname", "first", "forename", "vorname", "prenom", "prénom", "nombrepila", "voornaam" -> t(CsvField.GIVEN)
            "additionalname", "middlename", "middle", "secondname" -> t(CsvField.MIDDLE)
            "familyname", "lastname", "last", "surname", "nachname", "nom", "apellido", "apellidos", "cognome", "achternaam", "фамилия" -> t(CsvField.FAMILY)
            "nameprefix", "prefix", "salutation", "anrede" -> t(CsvField.PREFIX)
            "title" -> if (outlookTitle) t(CsvField.PREFIX) else t(CsvField.TITLE)
            "namesuffix", "suffix" -> t(CsvField.SUFFIX)
            "nickname", "shortname" -> t(CsvField.NICKNAME)
            "mobilephone", "mobile", "mobilenumber", "cell", "cellphone", "cellular", "handy", "mobil", "portable", "móvil", "movil", "cellulare", "whatsapp" -> t(CsvField.PHONE, 2)
            "homephone", "homephone2", "home", "privat", "telefonprivat" -> t(CsvField.PHONE, 1)
            "businessphone", "businessphone2", "workphone", "work", "office", "officephone", "telefongeschäftlich", "telefongeschaftlich" -> t(CsvField.PHONE, 3)
            "companymainphone" -> t(CsvField.PHONE, 10)
            "primaryphone", "mainphone" -> t(CsvField.PHONE, 12)
            "otherphone" -> t(CsvField.PHONE, 7)
            "businessfax", "workfax" -> t(CsvField.PHONE, 4)
            "homefax" -> t(CsvField.PHONE, 5)
            "otherfax" -> t(CsvField.PHONE, 13)
            "pager" -> t(CsvField.PHONE, 6)
            "carphone" -> t(CsvField.PHONE, 9)
            "callback" -> t(CsvField.PHONE, 8)
            "assistantsphone", "assistantphone" -> t(CsvField.PHONE, 19)
            "radiophone" -> t(CsvField.PHONE, 14)
            "isdn" -> t(CsvField.PHONE, 11)
            "telex" -> t(CsvField.PHONE, 15)
            "ttytddphone" -> t(CsvField.PHONE, 16)
            "phone", "phonenumber", "phones", "telephone", "tel", "telefon", "telefono", "teléfono", "téléphone", "telephonenumber", "number", "numero", "número", "nummer", "телефон" -> t(CsvField.PHONE)
            "email", "emailaddress", "email2address", "email3address", "mail", "emails", "courriel" -> t(CsvField.EMAIL)
            "emaildisplayname", "email2displayname", "email3displayname", "emailtype", "email2type", "email3type" -> t(CsvField.IGNORE)
            "company", "organization", "organisation", "organizationname", "organization1name", "org", "firma", "empresa", "société", "societe", "entreprise" -> t(CsvField.ORG)
            "jobtitle", "organizationtitle", "organization1title", "position", "role" -> t(CsvField.TITLE)
            "notes", "note", "comment", "comments", "remarks", "notiz", "notizen" -> t(CsvField.NOTES)
            "birthday", "birthdate", "dateofbirth", "dob", "geburtstag", "anniversaire", "cumpleaños" -> t(CsvField.BIRTHDAY)
            "labels", "label", "groupmembership", "categories", "category", "groups", "group", "tags", "gruppe" -> t(CsvField.LABELS)
            "website", "webpage", "url", "homepage", "personalwebpage", "webpage2" -> t(CsvField.WEBSITE)
            "address", "address1formatted", "homeaddress", "businessaddress", "streetaddress", "adresse", "dirección", "direccion", "indirizzo" -> t(CsvField.ADDRESS)
            else -> when {
                n.contains("mobile") || n.contains("cell") -> t(CsvField.PHONE, 2)
                n.contains("phone") || n.contains("tel") -> t(CsvField.PHONE)
                n.contains("mail") -> t(CsvField.EMAIL)
                else -> null
            }
        }
    }

    /** "Mobile", "* Mobile" (Google marks the primary one), "cell"… → phone type and custom label. */
    fun phoneType(label: String): Pair<Int, String?> = typeOf(label, PHONE_TYPES, 2, mapOf("cell" to 2, "mobil" to 2, "handy" to 2, "business" to 3, "office" to 3, "fax" to 4))

    fun emailType(label: String): Pair<Int, String?> = typeOf(label, EMAIL_TYPES, 3, mapOf("personal" to 1, "private" to 1, "business" to 2, "office" to 2))

    private fun typeOf(label: String, names: Map<Int, String>, default: Int, aliases: Map<String, Int>): Pair<Int, String?> {
        val s = label.trim().removePrefix("*").trim()
        if (s.isEmpty()) return default to null
        names.entries.firstOrNull { it.value.equals(s, ignoreCase = true) }?.let { return it.key to null }
        aliases[s.lowercase()]?.let { return it to null }
        return 0 to s
    }

    /** Several values in one cell: Google's " ::: " (also used for labels), or ";" / "," for labels. */
    private fun split(v: String): List<String> = v.split(":::").map { it.trim() }.filter { it.isNotEmpty() }

    private fun splitLabels(v: String): List<String> {
        val parts = if (v.contains(":::")) v.split(":::") else v.split(';', ',')
        // Google's system groups ("* myContacts", "* starred") aren't labels.
        return parts.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("*") }
    }

    /** The column a type column describes: the phone (or e-mail) right after it, else right before it. */
    private fun pairedLabel(mapping: List<ColumnTarget>, valueCol: Int, labelField: CsvField): Int? {
        val before = valueCol - 1
        val after = valueCol + 1
        return when {
            mapping.getOrNull(before)?.field == labelField -> before
            mapping.getOrNull(after)?.field == labelField && mapping.getOrNull(after + 1)?.field != mapping[valueCol].field -> after
            else -> null
        }
    }

    /** One line to one contact, or null when the line holds nothing that is imported. */
    fun toRecord(cells: List<String>, mapping: List<ColumnTarget>): ContactRecord? {
        fun cell(i: Int): String = cells.getOrNull(i)?.let { ContactCsv.unescapeFormula(it.trim()) }?.trim().orEmpty()
        val rows = ArrayList<DataRow>()
        fun put(mime: String, vararg pairs: Pair<String, String?>) {
            val v = linkedMapOf<String, String>()
            pairs.forEach { (k, value) -> if (!value.isNullOrEmpty()) v[k] = value }
            if (v.isNotEmpty()) rows += DataRow(mime, v)
        }
        fun first(f: CsvField): String = mapping.indices.filter { mapping[it].field == f }.map(::cell).firstOrNull { it.isNotEmpty() }.orEmpty()

        val name = listOf(
            Col.D1 to first(CsvField.FULL_NAME), Col.D4 to first(CsvField.PREFIX), Col.D2 to first(CsvField.GIVEN),
            Col.D5 to first(CsvField.MIDDLE), Col.D3 to first(CsvField.FAMILY), Col.D6 to first(CsvField.SUFFIX),
        )
        if (name.any { it.second.isNotEmpty() }) {
            // A full name alone is split into parts by Android; with parts, the full name would override them.
            val parts = name.drop(1)
            put(Mime.NAME, *(if (parts.any { it.second.isNotEmpty() }) parts else name.take(1)).toTypedArray())
        }
        val notes = ArrayList<String>()
        var org = ""
        var title = ""
        mapping.forEachIndexed { i, target ->
            val v = cell(i)
            if (v.isEmpty()) return@forEachIndexed
            when (target.field) {
                CsvField.NICKNAME -> put(Mime.NICKNAME, Col.D1 to v)
                CsvField.PHONE -> {
                    val label = pairedLabel(mapping, i, CsvField.PHONE_LABEL)?.let(::cell).orEmpty()
                    val (t, l) = target.type?.let { it to null } ?: phoneType(label)
                    split(v).forEach { put(Mime.PHONE, Col.D1 to it, Col.D2 to t.toString(), Col.D3 to l) }
                }
                CsvField.EMAIL -> {
                    val label = pairedLabel(mapping, i, CsvField.EMAIL_LABEL)?.let(::cell).orEmpty()
                    val (t, l) = target.type?.let { it to null } ?: emailType(label)
                    split(v).forEach { put(Mime.EMAIL, Col.D1 to it, Col.D2 to t.toString(), Col.D3 to l) }
                }
                CsvField.ORG -> if (org.isEmpty()) org = v
                CsvField.TITLE -> if (title.isEmpty()) title = v
                CsvField.ADDRESS -> split(v).forEach { put(Mime.POSTAL, Col.D1 to it, Col.D2 to "3") }
                CsvField.WEBSITE -> split(v).forEach { put(Mime.WEBSITE, Col.D1 to it, Col.D2 to "7") }
                CsvField.BIRTHDAY -> put(Mime.EVENT, Col.D1 to VCardMapper.normalizeDate(v), Col.D2 to "3")
                CsvField.NOTES -> notes += v
                CsvField.LABELS -> splitLabels(v).forEach { put(Mime.GROUP, Col.GROUP_TITLE to it) }
                else -> Unit
            }
        }
        if (org.isNotEmpty() || title.isNotEmpty()) put(Mime.ORG, Col.D1 to org, Col.D4 to title)
        if (notes.isNotEmpty()) put(Mime.NOTE, Col.D1 to notes.joinToString("\n"))
        // Labels alone don't make a contact.
        if (rows.none { it.mimeType != Mime.GROUP }) return null
        return VCardMapper.canonical(ContactRecord(key = "", displayName = "", raws = listOf(RawRecord(null, null, rows = rows))))
    }

    /**
     * Reads every line of [input] with [mapping]: the header line is skipped when [hasHeader]. Ignored columns that
     * hold something are listed in [report] as not imported, by header name.
     */
    fun read(
        input: Reader,
        delimiter: Char,
        mapping: List<ColumnTarget>,
        hasHeader: Boolean,
        report: ImportReportBuilder,
        onRecord: (ParsedCard) -> Unit,
    ) {
        val lines = ContactCsv.parse(input, delimiter).iterator()
        var header: List<String> = emptyList()
        var line = 0
        if (hasHeader && lines.hasNext()) {
            header = lines.next()
            line = 1
        }
        while (lines.hasNext()) {
            val cells = lines.next()
            line++
            val raw = cells.joinToString(delimiter.toString())
            try {
                cells.forEachIndexed { i, c ->
                    if (c.isNotBlank() && mapping.getOrNull(i)?.field.let { it == null || it == CsvField.IGNORE }) {
                        report.unmapped("CSV column “" + (header.getOrNull(i)?.trim()?.ifEmpty { null } ?: "${i + 1}") + "”")
                    }
                }
                val record = toRecord(cells, mapping)
                if (record == null) {
                    if (cells.any { it.isNotBlank() }) report.fail(line, "Nothing to import on this line with the chosen columns", raw)
                    continue
                }
                report.cardsParsed++
                onRecord(ParsedCard(line, record, raw))
            } catch (e: Exception) {
                report.fail(line, "Could not read this line: ${e.message ?: e.javaClass.simpleName}", raw)
            }
        }
    }

    /** Convenience for tests and previews: the records of [text]. */
    fun readAll(text: String, mapping: List<ColumnTarget>, hasHeader: Boolean, delimiter: Char = ContactCsv.detectDelimiter(text.substringBefore('\n'))): Pair<List<ContactRecord>, ImportReport> {
        val report = ImportReportBuilder()
        val out = ArrayList<ContactRecord>()
        read(text.reader(), delimiter, mapping, hasHeader, report) { out += it.record }
        return out to report.build()
    }

    /** "Ana Silva · +351 912 345 678 · ana@example.com" for the preview. */
    fun describe(record: ContactRecord): String {
        val rows = record.raws.flatMap { it.rows }
        val n = rows.firstOrNull { it.mimeType == Mime.NAME }
        val name = n?.get(Col.D1) ?: listOfNotNull(n?.get(Col.D4), n?.get(Col.D2), n?.get(Col.D5), n?.get(Col.D3), n?.get(Col.D6)).joinToString(" ")
        val org = rows.firstOrNull { it.mimeType == Mime.ORG }?.get(Col.D1)
        return listOfNotNull(
            name.ifBlank { org ?: "(no name)" },
            rows.filter { it.mimeType == Mime.PHONE }.mapNotNull { it[Col.D1] }.joinToString(", ").ifEmpty { null },
            rows.filter { it.mimeType == Mime.EMAIL }.mapNotNull { it[Col.D1] }.joinToString(", ").ifEmpty { null },
            rows.filter { it.mimeType == Mime.GROUP }.mapNotNull { it[Col.GROUP_TITLE] }.joinToString(", ").ifEmpty { null }?.let { "Labels: $it" },
        ).joinToString(" · ")
    }
}
