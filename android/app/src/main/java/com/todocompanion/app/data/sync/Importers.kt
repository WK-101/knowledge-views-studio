package com.todocompanion.app.data.sync

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * On-device importers for the incumbents' exports — Todoist CSV, TickTick CSV, and MLO OPML.
 * Everything is parsed locally; nothing is uploaded. Produces a flat list of [Imported] rows the
 * repository turns into lists + tasks.
 */
object Importers {

    data class Imported(
        val list: String,
        val title: String,
        val note: String = "",
        val importance: Int = 3,
        val urgency: Int = 3,
        val dueMillis: Long? = null,
        val completed: Boolean = false,
        val tags: List<String> = emptyList(),
    )

    data class Result(val source: String, val rows: List<Imported>)

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): Result? {
        val trimmed = text.trimStart()
        return when {
            trimmed.startsWith("<") && trimmed.contains("<opml", ignoreCase = true) -> Result("MLO", parseOpml(text))
            else -> {
                val header = firstDataLine(text) ?: return null
                when {
                    header.contains("List Name") && header.contains("Title") -> Result("TickTick", parseTickTick(text, zone))
                    header.startsWith("TYPE", ignoreCase = true) || (header.contains("CONTENT") && header.contains("PRIORITY")) -> Result("Todoist", parseTodoist(text, zone))
                    else -> null
                }
            }
        }
    }

    // ---------- CSV core ----------
    /** Split CSV text into rows of fields, honoring quotes, doubled quotes, and embedded newlines. */
    private fun csvRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var field = StringBuilder()
        var row = ArrayList<String>()
        var inQuotes = false
        var i = 0
        val s = text.replace("\r\n", "\n").replace('\r', '\n')
        while (i < s.length) {
            val c = s[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < s.length && s[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> { row.add(field.toString()); field = StringBuilder() }
                c == '\n' -> { row.add(field.toString()); rows.add(row); row = ArrayList(); field = StringBuilder() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows.filter { it.any { f -> f.isNotBlank() } }
    }

    private fun firstDataLine(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }

    private fun parseDate(raw: String, zone: ZoneId): Long? {
        val v = raw.trim().ifBlank { return null }
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm",
        )
        for (p in patterns) runCatching {
            return LocalDateTime.parse(v, DateTimeFormatter.ofPattern(p)).atZone(zone).toInstant().toEpochMilli()
        }
        runCatching { return LocalDate.parse(v).atStartOfDay(zone).toInstant().toEpochMilli() }
        // Todoist free-form dates ("2024-08-25") already covered; give up gracefully otherwise.
        return null
    }

    // ---------- TickTick ----------
    // Header: Folder Name,List Name,Title,Kind,Tags,Content,Is Check list,Start Date,Due Date,Reminder,Repeat,Priority,Status,...
    private fun parseTickTick(text: String, zone: ZoneId): List<Imported> {
        val rows = csvRows(text)
        val headerIdx = rows.indexOfFirst { it.contains("Title") && it.contains("List Name") }
        if (headerIdx < 0) return emptyList()
        val h = rows[headerIdx].map { it.trim() }
        fun col(name: String) = h.indexOfFirst { it.equals(name, true) }
        val cList = col("List Name"); val cTitle = col("Title"); val cTags = col("Tags"); val cContent = col("Content")
        val cDue = col("Due Date"); val cPri = col("Priority"); val cStatus = col("Status")
        val out = ArrayList<Imported>()
        rows.drop(headerIdx + 1).forEach { r ->
            fun at(idx: Int) = if (idx in r.indices) r[idx].trim() else ""
            val title = at(cTitle); if (title.isBlank()) return@forEach
            val pri = at(cPri).toIntOrNull() ?: 0
            val imp = when { pri >= 5 -> 5; pri >= 3 -> 4; pri >= 1 -> 3; else -> 3 }
            out.add(Imported(
                list = at(cList).ifBlank { "Imported" }, title = title, note = at(cContent),
                importance = imp, urgency = imp,
                dueMillis = parseDate(at(cDue), zone),
                completed = at(cStatus) == "2",
                tags = at(cTags).split(",", ";").map { it.trim() }.filter { it.isNotBlank() },
            ))
        }
        return out
    }

    // ---------- Todoist ----------
    // Header: TYPE,CONTENT,DESCRIPTION,PRIORITY,INDENT,AUTHOR,RESPONSIBLE,DATE,...
    private fun parseTodoist(text: String, zone: ZoneId): List<Imported> {
        val rows = csvRows(text)
        val headerIdx = rows.indexOfFirst { it.any { c -> c.equals("CONTENT", true) } }
        if (headerIdx < 0) return emptyList()
        val h = rows[headerIdx].map { it.trim() }
        fun col(name: String) = h.indexOfFirst { it.equals(name, true) }
        val cType = col("TYPE"); val cContent = col("CONTENT"); val cDesc = col("DESCRIPTION")
        val cPri = col("PRIORITY"); val cDate = col("DATE")
        var section = "Imported"
        val out = ArrayList<Imported>()
        rows.drop(headerIdx + 1).forEach { r ->
            fun at(idx: Int) = if (idx in r.indices) r[idx].trim() else ""
            val type = at(cType).lowercase()
            val content = at(cContent)
            if (type == "section") { if (content.isNotBlank()) section = content; return@forEach }
            if (type != "task" && type.isNotBlank()) return@forEach
            if (content.isBlank()) return@forEach
            // Todoist export priority: 4=P1 (highest) … 1=P4.
            val pri = at(cPri).toIntOrNull() ?: 1
            val imp = when (pri) { 4 -> 5; 3 -> 4; 2 -> 3; else -> 3 }
            out.add(Imported(
                list = section, title = content, note = at(cDesc),
                importance = imp, urgency = imp, dueMillis = parseDate(at(cDate), zone),
            ))
        }
        return out
    }

    // ---------- MLO OPML ----------
    private fun parseOpml(text: String): List<Imported> {
        // Lightweight: each <outline text="..."> becomes a task; nested outlines flatten into one list.
        val out = ArrayList<Imported>()
        val re = Regex("<outline\\b[^>]*\\btext=\"([^\"]*)\"[^>]*>", RegexOption.IGNORE_CASE)
        re.findAll(text).forEach { m ->
            val title = m.groupValues[1].trim()
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            if (title.isNotBlank()) out.add(Imported(list = "MLO import", title = title))
        }
        return out
    }
}
