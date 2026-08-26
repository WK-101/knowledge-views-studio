package com.todocompanion.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Tier Z6 — a plain record of what the assistant did on your behalf (schedule, backfill, reshape), each
 * with enough payload to undo it. Acting for the user is only safe if every action is visible and
 * reversible; this is that ledger. Bounded to a recent window; stored as JSON in settings.
 *
 * [undo] is a small JSON blob interpreted by [kind]:
 *   · "backfill" → {"entry":"<timeEntryId>"}                      (undo deletes the entry)
 *   · "rhythm"   → {"habit":"<id>","freq":"<old>","days":"<old>"} (undo restores the old schedule)
 *   · "plan"     → {"dues":{"<taskId>":<oldDueMillis|0>, …}}      (undo restores each old due date)
 */
@Serializable
data class AssistantAction(
    val id: String,
    val at: Long,
    val kind: String,
    val description: String,
    val undo: String = "",       // "" = not reversible, log-only
    val undone: Boolean = false,
) {
    val reversible get() = undo.isNotBlank() && !undone
}

object AssistantLog {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun parse(s: String): List<AssistantAction> =
        if (s.isBlank()) emptyList() else runCatching { json.decodeFromString<List<AssistantAction>>(s) }.getOrDefault(emptyList())
    fun encode(list: List<AssistantAction>): String = runCatching { json.encodeToString(list) }.getOrDefault("")
    /** Prepend a new action, keeping only the most recent [cap]. */
    fun push(list: List<AssistantAction>, a: AssistantAction, cap: Int = 50): List<AssistantAction> =
        (listOf(a) + list).take(cap)
    fun markUndone(list: List<AssistantAction>, id: String): List<AssistantAction> =
        list.map { if (it.id == id) it.copy(undone = true) else it }
}
