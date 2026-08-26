package com.todocompanion.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Tier W6 — a routine tag. A named bundle that one NFC/QR tap (or a launcher shortcut) fires to spin up
 * a whole ritual at once: start tracking [activityId], surface the habits in [habitCategory], and let the
 * on-start automation rules (silence, chained timers) run. One tap → a coordinated first-hour system
 * across every module. Fully local; round-trips in the JSON backup.
 */
@Serializable
data class Routine(
    val id: String,
    val name: String,
    val emoji: String = "🔗",
    val activityId: String = "",     // start tracking this activity (blank = none)
    val habitCategory: String = "",  // surface habits in this group (blank = none)
    val note: String = "",
)

object Routines {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(s: String): List<Routine> =
        if (s.isBlank()) emptyList() else runCatching { json.decodeFromString<List<Routine>>(s) }.getOrDefault(emptyList())

    fun encode(list: List<Routine>): String = runCatching { json.encodeToString(list) }.getOrDefault("")

    fun byName(list: List<Routine>, name: String): Routine? = list.firstOrNull { it.name.equals(name.trim(), true) }
}
