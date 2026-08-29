package com.todocompanion.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
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
    val keys: List<String> = listOf("flag", "star", "bookmark", "label", "circle", "bolt", "fire", "heart", "priority")

    fun vector(key: String?): ImageVector = when (key) {
        "star" -> Icons.Filled.Star
        "bookmark" -> Icons.Filled.Bookmark
        "label" -> Icons.Filled.Label
        "circle" -> Icons.Filled.Circle
        "bolt" -> Icons.Filled.Bolt
        "fire" -> Icons.Filled.Whatshot
        "heart" -> Icons.Filled.Favorite
        "priority" -> Icons.Filled.PriorityHigh
        "flag" -> Icons.Filled.Flag
        // Default marker is a bookmark, kept visually distinct from PRIORITY (the coloured flag).
        else -> Icons.Filled.Bookmark
    }
}
