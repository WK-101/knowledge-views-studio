/** Pure helpers for the "has a dedicated note" gutter indicator (testable, no Obsidian deps). */

import { HIGHLIGHT_COLORS, type HighlightColor } from "../../shared/annotations";

/** Name of the column holding a row's dedicated-note link: a column named "Note" (any type), else a
 *  "link"-typed column. Returns the column NAME or null. */
export function noteLinkColumnName(columns: readonly { name: string; type: string }[]): string | null {
  const byName = columns.find((c) => /^note$/i.test(c.name.trim()));
  if (byName) return byName.name;
  const byType = columns.find((c) => c.type === "link");
  return byType ? byType.name : null;
}

/**
 * The column that holds a row's wikilink back to its dedicated note.
 *
 * An explicit per-view choice wins when it still names a real column; otherwise the auto-detection above.
 * This is what replaces the old *fixed* magic: the "Note"/link-typed guess is now just the default, and a
 * view can name any column instead — the arrangement is declared, not hidden.
 */
export function resolveNoteLinkColumn(
  explicit: string | undefined,
  columns: readonly { name: string; type: string }[],
): string | null {
  const name = (explicit ?? "").trim();
  if (name !== "") {
    const found = columns.find((c) => c.name.trim().toLowerCase() === name.toLowerCase());
    if (found) return found.name;
    // A stale explicit choice (renamed column) falls back to the guess rather than silently linking nothing.
  }
  return noteLinkColumnName(columns);
}

/** Whether a view offers promoted notes at all. Unset means on — promotion is available on every view. */
export function promotedNotesEnabled(profile: { promotedNotes?: boolean }): boolean {
  return profile.promotedNotes !== false;
}

/** The default tint for a promoted row's checkbox — the accent-family colour, subtle but recognisable. */
export const DEFAULT_PROMOTED_FILL: HighlightColor = "purple";

/**
 * The palette colour a promoted row is tinted with, or null for "no fill". Unset falls back to the default;
 * an unknown value is treated as the default rather than dropped, so a corrupt setting still shows something.
 */
export function promotedFillColor(raw: string | undefined): HighlightColor | null {
  if (raw === "none") return null;
  if (raw !== undefined && (HIGHLIGHT_COLORS as readonly string[]).includes(raw)) return raw as HighlightColor;
  return DEFAULT_PROMOTED_FILL;
}

/** The wikilink target inside a value like `[[Note]]` or `[[Note|alias]]`, or null. */
export function wikilinkTarget(value: string): string | null {
  const m = /\[\[([^\]|]+)/.exec(value ?? "");
  return m ? (m[1] ?? "").trim() || null : null;
}

/** Name of the citation-key column (type "citekey", else a column named "Cite key"), or null. */
export function citeKeyColumnName(columns: readonly { name: string; type: string }[]): string | null {
  const byType = columns.find((c) => c.type === "citekey");
  if (byType) return byType.name;
  const byName = columns.find((c) => /^cite ?key$/i.test(c.name.trim()));
  return byName ? byName.name : null;
}
