package com.todocompanion.app.data.sync

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Reads habit check-ins from a CSV — our own long-format export, or **Loop Habit Tracker's**
 * "Checkmarks" wide export (a Date column followed by one column per habit). Pure parsing, no I/O;
 * the repository maps names to habits and writes check-ins. Fully offline.
 */
object HabitImporter {
    data class Row(val habit: String, val epochDay: Long, val count: Int, val status: String)
    data class Result(val rows: List<Row>, val source: String)

    private val DATE_FORMATS = listOf("yyyy-MM-dd", "yyyy/MM/dd", "dd-MM-yyyy", "MM/dd/yyyy").map { DateTimeFormatter.ofPattern(it) }

    private fun parseDate(s: String): Long? {
        val t = s.trim()
        for (f in DATE_FORMATS) runCatching { return LocalDate.parse(t, f).toEpochDay() }
        return runCatching { LocalDate.parse(t).toEpochDay() }.getOrNull()
    }

    /** Minimal RFC-4180-ish splitter: handles quoted fields with embedded commas and "" escapes. */
    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                c == ',' && !q -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    fun parse(text: String): Result? {
        val lines = text.split('\n', '\r').map { it }.filter { it.isNotBlank() }
        if (lines.size < 2) return null
        val header = splitCsv(lines.first()).map { it.trim() }
        val lower = header.map { it.lowercase() }

        // Our long format: Habit,…,Date,Value,Status
        if ("habit" in lower && "date" in lower && "value" in lower) {
            val hi = lower.indexOf("habit"); val di = lower.indexOf("date"); val vi = lower.indexOf("value")
            val si = lower.indexOf("status")
            val rows = lines.drop(1).mapNotNull { ln ->
                val f = splitCsv(ln)
                val name = f.getOrNull(hi)?.trim().orEmpty(); if (name.isBlank()) return@mapNotNull null
                val day = f.getOrNull(di)?.let { parseDate(it) } ?: return@mapNotNull null
                val v = f.getOrNull(vi)?.trim()?.toDoubleOrNull()?.toInt() ?: 0
                val st = f.getOrNull(si)?.trim()?.ifBlank { null } ?: "done"
                Row(name, day, v, if (st == "skip") "skip" else "done")
            }
            return if (rows.isEmpty()) null else Result(rows, "CSV")
        }

        // Loop Checkmarks wide format: first column "Date", the rest are habit names.
        if (lower.firstOrNull() == "date") {
            val names = header.drop(1)
            val rows = ArrayList<Row>()
            lines.drop(1).forEach { ln ->
                val f = splitCsv(ln)
                val day = f.getOrNull(0)?.let { parseDate(it) } ?: return@forEach
                names.forEachIndexed { idx, nm ->
                    val raw = f.getOrNull(idx + 1)?.trim().orEmpty()
                    val v = raw.toDoubleOrNull() ?: return@forEachIndexed
                    if (v > 0) rows.add(Row(nm.trim(), day, v.toInt().coerceAtLeast(1), "done"))
                }
            }
            return if (rows.isEmpty()) null else Result(rows, "Loop")
        }
        return null
    }
}
