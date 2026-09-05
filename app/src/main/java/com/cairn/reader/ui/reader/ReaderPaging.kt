package com.cairn.reader.ui.reader

/**
 * Bridges the hardware volume keys (handled by the Activity) to the on-screen reader (a Composable).
 * The reader registers a handler while it is visible and volume-key paging is enabled; MainActivity
 * consults it in onKeyDown. When no reader is active the handler is null and the volume keys behave
 * normally, so this never steals volume control outside the reader.
 */
object ReaderPaging {
    /** Returns true if it consumed the key. [down] = page down (volume-down), false = page up. */
    @Volatile
    var handler: ((down: Boolean) -> Boolean)? = null

    fun onVolumeKey(down: Boolean): Boolean = handler?.invoke(down) ?: false
}
