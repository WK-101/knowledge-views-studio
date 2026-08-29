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
            trimmed.contains("<opml", ignoreCase = true) -> Result("MLO", parseOpml(text))
            // MLO's native document/backup XML — task titles live in Caption attributes/elements.
            isMloXml(text) -> Result("MLO", parseMloXml(text)).takeIf { it.rows.isNotEmpty() }
            // MLO `.mlobak` also ships as a ZIP of multi-section CSV (no XML at all): detect the task
            // section by MLO's distinctive columns and pull the titles. This is the format that made a
            // shared .mlobak fail with "not a valid backup" (R23).
            isMloCsv(text) -> Result("MLO", parseMloCsv(text)).takeIf { it.rows.isNotEmpty() }
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

    private fun isMloXml(text: String): Boolean =
        text.contains("MyLifeOrganized", ignoreCase = true) ||
            text.contains("<TaskNode", ignoreCase = true) ||
            text.contains("<MLO", ignoreCase = true) ||
            Regex("\\bCaption\\s*=\\s*\"", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** MLO's exported task table (CSV, inside a .mlobak) carries a dual importance/urgency model and a
     *  Caption/ParentTaskId shape no other tool exports — a reliable fingerprint that won't match a
     *  Todoist/TickTick CSV or our own JSON. */
    private fun isMloCsv(text: String): Boolean {
        if (text.trimStart().startsWith("<") || text.trimStart().startsWith("{")) return false
        return text.lineSequence().take(300).any { line ->
            val u = line.lowercase()
            u.contains("caption") || u.contains("parenttaskid") || (u.contains("importance") && u.contains("urgency"))
        }
    }

    /**
     * Turn the raw bytes of an export/backup into parseable text. MLO's `.mlobak` (and some `.ml`
     * documents) are ZIP archives wrapping the XML document, or UTF-16 XML — reading them as UTF-8 text
     * yields garbage, which is why "import → merge" failed. We unzip when we see a ZIP header, then fall
     * back to UTF-16 if UTF-8 decoding produces replacement characters. Returns null only for bytes we
     * genuinely can't turn into text (a truly binary, proprietary blob).
     */
    fun bytesToText(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        // ZIP? "PK\x03\x04" — pull the text of every entry that looks like XML/CSV and concatenate.
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            return runCatching {
                java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
                    val sb = StringBuilder()
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val content = decodeText(zis.readBytes())
                            if (content.contains('<') || content.contains(',')) sb.append(content).append('\n')
                        }
                        e = zis.nextEntry
                    }
                    sb.toString().ifBlank { null }
                }
            }.getOrNull()
        }
        return decodeText(bytes)
    }

    /** Decode bytes as UTF-8, retrying as UTF-16 (BOM or many replacement chars) — MLO XML is often UTF-16. */
    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())))
            return runCatching { String(bytes, Charsets.UTF_16) }.getOrDefault(String(bytes, Charsets.UTF_8))
        val utf8 = String(bytes, Charsets.UTF_8)
        if (utf8.count { it == '\uFFFD' } > utf8.length / 20 + 1)
            return runCatching { String(bytes, Charsets.UTF_16) }.getOrDefault(utf8)
        return utf8
    }

    private fun unescapeXml(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    // ---------- MLO native XML (.mlobak / .ml document) ----------
    private fun parseMloXml(text: String): List<Imported> {
        val out = ArrayList<Imported>()
        val seen = LinkedHashSet<String>()
        // Titles live in Caption (native XML) or text/Title (mixed exports) — restrict to those keys so we
        // don't scoop up every attribute. Also accept <Caption>…</Caption> element form.
        val attrRe = Regex("\\b(?:Caption|Title|text)\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        val elemRe = Regex("<Caption[^>]*>([^<]*)</Caption>", RegexOption.IGNORE_CASE)
        (attrRe.findAll(text).map { it.groupValues[1] } + elemRe.findAll(text).map { it.groupValues[1] }).forEach { raw ->
            val title = unescapeXml(raw.trim())
            if (title.isNotBlank() && title.length in 1..500 && seen.add(title)) out.add(Imported(list = "MLO import", title = title))
        }
        return out
    }

    // ---------- MLO multi-section CSV (inside a .mlobak) ----------
    private val MLO_TITLE_COLS = setOf("caption", "text", "title", "name", "task", "subject")
    private val MLO_SECTION_COLS = setOf("completed", "importance", "urgency", "parenttaskid", "duedatetime", "id", "flag")
    private fun parseMloCsv(text: String): List<Imported> {
        val rows = csvRows(text)
        val out = ArrayList<Imported>()
        val seen = LinkedHashSet<String>()
        var titleIdx = -1
        var completedIdx = -1
        for (r in rows) {
            val lower = r.map { it.trim().lowercase() }
            // A header row for the task section: a title column plus at least one MLO-specific column.
            val hIdx = lower.indexOfFirst { it in MLO_TITLE_COLS }
            if (hIdx >= 0 && lower.any { it in MLO_SECTION_COLS }) {
                titleIdx = hIdx
                completedIdx = lower.indexOfFirst { it == "completed" || it == "done" }
                continue
            }
            if (titleIdx in r.indices) {
                val title = r[titleIdx].trim()
                if (title.isNotBlank() && title.length in 1..500 && title.lowercase() !in MLO_TITLE_COLS && seen.add(title)) {
                    val done = completedIdx in r.indices &&
                        r[completedIdx].trim().let { it == "1" || it.equals("true", true) || it.equals("yes", true) }
                    out.add(Imported(list = "MLO import", title = title, completed = done))
                }
            }
        }
        return out
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
