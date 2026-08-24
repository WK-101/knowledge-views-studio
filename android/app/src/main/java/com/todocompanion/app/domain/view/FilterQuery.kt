package com.todocompanion.app.domain.view

import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.priority.PriorityLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId

/**
 * A saved-filter query. Each non-empty criterion group is one condition; groups are combined with
 * AND when [matchAll] is true, otherwise OR. Within a group the test is "task matches any listed
 * value". Serialized into [com.todocompanion.app.data.entity.FilterEntity.queryJson].
 */
@Serializable
data class FilterQuery(
    val matchAll: Boolean = true,
    val listIds: Set<String> = emptySet(),
    val tagIds: Set<String> = emptySet(),
    val contextIds: Set<String> = emptySet(),
    val levels: Set<String> = emptySet(),     // PriorityLevel names: HIGH/MEDIUM/LOW/NONE
    val flaggedOnly: Boolean = false,
    val dueWithinDays: Int? = null,           // null = ignore; N = due within N days (0 = today or overdue)
    val maxDurationMin: Int? = null,          // null = ignore; N = task estimate fits within N minutes
    val includeCompleted: Boolean = false,
) {
    fun isEmpty(): Boolean =
        listIds.isEmpty() && tagIds.isEmpty() && contextIds.isEmpty() && levels.isEmpty() && !flaggedOnly && dueWithinDays == null && maxDurationMin == null
}

object Filters {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(q: FilterQuery): String = json.encodeToString(FilterQuery.serializer(), q)
    fun parse(s: String?): FilterQuery = if (s.isNullOrBlank()) FilterQuery() else runCatching { json.decodeFromString(FilterQuery.serializer(), s) }.getOrDefault(FilterQuery())

    fun matches(q: FilterQuery, task: TaskEntity, taskTagIds: Set<String>, taskCtxIds: Set<String>, now: Long, zone: ZoneId): Boolean {
        if (task.trashed) return false
        if (!q.includeCompleted && (task.completed || task.abandoned)) return false
        val checks = ArrayList<Boolean>(6)
        if (q.listIds.isNotEmpty()) checks += task.listId in q.listIds
        if (q.tagIds.isNotEmpty()) checks += taskTagIds.any { it in q.tagIds }
        if (q.contextIds.isNotEmpty()) checks += taskCtxIds.any { it in q.contextIds }
        if (q.levels.isNotEmpty()) checks += PriorityLevel.from(task.importance, task.urgency).name in q.levels
        if (q.flaggedOnly) checks += task.star
        q.dueWithinDays?.let { days ->
            val due = task.dueDate
            checks += due != null && run {
                val d = Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
                val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                !d.isAfter(today.plusDays(days.toLong()))
            }
        }
        // "Time available": the task's estimate (default 15 min) fits the window.
        q.maxDurationMin?.let { cap -> checks += (task.estimateMin ?: 15) <= cap }
        if (checks.isEmpty()) return true
        return if (q.matchAll) checks.all { it } else checks.any { it }
    }
}
