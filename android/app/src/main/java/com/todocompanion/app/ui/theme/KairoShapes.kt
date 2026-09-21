package com.todocompanion.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Re-audit #19 — the app's shared semantic shape scale, promoted out of the Notes-scoped `NotesTokens` so
 * the two corner-radius roles are single-sourced for every module rather than living only in Notes. The UI
 * had drifted into four near-identical radii (8/9/10/12dp) across pills, chips, steppers and tiles; these two
 * roles are the canonical answer, complementing `MaterialTheme.shapes` (AppShapes) with the app's own named
 * Pill/Card roles. New UI in any module should reach for these.
 */
object KairoShapes {
    /** Small inline controls — tags, meta pills, toolbar pills, steppers, chips. */
    val Pill = RoundedCornerShape(8.dp)
    /** Cards and larger tappable tiles. */
    val Card = RoundedCornerShape(12.dp)
}
