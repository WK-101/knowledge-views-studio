package app.parley.common.vcard

import app.parley.common.CalendarConverter
import app.parley.common.record.ContactRecord
import app.parley.common.security.Bounded
import app.parley.common.security.LimitExceededException
import ezvcard.VCard
import ezvcard.VCardVersion
import ezvcard.io.text.VCardReader
import ezvcard.io.text.VCardWriter
import ezvcard.property.ProductId
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringWriter
import java.io.Writer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * One card read from a file: its 1-based position, the mapped record, the raw text (for error reports) and Parley's own
 * notes when the card came from an open export ([CardNotes]).
 */
class ParsedCard(val index: Int, val record: ContactRecord, val raw: String, val notes: CardNotes? = null)

/** Streaming vCard file reading and writing, one card at a time, so large address books never sit in memory. */
object VCardStream {
    const val PRODID = "-//Parley//Parley Phone//EN"

    /** Writes cards as vCard 4.0 (UTF-8 when [out] is UTF-8). */
    class CardWriter(out: Writer) : Closeable {
        private val writer = VCardWriter(out, VCardVersion.V4_0).apply {
            isCaretEncodingEnabled = true // RFC 6868: lets parameter values (address LABEL) hold line breaks and quotes
            isAddProdId = false
            Rfc9554.scribes.forEach { registerScribe(it) }
        }

        /** Writes [record], with Parley's [notes] about the person and their readable [summary] when given ([CardNotes]). */
        fun write(record: ContactRecord, groupTitles: Map<Long, String> = emptyMap(), notes: CardNotes? = null, summary: String? = null) {
            val card = VCardMapper.toVCard(record, groupTitles)
            if (notes != null) CardNotes.write(card, notes, summary)
            card.addProperty(ProductId(PRODID))
            writer.write(card)
        }

        fun flush() = writer.flush()

        override fun close() = writer.close()
    }

    /** Opens [input] as text, honouring a UTF-8/UTF-16 byte-order mark; defaults to UTF-8. */
    fun reader(input: InputStream): Reader {
        val bin = BufferedInputStream(input)
        bin.mark(4)
        val b = ByteArray(3)
        val n = bin.read(b)
        bin.reset()
        val (charset: Charset, skip) = when {
            n >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3L
            n >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2L
            n >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2L
            else -> Charsets.UTF_8 to 0L
        }
        if (skip > 0) bin.skip(skip)
        val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        return InputStreamReader(bin, decoder)
    }

    /**
     * Reads every card in [input], calling [onCard] for each one that maps to a contact. Cards that cannot be
     * parsed are recorded in [report] with their raw text; unmapped properties are counted there too. [calendars]
     * reads dates written in another calendar ([VCardMapper.fromVCard]).
     */
    fun read(input: Reader, report: ImportReportBuilder, calendars: CalendarConverter? = null, onCard: (ParsedCard) -> Unit) {
        // Bounded lines and cards: a crafted file can't make one line or one card fill the memory.
        val lines = Bounded.LineReader(input)
        val chunk = StringBuilder()
        var depth = 0
        var index = 0
        var sawCard = false
        var stray = false
        while (true) {
            val line = lines.readLine() ?: break
            val head = line.trimStart().uppercase()
            if (depth == 0) {
                if (head.startsWith("BEGIN:VCARD")) {
                    depth = 1
                    chunk.setLength(0)
                    chunk.append(line).append("\r\n")
                } else if (line.isNotBlank()) {
                    stray = true
                }
                continue
            }
            chunk.append(line).append("\r\n")
            if (chunk.length > Bounded.Caps.VCARD_CARD) throw LimitExceededException("A card is larger than ${Bounded.Caps.VCARD_CARD shr 20} MB")
            if (head.startsWith("BEGIN:VCARD")) depth++
            if (head.startsWith("END:VCARD") && --depth == 0) {
                sawCard = true
                if (index >= Bounded.Caps.IMPORT_ENTRIES) throw LimitExceededException("The file has more than ${Bounded.Caps.IMPORT_ENTRIES} cards")
                parseChunk(++index, chunk.toString(), report, calendars, onCard)
            }
        }
        if (depth > 0) {
            sawCard = true
            report.fail(++index, "The card is cut off (no END:VCARD); the file may be incomplete.", chunk.toString())
        }
        if (!sawCard && stray) report.fail(0, "This file contains no vCards.", "")
    }

    private fun parseChunk(index: Int, raw: String, report: ImportReportBuilder, calendars: CalendarConverter?, onCard: (ParsedCard) -> Unit) {
        var notes: CardNotes? = null
        val record = try {
            val reader = VCardReader(raw)
            reader.defaultQuotedPrintableCharset = Charsets.UTF_8
            Rfc9554.scribes.forEach { reader.registerScribe(it) }
            val card = reader.use { it.readNext() }
            if (card == null) {
                report.fail(index, "Could not read this card.", raw)
                return
            }
            val unmapped = LinkedHashMap<String, Int>()
            // Parley's own notes aren't fields of the contact: taken out before mapping, kept beside the record.
            notes = CardNotes.take(card)
            val record = VCardMapper.fromVCard(card, unmapped, calendars)
            unmapped.forEach { (k, v) -> report.unmapped(k, v) }
            record
        } catch (e: Exception) {
            report.fail(index, "Could not read this card: ${e.message ?: e.javaClass.simpleName}", raw)
            return
        }
        if (record.displayName.isBlank() && record.raws.all { it.rows.isEmpty() }) {
            report.fail(index, "The card is empty.", raw)
            return
        }
        report.cardsParsed++
        onCard(ParsedCard(index, record, raw, notes))
    }

    /** The first card of [text] (RFC 9554's name and address parts included), or null. */
    fun parseOne(text: String): VCard? {
        val reader = VCardReader(text)
        reader.defaultQuotedPrintableCharset = Charsets.UTF_8
        Rfc9554.scribes.forEach { reader.registerScribe(it) }
        return reader.use { it.readNext() }
    }

    /** [card] as vCard 4.0 text, without a PRODID (RFC 9554's name and address parts included). */
    fun writeOne(card: VCard, caretEncoding: Boolean = false): String {
        val sw = StringWriter()
        VCardWriter(sw, VCardVersion.V4_0).use { w ->
            w.isCaretEncodingEnabled = caretEncoding
            w.isAddProdId = false
            Rfc9554.scribes.forEach { w.registerScribe(it) }
            w.write(card)
        }
        return sw.toString()
    }

    /** Convenience for tests and small inputs: parses all cards in [text]. */
    fun readAll(text: String, calendars: CalendarConverter? = null): Pair<List<ContactRecord>, ImportReport> {
        val report = ImportReportBuilder()
        val out = ArrayList<ContactRecord>()
        read(text.reader(), report, calendars) { out += it.record }
        return out to report.build()
    }

    /** Convenience: writes [records] to a string. */
    fun writeAll(records: List<ContactRecord>, groupTitles: Map<Long, String> = emptyMap()): String {
        val sw = StringWriter()
        CardWriter(sw).use { w -> records.forEach { w.write(it, groupTitles) } }
        return sw.toString()
    }
}
