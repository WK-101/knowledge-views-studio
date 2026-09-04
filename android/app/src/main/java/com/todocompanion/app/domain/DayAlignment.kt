package com.todocompanion.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Phase E — the day's *alignment*: how the closed day connected to what the user is working toward.
 * It holds two small sets, both optional:
 *  - [movedGoalIds]  — ids of the user's active Unified Goals (see [Goal], stored in settings JSON) that
 *                      today advanced;
 *  - [honoredValueIds] — ids of the user's top core values (the values card-sort ranking; see
 *                      CoreValueEntity) that today honored.
 *
 * Both stores are id references into feature data the app already owns, so the reflect card can render
 * live names/emoji and nothing is duplicated or hard-coded. The whole record lives as ONE JSON blob on
 * that day's DayLogEntity (column `alignmentJson`); "" = nothing recorded, so old backups round-trip.
 * Kept Compose-free so it unit-tests as plain Kotlin, mirroring DailyQuestions / DayPrompts / Goals.
 */
@Serializable
data class DayAlignment(
    val movedGoalIds: List<String> = emptyList(),
    val honoredValueIds: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = movedGoalIds.isEmpty() && honoredValueIds.isEmpty()
}

object DayAlignments {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Parse a day's alignment from its DayLog JSON ("" / malformed = an empty record). */
    fun parse(s: String): DayAlignment =
        if (s.isBlank()) DayAlignment()
        else runCatching { json.decodeFromString<DayAlignment>(s) }.getOrDefault(DayAlignment())

    /** Serialize a day's alignment back to a JSON string ("" when there is nothing to store). */
    fun encode(a: DayAlignment): String =
        if (a.isEmpty) "" else runCatching { json.encodeToString(a) }.getOrDefault("")
}
