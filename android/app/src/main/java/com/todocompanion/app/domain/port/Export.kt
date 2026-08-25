package com.todocompanion.app.domain.port

import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.TagEntity
import com.todocompanion.app.data.entity.TaskEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Human-readable exports (Markdown outline + CSV) — a lossy, portable companion to the lossless
 * JSON backup. Pure formatting, no I/O; the ViewModel writes the returned string to a file.
 */
object Export {

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun day(millis: Long?, zone: ZoneId): String =
        millis?.let { Instant.ofEpochMilli(it).atZone(zone).format(DATE) } ?: ""

    private fun stamp(millis: Long?, zone: ZoneId): String =
        millis?.let { Instant.ofEpochMilli(it).atZone(zone).format(DATETIME) } ?: ""

    private fun priorityLabel(t: TaskEntity): String {
        val m = maxOf(t.importance, t.urgency)
        return when { m >= 5 -> "High"; m >= 4 -> "Medium"; m >= 3 -> "Low"; else -> "" }
    }

    /**
     * Nested Markdown: each list becomes a heading; tasks are checkbox bullets, indented by
     * their parent/child hierarchy, with due date, priority and tags as inline suffixes.
     */
    fun toMarkdown(
        tasks: List<TaskEntity>,
        lists: List<ListEntity>,
        tags: List<TagEntity>,
        taskTagPairs: List<Pair<String, String>>,   // taskId to tagId
        includeCompleted: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val tagName = tags.associate { it.id to it.name }
        val tagsByTask = taskTagPairs.groupBy({ it.first }, { it.second })
        val visible = tasks.filter { !it.trashed && (includeCompleted || (!it.completed && !it.abandoned)) }
        val byParent = visible.groupBy { it.parentId }
        val sb = StringBuilder()
        sb.append("# ToDo Companion export\n\n")
        sb.append("_Exported ").append(stamp(System.currentTimeMillis(), zone)).append("_\n\n")

        val listOrder = (lists.sortedBy { it.sortOrder })
        // A task belongs under its list; children are printed under their parent regardless of list.
        fun bullet(t: TaskEntity, depth: Int) {
            val box = if (t.completed) "[x]" else "[ ]"
            val bits = buildList {
                day(t.dueDate, zone).takeIf { it.isNotEmpty() }?.let { add("📅 $it") }
                day(t.deadlineDate, zone).takeIf { it.isNotEmpty() }?.let { add("⏳ $it") }
                priorityLabel(t).takeIf { it.isNotEmpty() }?.let { add("‼️ $it") }
                tagsByTask[t.id]?.mapNotNull { tagName[it] }?.forEach { add("#$it") }
            }
            sb.append("  ".repeat(depth)).append("- ").append(box).append(' ').append(t.title.ifBlank { "Untitled" })
            if (bits.isNotEmpty()) sb.append("  ").append(bits.joinToString("  "))
            sb.append('\n')
            if (t.note.isNotBlank()) t.note.lines().forEach { sb.append("  ".repeat(depth + 1)).append("> ").append(it).append('\n') }
            byParent[t.id]?.sortedBy { it.sortOrder }?.forEach { bullet(it, depth + 1) }
        }

        listOrder.forEach { l ->
            val roots = byParent[null]?.filter { it.listId == l.id }?.sortedBy { it.sortOrder }.orEmpty()
            if (roots.isEmpty()) return@forEach
            sb.append("\n## ").append(l.name).append('\n')
            roots.forEach { bullet(it, 0) }
        }
        return sb.toString()
    }

    /** Flat CSV with a header row. Quotes fields that contain commas, quotes or newlines. */
    fun toCsv(
        tasks: List<TaskEntity>,
        lists: List<ListEntity>,
        tags: List<TagEntity>,
        taskTagPairs: List<Pair<String, String>>,
        includeCompleted: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val listName = lists.associate { it.id to it.name }
        val tagName = tags.associate { it.id to it.name }
        val tagsByTask = taskTagPairs.groupBy({ it.first }, { it.second })
        fun esc(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val sb = StringBuilder()
        sb.append("Title,List,Status,Priority,Due,Deadline,Start,Energy,Estimate(min),Tags,Note\n")
        tasks.filter { !it.trashed && (includeCompleted || (!it.completed && !it.abandoned)) }
            .forEach { t ->
                val status = when { t.completed -> "Completed"; t.abandoned -> "Won't do"; else -> "Open" }
                val energy = when (t.energy) { 1 -> "Low"; 2 -> "Medium"; 3 -> "High"; else -> "" }
                val tagStr = tagsByTask[t.id]?.mapNotNull { tagName[it] }?.joinToString(" ") { "#$it" } ?: ""
                val row = listOf(
                    t.title, listName[t.listId] ?: "", status, priorityLabel(t),
                    day(t.dueDate, zone), day(t.deadlineDate, zone), day(t.startDate, zone),
                    energy, (t.estimateMin ?: t.estimateMax)?.toString() ?: "", tagStr, t.note.replace("\n", " "),
                )
                sb.append(row.joinToString(",") { esc(it) }).append('\n')
            }
        return sb.toString()
    }

    /** Long-format CSV of habit check-ins (re-importable by us; opens in any spreadsheet). */
    fun toHabitsCsv(
        habits: List<com.todocompanion.app.data.entity.HabitEntity>,
        checkins: List<com.todocompanion.app.data.entity.HabitCheckinEntity>,
    ): String {
        fun esc(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val byId = habits.associateBy { it.id }
        val sb = StringBuilder()
        sb.append("Habit,Type,Unit,Target,Frequency,Date,Value,Status\n")
        checkins.sortedWith(compareBy({ it.habitId }, { it.epochDay })).forEach { c ->
            val h = byId[c.habitId] ?: return@forEach
            val date = java.time.LocalDate.ofEpochDay(c.epochDay).format(DATE)
            val row = listOf(h.name, h.habitType, h.unit ?: "", h.targetPerDay.toString(),
                com.todocompanion.app.domain.habit.HabitStats.frequencyLabel(h), date, c.count.toString(), c.status)
            sb.append(row.joinToString(",") { esc(it) }).append('\n')
        }
        return sb.toString()
    }

    private val ICS_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val ICS_DTUTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    private fun icsEscape(s: String): String =
        s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    /** Fold long lines to 75 octets per RFC 5545 (continuation lines start with a space). */
    private fun icsFold(line: String): String {
        if (line.length <= 73) return line
        val sb = StringBuilder()
        var i = 0
        while (i < line.length) {
            val end = minOf(i + 73, line.length)
            if (i > 0) sb.append("\r\n ")
            sb.append(line, i, end)
            i = end
        }
        return sb.toString()
    }

    /**
     * A standards-compliant iCalendar (.ics) of every dated task — a VEVENT per due date and one
     * per deadline — so any calendar app (Google Calendar, Apple, Outlook, Thunderbird) can import
     * your plan. Pure text; the caller writes it to a file or the share sheet. Offline by nature.
     */
    fun toIcs(
        tasks: List<TaskEntity>,
        includeCompleted: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = 0L,
    ): String {
        val sb = StringBuilder()
        fun line(s: String) { sb.append(icsFold(s)).append("\r\n") }
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:-//ToDo Companion//Tasks//EN")
        line("CALSCALE:GREGORIAN")
        line("METHOD:PUBLISH")
        line("X-WR-CALNAME:ToDo Companion")
        val stampUtc = if (now > 0) Instant.ofEpochMilli(now).atZone(ZoneId.of("UTC")).format(ICS_DTUTC) else "19700101T000000Z"

        fun event(uid: String, summary: String, whenMillis: Long, allDay: Boolean, durationMin: Int?, note: String) {
            val z = Instant.ofEpochMilli(whenMillis).atZone(zone)
            line("BEGIN:VEVENT")
            line("UID:$uid@todocompanion")
            line("DTSTAMP:$stampUtc")
            if (allDay) {
                line("DTSTART;VALUE=DATE:${z.format(ICS_DATE)}")
                line("DTEND;VALUE=DATE:${z.plusDays(1).format(ICS_DATE)}")
            } else {
                val startUtc = z.withZoneSameInstant(ZoneId.of("UTC"))
                val endUtc = z.plusMinutes((durationMin ?: 30).toLong()).withZoneSameInstant(ZoneId.of("UTC"))
                line("DTSTART:${startUtc.format(ICS_DTUTC)}")
                line("DTEND:${endUtc.format(ICS_DTUTC)}")
            }
            line("SUMMARY:${icsEscape(summary)}")
            if (note.isNotBlank()) line("DESCRIPTION:${icsEscape(note)}")
            line("END:VEVENT")
        }

        tasks.filter { !it.trashed && (includeCompleted || (!it.completed && !it.abandoned)) }.forEach { t ->
            val title = t.title.ifBlank { "Untitled" }
            t.dueDate?.let { due ->
                val timed = !t.isAllDay && Instant.ofEpochMilli(due).atZone(zone).let { it.hour != 0 || it.minute != 0 }
                event(t.id, title, due, !timed, t.durationMin, t.note)
            }
            t.deadlineDate?.let { dl ->
                val timed = Instant.ofEpochMilli(dl).atZone(zone).let { it.hour != 0 || it.minute != 0 }
                event("${t.id}-deadline", "⛳ $title (deadline)", dl, !timed, null, t.note)
            }
        }
        line("END:VCALENDAR")
        return sb.toString()
    }
}
