package com.wkhan.hexis.domain

import com.wkhan.hexis.data.entity.FolderEntity
import com.wkhan.hexis.data.entity.ListEntity
import com.wkhan.hexis.data.entity.TaskEntity

/**
 * Ranks list/folder move targets for the task(s) being filed, so the "Move to" picker can surface the
 * likeliest destination first instead of showing a passive, alphabetical catalogue.
 *
 * Two signals, and content beats frequency:
 *  1. **Content similarity** — every existing task whose title shares meaningful words with what you're
 *     filing lends its own list/folder some credit; the destination that look-alike tasks already live in
 *     floats to the top. (e.g. filing "Buy milk" surfaces the list your earlier "Buy …" tasks went to.)
 *  2. **Frequency** — when nothing looks similar, fall back to the list/folder that simply holds the most
 *     tasks, so the picker still opens on your busiest, most-likely destination.
 *
 * Pure, synchronous and offline — it reads only the in-memory task/list/folder snapshots. The result is a
 * short, ordered list of refs ("folder:<id>" / "list:<id>") matching the ref scheme the picker already
 * uses, so the same ranking drops into every call site (task editor, bulk move, quick-add) unchanged.
 */
object MoveSuggester {
    // Common words carry no filing signal — drop them so "Buy milk" matches "Buy bread", not "the plan".
    private val STOP = setOf(
        "the", "a", "an", "and", "or", "to", "of", "for", "in", "on", "at", "with", "my", "me", "is", "it",
        "this", "that", "be", "do", "go", "get", "got", "set", "add", "new", "from", "by", "up", "out", "off",
        "re", "we", "you", "your", "i", "as", "so", "no", "not", "are", "was", "will", "can", "all", "any",
    )

    private fun tokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in STOP }
            .toSet()

    /** The list/folder a task actually lives in, as a picker ref — Inbox/unfiled is never a suggestion. */
    private fun refOf(t: TaskEntity): String? = when {
        t.folderId != null -> "folder:${t.folderId}"
        t.listId.isNotBlank() && t.listId != ListEntity.INBOX_ID -> "list:${t.listId}"
        else -> null
    }

    /**
     * @param queryTitles the title(s) of the task(s) being filed (for quick-add, the text typed so far).
     * @param excludeTaskIds tasks NOT to learn from (the very ones being moved).
     * @return up to [max] refs, best destination first; empty when there's nothing meaningful to suggest.
     */
    fun rank(
        queryTitles: List<String>,
        tasks: List<TaskEntity>,
        folders: List<FolderEntity>,
        lists: List<ListEntity>,
        excludeTaskIds: Set<String> = emptySet(),
        max: Int = 3,
    ): List<String> {
        val validRefs = HashSet<String>()
        folders.forEach { if (!it.archived) validRefs += "folder:${it.id}" }
        lists.forEach { if (!it.archived && it.id != ListEntity.INBOX_ID) validRefs += "list:${it.id}" }
        if (validRefs.isEmpty()) return emptyList()

        val qTokens = queryTitles.flatMap { tokens(it) }.toSet()
        val sim = HashMap<String, Int>()
        val freq = HashMap<String, Int>()
        for (t in tasks) {
            if (t.trashed || t.id in excludeTaskIds) continue
            val ref = refOf(t) ?: continue
            if (ref !in validRefs) continue
            freq[ref] = (freq[ref] ?: 0) + 1
            if (qTokens.isNotEmpty()) {
                val shared = tokens(t.title).count { it in qTokens }
                if (shared > 0) sim[ref] = (sim[ref] ?: 0) + shared
            }
        }
        if (freq.isEmpty()) return emptyList()
        // Any content match dominates raw frequency (×1000), so a look-alike destination always outranks a
        // merely busy one; frequency then orders the similar refs among themselves and fills the rest.
        return freq.keys
            .map { ref -> ref to ((sim[ref] ?: 0) * 1000 + (freq[ref] ?: 0)) }
            .sortedByDescending { it.second }
            .take(max)
            .map { it.first }
    }
}
