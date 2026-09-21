package com.todocompanion.app.ui.screens

import com.todocompanion.app.ui.theme.KairoShapes

/**
 * The Notes module's shape roles. #19 — these two roles were promoted app-wide into
 * [com.todocompanion.app.ui.theme.KairoShapes]; this object is now a thin Notes-local alias so the many
 * existing Notes call sites (`NotesTokens.Pill` / `.Card`) keep working while the radii are single-sourced.
 */
object NotesTokens {
    /** Small inline controls — tags, meta pills, the toolbar pill, steppers, cover-emoji chip. */
    val Pill = KairoShapes.Pill
    /** Cards and larger tappable tiles (note cards, the properties-sheet action tiles). */
    val Card = KairoShapes.Card
}
