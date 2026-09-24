package app.parley.common.history

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.PhoneNumbers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One call as it is exported. Durations are always the stored ones: billing rounding never touches them. */
data class ExportRow(
    val date: Long,
    val durationSec: Long,
    val type: CallType,
    val number: String,
    /** Contact or cached name; null (an empty cell) when unknown. */
    val name: String?,
    val simLabel: String?,
    val notes: List<String> = emptyList(),
)

/** A call note as stored: the number's match key and when it was written (call connect time). */
data class ExportNote(val numberKey: String, val time: Long, val text: String)

enum class ExportFormat(val extension: String, val mime: String, val label: String) {
    CSV("csv", "text/csv", "CSV (spreadsheet)"),
    JSON("json", "application/json", "JSON"),
    ICS("ics", "text/calendar", "Calendar (.ics)"),
    PDF("pdf", "application/pdf", "PDF"),
}

object CallExport {
    fun typeLabel(t: CallType): String = when (t) {
        CallType.INCOMING -> "Incoming"
        CallType.OUTGOING -> "Outgoing"
        CallType.MISSED -> "Missed"
        CallType.REJECTED -> "Rejected"
        CallType.BLOCKED -> "Blocked"
        CallType.VOICEMAIL -> "Voicemail"
        CallType.ANSWERED_EXTERNALLY -> "Answered elsewhere"
        CallType.UNKNOWN -> "Unknown type"
    }

    /**
     * Attaches notes to calls: a note belongs to a call on the same number written between one minute before
     * the call started and ten minutes after it ended (notes are stamped with the connect time).
     */
    fun rows(calls: List<CallEntry>, names: (CallEntry) -> String?, simLabel: (String?) -> String?, notes: List<ExportNote>): List<ExportRow> {
        val byKey = notes.groupBy { it.numberKey }
        return calls.map { e ->
            val mine = byKey[PhoneNumbers.matchKey(e.number)].orEmpty()
                .filter { it.time >= e.date - 60_000 && it.time <= e.date + e.durationSec * 1000 + 600_000 }
                .sortedBy { it.time }.map { it.text }
            ExportRow(e.date, e.durationSec, e.type, if (e.presentationHidden) "" else e.number, names(e), simLabel(e.accountId), mine)
        }
    }

    // ------------------------------------------------------------------ CSV (RFC 4180)

    val CSV_HEADER = listOf("Date", "Time", "Type", "Name", "Number", "Duration (s)", "Duration", "SIM", "Notes", "Timestamp (ms)")

    /**
     * RFC 4180 CSV: CRLF line endings, every field that contains a comma, quote, CR or LF quoted with doubled
     * quotes, and formula-looking fields (starting with `=`, `+`, `-`, `@`, tab or CR) neutralised with a
     * leading apostrophe so spreadsheets never evaluate them. [bom] prepends a UTF-8 byte-order mark for Excel.
     */
    fun csv(rows: List<ExportRow>, zone: ZoneId, bom: Boolean = false): String {
        val sb = StringBuilder()
        if (bom) sb.append('﻿')
        csvLine(sb, CSV_HEADER)
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val time = DateTimeFormatter.ofPattern("HH:mm:ss")
        for (r in rows) {
            val t = Instant.ofEpochMilli(r.date).atZone(zone)
            csvLine(
                sb,
                listOf(
                    date.format(t), time.format(t), typeLabel(r.type), r.name.orEmpty(), r.number,
                    r.durationSec.toString(), hms(r.durationSec), r.simLabel.orEmpty(), r.notes.joinToString("\n"), r.date.toString(),
                ),
            )
        }
        return sb.toString()
    }

    private fun csvLine(sb: StringBuilder, fields: List<String>) {
        fields.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append(csvField(f))
        }
        sb.append("\r\n")
    }

    /** One escaped, neutralised CSV field. */
    fun csvField(raw: String): String {
        val v = neutralise(raw)
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    /** Prefixes an apostrophe to values a spreadsheet would treat as a formula (OWASP CSV injection). */
    fun neutralise(v: String): String = if (v.isNotEmpty() && v[0] in FORMULA_START) "'$v" else v

    private val FORMULA_START = charArrayOf('=', '+', '-', '@', '\t', '\r')

    fun hms(sec: Long): String {
        val s = sec.coerceAtLeast(0)
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    // ------------------------------------------------------------------ JSON

    private val prettyJson = Json { prettyPrint = true }

    fun json(rows: List<ExportRow>, zone: ZoneId): String {
        val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val arr = buildJsonArray {
            rows.forEach { r ->
                add(
                    buildJsonObject {
                        put("date", iso.format(Instant.ofEpochMilli(r.date).atZone(zone)))
                        put("timestamp", r.date)
                        put("type", r.type.name.lowercase())
                        put("name", r.name?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("number", r.number)
                        put("durationSec", r.durationSec)
                        put("sim", r.simLabel?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("notes", buildJsonArray { r.notes.forEach { add(JsonPrimitive(it)) } })
                    },
                )
            }
        }
        return prettyJson.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), arr) + "\n"
    }

    // ------------------------------------------------------------------ ICS (RFC 5545)

    /** One VEVENT per call. Text escaped per RFC 5545 §3.3.11, lines folded at 75 octets, CRLF endings. */
    fun ics(rows: List<ExportRow>, now: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder()
        fun line(s: String) {
            sb.append(fold(s)).append("\r\n")
        }
        val utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:-//Parley//Call history//EN")
        line("CALSCALE:GREGORIAN")
        line("METHOD:PUBLISH")
        for (r in rows) {
            val who = r.name?.takeIf { it.isNotBlank() } ?: r.number.ifBlank { "private number" }
            val summary = when (r.type) {
                CallType.OUTGOING -> "Call to $who"
                CallType.MISSED -> "Missed call from $who"
                CallType.REJECTED -> "Rejected call from $who"
                CallType.BLOCKED -> "Blocked call from $who"
                CallType.VOICEMAIL -> "Voicemail from $who"
                else -> "Call from $who"
            }
            val desc = buildList {
                if (r.number.isNotBlank()) add("Number: ${r.number}")
                add("Type: ${typeLabel(r.type)}")
                add("Duration: ${hms(r.durationSec)}")
                r.simLabel?.let { add("SIM: $it") }
                r.notes.forEach { add("Note: $it") }
            }.joinToString("\n")
            line("BEGIN:VEVENT")
            line("UID:" + uid(r))
            line("DTSTAMP:" + utc.format(Instant.ofEpochMilli(now)))
            line("DTSTART:" + utc.format(Instant.ofEpochMilli(r.date)))
            // A call without talk time is an instant: DTSTART only means it ends when it starts.
            if (r.durationSec > 0) line("DURATION:PT${r.durationSec}S")
            line("SUMMARY:" + icsText(summary))
            line("DESCRIPTION:" + icsText(desc))
            line("CATEGORIES:" + icsText(typeLabel(r.type)))
            line("TRANSP:TRANSPARENT")
            line("END:VEVENT")
        }
        line("END:VCALENDAR")
        return sb.toString()
    }

    /** RFC 5545 TEXT escaping: backslash, semicolon, comma and newlines. */
    fun icsText(s: String): String = buildString(s.length + 8) {
        var i = 0
        while (i < s.length) {
            when (val c = s[i]) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\r' -> { append("\\n"); if (i + 1 < s.length && s[i + 1] == '\n') i++ }
                '\n' -> append("\\n")
                else -> if (c < ' ' && c != '\t') Unit else append(c)
            }
            i++
        }
    }

    /**
     * Folds a content line so no physical line exceeds 75 octets of UTF-8, never splitting a character
     * (surrogate pairs stay together). Continuation lines start with one space, which counts toward 75.
     */
    fun fold(line: String): String {
        val out = StringBuilder(line.length + 8)
        var used = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val chars = Character.charCount(cp)
            val bytes = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (used + bytes > 75) {
                out.append("\r\n ")
                used = 1
            }
            out.appendCodePoint(cp)
            used += bytes
            i += chars
        }
        return out.toString()
    }

    private fun uid(r: ExportRow): String {
        val md = MessageDigest.getInstance("SHA-256").digest("${r.date}|${r.number}|${r.type}|${r.durationSec}".toByteArray())
        return md.take(12).joinToString("") { "%02x".format(it) } + "@parley.call"
    }

    // ------------------------------------------------------------------ file names

    /**
     * A safe file name: no path separators, colons or other characters that are reserved on common file
     * systems, no control characters, no leading/trailing dots or spaces, at most [max] characters.
     */
    fun sanitiseFileName(raw: String, max: Int = 80): String {
        val cleaned = buildString {
            for (c in raw) {
                append(
                    when {
                        c.code < 0x20 || c == '\u007F' -> ' '
                        c in "/\\:*?\"<>|" -> '-'
                        else -> c
                    },
                )
            }
        }.replace(Regex("\\s+"), " ").replace(Regex("-{2,}"), "-").trim(' ', '.', '-')
        val cut = if (cleaned.length > max) cleaned.substring(0, max).trimEnd(' ', '.', '-') else cleaned
        return cut.ifEmpty { "calls" }
    }

    /** "Parley calls – Anna – 2026-09-24 14-05.csv" (times use '-' so the name is valid everywhere). */
    fun fileName(subject: String?, now: Long, zone: ZoneId, format: ExportFormat): String {
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm").format(Instant.ofEpochMilli(now).atZone(zone))
        val base = listOfNotNull("Parley calls", subject?.takeIf { it.isNotBlank() }, stamp).joinToString(" – ")
        return sanitiseFileName(base) + "." + format.extension
    }
}
