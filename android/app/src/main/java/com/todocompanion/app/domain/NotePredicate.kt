package com.todocompanion.app.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wave D — Smart Views. A saved, dynamic filter over notes, expressed as a tiny predicate tree
 * (Standard Notes' model, reimplemented pure & on-device). A [Group] combines children with AND (all)
 * or OR (any); a [Cond] is one leaf test on a note field. Serialized to JSON in `smart_views.predicateJson`.
 */
@Serializable
sealed interface NotePredicate {
    @Serializable @SerialName("group")
    data class Group(val any: Boolean, val children: List<NotePredicate>) : NotePredicate

    @Serializable @SerialName("cond")
    data class Cond(val field: String, val value: String = "") : NotePredicate
}

/** Pure evaluator + JSON for Smart Views — no Android, no DB, so it unit-tests cleanly. */
object NoteSmartViews {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(p: NotePredicate): String = json.encodeToString(NotePredicate.serializer(), p)
    fun decode(s: String): NotePredicate? =
        runCatching { json.decodeFromString(NotePredicate.serializer(), s) }.getOrNull()

    /** The note fields a condition can test. [now] lets `olderThanDays` be evaluated deterministically.
     *  Wave J (M4) — the last block is cross-module state (a note's reminder, its open action items, and
     *  the live status of the task it's woven to): the thing a notes-only app cannot filter on. */
    data class Ctx(
        val pinned: Boolean, val favorite: Boolean, val archived: Boolean, val trashed: Boolean,
        val title: String, val body: String, val kind: String, val updatedAt: Long,
        val tagIds: Set<String>, val now: Long,
        val hasReminder: Boolean = false,
        val hasOpenItems: Boolean = false,
        val linkedTaskId: String? = null,
        val linkedEventId: String? = null,
        val openTaskIds: Set<String> = emptySet(),
        val overdueTaskIds: Set<String> = emptySet(),
    )

    fun matches(p: NotePredicate, c: Ctx): Boolean = when (p) {
        is NotePredicate.Group -> if (p.any) p.children.any { matches(it, c) } else p.children.all { matches(it, c) }
        is NotePredicate.Cond -> evalCond(p.field, p.value, c)
    }

    private fun evalCond(field: String, value: String, c: Ctx): Boolean = when (field) {
        "pinned" -> c.pinned
        "favorite" -> c.favorite
        "archived" -> c.archived
        "trashed" -> c.trashed
        "untagged" -> c.tagIds.isEmpty()
        "hasTag" -> value in c.tagIds
        "titleContains" -> value.isNotBlank() && c.title.contains(value, ignoreCase = true)
        "bodyContains" -> value.isNotBlank() && c.body.contains(value, ignoreCase = true)
        "textContains" -> value.isNotBlank() && (c.title.contains(value, true) || c.body.contains(value, true))
        "kind" -> c.kind == value
        "olderThanDays" -> (value.toLongOrNull() ?: 0L).let { d -> d > 0 && (c.now - c.updatedAt) > d * 86_400_000L }
        // Wave J (M4) — cross-module conditions the engine answers.
        "hasReminder" -> c.hasReminder
        "hasOpenItems" -> c.hasOpenItems              // has unchecked "- [ ]" action items
        "linkedTask" -> c.linkedTaskId != null
        "linkedEvent" -> c.linkedEventId != null
        "linkedTaskOpen" -> c.linkedTaskId != null && c.linkedTaskId in c.openTaskIds
        "linkedTaskOverdue" -> c.linkedTaskId != null && c.linkedTaskId in c.overdueTaskIds
        else -> false
    }

    // ── Built-in "system" views (hardcoded predicates; not stored) ──
    val ALL = NotePredicate.Group(any = false, children = emptyList())        // empty AND ⇒ matches everything
    val PINNED = NotePredicate.Cond("pinned")
    val FAVORITES = NotePredicate.Cond("favorite")
    val UNTAGGED = NotePredicate.Cond("untagged")
    val ARCHIVED = NotePredicate.Cond("archived")
}
