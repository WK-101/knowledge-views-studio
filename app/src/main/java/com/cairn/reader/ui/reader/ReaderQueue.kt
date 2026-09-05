package com.cairn.reader.ui.reader

/**
 * The current reading order, so the reader can flow to the previous/next article without going back
 * to the list. A list surface sets [ids] to its visible item ids just before opening one; the reader
 * resolves neighbours from it. App-scoped and best-effort — if it's empty (e.g. after process death,
 * or opened from a notification), prev/next are simply unavailable until the user opens from a list.
 */
object ReaderQueue {
    @Volatile
    var ids: List<String> = emptyList()

    /** Replace the queue with the ids currently shown in a surface. */
    fun set(newIds: List<String>) { ids = newIds }

    /** The id [delta] positions from [currentId] in the queue, or null if out of range / unknown. */
    fun neighbor(currentId: String, delta: Int): String? {
        val i = ids.indexOf(currentId)
        if (i < 0) return null
        return ids.getOrNull(i + delta)
    }
}
