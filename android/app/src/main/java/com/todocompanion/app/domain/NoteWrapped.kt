package com.todocompanion.app.domain

import java.time.Instant
import java.time.ZoneId

/**
 * Wave V — "Notes Wrapped": a locally-generated yearly recap of your notes (Notesnook ships a beloved
 * one). Pure and unit-tested — no cloud, no account, computed entirely on-device from the notes you own.
 */
object NoteWrapped {
    data class In(val id: String, val title: String, val body: String, val createdAt: Long, val kind: String, val dayEpoch: Long?)
    data class Stats(
        val year: Int,
        val created: Int,
        val words: Int,
        val busiestMonth: String,
        val busiestMonthCount: Int,
        val topTags: List<Pair<String, Int>>,
        val longestTitle: String,
        val longestWords: Int,
        val journalDays: Int,
        val distinctTags: Int,
    ) { val isEmpty: Boolean get() = created == 0 }

    private val tag = Regex("(?<![\\w#/])#([A-Za-z][\\w/-]*)")
    private val months = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    private fun words(s: String): Int = s.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    fun compute(notes: List<In>, year: Int, zone: ZoneId = ZoneId.systemDefault()): Stats {
        val inYear = notes.filter { Instant.ofEpochMilli(it.createdAt).atZone(zone).year == year }
        if (inYear.isEmpty()) return Stats(year, 0, 0, "", 0, emptyList(), "", 0, 0, 0)
        val byMonth = IntArray(12)
        val tagCounts = LinkedHashMap<String, Int>()
        var totalWords = 0
        var longest = inYear.first(); var longestW = -1
        val journalDays = HashSet<Long>()
        for (n in inYear) {
            byMonth[Instant.ofEpochMilli(n.createdAt).atZone(zone).monthValue - 1]++
            val w = words(n.body); totalWords += w
            if (w > longestW) { longestW = w; longest = n }
            for (m in tag.findAll(n.body)) { val t = m.groupValues[1].lowercase(); tagCounts[t] = (tagCounts[t] ?: 0) + 1 }
            if (n.kind == "journal") n.dayEpoch?.let { journalDays.add(it) }
        }
        val busiest = byMonth.indices.maxByOrNull { byMonth[it] } ?: 0
        val top = tagCounts.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        return Stats(
            year = year,
            created = inYear.size,
            words = totalWords,
            busiestMonth = months[busiest],
            busiestMonthCount = byMonth[busiest],
            topTags = top,
            longestTitle = longest.title.ifBlank { "(untitled)" },
            longestWords = longestW.coerceAtLeast(0),
            journalDays = journalDays.size,
            distinctTags = tagCounts.size,
        )
    }
}
