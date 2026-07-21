import { HIGHLIGHT_COLORS, type HighlightColor } from "../../../shared/annotations";

/**
 * The highlight minimap — a slim rail of colour-coded dots down the page edge, one per highlight, placed at
 * the highlight's proportional position in the document, so a long article becomes a glanceable map of your
 * own attention (Web Highlights and WuCai both build their identity on this; it's the single highest-polish
 * annotation affordance). The DOM lives in the annotator; the arithmetic lives here, pure and testable.
 */

/** Clamp a value into [lo, hi]. */
function clamp(value: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, value));
}

/**
 * Where a highlight's dot sits on the rail, in pixels from the rail's top.
 *
 * The dot's position is the highlight's fraction of the way down the whole document, scaled to the rail's
 * height — so the rail is a scale model of the page. A zero-or-negative document height (nothing to scroll)
 * puts everything at the top rather than dividing by zero.
 */
export function dotTop(docTop: number, docHeight: number, railHeight: number): number {
  if (docHeight <= 0) return 0;
  const fraction = clamp(docTop / docHeight, 0, 1);
  return Math.round(fraction * railHeight);
}

/**
 * The palette colour a number key selects: "1" → the first colour, through "8" → the eighth, in the canonical
 * palette order. Anything else (including "0" or "9") is null — not a colour shortcut.
 */
export function colorForKey(key: string): HighlightColor | null {
  const n = Number(key);
  if (!Number.isInteger(n) || n < 1 || n > HIGHLIGHT_COLORS.length) return null;
  return HIGHLIGHT_COLORS[n - 1] ?? null;
}

/** The 1-based number-key label for a palette colour (for a swatch tooltip), or "" if it isn't in the palette. */
export function keyForColor(color: HighlightColor): string {
  const index = HIGHLIGHT_COLORS.indexOf(color);
  return index >= 0 ? String(index + 1) : "";
}
