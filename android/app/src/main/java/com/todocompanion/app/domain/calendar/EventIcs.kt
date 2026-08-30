package com.todocompanion.app.domain.calendar

import com.todocompanion.app.data.entity.EventCalendarEntity
import com.todocompanion.app.data.entity.EventEntity
import com.todocompanion.app.domain.recurrence.Freq
import com.todocompanion.app.domain.recurrence.Recur
import com.todocompanion.app.domain.recurrence.Recurrence
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * R38 — the privacy-safe interop bridge: read and write local .ics files (RFC-5545 VEVENT), with
 * LOCATION, DESCRIPTION and RRULE. No network, no calendar-provider — the only way events cross the app
 * boundary. Recurrence maps to/from the app's compact rule (domain/recurrence/Recurrence.kt).
 */
object EventIcs {

    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val ISO_BYDAY = mapOf(1 to "MO", 2 to "TU", 3 to "WE", 4 to "TH", 5 to "FR", 6 to "SA", 7 to "SU")
    private val BYDAY_ISO = ISO_BYDAY.entries.associate { it.value to it.key }

    // ── export ──────────────────────────────────────────────────────────────────────────────────
    fun export(events: List<EventEntity>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append("BEGIN:VCALENDAR\r\n").append("VERSION:2.0\r\n").append("PRODID:-//ToDoCompanion//Calendar//EN\r\n").append("CALSCALE:GREGORIAN\r\n")
        events.filter { it.recurrenceParentId == null }.forEach { e ->
            append("BEGIN:VEVENT\r\n")
            append("UID:").append(e.id).append("\r\n")
            append("SUMMARY:").append(esc(e.title)).append("\r\n")
            if (e.location.isNotBlank()) append("LOCATION:").append(esc(e.location)).append("\r\n")
            if (e.notes.isNotBlank()) append("DESCRIPTION:").append(esc(e.notes)).append("\r\n")
            if (e.url.isNotBlank()) append("URL:").append(e.url).append("\r\n")
            if (e.allDay) {
                val d = Instant.ofEpochMilli(e.startMillis).atZone(zone).toLocalDate()
                append("DTSTART;VALUE=DATE:").append(d.format(DATE)).append("\r\n")
                append("DTEND;VALUE=DATE:").append(d.plusDays(1).format(DATE)).append("\r\n")
            } else {
                append("DTSTART:").append(Instant.ofEpochMilli(e.startMillis).atZone(zone).toLocalDateTime().format(DT)).append("\r\n")
                append("DTEND:").append(Instant.ofEpochMilli(e.endMillis).atZone(zone).toLocalDateTime().format(DT)).append("\r\n")
            }
            rruleToRfc(e.rrule, zone)?.let { append("RRULE:").append(it).append("\r\n") }
            e.exDates.split(",").mapNotNull { it.trim().toLongOrNull() }.forEach { day ->
                append("EXDATE;VALUE=DATE:").append(LocalDate.ofEpochDay(day).format(DATE)).append("\r\n")
            }
            append("END:VEVENT\r\n")
        }
        append("END:VCALENDAR\r\n")
    }

    private fun rruleToRfc(rule: String, zone: ZoneId): String? {
        val r = Recurrence.parse(rule) ?: return null
        val sb = StringBuilder()
        when (r.freq) {
            Freq.DAILY -> sb.append("FREQ=DAILY")
            Freq.WEEKLY -> { sb.append("FREQ=WEEKLY"); if (r.byDays.isNotEmpty()) sb.append(";BYDAY=").append(r.byDays.sorted().joinToString(",") { ISO_BYDAY.getValue(it) }) }
            Freq.WEEKDAYS -> sb.append("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")
            Freq.MONTHLY -> sb.append("FREQ=MONTHLY")
            Freq.YEARLY -> sb.append("FREQ=YEARLY")
        }
        if (r.interval > 1) sb.append(";INTERVAL=").append(r.interval)
        r.count?.let { sb.append(";COUNT=").append(it) }
        r.untilEpochDay?.let { sb.append(";UNTIL=").append(LocalDate.ofEpochDay(it).format(DATE)).append("T000000Z") }
        return sb.toString()
    }

    // ── import ──────────────────────────────────────────────────────────────────────────────────
    fun import(text: String, calendarId: String, zone: ZoneId = ZoneId.systemDefault()): List<EventEntity> {
        val lines = unfold(text)
        val out = ArrayList<EventEntity>()
        var inEvent = false
        var uid: String? = null; var summary = ""; var location = ""; var notes = ""; var url = ""
        var start: LocalDateTime? = null; var end: LocalDateTime? = null; var allDay = false; var rrule = ""
        val ex = ArrayList<Long>()
        fun reset() { uid = null; summary = ""; location = ""; notes = ""; url = ""; start = null; end = null; allDay = false; rrule = ""; ex.clear() }
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.equals("BEGIN:VEVENT", true) -> { inEvent = true; reset() }
                line.equals("END:VEVENT", true) -> {
                    if (inEvent && summary.isNotBlank() && start != null) {
                        val s = start!!.atZone(zone).toInstant().toEpochMilli()
                        val e = (end ?: start!!.plusHours(1)).atZone(zone).toInstant().toEpochMilli()
                        out += EventEntity(
                            id = java.util.UUID.randomUUID().toString(), calendarId = calendarId, title = summary,
                            location = location, notes = notes, url = url, startMillis = s, endMillis = if (allDay) s else e,
                            allDay = allDay, rrule = rrule, exDates = ex.joinToString(","),
                            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
                    }
                    inEvent = false
                }
                inEvent -> {
                    val colon = line.indexOf(':'); if (colon < 0) continue
                    val nameAndParams = line.substring(0, colon); val value = line.substring(colon + 1)
                    val name = nameAndParams.substringBefore(';').uppercase()
                    when (name) {
                        "UID" -> uid = value
                        "SUMMARY" -> summary = unesc(value)
                        "LOCATION" -> location = unesc(value)
                        "DESCRIPTION" -> notes = unesc(value)
                        "URL" -> url = value.trim()
                        "DTSTART" -> parseDt(nameAndParams, value)?.let { (dt, ad) -> start = dt; if (ad) allDay = true }
                        "DTEND" -> parseDt(nameAndParams, value)?.let { (dt, _) -> end = dt }
                        "RRULE" -> rrule = rfcToRule(value)
                        "EXDATE" -> parseDt(nameAndParams, value)?.let { (dt, _) -> ex += dt.toLocalDate().toEpochDay() }
                    }
                }
            }
        }
        return out
    }

    private fun rfcToRule(value: String): String {
        val parts = value.split(";").mapNotNull { val kv = it.split("="); if (kv.size == 2) kv[0].uppercase() to kv[1] else null }.toMap()
        val freqRaw = parts["FREQ"]?.uppercase() ?: return ""
        val byday = parts["BYDAY"]?.split(",")?.mapNotNull { BYDAY_ISO[it.takeLast(2).uppercase()] }?.toSet() ?: emptySet()
        val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
        val count = parts["COUNT"]?.toIntOrNull()
        val until = parts["UNTIL"]?.take(8)?.let { runCatching { LocalDate.parse(it, DATE).toEpochDay() }.getOrNull() }
        val weekdaySet = setOf(1, 2, 3, 4, 5)
        val freq = when (freqRaw) {
            "DAILY" -> Freq.DAILY
            "WEEKLY" -> if (byday == weekdaySet) Freq.WEEKDAYS else Freq.WEEKLY
            "MONTHLY" -> Freq.MONTHLY
            "YEARLY" -> Freq.YEARLY
            else -> Freq.WEEKLY
        }
        return Recurrence.encode(Recur(freq, interval, if (freq == Freq.WEEKLY) byday else emptySet(), until, count))
    }

    private fun parseDt(nameAndParams: String, value: String): Pair<LocalDateTime, Boolean>? {
        val v = value.trim().removeSuffix("Z")
        val isDate = nameAndParams.contains("VALUE=DATE", true) || (v.length == 8 && !v.contains('T'))
        return runCatching {
            if (isDate) LocalDate.parse(v, DATE).atStartOfDay() to true
            else LocalDateTime.parse(v.take(15), DT) to false
        }.getOrNull()
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
    private fun unesc(s: String) = s.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private fun unfold(text: String): List<String> {
        val raw = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val out = ArrayList<String>()
        for (l in raw) { if ((l.startsWith(" ") || l.startsWith("\t")) && out.isNotEmpty()) out[out.size - 1] = out.last() + l.substring(1) else out += l }
        return out
    }
}
