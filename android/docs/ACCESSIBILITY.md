# Kairo — Accessibility

Kairo targets WCAG 2.1 AA for the properties that can be verified without a device, and is built so a
screen-reader user can operate every interactive control. This documents the coverage, the contrast
audit, and what still needs a physical device to certify.

## 1. Screen-reader labelling

Every actionable control carries an explicit semantic label, role and (where stateful) state, so TalkBack
announces something meaningful rather than "button" or an icon name:

- App-bar actions, FABs, task rows, and dialog buttons — labelled (icon content descriptions passed
  positionally on the `Icon`/`IconButton`).
- **Task/subtask checkbox** — role `Checkbox`, with a state-aware label ("Completed. Double-tap to mark
  incomplete." / "Mark complete.").
- **Habit check-ring** — announces the habit name, date and done/not-done state; the click action is
  labelled "Mark done" / "Mark not done".
- **Habit-matrix day cells** — announce habit name + date + state, role `Button`.
- **Colour swatches / colour-picker** — each swatch announces "Colour, selected"/"Colour" with role
  `RadioButton`; the picker entry announces "Pick a colour", role `Button`.
- **Flag / star** toggles — labelled.

Dynamic type is respected — text scales with the system font-size setting; layouts use scalable units and
wrap rather than truncate at large scales.

### Regression protection (CI, no device)

A Compose-UI test layer runs on the JVM under Robolectric (`createComposeRule`) and asserts the semantics
tree — the checkbox role/state/label, the flag & star labels, the colour-picker button, the empty-state
and tip-banner text. Stripping any one of these labels fails the build. See
`app/src/testDebug/.../AccessibilitySemanticsTest.kt` and `ComponentSemanticsTest.kt`.

## 2. Colour-contrast audit (WCAG 2.1 AA)

Ratios below are computed from relative luminance per WCAG 2.1. AA requires **4.5:1** for body text and
**3.0:1** for large text and the visual boundaries of UI components. The tokens mirror `ui/theme/Theme.kt`:
custom surface/background colours paired with the Material 3 default on-colours, plus the brand accent.

**Every functional text and UI pair clears AA in all three themes.** Representative worst cases:

| Pair | Light | Dark | AMOLED | AA need |
| --- | --- | --- | --- | --- |
| onSurface / surface (body text) | 17.13:1 | 13.19:1 | 15.24:1 | 4.5 |
| onSurfaceVariant / surfaceVariant (secondary text) | 8.05:1 | 8.98:1 | 10.41:1 | 4.5 |
| outline / surfaceVariant (component border) | 3.92:1 | 4.83:1 | 5.60:1 | 3.0 |
| brand accent text / surface | 5.51:1 | 5.65:1 | 6.53:1 | 4.5 |
| button label / primary fill | 5.51:1 | — | — | 4.5 |

The **lowest functional ratio anywhere** is `outline / surfaceVariant` in light mode at **3.92:1**, still
above the 3.0 UI threshold.

### The one sub-threshold token — and why it's compliant

`outlineVariant` (Material 3 default `#CAC4D0` light / `#49454F` dark) sits at 1.47–2.25:1 against the
surfaces. It is used **only for decorative hairline dividers and non-essential borders**, which
WCAG 1.4.11 explicitly exempts from the 3:1 requirement (the contrast rule applies to boundaries needed to
identify a control, not to purely decorative separators). No functional control relies on it. The audit
therefore records the palette as AA-compliant with no token change required.

### Regression protection (CI, no device)

`app/src/test/.../ContrastAuditTest.kt` recomputes these ratios on every build and fails if any functional
pair drops below its AA threshold. `outlineVariant` is pinned to its known decorative default so that
repurposing it as a functional boundary would break the test and force a re-check.

## 3. What still needs a device

- A **full manual TalkBack pass** — reading order, focus traversal, and gesture navigation across the live
  screens can only be certified on real hardware; the JVM semantics tests verify labels exist and are
  correct but not the on-device reading experience.
- **Switch Access / large-display** ergonomics.

These are the residual items on the Accessibility dimension; the labelling and contrast are complete and
CI-guarded.

_Last reviewed: R106._
