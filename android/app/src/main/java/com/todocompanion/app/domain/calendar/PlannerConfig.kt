package com.todocompanion.app.domain.calendar

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * R42 — planner configuration objects stored as JSON in settings (no DB migration; round-trip in the
 * lossless backup): reusable day routines, protected life-windows, and context modes.
 */

/** One block inside a day routine: an offset from the working-day start, a duration, and a title. */
@Serializable
data class RoutineBlock(val title: String, val startMin: Int, val durationMin: Int, val colorArgb: Long? = null)

/** A named "template day" you can inject onto any date in one tap. */
@Serializable
data class DayRoutine(val id: String, val name: String, val emoji: String = "📆", val blocks: List<RoutineBlock> = emptyList())

/** An inviolable recurring window (e.g. family dinner) the scheduler treats as a wall and that mutes
 *  habit nudges while it runs. [days] are ISO day-of-week numbers (1=Mon..7=Sun); empty = every day. */
@Serializable
data class ProtectedWindow(val id: String, val name: String, val startMin: Int, val endMin: Int, val days: List<Int> = emptyList()) {
    fun appliesTo(isoDow: Int): Boolean = days.isEmpty() || isoDow in days
}

/** A context mode: hides the other domain's calendars and limits what the scheduler considers. */
@Serializable
data class CalContext(val id: String, val name: String, val emoji: String = "🗂", val calendarIds: List<String> = emptyList())

object DayRoutines {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun parse(s: String): List<DayRoutine> = if (s.isBlank()) emptyList() else runCatching { json.decodeFromString<List<DayRoutine>>(s) }.getOrDefault(emptyList())
    fun encode(l: List<DayRoutine>): String = runCatching { json.encodeToString(l) }.getOrDefault("")
    fun upsert(l: List<DayRoutine>, r: DayRoutine) = if (l.any { it.id == r.id }) l.map { if (it.id == r.id) r else it } else l + r
    fun remove(l: List<DayRoutine>, id: String) = l.filterNot { it.id == id }
}

object ProtectedWindows {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun parse(s: String): List<ProtectedWindow> = if (s.isBlank()) emptyList() else runCatching { json.decodeFromString<List<ProtectedWindow>>(s) }.getOrDefault(emptyList())
    fun encode(l: List<ProtectedWindow>): String = runCatching { json.encodeToString(l) }.getOrDefault("")
    fun upsert(l: List<ProtectedWindow>, w: ProtectedWindow) = if (l.any { it.id == w.id }) l.map { if (it.id == w.id) w else it } else l + w
    fun remove(l: List<ProtectedWindow>, id: String) = l.filterNot { it.id == id }
}

object CalContexts {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun parse(s: String): List<CalContext> = if (s.isBlank()) emptyList() else runCatching { json.decodeFromString<List<CalContext>>(s) }.getOrDefault(emptyList())
    fun encode(l: List<CalContext>): String = runCatching { json.encodeToString(l) }.getOrDefault("")
    fun upsert(l: List<CalContext>, c: CalContext) = if (l.any { it.id == c.id }) l.map { if (it.id == c.id) c else it } else l + c
    fun remove(l: List<CalContext>, id: String) = l.filterNot { it.id == id }
}
