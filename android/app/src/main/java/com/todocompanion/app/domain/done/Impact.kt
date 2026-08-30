package com.todocompanion.app.domain.done

import com.todocompanion.app.data.entity.TaskEntity

/**
 * Phase 5 — the impact graph as a query, not an integration. Finished tasks roll up to the goal / project
 * they served (their nearest goal or project ancestor), so a wall of checkboxes becomes the shape of real
 * progress: what the work added up to. Everything here is derived from the one schema this app already
 * stores — tasks, goals and outcomes share it — so the "graph" is a local query, not four synced apps.
 */
object Impact {
    data class Node(
        val goalId: String?,          // null = direct work with no goal ancestor
        val goalTitle: String,
        val isGoalDone: Boolean,      // the goal/project itself was completed in range
        val items: List<Accomplishment>,   // the finished tasks that served it
        val totalMinutes: Int,
        val outcomes: List<String>,
    )
    data class Graph(val nodes: List<Node>, val finished: Int, val goalsServed: Int, val outcomes: Int)

    fun build(items: List<Accomplishment>, tasks: List<TaskEntity>): Graph {
        val byId = tasks.associateBy { it.id }
        fun goalAncestorOf(start: TaskEntity?): TaskEntity? {
            var cur = start?.parentId?.let { byId[it] }; var guard = 0
            while (cur != null && guard++ < 64) {
                if (cur.isGoal || cur.isProject) return cur
                cur = cur.parentId?.let { byId[it] }
            }
            return null
        }
        val taskLike = items.filter { it.isTaskLike }
        // Leaves: plain finished tasks grouped under the goal ancestor they served (null = direct work).
        val leavesByGoal = LinkedHashMap<String?, MutableList<Accomplishment>>()
        taskLike.filter { it.kind == DoneKind.TASK }.forEach { a ->
            val g = goalAncestorOf(byId[a.refId])
            leavesByGoal.getOrPut(g?.id) { mutableListOf() }.add(a)
        }
        // Goals/projects finished in range become node headers (and mark that goal "done").
        val doneGoals = taskLike.filter { it.kind == DoneKind.GOAL || it.kind == DoneKind.PROJECT }.associateBy { it.refId }
        val goalIds = (leavesByGoal.keys.filterNotNull() + doneGoals.keys).toSet()

        val nodes = ArrayList<Node>()
        goalIds.forEach { gid ->
            val leaves = leavesByGoal[gid].orEmpty().sortedByDescending { it.whenMillis }
            val header = doneGoals[gid]
            val title = (byId[gid]?.title ?: header?.title)?.ifBlank { "Untitled" } ?: "Goal"
            val outcomes = (leaves.mapNotNull { it.outcome } + listOfNotNull(header?.outcome)).distinct()
            val totalMin = leaves.sumOf { it.durationMin } + (header?.durationMin ?: 0)
            nodes += Node(gid, title, header != null, leaves, totalMin, outcomes)
        }
        leavesByGoal[null]?.let { direct ->
            val sorted = direct.sortedByDescending { it.whenMillis }
            nodes += Node(null, "Direct work", false, sorted, sorted.sumOf { it.durationMin }, sorted.mapNotNull { it.outcome }.distinct())
        }
        // Goal nodes first, ordered by contribution; "Direct work" always last.
        val ordered = nodes.sortedWith(
            compareByDescending<Node> { it.goalId != null }
                .thenByDescending { it.items.size + (if (it.isGoalDone) 1 else 0) }
                .thenByDescending { it.totalMinutes }
        )
        return Graph(ordered, taskLike.size, goalIds.size, taskLike.count { it.outcome != null })
    }
}
