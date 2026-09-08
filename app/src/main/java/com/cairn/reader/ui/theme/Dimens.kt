package com.cairn.reader.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The app's spacing / sizing scale. Compose has no built-in spacing tokens, so before this every
 * gap and pad was a raw `.dp` literal (≈1,100 of them). New and refactored UI should reach for
 * these names instead of magic numbers so the rhythm stays consistent; the type scale already
 * lives in [CairnTypography] and radii in [CairnShapes], and this is the third leg of that stool.
 *
 * The steps map to the values that already dominate the codebase (8/12/16/24 lead), so adopting
 * them is a rename, not a redesign.
 */
object Dimens {
    /** 1.dp — hairline rules. */
    val hairline = 1.dp
    /** 2.dp. */
    val xxs = 2.dp
    /** 4.dp — tight inner gaps. */
    val xs = 4.dp
    /** 8.dp — the default small gap. */
    val sm = 8.dp
    /** 12.dp. */
    val md = 12.dp
    /** 16.dp — the standard content padding / screen gutter. */
    val lg = 16.dp
    /** 24.dp — section spacing. */
    val xl = 24.dp
    /** 32.dp — large empty-state / hero padding. */
    val xxl = 32.dp

    /** Standard horizontal screen gutter (== [lg]). */
    val gutter = 16.dp

    /** Inline icon in a row / sheet action. */
    val iconInline = 22.dp
    /** Nav / drawer icon. */
    val iconNav = 28.dp
    /** Empty-state illustration icon. */
    val iconEmpty = 40.dp

    /** Thin inset divider between list entries. */
    val entryDivider = 0.6.dp
}
