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
/** R52 — recognise the conferencing provider from a join URL, purely by host (offline, no network). */
object MeetingLink {
    fun provider(url: String): String? {
        val u = url.lowercase()
        return when {
            "zoom.us" in u || "zoom.com" in u -> "Zoom"
            "meet.google" in u || "g.co/meet" in u -> "Google Meet"
            "teams.microsoft" in u || "teams.live" in u -> "Microsoft Teams"
            "webex.com" in u -> "Webex"
            "meet.jit.si" in u || "jitsi" in u -> "Jitsi"
            "whereby.com" in u -> "Whereby"
            "skype.com" in u -> "Skype"
            "gotomeet" in u || "gotomeeting" in u -> "GoTo Meeting"
            url.startsWith("http", true) -> "Meeting link"
            else -> null
        }
    }
    fun emoji(provider: String?): String = when (provider) {
        "Zoom" -> "🎥"; "Google Meet" -> "📹"; "Microsoft Teams" -> "👥"; "Webex" -> "🟢"; else -> "🔗"
    }
}

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
            append("UID:").append(e.uid.ifBlank { e.id }).append("\r\n")
            if (e.sequence > 0) append("SEQUENCE:").append(e.sequence).append("\r\n")
            append("SUMMARY:").append(esc(e.title)).append("\r\n")
            if (e.location.isNotBlank()) append("LOCATION:").append(esc(e.location)).append("\r\n")
            if (e.notes.isNotBlank()) append("DESCRIPTION:").append(esc(e.notes)).append("\r\n")
            if (e.url.isNotBlank()) { append("URL:").append(e.url).append("\r\n"); append("CONFERENCE;VALUE=URI;FEATURE=VIDEO:").append(e.url).append("\r\n") }
            if (e.organizer.isNotBlank()) append("ORGANIZER;CN=").append(esc(e.organizer)).append(":mailto:unknown@local\r\n")
            e.attendees.split("\n", ",").map { it.trim() }.filter { it.isNotBlank() }.forEach { append("ATTENDEE;CN=").append(esc(it)).append(":mailto:unknown@local\r\n") }
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
        // R52 — invitation fields.
        var organizer = ""; var confUrl = ""; val attendees = ArrayList<String>()
        var sequence = 0   // R53 — revision number for invite updates
        val ex = ArrayList<Long>()
        fun reset() { uid = null; summary = ""; location = ""; notes = ""; url = ""; start = null; end = null; allDay = false; rrule = ""; organizer = ""; confUrl = ""; attendees.clear(); sequence = 0; ex.clear() }
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.equals("BEGIN:VEVENT", true) -> { inEvent = true; reset() }
                line.equals("END:VEVENT", true) -> {
                    if (inEvent && summary.isNotBlank() && start != null) {
                        val s = start!!.atZone(zone).toInstant().toEpochMilli()
                        val e = (end ?: start!!.plusHours(1)).atZone(zone).toInstant().toEpochMilli()
                        // A meeting invite scatters its join link across URL / CONFERENCE / X-props / LOCATION /
                        // DESCRIPTION; take the first that looks like one so the event gets a working "Join".
                        val joinUrl = url.ifBlank { confUrl }.ifBlank { detectUrl(location) }.ifBlank { detectUrl(notes) }
                        out += EventEntity(
                            id = java.util.UUID.randomUUID().toString(), calendarId = calendarId, title = summary,
                            location = location, notes = notes, url = joinUrl, startMillis = s, endMillis = if (allDay) s else e,
                            allDay = allDay, rrule = rrule, exDates = ex.joinToString(","),
                            organizer = organizer, attendees = attendees.joinToString("\n"),
                            uid = uid ?: "", sequence = sequence,
                            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
                    }
                    inEvent = false
                }
                inEvent -> {
                    val colon = line.indexOf(':'); if (colon < 0) continue
                    val nameAndParams = line.substring(0, colon); val value = line.substring(colon + 1)
                    val name = nameAndParams.substringBefore(';').uppercase()
                    when (name) {
                        "UID" -> uid = value.trim()
                        "SEQUENCE" -> sequence = value.trim().toIntOrNull() ?: 0
                        "SUMMARY" -> summary = unesc(value)
                        "LOCATION" -> location = unesc(value)
                        "DESCRIPTION" -> notes = unesc(value)
                        "URL" -> url = value.trim()
                        "ORGANIZER" -> organizer = cnOrValue(nameAndParams, value)
                        "ATTENDEE" -> cnOrValue(nameAndParams, value).takeIf { it.isNotBlank() }?.let { attendees += it }
                        "CONFERENCE", "X-GOOGLE-CONFERENCE", "X-MICROSOFT-SKYPETEAMSMEETINGURL",
                        "X-MICROSOFT-ONLINEMEETINGCONFLINK" -> if (confUrl.isBlank()) confUrl = detectUrl(value).ifBlank { value.trim() }
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

    /** ORGANIZER;CN=Jane Doe:mailto:jane@x.com → "Jane Doe"; else the mailto address. */
    private fun cnOrValue(nameAndParams: String, value: String): String {
        val cn = nameAndParams.split(';').firstOrNull { it.startsWith("CN=", true) }?.substringAfter('=')?.trim('"', ' ')
        return (cn?.takeIf { it.isNotBlank() } ?: value.removePrefix("mailto:").removePrefix("MAILTO:")).trim()
    }
    /** First http(s) URL inside a blob (LOCATION/DESCRIPTION often embed the join link in prose). */
    fun detectUrl(s: String): String = Regex("""https?://[^\s<>"']+""").find(s)?.value?.trimEnd('.', ',', ')', '>') ?: ""

    /** R53 — the calendar-level METHOD (REQUEST / CANCEL / REPLY / PUBLISH), upper-cased, or null. */
    fun methodOf(text: String): String? = unfold(text).firstNotNullOfOrNull { raw ->
        val line = raw.trim()
        if (line.uppercase().startsWith("METHOD:")) line.substringAfter(':').trim().uppercase() else null
    }

    /** R53 — build a METHOD:REPLY .ics carrying the user's RSVP as PARTSTAT, for them to send by hand
     *  (a purely-local app has no transport to reply to the organizer directly). [rsvp] is yes/maybe/no. */
    fun exportReply(e: EventEntity, rsvp: String, attendeeName: String = "Me", zone: ZoneId = ZoneId.systemDefault()): String {
        val partstat = when (rsvp.lowercase()) { "yes" -> "ACCEPTED"; "maybe" -> "TENTATIVE"; "no" -> "DECLINED"; else -> "NEEDS-ACTION" }
        return buildString {
            append("BEGIN:VCALENDAR\r\n").append("VERSION:2.0\r\n").append("PRODID:-//ToDoCompanion//Calendar//EN\r\n")
            append("METHOD:REPLY\r\n").append("BEGIN:VEVENT\r\n")
            append("UID:").append(e.uid.ifBlank { e.id }).append("\r\n")
            if (e.sequence > 0) append("SEQUENCE:").append(e.sequence).append("\r\n")
            append("SUMMARY:").append(esc(e.title)).append("\r\n")
            if (e.organizer.isNotBlank()) append("ORGANIZER;CN=").append(esc(e.organizer)).append(":mailto:unknown@local\r\n")
            append("ATTENDEE;CN=").append(esc(attendeeName)).append(";PARTSTAT=").append(partstat).append(":mailto:me@local\r\n")
            append("DTSTART:").append(Instant.ofEpochMilli(e.startMillis).atZone(zone).toLocalDateTime().format(DT)).append("\r\n")
            append("END:VEVENT\r\n").append("END:VCALENDAR\r\n")
        }
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
