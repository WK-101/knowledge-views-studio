package com.todocompanion.app.domain.view

import com.todocompanion.app.data.entity.TaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Which smart list, list, tag or context is being viewed. */
sealed interface ViewRef {
    data class Smart(val kind: SmartKind) : ViewRef
    data class ListView(val listId: String) : ViewRef
    data class FolderView(val folderId: String) : ViewRef
    data class TagView(val tagId: String) : ViewRef
    data class ContextView(val contextId: String) : ViewRef
    data class FilterView(val filterId: String) : ViewRef
}

enum class SmartKind(val title: String) {
    INBOX("Inbox"),
    TODAY("Today"),
    TOMORROW("Tomorrow"),
    NEXT7("Next 7 Days"),
    DO_NEXT("Do Next"),
    SCHEDULED("Scheduled"),
    FLAGGED("Flagged"),
    GOALS("Goals"),
    ALL("All"),
    COMPLETED("Completed"),
    WONT_DO("Won't Do"),
    TRASH("Trash"),
}

enum class GroupMode { NONE, DATE, PRIORITY }
enum class SortMode { MANUAL, PRIORITY, DUE, TITLE }

enum class Bucket(val label: String) {
    OVERDUE("Overdue"), TODAY("Today"), TOMORROW("Tomorrow"),
    WEEK("Next 7 days"), LATER("Later"), NODATE("No date")
}

data class TaskGroup(val key: String, val title: String, val tasks: List<TaskEntity>)

object TaskViews {

    private fun localDate(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun bucketOf(task: TaskEntity, now: Long, zone: ZoneId = ZoneId.systemDefault()): Bucket {
        val due = task.dueDate ?: return Bucket.NODATE
        val today = localDate(now, zone)
        val d = localDate(due, zone)
        return when {
            d.isBefore(today) -> Bucket.OVERDUE
            d == today -> Bucket.TODAY
            d == today.plusDays(1) -> Bucket.TOMORROW
            d.isBefore(today.plusDays(8)) -> Bucket.WEEK
            else -> Bucket.LATER
        }
    }

    /** Non-trashed, non-completed, non-abandoned "open" tasks. */
    private fun isOpen(t: TaskEntity) = !t.trashed && !t.completed && !t.abandoned

    /**
     * Filter tasks for a smart-list view. Tag/context/list views are resolved in the
     * caller (they need cross-ref data); this handles the smart lists + a plain list.
     */
    fun filterSmart(all: List<TaskEntity>, kind: SmartKind, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<TaskEntity> {
        val today = localDate(now, zone)
        return when (kind) {
            SmartKind.INBOX -> all.filter { isOpen(it) && it.listId == "inbox" }
            SmartKind.TODAY -> all.filter { isOpen(it) && it.dueDate != null && !localDate(it.dueDate!!, zone).isAfter(today) }
            SmartKind.TOMORROW -> all.filter { isOpen(it) && it.dueDate != null && localDate(it.dueDate!!, zone) == today.plusDays(1) }
            SmartKind.NEXT7 -> all.filter { isOpen(it) && it.dueDate != null && !localDate(it.dueDate!!, zone).isAfter(today.plusDays(7)) }
            SmartKind.SCHEDULED -> all.filter { isOpen(it) && it.dueDate != null }
            SmartKind.FLAGGED -> all.filter { isOpen(it) && it.star }
            SmartKind.GOALS -> all.filter { isOpen(it) && it.isGoal }
            SmartKind.ALL -> all.filter { isOpen(it) }
            SmartKind.DO_NEXT -> all.filter { isOpen(it) }   // ranking applied separately by the engine
            SmartKind.COMPLETED -> all.filter { it.completed && !it.trashed }
            SmartKind.WONT_DO -> all.filter { it.abandoned && !it.trashed }
            SmartKind.TRASH -> all.filter { it.trashed }
        }
    }

    fun group(tasks: List<TaskEntity>, mode: GroupMode, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<TaskGroup> {
        return when (mode) {
            GroupMode.NONE -> listOf(TaskGroup("all", "", tasks))
            GroupMode.DATE -> Bucket.entries.mapNotNull { b ->
                val items = tasks.filter { bucketOf(it, now, zone) == b }
                if (items.isEmpty()) null else TaskGroup(b.name, b.label, items)
            }
            GroupMode.PRIORITY -> {
                val labels = listOf("High" to 5, "Medium" to 4, "Low" to 3, "None" to 0)
                labels.mapNotNull { (label, min) ->
                    val items = tasks.filter { levelBucket(it) == label }
                    if (items.isEmpty()) null else TaskGroup(label, label, items)
                }
            }
        }
    }

    private fun levelBucket(t: TaskEntity): String {
        val m = maxOf(t.importance, t.urgency)
        return when {
            m >= 5 -> "High"; m >= 4 -> "Medium"; m >= 3 -> "Low"; else -> "None"
        }
    }

    fun sort(tasks: List<TaskEntity>, mode: SortMode): List<TaskEntity> {
        // A stable tiebreaker (createdAt, id) keeps order fixed when an unrelated field
        // (star, flag, updatedAt) changes — otherwise rows visually swap on toggle.
        val tie = compareBy<TaskEntity>({ it.createdAt }, { it.id })
        val cmp = when (mode) {
            SortMode.MANUAL -> compareBy<TaskEntity> { it.sortOrder }
            SortMode.PRIORITY -> compareByDescending<TaskEntity> { maxOf(it.importance, it.urgency) }
            SortMode.DUE -> compareBy(nullsLast()) { it: TaskEntity -> it.dueDate }
            SortMode.TITLE -> compareBy<TaskEntity> { it.title.lowercase() }
        }
        // Pinned tasks always float to the top.
        val pin = compareByDescending<TaskEntity> { it.pinned }
        return tasks.sortedWith(pin.then(cmp).then(tie))
    }
}
