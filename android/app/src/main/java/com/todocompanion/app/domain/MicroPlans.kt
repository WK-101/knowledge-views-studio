package com.todocompanion.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * R67 — small paired-plan records that power two evidence-based behaviour tools, stored together in one
 * settings-JSON list (no schema change; rides the backup like every other setting):
 *
 *  • kind = "bundle"  — Temptation bundling (Milkman): pair a "want" with a "should" so the pull of the
 *    want tows the should along ("only listen to my audiobook WHILE at the gym").
 *  • kind = "ifthen"  — Implementation intentions (Gollwitzer): a concrete "WHEN situation X, THEN I
 *    will do Y" plan, which roughly doubles follow-through by pre-deciding the moment of action.
 *
 * [a] is the trigger side (the want, or the situation); [b] is the response side (the should, or the action).
 * [habitId] optionally ties the plan to a habit it protects, so the plan surfaces on that habit's detail
 * ("your if-then for this one") — blank means a free-standing plan. Defaulted, so old records still parse.
 */
@Serializable
data class MicroPlan(
    val id: String,
    val kind: String,          // "bundle" | "ifthen"
    val a: String,
    val b: String,
    val createdAt: Long = 0L,
    val habitId: String = "",
)

object MicroPlans {
    const val BUNDLE = "bundle"
    const val IF_THEN = "ifthen"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(s: String): List<MicroPlan> =
        if (s.isBlank()) emptyList() else runCatching { json.decodeFromString<List<MicroPlan>>(s) }.getOrDefault(emptyList())

    fun encode(plans: List<MicroPlan>): String = runCatching { json.encodeToString(plans) }.getOrDefault("")
}
