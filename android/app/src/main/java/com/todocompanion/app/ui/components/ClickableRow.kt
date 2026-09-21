package com.todocompanion.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * Re-audit #18 — one clickable modifier for list/card ROWS that behave as buttons (tap opens or navigates).
 *
 * A bare `Modifier.clickable { }` gives TalkBack a focusable with no role, so it is announced without the
 * "button" affordance and, for icon-only or emoji rows, often with no action label at all. Routing row taps
 * through this helper attaches [Role.Button] (and an optional spoken [onClickLabel], e.g. "Open task") with no
 * visual change — it uses the same default ripple indication as `clickable`. Use it for whole-row/whole-card
 * click targets; leave `Switch`/`Checkbox`/toggle rows and links on their own role-carrying controls.
 */
fun Modifier.clickableRow(
    onClickLabel: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = this.clickable(enabled = enabled, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
