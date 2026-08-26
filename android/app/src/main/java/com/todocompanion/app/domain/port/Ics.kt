package com.todocompanion.app.domain.port

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * CU3 — the import half of a dependency-free iCalendar (.ics) bridge (export lives in [Export]). Fully
 * offline: it parses a .ics document a phone calendar exported into VEvents the app turns into tasks —
 * the zero-permission answer to the cloud-calendar sync the big apps do. Times are read as floating
 * local, tolerant of timezone parameters and RFC5545 line folding.
 */
object Ics {
    data class VEvent(
        val uid: String,
        val summary: String,
        val start: LocalDateTime,
        val end: LocalDateTime?,   // null for all-day or point events
        val allDay: Boolean,
    )

    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATETIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** Parse the VEVENTs out of an .ics document. Tolerant of parameters (DTSTART;TZID=…:) and line folding. */
    fun parse(text: String): List<VEvent> {
        val lines = unfold(text)
        val out = ArrayList<VEvent>()
        var uid: String? = null; var summary: String? = null
        var start: LocalDateTime? = null; var end: LocalDateTime? = null; var allDay = false
        var inEvent = false
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.equals("BEGIN:VEVENT", true) -> { inEvent = true; uid = null; summary = null; start = null; end = null; allDay = false }
                line.equals("END:VEVENT", true) -> {
                    if (inEvent && summary != null && start != null) {
                        out += VEvent(uid ?: "imported-${out.size}", summary!!, start!!, end, allDay)
                    }
                    inEvent = false
                }
                inEvent -> {
                    val colon = line.indexOf(':'); if (colon < 0) continue
                    val nameAndParams = line.substring(0, colon)
                    val value = line.substring(colon + 1)
                    val name = nameAndParams.substringBefore(';').uppercase()
                    when (name) {
                        "UID" -> uid = value
                        "SUMMARY" -> summary = unescape(value)
                        "DTSTART" -> parseDateTime(nameAndParams, value)?.let { (dt, ad) -> start = dt; if (ad) allDay = true }
                        "DTEND" -> parseDateTime(nameAndParams, value)?.let { (dt, _) -> end = dt }
                    }
                }
            }
        }
        return out
    }

    private fun parseDateTime(nameAndParams: String, value: String): Pair<LocalDateTime, Boolean>? {
        val v = value.trim().removeSuffix("Z")  // treat UTC 'Z' as floating; good enough for a local bridge
        val isDate = nameAndParams.contains("VALUE=DATE", true) || (v.length == 8 && !v.contains('T'))
        return runCatching {
            if (isDate) LocalDate.parse(v, DATE).atStartOfDay() to true
            else LocalDateTime.parse(v.take(15), DATETIME) to false
        }.getOrNull()
    }

    private fun unfold(text: String): List<String> {
        // RFC5545 line folding: a line beginning with a space/tab continues the previous line.
        val raw = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val out = ArrayList<String>()
        for (l in raw) {
            if ((l.startsWith(" ") || l.startsWith("\t")) && out.isNotEmpty()) out[out.size - 1] = out.last() + l.substring(1)
            else out += l
        }
        return out
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
}
