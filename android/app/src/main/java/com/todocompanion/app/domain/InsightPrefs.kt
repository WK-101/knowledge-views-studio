package com.todocompanion.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Tier Z2 — the user's control over the assistant's nudges. Each insight, coach line and radar warning
 * carries a stable key; the user can dismiss it forever or snooze it for a while. Nothing the assistant
 * says is un-silenceable. Stored as JSON in settings; round-trips in the backup.
 */
@Serializable
data class InsightPrefs(
    val dismissed: Set<String> = emptySet(),        // keys the user chose "never" on
    val snoozeUntil: Map<String, Long> = emptyMap(), // key → epoch-day through which it stays hidden
) {
    fun suppressed(key: String, today: Long): Boolean =
        key in dismissed || (snoozeUntil[key]?.let { today <= it } == true)
    fun dismiss(key: String) = copy(dismissed = dismissed + key)
    fun snooze(key: String, untilEpochDay: Long) = copy(snoozeUntil = snoozeUntil + (key to untilEpochDay))
    fun restore(key: String) = copy(dismissed = dismissed - key, snoozeUntil = snoozeUntil - key)
}

object InsightPrefsCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun parse(s: String): InsightPrefs =
        if (s.isBlank()) InsightPrefs() else runCatching { json.decodeFromString<InsightPrefs>(s) }.getOrDefault(InsightPrefs())
    fun encode(p: InsightPrefs): String = runCatching { json.encodeToString(p) }.getOrDefault("")
}
