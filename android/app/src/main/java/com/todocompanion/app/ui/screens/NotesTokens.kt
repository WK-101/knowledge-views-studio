package com.todocompanion.app.ui.screens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The Notes module's shared shape scale. The UI had grown four near-identical corner radii (8/9/10/12dp)
 * scattered across pills, chips, steppers and tiles — visually inconsistent and impossible to adjust in
 * one place. Everything now snaps to two roles: [Pill] for small inline controls (chips, meta pills,
 * steppers, the toolbar pill) and [Card] for cards and larger tappable tiles.
 */
object NotesTokens {
    /** Small inline controls — tags, meta pills, the toolbar pill, steppers, cover-emoji chip. */
    val Pill = RoundedCornerShape(8.dp)
    /** Cards and larger tappable tiles (note cards, the properties-sheet action tiles). */
    val Card = RoundedCornerShape(12.dp)
}
