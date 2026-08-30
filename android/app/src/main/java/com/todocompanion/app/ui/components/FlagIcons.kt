package com.todocompanion.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The curated set of icons a flag can wear. Stored on [com.todocompanion.app.data.entity.FlagEntity]
 * as a stable string key so it survives export/import; resolved to a vector for rendering.
 */
object FlagIcons {
    // R32 — the flag marker's identity is a BOOKMARK (the literal flag glyph collided with PRIORITY,
    // which now owns the coloured flag). "flag" is dropped from the picker; any legacy value stored as
    // "flag" resolves to the bookmark below, so old flags render consistently with the revised icon.
    val keys: List<String> = listOf("bookmark", "star", "label", "circle", "bolt", "fire", "heart", "priority")

    fun vector(key: String?): ImageVector = when (key) {
        "star" -> Icons.Filled.Star
        "bookmark" -> Icons.Filled.Bookmark
        "label" -> Icons.Filled.Label
        "circle" -> Icons.Filled.Circle
        "bolt" -> Icons.Filled.Bolt
        "fire" -> Icons.Filled.Whatshot
        "heart" -> Icons.Filled.Favorite
        "priority" -> Icons.Filled.PriorityHigh
        // Default marker (and legacy "flag") is a bookmark, kept visually distinct from PRIORITY.
        else -> Icons.Filled.Bookmark
    }
}
