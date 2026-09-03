package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.TaskEntity

/**
 * G5 — spot repeated task shapes worth turning into a template. Pure over the task list plus the set of
 * template names that already exist; R78 lifted it out of AppViewModel so it's independently testable.
 */
object TemplateSuggest {
    data class Suggestion(val title: String, val count: Int, val exampleId: String)

    /**
     * Titles that recur at least [minCount] times among live (non-trashed, non-note) tasks and aren't
     * already a template, newest example first per group, ranked by frequency, capped at five.
     * [existingNames] is the lower-cased set of current template names.
     */
    fun suggest(tasks: List<TaskEntity>, existingNames: Set<String>, minCount: Int = 3): List<Suggestion> =
        tasks.asSequence()
            .filter { !it.trashed && !it.isNote && it.title.isNotBlank() }
            .groupBy { it.title.trim().lowercase() }
            .filter { (norm, list) -> list.size >= minCount && norm !in existingNames }
            .map { (_, list) ->
                val newest = list.maxByOrNull { it.createdAt }!!
                Suggestion(newest.title.trim(), list.size, newest.id)
            }
            .sortedByDescending { it.count }
            .take(5)
}
