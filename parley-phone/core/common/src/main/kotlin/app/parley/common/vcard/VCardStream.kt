package app.parley.common.vcard

import app.parley.common.record.ContactRecord
import ezvcard.VCardVersion
import ezvcard.io.text.VCardReader
import ezvcard.io.text.VCardWriter
import ezvcard.property.ProductId
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.Writer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** One card read from a file: its 1-based position, the mapped record and the raw text (for error reports). */
class ParsedCard(val index: Int, val record: ContactRecord, val raw: String)

/** Streaming vCard file reading and writing, one card at a time, so large address books never sit in memory. */
object VCardStream {
    const val PRODID = "-//Parley//Parley Phone//EN"

    /** Writes cards as vCard 4.0 (UTF-8 when [out] is UTF-8). */
    class CardWriter(out: Writer) : Closeable {
        private val writer = VCardWriter(out, VCardVersion.V4_0).apply {
            isCaretEncodingEnabled = true // RFC 6868: lets parameter values (address LABEL) hold line breaks and quotes
            isAddProdId = false
        }

        fun write(record: ContactRecord, groupTitles: Map<Long, String> = emptyMap()) {
            val card = VCardMapper.toVCard(record, groupTitles)
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
     * parsed are recorded in [report] with their raw text; unmapped properties are counted there too.
     */
    fun read(input: Reader, report: ImportReportBuilder, onCard: (ParsedCard) -> Unit) {
        val lines = if (input is BufferedReader) input else BufferedReader(input)
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
            if (head.startsWith("BEGIN:VCARD")) depth++
            if (head.startsWith("END:VCARD") && --depth == 0) {
                sawCard = true
                parseChunk(++index, chunk.toString(), report, onCard)
            }
        }
        if (depth > 0) {
            sawCard = true
            report.fail(++index, "The card is cut off (no END:VCARD); the file may be incomplete.", chunk.toString())
        }
        if (!sawCard && stray) report.fail(0, "This file contains no vCards.", "")
    }

    private fun parseChunk(index: Int, raw: String, report: ImportReportBuilder, onCard: (ParsedCard) -> Unit) {
        val record = try {
            val reader = VCardReader(raw)
            reader.defaultQuotedPrintableCharset = Charsets.UTF_8
            val card = reader.use { it.readNext() }
            if (card == null) {
                report.fail(index, "Could not read this card.", raw)
                return
            }
            val unmapped = LinkedHashMap<String, Int>()
            val record = VCardMapper.fromVCard(card, unmapped)
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
        onCard(ParsedCard(index, record, raw))
    }

    /** Convenience for tests and small inputs: parses all cards in [text]. */
    fun readAll(text: String): Pair<List<ContactRecord>, ImportReport> {
        val report = ImportReportBuilder()
        val out = ArrayList<ContactRecord>()
        read(text.reader(), report) { out += it.record }
        return out to report.build()
    }

    /** Convenience: writes [records] to a string. */
    fun writeAll(records: List<ContactRecord>, groupTitles: Map<Long, String> = emptyMap()): String {
        val sw = java.io.StringWriter()
        CardWriter(sw).use { w -> records.forEach { w.write(it, groupTitles) } }
        return sw.toString()
    }
}
