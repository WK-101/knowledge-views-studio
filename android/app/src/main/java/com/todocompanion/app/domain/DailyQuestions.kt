package com.todocompanion.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Phase C — self-scored Daily Questions (Marshall Goldsmith's "active questions"). Each evening you
 * score a small set of "Did I do my best to…" questions 1–5. The framing scores your EFFORT, not the
 * outcome — "did I do my best to be present?" rather than "was I present?" — which keeps agency and
 * motivation with the person. The questions themselves are user-defined and tied to what they value;
 * a small sparkline trends each one over recent days. All local; both stores round-trip in the backup.
 *
 * Two tiny stores back this feature, matching how the app already keeps list-like data as JSON strings:
 *  - the QUESTION LIST lives in a settings JSON value ([DailyQuestion] array),
 *  - the PER-DAY SCORES live in a JSON object (questionId → score) on that day's DayLogEntity.
 */
@Serializable
data class DailyQuestion(val id: String, val text: String)

object DailyQuestions {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The user keeps at most a handful of active questions — a small set is the point. */
    const val MAX = 5

    /** Suggested starter questions, offered when the user has none yet. All effort-framed. */
    val SUGGESTED: List<String> = listOf(
        "Did I do my best to set clear goals?",
        "Did I do my best to make progress on what matters?",
        "Did I do my best to be fully present?",
        "Did I do my best to look after my body?",
        "Did I do my best to be kind?",
    )

    /** Parse the user's saved question list from its settings JSON ("" = none). */
    fun parseQuestions(s: String): List<DailyQuestion> =
        if (s.isBlank()) emptyList()
        else runCatching { json.decodeFromString<List<DailyQuestion>>(s) }.getOrDefault(emptyList())

    /** Serialize the question list back to a settings JSON string. */
    fun toJson(list: List<DailyQuestion>): String =
        runCatching { json.encodeToString(list) }.getOrDefault("")

    /** Parse one day's scores map (questionId → score 1..5) from its DayLog JSON ("" = none scored). */
    fun parseScores(s: String): Map<String, Int> =
        if (s.isBlank()) emptyMap()
        else runCatching { json.decodeFromString<Map<String, Int>>(s) }.getOrDefault(emptyMap())

    /** Serialize a day's scores map back to a JSON string. */
    fun scoresToJson(map: Map<String, Int>): String =
        runCatching { json.encodeToString(map) }.getOrDefault("")
}
