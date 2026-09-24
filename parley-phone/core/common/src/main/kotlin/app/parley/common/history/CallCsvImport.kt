package app.parley.common.history

import app.parley.common.CallType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** RFC 4180 reader that also accepts LF-only and CR-only files, a BOM, and `;` or tab separators. */
object Csv {
    fun parse(text: String, separator: Char? = null): List<List<String>> {
        val s = text.removePrefix("﻿")
        val sep = separator ?: detectSeparator(s)
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var fieldStarted = false
        var i = 0
        fun endField() {
            row.add(field.toString()); field.setLength(0); fieldStarted = false
        }
        fun endRow() {
            endField()
            if (!(row.size == 1 && row[0].isEmpty())) rows.add(row)
            row = ArrayList()
        }
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> if (!fieldStarted && field.isEmpty()) { quoted = true; fieldStarted = true } else field.append(c)
                    sep -> endField()
                    '\r' -> { endRow(); if (i + 1 < s.length && s[i + 1] == '\n') i++ }
                    '\n' -> endRow()
                    else -> { field.append(c); fieldStarted = true }
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty() || fieldStarted) endRow()
        return rows
    }

    /** The most frequent of `,` `;` tab in the first line, outside quotes. */
    fun detectSeparator(s: String): Char {
        val counts = HashMap<Char, Int>()
        var quoted = false
        for (c in s) {
            if (c == '"') quoted = !quoted
            if (!quoted && (c == '\n' || c == '\r')) break
            if (!quoted && (c == ',' || c == ';' || c == '\t')) counts[c] = (counts[c] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key ?: ','
    }
}

enum class ImportSource {
    PARLEY,
    LOGGER,
    GENERIC,
}

/** Which column holds what (indexes into a row; null = not present). */
data class ColumnMapping(
    val timestamp: Int? = null,
    val date: Int? = null,
    val time: Int? = null,
    val number: Int? = null,
    val type: Int? = null,
    val duration: Int? = null,
    val name: Int? = null,
    val sim: Int? = null,
    val accountId: Int? = null,
) {
    /** Enough to import: a number, a type and a date. */
    val isUsable: Boolean get() = number != null && type != null && (timestamp != null || date != null)
}

/** One call read from a CSV. [type] uses the platform CallLog.Calls.TYPE constants. */
data class ImportedCall(
    val number: String,
    val date: Long,
    val durationSec: Long,
    val type: Int,
    val name: String?,
    val simLabel: String?,
    val accountId: String?,
) {
    /**
     * Column values for inserting into the system call log (keys are the CallLog.Calls column names).
     * Imported calls are history, not news: NEW=0 and IS_READ=1, so no missed-call badge or notification.
     */
    fun providerValues(): Map<String, Any?> = buildMap {
        put(ProviderColumns.NUMBER, number)
        put(ProviderColumns.DATE, date)
        put(ProviderColumns.DURATION, durationSec)
        put(ProviderColumns.TYPE, type)
        put(ProviderColumns.NEW, 0)
        put(ProviderColumns.IS_READ, 1)
        put(ProviderColumns.PRESENTATION, if (number.isBlank()) ProviderColumns.PRESENTATION_RESTRICTED else ProviderColumns.PRESENTATION_ALLOWED)
        name?.takeIf { it.isNotBlank() }?.let { put(ProviderColumns.CACHED_NAME, it) }
        accountId?.takeIf { it.isNotBlank() }?.let { put(ProviderColumns.PHONE_ACCOUNT_ID, it) }
    }

    val dedupeKey: String get() = NumberKeys.dedupe(number, date) + "|" + type
}

/** android.provider.CallLog.Calls column names and constants, for pure code and tests. */
object ProviderColumns {
    const val NUMBER = "number"
    const val DATE = "date"
    const val DURATION = "duration"
    const val TYPE = "type"
    const val NEW = "new"
    const val IS_READ = "is_read"
    const val CACHED_NAME = "name"
    const val PRESENTATION = "presentation"
    const val PHONE_ACCOUNT_ID = "subscription_id"
    const val PRESENTATION_ALLOWED = 1
    const val PRESENTATION_RESTRICTED = 2

    const val INCOMING = 1
    const val OUTGOING = 2
    const val MISSED = 3
    const val VOICEMAIL = 4
    const val REJECTED = 5
    const val BLOCKED = 6
    const val ANSWERED_EXTERNALLY = 7

    fun typeOf(t: CallType): Int = when (t) {
        CallType.INCOMING -> INCOMING
        CallType.OUTGOING -> OUTGOING
        CallType.MISSED -> MISSED
        CallType.VOICEMAIL -> VOICEMAIL
        CallType.REJECTED -> REJECTED
        CallType.BLOCKED -> BLOCKED
        CallType.ANSWERED_EXTERNALLY -> ANSWERED_EXTERNALLY
        CallType.UNKNOWN -> INCOMING
    }
}

/** Why a row (or the whole file) can't be imported; the app maps each kind to a localised message. */
enum class RowProblemKind { EMPTY_FILE, NEED_COLUMNS, UNREADABLE_ROW, UNKNOWN_TYPE, UNREADABLE_DATE, UNREADABLE_DURATION, NOT_A_NUMBER }

/** [value] is the offending cell text, when there is one. */
data class RowProblem(val line: Int, val kind: RowProblemKind, val value: String = "")

/** Thrown by [CallCsvImport.parseRow] for a row that can't be read. */
class RowProblemException(val kind: RowProblemKind, val value: String) : IllegalArgumentException("$kind '$value'")

/** A dry run: what an import would do. Nothing is written until the user confirms. */
data class ImportPlan(
    val source: ImportSource,
    val header: List<String>,
    val mapping: ColumnMapping,
    val toInsert: List<ImportedCall>,
    /** Rows already in the call history (or repeated in the file). */
    val duplicates: Int,
    val problems: List<RowProblem>,
    val rowsRead: Int,
) {

}

/**
 * Call-history CSV import: Parley's own export, Logger's export (`name,duration,number,phone_account_id,
 * call_type,…,timestamp,…` with `CallType.incoming` types and millisecond timestamps) and generic CSVs whose
 * columns are guessed from the header and can be remapped by the user.
 */
object CallCsvImport {
    val LOGGER_HEADER = listOf(
        "name", "duration", "number", "phone_account_id", "call_type", "formatted_number",
        "sim_display_name", "timestamp", "cached_number_label", "cached_number_type", "cached_matched_number",
    )

    fun detect(header: List<String>): ImportSource {
        val h = header.map { it.trim().removePrefix("﻿") }
        if (h.size >= LOGGER_HEADER.size && LOGGER_HEADER.indices.all { i ->
                h[i] == LOGGER_HEADER[i] || (LOGGER_HEADER[i] == "formatted_number" && h[i] == "formattedNumber")
            }
        ) return ImportSource.LOGGER
        if (h.take(CallExport.CSV_HEADER.size) == CallExport.CSV_HEADER) return ImportSource.PARLEY
        return ImportSource.GENERIC
    }

    /** Guesses the mapping from header names (English, case and punctuation insensitive). */
    fun guess(header: List<String>): ColumnMapping {
        val norm = header.map { it.trim().removePrefix("﻿").lowercase().replace(Regex("[^a-z0-9]"), "") }
        fun find(vararg names: String): Int? = norm.indexOfFirst { it in names }.takeIf { it >= 0 }
        fun findContains(vararg parts: String): Int? = norm.indexOfFirst { n -> parts.any { n.contains(it) } }.takeIf { it >= 0 }
        return ColumnMapping(
            timestamp = find("timestamp", "timestampms", "epoch", "epochms", "unixtime", "millis"),
            date = find("date", "datetime", "calldate", "start", "starttime", "when", "day"),
            time = find("time", "calltime", "hour"),
            number = find("number", "phonenumber", "phone", "tel", "telephone", "callernumber", "msisdn") ?: findContains("number", "phone"),
            type = find("type", "calltype", "direction", "kind") ?: findContains("type", "direction"),
            duration = find("durations", "duration", "durationsec", "durationseconds", "seconds", "length", "talktime") ?: findContains("duration"),
            name = find("name", "contact", "contactname", "cachedname", "caller"),
            sim = find("sim", "simdisplayname", "simname", "simlabel", "line"),
            accountId = find("phoneaccountid", "subscriptionid", "accountid"),
        )
    }

    fun mappingFor(source: ImportSource, header: List<String>): ColumnMapping = when (source) {
        ImportSource.LOGGER -> ColumnMapping(timestamp = 7, number = 2, type = 4, duration = 1, name = 0, sim = 6, accountId = 3)
        ImportSource.PARLEY -> ColumnMapping(timestamp = 9, date = 0, time = 1, number = 4, type = 2, duration = 5, name = 3, sim = 7)
        ImportSource.GENERIC -> guess(header)
    }

    /**
     * Dry run over [text]. [existing] holds [ImportedCall.dedupeKey]s already in the call history; rows that
     * match, or repeat an earlier row of the file, are counted as duplicates and not imported.
     * [dayFirst] decides how "03/04/2025" is read.
     */
    fun plan(
        text: String,
        existing: Set<String>,
        zone: ZoneId,
        mapping: ColumnMapping? = null,
        dayFirst: Boolean = true,
    ): ImportPlan {
        val rows = Csv.parse(text)
        if (rows.isEmpty()) return ImportPlan(ImportSource.GENERIC, emptyList(), ColumnMapping(), emptyList(), 0, listOf(RowProblem(1, RowProblemKind.EMPTY_FILE)), 0)
        val header = rows.first()
        val source = detect(header)
        val map = mapping ?: mappingFor(source, header)
        if (!map.isUsable) {
            return ImportPlan(source, header, map, emptyList(), 0, listOf(RowProblem(1, RowProblemKind.NEED_COLUMNS)), rows.size - 1)
        }
        val seen = HashSet<String>()
        val out = ArrayList<ImportedCall>()
        val problems = ArrayList<RowProblem>()
        var dupes = 0
        rows.drop(1).forEachIndexed { i, r ->
            val line = i + 2
            if (r.all { it.isBlank() }) return@forEachIndexed
            val call = try {
                parseRow(r, map, zone, dayFirst)
            } catch (e: RowProblemException) {
                problems += RowProblem(line, e.kind, e.value)
                return@forEachIndexed
            } catch (_: IllegalArgumentException) {
                problems += RowProblem(line, RowProblemKind.UNREADABLE_ROW)
                return@forEachIndexed
            }
            val key = call.dedupeKey
            if (key in existing || !seen.add(key)) dupes++ else out += call
        }
        return ImportPlan(source, header, map, out, dupes, problems, rows.size - 1)
    }

    internal fun parseRow(r: List<String>, m: ColumnMapping, zone: ZoneId, dayFirst: Boolean): ImportedCall {
        fun cell(i: Int?): String? = i?.let { r.getOrNull(it) }?.trim()?.let(::unNeutralise)?.takeIf { it.isNotEmpty() && it != "null" }
        val number = cell(m.number).orEmpty()
        val type = parseType(cell(m.type)) ?: throw RowProblemException(RowProblemKind.UNKNOWN_TYPE, cell(m.type).orEmpty())
        val date = cell(m.timestamp)?.let { parseEpoch(it) }
            ?: parseDate(cell(m.date), cell(m.time), zone, dayFirst)
            ?: throw RowProblemException(RowProblemKind.UNREADABLE_DATE, listOfNotNull(cell(m.date), cell(m.time)).joinToString(" "))
        val duration = cell(m.duration)?.let { parseDuration(it) ?: throw RowProblemException(RowProblemKind.UNREADABLE_DURATION, it) } ?: 0L
        if (number.isNotEmpty() && number.none { it.isDigit() }) throw RowProblemException(RowProblemKind.NOT_A_NUMBER, number)
        return ImportedCall(number, date, duration, type, cell(m.name), cell(m.sim), cell(m.accountId))
    }

    /** Undoes [CallExport.neutralise] so re-importing Parley's own CSV gives the original values. */
    private fun unNeutralise(v: String): String = if (v.length >= 2 && v[0] == '\'' && v[1] in "=+-@\t\r") v.substring(1) else v

    fun parseType(raw: String?): Int? {
        val v = raw?.trim()?.lowercase()?.removePrefix("calltype.")?.replace(Regex("[^a-z0-9]"), "") ?: return null
        v.toIntOrNull()?.let { return it.takeIf { n -> n in 1..7 } ?: when (it) { 9 -> 1; 10 -> 2; else -> null } }
        return when (v) {
            "incoming", "in", "received", "inbound", "answered", "wifiincoming" -> ProviderColumns.INCOMING
            "outgoing", "out", "dialed", "dialled", "outbound", "made", "wifioutgoing" -> ProviderColumns.OUTGOING
            "missed" -> ProviderColumns.MISSED
            "voicemail" -> ProviderColumns.VOICEMAIL
            "rejected", "declined" -> ProviderColumns.REJECTED
            "blocked" -> ProviderColumns.BLOCKED
            "answeredexternally", "answeredelsewhere" -> ProviderColumns.ANSWERED_EXTERNALLY
            else -> null
        }
    }

    /** Epoch millis (13+ digits) or seconds (9–11 digits). */
    fun parseEpoch(v: String): Long? {
        val n = v.trim().toLongOrNull() ?: return null
        return when {
            n >= 100_000_000_000L -> n
            n >= 100_000_000L -> n * 1000
            else -> null
        }
    }

    private val dateTimePatterns = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm")
    private val dayFirstPatterns = listOf("dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm", "dd.MM.yyyy HH:mm:ss", "dd.MM.yyyy HH:mm", "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm", "d/M/yyyy H:mm:ss", "d/M/yyyy H:mm", "d.M.yyyy H:mm:ss", "d.M.yyyy H:mm")
    private val monthFirstPatterns = listOf("MM/dd/yyyy HH:mm:ss", "MM/dd/yyyy HH:mm", "M/d/yyyy H:mm:ss", "M/d/yyyy H:mm", "M/d/yyyy h:mm:ss a", "M/d/yyyy h:mm a")

    fun parseDate(date: String?, time: String?, zone: ZoneId, dayFirst: Boolean): Long? {
        val d = date?.trim() ?: return null
        parseEpoch(d)?.let { return it }
        runCatching { return OffsetDateTime.parse(d).toInstant().toEpochMilli() }
        runCatching { return java.time.Instant.parse(d).toEpochMilli() }
        runCatching { return LocalDateTime.parse(d).atZone(zone).toInstant().toEpochMilli() }
        val full = if (time != null) "$d ${time.trim()}" else d
        val patterns = dateTimePatterns + if (dayFirst) dayFirstPatterns + monthFirstPatterns else monthFirstPatterns + dayFirstPatterns
        for (p in patterns) {
            try {
                return LocalDateTime.parse(full, DateTimeFormatter.ofPattern(p, java.util.Locale.ROOT)).atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
            }
        }
        if (time == null) {
            runCatching { return LocalDate.parse(d).atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli() }
        }
        return null
    }

    /** "75", "1:15", "0:01:15", "1h 2m 3s", "2m 5s", "45s", "75.0". */
    fun parseDuration(v: String): Long? {
        val s = v.trim().lowercase()
        if (s.isEmpty()) return 0
        s.toLongOrNull()?.let { return it.takeIf { n -> n >= 0 } }
        s.toDoubleOrNull()?.let { return if (it >= 0) it.toLong() else null }
        if (Regex("\\d+(:\\d{1,2}){1,2}").matches(s)) {
            return s.split(':').map { it.toLong() }.fold(0L) { acc, p -> acc * 60 + p }
        }
        val m = Regex("^(?:(\\d+)\\s*h)?\\s*(?:(\\d+)\\s*m(?:in)?)?\\s*(?:(\\d+)\\s*s(?:ec)?)?$").matchEntire(s) ?: return null
        if (m.groupValues.drop(1).all { it.isEmpty() }) return null
        val (h, mi, se) = m.destructured
        return (h.toLongOrNull() ?: 0) * 3600 + (mi.toLongOrNull() ?: 0) * 60 + (se.toLongOrNull() ?: 0)
    }
}
