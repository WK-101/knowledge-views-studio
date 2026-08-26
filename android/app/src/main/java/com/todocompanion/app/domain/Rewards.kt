package com.todocompanion.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Tier V12 — a self-defined rewards store. You earn momentum points by keeping habits and finishing
 * tasks, and spend them on rewards you choose yourself ("movie night", "new book"). Habitica's Rewards
 * idea, minus the server and minus any punishment: points only ever go up from doing the work, and a
 * reward is a treat you grant yourself, never a penalty. All local; it round-trips in the JSON backup.
 */
@Serializable
data class Reward(
    val id: String,
    val name: String,
    val emoji: String = "🎁",
    val cost: Int = 10,
    val redeemed: Int = 0,
)

object Rewards {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(s: String): List<Reward> =
        if (s.isBlank()) emptyList() else runCatching { json.decodeFromString<List<Reward>>(s) }.getOrDefault(emptyList())

    fun encode(rewards: List<Reward>): String = runCatching { json.encodeToString(rewards) }.getOrDefault("")
}
