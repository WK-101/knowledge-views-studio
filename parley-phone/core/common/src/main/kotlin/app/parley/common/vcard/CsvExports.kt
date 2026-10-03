package app.parley.common.vcard

import app.parley.common.EventDate
import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.people.OtherFields

/** The CSV layouts Parley exports: its own ([ContactCsv]), and the ones Google Contacts and Outlook import. */
enum class CsvFormat { PARLEY, GOOGLE, OUTLOOK }

/**
 * Google Contacts' and Outlook's CSV layouts, with their exact headers, so a file imports there without mapping
 * columns. Both are interchange formats: what a layout has no column for (a fourth e-mail in Outlook, a messenger
 * handle in Google) is left out, which is why Parley's own CSV and vCard stay the lossless choices.
 */
object CsvExports {
    fun write(format: CsvFormat, records: List<ContactRecord>, out: Appendable, groupTitles: Map<Long, String> = emptyMap(), withBom: Boolean = true) {
        when (format) {
            CsvFormat.PARLEY -> ContactCsv.write(records, out, groupTitles, withBom)
            CsvFormat.GOOGLE -> table(Google.header(records.map { canonical(it, groupTitles) }), records.map { Google.row(it, groupTitles) }, out, withBom)
            CsvFormat.OUTLOOK -> table(Outlook.HEADER, records.map { Outlook.row(canonical(it, groupTitles)) }, out, withBom)
        }
    }

    fun writeAll(format: CsvFormat, records: List<ContactRecord>, groupTitles: Map<Long, String> = emptyMap(), withBom: Boolean = false): String =
        StringBuilder().also { write(format, records, it, groupTitles, withBom) }.toString()

    private fun canonical(r: ContactRecord, titles: Map<Long, String>) = VCardMapper.canonical(r, titles)

    private fun table(header: List<String>, rows: List<Map<String, String>>, out: Appendable, withBom: Boolean) {
        if (withBom) out.append('﻿')
        ContactCsv.writeLine(out, header)
        rows.forEach { r -> ContactCsv.writeLine(out, header.map { r[it].orEmpty() }) }
    }

    private fun DataRow.v(col: String) = this[col].orEmpty()

    private fun ContactRecord.rows(): List<DataRow> = raws.single().rows

    /** A typed row's label: its custom label, else [names]' English name for its type. */
    private fun label(r: DataRow, names: Map<Int, String>): String =
        if (r[Col.D2] == "0") r.v(Col.D3) else r[Col.D2]?.toIntOrNull()?.let { names[it] } ?: r.v(Col.D3)

    private val PHONE_NAMES = mapOf(
        1 to "Home", 2 to "Mobile", 3 to "Work", 4 to "Work Fax", 5 to "Home Fax", 6 to "Pager", 7 to "Other", 8 to "Callback", 9 to "Car",
        10 to "Company Main", 11 to "ISDN", 12 to "Main", 13 to "Other Fax", 14 to "Radio", 15 to "Telex", 16 to "TTY/TDD", 17 to "Work Mobile",
        18 to "Work Pager", 19 to "Assistant", 20 to "MMS",
    )
    private val EMAIL_NAMES = mapOf(1 to "Home", 2 to "Work", 3 to "Other", 4 to "Mobile")
    private val POSTAL_NAMES = mapOf(1 to "Home", 2 to "Work", 3 to "Other")
    private val WEB_NAMES = mapOf(1 to "Home Page", 2 to "Blog", 3 to "Profile", 4 to "Home", 5 to "Work", 6 to "FTP", 7 to "Other")
    private val EVENT_NAMES = mapOf(1 to "Anniversary", 2 to "Other", 3 to "Birthday")
    private val RELATION_NAMES = mapOf(
        1 to "Assistant", 2 to "Brother", 3 to "Child", 4 to "Domestic Partner", 5 to "Father", 6 to "Friend", 7 to "Manager", 8 to "Mother",
        9 to "Parent", 10 to "Partner", 11 to "Referred By", 12 to "Relative", 13 to "Sister", 14 to "Spouse",
    )

    /** Google Contacts' export layout (2024–26): fixed name and work columns, then numbered groups as the data needs. */
    object Google {
        private val HEAD = listOf(
            "First Name", "Middle Name", "Last Name", "Phonetic First Name", "Phonetic Middle Name", "Phonetic Last Name",
            "Name Prefix", "Name Suffix", "Nickname", "File As", "Organization Name", "Organization Title", "Organization Department",
            "Birthday", "Notes", "Photo", "Labels",
        )
        private val ADDRESS = listOf("Label", "Formatted", "Street", "City", "PO Box", "Region", "Postal Code", "Country", "Extended Address")
        private const val MY_CONTACTS = "* myContacts"
        private const val STARRED = "* starred"

        private fun count(records: List<ContactRecord>, min: Int, pick: (List<DataRow>) -> Int) = maxOf(min, records.maxOfOrNull { pick(it.rows()) } ?: 0)

        /** The header for [records] (canonical): at least one e-mail, phone and address group, as Google writes. */
        fun header(records: List<ContactRecord>): List<String> = buildList {
            addAll(HEAD)
            fun group(name: String, n: Int, parts: List<String>) { for (i in 1..n) parts.forEach { add("$name $i - $it") } }
            group("E-mail", count(records, 1) { r -> r.count { it.mimeType == Mime.EMAIL } }, listOf("Label", "Value"))
            group("Phone", count(records, 1) { r -> r.count { it.mimeType == Mime.PHONE } }, listOf("Label", "Value"))
            group("Address", count(records, 1) { r -> r.count { it.mimeType == Mime.POSTAL } }, ADDRESS)
            group("Relation", count(records, 0) { r -> r.count { it.mimeType == Mime.RELATION } }, listOf("Label", "Value"))
            group("Website", count(records, 0) { r -> r.count { it.mimeType == Mime.WEBSITE } }, listOf("Label", "Value"))
            group("Event", count(records, 0) { r -> events(r).size }, listOf("Label", "Value"))
            group("Custom Field", count(records, 0) { r -> r.count { it.mimeType == Mime.CUSTOM_FIELD } }, listOf("Label", "Value"))
        }

        /** Dates other than the birthday in the Birthday column. */
        private fun events(rows: List<DataRow>): List<DataRow> {
            val dates = rows.filter { it.mimeType == Mime.EVENT }
            val birthday = dates.firstOrNull { it[Col.D2] == "3" }
            return dates.filter { it !== birthday }
        }

        fun row(record: ContactRecord, groupTitles: Map<Long, String> = emptyMap()): Map<String, String> {
            val c = VCardMapper.canonical(record, groupTitles)
            val rows = c.rows()
            val out = HashMap<String, String>()
            fun put(k: String, v: String?) { if (!v.isNullOrEmpty()) out[k] = v }
            rows.firstOrNull { it.mimeType == Mime.NAME }?.let { n ->
                put("First Name", n[Col.D2]); put("Middle Name", n[Col.D5]); put("Last Name", n[Col.D3])
                put("Phonetic First Name", n[Col.D7]); put("Phonetic Middle Name", n[Col.D8]); put("Phonetic Last Name", n[Col.D9])
                put("Name Prefix", n[Col.D4]); put("Name Suffix", n[Col.D6])
                // Only a formatted name: Google reads the parts, so it goes in First Name.
                if (listOf(Col.D2, Col.D3, Col.D4, Col.D5, Col.D6).all { n[it] == null }) put("First Name", n[Col.D1])
            }
            put("Nickname", rows.filter { it.mimeType == Mime.NICKNAME }.mapNotNull { it[Col.D1] }.joinToString(", "))
            put("File As", rows.firstOrNull { it.mimeType == OtherFields.GOOGLE_FILE_AS }?.get(Col.D1))
            rows.firstOrNull { it.mimeType == Mime.ORG }?.let { o ->
                put("Organization Name", o[Col.D1]); put("Organization Title", o[Col.D4]); put("Organization Department", o[Col.D5])
            }
            put("Birthday", rows.firstOrNull { it.mimeType == Mime.EVENT && it[Col.D2] == "3" }?.get(Col.D1))
            put("Notes", rows.filter { it.mimeType == Mime.NOTE }.mapNotNull { it[Col.D1] }.joinToString("\n\n"))
            val labels = listOf(MY_CONTACTS) + listOfNotNull(STARRED.takeIf { c.starred }) +
                rows.filter { it.mimeType == Mime.GROUP }.mapNotNull { it[Col.GROUP_TITLE] }
            put("Labels", labels.joinToString(ContactCsv.GROUP_SEPARATOR))
            fun pairs(name: String, list: List<DataRow>, names: Map<Int, String>, value: (DataRow) -> String = { it.v(Col.D1) }) =
                list.forEachIndexed { i, r -> put("$name ${i + 1} - Label", label(r, names)); put("$name ${i + 1} - Value", value(r)) }
            pairs("E-mail", rows.filter { it.mimeType == Mime.EMAIL }, EMAIL_NAMES)
            pairs("Phone", rows.filter { it.mimeType == Mime.PHONE }, PHONE_NAMES)
            rows.filter { it.mimeType == Mime.POSTAL }.forEachIndexed { i, a ->
                val g = "Address ${i + 1} - "
                put(g + "Label", label(a, POSTAL_NAMES)); put(g + "Formatted", a[Col.D1]); put(g + "Street", a[Col.D4]); put(g + "City", a[Col.D7])
                put(g + "PO Box", a[Col.D5]); put(g + "Region", a[Col.D8]); put(g + "Postal Code", a[Col.D9]); put(g + "Country", a[Col.D10])
                put(g + "Extended Address", a[Col.D6])
            }
            pairs("Relation", rows.filter { it.mimeType == Mime.RELATION }, RELATION_NAMES)
            pairs("Website", rows.filter { it.mimeType == Mime.WEBSITE }, WEB_NAMES)
            pairs("Event", events(rows), EVENT_NAMES)
            rows.filter { it.mimeType == Mime.CUSTOM_FIELD }.forEachIndexed { i, f ->
                put("Custom Field ${i + 1} - Label", f[Col.D1]); put("Custom Field ${i + 1} - Value", f[Col.D2])
            }
            return out
        }
    }

    /** Outlook's (Outlook.com and desktop) contacts CSV: one fixed set of columns. */
    object Outlook {
        val HEADER = listOf(
            "First Name", "Middle Name", "Last Name", "Title", "Suffix", "Nickname", "Given Yomi", "Surname Yomi",
            "E-mail Address", "E-mail 2 Address", "E-mail 3 Address",
            "Home Phone", "Home Phone 2", "Business Phone", "Business Phone 2", "Mobile Phone", "Car Phone", "Other Phone", "Primary Phone",
            "Pager", "Business Fax", "Home Fax", "Other Fax", "Company Main Phone", "Callback", "Radio Phone", "Telex", "TTY/TDD Phone",
            "IMAddress", "Job Title", "Department", "Company", "Office Location", "Manager's Name", "Assistant's Name", "Assistant's Phone",
            "Company Yomi", "Business Street", "Business City", "Business State", "Business Postal Code", "Business Country/Region",
            "Home Street", "Home City", "Home State", "Home Postal Code", "Home Country/Region", "Other Street", "Other City", "Other State",
            "Other Postal Code", "Other Country/Region", "Personal Web Page", "Spouse", "Schools", "Hobby", "Location", "Web Page",
            "Birthday", "Anniversary", "Notes",
        )

        /** Phone type → Outlook's columns for it, in the order they fill. */
        private val PHONES = mapOf(
            1 to listOf("Home Phone", "Home Phone 2"), 3 to listOf("Business Phone", "Business Phone 2"), 2 to listOf("Mobile Phone"),
            17 to listOf("Mobile Phone"), 9 to listOf("Car Phone"), 7 to listOf("Other Phone"), 12 to listOf("Primary Phone"), 6 to listOf("Pager"),
            18 to listOf("Pager"), 4 to listOf("Business Fax"), 5 to listOf("Home Fax"), 13 to listOf("Other Fax"), 10 to listOf("Company Main Phone"),
            8 to listOf("Callback"), 14 to listOf("Radio Phone"), 15 to listOf("Telex"), 16 to listOf("TTY/TDD Phone"), 19 to listOf("Assistant's Phone"),
        )
        private val ADDRESS_GROUP = mapOf(2 to "Business", 1 to "Home")

        /** Outlook reads dates as month/day/year; one without a year has no place there. */
        fun date(stored: String?): String? = EventDate.parse(stored)?.takeIf { it.year != null }?.let { "${it.month}/${it.day}/${it.year}" }

        @Suppress("CyclomaticComplexMethod") // One branch per Outlook column group.
        fun row(c: ContactRecord): Map<String, String> {
            val rows = c.rows()
            val out = HashMap<String, String>()
            fun put(k: String, v: String?) { if (!v.isNullOrEmpty() && k !in out) out[k] = v }
            rows.firstOrNull { it.mimeType == Mime.NAME }?.let { n ->
                put("First Name", n[Col.D2] ?: n[Col.D1].takeIf { listOf(Col.D3, Col.D4, Col.D5, Col.D6).all { k -> n[k] == null } })
                put("Middle Name", n[Col.D5]); put("Last Name", n[Col.D3]); put("Title", n[Col.D4]); put("Suffix", n[Col.D6])
                put("Given Yomi", n[Col.D7]); put("Surname Yomi", n[Col.D9])
            }
            put("Nickname", rows.firstOrNull { it.mimeType == Mime.NICKNAME }?.get(Col.D1))
            rows.filter { it.mimeType == Mime.EMAIL }.take(3).forEachIndexed { i, e ->
                put(if (i == 0) "E-mail Address" else "E-mail ${i + 1} Address", e[Col.D1])
            }
            val leftOver = ArrayList<String>()
            rows.filter { it.mimeType == Mime.PHONE }.forEach { p ->
                val number = p.v(Col.D1)
                val col = PHONES[p[Col.D2]?.toIntOrNull()].orEmpty().firstOrNull { it !in out }
                if (col != null) out[col] = number else leftOver += number
            }
            // Numbers without a column of their own (custom labels, a third home number) go where there's room.
            leftOver.forEach { n -> listOf("Other Phone", "Home Phone 2", "Business Phone 2").firstOrNull { it !in out }?.let { out[it] = n } }
            put("IMAddress", rows.firstOrNull { it.mimeType == Mime.IM || it.mimeType == Mime.SIP }?.get(Col.D1))
            rows.firstOrNull { it.mimeType == Mime.ORG }?.let { o ->
                put("Job Title", o[Col.D4]); put("Department", o[Col.D5]); put("Company", o[Col.D1]); put("Office Location", o[Col.D9])
                put("Company Yomi", o[Col.D8])
            }
            val relations = rows.filter { it.mimeType == Mime.RELATION }
            put("Manager's Name", relations.firstOrNull { it[Col.D2] == "7" }?.get(Col.D1))
            put("Assistant's Name", relations.firstOrNull { it[Col.D2] == "1" }?.get(Col.D1))
            put("Spouse", relations.firstOrNull { it[Col.D2] == "14" }?.get(Col.D1))
            rows.filter { it.mimeType == Mime.POSTAL }.forEach { a ->
                val group = ADDRESS_GROUP[a[Col.D2]?.toIntOrNull()] ?: "Other"
                if ("$group Street" in out || "$group City" in out) return@forEach
                val street = listOfNotNull(a[Col.D4], a[Col.D5], a[Col.D6]).joinToString("\n")
                    .ifEmpty { a[Col.D1].takeIf { listOf(Col.D7, Col.D8, Col.D9, Col.D10).all { k -> a[k] == null } }.orEmpty() }
                put("$group Street", street); put("$group City", a[Col.D7]); put("$group State", a[Col.D8])
                put("$group Postal Code", a[Col.D9]); put("$group Country/Region", a[Col.D10])
            }
            val sites = rows.filter { it.mimeType == Mime.WEBSITE }
            val personal = sites.firstOrNull { it[Col.D2] == "1" || it[Col.D2] == "4" }
            put("Personal Web Page", personal?.get(Col.D1))
            put("Web Page", sites.firstOrNull { it !== personal }?.get(Col.D1))
            val dates = rows.filter { it.mimeType == Mime.EVENT }
            put("Birthday", date(dates.firstOrNull { it[Col.D2] == "3" }?.get(Col.D1)))
            put("Anniversary", date(dates.firstOrNull { it[Col.D2] == "1" }?.get(Col.D1)))
            put("Notes", rows.filter { it.mimeType == Mime.NOTE }.mapNotNull { it[Col.D1] }.joinToString("\n\n"))
            return out
        }
    }
}
