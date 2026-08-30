package com.todocompanion.app.domain.calendar

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * R41 — a reusable event blueprint. "Stand-up · 15m · Work · alert 5m", "Gym · 60m · Fitness". One tap
 * drops it onto the calendar with its duration, colour, calendar and alerts pre-filled. Stored as JSON
 * in settings (no DB migration); round-trips losslessly in the backup.
 */
@Serializable
data class EventTemplate(
    val id: String,
    val title: String,
    val durationMin: Int = 30,
    val calendarId: String = "",       // "" = default calendar at apply time
    val colorArgb: Long? = null,       // per-event colour override
    val location: String = "",
    val alertsMinutes: String = "",    // comma of minutes-before
    val busy: Boolean = true,
    val emoji: String = "📌",
)

object EventTemplates {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(s: String): List<EventTemplate> =
        if (s.isBlank()) emptyList() else runCatching { json.decodeFromString<List<EventTemplate>>(s) }.getOrDefault(emptyList())

    fun encode(list: List<EventTemplate>): String = runCatching { json.encodeToString(list) }.getOrDefault("")

    fun upsert(list: List<EventTemplate>, t: EventTemplate): List<EventTemplate> =
        if (list.any { it.id == t.id }) list.map { if (it.id == t.id) t else it } else list + t

    fun remove(list: List<EventTemplate>, id: String): List<EventTemplate> = list.filterNot { it.id == id }
}

/**
 * R41 — remembered travel time per place. When an event has a location, an optional auto-buffer reserves
 * the minutes it takes to get there; the app remembers the last figure per normalized place name so the
 * next event at the same spot pre-fills it. No Maps, no network — just your own memory of the trip.
 */
object TravelTimes {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun parse(s: String): Map<String, Int> =
        if (s.isBlank()) emptyMap() else runCatching { json.decodeFromString<Map<String, Int>>(s) }.getOrDefault(emptyMap())
    fun encode(map: Map<String, Int>): String = runCatching { json.encodeToString(map) }.getOrDefault("")
    fun key(place: String): String = place.trim().lowercase()
    fun forPlace(map: Map<String, Int>, place: String): Int? = map[key(place)]
    fun remember(map: Map<String, Int>, place: String, minutes: Int): Map<String, Int> =
        if (place.isBlank() || minutes <= 0) map else map + (key(place) to minutes)
}
