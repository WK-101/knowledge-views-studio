package com.todocompanion.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * U3 — the app's spacing scale, named once. Before this, `ui/screens` carried ~4,900 raw `.dp` literals
 * with no single source (6dp×686, 8dp×665, 4dp×503, 12dp×455, 16dp×227 were clearly an unnamed scale), so
 * "widen the gutter" or "tighten the rhythm" was a 400-site find-and-replace. New UI should reach for these
 * tokens; existing intentional code is left as-is (the UI-coherence ratchet stops NEW raw-dp drift).
 *
 * Kept as a plain object of `Dp` constants (not a CompositionLocal) so it's usable from any composable with
 * no provider and zero overhead — `Spacing.md`, `padding(Spacing.gutter)`.
 */
object Spacing {
    /** 2dp — hairline gaps, the 2px surface gap between adjacent fills. */
    val xxs = 2.dp
    /** 4dp — tight inner padding, icon-to-label gaps. */
    val xs = 4.dp
    /** 8dp — the default small gap between chips/rows. */
    val sm = 8.dp
    /** 12dp — comfortable gap inside cards, between list items. */
    val md = 12.dp
    /** 16dp — the standard screen side gutter and section spacing. */
    val lg = 16.dp
    /** 24dp — spacing between major sections. */
    val xl = 24.dp
    /** 32dp — generous top/bottom breathing room. */
    val xxl = 32.dp

    /** The de-facto screen side gutter (was `padding(horizontal = 16.dp)` in 26+ places). */
    val gutter = 16.dp
}
